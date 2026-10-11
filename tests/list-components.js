'use strict';
// Inspect the same bounded parts as Android; never decode the whole recording.
const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const [file='/sdcard/Download/977.rrd',oracleFile='../.toolchain/recording-probe/oracle.json',output='../.toolchain/source-index.json']=process.argv.slice(2);
const rows=JSON.parse(fs.readFileSync(oracleFile)),fd=fs.openSync(file,'r');
const read=(offset,n)=>{const b=Buffer.alloc(n);assert.equal(fs.readSync(fd,b,0,n,offset),n);return b;};
const header=read(0,12),metadata=read(rows[0][0],rows[0][1]+8);
let input,result,context,part=0;const index=[];
function boot(){context=vm.createContext({TextDecoder,TextEncoder,console,setTimeout,clearTimeout,
 self:{postMessage:data=>{result=data;}},importScripts:()=>{},fetch:async()=>({ok:true,body:{getReader(){let sent=false;return {async read(){if(sent)return {done:true};sent=true;return {done:false,value:input};},releaseLock(){},async cancel(){}};}}})});
 vm.runInContext(fs.readFileSync('assets/rerun/rrd_filter.js','utf8'),context);
 vm.runInContext(fs.readFileSync('assets/rerun/source_worker.js','utf8'),context);
}
(async()=>{boot();let pending=[],encoded=12+metadata.length,unc=rows[0][2];
 async function flush(){if(!pending.length)return;
  input=Buffer.concat([header,metadata,...pending,Buffer.alloc(8)]);result=null;
  await vm.runInContext(`self.onmessage({data:{request:${part},url:'fixture-${part}',indexOnly:true}})`,context);
  assert(result&&!result.error,result?.error||'Worker did not respond');
  index.push(...result.index.filter(x=>x.id).map(x=>({...x,part})));
  part++;pending=[];encoded=12+metadata.length;unc=rows[0][2];if(part%16===0)boot();
 }
 for(const [offset,enc,size] of rows.slice(1)){
  if(pending.length&&(encoded+enc+8>1024*1024||unc+size>4*1024*1024))await flush();
  pending.push(read(offset,enc+8));encoded+=enc+8;unc+=size;
 }
 await flush();fs.writeFileSync(output,JSON.stringify(index));
 const entities=new Map();for(const x of index){const key=x.entity+'|'+x.components.join(',');
  const o=entities.get(key)||{entity:x.entity,components:x.components,n:0,maxSpan:0n};o.n++;
  const r=x.timelines.time;if(r){const span=BigInt(r.hi)-BigInt(r.lo);if(span>o.maxSpan)o.maxSpan=span;}
  entities.set(key,o);
 }
 for(const o of [...entities.values()].sort((a,b)=>b.n-a.n))console.log(o.n,o.entity,'max-span-ms='+Number(o.maxSpan/1000000n),JSON.stringify(o.components));
 console.log('parts='+part,'chunks='+index.length,'index='+output);
})().catch(e=>{console.error(e);process.exitCode=1;}).finally(()=>fs.closeSync(fd));
