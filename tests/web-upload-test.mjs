// Execute the actual Web-only upload loops extracted from vendored renderer.rs.
// Mock queue proves byte/offset/alignment preservation, not real WebGL rendering.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
const source = fs.readFileSync('vendor/egui-wgpu/src/renderer.rs', 'utf8');
const chunk = source.match(/const WEB_UPLOAD_CHUNK_BYTES: usize = ([^;]+);/)[1];
const loops = [...source.matchAll(/#\[cfg\(target_arch = "wasm32"\)\]\s*\{\s*\/\/ WebGL/g)].map(match => {
  const begin = source.indexOf('{', match.index);
  let depth = 1, end = begin + 1;
  while (depth && end < source.length) { if (source[end] === '{') depth++; if (source[end] === '}') depth--; end++; }
  if (depth) throw Error('Unclosed upload block');
  return source.slice(begin, end);
});
if (loops.length !== 2) throw Error('Expected two Web upload loops');
const program = `
use std::{cell::RefCell,ops::Range};
const WEB_UPLOAD_CHUNK_BYTES:usize=${chunk};
mod bytemuck {
  pub fn cast_slice(values:&[u32])->&[u8] {
    unsafe {std::slice::from_raw_parts(values.as_ptr().cast(),values.len()*4)}
  }
}
struct Buffer{ data:RefCell<Vec<u8>> }
struct Queue;
impl Queue {
 fn write_buffer(&self,buffer:&Buffer,offset:u64,data:&[u8]) {
   assert_eq!(offset%4,0); assert_eq!(data.len()%4,0);
   assert!(data.len()<=WEB_UPLOAD_CHUNK_BYTES);assert!(!data.is_empty());
   let p=offset as usize;buffer.data.borrow_mut()[p..p+data.len()].copy_from_slice(data);
 }
}
struct Mesh{indices:Vec<u32>,vertices:Vec<u32>}
enum Primitive{Mesh(Mesh),Callback(())}
mod epaint{pub struct ClippedPrimitive{pub primitive:super::Primitive}}
use epaint::ClippedPrimitive;
struct Sliced { buffer:Buffer, slices:Vec<Range<usize>> }
struct Renderer{index_buffer:Sliced,vertex_buffer:Sliced}
impl Renderer {
 fn upload(&mut self,queue:&Queue,paint_jobs:&[ClippedPrimitive],required_index_buffer_size:u64,required_vertex_buffer_size:u64){
 ${loops.join('\n')}
 }
}
fn main(){
 let sizes=[0,1,65535,65536,65537,65536*9+3,0,6];
 let mut jobs=vec![ClippedPrimitive{primitive:Primitive::Callback(())}];
 let mut expected_i=vec![];let mut expected_v=vec![];let mut expected_slices=vec![];
 for (i,size) in sizes.into_iter().enumerate(){
   let bytes=vec![(i as u32+1)*0x01010101;size];
   let start=expected_i.len();expected_i.extend_from_slice(bytemuck::cast_slice(&bytes));
   expected_v.extend_from_slice(bytemuck::cast_slice(&bytes));
   expected_slices.push(start..expected_i.len());
   jobs.push(ClippedPrimitive{primitive:Primitive::Mesh(Mesh{indices:bytes.clone(),vertices:bytes})});
   jobs.push(ClippedPrimitive{primitive:Primitive::Callback(())});
 }
 let n=expected_i.len();let fresh=||Sliced{buffer:Buffer{data:RefCell::new(vec![0;n])},slices:vec![]};
 let mut renderer=Renderer{index_buffer:fresh(),vertex_buffer:fresh()};
 renderer.upload(&Queue,&jobs,n as u64,n as u64);
 assert_eq!(*renderer.index_buffer.buffer.data.borrow(),expected_i);
 assert_eq!(*renderer.vertex_buffer.buffer.data.borrow(),expected_v);
 assert_eq!(renderer.index_buffer.slices,expected_slices);
 assert_eq!(renderer.vertex_buffer.slices,expected_slices);
 println!("PASS actual Web upload loops: all bytes, draw offsets, empty meshes, callbacks, alignment, <=256KiB writes");
}
`;
const dir = fs.mkdtempSync(path.join(process.env.TMPDIR || os.tmpdir(), 'web-upload-'));
try {
  const input=path.join(dir,'test.rs'), output=path.join(dir,'test');
  fs.writeFileSync(input,program);
  execFileSync('rustc',['--edition','2021',input,'-o',output],{stdio:'inherit'});
  execFileSync(output,[],{stdio:'inherit'});
} finally {fs.rmSync(dir,{recursive:true,force:true});}
