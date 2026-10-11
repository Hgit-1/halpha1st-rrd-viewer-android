'use strict';
const assert=require('node:assert/strict');const fs=require('node:fs');const vm=require('node:vm');
const {selectChunks,frameBytes}=require('../assets/rerun/source_loader.js');
const item=(id,lo,hi,entity='/image/img',components=['Blob'])=>({id,entity,components,part:0,offset:12,length:8,timelines:{time:{lo:String(lo),hi:String(hi)}}});
const n=1786790718411609000n;
const index=[item('past',n-20n,n-10n),item('current',n-2n,n+5n),item('future',n+50n,n+60n),item('boxes',n-100n,n+100n,'/image/boxes',['Boxes']),item('previous-level',n-50n,n-30n,'/log',['Level']),{...item('static',0,0,'/static'),timelines:{}}];
const ids=selectChunks(index,'time',String(n)).map(x=>x.id);
assert(ids.includes('current'));assert(ids.includes('boxes'));assert(ids.includes('previous-level'));assert(ids.includes('static'));assert(!ids.includes('future'));
assert(selectChunks(index,'time',String(n),100n).some(x=>x.id==='future'));
assert.equal(selectChunks(index,'time',String(n-3n))[0].id,'past');
// Exact encoded frame/header bytes and a fresh stream terminator.
const b=Uint8Array.from({length:40},(_,i)=>i),fr=frameBytes(b,{offset:20,length:8});
assert.deepEqual([...fr.slice(0,12)],[...b.slice(0,12)]);assert.deepEqual([...fr.slice(12,20)],[...b.slice(20,28)]);assert.deepEqual([...fr.slice(-8)],Array(8).fill(0));
const html=fs.readFileSync('assets/rerun/index.html','utf8');const start=html.indexOf('      window.startFilteredRecording =');const end=html.indexOf('\n      function on_app_started',start);
async function wrapper(busy,fail){let loaded=0,fatal=0;const window={_filteredBusy:busy};const ctx=vm.createContext({window,viewerFailed:false,RerunSource:{load:async()=>{loaded++;if(fail)throw Error('decoder failed');}},reportFatal:()=>fatal++});vm.runInContext(html.slice(start,end),ctx);const promise=window.startFilteredRecording({},'source',319);if(busy){await assert.rejects(promise,/already active/);assert.equal(loaded,0);}else{await promise;assert.equal(window._filteredBusy,false);assert.equal(fatal,fail?1:0);}}
(async()=>{await wrapper(false,false);await wrapper(false,true);await wrapper(true,false);
assert(fs.readFileSync('assets/rerun/source_loader.js','utf8').includes('mobile_protect_chunks'));
console.log('PASS source seek selection, exact nanosecond targets, atomic frame reconstruction, failure stop, duplicate import guard');})().catch(e=>{console.error(e);process.exitCode=1;});
