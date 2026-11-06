package com.forge.pixpin.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp

/**
 * **El texto rico, visto ya compuesto mientras se escribe** (6-nov-2026, el usuario: «le cambio el color con la barra
 * y aparece como markdown, con corchetes verdes; en negrita, con asteriscos; y al salir de la caja se aplica. Que no
 * aparezca y que el cambio se vea directamente en el texto»).
 *
 * La fuente sigue siendo la misma (`**X**`, `*X*`, `~~X~~`, `<u>X</u>`, `\color{rojo}{X}`, `\tam{1.5}{X}` y las
 * directivas de renglón `\centro`...): es lo que viaja al PC y lo que se vuelve a abrir. Aquí solo **se enseña** sin
 * las marcas y con su estilo, con el cursor y la selección traducidos a la fuente ([OffsetMapping]). Las fórmulas
 * (`$...$`) se ven tal cual.
 */
class VerTextoRico(private val tamano: TextUnit = TextUnit.Unspecified) : VisualTransformation {

    /** Lo que se ve y, por cada letra que se ve, de qué letra de la fuente sale. */
    class Visto(val texto: AnnotatedString, val origen: IntArray, val largoFuente: Int) {
        /** De la fuente a lo que se ve: las marcas caen en la letra visible que las sigue. */
        fun aVisto(o: Int): Int {
            var j = 0
            while (j < origen.size && origen[j] < o) j++
            return j
        }

        /** De lo que se ve a la fuente: delante de la letra; al final, tras la última que se ve. */
        fun aFuente(t: Int): Int = when {
            origen.isEmpty() -> largoFuente
            t <= 0 -> origen[0].coerceAtMost(largoFuente)
            t >= origen.size -> (origen.last() + 1).coerceAtMost(largoFuente)
            else -> origen[t]
        }
    }

    override fun filter(text: AnnotatedString): TransformedText {
        val v = ver(text.text, tamano)
        return TransformedText(v.texto, object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int = v.aVisto(offset).coerceIn(0, v.texto.length)
            override fun transformedToOriginal(offset: Int): Int = v.aFuente(offset).coerceIn(0, text.length)
        })
    }

    override fun equals(other: Any?): Boolean = other is VerTextoRico && other.tamano == tamano
    override fun hashCode(): Int = tamano.hashCode()

    companion object {
        private val DIRECTIVAS = listOf(
            "\\centering", "\\center", "\\centro", "\\derecha", "\\flushright", "\\izquierda", "\\flushleft",
            "\\justificado", "\\justify"
        ).sortedByDescending { it.length }

        private sealed interface Abierto {
            data class Color(val c: androidx.compose.ui.graphics.Color) : Abierto
            data class Tam(val f: Float) : Abierto
            data object Subrayado : Abierto
        }

        /** Compone la fuente [s]: el texto sin marcas, con sus estilos y su mapa de vuelta a la fuente. */
        fun ver(s: String, tamano: TextUnit = TextUnit.Unspecified): Visto {
            val b = AnnotatedString.Builder()
            val origen = ArrayList<Int>(s.length)
            var negrita = false
            var cursiva = false
            var tachado = false
            var formula = false
            val pila = ArrayList<Abierto>()
            var principioDeRenglon = true

            fun estilo(): SpanStyle {
                var color = Color.Unspecified
                var factor = 1f
                var subrayado = false
                for (a in pila) when (a) {
                    is Abierto.Color -> color = a.c
                    is Abierto.Tam -> factor *= a.f
                    Abierto.Subrayado -> subrayado = true
                }
                val deco = listOfNotNull(
                    TextDecoration.Underline.takeIf { subrayado }, TextDecoration.LineThrough.takeIf { tachado }
                )
                return SpanStyle(
                    color = color,
                    fontWeight = if (negrita) FontWeight.Bold else null,
                    fontStyle = if (cursiva) FontStyle.Italic else null,
                    textDecoration = if (deco.isEmpty()) null else TextDecoration.combine(deco),
                    fontSize = if (factor != 1f && tamano.isSpecified) (tamano.value * factor).sp else TextUnit.Unspecified
                )
            }

            // Los tramos del mismo estilo, juntos: un estilo por letra pesaría en cada tecla.
            var estiloDelTramo: SpanStyle? = null
            var inicioDelTramo = 0
            fun cerrarTramo() {
                val e = estiloDelTramo ?: return
                if (origen.size > inicioDelTramo && e != SpanStyle()) b.addStyle(e, inicioDelTramo, origen.size)
            }
            fun poner(c: Char, i: Int) {
                val e = estilo()
                if (e != estiloDelTramo) { cerrarTramo(); estiloDelTramo = e; inicioDelTramo = origen.size }
                b.append(c)
                origen += i
            }

            /** `{...}` que empieza en [i]: su contenido y dónde acaba, o null. */
            fun llaves(i: Int): Pair<String, Int>? {
                if (s.getOrNull(i) != '{') return null
                val fin = s.indexOf('}', i + 1)
                if (fin < 0) return null
                return s.substring(i + 1, fin) to fin
            }

            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\n') { poner(c, i); i++; principioDeRenglon = true; continue }
                // La directiva de alineado, al principio del renglón: no se ve.
                if (principioDeRenglon && !formula) {
                    val d = DIRECTIVAS.firstOrNull { s.startsWith(it, i) && s.getOrNull(i + it.length)?.isLetter() != true }
                    if (d != null) {
                        i += d.length
                        if (s.getOrNull(i) == ' ') i++
                        principioDeRenglon = false
                        continue
                    }
                }
                principioDeRenglon = false
                if (c == '$') { formula = !formula; poner(c, i); i++; continue }
                if (formula) { poner(c, i); i++; continue }
                when {
                    s.startsWith("**", i) -> { negrita = !negrita; i += 2 }
                    s.startsWith("~~", i) -> { tachado = !tachado; i += 2 }
                    s.startsWith("<u>", i) -> { pila += Abierto.Subrayado; i += 3 }
                    s.startsWith("</u>", i) -> { pila.indexOfLast { it == Abierto.Subrayado }.takeIf { it >= 0 }?.let { pila.removeAt(it) }; i += 4 }
                    s.startsWith("\\color", i) -> {
                        val nombre = llaves(i + 6)
                        val color = nombre?.first?.let { com.forge.pixpin.motor.TextoRico.colorDeNombre(it) }
                        if (nombre != null && color != null && s.getOrNull(nombre.second + 1) == '{') {
                            pila += Abierto.Color(Color(color)); i = nombre.second + 2
                        } else { poner(c, i); i++ }
                    }
                    s.startsWith("\\tam", i) -> {
                        val f = llaves(i + 4)
                        val v = f?.first?.toFloatOrNull()
                        if (f != null && v != null && v > 0f && s.getOrNull(f.second + 1) == '{') {
                            pila += Abierto.Tam(v); i = f.second + 2
                        } else { poner(c, i); i++ }
                    }
                    c == '}' && pila.lastOrNull().let { it is Abierto.Color || it is Abierto.Tam } -> { pila.removeAt(pila.lastIndex); i++ }
                    c == '*' -> { cursiva = !cursiva; i++ }
                    else -> { poner(c, i); i++ }
                }
            }
            cerrarTramo()
            return Visto(b.toAnnotatedString(), origen.toIntArray(), s.length)
        }
    }
}
