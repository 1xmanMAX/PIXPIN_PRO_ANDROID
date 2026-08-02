package com.forge.pixpin.croquis

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.tan
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

    /** Ángulo de la dirección a→b respecto a la horizontal, en grados. */
    fun gradosDe(a: P, b: P): Double =
        Math.toDegrees(atan2(b.y - a.y, b.x - a.x))

    /** Punto a [longitudM] de [desde] en la dirección [grados]. */
    fun desdePolar(desde: P, longitudM: Double, grados: Double): P {
        val r = Math.toRadians(grados)
        return P(desde.x + longitudM * cos(r), desde.y + longitudM * sin(r))
    }

    /**
     * Pendiente en tanto por ciento, que es como se habla de un desnivel en
     * obra: el 100 % son 45°, no la vertical.
     */
    fun gradosAPorcentaje(grados: Double): Double = tan(Math.toRadians(grados)) * 100.0

    fun porcentajeAGrados(porcentaje: Double): Double =
        Math.toDegrees(atan(porcentaje / 100.0))

    /**
     * Dónde se cruzan las **rectas infinitas** que pasan por cada par de
     * puntos, o null si son paralelas.
     *
     * Infinitas y no segmentos a propósito: extender necesita el corte de algo
     * que todavía no llega, y recortar decide aparte si el corte le sirve.
     */
    fun corteDeRectas(a1: P, a2: P, b1: P, b2: P): P? {
        val d1x = a2.x - a1.x
        val d1y = a2.y - a1.y
        val d2x = b2.x - b1.x
        val d2y = b2.y - b1.y
        val den = d1x * d2y - d1y * d2x
        if (abs(den) < 1e-12) return null
        val t = ((b1.x - a1.x) * d2y - (b1.y - a1.y) * d2x) / den
        return P(a1.x + d1x * t, a1.y + d1y * t)
    }

    /**
     * Alarga [linea] hasta la recta de [contra], moviendo **el extremo más
     * cercano** al punto de corte y dejando el otro donde estaba.
     */
    fun extender(linea: Entidad.Linea, contra: Entidad.Linea): Entidad.Linea? {
        val corte = corteDeRectas(linea.a, linea.b, contra.a, contra.b) ?: return null
        return if (distancia(linea.a, corte) <= distancia(linea.b, corte)) {
            linea.copy(a = corte)
        } else {
            linea.copy(b = corte)
        }
    }

    /**
     * Corta [linea] por donde la cruza [cuchilla] y **tira el trozo del lado
     * donde se tocó**, que es como funciona el recorte de un CAD: se señala lo
     * que sobra, no lo que se queda.
     *
     * Devuelve null si el corte no cae dentro del segmento: ahí no hay nada que
     * recortar y alterarlo sería una sorpresa desagradable.
     */
    fun recortar(linea: Entidad.Linea, cuchilla: Entidad.Linea, tocado: P): Entidad.Linea? {
        val corte = corteDeRectas(linea.a, linea.b, cuchilla.a, cuchilla.b) ?: return null
        val tCorte = parametroEn(linea, corte) ?: return null
        if (tCorte <= 1e-9 || tCorte >= 1.0 - 1e-9) return null
        val tTocado = parametroEn(linea, tocado) ?: return null
        return if (tTocado > tCorte) linea.copy(b = corte) else linea.copy(a = corte)
    }

    /**
     * Distancia de [p] al **segmento**, no a su recta infinita.
     *
     * La diferencia importa al elegir con el dedo: tocando más allá del final
     * de una línea, la perpendicular a su recta puede ser diminuta y la línea
     * quedar a metros. Lo que se siente es la distancia al trozo dibujado.
     */
    fun distanciaA(linea: Entidad.Linea, p: P): Double {
        val t = (parametroEn(linea, p) ?: return distancia(linea.a, p)).coerceIn(0.0, 1.0)
        val sobre = P(
            linea.a.x + (linea.b.x - linea.a.x) * t,
            linea.a.y + (linea.b.y - linea.a.y) * t
        )
        return distancia(sobre, p)
    }

    /**
     * Índice de la línea más cercana a [p] dentro de [toleranciaM], o null si
     * no hay ninguna a tiro. Solo mira líneas: recortar y extender no tienen
     * sentido sobre un círculo o un texto.
     */
    fun lineaMasCercana(croquis: Croquis, p: P, toleranciaM: Double): Int? {
        var mejor: Int? = null
        var mejorD = Double.MAX_VALUE
        croquis.entidades.forEachIndexed { i, e ->
            if (e is Entidad.Linea) {
                val d = distanciaA(e, p)
                if (d <= toleranciaM && d < mejorD) {
                    mejorD = d
                    mejor = i
                }
            }
        }
        return mejor
    }

    /** Dónde cae [p] sobre la línea, con 0 en su principio y 1 en su final. */
    private fun parametroEn(linea: Entidad.Linea, p: P): Double? {
        val dx = linea.b.x - linea.a.x
        val dy = linea.b.y - linea.a.y
        val largo2 = dx * dx + dy * dy
        if (largo2 < 1e-18) return null
        return ((p.x - linea.a.x) * dx + (p.y - linea.a.y) * dy) / largo2
    }

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

    /**
     * El rectángulo del mundo que abarca todo lo dibujado, o null si no hay
     * nada. Es lo que permite encajar el croquis en una hoja al exportar.
     */
    fun extension(croquis: Croquis): Caja? {
        var minX = Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        var hayAlgo = false

        fun meter(x: Double, y: Double) {
            hayAlgo = true
            if (x < minX) minX = x
            if (y < minY) minY = y
            if (x > maxX) maxX = x
            if (y > maxY) maxY = y
        }

        for (e in croquis.entidades) when (e) {
            is Entidad.Linea -> { meter(e.a.x, e.a.y); meter(e.b.x, e.b.y) }
            is Entidad.Polilinea -> e.puntos.forEach { meter(it.x, it.y) }
            is Entidad.Rect -> { meter(e.a.x, e.a.y); meter(e.b.x, e.b.y) }
            is Entidad.Circulo -> {
                meter(e.centro.x - e.radio, e.centro.y - e.radio)
                meter(e.centro.x + e.radio, e.centro.y + e.radio)
            }
            // El texto cuenta solo por su anclaje: medir su ancho real exige
            // Android, y aquí basta para no dejarlo fuera de la hoja.
            is Entidad.Texto -> meter(e.en.x, e.en.y)
            is Entidad.Cota -> { meter(e.a.x, e.a.y); meter(e.b.x, e.b.y) }
        }

        return if (hayAlgo) Caja(P(minX, minY), P(maxX, maxY)) else null
    }

    /**
     * La vista que mete el croquis entero dentro de una hoja de [anchoPx] por
     * [altoPx], dejando [margenPx] alrededor.
     *
     * Manda el lado que se queda corto: encajar por el otro sacaría el dibujo
     * de la hoja. Devuelve null si no hay nada que encajar.
     */
    fun vistaQueEncaja(croquis: Croquis, anchoPx: Int, altoPx: Int, margenPx: Int): Vista? =
        vistaParaCaja(extension(croquis), anchoPx, altoPx, margenPx)

    /** Igual que [vistaQueEncaja] pero para un rectángulo cualquiera del mundo. */
    fun vistaParaCaja(caja: Caja?, anchoPx: Int, altoPx: Int, margenPx: Int): Vista? {
        if (caja == null) return null
        val utilAncho = (anchoPx - 2 * margenPx).coerceAtLeast(1)
        val utilAlto = (altoPx - 2 * margenPx).coerceAtLeast(1)
        // Un croquis puede ser una sola línea horizontal: sin alto no hay
        // división que valga, así que ese lado no restringe.
        val porAncho = if (caja.ancho > 0) utilAncho / caja.ancho else Double.MAX_VALUE
        val porAlto = if (caja.alto > 0) utilAlto / caja.alto else Double.MAX_VALUE
        val escala = minOf(porAncho, porAlto)
        if (!escala.isFinite() || escala <= 0.0) return null
        return Vista(
            centro = P((caja.min.x + caja.max.x) / 2, (caja.min.y + caja.max.y) / 2),
            pixelsPorMetro = escala
        )
    }

    /** Proporción de un A4: 595 × 842 puntos PostScript. */
    private const val A4_CORTO = 595.0
    private const val A4_LARGO = 842.0

    /** Aire que se deja alrededor del dibujo dentro de la hoja. */
    private const val AIRE = 0.10

    /**
     * La hoja A4 que se va a exportar, en coordenadas del mundo.
     *
     * Se dibuja en el editor para que **lo que se ve encuadrado sea lo que sale
     * en el PDF**. Se orienta sola: apaisada si el dibujo es más ancho que alto.
     * Con el croquis vacío se centra en la vista, para que haya hoja sobre la
     * que empezar a dibujar.
     */
    fun hojaA4(croquis: Croquis, vista: Vista, anchoPx: Int, altoPx: Int): Caja? {
        val contenido = extension(croquis)
        val centro: P
        var ancho: Double
        var alto: Double

        if (contenido == null) {
            centro = vista.centro
            // Una hoja que ocupe más o menos lo que se está mirando.
            ancho = anchoPx / vista.pixelsPorMetro * 0.8
            alto = altoPx / vista.pixelsPorMetro * 0.8
        } else {
            centro = P(
                (contenido.min.x + contenido.max.x) / 2,
                (contenido.min.y + contenido.max.y) / 2
            )
            ancho = contenido.ancho * (1 + 2 * AIRE)
            alto = contenido.alto * (1 + 2 * AIRE)
        }
        if (!ancho.isFinite() || !alto.isFinite()) return null

        // Un dibujo puede no tener alto —una línea horizontal sola—: hay que
        // darle algo, o la proporción sería una división por cero.
        val minimo = maxOf(ancho, alto, 1e-9) * 0.01
        ancho = maxOf(ancho, minimo)
        alto = maxOf(alto, minimo)

        val proporcion = if (ancho >= alto) A4_LARGO / A4_CORTO else A4_CORTO / A4_LARGO
        // Se crece por el lado que falte; nunca se recorta, o el dibujo se
        // saldría de la hoja que dice contenerlo.
        if (ancho / alto > proporcion) alto = ancho / proporcion else ancho = alto * proporcion

        return Caja(
            P(centro.x - ancho / 2, centro.y - alto / 2),
            P(centro.x + ancho / 2, centro.y + alto / 2)
        )
    }

    /** De la pantalla al mundo: la inversa exacta de [aPantalla]. */
    fun aMundo(px: Px, vista: Vista, anchoPx: Int, altoPx: Int): P {
        val dx = (px.x - anchoPx / 2.0) / vista.pixelsPorMetro
        val dy = (altoPx / 2.0 - px.y) / vista.pixelsPorMetro
        return P(vista.centro.x + dx, vista.centro.y + dy)
    }
}
