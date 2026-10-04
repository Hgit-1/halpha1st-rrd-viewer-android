// Compile actual padding/upload methods with mocked mapped memory. Does not test GPU.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {execFileSync} from 'node:child_process';
const root=process.env.RERUN_SOURCE || '../rerun-source/rerun-0.20.3';
function extract(file, signature) {
  const code=fs.readFileSync(`${root}/${file}`,'utf8');
  const start=code.indexOf(signature); if(start<0)throw Error(signature);
  let pos=code.indexOf('{',start)+1, depth=1;
  while(depth){const ch=code[pos++];if(ch==='{')depth++;else if(ch==='}')depth--;if(pos>code.length)throw Error('unclosed');}
  return code.slice(start,pos).replace('    re_tracing::profile_function!();','');
}
const method=extract('crates/viewer/re_renderer/src/allocator/cpu_write_gpu_read_belt.rs','    pub fn extend_rgb8_as_rgba(');
const pad=extract('crates/viewer/re_renderer/src/lib.rs','pub fn pad_rgb_to_rgba');
const code=`
#[derive(Debug)] enum CpuWriteGpuReadError {BufferFull{buffer_capacity_elements:usize,num_elements_attempted_to_add:usize,num_elements_actually_added:usize}}
struct Mock {memory:Vec<u8>,unwritten_element_range:std::ops::Range<usize>}
impl Mock {
 fn capacity(&self)->usize{self.memory.len()}
 fn remaining_capacity(&self)->usize{self.unwritten_element_range.len()}
 fn as_mut_byte_slice(&mut self)->&mut[u8]{&mut self.memory[self.unwritten_element_range.clone()]}
 ${method}
}
${pad}
fn main(){
 for width in [1,2,63,64,65,640,1280] {
  for height in [1,2,17,720] {
   let src:Vec<u8>=(0..width*height*3).map(|i|(i%251)as u8).collect();
   let row=((width*4+255)/256)*256;let size=row*height;
   let offset=16;
   let mut m=Mock{memory:vec![17;offset+size+32],unwritten_element_range:offset..offset+size};
   m.extend_rgb8_as_rgba(&src,width,height,row).unwrap();
   assert_eq!(m.remaining_capacity(),0);assert_eq!(m.unwritten_element_range.start,offset+size);
   assert!(m.memory[..offset].iter().all(|x|*x==17));
   assert!(m.memory[offset+size..].iter().all(|x|*x==17));
   for y in 0..height {
    let dst=&m.memory[offset+y*row..offset+(y+1)*row];
    assert_eq!(&dst[..width*4],pad_rgb_to_rgba(&src[y*width*3..(y+1)*width*3],255));
    assert!(dst[width*4..].iter().all(|x|*x==0));
   }
   let mut bad=Mock{memory:vec![17;size-1],unwritten_element_range:0..size-1};
   assert!(bad.extend_rgb8_as_rgba(&src,width,height,row).is_err());
   assert_eq!(bad.unwritten_element_range.start,0);
   assert!(bad.memory.iter().all(|x|*x==17));
  }
 }
 assert_eq!(pad_rgb_to_rgba(&[1i8,2,3,-1,-2,-3,9],-128),[1,2,3,-128,-1,-2,-3,-128]);
 assert_eq!(pad_rgb_to_rgba(&[1f32,2.,3.],1.),[1.,2.,3.,1.]);
 println!("PASS actual direct RGB upload: all pixels/alpha, row padding, byte offsets, capacity guard, 1280x720, typed fallback");
}
`;
const dir=fs.mkdtempSync(path.join(process.env.TMPDIR||os.tmpdir(),'rgb-upload-'));
try{fs.writeFileSync(path.join(dir,'test.rs'),code);execFileSync('rustc',['--edition','2021','-O','-A','dead_code',path.join(dir,'test.rs'),'-o',path.join(dir,'test')],{stdio:'inherit'});execFileSync(path.join(dir,'test'),[],{stdio:'inherit'});}finally{fs.rmSync(dir,{recursive:true,force:true});}
