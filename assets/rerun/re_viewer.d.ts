declare namespace wasm_bindgen {
	/* tslint:disable */
	/* eslint-disable */
	/**
	* @returns {number}
	*/
	export function mobile_live_allocated_bytes(): number;
	/**
	* @returns {number}
	*/
	export function mobile_failed_allocation_bytes(): number;
	/**
	* @returns {number}
	*/
	export function mobile_failed_allocation_alignment(): number;
	/**
	*/
	export class IntoUnderlyingByteSource {
	  free(): void;
	/**
	* @param {ReadableByteStreamController} controller
	* @returns {Promise<any>}
	*/
	  pull(controller: ReadableByteStreamController): Promise<any>;
	/**
	* @param {ReadableByteStreamController} controller
	*/
	  start(controller: ReadableByteStreamController): void;
	/**
	*/
	  cancel(): void;
	/**
	*/
	  readonly autoAllocateChunkSize: number;
	/**
	*/
	  readonly type: string;
	}
	/**
	*/
	export class IntoUnderlyingSink {
	  free(): void;
	/**
	* @param {any} reason
	* @returns {Promise<any>}
	*/
	  abort(reason: any): Promise<any>;
	/**
	* @returns {Promise<any>}
	*/
	  close(): Promise<any>;
	/**
	* @param {any} chunk
	* @returns {Promise<any>}
	*/
	  write(chunk: any): Promise<any>;
	}
	/**
	*/
	export class IntoUnderlyingSource {
	  free(): void;
	/**
	* @param {ReadableStreamDefaultController} controller
	* @returns {Promise<any>}
	*/
	  pull(controller: ReadableStreamDefaultController): Promise<any>;
	/**
	*/
	  cancel(): void;
	}
	/**
	*/
	export class WebHandle {
	  free(): void;
	/**
	* @param {any} canvas
	* @returns {Promise<void>}
	*/
	  start(canvas: any): Promise<void>;
	/**
	* Add a new receiver streaming data from the given url.
	*
	* If `follow_if_http` is `true`, and the url is an HTTP source, the viewer will open the stream
	* in `Following` mode rather than `Playing` mode.
	*
	* Websocket streams are always opened in `Following` mode.
	*
	* It is an error to open a channel twice with the same id.
	* @param {string} url
	* @param {boolean | undefined} [follow_if_http]
	*/
	  add_receiver(url: string, follow_if_http?: boolean): void;
	/**
	* @returns {boolean}
	*/
	  has_panicked(): boolean;
	/**
	* Open a new channel for streaming data.
	*
	* It is an error to open a channel twice with the same id.
	* @param {string} id
	* @param {string} channel_name
	*/
	  open_channel(id: string, channel_name: string): void;
	/**
	* Close an existing channel for streaming data.
	*
	* No-op if the channel is already closed.
	* @param {string} id
	*/
	  close_channel(id: string): void;
	/**
	* @returns {string | undefined}
	*/
	  panic_message(): string | undefined;
	/**
	* @returns {string | undefined}
	*/
	  panic_callstack(): string | undefined;
	/**
	* @param {string} url
	*/
	  remove_receiver(url: string): void;
	/**
	* @returns {number}
	*/
	  mobile_queue_len(): number;
	/**
	* Exact bounded decode, one complete RRF2 frame per call. Unlike the stock
	* async API this returns after the decoder has sent the message; queue_len
	* then provides store-consumption backpressure.
	* @param {string} id
	* @param {Uint8Array} data
	*/
	  send_rrd_frame_sync(id: string, data: Uint8Array): void;
	/**
	* Add an rrd to the viewer directly from a byte array.
	* @param {string} id
	* @param {Uint8Array} data
	*/
	  send_rrd_to_channel(id: string, data: Uint8Array): void;
	/**
	* @param {string} name
	* @param {boolean} sequence
	* @param {string} min
	* @param {string} max
	*/
	  mobile_source_bounds(name: string, sequence: boolean, min: string, max: string): void;
	/**
	* @param {string} panel
	* @param {string | undefined} [state]
	*/
	  override_panel_state(panel: string, state?: string): void;
	/**
	*/
	  enable_source_backing(): void;
	/**
	* @param {string} ids
	* @returns {string}
	*/
	  mobile_missing_chunks(ids: string): string;
	/**
	* JSON with decimal-string time: never round nanosecond timestamps through JS f64.
	* @returns {string | undefined}
	*/
	  mobile_playback_state(): string | undefined;
	/**
	* @param {string} ids
	*/
	  mobile_protect_chunks(ids: string): void;
	/**
	* Evict only source-backed recording residency after each bounded part.
	* Source frames remain on disk; the loader has already protected the
	* active query's original chunk IDs before calling this method.
	*/
	  mobile_reclaim_source(): void;
	/**
	* @param {boolean | undefined} [value]
	*/
	  toggle_panel_overrides(value?: boolean): void;
	/**
	* @param {any} app_options
	*/
	  constructor(app_options: any);
	/**
	*/
	  destroy(): void;
	}
	
}

declare type InitInput = RequestInfo | URL | Response | BufferSource | WebAssembly.Module;

declare interface InitOutput {
  readonly memory: WebAssembly.Memory;
  readonly __wbg_webhandle_free: (a: number, b: number) => void;
  readonly mobile_failed_allocation_alignment: () => number;
  readonly mobile_failed_allocation_bytes: () => number;
  readonly mobile_live_allocated_bytes: () => number;
  readonly webhandle_add_receiver: (a: number, b: number, c: number, d: number) => void;
  readonly webhandle_close_channel: (a: number, b: number, c: number) => void;
  readonly webhandle_destroy: (a: number) => void;
  readonly webhandle_enable_source_backing: (a: number) => void;
  readonly webhandle_has_panicked: (a: number) => number;
  readonly webhandle_mobile_missing_chunks: (a: number, b: number, c: number, d: number) => void;
  readonly webhandle_mobile_playback_state: (a: number, b: number) => void;
  readonly webhandle_mobile_protect_chunks: (a: number, b: number, c: number) => void;
  readonly webhandle_mobile_queue_len: (a: number) => number;
  readonly webhandle_mobile_reclaim_source: (a: number) => void;
  readonly webhandle_mobile_source_bounds: (a: number, b: number, c: number, d: number, e: number, f: number, g: number, h: number, i: number) => void;
  readonly webhandle_new: (a: number, b: number) => void;
  readonly webhandle_open_channel: (a: number, b: number, c: number, d: number, e: number) => void;
  readonly webhandle_override_panel_state: (a: number, b: number, c: number, d: number, e: number, f: number) => void;
  readonly webhandle_panic_callstack: (a: number, b: number) => void;
  readonly webhandle_panic_message: (a: number, b: number) => void;
  readonly webhandle_remove_receiver: (a: number, b: number, c: number) => void;
  readonly webhandle_send_rrd_frame_sync: (a: number, b: number, c: number, d: number, e: number, f: number) => void;
  readonly webhandle_send_rrd_to_channel: (a: number, b: number, c: number, d: number, e: number) => void;
  readonly webhandle_start: (a: number, b: number) => number;
  readonly webhandle_toggle_panel_overrides: (a: number, b: number) => void;
  readonly wgpu_render_bundle_draw: (a: number, b: number, c: number, d: number, e: number) => void;
  readonly wgpu_render_bundle_draw_indexed: (a: number, b: number, c: number, d: number, e: number, f: number) => void;
  readonly wgpu_render_bundle_set_pipeline: (a: number, b: number) => void;
  readonly wgpu_render_bundle_draw_indirect: (a: number, b: number, c: number) => void;
  readonly wgpu_render_bundle_set_bind_group: (a: number, b: number, c: number, d: number, e: number) => void;
  readonly wgpu_render_bundle_set_vertex_buffer: (a: number, b: number, c: number, d: number, e: number) => void;
  readonly wgpu_render_bundle_set_push_constants: (a: number, b: number, c: number, d: number, e: number) => void;
  readonly wgpu_render_bundle_draw_indexed_indirect: (a: number, b: number, c: number) => void;
  readonly wgpu_render_bundle_insert_debug_marker: (a: number, b: number) => void;
  readonly wgpu_render_bundle_pop_debug_group: (a: number) => void;
  readonly wgpu_render_bundle_set_index_buffer: (a: number, b: number, c: number, d: number, e: number) => void;
  readonly wgpu_render_bundle_push_debug_group: (a: number, b: number) => void;
  readonly __wbg_intounderlyingbytesource_free: (a: number, b: number) => void;
  readonly intounderlyingbytesource_autoAllocateChunkSize: (a: number) => number;
  readonly intounderlyingbytesource_cancel: (a: number) => void;
  readonly intounderlyingbytesource_pull: (a: number, b: number) => number;
  readonly intounderlyingbytesource_start: (a: number, b: number) => void;
  readonly intounderlyingbytesource_type: (a: number, b: number) => void;
  readonly __wbg_intounderlyingsink_free: (a: number, b: number) => void;
  readonly __wbg_intounderlyingsource_free: (a: number, b: number) => void;
  readonly intounderlyingsink_abort: (a: number, b: number) => number;
  readonly intounderlyingsink_close: (a: number) => number;
  readonly intounderlyingsink_write: (a: number, b: number) => number;
  readonly intounderlyingsource_cancel: (a: number) => void;
  readonly intounderlyingsource_pull: (a: number, b: number) => number;
  readonly __wbindgen_malloc: (a: number, b: number) => number;
  readonly __wbindgen_realloc: (a: number, b: number, c: number, d: number) => number;
  readonly __wbindgen_export_2: WebAssembly.Table;
  readonly __wbindgen_export_3: WebAssembly.Table;
  readonly __wbindgen_add_to_stack_pointer: (a: number) => number;
  readonly closure942_externref_shim: (a: number, b: number, c: number, d: number) => void;
  readonly __externref_table_dealloc: (a: number) => void;
  readonly closure5907_externref_shim: (a: number, b: number, c: number) => void;
  readonly _dyn_core_ac01c6a0b7493871___ops__function__FnMut_____Output______as_wasm_bindgen_762e580ffbb6fb19___closure__WasmClosure___describe__invoke___core_ac01c6a0b7493871___result__Result_____wasm_bindgen_762e580ffbb6fb19___JsValue__: (a: number, b: number, c: number) => void;
  readonly _dyn_core_ac01c6a0b7493871___ops__function__FnMut_____Output______as_wasm_bindgen_762e580ffbb6fb19___closure__WasmClosure___describe__invoke______: (a: number, b: number) => void;
  readonly closure8280_externref_shim: (a: number, b: number, c: number) => void;
  readonly closure11300_externref_shim: (a: number, b: number, c: number) => void;
  readonly closure15390_externref_shim: (a: number, b: number, c: number) => void;
  readonly closure16391_externref_shim: (a: number, b: number, c: number) => void;
  readonly __wbindgen_exn_store: (a: number) => void;
  readonly __externref_table_alloc: () => number;
  readonly __wbindgen_free: (a: number, b: number, c: number) => void;
  readonly closure19551_externref_shim: (a: number, b: number, c: number, d: number) => void;
  readonly __wbindgen_start: () => void;
}

/**
* If `module_or_path` is {RequestInfo} or {URL}, makes a request and
* for everything else, calls `WebAssembly.instantiate` directly.
*
* @param {{ module_or_path: InitInput | Promise<InitInput> }} module_or_path - Passing `InitInput` directly is deprecated.
*
* @returns {Promise<InitOutput>}
*/
declare function wasm_bindgen (module_or_path: { module_or_path: InitInput | Promise<InitInput> } | InitInput | Promise<InitInput>): Promise<InitOutput>;
