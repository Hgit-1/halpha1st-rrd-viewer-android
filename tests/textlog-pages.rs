// Compile and execute the exact shipped paged derived-entry storage.
#[path = "../../rerun-source/rerun-0.20.3/crates/viewer/re_space_view_text_log/src/paged_entries.rs"]
mod paged_entries;

#[unsafe(no_mangle)]
pub extern "C" fn probe_pages() -> u32 {
    let mut values = paged_entries::PagedEntries::default();
    for i in 0..480_597usize {
        values.push((i % 127, i, format!("row-{i}"), [i as u64; 3]));
    }
    values.stable_sort_by_key(|v| v.0);
    let mut count = 0usize;
    let mut per_key = [0usize; 127];
    let mut last = [None; 127];
    let mut previous_key = 0;
    for (key, i, body, timelines) in values.iter() {
        if *i >= 480_597 || *key != *i % 127 || *key < previous_key
            || body != &format!("row-{i}") || *timelines != [*i as u64; 3]
            || last[*key].is_some_and(|old| old >= *i) { return 1; }
        previous_key = *key;
        last[*key] = Some(*i);
        per_key[*key] += 1;
        count += 1;
    }
    if count != 480_597 { return 2; }
    for key in 0..127 { if per_key[key] != (480_596 - key) / 127 + 1 { return 3; } }
    values.clear();
    values.push((0, 0, "reuse".to_owned(), [7; 3]));
    if values.iter().count() != 1 { return 4; }
    0
}
