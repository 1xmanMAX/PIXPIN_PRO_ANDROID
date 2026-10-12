package com.forge.pixpin.excel

import android.content.res.AssetManager
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import java.util.concurrent.ConcurrentHashMap

/**
 * **Las letras de una hoja**: las de PixPin que miden como las de Windows (`assets/fuentes-ttf`,
 * las mismas que el visor de planos y Word: Carlito por Calibri, Caladea por Cambria, Liberation por
 * Arial, Times New Roman y Courier New), y si no, la del sistema que más se parezca. Así una
 * columna de Excel de 64 px enseña lo mismo que en el PC, sin «###» ni desbordes de más.
 */
object LetrasExcel {
    private val tipos = ConcurrentHashMap<String, Typeface>()

    /** El archivo de PixPin de una familia (sin la variante), o null si no hay uno que mida igual. */
    fun grupo(familia: String): String? = when (familia.trim().lowercase()) {
        "calibri", "calibri light", "carlito", "aptos", "aptos display", "aptos narrow", "aptos light", "aptos serif" -> "Carlito"
        "cambria", "cambria math", "caladea" -> "Caladea"
        "arial", "helvetica", "liberation sans", "arimo", "arial unicode ms", "helvetica neue" -> "LiberationSans"
        "arial narrow", "liberation sans narrow" -> "LiberationSansNarrow"
        "times new roman", "times", "liberation serif", "tinos" -> "LiberationSerif"
        "courier new", "courier", "liberation mono", "cousine", "consolas" -> "LiberationMono"
        else -> null
    }

    /** Lo que se finge porque el archivo no lo trae: 1 negrita, 2 cursiva. */
    class Letra(val tipo: Typeface, val fingir: Int)

    fun letra(assets: AssetManager, familia: String, negrita: Boolean, cursiva: Boolean): Letra {
        val g = grupo(familia)
        if (g != null) {
            // Los archivos que hay: -Regular, -Bold y, solo en Liberation Sans, -Italic.
            val conCursiva = g == "LiberationSans" && cursiva && !negrita
            val variante = when {
                conCursiva -> "Italic"
                negrita -> "Bold"
                else -> "Regular"
            }
            val t = cargar(assets, "$g-$variante") ?: cargar(assets, "$g-Regular")
            if (t != null) {
                val fingir = (if (cursiva && !conCursiva) 2 else 0) or (if (negrita && t === tipos["$g-Regular"]) 1 else 0)
                return Letra(t, fingir)
            }
        }
        val f = familia.lowercase()
        val base = when {
            listOf("mono", "consolas", "courier", "code", "lucida console").any { it in f } -> Typeface.MONOSPACE
            listOf("times", "serif", "georgia", "garamond", "cambria", "book", "roman", "palatino", "century").any { it in f } && "sans" !in f -> Typeface.SERIF
            else -> Typeface.SANS_SERIF
        }
        val estilo = (if (negrita) Typeface.BOLD else 0) or (if (cursiva) Typeface.ITALIC else 0)
        return Letra(Typeface.create(base, estilo), 0)
    }

    private fun cargar(assets: AssetManager, nombre: String): Typeface? {
        tipos[nombre]?.let { return it }
        val t = runCatching { Typeface.createFromAsset(assets, "fuentes-ttf/$nombre.ttf") }.getOrNull() ?: return null
        tipos[nombre] = t
        return t
    }

    /**
     * La pintura de un texto de la hoja, en px de hoja al 100 % (a 96 ppp, como el PC): quien
     * pinta escala el lienzo. Medidas lineales y sin ajustar a píxeles: miden lo mismo a cualquier zoom.
     */
    fun pintura(assets: AssetManager, familia: String, puntos: Float, letra: Int, argb: Int): TextPaint {
        val l = letra(assets, familia, letra and 1 != 0, letra and 2 != 0)
        return TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG).apply {
            typeface = l.tipo
            textSize = (puntos.takeIf { it > 0f } ?: 11f) * 96f / 72f * (if (letra and (32 or 64) != 0) 0.7f else 1f)
            color = argb
            isFakeBoldText = l.fingir and 1 != 0
            textSkewX = if (l.fingir and 2 != 0) -0.22f else 0f
            isUnderlineText = letra and 24 != 0
            isStrikeThruText = letra and 4 != 0
        }
    }
}
