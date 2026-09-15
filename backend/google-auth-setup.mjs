// Administrative setup through the user's authenticated gcloud CLI. Does not log credentials.
import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';
const configPath=new URL('../app/google-services.json',import.meta.url);
const androidConfig=JSON.parse(readFileSync(configPath,'utf8'));
const project=androidConfig.project_info.project_id;
const app=androidConfig.client.find(c=>c.client_info.android_client_info.package_name==='com.bitcoinobsessed.livewallpaper').client_info.mobilesdk_app_id;
const access=execFileSync('gcloud',['auth','print-access-token'],{encoding:'utf8'}).trim();
async function api(url,method='GET',body) {
  const response=await fetch(url,{method,headers:{Authorization:`Bearer ${access}`,'Content-Type':'application/json','x-goog-user-project':project},body:body===undefined?undefined:JSON.stringify(body)});
  const data=await response.json();
  if(!response.ok) throw new Error(`${method} ${new URL(url).pathname}: ${response.status} ${data.error?.message}`);
  return data;
}
const admin=`https://identitytoolkit.googleapis.com/admin/v2/projects/${project}`;
const firebase=`https://firebase.googleapis.com/v1beta1/projects/${project}/androidApps/${app}`;
if(process.argv.includes('--sha')) {
  const existing=await api(`${firebase}/sha`);
  for(const [certType,shaHash] of [
    ['SHA_1',process.env.ANDROID_SHA1],
    ['SHA_256',process.env.ANDROID_SHA256]
  ]) {
    if(!shaHash)throw new Error('Set ANDROID_SHA1 and ANDROID_SHA256 before registering certificates.');
    const normalized=shaHash.replaceAll(':','').toUpperCase();
    if(!existing.certificates?.some(c=>c.shaHash.replaceAll(':','').toUpperCase()===normalized)) await api(`${firebase}/sha`,'POST',{certType,shaHash:normalized});
  }
  console.log('Android signing fingerprints registered.');
}
if(process.argv.includes('--enable')) {
  await api(`${admin}/defaultSupportedIdpConfigs/google.com?updateMask=enabled`,'PATCH',{enabled:true});
  console.log('Google provider enabled.');
}
if(process.argv.includes('--download')) {
  const config=await api(`${firebase}/config`);
  writeFileSync(configPath,Buffer.from(config.configFileContents,'base64'),{mode:0o600});
  console.log('Updated Android configuration downloaded.');
}
try {
  const config=await api(`${admin}/config`);
  console.log(JSON.stringify({project:config.name,signIn:config.signIn?.allowDuplicateEmails,client:config.client?.apiKey?'configured':undefined}));
  const providers=await api(`${admin}/defaultSupportedIdpConfigs`);
  console.log(JSON.stringify(providers.defaultSupportedIdpConfigs?.map(p=>({name:p.name,enabled:p.enabled,clientId:p.clientId}))||[]));
} catch(error) { console.error(error.message); process.exitCode=1; }
