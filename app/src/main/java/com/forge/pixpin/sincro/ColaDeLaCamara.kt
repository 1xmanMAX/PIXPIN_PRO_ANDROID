package com.forge.pixpin.sincro

import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * **Las fotos y los clips de la cámara del PC que esperan a subir** (28-oct-2026, §7.3 de
 * `docs/movil-como-camara.md` del PC).
 *
 * Cada disparo se apunta aquí con todo lo que lleva al PC ([Toma]: su minuto, su hora, el desfase
 * medido y su `clave` fija) y **se sube en segundo plano** ([SubidaDeLaCamaraService]) con
 * reintentos: el visor sigue libre para la siguiente, y cerrar la pantalla o quedarse sin red no
 * tira nada. Lo que llega tarde aún entra en la clase (el PC recuerda sus últimas grabaciones).
 *
 * La guía dice «Room»; aquí no hay Room, y la cola es pequeña (unas decenas de tomas por clase):
 * un JSON en `filesDir/sincro/camara-movil/cola.json`, reescrito entero al cambiar (primero a un
 * `.tmp` y luego renombrado, para no dejarlo a medias), y cada fichero en `tomas/`. Lógica pura:
 * lo prueba `ColaDeLaCamaraTest`.
 */
class ColaDeLaCamara(val carpeta: File) {

    /** Una foto o un clip para la grabación de un PC. */
    @Serializable
    data class Toma(
        /** El id de la toma: el mismo en cada intento (con él, un clip cortado sigue donde se quedó). */
        val clave: String,
        /** El fichero, dentro de `tomas/`. */
        val fichero: String,
        /** Con qué nombre llega al PC («IMG_20261028_100512.jpg»). */
        val nombre: String,
        val mime: String,
        /** El id de la grabación del PC. */
        val grabacion: String,
        /** El id del PC (para saber a quién llamar) y su nombre, para enseñarlo. */
        val pc: String,
        val pcNombre: String = "",
        val titulo: String = "",
        /** El minuto de la grabación en el disparo (un clip: donde empezó). */
        val ms: Long,
        /** La hora de este teléfono al hacerla. */
        val tomadaEn: Long,
        /** Lo que va adelantado el reloj de este teléfono sobre el del PC. */
        val reloj: Long = 0,
        /** Lo que dura un clip (una foto, 0). */
        val duracionMs: Long = 0,
        val estado: String = PENDIENTE,
        /** El minuto donde quedó, según el «listo» del PC. */
        val msListo: Long? = null,
        /** Lo último que salió mal. */
        val error: String? = null,
        val intentos: Int = 0,
        /** Cuándo se apuntó (para el orden y para limpiar). */
        val creada: Long = 0,
        /** Cuándo llegó al PC o se negó. */
        val acabadaEn: Long = 0
    ) {
        val esClip: Boolean get() = CamaraRemota.esClip(mime)
        val pendiente: Boolean get() = estado == PENDIENTE
        val lista: Boolean get() = estado == LISTA
    }

    private val tomas get() = File(carpeta, "tomas")
    private val archivo get() = File(carpeta, "cola.json")

    /** **Un fichero nuevo** para lo que va a escribir la cámara, con su extensión. */
    fun ficheroNuevo(extension: String): File {
        tomas.mkdirs()
        return File(tomas, UUID.randomUUID().toString() + "." + extension)
    }

    fun fichero(t: Toma): File = File(tomas, t.fichero)

    fun todas(): List<Toma> = synchronized(CERROJO) { leer() }

    /** Lo que queda por subir, de lo más viejo a lo más nuevo. */
    fun pendientes(): List<Toma> = todas().filter { it.pendiente }.sortedBy { it.creada }

    fun hayPendientes(): Boolean = todas().any { it.pendiente }

    /** **Apunta una toma** (el fichero ya está en [fichero]). Con la misma [Toma.clave], la sustituye. */
    fun poner(t: Toma) = cambiar { l -> l.filter { it.clave != t.clave } + t }

    /** Llegó al PC: quedó en el minuto [ms]. */
    fun lista(clave: String, ms: Long, ahora: Long) =
        cambiar { l -> l.map { if (it.clave == clave) it.copy(estado = LISTA, msListo = ms, error = null, acabadaEn = ahora) else it } }

    /** El PC dijo que no, para siempre ([CamaraRemota.esNoParaSiempre]). */
    fun negada(clave: String, error: String, ahora: Long) =
        cambiar { l -> l.map { if (it.clave == clave) it.copy(estado = NEGADA, error = error, acabadaEn = ahora) else it } }

    /** No se pudo ahora: se volverá a intentar. */
    fun fallo(clave: String, error: String) =
        cambiar { l -> l.map { if (it.clave == clave) it.copy(error = error, intentos = it.intentos + 1) else it } }

    /**
     * **Lo viejo fuera**: lo que llegó hace más de [DIAS_LISTAS] días y lo negado hace más de
     * [DIAS_NEGADAS], con su fichero. Lo pendiente no se toca nunca: aún puede entrar.
     */
    fun limpiar(ahora: Long) {
        val fuera = ArrayList<Toma>()
        cambiar { l ->
            l.filter { t ->
                val vieja = when (t.estado) {
                    LISTA -> ahora - t.acabadaEn > DIAS_LISTAS * DIA
                    NEGADA -> ahora - t.acabadaEn > DIAS_NEGADAS * DIA
                    else -> false
                }
                if (vieja) fuera += t
                !vieja
            }
        }
        for (t in fuera) fichero(t).delete()
    }

    private fun cambiar(f: (List<Toma>) -> List<Toma>) {
        synchronized(CERROJO) {
            val nueva = f(leer())
            carpeta.mkdirs()
            val tmp = File(carpeta, "cola.json.tmp")
            tmp.writeText(Protocolo.json.encodeToString(LISTA_DE_TOMAS, nueva))
            if (!tmp.renameTo(archivo)) { archivo.delete(); tmp.renameTo(archivo) }
        }
        cambios.value = cambios.value + 1
    }

    private fun leer(): List<Toma> = runCatching {
        if (!archivo.isFile) emptyList() else Protocolo.json.decodeFromString(LISTA_DE_TOMAS, archivo.readText())
    }.getOrDefault(emptyList())

    companion object {
        const val PENDIENTE = "pendiente"
        const val LISTA = "lista"
        const val NEGADA = "negada"
        const val DIAS_LISTAS = 2L
        const val DIAS_NEGADAS = 7L
        private const val DIA = 24 * 60 * 60 * 1000L
        private val LISTA_DE_TOMAS = ListSerializer(Toma.serializer())
        /** Uno para todo el proceso: la pantalla y el servicio escriben la misma cola. */
        private val CERROJO = Any()

        /** Sube cada vez que cambia la cola: la pantalla de cámara lo mira para sus miniaturas. */
        val cambios = MutableStateFlow(0)

        /** **La espera antes del intento siguiente**: 2, 4, 8… s, hasta un minuto. */
        fun espera(fallos: Int): Long = minOf(2_000L shl (fallos - 1).coerceIn(0, 5), 60_000L)

        /** **Lo que dice la notificación** mientras sube: «Subiendo 3 fotos al PC…». */
        fun aviso(pendientes: List<Toma>): String {
            val clips = pendientes.count { it.esClip }
            val fotos = pendientes.size - clips
            val partes = listOfNotNull(
                fotos.takeIf { it > 0 }?.let { if (it == 1) "1 foto" else "$it fotos" },
                clips.takeIf { it > 0 }?.let { if (it == 1) "1 clip" else "$it clips" }
            )
            return if (partes.isEmpty()) "Subiendo al PC…" else "Subiendo ${partes.joinToString(" y ")} al PC…"
        }

        /** La cola de este teléfono. */
        fun de(filesDir: File) = ColaDeLaCamara(File(filesDir, "sincro/camara-movil"))
    }
}
