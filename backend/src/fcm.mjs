let cached;
const encoder = new TextEncoder();
function base64url(bytes) { return btoa(String.fromCharCode(...bytes)).replace(/=/g, '').replace(/\+/g, '-').replace(/\//g, '_'); }
function encoded(value) { return base64url(encoder.encode(JSON.stringify(value))); }

async function accessToken(env) {
  if (cached && cached.expires > Date.now() + 60000) return cached.token;
  const service = JSON.parse(env.FCM_SERVICE_ACCOUNT);
  const seconds = Math.floor(Date.now() / 1000);
  const unsigned = `${encoded({ alg: 'RS256', typ: 'JWT' })}.${encoded({ iss: service.client_email, scope: 'https://www.googleapis.com/auth/firebase.messaging', aud: 'https://oauth2.googleapis.com/token', iat: seconds, exp: seconds + 3600 })}`;
  const pem = service.private_key.replace(/-----[^-]+-----/g, '').replace(/\s/g, '');
  const key = await crypto.subtle.importKey('pkcs8', Uint8Array.from(atob(pem), c => c.charCodeAt(0)), { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, ['sign']);
  const signature = await crypto.subtle.sign('RSASSA-PKCS1-v1_5', key, encoder.encode(unsigned));
  const response = await fetch('https://oauth2.googleapis.com/token', {
    method: 'POST', body: new URLSearchParams({ grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer', assertion: `${unsigned}.${base64url(new Uint8Array(signature))}` }), signal: AbortSignal.timeout(15000)
  });
  if (!response.ok) throw new Error(`OAuth HTTP ${response.status}`);
  const result = await response.json();
  cached = { token: result.access_token, expires: Date.now() + result.expires_in * 1000 };
  return cached.token;
}

export async function sendAlert(env, token, alert) {
  const response = await fetch(`https://fcm.googleapis.com/v1/projects/${env.FIREBASE_PROJECT_ID}/messages:send`, {
    method: 'POST', headers: { Authorization: `Bearer ${await accessToken(env)}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ message: { token, android: { priority: 'high', ttl: '3600s' }, data: {
      alertId: alert.id, ownerUid: alert.owner_uid, priceRuleId: alert.price_rule_id || '', zone: alert.zone, rsi: String(alert.rsi), price: String(alert.price), closeTime: String(alert.close_time)
    } } }), signal: AbortSignal.timeout(15000)
  });
  if (!response.ok) {
    const result = await response.json().catch(() => ({}));
    const unregistered = result.error?.details?.some(d => d.errorCode === 'UNREGISTERED');
    const error = new Error(`FCM HTTP ${response.status}`);
    error.unregistered = Boolean(unregistered);
    throw error;
  }
}
