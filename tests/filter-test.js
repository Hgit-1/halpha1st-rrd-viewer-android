'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const context = vm.createContext({TextDecoder, TextEncoder, console, Uint8Array, ArrayBuffer,
  Int8Array, Int16Array, Int32Array, Uint16Array, Uint32Array, Float32Array, Float64Array,
  BigInt64Array, BigUint64Array, DataView, setTimeout, clearTimeout});
vm.runInContext(fs.readFileSync('assets/rerun/rrd_filter.js','utf8'), context);
const {lz4Decode,lz4Literal,inspectRrd,transformRrd} = context.RerunFilter;
// Matches overlap: offset 1 must repeat the last byte, not read one byte ahead.
assert.equal(Buffer.from(lz4Decode(Uint8Array.from([0x13,97,1,0,0x50,98,99,100,101,102]),13)).toString(), 'aaaaaaaabcdef');
assert.equal(Buffer.from(lz4Decode(Uint8Array.from([0x44,97,98,99,100,4,0,0x50,49,50,51,52,53]),17)).toString(), 'abcdabcdabcd12345');
for (const n of [0,1,14,15,16,269,270,271,65536]) {
  const input=Uint8Array.from({length:n},(_,i)=>i%251);
  assert.deepEqual(Buffer.from(lz4Decode(lz4Literal(input),n)),Buffer.from(input));
}
assert.throws(()=>lz4Decode(Uint8Array.from([0,0,0]),4));
assert.throws(()=>lz4Decode(Uint8Array.from([0x20,1]),2));
// Malformed MessagePack must not be silently treated as a non-image frame.
const header=Buffer.from([82,82,70,50,0,19,0,0,1,1,0,0]);
const payload=lz4Literal(Uint8Array.from([0xd9]));
const fh=Buffer.alloc(8); fh.writeUInt32LE(payload.length);fh.writeUInt32LE(1,4);
assert.throws(()=>inspectRrd(Buffer.concat([header,fh,Buffer.from(payload),fh,Buffer.from(payload),Buffer.alloc(8)])));
// A compact LZ4 frame containing a MessagePack binary value with repeated bytes.
// The first frame is deliberately NOT SetStoreInfo: dropping it would lose data.
const compressed=Buffer.from([0x34,0xc4,14,97,1,0,0x50,98,99,100,101,102]);
const frameHeader=Buffer.alloc(8);frameHeader.writeUInt32LE(compressed.length);frameHeader.writeUInt32LE(16,4);
const original=Buffer.concat([header,frameHeader,compressed,Buffer.alloc(8)]);
for(const includeMetadata of [true,false]) {
  assert.deepEqual(Buffer.from(transformRrd(original,NaN,NaN,includeMetadata)),original);
  assert.deepEqual(Buffer.from(transformRrd(original,0,1,includeMetadata)),original);
}
// A SetStoreInfo map is removable without rewriting subsequent compressed frames.
const metadataRaw=Buffer.concat([Buffer.from([0x81,0xac]),Buffer.from('SetStoreInfo'),Buffer.from([0xc0])]);
const metadata=lz4Literal(metadataRaw),mh=Buffer.alloc(8);mh.writeUInt32LE(metadata.length);mh.writeUInt32LE(metadataRaw.length,4);
const withMetadata=Buffer.concat([header,mh,metadata,frameHeader,compressed,Buffer.alloc(8)]);
assert.deepEqual(Buffer.from(transformRrd(withMetadata,NaN,NaN,false)),original);
assert.throws(()=>inspectRrd(original.subarray(0,original.length-1)),/terminator/);
console.log('PASS bundled filter: LZ4, malformed payloads, compressed-frame preservation, metadata handling');
