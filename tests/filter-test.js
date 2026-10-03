'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const context = vm.createContext({TextDecoder, TextEncoder, console, Uint8Array, ArrayBuffer,
  Int8Array, Int16Array, Int32Array, Uint16Array, Uint32Array, Float32Array, Float64Array,
  BigInt64Array, BigUint64Array, DataView, setTimeout, clearTimeout});
vm.runInContext(fs.readFileSync('assets/rerun/rrd_filter.js','utf8'), context);
const {lz4Decode,lz4Literal,inspectRrd} = context.RerunFilter;
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
console.log('PASS bundled filter: overlapping LZ4 matches, literals, malformed payload');
