'use strict';
const assert=require('node:assert/strict'), fs=require('node:fs'), vm=require('node:vm');
const {selectChunks}=require('../assets/rerun/source_loader.js');
const header=Uint8Array.from([82,82,70,50,0,0,0,0,0,0,0,0]);
function part(value,index){const bytes=new Uint8Array(36);bytes.set(header);bytes.set([1,2,3,4,5,6,7,8],12);bytes.fill(value,20,28);return {bytes,index};}
const metadata={metadata:true,offset:12,length:8};
const chunk=(id,lo,offset=20)=>({id,entity:'/image/img',components:['rerun.components.Blob'],offset,length:8,timelines:{time:{lo:String(lo),hi:String(lo),sequence:false}}});
const delayed=chunk('delayed',600);delayed.entity='/image/detection_boxes';delayed.components=['Boxes2DIndicator'];
const delayedWanted=selectChunks([delayed],'time','1000',0n,120n);assert.equal(delayedWanted.length,1,'delayed detection keeps the bounded 500ms tail');
const a=chunk('a',100),b=chunk('b',200);
const parts=[part(11,[metadata,a]),part(22,[metadata,b])];
const resident=new Set(), events=[], sent=[];let protectedIds=[],done=false;
const window={};
class Worker {
  postMessage({request,url}){
    const n=Number(/part-(\d+)/.exec(url)[1]);events.push('fetch:'+n);
    queueMicrotask(()=>this.onmessage({data:{request,...parts[n]}}));
  }
  terminate(){events.push('terminate');}
}
const handle={
  open_channel(){events.push('open');},close_channel(){events.push('close');},
  enable_source_backing(){events.push('enable');},mobile_queue_len(){return 0;},
  mobile_source_bounds(name,sequence,lo,hi){assert.equal(name,'time');assert.equal(sequence,false);assert(BigInt(lo)<=BigInt(hi));},
  mobile_playback_state(){return JSON.stringify({timeline:'time',time:'200',playing:false,dragging:false});},
  mobile_protect_chunks(csv){protectedIds=csv.split(',').filter(Boolean);events.push('protect:'+csv);},
  mobile_missing_chunks(csv){return csv.split(',').filter(id=>id&&!resident.has(id)).join(',');},
  send_rrd_frame_sync(channel,bytes){
    assert.equal(bytes.length,28);assert.deepEqual([...bytes.slice(0,12)],[...header]);
    assert.deepEqual([...bytes.slice(-8)],Array(8).fill(0));
    const payload=[...bytes.slice(12,20)];sent.push(payload);events.push('send:'+payload[0]);
    if(payload[0]===11)resident.add('a');if(payload[0]===22)resident.add('b');
  },
  mobile_reclaim_source(){events.push('reclaim');for(const id of resident)if(!protectedIds.includes(id))resident.delete(id);},
};
const ctx=vm.createContext({window,Worker,location:{search:'?asset_version=test'},performance,
  Uint8Array,Map,Set,BigInt,Error,JSON,encodeURIComponent,setTimeout:(cb)=>{queueMicrotask(cb);return 1;}});
vm.runInContext(fs.readFileSync('assets/rerun/source_loader.js','utf8'),ctx);
(async()=>{
 await window.RerunSource.load(handle,'fixture',2,{stage(){},failed:()=>done,submitted:()=>{done=true;}});
 assert.equal(sent.length,3,'bootstrap plus only the latest image frame in the bounded window');assert.deepEqual(sent.slice(1),[Array(8).fill(11),Array(8).fill(22)]);
 assert(events.indexOf('protect:a,b')<events.indexOf('send:22'),'new active IDs protected before insertion');
 assert.equal(events.filter(e=>e==='reclaim').length,1);assert.deepEqual([...resident].sort(),['a','b']);
 assert.equal(events.at(-1),'close');assert(events.includes('terminate'));
 console.log('PASS source-loader execution: original frames, metadata once, pre-insert protection, reclamation, cleanup');
})().catch(error=>{console.error(error);process.exitCode=1;});
