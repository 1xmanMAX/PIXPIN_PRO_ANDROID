package com.forge.pixpin.ocr.indra

import com.forge.pixpin.ocr.indra.OpsGris.Interpolacion
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * **La página preparada para Indra** (26-oct-2026, `IndraPrep.kt` del escáner = `prep.rs` del PC):
 * el fondo normalizado —papel a blanco, sin sombras ni degradados— y un enfoque suave. Es lo que
 * ve el OCR, no el usuario.
 *
 * Indra lee «tinta» = 1 − gris/255: un papel gris o una sombra son tinta tenue para el detector y
 * el reconocedor. En el banco del escáner (54 imágenes con verdad) llevar el papel a blanco subió
 * el español impreso de 73,9 a 91,9 % de palabras.
 *
 * **Lo que añadió el PC y aquí se copia** ([conviene]): no se prepara una página oscura (modo
 * oscuro, una terminal). Ahí el «papel» es el fondo negro y dividir por él vuelve blanco el texto
 * claro; con una captura de código el CER pasaba de 0 a 1,95 %. En el teléfono, con el modo noche
 * de fábrica, esas capturas son lo normal.
 */
object PrepIndra {
    /** Lado al que se estima el papel. */
    const val LADO_FONDO = 512
    /** Diámetro del cierre que borra la tinta al estimar el papel (fracción del lado reducido). */
    const val NUCLEO_FONDO = 1.0 / 30
    const val ENFOQUE = 0.6f
    const val SIGMA_ENFOQUE = 1.0

    /** Si conviene preparar la página: no si la mediana de gris es oscura (< 128). */
    fun conviene(g: Gris): Boolean {
        val hist = IntArray(256)
        for (b in g.px) hist[b.toInt() and 0xFF]++
        val mitad = g.px.size / 2
        var acc = 0
        for (i in 0 until 256) {
            acc += hist[i]
            if (acc > mitad) return i >= 128
        }
        return true
    }

    /** La página preparada (nueva; [g] no cambia). */
    fun preparar(g: Gris): Gris = enfocar(normalizarFondo(g), ENFOQUE, SIGMA_ENFOQUE)

    /** gris / papel · 255, con el papel = cierre + mediana + suavizado a [LADO_FONDO] px. */
    fun normalizarFondo(g: Gris): Gris {
        val w = g.ancho
        val h = g.alto
        val s = LADO_FONDO.toDouble() / max(w, h)
        val pequena = if (s < 1) OpsGris.redimensionar(g, max(1, (w * s).roundToInt()), max(1, (h * s).roundToInt()), Interpolacion.AREA) else g
        val k = max(5, (max(pequena.ancho, pequena.alto) * NUCLEO_FONDO).toInt() or 1)
        val cerrado = OpsGris.mediana5(OpsGris.cerrar(pequena, k))
        val suave = OpsGris.desenfocar(cerrado, k / 3.0)
        var fondo = Gris(cerrado.ancho, cerrado.alto, ByteArray(suave.size) { OpsGris.aByte(suave[it]) })
        if (s < 1) fondo = OpsGris.redimensionar(fondo, w, h, Interpolacion.LINEAL)
        return Gris(w, h, ByteArray(w * h) {
            OpsGris.aByte((g.px[it].toInt() and 0xFF) * 255f / max(1f, (fondo.px[it].toInt() and 0xFF).toFloat()))
        })
    }

    /** Máscara de enfoque: `(1 + a)·g − a·desenfoque(g)`. */
    fun enfocar(g: Gris, a: Float, sigma: Double): Gris {
        val b = OpsGris.desenfocar(g, sigma)
        return Gris(g.ancho, g.alto, ByteArray(g.px.size) { OpsGris.aByte((1 + a) * (g.px[it].toInt() and 0xFF) - a * b[it]) })
    }
}
