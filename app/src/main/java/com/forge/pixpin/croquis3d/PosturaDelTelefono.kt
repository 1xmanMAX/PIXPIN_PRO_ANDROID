package com.forge.pixpin.croquis3d

import com.forge.pixpin.motor.Pt3
import kotlin.math.asin
import kotlin.math.atan2

/**
 * **Cómo está puesto el teléfono, en los ángulos que entiende la cámara del croquis.**
 *
 * Aparte de la pantalla y aparte del sensor a propósito: esto es trigonometría y se
 * comprueba sin dispositivo. Lo que llega de Android es una matriz de giro —la que saca
 * `SensorManager.getRotationMatrix` del vector de rotación— y lo que sale son los tres
 * ángulos de [Camara3D]. En medio no hay nada de Android.
 *
 * ## Los dos sistemas
 *
 * Android usa el del mundo real: `x` al este, `y` al norte, `z` hacia arriba. El croquis usa
 * `x` a la derecha, `y` hacia dentro, `z` hacia arriba. Son el mismo sistema con otros
 * nombres —los dos con la `z` arriba y los dos a derechas—, así que **no hay nada que
 * convertir**: el norte del mundo es el «hacia dentro» del croquis, y girarse hacia el este
 * es girarse hacia la `x`. Lo que uno dibujó mirando a un sitio se queda en ese sitio.
 *
 * ## Y de la matriz salen los tres, no dos
 *
 * A dónde apunta la cámara del teléfono da el giro y la inclinación. El tercero —el ladeo—
 * hace falta igual: **el teléfono se inclina de lado**, y sin llevarlo el croquis se
 * quedaría con el horizonte clavado mientras la pantalla se tuerce, que es exactamente la
 * sensación de que lo dibujado «flota» en vez de estar puesto en el sitio.
 */
class PosturaDelTelefono(val giro: Double, val inclinacion: Double, val balanceo: Double) {

    companion object {

        /**
         * Los tres ángulos que salen de la matriz de giro de Android.
         *
         * [r] es la matriz de nueve, tal cual la devuelve `SensorManager`, **ya remapeada a
         * cómo está puesta la pantalla**: lleva un vector de las coordenadas del aparato a
         * las del mundo. De ella se sacan las tres direcciones que importan —hacia dónde
         * mira la cámara, la derecha de la pantalla y su arriba— y de esas tres, los
         * ángulos.
         *
         * La cámara de atrás mira por donde **sale** la pantalla al revés: en las
         * coordenadas del aparato eso es `-z`, y de ahí el signo.
         */
        fun deLaMatriz(r: FloatArray): PosturaDelTelefono? {
            if (r.size < 9) return null

            // Las columnas de la matriz son los ejes del aparato vistos desde el mundo.
            val adelante = Pt3(-r[2].toDouble(), -r[5].toDouble(), -r[8].toDouble())
            val derecha = Pt3(r[0].toDouble(), r[3].toDouble(), r[6].toDouble())
            if (largo(adelante) < 1e-6 || largo(derecha) < 1e-6) return null
            val mira = normalizado(adelante)

            // La inclinación de la cámara cuenta al revés que la `z`: mirar hacia abajo es
            // inclinación positiva. Ver [Camara3D.adelante].
            val inclinacion = asin((-mira.z).coerceIn(-1.0, 1.0))
            val giro = atan2(mira.x, mira.y)

            // **Y el ladeo, medido contra la horizontal de esa dirección.**
            //
            // Con la cámara sin ladear, la derecha de la pantalla es la horizontal
            // perpendicular a donde se mira. El ladeo es cuánto se ha girado la derecha de
            // verdad respecto de esa, alrededor del eje de la mirada. Mirando justo al
            // cenit o al nadir no hay horizontal que valga y no hay ladeo que medir: se
            // deja en cero, que es lo único que no pega un tirón al cruzar.
            val horizontal = Pt3(kotlin.math.cos(giro), -kotlin.math.sin(giro), 0.0)
            val vertical = producto(horizontal, mira)
            val balanceo =
                if (largo(vertical) < 1e-6) 0.0
                else atan2(escalar(derecha, normalizado(vertical)), escalar(derecha, horizontal))

            return PosturaDelTelefono(giro, inclinacion, balanceo)
        }
    }
}
