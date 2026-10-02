'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const mobile = require('../assets/rerun/mobile.js');
assert.equal(mobile.pixelRatio(3),1);
assert.equal(mobile.pixelRatio(0.75),0.75);
assert.equal(mobile.pixelRatio(NaN),1);
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
assert(glue.includes('RerunMobile.pixelRatio(arg0.devicePixelRatio)'));
const java=fs.readFileSync('src/io/rerun/viewer/shell/MainActivity.java','utf8');
assert(java.includes('!"mobile.js".equals(name)'));
assert(java.includes('enableStaticSegmentation = true'));
assert(!java.includes('using whole file'));
assert(!java.includes('root.addView(importStatus'));
console.log('PASS mobile helpers, JS syntax and loading guard checks');
