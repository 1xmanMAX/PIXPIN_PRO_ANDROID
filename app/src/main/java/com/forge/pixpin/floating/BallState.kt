package com.forge.pixpin.floating

import kotlin.math.roundToInt

/** Qué hace falta para que la bola vuelva a verse. */
enum class BallRecovery { NADA, CREAR, VOLVER_A_ENSENAR }

/** Dónde queda la bola: la esquina de arriba a la izquierda, en píxeles. */
data class SitioDeLaBola(val x: Int, val y: Int)

/**
 * El estado de la bola tiene TRES casos, no dos.
 *
 * El tercero —ventana puesta pero con el contenido en GONE— es como queda si una
 * captura se va al traste sin restaurar los overlays: el servicio de captura
 * muere a media captura, cancela la corrutina que iba a volver a enseñarlos y
 * nadie deshace el ocultado.
 *
 * Mientras el código solo miró «¿existe la ventana?», ese caso se daba por
 * bueno: la bola había desaparecido de la pantalla y ni «Comenzar» ni reiniciar
 * el servicio la traían de vuelta, porque ambos preguntaban por la existencia de
 * la ventana y no por si se veía.
 *
 * ## Y hay un cuarto: puesta, visible y fuera de la pantalla
 *
 * Que es peor que los otros tres, porque desde dentro **todo parece correcto**.
 * La ventana existe, su contenido está visible, y `recoveryFor` decía NADA — así
 * que «Comenzar» no hacía nada y no quedaba forma de recuperarla salvo borrar
 * los datos de la app.
 *
 * Pasaba al girar el móvil. La bola guarda su sitio en píxeles absolutos, y al
 * girar la pantalla cambia de forma sin que nadie recalcule nada: una bola
 * pegada al borde derecho en vertical (x ≈ 1050 de 1080) se queda en x ≈ 1050 de
 * 2400 en horizontal, o sea **en mitad de la pantalla**; y una pegada abajo en
 * horizontal se va por debajo del borde en vertical y no vuelve.
 *
 * Por eso la geometría vive aquí, sin Android: es lo único de la bola que se
 * puede comprobar sin un móvil girando en la mano.
 */
object BallState {

    /**
     * Cuánto tiene que asomar para darla por recuperable, en fracción de su
     * lado.
     *
     * No es «que se vea entera»: la bola **se esconde a medias a propósito** al
     * pegarse a un borde, que es lo que la quita de en medio sin perderla. Con
     * un cuarto asomando todavía se agarra con el dedo.
     */
    private const val MINIMO_VISIBLE = 0.25

    fun recoveryFor(windowExists: Boolean, contentVisible: Boolean): BallRecovery = when {
        !windowExists -> BallRecovery.CREAR
        !contentVisible -> BallRecovery.VOLVER_A_ENSENAR
        else -> BallRecovery.NADA
    }

    /** ¿Está pegada al borde izquierdo? Se mira por su centro. */
    fun enLaIzquierda(x: Int, lado: Int, ancho: Int): Boolean = x + lado / 2 < ancho / 2

    /**
     * La x de la bola pegada a un borde, **retraída a medias**.
     *
     * Es la postura de reposo: medio cuerpo fuera de la pantalla, como en el
     * PixPin de escritorio. La mitad que asoma es lo que se toca y lo que se
     * arrastra.
     */
    fun pegadaAlBorde(izquierda: Boolean, lado: Int, ancho: Int): Int =
        if (izquierda) -lado / 2 else ancho - lado / 2

    /** Nunca fuera del alcance vertical del pulgar. */
    fun alturaSegura(y: Int, lado: Int, alto: Int): Int =
        y.coerceIn(0, (alto - lado).coerceAtLeast(0))

    /** ¿Asoma lo bastante como para poder cogerla? */
    fun seVe(x: Int, y: Int, lado: Int, ancho: Int, alto: Int): Boolean {
        if (lado <= 0 || ancho <= 0 || alto <= 0) return false
        val minimo = (lado * MINIMO_VISIBLE).roundToInt().coerceAtLeast(1)
        val dentroX = minOf(x + lado, ancho) - maxOf(x, 0)
        val dentroY = minOf(y + lado, alto) - maxOf(y, 0)
        return dentroX >= minimo && dentroY >= minimo
    }

    /**
     * La devuelve a la pantalla si se había ido, y la deja donde está si no.
     *
     * Es la red de seguridad: se llama al arrancar el servicio y al pulsar
     * «Comenzar», así que **haya pasado lo que haya pasado, la bola vuelve**.
     */
    fun rescatar(x: Int, y: Int, lado: Int, ancho: Int, alto: Int): SitioDeLaBola {
        if (ancho <= 0 || alto <= 0) return SitioDeLaBola(x, y)
        if (seVe(x, y, lado, ancho, alto)) return SitioDeLaBola(x, y)
        return SitioDeLaBola(
            pegadaAlBorde(enLaIzquierda(x, lado, ancho), lado, ancho),
            alturaSegura(y, lado, alto)
        )
    }

    /**
     * Recoloca la bola cuando la pantalla cambia de forma: al girar el móvil.
     *
     * **Se conserva el borde y la altura relativa, no los píxeles.** Es lo que
     * uno espera: la bola estaba a la derecha y a media altura, y sigue a la
     * derecha y a media altura. Guardando los píxeles tal cual, girar la
     * mandaba al centro o fuera del borde de abajo, que es justo el fallo que
     * la hacía desaparecer.
     */
    fun alCambiarLaPantalla(
        x: Int, y: Int, lado: Int,
        anchoViejo: Int, altoViejo: Int,
        anchoNuevo: Int, altoNuevo: Int
    ): SitioDeLaBola {
        if (anchoViejo <= 0 || altoViejo <= 0 || anchoNuevo <= 0 || altoNuevo <= 0) {
            return SitioDeLaBola(x, y)
        }
        val izquierda = enLaIzquierda(x, lado, anchoViejo)
        // La altura va por fracción del centro de la bola: así una bola a un
        // tercio sigue a un tercio, mida lo que mida la pantalla nueva.
        val fraccion = ((y + lado / 2.0) / altoViejo).coerceIn(0.0, 1.0)
        return SitioDeLaBola(
            pegadaAlBorde(izquierda, lado, anchoNuevo),
            alturaSegura((fraccion * altoNuevo).roundToInt() - lado / 2, lado, altoNuevo)
        )
    }

    /**
     * Hasta dónde se le deja llegar mientras el dedo la arrastra.
     *
     * Sin esto se puede sacar de la pantalla entera de un manotazo —«se va
     * volando»—, y aunque al soltar volviera al borde, por el camino
     * desaparece. Se le deja pasarse del borde, que es lo que hace falta para
     * poder soltarla escondida, pero sin perderla de vista.
     */
    fun mientrasSeArrastra(x: Int, y: Int, lado: Int, ancho: Int, alto: Int): SitioDeLaBola {
        if (ancho <= 0 || alto <= 0) return SitioDeLaBola(x, y)
        val margen = lado * 3 / 4
        return SitioDeLaBola(
            x.coerceIn(-margen, (ancho - lado / 4).coerceAtLeast(-margen)),
            alturaSegura(y, lado, alto)
        )
    }
}
