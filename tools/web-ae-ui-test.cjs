// node tools/web-ae-ui-test.cjs [path/to/aestorage.py]; no npm or network needed.
const fs=require('node:fs'),vm=require('node:vm'),crypto=require('node:crypto'),assert=require('node:assert/strict');
const source=fs.readFileSync(process.argv[2]||'src/main/resources/assets/mpy4oc/mpyos/lib/aestorage.py','utf8');
const script=source.match(/<script>([\s\S]*?)<\/script>/)[1];new vm.Script(script);
function realm(){
 const c=vm.createContext({TextEncoder,URL,AbortController,setTimeout,clearTimeout,location:{origin:'http://127.0.0.1:5534'}});
 vm.runInContext(script.split('// DOM application starts here.')[0],c);
 c.Index=vm.runInContext('FluidIconIndex',c);c.key=vm.runInContext('ICON_CACHE_KEY',c);c.ttl=vm.runInContext('ICON_CACHE_TTL',c);return c;
}
const context=realm();
for(const text of ['', 'abc', 'Icon_minecraft_iron_ingot.png', 'Icon_gregtech_gt.metaitem.01_11035.png', 'x'.repeat(55), 'x'.repeat(56), 'x'.repeat(64), 'x'.repeat(120), '📦'])assert.equal(context.md5(text),crypto.createHash('md5').update(text).digest('hex'));
assert.equal(context.wikiIcon({kind:'item',name:'minecraft:iron_ingot',damage:'0'}),'https://huiji-thumb.huijistatic.com/gtnh/uploads/thumb/6/62/Icon_minecraft_iron_ingot.png/64px-Icon_minecraft_iron_ingot.png');
for(const name of ['water','minecraft:water','minecraft:flowing_water'])assert.equal(context.wikiIcon({kind:'fluid',name}),'https://huiji-thumb.huijistatic.com/gtnh/uploads/thumb/e/e2/Icon_minecraft_flowing_water.png/64px-Icon_minecraft_flowing_water.png');
assert.equal(vm.runInContext('fluidIcons.size',context),0,'No embedded fluid index');
for(const row of [{kind:'fluid',name:'oxygen'}, {kind:'item',name:'test:<img>',damage:'0'}, {kind:'item',name:'test:../../x',damage:'0'}, {kind:'item',name:'minecraft:iron_ingot',damage:'-1'}])assert.equal(context.wikiIcon(row),null);
const base=9007199254740993n;
assert.equal(context.fraction(base,base,base+2n),0);assert.equal(context.fraction(base+1n,base,base+2n),0.5);assert.equal(context.fraction(base+2n,base,base+2n),1);assert.equal(context.fraction(base,base,base),0.5);
assert.match(source,/id="images" autocomplete="off">/);assert.match(source,/connect-src 'self' https:\/\/gtnh[.]huijiwiki[.]com;/);
assert.doesNotMatch(source,/GENERATED FLUID ICONS|[\u3400-\u9fff]/,'No bundled table; English UI');
const prefix='Data:Versions/GTNH/2.9.0-RC-1/Items/',filePrefix='Icon_gregtech_gt.GregTech_FluidDisplay_';
const records=[['oxygen','111_Lftlt65FCj6aiKB'],['molten.iron','288_VjzqOBvE2UL4RDM'],['steam','69_529uctABUjKQzKu'],['aqua regia','1_shared'],['1,1dimethylhydrazine','2_shared'],['mtbereactionmixture(butene)','3_shared'],["molten.wood'sglass",'4_shared']];
function revision(title,rows){return {title,revisions:[{revid:123,slots:{main:{content:JSON.stringify({items:rows.map(([name,suffix])=>({key:'fluid.'+name,iconName:'i'+filePrefix.slice(1)+suffix+'.png'}))})}}}]};}
function replies(){return [
 {query:{searchinfo:{totalhits:2},search:[{title:prefix+'26.json'}]},continue:{continue:'-||',sroffset:1}},
 {query:{search:[{title:prefix+'27.json'}]}},
 {query:{pages:[revision(prefix+'26.json',records.slice(0,3)),revision(prefix+'27.json',records.slice(3))]}}
];}
function memoryStorage(){const data=new Map();return {data,getItem:key=>data.get(key)||null,setItem:(key,value)=>data.set(key,value)};}
function wiki(responses=replies()){
 const calls=[];const request=async(url,options)=>{
  const parsed=new URL(url);calls.push(parsed);assert.equal(parsed.origin,'https://gtnh.huijiwiki.com');assert.equal(parsed.searchParams.get('origin'),'http://127.0.0.1:5534');assert.equal(options.credentials,'omit');assert.equal(options.referrerPolicy,'no-referrer');
  const reply=responses.shift();if(reply instanceof Error)throw reply;assert.ok(reply,'Unexpected extra Wiki request');return {ok:true,status:200,text:async()=>JSON.stringify(reply)};
 };return {calls,request};
}
function index(ctx,storage,network,now=()=>2000000000000,extra={}){
 const states=[];const loader=new ctx.Index({storage:()=>storage,request:network.request,now,changed:(map,state)=>states.push({map,...state}),...extra});return {loader,states};
}
async function main(){
 const storage=memoryStorage(),network=wiki(),{loader,states}=index(context,storage,network);
 await loader.refresh();await loader.setEnabled(false);assert.equal(network.calls.length,0,'No requests before opt-in, even via Refresh');assert.equal(storage.data.size,0);
 await Promise.all([loader.setEnabled(true),loader.setEnabled(true),loader.refresh()]);
 assert.equal(network.calls.length,3,'Concurrent opt-in/refresh shares one fetch');assert.equal(network.calls[1].searchParams.get('sroffset'),'1');assert.equal(network.calls[2].searchParams.get('titles'),prefix+'26.json|'+prefix+'27.json');
 assert.equal(states.at(-1).phase,'updated');assert.equal(states.at(-1).persistent,true);assert.equal(loader.record.map.size,records.length);
 context.loadedMap=loader.record.map;vm.runInContext('fluidIcons=loadedMap',context);
 assert.equal(context.wikiFile({kind:'fluid',name:'oxygen'}),filePrefix+'111_Lftlt65FCj6aiKB.png');
 assert.equal(context.wikiOriginal({kind:'fluid',name:'steam'}),'https://huiji-public.huijistatic.com/gtnh/uploads/0/0e/Icon_gregtech_gt.GregTech_FluidDisplay_69_529uctABUjKQzKu.png');
 for(const [name] of records)assert.match(context.wikiIcon({kind:'fluid',name}),/^https:\/\/huiji-thumb[.]huijistatic[.]com\//);
 // Reload into a fresh realm: only persistent storage survives; opt-in resets.
 const secondNetwork=wiki(),second=index(realm(),storage,secondNetwork);
 assert.equal(second.loader.enabled,false);assert.equal(second.loader.record,null);await second.loader.setEnabled(true);assert.equal(secondNetwork.calls.length,0,'Reload + opt-in uses persistent cache');assert.equal(second.states.at(-1).phase,'cached');
 await second.loader.refresh();assert.equal(secondNetwork.calls.length,3,'Manual refresh bypasses fresh cache');await second.loader.setEnabled(false);await second.loader.setEnabled(true);assert.equal(secondNetwork.calls.length,3);
 const prior=storage.getItem(context.key),expired=index(context,storage,wiki([new Error('offline')]),()=>2000000000000+context.ttl+1);
 await expired.loader.setEnabled(true);assert.equal(expired.states[0].phase,'refreshing');assert.equal(expired.states.at(-1).phase,'error');assert.equal(expired.loader.record.map.size,records.length);assert.equal(storage.getItem(context.key),prior,'Failed refresh preserves old durable cache');
 for(const raw of ['{broken',JSON.stringify({schema:2}),prior.replace('Icon_gregtech','https://evil.test/'),prior.replace('2000000000000','3000000000000')]){
  const broken=memoryStorage();broken.setItem(context.key,raw);const net=wiki(),item=index(context,broken,net);await item.loader.setEnabled(true);assert.equal(net.calls.length,3);assert.equal(item.states.at(-1).phase,'updated');
 }
 const denied=()=>{throw Error('storage denied');},privateNet=wiki(),privateIndex=index(context,{getItem:denied,setItem:denied},privateNet);
 await privateIndex.loader.setEnabled(true);assert.equal(privateIndex.states.at(-1).persistent,false);await privateIndex.loader.setEnabled(false);await privateIndex.loader.setEnabled(true);assert.equal(privateNet.calls.length,3,'Denied storage retains session cache');
 const getter=index(context,null,wiki(),undefined,{storage:denied});await getter.loader.setEnabled(true);assert.equal(getter.states.at(-1).phase,'updated');
 for(const responses of [
  [{query:{search:[{title:prefix+'26.json'}]}},{query:{pages:[]}}],
  [{query:{search:[{title:prefix+'26.json'}]},continue:{sroffset:1}},{query:{search:[]},continue:{sroffset:1}}],
  [{query:{searchinfo:{totalhits:2},search:[{title:prefix+'26.json'}]}}],
  [{query:{search:[{title:'Data:Versions/GTNH/old/Items/26.json'}]}}],
  [{error:{code:'maxlag'}}],
  [{query:{search:[{title:prefix+'26.json'}]}},{query:{pages:[revision(prefix+'26.json',[['same','1_a'],['same','2_b']])]}}],
  [{query:{search:[{title:prefix+'26.json'}]}},{query:{pages:[revision(prefix+'26.json',[['evil','../../bad']])]}}]
 ]){
  const item=index(context,storage,wiki(responses),()=>2000000000000+context.ttl+1);await item.loader.setEnabled(true);assert.equal(item.states.at(-1).phase,'error','Malformed/partial data must fail');assert.equal(storage.getItem(context.key),prior);
 }
 // An abandoned request finishing after a retry cannot overwrite the new result.
 let release,signal;const raceStorage=memoryStorage(),oldNetwork=wiki(),retryNetwork=wiki();
 const race=index(context,raceStorage,{request:(url,options)=>{signal=options.signal;return new Promise(resolve=>{release=async()=>resolve(await oldNetwork.request(url,options));});}});
 const pending=race.loader.setEnabled(true);await race.loader.setEnabled(false);assert.equal(signal.aborted,true);race.loader.request=retryNetwork.request;await race.loader.setEnabled(true);
 const afterRetry=raceStorage.getItem(context.key);await release();await pending;assert.equal(raceStorage.getItem(context.key),afterRetry);assert.equal(race.states.at(-1).phase,'updated');assert.equal(oldNetwork.calls.length,1);
 const timeout=index(context,memoryStorage(),{request:(url,{signal})=>new Promise((resolve,reject)=>signal.addEventListener('abort',()=>reject(Error('aborted'))))},undefined,{timeout:5});await timeout.loader.setEnabled(true);assert.equal(timeout.states.at(-1).error,'Request timed out');assert.equal(timeout.loader.pending,null);
 console.log('AE frontend passed: opt-in, persistent cache, TTL, manual refresh, denial/corruption, partial Wiki data, cancellation races, timeout, icons, chart precision and English UI.');
}
main().catch(error=>{console.error(error);process.exitCode=1;});
