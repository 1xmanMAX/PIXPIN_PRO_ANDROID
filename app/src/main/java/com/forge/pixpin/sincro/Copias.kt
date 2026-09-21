package com.forge.pixpin.sincro

import com.forge.pixpin.guardados.Mensaje
import com.forge.pixpin.motor.Proyecto
import com.forge.pixpin.motor.Proyectos
import java.io.File
import kotlinx.serialization.Serializable

/**
 * **Copias de seguridad de un proyecto, solas, antes de que nada lo pise** (15-sep-2026).
 *
 * El usuario perdió lienzos de su proyecto «Tesis»: primero al recibir un lienzo por Wi-Fi —el
 * proyecto del teléfono quedó sustituido por el de la tableta, con un lienzo repetido tres veces—
 * y después al sincronizar, que tomó ese estado roto por un borrado y lo llevó a los dos aparatos.
 * Y preguntó lo que había que haber resuelto antes: **si algo sale mal, ¿cómo vuelvo a lo de antes?**
 *
 * Antes de cada cosa que escribe encima de un proyecto desde fuera —recibir un envío,
 * sincronizar— se guarda aquí cómo estaba: el índice del proyecto, los mensajes de su chat y sus
 * archivos (lienzos, tablas, croquis…). Desde la pantalla de copias se vuelve a cualquiera.
 *
 * - **Cada archivo se guarda una vez, por su contenido** (`objetos/<resumen>`): diez copias de un
 *   proyecto donde solo cambió un lienzo ocupan ese lienzo diez veces, no el proyecto entero.
 * - **Si nada cambió desde la última copia, no se hace otra.** Sincronizar cinco veces seguidas no
 *   llena la lista de copias iguales.
 * - Se quedan las [POR_PROYECTO] más nuevas; los objetos que ya no usa ninguna se tiran.
 * - Los archivos de más de [TOPE_POR_ARCHIVO] (un PDF de cientos de megas) no se copian: no los
 *   pisa nadie —el PDF de un proyecto no se reescribe— y llenarían el teléfono.
 * - Viven en `files/copias`, **fuera de lo que viaja** al sincronizar (ver `Disco.CARPETAS`): son
 *   de este aparato.
 *
 * **Restaurar no borra lo nuevo**: vuelve a poner el índice y los archivos de la copia, y los
 * mensajes que faltan; lo que se escribió después de la copia y no estaba en ella se queda. Y antes
 * de restaurar se hace otra copia, así que restaurar también se puede deshacer.
 *
 * Sin Android, sobre [Disco]: se prueba con dos carpetas como la sincronización.
 */
class Copias(private val disco: Disco) {

    @Serializable
    data class Copia(
        val id: String,
        val chat: String,
        val cuando: Long,
        /** Por qué se hizo, en palabras: «Antes de sincronizar con Tableta». */
        val motivo: String,
        val nombre: String,
        val hojas: Int,
        /** El proyecto tal como estaba, en JSON de este aparato. Null si el chat no es de un proyecto. */
        val proyecto: String? = null,
        /** Los mensajes de su chat, uno por línea en JSON. */
        val mensajes: List<String> = emptyList(),
        /** Ruta relativa → resumen del contenido guardado en `objetos/`. */
        val archivos: Map<String, String> = emptyMap(),
        /** Lo que no se copió por grande. */
        val sinCopiar: List<String> = emptyList()
    )

    private val raiz get() = File(disco.filesDir, CARPETA)
    private val objetos get() = File(raiz, "objetos").apply { mkdirs() }
    private fun carpetaDe(chat: String) = File(raiz, "proyectos/" + chat.replace(Regex("[^A-Za-z0-9._-]"), "_"))

    /**
     * Guarda cómo está [chat] ahora. Devuelve la copia, o null si no había nada o si es igual que la
     * última (entonces la última ya sirve).
     */
    fun hacer(chat: String, motivo: String, ahora: Long = System.currentTimeMillis()): Copia? = synchronized(CERROJO) {
        runCatching { hacerYa(chat, motivo, ahora) }.getOrNull()
    }

    private fun hacerYa(chat: String, motivo: String, ahora: Long, ademas: Collection<String> = emptyList()): Copia? {
        val p = disco.leerProyectos().firstOrNull { it.id == chat }
        val mensajes = disco.leerMensajes().filter { Disco.chatDe(it) == chat }
        if (p == null && mensajes.isEmpty()) return null
        val archivos = LinkedHashMap<String, String>()
        val grandes = ArrayList<String>()
        // [ademas]: lo que se va a escribir encima aunque ya no sea del proyecto (al restaurar).
        for (rel in (disco.alcance(chat).keys + ademas).distinct()) {
            val f = disco.archivo(rel)
            if (!f.isFile) continue
            if (f.length() > TOPE_POR_ARCHIVO) { grandes += rel; continue }
            val resumen = f.inputStream().use { Disco.sha256De(it) }
            val destino = File(objetos, resumen)
            if (!destino.exists()) {
                val tmp = File(objetos, "$resumen.tmp")
                f.copyTo(tmp, overwrite = true)
                if (!tmp.renameTo(destino)) { tmp.copyTo(destino, overwrite = true); tmp.delete() }
            }
            archivos[rel] = resumen
        }
        val copia = Copia(
            id = ahora.toString(), chat = chat, cuando = ahora, motivo = motivo,
            nombre = p?.nombre ?: "Conversación", hojas = p?.hojas?.size ?: 0,
            proyecto = p?.let { Proyectos.json.encodeToString(Proyecto.serializer(), it) },
            mensajes = mensajes.map { Disco.JSON.encodeToString(Mensaje.serializer(), it) },
            archivos = archivos, sinCopiar = grandes
        )
        val ultima = lista(chat).firstOrNull()
        if (ultima != null && ultima.proyecto == copia.proyecto && ultima.mensajes == copia.mensajes && ultima.archivos == copia.archivos) return null
        val carpeta = carpetaDe(chat).apply { mkdirs() }
        var archivo = File(carpeta, "${copia.id}.json")
        var n = 1
        while (archivo.exists()) archivo = File(carpeta, "${copia.id}-${n++}.json")
        val puesta = copia.copy(id = archivo.name.removeSuffix(".json"))
        val tmp = File(carpeta, archivo.name + ".tmp")
        tmp.writeText(Disco.JSON.encodeToString(Copia.serializer(), puesta))
        if (!tmp.renameTo(archivo)) { tmp.copyTo(archivo, overwrite = true); tmp.delete() }
        podar(chat)
        return puesta
    }

    /** Las copias de [chat], la más nueva primero. */
    fun lista(chat: String): List<Copia> =
        carpetaDe(chat).listFiles { f -> f.name.endsWith(".json") }.orEmpty()
            .mapNotNull { f -> runCatching { Disco.JSON.decodeFromString(Copia.serializer(), f.readText()) }.getOrNull() }
            .sortedByDescending { it.cuando }

    /** Las copias de todos los proyectos, la más nueva primero. */
    fun todas(): List<Copia> =
        File(raiz, "proyectos").listFiles().orEmpty().filter { it.isDirectory }
            .flatMap { d -> d.listFiles { f -> f.name.endsWith(".json") }.orEmpty().toList() }
            .mapNotNull { f -> runCatching { Disco.JSON.decodeFromString(Copia.serializer(), f.readText()) }.getOrNull() }
            .sortedByDescending { it.cuando }

    /**
     * **Vuelve a la copia.** Antes guarda cómo está ahora (restaurar también se deshace). Los
     * archivos de la copia se escriben en su sitio; el proyecto vuelve a su índice de entonces —con
     * los lienzos añadidos después **conservados al final**, que restaurar no es borrar—; y los
     * mensajes que faltan vuelven. Devuelve si se pudo.
     */
    fun restaurar(copia: Copia, ahora: Long = System.currentTimeMillis()): Boolean = synchronized(CERROJO) {
        runCatching {
            hacerYa(copia.chat, "Antes de volver a la copia de ${copia.motivo.replaceFirstChar { it.lowercase() }}", ahora, copia.archivos.keys)
            for ((rel, resumen) in copia.archivos) {
                val origen = File(objetos, resumen)
                if (!origen.isFile) continue
                val destino = disco.archivo(rel)
                destino.parentFile?.mkdirs()
                val tmp = File(destino.parentFile, destino.name + ".restaurando")
                origen.copyTo(tmp, overwrite = true)
                if (!tmp.renameTo(destino)) { tmp.copyTo(destino, overwrite = true); tmp.delete() }
            }
            val mensajes = copia.mensajes.mapNotNull { runCatching { Disco.JSON.decodeFromString(Mensaje.serializer(), it) }.getOrNull() }
            disco.reponerMensajes(mensajes)
            // **Si el proyecto estaba borrado, deja de estarlo** (21-sep-2026): con la lápida
            // puesta, la siguiente sincronización lo volvía a borrar nada más recuperarlo.
            disco.quitarLapida(copia.chat)
            copia.proyecto?.let { Proyectos.json.decodeFromString(Proyecto.serializer(), it) }?.let { deEntonces ->
                val ahoraP = disco.leerProyectos().firstOrNull { it.id == deEntonces.id }
                val usados = deEntonces.hojas.mapTo(HashSet()) { it.id }
                val despues = ahoraP?.hojas.orEmpty().filter { it.id !in usados }
                disco.guardarProyecto(
                    Proyectos.sinHojasRepetidas(deEntonces.copy(hojas = deEntonces.hojas + despues, tocado = ahora))
                )
            }
            disco.avisar(Disco.Cambio.ARCHIVOS)
            true
        }.getOrDefault(false)
    }

    /**
     * **Los proyectos borrados que se pueden recuperar enteros** (21-sep-2026): los que tienen
     * copia y ya no están en la lista. De cada uno, su copia más completa —la de más hojas, y a
     * igualdad la más nueva—: la de «antes de borrarlo» lo es casi siempre, pero si se borró dos
     * veces la segunda copia puede ser de un proyecto ya a medias.
     *
     * El usuario recuperó un teléfono entero lienzo a lienzo con «Devolver», y todo cayó en un
     * solo proyecto: faltaba esto, devolver **el proyecto**, con su nombre, su orden y su chat.
     */
    fun proyectosBorrados(): List<Copia> {
        val vivos = disco.leerProyectos().mapTo(HashSet()) { it.id }
        return todas().filter { it.proyecto != null && it.chat !in vivos }
            .groupBy { it.chat }
            .map { (_, suyas) -> suyas.maxWith(compareBy({ it.hojas }, { it.cuando })) }
            .sortedByDescending { it.cuando }
    }

    fun borrar(copia: Copia) = synchronized(CERROJO) {
        File(carpetaDe(copia.chat), "${copia.id}.json").delete()
        recogerObjetos()
    }

    private fun podar(chat: String) {
        val sobran = lista(chat).drop(POR_PROYECTO)
        if (sobran.isEmpty()) return
        sobran.forEach { File(carpetaDe(chat), "${it.id}.json").delete() }
        recogerObjetos()
    }

    /** Tira los objetos que ya no señala ninguna copia. */
    private fun recogerObjetos() {
        val vivos = todas().flatMapTo(HashSet()) { it.archivos.values }
        objetos.listFiles().orEmpty().forEach { if (it.name !in vivos) it.delete() }
    }

    // ------------------------------------------------------------ lienzos sueltos

    /** Un lienzo que está en el disco y en ningún proyecto. */
    data class Suelto(val dibujo: String, val bytes: Long, val tocado: Long, val nombre: String?, val deProyecto: String?)

    /**
     * **Los lienzos que se quedaron sin proyecto.** Quitar un lienzo del índice de un proyecto —lo
     * que hicieron el envío y la sincronización del fallo— no borra su archivo: sigue en
     * `pins/draw`. Aquí se buscan los que no están en ningún proyecto, para devolverlos a uno.
     *
     * No cuentan los dibujos de la conversación general que nunca fueron de un proyecto (esos
     * están donde tienen que estar) ni las fotos, que también se guardan como dibujo.
     */
    fun lienzosSueltos(): List<Suelto> {
        val carpeta = File(disco.filesDir, "pins/draw")
        val enProyectos = disco.leerProyectos().flatMapTo(HashSet()) { p -> p.hojas.mapNotNull { it.dibujo } }
        val mensajes = disco.leerMensajes()
        val porReferencia = mensajes.filter { it.referencia != null }.groupBy { it.referencia!! }
        return carpeta.listFiles { f -> f.name.endsWith(".excalidraw.gz") }.orEmpty().mapNotNull { f ->
            val id = f.name.removeSuffix(".excalidraw.gz")
            if (id in enProyectos || id.startsWith("foto-")) return@mapNotNull null
            val suyos = porReferencia[id].orEmpty()
            // Un dibujo de la conversación general que nunca fue de un proyecto: está bien donde está.
            if (suyos.isNotEmpty() && suyos.all { it.proyecto == null }) return@mapNotNull null
            if (suyos.any { it.clase == com.forge.pixpin.guardados.Clase.IMAGEN }) return@mapNotNull null
            val m = suyos.firstOrNull()
            Suelto(id, f.length(), f.lastModified(), m?.nombre?.ifBlank { null }, m?.proyecto)
        }.sortedByDescending { it.tocado }
    }

    companion object {
        const val CARPETA = "copias"
        const val POR_PROYECTO = 30
        const val TOPE_POR_ARCHIVO = 40L * 1024 * 1024
        private val CERROJO = Any()
    }
}
