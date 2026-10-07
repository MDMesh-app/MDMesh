import { chromium } from 'playwright';
import { buildFixtures } from './fixtures.mjs';
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
const root=fileURLToPath(new URL('../../',import.meta.url));
const fx=buildFixtures(); let saved;
const server=http.createServer((req,res)=>{
 let file=path.join(root,'web/dist',new URL(req.url,'http://localhost').pathname);
 if (!fs.existsSync(file)||fs.statSync(file).isDirectory()) file=path.join(root,'web/dist/index.html');
 const mime={'.html':'text/html','.js':'text/javascript','.css':'text/css','.svg':'image/svg+xml'};
 res.writeHead(200,{'Content-Type':mime[path.extname(file)]||'application/octet-stream'});fs.createReadStream(file).pipe(res);
});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
let browser;
try {
 browser=await chromium.launch({...(process.env.CHROMIUM_PATH ? {executablePath:process.env.CHROMIUM_PATH} : {}),headless:true,args:['--no-sandbox']});
 const context=await browser.newContext({viewport:{width:1440,height:1000}});
 await context.addInitScript(user=>{localStorage.setItem('hmdm.admin.user',JSON.stringify(user));localStorage.setItem('mdmesh-theme','dark');},fx.user);
 await context.route('**/rest/**',async route=>{
  const req=route.request();const p=new URL(req.url()).pathname;let data=[];
  if(p.endsWith('/configurations/search')) data=[{id:2,name:'App restrictions laboratory',blockUserAppInstall:null,blockUserAppUninstall:null,blockedAppStores:null}];
  else if(p.includes('/applications/search')) data=fx.applications;
  else if(req.method()==='PUT'&&p.endsWith('/configurations')) {saved=req.postDataJSON();data=saved;}
  await route.fulfill({status:200,contentType:'application/json',body:JSON.stringify({status:'OK',data})});
 });
 const page=await context.newPage();const errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.goto(`http://127.0.0.1:${server.address().port}/configs`);
 await page.getByRole('button',{name:'Edit',exact:true}).click();
 for(const label of ['Block user app installation','Block user app uninstallation']) {
  const field=page.locator('.cfg-field').filter({hasText:label});
  await field.getByRole('button',{name:'On',exact:true}).click();
  await field.getByRole('button',{name:'Off',exact:true}).click();
  await field.getByRole('button',{name:'Auto',exact:true}).click();
  await field.getByRole('button',{name:'On',exact:true}).click();
 }
 const stores=page.locator('.cfg-field').filter({hasText:'App stores to block'});
 await stores.getByRole('textbox').fill('com.acme.store');
 await stores.getByRole('button',{name:'Use defaults'}).click();
 assert.equal(await stores.getByRole('textbox').inputValue(),'');
 const panel=page.locator('section.cfg-panel').filter({has:page.locator('.cfg-sec-h',{hasText:'Restrictions'})});
 if (process.env.SCREENSHOT_PATH) await panel.screenshot({path:process.env.SCREENSHOT_PATH});
 await stores.getByRole('textbox').fill('com.acme.store');
 await page.getByRole('button',{name:'Save',exact:true}).click();
 await page.getByRole('heading',{name:'Configurations',exact:true}).waitFor();
 assert.equal(saved.blockUserAppInstall,true);assert.equal(saved.blockUserAppUninstall,true);assert.equal(saved.blockedAppStores,'com.acme.store');
 assert.deepEqual(errors,[]);
 console.log('UI PASS: On/Off/Auto, default store reset, saved payload, no page errors; screenshot written.');
} finally {await browser?.close();await new Promise(resolve=>server.close(resolve));}
