package com.forge.pixpin.croquis

import kotlinx.serialization.Serializable

/**
 * Punto de dos coordenadas en doble precisión.
 *
 * En el croquis son **metros del mundo real**; en la calibración, píxeles de la
 * imagen de fondo. `Double` y no `Float` porque esta es una herramienta de
 * medir: con coordenadas del orden del millón —las UTM de un plano de
 * topografía llegan a 8.240.708— un `Float` de 32 bits guarda siete cifras
 * significativas y pierde alrededor de un metro. Un dibujo que se ve bien y
 * mide mal es el peor fallo que puede tener esto.
 */
@Serializable
data class P(val x: Double, val y: Double)

/** Punto en píxeles de la pantalla. `Float` porque es lo que come el `Canvas`. */
data class Px(val x: Float, val y: Float)

/** Rectángulo del mundo que abarca algo: para encajar el croquis en una hoja. */
data class Caja(val min: P, val max: P) {
    val ancho: Double get() = max.x - min.x
    val alto: Double get() = max.y - min.y
}

/**
 * Qué trozo del mundo se está mirando: dónde cae el centro de la pantalla y
 * cuánto se amplía. La hoja es infinita, así que no hay más estado que este.
 */
data class Vista(val centro: P, val pixelsPorMetro: Double)

/**
 * Lo que se puede dibujar, en coordenadas del mundo.
 *
 * Interfaz **sellada**: kotlinx resuelve solo el polimorfismo cerrado, sin
 * registrar subtipos a mano. La objeción anotada en `PinModels` iba contra el
 * polimorfismo abierto, que es otra cosa.
 */
/**
 * Cómo se pinta una entidad.
 *
 * Va en una clase aparte y con valores por defecto para que los croquis ya
 * guardados en disco sigan leyéndose: un JSON sin `estilo` da el estilo normal.
 * [grosor] es un multiplicador sobre el trazo base, no un ancho absoluto — así
 * una línea gruesa lo sigue siendo con el dibujo diminuto o a pantalla completa.
 */
@Serializable
data class Estilo(val grosor: Float = 1f, val colorArgb: Int = 0) {
    /** El 0 significa «el color de la tinta», sea cual sea el fondo. */
    val usaTintaPorDefecto: Boolean get() = colorArgb == 0
}

@Serializable
sealed interface Entidad {

    val estilo: Estilo

    @Serializable
    data class Linea(val a: P, val b: P, override val estilo: Estilo = Estilo()) : Entidad

    @Serializable
    data class Polilinea(
        val puntos: List<P>,
        val cerrada: Boolean = false,
        override val estilo: Estilo = Estilo()
    ) : Entidad

    @Serializable
    data class Rect(val a: P, val b: P, override val estilo: Estilo = Estilo()) : Entidad

    @Serializable
    data class Circulo(
        val centro: P,
        val radio: Double,
        override val estilo: Estilo = Estilo()
    ) : Entidad

    @Serializable
    data class Texto(
        val en: P,
        val texto: String,
        val alturaM: Double,
        override val estilo: Estilo = Estilo()
    ) : Entidad

    /**
     * Cota entre dos puntos.
     *
     * **No guarda su cifra: la calcula.** Un número escrito a mano sobrevive a
     * que muevas el extremo y se queda mintiendo; uno calculado, no.
     * [desplazamiento] es cuánto se separa la línea de cota de la que mide, en
     * metros, con signo para elegir el lado.
     */
    @Serializable
    data class Cota(
        val a: P,
        val b: P,
        val desplazamiento: Double = 0.0,
        override val estilo: Estilo = Estilo()
    ) : Entidad {
        fun medida(): Double = CroquisGeometria.distancia(a, b)
    }
}

/**
 * La imagen de fondo sobre la que se mide: una captura de pantalla de un plano.
 *
 * Una captura es ortogonal por definición, así que no hay perspectiva que
 * corregir y la calibración es exacta, no aproximada.
 */
@Serializable
data class Fondo(
    val imagenPath: String,
    /** Dónde cae la esquina superior izquierda de la imagen, en metros. */
    val origen: P = P(0.0, 0.0),
    /** Lo que sale de calibrar: metros de mundo por píxel de la imagen. */
    val metrosPorPixel: Double
)

/** Un croquis completo: lo dibujado, con qué escala, y sobre qué. */
@Serializable
data class Croquis(
    val entidades: List<Entidad> = emptyList(),
    val fondo: Fondo? = null,
    val decimales: Int = 2
) {
    /** Todos los extremos a los que se puede imantar un punto nuevo. */
    fun extremos(): List<P> = entidades.flatMap { e ->
        when (e) {
            is Entidad.Linea -> listOf(e.a, e.b)
            is Entidad.Polilinea -> e.puntos
            is Entidad.Rect -> listOf(e.a, P(e.b.x, e.a.y), e.b, P(e.a.x, e.b.y))
            is Entidad.Circulo -> listOf(e.centro)
            is Entidad.Texto -> listOf(e.en)
            is Entidad.Cota -> listOf(e.a, e.b)
        }
    }
}
