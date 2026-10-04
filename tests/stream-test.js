'use strict';
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const html=fs.readFileSync('assets/rerun/index.html','utf8');
const start=html.indexOf('      window.startFilteredRecording =');
const end=html.indexOf('\n      function on_app_started',start);
assert(start>=0 && end>start);
async function run({failSend=false,failDuringYield=false,busy=false,empty=false}={}) {
  const events=[],stages=[],fatal=[],timers=[];
  const window={_filteredBusy:busy,RerunAndroid:{stage:x=>stages.push(x)},RerunFilter:{
    inspectRrd:()=>({image:null}),
    *transformRrdParts(bytes,lo,hi,include){
      events.push(['filter',include]);
      if(!empty){yield Uint8Array.of(1);yield Uint8Array.of(2);}
    }
  }};
  const context=vm.createContext({window,viewerFailed:false,wasmExports:{memory:{buffer:{byteLength:123}}},
    fetch:async url=>{events.push(['fetch',url]);return {ok:true};},
    readBoundedResponse:async()=>Uint8Array.of(9),
    reportFatal:x=>fatal.push(x),
    setTimeout:fn=>{timers.push(fn);}
  });
  const metricsStart=html.indexOf('      function allocationMetrics()');
  const metricsEnd=html.indexOf('      function reportFatal(',metricsStart);
  assert(metricsStart>=0 && metricsEnd>metricsStart);
  vm.runInContext(html.slice(metricsStart,metricsEnd),context);
  vm.runInContext(html.slice(start,end),context);
  const handle={open_channel:()=>events.push(['open']),send_rrd_to_channel:(id,bytes)=>{
    events.push(['send',bytes[0]]);if(failSend)throw Error('send failure');
  }};
  const promise=window.startFilteredRecording(handle,'a/b',2);
  if(busy){await assert.rejects(promise,/already active/);assert.equal(events.length,0);return;}
  // Drain microtasks deliberately: no additional send may occur until the
  // pacing timer fires. This tests scheduling, not WASM decoder completion.
  for(let n=0;n<40;n++) {
    await Promise.resolve();
    if(timers.length){
      assert.equal(timers.length,1);
      const sent=events.filter(x=>x[0]==='send').length;
      await Promise.resolve();
      assert.equal(events.filter(x=>x[0]==='send').length,sent);
      if(failDuringYield)context.viewerFailed=true;
      timers.shift()();
    }
  }
  await promise;
  assert.equal(window._filteredBusy,false);
  const sent=events.filter(x=>x[0]==='send');
  if(failSend||failDuringYield){
    assert.equal(sent.length,1);
    assert.equal(fatal.length,1);
    assert(!stages.some(x=>x.startsWith('filtered-submitted')));
  }else{
    assert.deepEqual(sent.map(x=>x[1]),empty?[]:[1,2,1,2]);
    assert.deepEqual(events.filter(x=>x[0]==='filter').map(x=>x[1]),[true,false]);
    assert(stages.includes('filtered-submitted parts=2 decode-completion=unconfirmed'));
    assert.equal(fatal.length,0);
    assert(events.some(x=>x[0]==='fetch'&&x[1]==='/local/a%2Fb/part-0.rrd'));
  }
}
(async()=>{
  await run();await run({empty:true});await run({failSend:true});
  await run({failDuringYield:true});await run({busy:true});
  console.log('PASS paced channel submission: yields, failure stop, empty parts, duplicate import guard');
})().catch(error=>{console.error(error);process.exitCode=1;});
