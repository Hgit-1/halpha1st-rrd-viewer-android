'use strict';
const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
(async()=>{
  let time='1000000000',playing=false,done=false,inFlight=0,unsafeRecycles=0;
  const resident=new Set(),stages=[],workers=[];
  const fixtures=Array.from({length:17},(_,part)=>{
    const bytes=new Uint8Array(36);bytes.set([82,82,70,50],0);bytes.fill(200,12,20);bytes.fill(part+1,20,28);
    const index=[{metadata:true,offset:12,length:8}];
    if(part===0||part===1||part===14)index.push({id:'id'+part,entity:part===0?'/bootstrap':'/image/img',
      components:part===0?['Text']:['EncodedImageIndicator','Blob'],offset:20,length:8,
      timelines:{time:{lo:part===14?'2000000000':'1000000000',hi:part===14?'2100000000':'1100000000',sequence:false}}});
    return {bytes,index};
  });
  class Worker{
    constructor(){this.dead=false;workers.push(this);}
    postMessage({request,url,indexOnly}){
      const part=Number(/part-(\d+)/.exec(url)[1]);
      let delay=1;
      if(indexOnly&&part===15){
        time='2000000000';playing=true;delay=180;
        setTimeout(()=>{playing=false;},140);
      }
      if(!indexOnly&&part===14){delay=350;inFlight++;}
      setTimeout(()=>{
        if(!indexOnly&&part===14)inFlight--;
        if(!this.dead)this.onmessage({data:{request,index:fixtures[part].index,...(indexOnly?{}:{bytes:fixtures[part].bytes})}});
      },delay);
    }
    terminate(){if(inFlight&&!done)unsafeRecycles++;this.dead=true;}
  }
  const window={};let protectedIds=[];
  const handle={open_channel(){},enable_source_backing(){},close_channel(){},mobile_queue_len(){return 0;},
    mobile_playback_state(){return JSON.stringify({timeline:'time',time,playing,dragging:false});},
    mobile_source_bounds(){},mobile_protect_chunks(ids){protectedIds=ids.split(',');},
    mobile_reclaim_source(){for(const id of resident)if(!protectedIds.includes(id))resident.delete(id);},
    mobile_missing_chunks(ids){return ids.split(',').filter(id=>id&&!resident.has(id)).join(',');},
    send_rrd_frame_sync(c,bytes){if(bytes[12]!==200)resident.add('id'+(bytes[12]-1));},
  };
  vm.runInNewContext(fs.readFileSync('assets/rerun/source_loader.js','utf8'),{window,Worker,
    location:{search:''},performance,Uint8Array,Map,Set,BigInt,Error,JSON,encodeURIComponent,setTimeout});
  let watchdog;
  try{
    await Promise.race([
      window.RerunSource.load(handle,'playback-race',17,{failed:()=>done,stage(s){stages.push(s);},submitted(){done=true;}}),
      new Promise((_,reject)=>{watchdog=setTimeout(()=>{done=true;window._sourceLoader.stop();reject(Error('playback restore stalled during Worker recycling'));},2500);}),
    ]);
    assert.equal(unsafeRecycles,0,'never terminate a Worker with a pending playback read');
    assert(resident.has('id14'),'new playback image delivered after slow I/O');
    assert(stages.some(s=>s.startsWith('source-index-complete')),'scan and playback both finish');
    console.log('PASS concurrent index/playback: slow image fetch survives Worker recycle boundary');
  }finally{clearTimeout(watchdog);done=true;window._sourceLoader.stop();}
})().catch(e=>{console.error(e);process.exitCode=1;});
