package com.forge.pixpin.guardados

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * **La cola de lo que hay que pasar a texto** (28-oct-2026, el usuario: «grabé la voz en una reunión
 * presencial y no se está transcribiendo. Asegúrate de que todo lo grabado se transcriba siempre, con
 * sus tiempos, para saltar rápido entre ellos»).
 *
 * ## Por qué no se pasaba a texto la reunión presencial
 *
 * La reunión se guarda **con la app fuera** (es lo natural: el móvil en la mesa, la pantalla apagada).
 * Al terminar, el servicio de la reunión guardaba la nota, lanzaba el reconocedor en un hilo suelto y
 * **se paraba**. Sin nada en primer plano, Android deja el proceso «en caché»: lo congela en segundos
 * (el congelador de apps de Android 11+) o lo mata para hacer sitio (Whisper con una hora de audio
 * ocupa cientos de MB). El hilo no acababa nunca, y como nada se apuntaba en el disco, **nadie lo
 * volvía a intentar**. Y si el modelo no estaba bajado y no había red, se marcaba «mal» y ahí se
 * quedaba.
 *
 * ## Lo que hay ahora
 *
 * - Lo que hay que pasar a texto **se apunta en el disco** (`pasar_a_texto.json`, [Pendiente]) antes
 *   de empezar y se quita al acabar. Si el proceso muere, se retoma al volver a abrir PixPin
 *   ([PasarATexto.vigilar]).
 * - Se trabaja **dentro de [PasarATextoService]**, en primer plano, con su notificación y su avance:
 *   cerrar la app no lo para.
 * - **Sin el modelo de voz** y sin red, se queda en la cola esperando ([Pendiente.faltaElModelo]) y
 *   se le dice al usuario cómo bajarlo; en cuanto hay red, sigue solo.
 * - Un fallo de verdad se reintenta hasta [MAXIMO_DE_INTENTOS] veces.
 *
 * Lo puro (qué toca hacer con un mensaje y cómo cambia la cola) está aquí y se prueba en
 * `ColaDeTextoTest`; lo de Android, en [PasarATexto].
 */
object ColaDeTexto {

    /** Lo que necesita un mensaje. */
    enum class Tarea {
        /** Una nota de voz (una clase, una reunión) sin texto: pasarla a texto con `[m:ss]`. */
        PASAR_A_TEXTO,

        /**
         * Una canción sin tiempos en su letra (o sin letra): oírla y ponerle los tiempos a la letra
         * pegada ([AlinearLetra]), o, sin letra, lo oído como letra.
         */
        TIEMPOS_DE_LA_LETRA,

        NADA
    }

    /** Las veces que se intenta algo que falla antes de dejarlo (se puede pedir a mano luego). */
    const val MAXIMO_DE_INTENTOS = 3

    /**
     * **Qué toca hacer con [m]**: nada si no es audio o el audio no está en este aparato ([hayAudio]);
     * a una canción, ponerle tiempos si su letra no los lleva; a una nota de voz, pasarla a texto si
     * no lo tiene (también si un intento anterior acabó mal y sin texto).
     */
    fun queToca(m: Mensaje?, hayAudio: Boolean): Tarea {
        if (m == null || m.clase != Clase.VOZ || m.ruta == null || !hayAudio) return Tarea.NADA
        if (m.esMusica) {
            val letra = m.transcripcion.orEmpty()
            return if (letra.isBlank() || !LetraDeCancion.leer(letra).conTiempos) Tarea.TIEMPOS_DE_LA_LETRA else Tarea.NADA
        }
        return if (m.transcripcion.isNullOrBlank()) Tarea.PASAR_A_TEXTO else Tarea.NADA
    }

    /**
     * **Si un mensaje recién guardado entra solo en la cola**: lo grabado en la app (una clase, una
     * reunión presencial, una nota de voz del micrófono —[grabadoAqui]—) siempre; lo importado
     * (un audio o un vídeo que se sube) no, que lo pidió así el usuario el 8-sep-2026 y se pasa con el
     * botón. Una canción entra siempre que le falten tiempos: es lo que se pidió para la música.
     */
    fun entraSola(m: Mensaje, hayAudio: Boolean, grabadoAqui: Boolean): Boolean {
        val tarea = queToca(m, hayAudio)
        if (tarea == Tarea.NADA) return false
        if (tarea == Tarea.TIEMPOS_DE_LA_LETRA) return true
        return grabadoAqui || m.grabacion == GrabarClase.CLASE || m.capturas != null
    }

    /** Uno de la cola: su mensaje, cuántas veces falló, si espera al modelo y en qué idioma se pidió. */
    data class Pendiente(
        val id: String,
        val intentos: Int = 0,
        val faltaElModelo: Boolean = false,
        val idioma: String? = null
    )

    /** Cómo acabó un intento. */
    enum class Fin { HECHO, SIN_MODELO, FALLO, NADA_QUE_HACER }

    /** Lo pone en la cola si no estaba; si estaba, se queda como está (con su idioma nuevo, si lo trae). */
    fun poner(cola: List<Pendiente>, id: String, idioma: String? = null): List<Pendiente> {
        val i = cola.indexOfFirst { it.id == id }
        if (i < 0) return cola + Pendiente(id, idioma = idioma)
        if (idioma == null) return cola
        return cola.toMutableList().also { it[i] = it[i].copy(idioma = idioma) }
    }

    /**
     * **La cola después de un intento**: hecho (o ya no hacía falta), fuera; sin modelo, se queda
     * esperando la red sin gastar intentos; un fallo suma uno y, al llegar a [MAXIMO_DE_INTENTOS], fuera.
     */
    fun alAcabar(cola: List<Pendiente>, id: String, fin: Fin): List<Pendiente> = cola.mapNotNull { p ->
        if (p.id != id) return@mapNotNull p
        when (fin) {
            Fin.HECHO, Fin.NADA_QUE_HACER -> null
            Fin.SIN_MODELO -> p.copy(faltaElModelo = true)
            Fin.FALLO -> if (p.intentos + 1 >= MAXIMO_DE_INTENTOS) null else p.copy(intentos = p.intentos + 1, faltaElModelo = false)
        }
    }

    /**
     * **Qué se retoma ahora**: todo lo pendiente; lo que espera al modelo, solo con red ([hayRed]),
     * que es lo que hace falta para bajarlo. Fuera lo que [enMarcha] ya está haciendo.
     */
    fun aRetomar(cola: List<Pendiente>, hayRed: Boolean, enMarcha: Set<String>): List<Pendiente> =
        cola.filter { it.id !in enMarcha && (!it.faltaElModelo || hayRed) }

    /** Fuera de la cola lo que ya no existe o ya no necesita nada ([queToca]). */
    fun limpiar(cola: List<Pendiente>, mensajes: Map<String, Mensaje>, hayAudio: (Mensaje) -> Boolean): List<Pendiente> =
        cola.filter { p -> mensajes[p.id]?.let { queToca(it, hayAudio(it)) != Tarea.NADA } == true }

    /** Si algo de la cola espera a que se baje el modelo. */
    fun esperaAlModelo(cola: List<Pendiente>): Boolean = cola.any { it.faltaElModelo }

    // --- En el disco ---------------------------------------------------------------------------

    /** La cola como se guarda: `[{"id":…,"idioma":…,"intentos":…,"modelo":…}]`. */
    fun aJson(cola: List<Pendiente>): String = buildJsonArray {
        for (p in cola) add(buildJsonObject {
            put("id", JsonPrimitive(p.id))
            p.idioma?.let { put("idioma", JsonPrimitive(it)) }
            put("intentos", JsonPrimitive(p.intentos))
            put("modelo", JsonPrimitive(p.faltaElModelo))
        })
    }.toString()

    /** La cola leída; lo que no se entiende se salta (y un archivo roto es una cola vacía). */
    fun deJson(texto: String?): List<Pendiente> {
        if (texto.isNullOrBlank()) return emptyList()
        val lista = runCatching { Json.parseToJsonElement(texto) as? JsonArray }.getOrNull() ?: return emptyList()
        return lista.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = (o["id"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Pendiente(
                id = id,
                intentos = (o["intentos"] as? JsonPrimitive)?.intOrNull?.coerceAtLeast(0) ?: 0,
                faltaElModelo = (o["modelo"] as? JsonPrimitive)?.booleanOrNull ?: false,
                idioma = (o["idioma"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            )
        }.distinctBy { it.id }
    }
}
