export const HOUR = 3_600_000;

// Wilder's smoothing. A flat series is neutral (50), an all-gain series is 100.
export function rsi(closes, period = 14) {
  if (!Number.isInteger(period) || period < 2 || closes.length < period + 1 || closes.some(v => !Number.isFinite(v) || v <= 0)) {
    throw new Error('Invalid RSI history');
  }
  let gain = 0, loss = 0;
  for (let i = 1; i <= period; i++) {
    const d = closes[i] - closes[i - 1];
    gain += Math.max(d, 0) / period;
    loss += Math.max(-d, 0) / period;
  }
  for (let i = period + 1; i < closes.length; i++) {
    const d = closes[i] - closes[i - 1];
    gain = (gain * (period - 1) + Math.max(d, 0)) / period;
    loss = (loss * (period - 1) + Math.max(-d, 0)) / period;
  }
  return loss === 0 ? (gain === 0 ? 50 : 100) : 100 - 100 / (1 + gain / loss);
}

export function zone(value, low, high) { return value <= low ? 'oversold' : value >= high ? 'overbought' : 'neutral'; }

export function crossesPrice(previous, current, target) {
  return Number.isFinite(previous) && ((previous < target && current >= target) || (previous > target && current <= target) || current === target);
}

export async function loadPrice() {
  const response = await fetch('https://api.hyperliquid.xyz/info', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({type:'allMids'}), signal: AbortSignal.timeout(15000)
  });
  if (!response.ok) throw new Error(`Price HTTP ${response.status}`);
  const value = Number((await response.json()).BTC);
  if (!Number.isFinite(value) || value <= 0) throw new Error('Invalid price');
  return value;
}

export function closedCandles(raw, now = Date.now()) {
  if (!Array.isArray(raw)) throw new Error('Invalid candle response');
  const candles = raw.filter(c => Number(c.t) + HOUR <= now).map(c => ({
    t: Number(c.t), o: Number(c.o), h: Number(c.h), l: Number(c.l), c: Number(c.c)
  })).sort((a, b) => a.t - b.t);
  if (candles.length < 200) throw new Error('Insufficient candle history');
  for (let i = 0; i < candles.length; i++) {
    const c = candles[i];
    if (![c.t, c.o, c.h, c.l, c.c].every(Number.isFinite) || c.l <= 0 || c.h < Math.max(c.o, c.c) || c.l > Math.min(c.o, c.c) || c.t % HOUR !== 0) throw new Error('Invalid OHLC');
    if (i && c.t !== candles[i - 1].t + HOUR) throw new Error('Candle gap or duplicate');
  }
  if (candles.at(-1).t !== Math.floor(now / HOUR) * HOUR - HOUR) throw new Error('Stale closed candle');
  return candles;
}

export async function loadCandles(now = Date.now()) {
  const response = await fetch('https://api.hyperliquid.xyz/info', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ type: 'candleSnapshot', req: { coin: 'BTC', interval: '1h', startTime: now - 300 * HOUR, endTime: now } }),
    signal: AbortSignal.timeout(15000)
  });
  if (!response.ok) throw new Error(`Market HTTP ${response.status}`);
  return closedCandles(await response.json(), now);
}
