package com.forge.pixpin.motor

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import com.forge.pixpin.R

/**
 * Las tres letras de Excalidraw.
 *
 * **Sin ellas el texto no se parece.** Un dibujo a mano alzada con la letra del
 * sistema encima canta enseguida: todo lo demás tiembla y el texto va recto y
 * perfecto. Por eso el original trae sus propias fuentes y aquí van las mismas:
 *
 * - **Excalifont** — la de a mano, la que sustituyó a Virgil. Es la de por
 *   defecto.
 * - **Nunito** — la «normal», para cuando hace falta que se lea limpio.
 * - **Comic Shanns** — la monoespaciada, para código.
 *
 * Las tres son OFL. Se guardan como TTF porque Android no carga `woff2`, que es
 * como las distribuye el original; Excalifont se convirtió desde su `woff2`
 * completo, con sus 585 glifos.
 *
 * Se cachean por familia: `ResourcesCompat.getFont` va al disco, y el
 * renderizador la pide **por cada elemento de texto y en cada fotograma**.
 */
object DrawFonts {

    private val cache = HashMap<Int, Typeface?>()

    /**
     * La letra de [familia], resolviendo la numeración vieja.
     *
     * Devuelve null si la fuente no se pudo cargar, y entonces el renderizador
     * se queda con la del sistema: es preferible a que no se vea el texto.
     */
    fun typefaceFor(context: Context, familia: Int?): Typeface? {
        val id = ItemStyle.fontFamilyResuelta(familia)
        return cache.getOrPut(id) {
            val recurso = when (id) {
                ItemStyle.FONT_NUNITO -> R.font.nunito
                ItemStyle.FONT_COMIC_SHANNS -> R.font.comic_shanns
                else -> R.font.excalifont
            }
            runCatching { ResourcesCompat.getFont(context, recurso) }.getOrNull()
        }
    }

    /** El proveedor que espera [Renderer], ya atado a un contexto. */
    fun provider(context: Context): (Int?) -> Typeface? {
        val app = context.applicationContext
        return { familia -> typefaceFor(app, familia) }
    }

    /**
     * Lo que ocupa [texto] escrito con esa letra y ese tamaño.
     *
     * Vive aquí y no en el editor porque lo necesita **todo el que deje
     * escribir un texto**: el editor a pantalla completa, la captura y, en
     * cuanto pueda teclearse ahí, el pin. Un texto con la caja mal medida se
     * selecciona por donde no es y se recorta al exportar, así que medirlo de
     * dos maneras distintas es garantía de que una de las dos esté mal.
     */
    fun medirTexto(
        context: Context, texto: String, familia: Int?, tamano: Double
    ): Pair<Double, Double> {
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.textSize = tamano.toFloat()
        p.typeface = typefaceFor(context, familia)
        val lineas = texto.split('\n')
        val ancho = lineas.maxOfOrNull { p.measureText(it).toDouble() } ?: 0.0
        // El interlineado de Excalidraw es 1,25 del tamaño de fuente.
        return ancho to lineas.size * tamano * 1.25
    }

    /** Nombre de cada familia para la interfaz. */
    fun nombreDe(familia: Int): String = when (ItemStyle.fontFamilyResuelta(familia)) {
        ItemStyle.FONT_NUNITO -> "Normal"
        ItemStyle.FONT_COMIC_SHANNS -> "Código"
        else -> "A mano"
    }
}
