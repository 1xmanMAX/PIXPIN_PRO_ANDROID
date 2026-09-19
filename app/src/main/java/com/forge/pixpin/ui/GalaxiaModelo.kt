package com.forge.pixpin.ui

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * **El sistema solar de proyectos: lo que se guarda.** Ver [PantallaDeGalaxia].
 *
 * Cada proyecto es un **sol** en un plano infinito; alrededor giran sus **exoplanetas** —notas
 * y emojis que el usuario deja—, y entre soles puede haber **conexiones**. Aquí solo consta
 * dónde está cada cosa, lo grande que es cada sol y quién va con quién. Los proyectos no se
 * copian: un sol cuyo proyecto ya no existe no se pinta, y sus conexiones tampoco.
 *
 * Las coordenadas van en dp del mundo. Lo pidió el usuario (17-sep-2026).
 */
@Serializable
data class Galaxia(
    /** Dónde está cada sol, por el id del proyecto. El que no está va a su sitio de la espiral. */
    val posiciones: Map<String, PuntoDeGalaxia> = emptyMap(),
    /** Lo grande que es cada sol respecto de su tamaño normal (pellizcándolo). */
    val tamanos: Map<String, Float> = emptyMap(),
    /** Los exoplanetas: notas y emojis, sueltos o de un sol. */
    val notas: List<NotaDeGalaxia> = emptyList(),
    /** Las conexiones entre soles. */
    val enlaces: List<EnlaceDeGalaxia> = emptyList()
) {
    fun conPosicion(id: String, punto: PuntoDeGalaxia) = copy(posiciones = posiciones + (id to punto))

    fun conTamano(id: String, t: Float) =
        copy(tamanos = if (t == 1f) tamanos - id else tamanos + (id to t.coerceIn(TAMANO_MIN, TAMANO_MAX)))

    fun tamanoDe(id: String) = tamanos[id] ?: 1f

    fun conNota(nota: NotaDeGalaxia) =
        copy(notas = if (notas.any { it.id == nota.id }) notas.map { if (it.id == nota.id) nota else it } else notas + nota)

    /** Las notas y los emojis sí se quitan: son del mapa. Los proyectos, desde aquí, **nunca**. */
    fun sinNota(id: String) = copy(notas = notas.filterNot { it.id == id })

    fun conectados(a: String, b: String) = enlaces.any { it.une(a, b) }

    /** Conecta dos soles, o los desconecta si ya lo estaban. */
    fun alternarEnlace(a: String, b: String): Galaxia {
        if (a == b) return this
        return if (conectados(a, b)) copy(enlaces = enlaces.filterNot { it.une(a, b) })
        else copy(enlaces = enlaces + EnlaceDeGalaxia(minOf(a, b), maxOf(a, b)))
    }

    fun sinEnlacesDe(id: String) = copy(enlaces = enlaces.filterNot { it.a == id || it.b == id })

    /** Las conexiones cuyos dos soles siguen existiendo. */
    fun enlacesEntre(ids: Set<String>) = enlaces.filter { it.a in ids && it.b in ids }

    /** Todo lo que se ha movido, de una vez: los soles por id, y los exoplanetas por id de nota. */
    fun conSitios(soles: Map<String, PuntoDeGalaxia>, satelites: Map<String, PuntoDeGalaxia>) = copy(
        posiciones = posiciones + soles,
        notas = notas.map { n -> satelites[n.id]?.let { n.copy(x = it.x, y = it.y) } ?: n }
    )

    /**
     * Dónde se pinta cada proyecto de [ids] (en el orden de la lista): el sitio guardado, o el
     * suyo de la espiral según su puesto.
     */
    fun colocar(ids: List<String>): Map<String, PuntoDeGalaxia> =
        ids.withIndex().associate { (i, id) -> id to (posiciones[id] ?: enEspiral(i)) }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun leer(texto: String?): Galaxia =
            if (texto.isNullOrBlank()) Galaxia()
            else runCatching { json.decodeFromString(serializer(), texto) }.getOrDefault(Galaxia())

        fun escribir(g: Galaxia): String = json.encodeToString(serializer(), g)

        /** Lo que separa dos vueltas de la espiral, en dp. Mayor que un sol con su nombre. */
        const val PASO = 170f

        const val TAMANO_MIN = 0.5f
        const val TAMANO_MAX = 3f

        /**
         * **La espiral de Fermat con el ángulo áureo**, la de las pipas del girasol: cada punto
         * nuevo cae en el hueco que dejan los anteriores, así que por muchos proyectos que haya
         * nunca se pisan, y los primeros —los que están en marcha— quedan en el centro.
         */
        fun enEspiral(i: Int): PuntoDeGalaxia {
            if (i == 0) return PuntoDeGalaxia(0f, 0f)
            val r = PASO * sqrt(i.toFloat())
            val a = i * ANGULO_AUREO
            return PuntoDeGalaxia((r * cos(a)).toFloat(), (r * sin(a)).toFloat())
        }

        /** Dónde nace el exoplaneta número [k] de un sol de radio [radio] centrado en [centro]. */
        fun enOrbita(centro: PuntoDeGalaxia, radio: Float, k: Int): PuntoDeGalaxia {
            val r = radio + 70f + 26f * (k % 3)
            val a = k * ANGULO_AUREO - Math.PI / 2
            return PuntoDeGalaxia(centro.x + (r * cos(a)).toFloat(), centro.y + (r * sin(a)).toFloat())
        }

        const val ANGULO_AUREO = 2.399963229728653 // π(3 − √5)

        /** El diámetro normal del sol, en dp: crece con las hojas, pero poco, y con tope. */
        fun diametro(hojas: Int): Float = (64f + 7f * sqrt(hojas.toFloat())).coerceAtMost(112f)

        /** Hasta dónde llega el imán de un sol de radio [radio]: más cerca, un exoplaneta se engancha. */
        fun alcanceDelIman(radio: Float) = radio + 150f
    }
}

@Serializable
data class PuntoDeGalaxia(val x: Float, val y: Float)

@Serializable
data class NotaDeGalaxia(
    val id: String,
    val texto: String,
    val x: Float,
    val y: Float,
    /** Si está, es un emoji flotante y [texto] no cuenta. */
    val emoji: String? = null,
    /** El sol al que orbita, o null si va suelta. */
    val sol: String? = null,
    /** Cuál de [COLORES_DE_NOTA]. */
    val color: Int = 0
)

@Serializable
data class EnlaceDeGalaxia(val a: String, val b: String) {
    fun une(p: String, q: String) = (a == p && b == q) || (a == q && b == p)
}
