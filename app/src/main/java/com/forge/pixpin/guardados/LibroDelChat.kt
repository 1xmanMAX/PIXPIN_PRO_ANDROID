package com.forge.pixpin.guardados

import android.content.Context
import com.forge.pixpin.motor.ImportarHojas
import com.forge.pixpin.motor.TablasEnDisco
import java.io.File

/**
 * **Un Excel del chat es un libro de tablas**, como un PDF del chat es un documento de páginas
 * (lo pidió el usuario el 11-sep-2026).
 *
 * Se lee la primera vez que se abre o que se une a un proyecto, y cada hoja queda como una tabla
 * con un identificador **sacado del mensaje** (`libro-<mensaje>-<hoja>`): así tocar el archivo en
 * el chat y abrir la hoja desde el proyecto llevan a la misma tabla, y lo que se cambie en un
 * sitio se ve en el otro. Es lo mismo que se hace con las fotos: ver [Mensaje.dibujoDeLaFoto].
 */
object LibroDelChat {

    fun esLibro(m: Mensaje): Boolean =
        m.clase == Clase.ARCHIVO && m.ruta != null &&
            (ImportarHojas.esLibro(m.nombre) || ImportarHojas.esLibro(m.ruta))

    fun idDeHoja(m: Mensaje, hoja: Int) = idDeHoja(m.id, hoja)

    fun idDeHoja(mensaje: String, hoja: Int) = "libro-$mensaje-$hoja"

    /**
     * Las tablas del libro —identificador y nombre—, leyéndolo si aún no se había leído. Trabajo
     * de disco: fuera del hilo principal. Si el archivo no se deja leer, lanza
     * [ImportarHojas.NoSeLee] con el porqué.
     */
    fun tablas(context: Context, m: Mensaje): List<Pair<String, String>> = tablas(context, m.id, m.ruta, m.nombre)

    /**
     * Lo mismo con el identificador del mensaje (o, de un archivo que no está en el chat, uno
     * sacado de su ruta: ver [com.forge.pixpin.excel.ExcelActivity]), su ruta y su nombre.
     */
    fun tablas(context: Context, mensaje: String, ruta: String?, nombre: String): List<Pair<String, String>> {
        val almacen = TablasEnDisco.de(context.filesDir)
        // **Primero, las hojas que ya son mensajes del chat** (las del PC o las editadas aquí desde el
        // 13-oct-2026): son las que viajan. Ver [TablasDelChat].
        val store = MensajesStore(context)
        val enElChat = TablasDelChat.hechas(store.leer(), mensaje)
        if (enElChat.isNotEmpty()) return enElChat.map { (i, m) -> idDeHoja(mensaje, i) to m.nombre.ifBlank { "Tabla" } }
        // Lo que ya hay en disco, hoja a hoja: el archivo de `tablas/` (lo editado aquí antes del
        // 13-oct, o una tabla de un archivo que no es del chat) o, si no, la copia de trabajo de
        // haberlo leído ya, que no viaja (ver [TablasEnDisco.leida]).
        // Uno roto no cuenta (se lee el libro, como en el PC, y el archivo sigue tal cual).
        val hechas = generateSequence(0) { it + 1 }.map { idDeHoja(mensaje, it) }
            .map { it to almacen.cargar(it) }.takeWhile { it.second != null }.toList()
        if (hechas.isNotEmpty()) {
            // **Migración**: las hojas de antes, editadas aquí y guardadas solo en archivo, pasan a
            // mensajes para que el PC las vea. Los archivos se quedan. Solo copias de trabajo: nada
            // que migrar (no se editaron).
            if (hechas.any { almacen.archivo(it.first).exists() }) {
                store.leer().firstOrNull { it.id == mensaje }?.let { runCatching { TablasDelChat(context).migrar(it) } }
            }
            return hechas.map { (id, t) -> id to (t?.nombre?.ifBlank { null } ?: "Tabla") }
        }
        if (ruta == null) return emptyList()
        val hojas = ImportarHojas.leer(File(ruta), nombre.ifBlank { File(ruta).name })
        return hojas.mapIndexedNotNull { i, h ->
            val id = idDeHoja(mensaje, i)
            if (almacen.deTrabajo(id, h.tabla)) id to h.nombre else null
        }
    }

    /**
     * Si [id] es una hoja de un Excel del chat que aquí aún no tiene nada (por ejemplo, la hoja de
     * un proyecto que llegó por la sincronización: la copia de trabajo no viaja), lee el libro para
     * que se pueda abrir. True si después la hay. Trabajo de disco.
     */
    fun prepararHoja(context: Context, id: String): Boolean {
        if (!id.startsWith(com.forge.pixpin.motor.TablasEnMensajes.LIBRO)) return false
        val (libro, _) = TablasDelChat.partirLibro(id) ?: return false
        val m = MensajesStore(context).leer().firstOrNull { it.id == libro && esLibro(it) } ?: return false
        return runCatching { tablas(context, m).any { it.first == id } }.getOrDefault(false)
    }
}
