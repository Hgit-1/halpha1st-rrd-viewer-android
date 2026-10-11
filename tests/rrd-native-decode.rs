// Link the same serde/Arrow decoder and Chunk validator used by the Viewer.
#[unsafe(no_mangle)]
pub extern "C" fn probe_alloc(n: usize) -> *mut u8 {
    let bytes = vec![0u8; n].into_boxed_slice();
    Box::into_raw(bytes) as *mut u8
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn probe_free(p: *mut u8, n: usize) {
    unsafe { drop(Box::from_raw(std::ptr::slice_from_raw_parts_mut(p, n))); }
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn probe_decode(p: *const u8, n: usize) -> i32 {
    let bytes = unsafe { std::slice::from_raw_parts(p, n) };
    let msg: re_log_types::LogMsg = match rmp_serde::from_slice(bytes) {
        Ok(msg) => msg,
        Err(_) => return -1,
    };
    match msg {
        re_log_types::LogMsg::ArrowMsg(_, msg) => {
            let chunk = match re_chunk::Chunk::from_arrow_msg(&msg) {
                Ok(chunk) => chunk,
                Err(_) => return -2,
            };
            chunk.num_rows() as i32
        }
        _ => 0,
    }
}
