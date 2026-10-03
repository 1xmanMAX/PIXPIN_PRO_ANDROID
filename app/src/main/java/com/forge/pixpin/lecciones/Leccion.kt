package com.forge.pixpin.lecciones

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * **Una lección aprendida** (3-oct-2026).
 *
 * Los campos salen del estudio que se hizo antes de escribir esto (ver `docs/lecciones.md`): las
 * cuatro preguntas de la revisión después de la acción del ejército de EE. UU. (qué pasó, por qué,
 * qué haré distinto), la «prueba de que no se repite» de la base de lecciones de la NASA —aquí,
 * [repeticiones]—, y lo que dicen los estudios de por qué estas bases fracasan: **nadie las
 * rellena porque cuesta y nadie las consulta porque no salen cuando hacen falta**. Por eso solo
 * [titulo] es obligatorio, todo lo demás se propone solo ([Etiquetador]) y se acepta con un toque.
 *
 * **Dónde vive.** Cada lección es un archivo `guardados/lecciones/<id>.leccion` con este JSON, y
 * un mensaje del chat lo señala (ver `LeccionesStore`). Así viaja con la sincronización, se borra
 * con su lápida, entra en las copias y en el `.pixpin` sin tocar el protocolo —ni la aplicación
 * de Windows, que lo ve como un adjunto más—, y una lección de un proyecto sale en su chat.
 *
 * Los textos que eligen entre opciones ([tipo], [area]) son cadenas y no enums: un enum que no
 * conoce una versión vieja hace fallar la lectura entera del archivo.
 */
@Serializable
data class Leccion(
    val id: String,
    val creada: Long,
    val tocada: Long = creada,
    /** **Lo que aprendí**, en una frase. Lo único obligatorio. */
    val titulo: String,
    /** Qué pasó: el suceso que la originó. */
    val quePaso: String = "",
    /** Por qué pasó: la causa, como pista y no como verdad única. */
    val porQue: String = "",
    /** Qué haré la próxima vez, mejor en forma «si…, entonces…». Es lo que va a la lista de comprobación. */
    val proxima: String = "",
    /** [TIPO_LECCION], [TIPO_ERROR] (error que no repetir) o [TIPO_ACIERTO] (algo que salió bien y vale repetir). */
    val tipo: String = TIPO_LECCION,
    /** El área grande: Trabajo, Construcción, Estudio, Vida diaria o una propia. Vacía = sin área. */
    val area: String = "",
    /** 1 leve, 2 importante, 3 grave. Ordena lo que sale primero. */
    val gravedad: Int = 1,
    /** Las etiquetas que puso uno, o que aceptó de las propuestas. */
    val etiquetas: List<String> = emptyList(),
    /** Las que puso el [Etiquetador] y siguen ahí. Se enseñan con ✨. */
    val etiquetasAuto: List<String> = emptyList(),
    /** Las automáticas que uno quitó: no vuelven a salir aunque el texto las siga pidiendo. */
    val quitadas: List<String> = emptyList(),
    /** **Palabras de referencia** ocultas: encuentran la lección aunque no salgan en su texto. */
    val referencias: List<String> = emptyList(),
    /** Las causas marcadas con un toque. Ver [CAUSAS]. */
    val causas: List<String> = emptyList(),
    /** **Cada vez que volvió a pasar.** Es la medida de si se aprendió o solo se apuntó. */
    val repeticiones: List<Long> = emptyList(),
    /** El mensaje del chat del que salió, si salió de uno. */
    val deMensaje: String? = null,
    /** El repaso espaciado: en qué caja va (0..[INTERVALOS].lastIndex) y cuándo toca. */
    val caja: Int = 0,
    val repasar: Long = creada + DIA,
    /** Si su [proxima] sale en la lista de comprobación. */
    val enLista: Boolean = true,
    /**
     * **Fotos y audios** (4-oct-2026): los ids de los mensajes del chat que los llevan. Cada uno
     * es una foto o una nota de voz corriente, en el mismo chat y respondiendo a la lección: así
     * viajan con la sincronización y se ven en Windows sin tocar nada. Ver [LeccionesStore.guardar].
     */
    val adjuntos: List<String> = emptyList()
) {
    val todasLasEtiquetas: List<String> get() = (etiquetas + etiquetasAuto).distinct()
    val vecesQuePaso: Int get() = 1 + repeticiones.size
    val esError: Boolean get() = tipo == TIPO_ERROR

    companion object {
        const val TIPO_LECCION = "leccion"
        const val TIPO_ERROR = "error"
        const val TIPO_ACIERTO = "acierto"

        const val DIA = 24L * 60 * 60 * 1000

        /** Días hasta el siguiente repaso, por caja: lo que dicen los estudios del repaso espaciado. */
        val INTERVALOS = intArrayOf(1, 3, 7, 14, 30, 90, 180)

        /** Las áreas con las que se empieza (decisión del usuario, 2-oct-2026). */
        val AREAS = listOf("Trabajo", "Construcción", "Estudio", "Vida diaria")

        /** Las causas de un toque: las de siempre en los cuadernos de errores y en los informes de incidentes. */
        val CAUSAS = listOf(
            "Prisa", "No revisé", "Comunicación", "No sabía", "Supuse algo",
            "Herramienta", "Planificación", "Cansancio", "Distracción"
        )

        val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

        fun leer(texto: String): Leccion? = runCatching { JSON.decodeFromString(serializer(), texto) }.getOrNull()
        fun escribir(l: Leccion): String = JSON.encodeToString(serializer(), l)
    }
}

/** **El repaso espaciado**: acordarse sube de caja y aleja el siguiente; olvidarse vuelve a empezar. */
object Repaso {
    fun toca(l: Leccion, ahora: Long): Boolean = l.repasar <= ahora

    fun recordada(l: Leccion, ahora: Long): Leccion {
        val caja = (l.caja + 1).coerceAtMost(Leccion.INTERVALOS.lastIndex)
        return l.copy(caja = caja, repasar = ahora + Leccion.INTERVALOS[caja] * Leccion.DIA)
    }

    fun olvidada(l: Leccion, ahora: Long): Leccion = l.copy(caja = 0, repasar = ahora + Leccion.DIA)

    /**
     * **Me volvió a pasar**: se apunta la fecha, sube la gravedad si se repite mucho y el repaso
     * vuelve a empezar —si pasó otra vez, no estaba aprendida—.
     */
    fun repetida(l: Leccion, ahora: Long): Leccion {
        val veces = l.repeticiones + ahora
        val gravedad = if (veces.size >= 2) maxOf(l.gravedad, 3) else maxOf(l.gravedad, 2)
        return l.copy(repeticiones = veces, gravedad = gravedad, caja = 0, repasar = ahora + Leccion.DIA, tocada = ahora)
    }

    /** Las que tocan hoy, primero las graves y las que más se repiten. */
    fun deHoy(todas: List<Leccion>, ahora: Long, cuantas: Int = 5): List<Leccion> =
        todas.filter { toca(it, ahora) }
            .sortedWith(compareByDescending<Leccion> { it.gravedad }.thenByDescending { it.repeticiones.size }.thenBy { it.repasar })
            .take(cuantas)
}
