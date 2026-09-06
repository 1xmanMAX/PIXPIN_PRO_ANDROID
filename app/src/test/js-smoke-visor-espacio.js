// Un DOM y un WebGL de mentira: lo justo para que el visor arranque, pinte y responda.
function noop(){ return 1; }
function fakeCtx2d(){ return new Proxy({filter:'', canvas:{}}, {get:(t,k)=>k in t?t[k]:(k==='createPattern'?()=>({}):k==='createImageData'?(w,h)=>({data:new Uint8ClampedArray(w*h*4)}):noop), set:(t,k,v)=>{t[k]=v;return true;}}); }
function fakeGl(){
  const g={COMPILE_STATUS:1,LINK_STATUS:2,VERTEX_SHADER:3,FRAGMENT_SHADER:4,ARRAY_BUFFER:5,STATIC_DRAW:6,DEPTH_TEST:7,LEQUAL:8,BLEND:9,ONE:10,ONE_MINUS_SRC_ALPHA:11,COLOR_BUFFER_BIT:12,DEPTH_BUFFER_BIT:13,TRIANGLES:14,TRIANGLE_STRIP:15,LINES:16,FLOAT:17,TEXTURE_2D:18,TEXTURE0:19,RGBA:20,UNSIGNED_BYTE:21,TEXTURE_WRAP_S:22,TEXTURE_WRAP_T:23,CLAMP_TO_EDGE:24,TEXTURE_MIN_FILTER:25,TEXTURE_MAG_FILTER:26,LINEAR:27,
    llamadas:{}, fuentes:[]};
  return new Proxy(g,{get:(t,k)=>{ if(k in t) return t[k];
    return function(){ t.llamadas[k]=(t.llamadas[k]||0)+1;
      if(k==='getShaderParameter'||k==='getProgramParameter') return true;
      if(k==='shaderSource') t.fuentes.push(arguments[1]);
      if(k==='getAttribLocation') return 0;
      return {}; }; }});
}
const elementos=[];
function el(tag){
  const e={tag,children:[],style:{},dataset:{},classList:{toggle(){},add(){},remove(){}},
    listeners:{},width:0,height:0,attributes:{},
    appendChild(c){this.children.push(c);c.parentNode=this;return c;},
    insertBefore(c){this.children.unshift(c);c.parentNode=this;return c;},
    removeChild(c){this.children=this.children.filter(x=>x!==c);},
    addEventListener(n,f){(this.listeners[n]=this.listeners[n]||[]).push(f);},
    setPointerCapture(){}, releasePointerCapture(){},
    getBoundingClientRect(){return {left:0,top:0,width:800,height:600};},
    getContext(t){ return t==='2d'?fakeCtx2d():(t==='webgl'?(this.gl=this.gl||fakeGl()):null); },
    fire(n,ev){ (this.listeners[n]||[]).forEach(f=>f(Object.assign({preventDefault(){},timeStamp:Date.now()},ev))); }
  };
  elementos.push(e); return e;
}
global.document={createElement:el,body:el('body')};
global.window={devicePixelRatio:2};
global.Image=function(){ this.complete=false; };
let cola=[]; global.requestAnimationFrame=f=>{cola.push(f);return 1;};
global.setTimeout=(f,ms)=>{cola.push(f);return 1;};
function vaciar(){ let n=0; while(cola.length&&n<50){ const f=cola.shift(); f(performance.now()); n++; } }
global.Blob=function(){}; global.URL={createObjectURL(){return 'blob:';},revokeObjectURL(){}};
const fs=require('fs');
const src=fs.readFileSync(process.argv[2],'utf8');
eval(src+'\nglobal.crearEspacio=crearEspacio;');
const D={f:'#14161c',luz:[0.3,0.2,-0.9],c:{g:0.3,i:0.2,z:1.5,b:0,l:0.4,r:1,c:[1,2,3]},gr:[{i:'g1',n:'Muros'}],
  tr:[{c:'#ff0000',w:2,p:[0,0,0,10,0,0,10,10,0,20,10,5],a:[1,2,2,1],g:'g1'},{c:'#00ff00',w:1,p:[0,0,0,0,0,10]}],
  ho:[{c:'#3366ff',o:1,k:1,s:[[0,0,0,1,0,0,1,1,0],[0,0,1,1,0,1,1,1,1]]},{c:'#00ff00',o:1,b:[0,0,0,4,0,0,4,3,0,0,3,0],n:[0,0,1]}],
  im:[],vi:[{n:'Arriba',c:{g:0,i:1.5,z:2,b:0,l:0,r:0,c:[0,0,0]}}],sol:[0.3,0.2,0.9],cj:[0,0,0,20,10,10],po:'data:image/jpeg;base64,AAA'};
const caja=el('div'); caja.dataset.fondo='#14161c';
const dichos=[]; const api={decir:t=>dichos.push(t),refrescar(){},agarrado(){},color(){return '#f00';},grosor(){return 4;}};
const v=crearEspacio(caja,D,api);
console.log('herramientas',v.herramientas.join(','));
v.activar(); vaciar();
const lienzo=caja.children.find(c=>c.tag==='canvas'&&!c.gl)||caja.children[0];
const glCanvas=caja.children.find(c=>c.gl);
console.log('portada puesta:', caja.children.some(c=>c.className==='portada'), 'gl arrancó:', !!glCanvas);
// Gestos: giro, suelta con inercia, pinza, tres dedos, doble toque.
const c=caja.children.find(x=>x.tag==='canvas'&&x.listeners.pointerdown);
c.fire('pointerdown',{pointerId:1,clientX:100,clientY:100,button:0,pointerType:'touch'});
for(let i=1;i<=5;i++) c.fire('pointermove',{pointerId:1,clientX:100+i*10,clientY:100,button:0,pointerType:'touch'});
c.fire('pointerup',{pointerId:1,clientX:150,clientY:100,button:0,pointerType:'touch'}); vaciar();
c.fire('pointerdown',{pointerId:1,clientX:100,clientY:100,button:0,pointerType:'touch'});
c.fire('pointerdown',{pointerId:2,clientX:200,clientY:100,button:0,pointerType:'touch'});
c.fire('pointerdown',{pointerId:3,clientX:150,clientY:200,button:0,pointerType:'touch'});
c.fire('pointermove',{pointerId:3,clientX:150,clientY:240,button:0,pointerType:'touch'});
[3,2,1].forEach(id=>c.fire('pointerup',{pointerId:id,clientX:150,clientY:240,button:0,pointerType:'touch'}));
c.fire('pointerdown',{pointerId:1,clientX:100,clientY:100,button:0,pointerType:'touch'});
c.fire('pointerup',{pointerId:1,clientX:100,clientY:100,button:0,pointerType:'touch'});
c.fire('pointerdown',{pointerId:1,clientX:101,clientY:100,button:0,pointerType:'touch'});
c.fire('pointerup',{pointerId:1,clientX:101,clientY:100,button:0,pointerType:'touch'}); vaciar();
c.fire('wheel',{deltaY:120,clientX:400,clientY:300}); c.fire('dblclick',{button:0}); c.fire('dblclick',{button:2}); vaciar();
v.orto(); v.lente(0.1); v.comoSeExporto(); vaciar();
// La escena entera, encendida y apagada.
v.escena().forEach(o=>{ o.poner(true); vaciar(); o.poner(false); vaciar(); });
v.escena().forEach(o=>{ o.poner(true); }); vaciar(); vaciar();
console.log('giradiscos', v.giradiscos(), v.giradiscos());
console.log('guía', v.guia().map(b=>b.t+':'+b.f.length).join(' '));
v.vistas().forEach(x=>{x.ir(); vaciar();});
v.grupos().forEach(g=>{g.poner(false);g.acercarse();g.poner(true);});
v.modo('medir'); c.fire('pointerdown',{pointerId:1,clientX:400,clientY:300,button:0,pointerType:'touch'}); c.fire('pointerup',{pointerId:1,clientX:400,clientY:300,button:0,pointerType:'touch'}); vaciar();
const m=v.obj();
console.log('obj líneas', m.obj.split('\n').length, 'v:', (m.obj.match(/^v /gm)||[]).length, 'f:', (m.obj.match(/^f /gm)||[]).length, 'mtl:', (m.mtl.match(/newmtl/g)||[]).length);
const g=glCanvas.gl; console.log('gl draws', g.llamadas.drawArrays, 'programas', g.llamadas.linkProgram, 'shaders', g.fuentes.length);
// Los sombreadores: que cada uno declare lo que usa.
g.fuentes.forEach((f,i)=>{ ['usombra','vH','ufondo','usombraTinta','uniebla'].forEach(u=>{ if(f.includes(u+'')&&!new RegExp('(uniform|varying)[^;]*\\b'+u+'\\b').test(f)) console.log('AVISO shader',i,'usa',u,'sin declarar'); }); });
v.desactivar();
console.log('dichos', JSON.stringify(dichos.slice(-4)));
console.log('OK');
