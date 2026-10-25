package com.forge.pixpin.horario

import com.forge.pixpin.PixPinApp
import com.forge.pixpin.guardados.Clase
import com.forge.pixpin.guardados.Mensaje
import com.forge.pixpin.guardados.MensajesStore
import com.forge.pixpin.mini.MiniApp
import com.forge.pixpin.mini.Tareas
import com.forge.pixpin.mini.TodasLasTareas
import java.util.UUID

/**
 * **Cada asignatura es un grupo de tareas** (25-oct-2026, `horario/asignaturas.rs` del PC; el
 * usuario: «que cada asignatura se cree como un grupo de tareas»).
 *
 * POR QUÉ así: en PixPin un «grupo de tareas» ya es una lista de tareas de un chat (la mini-app
 * Tareas), y la pantalla de Tareas, los recordatorios y la sincronización trabajan sobre ellas.
 * Así que una asignatura no inventa otro almacén: es **una lista de tareas con su nombre en el
 * chat «Asignaturas»**, el mismo chat que crea el PC (y que viaja: si ya llegó del PC, se usa ese).
 * Se busca por nombre: el teléfono no guarda la marca del índice que pone el PC, y el nombre es lo
 * que el PC busca cuando no hay marca.
 *
 * La lista nace al crear la asignatura, y la que se busca es la del mismo nombre sin mirar
 * mayúsculas ni tildes. Borrar una asignatura del horario NO borra su lista: las tareas son del
 * usuario y se quitan en Tareas.
 */
object Asignaturas {

    /** Cómo se llama el chat de las asignaturas (el mismo que en el PC). */
    const val NOMBRE_CHAT = "Asignaturas"

    /** El chat «Asignaturas», si ya está. */
    fun chat(app: PixPinApp): String? = app.proyectos.proyectos.value.firstOrNull { it.nombre == NOMBRE_CHAT }?.id

    /** La lista de la asignatura [nombre] entre los mensajes, si está. */
    fun lista(mensajes: List<Mensaje>, chat: String?, nombre: String): Mensaje? {
        if (chat == null) return null
        val n = Horario.norm(nombre.trim())
        return mensajes.firstOrNull {
            TodasLasTareas.esLista(it) && !it.enBuzon && it.proyecto == chat && Horario.norm(TodasLasTareas.nombreDeLista(it).trim()) == n
        }
    }

    /** Las tareas pendientes de la asignatura, como se leen. */
    fun pendientes(app: PixPinApp, nombre: String): List<String> {
        val m = lista(MensajesStore(app).leer(), chat(app), nombre) ?: return emptyList()
        return Tareas.leer(m.texto).filter { !it.hecha }.map { Tareas.legible(it.texto) }
    }

    /** La lista de tareas de la asignatura, creándola (y el chat) si no está. Trabajo de disco. */
    fun asegurarLista(app: PixPinApp, nombre: String) {
        val chat = chat(app) ?: app.proyectos.nuevo(NOMBRE_CHAT, System.currentTimeMillis()).id
        val almacen = MensajesStore(app)
        if (lista(almacen.leer(), chat, nombre) != null) return
        almacen.anadir(
            Mensaje(
                id = UUID.randomUUID().toString(), cuando = System.currentTimeMillis(), clase = Clase.MINIAPP,
                miniapp = MiniApp.TAREAS.id, texto = Tareas.escribir(nombre.trim(), emptyList()), proyecto = chat
            )
        )
    }

    /** **Apunta una tarea en la asignatura** (creando su lista si hace falta). Trabajo de disco. */
    fun apuntar(app: PixPinApp, nombre: String, tarea: String) {
        val t = com.forge.pixpin.mini.TareaInteligente.paraApuntar(tarea)
        if (t.isEmpty()) return
        asegurarLista(app, nombre)
        val almacen = MensajesStore(app)
        val m = lista(almacen.leer(), chat(app), nombre) ?: return
        almacen.actualizar(m.id) { it.copy(texto = TodasLasTareas.conTarea(it.texto, t)) }
    }
}
