import { Unpackr } from 'msgpackr';
import { tableFromIPC } from 'apache-arrow';

const unpackr = new Unpackr({ useRecords: false, mapsAsObjects: false, structuredClone: false, moreTypes: true });

function lz4Decode(src, length) {
  const out = new Uint8Array(length); let s = 0, d = 0;
  while (s < src.length) {
    const token = src[s++]; let literals = token >>> 4;
    if (literals === 15) { let v; do { if (s >= src.length) throw Error('literal'); v=src[s++]; literals += v; } while(v===255); }
    if (s + literals > src.length || d + literals > length) throw Error('literal bounds');
    out.set(src.subarray(s, s+literals), d); s += literals; d += literals;
    if (s === src.length) break;
    if (s + 2 > src.length) throw Error('offset');
    const offset = src[s] | (src[s+1] << 8); s += 2;
    if (!offset || offset > d) throw Error('bad offset');
    let match = (token & 15) + 4;
    if ((token & 15) === 15) { let v; do { if(s>=src.length)throw Error('match'); v=src[s++]; match += v; } while(v===255); }
    if (d + match > length) throw Error('match bounds');
    for (let i=0;i<match;i++) { out[d] = out[d-offset]; d++; }
  }
  if (d !== length) throw Error('decoded length');
  return out;
}
function put32(a,p,n) { a[p]=n&255;a[p+1]=(n>>>8)&255;a[p+2]=(n>>>16)&255;a[p+3]=(n>>>24)&255; }
function lz4Literal(raw) {
  // A final literal-only sequence is a valid LZ4 block.
  const extra = raw.length < 15 ? 0 : 1 + Math.floor((raw.length - 15) / 255);
  const out = new Uint8Array(1 + extra + raw.length); let p=0;
  out[p++] = Math.min(raw.length,15) << 4;
  if (raw.length >= 15) { let n=raw.length-15; while(n>=255){out[p++]=255;n-=255;} out[p++]=n; }
  out.set(raw,p); return out;
}
function u32(a,p) { return (a[p] | a[p+1]<<8 | a[p+2]<<16 | a[p+3]<<24) >>> 0; }
function mapGet(m,k) { return m instanceof Map ? m.get(k) : undefined; }
function isBytes(x) { return x instanceof Uint8Array || (typeof Buffer !== 'undefined' && Buffer.isBuffer(x)); }
function decodePayload(raw) {
  const msg = unpackr.unpack(raw), arrow = mapGet(msg, 'ArrowMsg');
  if (arrow === undefined) return null; // Store metadata is not Arrow.
  if (!Array.isArray(arrow) || !Array.isArray(arrow[1]) || !isBytes(arrow[1][2]))
    throw Error('Invalid ArrowMsg envelope');
  return { msg, arrow, table: tableFromIPC(arrow[1][2]) };
}
function timeRange(raw, entityName) {
  const decoded = decodePayload(raw); if (!decoded) return null;
  const md = decoded.table.schema.metadata;
  if (entityName && (!md || md.get('rerun.entity_path') !== entityName)) return null;
  let name = null;
  for (const f of decoded.table.schema.fields) {
    if (f.name === 'time') { name = f.name; break; }
  }
  if (!name) return null;
  const values = decoded.table.getChild(name).toArray(); let lo=Infinity, hi=-Infinity;
  for (const value of values) { const n=Number(value); if(Number.isFinite(n)){lo=Math.min(lo,n);hi=Math.max(hi,n);} }
  return lo <= hi ? {lo,hi} : null;
}
function imageRange(parsed) {
  let lo=Infinity, hi=-Infinity;
  for (let i=0;i<parsed.frames.length;i++) { const r=timeRange(parsed.frames[i], '/image/img'); if(r){lo=Math.min(lo,r.lo);hi=Math.max(hi,r.hi);} }
  return lo <= hi ? {lo,hi} : null;
}
// lo/hi remain API-compatible hints for inspection/presentation, not permission
// to remove source rows. An Arrow chunk and its ID are atomic. In particular,
// selecting disjoint runs and Table.concat emits multiple IPC RecordBatches
// that Rerun rejects; selecting even one run loses out-of-window history.
// All data frames therefore retain their original encoded bytes below.
function parseRrd(input) {
  const src=input instanceof Uint8Array?input:new Uint8Array(input);
  if(src.length<20||src[0]!==82||src[1]!==82||src[2]!==70||src[3]!==50)throw Error('not RRF2');
  const frames=[],encodedFrames=[];let p=12;
  while(p+8<=src.length){
    const start=p,enc=u32(src,p),unc=u32(src,p+4);p+=8;
    if(!enc&&!unc){
      if(p!==src.length)throw Error('trailing stream bytes');
      return {header:src.subarray(0,12),frames,encodedFrames};
    }
    if(p+enc>src.length)throw Error('truncated frame');
    frames.push(lz4Decode(src.subarray(p,p+enc),unc));p+=enc;
    encodedFrames.push(src.subarray(start,p));
  }
  throw Error('missing stream terminator');
}
function joinChunks(chunks){
  let n=0;for(const chunk of chunks)n+=chunk.length;
  const out=new Uint8Array(n);let p=0;
  for(const chunk of chunks){out.set(chunk,p);p+=chunk.length;}
  return out;
}
function transformRrd(input,lo=NaN,hi=NaN,includeMetadata=true){
  const parsed=parseRrd(input);
  const chunks=[parsed.header];
  for(let i=0;i<parsed.frames.length;i++){
    const raw=parsed.frames[i];
    // Drop only repeated store metadata, never an assumed first data frame.
    if(!includeMetadata && mapGet(unpackr.unpack(raw),'SetStoreInfo')!==undefined)continue;
    chunks.push(parsed.encodedFrames[i]);
  }
  chunks.push(new Uint8Array(8));return joinChunks(chunks);
}
// Each call to send_rrd_to_channel starts a decoder, so each submission
// needs its own header and terminator. Arrow chunks and their IDs stay intact.
function* transformRrdParts(input,lo=NaN,hi=NaN,includeMetadata=true){
  const parsed=parseRrd(input);
  for(let i=0;i<parsed.frames.length;i++){
    const raw=parsed.frames[i];
    if(!includeMetadata && mapGet(unpackr.unpack(raw),'SetStoreInfo')!==undefined)continue;
    yield joinChunks([parsed.header,parsed.encodedFrames[i],new Uint8Array(8)]);
  }
}
function inspectRrd(input){const parsed=parseRrd(input);return {image:imageRange(parsed),frames:parsed.frames.length};}
function analyzeRrd(input, firstFrame=0) {
  const parsed=parseRrd(input); let offset=12;
  return parsed.frames.map((raw,i)=>{
    const encoded=parsed.encodedFrames[i], decoded=decodePayload(raw);
    const base={sourceFrame:firstFrame+i,offset,length:encoded.length}; offset+=encoded.length;
    if(!decoded)return {...base,metadata:true};
    const table=decoded.table, timelines={};
    for(const field of table.schema.fields){
      if(field.metadata?.get('rerun.kind')!=='time')continue;
      const values=table.getChild(field.name).toArray(); let lo=null,hi=null;
      const isTimestamp=String(field.type).startsWith('Timestamp');
      for(const value of values){
        // Timestamp toArray exposes the raw nanosecond Int64 buffer, unlike
        // Vector.get() (fractional Number milliseconds). Refuse lossy fallback.
        if(isTimestamp && typeof value!=='bigint')throw Error('expected exact timestamp Int64 buffer');
        const n=BigInt(value);
        lo=lo===null||n<lo?n:lo;hi=hi===null||n>hi?n:hi;
      }
      if(lo!==null)timelines[field.name]={lo:String(lo),hi:String(hi),sequence:field.type.typeId!==10};
    }
    return {...base,id:table.schema.metadata.get('rerun.id'),entity:table.schema.metadata.get('rerun.entity_path'),timelines,
      components:table.schema.fields.filter(f=>f.metadata?.get('rerun.kind')==='data').map(f=>f.name)};
  });
}
const api={transformRrd,transformRrdParts,inspectRrd,lz4Decode,lz4Literal,analyzeRrd};
if(typeof globalThis!=='undefined')globalThis.RerunFilter=api;
export {transformRrd,transformRrdParts,inspectRrd,lz4Decode,lz4Literal,analyzeRrd};
