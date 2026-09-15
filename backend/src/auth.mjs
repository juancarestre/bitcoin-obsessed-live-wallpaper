import { createRemoteJWKSet, jwtVerify } from 'jose';
const keys = createRemoteJWKSet(new URL('https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com'));

export async function authenticate(request, env, keySet = keys) {
  const header = request.headers.get('Authorization') || '';
  if (!header.startsWith('Bearer ') || header.length > 12000) return null;
  try {
    const { payload } = await jwtVerify(header.slice(7), keySet, {
      issuer: `https://securetoken.google.com/${env.FIREBASE_PROJECT_ID}`,
      audience: env.FIREBASE_PROJECT_ID, algorithms: ['RS256'],
      requiredClaims: ['sub','iat','exp','auth_time'], clockTolerance: 10
    });
    if (!payload.sub || payload.sub.length > 128 || payload.email_verified !== true || typeof payload.email !== 'string' || payload.firebase?.sign_in_provider !== 'google.com' || typeof payload.auth_time !== 'number' || payload.auth_time > Date.now()/1000+10 || typeof payload.iat !== 'number' || payload.iat > Date.now()/1000+10) return null;
    const email = payload.email.toLowerCase();
    return { uid: payload.sub, email };
  } catch { return null; }
}
