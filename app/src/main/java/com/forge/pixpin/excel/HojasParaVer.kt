package com.forge.pixpin.excel

import android.content.Context
import com.forge.pixpin.guardados.LibroDelChat
import com.forge.pixpin.guardados.MensajesStore
import com.forge.pixpin.guardados.TablasDelChat
import com.forge.pixpin.motor.ImportarHojas
import com.forge.pixpin.motor.TablaDeCalculo
import com.forge.pixpin.motor.TablasEnDisco
import com.forge.pixpin.motor.TablasEnMensajes
import com.forge.pixpin.sincro.AnotacionesDelAdjunto
import com.forge.pixpin.sincro.Codigos
import java.io.File

/**
 * **Lo que abre el visor, leído sin tocar nada** (25-oct-2026). Trabajo de disco y de cálculo: fuera
 * de la pantalla.
 *
 * Solo lee: ni crea las tablas de un libro (`LibroDelChat.tablas` las guardaba al abrirlo en
 * Tablas), ni migra, ni guarda. Lo que llegó del PC —los mensajes `tabla`, sus claves raras— se
 * queda exactamente como estaba.
 */
internal object HojasParaVer {

    /** Lo abierto y el código con que se guarda lo anotado (`anot-<uid>…`), si lo tiene. */
    /** [tablas]: las tablas que se ven, si no es el archivo leído por el .so (para exportarlas). */
    class Abierto(val libro: LibroExcel, val uid: String?, val nombre: String, val tablas: List<TablaDeCalculo>? = null)

    /**
     * Un archivo: el `.xlsx` con el .so; lo demás —y el libro cuyas hojas se editaron— por sus tablas
     * ([EntradaDeHojas.comoSeVe]). Lo anotado va con el código de su mensaje, como hasta ahora (el
     * PC hace igual: `anotado_del_adjunto::del_mensaje_o_junto`), se vea el archivo o sus tablas.
     */
    fun deArchivo(c: Context, ruta: String, nombre: String, mensaje: String?): Abierto {
        val uid = AnotacionesDelAdjunto.uidDe(c.filesDir, ruta)
        val todos = if (mensaje != null) MensajesStore(c).leer() else emptyList()
        val hechas = if (mensaje != null) TablasDelChat.hechas(todos, mensaje) else emptyList()
        val almacen = TablasEnDisco.de(c.filesDir)
        val viejas = mensaje != null && almacen.archivo(LibroDelChat.idDeHoja(mensaje, 0)).exists()
        val editadas = hechas.any { (_, m) -> EntradaDeHojas.editada(TablaDeCalculo.deJson(m.texto)?.tocado ?: 0L, m.cuando) }
        val como = EntradaDeHojas.comoSeVe(LibroExcel.seAbre(nombre, ruta), editadas, hechas.isNotEmpty(), viejas)
        if (como == EntradaDeHojas.ComoSeVe.ARCHIVO) LibroExcel.abrir(ruta)?.let { return Abierto(it, uid, nombre) }
        val tablas: List<TablaDeCalculo> = when {
            hechas.isNotEmpty() -> hechas.mapNotNull { (_, m) -> TablaDeCalculo.deJson(m.texto)?.let { t -> t.copy(nombre = t.nombre.ifBlank { m.nombre }) } }
            viejas -> generateSequence(0) { it + 1 }.map { almacen.archivo(LibroDelChat.idDeHoja(mensaje!!, it)) }
                .takeWhile { it.exists() }.mapNotNull { TablaDeCalculo.deJson(it.readText()) }.toList()
            // Lanza [ImportarHojas.NoSeLee] con el porqué (un `.xls` sin el .so, uno roto).
            else -> ImportarHojas.leer(File(ruta), nombre).map { it.tabla }
        }
        val libro = LibroExcel.deTablas(tablas.map { TablasEnElVisor.hoja(it) }, ruta)
            ?: throw ImportarHojas.NoSeLee("El libro no tiene ninguna hoja con datos")
        return Abierto(libro, uid, nombre, tablas)
    }

    /** Unas tablas por su id (una pestaña cada una), con [activa] delante. Null si no hay ninguna. */
    fun deIds(c: Context, ids: List<String>, activa: String?): Abierto? {
        val almacen = TablasEnDisco.de(c.filesDir)
        val cargadas = ids.mapNotNull { id -> almacen.cargar(id)?.let { id to it } }
        if (cargadas.isEmpty()) return null
        val delante = cargadas.indexOfFirst { it.first == activa }.coerceAtLeast(0)
        val libro = LibroExcel.deTablas(cargadas.map { TablasEnElVisor.hoja(it.second) }, activa = delante) ?: return null
        val nombre = if (cargadas.size == 1) cargadas[0].second.nombre.ifBlank { "Tabla" }
        else cargadas[0].second.nombre.substringBeforeLast(" · ").ifBlank { "Libro" }
        return Abierto(libro, uidDeTabla(c, cargadas[delante].first), nombre, cargadas.map { it.second })
    }

    /**
     * **El código de lo anotado sobre una tabla**, el de lo que la lleva, para que viaje con ella:
     *
     * - una hoja de un Excel del chat (`libro-<mensaje>-<hoja>`): el del mensaje del Excel, el mismo
     *   que cuando se ve el archivo (la tinta de la hoja `i` es `anot-<uid>-h<i>`);
     * - la mini-app `tabla` (`mini-<mensaje>`): el de su mensaje;
     * - la tabla de un proyecto: el de su hoja ([Codigos.unico] de la hoja), que viaja con el proyecto;
     * - una tabla suelta: uno sacado de su id ([Codigos.de]); se guarda pero no viaja (no la nombra
     *   ningún mensaje ni hoja).
     *
     * En el PC una tabla del chat no guarda tinta (`ruta_original` es `None`): esto es lo de los
     * archivos con el id de la tabla, listo para cuando lo haga.
     */
    fun uidDeTabla(c: Context, id: String): String {
        val todos by lazy { MensajesStore(c).leer() }
        if (id.startsWith(TablasEnMensajes.LIBRO)) {
            TablasDelChat.partirLibro(id)?.first?.let { libro -> todos.firstOrNull { it.id == libro } }?.let { return Codigos.unico(it) }
        }
        if (id.startsWith(TablasEnMensajes.MINI)) {
            val m = id.removePrefix(TablasEnMensajes.MINI)
            todos.firstOrNull { it.id == m }?.let { return Codigos.unico(it) }
        }
        val app = c.applicationContext as? com.forge.pixpin.PixPinApp
        app?.proyectos?.proyectos?.value?.firstNotNullOfOrNull { p -> p.hojas.firstOrNull { it.tabla == id } }?.let { return Codigos.unico(it) }
        return Codigos.de("t:$id")
    }

}
