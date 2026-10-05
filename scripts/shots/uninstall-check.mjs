import { chromium } from 'playwright';
import { buildFixtures } from './fixtures.mjs';
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
const root=fileURLToPath(new URL('../../',import.meta.url));
// Run after web build and npm install in scripts/shots. All REST calls use fixtures.
const fx=buildFixtures(), queued=[];
const server=http.createServer((req,res)=>{
 let file=path.join(root,'web/dist',new URL(req.url,'http://localhost').pathname);
 if(!fs.existsSync(file)||fs.statSync(file).isDirectory())file=path.join(root,'web/dist/index.html');
 res.writeHead(200,{'Content-Type':({'.html':'text/html','.js':'text/javascript','.css':'text/css','.svg':'image/svg+xml'})[path.extname(file)]||'application/octet-stream'});
 fs.createReadStream(file).pipe(res);
});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
let browser;
try{
 browser=await chromium.launch({...(process.env.CHROMIUM_PATH ? {executablePath:process.env.CHROMIUM_PATH} : {}),headless:true,args:['--no-sandbox']});
 const context=await browser.newContext();
 await context.addInitScript(user=>localStorage.setItem('hmdm.admin.user',JSON.stringify(user)),fx.user);
 await context.route('**/rest/**',async route=>{
  const req=route.request(),p=new URL(req.url()).pathname;let data=null;
  if(p.endsWith('/devices/search'))data={devices:{items:[fx.devices[0]],totalItemsCount:1},configurations:fx.configurations};
  else if(p.endsWith('/state'))data=fx.state;
  else if(p.endsWith('/commands')){
   if(req.method()==='POST'){assert.equal(p,'/rest/private/agent/v1/devices/WH-SCAN-07/commands');queued.push(req.postDataJSON());data={id:801,type:'app.uninstall',status:'pending',subject:'com.example.lab'};}else data=[];
  }
  await route.fulfill({status:200,contentType:'application/json',body:JSON.stringify({status:'OK',data})});
 });
 const page=await context.newPage(),errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.goto(`http://127.0.0.1:${server.address().port}/devices/WH-SCAN-07`);
 await page.getByRole('button',{name:'Uninstall app',exact:true}).click();
 let modal=page.getByRole('dialog');
 await modal.getByRole('button',{name:'Confirm',exact:true}).waitFor();
 assert.equal(await modal.getByRole('button',{name:'Confirm',exact:true}).isDisabled(),true);
 await modal.getByRole('textbox',{name:'Package name'}).fill('com.mdmesh.agent.debug');
 assert.equal(await modal.getByRole('button',{name:'Confirm',exact:true}).isDisabled(),true);
 await modal.getByRole('textbox',{name:'Package name'}).fill('com.mdmesh.agent');
 assert.equal(await modal.getByRole('button',{name:'Confirm',exact:true}).isDisabled(),true);
 await modal.getByRole('textbox',{name:'Package name'}).fill('invalid package');
 assert.equal(await modal.getByRole('button',{name:'Confirm',exact:true}).isDisabled(),true);
 await modal.getByRole('textbox',{name:'Package name'}).fill('com.example.lab');
 if(process.env.SCREENSHOT_PATH) await page.screenshot({path:process.env.SCREENSHOT_PATH});
 await modal.getByRole('button',{name:'Cancel',exact:true}).click();
 assert.equal(queued.length,0);
 await page.getByRole('button',{name:'Uninstall app',exact:true}).click();
 modal=page.getByRole('dialog');
 await modal.getByRole('textbox',{name:'Package name'}).fill('  com.example.lab  ');
 await modal.getByRole('button',{name:'Confirm',exact:true}).click();
 await page.getByText('Uninstall app queued',{exact:true}).waitFor();
 assert.deepEqual(queued,[{type:'app.uninstall',requiresCapability:'app.silentInstall',payload:JSON.stringify({packageName:'com.example.lab'})}]);
 assert.deepEqual(errors,[]);
 console.log('UI passed: blank/self-agent blocked, cancel sends no command, confirmed uninstall sends trimmed package to the selected device.');
}finally{await browser?.close();await new Promise(resolve=>server.close(resolve));}
