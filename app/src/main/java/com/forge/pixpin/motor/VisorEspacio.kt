package com.forge.pixpin.motor

/**
 * **El visor del croquis en el espacio, para la página web exportada.**
 *
 * Es texto: JavaScript que se mete dentro del HTML que arma [ExportarHtml]. Vive aquí, en el
 * motor, y no en `croquis3d`, porque el motor no puede depender de nada de arriba —lo
 * comprueba `MotorSeparadoTest`— y quien arma el documento es el motor. Lo que sabe del
 * croquis se lo dan hecho: un JSON que escribe `croquis3d/ExportarCroquisHtml`.
 *
 * ## Qué pinta, y por qué así
 *
 * Un lienzo 2D con la **misma proyección que `Camara3D.aPantalla`**, portada línea a línea
 * (hay una prueba que compara las dos: si se toca una y no la otra, se cae). Nada de WebGL
 * ni de traer un motor 3D de fuera: lo que hay que enseñar son rayas, hojas y bolas, y con
 * eso el archivo pesa unos kilobytes en vez de un mega.
 *
 * - **Los trazos** son polilíneas del grueso que se les dio, ordenadas de lejos a cerca.
 * - **Las hojas planas** van como su contorno relleno, con el mismo velo que en la
 *   aplicación: más opaco cuanto más de frente se las mira, casi invisible de canto.
 * - **Los sólidos** —bola, cilindro, cono, anillo— van cara a cara, con las de atrás
 *   descartadas por el sentido del giro en pantalla y cada cara sombreada según hacia dónde
 *   mira. Es lo que hace que una bola parezca una bola y no una mancha.
 * - **Las imágenes puestas en el espacio** van con su textura, encajada en las cuatro
 *   esquinas con la transformación afín que las lleva ahí.
 *
 * ## El giro
 *
 * **Se gira alrededor del croquis, no alrededor de un punto cualquiera.** El centro de la
 * cámara es donde uno lo dejó al exportar, y girar alrededor de eso hace que el dibujo se
 * vaya de la pantalla en cuanto está un poco apartado: se siente como si todo estuviera
 * volteado. Aquí el eje es el centro de lo que se ve, y al girar se recoloca la cámara para
 * que ese punto **no se mueva de la pantalla**. Y se gira como se gira en todas partes: lo
 * dibujado sigue al dedo.
 */
internal object VisorEspacio {

    /** El JavaScript del visor. Define `crearEspacio(caja, datos, api)`. */
    val JS = """
function crearEspacio(caja, D, api){
"use strict";
var CAMPO_MAX=160*Math.PI/180, TOPE_RECT=80*Math.PI/180, TOPE_INC=Math.PI/2-0.02;
// Una vuelta entera son mil píxeles de arrastre: en la aplicación son setecientos, pero en un
// navegador el dedo recorre más pantalla y el usuario lo notaba demasiado sensible.
var ZOOM_MIN=0.05, ZOOM_MAX=40, VUELTA_ENTERA=1000;
var lienzo=document.createElement('canvas');
lienzo.className='espacio';
caja.appendChild(lienzo);
var ctx=lienzo.getContext('2d');
var cam=clonar(D.c), inicial=clonar(D.c), w=0, h=0, dpr=1;
var apagados=Object.create(null), modo='girar', medida=[], pendiente=false, vivo=false;
var pivote=null, texturas=Object.create(null);
// **La tarjeta gráfica, si la hay.** Ver [arrancarGl]: lo dibujado se sube una vez y pintar
// es mandarle la cámara. El lienzo 2D se queda encima solo para la medida.
var gl=null, lienzoGl=null, GL=null;

function clonar(c){return {g:c.g,i:c.i,z:c.z,b:c.b,l:c.l,r:c.r,c:[c.c[0],c.c[1],c.c[2]]};}
function norm(v){var d=Math.hypot(v[0],v[1],v[2]);return d<1e-12?[0,0,0]:[v[0]/d,v[1]/d,v[2]/d];}
function cruz(a,b){return [a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0]];}

// Idéntica a Camara3D: adelante, derecha y arriba salen del giro y la inclinación.
//
// **La base sale en números sueltos, no en vectores.** Medido en este proyecto: con los ejes
// guardados como arrays y leídos dentro del bucle (`B.d[0]`…) la proyección va a un millón de
// puntos por segundo; con los mismos números sacados a variables locales del bucle, a ciento
// sesenta millones. Es la diferencia entre un croquis que se pasea y uno que se arrastra, y
// es lo mismo que hace `BaseDeCamara` en la aplicación con sus campos sueltos.
function base(){
 var ci=Math.cos(cam.i), f=norm([Math.sin(cam.g)*ci, Math.cos(cam.g)*ci, -Math.sin(cam.i)]);
 var d=norm([Math.cos(cam.g), -Math.sin(cam.g), 0]);
 if(cam.b){var vv=norm(cruz(d,f)), cb=Math.cos(cam.b), sb=Math.sin(cam.b);
  d=norm([d[0]*cb+vv[0]*sb, d[1]*cb+vv[1]*sb, d[2]*cb+vv[2]*sb]);}
 var a=norm(cruz(d,f));
 var campo=Math.min(Math.max(cam.l,0),1)*CAMPO_MAX;
 var focal=campo>0?(cam.r?(h/2)/Math.tan(campo/2):h/campo):0;
 return {dx:d[0],dy:d[1],dz:d[2], ax:a[0],ay:a[1],az:a[2], fx:f[0],fy:f[1],fz:f[2],
  cx:cam.c[0],cy:cam.c[1],cz:cam.c[2],
  campo:campo, focal:focal, ojo:campo>0?focal/cam.z:0,
  mw:w/2, mh:h/2, z:cam.z, r:cam.r, d:d, a:a, f:f};
}
var salida=[0,0,0];
function proyectar(B,x,y,z){
 var vx=x-B.cx, vy=y-B.cy, vz=z-B.cz;
 var u=vx*B.dx+vy*B.dy+vz*B.dz, v=vx*B.ax+vy*B.ay+vz*B.az;
 var hondo=vx*B.fx+vy*B.fy+vz*B.fz;
 if(B.campo<=0){salida[0]=B.mw+u*B.z; salida[1]=B.mh-v*B.z; salida[2]=hondo; return salida;}
 var radio=Math.sqrt(u*u+v*v), prof=hondo+B.ojo;
 if(radio<1e-9){salida[0]=B.mw; salida[1]=prof>=0?B.mh:B.mh+B.focal*Math.PI; salida[2]=hondo; return salida;}
 var ang=Math.atan2(radio,prof);
 var en=B.focal*(B.r?Math.tan(Math.min(ang,TOPE_RECT)):ang);
 salida[0]=B.mw+en*u/radio; salida[1]=B.mh-en*v/radio; salida[2]=hondo;
 return salida;
}
function visible(o){return !apagados[o.g||''];}
// **El centro de cada cosa se saca una vez**, no en cada fotograma. Lo que ordena de lejos a
// cerca es su centro, y su centro no cambia al girar: recorrer todos los puntos de todo lo
// dibujado para poder ordenarlo costaba tanto como pintarlo. Se apunta en el propio objeto.
function centroDe(p,en){
 if(en._m) return en._m;
 var sx=0,sy=0,sz=0,n=p.length/3;
 for(var i=0;i<p.length;i+=3){sx+=p[i];sy+=p[i+1];sz+=p[i+2];}
 en._m=n?[sx/n,sy/n,sz/n]:[0,0,0];
 return en._m;
}
function hondura(B,p,en){
 var m=centroDe(p,en||p);
 return (m[0]-B.cx)*B.fx+(m[1]-B.cy)*B.fy+(m[2]-B.cz)*B.fz;
}
function rgb(hex){
 var s=(hex||'#888888').replace('#','');
 if(s.length===3)s=s[0]+s[0]+s[1]+s[1]+s[2]+s[2];
 var v=parseInt(s,16)||0;
 return [(v>>16)&255,(v>>8)&255,v&255];
}
function color(hex,op,luz){
 var c=rgb(hex);
 if(luz!==undefined){
  c=[Math.min(255,Math.round(c[0]*luz)),Math.min(255,Math.round(c[1]*luz)),
     Math.min(255,Math.round(c[2]*luz))];
 }
 else if(op>=1) return hex;
 return 'rgba('+c[0]+','+c[1]+','+c[2]+','+(op===undefined?1:op).toFixed(3)+')';
}
// La normal de un polígono en el mundo, por sus tres primeros puntos distintos.
function normalDe(p){
 var ax=p[3]-p[0], ay=p[4]-p[1], az=p[5]-p[2];
 for(var i=6;i<p.length;i+=3){
  var bx=p[i]-p[0], by=p[i+1]-p[1], bz=p[i+2]-p[2];
  var n=cruz([ax,ay,az],[bx,by,bz]);
  if(Math.hypot(n[0],n[1],n[2])>1e-9) return norm(n);
 }
 return [0,0,1];
}

// ---- El pintado ----
function pintar(){
 pendiente=false;
 if(!vivo||!w||!h) return;
 var B=base();
 ctx.setTransform(dpr,0,0,dpr,0,0);
 ctx.clearRect(0,0,w,h);
 if(arrancarGl()){ pintarGl(B); if(medida.length) pintarMedida(B); return; }
 var cola=[],i,j;
 for(i=0;i<D.im.length;i++){ var im=D.im[i];
  if(visible(im)) cola.push({o:im,t:2,d:hondura(B,im.e,im)}); }
 for(i=0;i<D.ho.length;i++){ var l=D.ho[i];
  if(!visible(l)) continue;
  if(l.k){ // sólido: cara a cara, que es lo que le da bulto
   for(j=0;j<l.s.length;j++) cola.push({o:l,t:3,c:l.s[j],d:hondura(B,l.s[j],l.s[j])});
  } else cola.push({o:l,t:1,d:hondura(B,l.b,l)});
 }
 for(i=0;i<D.tr.length;i++){ var tz=D.tr[i]; if(visible(tz)) cola.push({o:tz,t:0,d:hondura(B,tz.p,tz)}); }
 cola.sort(function(a,b){return b.d-a.d;}); // lo de lejos primero
 ctx.lineCap='round'; ctx.lineJoin='round';
 for(i=0;i<cola.length;i++){
  var e=cola[i];
  if(e.t===0) trazo(B,e.o);
  else if(e.t===1) hoja(B,e.o);
  else if(e.t===2) imagen(B,e.o);
  else cara(B,e.o,e.c);
 }
 if(medida.length) pintarMedida(B);
}
function camino(B,p){
 ctx.beginPath();
 var minx=1e9,miny=1e9,maxx=-1e9,maxy=-1e9;
 var orto=B.campo<=0;
 var dx=B.dx,dy=B.dy,dz=B.dz,ax=B.ax,ay=B.ay,az=B.az;
 var cx=B.cx,cy=B.cy,cz=B.cz,zz=B.z,mw=B.mw,mh=B.mh;
 for(var i=0;i<p.length;i+=3){
  var x,y;
  if(orto){
   var vx=p[i]-cx, vy=p[i+1]-cy, vz=p[i+2]-cz;
   x=mw+(vx*dx+vy*dy+vz*dz)*zz; y=mh-(vx*ax+vy*ay+vz*az)*zz;
  } else { var s=proyectar(B,p[i],p[i+1],p[i+2]); x=s[0]; y=s[1]; }
  if(x<minx)minx=x; if(x>maxx)maxx=x; if(y<miny)miny=y; if(y>maxy)maxy=y;
  if(i===0) ctx.moveTo(x,y); else ctx.lineTo(x,y);
 }
 ctx.closePath();
 return maxx<0||minx>w||maxy<0||miny>h;
}
// Una hoja plana: su contorno, con el mismo velo que en la aplicación —más opaca cuanto
// más de frente, casi invisible de canto—, y su borde marcado.
function hoja(B,l){
 if(l.b.length<9) return;
 if(camino(B,l.b)) return;
 var n=l.n||normalDe(l.b);
 var deFrente=Math.abs(n[0]*B.f[0]+n[1]*B.f[1]+n[2]*B.f[2]);
 var alfa=Math.min((0.055+0.11*deFrente)*(l.o===undefined?1:l.o),0.35);
 ctx.fillStyle=color(l.c,alfa); ctx.fill();
 ctx.strokeStyle=color(l.c,Math.min(alfa*2.4,0.55)); ctx.lineWidth=1; ctx.stroke();
}
// Una cara de un sólido: se descartan las de atrás por el sentido del giro en pantalla y
// se sombrea según hacia dónde mira. Sin esto una bola es una mancha.
function cara(B,l,p){
 if(p.length<9) return;
 var s0=proyectar(B,p[0],p[1],p[2]), ax=s0[0], ay=s0[1];
 var s1=proyectar(B,p[3],p[4],p[5]), bx=s1[0], by=s1[1];
 var s2=proyectar(B,p[6],p[7],p[8]), cx=s2[0], cy=s2[1];
 if((bx-ax)*(cy-ay)-(by-ay)*(cx-ax)<=0) return;
 if(camino(B,p)) return;
 var n=normalDe(p);
 var sol=D.sol||B.f;
 var luz=0.42+0.58*Math.abs(n[0]*sol[0]+n[1]*sol[1]+n[2]*sol[2]);
 ctx.fillStyle=color(l.c,l.o===undefined?1:l.o,luz);
 ctx.fill();
 // Un filo del mismo tono tapa la costura blanca entre caras vecinas.
 ctx.strokeStyle=ctx.fillStyle; ctx.lineWidth=1; ctx.stroke();
}
// Una imagen puesta en el espacio: la textura encajada en sus cuatro esquinas. Con tres
// esquinas proyectadas se arma la transformación afín que la lleva ahí.
function imagen(B,im){
 var t=texturas[im.u];
 if(t===undefined){
  t=new Image();
  t.onload=function(){repintar();};
  t.src=im.u;
  texturas[im.u]=t;
 }
 if(!t.complete||!t.naturalWidth) return;
 var e=im.e;
 var p0=proyectar(B,e[0],e[1],e[2]).slice(0,2);
 var p1=proyectar(B,e[3],e[4],e[5]).slice(0,2);
 var p3=proyectar(B,e[9],e[10],e[11]).slice(0,2);
 var iw=t.naturalWidth, ih=t.naturalHeight;
 var a=(p1[0]-p0[0])/iw, b=(p1[1]-p0[1])/iw;
 var c=(p3[0]-p0[0])/ih, d=(p3[1]-p0[1])/ih;
 if(!isFinite(a)||!isFinite(d)||Math.abs(a*d-b*c)<1e-12) return;
 ctx.save();
 ctx.globalAlpha=im.o===undefined?1:im.o;
 ctx.setTransform(a*dpr,b*dpr,c*dpr,d*dpr,p0[0]*dpr,p0[1]*dpr);
 ctx.drawImage(t,0,0);
 ctx.restore();
 ctx.setTransform(dpr,0,0,dpr,0,0);
}
// El corro donde se arma un trazo: uno solo, no uno por trazo y por fotograma.
var corro={xs:new Float64Array(20000),ys:new Float64Array(20000),ws:new Float64Array(20000)};

/**
 * El contorno de una cinta: se va por un borde y se vuelve por el otro.
 * [parte] es qué fracción del ancho ocupa; [ox],[oy] hacia dónde se corre —la luz—, y
 * cuánto se corre sale de lo que le sobra de ancho, así que el lomo nunca se sale del trazo.
 */
function cinta(xs,ys,ws,n,parte,ox,oy){
 var corre=(ox!==undefined);
 var i,nx,ny,d,wi,corrido;
 ctx.beginPath();
 for(i=0;i<n;i++){
  var a=i>0?i-1:0, b=i<n-1?i+1:n-1;
  nx=-(ys[b]-ys[a]); ny=xs[b]-xs[a];
  d=Math.hypot(nx,ny); if(d<1e-9){nx=0;ny=1;d=1;}
  nx/=d; ny/=d;
  wi=ws[i]*parte;
  corrido=corre?(nx*ox+ny*oy)*(ws[i]-wi):0;
  if(i===0) ctx.moveTo(xs[i]+nx*(corrido+wi), ys[i]+ny*(corrido+wi));
  else ctx.lineTo(xs[i]+nx*(corrido+wi), ys[i]+ny*(corrido+wi));
 }
 for(i=n-1;i>=0;i--){
  var a2=i>0?i-1:0, b2=i<n-1?i+1:n-1;
  nx=-(ys[b2]-ys[a2]); ny=xs[b2]-xs[a2];
  d=Math.hypot(nx,ny); if(d<1e-9){nx=0;ny=1;d=1;}
  nx/=d; ny/=d;
  wi=ws[i]*parte;
  corrido=corre?(nx*ox+ny*oy)*(ws[i]-wi):0;
  ctx.lineTo(xs[i]+nx*(corrido-wi), ys[i]+ny*(corrido-wi));
 }
 ctx.closePath();
}

// **Un trazo es una cinta, no una raya.** La aplicación lo pinta barriendo su sección por
// el mundo, y de ahí le vienen las dos cosas que se ven: **se afila en las puntas y engorda
// donde se apretó**, y **tiene lomo y flanco**. Exportado como una línea de grosor constante
// no se parecía. Aquí llega el ancho de cada muestra ya cocido (`t.a`), y con él se arman los
// dos bordes; la sombra es un lomo más claro corrido hacia la luz. Los trazos viejos —los que
// no traen anchos— siguen yendo como línea.
function trazo(B,t){
 var p=t.p, anchos=t.a;
 var orto=B.campo<=0;
 var dx=B.dx,dy=B.dy,dz=B.dz,ax=B.ax,ay=B.ay,az=B.az;
 var cx=B.cx,cy=B.cy,cz=B.cz,zz=B.z,mw=B.mw,mh=B.mh;
 var minx=1e9,miny=1e9,maxx=-1e9,maxy=-1e9, px=0,py=0, n=0;
 // Los puntos que caben en el mismo medio píxel no se pintan: es lo que deja alejarse sin
 // tirones en un croquis de miles de muestras.
 var xs=corro.xs, ys=corro.ys, ws=corro.ws;
 for(var i=0;i<p.length;i+=3){
  var x,y;
  if(orto){
   var vx=p[i]-cx, vy=p[i+1]-cy, vz=p[i+2]-cz;
   x=mw+(vx*dx+vy*dy+vz*dz)*zz; y=mh-(vx*ax+vy*ay+vz*az)*zz;
  } else { var s=proyectar(B,p[i],p[i+1],p[i+2]); x=s[0]; y=s[1]; }
  if(x<minx)minx=x; if(x>maxx)maxx=x; if(y<miny)miny=y; if(y>maxy)maxy=y;
  var k=i/3;
  if(n>0 && Math.abs(x-px)+Math.abs(y-py)<0.5 && i<p.length-3) continue;
  if(n>=xs.length) break;
  xs[n]=x; ys[n]=y;
  ws[n]=Math.max((anchos?anchos[k]:t.w)*zz/2,0.4);
  px=x; py=y; n++;
 }
 if(n===0) return;
 var gordo=t.w*zz+2;
 if(maxx< -gordo||minx>w+gordo||maxy< -gordo||miny>h+gordo) return;
 var opaco=t.o===undefined?1:t.o;
 // Lo que entero cabe en un punto se pinta como un punto.
 if(maxx-minx<ws[0]*2 && maxy-miny<ws[0]*2){
  ctx.beginPath(); ctx.arc((minx+maxx)/2,(miny+maxy)/2,Math.max(ws[0],0.5),0,6.2832);
  ctx.fillStyle=color(t.c,opaco); ctx.fill(); return;
 }
 if(n===1||!anchos){
  ctx.beginPath();
  for(var q=0;q<n;q++){ if(q===0) ctx.moveTo(xs[q],ys[q]); else ctx.lineTo(xs[q],ys[q]); }
  ctx.strokeStyle=color(t.c,opaco);
  ctx.lineWidth=Math.max(t.w*zz,0.75);
  ctx.stroke();
  return;
 }
 // La luz, en la pantalla: de ella sale hacia qué lado corre el lomo.
 var L=D.luz||[0,0,-1];
 var lx=L[0]*dx+L[1]*dy+L[2]*dz, ly=-(L[0]*ax+L[1]*ay+L[2]*az);
 cinta(xs,ys,ws,n,1);
 ctx.fillStyle=color(t.c,opaco); ctx.fill();
 // El lomo: la misma cinta más estrecha, corrida hacia la luz y un punto más clara. Dos
 // rellenos por trazo es lo que cuesta que se lea el bulto.
 if(ws[0]>1.2||ws[(n/2)|0]>1.2){
  cinta(xs,ys,ws,n,0.42,lx,ly);
  ctx.fillStyle=color(t.c,opaco,1.34);
  ctx.fill();
 }
}
function pintarMedida(B){
 var pts=medida.map(function(p){var s=proyectar(B,p[0],p[1],p[2]);return [s[0],s[1]];});
 ctx.save();
 ctx.strokeStyle='#ff8a3d'; ctx.fillStyle='#ff8a3d'; ctx.lineWidth=2;
 if(pts.length===2){ctx.beginPath();ctx.moveTo(pts[0][0],pts[0][1]);ctx.lineTo(pts[1][0],pts[1][1]);ctx.stroke();}
 for(var i=0;i<pts.length;i++){ctx.beginPath();ctx.arc(pts[i][0],pts[i][1],5,0,6.2832);ctx.fill();}
 ctx.restore();
}
function repintar(){ if(!pendiente&&vivo){pendiente=true; requestAnimationFrame(pintar);} }

function medir(){
 var r=caja.getBoundingClientRect();
 w=Math.max(r.width,1); h=Math.max(r.height,1);
 dpr=Math.min(window.devicePixelRatio||1,2); // por encima de dos no se nota y cuesta el cuádruple
 lienzo.width=Math.round(w*dpr); lienzo.height=Math.round(h*dpr);
 lienzo.style.width=w+'px'; lienzo.style.height=h+'px';
 if(lienzoGl){ lienzoGl.width=lienzo.width; lienzoGl.height=lienzo.height;
  lienzoGl.style.width=lienzo.style.width; lienzoGl.style.height=lienzo.style.height; }
 repintar();
}

// ---- La tarjeta gráfica ----
//
// Es lo que pedía el usuario para el croquis: que se mueva como el plano. Todo lo dibujado se
// sube **una vez** a la tarjeta —los trazos como cintas que se ensanchan en el propio programa
// de vértices, las hojas y los sólidos como triángulos, las imágenes como texturas— y a partir
// de ahí pintar es mandarle la cámara. La proyección es la misma que [proyectar], línea a
// línea, con la lente y el ojo de pez incluidos; la profundidad la decide la tarjeta píxel a
// píxel, que es mejor que el orden por centros del lienzo 2D. Si no hay tarjeta, se sigue con
// el lienzo 2D de siempre.
var PROYECCION=
 'uniform vec3 ud,ua,uf,uc;uniform float uz,ufocal,uojo,ucampo,urect,umw,umh,uhondo;'+
 'vec3 proy(vec3 P){vec3 v=P-uc;float u=dot(v,ud),vv=dot(v,ua),hd=dot(v,uf);'+
 ' if(ucampo<=0.0) return vec3(umw+u*uz,umh-vv*uz,hd);'+
 ' float radio=sqrt(u*u+vv*vv),prof=hd+uojo;'+
 ' if(radio<1e-9) return vec3(umw,umh,hd);'+
 ' float ang=atan(radio,prof);'+
 ' float en=ufocal*(urect>0.5?tan(min(ang,1.3962634)):ang);'+
 ' return vec3(umw+en*u/radio,umh-en*vv/radio,hd);}'+
 'uniform vec2 upx;'+
 'vec4 clip(vec3 s){return vec4(s.x*upx.x-1.0,1.0-s.y*upx.y,clamp(s.z/uhondo,-0.999,0.999),1.0);}';
function programa(vs,fs){
 function sh(t,src){var o=gl.createShader(t);gl.shaderSource(o,src);gl.compileShader(o);
  if(!gl.getShaderParameter(o,gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(o)); return o;}
 var p=gl.createProgram();
 gl.attachShader(p,sh(gl.VERTEX_SHADER,vs)); gl.attachShader(p,sh(gl.FRAGMENT_SHADER,fs));
 gl.linkProgram(p);
 if(!gl.getProgramParameter(p,gl.LINK_STATUS)) throw new Error('programa');
 return p;
}
function arrancarGl(){
 if(gl!==null) return gl;
 gl=false;
 try{
  var c=document.createElement('canvas');
  c.className='espacio gl';
  var g=c.getContext('webgl',{antialias:true,alpha:true,depth:true})||c.getContext('experimental-webgl',{antialias:true,alpha:true,depth:true});
  if(!g) return false;
  gl=g;
  GL={};
  // Las cintas: cada muestra son dos vértices, uno por lado, y el ancho se pone en pantalla.
  GL.cinta=programa(
   'attribute vec3 p,q0,q1;attribute float lado,medio;attribute vec4 tinta;'+PROYECCION+
   'varying vec4 vT;varying vec2 vN;varying float vL;'+
   'void main(){vec3 s=proy(p),s0=proy(q0),s1=proy(q1);vec2 t=s1.xy-s0.xy;float L=length(t);'+
   ' vec2 n=(L<1e-6)?vec2(0.0,1.0):vec2(-t.y,t.x)/L;float an=max(medio*uz,0.4);'+
   ' vec2 sc=s.xy+n*an*lado;gl_Position=clip(vec3(sc,s.z));vT=tinta;vN=n;vL=lado;}',
   // **Un trazo es un tubo, no una cinta plana.** La sección se sombrea redonda —más claro
   // en el lomo, más oscuro en los flancos— y el lado que mira a la luz brilla: es lo que en
   // la aplicación hace que un trazo cambie de tono al girar y se lea con bulto.
   'precision mediump float;varying vec4 vT;varying vec2 vN;varying float vL;uniform vec2 uluz;'+
   'void main(){float x=clamp(vL,-1.0,1.0);float tubo=0.58+0.5*sqrt(max(0.0,1.0-x*x));'+
   ' float brillo=max(0.0,dot(vN,uluz)*x)*0.45;'+
   ' vec3 c=vT.rgb*(tubo+brillo);gl_FragColor=vec4(min(c,vec3(vT.a)),vT.a);}');
  // Las caras: sólidos sombreados por su normal, hojas con su velo según lo de frente que se miren.
  GL.cara=programa(
   'attribute vec3 p,nrm;attribute vec4 tinta;attribute float clase;'+PROYECCION+
   'uniform vec3 usol;varying vec4 vT;'+
   'void main(){gl_Position=clip(proy(p));'+
   ' if(clase<0.5){float luz=0.42+0.58*abs(dot(nrm,usol));vT=vec4(min(tinta.rgb*luz,1.0),tinta.a);}'+
   ' else{float f=abs(dot(nrm,uf));float a=min((0.055+0.11*f)*tinta.a,0.35);'+
   '  vT=vec4(tinta.rgb,clase<1.5?a:min(a*2.4,0.55));}}',
   'precision mediump float;varying vec4 vT;void main(){gl_FragColor=vec4(vT.rgb*vT.a,vT.a);}');
  // Las imágenes: un cuadrilátero con su textura.
  GL.foto=programa(
   'attribute vec3 p;attribute vec2 uv;'+PROYECCION+'varying vec2 vUv;'+
   'void main(){gl_Position=clip(proy(p));vUv=uv;}',
   'precision mediump float;varying vec2 vUv;uniform sampler2D tex;uniform float uop;'+
   'void main(){vec4 c=texture2D(tex,vUv);gl_FragColor=vec4(c.rgb*c.a*uop,c.a*uop);}');
  lienzoGl=c;
  caja.insertBefore(c, lienzo);      // debajo del lienzo 2D, que se queda para la medida
  gl.enable(gl.DEPTH_TEST); gl.depthFunc(gl.LEQUAL);
  gl.enable(gl.BLEND); gl.blendFunc(gl.ONE, gl.ONE_MINUS_SRC_ALPHA);
  subirGl();
  medir();
  return gl;
 }catch(e){ gl=false; if(lienzoGl&&lienzoGl.parentNode) lienzoGl.parentNode.removeChild(lienzoGl); lienzoGl=null; return false; }
}
function tintaDe(hex,op){ var c=rgb(hex); var a=(op===undefined?1:op); return [c[0]/255*a,c[1]/255*a,c[2]/255*a,a]; }
function buffer(datos){ var b=gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER,b); gl.bufferData(gl.ARRAY_BUFFER,datos,gl.STATIC_DRAW); return b; }
// Todo lo dibujado, a la tarjeta, ordenado por grupo para poder apagar un grupo sin resubir.
function subirGl(){
 var i,j,k;
 // ---- Las cintas: 15 números por vértice (p, q0, q1, lado, medio, tinta) ----
 var nv=0; for(i=0;i<D.tr.length;i++) nv+=(D.tr[i].p.length/3)*2+2;
 var cintas=new Float32Array(nv*15), n=0;
 GL.tramosCinta=[];
 var grupos=[]; D.tr.forEach(function(t){var g=t.g||''; if(grupos.indexOf(g)<0) grupos.push(g);});
 function vert(P,Q0,Q1,lado,medio,tinta){
  cintas[n]=P[0];cintas[n+1]=P[1];cintas[n+2]=P[2];
  cintas[n+3]=Q0[0];cintas[n+4]=Q0[1];cintas[n+5]=Q0[2];
  cintas[n+6]=Q1[0];cintas[n+7]=Q1[1];cintas[n+8]=Q1[2];
  cintas[n+9]=lado;cintas[n+10]=medio;
  cintas[n+11]=tinta[0];cintas[n+12]=tinta[1];cintas[n+13]=tinta[2];cintas[n+14]=tinta[3];
  n+=15;
 }
 for(k=0;k<grupos.length;k++){
  var desde=n/15;
  for(i=0;i<D.tr.length;i++){
   var t=D.tr[i]; if((t.g||'')!==grupos[k]) continue;
   var p=t.p, m=p.length/3; if(m<1) continue;
   var tinta=tintaDe(t.c,t.o);
   for(j=0;j<m;j++){
    var P=[p[j*3],p[j*3+1],p[j*3+2]];
    var a=j>0?j-1:j, b=j<m-1?j+1:j;
    var Q0=[p[a*3],p[a*3+1],p[a*3+2]], Q1=[p[b*3],p[b*3+1],p[b*3+2]];
    var medio=(t.a?t.a[j]:t.w)/2;
    if(j===0) vert(P,Q0,Q1,-1,medio,tinta);      // el triángulo degenerado que separa cintas
    vert(P,Q0,Q1,-1,medio,tinta); vert(P,Q0,Q1,1,medio,tinta);
    if(j===m-1) vert(P,Q0,Q1,1,medio,tinta);
   }
  }
  GL.tramosCinta.push({g:grupos[k],desde:desde,cuantos:n/15-desde});
 }
 GL.bCinta=buffer(cintas.subarray(0,n)); GL.nCinta=n/15;
 // ---- Las caras: 11 números por vértice (p, nrm, tinta, clase) ----
 var caras=[], bordes=[];
 function tri(lista,P,N,T,clase){ lista.push(P[0],P[1],P[2],N[0],N[1],N[2],T[0],T[1],T[2],T[3],clase); }
 function abanico(lista,p,N,T,clase){
  for(var q=6;q<p.length;q+=3){
   tri(lista,[p[0],p[1],p[2]],N,T,clase); tri(lista,[p[q-3],p[q-2],p[q-1]],N,T,clase); tri(lista,[p[q],p[q+1],p[q+2]],N,T,clase);
  }
 }
 GL.hojas=[]; GL.solidos=[];
 for(i=0;i<D.ho.length;i++){
  var l=D.ho[i], T=tintaDe(l.c,1); T[3]=(l.o===undefined?1:l.o);
  if(l.k){
   var desdeS=caras.length/11;
   for(j=0;j<l.s.length;j++){ var f=l.s[j]; if(f.length<9) continue; abanico(caras,f,normalDe(f),T,0); }
   GL.solidos.push({o:l,desde:desdeS,cuantos:caras.length/11-desdeS});
  } else if(l.b.length>=9){
   var N=l.n||normalDe(l.b), desdeH=caras.length/11;
   abanico(caras,l.b,N,T,1);
   var desdeB=bordes.length/11;
   for(j=0;j<l.b.length;j+=3){ var q2=(j+3)%l.b.length;
    tri(bordes,[l.b[j],l.b[j+1],l.b[j+2]],N,T,2); tri(bordes,[l.b[q2],l.b[q2+1],l.b[q2+2]],N,T,2); }
   GL.hojas.push({o:l,desde:desdeH,cuantos:caras.length/11-desdeH,bdesde:desdeB,bcuantos:bordes.length/11-desdeB});
  }
 }
 GL.bCara=buffer(new Float32Array(caras)); GL.bBorde=buffer(new Float32Array(bordes));
 // ---- Las imágenes: cuatro esquinas y su textura, que se sube cuando carga ----
 GL.fotos=D.im.map(function(im){
  var e=im.e, v=new Float32Array([e[0],e[1],e[2],0,0, e[3],e[4],e[5],1,0, e[9],e[10],e[11],0,1,
                                  e[3],e[4],e[5],1,0, e[6],e[7],e[8],1,1, e[9],e[10],e[11],0,1]);
  return {o:im,b:buffer(v),tex:null};
 });
 var lados=D.cj?Math.hypot(D.cj[3]-D.cj[0],D.cj[4]-D.cj[1],D.cj[5]-D.cj[2]):100;
 GL.hondo=Math.max(lados*4,1);
}
function camaraGl(prog,B){
 gl.useProgram(prog);
 gl.uniform3f(gl.getUniformLocation(prog,'ud'),B.dx,B.dy,B.dz);
 gl.uniform3f(gl.getUniformLocation(prog,'ua'),B.ax,B.ay,B.az);
 gl.uniform3f(gl.getUniformLocation(prog,'uf'),B.fx,B.fy,B.fz);
 gl.uniform3f(gl.getUniformLocation(prog,'uc'),B.cx,B.cy,B.cz);
 gl.uniform1f(gl.getUniformLocation(prog,'uz'),B.z);
 gl.uniform1f(gl.getUniformLocation(prog,'ufocal'),B.focal);
 gl.uniform1f(gl.getUniformLocation(prog,'uojo'),B.ojo);
 gl.uniform1f(gl.getUniformLocation(prog,'ucampo'),B.campo);
 gl.uniform1f(gl.getUniformLocation(prog,'urect'),B.r?1:0);
 gl.uniform1f(gl.getUniformLocation(prog,'umw'),B.mw);
 gl.uniform1f(gl.getUniformLocation(prog,'umh'),B.mh);
 gl.uniform1f(gl.getUniformLocation(prog,'uhondo'),GL.hondo);
 gl.uniform2f(gl.getUniformLocation(prog,'upx'),2/w,2/h);
}
function atributo(prog,nombre,cuantos,paso,desde){
 var a=gl.getAttribLocation(prog,nombre); if(a<0) return;
 gl.enableVertexAttribArray(a); gl.vertexAttribPointer(a,cuantos,gl.FLOAT,false,paso*4,desde*4);
}
function pintarGl(B){
 var i;
 gl.viewport(0,0,lienzoGl.width,lienzoGl.height);
 gl.clearColor(0,0,0,0); gl.clear(gl.COLOR_BUFFER_BIT|gl.DEPTH_BUFFER_BIT);
 gl.depthMask(true);
 // Los sólidos, opacos, con la luz.
 if(GL.solidos.length){
  camaraGl(GL.cara,B);
  var sol=D.sol||[B.fx,B.fy,B.fz];
  gl.uniform3f(gl.getUniformLocation(GL.cara,'usol'),sol[0],sol[1],sol[2]);
  gl.bindBuffer(gl.ARRAY_BUFFER,GL.bCara);
  atributo(GL.cara,'p',3,11,0); atributo(GL.cara,'nrm',3,11,3); atributo(GL.cara,'tinta',4,11,6); atributo(GL.cara,'clase',1,11,10);
  for(i=0;i<GL.solidos.length;i++){ var s=GL.solidos[i]; if(visible(s.o)&&s.cuantos) gl.drawArrays(gl.TRIANGLES,s.desde,s.cuantos); }
 }
 // Las imágenes.
 for(i=0;i<GL.fotos.length;i++){
  var f=GL.fotos[i]; if(!visible(f.o)) continue;
  if(!f.tex){
   var im=texturas[f.o.u];
   if(im===undefined){ im=new Image(); im.onload=function(){repintar();}; im.src=f.o.u; texturas[f.o.u]=im; }
   if(!im.complete||!im.naturalWidth) continue;
   f.tex=gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D,f.tex);
   gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_S,gl.CLAMP_TO_EDGE); gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_T,gl.CLAMP_TO_EDGE);
   gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MIN_FILTER,gl.LINEAR); gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MAG_FILTER,gl.LINEAR);
   gl.texImage2D(gl.TEXTURE_2D,0,gl.RGBA,gl.RGBA,gl.UNSIGNED_BYTE,im);
  }
  camaraGl(GL.foto,B);
  gl.activeTexture(gl.TEXTURE0); gl.bindTexture(gl.TEXTURE_2D,f.tex);
  gl.uniform1i(gl.getUniformLocation(GL.foto,'tex'),0);
  gl.uniform1f(gl.getUniformLocation(GL.foto,'uop'),f.o.o===undefined?1:f.o.o);
  gl.bindBuffer(gl.ARRAY_BUFFER,f.b);
  atributo(GL.foto,'p',3,5,0); atributo(GL.foto,'uv',2,5,3);
  gl.drawArrays(gl.TRIANGLES,0,6);
 }
 // Los trazos, como cintas.
 if(GL.nCinta){
  camaraGl(GL.cinta,B);
  var L=D.luz||[0,0,-1];
  var lx=L[0]*B.dx+L[1]*B.dy+L[2]*B.dz, ly=-(L[0]*B.ax+L[1]*B.ay+L[2]*B.az);
  gl.uniform2f(gl.getUniformLocation(GL.cinta,'uluz'),lx,ly);
  gl.bindBuffer(gl.ARRAY_BUFFER,GL.bCinta);
  atributo(GL.cinta,'p',3,15,0); atributo(GL.cinta,'q0',3,15,3); atributo(GL.cinta,'q1',3,15,6);
  atributo(GL.cinta,'lado',1,15,9); atributo(GL.cinta,'medio',1,15,10); atributo(GL.cinta,'tinta',4,15,11);
  for(i=0;i<GL.tramosCinta.length;i++){ var tr=GL.tramosCinta[i];
   if(!apagados[tr.g]&&tr.cuantos) gl.drawArrays(gl.TRIANGLE_STRIP,tr.desde,tr.cuantos); }
 }
 // Las hojas, translúcidas, de lejos a cerca y sin escribir profundidad.
 if(GL.hojas.length){
  gl.depthMask(false);
  camaraGl(GL.cara,B);
  var orden=GL.hojas.filter(function(hj){return visible(hj.o);});
  orden.sort(function(a,b){return hondura(B,b.o.b,b.o)-hondura(B,a.o.b,a.o);});
  gl.bindBuffer(gl.ARRAY_BUFFER,GL.bCara);
  atributo(GL.cara,'p',3,11,0); atributo(GL.cara,'nrm',3,11,3); atributo(GL.cara,'tinta',4,11,6); atributo(GL.cara,'clase',1,11,10);
  for(i=0;i<orden.length;i++) if(orden[i].cuantos) gl.drawArrays(gl.TRIANGLES,orden[i].desde,orden[i].cuantos);
  gl.bindBuffer(gl.ARRAY_BUFFER,GL.bBorde);
  atributo(GL.cara,'p',3,11,0); atributo(GL.cara,'nrm',3,11,3); atributo(GL.cara,'tinta',4,11,6); atributo(GL.cara,'clase',1,11,10);
  for(i=0;i<orden.length;i++) if(orden[i].bcuantos) gl.drawArrays(gl.LINES,orden[i].bdesde,orden[i].bcuantos);
  gl.depthMask(true);
 }
}

// ---- Encajar y el eje del giro ----
function cajaDe(filtro){
 var c=[1e18,1e18,1e18,-1e18,-1e18,-1e18], hay=false;
 function mete(p){for(var i=0;i<p.length;i+=3){hay=true;
  if(p[i]<c[0])c[0]=p[i]; if(p[i]>c[3])c[3]=p[i];
  if(p[i+1]<c[1])c[1]=p[i+1]; if(p[i+1]>c[4])c[4]=p[i+1];
  if(p[i+2]<c[2])c[2]=p[i+2]; if(p[i+2]>c[5])c[5]=p[i+2];}}
 D.tr.forEach(function(t){if(filtro(t))mete(t.p);});
 D.ho.forEach(function(l){if(filtro(l)){ if(l.k)l.s.forEach(mete); else mete(l.b); }});
 D.im.forEach(function(im){if(filtro(im))mete(im.e);});
 return hay?c:null;
}
function cajaVisible(){ return cajaDe(visible)||D.cj; }
function centroDe(c){ return [(c[0]+c[3])/2,(c[1]+c[4])/2,(c[2]+c[5])/2]; }
function refrescarPivote(){ pivote=centroDe(cajaVisible()); }
function encajar(c){
 c=c||cajaVisible();
 cam.c=centroDe(c);
 var z=cam.z; cam.z=1;
 var B=base(), r=0;
 for(var i=0;i<8;i++){
  var s=proyectar(B, i&1?c[3]:c[0], i&2?c[4]:c[1], i&4?c[5]:c[2]);
  r=Math.max(r, Math.abs(s[0]-B.mw), Math.abs(s[1]-B.mh));
 }
 cam.z=r>1e-6?Math.min(Math.max(Math.min(w,h)*0.42/r, ZOOM_MIN), ZOOM_MAX):z;
 refrescarPivote();
 repintar();
}

// **Girar alrededor del croquis.** El punto que se toma por eje no se mueve de la pantalla:
// se apunta dónde cae antes de girar y se recoloca la cámara para que caiga en el mismo
// sitio después. Sin esto, con el croquis apartado del centro de la cámara, girar lo lanza
// fuera de la pantalla y parece que todo está al revés.
function girar(dx,dy){
 if(!pivote) refrescarPivote();
 var P=pivote, B=base();
 var vx=P[0]-cam.c[0], vy=P[1]-cam.c[1], vz=P[2]-cam.c[2];
 var u=vx*B.d[0]+vy*B.d[1]+vz*B.d[2], v=vx*B.a[0]+vy*B.a[1]+vz*B.a[2];
 // **El mismo sentido que en la aplicación** (`Camara3D.girada`: el giro crece con el
 // arrastre hacia la derecha). Iba al revés y el usuario lo sentía invertido.
 cam.g+=dx/VUELTA_ENTERA*Math.PI*2;
 cam.i=Math.min(Math.max(cam.i+dy/VUELTA_ENTERA*Math.PI*2,-TOPE_INC),TOPE_INC);
 var C=base();
 cam.c=[P[0]-u*C.d[0]-v*C.a[0], P[1]-u*C.d[1]-v*C.a[1], P[2]-u*C.d[2]-v*C.a[2]];
}
function desplazar(dx,dy){
 var B=base();
 var k=B.campo>0?(B.ojo/Math.max(B.focal,1e-6)):(1/cam.z);
 cam.c=[cam.c[0]-(B.d[0]*dx-B.a[0]*dy)*k, cam.c[1]-(B.d[1]*dx-B.a[1]*dy)*k,
        cam.c[2]-(B.d[2]*dx-B.a[2]*dy)*k];
}
function acercar(f,cx,cy){
 var z=Math.min(Math.max(cam.z*f,ZOOM_MIN),ZOOM_MAX);
 if(z===cam.z) return;
 var B=base(), k=(1/cam.z-1/z);
 var r=caja.getBoundingClientRect();
 var dx=(cx===undefined?w/2:cx-r.left)-B.mw, dy=(cy===undefined?h/2:cy-r.top)-B.mh;
 if(B.campo<=0) cam.c=[cam.c[0]+(B.d[0]*dx-B.a[0]*dy)*k, cam.c[1]+(B.d[1]*dx-B.a[1]*dy)*k,
                       cam.c[2]+(B.d[2]*dx-B.a[2]*dy)*k];
 cam.z=z;
}

// ---- El dedo ----
var punteros=new Map(), gesto=null, movido=0;
function muestras(e){ return (e.getCoalescedEvents&&e.getCoalescedEvents())||[e]; }
lienzo.addEventListener('pointerdown',function(e){
 if(e.pointerType==='mouse'&&e.button!==0&&e.button!==2) return;
 lienzo.setPointerCapture(e.pointerId);
 punteros.set(e.pointerId,{x:e.clientX,y:e.clientY,boton:e.button});
 movido=0;
 if(punteros.size===2){var v=Array.from(punteros.values());
  gesto={d:Math.hypot(v[0].x-v[1].x,v[0].y-v[1].y),x:(v[0].x+v[1].x)/2,y:(v[0].y+v[1].y)/2};}
 else gesto=null;
});
lienzo.addEventListener('contextmenu',function(e){e.preventDefault();});
lienzo.addEventListener('pointermove',function(e){
 if(!punteros.has(e.pointerId)) return;
 e.preventDefault();
 var ms=muestras(e), a=punteros.get(e.pointerId);
 for(var k=0;k<ms.length;k++){
  var m=ms[k];
  var dx=m.clientX-a.x, dy=m.clientY-a.y;
  a.x=m.clientX; a.y=m.clientY;
  movido+=Math.abs(dx)+Math.abs(dy);
  if(punteros.size===1){
   // El botón derecho y el modo mover desplazan; lo demás gira. Es lo de siempre.
   if(modo==='mover'||a.boton===2) desplazar(dx,dy);
   else if(modo==='girar') girar(dx,dy);
  }
 }
 if(punteros.size===2 && gesto){
  var v=Array.from(punteros.values());
  var d=Math.hypot(v[0].x-v[1].x,v[0].y-v[1].y), cx=(v[0].x+v[1].x)/2, cy=(v[0].y+v[1].y)/2;
  if(gesto.d>0) acercar(d/gesto.d, cx, cy);
  desplazar(cx-gesto.x, cy-gesto.y);
  gesto={d:d,x:cx,y:cy};
 }
 repintar();
});
function fin(e){ punteros.delete(e.pointerId); if(punteros.size<2) gesto=null; }
lienzo.addEventListener('pointerup',function(e){
 if(modo==='medir'&&punteros.size===1&&movido<12) tomarPunto(e);
 fin(e);
});
lienzo.addEventListener('pointercancel',fin);
lienzo.addEventListener('wheel',function(e){
 e.preventDefault();
 acercar(Math.pow(0.999,e.deltaY), e.clientX, e.clientY);
 repintar();
},{passive:false});
lienzo.addEventListener('dblclick',function(){encajar();});

// ---- Medir ----
function tomarPunto(e){
 var B=base(), mejor=null, cerca=24*24;
 var r=caja.getBoundingClientRect(), ex=e.clientX-r.left, ey=e.clientY-r.top;
 function mira(p){
  for(var i=0;i<p.length;i+=3){
   var s=proyectar(B,p[i],p[i+1],p[i+2]);
   var d=(s[0]-ex)*(s[0]-ex)+(s[1]-ey)*(s[1]-ey);
   if(d<cerca){cerca=d; mejor=[p[i],p[i+1],p[i+2]];}
  }
 }
 D.tr.forEach(function(t){if(visible(t))mira(t.p);});
 D.ho.forEach(function(l){if(visible(l)){ if(l.k)l.s.forEach(mira); else mira(l.b); }});
 D.im.forEach(function(im){if(visible(im))mira(im.e);});
 if(!mejor){ api.decir('Toca cerca de un punto dibujado'); return; }
 if(medida.length>=2) medida=[];
 medida.push(mejor);
 if(medida.length===2){
  var a=medida[0], b=medida[1];
  api.decir('Distancia: '+Math.hypot(b[0]-a[0],b[1]-a[1],b[2]-a[2]).toFixed(2)+' u');
 } else api.decir('Primer punto puesto: toca el segundo');
 repintar();
}

refrescarPivote();
return {
 activar:function(){ vivo=true; medir(); },
 desactivar:function(){ vivo=false; },
 medir:medir,
 repintar:repintar,
 encajar:function(){ encajar(); },
 // Los botones de acercar y alejar **no hacían nada aquí**: el visor del espacio no
 // ofrecía con qué. En un teléfono no hay rueda, así que sin ellos acercarse era cosa de
 // dos dedos o de nada. El signo es el del visor del dibujo: menos de uno es acercarse.
 zoom:function(f){ acercar(1/f); repintar(); },
 modo:function(m){ modo=m; medida=[]; if(m==='medir') api.decir('Toca dos puntos del dibujo'); else api.decir(''); repintar(); },
 herramientas:['girar','mover','medir'].filter(function(h){
  return (document.body.dataset.herramientas||'girar mover medir').split(' ').indexOf(h)>=0; }),
 vistas:function(){
  var l=[{n:'Como se exportó',ir:function(){cam=clonar(inicial);refrescarPivote();repintar();}},
         {n:'Encajar todo',ir:function(){encajar(D.cj);}}];
  D.vi.forEach(function(v){l.push({n:v.n,ir:function(){cam=clonar(v.c);refrescarPivote();repintar();}});});
  return l;
 },
 grupos:function(){
  var l=D.gr.slice();
  var sueltos=D.tr.some(function(t){return !t.g;})||D.ho.some(function(o){return !o.g;})||
              D.im.some(function(o){return !o.g;});
  if(sueltos) l.push({i:'',n:'Sin grupo'});
  return l.map(function(g){ return {
   id:g.i, nombre:g.n,
   puesto:function(){return !apagados[g.i];},
   poner:function(si){ apagados[g.i]=!si; refrescarPivote(); repintar(); },
   acercarse:function(){ var c=cajaDe(function(o){return (o.g||'')===g.i;}); if(c) encajar(c); }
  };});
 }
};
}
"""
}
