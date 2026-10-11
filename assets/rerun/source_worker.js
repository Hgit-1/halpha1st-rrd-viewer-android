/* Runs LZ4/MessagePack/Arrow inspection away from the UI thread. */
importScripts('rrd_filter.js');
const indexedParts = new Map();
self.onmessage = async ({data}) => {
  try {
    const response = await fetch(data.url, {cache:'no-store'});
    if (!response.ok) throw Error(`HTTP ${response.status}`);
    const reader=response.body.getReader();const chunks=[];let size=0;
    try {for (;;) {const next=await reader.read();if(next.done)break;
      size+=next.value.length;if(size>8*1024*1024)throw Error('part exceeds 8MiB');chunks.push(next.value);
    }} catch(error) {await reader.cancel();throw error;} finally {reader.releaseLock();}
    const bytes=new Uint8Array(size);let at=0;
    for(const chunk of chunks){bytes.set(chunk,at);at+=chunk.length;}
    let index = indexedParts.get(data.url);
    if (!index) index=RerunFilter.analyzeRrd(bytes);
    // Index-only requests are one-shot during the full scan. Do not retain
    // 319 metadata arrays in the Worker cache while Android is under memory
    // pressure; the main thread owns its compact combined index instead.
    if (!data.indexOnly) indexedParts.set(data.url,index);
    if (data.indexOnly) self.postMessage({request:data.request,index,size});
    else self.postMessage({request:data.request,bytes,index},[bytes.buffer]);
  } catch(error){self.postMessage({request:data.request,error:String(error.stack||error)});}
};
