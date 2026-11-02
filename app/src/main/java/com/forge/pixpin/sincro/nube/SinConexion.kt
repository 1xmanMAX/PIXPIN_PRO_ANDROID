package com.forge.pixpin.sincro.nube

import com.forge.pixpin.sincro.AnotacionesDelAdjunto
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/**
 * **«Sin conexión» en un documento de un chat de la nube** (puerto de `nube/sin_conexion.rs` del PC).
 *
 * - **Al pasar a sin conexión** ([retener]) se copia aparte lo anotado del documento
 *   (`anot-<uid>…`, o el lienzo `pins/draw/<referencia>.excalidraw.gz` y lo de su lado, en
 *   `sincro/nube/sin-conexion/`); desde ahí la vuelta **no sube** esos archivos.
 * - **Lo de los demás se sigue viendo**: lo que llega se escribe en el archivo de verdad y también en
 *   la copia, que es siempre «lo de la nube sin lo mío sin conexión».
 * - **Volver a en línea** ([volver]): se tira la copia y la vuelta siguiente lo sube como delta.
 * - **Descartar** ([descartar]): la copia vuelve a su sitio y lo nacido sin conexión se borra.
 *
 * La **clave** es el código de un adjunto o la referencia de un lienzo; con varias partes
 * (`codigo|dibujo0|dibujo1`, [claveConLienzos]) para el PDF de un proyecto.
 */
class SinConexion(private val filesDir: File) : Retencion {

    @Serializable
    data class Retenido(val chat: String = "", val clave: String = "", val desde: Long = 0, val habia: List<String> = emptyList())

    private val carpeta get() = carpetaDeLaNube(filesDir)
    private val indice get() = File(carpeta, "sin-conexion.json")

    private fun limpio(s: String) = s.map { if (it.isLetterOrDigit() || it == '-' || it == '_' || it == '.') it else '_' }.joinToString("")
    private fun carpetaDe(chat: String, clave: String) = File(carpeta, "sin-conexion/${limpio(chat)}/${limpio(primera(clave))}")
    private fun copiaDe(chat: String, clave: String, rel: String) = File(carpetaDe(chat, clave), limpio(rel.substringAfterLast('/')))

    fun lista(): List<Retenido> = runCatching { Nube.JSON.decodeFromString(ListSerializer(Retenido.serializer()), indice.readText()) }.getOrDefault(emptyList())

    private fun guardar(l: List<Retenido>) = escribirAtomico(indice, Nube.JSON.encodeToString(ListSerializer(Retenido.serializer()), l).toByteArray())

    fun esta(chat: String, clave: String) = lista().any { it.chat == chat && mismaClave(it.clave, clave) }

    /** Lo anotado de [clave] que hay ahora en disco. */
    private fun ficheros(clave: String): List<String> {
        val dir = File(filesDir, AnotacionesDelAdjunto.CARPETA)
        val v = (dir.list() ?: emptyArray()).map { "${AnotacionesDelAdjunto.CARPETA}/$it" }.filter { pertenece(clave, it) && !it.endsWith(".tmp") }.toMutableList()
        for (parte in partes(clave)) for (t in listOf(".excalidraw.gz", ".marcas", ".vistas.json")) {
            val r = "${AnotacionesDelAdjunto.CARPETA}/$parte$t"
            if (r !in v && File(filesDir, r).isFile) v += r
        }
        return v
    }

    /** **Pasa a sin conexión**: copia aparte lo anotado tal como está. Si ya lo estaba, nada. */
    @Synchronized
    fun retener(chat: String, clave: String, ahora: Long = System.currentTimeMillis()) {
        val l = lista()
        if (l.any { it.chat == chat && mismaClave(it.clave, clave) }) return
        val habia = ficheros(clave)
        val dir = carpetaDe(chat, clave)
        dir.deleteRecursively(); dir.mkdirs()
        for (rel in habia) File(filesDir, rel).copyTo(copiaDe(chat, clave, rel), overwrite = true)
        guardar(l + Retenido(chat, clave, ahora, habia))
    }

    @Synchronized
    private fun soltar(chat: String, clave: String) {
        guardar(lista().filterNot { it.chat == chat && mismaClave(it.clave, clave) })
        carpetaDe(chat, clave).deleteRecursively()
    }

    /** **Vuelve a en línea**: lo anotado se queda y la vuelta lo sube. */
    fun volver(chat: String, clave: String) = soltar(chat, clave)

    /** **Descarta lo anotado sin conexión**. Dice cuántos archivos se tocaron. */
    @Synchronized
    fun descartar(chat: String, clave0: String): Int {
        val r = lista().firstOrNull { it.chat == chat && mismaClave(it.clave, clave0) } ?: return 0
        val clave = claveConLienzos(primera(r.clave), partes(r.clave).drop(1) + partes(clave0).drop(1))
        var n = 0
        for (rel in r.habia) {
            val copia = copiaDe(chat, clave, rel)
            if (copia.isFile) {
                val destino = File(filesDir, rel)
                val b = copia.readBytes()
                if (!(destino.isFile && destino.readBytes().contentEquals(b))) { escribirAtomico(destino, b); n++ }
            }
        }
        // Lo que no tiene copia nació aquí sin conexión.
        for (rel in ficheros(clave)) if (rel !in r.habia && File(filesDir, rel).delete()) n++
        soltar(chat, clave)
        return n
    }

    override fun retenido(chat: String, rel: String) = lista().any { it.chat == chat && pertenece(it.clave, rel) }

    override fun copia(chat: String, rel: String): File? {
        val r = lista().firstOrNull { it.chat == chat && pertenece(it.clave, rel) } ?: return null
        return copiaDe(chat, r.clave, rel)
    }

    companion object {
        const val ENTRE_PARTES = '|'

        fun claveConLienzos(codigo: String, lienzos: List<String>): String {
            val vistos = mutableListOf(codigo)
            val s = StringBuilder(codigo)
            for (l in lienzos) {
                if (l.isEmpty() || ENTRE_PARTES in l || l in vistos) continue
                vistos += l; s.append(ENTRE_PARTES).append(l)
            }
            return s.toString()
        }

        fun partes(clave: String) = clave.split(ENTRE_PARTES).filter { it.isNotEmpty() }
        fun primera(clave: String) = clave.substringBefore(ENTRE_PARTES)
        fun mismaClave(a: String, b: String) = primera(a) == primera(b)

        /** El código de un nombre `anot-<uid>…`; null si no es de lo anotado. */
        private fun uidDelNombre(nombre: String): String? =
            nombre.takeIf { it.startsWith("anot-") }?.removePrefix("anot-")?.takeWhile { it != '-' && it != '.' }?.takeIf { it.isNotEmpty() }

        /** Si [rel] es de lo anotado con [clave]. */
        fun pertenece(clave: String, rel: String): Boolean {
            val nombre = rel.removePrefix("${AnotacionesDelAdjunto.CARPETA}/").takeIf { it != rel && '/' !in it } ?: return false
            return partes(clave).any { parte ->
                val u = uidDelNombre(nombre)
                if (u != null) u == parte else nombre.startsWith("$parte.")
            }
        }
    }
}
