package com.forge.pixpin.motor

/**
 * **La tabla dentro de la página web**: se ve, se edita, recalcula, se pega desde Excel, se
 * copia hacia Excel y se guarda con el resto del documento. Ver [ExportarHtml].
 *
 * ## Dos trozos
 *
 * - [CALCULO] es **el motor de la aplicación escrito otra vez en JavaScript**: [Calculadora],
 *   [CalculoLexico], [CalculoFormato] y lo de R1C1 y tabuladores de [PortapapelesDeTabla],
 *   función por función y con los mismos nombres. No depende del DOM, y por eso
 *   `CalculoEnNodeTest` lo puede correr en node y comparar lo que enseña con lo que enseña
 *   Kotlin, fórmula a fórmula.
 * - [JS] es el visor: la rejilla como `<table>` de verdad —cabeceras que se quedan quietas
 *   al desplazar, texto que se busca con la lupa del navegador—, la barra de fórmula arriba y
 *   el teclado, el ratón y el dedo.
 *
 * ## Sin guion, igual se lee
 *
 * La tabla sale **ya calculada** en el HTML ([estatica]): quien la abra en un visor que no
 * ejecuta guiones —muchos de los que se abren desde un chat— ve los totales. Con guion, el
 * visor la rehace desde el JSON y a partir de ahí es suya.
 *
 * ## Guardar se come su guion, otra vez no
 *
 * Guardar reescribe el JSON y la tabla estática dentro de la plantilla de la página, que es el
 * documento **entero** con este guion incluido. Por eso aquí no aparece nunca escrito el
 * principio de esas dos etiquetas: se arma sumando trozos (`'class="'+'tabla"'`), y la
 * sustitución solo toca las primeras coincidencias. Ver la memoria del fallo de
 * `<g id="croquis">` en [ExportarHtml].
 */
object VisorTabla {

    /** La tabla ya calculada, para leerse sin guion y para buscar dentro. */
    fun estatica(t: TablaDeCalculo, maxFilas: Int = 2000, maxCols: Int = 200): String {
        val v = TablaViva(t)
        val m = marco(v.calc)
        val f0 = m[0]
        val c0 = m[1]
        val f1 = minOf(m[2], f0 + maxFilas - 1)
        val c1 = minOf(m[3], c0 + maxCols - 1)
        return buildString((f1 - f0 + 1) * (c1 - c0 + 1) * 24 + 200) {
            append("<table class=\"calc\"><thead><tr><th class=\"esq\"></th>")
            for (c in c0..c1) {
                append("<th style=\"width:").append(v.ancho(c)).append("px\">").append(Celdas.letras(c)).append("</th>")
            }
            append("</tr></thead><tbody>")
            for (f in f0..f1) {
                append("<tr><th>").append(f + 1).append("</th>")
                for (c in c0..c1) {
                    val texto = v.visto(f, c)
                    val e = v.estilo(f, c)
                    val valor = if (v.calc.crudo(f, c).isEmpty()) null else v.calc.valor(f, c)
                    val al = e?.a ?: when (valor) {
                        is Valor.Num -> "d"
                        is Valor.Log, is Valor.Err -> "c"
                        else -> "i"
                    }
                    val clases = buildList {
                        if (al == "d") add("d") else if (al == "c") add("c")
                        if (e?.n == true) add("n")
                        if (e?.k == true) add("k")
                        if (e?.s == true) add("s")
                        if (e?.t == true) add("t")
                        if (e?.w == true) add("w")
                        if (valor is Valor.Err) add("err")
                        if (Calculadora.esFormula(v.calc.crudo(f, c))) add("f")
                        if (t.protegida && e?.e == true) add("ed")
                    }
                    append("<td")
                    if (clases.isNotEmpty()) append(" class=\"").append(clases.joinToString(" ")).append('"')
                    val fondo = e?.f?.takeIf { COLOR.matches(it) }
                    val letra = e?.c?.takeIf { COLOR.matches(it) }
                    if (fondo != null || letra != null) {
                        append(" style=\"")
                        if (fondo != null) append("background:").append(fondo)
                        if (letra != null) append(if (fondo != null) ";" else "").append("color:").append(letra)
                        append('"')
                    }
                    append('>').append(escapar(texto)).append("</td>")
                }
                append("</tr>")
            }
            append("</tbody></table>")
        }
    }

    /**
     * **Qué parte de la tabla se comparte**: lo escrito y una fila y una columna de margen por
     * cada lado, no una rejilla infinita (lo pidió el usuario el 11-sep-2026). Arriba y a la
     * izquierda el margen no pasa de A1. `[fila0, col0, fila1, col1]`, todo incluido. El visor
     * hace la misma cuenta cada vez que se escribe: escribir en el margen lo agranda.
     */
    fun marco(calc: Calculadora): IntArray {
        val k = calc.caja() ?: return intArrayOf(0, 0, 1, 1)
        return intArrayOf(
            maxOf(0, k[0] - 1), maxOf(0, k[1] - 1),
            minOf(Celdas.MAX_FILAS - 1, k[2] + 1), minOf(Celdas.MAX_COLS - 1, k[3] + 1)
        )
    }

    private val COLOR = Regex("^#[0-9a-fA-F]{6}$")

    /** El JSON de la tabla, sin un solo `<`: así nunca cierra el `script` que lo lleva. */
    fun json(t: TablaDeCalculo): String = TablaDeCalculo.aJson(t).replace("<", "\\u003c")

    private fun escapar(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    val ESTILO = """
        :root{--cabecera:#f1f3f4;--rejilla:#dadce0}
        body.oscuro{--cabecera:#2b2c2f;--rejilla:#3c4043}
        .hoja[data-tipo=tabla]{display:flex;flex-direction:column}
        /* Sin esto la tabla oculta se sigue pintando encima de las otras páginas: la regla de
           arriba pesa lo mismo que `.hoja[hidden]` y va detrás (usuario, 11-sep-2026). */
        .hoja[data-tipo=tabla][hidden]{display:none}
        .tabla-fx{display:flex;align-items:center;gap:8px;padding:8px 10px;flex:none;
          border-bottom:1px solid var(--rejilla);background:var(--vidrio)}
        .tabla-dir{min-width:4.6em;font:600 13px ui-monospace,SFMono-Regular,Menlo,monospace;opacity:.75}
        .tabla-fx-in{flex:1;min-width:0;font:15px ui-monospace,SFMono-Regular,Menlo,monospace;padding:8px 10px;
          border:1px solid var(--rejilla);border-radius:9px;background:transparent;color:inherit;outline:none}
        .tabla-fx-in:focus{border-color:#1a73e8;box-shadow:0 0 0 2px rgba(26,115,232,.2)}
        .tabla-caja{flex:1;overflow:auto;-webkit-overflow-scrolling:touch;outline:none;position:relative}
        table.calc{border-collapse:separate;border-spacing:0;table-layout:fixed;position:relative;
          font-size:calc(13px*var(--tz,1));margin-bottom:140px;font-variant-numeric:tabular-nums}
        table.calc th,table.calc td{border-right:1px solid var(--rejilla);border-bottom:1px solid var(--rejilla);
          padding:0 6px;height:calc(26px*var(--tz,1));white-space:nowrap;overflow:hidden;text-overflow:ellipsis;
          box-sizing:border-box;text-align:left}
        table.calc thead th{position:sticky;top:0;z-index:2;background:var(--cabecera);font-weight:500;
          text-align:center;opacity:1}
        table.calc tbody th{position:sticky;left:0;z-index:1;background:var(--cabecera);font-weight:500;
          text-align:center;width:46px;min-width:46px}
        table.calc th.esq{left:0;z-index:3;width:46px;min-width:46px}
        table.calc td.d{text-align:right}
        table.calc td.c{text-align:center}
        table.calc td.n{font-weight:700}
        table.calc td.k{font-style:italic}
        table.calc td.s{text-decoration:underline}
        table.calc td.t{text-decoration:line-through}
        table.calc td.s.t{text-decoration:underline line-through}
        table.calc td.w{white-space:normal;word-break:break-word;text-overflow:clip;line-height:1.15}
        table.calc td.err{color:#d93025}
        table.calc td.sel{box-shadow:inset 0 0 0 999px rgba(26,115,232,.14)}
        table.calc td.act{outline:2px solid #1a73e8;outline-offset:-2px}
        table.calc th.marcada{background:#d2e3fc;color:#174ea6}
        body.oscuro table.calc th.marcada{background:#28436b;color:#d2e3fc}
        html.vivo table.calc{user-select:none;-webkit-user-select:none;-webkit-touch-callout:none;cursor:cell}
        html.vivo table.calc th{cursor:pointer}
        /* Las celdas con fórmula: un marco fino y discreto, sin relleno (usuario, 11-sep-2026). */
        table.calc td.f{box-shadow:inset 0 0 0 1px rgba(217,48,37,.55)}
        table.calc td.f.sel{box-shadow:inset 0 0 0 1px rgba(217,48,37,.55),inset 0 0 0 999px rgba(26,115,232,.14)}
        /* **La tabla, dentro de un lienzo.** Con guion, la caja no se desplaza: se pasea y se amplía
           el mundo que la lleva, y alrededor queda sitio para anotar. Las cabeceras van con la tabla. */
        html.vivo .tabla-caja{overflow:hidden;touch-action:none;cursor:grab}
        html.vivo .tabla-caja.agarrada{cursor:grabbing}
        #lienzo.pintando .tabla-caja{cursor:crosshair}
        .tabla-mundo{position:absolute;left:0;top:0;transform-origin:0 0}
        html.vivo table.calc{margin:0;box-shadow:0 1px 8px rgba(0,0,0,.14)}
        html.vivo table.calc thead th,html.vivo table.calc tbody th{position:static}
        .tabla-mundo svg.tinta{position:absolute;left:0;top:0;width:1px;height:1px;overflow:visible;
          pointer-events:none!important;z-index:2}
        /* En una tabla protegida, las celdas que sí se pueden cambiar. Van detrás de la fórmula:
           una celda editable con fórmula se ve editable. */
        table.calc td.ed{background:rgba(255,193,7,.24)}
        .tabla-caja svg.tinta{z-index:1}
        .tabla-marcas{position:absolute;left:0;top:0;width:0;height:0;pointer-events:none;z-index:1}
        .tabla-marca{position:absolute;border:2px solid;box-sizing:border-box;border-radius:2px}
    """.trimIndent()

    /**
     * **El motor, en JavaScript.** Ver la cabecera: cada función es la de Kotlin con el mismo
     * nombre, y `CalculoEnNodeTest` comprueba que enseñan lo mismo.
     */
    val CALCULO = """
var Calculo=(function(){
"use strict";
var MAX_COLS=702, MAX_FILAS=100000, FILAS_EN_FORMULA=1048576, PROFUNDIDAD=400, DIAS=25569;
var E_DIV0='#¡DIV/0!', E_VALOR='#¡VALOR!', E_REF='#¡REF!', E_NOMBRE='#¿NOMBRE?', E_ND='#N/A', E_NUM='#¡NUM!', E_CIRC='#¡CICLO!';
var VACIO={t:'v'}, HONDO={hondo:1};
function N(n,f){return {t:'n',n:n,f:!!f};}
function S(s){return {t:'s',s:s};}
function B(b){return {t:'b',b:b};}
function E(e){return {t:'e',e:e};}
function toInt(x){ if(x!==x)return 0; if(x>=2147483647)return 2147483647; if(x<=-2147483648)return -2147483648; return x<0?Math.ceil(x):Math.floor(x); }
function cmpStr(a,b){return a<b?-1:a>b?1:0;}

// ---- Celdas ----
function letras(col){var n=col+1,s='';while(n>0){var r=(n-1)%26;s=String.fromCharCode(65+r)+s;n=Math.floor((n-1)/26);}return s;}
function columna(l){ if(!l||l.length>3)return -1; var n=0; for(var i=0;i<l.length;i++){var u=l.charCodeAt(i); if(u>=97&&u<=122)u-=32; if(u<65||u>90)return -1; n=n*26+(u-64);} return n-1; }
function nombre(f,c){return letras(c)+(f+1);}
var DIR=/^\$?([A-Za-z]{1,3})\$?([0-9]{1,6})$/;
function leer(s){var m=DIR.exec(String(s).trim()); if(!m)return null; var c=columna(m[1]), f=parseInt(m[2],10)-1; if(c<0||c>=MAX_COLS||f<0||f>=MAX_FILAS)return null; return [f,c];}

// ---- CalculoFormato ----
var MONEDA=/^(S\/\.?|US\$|\$|€|£)\s*/, CIFRAS=/^[0-9][0-9.,]*([eE][+-]?[0-9]+)?$|^[.,][0-9]+([eE][+-]?[0-9]+)?$/;
function contar(s,ch){var n=0;for(var i=0;i<s.length;i++)if(s[i]===ch)n++;return n;}
function numero(texto){
  var t=String(texto).trim(); if(!t)return null;
  var neg=false;
  if(t.length>2&&t[0]==='('&&t[t.length-1]===')'){neg=true;t=t.slice(1,-1).trim();}
  if(t[0]==='-'){neg=!neg;t=t.slice(1).trim();} else if(t[0]==='+') t=t.slice(1).trim();
  t=t.replace(MONEDA,'');
  var pc=false; if(t[t.length-1]==='%'){pc=true;t=t.slice(0,-1).trim();}
  t=t.replace(/[ \u00a0\u202f]/g,'');
  if(t[0]==='-'){neg=!neg;t=t.slice(1);}
  if(!CIFRAS.test(t))return null;
  var e=t.search(/[eE]/), cuerpo=e>=0?t.slice(0,e):t, exp=e>=0?t.slice(e):'';
  var p=contar(cuerpo,'.'), c=contar(cuerpo,',');
  if(p>0&&c>0){
    if(cuerpo.lastIndexOf('.')>cuerpo.lastIndexOf(',')){ cuerpo=cuerpo.replace(/,/g,''); if(p>1)return null; }
    else { cuerpo=cuerpo.replace(/\./g,''); if(c>1)return null; cuerpo=cuerpo.replace(',','.'); }
  } else if(c>0){
    var i=cuerpo.indexOf(',');
    if(c>1) cuerpo=cuerpo.replace(/,/g,'');
    else { var despues=cuerpo.length-i-1; cuerpo=(despues===3&&i>=1&&i<=3&&cuerpo[0]!=='0')?cuerpo.replace(',',''):cuerpo.replace(',','.'); }
  } else if(p>1) cuerpo=cuerpo.replace(/\./g,'');
  if(cuerpo[0]==='.')cuerpo='0'+cuerpo;
  if(cuerpo[cuerpo.length-1]==='.')cuerpo=cuerpo.slice(0,-1);
  if(!cuerpo)return null;
  var v=Number(cuerpo+exp); if(v!==v)return null;
  var r=pc?v/100:v; if(neg)r=-r;
  return isFinite(r)?r:null;
}
var FDMA=/^([0-9]{1,2})\/([0-9]{1,2})\/([0-9]{4})$/, FAMD=/^([0-9]{4})-([0-9]{1,2})-([0-9]{1,2})$/;
function diasDelMes(a,m){ if(m===2) return ((a%4===0&&a%100!==0)||a%400===0)?29:28; return (m===4||m===6||m===9||m===11)?30:31; }
function fecha(texto){
  var t=String(texto).trim(), m=FDMA.exec(t), d,mes,a;
  if(m){d=+m[1];mes=+m[2];a=+m[3];} else { m=FAMD.exec(t); if(!m)return null; d=+m[3];mes=+m[2];a=+m[1]; }
  if(mes<1||mes>12||d<1||d>diasDelMes(a,mes))return null;
  return serial(a,mes,d);
}
function serial(a,m,d){ var x=new Date(0); x.setUTCFullYear(a,m-1,d); return aSerie(x.getUTCFullYear(),x.getUTCMonth()+1,x.getUTCDate()); }
function partes(s){ var k=Math.floor(s); if(k>=0)return desdeSerie(k); var x=new Date((k-DIAS)*86400000); return [x.getUTCFullYear(),x.getUTCMonth()+1,x.getUTCDate()]; }
function dos(n){return n<10?'0'+n:''+n;}
function textoDeFecha(s){
  if(!isFinite(s)||s< -657434||s>2958465)return E_NUM;
  var p=partes(s), base=dos(p[2])+'/'+dos(p[1])+'/'+p[0], min=Math.round((s-Math.floor(s))*1440);
  if(min<=0||min>=1440)return base;
  return base+' '+dos(Math.floor(min/60))+':'+dos(min%60);
}
function ceros(n){var s='';while(n-->0)s+='0';return s;}
// Las cifras exactas de un double (como BigDecimal de Kotlin): [cifras, p], valor = 0.cifras × 10^p.
function exacto(a){
  var b=new DataView(new ArrayBuffer(8)); b.setFloat64(0,a);
  var hi=b.getUint32(0), lo=b.getUint32(4), ex=(hi>>>20)&0x7ff;
  var m=(BigInt(hi&0xfffff)<<BigInt(32))|BigInt(lo);
  if(ex===0)ex=1; else m=m|(BigInt(1)<<BigInt(52));
  var e2=ex-1075, d, p;
  if(e2>=0){ d=(m<<BigInt(e2)).toString(); p=d.length; }
  else { d=(m*(BigInt(5)**BigInt(-e2))).toString(); p=d.length+e2; }
  d=d.replace(/0+$/,'')||'0';
  return [d,p];
}
// [n] cifras significativas de |a|, mitad al par sobre el valor exacto (MathContext(n, HALF_EVEN)).
function redSig(a,n){
  a=Math.abs(a);
  // Deprisa: lejos de un empate, el redondeo de toExponential (sobre el valor exacto) es el mismo.
  if(n<=15&&a>0){
    var t=a.toExponential(n+2).split('e'), cif=t[0].replace('.',''), cola=+cif.slice(n);
    if(cola<495||cola>505){ var q=a.toExponential(n-1).split('e'); return {d:q[0].replace('.',''),p:+q[1]+1}; }
  }
  var x=exacto(a), d=x[0], p=x[1];
  if(d.length<=n)return {d:d+ceros(n-d.length),p:p};
  var c=d.slice(0,n), r=d.slice(n), sube;
  if(r[0]>'5')sube=true; else if(r[0]<'5')sube=false; else if(/[1-9]/.test(r.slice(1)))sube=true; else sube=(+c[n-1])%2===1;
  if(sube){
    var arr=c.split('').map(Number), i=n-1;
    while(i>=0){ if(arr[i]===9){arr[i]=0;i--;} else {arr[i]++;break;} }
    if(i<0){ arr.unshift(1); arr.pop(); p++; }
    c=arr.join('');
  }
  return {d:c,p:p};
}
// Las cifras d (valor 0.d × 10^p) escritas con [dec] decimales exactos.
function plano(d,p,dec){
  var ent, fr;
  if(p<=0){ ent='0'; fr=ceros(-p)+d; }
  else { ent=d.length>=p?d.slice(0,p):d+ceros(p-d.length); fr=d.length>p?d.slice(p):''; }
  ent=ent.replace(/^0+(?=[0-9])/,'');
  fr=(fr+ceros(dec)).slice(0,dec);
  return dec>0?ent+'.'+fr:ent;
}
// BigDecimal(|x|).setScale(dec, HALF_EVEN).toPlainString(): `format!("{:.dec}")` de Rust.
function fixDec(x,dec){
  x=Math.abs(x);
  if(x===0)return plano('0',1,dec);
  var e=exacto(x), n=e[1]+dec;
  if(n>=1){ var r=redSig(x,n); return plano(r.d,r.p,dec); }
  if(n===0){ var d=e[0], sube=d[0]>'5'||(d[0]==='5'&&/[1-9]/.test(d.slice(1))); return sube?plano('1',1-dec,dec):plano('0',1,dec); }
  return plano('0',1,dec);
}
function general(d){
  if(!isFinite(d))return E_NUM;
  if(d===0)return '0';
  var a=Math.abs(d), s=d<0?'-':'', r, t;
  if(a<1e15&&a===Math.floor(a))return s+String(a);
  if(a>=1e15||a<1e-9){
    r=redSig(a,6); var exp=r.p-1, resto=r.d.slice(1).replace(/0+$/,''), e=Math.abs(exp);
    return s+r.d[0]+(resto?'.'+resto:'')+'E'+(exp<0?'-':'+')+(e<10?'0'+e:''+e);
  }
  r=redSig(a,10);
  t=plano(r.d,r.p,Math.max(0,10-r.p));
  if(t.indexOf('.')>=0)t=t.replace(/0+$/,'').replace(/\.$/,'');
  return s+t;
}
function redondo(a){ return a>=4.5e15?a:Math.round(a); }
// REDONDEAR del PC: lo que está a una milmillonésima de un entero o de una mitad, lo es.
function redondear(x,decimales,modo){
  if(x===0||!isFinite(x))return x;
  var d=Math.max(-400,Math.min(400,decimales)), f=Math.pow(10,Math.min(Math.abs(d),300));
  var esc=d>=0?x*f:x/f;
  if(!isFinite(esc))return x;
  var a=Math.abs(esc), ent=redondo(a), r;
  if(Math.abs(a-ent)<=1e-9*Math.max(a,1))a=ent;
  if(modo===1)r=Math.ceil(a);
  else if(modo===-1)r=Math.floor(a);
  else { var doble=a*2; if(Math.abs(doble-redondo(doble))<=1e-9*Math.max(doble,1))a=redondo(doble)/2; r=redondo(a); }
  r=x<0?-r:r;
  return d>=0?r/f:r*f;
}
// ---- Fechas como el PC (fecha.rs) ----
var BASE=-25569;
function diasCiviles(a0,m,d){
  var a=m<=2?a0-1:a0, era=Math.floor(a/400), yoe=a-era*400, mp=(m+9)%12;
  var doy=Math.floor((153*mp+2)/5)+d-1, doe=yoe*365+Math.floor(yoe/4)-Math.floor(yoe/100)+doy;
  return era*146097+doe-719468;
}
function civilDeDias(z0){
  var z=z0+719468, era=Math.floor(z/146097), doe=z-era*146097;
  var yoe=Math.floor((doe-Math.floor(doe/1460)+Math.floor(doe/36524)-Math.floor(doe/146096))/365);
  var a=yoe+era*400, doy=doe-(365*yoe+Math.floor(yoe/4)-Math.floor(yoe/100)), mp=Math.floor((5*doy+2)/153);
  var d=doy-Math.floor((153*mp+2)/5)+1, m=mp<10?mp+3:mp-9;
  return [m<=2?a+1:a,m,d];
}
function aSerie(a,m,d){ var dias=diasCiviles(a,m,d)-BASE; return dias<61?dias-1:dias; }
function desdeSerie(s){
  if(s<0)return null; if(s===0)return [1900,1,0]; if(s<60)return civilDeDias(BASE+s+1); if(s===60)return [1900,2,29];
  return civilDeDias(BASE+s);
}
function serieDeTexto(texto){
  var t=String(texto).trim(), partes=t.split(/[\/.\-]/);
  if(partes.length!==3||partes.some(function(p){return !p||p.length>4;}))return null;
  var n=[];
  for(var i=0;i<3;i++){ if(!/^[+-]?[0-9]+$/.test(partes[i]))return null; n.push(parseInt(partes[i],10)); }
  var a,m,d;
  if(partes[0].length===4){a=n[0];m=n[1];d=n[2];} else if(partes[2].length===4){a=n[2];m=n[1];d=n[0];} else return null;
  if(m<1||m>12||d<1||d>31||a<1900||a>9999)return null;
  var s=aSerie(a,m,d), v=desdeSerie(s);
  return (v&&v[0]===a&&v[1]===m&&v[2]===d)?s:null;
}
function fechaDeSerie(dias){ return desdeSerie(Math.max(0,dias)); }
// El formato de número de una celda (el `nf` de su estilo): CalculoFormato.conFormatoDeCelda.
var MONEDA_POR_DEFECTO='S/';
function digitos(v,dec){
  var a=Math.abs(v), k;
  if(a===0)return ['0',ceros(dec)];
  var r=redSig(a,15), cifras=r.d, punto=r.p, corte=punto+dec, d=[];
  for(k=0;k<corte;k++)d.push(k<cifras.length?+cifras[k]:0);
  var sig=corte<0?0:(corte<cifras.length?+cifras[corte]:0);
  if(sig>=5){ k=d.length; while(true){ if(k===0){d.unshift(1);break;} k--; if(d[k]===9)d[k]=0; else {d[k]++;break;} } }
  while(d.length<dec+1)d.unshift(0);
  var ent=d.slice(0,d.length-dec).join('').replace(/^0+/,'')||'0', frac=d.slice(d.length-dec).join('');
  return [ent,frac];
}
function conMiles(v,dec){
  var x=digitos(v,dec), e=x[0], sb='', i;
  for(i=0;i<e.length;i++){ if(i>0&&(e.length-i)%3===0)sb+=','; sb+=e[i]; }
  return dec>0?sb+'.'+x[1]:sb;
}
function fechaCorta(v){
  if(!(v>=0&&v<=2958466))return '###########';
  var total=Math.round(v*86400), p=fechaDeSerie(Math.floor(total/86400));
  return dos(p[2])+'/'+dos(p[1])+'/'+p[0];
}
function conFormatoDeCelda(v,nf,moneda){
  if(!isFinite(v))return E_NUM;
  var s;
  switch(nf){
    case 'numero': s=conMiles(v,2); return (v<0&&/[1-9]/.test(s)?'-':'')+s;
    case 'moneda': return (v<0?'-':'')+(moneda||MONEDA_POR_DEFECTO)+' '+conMiles(v,2);
    case 'porcentaje': var x=digitos(v*100,2); return (v<0&&/[1-9]/.test(x[0]+x[1])?'-':'')+x[0]+'.'+x[1]+'%';
    case 'fecha': return fechaCorta(v);
  }
  return null;
}
var FORMATOS={numero:1,moneda:1,porcentaje:1,fecha:1};
/** Lo que enseña la celda con el estilo es: TablaViva.conFormato. */
function visto(calc,es,f,c,moneda){
  var nf=es&&es.nf;
  if(!nf||!FORMATOS[nf])return calc.texto(f,c);
  if(!calc.crudo(f,c))return '';
  var v=calc.valor(f,c);
  return v.t==='n'?conFormatoDeCelda(v.n,nf,moneda):calc.texto(f,c);
}

// ---- CalculoLexico ----
var P_NUM=1,P_TXT=2,P_REF=3,P_COLS=4,P_NOM=5,P_OP=6,P_ABRE=7,P_CIERRA=8,P_SEP=9,P_DP=10,P_ERR=11,P_MAL=12,P_MALA_REF=13;
var ERRORES=[['#¡DIV/0!','#¡DIV/0!'],['#DIV/0!','#¡DIV/0!'],['#¡VALOR!','#¡VALOR!'],['#VALUE!','#¡VALOR!'],
  ['#¡REF!','#¡REF!'],['#REF!','#¡REF!'],['#¿NOMBRE?','#¿NOMBRE?'],['#NAME?','#¿NOMBRE?'],['#N/D','#N/A'],['#N/A','#N/A'],
  ['#¡NUM!','#¡NUM!'],['#NUM!','#¡NUM!'],['#¡NULO!','#¡NULO!'],['#NULL!','#¡NULO!'],['#¡CICLO!','#¡CICLO!'],['#¡CIRC!','#¡CICLO!']];
var TILDES='ÁÉÍÓÚÜÑáéíóúüñ';
function esLetra(c){return c!==undefined&&((c>='A'&&c<='Z')||(c>='a'&&c<='z')||c==='_'||(c.length===1&&TILDES.indexOf(c)>=0));}
function esCifra(c){return c!==undefined&&c>='0'&&c<='9';}
function esAZ(c){return c!==undefined&&((c>='A'&&c<='Z')||(c>='a'&&c<='z'));}
var NUMERO_LITERAL=/^([0-9]+\.?[0-9]*|\.[0-9]+)([eE][+-]?[0-9]+)?$/;
function pz(t,x,d,h,o){ var p={t:t,x:x,d:d,h:h,n:0,f:0,c:0,ff:false,cf:false,c2:0,c2f:false}; if(o)for(var k in o)p[k]=o[k]; return p; }
function piezas(f){
  var out=[],i=0,n=f.length;
  while(i<n){
    var c=f[i];
    if(/\s/.test(c)){i++;continue;}
    var d=i;
    if(c==='"'){
      var sb='',cerrada=false; i++;
      while(i<n){ if(f[i]==='"'){ if(i+1<n&&f[i+1]==='"'){sb+='"';i+=2;continue;} i++;cerrada=true;break; } sb+=f[i];i++; }
      out.push(pz(cerrada?P_TXT:P_MAL,sb,d,i));
    } else if(esCifra(c)||(c==='.'&&i+1<n&&esCifra(f[i+1]))){
      while(i<n&&(esCifra(f[i])||f[i]==='.'))i++;
      if(i<n&&(f[i]==='e'||f[i]==='E')){ var j=i+1; if(j<n&&(f[j]==='+'||f[j]==='-'))j++; if(j<n&&esCifra(f[j])){ i=j; while(i<n&&esCifra(f[i]))i++; } }
      var t=f.slice(d,i);
      out.push(NUMERO_LITERAL.test(t)?pz(P_NUM,t,d,i,{n:Number(t)}):pz(P_MAL,t,d,i));
    } else if(c==='#'){
      var resto=f.slice(i), hall=null;
      for(var k=0;k<ERRORES.length;k++){ if(resto.slice(0,ERRORES[k][0].length).toUpperCase()===ERRORES[k][0].toUpperCase()){hall=ERRORES[k];break;} }
      if(!hall){i++;out.push(pz(P_MAL,'#',d,i));} else {i+=hall[0].length;out.push(pz(P_ERR,hall[1],d,i));}
    } else if(c==='$'||esLetra(c)){ var pp=palabra(f,i); out.push(pp); i=pp.h; }
    else if(c==='('){i++;out.push(pz(P_ABRE,'(',d,i));}
    else if(c===')'){i++;out.push(pz(P_CIERRA,')',d,i));}
    else if(c===','||c===';'){i++;out.push(pz(P_SEP,c,d,i));}
    else if(c===':'){i++;out.push(pz(P_DP,':',d,i));}
    else if(c==='<'&&i+1<n&&(f[i+1]==='='||f[i+1]==='>')){i+=2;out.push(pz(P_OP,f.slice(d,i),d,i));}
    else if(c==='>'&&i+1<n&&f[i+1]==='='){i+=2;out.push(pz(P_OP,'>=',d,i));}
    else if('+-*/^&=<>%'.indexOf(c)>=0){i++;out.push(pz(P_OP,c,d,i));}
    else {i++;out.push(pz(P_MAL,c,d,i));}
  }
  return out;
}
function palabra(f,d){
  var n=f.length, j=d, cf=j<n&&f[j]==='$'; if(cf)j++;
  var l0=j; while(j<n&&esAZ(f[j]))j++;
  var le=f.slice(l0,j);
  if(le.length>0&&le.length<=3){
    var k=j, ff=k<n&&f[k]==='$'; if(ff)k++;
    var c0=k; while(k<n&&esCifra(f[k]))k++;
    var ci=f.slice(c0,k), sigue=k<n?f[k]:' ';
    var tras=k; while(tras<n&&(f[tras]===' '||f[tras]==='\t'||f[tras]==='\u00a0'||f[tras]==='\u202f'))tras++;
    var llamada=tras<n&&f[tras]==='('&&!cf&&!ff;
    if(ci.length>0&&!esLetra(sigue)&&!llamada&&sigue!=='('&&sigue!=='.'&&sigue!=='_'){
      var col=columna(le), fila=ci.length<=9?parseInt(ci,10)-1:-1;
      if(col>=0&&col<MAX_COLS&&fila>=0&&fila<FILAS_EN_FORMULA) return pz(P_REF,f.slice(d,k),d,k,{f:fila,c:col,ff:ff,cf:cf});
      return pz(P_MALA_REF,f.slice(d,k),d,k);
    }
    if(ci.length===0&&!ff&&k<n&&f[k]===':'){
      var m=k+1, c2f=m<n&&f[m]==='$'; if(c2f)m++;
      var l2=m; while(m<n&&esAZ(f[m]))m++;
      var le2=f.slice(l2,m), s2=m<n?f[m]:' ';
      if(le2.length>0&&le2.length<=3&&!esCifra(s2)&&!esLetra(s2)&&s2!=='('){
        var a1=columna(le), a2=columna(le2);
        if(a1>=0&&a1<MAX_COLS&&a2>=0&&a2<MAX_COLS) return pz(P_COLS,f.slice(d,m),d,m,{c:a1,cf:cf,c2:a2,c2f:c2f});
      }
    }
  }
  if(f[d]==='$') return pz(P_MAL,'$',d,d+1);
  var i=d; while(i<n&&(esLetra(f[i])||esCifra(f[i])||f[i]==='.'))i++;
  var pal=f.slice(d,i), j=i; while(j<n&&/\s/.test(f[j]))j++;
  if((j>=n||f[j]!=='(')&&/[0-9]/.test(pal))return pz(P_MALA_REF,pal,d,i);
  return pz(P_NOM,pal,d,i);
}
function Mal(e){this.e=e||E_VALOR;}
var ANIDADO_MAXIMO=64;
function arbol(formula){
  var p=piezas(formula); if(!p.length) return {k:'err',e:E_VALOR};
  var i=0, hondo=0;
  function entra(){ if(++hondo>ANIDADO_MAXIMO)throw new Mal(); }
  function esOp(ops){ var x=p[i]; return (x&&x.t===P_OP&&ops.indexOf(x.x)>=0)?x.x:null; }
  function comparacion(){ var a=concatenar(),op; while((op=esOp(['=','<>','<','>','<=','>=']))){i++;a={k:'op',op:op,a:a,b:concatenar()};} return a; }
  function concatenar(){ var a=suma(); while(esOp(['&'])){i++;a={k:'op',op:'&',a:a,b:suma()};} return a; }
  function suma(){ var a=producto(),op; while((op=esOp(['+','-']))){i++;a={k:'op',op:op,a:a,b:producto()};} return a; }
  function producto(){ var a=potencia(),op; while((op=esOp(['*','/']))){i++;a={k:'op',op:op,a:a,b:potencia()};} return a; }
  function potencia(){ var a=signo(); while(esOp(['^'])){i++;a={k:'op',op:'^',a:a,b:signo()};} return a; }
  function signo(){ var op=esOp(['+','-']); if(op){i++;entra();var x=signo();hondo--;return op==='-'?{k:'menos',x:x}:x;} return porCiento(); }
  function porCiento(){ var a=primario(); while(esOp(['%'])){i++;a={k:'pc',x:a};} return a; }
  function primario(){
    var x=p[i]; if(!x) throw new Mal();
    switch(x.t){
      case P_NUM: i++; return {k:'num',n:x.n};
      case P_TXT: i++; return {k:'txt',s:x.x};
      case P_ERR: i++; return {k:'err',e:x.x};
      case P_MALA_REF: i++; if(p[i]&&p[i].t===P_DP)i+=2; return {k:'err',e:E_REF};
      case P_COLS: i++; return {k:'rango',f1:0,c1:Math.min(x.c,x.c2),f2:MAX_FILAS-1,c2:Math.max(x.c,x.c2),col:true};
      case P_REF:
        i++;
        var s=p[i];
        if(s&&s.t===P_DP){
          var o=p[i+1];
          if(o&&o.t===P_REF){ i+=2; return {k:'rango',f1:Math.min(x.f,o.f),c1:Math.min(x.c,o.c),f2:Math.max(x.f,o.f),c2:Math.max(x.c,o.c),col:false}; }
          if(o&&o.t===P_MALA_REF){ i+=2; return {k:'err',e:E_REF}; }
          throw new Mal(E_REF);
        }
        return {k:'ref',f:x.f,c:x.c};
      case P_NOM:
        i++;
        var nom=x.x.toUpperCase(), sg=p[i];
        if(sg&&sg.t===P_ABRE){
          i++; entra(); var args=[];
          if(p[i]&&p[i].t===P_CIERRA){i++;hondo--;return {k:'fn',nombre:nom,args:args};}
          while(true){
            args.push(comparacion());
            var q=p[i]; if(!q) throw new Mal();
            if(q.t===P_SEP){i++;continue;}
            if(q.t===P_CIERRA){i++;break;}
            throw new Mal();
          }
          hondo--;
          return {k:'fn',nombre:nom,args:args};
        }
        if(nom==='VERDADERO'||nom==='TRUE') return {k:'log',b:true};
        if(nom==='FALSO'||nom==='FALSE') return {k:'log',b:false};
        throw new Mal();
      case P_ABRE:
        i++; entra(); var dentro=comparacion(); if(!p[i]||p[i].t!==P_CIERRA) throw new Mal(); i++; hondo--; return dentro;
      default: throw new Mal();
    }
  }
  try{ var r=comparacion(); return i!==p.length?{k:'err',e:E_VALOR}:r; }
  catch(e){ if(e instanceof Mal) return {k:'err',e:e.e}; throw e; }
}
function dir(f,c,ff,cf){return (cf?'$':'')+letras(c)+(ff?'$':'')+(f+1);}
function desplazar(formula,df,dc){
  if(df===0&&dc===0)return formula;
  if(formula[0]!=='=')return formula;
  var cuerpo=formula.slice(1), sb='=', ult=0, ps=piezas(cuerpo);
  for(var i=0;i<ps.length;i++){
    var x=ps[i], nuevo=null;
    if(x.t===P_REF){ var f=x.ff?x.f:x.f+df, c=x.cf?x.c:x.c+dc; nuevo=(f<0||f>=MAX_FILAS||c<0||c>=MAX_COLS)?E_REF:dir(f,c,x.ff,x.cf); }
    else if(x.t===P_COLS){ var c1=x.cf?x.c:x.c+dc, c2=x.c2f?x.c2:x.c2+dc; nuevo=(c1<0||c1>=MAX_COLS||c2<0||c2>=MAX_COLS)?E_REF:(x.cf?'$':'')+letras(c1)+':'+(x.c2f?'$':'')+letras(c2); }
    if(nuevo===null)continue;
    sb+=cuerpo.slice(ult,x.d)+nuevo; ult=x.h;
  }
  return sb+cuerpo.slice(ult);
}

// ---- PortapapelesDeTabla ----
var R1C1=/^R(\[-?[0-9]+\]|[0-9]+)?C(\[-?[0-9]+\]|[0-9]+)?/;
function letraODigito(c){ return c!==undefined&&/[0-9A-Za-zÀ-ɏ]/.test(c); }
function parte(t,desde){ if(!t)return [desde,false]; if(t[0]==='['){ var v=parseInt(t.slice(1,-1),10); return v!==v?null:[desde+v,false]; } var a=parseInt(t,10); return a!==a?null:[a-1,true]; }
function r1c1(formula,fila,col){
  var sb='',i=0,n=formula.length, up=formula.toUpperCase();
  while(i<n){
    var c=formula[i];
    if(c==='"'){
      var j=formula.indexOf('"',i+1); if(j<0)j=n-1;
      var fin=j; while(fin+1<n&&formula[fin+1]==='"'){ var otra=formula.indexOf('"',fin+2); fin=otra<0?n-1:otra; }
      sb+=formula.slice(i,fin+1); i=fin+1; continue;
    }
    var antes=i>0?formula[i-1]:' ';
    if((c==='R'||c==='r')&&!letraODigito(antes)&&antes!=='.'&&antes!=='_'){
      var m=R1C1.exec(up.slice(i));
      if(m){
        var ult=i+m[0].length, des=ult<n?formula[ult]:' ';
        if(!letraODigito(des)&&des!=='('&&des!=='.'&&des!=='_'){
          var f=parte(m[1],fila), k=parte(m[2],col);
          if(!f||!k||f[0]<0||f[0]>=MAX_FILAS||k[0]<0||k[0]>=MAX_COLS) sb+=E_REF;
          else sb+=(k[1]?'$':'')+letras(k[0])+(f[1]?'$':'')+(f[0]+1);
          i=ult; continue;
        }
      }
    }
    sb+=c; i++;
  }
  return sb;
}
function aR1c1(formula,fila,col){
  if(formula[0]!=='=')return formula;
  var cuerpo=formula.slice(1), sb='=', ult=0, ps=piezas(cuerpo);
  for(var i=0;i<ps.length;i++){
    var x=ps[i], nuevo=null;
    if(x.t===P_REF) nuevo=(x.ff?'R'+(x.f+1):'R['+(x.f-fila)+']')+(x.cf?'C'+(x.c+1):'C['+(x.c-col)+']');
    else if(x.t===P_COLS) nuevo=(x.cf?'C'+(x.c+1):'C['+(x.c-col)+']')+':'+(x.c2f?'C'+(x.c2+1):'C['+(x.c2-col)+']');
    if(nuevo===null)continue;
    sb+=cuerpo.slice(ult,x.d)+nuevo; ult=x.h;
  }
  return sb+cuerpo.slice(ult);
}
function deTsv(texto){
  var filas=[], fila=[], celda='', i=0, n=texto.length, alEmpezar=true;
  while(i<n){
    var c=texto[i];
    if(alEmpezar&&c==='"'){
      var j=i+1, sb='', cerrada=-1;
      while(j<n){ if(texto[j]==='"'){ if(j+1<n&&texto[j+1]==='"'){sb+='"';j+=2;continue;} cerrada=j;break; } sb+=texto[j];j++; }
      var tras=(cerrada>=0&&cerrada+1<n)?texto[cerrada+1]:(cerrada>=0?'\n':'x');
      if(cerrada>=0&&(tras==='\t'||tras==='\n'||tras==='\r')){ celda+=sb; i=cerrada+1; alEmpezar=false; continue; }
    }
    alEmpezar=false;
    if(c==='\t'){ fila.push(celda); celda=''; alEmpezar=true; }
    else if(c==='\r'||c==='\n'){ if(c==='\r'&&i+1<n&&texto[i+1]==='\n')i++; fila.push(celda); celda=''; filas.push(fila); fila=[]; alEmpezar=true; }
    else celda+=c;
    i++;
  }
  if(celda.length>0||fila.length>0){ fila.push(celda); filas.push(fila); }
  return filas;
}
function aTsv(filas){
  return filas.map(function(fila){ return fila.map(function(v){
    return (/[\t\n\r]/.test(v)||v[0]==='"')?'"'+v.replace(/"/g,'""')+'"':v;
  }).join('\t'); }).join('\n');
}
function referencias(formula){
  if(formula[0]!=='=')return [];
  var p=piezas(formula.slice(1)), out=[], k=0;
  while(k<p.length){
    var x=p[k];
    if(x.t===P_REF&&k+2<p.length&&p[k+1].t===P_DP&&p[k+2].t===P_REF){
      var y=p[k+2];
      out.push({f1:Math.min(x.f,y.f),c1:Math.min(x.c,y.c),f2:Math.max(x.f,y.f),c2:Math.max(x.c,y.c),desde:x.d+1,hasta:y.h+1});
      k+=3; continue;
    }
    if(x.t===P_REF)out.push({f1:x.f,c1:x.c,f2:x.f,c2:x.c,desde:x.d+1,hasta:x.h+1});
    else if(x.t===P_COLS)out.push({f1:0,c1:Math.min(x.c,x.c2),f2:MAX_FILAS-1,c2:Math.max(x.c,x.c2),desde:x.d+1,hasta:x.h+1});
    k++;
  }
  return out;
}

// ---- Calculadora ---- (el motor del PC, como Calculo.kt: ver allí)
function esFormula(raw){return raw.length>1&&raw[0]==='=';}
function literal(raw){
  if(raw[0]==="'")return S(raw.slice(1));
  var t=raw.trim(); if(!t)return S('');
  var x=numero(t); if(x!==null)return N(x);
  x=fecha(t); if(x!==null)return N(x,true);
  var u=t.toUpperCase();
  if(u==='VERDADERO'||u==='TRUE')return B(true);
  if(u==='FALSO'||u==='FALSE')return B(false);
  return S(t);
}
function mostrar(v){
  switch(v.t){ case 'n': return v.f?textoDeFecha(v.n):general(v.n); case 's': return v.s; case 'b': return v.b?'VERDADERO':'FALSO'; case 'e': return v.e; default: return ''; }
}
function Fallo(e){this.e=e;}
function esVacio(v){ return v.t==='v'||(v.t==='s'&&v.s===''); }
function vacioComoTexto(v){ return v.t==='v'?S(''):v; }
function aNum(v){
  switch(v.t){
    case 'n': return v.n; case 'b': return v.b?1:0; case 'v': return 0;
    case 's': if(v.s==='')return 0; var x=numero(v.s); if(x===null)throw new Fallo(E_VALOR); return x;
    default: throw new Fallo(v.e);
  }
}
function aTxt(v){
  switch(v.t){ case 'n': return general(v.n); case 's': return v.s; case 'b': return v.b?'VERDADERO':'FALSO'; case 'v': return ''; default: throw new Fallo(v.e); }
}
function aLog(v){
  switch(v.t){
    case 'n': return v.n!==0; case 'b': return v.b; case 'v': return false;
    case 's': var u=v.s.trim().toUpperCase(); if(u==='')return false; if(u==='VERDADERO'||u==='TRUE')return true; if(u==='FALSO'||u==='FALSE')return false; throw new Fallo(E_VALOR);
    default: throw new Fallo(v.e);
  }
}
function potencia(x,y){
  if(x===0&&y===0)throw new Fallo(E_NUM);
  if(x===0&&y<0)throw new Fallo(E_DIV0);
  if(x<0&&y!==Math.floor(y))throw new Fallo(E_NUM);
  var r=Math.pow(x,y); if(!isFinite(r))throw new Fallo(E_NUM); return r;
}
function letrasDe(s){ return Array.from(s); }
function nomPropio(t){
  var sale='', en=false, l=Array.from(t);
  for(var i=0;i<l.length;i++){ var ch=l[i]; if(/\p{L}/u.test(ch)){ sale+=en?ch.toLowerCase():ch.toUpperCase(); en=true; } else { sale+=ch; en=false; } }
  return sale;
}
function tipoDe(v){ return v.t==='n'?0:(v.t==='s'||v.t==='v')?1:v.t==='b'?2:3; }
function signo(x){ return x<0?-1:x>0?1:0; }
function mismoNumero(a,b){ return a===b||Math.abs(a-b)<=1e-14*Math.max(Math.abs(a),Math.abs(b)); }
function numCmp(a,b){ return mismoNumero(a,b)?0:a<b?-1:1; }
function vacioComo(o){ return o.t==='n'?N(0):o.t==='b'?B(false):S(''); }
function comparar(a,b){
  var va=esVacio(a), vb=esVacio(b);
  if(va&&vb)return 0;
  var x=va?vacioComo(b):a, y=vb?vacioComo(a):b;
  if(x.t==='n'&&y.t==='n')return numCmp(x.n,y.n);
  if(x.t==='s'&&y.t==='s')return cmpStr(x.s.toLowerCase(),y.s.toLowerCase());
  if(x.t==='b'&&y.t==='b')return x.b===y.b?0:!x.b?-1:1;
  return signo(tipoDe(x)-tipoDe(y));
}
function ordenDelMismoTipo(a,b){
  var ta=a.t==='v'?S(''):a, tb=b.t==='v'?S(''):b;
  return (ta.t===tb.t&&(ta.t==='n'||ta.t==='s'||ta.t==='b'))?comparar(ta,tb):null;
}
function tieneComodines(t){ return /[*?~]/.test(t); }
function comodinL(p,pi,t,ti,exacto){
  if(pi>=p.length)return !exacto||ti>=t.length;
  var c=p[pi];
  if(c==='*'){ for(var k=ti;k<=t.length;k++)if(comodinL(p,pi+1,t,k,exacto))return true; return false; }
  if(c==='?')return ti<t.length&&comodinL(p,pi+1,t,ti+1,exacto);
  if(c==='~'&&pi+1<p.length&&(p[pi+1]==='*'||p[pi+1]==='?'||p[pi+1]==='~'))return ti<t.length&&t[ti]===p[pi+1]&&comodinL(p,pi+2,t,ti+1,exacto);
  return ti<t.length&&t[ti]===c&&comodinL(p,pi+1,t,ti+1,exacto);
}
function comodin(p,s){ return comodinL(Array.from(p),0,Array.from(s),0,true); }
function esIgual(buscado,celda,comodines){
  var c=celda.t==='v'?S(''):celda;
  if(buscado.t==='n'&&c.t==='n')return mismoNumero(buscado.n,c.n);
  if(buscado.t==='b'&&c.t==='b')return buscado.b===c.b;
  if(buscado.t==='s'&&c.t==='s'){
    if(comodines&&tieneComodines(buscado.s))return comodinL(Array.from(buscado.s.toLowerCase()),0,Array.from(c.s.toLowerCase()),0,true);
    return buscado.s.toLowerCase()===c.s.toLowerCase();
  }
  return false;
}
/** Un criterio de CONTAR.SI y familia (criterio.rs). */
function Criterio(op,tipo,n,b,t){ this.op=op; this.tipo=tipo; this.n=n; this.b=b; this.t=t; }
Criterio.de=function(v){
  switch(v.t){
    case 'n': return new Criterio('=','n',v.n);
    case 'b': return new Criterio('=','b',0,v.b);
    case 'e': case 'v': return new Criterio('=','v');
  }
  var ops=['<>','<=','>=','=','<','>'], op='', i;
  for(i=0;i<ops.length;i++) if(v.s.slice(0,ops[i].length)===ops[i]){op=ops[i];break;}
  var resto=v.s.slice(op.length), o=op||'=';
  if(resto==='')return new Criterio(o,'v');
  var x=numero(resto); if(x!==null)return new Criterio(o,'n',x);
  var u=resto.toUpperCase();
  if(u==='VERDADERO'||u==='TRUE')return new Criterio(o,'b',0,true);
  if(u==='FALSO'||u==='FALSE')return new Criterio(o,'b',0,false);
  return new Criterio(o,'t',0,false,resto.toLowerCase());
};
Criterio.prototype.ord=function(x){ switch(this.op){ case '=': return x===0; case '<>': return x!==0; case '<': return x<0; case '>': return x>0; case '<=': return x<=0; default: return x>=0; } };
Criterio.prototype.cumple=function(v){
  if(v.t==='e')return false;
  var otro=this.op==='<>';
  switch(this.tipo){
    case 'v': return this.op==='<>'?!esVacio(v):esVacio(v);
    case 'n': return v.t==='n'?this.ord(mismoNumero(v.n,this.n)?0:v.n<this.n?-1:1):otro;
    case 'b': return v.t==='b'?this.ord(v.b===this.b?0:!v.b?-1:1):otro;
  }
  if(!(v.t==='s'&&v.s!==''))return otro;
  if(this.op==='='||this.op==='<>'){
    var casa=tieneComodines(this.t)?comodinL(Array.from(this.t),0,Array.from(v.s.toLowerCase()),0,true):v.s.toLowerCase()===this.t;
    return casa===(this.op==='=');
  }
  return this.ord(cmpStr(v.s.toLowerCase(),this.t));
};
function claveDe(v){ return v.t==='n'?'n'+general(v.n)+':'+v.n:v.t==='s'?'t'+v.s:v.t==='b'?'b'+v.b:v.t==='e'?'e'+v.e:'t'; }
// Las funciones del PC (motor::CATALOGO) y las que solo tiene el móvil: nombre → [español, mínimo, máximo].
var CATALOGO={};
(function(){
  function p(c,a,b,nombres){ nombres.forEach(function(n){ CATALOGO[n]=[c,a,b]; }); }
  p('SI',2,3,['SI','IF']); p('Y',1,255,['Y','AND']); p('O',1,255,['O','OR']); p('NO',1,1,['NO','NOT']);
  p('SI.ERROR',2,2,['SI.ERROR','IFERROR']); p('SI.ND',2,2,['SI.ND','IFNA']); p('SI.CONJUNTO',2,254,['SI.CONJUNTO','IFS']);
  p('VERDADERO',0,0,['VERDADERO','TRUE']); p('FALSO',0,0,['FALSO','FALSE']);
  p('SUMA',0,255,['SUMA','SUM']); p('CONTAR',0,255,['CONTAR','COUNT']); p('PROMEDIO',0,255,['PROMEDIO','AVERAGE']);
  p('MIN',0,255,['MIN','MINIMO','MÍNIMO']); p('MAX',0,255,['MAX','MAXIMO','MÁXIMO']);
  p('REDONDEAR',2,2,['REDONDEAR','ROUND']); p('REDONDEAR.MAS',2,2,['REDONDEAR.MAS','ROUNDUP']); p('REDONDEAR.MENOS',2,2,['REDONDEAR.MENOS','ROUNDDOWN']);
  p('ENTERO',1,1,['ENTERO','INT']); p('ABS',1,1,['ABS']); p('RESIDUO',2,2,['RESIDUO','MOD']); p('POTENCIA',2,2,['POTENCIA','POWER']);
  p('RAIZ',1,1,['RAIZ','SQRT','RAÍZ']); p('PRODUCTO',1,255,['PRODUCTO','PRODUCT']); p('MEDIANA',1,255,['MEDIANA','MEDIAN']);
  p('PI',0,0,['PI']); p('ALEATORIO',0,0,['ALEATORIO','RAND']); p('ALEATORIO.ENTRE',2,2,['ALEATORIO.ENTRE','RANDBETWEEN']);
  p('CONTARA',1,255,['CONTARA','COUNTA']); p('CONTAR.BLANCO',1,1,['CONTAR.BLANCO','COUNTBLANK']);
  p('SUMAR.SI',2,3,['SUMAR.SI','SUMIF']); p('SUMAR.SI.CONJUNTO',3,255,['SUMAR.SI.CONJUNTO','SUMIFS']);
  p('CONTAR.SI',2,2,['CONTAR.SI','COUNTIF']); p('CONTAR.SI.CONJUNTO',2,254,['CONTAR.SI.CONJUNTO','COUNTIFS']);
  p('PROMEDIO.SI',2,3,['PROMEDIO.SI','AVERAGEIF']);
  p('BUSCARV',3,4,['BUSCARV','VLOOKUP']); p('BUSCARH',3,4,['BUSCARH','HLOOKUP']); p('INDICE',2,3,['INDICE','INDEX','ÍNDICE']);
  p('COINCIDIR',2,3,['COINCIDIR','MATCH']); p('BUSCARX',3,6,['BUSCARX','XLOOKUP']);
  p('CONCATENAR',1,255,['CONCATENAR','CONCATENATE']); p('CONCAT',1,255,['CONCAT']); p('UNIRCADENAS',3,255,['UNIRCADENAS','TEXTJOIN']);
  p('IZQUIERDA',1,2,['IZQUIERDA','LEFT']); p('DERECHA',1,2,['DERECHA','RIGHT']); p('EXTRAE',3,3,['EXTRAE','MID']);
  p('LARGO',1,1,['LARGO','LEN']); p('MAYUSC',1,1,['MAYUSC','UPPER']); p('MINUSC',1,1,['MINUSC','LOWER']);
  p('NOMPROPIO',1,1,['NOMPROPIO','PROPER']); p('ESPACIOS',1,1,['ESPACIOS','TRIM']); p('SUSTITUIR',3,4,['SUSTITUIR','SUBSTITUTE']);
  p('ENCONTRAR',2,3,['ENCONTRAR','FIND']); p('HALLAR',2,3,['HALLAR','SEARCH']); p('TEXTO',2,2,['TEXTO','TEXT']); p('VALOR',1,1,['VALOR','VALUE']);
  p('HOY',0,0,['HOY','TODAY']); p('AHORA',0,0,['AHORA','NOW']); p('FECHA',3,3,['FECHA','DATE']);
  p('AÑO',1,1,['AÑO','ANO','YEAR']); p('MES',1,1,['MES','MONTH']); p('DIA',1,1,['DIA','DAY','DÍA']);
  p('DIAS',2,2,['DIAS','DAYS','DÍAS']); p('DIASEM',1,2,['DIASEM','WEEKDAY']);
  p('ESERROR',1,1,['ESERROR','ISERROR']); p('ESBLANCO',1,1,['ESBLANCO','ISBLANK']); p('ESNUMERO',1,1,['ESNUMERO','ISNUMBER']);
  p('ESND',1,1,['ESND','ISNA']); p('ESTEXTO',1,1,['ESTEXTO','ISTEXT']);
  p('DESVEST',1,255,['DESVEST','DESVEST.M','STDEV','STDEV.S']); p('TRUNCAR',1,2,['TRUNCAR','TRUNC']);
  p('EXP',1,1,['EXP']); p('LN',1,1,['LN']); p('LOG10',1,1,['LOG10']); p('LOG',1,2,['LOG']);
  p('SENO',1,1,['SENO','SIN']); p('COS',1,1,['COS']); p('TAN',1,1,['TAN']); p('GRADOS',1,1,['GRADOS','DEGREES']);
  p('RADIANES',1,1,['RADIANES','RADIANS']); p('SIGNO',1,1,['SIGNO','SIGN']); p('REPETIR',2,2,['REPETIR','REPT']);
  p('SUMAPRODUCTO',1,255,['SUMAPRODUCTO','SUMPRODUCT']); p('ELEGIR',2,255,['ELEGIR','CHOOSE']);
  p('FILA',0,1,['FILA','ROW']); p('COLUMNA',0,1,['COLUMNA','COLUMN']); p('PROMEDIO.SI.CONJUNTO',3,255,['PROMEDIO.SI.CONJUNTO','AVERAGEIFS']);
})();
var MESES=['enero','febrero','marzo','abril','mayo','junio','julio','agosto','septiembre','octubre','noviembre','diciembre'];
var DIAS_SEMANA=['domingo','lunes','martes','miércoles','jueves','viernes','sábado'];
function fixDec(x,dec){ // format!("{:.dec}") de Rust: mitad al par sobre el valor exacto
  var s=x.toFixed(dec);
  var exacto=x.toPrecision(100).replace(/0+$/,''), p=exacto.indexOf('.');
  var frac=p<0?'':exacto.slice(p+1);
  if(frac.length===dec+1&&frac[dec]==='5'){ // empate exacto: Rust redondea al par
    var abajo=(Math.floor(x*Math.pow(10,dec))/Math.pow(10,dec)).toFixed(dec);
    var ult=+abajo[abajo.length-1]; if(ult%2===0)return abajo;
  }
  return s;
}
function numeroConFormato(n,formato){
  var ch=Array.from(formato), prefijo='', region='', sufijo='', porciento=false, i=0, estado=0;
  while(i<ch.length){
    var c=ch[i], lit=null;
    if(c==='"'){ var fin=-1; for(var k=i+1;k<ch.length;k++)if(ch[k]==='"'){fin=k;break;} if(fin>=0){lit=ch.slice(i+1,fin).join('');i=fin+1;} else {lit=ch.slice(i+1).join('');i=ch.length;} }
    else if(c==='\\'||c==='_'){ lit=i+1<ch.length?(c==='_'?' ':ch[i+1]):''; i+=2; }
    else if(c==='%'){ porciento=true; i++; lit='%'; }
    else if('0#?.,'.indexOf(c)>=0) lit=null;
    else { i++; lit=c; }
    if(lit!==null){ if(estado===0)prefijo+=lit; else { estado=2; sufijo+=lit; } }
    else { if(estado===2)sufijo+=c; else { estado=1; region+=c; } i++; }
  }
  var pp=region.indexOf('.'), ent=pp>=0?region.slice(0,pp):region, dec=pp>=0?region.slice(pp+1):'';
  var miles=ent.indexOf(',')>=0, minEnt=contar(ent,'0'), decimales=0, minDec=contar(dec,'0');
  for(var q=0;q<dec.length;q++)if(dec[q]==='0'||dec[q]==='#'||dec[q]==='?')decimales++;
  var valor=porciento?n*100:n, red=redondear(valor,decimales,0), neg=red<0;
  var cuerpo=fixDec(Math.abs(red),decimales), pq=cuerpo.indexOf('.');
  var pe=pq>=0?cuerpo.slice(0,pq):cuerpo, pd=pq>=0?cuerpo.slice(pq+1):'';
  while(pd.length>minDec&&pd[pd.length-1]==='0')pd=pd.slice(0,-1);
  if(minEnt===0&&pe==='0'&&(decimales>0||region.indexOf('#')>=0))pe='';
  while(pe.length<minEnt)pe='0'+pe;
  if(miles){ var sb=''; for(var j=0;j<pe.length;j++){ if(j>0&&(pe.length-j)%3===0)sb+=','; sb+=pe[j]; } pe=sb; }
  return (neg?'-':'')+prefijo+pe+(pd?'.'+pd:'')+sufijo;
}
function fechaConFormato(n,formato){
  if(n<0||n>2958465.999999)return null;
  var ch=Array.from(formato), tr=[], i=0, k;
  function bajo(c){ return c.charCodeAt(0)<128?c.toLowerCase():c; }
  while(i<ch.length){
    var c=ch[i], b=bajo(c);
    if(ch.slice(i).join('').toUpperCase().indexOf('AM/PM')===0){ tr.push({t:'p',n:0}); i+=5; continue; }
    var cor=0; while(i+cor<ch.length&&bajo(ch[i+cor])===b)cor++;
    if(b==='y'||b==='a')tr.push({t:'a',n:cor}); else if(b==='m')tr.push({t:'m',n:cor}); else if(b==='d')tr.push({t:'d',n:cor});
    else if(b==='h')tr.push({t:'h',n:cor}); else if(b==='s')tr.push({t:'s',n:cor});
    else {
      if(c==='"'){ var fin=-1; for(k=i+1;k<ch.length;k++)if(ch[k]==='"'){fin=k;break;} if(fin>=0){tr.push({t:'l',x:ch.slice(i+1,fin).join('')});i=fin+1;} else {tr.push({t:'l',x:ch.slice(i+1).join('')});i=ch.length;} }
      else if(c==='\\'){ if(i+1<ch.length)tr.push({t:'l',x:ch[i+1]}); i+=2; }
      else { tr.push({t:'l',x:c}); i++; }
      continue;
    }
    i+=cor;
  }
  for(k=0;k<tr.length;k++){
    if(tr[k].t!=='m')continue;
    var antes=null, despues=null, j;
    for(j=k-1;j>=0;j--)if(tr[j].t!=='l'){antes=tr[j];break;}
    for(j=k+1;j<tr.length;j++)if(tr[j].t!=='l'){despues=tr[j];break;}
    if((antes&&antes.t==='h')||(despues&&despues.t==='s'))tr[k].t='n';
  }
  var dia=Math.floor(n), seg=Math.round((n-Math.floor(n))*86400);
  if(seg>=86400){seg-=86400;dia++;}
  var f=desdeSerie(dia); if(!f)return null;
  var a=f[0], m=f[1], d=f[2], sem=((dia-1)%7+7)%7, hh=Math.floor(seg/3600), mm=Math.floor(seg/60)%60, ss=seg%60;
  var conAmPm=tr.some(function(t){return t.t==='p';}), s='';
  tr.forEach(function(t){
    switch(t.t){
      case 'a': s+=t.n<=2?dos(a%100):String(a).padStart(4,'0'); break;
      case 'm': s+=t.n===1?String(m):t.n===2?dos(m):t.n===3?Array.from(MESES[m-1]).slice(0,3).join(''):MESES[m-1]; break;
      case 'd': s+=t.n===1?String(d):t.n===2?dos(d):t.n===3?Array.from(DIAS_SEMANA[sem]).slice(0,3).join(''):DIAS_SEMANA[sem]; break;
      case 'h': var h=conAmPm?(hh+11)%12+1:hh; s+=t.n>=2?dos(h):String(h); break;
      case 'n': s+=t.n>=2?dos(mm):String(mm); break;
      case 's': s+=t.n>=2?dos(ss):String(ss); break;
      case 'p': s+=hh<12?'AM':'PM'; break;
      default: s+=t.x;
    }
  });
  return s;
}
/** TEXTO de un número: [texto, error] (CalculoFormato.darFormato). */
function darFormato(n,fe){
  var formato=fe.split(';')[0];
  if(!isFinite(n))return [null,E_NUM];
  if(/[\[Ee]/.test(formato)&&formato.indexOf('"')<0&&/E\+|E-|e\+|e-|\[/.test(formato))return [null,E_VALOR];
  if(formato.toLowerCase()==='general'||!formato)return [general(n),null];
  var marcas=/[0#]/.test(formato);
  if(!marcas){
    var com=false, esF=false;
    for(var i=0;i<formato.length;i++){ var c=formato[i]; if(c==='"')com=!com; else if(!com&&'dmyahs'.indexOf(c.toLowerCase())>=0){esF=true;break;} }
    if(esF){ var r=fechaConFormato(n,formato); return r===null?[null,E_NUM]:[r,null]; }
  }
  return [numeroConFormato(n,formato),null];
}
function rg(f1,c1,f2,c2){ return {f1:f1,c1:c1,f2:f2,c2:c2,nf:f2-f1+1,nc:c2-c1+1,clave:f1+','+c1+','+f2+','+c2}; }
function V(v){return {k:'V',v:v};}
function C(v){return {k:'C',v:v};}
function R(r){return {k:'R',r:r};}

function crear(){
  var crudos=new Map(), valores=new Map(), arboles=new Map(), calculando=new Set(), rangos=new Map(), criterios=new Map();
  var medidas=true, nF=0, nC=0, instante=null;
  // reloj: [año, mes, día, hora, minuto, segundo, ms] en hora local.
  var yo={reloj:function(){ var d=new Date(); return [d.getFullYear(),d.getMonth()+1,d.getDate(),d.getHours(),d.getMinutes(),d.getSeconds(),d.getMilliseconds()]; }, azar:Math.random};
  function medir(){ if(medidas)return; var f=0,c=0; crudos.forEach(function(v,k){ f=Math.max(f,Math.floor(k/1024)+1); c=Math.max(c,k%1024+1); }); nF=f;nC=c;medidas=true; }
  function filas(){medir();return nF;}
  function cols(){medir();return nC;}
  function olvidar(){ valores.clear(); rangos.clear(); criterios.clear(); instante=null; }
  function poner(f,c,t){
    var k=f*1024+c;
    if(!t){ if(crudos.delete(k))medidas=false; }
    else { crudos.set(k,t); if(medidas){nF=Math.max(nF,f+1);nC=Math.max(nC,c+1);} }
    olvidar();
  }
  function crudo(f,c){ var v=crudos.get(f*1024+c); return v===undefined?'':v; }
  function texto(f,c){ var raw=crudos.get(f*1024+c); if(raw===undefined)return ''; if(!esFormula(raw))return raw[0]==="'"?raw.slice(1):raw; return mostrar(valor(f,c)); }
  function mostrarGeneral(f,c){ var raw=crudos.get(f*1024+c); if(raw===undefined)return ''; if(!esFormula(raw))return texto(f,c); var v=valor(f,c); return v.t==='n'?general(v.n):mostrar(v); }
  function alineacion(f,c){ var v=valor(f,c); return v.t==='n'?'d':(v.t==='b'||v.t==='e')?'c':'i'; }
  function valor(f,c){
    try{ return celda(f,c,0); }
    catch(x){ if(x!==HONDO)throw x; calentar(); try{ return celda(f,c,0); }catch(y){ if(y!==HONDO)throw y; return E(E_CIRC); } }
  }
  function calentar(){
    var orden=[]; crudos.forEach(function(v,k){ if(esFormula(v))orden.push(k); });
    orden.sort(function(a,b){ return (a%1024)-(b%1024)||Math.floor(a/1024)-Math.floor(b/1024); });
    var pasadas=Math.floor(orden.length/PROFUNDIDAD)+2;
    for(var p=0;p<pasadas;p++){
      var falta=false;
      for(var q=0;q<orden.length;q++){
        var k=p%2===0?orden[q]:orden[orden.length-1-q];
        if(valores.has(k))continue;
        try{ celda(Math.floor(k/1024),k%1024,0); }catch(x){ if(x!==HONDO)throw x; falta=true; }
      }
      if(!falta)return;
    }
  }
  function celda(f,c,prof){
    var k=f*1024+c;
    if(calculando.has(k))return E(E_CIRC);
    var v=valores.get(k);
    if(v!==undefined)return v;
    var raw=crudos.get(k);
    if(raw===undefined)return VACIO;
    if(!esFormula(raw)){ v=literal(raw); valores.set(k,v); return v; }
    if(prof>PROFUNDIDAD)throw HONDO;
    calculando.add(k);
    try{
      var a=arboles.get(raw); if(!a){ a=arbol(raw.slice(1)); arboles.set(raw,a); }
      v=escalar(evaluar(a,prof+1,f,c),prof+1);
      if(v.t==='v')v=S('');
      if(v.t==='n'&&!isFinite(v.n))v=E(E_NUM);
      if(!(v.t==='e'&&v.e===E_CIRC))valores.set(k,v);
      return v;
    } finally { calculando.delete(k); }
  }
  function conFallo(fn){ try{ return fn(); }catch(z){ if(z instanceof Fallo)return E(z.e); throw z; } }
  function evaluar(n,prof,f,c){
    switch(n.k){
      case 'num': return V(N(n.n));
      case 'txt': return V(S(n.s));
      case 'log': return V(B(n.b));
      case 'err': return V(E(n.e));
      case 'ref': return C(celda(n.f,n.c,prof));
      case 'rango': return R(n.col?rg(n.f1,n.c1,Math.max(n.f1,filas()-1),n.c2):rg(n.f1,n.c1,n.f2,n.c2));
      case 'menos': return V(conFallo(function(){ return N(-aNum(valorDe(n.x,prof,f,c))); }));
      case 'pc': return V(conFallo(function(){ return N(aNum(valorDe(n.x,prof,f,c))/100); }));
      case 'op': return V(operar(n.op,valorDe(n.a,prof,f,c),valorDe(n.b,prof,f,c)));
      case 'fn': return llamar(n,prof,f,c);
    }
  }
  function valorDe(n,prof,f,c){ return escalar(evaluar(n,prof,f,c),prof); }
  function escalar(e,prof){
    if(e.k!=='R')return e.v;
    return (e.r.nf===1&&e.r.nc===1)?celda(e.r.f1,e.r.c1,prof):E(E_VALOR);
  }
  function operar(op,a,b){
    return conFallo(function(){
      if(op==='&')return S(aTxt(a)+aTxt(b));
      if(op==='='||op==='<>'||op==='<'||op==='>'||op==='<='||op==='>='){
        if(a.t==='e')throw new Fallo(a.e);
        if(b.t==='e')throw new Fallo(b.e);
        var k=comparar(a,b);
        return B(op==='='?k===0:op==='<>'?k!==0:op==='<'?k<0:op==='>'?k>0:op==='<='?k<=0:k>=0);
      }
      var x=aNum(a), y=aNum(b), fx=a.t==='n'&&a.f, fy=b.t==='n'&&b.f, r;
      if(op==='+')r=x+y; else if(op==='-')r=x-y; else if(op==='*')r=x*y;
      else if(op==='/'){ if(y===0)throw new Fallo(E_DIV0); r=x/y; }
      else r=potencia(x,y);
      if(!isFinite(r))throw new Fallo(E_NUM);
      return N(r,(op==='+'&&(fx||fy))||(op==='-'&&fx&&!fy));
    });
  }
  function ev(e,prof){ return escalar(e,prof); }
  function num(e,prof){ return aNum(ev(e,prof)); }
  function txt(e,prof){ return aTxt(ev(e,prof)); }
  function log(e,prof){ return aLog(ev(e,prof)); }
  function entero(e,prof){ var n=num(e,prof); if(!isFinite(n)||Math.abs(n)>9e15)throw new Fallo(E_NUM); return n<0?Math.ceil(n):Math.floor(n); }
  function rala(r,prof){
    var hecho=rangos.get(r.clave); if(hecho)return hecho;
    var claves=[], f, c;
    if(r.nf*r.nc<=crudos.size){ for(c=r.c1;c<=r.c2;c++)for(f=r.f1;f<=r.f2;f++){ var kk=f*1024+c; if(crudos.has(kk))claves.push(kk); } }
    else {
      crudos.forEach(function(v,k){ var ff=Math.floor(k/1024), cc=k%1024; if(ff>=r.f1&&ff<=r.f2&&cc>=r.c1&&cc<=r.c2)claves.push(k); });
      claves.sort(function(a,b){ return (a%1024)-(b%1024)||Math.floor(a/1024)-Math.floor(b/1024); });
    }
    var salida=[];
    for(var i=0;i<claves.length;i++){
      var k=claves[i], v=celda(Math.floor(k/1024),k%1024,prof);
      if(v.t==='e'&&v.e===E_CIRC)throw new Fallo(E_CIRC);
      salida.push({f:Math.floor(k/1024),c:k%1024,v:v});
    }
    rangos.set(r.clave,salida);
    return salida;
  }
  function numeros(args,prof){
    var l=[];
    args.forEach(function(a){
      var v;
      if(a.k==='V'){ v=a.v; if(v.t==='n')l.push(v.n); else if(v.t==='b')l.push(v.b?1:0); else if(v.t==='s'){ var x=numero(v.s); if(x!==null)l.push(x); } else if(v.t==='e')throw new Fallo(v.e); }
      else if(a.k==='C'){ v=a.v; if(v.t==='n')l.push(v.n); else if(v.t==='e')throw new Fallo(v.e); }
      else rala(a.r,prof).forEach(function(x){ if(x.v.t==='n')l.push(x.v.n); else if(x.v.t==='e')throw new Fallo(x.v.e); });
    });
    return l;
  }
  function ahora(){
    if(instante!==null)return instante;
    var t=yo.reloj();
    instante=aSerie(t[0],t[1],t[2])+(t[3]*3600000+t[4]*60000+t[5]*1000+(t[6]||0))/86400000;
    return instante;
  }
  function llamar(fn,prof,f,c){
    var cat=CATALOGO[fn.nombre]; if(!cat)return V(E(E_NOMBRE));
    var canon=cat[0], a=fn.args;
    if(a.length<cat[1]||a.length>cat[2])return V(E(E_VALOR));
    function e(i){ return evaluar(a[i],prof,f,c); }
    try{
      switch(canon){
        case 'SI': if(!log(e(0),prof))return a.length>2?e(2):V(B(false)); return e(1);
        case 'SI.ERROR': case 'SI.ND':
          var x=e(0); if(x.k==='R')return x;
          var v=x.v;
          if(v.t==='e'&&v.e!==E_CIRC&&(canon==='SI.ERROR'||v.e===E_ND))return e(1);
          return x;
        case 'SI.CONJUNTO':
          if(a.length%2!==0)return V(E(E_VALOR));
          for(var i=0;i<a.length;i+=2)if(log(e(i),prof))return e(i+1);
          return V(E(E_ND));
        case 'FILA': case 'COLUMNA':
          var r;
          if(!a.length)r=[f,c]; else if(a[0].k==='ref')r=[a[0].f,a[0].c]; else if(a[0].k==='rango')r=[a[0].f1,a[0].c1]; else return V(E(E_VALOR));
          return V(N((canon==='FILA'?r[0]:r[1])+1));
        case 'ELEGIR':
          var n=entero(e(0),prof); if(n<1||n>=a.length)return V(E(E_VALOR)); return e(n);
      }
      var evs=a.map(function(nodo,i){ return (canon==='SUMAPRODUCTO'&&nodo.k==='ref')?R(rg(nodo.f,nodo.c,nodo.f,nodo.c)):e(i); });
      if((canon.indexOf('ES')!==0||canon==='ESPACIOS')&&canon!=='CONTARA'&&canon!=='CONTAR.BLANCO'){
        for(var q=0;q<evs.length;q++)if(evs[q].k!=='R'&&evs[q].v.t==='e')return V(evs[q].v);
      }
      return V(funcion(canon,evs,prof));
    }catch(z){ if(z instanceof Fallo)return V(E(z.e)); throw z; }
  }
  function suma(l){ var s=0; for(var i=0;i<l.length;i++)s+=l[i]; return s; }
  function funcion(canon,a,prof){
    var l, x, y, n, t, k;
    switch(canon){
      case 'VERDADERO': return B(true);
      case 'FALSO': return B(false);
      case 'Y': case 'O': return logica(canon==='Y',a,prof);
      case 'NO': return B(!log(a[0],prof));
      case 'SUMA': return N(suma(numeros(a,prof)));
      case 'CONTAR': return N(numeros(a,prof).length);
      case 'PROMEDIO': l=numeros(a,prof); if(!l.length)throw new Fallo(E_DIV0); return N(suma(l)/l.length);
      case 'MIN': l=numeros(a,prof); return N(l.length?l.reduce(function(p,q){return Math.min(p,q);}):0);
      case 'MAX': l=numeros(a,prof); return N(l.length?l.reduce(function(p,q){return Math.max(p,q);}):0);
      case 'PRODUCTO': l=numeros(a,prof); return N(l.length?l.reduce(function(p,q){return p*q;},1):0);
      case 'MEDIANA': l=numeros(a,prof); if(!l.length)throw new Fallo(E_NUM); l.sort(function(p,q){return p-q;}); k=l.length>>1; return N(l.length%2===1?l[k]:(l[k-1]+l[k])/2);
      case 'REDONDEAR': return redondearF(a,prof,0);
      case 'REDONDEAR.MAS': return redondearF(a,prof,1);
      case 'REDONDEAR.MENOS': return redondearF(a,prof,-1);
      case 'ENTERO': return N(Math.floor(num(a[0],prof)));
      case 'ABS': return N(Math.abs(num(a[0],prof)));
      case 'RESIDUO': x=num(a[0],prof); y=num(a[1],prof); if(y===0)throw new Fallo(E_DIV0); return N(x-y*Math.floor(x/y));
      case 'POTENCIA': x=num(a[0],prof); y=num(a[1],prof); return N(potencia(x,y));
      case 'RAIZ': x=num(a[0],prof); if(x<0)throw new Fallo(E_NUM); return N(Math.sqrt(x));
      case 'PI': return N(Math.PI);
      case 'ALEATORIO': return N(yo.azar());
      case 'ALEATORIO.ENTRE': x=Math.ceil(num(a[0],prof)); y=Math.floor(num(a[1],prof)); if(x>y||!isFinite(x)||!isFinite(y))throw new Fallo(E_NUM); return N(Math.min(x+Math.floor(yo.azar()*(y-x+1)),y));
      case 'CONTARA':
        n=0; a.forEach(function(q){ if(q.k==='V')n++; else if(q.k==='C'){ if(!esVacio(q.v))n++; } else rala(q.r,prof).forEach(function(z){ if(!esVacio(z.v))n++; }); }); return N(n);
      case 'CONTAR.BLANCO':
        x=a[0]; if(x.k==='R'){ n=0; rala(x.r,prof).forEach(function(z){ if(!esVacio(z.v))n++; }); return N(x.r.nf*x.r.nc-n); }
        return N(esVacio(x.v)?1:0);
      case 'CONTAR.SI': return criterioUna(a,null,'CONTAR',prof);
      case 'SUMAR.SI': return criterioUna(a,a[2]||null,'SUMAR',prof);
      case 'PROMEDIO.SI': return criterioUna(a,a[2]||null,'PROMEDIAR',prof);
      case 'CONTAR.SI.CONJUNTO': return criterioVarias(a,null,'CONTAR',prof);
      case 'SUMAR.SI.CONJUNTO': return criterioVarias(a.slice(1),a[0],'SUMAR',prof);
      case 'PROMEDIO.SI.CONJUNTO': return criterioVarias(a.slice(1),a[0],'PROMEDIAR',prof);
      case 'BUSCARV': return buscarVH(a,true,prof);
      case 'BUSCARH': return buscarVH(a,false,prof);
      case 'INDICE': return indice(a,prof);
      case 'COINCIDIR': return coincidir(a,prof);
      case 'BUSCARX': return buscarX(a,prof);
      case 'CONCATENAR': return S(a.map(function(q){ return txt(q,prof); }).join(''));
      case 'CONCAT':
        t=''; a.forEach(function(q){
          if(q.k==='R') rala(q.r,prof).slice().sort(function(p,o){ return p.f-o.f||p.c-o.c; }).forEach(function(z){ t+=aTxt(z.v); });
          else t+=txt(q,prof);
        }); return S(t);
      case 'UNIRCADENAS': return unir(a,prof);
      case 'IZQUIERDA': case 'DERECHA':
        t=letrasDe(txt(a[0],prof)); n=a.length>1?entero(a[1],prof):1; if(n<0)throw new Fallo(E_VALOR);
        k=Math.min(n,t.length); return S(canon==='IZQUIERDA'?t.slice(0,k).join(''):t.slice(t.length-k).join(''));
      case 'EXTRAE':
        t=letrasDe(txt(a[0],prof)); x=entero(a[1],prof); y=entero(a[2],prof); if(x<1||y<0)throw new Fallo(E_VALOR);
        k=Math.min(x-1,t.length); return S(t.slice(k,Math.min(k+y,t.length)).join(''));
      case 'LARGO': return N(letrasDe(txt(a[0],prof)).length);
      case 'MAYUSC': return S(txt(a[0],prof).toUpperCase());
      case 'MINUSC': return S(txt(a[0],prof).toLowerCase());
      case 'NOMPROPIO': return S(nomPropio(txt(a[0],prof)));
      case 'ESPACIOS': return S(txt(a[0],prof).split(' ').filter(function(p){return p!=='';}).join(' '));
      case 'SUSTITUIR': return sustituir(a,prof);
      case 'ENCONTRAR': return buscarTexto(a,true,prof);
      case 'HALLAR': return buscarTexto(a,false,prof);
      case 'TEXTO': x=ev(a[0],prof); t=txt(a[1],prof); return textoConFormato(x,t);
      case 'VALOR':
        x=ev(a[0],prof);
        if(x.t==='n')return N(x.n); if(x.t==='b')throw new Fallo(E_VALOR); if(x.t==='e')return x; if(x.t==='v')return N(0);
        if(!x.s.trim())return N(0);
        y=numero(x.s); if(y===null)y=serieDeTexto(x.s); if(y===null)throw new Fallo(E_VALOR); return N(y);
      case 'REPETIR': n=entero(a[1],prof); if(n<0||n>10000)throw new Fallo(E_VALOR); return S(txt(a[0],prof).repeat(n));
      case 'HOY': return N(Math.floor(ahora()),true);
      case 'AHORA': return N(ahora(),true);
      case 'FECHA': return N(fechaF(num(a[0],prof),num(a[1],prof),num(a[2],prof)),true);
      case 'AÑO': case 'MES': case 'DIA':
        x=desdeSerie(Math.floor(serie(a[0],prof))); if(!x)throw new Fallo(E_NUM);
        return N(x[canon==='AÑO'?0:canon==='MES'?1:2]);
      case 'DIAS': return N(Math.floor(serie(a[0],prof))-Math.floor(serie(a[1],prof)));
      case 'DIASEM':
        x=serie(a[0],prof); n=a.length>1?entero(a[1],prof):1; k=((Math.floor(x)-1)%7+7)%7;
        if(n===1||n===17)return N(k+1); if(n===2||n===11)return N((k+6)%7+1); if(n===3)return N((k+6)%7);
        throw new Fallo(E_NUM);
      case 'ESERROR': case 'ESND': case 'ESBLANCO': case 'ESNUMERO': case 'ESTEXTO':
        x=ev(a[0],prof); if(x.t==='e'&&x.e===E_CIRC)throw new Fallo(E_CIRC);
        return B(canon==='ESERROR'?x.t==='e':canon==='ESND'?(x.t==='e'&&x.e===E_ND):canon==='ESBLANCO'?esVacio(x):canon==='ESNUMERO'?x.t==='n':(x.t==='s'&&x.s!==''));
      case 'TRUNCAR': n=a.length>1?Math.max(-400,Math.min(400,entero(a[1],prof))):0; return N(redondear(num(a[0],prof),n,-1));
      case 'DESVEST': l=numeros(a,prof); if(l.length<2)throw new Fallo(E_DIV0); x=suma(l)/l.length; return N(Math.sqrt(suma(l.map(function(q){return (q-x)*(q-x);}))/(l.length-1)));
      case 'EXP': return N(Math.exp(num(a[0],prof)));
      case 'LN': x=num(a[0],prof); if(x<=0)throw new Fallo(E_NUM); return N(Math.log(x));
      case 'LOG10': x=num(a[0],prof); if(x<=0)throw new Fallo(E_NUM); return N(Math.log10(x));
      case 'LOG': x=num(a[0],prof); y=a.length>1?num(a[1],prof):10; if(x<=0||y<=0)throw new Fallo(E_NUM); if(y===1)throw new Fallo(E_DIV0); return N(Math.log(x)/Math.log(y));
      case 'SENO': return N(Math.sin(num(a[0],prof)));
      case 'COS': return N(Math.cos(num(a[0],prof)));
      case 'TAN': return N(Math.tan(num(a[0],prof)));
      case 'GRADOS': return N(num(a[0],prof)*180/Math.PI);
      case 'RADIANES': return N(num(a[0],prof)*Math.PI/180);
      case 'SIGNO': x=num(a[0],prof); return N(x>0?1:x<0?-1:0);
      case 'SUMAPRODUCTO': return sumaProducto(a,prof);
    }
    throw new Fallo(E_NOMBRE);
  }
  function logica(y,a,prof){
    var hay=false, r=y;
    a.forEach(function(x){
      var lista=x.k==='R'?rala(x.r,prof).map(function(z){return z.v;}):[x.v];
      lista.forEach(function(v){
        var b;
        if(v.t==='e')throw new Fallo(v.e);
        else if(v.t==='n')b=v.n!==0;
        else if(v.t==='b')b=v.b;
        else { if(x.k!=='V')return; b=aLog(v); }
        hay=true; r=y?(r&&b):(r||b);
      });
    });
    if(!hay)throw new Fallo(E_VALOR);
    return B(r);
  }
  function redondearF(a,prof,modo){
    var n=num(a[0],prof), d=Math.max(-400,Math.min(400,entero(a[1],prof))), r=redondear(n,d,modo);
    if(!isFinite(r))throw new Fallo(E_NUM); return N(r);
  }
  function rangoDe(e){ if(e.k!=='R')throw new Fallo(E_VALOR); return e.r; }
  function criterioUna(a,sumaE,que,prof){
    var crit=ev(a[1],prof); if(crit.t==='e')throw new Fallo(crit.e);
    var r=rangoDe(a[0]), s=sumaE?rangoDe(sumaE):null;
    return resolver([[r,Criterio.de(crit)]],s,que,que+'|'+r.clave+'|'+(s?s.clave:'-')+'|'+claveDe(crit),prof);
  }
  function criterioVarias(a,sumaE,que,prof){
    if(a.length%2!==0)throw new Fallo(E_VALOR);
    var pares=[], clave=que;
    for(var i=0;i<a.length;i+=2){
      var crit=ev(a[i+1],prof); if(crit.t==='e')throw new Fallo(crit.e);
      var r=rangoDe(a[i]); clave+='|'+r.clave+'|'+claveDe(crit); pares.push([r,Criterio.de(crit)]);
    }
    var s=null;
    if(sumaE){ s=rangoDe(sumaE); if(s.nf!==pares[0][0].nf||s.nc!==pares[0][0].nc)throw new Fallo(E_VALOR); }
    return resolver(pares,s,que,clave+'|'+(s?s.clave:'-'),prof);
  }
  function resolver(pares,sumaR,que,clave,prof){
    var hecho=criterios.get(clave); if(hecho)return hecho;
    var fr=pares[0][0].nf, cr=pares[0][0].nc, i, k;
    pares.forEach(function(p){ if(p[0].nf!==fr||p[0].nc!==cr)throw new Fallo(E_VALOR); });
    var s=sumaR?rg(sumaR.f1,sumaR.c1,Math.min(sumaR.f1+Math.max(fr,1)-1,FILAS_EN_FORMULA-1),Math.min(sumaR.c1+Math.max(cr,1)-1,MAX_COLS-1)):null;
    var extF=filas(), extC=cols(), cajaF=0, cajaC=0;
    pares.map(function(p){return p[0];}).concat(s?[s]:[]).forEach(function(r){
      cajaF=Math.max(cajaF,Math.min(Math.max(0,extF-r.f1),fr)); cajaC=Math.max(cajaC,Math.min(Math.max(0,extC-r.c1),cr));
    });
    var leidos=pares.map(function(p){ return rala(p[0],prof); }), leidoS=s?rala(s,prof):null;
    function cuadricula(l,r){ var c=new Array(cajaF*cajaC).fill(VACIO); l.forEach(function(x){ var ii=x.f-r.f1, jj=x.c-r.c1; if(ii<cajaF&&jj<cajaC)c[ii*cajaC+jj]=x.v; }); return c; }
    var datos=pares.map(function(p,j){ return cuadricula(leidos[j],p[0]); }), datosS=s?cuadricula(leidoS,s):null;
    var cuenta=0, total=0, conNum=0;
    for(k=0;k<cajaF*cajaC;k++){
      var todos=true; for(i=0;i<pares.length;i++)if(!pares[i][1].cumple(datos[i][k])){todos=false;break;}
      if(!todos)continue;
      cuenta++;
      if(que==='CONTAR')continue;
      var v=(datosS||datos[0])[k];
      if(v.t==='n'){ total+=v.n; conNum++; } else if(v.t==='e')throw new Fallo(v.e);
    }
    if(que==='CONTAR'){ var fuera=fr*cr-cajaF*cajaC; if(fuera>0&&pares.every(function(p){return p[1].cumple(S(''));}))cuenta+=fuera; }
    var r;
    if(que==='CONTAR')r=N(cuenta); else if(que==='SUMAR')r=N(total); else { if(!conNum)throw new Fallo(E_DIV0); r=N(total/conNum); }
    criterios.set(clave,r);
    return r;
  }
  function matriz(e){ return e.k==='R'?{r:e.r,dim:[e.r.nf,e.r.nc]}:{una:e.v,dim:[1,1]}; }
  function enM(m,f,c,prof){ return m.r?celda(m.r.f1+f,m.r.c1+c,prof):m.una; }
  function lineaM(m,vertical,prof){
    if(!m.r)return [[0,m.una]];
    var r=m.r, sub=vertical?rg(r.f1,r.c1,r.f2,r.c1):rg(r.f1,r.c1,r.f1,r.c2);
    return rala(sub,prof).map(function(z){ return [vertical?z.f-r.f1:z.c-r.c1,z.v]; });
  }
  function ultimoMenorOIgual(b,celdas){
    var mejor=null;
    for(var i=0;i<celdas.length;i++){ var o=ordenDelMismoTipo(celdas[i][1],b); if(o===null)continue; if(o>0)break; mejor=celdas[i][0]; }
    return mejor;
  }
  function primeraIgual(b,celdas,com){ for(var i=0;i<celdas.length;i++)if(esIgual(b,celdas[i][1],com))return celdas[i][0]; return null; }
  function buscarVH(a,vertical,prof){
    var b=vacioComoTexto(ev(a[0],prof)), m=matriz(a[1]), n=entero(a[2],prof), aprox=a.length>3?log(a[3],prof):true;
    if(n<1)throw new Fallo(E_VALOR);
    if(n>(vertical?m.dim[1]:m.dim[0]))throw new Fallo(E_REF);
    var celdas=lineaM(m,vertical,prof), pos=aprox?ultimoMenorOIgual(b,celdas):primeraIgual(b,celdas,true);
    if(pos===null)throw new Fallo(E_ND);
    return vertical?enM(m,pos,n-1,prof):enM(m,n-1,pos,prof);
  }
  function indice(a,prof){
    var m=matriz(a[0]), x=entero(a[1],prof), f, k;
    if(a.length>2){ f=x; k=entero(a[2],prof); } else if(m.dim[1]===1){ f=x; k=1; } else if(m.dim[0]===1){ f=1; k=x; } else throw new Fallo(E_VALOR);
    if(f<=0||k<=0)throw new Fallo(E_VALOR);
    if(f>m.dim[0]||k>m.dim[1])throw new Fallo(E_REF);
    return enM(m,f-1,k-1,prof);
  }
  function lineaUnica(e,prof){ var m=matriz(e); if(m.dim[0]!==1&&m.dim[1]!==1)throw new Fallo(E_ND); return [m,lineaM(m,m.dim[1]===1,prof)]; }
  function coincidir(a,prof){
    var b=vacioComoTexto(ev(a[0],prof)), celdas=lineaUnica(a[1],prof)[1], tipo=a.length>2?num(a[2],prof):1, pos=null;
    if(tipo===0)pos=primeraIgual(b,celdas,true);
    else if(tipo>0)pos=ultimoMenorOIgual(b,celdas);
    else for(var i=0;i<celdas.length;i++){ var o=ordenDelMismoTipo(celdas[i][1],b); if(o===null)continue; if(o<0)break; pos=celdas[i][0]; }
    if(pos===null)throw new Fallo(E_ND);
    return N(pos+1);
  }
  function buscarX(a,prof){
    var b=vacioComoTexto(ev(a[0],prof)), lu=lineaUnica(a[1],prof), donde=lu[0], celdas=lu[1], dev=matriz(a[2]);
    var df=dev.dim[0], dc=dev.dim[1];
    if(df!==1&&dc!==1)throw new Fallo(E_VALOR);
    if(Math.max(donde.dim[0],donde.dim[1])!==Math.max(df,dc))throw new Fallo(E_VALOR);
    var modo=a.length>4?entero(a[4],prof):0, sentido=a.length>5?entero(a[5],prof):1;
    if(modo<-1||modo>2||[-2,-1,1,2].indexOf(sentido)<0)throw new Fallo(E_VALOR);
    if(sentido<0)celdas=celdas.slice().reverse();
    var pos=primeraIgual(b,celdas,modo===2);
    if(pos===null&&(modo===-1||modo===1)){
      var mejor=null;
      celdas.forEach(function(z){
        var o=ordenDelMismoTipo(z[1],b); if(o===null)return;
        if(modo===-1?o>=0:o<=0)return;
        if(mejor===null||(function(q){ return modo===-1?q>0:q<0; })(comparar(z[1],mejor[1])))mejor=z;
      });
      pos=mejor?mejor[0]:null;
    }
    if(pos!==null)return df===1?enM(dev,0,pos,prof):enM(dev,pos,0,prof);
    if(a.length>3)return ev(a[3],prof);
    throw new Fallo(E_ND);
  }
  function unir(a,prof){
    var sep=txt(a[0],prof), ignorar=log(a[1],prof), trozos=[];
    a.slice(2).forEach(function(x){
      if(x.k==='R'){
        var mapa=new Map(); rala(x.r,prof).forEach(function(z){ mapa.set(z.f*1024+z.c,z.v); });
        var f2=Math.min(x.r.f2,filas()-1), c2=Math.min(x.r.c2,cols()-1);
        if(x.r.f1<=f2&&x.r.c1<=c2)for(var f=x.r.f1;f<=f2;f++)for(var c=x.r.c1;c<=c2;c++)trozos.push(aTxt(mapa.get(f*1024+c)||VACIO));
      } else trozos.push(txt(x,prof));
    });
    return S((ignorar?trozos.filter(function(t){return t!=='';}):trozos).join(sep));
  }
  function sustituir(a,prof){
    var t=txt(a[0],prof), viejo=txt(a[1],prof), nuevo=txt(a[2],prof), cual=null;
    if(a.length>3){ cual=entero(a[3],prof); if(cual<1)throw new Fallo(E_VALOR); }
    if(!viejo)return S(t);
    var sb='', desde=0, visto=0;
    while(true){ var i=t.indexOf(viejo,desde); if(i<0)break; visto++; sb+=t.slice(desde,i)+((cual===null||cual===visto)?nuevo:viejo); desde=i+viejo.length; }
    return S(sb+t.slice(desde));
  }
  function buscarTexto(a,exacto,prof){
    var quiero=txt(a[0],prof), en=txt(a[1],prof), desde=a.length>2?entero(a[2],prof):1, e0=letrasDe(en);
    if(desde<1||desde>Math.max(e0.length,1))throw new Fallo(E_VALOR);
    var d=desde-1, q=exacto?letrasDe(quiero):letrasDe(quiero.toLowerCase()), e=exacto?e0:letrasDe(en.toLowerCase());
    if(!q.length)return N(d+1);
    for(var i=d;i<e.length;i++){
      var casa;
      if(exacto){ casa=i+q.length<=e.length; for(var j=0;casa&&j<q.length;j++)if(e[i+j]!==q[j])casa=false; }
      else casa=comodinL(q,0,e,i,false);
      if(casa)return N(i+1);
    }
    throw new Fallo(E_VALOR);
  }
  function textoConFormato(v,formato){
    var n;
    if(v.t==='e')throw new Fallo(v.e);
    if(v.t==='n')n=v.n;
    else if(v.t==='b')return S(v.b?'VERDADERO':'FALSO');
    else if(v.t==='v')return S('');
    else { n=v.s!==''?numero(v.s):null; if(n===null)n=serieDeTexto(v.s); if(n===null)return S(v.s); }
    var r=darFormato(n,formato); if(r[1])throw new Fallo(r[1]); return S(r[0]);
  }
  function serie(e,prof){
    var v=ev(e,prof), s;
    if(v.t==='n')s=v.n; else if(v.t==='b')s=v.b?1:0; else if(v.t==='v')s=0; else if(v.t==='e')throw new Fallo(v.e);
    else { if(v.s==='')s=0; else { s=serieDeTexto(v.s); if(s===null)throw new Fallo(E_VALOR); } }
    if(s<0||!isFinite(s)||s>2958465)throw new Fallo(E_NUM);
    return s;
  }
  function fechaF(a0,m0,d0){
    function tr(x){ return x<0?Math.ceil(x):Math.floor(x); }
    var a=tr(a0), m=tr(m0), d=tr(d0);
    if(!(a>=0&&a<10000))throw new Fallo(E_NUM);
    if(a<1900)a+=1900;
    if(Math.abs(m)>1e6||Math.abs(d)>1e7)throw new Fallo(E_NUM);
    var total=a*12+(m-1), anio=Math.floor(total/12), mes=total-anio*12+1;
    var s=(anio===1900&&mes===2&&d===29)?60:aSerie(anio,mes,d);
    if(!(s>=0&&s<=2958465))throw new Fallo(E_NUM);
    return s;
  }
  function sumaProducto(a,prof){
    var rs=a.map(function(e){ return rangoDe(e); }), base=rs[0];
    rs.forEach(function(r){ if(r.nf!==base.nf||r.nc!==base.nc)throw new Fallo(E_VALOR); });
    var leidas=rs.map(function(r){ var m=new Map(); rala(r,prof).forEach(function(z){ m.set((z.f-r.f1)*1024+(z.c-r.c1),z); }); return m; });
    var s=0;
    leidas[0].forEach(function(x,k){
      var p=1;
      for(var i=0;i<leidas.length;i++){ var z=i===0?x:leidas[i].get(k), v=z?z.v:VACIO; if(v.t==='e')throw new Fallo(v.e); p*=v.t==='n'?v.n:0; }
      s+=p;
    });
    for(var i=1;i<leidas.length;i++)leidas[i].forEach(function(z){ if(z.v.t==='e')throw new Fallo(z.v.e); });
    return N(s);
  }
  yo.poner=poner; yo.crudo=crudo; yo.texto=texto; yo.valor=valor; yo.alineacion=alineacion; yo.mostrarGeneral=mostrarGeneral;
  yo.filas=filas; yo.cols=cols; yo.olvidar=olvidar;
  yo.esFormula=function(f,c){return esFormula(crudo(f,c));};
  yo.cada=function(fn){ crudos.forEach(function(v,k){ fn(Math.floor(k/1024),k%1024,v); }); };
  yo.caja=function(){
    if(!crudos.size)return null;
    var f1=Infinity,c1=Infinity,f2=-1,c2=-1;
    crudos.forEach(function(v,k){ var f=Math.floor(k/1024),c=k%1024; if(f<f1)f1=f; if(c<c1)c1=c; if(f>f2)f2=f; if(c>c2)c2=c; });
    return [f1,c1,f2,c2];
  };
  return yo;
}
return {crear:crear, letras:letras, columna:columna, nombre:nombre, leer:leer, numero:numero, fecha:fecha,
  general:general, redondear:redondear, darFormato:darFormato, textoDeFecha:textoDeFecha, serial:serial,
  aSerie:aSerie, desdeSerie:desdeSerie, serieDeTexto:serieDeTexto,
  digitos:digitos, conFormatoDeCelda:conFormatoDeCelda, fechaCorta:fechaCorta, fechaDeSerie:fechaDeSerie, visto:visto,
  piezas:piezas, arbol:arbol, desplazar:desplazar, r1c1:r1c1, aR1c1:aR1c1, deTsv:deTsv, aTsv:aTsv,
  esFormula:esFormula, literal:literal, mostrar:mostrar, comodin:comodin, referencias:referencias};
})();
"""

    /**
     * **El visor.** `crearTabla(hoja, api)` devuelve el controlador que el armazón de
     * [ExportarHtml] espera de cada página: activar, encajar, zoom, deshacer, y además
     * `tecla` (para que las letras escritas en la tabla no disparen los atajos del armazón),
     * `accion` (los botones de la barra) y `json`/`estatica` (guardar).
     */
    val JS = """
function crearTabla(d,api){
"use strict";
var ANCHO=96, CAB=46, MAX_PINTADAS=5000, NS='http://www.w3.org/2000/svg';
// Los colores de las celdas citadas mientras se escribe una fórmula, en orden de aparición.
var COLORES_REF=['#1a73e8','#d93025','#8e24aa','#188038','#e8710a','#0097a7','#c2185b','#795548'];
// Ver [ExportarHtml.TABLAS_SOLO_VER]: con esto se mira, se elige, se copia y se raya; no se escribe.
var editable=document.body.dataset.tablaEditable!=='0';
var MONEDA=document.body.dataset.moneda||'S/';
var guion=d.querySelector('script.tabla');
var datos={};
try{ datos=JSON.parse(guion.textContent)||{}; }catch(e){ datos={}; }
var calc=Calculo.crear();
var estilos={}, anchos={}, nombreTabla=datos.nombre||'', protegida=!!datos.protegida;
(function(){
  var cs=datos.celdas||{}, k, p;
  for(k in cs){ p=Calculo.leer(k); if(p)calc.poner(p[0],p[1],String(cs[k])); }
  var es=datos.estilos||{};
  for(k in es){ p=Calculo.leer(k); if(p&&es[k])estilos[p[0]*1024+p[1]]=es[k]; }
  var an=datos.anchos||{};
  for(k in an){ var c=Calculo.columna(k); if(c>=0)anchos[c]=+an[k]; }
})();
var caja=d.querySelector('.tabla-caja'), dirEl=d.querySelector('.tabla-dir'), fx=d.querySelector('.tabla-fx-in');
if(!editable){ fx.readOnly=true; fx.placeholder='Solo lectura'; }
var svg=caja.querySelector('svg.tinta'), origen=svg?svg.querySelector('g.origen'):null, croquis=svg?svg.querySelector('#croquis'):null;
var vieja=caja.querySelector('table.calc'); if(vieja)caja.removeChild(vieja);
var tabla=document.createElement('table'); tabla.className='calc';
var marcas=document.createElement('div'); marcas.className='tabla-marcas';
// **La tabla va dentro de un mundo que se pasea y se amplía**, como un dibujo en el lienzo: así
// alrededor queda sitio para anotar, y se puede empezar a rayar fuera de las celdas.
var mundo=document.createElement('div'); mundo.className='tabla-mundo';
mundo.appendChild(tabla); mundo.appendChild(marcas); if(svg)mundo.appendChild(svg);
caja.appendChild(mundo);
var cabeza=null, cuerpo=null, filaCab=null, esq=null;
var B={f0:0,c0:0,f1:-1,c1:-1}, tds=[], zoom=1, activo=false, O={x:0,y:0};
// La vista: dónde cae la esquina de la tabla en la caja y a qué aumento. Ver [aplicarVista].
var V={x:16,y:16,z:1}, punteros=new Map(), gesto=null, hayLapiz=false, centrada=false;
var act={f:0,c:0}, ancla={f:0,c:0};
var editando=false, original='', mantener=false, refPuesta=null;
var hecho=[], rehecho=[], portapapeles=null;
var arrastrando=false, refArrastre=false, toque=null, ultimoToque=null;
var modo='mano', trazo=null;

// ---- El marco: lo escrito más uno alrededor ----
function limites(){
  var k=calc.caja();
  if(!k)return {f0:0,c0:0,f1:1,c1:1};
  var f0=Math.max(0,k[0]-1), c0=Math.max(0,k[1]-1);
  return {f0:f0,c0:c0,f1:Math.min(99999,k[2]+1,f0+MAX_PINTADAS-1),c1:Math.min(701,k[3]+1)};
}
function mismo(a,b){ return a.f0===b.f0&&a.c0===b.c0&&a.f1===b.f1&&a.c1===b.c1; }
/**
 * Si esta celda se deja cambiar: nada con la página en solo lectura; en una tabla protegida,
 * solo las marcadas como editables. Lo que dependa de ellas recalcula igual.
 */
function puedeEditar(f,c){ if(!editable)return false; if(!protegida)return true; var e=estilos[f*1024+c]; return !!(e&&e.e); }
function ancho(c){ return Math.round((anchos[c]||ANCHO)*zoom); }
function anchoTotal(){ var w=Math.round(CAB*zoom); for(var c=B.c0;c<=B.c1;c++)w+=ancho(c); return w; }
function celdaTd(f,c){ var r=tds[f-B.f0]; return r?(r[c-B.c0]||null):null; }
function nuevaTd(f,c){ var td=document.createElement('td'); td.dataset.f=f; td.dataset.c=c; return td; }
function thCol(c){ var h=document.createElement('th'); h.textContent=Calculo.letras(c); h.dataset.c=c; h.style.width=ancho(c)+'px'; return h; }
function filaNueva(f){
  var tr=cuerpo.insertRow(), n=document.createElement('th'), fila=[], c;
  n.textContent=f+1; n.dataset.f=f; tr.appendChild(n);
  for(c=B.c0;c<=B.c1;c++){ var td=nuevaTd(f,c); tr.appendChild(td); fila.push(td); }
  tds.push(fila);
  for(c=B.c0;c<=B.c1;c++)pintarCelda(f,c);
}
/**
 * Rehace la rejilla al marco que toca. Crecer por abajo o por la derecha —lo corriente al ir
 * llenando— añade lo que falta; cualquier otro cambio la rehace entera.
 */
function construir(){
  var nb=limites(), f, c;
  if(B.f1>=0&&nb.f0===B.f0&&nb.c0===B.c0&&nb.f1>=B.f1&&nb.c1>=B.c1){
    while(B.c1<nb.c1){
      B.c1++; filaCab.appendChild(thCol(B.c1));
      for(f=B.f0;f<=B.f1;f++){ var td=nuevaTd(f,B.c1); cuerpo.rows[f-B.f0].appendChild(td); tds[f-B.f0].push(td); pintarCelda(f,B.c1); }
    }
    while(B.f1<nb.f1){ B.f1++; filaNueva(B.f1); }
  } else {
    B={f0:nb.f0,c0:nb.c0,f1:nb.f0-1,c1:nb.c1};
    tabla.innerHTML=''; tds=[];
    cabeza=tabla.createTHead(); cuerpo=tabla.createTBody(); filaCab=cabeza.insertRow();
    esq=document.createElement('th'); esq.className='esq'; filaCab.appendChild(esq);
    for(c=B.c0;c<=B.c1;c++)filaCab.appendChild(thCol(c));
    while(B.f1<nb.f1){ B.f1++; filaNueva(B.f1); }
  }
  esq.style.width=esq.style.minWidth=Math.round(CAB*zoom)+'px';
  tabla.style.width=anchoTotal()+'px';
  marcarCabeceras(rango());
  medirTinta();
  pintarMarcas();
}
function rango(){ return {f:Math.min(act.f,ancla.f),c:Math.min(act.c,ancla.c),f2:Math.max(act.f,ancla.f),c2:Math.max(act.c,ancla.c)}; }
function enRango(r,f,c){ return f>=r.f&&f<=r.f2&&c>=r.c&&c<=r.c2; }
function clase(f,c){
  var td=celdaTd(f,c); if(!td)return;
  var r=rango(), k=td._b||'';
  if(enRango(r,f,c)&&(r.f!==r.f2||r.c!==r.c2))k+=' sel';
  if(f===act.f&&c===act.c)k+=' act';
  if(td.className!==k)td.className=k;
}
function pintarCelda(f,c){
  var td=celdaTd(f,c); if(!td)return;
  var es=estilos[f*1024+c], raw=calc.crudo(f,c), t=raw?Calculo.visto(calc,es,f,c,MONEDA):'', v=raw?calc.valor(f,c):null;
  var al=es&&es.a?es.a:(v?(v.t==='n'?'d':(v.t==='b'||v.t==='e')?'c':'i'):'i');
  var b=al==='d'?'d':al==='c'?'c':'';
  if(es&&es.n)b+=(b?' ':'')+'n';
  if(es&&es.k)b+=(b?' ':'')+'k';
  if(es&&es.s)b+=(b?' ':'')+'s';
  if(es&&es.t)b+=(b?' ':'')+'t';
  if(es&&es.w)b+=(b?' ':'')+'w';
  if(v&&v.t==='e')b+=(b?' ':'')+'err';
  // Las celdas con fórmula, sombreadas: se ve de un vistazo qué se calcula y qué se escribió.
  if(Calculo.esFormula(raw))b+=(b?' ':'')+'f';
  if(protegida&&es&&es.e)b+=(b?' ':'')+'ed';
  td._b=b;
  if(td.textContent!==t)td.textContent=t;
  var fondo=es&&es.f&&/^#[0-9a-fA-F]{6}$/.test(es.f)?es.f:'';
  if(td.style.background!==fondo)td.style.background=fondo;
  var letra=es&&es.c&&/^#[0-9a-fA-F]{6}$/.test(es.c)?es.c:'';
  if(td.style.color!==letra)td.style.color=letra;
  clase(f,c);
}
/** Tras un cambio: el marco si se movió, lo tocado y todas las fórmulas, que pueden cambiar solas. */
function repintar(tocadas){
  var i;
  if(!mismo(limites(),B))construir();
  for(i=0;i<tocadas.length;i++)pintarCelda(tocadas[i][0],tocadas[i][1]);
  calc.cada(function(f,c,v){ if(v.length>1&&v[0]==='=')pintarCelda(f,c); });
  if(act.f<B.f0||act.f>B.f1||act.c<B.c0||act.c>B.c1||ancla.f<B.f0||ancla.f>B.f1||ancla.c<B.c0||ancla.c>B.c1)seleccionar(act.f,act.c,false,true);
  mostrarBarra(); estadoRango(); api.refrescar();
}
function marcarCabeceras(r){
  var i;
  if(!filaCab)return;
  for(i=1;i<filaCab.cells.length;i++){ var h=filaCab.cells[i], c=B.c0+i-1, m=c>=r.c&&c<=r.c2; if(h.classList.contains('marcada')!==m)h.classList.toggle('marcada',m); }
  for(i=0;i<cuerpo.rows.length;i++){ var th=cuerpo.rows[i].cells[0], f=B.f0+i, mm=f>=r.f&&f<=r.f2; if(th.classList.contains('marcada')!==mm)th.classList.toggle('marcada',mm); }
}
function seleccionar(f,c,extender,sinVer){
  f=Math.max(B.f0,Math.min(B.f1,f)); c=Math.max(B.c0,Math.min(B.c1,c));
  var viejo=rango(), x, y;
  act={f:f,c:c};
  if(!extender)ancla={f:f,c:c};
  else ancla={f:Math.max(B.f0,Math.min(B.f1,ancla.f)),c:Math.max(B.c0,Math.min(B.c1,ancla.c))};
  var nuevo=rango();
  for(x=Math.max(viejo.f,B.f0);x<=Math.min(viejo.f2,B.f1);x++)for(y=Math.max(viejo.c,B.c0);y<=Math.min(viejo.c2,B.c1);y++)clase(x,y);
  for(x=nuevo.f;x<=nuevo.f2;x++)for(y=nuevo.c;y<=nuevo.c2;y++)clase(x,y);
  marcarCabeceras(nuevo);
  mostrarBarra(); estadoRango();
  if(!sinVer)verCelda(f,c);
}
/** Pasea lo justo para que la celda se vea entera (moverse con las flechas o con Enter). */
function verCelda(f,c){
  var td=celdaTd(f,c); if(!td||!caja.clientWidth)return;
  var x0=V.x+td.offsetLeft*V.z, y0=V.y+td.offsetTop*V.z, x1=x0+td.offsetWidth*V.z, y1=y0+td.offsetHeight*V.z;
  var W=caja.clientWidth, H=caja.clientHeight-90, m=12, cambio=false;
  if(x0<m){ V.x+=m-x0; cambio=true; } else if(x1>W-m){ V.x-=x1-(W-m); cambio=true; }
  if(y0<m){ V.y+=m-y0; cambio=true; } else if(y1>H){ V.y-=y1-H; cambio=true; }
  if(cambio)aplicarVista();
}
function mostrarBarra(){
  var r=rango();
  dirEl.textContent=(r.f===r.f2&&r.c===r.c2)?Calculo.nombre(act.f,act.c):Calculo.nombre(r.f,r.c)+':'+Calculo.nombre(r.f2,r.c2);
  if(!editando){
    fx.value=calc.crudo(act.f,act.c);
    fx.readOnly=!puedeEditar(act.f,act.c);
    fx.placeholder=!editable?'Solo lectura':(fx.readOnly?'Celda protegida':'Valor o =SUMA(A1:A3)');
  }
}
/** Lo que dice la barra de estado de Excel: suma, promedio y cuenta de lo elegido. */
function estadoRango(){
  var r=rango();
  if(r.f===r.f2&&r.c===r.c2){ api.decir(''); return; }
  var s=0,n=0,llenas=0,f,c,f2=Math.min(r.f2,calc.filas()-1),c2=Math.min(r.c2,calc.cols()-1);
  for(f=r.f;f<=f2;f++)for(c=r.c;c<=c2;c++){ if(!calc.crudo(f,c))continue; llenas++; var v=calc.valor(f,c); if(v.t==='n'){s+=v.n;n++;} }
  api.decir(n?'Suma '+Calculo.general(s)+' · Promedio '+Calculo.general(s/n)+' · Cuenta '+llenas:(llenas?'Cuenta '+llenas:''));
}

// ---- Las celdas que cita la fórmula, cada una de su color ----
function pintarMarcas(){
  marcas.innerHTML='';
  if(!editando||fx.value[0]!=='=')return;
  var vistos={}, n=0;
  Calculo.referencias(fx.value).forEach(function(r){
    var k=r.f1+','+r.c1+','+r.f2+','+r.c2;
    if(!(k in vistos))vistos[k]=n++;
    var color=COLORES_REF[vistos[k]%COLORES_REF.length];
    var f1=Math.max(r.f1,B.f0), c1=Math.max(r.c1,B.c0), f2=Math.min(r.f2,B.f1), c2=Math.min(r.c2,B.c1);
    if(f1>f2||c1>c2)return;
    var a=celdaTd(f1,c1), b=celdaTd(f2,c2); if(!a||!b)return;
    var m=document.createElement('div'); m.className='tabla-marca';
    m.style.left=a.offsetLeft+'px'; m.style.top=a.offsetTop+'px';
    m.style.width=(b.offsetLeft+b.offsetWidth-a.offsetLeft)+'px';
    m.style.height=(b.offsetTop+b.offsetHeight-a.offsetTop)+'px';
    m.style.borderColor=color; m.style.background=color+'1f';
    m.dataset.ref=k;
    marcas.appendChild(m);
  });
}

// ---- Escribir, con deshacer ----
function escribir(cambios,estilosNuevos){
  if(!editable)return;
  var paso={celdas:[],estilos:[]}, tocadas=[], i, protegidas=0;
  for(i=0;i<cambios.length;i++){
    var f=cambios[i][0], c=cambios[i][1], t=cambios[i][2];
    if(f<0||c<0||f>=100000||c>=702)continue;
    if(!puedeEditar(f,c)){ if(calc.crudo(f,c)!==t)protegidas++; continue; }
    var antes=calc.crudo(f,c); if(antes===t)continue;
    paso.celdas.push([f*1024+c,antes,t]); calc.poner(f,c,t); tocadas.push([f,c]);
  }
  if(estilosNuevos)for(i=0;i<estilosNuevos.length;i++){
    var k=estilosNuevos[i][0], a=estilos[k]||null, dsp=estilosNuevos[i][1];
    if(JSON.stringify(a)===JSON.stringify(dsp))continue;
    if(!puedeEditar(Math.floor(k/1024),k%1024))continue;
    // La marca de editable no la cambia quien recibe la página.
    if(dsp&&a&&a.e)dsp.e=true; else if(dsp)delete dsp.e;
    paso.estilos.push([k,a,dsp]); if(dsp)estilos[k]=dsp; else delete estilos[k];
    tocadas.push([Math.floor(k/1024),k%1024]);
  }
  if(protegidas)api.decir(protegidas===1?'Esa celda está protegida':protegidas+' celdas protegidas no se cambiaron');
  if(!paso.celdas.length&&!paso.estilos.length)return;
  anotar(paso);
  repintar(tocadas);
}
function anotar(paso){ hecho.push(paso); if(hecho.length>200)hecho.shift(); rehecho.length=0; api.refrescar(); }
/** Un paso es de celdas o de tinta; los dos van en la misma pila, como se hicieron. */
function aplicar(paso,adelante){
  if(paso.tinta==='pinta'){
    if(adelante)croquis.appendChild(paso.raya); else if(paso.raya.parentNode)croquis.removeChild(paso.raya);
    api.refrescar(); return;
  }
  if(paso.tinta==='borra'){
    if(adelante){ paso.antes=paso.raya.nextSibling; if(paso.raya.parentNode)croquis.removeChild(paso.raya); }
    else croquis.insertBefore(paso.raya,paso.antes&&paso.antes.parentNode===croquis?paso.antes:null);
    api.refrescar(); return;
  }
  var tocadas=[], i;
  var cs=adelante?paso.celdas:paso.celdas.slice().reverse();
  for(i=0;i<cs.length;i++){ var k=cs[i][0]; calc.poner(Math.floor(k/1024),k%1024,adelante?cs[i][2]:cs[i][1]); tocadas.push([Math.floor(k/1024),k%1024]); }
  for(i=0;i<paso.estilos.length;i++){ var e=paso.estilos[i], x=adelante?e[2]:e[1]; if(x)estilos[e[0]]=x; else delete estilos[e[0]]; tocadas.push([Math.floor(e[0]/1024),e[0]%1024]); }
  repintar(tocadas);
}
function deshacer(){ if(editando)cancelar(); var p=hecho.pop(); if(!p)return; aplicar(p,false); rehecho.push(p); api.refrescar(); }
function rehacer(){ if(editando)cancelar(); var p=rehecho.pop(); if(!p)return; aplicar(p,true); hecho.push(p); api.refrescar(); }

// ---- La tinta: lápiz, resaltador y borrador encima de las celdas ----
function r3(x){return Math.round(x*1000)/1000;}
/**
 * La capa mide lo que mide la tabla y va en unidades de zoom 1 **contadas desde A1**, aunque A1
 * no se vea: así lo rayado se queda en su celda al ampliar y al crecer el marco por arriba.
 */
function medirTinta(){
  if(!svg||!origen)return;
  var td=celdaTd(B.f0,B.c0), antes=0, c;
  for(c=0;c<B.c0;c++)antes+=anchos[c]||ANCHO;
  O={x:td?td.offsetLeft-antes:CAB, y:td?td.offsetTop-B.f0*td.offsetHeight:26};
  origen.setAttribute('transform','translate('+r3(O.x)+' '+r3(O.y)+')');
}
/** Del sitio del puntero en la ventana al punto del mundo, contado desde A1. */
function donde(e){ var r=caja.getBoundingClientRect(); return {x:r3((e.clientX-r.left-V.x)/V.z-O.x), y:r3((e.clientY-r.top-V.y)/V.z-O.y)}; }
function anadirPunto(t,p){
  var pts=t.puntos, n=pts.length;
  pts.push(p);
  if(n===0){t.abierto='M '+p.x+' '+p.y;return;}
  var a=pts[n-1];
  t.abierto+=(n===1?' L ':' Q '+a.x+' '+a.y+' ')+r3((a.x+p.x)/2)+' '+r3((a.y+p.y)/2);
}
function pintarTrazo(t){
  var u=t.puntos[t.puntos.length-1];
  t.setAttribute('d',t.abierto+(t.puntos.length>1?' L '+u.x+' '+u.y:''));
}
function muestras(e){ var lote=(e.getCoalescedEvents&&e.getCoalescedEvents())||[]; return lote.length?lote:[e]; }
/** Los puntos de una raya: los que se trazaron, o los que se leen de su camino si vino guardada. */
function puntosDe(r){
  if(r.puntos&&r.puntos.length)return r.puntos;
  var n=(r.getAttribute('d')||'').match(/-?[0-9.]+/g)||[], pts=[];
  for(var i=0;i+1<n.length;i+=2)pts.push({x:+n[i],y:+n[i+1]});
  r.puntos=pts; return pts;
}
function borrarEn(p){
  var rayas=[].slice.call(croquis.children);
  for(var i=0;i<rayas.length;i++){
    var r=rayas[i], pts=puntosDe(r), gordo=(+r.getAttribute('stroke-width')||2)/2+10/V.z;
    for(var j=0;j<pts.length;j++){
      if(Math.hypot(pts[j].x-p.x,pts[j].y-p.y)<=gordo){
        anotar({tinta:'borra',raya:r,antes:r.nextSibling});
        croquis.removeChild(r); break;
      }
    }
  }
}
// La raya la empieza, sigue y suelta el manejador de la caja: así se raya también fuera de la tabla.
function empezarTrazo(e){
  var p=donde(e);
  if(modo==='goma'){ borrarEn(p); trazo=null; return; }
  var marca=modo==='marcador';
  trazo=document.createElementNS(NS,'path');
  trazo.setAttribute('fill','none');
  trazo.setAttribute('stroke',api.color());
  // El grosor se mide en la pantalla del momento: una raya fina de lejos sigue siendo fina.
  trazo.setAttribute('stroke-width',r3((marca?api.grosor()*3:api.grosor())/V.z));
  trazo.setAttribute('stroke-linecap','round');
  trazo.setAttribute('stroke-linejoin','round');
  if(marca){ trazo.setAttribute('stroke-opacity','0.45'); trazo.setAttribute('class','marca'); }
  trazo.puntos=[]; anadirPunto(trazo,p); pintarTrazo(trazo);
  croquis.appendChild(trazo);
}
function seguirTrazo(e){
  if(modo==='goma'){ borrarEn(donde(e)); return; }
  if(!trazo)return;
  var lote=muestras(e);
  for(var i=0;i<lote.length;i++)anadirPunto(trazo,donde(lote[i]));
  pintarTrazo(trazo);
}
function soltarTrazo(){
  if(!trazo)return;
  if(trazo.puntos.length<2){ if(trazo.parentNode)croquis.removeChild(trazo); }
  else anotar({tinta:'pinta',raya:trazo});
  trazo=null;
}
function rayas(){
  var s='';
  if(!croquis)return s;
  [].forEach.call(croquis.children,function(r){
    s+='<'+r.localName;
    for(var i=0;i<r.attributes.length;i++){
      var a=r.attributes[i];
      s+=' '+a.name+'="'+String(a.value).replace(/&/g,'&amp;').replace(/"/g,'&quot;')+'"';
    }
    s+='/>\n';
  });
  return s;
}

// ---- Editar en la barra de fórmula ----
function editar(inicial){
  if(!editable)return;
  if(!puedeEditar(act.f,act.c)){ api.decir('Esta celda está protegida'); return; }
  editando=true; original=calc.crudo(act.f,act.c); refPuesta=null;
  fx.value=inicial!==undefined?inicial:original;
  fx.focus();
  try{ fx.setSelectionRange(fx.value.length,fx.value.length); }catch(e){}
  pintarMarcas();
}
function confirmar(){
  if(!editando)return;
  editando=false; refPuesta=null;
  var t=fx.value;
  if(t!==original)escribir([[act.f,act.c,t]]); else pintarCelda(act.f,act.c);
  pintarMarcas();
}
function cancelar(){ editando=false; refPuesta=null; fx.value=original; pintarCelda(act.f,act.c); pintarMarcas(); caja.focus({preventScroll:true}); }
function vivo(){ var td=celdaTd(act.f,act.c); if(td&&editando)td.textContent=fx.value; pintarMarcas(); }
fx.addEventListener('focus',function(){ if(!editando&&puedeEditar(act.f,act.c)){ editando=true; original=calc.crudo(act.f,act.c); refPuesta=null; pintarMarcas(); } });
fx.addEventListener('input',vivo);
fx.addEventListener('click',pintarMarcas);
fx.addEventListener('keyup',pintarMarcas);
fx.addEventListener('keydown',function(e){
  if(e.key==='Enter'){ e.preventDefault(); confirmar(); seleccionar(act.f+(e.shiftKey?-1:1),act.c,false); caja.focus({preventScroll:true}); }
  else if(e.key==='Tab'){ e.preventDefault(); confirmar(); seleccionar(act.f,act.c+(e.shiftKey?-1:1),false); caja.focus({preventScroll:true}); }
  else if(e.key==='Escape'){ e.preventDefault(); cancelar(); }
});
fx.addEventListener('blur',function(){
  setTimeout(function(){ if(editando&&document.activeElement!==fx&&!mantener)confirmar(); mantener=false; },0);
});
/** Con una fórmula a medias, tocar una celda **escribe su dirección**, como en Excel. */
function puedePonerRef(){
  if(!editando||fx.value[0]!=='=')return false;
  if(refPuesta&&refPuesta.valor===fx.value)return true;
  var pos=fx.selectionStart==null?fx.value.length:fx.selectionStart;
  return /[=+\-*\/^&(;,:<>]\s*$/.test(fx.value.slice(0,pos));
}
function ponerRef(f,c,extender){
  var v=fx.value, texto;
  if(extender&&refPuesta&&refPuesta.valor===v){
    texto=Calculo.nombre(refPuesta.f,refPuesta.c)+':'+Calculo.nombre(f,c);
    v=v.slice(0,refPuesta.d)+texto+v.slice(refPuesta.h);
    refPuesta.h=refPuesta.d+texto.length;
  } else {
    var pos=fx.selectionStart==null?v.length:fx.selectionStart, fin=fx.selectionEnd==null?pos:fx.selectionEnd;
    if(refPuesta&&refPuesta.valor===v){ pos=refPuesta.d; fin=refPuesta.h; }
    texto=Calculo.nombre(f,c);
    v=v.slice(0,pos)+texto+v.slice(fin);
    refPuesta={d:pos,h:pos+texto.length,f:f,c:c};
  }
  fx.value=v; refPuesta.valor=v; mantener=true;
  fx.focus();
  try{ fx.setSelectionRange(refPuesta.h,refPuesta.h); }catch(e){}
  vivo();
}

// ---- Dedo, ratón y cabeceras ----
function celdaDe(el){ var td=el&&el.closest?el.closest('td'):null; return td&&td.dataset.f!=null?[+td.dataset.f,+td.dataset.c]:null; }
function aplicarVista(){ mundo.style.transform='translate('+r3(V.x)+'px,'+r3(V.y)+'px) scale('+r3(V.z)+')'; }
/** Amplía alrededor de un punto de la ventana: lo que hay debajo no se mueve. */
function zoomEn(cx,cy,factor){
  var r=caja.getBoundingClientRect(), z=Math.max(0.2,Math.min(4,V.z*factor));
  var wx=(cx-r.left-V.x)/V.z, wy=(cy-r.top-V.y)/V.z;
  V.z=z; V.x=cx-r.left-wx*z; V.y=cy-r.top-wy*z; aplicarVista();
}
/** Centra la tabla: entera si cabe, y si no, a un tamaño que todavía se lea. */
function encajar(){
  var W=caja.clientWidth, H=caja.clientHeight, w=tabla.offsetWidth, h=tabla.offsetHeight;
  if(!W||!H||!w){ V={x:16,y:16,z:1}; aplicarVista(); return; }
  var z=Math.max(0.45,Math.min(1,(W-32)/w,(H-110)/h));
  V.z=z; V.x=w*z>W-32?16:(W-w*z)/2; V.y=Math.max(16,(H-90-h*z)/2);
  aplicarVista();
}
function losPunteros(){ return Array.from(punteros.values()); }
/**
 * **Un solo manejador para toda la caja**, dentro y fuera de la tabla:
 * - con lápiz o resaltador en la mano, se raya donde se apoye (si ya se usó un lápiz de verdad,
 *   el dedo pasea, como en el lienzo);
 * - con la mano: el ratón elige celdas arrastrando sobre ellas y pasea arrastrando fuera; el dedo
 *   pasea, toca para elegir, dos toques editan y mantener elige un rango;
 * - dos dedos amplían y pasean; la rueda pasea y con Ctrl amplía.
 */
caja.addEventListener('mousedown',function(e){ if(editando&&puedePonerRef())e.preventDefault(); });
caja.addEventListener('pointerdown',function(e){
  punteros.set(e.pointerId,{x:e.clientX,y:e.clientY});
  if(e.pointerType==='pen')hayLapiz=true;
  if(punteros.size===2&&e.pointerType!=='mouse'){
    if(gesto&&gesto.timer)clearTimeout(gesto.timer);
    if(trazo){ if(trazo.parentNode)croquis.removeChild(trazo); trazo=null; }
    mantener=false;
    var ps=losPunteros();
    gesto={tipo:'pellizco',d0:Math.hypot(ps[0].x-ps[1].x,ps[0].y-ps[1].y)||1,mx:(ps[0].x+ps[1].x)/2,my:(ps[0].y+ps[1].y)/2,v:{x:V.x,y:V.y,z:V.z}};
    return;
  }
  if(punteros.size>1)return;
  try{ caja.setPointerCapture(e.pointerId); }catch(x){}
  if(modo!=='mano'&&svg&&croquis&&!(e.pointerType==='touch'&&hayLapiz)&&!(e.pointerType==='mouse'&&e.button!==0)){
    e.preventDefault(); empezarTrazo(e); gesto={tipo:'tinta'}; return;
  }
  var p=celdaDe(e.target), th=e.target.closest?e.target.closest('th'):null;
  if(e.pointerType==='mouse'&&e.button===0&&p&&modo==='mano'){
    if(puedePonerRef()){ e.preventDefault(); ponerRef(p[0],p[1],e.shiftKey); gesto={tipo:'refs'}; return; }
    if(editando)confirmar();
    seleccionar(p[0],p[1],e.shiftKey); gesto={tipo:'celdas'};
    caja.focus({preventScroll:true}); e.preventDefault();
    return;
  }
  var g={tipo:'pendiente',x0:e.clientX,y0:e.clientY,vx:V.x,vy:V.y,celda:modo==='mano'?p:null,th:modo==='mano'?th:null,ref:!!p&&modo==='mano'&&puedePonerRef()};
  gesto=g;
  if(g.ref)mantener=true;
  if(g.celda&&e.pointerType!=='mouse'){
    g.timer=setTimeout(function(){
      if(gesto!==g||g.tipo!=='pendiente')return;
      if(g.ref){ ponerRef(p[0],p[1],false); g.tipo='refs'; }
      else { if(editando)confirmar(); seleccionar(p[0],p[1],false,true); g.tipo='celdas'; api.decir('Arrastra para elegir varias celdas'); }
      if(navigator.vibrate)try{navigator.vibrate(12);}catch(x){}
    },430);
  }
});
caja.addEventListener('pointermove',function(e){
  if(punteros.has(e.pointerId))punteros.set(e.pointerId,{x:e.clientX,y:e.clientY});
  var g=gesto; if(!g)return;
  if(g.tipo==='pellizco'){
    if(punteros.size<2)return;
    var ps=losPunteros(), d=Math.hypot(ps[0].x-ps[1].x,ps[0].y-ps[1].y)||1;
    var mx=(ps[0].x+ps[1].x)/2, my=(ps[0].y+ps[1].y)/2, r=caja.getBoundingClientRect(), v=g.v;
    var z=Math.max(0.2,Math.min(4,v.z*d/g.d0)), wx=(g.mx-r.left-v.x)/v.z, wy=(g.my-r.top-v.y)/v.z;
    V.z=z; V.x=mx-r.left-wx*z; V.y=my-r.top-wy*z; aplicarVista();
    return;
  }
  if(g.tipo==='tinta'){ seguirTrazo(e); return; }
  if(g.tipo==='pendiente'){
    if(Math.hypot(e.clientX-g.x0,e.clientY-g.y0)<=6)return;
    if(g.timer)clearTimeout(g.timer);
    mantener=false; g.tipo='pan'; caja.classList.add('agarrada');
  }
  if(g.tipo==='pan'){ V.x=g.vx+(e.clientX-g.x0); V.y=g.vy+(e.clientY-g.y0); aplicarVista(); return; }
  var q=celdaDe(document.elementFromPoint(e.clientX,e.clientY)); if(!q)return;
  if(g.tipo==='refs')ponerRef(q[0],q[1],true); else if(g.tipo==='celdas')seleccionar(q[0],q[1],true,true);
});
function soltarPuntero(e){
  punteros.delete(e.pointerId);
  var g=gesto; if(!g)return;
  if(g.tipo==='pellizco'){ if(punteros.size===0)gesto=null; return; }
  if(g.timer)clearTimeout(g.timer);
  if(g.tipo==='tinta')soltarTrazo();
  else if(g.tipo==='pendiente'&&e.type==='pointerup'){
    if(g.celda){
      var t=g.celda, ahora=Date.now();
      if(g.ref)ponerRef(t[0],t[1],false);
      else if(ultimoToque&&ultimoToque.f===t[0]&&ultimoToque.c===t[1]&&ahora-ultimoToque.t<380){ ultimoToque=null; seleccionar(t[0],t[1],false); editar(); }
      else { if(editando)confirmar(); ultimoToque={f:t[0],c:t[1],t:ahora}; seleccionar(t[0],t[1],false,true); }
    } else if(g.th&&!g.th.classList.contains('esq')){
      if(editando)confirmar();
      if(g.th.dataset.c!=null){ var c=+g.th.dataset.c; ancla={f:B.f0,c:c}; seleccionar(B.f1,c,true,true); }
      else if(g.th.dataset.f!=null){ var f=+g.th.dataset.f; ancla={f:f,c:B.c0}; seleccionar(f,B.c1,true,true); }
    }
  } else if(g.tipo==='pendiente')mantener=false;
  caja.classList.remove('agarrada');
  gesto=null;
}
caja.addEventListener('pointerup',soltarPuntero);
caja.addEventListener('pointercancel',soltarPuntero);
caja.addEventListener('wheel',function(e){
  e.preventDefault();
  if(e.ctrlKey||e.metaKey)zoomEn(e.clientX,e.clientY,Math.exp(-e.deltaY*0.0015));
  else { V.x-=e.deltaX; V.y-=e.deltaY; aplicarVista(); }
},{passive:false});
tabla.addEventListener('dblclick',function(e){ var p=celdaDe(e.target); if(p&&modo==='mano'){ seleccionar(p[0],p[1],false); editar(); } });

// ---- Teclado ----
function mover(df,dc,ext){ seleccionar(act.f+df,act.c+dc,ext); }
function tecla(e){
  if(!activo)return false;
  if(e.target===fx)return true;
  var ctrl=e.ctrlKey||e.metaKey, k=e.key||'', l=k.toLowerCase();
  if(ctrl){
    if(l==='z'&&!e.shiftKey){ e.preventDefault(); deshacer(); return true; }
    if(l==='y'||(l==='z'&&e.shiftKey)){ e.preventDefault(); rehacer(); return true; }
    if(l==='c'||l==='x'||l==='v')return true;
    if(l==='a'){ e.preventDefault(); ancla={f:B.f0,c:B.c0}; seleccionar(B.f1,B.c1,true,true); return true; }
    if(editable&&l==='b'){ e.preventDefault(); accion('t-negrita'); return true; }
    if(editable&&l==='d'){ e.preventDefault(); rellenar(true); return true; }
    return false;
  }
  if(e.altKey)return false;
  switch(k){
    case 'ArrowUp': e.preventDefault(); mover(-1,0,e.shiftKey); return true;
    case 'ArrowDown': e.preventDefault(); mover(1,0,e.shiftKey); return true;
    case 'ArrowLeft': e.preventDefault(); mover(0,-1,e.shiftKey); return true;
    case 'ArrowRight': e.preventDefault(); mover(0,1,e.shiftKey); return true;
    case 'Tab': e.preventDefault(); mover(0,e.shiftKey?-1:1,false); return true;
    case 'Home': e.preventDefault(); seleccionar(act.f,B.c0,e.shiftKey); return true;
    case 'PageDown': case 'PageUp': return false;
  }
  // En solo lectura las letras vuelven a ser atajos del armazón (P lápiz, E borrador…).
  if(!editable)return false;
  if(!puedeEditar(act.f,act.c)&&(k==='Enter'||k==='F2'||(k.length===1&&modo==='mano'))){
    e.preventDefault(); api.decir('Esta celda está protegida'); return true;
  }
  switch(k){
    case 'Enter': e.preventDefault(); if(e.shiftKey)mover(-1,0,false); else editar(); return true;
    case 'F2': e.preventDefault(); editar(); return true;
    case 'Delete': case 'Backspace': e.preventDefault(); borrar(); return true;
  }
  if(k.length===1&&modo==='mano'){ e.preventDefault(); editar(k); return true; }
  return false;
}
function borrar(){
  var r=rango(), cambios=[];
  calc.cada(function(f,c){ if(enRango(r,f,c))cambios.push([f,c,'']); });
  escribir(cambios);
}
function rellenar(abajo){
  var r=rango(), cambios=[], f, c, raw;
  if(abajo){ for(c=r.c;c<=r.c2;c++){ raw=calc.crudo(r.f,c); for(f=r.f+1;f<=r.f2;f++)cambios.push([f,c,Calculo.esFormula(raw)?Calculo.desplazar(raw,f-r.f,0):raw]); } }
  else { for(f=r.f;f<=r.f2;f++){ raw=calc.crudo(f,r.c); for(c=r.c+1;c<=r.c2;c++)cambios.push([f,c,Calculo.esFormula(raw)?Calculo.desplazar(raw,0,c-r.c):raw]); } }
  escribir(cambios);
}

// ---- Portapapeles ----
function escaparHtml(s){ return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;'); }
function copiaDe(r){
  var valores=[], crudos=[], html='<meta charset="utf-8"><'+'table>', f, c;
  for(f=r.f;f<=r.f2;f++){
    var fv=[], fc=[]; html+='<tr>';
    for(c=r.c;c<=r.c2;c++){
      var es=estilos[f*1024+c], t=Calculo.visto(calc,es,f,c,MONEDA), raw=calc.crudo(f,c);
      fv.push(t); fc.push(raw);
      html+='<td'+(Calculo.esFormula(raw)?' data-sheets-formula="'+escaparHtml(Calculo.aR1c1(raw,f,c))+'"':'')+(es&&es.n?' style="font-weight:bold"':'')+'>'+escaparHtml(t).replace(/\n/g,'<br>')+'</td>';
    }
    valores.push(fv); crudos.push(fc); html+='</tr>';
  }
  html+='</'+'table>';
  return {tsv:Calculo.aTsv(valores), html:html, crudos:crudos, f:r.f, c:r.c};
}
document.addEventListener('copy',function(e){
  if(!activo||document.activeElement===fx||!e.clipboardData)return;
  var r=rango(), k=copiaDe(r);
  e.clipboardData.setData('text/plain',k.tsv); e.clipboardData.setData('text/html',k.html); e.preventDefault();
  portapapeles=k;
  api.decir('Copiado: '+(r.f2-r.f+1)+' × '+(r.c2-r.c+1));
});
document.addEventListener('cut',function(e){
  if(!activo||!editable||document.activeElement===fx||!e.clipboardData)return;
  var k=copiaDe(rango());
  e.clipboardData.setData('text/plain',k.tsv); e.clipboardData.setData('text/html',k.html); e.preventDefault();
  portapapeles=k; borrar(); api.decir('Cortado');
});
/** El número exacto que trae la hoja de origen, si lo que se ve es ese número redondeado. */
function exacto(td,txt){
  if(txt.indexOf('%')>=0||Calculo.numero(txt)===null)return null;
  var xn=td.getAttribute('x:num');
  if(xn&&Calculo.numero(xn)!==null)return xn;
  var sv=td.getAttribute('data-sheets-value');
  if(sv){ try{ var o=JSON.parse(sv); if(o&&o['1']===3&&typeof o['3']==='number')return String(o['3']); }catch(e){} }
  return null;
}
function deHtml(html){
  var doc; try{ doc=new DOMParser().parseFromString(html,'text/html'); }catch(e){ return null; }
  var t=doc.querySelector('table'); if(!t)return null;
  var filas=[];
  [].forEach.call(t.querySelectorAll('tr'),function(tr){
    var fila=[];
    [].forEach.call(tr.children,function(td){
      if(td.tagName!=='TD'&&td.tagName!=='TH')return;
      var fs=td.getAttribute('data-sheets-formula'), fe=td.getAttribute('x:fmla');
      var st=(td.getAttribute('style')||'').toLowerCase();
      var neg=/font-weight:\s*(bold|[6-9]00)/.test(st)||!!td.querySelector('b,strong')||/font-weight:\s*(bold|[6-9]00)/i.test(td.innerHTML);
      var h=td.innerHTML.replace(/<br\b[^>]*>/gi,'\u0000').replace(/<[^>]+>/g,'').replace(/[ \t\r\n]+/g,' ');
      var ta=document.createElement('textarea'); ta.innerHTML=h;
      var txt=ta.value.split('\u0000').map(function(s){return s.trim();}).join('\n').trim();
      if(fs&&fs[0]==='=')fila.push({texto:fs,r1c1:true,negrita:neg});
      else if(fe&&fe[0]==='=')fila.push({texto:fe,negrita:neg});
      else if(txt[0]==='=')fila.push({texto:"'"+txt,negrita:neg});
      else fila.push({texto:exacto(td,txt)||txt,negrita:neg});
      var span=parseInt(td.getAttribute('colspan')||'1',10)||1;
      for(var k=1;k<Math.min(span,51);k++)fila.push({texto:''});
    });
    filas.push(fila);
  });
  return filas;
}
function pegarBloque(html,texto){
  if(!editable)return false;
  var bloque=null, origenCopia=null;
  if(portapapeles&&texto&&texto.replace(/\r?\n$/,'')===portapapeles.tsv){
    bloque=portapapeles.crudos.map(function(fila){ return fila.map(function(x){ return {texto:x}; }); });
    origenCopia=[portapapeles.f,portapapeles.c];
  }
  if(!bloque&&html)bloque=deHtml(html);
  if(!bloque&&texto)bloque=Calculo.deTsv(texto).map(function(fila){ return fila.map(function(x){ return {texto:x}; }); });
  if(!bloque||!bloque.length)return false;
  if(editando){ editando=false; refPuesta=null; pintarMarcas(); }
  var cambios=[], negritas=[], ff=act.f, cc=act.c, ultF=ff, ultC=cc;
  for(var i=0;i<bloque.length;i++)for(var j=0;j<bloque[i].length;j++){
    var p=bloque[i][j], f=ff+i, c=cc+j, t=p.texto;
    if(f>=100000||c>=702)continue;
    if(p.r1c1)t=Calculo.r1c1(t,f,c);
    else if(origenCopia&&Calculo.esFormula(t))t=Calculo.desplazar(t,f-(origenCopia[0]+i),c-(origenCopia[1]+j));
    cambios.push([f,c,t]);
    if(p.negrita){ var k=f*1024+c, a=estilos[k]; if(!(a&&a.n)){ var n={}; for(var q in a||{})n[q]=a[q]; n.n=true; negritas.push([k,n]); } }
    ultF=Math.max(ultF,f); ultC=Math.max(ultC,c);
  }
  escribir(cambios,negritas);
  ancla={f:ff,c:cc}; seleccionar(ultF,ultC,true,true);
  api.decir('Pegado: '+bloque.length+' × '+(ultC-cc+1));
  return true;
}
document.addEventListener('paste',function(e){
  if(!activo||!editable||!e.clipboardData)return;
  var html=e.clipboardData.getData('text/html')||'', texto=e.clipboardData.getData('text/plain')||'';
  var varias=/[\t\n]/.test(texto.replace(/\r?\n$/,''))||/<table/i.test(html);
  if(document.activeElement===fx&&!varias)return;
  e.preventDefault();
  pegarBloque(html,texto);
});
function copiarConBoton(){
  var ok=false;
  try{ ok=document.execCommand('copy'); }catch(e){}
  if(!ok&&navigator.clipboard&&navigator.clipboard.writeText){
    var k=copiaDe(rango()); portapapeles=k;
    navigator.clipboard.writeText(k.tsv).then(function(){ api.decir('Copiado'); },function(){ api.decir('No se pudo copiar'); });
  }
}
function pegarConBoton(){
  var c=navigator.clipboard;
  if(c&&c.read){
    c.read().then(function(items){
      var html=null, texto=null, esperas=[];
      items.forEach(function(it){
        if(it.types.indexOf('text/html')>=0)esperas.push(it.getType('text/html').then(function(b){return b.text();}).then(function(t){html=t;}));
        if(it.types.indexOf('text/plain')>=0)esperas.push(it.getType('text/plain').then(function(b){return b.text();}).then(function(t){texto=t;}));
      });
      return Promise.all(esperas).then(function(){ if(!pegarBloque(html,texto))api.decir('No hay nada que pegar'); });
    }).catch(function(){ leerTexto(); });
  } else leerTexto();
  function leerTexto(){
    if(c&&c.readText) c.readText().then(function(t){ if(!pegarBloque(null,t))api.decir('No hay nada que pegar'); },function(){ aviso(); });
    else aviso();
  }
  function aviso(){ api.decir('Mantén pulsada la barra de fórmula y elige Pegar'); fx.focus(); }
}
/** El CSV lleva solo lo escrito: el rectángulo que ocupa, sin márgenes. */
function bajarCsv(){
  var k=calc.caja()||[0,0,0,0], lineas=[];
  for(var f=k[0];f<=k[2];f++){
    var fila=[];
    for(var c=k[1];c<=k[3];c++){ var t=calc.texto(f,c); fila.push(/[",\n\r;]/.test(t)?'"'+t.replace(/"/g,'""')+'"':t); }
    lineas.push(fila.join(','));
  }
  var a=document.createElement('a');
  a.href=URL.createObjectURL(new Blob(['\ufeff'+lineas.join('\r\n')],{type:'text/csv'}));
  a.download=((document.body.dataset.nombre||nombreTabla||'tabla').replace(/[\\/:*?"<>|]+/g,'-').trim()||'tabla')+'.csv';
  document.body.appendChild(a); a.click(); document.body.removeChild(a);
  setTimeout(function(){ URL.revokeObjectURL(a.href); },4000);
  api.decir('CSV bajado: '+a.download);
}
function accion(n){
  var r=rango(), cambios=[], f, c;
  switch(n){
    case 't-copiar': copiarConBoton(); break;
    case 't-pegar': pegarConBoton(); break;
    case 't-csv': bajarCsv(); break;
    case 't-borrar': borrar(); break;
    case 't-negrita':
      if(!editable)break;
      var todas=true;
      for(f=r.f;f<=r.f2&&todas;f++)for(c=r.c;c<=r.c2;c++){ var e=estilos[f*1024+c]; if(!(e&&e.n)){todas=false;break;} }
      for(f=r.f;f<=Math.min(r.f2,r.f+5000);f++)for(c=r.c;c<=r.c2;c++){
        var k=f*1024+c, a=estilos[k], nuevo={}; for(var q in a||{})nuevo[q]=a[q];
        if(todas)delete nuevo.n; else nuevo.n=true;
        cambios.push([k,Object.keys(nuevo).length?nuevo:null]);
      }
      escribir([],cambios); break;
    case 't-suma':
      if(!editable)break;
      var fs=act.f-1; while(fs>=0&&calc.valor(fs,act.c).t==='n')fs--;
      editar(fs<act.f-1?'=SUMA('+Calculo.nombre(fs+1,act.c)+':'+Calculo.nombre(act.f-1,act.c)+')':'=SUMA()'); break;
  }
}

// ---- Guardar ----
function json(){
  var celdas={}, es={}, an={}, k;
  calc.cada(function(f,c,v){ celdas[Calculo.nombre(f,c)]=v; });
  for(k in estilos)es[Calculo.nombre(Math.floor(k/1024),k%1024)]=estilos[k];
  for(k in anchos)an[Calculo.letras(+k)]=anchos[k];
  var o={}; if(nombreTabla)o.nombre=nombreTabla; o.celdas=celdas; if(protegida)o.protegida=true;
  if(Object.keys(an).length)o.anchos=an;
  if(Object.keys(es).length)o.estilos=es;
  return JSON.stringify(o).replace(/</g,'\\u003c');
}
/** La tabla ya calculada para el archivo guardado: el mismo marco que se ve. */
function estatica(){
  var m=limites(), f, c, f1=Math.min(m.f1,m.f0+1999), c1=Math.min(m.c1,m.c0+199);
  var s='<'+'table class="'+'calc"><thead><tr><th class="esq"></th>';
  for(c=m.c0;c<=c1;c++)s+='<th style="width:'+(anchos[c]||ANCHO)+'px">'+Calculo.letras(c)+'</th>';
  s+='</tr></thead><tbody>';
  for(f=m.f0;f<=f1;f++){
    s+='<tr><th>'+(f+1)+'</th>';
    for(c=m.c0;c<=c1;c++){
      var raw=calc.crudo(f,c), v=raw?calc.valor(f,c):null, es=estilos[f*1024+c];
      var al=es&&es.a?es.a:(v?(v.t==='n'?'d':(v.t==='b'||v.t==='e')?'c':'i'):'i'), cl=[];
      if(al==='d')cl.push('d'); else if(al==='c')cl.push('c');
      if(es&&es.n)cl.push('n'); if(es&&es.k)cl.push('k'); if(es&&es.s)cl.push('s'); if(es&&es.t)cl.push('t'); if(es&&es.w)cl.push('w');
      if(v&&v.t==='e')cl.push('err'); if(Calculo.esFormula(raw))cl.push('f'); if(protegida&&es&&es.e)cl.push('ed');
      var st=[]; if(es&&es.f&&/^#[0-9a-fA-F]{6}$/.test(es.f))st.push('background:'+es.f); if(es&&es.c&&/^#[0-9a-fA-F]{6}$/.test(es.c))st.push('color:'+es.c);
      s+='<td'+(cl.length?' class="'+cl.join(' ')+'"':'')+(st.length?' style="'+st.join(';')+'"':'')+'>'+String(raw?Calculo.visto(calc,es,f,c,MONEDA):'').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;')+'</td>';
    }
    s+='</tr>';
  }
  return s+'</tbody></'+'table>';
}
construir();
aplicarVista();
seleccionar(0,0,false,true);
return {
  tipo:'tabla',
  herramientas:['mano','lapiz','marcador','goma'].filter(function(h){
    return (document.body.dataset.herramientas||'mano lapiz marcador goma').split(' ').indexOf(h)>=0; }),
  activar:function(){
    activo=true; caja.focus({preventScroll:true}); medirTinta();
    // La primera vez que se mira, centrada: antes la hoja estaba oculta y no tenía medidas.
    if(!centrada){ centrada=true; encajar(); }
    mostrarBarra(); estadoRango();
  },
  desactivar:function(){ if(editando)confirmar(); activo=false; },
  medir:function(){ medirTinta(); pintarMarcas(); },
  modo:function(m){ modo=m; if(m!=='mano'&&editando)confirmar(); },
  pintando:function(){ return modo!=='mano'; },
  encajar:encajar,
  zoom:function(f){ var r=caja.getBoundingClientRect(); zoomEn(r.left+caja.clientWidth/2,r.top+caja.clientHeight/2,1/f); },
  deshacer:deshacer, rehacer:rehacer,
  puedeDeshacer:function(){ return hecho.length>0; },
  puedeRehacer:function(){ return rehecho.length>0; },
  tecla:tecla, accion:accion, json:json, estatica:estatica, rayas:rayas
};
}
"""
}
