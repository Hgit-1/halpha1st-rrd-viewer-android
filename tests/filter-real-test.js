'use strict';
const fs=require('node:fs'), vm=require('node:vm'), assert=require('node:assert/strict');
const {createHash}=require('node:crypto');
const context=vm.createContext({TextDecoder,TextEncoder,console,setTimeout,clearTimeout});
vm.runInContext(fs.readFileSync(process.env.RERUN_FILTER_BUNDLE || 'assets/rerun/rrd_filter.js','utf8'),context);
const api=context.RerunFilter;
let native;
if(process.env.RERUN_NATIVE_DECODE_JS) {
  native=require(require('node:path').resolve(process.env.RERUN_NATIVE_DECODE_JS)).__wasm;
} else if(process.env.RERUN_NATIVE_DECODE_WASM) {
  const mod=new WebAssembly.Module(fs.readFileSync(process.env.RERUN_NATIVE_DECODE_WASM));
  const imports={};
  for(const entry of WebAssembly.Module.imports(mod)) {
    assert.equal(entry.kind,'function');
    (imports[entry.module] ||= {})[entry.name]=()=>{throw Error('Unexpected native decoder host call: '+entry.name);};
  }
  native=new WebAssembly.Instance(mod,imports).exports;
}
let nativeMessages=0,nativeRows=0;
let originalNativeRows=0,submittedNativeRows=0,submittedFrames=0;
const submittedHash=createHash('sha256'),originalHash=createHash('sha256');
function checkNative(raw,label) {
  if(!native)return;
  const p=native.probe_alloc(raw.length);
  try {
    new Uint8Array(native.memory.buffer,p,raw.length).set(raw);
    const rows=native.probe_decode(p,raw.length);
    assert(rows>=0,`${label}: Rerun serde/Arrow/Chunk rejected frame, code=${rows}`);
    nativeMessages++;nativeRows+=rows;
    return rows;
  } finally {native.probe_free(p,raw.length);}
}
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
  originalNativeRows+=checkNative(raw,`original frame ${verified}`)||0;
  if(verified>0)originalHash.update(readAt(offset,enc+8));
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
  // Reassemble the paced streams and compare every encoded frame with the
  // established whole-part transform. This covers all Arrow data, IDs, row
  // buffers, image blobs and non-image entities, not only image time bounds.
  const mini=vm.runInContext('Array.from(RerunFilter.transformRrdParts(new Uint8Array(input),lo,hi,include))',context);
  for(const one of mini){
    context.one=one;
    assert.equal(vm.runInContext('RerunFilter.inspectRrd(one).frames',context),1);
    assert.deepEqual(Buffer.from(one.subarray(0,12)),header);
    assert.deepEqual(Buffer.from(one.subarray(one.length-8)),Buffer.alloc(8));
    const enc=Buffer.from(one).readUInt32LE(12),unc=Buffer.from(one).readUInt32LE(16);
    const raw=api.lz4Decode(one.subarray(20,20+enc),unc);
    const encoded=Buffer.from(one.subarray(12,one.length-8));
    const isMetadata=parts===0 && submittedFrames===0;
    if(!isMetadata)submittedHash.update(encoded);
    submittedFrames++;
    submittedNativeRows+=checkNative(raw,`submitted part ${parts}`)||0;
  }
  const rejoined=Buffer.concat([header,...mini.map(one=>Buffer.from(one.subarray(12,one.length-8))),Buffer.alloc(8)]);
  assert.deepEqual(rejoined,Buffer.from(output),`part ${parts}: paced frames altered chunk data`);
  const expected=parts===0 ? input : Buffer.concat([header,...pending,Buffer.alloc(8)]);
  assert.deepEqual(Buffer.from(output),expected,`part ${parts}: source frame changed or discarded`);
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
assert.equal(submittedFrames,rows.length,'source frame lost or duplicated');
assert.equal(submittedHash.digest('hex'),originalHash.digest('hex'),'encoded frame order/content changed');
if(native)assert.equal(submittedNativeRows,originalNativeRows,'native decoded row count changed');
console.log(`PASS complete original frame retention: ${submittedFrames} frames; every encoded byte in original order`);
console.log(`PASS native LZ4 oracle: ${verified} frames; real 1MiB segmentation: ${parts} parts, ${imageParts} image-bearing parts retained`);
if(native)console.log(`PASS actual Rerun Rust serde/Arrow/Chunk decoder: ${nativeMessages} original+filtered messages, ${nativeRows} rows`);
