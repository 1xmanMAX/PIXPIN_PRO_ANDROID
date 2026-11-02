package com.forge.pixpin.ui

import android.content.Context
import com.forge.pixpin.R
import com.forge.pixpin.motormd.MarcasDeTexto
import org.json.JSONObject

/**
 * **Marcar el texto de una página web o de una nota que se lee** (2-nov-2026, como el PC
 * `marcas_web.rs`): la página lleva el guion del PC tal cual (`assets/marcas-pagina.js`, que elige,
 * pinta con `CSS.highlights`, deshace y manda la lista entera) y aquí solo el archivo
 * ([MarcasDeTexto.DeLaPagina]).
 *
 * El guion del PC habla con `window.chrome.webview.postMessage` y se entera de lo elegido al soltar
 * el ratón; en el teléfono se le pone un puente con ese nombre que llama a [PUENTE] y, como con el
 * dedo no hay «soltar el ratón» al acabar de elegir, se le manda uno cuando la selección se queda
 * quieta. La burbuja va debajo de lo elegido, que encima está la barra de copiar del sistema.
 */
object MarcasDeTextoEnLaWeb {
    /** El nombre del objeto de Kotlin en la página. */
    const val PUENTE = "PixPinMarcas"

    private fun guionDelPc(c: Context): String =
        runCatching { c.assets.open("marcas-pagina.js").use { it.readBytes().toString(Charsets.UTF_8) } }.getOrDefault("")

    private const val ANTES = """(function(){if(window.__pixpinPuenteMarcas)return;window.__pixpinPuenteMarcas=1;
try{window.chrome=window.chrome||{};window.chrome.webview={postMessage:function(s){try{$PUENTE.mensaje(String(s))}catch(e){}}};}catch(e){}})();"""

    /** Con el dedo: la selección quieta es «soltar», y la burbuja debajo. */
    private const val TACTIL = """(function(){if(window.__pixpinTactil)return;window.__pixpinTactil=1;var t=0;
function abajo(){var b=document.getElementById('pixpin-marcas');var s=getSelection();if(!b||b.style.display==='none'||!s||!s.rangeCount||s.isCollapsed)return;
var rs=s.getRangeAt(0).getClientRects();if(!rs.length)return;var u=rs[rs.length-1];var h=b.getBoundingClientRect().height||46;var y=u.bottom+28;
if(y+h>innerHeight-6)y=Math.max(6,rs[0].top-h-60);b.style.top=y+'px';}
document.addEventListener('selectionchange',function(){clearTimeout(t);t=setTimeout(function(){var s=getSelection();if(!s||s.isCollapsed||!s.rangeCount)return;
var r=s.getRangeAt(0).getBoundingClientRect();dispatchEvent(new MouseEvent('mouseup',{button:0,clientX:r.left,clientY:r.top,bubbles:true}));setTimeout(abajo,30);},450);});})();"""

    /** Todo lo que se le pone a la página al acabar de cargar. */
    fun guion(c: Context): String = ANTES + "\n" + guionDelPc(c) + "\n" + TACTIL

    /** Los rótulos de los botones, en el idioma de la app. */
    fun rotulos(c: Context): String = JSONObject().apply {
        put("copiar", c.getString(R.string.mt_copiar))
        put("resaltar", c.getString(R.string.mt_resaltar))
        put("ondulada", c.getString(R.string.mt_ondulada))
        put("subrayar", c.getString(R.string.mt_subrayar))
        put("tachar", c.getString(R.string.mt_tachar))
        put("tapar", c.getString(R.string.mt_tapar))
        put("quitar", c.getString(R.string.mt_quitar))
        put("color", c.getString(R.string.mt_color))
    }.toString()

    /** El guion que le deja a la página sus marcas (y los rótulos, si van). */
    fun recibir(marcas: String, rotulos: String? = null): String =
        "window.__pixpinMarcas&&window.__pixpinMarcas.recibir({\"marcas\":$marcas" + (rotulos?.let { ",\"rotulos\":$it" } ?: "") + "})"

    sealed interface Mensaje {
        data object Pedir : Mensaje
        data class Lista(val marcas: String) : Mensaje
    }

    /** Lo que manda el guion (`{"pixpin_marcas": …}`); null si no es suyo. */
    fun leer(m: String): Mensaje? = runCatching {
        val o = JSONObject(m)
        when (o.optString("pixpin_marcas")) {
            "pedir" -> Mensaje.Pedir
            "lista" -> Mensaje.Lista(o.optJSONArray("marcas")?.toString() ?: "[]")
            else -> null
        }
    }.getOrNull()

    /** **Lo que pide la página**: el guion que la contesta, si hay que contestar. */
    fun atender(c: Context, p: MarcasDeTexto.DeLaPagina, m: String): String? = when (val l = leer(m)) {
        Mensaje.Pedir -> recibir(p.lista(), rotulos(c))
        is Mensaje.Lista -> p.deLaPagina(l.marcas)?.let { recibir(it) }
        null -> null
    }
}
