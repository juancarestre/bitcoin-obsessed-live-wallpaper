// Run locally after gcloud auth login and wrangler login. Never prints secret values.
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { execFileSync } from 'node:child_process';

const service = process.env.FCM_SERVICE_ACCOUNT_EMAIL;
const project = process.env.FIREBASE_PROJECT_ID;
if (!service || !project) throw new Error('Set FCM_SERVICE_ACCOUNT_EMAIL and FIREBASE_PROJECT_ID.');
const directory = mkdtempSync(join(tmpdir(), 'bitcoin-obsessed-'));
const path = join(directory, 'fcm.json');
let uploaded = false;
let keyId;
try {
  execFileSync('gcloud', ['iam', 'service-accounts', 'keys', 'create', path, `--iam-account=${service}`, `--project=${project}`], { stdio: ['ignore', 'ignore', 'inherit'] });
  const credential = readFileSync(path, 'utf8');
  keyId = JSON.parse(credential).private_key_id;
  execFileSync('wrangler', ['secret', 'put', 'FCM_SERVICE_ACCOUNT'], { input: credential, stdio: ['pipe', 'inherit', 'inherit'] });
  uploaded = true;
  console.log('FCM credential uploaded; local temporary key removed. Device authorization uses Google sign-in.');
} catch (error) {
  if (keyId && !uploaded) execFileSync('gcloud', ['iam', 'service-accounts', 'keys', 'delete', keyId, `--iam-account=${service}`, `--project=${project}`, '--quiet'], { stdio: 'inherit' });
  throw error;
} finally { rmSync(directory, { recursive: true, force: true }); }
