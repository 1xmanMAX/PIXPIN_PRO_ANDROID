package com.forge.pixpin.guardados

/**
 * Las cuentas, sin Android, de tres cosas del chat que el PC estrenó el 4-oct-2026: la foto que
 * se ve derecha según su EXIF ([GiroExif]), el logo de un proyecto ([CuentasDelLogo]) y el
 * cuadro de enviar varias fotos ([EnvioDeVarios]). Lo que toca `Bitmap` y disco vive en
 * [FotoDerecha], [LogoDelProyecto] y en la pantalla del chat. Pruebas: `CuentasDeLaFotoTest`.
 */

/**
 * Lo que hay que hacerle a los píxeles guardados para verlos derechos: girar [grados] (en el
 * sentido de las agujas, como `Matrix.setRotate`) y **después** voltear en horizontal si
 * [espejo]. Es la misma tabla que `transformacion_exif` del PC (`pixpin-codec/src/vista.rs`).
 */
data class Giro(val grados: Int, val espejo: Boolean) {
    val nada: Boolean get() = grados == 0 && !espejo
}

object GiroExif {
    /**
     * El giro de una etiqueta de orientación EXIF (1 a 8). Una desconocida se trata como «tal
     * cual», que es lo que hacen todos los visores.
     */
    fun de(orientacion: Int): Giro = when (orientacion) {
        2 -> Giro(0, true)
        3 -> Giro(180, false)
        // Volteo vertical = girar 180 y voltear en horizontal.
        4 -> Giro(180, true)
        // Traspuesta: girar 90 y voltear en horizontal.
        5 -> Giro(90, true)
        6 -> Giro(90, false)
        // Transversa: girar 270 y voltear en horizontal.
        7 -> Giro(270, true)
        8 -> Giro(270, false)
        else -> Giro(0, false)
    }

    /** Si esa orientación cambia el ancho por el alto. */
    fun tumbada(orientacion: Int): Boolean = orientacion in 5..8
}

/**
 * **El logo de un proyecto**: una imagen en lugar del círculo con la inicial. Se guarda ya
 * cuadrado y pequeño ([LADO] de lado, del centro de la foto), como `logo.rs` del PC.
 */
object CuentasDelLogo {
    /** El avatar más grande a tres por punto y algo de sobra para reducirlo sin dientes. */
    const val LADO = 256

    /** El cuadrado más grande del centro de una imagen: `(x, y, lado)`. Lo impar sobra a la derecha. */
    fun cuadradoCentral(ancho: Int, alto: Int): Triple<Int, Int, Int> {
        val lado = minOf(ancho, alto)
        return Triple((ancho - lado) / 2, (alto - lado) / 2, lado)
    }

    /** El lado con que se guarda: una pequeña **no** se agranda, solo gastaría disco. */
    fun ladoGuardado(lado: Int): Int = minOf(lado, LADO)

    /** Lo que se puede reducir al leer una imagen de [ancho]×[alto] y que siga sobrando para [LADO]. */
    fun muestreo(ancho: Int, alto: Int): Int {
        var m = 1
        while (minOf(ancho, alto) / (m * 2) >= LADO) m *= 2
        return m
    }
}

/**
 * **El cuadro de enviar varias fotos** (`envio.rs` del PC): miniaturas numeradas que se
 * reordenan arrastrando, se quitan con su aspa y se añaden más.
 */
object EnvioDeVarios {
    /**
     * Mueve el elemento [desde] para que quede delante de lo que estaba en [hueco] (0 es delante
     * de todo; `size`, al final). Soltarlo en su propio sitio no cambia nada.
     */
    fun <T> mover(v: List<T>, desde: Int, hueco: Int): List<T> {
        if (desde !in v.indices) return v
        val h = hueco.coerceIn(0, v.size)
        val destino = if (h > desde) h - 1 else h
        if (destino == desde) return v
        val m = v.toMutableList()
        val x = m.removeAt(desde)
        m.add(destino, x)
        return m
    }

    /**
     * El hueco donde cae una miniatura arrastrada: [desde] corrido [desplazamiento] en anchos de
     * miniatura (con su separación). Pasada la mitad de la vecina, se mete detrás de ella.
     */
    fun huecoTrasArrastrar(desde: Int, desplazamiento: Float, cuantos: Int): Int {
        val pasos = kotlin.math.round(desplazamiento).toInt()
        val destino = (desde + pasos).coerceIn(0, maxOf(cuantos - 1, 0))
        return if (destino > desde) destino + 1 else destino
    }

    /** Añade lo nuevo **sin repetir** lo que ya estaba. */
    fun <T> anadir(v: List<T>, nuevos: List<T>): List<T> {
        val r = v.toMutableList()
        nuevos.forEach { if (it !in r) r.add(it) }
        return r
    }
}
