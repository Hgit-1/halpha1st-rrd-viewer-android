/* Source-backed RRF2 loading. Source frames stay atomic and on disk. */
(function(root) {
  'use strict';
  const isCritical=item=>{const entity=String(item.entity||''), components=item.components||[];
    return /(^|\/)(diagnos(?:is|tic)?|debug|metric|telemetry|measurement|log|yolo|detect(?:ion)?|boxes|bbox|bounding[_-]?boxes|inference|prediction)(\/|$)/i.test(entity)
      ||entity==='/image/detection_boxes'
      ||components.some(name=>/(Text|TextLog|Scalar|Series|Metric|Status|Diagnostic|Diagnosis|Boxes2D|Boxes3D|Boxes2DIndicator|Boxes3DIndicator|Position2D|Position3D|HalfSize2D|HalfSize3D|ClassId|ClassDescription|Keypoint|Confidence|Label|Annotation)/i.test(name));
  };
  // The recording's detector is visibly delayed by about 399 ms relative to
  // /image/img. Do not let pressure-based shrinking hide the current YOLO
  // frame: these streams get a bounded 500 ms tail even when ordinary text
  // and scalar streams are reduced to 120 ms.
  const isDelayedOverlay=item=>{
    const entity=String(item.entity||''), components=item.components||[];
    return entity==='/image/detection_boxes'||entity==='/field/detection_points'
      ||/(Boxes2D|Boxes2DIndicator|HalfSize2D|Position2D|Confidence|ClassId)/i.test(components.join(' '));
  };
  function selectChunks(index, timeline, value, lookAhead = 0n, lookBehind = 0n) {
    const at=BigInt(value), start=at-lookBehind, end=at+lookAhead;
    const selected=new Map(), previous=new Map(), candidates=new Map();
    const streamKey=item=>String(item.entity||'')+'\0'+(item.components||[]).join('\0');
    const isImage=item=>String(item.entity||'')==='/image/img'
      ||(item.components||[]).some(name=>/^(?:rerun\.)?(?:components\.)?(?:Blob|EncodedImageIndicator)$/.test(name));
    for(const item of index) {
      if(!item.id)continue;
      const range=item.timelines[timeline];
      if(!range) {if(!Object.keys(item.timelines).length)selected.set(item.id,item);continue;}
      const lo=BigInt(range.lo),hi=BigInt(range.hi);
      const itemStart=isDelayedOverlay(item)
        ?at-(timeline==='time'?500000000n:500n):start;
      if(lo<=end && hi>=itemStart) {
        const key=streamKey(item), list=candidates.get(key)||[];
        list.push({item,distance:lo<=at&&at<=hi?0n:(lo>at?lo-at:at-hi)});candidates.set(key,list);
        continue;
      }
      if(hi<=at)for(const component of item.components||[]) {
        const key=streamKey(item)+'\0'+component, old=previous.get(key);
        if(!old || hi>old.time)previous.set(key,{time:hi,item,distance:at-hi});
      }
    }
    // Bound store queries per stream. Large TextLog/Scalar/geometry chunks can
    // tessellate thousands of rows in one frame; loading every overlapping
    // chunk defeats the sliding window and grows the WASM heap. Keep the
    // nearest ordinary chunk, and two adjacent image chunks for continuity.
    for(const list of candidates.values()) {
      // Keep one JPEG: the image visualizer decodes only the newest row. Keep
      // two adjacent critical chunks so TextLog/Scalar/YOLO history is not
      // silently dropped when a source chunk boundary crosses the window.
      const image=list.some(x=>isImage(x.item));
      const limit=image?2:(list.some(x=>isCritical(x.item))?2:1);
      list.sort((a,b)=>a.distance<b.distance?-1:a.distance>b.distance?1:
        isImage(a.item) ? (b.item.part-a.item.part||b.item.offset-a.item.offset)
                        : (a.item.part-b.item.part||a.item.offset-b.item.offset));
      for(const {item} of list.slice(0,limit))selected.set(item.id,item);
    }
    // Keep latest-at for sparse labels/diagnostics, but bound it by both
    // stream count and bytes so it cannot recreate recording-wide history.
    const fallback=[...previous.values()].sort((a,b)=>{
      const cp=Number(isCritical(b.item))-Number(isCritical(a.item));
      return cp!==0?cp:(a.distance<b.distance?-1:a.distance>b.distance?1:a.item.part-b.item.part);
    });
    let fallbackBytes=0;
    for(const {item} of fallback){
      const cap=isCritical(item)?2*1024*1024:1024*1024;
      if(fallbackBytes+item.length>cap || fallbackBytes>=cap)continue;
      selected.set(item.id,item);fallbackBytes+=item.length;
    }

    const priority=item=>{const range=item.timelines[timeline];
      const active=range&&BigInt(range.lo)<=end&&BigInt(range.hi)>=at;
      return (item.entity==='/image/img'?0:2)+(active?0:1);};
    return [...selected.values()].sort((a,b)=>priority(a)-priority(b)||a.part-b.part||a.offset-b.offset);
  }
  function groupParts(items) {
    // Images first, but fetch each bounded part only once even when components
    // and entities interleave in file order.
    const groups=new Map();
    for(const item of items){if(!groups.has(item.part))groups.set(item.part,[]);groups.get(item.part).push(item);}
    return [...groups].map(([part,frames])=>({part,frames:frames.sort((a,b)=>a.offset-b.offset)}));
  }
  function staleTarget(current,now,margin) {
    if(!now)return false;
    if(now.timeline!==current.timeline)return true;
    if(now.time===current.time)return false;
    if(!current.playing||current.dragging||!now.playing||now.dragging)return true;
    const delta=BigInt(now.time)-BigInt(current.time);
    return delta<0n||delta>margin;
  }
  function frameBytes(bytes,item) {
    const out=new Uint8Array(12+item.length+8);
    out.set(bytes.subarray(0,12));out.set(bytes.subarray(item.offset,item.offset+item.length),12);
    return out;
  }
  async function load(handle,id,count,options) {
    const {stage,failed}=options, channel='filtered-'+id;
    let worker;
    const index=[],seen=new Set(),bounds=new Map();
    const pending=new Map(),cache=new Map();
    let request=0,alive=true,lastDiagnostic=0,cacheBytes=0,protectedKey=null,lastReclaim=0;
    let playbackPumpStarted=false,playbackRestoreBusy=false,lastPlaybackTime=null,lastPlaybackLog=0;
    let restoreRunning=false,restorePending=null,restoreGeneration=0;
    const attachWorker=()=>{
      worker=new Worker('/source_worker.js'+location.search);
      worker.onmessage=({data})=>{const p=pending.get(data.request);if(!p)return;pending.delete(data.request);data.error?p.reject(Error(data.error)):p.resolve(data);};
      worker.onerror=event=>{for(const p of pending.values())p.reject(Error(event.message||'source worker failed'));pending.clear();stop();};
    };
    function stop() {
      if(!alive)return;
      alive=false;if(worker)worker.terminate();cache.clear();cacheBytes=0;
      const error=Object.assign(Error('source loader canceled'),{sourceCanceled:true});
      for(const p of pending.values())p.reject(error);pending.clear();
      handle.close_channel(channel);
    }
    if(root._sourceLoader)root._sourceLoader.stop();
    root._sourceLoader={stop,index};
    attachWorker();
    const fetchPart=(part,indexOnly=false)=>{
      if(!indexOnly&&cache.has(part)){const result=cache.get(part);cache.delete(part);cache.set(part,result);return Promise.resolve(result);}
      return new Promise((resolve,reject)=>{
        pending.set(++request,{resolve:result=>{
          if(!indexOnly&&result.bytes.length<=1024*1024){
            // Keep at most one bounded source part in the JS cache. A part
            // larger than this is intentionally not retained after submit.
            while(cacheBytes+result.bytes.length>1024*1024&&cache.size){const key=cache.keys().next().value;cacheBytes-=cache.get(key).bytes.length;cache.delete(key);}
            if(cacheBytes+result.bytes.length<=1024*1024){cache.set(part,result);cacheBytes+=result.bytes.length;}
          }
          resolve(result);
        },reject});worker.postMessage({request,indexOnly,url:`/local/${encodeURIComponent(id)}/part-${part}.rrd`});
      });
    };
    const yieldUI=()=>new Promise(resolve=>setTimeout(resolve,8));
    async function drain() {
      const start=performance.now();
      do {await yieldUI();
        if(!alive)throw Object.assign(Error('source loader canceled'),{sourceCanceled:true});
        if(failed())throw Error('Viewer failed; import stopped');
        if(performance.now()-start>30000)throw Error('Viewer receiver stalled for 30s');
      }while(handle.mobile_queue_len()>0);
    }
    const state=()=>{const json=handle.mobile_playback_state();return json?JSON.parse(json):null;};
    function remember(part,items) {
      for(const entry of items){
        if(!entry.id||seen.has(entry.id))continue;
        seen.add(entry.id);const item={...entry,part};index.push(item);
        for(const [name,r] of Object.entries(item.timelines)){
          const lo=BigInt(r.lo),hi=BigInt(r.hi),old=bounds.get(name);
          bounds.set(name,{lo:old&&old.lo<lo?old.lo:lo,hi:old&&old.hi>hi?old.hi:hi,sequence:r.sequence});
        }
      }
    }
    function setBounds(){for(const [name,r] of bounds)handle.mobile_source_bounds(name,r.sequence,String(r.lo),String(r.hi));}
    const publishedBounds=new Map();
    function publishBoundsForPlayback() {
      let changed=false;
      for(const [name,r] of bounds){const old=publishedBounds.get(name);if(!old||old.lo!==r.lo||old.hi!==r.hi){publishedBounds.set(name,{lo:r.lo,hi:r.hi});changed=true;}}
      if(changed)setBounds();
    }
    function isOverlayEntry(item) {
      const entity=String(item.entity||''), components=item.components||[];
      return /(^|\/)(yolo|detect(?:ion)?|box(?:es)?|bbox|bounding[_-]?boxes|inference|prediction)(\/|$)/i.test(entity)
        ||entity==='/image/detection_boxes'
        ||components.some(name=>/(Boxes2D|Boxes3D|Boxes2DIndicator|Boxes3DIndicator|Position2D|Position3D|HalfSize2D|HalfSize3D|ClassId|ClassDescription|Keypoint|Confidence|Label|Annotation)/i.test(name));
    }
    function isPlaybackDiagnostic(item) {
      const entity=String(item.entity||''), components=item.components||[];
      return /(^|\/)(diagnos(?:is|tic)?|debug|metric|telemetry|measurement|log)(\/|$)/i.test(entity)
        ||components.some(name=>/(^|\.)(Text|TextLog|Scalar|Series|Metric|Status|Diagnostic|Diagnosis)$/.test(name));
    }
    function isVisualEntry(item) {
      const entity=String(item.entity||''), components=item.components||[];
      const overlayEntity=/(^|\/)(yolo|detect(?:ion)?|detection_boxes|boxes|bbox|bounding_boxes)(\/|$)/i.test(entity)
        ||entity==='/image/detection_boxes';
      const overlayComponent=components.some(name=>/(Boxes2D|Boxes3D|Boxes2DIndicator|Boxes3DIndicator|Position2D|Position3D|HalfSize2D|HalfSize3D|ClassId|ClassDescription|Keypoint|Confidence|Label|Annotation)/i.test(name));
      // Image, geometry, and overlay streams may use a short forward runway.
      // Non-image streams are selected separately at the current time/latest-at.
      return overlayEntity||overlayComponent
        ||entity.startsWith('/image/')||entity.startsWith('/depth/')||entity.startsWith('/field/')
        ||components.some(name=>/(Blob|EncodedImage|Position2D|Position3D|Points2D|Points3D|Boxes2D|Boxes3D|Mesh3D|LineStrip|LineStrips|Arrows2D|TriangleIndices|HalfSize|PoseTranslation)/.test(name));
    }
    function isAtOrBefore(item,timeline,time) {
      const range=(item.timelines||{})[timeline];
      return !range||BigInt(range.lo)<=BigInt(time);
    }
    function progressiveTarget() {
      const first=new Map();
      for(const item of index){
        const components=item.components||[], entity=String(item.entity||''), range=item.timelines.time;
        if(!range)continue;
        let key=null;
        if(entity==='/image/img'&&components.some(name=>/EncodedImageIndicator/.test(name)))key='image';
        else if(entity==='/image/detection_boxes'&&components.some(name=>/Boxes2D|Boxes2DIndicator|Position2D|HalfSize2D/.test(name)))key='boxes';
        else if(entity==='/depth/depth_points'&&components.some(name=>/Points3D|Position3D/.test(name)))key='points';
        else if(entity==='/depth/grid_mesh'&&components.some(name=>/Mesh3D|TriangleIndices/.test(name)))key='mesh';
        else if(isOverlayEntry(item))key='overlay';
        if(!key)continue;
        const lo=BigInt(range.lo),old=first.get(key);
        if(old===undefined||lo<old)first.set(key,lo);
      }
      if(!first.has('image'))return null;
      // Show the first image as soon as it is indexed. When overlay starts are
      // known, advance to their first common window without waiting for the
      // remaining source parts to finish indexing.
      let target=first.get('image');
      for(const key of ['points','mesh','boxes','overlay'])if(first.has(key)&&first.get(key)>target)target=first.get(key);
      return {timeline:'time',time:String(target),playing:false,dragging:false};
    }
    async function progressiveRestore() {
      const target=progressiveTarget();
      if(!target)return false;
      setBounds();
      const ok=await restore(target,true,true);
      if(ok)stage(`source-progressive-ready time=${target.time}`);
      return ok;
    }
    async function playbackPump() {
      if(playbackPumpStarted)return;
      playbackPumpStarted=true;
      while(alive&&!failed()){
        const current=state();
        if(current&&current.timeline&&current.time
            &&(current.playing||current.dragging===false)
            &&current.time!==lastPlaybackTime){
          lastPlaybackTime=current.time;
          // Do not wait for a slow part read here. The next timer tick must be
          // allowed to supersede this job, otherwise the requested pointer
          // runs ahead while an old restore is still loading.
          restore(current,true,true).then(ok=>{
            if(ok&&performance.now()-lastPlaybackLog>500){
              lastPlaybackLog=performance.now();
              stage(`source-progressive-playback time=${current.time}`);
            }
          }).catch(error=>{
            if(!error.sourceCanceled)stage('source-playback-error '+String(error.stack||error));
          });
        }
        await new Promise(resolve=>setTimeout(resolve,current?.playing?60:120));
      }
    }
    function startPlaybackPump(){if(!playbackPumpStarted)playbackPump();}
    async function restoreImpl(current, progressive=false, visualOnly=false, generation=0) {
      const currentRestore=()=>generation===restoreGeneration&&alive;
      if(!currentRestore())return false;
      const timelineInfo=bounds.get(current.timeline), sequence=timelineInfo?.sequence;
      // Prefer forward preloading, but shrink the window before the next
      // decode allocation when the live WASM heap approaches the device
      // budget. Dragging is intentionally a hard reset: discard the old
      // window and restore only the newly selected time.
      const pressure=typeof root._sourceMemoryPressure==='function'
        ?root._sourceMemoryPressure():{live:0,linear:0,max:0};
      const high=pressure.live>96*1024*1024||pressure.linear>112*1024*1024;
      const critical=pressure.live>112*1024*1024||pressure.linear>128*1024*1024;
      const drag=!!current.dragging;
      const lookBehind=sequence?(drag?0n:(critical?120n:high?250n:500n))
        :(drag?0n:(critical?120000000n:high?250000000n:500000000n));
      const lookAhead=sequence?(drag?0n:(critical?500n:high?1000n:2500n))
        :(drag?0n:(critical?250000000n:high?600000000n:1000000000n));
      const margin=lookAhead;
      const start=performance.now();
      // For a paused target use only the target row/latest-at fallback. For
      // playback use a fixed sliding window; do not silently append a second
      // history policy for text streams.
      const allWanted=selectChunks(index,current.timeline,current.time,
        current.playing&&!current.dragging?lookAhead:0n,
        lookBehind);
      const delayedTail=current.timeline==='time'?500000000n:500n;
      const wanted=current.dragging?allWanted:selectChunks(index,current.timeline,current.time,
        current.playing&&!current.dragging?lookAhead:0n,
        lookBehind>delayedTail?lookBehind:delayedTail);
      const ids=wanted.map(x=>x.id).sort().join(',');
      // No unconditional full GC on every idle poll. On a new working set,
      // free old residency BEFORE loading the directly selected target.
      if(ids!==protectedKey){handle.mobile_protect_chunks(ids);handle.mobile_reclaim_source();protectedKey=ids;lastReclaim=performance.now();}
      else if(performance.now()-lastReclaim>250){
        // Re-run GC even when two adjacent timestamps select the same chunks;
        // this keeps the fixed window strict when a chunk contains many rows.
        handle.mobile_reclaim_source();lastReclaim=performance.now();
      }
      const missing=new Set(handle.mobile_missing_chunks(ids).split(','));
      const work=groupParts(wanted.filter(x=>missing.has(x.id)));let loaded=0;
      for(const group of work){
        if(!currentRestore())return false;
        if(!progressive&&staleTarget(current,state(),margin))return false;
        const {bytes}=await fetchPart(group.part);
        // Also cancel a paused seek after pointer release, and re-check after
        // I/O, not merely before it. Never submit intermediate windows.
        if(!currentRestore()||(!progressive&&staleTarget(current,state(),margin)))return false;
        for(const item of group.frames){
          if(!currentRestore())return false;
          // A reverse seek may overlap another restore while the part is being
          // fetched. Re-check the exact id immediately before insertion so a
          // chunk that became resident meanwhile is never submitted twice.
          const missingNow=handle.mobile_missing_chunks(item.id);
          if(missingNow&&missingNow.split(',').includes(item.id)){
            handle.send_rrd_frame_sync(channel,frameBytes(bytes,item));loaded++;
          }
        }
        await drain();
      }
      if(loaded)stage(`source-seek timeline=${current.timeline} time=${current.time} frames=${loaded} parts=${work.length} elapsed-ms=${Math.round(performance.now()-start)}`);
      return true;
    }
    function restore(current, progressive=false, visualOnly=false) {
      // Coalesce requests instead of queueing every playback tick. At 2x speed
      // a part read can outlive the 100 ms pump interval; replaying all old
      // timestamps would evict the current image and leave the canvas blank.
      return new Promise((resolve,reject)=>{
        if(restorePending)restorePending.resolve(false);
        const generation=++restoreGeneration;
        restorePending={current,progressive,visualOnly,generation,resolve,reject};
        drainRestores();
      });
    }
    async function drainRestores() {
      if(restoreRunning)return;
      restoreRunning=true;
      try {
        while(restorePending){
          const job=restorePending;restorePending=null;
          try {job.resolve(await restoreImpl(job.current,job.progressive,job.visualOnly,job.generation));}
          catch(error){job.reject(error);}
        }
      } finally {restoreRunning=false;}
    }
    handle.open_channel(channel,'source-backed recording');handle.enable_source_backing();
    try {
      stage('source-index-start parts='+count);
      // Parse all frame metadata in the Worker without submitting future rows
      // to the Viewer. Complete bounds are published once, so timeline geometry
      // cannot move underneath the user's pointer as import windows advance.
      // Activate a real recording immediately, before the metadata-only scan.
      // v74 left the Viewer on its welcome page for the whole scan, allowing
      // navigation away and making file selection appear to have failed.
      const first=await fetchPart(0);remember(0,first.index);
      handle.mobile_protect_chunks(first.index.filter(item=>item.id).map(item=>item.id).join(','));
      for(const item of first.index){
        handle.send_rrd_frame_sync(channel,frameBytes(first.bytes,item));
        await drain();
      }
      stage('source-bootstrap-complete part=1/'+count);
      // Keep Worker parsing bounded to a single source part at a time.
      let progressiveKey='', progressivePlaybackTime=null;
      for(let part=1;part<count;part++){
        if(!alive)throw Object.assign(Error('source loader canceled'),{sourceCanceled:true});
        if(failed())throw Error('Viewer failed; import stopped');
        const result=await fetchPart(part,true);remember(part,result.index);
        // Extend the viewer's currently known time range as each part is
        // indexed. Playback must not be clamped to the first four parts while
        // the remaining source index is still being built.
        const playbackState=state();
        const progressive=progressiveTarget();
        // Publish the growing upper bound after every indexed visual part.
        // Rerun otherwise clamps playback to the first bootstrap window, so
        // the UI can show the play button while its time never advances.
        if(progressive)publishBoundsForPlayback();
        if(progressive){
          const key=progressive.time;
          if(key!==progressiveKey){
            progressiveKey=key;
            await progressiveRestore();
            startPlaybackPump();
          }
        }
        // The viewer can start playing as soon as the first visual window is
        // submitted. Keep feeding the current playback position while the
        // remaining metadata is indexed; otherwise the scan monopolizes this
        // async task and the first JPEG remains frozen until all 319 parts end.
        const playback=state();
        if(playback&&playback.playing&&!playback.dragging&&bounds.has(playback.timeline)){
          const time=BigInt(playback.time),known=bounds.get(playback.timeline).hi;
          if(time<=known&&(progressivePlaybackTime===null||time-progressivePlaybackTime>=300000000n)){
            progressivePlaybackTime=time;
            await restore(playback,true,true);
            stage(`source-progressive-playback time=${playback.time}`);
          }
        }
        stage(`source-index-part=${part+1}/${count}`);
        // A playback fetch may still be in flight on this same Worker.
        // Terminating it loses its response and leaves restoreRunning (and
        // every subsequent playback restore) waiting forever. Defer recycling
        // until all requests have settled.
        if((part+1)%16===0&&pending.size===0){worker.terminate();attachWorker();}
        await yieldUI();
      }
      publishBoundsForPlayback();await drain();
      // Metadata-only recordings do not necessarily have a time-control query
      // yet. Seed from exact indexed bounds, never the resident window's end.
      let current=state();
      if(!current){const timeline=bounds.has('time')?'time':bounds.keys().next().value;
        if(timeline)current={timeline,time:String(bounds.get(timeline).lo),playing:false,dragging:false};}
      if(current)await restore(current,current.playing&&!current.dragging,current.playing&&!current.dragging);
      options.submitted?.();
      stage('source-index-complete parts='+count+' frames='+index.length+' loading=on-demand');
      while(alive&&!failed()) {
        current=state();
        if(current){
          // Playback has its own visual-only pump. Do not enqueue a full
          // recording restore every 80 ms while that pump is catching up.
          if(!current.playing||current.dragging){
            if(await restore(current)===false)continue;
          }
          if(performance.now()-lastDiagnostic>2000){lastDiagnostic=performance.now();stage('source-state '+JSON.stringify(current));}
        }
        await new Promise(resolve=>setTimeout(resolve,current?.dragging?16:80));
      }
    } finally {stop();}
  }
  root.RerunSource={selectChunks,groupParts,staleTarget,frameBytes,load};
  if(typeof module!=='undefined')module.exports=root.RerunSource;
})(typeof window==='undefined'?globalThis:window);
