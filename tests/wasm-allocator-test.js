'use strict';
// Uses the shipped binary, not a substitute allocator. This is a Node runtime
// check only: it does NOT establish WebView, GPU, decoder, or playback safety.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const moduleBytes = fs.readFileSync(path.join(__dirname, '../assets/rerun/re_viewer_bg.wasm'));
const mod = new WebAssembly.Module(moduleBytes);
function instantiate() {
  const imports = {};
  for (const entry of WebAssembly.Module.imports(mod)) {
    assert.equal(entry.kind, 'function', 'unexpected imported memory/table');
    (imports[entry.module] ||= {})[entry.name] = () => {
      throw Error('Unexpected host call in allocator test: ' + entry.name);
    };
  }
  // No bindgen start or UI startup: only the exported allocation functions.
  return new WebAssembly.Instance(mod, imports).exports;
}
const MiB = 1024 * 1024;
{
  const e = instantiate();
  const allocated = [];
  try {
    for (let i = 0; i < 144; i++) {
      const p = e.__wbindgen_malloc(MiB, 1) >>> 0;
      assert(p > 0);
      new Uint8Array(e.memory.buffer, p, MiB).fill(i + 1);
      allocated.push(p);
    }
    // Refresh views after growth (the old ArrayBuffer may be detached).
    for (let i = 0; i < allocated.length; i++) {
      const view = new Uint8Array(e.memory.buffer, allocated[i], MiB);
      assert.equal(view[0], i + 1);
      assert.equal(view[MiB - 1], i + 1);
    }
    assert(e.memory.buffer.byteLength > 107216896);
    console.log('PASS shipped WASM contiguous allocations: live=144MiB linear=' + e.memory.buffer.byteLength);
  } finally {
    for (const p of allocated) e.__wbindgen_free(p, MiB, 1);
  }
}
{
  const e = instantiate();
  let seed = 3, liveBytes = 0;
  const live = [];
  function random() { seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0; return seed; }
  function checkAndFree(index) {
    const {p, size, align, value} = live[index];
    const view = new Uint8Array(e.memory.buffer, p, size);
    assert.equal(view[0], value, 'allocation contents changed');
    assert.equal(view[size - 1], value, 'allocation end changed');
    e.__wbindgen_free(p, size, align);
    liveBytes -= size;
    live[index] = live[live.length - 1];
    live.pop();
  }
  for (let i = 0; i < 50000; i++) {
    if (live.length && (liveBytes > 32 * MiB || random() % 3 === 0)) {
      checkAndFree(random() % live.length);
    } else {
      const size = 1 + random() % (random() % 10 === 0 ? 4 * MiB : 16384);
      const align = 1 << (random() % 5), value = i % 251;
      const p = e.__wbindgen_malloc(size, align) >>> 0;
      assert(p > 0);
      assert.equal(p % align, 0);
      new Uint8Array(e.memory.buffer, p, size).fill(value);
      live.push({p, size, align, value});
      liveBytes += size;
    }
  }
  while (live.length) checkAndFree(live.length - 1);
  assert.equal(liveBytes, 0);
  console.log('PASS shipped WASM allocation churn: 50000 operations; linear=' + e.memory.buffer.byteLength);
}
console.log('Scope: Node allocator only; Android rendering and full playback remain unverified.');
