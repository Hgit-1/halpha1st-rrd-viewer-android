'use strict';
const assert=require('node:assert/strict'),{selectChunks,groupParts,staleTarget}=require('../assets/rerun/source_loader.js');
const item=(id,part,lo,hi)=>({id,part,offset:12,length:8,entity:'/image/img',components:['Blob'],timelines:{time:{lo:String(lo),hi:String(hi)}}});
const index=[item('early',2,0,10),item('target',178,900,910),item('same-part',178,905,915),item('late',250,1400,1410)];
const wanted=selectChunks(index,'time','907');assert.deepEqual(wanted.map(x=>x.id),['target','same-part','early']);assert.deepEqual(groupParts(wanted).map(x=>x.part),[178,2]);
assert(staleTarget({timeline:'time',time:'907',playing:false,dragging:true},{timeline:'time',time:'1200',playing:false,dragging:true},1000000000n));
assert(staleTarget({timeline:'time',time:'907',playing:true,dragging:false},{timeline:'time',time:'1200',playing:true,dragging:false},100n));
assert(!staleTarget({timeline:'time',time:'907',playing:false,dragging:false},{timeline:'time',time:'907',playing:false,dragging:false},100n));
console.log('PASS direct seek selection: target plus latest-at fallback only, grouped atomic frames, stale target cancellation');
