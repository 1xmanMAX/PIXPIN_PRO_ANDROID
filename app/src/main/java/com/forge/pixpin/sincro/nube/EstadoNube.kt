package com.forge.pixpin.sincro.nube

import kotlinx.serialization.Serializable
import java.io.File

/** La carpeta de la nube dentro de `files/sincro/` (no viaja: `sincro/` no está en lo que viaja). */
fun carpetaDeLaNube(filesDir: File) = File(filesDir, "sincro/nube")

private fun limpio(s: String) = s.map { if (it.isLetterOrDigit() || it == '-' || it == '_' || it == '.') it else '_' }.joinToString("")

internal fun escribirAtomico(f: File, b: ByteArray) {
    f.parentFile?.mkdirs()
    val tmp = File(f.path + ".tmp")
    tmp.writeBytes(b)
    if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() } }
}

/** `version` y `versionNonce` guardados. */
@Serializable
data class VersionGuardada(val v: Long = 0, val n: Long = 0)

/** **Lo que la nube tiene de un archivo** (`EnLaNube` del PC). */
@Serializable
data class EnLaNube(
    /** El SHA-256 de lo que se subió (de un lienzo, de su original). */
    val sha256: String = "",
    val bytes: Long = 0,
    /** Los deltas encima del original (lienzos y tintas, `deltas1`). */
    val deltas: List<DeltaGuardado> = emptyList(),
    /** La versión de cada trazo que la nube ya tiene. */
    val versiones: Map<String, VersionGuardada> = emptyMap(),
    val imagenes: Set<String> = emptySet(),
    val estado: String? = null,
    /** El resumen de cómo estaba **aquí** al subirlo o bajarlo: si sigue igual, nada que mirar. */
    val local: String = ""
) {
    val conocido: TrazosNube.Conocido get() = TrazosNube.Conocido(versiones.mapValues { TrazosNube.Version(it.value.v, it.value.n) }, imagenes, estado)
    fun conConocido(k: TrazosNube.Conocido) = copy(versiones = k.versiones.mapValues { VersionGuardada(it.value.version, it.value.nonce) }, imagenes = k.imagenes, estado = k.estado)
    val bytesDeDeltas: Long get() = deltas.sumOf { it.bytes }
    val conTrazos: Boolean get() = versiones.isNotEmpty() || deltas.isNotEmpty()
    val losDeltas: List<Delta> get() = deltas.map { Delta(it.sha256, it.bytes) }
}

@Serializable
data class DeltaGuardado(val sha256: String = "", val bytes: Long = 0)

/** **Lo sabido de un chat en la nube** (`sincro/nube/chats/<chat>.json`). */
@Serializable
data class EstadoDeChat(
    /** El espacio donde vive (el de un compartido no es el del aparato). */
    val espacio: String = "",
    /** La última revisión que se bajó o se dejó (0: nunca). */
    val revision: Long = 0,
    val nombre: String = "",
    /** El `id` de cada mensaje → su resumen ([com.forge.pixpin.sincro.Disco.resumenDe]). */
    val mensajes: Map<String, String> = emptyMap(),
    val proyecto: String? = null,
    val compartido: String? = null,
    val archivos: Map<String, EnLaNube> = emptyMap(),
    /** Objetos que la nube dijo que faltan y no se pudieron subir. */
    val faltan: List<String> = emptyList(),
    val alDia: Long = 0,
    val error: String? = null
) {
    companion object {
        private fun archivo(filesDir: File, chat: String) = File(carpetaDeLaNube(filesDir), "chats/${limpio(chat)}.json")

        fun leer(filesDir: File, chat: String): EstadoDeChat = runCatching {
            Nube.JSON.decodeFromString(serializer(), archivo(filesDir, chat).readText())
        }.getOrDefault(EstadoDeChat())

        fun olvidar(filesDir: File, chat: String) { archivo(filesDir, chat).delete() }
    }

    fun guardar(filesDir: File, chat: String) = escribirAtomico(archivo(filesDir, chat), Nube.JSON.encodeToString(serializer(), this).toByteArray())
}

/** **Por dónde va un chat compartido**: por la red local (lo de siempre) o por la nube. */
enum class Modo { LOCAL, NUBE }

/** **Los chats que van por la nube** (`sincro/nube/chats.json`, fuera de `compartidos.json`). */
@Serializable
data class ChatsEnLaNube(
    val chats: Set<String> = emptySet(),
    /** Los que se pasaron a «Local» a mano: no se vuelven a poner solos. */
    val locales: Set<String> = emptySet(),
    /** Por dónde iban las novedades. */
    val seq: Long = 0
) {
    fun modo(chat: String) = if (chat in chats) Modo.NUBE else Modo.LOCAL

    /** Cambia el modo de un chat (a mano: manda lo que diga la persona). */
    fun poner(chat: String, modo: Modo) = when (modo) {
        Modo.NUBE -> copy(chats = chats + chat, locales = locales - chat)
        Modo.LOCAL -> copy(chats = chats - chat, locales = locales + chat)
    }

    /** Un compartido que otra persona puso en la nube: entra solo, salvo que aquí se dijera «Local». */
    fun adoptar(chat: String): ChatsEnLaNube? = if (chat in locales || chat in chats) null else copy(chats = chats + chat)

    companion object {
        private fun ruta(filesDir: File) = File(carpetaDeLaNube(filesDir), "chats.json")

        fun leer(filesDir: File): ChatsEnLaNube = runCatching { Nube.JSON.decodeFromString(serializer(), ruta(filesDir).readText()) }.getOrDefault(ChatsEnLaNube())

        /** Cambia la lista de una vez. */
        @Synchronized
        fun cambiar(filesDir: File, f: (ChatsEnLaNube) -> ChatsEnLaNube): ChatsEnLaNube {
            val antes = leer(filesDir)
            val despues = f(antes)
            if (despues != antes) escribirAtomico(ruta(filesDir), Nube.JSON.encodeToString(serializer(), despues).toByteArray())
            return despues
        }
    }
}
