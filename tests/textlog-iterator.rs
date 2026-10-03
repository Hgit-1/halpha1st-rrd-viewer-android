// Runs the actual patched Chunk iterator in wasm32, not a JS reimplementation.
use re_chunk::{Chunk, ChunkId, EntityPath, RowId, TimePoint, Timeline};
use re_types_core::ComponentName;
use re_arrow2::array::{Array, PrimitiveArray};
use std::sync::Arc;

#[unsafe(no_mangle)]
pub extern "C" fn test_iterator() -> u32 {
    let name = ComponentName::from("test.timeline_component");
    for timeline_count in [0, 1, 3, 5, 12] {
        for sparse in [false, true] {
            let mut builder = Chunk::builder_with_id(ChunkId::from_u128(1), EntityPath::root());
            for row in 0..7_u64 {
                let timepoint: TimePoint = (0..timeline_count)
                    .map(|i| (Timeline::new_sequence(format!("timeline_{i}")), (row * 100 + i) as i64))
                    .collect();
                let array: Option<Box<dyn Array>> = if sparse && row % 2 == 1 {
                    None
                } else {
                    // Valid but empty component batches must still yield a timeline row.
                    Some(Box::new(PrimitiveArray::<u32>::from_vec(if row == 2 { vec![] } else { vec![row as u32, 42] })))
                };
                builder = builder.with_sparse_row(RowId::from_u128(row as u128 + 1), timepoint, [(name, array)]);
            }
            let chunk = match builder.build() { Ok(c) => c, Err(_) => return 1 };
            let rows: Vec<_> = chunk.iter_component_timeline_values(&name).collect();
            let expected_count = if sparse { 4 } else { 7 };
            if rows.len() != expected_count { return 2; }
            for (index, values) in rows.iter().enumerate() {
                let row = if sparse { index * 2 } else { index };
                if values.len() != timeline_count as usize { return 3; }
                for i in 0..timeline_count {
                    let timeline = Timeline::new_sequence(format!("timeline_{i}"));
                    let found = values.iter().find(|(t, _)| *t == timeline).map(|(_, t)| t.as_i64());
                    if found != Some((row as u64 * 100 + i) as i64) { return 4; }
                }
                let sibling = Arc::clone(values);
                if !Arc::ptr_eq(values, &sibling) { return 5; }
            }
            if chunk.iter_component_timeline_values(&ComponentName::from("missing.component")).count() != 0 { return 6; }
            let mut exhausted = chunk.iter_component_timeline_values(&name);
            for _ in 0..expected_count { if exhausted.next().is_none() { return 7; } }
            if exhausted.next().is_some() || exhausted.next().is_some() { return 8; }
            if timeline_count > 0 {
                let old: Vec<_> = chunk.iter_component_timepoints(&name).collect();
                if old.len() != rows.len() { return 9; }
                for (old, values) in old.iter().zip(&rows) {
                    if old.iter().map(|(t, v)| (*t, *v)).collect::<Vec<_>>() != values.as_ref() { return 10; }
                }
            }
        }
    }
    0
}
