'use strict';
const fs = require('fs');
const assert = require('assert');
const path = require('path');
const wasm = path.resolve(__dirname, '../../.toolchain/textlog-sort.wasm');
assert(fs.existsSync(wasm), `missing ${wasm}; compile tests/textlog-sort.rs first`);
const module_ = new WebAssembly.Module(fs.readFileSync(wasm));
const imports = {};
for (const item of WebAssembly.Module.imports(module_)) {
  assert.equal(item.kind, 'function');
  (imports[item.module] ??= {})[item.name] = () => { throw new Error(`unexpected import ${item.name}`); };
}
const exports_ = new WebAssembly.Instance(module_, imports).exports;
assert.equal(exports_.probe_sort(), 0);
console.log('PASS actual wasm32 compact stable TextLog sort');
