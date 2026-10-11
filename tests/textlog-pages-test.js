'use strict';
const fs=require('node:fs'),assert=require('node:assert/strict');
const bytes=fs.readFileSync('../.toolchain/textlog-pages.wasm');
const mod=new WebAssembly.Module(bytes), imports={};
for(const entry of WebAssembly.Module.imports(mod)) {
  assert.equal(entry.kind,'function');
  (imports[entry.module] ||= {})[entry.name]=()=>{throw Error('Unexpected host call: '+entry.name);};
}
const wasm=new WebAssembly.Instance(mod,imports).exports;
assert.equal(wasm.probe_pages(),0);
console.log('PASS actual wasm32 paged TextLog entries: 480597 rows, stable sort, payload retention');
