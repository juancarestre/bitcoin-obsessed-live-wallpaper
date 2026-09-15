import { loadCandles, loadPrice, crossesPrice, rsi, zone, HOUR } from './market.mjs';
import { sendAlert } from './fcm.mjs';
import { authenticate } from './auth.mjs';

const json = (value, status = 200) => Response.json(value, { status, headers: { 'Cache-Control': 'no-store' } });
const validId = value => typeof value === 'string' && /^[a-zA-Z0-9-]{16,80}$/.test(value);
async function body(request) {
  if (Number(request.headers.get('Content-Length')) > 8192) return null;
  const text = await request.text();
  if (text.length > 8192) return null;
  try { const value = JSON.parse(text); return value && typeof value === 'object' && !Array.isArray(value) ? value : null; } catch { return null; }
}

// Auth is injectable only by direct module callers (tests); deployed fetch always verifies Google's signature.
export async function handleRequest(request, env, verify = authenticate) {
  const path = new URL(request.url).pathname;
  if (path === '/health' && request.method === 'GET') return json({ service: 'bitcoin-obsessed', version: 3 });
  const user = await verify(request, env);
  if (!user) return json({ error: 'Sign in required' }, 401);
  if (path === '/account' && request.method === 'GET') return json({ uid: user.uid, email: user.email, alertsEnabled: true });
  if (path === '/device' && request.method === 'DELETE') {
    const d = await body(request);
    if (!validId(d?.id)) return json({ error: 'Invalid device' }, 400);
    await env.DB.prepare('DELETE FROM devices WHERE id=? AND owner_uid=?').bind(d.id,user.uid).run();
    return json({ ok:true });
  }
  if (path === '/status' && request.method === 'GET') return json(await env.DB.prepare('SELECT checked_at,candle_time,error FROM health WHERE id=1').first() || {});
  if (path === '/device' && request.method === 'GET') {
    const id = new URL(request.url).searchParams.get('id');
    if (!validId(id)) return json({ error:'Invalid device' },400);
    return json(await env.DB.prepare('SELECT enabled,period,low,high,price_target,price_rule_id,price_fired FROM devices WHERE id=? AND owner_uid=?').bind(id,user.uid).first() || {});
  }
  if (path === '/test' && request.method === 'POST') {
    const d = await body(request);
    if (!validId(d?.id)) return json({ error:'Invalid device' },400);
    const device = await env.DB.prepare('SELECT token FROM devices WHERE id=? AND owner_uid=?').bind(d.id,user.uid).first();
    if (!device) return json({ error:'Connect your alerts first' },404);
    const now=Date.now(), alertId=`test:${d.id}:${now}`;
    await sendAlert(env,device.token,{id:alertId,owner_uid:user.uid,zone:'test',rsi:50,price:0,close_time:now});
    return json({ok:true,alertId});
  }
  if (path !== '/device' || request.method !== 'PUT') return json({ error:'Not found' },404);
  const d = await body(request);
  if (!validId(d?.id) || typeof d.token !== 'string' || d.token.length < 20 || d.token.length > 4096 || typeof d.enabled !== 'boolean' || !Number.isInteger(d.period) || d.period < 2 || d.period > 50 || !Number.isFinite(d.low) || !Number.isFinite(d.high) || d.low <= 0 || d.high >= 100 || d.low >= d.high || !(d.priceTarget === null || (Number.isFinite(d.priceTarget) && d.priceTarget > 0 && d.priceTarget <= 1e9 && validId(d.priceRuleId)))) return json({error:'Invalid alert settings'},400);
  const existing = await env.DB.prepare('SELECT owner_uid FROM devices WHERE id=?').bind(d.id).first();
  if (existing && existing.owner_uid !== user.uid) return json({error:'Device belongs to another session'},409);
  const ruleId = d.priceTarget === null ? null : d.priceRuleId;
  await env.DB.batch([
    env.DB.prepare(`INSERT INTO devices(id,owner_uid,owner_email,token,enabled,period,low,high,price_target,price_rule_id,updated_at)
      VALUES(?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET token=excluded.token,owner_email=excluded.owner_email,
      last_close=CASE WHEN devices.period!=excluded.period OR devices.low!=excluded.low OR devices.high!=excluded.high OR devices.enabled!=excluded.enabled THEN NULL ELSE devices.last_close END,
      zone=CASE WHEN devices.period!=excluded.period OR devices.low!=excluded.low OR devices.high!=excluded.high OR devices.enabled!=excluded.enabled THEN NULL ELSE devices.zone END,
      price_fired=CASE WHEN devices.price_rule_id IS NOT excluded.price_rule_id OR devices.price_target IS NOT excluded.price_target THEN 0 ELSE devices.price_fired END,
      previous_price=CASE WHEN devices.price_rule_id IS NOT excluded.price_rule_id OR devices.price_target IS NOT excluded.price_target THEN NULL ELSE devices.previous_price END,
      price_checked_at=CASE WHEN devices.price_rule_id IS NOT excluded.price_rule_id OR devices.price_target IS NOT excluded.price_target THEN NULL ELSE devices.price_checked_at END,
      enabled=excluded.enabled,period=excluded.period,low=excluded.low,high=excluded.high,price_target=excluded.price_target,price_rule_id=excluded.price_rule_id,updated_at=excluded.updated_at
      WHERE devices.owner_uid=excluded.owner_uid`).bind(d.id,user.uid,user.email,d.token,+d.enabled,d.period,d.low,d.high,d.priceTarget,ruleId,Date.now()),
    env.DB.prepare(`UPDATE alerts SET status='cancelled' WHERE device_id=? AND status='pending' AND EXISTS(SELECT 1 FROM devices d WHERE d.id=? AND (
      (alerts.zone!='price' AND (d.enabled=0 OR d.last_close IS NULL)) OR (alerts.zone='price' AND (d.price_target IS NULL OR d.price_rule_id IS NOT alerts.price_rule_id))))`).bind(d.id,d.id)
  ]);
  return json({ok:true});
}

async function processRsi(env,devices,now) {
  const expectedClose = Math.floor(now/HOUR)*HOUR;
  const active = devices.filter(d=>d.enabled && (!d.last_close || d.last_close<expectedClose));
  if (!active.length) return null;
  const candles=await loadCandles(now), closeTime=candles.at(-1).t+HOUR, closes=candles.map(c=>c.c);
  for(const d of active) {
    const value=rsi(closes,d.period), current=zone(value,d.low,d.high), statements=[];
    if(d.zone && d.last_close===closeTime-HOUR && current!=='neutral' && d.zone!==current) statements.push(
      env.DB.prepare(`INSERT OR IGNORE INTO alerts(id,device_id,close_time,zone,rsi,price,created_at)
      SELECT ?,?,?,?,?,?,? FROM devices WHERE id=? AND enabled=1 AND last_close=? AND updated_at=?`).bind(`${d.id}:${closeTime}:${current}`,d.id,closeTime,current,value,closes.at(-1),now,d.id,d.last_close,d.updated_at));
    statements.push(env.DB.prepare('UPDATE devices SET last_close=?,zone=? WHERE id=? AND updated_at=? AND (last_close IS NULL OR last_close<?)').bind(closeTime,current,d.id,d.updated_at,closeTime));
    await env.DB.batch(statements);
  }
  return closeTime;
}

async function processPrices(env,devices,now) {
  const active=devices.filter(d=>d.price_target!==null && !d.price_fired);
  if(!active.length)return;
  const price=await loadPrice();
  for(const d of active) {
    const hit = d.price_checked_at && now-d.price_checked_at<=5*60000 && crossesPrice(d.previous_price,price,d.price_target);
    const statements=[];
    if(hit)statements.push(env.DB.prepare(`INSERT OR IGNORE INTO alerts(id,device_id,close_time,zone,rsi,price,created_at,price_rule_id)
      SELECT ?,?,?,'price',0,?,?,? FROM devices WHERE id=? AND price_rule_id=? AND price_fired=0 AND updated_at=?`).bind(`${d.id}:price:${d.price_rule_id}`,d.id,now,price,now,d.price_rule_id,d.id,d.price_rule_id,d.updated_at));
    statements.push(env.DB.prepare('UPDATE devices SET previous_price=?,price_checked_at=?,price_fired=? WHERE id=? AND updated_at=? AND price_fired=0 AND (price_checked_at IS NULL OR price_checked_at<?)').bind(price,now,hit?1:0,d.id,d.updated_at,now));
    await env.DB.batch(statements);
  }
}

export async function scheduled(env) {
  const now=Date.now();
  // Only Google-linked devices participate; legacy code-only registrations remain disabled.
  const {results:devices}=await env.DB.prepare('SELECT * FROM devices WHERE owner_uid IS NOT NULL AND (enabled=1 OR price_target IS NOT NULL)').all();
  if(!devices.length)return;
  const errors=[];
  let closed=null;
  try { closed=await processRsi(env,devices,now); } catch(e) { errors.push(e.message); }
  try { await processPrices(env,devices,now); } catch(e) { errors.push(e.message); }
  await env.DB.prepare('INSERT INTO health(id,checked_at,candle_time,error) VALUES(1,?,?,?) ON CONFLICT(id) DO UPDATE SET checked_at=excluded.checked_at,candle_time=COALESCE(excluded.candle_time,health.candle_time),error=excluded.error').bind(now,closed,errors.length?errors.join('; ').slice(0,200):null).run();
  await env.DB.prepare("UPDATE alerts SET status='expired' WHERE status='pending' AND close_time<?").bind(now-HOUR).run();
  const {results:pending}=await env.DB.prepare(`SELECT a.*,d.token,d.owner_uid FROM alerts a JOIN devices d ON d.id=a.device_id
    WHERE a.status='pending' AND a.lease_until<? AND d.owner_uid IS NOT NULL
    AND ((a.zone='price' AND d.price_target IS NOT NULL AND a.price_rule_id=d.price_rule_id) OR (a.zone!='price' AND d.enabled=1)) ORDER BY a.created_at,a.id LIMIT 20`).bind(now).all();
  for(const alert of pending) {
    const claim=await env.DB.prepare("UPDATE alerts SET lease_until=?,attempts=attempts+1 WHERE id=? AND status='pending' AND lease_until<? RETURNING id").bind(now+120000,alert.id,now).first();
    if(!claim)continue;
    try {
      await sendAlert(env,alert.token,alert);
      await env.DB.prepare("UPDATE alerts SET status='sent' WHERE id=?").bind(alert.id).run();
    } catch(e) {
      console.error('Push delivery failed:',e.message);
      if(e.unregistered)await env.DB.batch([
        env.DB.prepare('UPDATE devices SET enabled=0,price_target=NULL WHERE id=? AND token=?').bind(alert.device_id,alert.token),
        env.DB.prepare("UPDATE alerts SET status='invalid_token' WHERE id=?").bind(alert.id)
      ]);
    }
  }
  await env.DB.prepare("DELETE FROM alerts WHERE status!='pending' AND created_at<?").bind(now-30*24*HOUR).run();
}

export default {
  async fetch(request,env) { try {return await handleRequest(request,env);} catch(e) {console.error('API failure:',e.message); return json({error:'Service unavailable'},503);} },
  async scheduled(controller,env) {await scheduled(env);}
};
