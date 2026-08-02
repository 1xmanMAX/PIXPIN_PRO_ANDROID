package com.forge.pixpin.croquis

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.round
import kotlin.math.sin
import java.util.Locale

/**
 * Geometría pura del croquis: sin Android, comprobable en JVM.
 *
 * Mismo patrón que [com.forge.pixpin.annotate.AnnotationGeometry] y
 * `TableData`: lo que decide una medida se prueba sin dispositivo, porque
 * equivocarse en una cota es peor que equivocarse en un color.
 */
object CroquisGeometria {

    /**
     * Metros que vale cada píxel de la imagen de fondo.
     *
     * Se traza una línea sobre algo de medida conocida —[a] y [b] en píxeles de
     * la imagen— y se teclea cuánto mide de verdad en [longitudRealM].
     */
    /**
     * Devuelve null si la calibración no puede ser cierta: una longitud que no
     * sea positiva, o una línea sin arrastrar. Sin escala válida no se entra en
     * modo medir — más vale no medir que medir mal.
     */
    fun calibrar(a: P, b: P, longitudRealM: Double): Double? {
        if (longitudRealM <= 0.0 || !longitudRealM.isFinite()) return null
        val distanciaPx = distancia(a, b)
        if (distanciaPx <= 0.0) return null
        return longitudRealM / distanciaPx
    }

    /** Distancia euclídea entre dos puntos. */
    fun distancia(a: P, b: P): Double = hypot(b.x - a.x, b.y - a.y)

    /**
     * Imanta [punto] al extremo más cercano que esté a menos de [toleranciaM],
     * o lo deja donde está si no hay ninguno a tiro.
     *
     * Es lo que hace que dos líneas **conecten** en vez de quedar «casi»
     * juntas. La tolerancia llega en metros del mundo: quien llama convierte
     * los dp de pantalla dividiendo por la escala, para que imantar sea igual
     * de fácil con el dibujo grande que diminuto.
     */
    fun imantar(punto: P, extremos: List<P>, toleranciaM: Double): P {
        var mejor: P? = null
        var mejorDistancia = Double.MAX_VALUE
        for (e in extremos) {
            val d = distancia(punto, e)
            if (d <= toleranciaM && d < mejorDistancia) {
                mejorDistancia = d
                mejor = e
            }
        }
        return mejor ?: punto
    }

    /**
     * Endereza la dirección [desde]→[hasta] al múltiplo de 45° más próximo,
     * si está a menos de [toleranciaGrados]. **Conserva la longitud**: orto
     * corrige hacia dónde va la línea, no cuánto mide.
     */
    fun orto(desde: P, hasta: P, toleranciaGrados: Double): P {
        val dx = hasta.x - desde.x
        val dy = hasta.y - desde.y
        val longitud = hypot(dx, dy)
        if (longitud == 0.0) return hasta

        val angulo = atan2(dy, dx)
        val paso = PI / 4
        val enderezado = round(angulo / paso) * paso
        // Por construcción de round, la diferencia ya viene en (-22,5°, 22,5°]:
        // no hace falta normalizar la vuelta completa.
        if (abs(Math.toDegrees(angulo - enderezado)) > toleranciaGrados) return hasta

        return P(desde.x + longitud * cos(enderezado), desde.y + longitud * sin(enderezado))
    }

    /**
     * Recoloca [hasta] para que la línea mida exactamente [longitudM],
     * **conservando la dirección**. Es lo que convierte el arrastre del dedo en
     * una medida exacta: se apunta con el dedo y se dicta el número.
     *
     * Devuelve null si no hay dirección que conservar o la longitud no es
     * positiva.
     */
    fun conLongitud(desde: P, hasta: P, longitudM: Double): P? {
        if (longitudM <= 0.0 || !longitudM.isFinite()) return null
        val actual = distancia(desde, hasta)
        if (actual <= 0.0) return null
        val factor = longitudM / actual
        return P(desde.x + (hasta.x - desde.x) * factor, desde.y + (hasta.y - desde.y) * factor)
    }

    /**
     * Una medida escrita para leer: «4,20 m».
     *
     * El idioma llega por parámetro en vez de leerse del sistema para que la
     * cifra sea comprobable sin depender de la configuración de la máquina.
     */
    fun formatear(metros: Double, decimales: Int, locale: Locale = Locale.getDefault()): String =
        String.format(locale, "%.${decimales.coerceIn(0, 6)}f m", metros)

    /**
     * Del mundo a la pantalla.
     *
     * **La resta del centro va en `Double`, y solo el resultado pasa a
     * `Float`.** Convertir primero la coordenada absoluta y restar después
     * perdería los milímetros de un plano en UTM, que es justo lo que esta
     * herramienta promete no perder.
     *
     * La Y se invierte: en CAD crece hacia arriba, en pantalla hacia abajo.
     */
    fun aPantalla(p: P, vista: Vista, anchoPx: Int, altoPx: Int): Px {
        val dx = (p.x - vista.centro.x) * vista.pixelsPorMetro
        val dy = (p.y - vista.centro.y) * vista.pixelsPorMetro
        return Px((anchoPx / 2.0 + dx).toFloat(), (altoPx / 2.0 - dy).toFloat())
    }

    /** De la pantalla al mundo: la inversa exacta de [aPantalla]. */
    fun aMundo(px: Px, vista: Vista, anchoPx: Int, altoPx: Int): P {
        val dx = (px.x - anchoPx / 2.0) / vista.pixelsPorMetro
        val dy = (altoPx / 2.0 - px.y) / vista.pixelsPorMetro
        return P(vista.centro.x + dx, vista.centro.y + dy)
    }
}
