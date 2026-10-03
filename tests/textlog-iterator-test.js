'use strict';
const fs = require('fs');
const assert = require('assert');
const module_ = new WebAssembly.Module(fs.readFileSync(process.argv[2]));
const imports = {};
for (const item of WebAssembly.Module.imports(module_)) {
  assert.equal(item.kind, 'function', 'Unexpected non-function import');
  (imports[item.module] ??= {})[item.name] = () => {
    throw new Error(`Unexpected runtime import: ${item.module}.${item.name}`);
  };
}
const exports_ = new WebAssembly.Instance(module_, imports).exports;
assert.equal(exports_.test_iterator(), 0, 'Patched Chunk iterator regression');
console.log('PASS actual wasm32 Chunk iterator: static/dense/sparse rows, empty batches, 0/1/3/5/12 timelines, exhaustion, old-iterator equivalence, Arc sharing');
console.log('Scope: synthetic Chunk execution in Node, not Android Viewer playback.');
