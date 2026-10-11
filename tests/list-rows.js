'use strict';
const fs=require('fs'),vm=require('vm'),assert=require('assert/strict');
const file='/sdcard/Download/977.rrd', rows=JSON.parse(fs.readFileSync('.toolchain/recording-probe/oracle.json')),fd=fs.openSync(file),header=Buffer.alloc(12);fs.readSync(fd,header,0,12,0);
const ctx=vm.createContext({TextDecoder,TextEncoder,console});vm.runInContext(fs.readFileSync('RerunViewer/assets/rerun/rrd_filter.js','utf8'),ctx);
const out=[];for(let i=0;i<rows.length;i++){let [off,e,u]=rows[i],b=Buffer.alloc(e+8);fs.readSync(fd,b,0,e+8,off);try{let r=ctx.RerunFilter.analyzeRrd(Buffer.concat([header,b,Buffer.alloc(8)]));out.push({...r[0],part:i});}catch(err){console.error('row',i,off,e,u,err.message);throw err;}}
for(const x of out){if(!x.id)continue;console.log(x.part,x.entity,x.timelines.time?.lo,x.timelines.time?.hi,JSON.stringify(x.components));}
