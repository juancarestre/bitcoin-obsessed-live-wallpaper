import test from 'node:test';
import assert from 'node:assert/strict';
import { rsi, zone, closedCandles, crossesPrice, HOUR } from '../src/market.mjs';
import { handleRequest } from '../src/index.mjs';

test('Wilder reference series (initial RSI ≈70.464)', () => {
  const closes = [44.34,44.09,44.15,43.61,44.33,44.83,45.10,45.42,45.84,46.08,45.89,46.03,45.61,46.28,46.28];
  assert.ok(Math.abs(rsi(closes) - 70.464135) < .00001);
  assert.ok(Math.abs(rsi([...closes, 46.00]) - 66.249619) < .00001);
});
test('flat, gain, loss, boundary and invalid histories', () => {
  assert.equal(rsi(Array(30).fill(100)), 50);
  assert.equal(rsi(Array.from({length:30}, (_,i) => i+1)), 100);
  assert.equal(rsi(Array.from({length:30}, (_,i) => 100-i)), 0);
  assert.equal(zone(70,30,70), 'overbought');
  assert.equal(zone(30,30,70), 'oversold');
  assert.equal(zone(50,30,70), 'neutral');
  assert.throws(() => rsi([10, NaN]));
});
test('only closed, fresh, contiguous OHLC candles accepted', () => {
  const now = 1000 * HOUR + 10000;
  const candles = Array.from({length:301}, (_,i) => ({t: (700+i)*HOUR,o:100,h:110,l:90,c:105}));
  assert.equal(closedCandles(candles, now).length, 300);
  assert.throws(() => closedCandles(candles.filter((_,i) => i!==50), now), /gap/);
  assert.throws(() => closedCandles(candles.slice(0,-2), now), /Stale/);
});
test('API rejects unauthenticated enrollment before database access', async () => {
  const response = await handleRequest(new Request('https://test/device', {method:'PUT',body:'{}'}), {INSTALL_TOKEN:'test'});
  assert.equal(response.status, 401);
});
test('API rejects malformed settings', async () => {
  const response = await handleRequest(new Request('https://test/device', {method:'PUT',body:'null'}), {},async()=>({uid:'test',email:'test@example.com'}));
  assert.equal(response.status, 400);
});
test('price targets detect crossings both ways and exact touches, not initial samples',()=>{
  assert.equal(crossesPrice(null,100,100),false);
  assert.equal(crossesPrice(90,100,100),true);
  assert.equal(crossesPrice(110,99,100),true);
  assert.equal(crossesPrice(90,110,100),true);
  assert.equal(crossesPrice(90,95,100),false);
});
