'use strict';
const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
(async()=>{
 let reads=0,analyzed=0;const messages=[],self={postMessage:(data)=>messages.push(data)};
 const context={self,Map,Uint8Array,Error,RerunFilter:{analyzeRrd(bytes){analyzed++;assert.equal(bytes.length,20);return [{metadata:true}];}},importScripts(){},
 fetch:async()=>({ok:true,body:{getReader(){return {async read(){return ++reads%2?{done:false,value:new Uint8Array(20)}:{done:true};},releaseLock(){},async cancel(){}};}}})};
 vm.runInNewContext(fs.readFileSync('assets/rerun/source_worker.js','utf8'),context);
 await self.onmessage({data:{request:1,url:'/part-1',indexOnly:true}});
 assert.equal(messages[0].bytes,undefined);assert.equal(messages[0].size,20);
 await self.onmessage({data:{request:2,url:'/part-1',indexOnly:false}});
 assert.equal(analyzed,2,'one-shot index-only results do not persist duplicate Worker metadata');
 assert.equal(messages[1].bytes.length,20);
 const source=fs.readFileSync('src/io/rerun/viewer/shell/MainActivity.java','utf8');
 assert(source.includes('shouldOverrideUrlLoading(WebView view, String url)'));
 assert(!source.includes('blocked external WebView navigation'));
 assert(source.includes('page-start url='));
 assert(source.includes('Never silently discard a selected recording'));
 console.log('PASS worker index-only ownership, full response, renderer failure diagnostics');
})().catch(e=>{console.error(e);process.exitCode=1;});
