package com.forge.pixpin.mini

import android.content.Context
import com.forge.pixpin.PixPinApp
import com.forge.pixpin.guardados.Clase
import com.forge.pixpin.guardados.Mensaje
import com.forge.pixpin.guardados.MensajesStore
import com.forge.pixpin.ia.Acciones
import com.forge.pixpin.motor.Proyecto
import java.util.UUID

/**
 * **Cada asignatura es un grupo de tareas** (PC `horario/asignaturas.rs`, 18-oct-2026). El usuario:
 * «que cada asignatura se cree como un grupo de tareas, y cuando cree una tarea pueda añadirla a una
 * asignatura».
 *
 * Un grupo de tareas ya es una lista de tareas de un chat, así que una asignatura no inventa otro
 * almacén: es **una lista con su nombre en el chat «Asignaturas»** (un proyecto como cualquier otro:
 * viaja, se ve en el chat y en Tareas, donde cada asignatura sale como un grupo o una columna del
 * Kanban). El chat se busca por su nombre (el PC adopta uno así si no lleva su marca) y la lista por
 * el de la asignatura, sin mirar mayúsculas ni tildes. Borrar una asignatura del horario NO borra su
 * lista: las tareas son del usuario.
 */
object Asignaturas {
    const val NOMBRE_CHAT = "Asignaturas"

    private fun mismo(a: String, b: String) = Acciones.normalizar(a.trim()) == Acciones.normalizar(b.trim())

    fun chat(c: Context): Proyecto? =
        (c.applicationContext as? PixPinApp)?.proyectos?.proyectos?.value?.firstOrNull { !it.archivado && mismo(it.nombre, NOMBRE_CHAT) }

    /** El chat «Asignaturas», creándolo si no está. */
    fun asegurarChat(c: Context): Proyecto? {
        chat(c)?.let { return it }
        val repo = (c.applicationContext as? PixPinApp)?.proyectos ?: return null
        return repo.nuevo(NOMBRE_CHAT, System.currentTimeMillis())
    }

    /** La lista de tareas de [asignatura] en el chat, si está. */
    fun lista(c: Context, asignatura: String, mensajes: List<Mensaje> = MensajesStore(c).leer()): Mensaje? {
        val p = chat(c) ?: return null
        return mensajes.firstOrNull { it.proyecto == p.id && TodasLasTareas.esLista(it) && mismo(TodasLasTareas.nombreDeLista(it), asignatura) }
    }

    /** La lista de [asignatura], creándola (y el chat) si no está. Trabajo de disco. */
    fun asegurarLista(c: Context, asignatura: String): Mensaje? {
        lista(c, asignatura)?.let { return it }
        val p = asegurarChat(c) ?: return null
        val m = Mensaje(
            id = UUID.randomUUID().toString(), cuando = System.currentTimeMillis(), clase = Clase.MINIAPP,
            miniapp = MiniApp.TAREAS.id, proyecto = p.id, texto = Tareas.escribir(asignatura.trim(), emptyList())
        )
        MensajesStore(c).anadir(m)
        return m
    }

    /** **Apunta una tarea en [asignatura]**, entendida como en la caja de Tareas. Devuelve lo guardado. */
    fun apuntar(c: Context, asignatura: String, texto: String): String? {
        if (Tareas.saneado(texto).isEmpty()) return null
        val entendida = Frase.paraGuardar(texto, com.forge.pixpin.ia.Momento.ahora())
        val l = asegurarLista(c, asignatura) ?: return null
        MensajesStore(c).actualizar(l.id) { m -> m.copy(texto = TodasLasTareas.conTarea(m.texto, entendida)) }
        TareaRapidaActivity.fijarSiTieneHora(c, entendida)
        return entendida
    }

    /** Las tareas pendientes de [asignatura] (su texto crudo), para verlas en el horario. */
    fun pendientes(c: Context, asignatura: String): List<TodasLasTareas.Fila> =
        lista(c, asignatura)?.let { m -> TodasLasTareas.filasDe(m.texto).filter { !it.hecha } }.orEmpty()

    /** Marca hecha la tarea [f] de [asignatura]. */
    fun marcar(c: Context, asignatura: String, f: TodasLasTareas.Fila) {
        val l = lista(c, asignatura) ?: return
        MensajesStore(c).actualizar(l.id) { m -> TodasLasTareas.marcar(m.texto, f, true)?.let { m.copy(texto = it) } ?: m }
    }

    /** Los nombres de las asignaturas del horario (para elegir dónde va una tarea nueva). */
    fun nombres(c: Context): List<String> =
        runCatching { Horario.deDisco(c.filesDir, com.forge.pixpin.ia.Momento.ahora()).todasLasAsignaturas().map { it.nombre } }.getOrDefault(emptyList())
}
