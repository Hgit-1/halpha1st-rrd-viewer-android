// Compile the exact shipped-source helper independently of desktop Viewer backends.
#[path = "../../rerun-source/rerun-0.20.3/crates/viewer/re_space_view_text_log/src/compact_sort.rs"]
mod compact_sort;

#[unsafe(no_mangle)]
pub extern "C" fn probe_sort() -> u32 {
    let mut seed = 1_u32;
    for len in [0, 1, 2, 127, 4096, 65536] {
        let mut input: Vec<_> = (0..len).map(|i| {
            seed = seed.wrapping_mul(1664525).wrapping_add(1013904223);
            (seed % 127, i, format!("row-{i}"))
        }).collect();
        let mut expected = input.clone();
        expected.sort_by_key(|value| value.0);
        compact_sort::stable_sort_by_key(&mut input, |value| value.0);
        if input != expected { return 1; }
        input.reverse();
        expected = input.clone();
        expected.sort_by_key(|value| value.0);
        compact_sort::stable_sort_by_key(&mut input, |value| value.0);
        if input != expected { return 2; }
    }
    0
}
