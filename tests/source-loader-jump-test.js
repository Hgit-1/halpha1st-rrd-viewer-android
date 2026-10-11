'use strict';
const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
async function scenario(changeDuringFetch){
  let time='300',done=false,protect=[],reclaims=0;const resident=new Set(),sent=[],fetches=[],bounds=[];
  const header=Uint8Array.from([82,82,70,50,0,0,0,0,0,0,0,0]);
  const fixtures=Array.from({length:3},(_,part)=>{
    const bytes=new Uint8Array(36);bytes.set(header);bytes.fill(9,12,20);bytes.fill(part+1,20,28);
    return {bytes,index:[{metadata:true,offset:12,length:8},{id:'id'+part,entity:'/image/img',components:['Blob'],offset:20,length:8,timelines:{time:{lo:String((part+1)*100),hi:String((part+1)*100+1),sequence:false}}}]};
  });
  const window={};class Worker{
    postMessage({request,url,indexOnly}){const part=Number(/part-(\d+)/.exec(url)[1]);fetches.push({part,indexOnly});
      if(indexOnly)assert.deepEqual(sent,[9,1],'real recording activated before metadata-only scan; no future rows submitted');
      else if(part===2&&changeDuringFetch)time='100';
      queueMicrotask(()=>this.onmessage({data:{request,index:fixtures[part].index,...(indexOnly?{}:{bytes:fixtures[part].bytes})}}));
    }
    terminate(){}
  }
  const handle={open_channel(){},enable_source_backing(){},close_channel(){},mobile_queue_len(){return 0;},
    mobile_playback_state(){return JSON.stringify({timeline:'time',time,playing:false,dragging:false});},
    mobile_source_bounds(t,s,lo,hi){bounds.push([lo,hi]);},
    mobile_protect_chunks(ids){protect=ids.split(',');},
    mobile_reclaim_source(){reclaims++;for(const id of resident)if(!protect.includes(id))resident.delete(id);},
    mobile_missing_chunks(ids){return ids.split(',').filter(id=>id&&!resident.has(id)).join(',');},
    send_rrd_frame_sync(c,bytes){const value=bytes[12];sent.push(value);if(value!==9)resident.add('id'+(value-1));},
  };
  vm.runInNewContext(fs.readFileSync('assets/rerun/source_loader.js','utf8'),{window,Worker,location:{search:''},performance,Uint8Array,Map,Set,BigInt,Error,JSON,encodeURIComponent,setTimeout:cb=>{queueMicrotask(cb);}});
  await window.RerunSource.load(handle,'test',3,{failed:()=>done,stage(message){if(message.startsWith('source-seek '))done=true;}});
  assert.deepEqual(bounds,[['100','301']],'complete bounds published once');
  assert.equal(reclaims,changeDuringFetch?2:1);
  if(changeDuringFetch){assert.deepEqual(sent,[9,1,1],'paused post-I/O stale target abandoned, latest target restored after reclaim');}
  else{assert.deepEqual(sent,[9,1,3,2],'bootstrap plus adjacent image chunk for continuity');}
  assert.equal(fetches.filter(x=>!x.indexOnly&&x.part===0).length,1,'recent part reused from bounded LRU');
}
(async()=>{await scenario(false);await scenario(true);console.log('PASS loader direct seek execution: real bootstrap before indexing, fixed bounds, target-first loading, post-I/O paused-target cancellation, LRU reuse');})().catch(e=>{console.error(e);process.exitCode=1;});
