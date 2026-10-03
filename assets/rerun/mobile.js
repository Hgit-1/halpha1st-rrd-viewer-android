/* Small, testable mobile boundary helpers. Does not suppress real GL errors. */
(function (root) {
  'use strict';
  const limits = new WeakMap();
  let skipped = 0;
  function framebufferTexture2D(gl, target, attachment, textarget, texture, level) {
    // Some GLES backends detach every desktop color slot, including slots beyond
    // this device's MAX_COLOR_ATTACHMENTS. Only skip such null-texture detaches.
    // Never rewrite a live attachment, a depth/stencil enum, or a texture target.
    if (texture == null && attachment >= 0x8CE0 && attachment <= 0x8CFF) {
      let max = limits.get(gl);
      if (max === undefined) {
        max = typeof gl.texImage3D === 'function' ? gl.getParameter(0x8CDF) : 1;
        if (!Number.isInteger(max) || max < 1) max = 32; // uncertain: forward unchanged
        limits.set(gl, max);
      }
      if (attachment - 0x8CE0 >= max) {
        if (skipped++ === 0) console.warn('mobile-gl: skipped unsupported null color detach attachment=' + attachment + ' max=' + max);
        return;
      }
    }
    gl.framebufferTexture2D(target, attachment, textarget, texture, level);
  }
  function panicDetail(handle) {
    try {
      if (!handle || !handle.has_panicked()) return null;
      return (handle.panic_message() || 'unknown WASM panic') + '\n'
        + (handle.panic_callstack ? handle.panic_callstack() || '' : '');
    } catch (error) {
      return 'panic inspection failed: ' + String(error);
    }
  }
  function callbackError(error) {
    // Observe before WebView masks an asynchronous exception as "Script error".
    // The caller must rethrow the original exception, never suppress it.
    try {
      const detail = error && error.stack ? error.stack : String(error);
      if (root.RerunAndroid) root.RerunAndroid.stage('wasm-callback-error:' + detail.slice(0, 1800));
    } catch (_) { /* Diagnostics must not replace the original exception. */ }
  }
  const api = { framebufferTexture2D, skipped: () => skipped, panicDetail, callbackError };
  root.RerunMobile = api;
  if (typeof module !== 'undefined') module.exports = api;
})(typeof window !== 'undefined' ? window : globalThis);
