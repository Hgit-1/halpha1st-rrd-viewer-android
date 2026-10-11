function capWasmMemory(bytes, maxPages) {
 const src=new Uint8Array(bytes),out=[...src.slice(0,8)];
 const read=(at)=>{let v=0,s=0,p=at;for(;;){const b=src[p++];v|=(b&127)<<s;if(!(b&128))return [v>>>0,p];s+=7;}};
 const leb=(v)=>{const a=[];do{let b=v&127;v>>>=7;if(v)b|=128;a.push(b)}while(v);return a};
 let p=8;while(p<src.length){const id=src[p++],q=read(p),n=q[0];p=q[1];const e=p+n;if(id!==5){out.push(id,...leb(n),...src.slice(p,e));p=e;continue}const c=read(p),f=read(c[1]),m=read(f[1]);if(c[0]!==1||(f[0]&1)||maxPages<m[0]){out.push(id,...leb(n),...src.slice(p,e));p=e;continue}const x=[...leb(1),...leb(1),...leb(m[0]),...leb(maxPages)];out.push(id,...leb(x.length),...x);p=e}return new Uint8Array(out).buffer}
