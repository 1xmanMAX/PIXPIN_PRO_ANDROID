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
                    val texto = v.calc.texto(f, c)
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
                        if (valor is Valor.Err) add("err")
                        if (Calculadora.esFormula(v.calc.crudo(f, c))) add("f")
                        if (t.protegida && e?.e == true) add("ed")
                    }
                    append("<td")
                    if (clases.isNotEmpty()) append(" class=\"").append(clases.joinToString(" ")).append('"')
                    e?.f?.takeIf { COLOR.matches(it) }?.let { append(" style=\"background:").append(it).append('"') }
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
var MAX_COLS=702, MAX_FILAS=100000, PROFUNDIDAD=400, DIAS=25569;
var E_DIV0='#¡DIV/0!', E_VALOR='#¡VALOR!', E_REF='#¡REF!', E_NOMBRE='#¿NOMBRE?', E_ND='#N/D', E_NUM='#¡NUM!', E_CIRC='#¡CIRC!';
var VACIO={t:'v'}, HUECO={k:'hueco'}, HONDO={hondo:1};
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
function serial(a,m,d){ var x=new Date(0); x.setUTCFullYear(a,m-1,d); return Math.round(x.getTime()/86400000)+DIAS; }
function partes(s){ var x=new Date((Math.floor(s)-DIAS)*86400000); return [x.getUTCFullYear(),x.getUTCMonth()+1,x.getUTCDate()]; }
function dos(n){return n<10?'0'+n:''+n;}
function textoDeFecha(s){
  if(!isFinite(s)||s< -657434||s>2958465)return E_NUM;
  var p=partes(s), base=dos(p[2])+'/'+dos(p[1])+'/'+p[0], min=Math.round((s-Math.floor(s))*1440);
  if(min<=0||min>=1440)return base;
  return base+' '+dos(Math.floor(min/60))+':'+dos(min%60);
}
function ceros(n){var s='';while(n-->0)s+='0';return s;}
function general(d){
  if(!isFinite(d))return E_NUM;
  if(d===0)return '0';
  var a=Math.abs(d), s=d<0?'-':'';
  if(a<1e15&&a===Math.floor(a))return s+String(a);
  var x,m,exp;
  if(a>=1e15||a<1e-9){
    x=a.toExponential(5).split('e'); m=x[0]; exp=+x[1];
    if(m.indexOf('.')>=0) m=m.replace(/0+$/,'').replace(/\.$/,'');
    var e=Math.abs(exp);
    return s+m+'E'+(exp<0?'-':'+')+(e<10?'0'+e:''+e);
  }
  x=a.toExponential(9).split('e'); var dig=x[0].replace('.',''), r; exp=+x[1];
  if(exp>=0){ r=dig.length<=exp+1?dig+ceros(exp+1-dig.length):dig.slice(0,exp+1)+'.'+dig.slice(exp+1); }
  else r='0.'+ceros(-exp-1)+dig;
  if(r.indexOf('.')>=0) r=r.replace(/0+$/,'').replace(/\.$/,'');
  return s+r;
}
function redondear(x,dec,modo){
  if(!isFinite(x))return x;
  var n=Math.max(-15,Math.min(15,dec)), f=Math.pow(10,Math.abs(n));
  var esc=n>=0?Math.abs(x)*f:Math.abs(x)/f;
  var limpio=esc===0?0:parseFloat(esc.toPrecision(15));
  var ent=modo===1?Math.ceil(limpio):modo===-1?Math.floor(limpio):Math.floor(limpio+0.5);
  var r=n>=0?ent/f:ent*f;
  return x<0?-r:r;
}
function conFormato(v,formato){
  var sin=formato.replace(/"[^"]*"/g,'').toLowerCase(), i;
  if(sin.indexOf('dd')>=0||sin.indexOf('yy')>=0||sin.indexOf('aa')>=0){
    var p=partes(v), s=formato;
    s=s.replace(/yyyy|aaaa/gi,String(p[0])); s=s.replace(/yy|aa/gi,dos(p[0]%100));
    s=s.replace(/mm/gi,dos(p[1])); s=s.replace(/dd/gi,dos(p[2]));
    return s;
  }
  var primero=-1; for(i=0;i<formato.length;i++){ if(formato[i]==='0'||formato[i]==='#'){primero=i;break;} }
  if(primero<0) return formato.replace(/"/g,'');
  var ultimo=-1; for(i=formato.length-1;i>=0;i--){ if('0#%.,'.indexOf(formato[i])>=0){ultimo=i;break;} }
  var delante=formato.slice(0,primero).replace(/"/g,''), mascara=formato.slice(primero,ultimo+1), detras=formato.slice(ultimo+1).replace(/"/g,'');
  var pc=mascara.indexOf('%')>=0, punto=mascara.indexOf('.'), dec=0;
  if(punto>=0){ var rr=mascara.slice(punto+1); for(i=0;i<rr.length;i++) if(rr[i]==='0'||rr[i]==='#')dec++; }
  var miles=(punto<0?mascara:mascara.slice(0,punto)).indexOf(',')>=0;
  var x=redondear(pc?v*100:v,dec,0), cifras=Math.abs(x).toFixed(dec);
  if(miles){
    var q=cifras.indexOf('.'), ent=q<0?cifras:cifras.slice(0,q), resto=q<0?'':cifras.slice(q), sb='';
    for(i=0;i<ent.length;i++){ if(i>0&&(ent.length-i)%3===0)sb+=','; sb+=ent[i]; }
    cifras=sb+resto;
  }
  return delante+(x<0?'-':'')+cifras+(pc?'%':'')+detras;
}

// ---- CalculoLexico ----
var P_NUM=1,P_TXT=2,P_REF=3,P_COLS=4,P_NOM=5,P_OP=6,P_ABRE=7,P_CIERRA=8,P_SEP=9,P_DP=10,P_ERR=11,P_MAL=12;
var ERRORES=[['#¡DIV/0!','#¡DIV/0!'],['#DIV/0!','#¡DIV/0!'],['#¡VALOR!','#¡VALOR!'],['#VALUE!','#¡VALOR!'],
  ['#¡REF!','#¡REF!'],['#REF!','#¡REF!'],['#¿NOMBRE?','#¿NOMBRE?'],['#NAME?','#¿NOMBRE?'],['#N/D','#N/D'],['#N/A','#N/D'],
  ['#¡NUM!','#¡NUM!'],['#NUM!','#¡NUM!'],['#¡NULO!','#¡NULO!'],['#NULL!','#¡NULO!'],['#¡CIRC!','#¡CIRC!']];
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
    if(c===' '||c==='\t'||c==='\n'||c==='\r'||c==='\u00a0'){i++;continue;}
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
    if(ci.length>0&&!esLetra(sigue)&&sigue!=='('&&sigue!=='.'&&sigue!=='_'){
      var col=columna(le), fila=ci.length<=9?parseInt(ci,10)-1:-1;
      if(col>=0&&col<MAX_COLS&&fila>=0&&fila<MAX_FILAS) return pz(P_REF,f.slice(d,k),d,k,{f:fila,c:col,ff:ff,cf:cf});
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
  return pz(P_NOM,f.slice(d,i),d,i);
}
function Mal(){}
function arbol(formula){
  var p=piezas(formula); if(!p.length) return {k:'err',e:E_NOMBRE};
  var i=0;
  function esOp(ops){ var x=p[i]; return (x&&x.t===P_OP&&ops.indexOf(x.x)>=0)?x.x:null; }
  function comparacion(){ var a=concatenar(),op; while((op=esOp(['=','<>','<','>','<=','>=']))){i++;a={k:'op',op:op,a:a,b:concatenar()};} return a; }
  function concatenar(){ var a=suma(); while(esOp(['&'])){i++;a={k:'op',op:'&',a:a,b:suma()};} return a; }
  function suma(){ var a=producto(),op; while((op=esOp(['+','-']))){i++;a={k:'op',op:op,a:a,b:producto()};} return a; }
  function producto(){ var a=potencia(),op; while((op=esOp(['*','/']))){i++;a={k:'op',op:op,a:a,b:potencia()};} return a; }
  function potencia(){ var a=signo(); while(esOp(['^'])){i++;a={k:'op',op:'^',a:a,b:signo()};} return a; }
  function signo(){ var op=esOp(['+','-']); if(op){i++;var x=signo();return op==='-'?{k:'menos',x:x}:x;} return porCiento(); }
  function porCiento(){ var a=primario(); while(esOp(['%'])){i++;a={k:'pc',x:a};} return a; }
  function primario(){
    var x=p[i]; if(!x) throw new Mal();
    switch(x.t){
      case P_NUM: i++; return {k:'num',n:x.n};
      case P_TXT: i++; return {k:'txt',s:x.x};
      case P_ERR: i++; return {k:'err',e:x.x};
      case P_COLS: i++; return {k:'rango',f1:0,c1:Math.min(x.c,x.c2),f2:MAX_FILAS-1,c2:Math.max(x.c,x.c2),col:true};
      case P_REF:
        i++;
        var s=p[i];
        if(s&&s.t===P_DP){ var o=p[i+1]; if(o&&o.t===P_REF){ i+=2; return {k:'rango',f1:Math.min(x.f,o.f),c1:Math.min(x.c,o.c),f2:Math.max(x.f,o.f),c2:Math.max(x.c,o.c),col:false}; } throw new Mal(); }
        return {k:'ref',f:x.f,c:x.c};
      case P_NOM:
        i++;
        var nom=x.x.toUpperCase(), sg=p[i];
        if(sg&&sg.t===P_ABRE){
          i++; var args=[];
          if(p[i]&&p[i].t===P_CIERRA){i++;return {k:'fn',nombre:nom,args:args};}
          while(true){
            var a=p[i]; if(!a) throw new Mal();
            if(a.t===P_SEP||a.t===P_CIERRA) args.push(HUECO); else args.push(comparacion());
            var q=p[i]; if(!q) throw new Mal();
            if(q.t===P_SEP){i++;continue;}
            if(q.t===P_CIERRA){i++;break;}
            throw new Mal();
          }
          return {k:'fn',nombre:nom,args:args};
        }
        if(nom==='VERDADERO'||nom==='TRUE') return {k:'log',b:true};
        if(nom==='FALSO'||nom==='FALSE') return {k:'log',b:false};
        return {k:'err',e:E_NOMBRE};
      case P_ABRE:
        i++; var dentro=comparacion(); if(!p[i]||p[i].t!==P_CIERRA) throw new Mal(); i++; return dentro;
      default: throw new Mal();
    }
  }
  try{ var r=comparacion(); return i!==p.length?{k:'err',e:E_NOMBRE}:r; }
  catch(e){ if(e instanceof Mal) return {k:'err',e:E_NOMBRE}; throw e; }
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

// ---- Calculadora ----
function esFormula(raw){return raw.length>1&&raw[0]==='=';}
function literal(raw){
  if(raw[0]==="'")return S(raw.slice(1));
  var x=numero(raw); if(x!==null)return N(x);
  x=fecha(raw); if(x!==null)return N(x,true);
  var u=raw.trim().toUpperCase();
  if(u==='VERDADERO'||u==='TRUE')return B(true);
  if(u==='FALSO'||u==='FALSE')return B(false);
  return S(raw);
}
function mostrar(v){
  switch(v.t){ case 'n': return v.f?textoDeFecha(v.n):general(v.n); case 's': return v.s; case 'b': return v.b?'VERDADERO':'FALSO'; case 'e': return v.e; default: return ''; }
}
function tipoDe(v){ return (v.t==='n'||v.t==='v')?0:v.t==='s'?1:v.t==='b'?2:3; }
function vacioComo(o){ return o.t==='s'?S(''):o.t==='b'?B(false):N(0); }
function numCmp(a,b){ if(a===b)return 0; if(Math.abs(a-b)<=1e-12*Math.max(Math.abs(a),Math.abs(b)))return 0; return a<b?-1:1; }
function comparar(a,b){
  var x=a.t==='v'?vacioComo(b):a, y=b.t==='v'?vacioComo(a):b, ra=tipoDe(x), rb=tipoDe(y);
  if(ra!==rb)return ra<rb?-1:1;
  if(x.t==='n')return numCmp(x.n,y.n);
  if(x.t==='s')return cmpStr(x.s.toLowerCase(),y.s.toLowerCase());
  if(x.t==='b')return x.b===y.b?0:!x.b?-1:1;
  return 0;
}
function comodin(p,s){
  var pi=0, si=0, estrella=-1, desde=0;
  while(si<s.length){
    if(pi<p.length){
      var ch=p[pi];
      if(ch==='*'){estrella=pi;desde=si;pi++;continue;}
      if(ch==='~'&&pi+1<p.length){ if(p[pi+1]===s[si]){pi+=2;si++;continue;} }
      else if(ch==='?'||ch===s[si]){pi++;si++;continue;}
    }
    if(estrella>=0){pi=estrella+1;desde++;si=desde;continue;}
    return false;
  }
  while(pi<p.length&&p[pi]==='*')pi++;
  return pi===p.length;
}
function cumple(v,crit){
  var vacia=v.t==='v'||(v.t==='s'&&v.s==='');
  switch(crit.t){
    case 'n': var nn=v.t==='n'?v.n:v.t==='s'?numero(v.s):null; if(nn===null)return false; return numCmp(nn,crit.n)===0;
    case 'b': return v.t==='b'&&v.b===crit.b;
    case 'e': return v.t==='e'&&v.e===crit.e;
    case 'v': return vacia;
  }
  var s=crit.s, ops=['>=','<=','<>','>','<','='], op='';
  for(var i=0;i<ops.length;i++) if(s.slice(0,ops[i].length)===ops[i]){op=ops[i];break;}
  var resto=s.slice(op.length);
  if(resto==='') return op==='<>'?!vacia:(op===''||op==='=')?vacia:false;
  var x=numero(resto); if(x===null)x=fecha(resto);
  if(x!==null){
    var vn=v.t==='n'?v.n:(v.t==='s'&&(op===''||op==='='))?numero(v.s):null;
    if(vn===null) return op==='<>';
    var k=numCmp(vn,x);
    return (op===''||op==='=')?k===0:op==='<>'?k!==0:op==='>'?k>0:op==='<'?k<0:op==='>='?k>=0:k<=0;
  }
  var u=resto.toUpperCase(), lg=(u==='VERDADERO'||u==='TRUE')?true:(u==='FALSO'||u==='FALSE')?false:null;
  if(lg!==null&&(op===''||op==='='||op==='<>')){ var ig=v.t==='b'&&v.b===lg; return op==='<>'?!ig:ig; }
  if(op===''||op==='='||op==='<>'){ var ig2=v.t==='s'&&comodin(resto.toLowerCase(),v.s.toLowerCase()); return op==='<>'?!ig2:ig2; }
  if(v.t!=='s')return false;
  var k2=cmpStr(v.s.toLowerCase(),resto.toLowerCase());
  return op==='>'?k2>0:op==='<'?k2<0:op==='>='?k2>=0:k2<=0;
}
var ALIAS={SUMA:'SUM',PROMEDIO:'AVERAGE',CONTAR:'COUNT',CONTARA:'COUNTA','CONTAR.BLANCO':'COUNTBLANK',PRODUCTO:'PRODUCT',
  MEDIANA:'MEDIAN',DESVEST:'STDEV','DESVEST.M':'STDEV','STDEV.S':'STDEV',SI:'IF','SI.CONJUNTO':'IFS',Y:'AND',O:'OR',NO:'NOT',
  'SI.ERROR':'IFERROR','SI.ND':'IFNA',ESERROR:'ISERROR',ESBLANCO:'ISBLANK',ESNUMERO:'ISNUMBER',ESTEXTO:'ISTEXT',
  REDONDEAR:'ROUND','REDONDEAR.MAS':'ROUNDUP','REDONDEAR.MENOS':'ROUNDDOWN',TRUNCAR:'TRUNC',ENTERO:'INT',RAIZ:'SQRT',
  'RAÍZ':'SQRT',POTENCIA:'POWER',RESIDUO:'MOD',SENO:'SIN',GRADOS:'DEGREES',RADIANES:'RADIANS',SIGNO:'SIGN',
  ALEATORIO:'RAND','ALEATORIO.ENTRE':'RANDBETWEEN',HOY:'TODAY',AHORA:'NOW',FECHA:'DATE',DIA:'DAY','DÍA':'DAY',MES:'MONTH',
  'AÑO':'YEAR',CONCATENAR:'CONCATENATE',LARGO:'LEN',MAYUSC:'UPPER',MINUSC:'LOWER',ESPACIOS:'TRIM',IZQUIERDA:'LEFT',
  DERECHA:'RIGHT',EXTRAE:'MID',ENCONTRAR:'FIND',HALLAR:'SEARCH',SUSTITUIR:'SUBSTITUTE',REPETIR:'REPT',TEXTO:'TEXT',
  VALOR:'VALUE',BUSCARV:'VLOOKUP',BUSCARH:'HLOOKUP',INDICE:'INDEX','ÍNDICE':'INDEX',COINCIDIR:'MATCH',BUSCARX:'XLOOKUP',
  'SUMAR.SI':'SUMIF','CONTAR.SI':'COUNTIF','PROMEDIO.SI':'AVERAGEIF','SUMAR.SI.CONJUNTO':'SUMIFS',
  'CONTAR.SI.CONJUNTO':'COUNTIFS','PROMEDIO.SI.CONJUNTO':'AVERAGEIFS',SUMAPRODUCTO:'SUMPRODUCT',ELEGIR:'CHOOSE',
  FILA:'ROW',COLUMNA:'COLUMN'};

function crear(){
  var crudos=new Map(), valores=new Map(), arboles=new Map(), calculando=new Set();
  var medidas=true, nF=0, nC=0;
  var yo={reloj:function(){return new Date();}, azar:Math.random};
  function medir(){ if(medidas)return; var f=0,c=0; crudos.forEach(function(v,k){ f=Math.max(f,Math.floor(k/1024)+1); c=Math.max(c,k%1024+1); }); nF=f;nC=c;medidas=true; }
  function filas(){medir();return nF;}
  function cols(){medir();return nC;}
  function poner(f,c,t){
    var k=f*1024+c;
    if(!t){ if(crudos.delete(k))medidas=false; }
    else { crudos.set(k,t); if(medidas){nF=Math.max(nF,f+1);nC=Math.max(nC,c+1);} }
    valores.clear();
  }
  function crudo(f,c){ var v=crudos.get(f*1024+c); return v===undefined?'':v; }
  function texto(f,c){ var raw=crudos.get(f*1024+c); if(raw===undefined)return ''; if(!esFormula(raw))return raw[0]==="'"?raw.slice(1):raw; return mostrar(valor(f,c)); }
  function alineacion(f,c){ var v=valor(f,c); return v.t==='n'?'d':(v.t==='b'||v.t==='e')?'c':'i'; }
  function valor(f,c){
    try{ return celda(f,c,0); }
    catch(x){ if(x!==HONDO)throw x; calentar(); try{ return celda(f,c,0); }catch(y){ if(y!==HONDO)throw y; return E(E_NUM); } }
  }
  function calentar(){
    var orden=[]; crudos.forEach(function(v,k){ if(esFormula(v))orden.push(k); });
    orden.sort(function(a,b){return a-b;});
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
    var k=f*1024+c, v=valores.get(k);
    if(v!==undefined)return v;
    var raw=crudos.get(k);
    if(raw===undefined)return VACIO;
    if(!esFormula(raw)){ v=literal(raw); valores.set(k,v); return v; }
    if(calculando.has(k))return E(E_CIRC);
    if(prof>PROFUNDIDAD)throw HONDO;
    calculando.add(k);
    try{
      var a=arboles.get(raw); if(!a){ a=arbol(raw.slice(1)); arboles.set(raw,a); }
      v=evaluar(a,prof+1,f,c);
      if(v.t==='v')v=N(0);
      if(v.t==='n'&&!isFinite(v.n))v=E(E_NUM);
      valores.set(k,v);
      return v;
    } finally { calculando.delete(k); }
  }
  function evaluar(n,prof,f,c){
    switch(n.k){
      case 'num': return N(n.n);
      case 'txt': return S(n.s);
      case 'log': return B(n.b);
      case 'err': return E(n.e);
      case 'hueco': return VACIO;
      case 'ref': return celda(n.f,n.c,prof);
      case 'rango': return (!n.col&&n.f1===n.f2&&n.c1===n.c2)?celda(n.f1,n.c1,prof):E(E_VALOR);
      case 'menos': var x=aNumero(evaluar(n.x,prof,f,c)); return x.t==='n'?N(-x.n):x;
      case 'pc': var y=aNumero(evaluar(n.x,prof,f,c)); return y.t==='n'?N(y.n/100):y;
      case 'op': return operar(n,prof,f,c);
      case 'fn': return llamar(n,prof,f,c);
    }
    return E(E_NOMBRE);
  }
  function operar(n,prof,f,c){
    var a=evaluar(n.a,prof,f,c), b=evaluar(n.b,prof,f,c), op=n.op;
    if(op==='&'){ var x=aTexto(a); if(x.t==='e')return x; var y=aTexto(b); if(y.t==='e')return y; return S(x.s+y.s); }
    if(op==='='||op==='<>'||op==='<'||op==='>'||op==='<='||op==='>='){
      if(a.t==='e')return a; if(b.t==='e')return b;
      var k=comparar(a,b);
      return B(op==='='?k===0:op==='<>'?k!==0:op==='<'?k<0:op==='>'?k>0:op==='<='?k<=0:k>=0);
    }
    var p=aNumero(a); if(p.t==='e')return p;
    var q=aNumero(b); if(q.t==='e')return q;
    var r;
    if(op==='+')return N(p.n+q.n,p.f||q.f);
    if(op==='-')return N(p.n-q.n,p.f&&!q.f);
    if(op==='*')r=p.n*q.n;
    else if(op==='/'){ if(q.n===0)return E(E_DIV0); r=p.n/q.n; }
    else r=Math.pow(p.n,q.n);
    return isFinite(r)?N(r):E(E_NUM);
  }
  function aNumero(v){
    switch(v.t){
      case 'n': case 'e': return v;
      case 'b': return N(v.b?1:0);
      case 'v': return N(0);
      case 's': var x=numero(v.s); if(x!==null)return N(x); x=fecha(v.s); if(x!==null)return N(x,true); return E(E_VALOR);
    }
  }
  function aTexto(v){ return (v.t==='s'||v.t==='e')?v:S(mostrar(v)); }
  function aLogico(v){
    switch(v.t){
      case 'b': case 'e': return v;
      case 'n': return B(v.n!==0);
      case 'v': return B(false);
      case 's': var u=v.s.trim().toUpperCase(); if(u==='VERDADERO'||u==='TRUE')return B(true); if(u==='FALSO'||u==='FALSE')return B(false); return E(E_VALOR);
    }
  }
  function rangoDe(n){
    if(n.k==='rango') return n.col?{k:'rango',f1:n.f1,c1:n.c1,f2:Math.max(n.f1,filas()-1),c2:n.c2,col:true}:n;
    return {k:'rango',f1:n.f,c1:n.c,f2:n.f,c2:n.c,col:false};
  }
  function recorrer(r,prof,solo,visita){
    var f2=solo?Math.min(r.f2,filas()-1):r.f2, c2=solo?Math.min(r.c2,cols()-1):r.c2;
    for(var f=r.f1;f<=f2;f++) for(var c=r.c1;c<=c2;c++){ if(visita(celda(f,c,prof),f-r.f1,c-r.c1)===false)return; }
  }
  function numeros(a,prof,f,c,contando,cada){
    for(var i=0;i<a.length;i++){
      var x=a[i];
      if(x.k==='rango'||x.k==='ref'){
        recorrer(rangoDe(x),prof,true,function(v){ if(v.t==='n')cada(v.n); else if(v.t==='e'&&!contando)throw {fallo:v}; return true; });
      } else if(x.k!=='hueco'){
        var v=evaluar(x,prof,f,c);
        if(v.t==='n')cada(v.n);
        else if(v.t==='b')cada(v.b?1:0);
        else if(v.t==='s'){ var nn=numero(v.s); if(nn===null)nn=fecha(v.s); if(nn!==null)cada(nn); else if(!contando)throw {fallo:E(E_VALOR)}; }
        else if(v.t==='e'){ if(!contando)throw {fallo:v}; }
      }
    }
  }
  function buscarEn(largo,buscado,tipo,en){
    var ultimo=-1;
    for(var i=0;i<largo;i++){
      var v=en(i); if(v.t==='v')continue;
      var mismo=tipoDe(v)===tipoDe(buscado);
      if(tipo===0){
        if(mismo&&comparar(v,buscado)===0)return i;
        if(buscado.t==='s'&&v.t==='s'&&(buscado.s.indexOf('*')>=0||buscado.s.indexOf('?')>=0)&&comodin(buscado.s.toLowerCase(),v.s.toLowerCase()))return i;
        continue;
      }
      if(!mismo)continue;
      var k=comparar(v,buscado);
      if(k===0)return i;
      if(tipo===1){ if(k<0)ultimo=i; else break; } else { if(k>0)ultimo=i; else break; }
    }
    return ultimo;
  }
  function llamar(fn,prof,f,c){
    var nombre=ALIAS[fn.nombre]||fn.nombre, a=fn.args;
    function falla(e){ throw {fallo:e}; }
    function ev(i){ return i<a.length?evaluar(a[i],prof,f,c):VACIO; }
    function pide(mn,mx){ if(a.length<mn||a.length>mx)falla(E(E_VALOR)); }
    function num(i){ var v=aNumero(ev(i)); if(v.t==='n')return v.n; if(v.t==='e')falla(v); falla(E(E_VALOR)); }
    function numO(i,si){ return (i>=a.length||a[i].k==='hueco')?si:num(i); }
    function txt(i){ var v=aTexto(ev(i)); if(v.t==='s')return v.s; if(v.t==='e')falla(v); return ''; }
    function log(i){ var v=aLogico(ev(i)); if(v.t==='b')return v.b; if(v.t==='e')falla(v); return false; }
    function rango(i){ var n=a[i]; if(n&&(n.k==='rango'||n.k==='ref'))return rangoDe(n); falla(E(E_VALOR)); }
    function num1(d){ return isFinite(d)?N(d):E(E_NUM); }
    function mismoTamano(r,base){ return r.f2-r.f1===base.f2-base.f1&&r.c2-r.c1===base.c2-base.c1; }
    try{
      var s,n,m,i,l,r,x,v,t;
      switch(nombre){
        case 'SUM': s=0; numeros(a,prof,f,c,false,function(z){s+=z;}); return N(s);
        case 'AVERAGE': s=0;n=0; numeros(a,prof,f,c,false,function(z){s+=z;n++;}); return n===0?E(E_DIV0):N(s/n);
        case 'MIN': case 'MAX':
          m=nombre==='MIN'?Infinity:-Infinity; n=0;
          numeros(a,prof,f,c,false,function(z){ m=nombre==='MIN'?Math.min(m,z):Math.max(m,z); n++; });
          return N(n===0?0:m);
        case 'COUNT': n=0; numeros(a,prof,f,c,true,function(){n++;}); return N(n);
        case 'COUNTA':
          n=0;
          for(i=0;i<a.length;i++){ x=a[i];
            if(x.k==='rango'||x.k==='ref') recorrer(rangoDe(x),prof,true,function(z){ if(z.t!=='v')n++; return true; });
            else if(x.k!=='hueco'){ if(evaluar(x,prof,f,c).t!=='v')n++; }
          }
          return N(n);
        case 'COUNTBLANK': pide(1,1); n=0; recorrer(rango(0),prof,false,function(z){ if(z.t==='v'||(z.t==='s'&&z.s===''))n++; return true; }); return N(n);
        case 'PRODUCT': s=1;n=0; numeros(a,prof,f,c,false,function(z){s*=z;n++;}); return num1(n===0?0:s);
        case 'MEDIAN':
          l=[]; numeros(a,prof,f,c,false,function(z){l.push(z);});
          if(!l.length)return E(E_NUM);
          l.sort(function(p,q){return p-q;}); m=Math.floor(l.length/2);
          return N(l.length%2===1?l[m]:(l[m-1]+l[m])/2);
        case 'STDEV':
          l=[]; numeros(a,prof,f,c,false,function(z){l.push(z);});
          if(l.length<2)return E(E_DIV0);
          m=0; for(i=0;i<l.length;i++)m+=l[i]; m/=l.length;
          s=0; for(i=0;i<l.length;i++)s+=(l[i]-m)*(l[i]-m);
          return N(Math.sqrt(s/(l.length-1)));
        case 'IF': pide(1,3); return log(0)?(a.length>1?ev(1):B(true)):(a.length>2?ev(2):B(false));
        case 'IFS':
          if(!a.length||a.length%2!==0)falla(E(E_VALOR));
          for(i=0;i<a.length;i+=2){ if(log(i))return ev(i+1); }
          return E(E_ND);
        case 'AND': case 'OR':
          var hay=false, res=nombre==='AND';
          for(i=0;i<a.length;i++){ x=a[i];
            if(x.k==='rango'||x.k==='ref'){
              recorrer(rangoDe(x),prof,true,function(z){
                var bb=z.t==='b'?z.b:z.t==='n'?z.n!==0:null;
                if(z.t==='e')falla(z);
                if(bb!==null){ hay=true; res=nombre==='AND'?(res&&bb):(res||bb); }
                return true;
              });
            } else if(x.k!=='hueco'){
              v=aLogico(evaluar(x,prof,f,c)); if(v.t==='e')falla(v);
              var b2=v.t==='b'?v.b:false; hay=true; res=nombre==='AND'?(res&&b2):(res||b2);
            }
          }
          return hay?B(res):E(E_VALOR);
        case 'NOT': pide(1,1); return B(!log(0));
        case 'IFERROR': pide(2,2); v=ev(0); return v.t==='e'?ev(1):v;
        case 'IFNA': pide(2,2); v=ev(0); return (v.t==='e'&&v.e===E_ND)?ev(1):v;
        case 'ISERROR': pide(1,1); return B(ev(0).t==='e');
        case 'ISBLANK': pide(1,1); return B(ev(0).t==='v');
        case 'ISNUMBER': pide(1,1); return B(ev(0).t==='n');
        case 'ISTEXT': pide(1,1); return B(ev(0).t==='s');
        case 'ROUND': pide(1,2); return N(redondear(num(0),toInt(numO(1,0)),0));
        case 'ROUNDUP': pide(1,2); return N(redondear(num(0),toInt(numO(1,0)),1));
        case 'ROUNDDOWN': case 'TRUNC': pide(1,2); return N(redondear(num(0),toInt(numO(1,0)),-1));
        case 'INT': pide(1,1); return N(Math.floor(num(0)));
        case 'ABS': pide(1,1); return N(Math.abs(num(0)));
        case 'SQRT': pide(1,1); x=num(0); return x<0?E(E_NUM):N(Math.sqrt(x));
        case 'POWER': pide(2,2); return num1(Math.pow(num(0),num(1)));
        case 'MOD': pide(2,2); x=num(0); var y=num(1); return y===0?E(E_DIV0):N(x-y*Math.floor(x/y));
        case 'PI': pide(0,0); return N(Math.PI);
        case 'EXP': pide(1,1); return num1(Math.exp(num(0)));
        case 'LN': pide(1,1); x=num(0); return x<=0?E(E_NUM):N(Math.log(x));
        case 'LOG10': pide(1,1); x=num(0); return x<=0?E(E_NUM):N(Math.log10(x));
        case 'LOG': pide(1,2); x=num(0); var bs=numO(1,10); if(x<=0||bs<=0)return E(E_NUM); if(bs===1)return E(E_DIV0); return N(Math.log(x)/Math.log(bs));
        case 'SIN': pide(1,1); return N(Math.sin(num(0)));
        case 'COS': pide(1,1); return N(Math.cos(num(0)));
        case 'TAN': pide(1,1); return N(Math.tan(num(0)));
        case 'DEGREES': pide(1,1); return N(num(0)*180/Math.PI);
        case 'RADIANS': pide(1,1); return N(num(0)*Math.PI/180);
        case 'SIGN': pide(1,1); x=num(0); return N(x>0?1:x<0?-1:0);
        case 'RAND': pide(0,0); return N(yo.azar());
        case 'RANDBETWEEN': pide(2,2); var lo=Math.ceil(num(0)), hi=Math.floor(num(1)); return hi<lo?E(E_NUM):N(Math.floor(yo.azar()*(hi-lo+1))+lo);
        case 'TODAY': pide(0,0); t=yo.reloj(); return N(serial(t.getFullYear(),t.getMonth()+1,t.getDate()),true);
        case 'NOW': pide(0,0); t=yo.reloj(); return N(serial(t.getFullYear(),t.getMonth()+1,t.getDate())+(t.getHours()*3600+t.getMinutes()*60+t.getSeconds())/86400,true);
        case 'DATE':
          pide(3,3); var an=toInt(num(0)); if(an>=0&&an<=1899)an+=1900;
          if(an<0||an>9999)return E(E_NUM);
          return N(serial(an,toInt(num(1)),toInt(num(2))),true);
        case 'DAY': case 'MONTH': case 'YEAR':
          pide(1,1); var pr=partes(num(0)); return N(pr[nombre==='YEAR'?0:nombre==='MONTH'?1:2]);
        case 'CONCATENATE': case 'CONCAT':
          var sb='';
          for(i=0;i<a.length;i++){ x=a[i];
            if(x.k==='rango'&&!(x.f1===x.f2&&x.c1===x.c2&&!x.col)){
              recorrer(rango(i),prof,true,function(z){ var tt=aTexto(z); if(tt.t==='s')sb+=tt.s; else if(tt.t==='e')falla(tt); return true; });
            } else sb+=txt(i);
          }
          return S(sb);
        case 'LEN': pide(1,1); return N(txt(0).length);
        case 'UPPER': pide(1,1); return S(txt(0).toUpperCase());
        case 'LOWER': pide(1,1); return S(txt(0).toLowerCase());
        case 'TRIM': pide(1,1); return S(txt(0).split(' ').filter(function(z){return z.length>0;}).join(' '));
        case 'LEFT': case 'RIGHT':
          pide(1,2); t=txt(0); n=toInt(numO(1,1));
          if(n<0)return E(E_VALOR);
          return S(nombre==='LEFT'?t.slice(0,n):t.slice(Math.max(0,t.length-n)));
        case 'MID':
          pide(3,3); t=txt(0); var desde=toInt(num(1)); n=toInt(num(2));
          if(desde<1||n<0)return E(E_VALOR);
          return S(desde>t.length?'':t.slice(desde-1,Math.min(t.length,desde-1+n)));
        case 'FIND': case 'SEARCH':
          pide(2,3); var que=txt(0), donde=txt(1), ini=toInt(numO(2,1));
          if(ini<1||ini>donde.length+1)return E(E_VALOR);
          i=nombre==='FIND'?donde.indexOf(que,ini-1):donde.toLowerCase().indexOf(que.toLowerCase(),ini-1);
          return i<0?E(E_VALOR):N(i+1);
        case 'SUBSTITUTE':
          pide(3,4); t=txt(0); var viejo=txt(1), nuevo=txt(2);
          if(!viejo.length)return S(t);
          if(a.length<4)return S(t.split(viejo).join(nuevo));
          var cual=toInt(num(3)); if(cual<1)return E(E_VALOR);
          var pos=-1, visto=0, dd=0;
          while(true){ var jj=t.indexOf(viejo,dd); if(jj<0)break; visto++; if(visto===cual){pos=jj;break;} dd=jj+viejo.length; }
          return S(pos<0?t:t.slice(0,pos)+nuevo+t.slice(pos+viejo.length));
        case 'REPT': pide(2,2); n=toInt(num(1)); return (n<0||n>10000)?E(E_VALOR):S(txt(0).repeat(n));
        case 'TEXT': pide(2,2); v=aNumero(ev(0)); return v.t==='n'?S(conFormato(v.n,txt(1))):S(txt(0));
        case 'VALUE': pide(1,1); v=ev(0); if(v.t==='n')return N(v.n); x=aNumero(S(txt(0))); return x.t==='n'?N(x.n):x;
        case 'VLOOKUP': case 'HLOOKUP':
          pide(3,4); var bu=ev(0); if(bu.t==='e')falla(bu);
          r=rango(1); var col=toInt(num(2)), aprox=(a.length>3&&a[3].k!=='hueco')?log(3):true, vert=nombre==='VLOOKUP';
          var largo=vert?r.f2-r.f1+1:r.c2-r.c1+1, ancho=vert?r.c2-r.c1+1:r.f2-r.f1+1;
          if(col<1)falla(E(E_VALOR)); if(col>ancho)falla(E(E_REF));
          var hasta=Math.min(largo,vert?filas()-r.f1:cols()-r.c1);
          i=buscarEn(hasta,bu,aprox?1:0,function(z){ return vert?celda(r.f1+z,r.c1,prof):celda(r.f1,r.c1+z,prof); });
          if(i<0)return E(E_ND);
          return vert?celda(r.f1+i,r.c1+col-1,prof):celda(r.f1+col-1,r.c1+i,prof);
        case 'MATCH':
          pide(2,3); var bm=ev(0); if(bm.t==='e')falla(bm);
          r=rango(1); var tipo=Math.max(-1,Math.min(1,toInt(numO(2,1)))), vm=r.c1===r.c2;
          if(!vm&&r.f1!==r.f2)falla(E(E_ND));
          var lm=vm?Math.min(r.f2-r.f1+1,filas()-r.f1):Math.min(r.c2-r.c1+1,cols()-r.c1);
          i=buscarEn(lm,bm,tipo,function(z){ return vm?celda(r.f1+z,r.c1,prof):celda(r.f1,r.c1+z,prof); });
          return i<0?E(E_ND):N(i+1);
        case 'XLOOKUP':
          pide(3,4); var bx=ev(0); if(bx.t==='e')falla(bx);
          r=rango(1); var dx=rango(2), vx=r.c1===r.c2;
          var lx=vx?Math.min(r.f2-r.f1+1,filas()-r.f1):Math.min(r.c2-r.c1+1,cols()-r.c1);
          i=buscarEn(lx,bx,0,function(z){ return vx?celda(r.f1+z,r.c1,prof):celda(r.f1,r.c1+z,prof); });
          if(i<0)return a.length>3?ev(3):E(E_ND);
          return vx?celda(dx.f1+i,dx.c1,prof):celda(dx.f1,dx.c1+i,prof);
        case 'INDEX':
          pide(2,3); r=rango(0); var fi=toInt(num(1)), co=toInt(numO(2,1));
          if(a.length===2&&r.f1===r.f2&&r.c1!==r.c2){ co=fi; fi=1; }
          if(fi<1||co<1)return E(E_VALOR);
          if(fi>r.f2-r.f1+1||co>r.c2-r.c1+1)return E(E_REF);
          return celda(r.f1+fi-1,r.c1+co-1,prof);
        case 'SUMIF': case 'AVERAGEIF': case 'COUNTIF':
          pide(2,nombre==='COUNTIF'?2:3); r=rango(0);
          var crit=ev(1); if(crit.t==='e')falla(crit);
          var destino=a.length>2?rango(2):r; s=0;n=0;
          var conVacias=nombre==='COUNTIF'&&cumple(VACIO,crit);
          recorrer(r,prof,!conVacias,function(z,df,dc){
            if(cumple(z,crit)){
              if(nombre==='COUNTIF')n++;
              else { var xx=celda(destino.f1+df,destino.c1+dc,prof); if(xx.t==='n'){s+=xx.n;n++;} }
            }
            return true;
          });
          return nombre==='COUNTIF'?N(n):nombre==='SUMIF'?N(s):(n===0?E(E_DIV0):N(s/n));
        case 'SUMIFS': case 'AVERAGEIFS': case 'COUNTIFS':
          var cuenta=nombre==='COUNTIFS', primero=cuenta?0:1;
          if(a.length<primero+2||(a.length-primero)%2!==0)falla(E(E_VALOR));
          var base=rango(0), pares=[], vacias=cuenta;
          for(i=primero;i<a.length;i+=2){
            var rr=rango(i); if(!mismoTamano(rr,base))falla(E(E_VALOR));
            var cr=ev(i+1); if(cr.t==='e')falla(cr);
            if(!cumple(VACIO,cr))vacias=false;
            pares.push([rr,cr]);
          }
          s=0;n=0;
          recorrer(base,prof,!vacias,function(z,df,dc){
            for(var w=0;w<pares.length;w++){ if(!cumple(celda(pares[w][0].f1+df,pares[w][0].c1+dc,prof),pares[w][1]))return true; }
            if(cuenta)n++; else if(z.t==='n'){s+=z.n;n++;}
            return true;
          });
          return nombre==='COUNTIFS'?N(n):nombre==='SUMIFS'?N(s):(n===0?E(E_DIV0):N(s/n));
        case 'SUMPRODUCT':
          if(!a.length)falla(E(E_VALOR));
          var rs=[]; for(i=0;i<a.length;i++)rs.push(rango(i));
          for(i=0;i<rs.length;i++) if(!mismoTamano(rs[i],rs[0]))falla(E(E_VALOR));
          s=0;
          recorrer(rs[0],prof,true,function(z,df,dc){
            var pp=z.t==='n'?z.n:0;
            for(var w=1;w<rs.length;w++){
              if(pp===0)break;
              var xx=celda(rs[w].f1+df,rs[w].c1+dc,prof);
              if(xx.t==='e')falla(xx);
              pp*=xx.t==='n'?xx.n:0;
            }
            if(z.t==='e')falla(z);
            s+=pp; return true;
          });
          return N(s);
        case 'CHOOSE':
          if(a.length<2)falla(E(E_VALOR));
          i=toInt(num(0)); return (i<1||i>=a.length)?E(E_VALOR):ev(i);
        case 'ROW': case 'COLUMN':
          pide(0,1);
          if(!a.length)return N((nombre==='ROW'?f:c)+1);
          r=rango(0); return N((nombre==='ROW'?r.f1:r.c1)+1);
      }
      return E(E_NOMBRE);
    }catch(z){ if(z&&z.fallo)return z.fallo; throw z; }
  }
  yo.poner=poner; yo.crudo=crudo; yo.texto=texto; yo.valor=valor; yo.alineacion=alineacion;
  yo.filas=filas; yo.cols=cols; yo.olvidar=function(){valores.clear();};
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
  general:general, redondear:redondear, conFormato:conFormato, textoDeFecha:textoDeFecha, serial:serial,
  piezas:piezas, arbol:arbol, desplazar:desplazar, r1c1:r1c1, aR1c1:aR1c1, deTsv:deTsv, aTsv:aTsv,
  esFormula:esFormula, literal:literal, mostrar:mostrar, cumple:cumple, comodin:comodin, referencias:referencias};
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
  var raw=calc.crudo(f,c), t=raw?calc.texto(f,c):'', es=estilos[f*1024+c], v=raw?calc.valor(f,c):null;
  var al=es&&es.a?es.a:(v?(v.t==='n'?'d':(v.t==='b'||v.t==='e')?'c':'i'):'i');
  var b=al==='d'?'d':al==='c'?'c':'';
  if(es&&es.n)b+=(b?' ':'')+'n';
  if(v&&v.t==='e')b+=(b?' ':'')+'err';
  // Las celdas con fórmula, sombreadas: se ve de un vistazo qué se calcula y qué se escribió.
  if(Calculo.esFormula(raw))b+=(b?' ':'')+'f';
  if(protegida&&es&&es.e)b+=(b?' ':'')+'ed';
  td._b=b;
  if(td.textContent!==t)td.textContent=t;
  var fondo=es&&es.f&&/^#[0-9a-fA-F]{6}$/.test(es.f)?es.f:'';
  if(td.style.background!==fondo)td.style.background=fondo;
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
      var t=calc.texto(f,c), raw=calc.crudo(f,c), es=estilos[f*1024+c];
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
      if(es&&es.n)cl.push('n'); if(v&&v.t==='e')cl.push('err'); if(Calculo.esFormula(raw))cl.push('f'); if(protegida&&es&&es.e)cl.push('ed');
      s+='<td'+(cl.length?' class="'+cl.join(' ')+'"':'')+(es&&es.f&&/^#[0-9a-fA-F]{6}$/.test(es.f)?' style="background:'+es.f+'"':'')+'>'+String(raw?calc.texto(f,c):'').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;')+'</td>';
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
