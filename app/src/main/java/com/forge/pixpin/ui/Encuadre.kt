package com.forge.pixpin.ui

/**
 * **Acercar y mover lo que se ve, como en un lienzo** (25-oct-2026, `pixpin_ui::encuadre` del PC,
 * 8d7fe93; el usuario en la ventana de estudiar una clase: «donde aparece el vídeo, permitir hacer
 * zoom como si fuera un canvas»).
 *
 * Lo puro de un visor de una imagen (un fotograma del vídeo, una captura): encajada por defecto en
 * su caja, el pellizco acerca hacia los dedos, arrastrar la lleva y el doble toque la vuelve a
 * encajar. Aquí no se pinta nada: quien pinta pide [destino] y dibuja la imagen en ese rectángulo,
 * recortado a la caja. Tres decisiones, las del PC:
 *
 * - **El aumento cuenta desde «encajada»** (1 = cabe entera), no desde los píxeles de la imagen: un
 *   vídeo de 1920 en una caja de 900 empieza en 1, no en 47 %. El tanto por ciento que se enseña sí
 *   es el de los píxeles ([porCiento]), que es lo que dice cuánto detalle hay.
 * - **No se aleja de «encajada»**: más pequeña que su caja no enseña nada más, solo deja marco negro.
 * - **No se pierde de vista**: al moverla, la imagen acercada nunca deja hueco dentro de la caja por
 *   un lado mientras sobra por el otro; en el eje en que cabe, va centrada.
 *
 * Del PC no se trae `a_la_ventana` (el ratón contado desde la esquina de la ventana de Windows): en
 * el teléfono los toques ya llegan en coordenadas de la vista.
 */
data class Encuadre(val aumento: Float = 1f, val dx: Float = 0f, val dy: Float = 0f) {

    /** Un rectángulo en píxeles de la vista. */
    data class Caja(val x: Float, val y: Float, val ancho: Float, val alto: Float)

    /** Si está como al abrir: encajada y en el centro. */
    val encajado: Boolean get() = Math.abs(aumento - 1f) < 1e-3f && Math.abs(dx) < 0.5f && Math.abs(dy) < 0.5f

    /** **Dónde se pinta la imagen** [w]×[h] en [caja]. */
    fun destino(caja: Caja, w: Float, h: Float): Caja {
        val b = encajada(caja, w, h)
        val cx = b.x + b.ancho / 2f
        val cy = b.y + b.alto / 2f
        val dw = b.ancho * aumento
        val dh = b.alto * aumento
        return Caja(cx - dw / 2f + dx, cy - dh / 2f + dy, dw, dh)
    }

    /** El aumento en píxeles de la imagen: 100 es un píxel de la imagen por píxel de la pantalla. */
    fun porCiento(caja: Caja, w: Float, h: Float): Int {
        if (w <= 0f) return 100
        return Math.round(destino(caja, w, h).ancho / w * 100f).coerceAtLeast(1)
    }

    /** Que no se salga: sin hueco dentro de la caja si es más grande que ella; centrada en el eje en que cabe. */
    fun acotado(caja: Caja, w: Float, h: Float): Encuadre {
        val a = aumento.coerceIn(1f, AUMENTO_MAXIMO)
        val b = encajada(caja, w, h)
        fun holgura(base: Float, ladoBase: Float, borde: Float, ladoCaja: Float): Pair<Float, Float> {
            val lado = ladoBase * a
            if (lado <= ladoCaja) return 0f to 0f
            // Lo que se puede correr hacia cada lado sin dejar hueco.
            val centro = base + ladoBase / 2f
            return ((borde + ladoCaja) - (centro + lado / 2f)) to (borde - (centro - lado / 2f))
        }
        val (mx, xx) = holgura(b.x, b.ancho, caja.x, caja.ancho)
        val (my, xy) = holgura(b.y, b.alto, caja.y, caja.alto)
        return Encuadre(a, dx.coerceIn(minOf(mx, xx), maxOf(mx, xx)), dy.coerceIn(minOf(my, xy), maxOf(my, xy)))
    }

    /** **Acerca ([factor] > 1) o aleja hacia [px],[py]**: lo que hay bajo los dedos se queda bajo los dedos. */
    fun acercar(caja: Caja, w: Float, h: Float, factor: Float, px: Float, py: Float): Encuadre {
        val antes = destino(caja, w, h)
        val nuevo = (aumento * factor).coerceIn(1f, AUMENTO_MAXIMO)
        if (antes.ancho <= 0f || Math.abs(nuevo - aumento) < 1e-6f) return acotado(caja, w, h)
        // El punto en fracción de la imagen, antes de acercar.
        val fx = (px - antes.x) / antes.ancho
        val fy = (py - antes.y) / antes.alto
        val con = copy(aumento = nuevo)
        val despues = con.destino(caja, w, h)
        return con.copy(dx = dx + px - (despues.x + fx * despues.ancho), dy = dy + py - (despues.y + fy * despues.alto))
            .acotado(caja, w, h)
    }

    /** Un paso de acercar ([pasos] > 0) o alejar, hacia el centro de la caja (los botones − y +). */
    fun paso(caja: Caja, w: Float, h: Float, pasos: Int): Encuadre =
        acercar(caja, w, h, Math.pow(PASO.toDouble(), pasos.toDouble()).toFloat(), caja.x + caja.ancho / 2f, caja.y + caja.alto / 2f)

    /** La lleva con el dedo (arrastrar). */
    fun mover(caja: Caja, w: Float, h: Float, ddx: Float, ddy: Float): Encuadre =
        copy(dx = dx + ddx, dy = dy + ddy).acotado(caja, w, h)

    /** **El punto de la imagen** (en sus píxeles) bajo [px],[py] de la vista; null fuera de ella. */
    fun aLaImagen(caja: Caja, w: Float, h: Float, px: Float, py: Float): Pair<Float, Float>? {
        val d = destino(caja, w, h)
        if (d.ancho <= 0f || d.alto <= 0f) return null
        val u = (px - d.x) / d.ancho
        val v = (py - d.y) / d.alto
        return if (u in 0f..1f && v in 0f..1f) (u * w) to (v * h) else null
    }

    companion object {
        /** Lo más que se acerca, veces «encajada». */
        const val AUMENTO_MAXIMO = 12f

        /** Lo que acerca un paso de los botones (y una muesca de rueda en el PC). */
        const val PASO = 1.25f

        /** La imagen [w]×[h] encajada en [caja] sin deformarla, centrada. */
        fun encajada(caja: Caja, w: Float, h: Float): Caja {
            if (w <= 0f || h <= 0f || caja.ancho <= 0f || caja.alto <= 0f) return caja
            val k = minOf(caja.ancho / w, caja.alto / h)
            val dw = w * k
            val dh = h * k
            return Caja(caja.x + (caja.ancho - dw) / 2f, caja.y + (caja.alto - dh) / 2f, dw, dh)
        }
    }
}
