import test from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { readFileSync } from 'node:fs';
import { generateKeyPairSync } from 'node:crypto';
import { scheduled, handleRequest } from '../src/index.mjs';
import { HOUR } from '../src/market.mjs';

// Execute the actual migration and production SQL against SQLite, using D1's result shape.
function database() {
  const db = new DatabaseSync(':memory:');
  db.exec(readFileSync(new URL('../migrations/0001_initial.sql', import.meta.url), 'utf8'));
  db.exec(readFileSync(new URL('../migrations/0002_google_premium_price.sql', import.meta.url), 'utf8'));
  return {
    prepare(sql) {
      const statement = db.prepare(sql);
      let values = [];
      return {
        bind(...args) { values = args; return this; },
        async first() { return statement.get(...values) || null; },
        async all() { return { results: statement.all(...values) }; },
        async run() { return statement.run(...values); }
      };
    },
    async batch(statements) {
      db.exec('BEGIN');
      try { const result = []; for (const statement of statements) result.push(await statement.run()); db.exec('COMMIT'); return result; }
      catch (error) { db.exec('ROLLBACK'); throw error; }
    },
    close() { db.close(); }
  };
}

test('enrollment, closed-hour transitions, FCM delivery, dedup and rule reset', async () => {
  const DB = database();
  const originalNow = Date.now, originalFetch = globalThis.fetch;
  let now = 1000 * HOUR + 300000;
  const sent = [];
  let extreme = false;
  let price = 100000;
  const { privateKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
  const env = { DB, FIREBASE_PROJECT_ID: 'test', FCM_SERVICE_ACCOUNT: JSON.stringify({ client_email: 'test@example.com', private_key: privateKey.export({ type: 'pkcs8', format: 'pem' }) }) };
  const verify = async()=>({uid:'owner',email:'owner@example.com'});
  Date.now = () => now;
  globalThis.fetch = async (url, options) => {
    if (url.includes('hyperliquid')) {
      if(JSON.parse(options.body).type==='allMids')return Response.json({BTC:String(price)});
      const start = Math.floor(now / HOUR) * HOUR - 300 * HOUR;
      return Response.json(Array.from({ length: 301 }, (_, i) => {
        const close = extreme && i >= 299 ? 100000 : 1000 - i;
        return { t: start + i * HOUR, o: close, h: close + 1, l: close - 1, c: close };
      }));
    }
    if (url.includes('oauth2')) return Response.json({ access_token: 'test-access', expires_in: 3600 });
    if (url.includes('fcm.googleapis')) { sent.push(JSON.parse(options.body)); return Response.json({ name: 'test-message' }); }
    throw new Error('Unexpected URL');
  };
  const enroll = (extra = {}) => handleRequest(new Request('https://example/device', { method: 'PUT', body: JSON.stringify({ id: 'device-test-123456', token: 'test-device-token-123456789', enabled: true, period: 14, low: 30, high: 70, priceTarget:null, ...extra }) }), env,verify);
  try {
    assert.equal((await enroll()).status, 200);
    await scheduled(env);
    assert.equal(sent.length, 0, 'Initial oversold baseline must not alert');
    assert.equal((await DB.prepare('SELECT zone FROM devices').first()).zone, 'oversold');
    now += HOUR; extreme = true;
    await scheduled(env);
    assert.equal(sent.length, 1);
    assert.equal(sent[0].message.data.zone, 'overbought');
    assert.equal((await DB.prepare('SELECT status FROM alerts').first()).status, 'sent');
    await scheduled(env);
    assert.equal(sent.length, 1, 'Same closed candle must not duplicate');
    now += HOUR;
    await scheduled(env);
    assert.equal(sent.length, 1, 'Staying in extreme zone must not repeat');
    await enroll({ high: 80 });
    assert.equal((await DB.prepare('SELECT last_close FROM devices').first()).last_close, null);
    await scheduled(env);
    assert.equal(sent.length, 1, 'Rule change resets baseline without historical alerts');
    now += 3 * HOUR; extreme = false;
    await scheduled(env);
    assert.equal(sent.length, 1, 'Outage recovery must not replay an old transition');
    await enroll({ enabled: false });
    assert.equal((await DB.prepare('SELECT enabled FROM devices').first()).enabled, 0);
    await enroll({enabled:false,priceTarget:110000,priceRuleId:'price-rule-12345678'});
    await scheduled(env);
    assert.equal(sent.length,1,'Price baseline must not alert');
    now+=60000; price=115000;
    await scheduled(env);
    assert.equal(sent.length,2,'Crossing target must notify even with RSI disabled');
    assert.equal(sent[1].message.data.zone,'price');
    assert.equal(sent[1].message.data.ownerUid,'owner');
    await enroll({enabled:false,priceTarget:110000,priceRuleId:'price-rule-12345678'});
    now+=60000; price=105000;
    await scheduled(env);
    assert.equal(sent.length,2,'Same fired rule must not rearm on token/settings sync');
    await enroll({enabled:false,priceTarget:110000,priceRuleId:'price-rule-87654321'});
    await scheduled(env);
    now+=60000; price=111000;
    await scheduled(env);
    assert.equal(sent.length,3,'New rule explicitly rearms target');
    const stranger=async()=>({uid:'other',email:'other@example.com'});
    const denied=await handleRequest(new Request('https://example/test',{method:'POST',body:JSON.stringify({id:'device-test-123456'})}),env,stranger);
    assert.equal(denied.status,404,'Signed-in users cannot target another owner’s device');
    const freeUser=async()=>({uid:'free-user',email:'new-user@example.com'});
    const free=await handleRequest(new Request('https://example/device',{method:'PUT',body:JSON.stringify({id:'second-device-123456',token:'second-device-token-123456',enabled:true,period:14,low:30,high:70,priceTarget:110000,priceRuleId:'second-price-rule-12345'})}),env,freeUser);
    assert.equal(free.status,200,'A new Google account can enroll without an invitation or payment');
    await scheduled(env);
    assert.equal(sent.length,3,'New account establishes baselines without historical alerts');
    now+=60000; price=109000;
    await scheduled(env);
    assert.equal(sent.length,4,'Price notifications must also be delivered to the new account');
    assert.equal(sent[3].message.data.ownerUid,'free-user');
    assert.equal(sent[3].message.token,'second-device-token-123456');
    now+=HOUR; extreme=true;
    await scheduled(env);
    assert.equal(sent.length,5,'RSI transitions must also be delivered to the new account');
    assert.equal(sent[4].message.data.ownerUid,'free-user');
    assert.equal(sent[4].message.data.zone,'overbought');
    const hidden=await handleRequest(new Request('https://example/device?id=device-test-123456'),env,freeUser);
    assert.deepEqual(await hidden.json(),{},'New users cannot read another account’s settings');
    const overwrite=await handleRequest(new Request('https://example/device',{method:'PUT',body:JSON.stringify({id:'device-test-123456',token:'another-device-token-123456',enabled:true,period:14,low:30,high:70,priceTarget:null})}),env,stranger);
    assert.equal(overwrite.status,409,'Device ownership cannot be overwritten');
    await handleRequest(new Request('https://example/device',{method:'DELETE',body:JSON.stringify({id:'device-test-123456'})}),env,stranger);
    assert.ok(await DB.prepare('SELECT id FROM devices').first(),'Another user cannot delete the device');
    await handleRequest(new Request('https://example/device',{method:'DELETE',body:JSON.stringify({id:'device-test-123456'})}),env,verify);
    assert.equal(await DB.prepare("SELECT id FROM devices WHERE id='device-test-123456'").first(),null,'Sign-out can unregister the owned device');
    assert.ok(await DB.prepare("SELECT id FROM devices WHERE id='second-device-123456'").first(),'Sign-out must preserve other users’ devices');
  } finally { globalThis.fetch = originalFetch; Date.now = originalNow; DB.close(); }
});
