// Install optional browser test dependency: npm install --no-save playwright
// Install Chromium for Playwright: npx playwright install chromium
// Run from project root: node scripts/ui_smoke.cjs
// These tests mock all /api responses; run scripts/integration_test.py separately for backend verification.
const {chromium}=require('playwright');
const assert=require('node:assert/strict');
const http=require('node:http');
const fs=require('node:fs');
const pathModule=require('node:path');
// UI-only contract test: /api calls below are fixtures, not the JDBC backend.
const webRoot=pathModule.resolve(__dirname,'../src/main/resources/web');
const server=http.createServer((req,res)=>{const target=pathModule.join(webRoot,req.url==='/'?'index.html':decodeURIComponent(req.url.split('?')[0]));if(!target.startsWith(webRoot+pathModule.sep)){res.writeHead(403);res.end();return;}fs.readFile(target,(err,bytes)=>{if(err){res.writeHead(404);res.end();return;}res.setHeader('Content-Type',target.endsWith('.js')?'application/javascript':target.endsWith('.css')?'text/css':target.endsWith('.svg')?'image/svg+xml':'text/html');res.end(bytes);});});
(async()=>{
 await new Promise(resolve=>server.listen(8861,'127.0.0.1',resolve));
 let user=null;let nextId=20;let created=null;let uploaded=false;let csrf='test-csrf';
 const categories=[{id:'BAGS',name:'Bags',subcategories:[{id:'BACKPACK',name:'Backpack'},{id:'TOTE',name:'Tote'}]},{id:'ELECTRONICS',name:'Electronics',subcategories:[{id:'PHONE',name:'Phone'},{id:'TABLET',name:'Tablet'}]},{id:'IDS',name:'IDs',subcategories:[{id:'USC_ID',name:'USC ID'}]},{id:'CLOTHING',name:'Clothing',subcategories:[{id:'JACKET',name:'Jacket'}]},{id:'OTHER',name:'Other belongings',subcategories:[{id:'WALLET',name:'Wallet'}]}];
 const reports=[['Olive green backpack','BAGS','BACKPACK','Leavey Library'],['Black wireless headphones','ELECTRONICS','PHONE','Doheny Library'],['USC student ID card','IDS','USC_ID','Tutor Campus Center'],['Brown leather wallet','OTHER','WALLET','McCarthy Quad'],['Navy blue jacket','CLOTHING','JACKET','Lyon Center'],['Canvas tote bag','BAGS','TOTE','Village']].map(([title,category,subcategory,location],i)=>({id:i+1,title,category,subcategory,location,type:'FOUND',visibility:'PUBLIC',description:'An item found near the entrance, in good condition. Looking for its owner.',color:'Green',brand:'',eventDate:'2026-11-20',status:'OPEN',hasPhoto:false,version:1}));
 const lost={...reports[0],id:10,type:'LOST',visibility:'PRIVATE'};
 const browser=await chromium.launch({...(process.env.CHROMIUM_PATH?{executablePath:process.env.CHROMIUM_PATH}:{}),headless:true,args:['--no-sandbox','--disable-dev-shm-usage','--no-zygote','--single-process']});const page=await browser.newPage({viewport:{width:1440,height:1000}});const errors=[];
 page.on('pageerror',e=>errors.push(e.message));
 await page.route('**/api/**',async route=>{
  const req=route.request(),url=new URL(req.url()),path=url.pathname,method=req.method();let data={};
  if(!['GET','HEAD'].includes(method)) assert.equal(req.headers()['x-csrf-token'],csrf);
  if(path==='/api/session')data={user,csrfToken:csrf,demoMode:true};
  else if(path==='/api/categories')data={categories};
  else if(path==='/api/search')data={reports,total:reports.length,page:1,pageSize:12,privateCount:user?3:null};
  else if(path==='/api/login'){const body=req.postDataJSON();user={id:body.username==='admin'?3:1,username:body.username,name:body.username==='admin'?'Site Administrator':'Alice Chen',role:body.username==='admin'?'ADMIN':'USER'};csrf='new-csrf';data={user,csrfToken:csrf};}
  else if(path==='/api/register'){user={id:7,...req.postDataJSON(),role:'USER'};data={user};}
  else if(path==='/api/logout'){user=null;csrf='out-csrf';data={csrfToken:csrf};}
  else if(path==='/api/me')data={reports:[lost,...(created?[created]:[])],matches:[{lostReportId:10,foundReport:{...reports[0],visibility:'PRIVATE'},score:90,reasons:['Same category','Compatible dates']}],claims:[{id:1,foundReportId:1,status:'PENDING',submittedAt:'2026-11-21T19:00:00Z'}],notifications:[{id:1,message:'A new report may match your lost item.',createdAt:'2026-11-21T19:00:00Z'}]};
  else if(path==='/api/reports'&&method==='POST'){created={...req.postDataJSON(),id:nextId++,status:'OPEN',version:1,hasPhoto:false};data={report:created};}
  else if(/\/photo$/.test(path)&&method==='GET'){await route.fulfill({contentType:'image/png',body:Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jT9sAAAAASUVORK5CYII=','base64')});return;}
  else if(/\/photo$/.test(path)){assert.equal(req.headers()['x-report-version'],String(created.version));uploaded=true;created.hasPhoto=true;created.version++;data={report:created};}
  else if(/^\/api\/reports\/\d+$/.test(path)){const id=+path.split('/').pop();const report=id===created?.id?created:id===10?lost:reports.find(r=>r.id===id);if(method==='DELETE'){data={ok:true};}else if(method==='PUT'){created={...report,...req.postDataJSON(),version:report.version+1};data={report:created};}else data={report,canEdit:!!user&&(id===10||id===created?.id),canClaim:!!user&&id!==10&&id!==created?.id,verificationQuestion:'What marking is on the underside?'};}
  else if(path==='/api/claims')data={ok:true};
  else if(path==='/api/admin')data={reports,users:[user],claims:[{id:1,foundReportId:1,claimantId:1,status:'PENDING',answerMatches:true,explanation:'I have a receipt.'}],audit:[]};
  else if(path.startsWith('/api/admin/claims/'))data={ok:true};
  else {console.log('UNHANDLED',path,method);data={};}
  await route.fulfill({json:data});
 });
 const checks=[];const check=(name)=>{checks.push(name);console.log('PASS',name);};
 await page.goto('http://127.0.0.1:8861/#/search');await page.getByRole('heading',{name:'Lost something? Let’s reunite you.'}).waitFor();assert.equal(await page.locator('.report-card').count(),6);check('Guest search renders six public report cards');
 if(process.env.UI_SCREENSHOTS)await page.screenshot({path:pathModule.join(process.env.UI_SCREENSHOTS,'reunite-search-desktop.png'),fullPage:true});
 assert.equal(await page.locator('.header-actions a[href="#/new"]').count(),0);check('Guest navigation hides report creation control');
 await page.getByRole('link',{name:'Sign in',exact:true}).first().click();await page.locator('#username').fill('alice');await page.locator('#password').fill('AliceDemo1!');await page.locator('#auth-form button[type=submit]').click();await page.getByRole('heading',{name:'Hello, Alice.'}).waitFor();check('Login rotates CSRF and opens authenticated dashboard');
 await page.getByRole('link',{name:'Potential matches (1)'}).click();await page.getByRole('heading',{name:'Olive green backpack'}).waitFor();check('Dashboard potential match view renders private qualifying report');
 await page.getByRole('link',{name:'View match'}).click();await page.getByRole('heading',{name:'Could this be yours?'}).waitFor();await page.locator('#answer').fill('star');await page.locator('#claim-form button[type=submit]').click();await page.getByRole('heading',{name:'Your claims'}).waitFor();check('Ownership claim submits and opens claim history');
 await page.goto('http://127.0.0.1:8861/#/new?type=FOUND');await page.locator('#report-form').waitFor();await page.locator('#title').fill('Black canvas backpack');await page.locator('#description').fill('Black canvas backpack with a red zipper near the entrance.');await page.locator('#category').selectOption('BAGS');await page.locator('#subcategory').selectOption('BACKPACK');await page.locator('#location').fill('Leavey Library');await page.locator('#isPrivate').check();await page.locator('#verificationQuestion').fill('What symbol is underneath?');await page.locator('#verificationAnswer').fill('star');
 await page.locator('#photo-file').setInputFiles({name:'test.png',mimeType:'image/png',buffer:Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jT9sAAAAASUVORK5CYII=','base64')});
 await page.locator('#report-form button[type=submit]').click();await page.getByRole('heading',{name:'Black canvas backpack',exact:true}).waitFor();assert.equal(created.visibility,'PRIVATE');assert.equal(uploaded,true);check('Private found report and raw image upload submit with version header');
 await page.getByRole('link',{name:'Edit report',exact:true}).click();await page.locator('#report-form').waitFor();assert.equal(await page.locator('#title').inputValue(),'Black canvas backpack');await page.locator('#title').fill('Updated black canvas backpack');await page.locator('#report-form button[type=submit]').click();await page.getByRole('heading',{name:'Updated black canvas backpack',exact:true}).waitFor();check('Report edit preserves values and submits optimistic version');
 await page.goto('http://127.0.0.1:8861/#/new?type=LOST');await page.locator('#report-form').waitFor();assert.equal(await page.locator('#found-visibility').isVisible(),false);assert.equal(await page.locator('#lost-visibility').isVisible(),true);assert.equal(await page.locator('#verificationQuestion').getAttribute('required'),null);check('Lost form uses private visibility and removes found verification requirement');
 await page.goto('http://127.0.0.1:8861/#/search');await page.locator('.report-card').first().waitFor();assert.equal(await page.locator('.private-banner strong').textContent(),'3 private found reports match your search');check('Authenticated search displays count-only private summary');
 await page.setViewportSize({width:390,height:844});if(process.env.UI_SCREENSHOTS)await page.screenshot({path:pathModule.join(process.env.UI_SCREENSHOTS,'reunite-search-mobile.png'),fullPage:true});assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth),true);check('Mobile search has no horizontal overflow');
 await page.getByRole('button',{name:'Sign out',exact:true}).click();await page.locator('.report-card').first().waitFor();await page.goto('http://127.0.0.1:8861/#/login');await page.locator('#username').fill('admin');await page.locator('#password').fill('AdminDemo1!');await page.locator('#auth-form button[type=submit]').click();await page.locator('a[href="#/admin"]').waitFor();await page.locator('a[href="#/admin"]').click();await page.getByRole('heading',{name:'Review with care.'}).waitFor();assert.equal(await page.locator('.admin-claim').count(),1);assert.match(await page.locator('.admin-claim').textContent(),/Matches stored answer/);check('Administrator workspace displays server answer-check result');
 await page.locator('.admin-decision-form input[name=reason]').fill('Receipt and identifying mark verified');await page.locator('.admin-decision-form button').click();await page.getByRole('heading',{name:'Review with care.'}).waitFor();check('Administrator decision submits with reason');
 assert.deepEqual(errors,[]);check('No uncaught browser JavaScript errors');console.log(JSON.stringify({passed:checks.length,checks},null,2));await browser.close();server.close();
})().catch(e=>{console.error(e);process.exit(1);});
