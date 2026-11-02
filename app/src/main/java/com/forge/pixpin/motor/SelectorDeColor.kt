package com.forge.pixpin.motor

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * **Las cuentas del color avanzado** (2-nov-2026, puerto de `pindesk-ui/src/selector_color.rs` del
 * PC): HSV de ida y vuelta, el código de 3, 6 u 8 cifras (las dos últimas, la opacidad), el
 * contraste con el papel (WCAG) y los colores recientes (los cinco últimos elegidos a mano).
 * Colores en `r, g, b` de 0 a 1.
 */
object SelectorDeColor {
    data class Rgb(val r: Float, val g: Float, val b: Float)

    data class Hsv(val h: Float, val s: Float, val v: Float) {
        fun color(): Rgb {
            val hh = ((h % 360f) + 360f) % 360f / 60f
            val ss = s.coerceIn(0f, 1f)
            val vv = v.coerceIn(0f, 1f)
            val c = vv * ss
            val x = c * (1f - abs(hh % 2f - 1f))
            val (r, g, b) = when (hh.toInt()) {
                0 -> Triple(c, x, 0f)
                1 -> Triple(x, c, 0f)
                2 -> Triple(0f, c, x)
                3 -> Triple(0f, x, c)
                4 -> Triple(x, 0f, c)
                else -> Triple(c, 0f, x)
            }
            val m = vv - c
            return Rgb(r + m, g + m, b + m)
        }

        /** Un gris (o el negro) no tiene tono: se queda con el que se estaba eligiendo. */
        fun conTonoDe(recordado: Float?): Hsv = if (recordado != null && (s <= 0.001f || v <= 0.001f)) copy(h = recordado) else this

        companion object {
            fun de(c: Rgb): Hsv {
                val r = c.r.coerceIn(0f, 1f)
                val g = c.g.coerceIn(0f, 1f)
                val b = c.b.coerceIn(0f, 1f)
                val mx = max(r, max(g, b))
                val mn = min(r, min(g, b))
                val d = mx - mn
                val h = when {
                    d <= 1e-7f -> 0f
                    mx == r -> 60f * ((((g - b) / d) % 6f + 6f) % 6f)
                    mx == g -> 60f * ((b - r) / d + 2f)
                    else -> 60f * ((r - g) / d + 4f)
                }
                return Hsv(((h % 360f) + 360f) % 360f, if (mx <= 1e-7f) 0f else d / mx, mx)
            }
        }
    }

    fun tonoPuro(h: Float): Rgb = Hsv(h, 1f, 1f).color()

    private fun byte(v: Float) = (v.coerceIn(0f, 1f) * 255f).roundToInt()

    /** `#rgb`, `#rrggbb` o `#rrggbbaa` (con o sin `#`): el color y, con 8 cifras, la opacidad. */
    fun leerCodigo(escrito: String): Pair<Rgb, Float?>? {
        val s = escrito.trim().removePrefix("#")
        if (s.isEmpty() || !s.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        fun par(i: Int) = s.substring(i, i + 2).toInt(16)
        fun uno(i: Int) = s.substring(i, i + 1).toInt(16) * 17
        val (r, g, b, a) = when (s.length) {
            3 -> listOf(uno(0), uno(1), uno(2), -1)
            6 -> listOf(par(0), par(2), par(4), -1)
            8 -> listOf(par(0), par(2), par(4), par(6))
            else -> return null
        }
        return Rgb(r / 255f, g / 255f, b / 255f) to (if (a < 0) null else a / 255f)
    }

    /** `rrggbb`, y `aa` detrás solo si la opacidad no es entera. */
    fun escribirCodigo(c: Rgb, opacidad: Float = 1f): String {
        val s = "%02x%02x%02x".format(byte(c.r), byte(c.g), byte(c.b))
        return if (byte(opacidad) < 255) s + "%02x".format(byte(opacidad)) else s
    }

    fun deHex(hex: String): Rgb? = leerCodigo(hex)?.first
    fun aHex(c: Rgb): String = "#" + escribirCodigo(c)

    fun luminancia(c: Rgb): Float {
        fun lineal(v0: Float): Float {
            val v = v0.coerceIn(0f, 1f)
            return if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
        }
        return 0.2126f * lineal(c.r) + 0.7152f * lineal(c.g) + 0.0722f * lineal(c.b)
    }

    /** El contraste WCAG (de 1 a 21). */
    fun contraste(a: Rgb, b: Rgb): Float {
        val la = luminancia(a)
        val lb = luminancia(b)
        return (max(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
    }

    /** Si encima de [fondo] se lee mejor una marca clara que una oscura. */
    fun marcaClara(fondo: Rgb): Boolean {
        val blanco = Rgb(1f, 1f, 1f)
        val oscuro = Rgb(0.118f, 0.118f, 0.118f)
        return contraste(fondo, blanco) >= contraste(fondo, oscuro)
    }

    fun iguales(a: Rgb, b: Rgb) = byte(a.r) == byte(b.r) && byte(a.g) == byte(b.g) && byte(a.b) == byte(b.b)

    /** **Los recientes**: los cinco últimos elegidos a mano, el último delante, sin repetir. */
    class Recientes private constructor(private val lista: MutableList<Rgb>) {
        val colores: List<Rgb> get() = lista

        fun codigos(): List<String> = lista.map { "#" + escribirCodigo(it) }

        /** Lo apunta delante; false si ya era el primero. */
        fun apuntar(c: Rgb): Boolean {
            if (lista.firstOrNull()?.let { iguales(it, c) } == true) return false
            lista.removeAll { iguales(it, c) }
            lista.add(0, c)
            while (lista.size > CUANTOS) lista.removeAt(lista.size - 1)
            return true
        }

        companion object {
            const val CUANTOS = 5

            fun deCodigos(codigos: List<String>): Recientes {
                val r = Recientes(ArrayList())
                for (c in codigos.asReversed()) leerCodigo(c)?.let { r.apuntar(it.first) }
                return r
            }

            /** Lo guardado en ajustes (`#rrggbb` separados por comas). */
            fun deTexto(t: String): Recientes = deCodigos(t.split(',').map { it.trim() }.filter { it.isNotEmpty() })
        }
    }
}
