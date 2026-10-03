'use strict';
const fs=require('node:fs'), vm=require('node:vm'), assert=require('node:assert/strict');
const {createHash}=require('node:crypto');
const context=vm.createContext({TextDecoder,TextEncoder,console,setTimeout,clearTimeout});
vm.runInContext(fs.readFileSync('assets/rerun/rrd_filter.js','utf8'),context);
const api=context.RerunFilter;
const [file,oracleFile]=process.argv.slice(2);
const rows=JSON.parse(fs.readFileSync(oracleFile,'utf8'));
const fd=fs.openSync(file,'r');
function readAt(offset,n){const b=Buffer.alloc(n);assert.equal(fs.readSync(fd,b,0,n,offset),n);return b;}
let verified=0;
for(const [offset,enc,unc,hash] of rows){
  const b=readAt(offset+8,enc);
  context.input=Uint8Array.from(b);context.size=unc;
  // Convert to the bundle's realm for instanceof checks.
  const raw=vm.runInContext('RerunFilter.lz4Decode(new Uint8Array(input),size)',context);
  assert.equal(createHash('sha256').update(raw).digest('hex'),hash,`frame ${verified}`);
  verified++;
}
const header=readAt(0,12), metadata=readAt(rows[0][0],rows[0][1]+8);
let pending=[],encoded=header.length+metadata.length,uncompressed=rows[0][2];
let parts=0,imageParts=0;
function flush(){
  if(!pending.length)return;
  const input=Buffer.concat([header,metadata,...pending,Buffer.alloc(8)]);
  context.input=input;
  const before=vm.runInContext('RerunFilter.inspectRrd(new Uint8Array(input))',context);
  context.lo=before.image ? before.image.lo-2e9 : NaN;
  context.hi=before.image ? before.image.hi+2e9 : NaN;context.include=parts===0;
  const output=vm.runInContext('RerunFilter.transformRrd(new Uint8Array(input),lo,hi,include)',context);
  if(!before.image){
    const expected=parts===0 ? input : Buffer.concat([header,...pending,Buffer.alloc(8)]);
    assert.deepEqual(Buffer.from(output),expected,`part ${parts}: unchanged frames re-encoded`);
  }
  if(before.image){
    context.output=output;
    const after=vm.runInContext('RerunFilter.inspectRrd(output)',context);
    assert(after.image,`part ${parts}: images lost`);
    assert.equal(after.image.lo,before.image.lo);
    assert.equal(after.image.hi,before.image.hi);
    imageParts++;
  }
  parts++;pending=[];encoded=12+metadata.length;uncompressed=rows[0][2];
}
for(const [offset,enc,unc] of rows.slice(1)){
  if(pending.length&&(encoded+enc+8>1024*1024||uncompressed+unc>4*1024*1024))flush();
  pending.push(readAt(offset,enc+8));encoded+=enc+8;uncompressed+=unc;
}
flush();fs.closeSync(fd);
console.log(`PASS native LZ4 oracle: ${verified} frames; real 1MiB segmentation: ${parts} parts, ${imageParts} image-bearing parts retained`);
