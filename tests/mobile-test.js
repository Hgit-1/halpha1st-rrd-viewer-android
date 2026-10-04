'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const mobile = require('../assets/rerun/mobile.js');
const calls=[];
let queries=0;
const gl={texImage3D(){},getParameter(p){assert.equal(p,0x8CDF);queries++;return 4;},framebufferTexture2D(...a){calls.push(a);}};
mobile.framebufferTexture2D(gl,1,0x8CE4,2,null,0);
mobile.framebufferTexture2D(gl,1,0x8CE5,2,null,0);
assert.equal(calls.length,0);assert.equal(queries,1);
mobile.framebufferTexture2D(gl,1,0x8CE3,2,null,0);
mobile.framebufferTexture2D(gl,1,0x8CE4,2,{},0); // live attachment must not be masked
mobile.framebufferTexture2D(gl,1,0x821A,2,null,0); // depth/stencil unchanged
mobile.framebufferTexture2D(gl,1,123,2,null,0); // unknown enum unchanged
assert.equal(calls.length,4);assert.equal(mobile.skipped(),2);
const webgl1={framebufferTexture2D(...a){calls.push(a);}};
mobile.framebufferTexture2D(webgl1,1,0x8CE1,2,null,0);assert.equal(calls.length,4);
mobile.framebufferTexture2D(webgl1,1,0x8CE0,2,null,0);assert.equal(calls.length,5);
const html=fs.readFileSync('assets/rerun/index.html','utf8');
for(const [,script] of html.matchAll(/<script>([\s\S]*?)<\/script>/g)) new vm.Script(script);
assert(html.includes('wasm_bindgen({module_or_path: response})'));
assert(!html.includes('response.arrayBuffer()'));
const glue=fs.readFileSync('assets/rerun/re_viewer.js','utf8');new vm.Script(glue);
assert.equal((glue.match(/RerunMobile.framebufferTexture2D\(/g)||[]).length,2);
assert(glue.includes('const ret = arg0.devicePixelRatio;'));
assert(glue.includes('const ret = arg0.devicePixelContentBoxSize;'));
assert(!glue.includes('RerunMobile.pixelRatio'));
assert(!glue.includes('RerunMobile.displayBox'));
assert(!html.includes('touch_controls'));
assert(!html.includes('controls.js'));
assert(!html.includes('data-hint'));
assert(!html.includes('toggleCompactPanels'));
assert(!html.includes('override_panel_state'));
assert(!html.includes('116px'));
assert(/canvas\s*\{[^}]*height: 100%;/.test(html));
assert(!fs.existsSync('assets/rerun/controls.js'));
const java=fs.readFileSync('src/io/rerun/viewer/shell/MainActivity.java','utf8');
assert(java.includes('!"mobile.js".equals(name)'));
assert(java.includes('enableStaticSegmentation = true'));
assert(!java.includes('using whole file'));
assert(!java.includes('root.addView(importStatus'));
assert.equal(mobile.panicDetail({has_panicked:()=>false}),null);
assert.equal(mobile.panicDetail({has_panicked:()=>true,panic_message:()=>'<panic>',panic_callstack:()=>'stack'}),'<panic>\nstack');
assert.match(mobile.panicDetail({has_panicked(){throw Error('borrowed');}}),/inspection failed/);
const diagnosticStages=[];
global.RerunAndroid={stage:value=>diagnosticStages.push(value)};
const originalError=new Error('original async failure');
mobile.callbackError(originalError);
assert(diagnosticStages[0].includes('original async failure'));
delete global.RerunAndroid;
// Exercise the generated Rust closure wrapper, including its lifetime cleanup.
const closureSource=glue.slice(glue.indexOf('function makeMutClosure('),glue.indexOf('\nfunction ',glue.indexOf('function makeMutClosure(')+1));
const closureContext=vm.createContext({RerunMobile:mobile,CLOSURE_DTORS:{register(){},unregister(){}}});
vm.runInContext(closureSource,closureContext);
const callback=closureContext.makeMutClosure(7,9,0,()=>{throw originalError;});
assert.throws(()=>callback(),error=>error===originalError);
assert.equal(callback.original.a,7);assert.equal(callback.original.cnt,1);
assert(!html.includes('script-error-unresolved')); // no masking anonymous failures
assert(html.includes('RerunMobile.panicDetail'));
const metricsStart=html.indexOf('      function allocationMetrics()');
const metricsEnd=html.indexOf('      function reportFatal(',metricsStart);
const metricContext=vm.createContext({wasmExports:{memory:{buffer:{byteLength:9000}},
  mobile_live_allocated_bytes:()=>7000,
  mobile_failed_allocation_bytes:()=>262144,
  mobile_failed_allocation_alignment:()=>16}});
vm.runInContext(html.slice(metricsStart,metricsEnd),metricContext);
assert.equal(metricContext.allocationMetrics(),
  'wasm-linear-bytes=9000 wasm-live-bytes=7000 failed-alloc-bytes=262144 failed-alloc-align=16');
metricContext.wasmExports={memory:{buffer:{byteLength:123}}};
assert.equal(metricContext.allocationMetrics(),
  'wasm-linear-bytes=123 wasm-live-bytes=-1 failed-alloc-bytes=-1 failed-alloc-align=-1');
console.log('PASS mobile helpers, JS syntax, loading guards and callback/panic diagnostics');
