package com.forge.pixpin.croquis3d

import com.forge.pixpin.motor.Pt3
import kotlin.math.exp

/**
 * **Cuánto se ha movido el teléfono**, sacado de su acelerómetro. En metros.
 *
 * ## Lo que se puede y lo que no
 *
 * Integrar dos veces la aceleración da la posición, y en un sensor de teléfono eso se va
 * a metros de error en pocos segundos: el ruido y el sesgo se integran también, y crecen
 * con el cuadrado del tiempo. Es la razón por la que ARCore sigue la habitación con la
 * cámara y no con el acelerómetro. Pero **a corto plazo sí sirve**: un vaivén de medio
 * segundo —inclinarse hacia el croquis, dar un paso atrás— se lee bien, y es justo lo que
 * hace falta para que acercarse a mirar un detalle acerque de verdad.
 *
 * Así que esto es la versión honesta de la odometría inercial de bolsillo, con los dos
 * remedios clásicos contra la deriva:
 *
 * 1. **Velocidad con fuga.** La velocidad integrada se apaga sola con una constante de
 *    tiempo [TAU]: lo que se mueve se nota, y lo que no se mueve se para en vez de seguir
 *    deslizándose por un sesgo de una centésima de g.
 * 2. **Actualización de velocidad cero (ZUPT)**: si la aceleración lleva [QUIETO_SEGUNDOS]
 *    por debajo de [UMBRAL_QUIETO], el aparato está quieto, y se pone la velocidad a cero
 *    de golpe. Es lo que usan los navegadores inerciales de pie —Solin, Foxlin— para
 *    cancelar la deriva a cada apoyo.
 *
 * Los pasos largos van aparte, por el podómetro del aparato: ver
 * [Croquis3DControlador.darUnPaso]. Y el sentido y la escala de todo esto se cierran en el
 * controlador, que es quien sabe a cuántas unidades del croquis va un metro.
 *
 * Entra la aceleración lineal **ya en el sistema del mundo** (sin gravedad, x al este, y al
 * norte, z arriba) y sale el desplazamiento desde la muestra anterior. Sin Android.
 */
class InerciaDelTelefono {

    private var vx = 0.0
    private var vy = 0.0
    private var vz = 0.0
    private var ultimoNanos = 0L
    private var quietoDesdeNanos = 0L

    /** La velocidad que lleva ahora mismo, en m/s. Para las pruebas y para quien lo mire. */
    val velocidad: Pt3 get() = Pt3(vx, vy, vz)

    fun reiniciar() {
        vx = 0.0; vy = 0.0; vz = 0.0
        ultimoNanos = 0L
        quietoDesdeNanos = 0L
    }

    /** Se acaba de dar un paso: la velocidad de lo integrado no vale nada ya. */
    fun pasoDado() {
        vx = 0.0; vy = 0.0; vz = 0.0
    }

    /**
     * Una muestra más. Devuelve cuánto se ha movido desde la anterior, en metros, o null
     * con la primera muestra (que solo pone el reloj en hora) o con un salto de reloj.
     */
    fun muestra(ax: Double, ay: Double, az: Double, nanos: Long): Pt3? {
        if (ultimoNanos == 0L || nanos <= ultimoNanos) {
            ultimoNanos = nanos
            quietoDesdeNanos = nanos
            return null
        }
        val dt = ((nanos - ultimoNanos) / 1e9).coerceAtMost(0.1)
        ultimoNanos = nanos

        val modulo = kotlin.math.sqrt(ax * ax + ay * ay + az * az)
        if (modulo < UMBRAL_QUIETO) {
            if (quietoDesdeNanos == 0L) quietoDesdeNanos = nanos
            if ((nanos - quietoDesdeNanos) / 1e9 >= QUIETO_SEGUNDOS) {
                vx = 0.0; vy = 0.0; vz = 0.0
                return Pt3(0.0, 0.0, 0.0)
            }
        } else {
            quietoDesdeNanos = 0L
        }

        val fuga = exp(-dt / TAU)
        vx = (vx + ax * dt) * fuga
        vy = (vy + ay * dt) * fuga
        vz = (vz + az * dt) * fuga
        val v = kotlin.math.sqrt(vx * vx + vy * vy + vz * vz)
        if (v > VELOCIDAD_MAXIMA) {
            val k = VELOCIDAD_MAXIMA / v
            vx *= k; vy *= k; vz *= k
        }
        return Pt3(vx * dt, vy * dt, vz * dt)
    }

    companion object {
        /** Por debajo de esto (m/s²) el aparato se da por quieto. Es el ruido de un MEMS en reposo, con margen. */
        const val UMBRAL_QUIETO = 0.25

        /** Cuánto tiene que llevar quieto para cancelar la velocidad. */
        const val QUIETO_SEGUNDOS = 0.2

        /**
         * La constante de tiempo de la fuga de la velocidad, en segundos.
         *
         * Estaba en 0,6 y el croquis **no se quedaba clavado**: acercarse a él con el
         * teléfono en la mano dura un segundo largo, y a los 0,6 s la velocidad ya se había
         * apagado a un tercio, así que el recorrido se quedaba corto y, al frenar, el
         * impulso de parar se integraba entero contra una velocidad ya casi nula y el
         * croquis **volvía hacia atrás**. Con tres segundos, un vaivén de un segundo se
         * integra casi entero y lo que queda de deriva —un sesgo de una centésima de g da
         * tres centímetros por segundo— lo corta el ZUPT en cuanto el aparato se para.
         * Sigue siendo odometría a ciegas: clavarlo de verdad es cosa de una cámara (ARCore).
         */
        const val TAU = 3.0

        /** Nadie anda a más de esto con un teléfono en la mano. */
        const val VELOCIDAD_MAXIMA = 2.5
    }
}
