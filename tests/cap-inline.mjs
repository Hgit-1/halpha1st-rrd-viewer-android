      function capWasmMemory(bytes, maxPages) {
        // Add a real maximum to the module's private memory before WebView
        // instantiates it. Use loops rather than spread on whole custom/data
        // sections: the module is tens of MiB and spread would overflow JS.
        const src = new Uint8Array(bytes), out = [];
        const readLeb = (at) => { let value=0, shift=0, p=at; for(;;){const b=src[p++];value|=(b&127)<<shift;if(!(b&128))return [value>>>0,p];shift+=7;} };
        const writeLeb = (value) => { const a=[]; do { let b=value&127; value>>>=7; if(value)b|=128; a.push(b); } while(value); return a; };
        const append = (from,to) => { for(let i=from;i<to;i++) out.push(src[i]); };
        append(0,8); let p=8;
        while(p<src.length){ const id=src[p++], lr=readLeb(p), len=lr[0]; p=lr[1]; const end=p+len;
          if(id!==5){ out.push(id,...writeLeb(len)); append(p,end); p=end; continue; }
          const count=readLeb(p), flags=readLeb(count[1]), min=readLeb(flags[1]);
          if(count[0]!==1 || (flags[0]&1) || maxPages<min[0]){out.push(id,...writeLeb(len));append(p,end);p=end;continue;}
          const payload=[...writeLeb(1),...writeLeb(1),...writeLeb(min[0]),...writeLeb(maxPages)];
          out.push(id,...writeLeb(payload.length),...payload); p=end;
        }
        return new Uint8Array(out).buffer;
      }


export {capWasmMemory};
