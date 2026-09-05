package com.forge.pixpin.motor

/**
 * **El visor del plano dentro del documento web**: el PDF pintado como líneas, no como foto.
 *
 * Va en un lienzo (`<canvas>`) **debajo** del SVG del dibujo, en las mismas unidades y con el
 * mismo encuadre, así que lo anotado sigue siendo SVG —se raya, se borra, se guarda— y el
 * papel de debajo es geometría: se amplía sin grano y pesa lo que pesa un archivo de texto
 * comprimido, no lo que pesa una imagen de cuatro mil píxeles. Ver [PlanoDePdf] y [PlanoWeb].
 *
 * ## Lo que hace que no dé tirones
 *
 * Un plano de verdad son un millón y medio de puntos, y eso no se repinta a sesenta veces por
 * segundo en un teléfono. Tres cosas lo arreglan:
 *
 * - **Mientras se mueve o se amplía, no se repinta**: el lienzo ya pintado se estira con una
 *   transformación de CSS —que la hace la tarjeta gráfica— y solo cuando la mano se para se
 *   vuelve a pintar de verdad. Es lo mismo que hace un mapa.
 * - **Nivel de detalle**: dos puntos que en la pantalla caen a menos de medio píxel son el
 *   mismo punto, así que se saltan. Mirando el plano entero eso quita el noventa por ciento
 *   del trabajo, y no se nota porque no se puede ver.
 * - **Se tira lo que no se mira**: cada camino trae su caja, y el que cae fuera de la pantalla
 *   ni se recorre.
 *
 * Y los datos llegan en enteros —`Int32Array`, no objetos con `.x`— porque leer un campo de un
 * objeto dentro de un bucle de un millón de vueltas cuesta doscientas cincuenta veces más que
 * leer un número de un array. Es la misma lección que [VisorEspacio].
 */
object VisorPlano {

    /**
     * **El descompresor**, en cien líneas y sin depender de nada.
     *
     * Los datos del plano viajan comprimidos con el mismo algoritmo que un ZIP (DEFLATE, RFC
     * 1951): comprimen a la séptima parte, y eso es lo que separa un archivo que se manda por
     * WhatsApp de uno que no. El navegador trae un descompresor —`DecompressionStream`— pero
     * es reciente y además funciona por promesas; esto son cien líneas, va donde sea y
     * devuelve los bytes sin esperar a nadie.
     */
    val INFLAR = """
function inflar(datos){
"use strict";
var pos=0, bit=0, salida=new Uint8Array(1<<18), n=0;
function sitio(k){ if(n+k>salida.length){ var g=new Uint8Array(Math.max(salida.length*2,n+k)); g.set(salida); salida=g; } }
function bits(k){ var v=0,i=0; while(i<k){ v|=((datos[pos]>>bit)&1)<<i; bit++; if(bit===8){bit=0;pos++;} i++; } return v; }
function mirar(k){ var v=0,p=pos,b=bit,i=0; while(i<k){ v|=((p<datos.length?datos[p]:0)>>b&1)<<i; b++; if(b===8){b=0;p++;} i++; } return v; }
function saltar(k){ bit+=k; pos+=bit>>3; bit&=7; }
// La tabla canónica de Huffman: el prefijo leído al revés dice qué símbolo es y cuántos bits
// ocupa, de una sola consulta.
function tabla(largos,max){
  var t=new Int32Array(1<<max), cuenta=new Int32Array(max+1), i, l;
  for(i=0;i<largos.length;i++) cuenta[largos[i]]++;
  cuenta[0]=0;
  var codigo=0, siguiente=new Int32Array(max+2);
  for(l=1;l<=max;l++){ codigo=(codigo+cuenta[l-1])<<1; siguiente[l]=codigo; }
  for(i=0;i<largos.length;i++){
    l=largos[i]; if(!l) continue;
    var c=siguiente[l]++, r=0, j;
    for(j=0;j<l;j++) r=(r<<1)|((c>>j)&1);
    for(j=r;j<(1<<max);j+=(1<<l)) t[j]=(i<<4)|l;
  }
  t.max=max;
  return t;
}
function simbolo(t){ var v=t[mirar(t.max)]; if(!v) throw new Error('deflate'); saltar(v&15); return v>>4; }
var LARGOS=[3,4,5,6,7,8,9,10,11,13,15,17,19,23,27,31,35,43,51,59,67,83,99,115,131,163,195,227,258];
var EXTRA_L=[0,0,0,0,0,0,0,0,1,1,1,1,2,2,2,2,3,3,3,3,4,4,4,4,5,5,5,5,0];
var DIST=[1,2,3,4,5,7,9,13,17,25,33,49,65,97,129,193,257,385,513,769,1025,1537,2049,3073,4097,6145,8193,12289,16385,24577];
var EXTRA_D=[0,0,0,0,1,1,2,2,3,3,4,4,5,5,6,6,7,7,8,8,9,9,10,10,11,11,12,12,13,13];
var ORDEN=[16,17,18,0,8,7,9,6,10,5,11,4,12,3,13,2,14,1,15];
var fijoL=null, fijoD=null, i;
for(;;){
  var ultimo=bits(1), tipo=bits(2), tl, td;
  if(tipo===0){
    if(bit){bit=0;pos++;}
    var largo=datos[pos]|(datos[pos+1]<<8); pos+=4;
    sitio(largo); salida.set(datos.subarray(pos,pos+largo),n); n+=largo; pos+=largo;
  } else {
    if(tipo===1){
      if(!fijoL){
        var ll=new Uint8Array(288);
        for(i=0;i<144;i++)ll[i]=8; for(;i<256;i++)ll[i]=9; for(;i<280;i++)ll[i]=7; for(;i<288;i++)ll[i]=8;
        fijoL=tabla(ll,9);
        var dd=new Uint8Array(30); for(i=0;i<30;i++)dd[i]=5;
        fijoD=tabla(dd,5);
      }
      tl=fijoL; td=fijoD;
    } else if(tipo===2){
      var hlit=bits(5)+257, hdist=bits(5)+1, hclen=bits(4)+4;
      var clen=new Uint8Array(19);
      for(i=0;i<hclen;i++) clen[ORDEN[i]]=bits(3);
      var tc=tabla(clen,7), todos=new Uint8Array(hlit+hdist), k=0, r;
      while(k<todos.length){
        var s=simbolo(tc);
        if(s<16) todos[k++]=s;
        else if(s===16){ r=3+bits(2); var v2=todos[k-1]; while(r--) todos[k++]=v2; }
        else if(s===17){ r=3+bits(3); while(r--) todos[k++]=0; }
        else { r=11+bits(7); while(r--) todos[k++]=0; }
      }
      var maxL=0, maxD=0;
      for(i=0;i<hlit;i++) if(todos[i]>maxL)maxL=todos[i];
      for(i=hlit;i<todos.length;i++) if(todos[i]>maxD)maxD=todos[i];
      tl=tabla(todos.subarray(0,hlit),maxL||1);
      td=tabla(todos.subarray(hlit),maxD||1);
    } else throw new Error('deflate');
    for(;;){
      var s2=simbolo(tl);
      if(s2===256) break;
      if(s2<256){ sitio(1); salida[n++]=s2; }
      else {
        var li=s2-257, cuanto=LARGOS[li]+bits(EXTRA_L[li]);
        var ds=simbolo(td), dist=DIST[ds]+bits(EXTRA_D[ds]);
        sitio(cuanto);
        var desde=n-dist;
        for(var q=0;q<cuanto;q++) salida[n++]=salida[desde+q];
      }
    }
  }
  if(ultimo) break;
}
return salida.subarray(0,n);
}
function deBase64(s){
  var bin=atob(s), out=new Uint8Array(bin.length);
  for(var i=0;i<bin.length;i++) out[i]=bin.charCodeAt(i);
  return out;
}
"""

    /** El visor propiamente: descifra los datos y los pinta en el lienzo. */
    val JS = """
function crearPlano(caja, api){
"use strict";
var guion=caja.querySelector('script.plano');
var lienzo=caja.querySelector('canvas.plano');
if(!guion||!lienzo) return null;
var D=JSON.parse(guion.textContent);
var ctx=lienzo.getContext('2d',{alpha:true});
var esc=D.e;                      // unidades del dibujo por paso del punto fijo
var ops=null, xs=null, ys=null;   // las órdenes y los puntos, en pasos
var cx0=null, cy0=null, cx1=null, cy1=null, cop=null, cpt=null; // la caja de cada camino
var brochas=D.brochas||[], textos=D.textos||[], capas=D.capas||[], fotos=D.fotos||[];
var encendida=[], sueltaPuesta=true, soloLineas=false;
var vista=null, pintado=null, coste=0, pendiente=0, quieto=0, listo=false, sucio=false;
// **La tarjeta gráfica, si la hay.** Ver [arrancarGl]: con ella las rayas viven en su memoria
// y mover o acercar es cambiarle dos números a la cámara — no se repinta nada nunca.
var gl=null, lienzoGl=null, programa=null, buffer=null, tramos=null, uA=null, uB=null, uColor=null;

for(var i=0;i<capas.length;i++) encendida[i]=capas[i].v!==0;
// Lo que no venía en ninguna capa: en un PDF sin capas es **todo**, así que se enciende y se
// enseña como una fila más del cajón para poder apagarlo igual que a las demás.
function seVe(c){ return c<0 ? sueltaPuesta : encendida[c]!==false; }
function haySueltas(){
  for(var i=0;i<brochas.length;i++) if(brochas[i].c<0) return true;
  for(var j=0;j<textos.length;j++) if(textos[j].c<0) return true;
  for(var k=0;k<fotos.length;k++) if(fotos[k].c<0) return true;
  return false;
}
// Las fotos del PDF —un logotipo, una ortofoto— van tal cual venían dentro del archivo. Se
// piden al abrir y, cuando llegan, se repinta: es lo único de la página que no está listo
// desde el primer fotograma.
for(var f=0;f<fotos.length;f++)(function(t){
  var img=new Image();
  img.onload=function(){ t.img=img; sucio=true; programar(); };
  img.src=t.u;
})(fotos[f]);

// ---- Desempaquetar ----
// Se hace una sola vez, la primera que se mira esta página: descomprimir y recorrer un plano
// grande son unas décimas, y no hay por qué gastarlas si el documento se abre por otra hoja.
function desempaquetar(){
  if(listo) return;
  listo=true;
  var d=inflar(deBase64(D.datos)), p=0, i, j;
  var nOps=0;
  for(i=0;i<brochas.length;i++) nOps+=brochas[i].n;
  ops=new Uint8Array(nOps);
  // Los puntos no se saben antes de recorrerlo; se pide de sobra y se recorta al final.
  xs=new Int32Array(nOps*2); ys=new Int32Array(nOps*2);
  cx0=new Int32Array(nOps); cy0=new Int32Array(nOps);
  cx1=new Int32Array(nOps); cy1=new Int32Array(nOps);
  cop=new Int32Array(nOps+1); cpt=new Int32Array(nOps+1);
  var x=0, y=0, o=0, q=0, nCam=0, act=-1;
  function varint(){
    var r=0, s=0, c;
    do{ c=d[p++]; r|=(c&0x7f)<<s; s+=7; }while(c&0x80);
    return (r>>>1)^-(r&1);
  }
  for(i=0;i<brochas.length;i++){
    var b=brochas[i];
    b.o0=o; b.p0=q; b.c0=nCam;
    for(j=0;j<b.n;j++){
      var op=d[p++];
      ops[o]=op;
      if(op===1){
        act=nCam++;
        cop[act]=o; cpt[act]=q;
        cx0[act]=cy0[act]=2147483647; cx1[act]=cy1[act]=-2147483648;
      }
      var cuantos=op===3?3:(op===4?0:1);
      for(var k=0;k<cuantos;k++){
        x+=varint(); y+=varint();
        xs[q]=x; ys[q]=y; q++;
        if(act>=0){
          if(x<cx0[act])cx0[act]=x; if(x>cx1[act])cx1[act]=x;
          if(y<cy0[act])cy0[act]=y; if(y>cy1[act])cy1[act]=y;
        }
      }
      o++;
    }
    b.o1=o; b.p1=q; b.c1=nCam;
  }
  // El final de un camino es el principio del siguiente; el del último, el final de todo.
  cop[nCam]=o; cpt[nCam]=q;
  // Se copian y no se recortan con `subarray`: un recorte es una ventana sobre el array
  // grande y lo mantendría vivo entero. Aquí sobraba la mitad, y en un plano eso son decenas
  // de megas en el navegador de un teléfono.
  xs=xs.slice(0,q); ys=ys.slice(0,q);
  cx0=cx0.slice(0,nCam); cy0=cy0.slice(0,nCam);
  cx1=cx1.slice(0,nCam); cy1=cy1.slice(0,nCam);
  cop=cop.slice(0,nCam+1); cpt=cpt.slice(0,nCam+1);
}

// ---- La tarjeta gráfica ----
//
// Es lo que hace que el visor de cad-viewer se mueva como se mueve: las rayas se suben una vez
// a la tarjeta y a partir de ahí pasear y ampliar solo cambian la cámara. Aquí va a pelo —dos
// programas de diez líneas— y no con una librería 3D entera, que pesaría un mega por archivo.
//
// **Lo que no da**: en casi ningún aparato se puede pedir una raya de más de un píxel de
// grosor, así que todas salen finas. Un plano es casi todo pelo, y a cambio no hay tirones.
// Si algo falla, se sigue con el lienzo de siempre.
function arrancarGl(){
  if(gl!==null) return gl;
  gl=false;
  try{
    var c=document.createElement('canvas');
    c.className='plano gl';
    var ctxGl=c.getContext('webgl',{antialias:true,alpha:true,depth:false,preserveDrawingBuffer:false})
      ||c.getContext('experimental-webgl',{antialias:true,alpha:true,depth:false});
    if(!ctxGl) return false;
    var v=ctxGl.createShader(ctxGl.VERTEX_SHADER);
    ctxGl.shaderSource(v,'attribute vec2 p;uniform vec2 ua;uniform vec2 ub;void main(){gl_Position=vec4(p*ua+ub,0.0,1.0);}');
    ctxGl.compileShader(v);
    var f=ctxGl.createShader(ctxGl.FRAGMENT_SHADER);
    ctxGl.shaderSource(f,'precision mediump float;uniform vec4 color;void main(){gl_FragColor=color;}');
    ctxGl.compileShader(f);
    var pr=ctxGl.createProgram();
    ctxGl.attachShader(pr,v); ctxGl.attachShader(pr,f); ctxGl.linkProgram(pr);
    if(!ctxGl.getProgramParameter(pr,ctxGl.LINK_STATUS)) return false;
    ctxGl.useProgram(pr);
    programa=pr;
    uA=ctxGl.getUniformLocation(pr,'ua');
    uB=ctxGl.getUniformLocation(pr,'ub');
    uColor=ctxGl.getUniformLocation(pr,'color');
    lienzoGl=c;
    lienzo.parentNode.insertBefore(c, lienzo.nextSibling);
    gl=ctxGl;
    // Con la tarjeta no hace falta pintar de más alrededor: **se repinta entero cada
    // fotograma y no cuesta**, así que el lienzo de debajo vuelve al tamaño de la ventana.
    MARGEN=1.0;
    pintado=null;
    subirRayas();
    return gl;
  }catch(e){ return false; }
}

/**
 * Sube todas las rayas a la tarjeta, en un solo montón.
 *
 * Dentro de cada brocha van **primero las largas**: así, mirando el plano entero, se dibuja
 * solo el principio de cada tramo y las que no llegan a un píxel ni se tocan, sin gastar el
 * doble de memoria en tener dos copias.
 */
function subirRayas(){
  desempaquetar();
  var total=0, i;
  for(i=0;i<brochas.length;i++) if(!brochas[i].r) total+=cuantasRayas(brochas[i]);
  var datos=new Float32Array(total*4);
  tramos=[];
  var n=0;
  for(i=0;i<brochas.length;i++){
    var b=brochas[i];
    if(b.r) continue;
    var desde=n;
    n=volcar(b, datos, n, true);      // las largas
    var corte=n;
    n=volcar(b, datos, n, false);     // y las cortas detrás
    tramos.push({
      b:b,
      // En bloques con su caja: la tarjeta no se salta sola lo que cae fuera de la pantalla,
      // y estando cerca eso es dibujar el plano entero para ver un cruce de calles.
      bloques:enBloques(datos, desde, corte, n)
    });
  }
  buffer=gl.createBuffer();
  gl.bindBuffer(gl.ARRAY_BUFFER, buffer);
  gl.bufferData(gl.ARRAY_BUFFER, datos.subarray(0,n), gl.STATIC_DRAW);
  rayasSubidas=n/4;
  var pos=gl.getAttribLocation(programa,'p');
  gl.enableVertexAttribArray(pos);
  gl.vertexAttribPointer(pos,2,gl.FLOAT,false,0,0);
  gl.disable(gl.DEPTH_TEST);
  gl.enable(gl.BLEND);
  gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA);
}
/**
 * Parte lo escrito en bloques de [POR_BLOQUE] rayas y le saca la caja a cada uno.
 *
 * [corte] es dónde acaban las largas y empiezan las cortas: los bloques no se mezclan, para
 * poder dibujar solo los primeros cuando se mira de lejos.
 */
function enBloques(datos, desde, corte, hasta){
  var out=[];
  function trozo(a, b, largas){
    while(a<b){
      var fin=Math.min(b, a+POR_BLOQUE*4);
      var x0=1/0, y0=1/0, x1=-1/0, y1=-1/0;
      for(var i=a;i<fin;i+=2){
        var x=datos[i], y=datos[i+1];
        if(x<x0)x0=x; if(x>x1)x1=x;
        if(y<y0)y0=y; if(y>y1)y1=y;
      }
      out.push({desde:a/4, cuantas:(fin-a)/4, largas:largas, x0:x0, y0:y0, x1:x1, y1:y1});
      a=fin;
    }
  }
  trozo(desde, corte, true);
  trozo(corte, hasta, false);
  return out;
}
function cuantasRayas(b){
  var n=0;
  for(var o=b.o0;o<b.o1;o++){
    var op=ops[o];
    if(op===2||op===4) n++;
    else if(op===3) n+=CACHOS;
  }
  return n;
}
// Vuelca las rayas de una brocha en el montón: [largas] dice si toca esta pasada.
function volcar(b, datos, n, largas){
  var p=b.p0, ux=0, uy=0, ix=0, iy=0, k;
  for(var o=b.o0;o<b.o1;o++){
    var op=ops[o];
    if(op===1){ ux=xs[p]*esc; uy=ys[p]*esc; ix=ux; iy=uy; p++; }
    else if(op===2){
      var x=xs[p]*esc, y=ys[p]*esc; p++;
      n=meter(datos,n,ux,uy,x,y,largas);
      ux=x; uy=y;
    }
    else if(op===3){
      var x1=xs[p]*esc, y1=ys[p]*esc, x2=xs[p+1]*esc, y2=ys[p+1]*esc, x3=xs[p+2]*esc, y3=ys[p+2]*esc;
      var ax=ux, ay=uy;
      for(k=1;k<=CACHOS;k++){
        var t=k/CACHOS, u=1-t;
        var bx=u*u*u*ux+3*u*u*t*x1+3*u*t*t*x2+t*t*t*x3;
        var by=u*u*u*uy+3*u*u*t*y1+3*u*t*t*y2+t*t*t*y3;
        n=meter(datos,n,ax,ay,bx,by,largas);
        ax=bx; ay=by;
      }
      ux=x3; uy=y3; p+=3;
    }
    else if(op===4){ n=meter(datos,n,ux,uy,ix,iy,largas); ux=ix; uy=iy; }
  }
  return n;
}
function meter(datos,n,x0,y0,x1,y1,largas){
  var larga=(Math.abs(x1-x0)+Math.abs(y1-y0))>LARGA;
  if(larga!==largas) return n;
  datos[n]=x0; datos[n+1]=y0; datos[n+2]=x1; datos[n+3]=y1;
  return n+4;
}

/** Pinta las rayas con la tarjeta. Aquí no hay cajas ni recortes: los hace ella. */
function pintarGl(v, e, m){
  var an=m.an, al=m.al, dpr=m.dpr;
  var w=Math.round(an*dpr), h=Math.round(al*dpr);
  if(lienzoGl.width!==w||lienzoGl.height!==h){
    lienzoGl.width=w; lienzoGl.height=h;
    lienzoGl.style.width=an+'px'; lienzoGl.style.height=al+'px';
    lienzoGl.style.left='0px'; lienzoGl.style.top='0px';
  }
  gl.viewport(0,0,w,h);
  gl.clearColor(0,0,0,0);
  gl.clear(gl.COLOR_BUFFER_BIT);
  gl.useProgram(programa);
  gl.bindBuffer(gl.ARRAY_BUFFER, buffer);
  var pos=gl.getAttribLocation(programa,'p');
  gl.enableVertexAttribArray(pos);
  gl.vertexAttribPointer(pos,2,gl.FLOAT,false,0,0);
  // De unidades del dibujo a la pantalla de la tarjeta, con el mismo encuadre que el SVG.
  gl.uniform2f(uA, 2/(e.k*an), -2/(e.k*al));
  gl.uniform2f(uB, e.ox*2/an-1-v.x*2/(e.k*an), 1-e.oy*2/al+v.y*2/(e.k*al));
  // **De lejos, solo las largas — pero solo si hay muchísimas.** Antes se dejaban de pintar
  // las cortas en cuanto una unidad del dibujo bajaba de un píxel, y eso es «la página cabe
  // en la pantalla»: en una hoja de texto, donde casi todo son trazos de letras de menos de
  // una unidad, desaparecía el 90 % del dibujo de golpe al alejarse y volvía al acercarse
  // (lo reportó el usuario el 5-sep-2026). La tarjeta pinta un millón de rayas sin
  // inmutarse, así que el recorte queda para los planos enormes, y solo cuando una raya
  // corta ya no llega a un tercio de píxel del aparato: ahí sí que no se ve.
  var soloLargas=rayasSubidas>MUCHAS_RAYAS && e.k*0.3>LARGA*dpr;
  var margen=e.k*8;
  var vx0=v.x-margen, vy0=v.y-margen, vx1=v.x+v.w+margen, vy1=v.y+v.h+margen;
  for(var i=0;i<tramos.length;i++){
    var t=tramos[i], b=t.b;
    if(!seVe(b.c)) continue;
    var c=color(b.t), o=(b.o===undefined?1:b.o);
    gl.uniform4f(uColor, c[0], c[1], c[2], o);
    // Los bloques que se tocan se dibujan de una sola orden: cambiar de tanda cuesta más que
    // dibujar unas rayas de más.
    var desde=-1, cuantas=0;
    for(var j=0;j<t.bloques.length;j++){
      var q=t.bloques[j];
      var vale=(!soloLargas||q.largas) && !(q.x1<vx0||q.x0>vx1||q.y1<vy0||q.y0>vy1);
      if(vale && desde<0){ desde=q.desde; cuantas=q.cuantas; }
      else if(vale && q.desde===desde+cuantas){ cuantas+=q.cuantas; }
      else if(vale){ gl.drawArrays(gl.LINES, desde*2, cuantas*2); desde=q.desde; cuantas=q.cuantas; }
      else if(desde>=0){ gl.drawArrays(gl.LINES, desde*2, cuantas*2); desde=-1; cuantas=0; }
    }
    if(desde>=0) gl.drawArrays(gl.LINES, desde*2, cuantas*2);
  }
}
function color(hex){
  var n=parseInt(hex.slice(1),16);
  return [((n>>16)&255)/255, ((n>>8)&255)/255, (n&255)/255];
}

// ---- Pintar ----
function encuadre(v, an, al){
  var k=Math.max(v.w/Math.max(an,1), v.h/Math.max(al,1));
  return {k:k, ox:(an-v.w/k)/2, oy:(al-v.h/k)/2};
}
// **El lienzo es más grande que la ventana**, y sobresale por los cuatro lados.
//
// Pintando justo lo que se ve, en cuanto la mano mueve el papel asoma por un borde lo que
// todavía no está pintado, y se ve «cargar». Con el lienzo al doble de la ventana hay media
// pantalla de reserva por cada lado: se pasea sin que aparezca nada, y cuando se para se
// repinta centrado otra vez. Es lo que pidió el usuario después de probarlo.
var MARGEN=2.0;
// En cuántos trozos se parte una curva al subirla a la tarjeta, y qué raya cuenta como larga
// (en unidades del dibujo).
var CACHOS=8, LARGA=1.0, POR_BLOQUE=8192, MUCHAS_RAYAS=1500000, rayasSubidas=0;
function medir(){
  var r=caja.getBoundingClientRect();
  var an=Math.round(r.width), al=Math.round(r.height);
  var anL=Math.round(an*MARGEN), alL=Math.round(al*MARGEN);
  // El tope de píxeles va sobre el lienzo entero: en un portátil grande, el doble de ancho y
  // el doble de alto por dos de densidad son treinta millones de píxeles, y eso no lo mueve
  // ningún navegador.
  var dpr=Math.min(window.devicePixelRatio||1,2);
  var tope=Math.sqrt(8000000/Math.max(anL*alL,1));
  if(dpr>tope) dpr=Math.max(tope,0.5);
  var w=Math.round(anL*dpr), h=Math.round(alL*dpr);
  if(lienzo.width!==w||lienzo.height!==h){
    lienzo.width=w; lienzo.height=h;
    lienzo.style.width=anL+'px'; lienzo.style.height=alL+'px';
    pintado=null;
  }
  // Sobresale la mitad del margen por cada lado.
  var dx=Math.round((anL-an)/2), dy=Math.round((alL-al)/2);
  lienzo.style.left=(-dx)+'px';
  lienzo.style.top=(-dy)+'px';
  return {an:an, al:al, anL:anL, alL:alL, dx:dx, dy:dy, dpr:dpr};
}
function pintar(){
  if(!vista) return;
  desempaquetar();
  arrancarGl();
  var t0=(performance&&performance.now)?performance.now():Date.now();
  var m=medir(), v=vista;
  var e=encuadre(v, m.an, m.al);
  lienzo.style.transform='';
  ctx.setTransform(1,0,0,1,0,0);
  ctx.clearRect(0,0,lienzo.width,lienzo.height);
  // Del papel a la pantalla: un paso del punto fijo mide `s` píxeles.
  var s=esc/e.k, z=s*m.dpr;
  // El lienzo empieza medio margen a la izquierda y arriba de la ventana: todo lo que se
  // pinta lleva ese desplazamiento, y por eso hay dibujo de reserva fuera de lo que se ve.
  var tx=(e.ox + m.dx - v.x/e.k)*m.dpr, ty=(e.oy + m.dy - v.y/e.k)*m.dpr;
  ctx.setTransform(z,0,0,z,tx,ty);
  // Lo que abarca el lienzo, en pasos: lo que caiga fuera no se recorre.
  var vx0=(v.x - m.dx*e.k)/esc, vy0=(v.y - m.dy*e.k)/esc;
  var vx1=(v.x + v.w + m.dx*e.k)/esc, vy1=(v.y + v.h + m.dy*e.k)/esc;
  var salto=0.5/s;            // medio píxel: por debajo de eso dos puntos son el mismo
  pintarPapel(v, e, m);
  pintarFotos(v, e, m);
  ctx.setTransform(z,0,0,z,tx,ty);
  ctx.lineCap='round'; ctx.lineJoin='round';
  for(var i=0;i<brochas.length;i++){
    var b=brochas[i];
    if(!seVe(b.c)) continue;
    if(soloLineas && b.r) continue;
    // Las rayas las lleva la tarjeta; aquí quedan el papel, las manchas y los rótulos.
    if(gl && !b.r) continue;
    ctx.beginPath();
    var algo=false;
    for(var c=b.c0;c<b.c1;c++){
      if(cx1[c]<vx0||cx0[c]>vx1||cy1[c]<vy0||cy0[c]>vy1) continue;
      // Un camino más chico que un píxel se pinta como un punto y ya.
      var ancho=(cx1[c]-cx0[c]), alto=(cy1[c]-cy0[c]);
      if(ancho<salto&&alto<salto){
        ctx.moveTo(cx0[c],cy0[c]); ctx.lineTo(cx0[c]+salto,cy0[c]); algo=true; continue;
      }
      var p=cpt[c], ux=0, uy=0;
      for(var o=cop[c];o<cop[c+1];o++){
        var op=ops[o];
        if(op===1){ ctx.moveTo(xs[p],ys[p]); ux=xs[p]; uy=ys[p]; p++; algo=true; }
        else if(op===2){
          var x=xs[p], y=ys[p]; p++;
          // El último de un camino se pone siempre: si no, un trazo largo hecho de tramos
          // cortos se quedaría corto.
          if(o+1<cop[c+1] && Math.abs(x-ux)<salto && Math.abs(y-uy)<salto) continue;
          ctx.lineTo(x,y); ux=x; uy=y;
        }
        else if(op===3){ ctx.bezierCurveTo(xs[p],ys[p],xs[p+1],ys[p+1],xs[p+2],ys[p+2]); ux=xs[p+2]; uy=ys[p+2]; p+=3; }
        else if(op===4){ ctx.closePath(); }
      }
    }
    if(!algo) continue;
    ctx.globalAlpha=(b.o===undefined?1:b.o);
    if(b.r){ ctx.fillStyle=b.t; ctx.fill(); }
    else {
      ctx.strokeStyle=b.t;
      // Un pelo del PDF (`0 w`) es «lo más fino que se pueda»; y ninguna raya baja de un
      // píxel de pantalla, o al alejarse el plano se desharía.
      ctx.lineWidth=Math.max((b.g||0)/esc, 1/s);
      if(b.d){ var raya=[]; for(var q=0;q<b.d.length;q++) raya.push(b.d[q]/esc); ctx.setLineDash(raya); }
      else ctx.setLineDash([]);
      ctx.stroke();
    }
  }
  ctx.setLineDash([]);
  ctx.globalAlpha=1;
  pintarTextos(v, e, m);
  if(gl) pintarGl(v, e, m);
  pintado={x:v.x,y:v.y,w:v.w,h:v.h,an:m.an,al:m.al};
  sucio=false;
  coste=((performance&&performance.now)?performance.now():Date.now())-t0;
}
// **El papel, blanco.** Es lo mismo que hace la aplicación al rasterizar una página (el papel
// se borra a blanco antes de dibujar encima): un PDF no trae fondo, y sin esto un plano de
// tinta negra sobre una página de fondo oscuro sería negro sobre negro.
function pintarPapel(v, e, m){
  var z=1/e.k*m.dpr;
  ctx.setTransform(z,0,0,z,(e.ox + m.dx - v.x/e.k)*m.dpr,(e.oy + m.dy - v.y/e.k)*m.dpr);
  ctx.fillStyle='#ffffff';
  ctx.fillRect(0,0,D.a,D.b);
  ctx.setTransform(1,0,0,1,0,0);
}
// Debajo de todo lo demás: en un plano una foto es el fondo, y las líneas van encima.
function pintarFotos(v, e, m){
  if(!fotos.length) return;
  var z=1/e.k*m.dpr;
  var tx=(e.ox + m.dx - v.x/e.k)*m.dpr, ty=(e.oy + m.dy - v.y/e.k)*m.dpr;
  for(var i=0;i<fotos.length;i++){
    var f=fotos[i];
    if(!f.img||!seVe(f.c)) continue;
    ctx.setTransform(z,0,0,z,tx,ty);
    ctx.transform(f.m[0],f.m[1],f.m[2],f.m[3],f.m[4],f.m[5]);
    ctx.globalAlpha=(f.o===undefined?1:f.o);
    try{ ctx.drawImage(f.img,0,0,1,1); }catch(err){}
    ctx.globalAlpha=1;
  }
  ctx.setTransform(1,0,0,1,0,0);
}
// Los rótulos van con la tipografía del aparato, estirados hasta ocupar lo que decía el PDF:
// así una cota cae dentro de su casilla aunque la letra no sea la misma.
function pintarTextos(v, e, m){
  if(!textos.length) return;
  var z=1/e.k*m.dpr;
  var tx=(e.ox + m.dx - v.x/e.k)*m.dpr, ty=(e.oy + m.dy - v.y/e.k)*m.dpr;
  for(var i=0;i<textos.length;i++){
    var t=textos[i];
    if(!seVe(t.c)) continue;
    var mm=t.m;
    var alto=Math.hypot(mm[2],mm[3])/e.k;
    if(alto<4) continue;                       // más pequeño que eso no se lee
    if(alto>Math.max(m.an,m.al)*4) continue;
    var ancho=t.w*Math.hypot(mm[0],mm[1])/e.k;
    var px=(mm[4]-v.x)/e.k+e.ox+m.dx, py=(mm[5]-v.y)/e.k+e.oy+m.dy;
    if(px<-ancho-alto||py<-alto*2||px>m.anL+ancho+alto||py>m.alL+alto*2) continue;
    ctx.setTransform(z,0,0,z,tx,ty);
    ctx.transform(mm[0],mm[1],mm[2],mm[3],mm[4],mm[5]);
    ctx.scale(0.01,0.01);
    ctx.font=(t.n?'bold ':'')+(t.i?'italic ':'')+'100px '+(t.f||'sans-serif');
    if(t.k===undefined){ t.k=ctx.measureText(t.s).width; }
    if(t.k>0) ctx.scale(t.w*100/t.k,1);
    ctx.fillStyle=t.t;
    ctx.globalAlpha=(t.o===undefined?1:t.o);
    ctx.fillText(t.s,0,0);
    ctx.globalAlpha=1;
  }
  ctx.setTransform(1,0,0,1,0,0);
}
// Mientras la mano se mueve no se repinta: se estira lo ya pintado, que lo hace la tarjeta
// gráfica y sale gratis. Ver la nota de arriba.
function estirar(){
  if(!pintado||!vista) return;
  var e0=encuadre(pintado, pintado.an, pintado.al);
  var m=medir();
  var e1=encuadre(vista, m.an, m.al);
  var f=e0.k/e1.k;
  // Al desplazamiento de siempre se le suma lo que se mueve la esquina del lienzo, que no
  // está en la de la ventana sino medio margen más allá. Ver [medir].
  var dx=(e1.ox - vista.x/e1.k) - (e0.ox - pintado.x/e0.k)*f + m.dx*(1-f);
  var dy=(e1.oy - vista.y/e1.k) - (e0.oy - pintado.y/e0.k)*f + m.dy*(1-f);
  lienzo.style.transformOrigin='0 0';
  lienzo.style.transform='translate('+dx+'px,'+dy+'px) scale('+f+')';
}
function programar(){
  // Si el último pintado costó poco, se repinta en el siguiente fotograma; si costó, se
  // espera a que la mano pare. Un plano grande cuesta, y estirar mientras tanto se ve mejor
  // que dar tirones. Sin encuadre anterior no hay nada que estirar: se pinta ya.
  clearTimeout(quieto);
  // Con tarjeta gráfica se repinta siempre en el acto: pintar es mandarle dos números.
  if(gl||coste<24||!pintado){
    if(pendiente) return;
    // La marca se pone **antes** de pedir el fotograma: si la petición contesta en el acto,
    // asignar el resultado después dejaría la marca puesta para siempre y no se repintaría más.
    pendiente=1;
    requestAnimationFrame(function(){ pendiente=0; pintar(); });
  } else {
    estirar();
    quieto=setTimeout(function(){ pintar(); }, 90);
  }
}
return {
  // Con tarjeta gráfica no se estira nada: se repinta, y se repinta entero.
  ver:function(v){ vista={x:v.x,y:v.y,w:v.w,h:v.h}; if(pintado&&!gl) estirar(); programar(); },
  medir:function(){ pintado=null; programar(); },
  capas:function(){
    var out=[];
    for(var i=0;i<capas.length;i++)(function(k){
      out.push({
        nombre:capas[k].n||('Capa '+(k+1)),
        puesto:function(){ return encendida[k]; },
        // No se repinta aquí: `Todas` y `Ninguna` mueven treinta capas de una vez, y pintar
        // el plano treinta veces seguidas es lo que convierte un botón en un cuelgue.
        poner:function(si){ encendida[k]=!!si; sucio=true; programar(); },
        acercarse:function(){ acercarseA(k); }
      });
    })(i);
    if(haySueltas()) out.push({
      nombre:'Sin capa',
      puesto:function(){ return sueltaPuesta; },
      poner:function(si){ sueltaPuesta=!!si; sucio=true; programar(); },
      acercarse:function(){ acercarseA(-1); }
    });
    return out;
  },
  soloLineas:function(si){ soloLineas=!!si; sucio=true; programar(); },
  esSoloLineas:function(){ return soloLineas; },
  hayRellenos:function(){ for(var i=0;i<brochas.length;i++) if(brochas[i].r) return true; return false; },
  // Dónde cae una capa, para el botón de la lupa del cajón.
  cajaDe:function(k){ return cajaDeCapa(k); }
};
function cajaDeCapa(k){
  desempaquetar();
  var x0=1/0, y0=1/0, x1=-1/0, y1=-1/0;
  for(var i=0;i<brochas.length;i++){
    var b=brochas[i];
    if(b.c!==k) continue;
    for(var c=b.c0;c<b.c1;c++){
      if(cx0[c]<x0)x0=cx0[c]; if(cx1[c]>x1)x1=cx1[c];
      if(cy0[c]<y0)y0=cy0[c]; if(cy1[c]>y1)y1=cy1[c];
    }
  }
  for(var t=0;t<textos.length;t++) if(textos[t].c===k){
    var m=textos[t].m;
    var px=m[4]/esc, py=m[5]/esc;
    if(px<x0)x0=px; if(px>x1)x1=px; if(py<y0)y0=py; if(py>y1)y1=py;
  }
  if(!isFinite(x0)) return null;
  return {x:x0*esc, y:y0*esc, w:Math.max((x1-x0)*esc,1), h:Math.max((y1-y0)*esc,1)};
}
function acercarseA(k){
  var c=cajaDeCapa(k);
  if(c&&api&&api.encuadrar) api.encuadrar(c);
}
}
"""
}
