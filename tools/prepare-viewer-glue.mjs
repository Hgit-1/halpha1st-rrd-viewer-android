import fs from 'node:fs';
import vm from 'node:vm';
// Transform the matching no-modules-base wasm-bindgen output for the Android shell.
// Fail closed if an upstream binding change would drop a compatibility/diagnostic hook.
const [input, output] = process.argv.slice(2);
if (!input || !output) throw new Error('Usage: node prepare-viewer-glue.mjs INPUT OUTPUT');
let code = fs.readFileSync(input, 'utf8');
function replaceExact(old, replacement, count = 1) {
  if (code.split(old).length - 1 !== count) throw new Error(`Unexpected occurrence count: ${old}`);
  code = code.split(old).join(replacement);
}
replaceExact('let wasm_bindgen;\n(function() {', '(function() {\nlet wasm_bindgen;');
replaceExact('wasm_bindgen = Object.assign(__wbg_init, { initSync }, __exports);', `function deinit() {
  __wbg_init.__wbindgen_wasm_module = null;
  wasm = null;
  cachedFloat32ArrayMemory0 = null;
  cachedInt32ArrayMemory0 = null;
  cachedUint32ArrayMemory0 = null;
  cachedUint8ArrayMemory0 = null;
}
wasm_bindgen = Object.assign(__wbg_init, { initSync, deinit }, __exports);
window.wasm_bindgen = wasm_bindgen;`);
replaceExact('    wasm.__wbindgen_export_3.get(state.dtor)(state.a, state.b)\n});', '    wasm?.__wbindgen_export_3.get(state.dtor)(state.a, state.b)\n});');
replaceExact('            return f(a, state.b, ...args);\n        } finally {', `            return f(a, state.b, ...args);
        } catch (error) {
            if (typeof RerunMobile !== "undefined" && RerunMobile.callbackError) RerunMobile.callbackError(error);
            throw error;
        } finally {`);
replaceExact('arg0.framebufferTexture2D(arg1 >>> 0, arg2 >>> 0, arg3 >>> 0, arg4, arg5);', 'RerunMobile.framebufferTexture2D(arg0, arg1 >>> 0, arg2 >>> 0, arg3 >>> 0, arg4, arg5);', 2);
new vm.Script(code);
fs.writeFileSync(output, code);
console.log('Prepared classic Viewer glue with framebuffer guard, callback diagnostics and deinit');
