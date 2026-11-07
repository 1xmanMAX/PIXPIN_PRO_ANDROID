package com.forge.pixpin.motor

import android.content.Context

/**
 * **El alto de un texto, atado a lo que tiene dentro** (5-nov-2026, el usuario: «la caja de texto: el alto lo
 * determina su texto, no puedo estirarla hacia abajo y dejar un hueco vacío; a lo largo sí, y que el texto se
 * acomode»). El motor reparte el texto en renglones según el ancho de su caja, y con esto, al estirar o estrechar,
 * el alto se vuelve a medir con la letra de verdad: estirar hacia abajo no deja hueco (vuelve al alto del texto) y
 * estrechar lo parte en más renglones. Era solo del lienzo a pantalla completa; ahora de cualquier capa (los
 * visores). Ver [DrawController.altoDelTexto].
 */
fun DrawController.medirTextosCon(context: Context) {
    val app = context.applicationContext
    altoDelTexto = medir@{ e, ancho ->
        val tam = e.fontSize ?: ItemStyle().fontSize
        // Uno rico no se parte suelto (como en el PC): su alto es el de lo compuesto.
        val fuente = TextoRico.fuenteVigente(e)
        if (fuente != null) {
            val medidor = PintorDeTextoRico(DrawFonts.provider(app)).medidor(e.fontFamily)
            return@medir TextoRico.medida(fuente, TextoRico.opcionesDe(e), medidor).second
        }
        val regla = DrawFonts.reglaDeAnchos(app, e.fontFamily, tam)
        val renglones = renglonesQueCaben(e.text.orEmpty(), ancho, regla)
        altoDeRenglones(renglones.size, tam)
    }
}
