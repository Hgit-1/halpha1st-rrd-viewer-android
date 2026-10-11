'use strict';
// Execute the actual Worker handler on all 319 bounded parts, not a fake index.
const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const [file,oracleFile]=process.argv.slice(2),rows=JSON.parse(fs.readFileSync(oracleFile)),fd=fs.openSync(file,'r');
const read=(offset,n)=>{const b=Buffer.alloc(n);assert.equal(fs.readSync(fd,b,0,n,offset),n);return b;};
const header=read(0,12),metadata=read(rows[0][0],rows[0][1]+8),ids=new Set();
let input,result,context,parts=0,frames=0,restarts=0;
function boot(){restarts++;context=vm.createContext({TextDecoder,TextEncoder,console,setTimeout,clearTimeout,
 self:{postMessage:data=>{result=data;}},importScripts:()=>{},fetch:async()=>({ok:true,body:{getReader(){let sent=false;return {async read(){if(sent)return {done:true};sent=true;return {done:false,value:input};},releaseLock(){},async cancel(){}};}}})});
 vm.runInContext(fs.readFileSync('assets/rerun/rrd_filter.js','utf8'),context);
 vm.runInContext(fs.readFileSync('assets/rerun/source_worker.js','utf8'),context);
}
(async()=>{boot();let pending=[],encoded=12+metadata.length,unc=rows[0][2];
 async function flush(){if(!pending.length)return;
 input=Buffer.concat([header,metadata,...pending,Buffer.alloc(8)]);result=null;
 await vm.runInContext(`self.onmessage({data:{request:${parts},url:'fixture-${parts}',indexOnly:${parts!==0}}})`,context);
 assert(result&&!result.error,result?.error||'Worker did not respond');
 if(parts!==0)assert.equal(result.bytes,undefined,'scan must not transfer source bytes');
 for(const item of result.index){if(item.id){assert(!ids.has(item.id),'duplicate original ID');ids.add(item.id);frames++;
 for(const r of Object.values(item.timelines)){assert.equal(typeof r.lo,'string');assert(BigInt(r.lo)<=BigInt(r.hi));}}}
 parts++;pending=[];encoded=12+metadata.length;unc=rows[0][2];
 if(parts%16===0)boot();
 }
 for(const [offset,enc,size] of rows.slice(1)){
  if(pending.length&&(encoded+enc+8>1024*1024||unc+size>4*1024*1024))await flush();
  pending.push(read(offset,enc+8));encoded+=enc+8;unc+=size;
 }
 await flush();assert.equal(parts,319);assert.equal(frames,1382);assert.equal(restarts,20);
 console.log(`PASS actual Worker full index: ${parts} parts, ${frames} original chunks, exact times, ${restarts} bounded Worker lifetimes`);
})().catch(e=>{console.error(e);process.exitCode=1;}).finally(()=>fs.closeSync(fd));
