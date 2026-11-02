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

    /** La lista de la asignatura [nombre] entre los mensajes, si está (por su nombre). */
    fun lista(mensajes: List<Mensaje>, chat: String?, nombre: String): Mensaje? {
        if (chat == null) return null
        val n = Horario.norm(nombre.trim())
        return mensajes.firstOrNull {
            TodasLasTareas.esLista(it) && !it.enBuzon && it.proyecto == chat && Horario.norm(TodasLasTareas.nombreDeLista(it).trim()) == n
        }
    }

    /**
     * **La lista de la asignatura [a]** (2-nov-2026, `de_asignatura` del PC): la de su enlace (`lista:<id>`,
     * aunque la lista ya se llame de otra forma) y, si no lo tiene o esa lista ya no está, la de su nombre.
     */
    fun deAsignatura(mensajes: List<Mensaje>, chat: String?, a: Horario.Asignatura): Mensaje? =
        a.lista?.let { id -> mensajes.firstOrNull { it.id == id && TodasLasTareas.esLista(it) && !it.enBuzon } }
            ?: lista(mensajes, chat, a.nombre)

    /** Las tareas pendientes de la asignatura, como se leen. */
    fun pendientes(app: PixPinApp, nombre: String): List<String> {
        val m = lista(MensajesStore(app).leer(), chat(app), nombre) ?: return emptyList()
        return Tareas.leer(m.texto).filter { !it.hecha }.map { Tareas.legible(it.texto) }
    }

    /** Que no creen dos la misma lista a la vez (la rejilla al añadir y [enlazar] al guardar). */
    private val cerrojo = Any()

    /** La lista de tareas de la asignatura, creándola (y el chat) si no está; su id. Trabajo de disco. */
    fun asegurarLista(app: PixPinApp, nombre: String): String = synchronized(cerrojo) {
        val chat = chat(app) ?: app.proyectos.nuevo(NOMBRE_CHAT, System.currentTimeMillis()).id
        val almacen = MensajesStore(app)
        lista(almacen.leer(), chat, nombre)?.let { return it.id }
        val id = UUID.randomUUID().toString()
        almacen.anadir(
            Mensaje(
                id = id, cuando = System.currentTimeMillis(), clase = Clase.MINIAPP, nombre = nombre.trim(),
                miniapp = MiniApp.TAREAS.id, texto = Tareas.escribir(nombre.trim(), emptyList()), proyecto = chat
            )
        )
        id
    }

    /**
     * **Renombra la lista** de una asignatura renombrada (`renombrar` del PC): cambia su título y su nombre y
     * deja sus tareas como estaban. Es un mensaje reescrito: el nombre nuevo viaja al PC como cualquier cambio.
     */
    fun renombrar(app: PixPinApp, id: String, nuevo0: String) {
        val nuevo = nuevo0.trim()
        if (nuevo.isEmpty()) return
        val almacen = MensajesStore(app)
        almacen.actualizar(id) { m ->
            if (!TodasLasTareas.esLista(m) || TodasLasTareas.nombreDeLista(m) == nuevo) m
            else m.copy(texto = Tareas.escribir(nuevo, Tareas.leer(m.texto)), nombre = nuevo)
        }
    }

    /** Lo que hay que hacer para que cada asignatura tenga su lista, ya decidido ([plan]). */
    sealed class Paso {
        /** Crear la lista de la asignatura (no hay ninguna con su enlace ni con su nombre). */
        data class Crear(val nombre: String) : Paso()
        /** La lista [codigo] es la de la asignatura [nombre], que no lo tenía apuntado. */
        data class Enlazar(val codigo: String, val nombre: String) : Paso()
        /** La asignatura se renombró: la lista [codigo] pasa a llamarse [nombre]. */
        data class Renombrar(val codigo: String, val nombre: String) : Paso()
    }

    /**
     * **Qué falta** para que cada asignatura del horario tenga su lista y el enlace apuntado (`plan` del PC).
     * [conLinea]: las de la lista de asignaturas (con línea donde apuntar el enlace); [soloEnClases]: las
     * que solo nombra alguna clase. Manda el nombre del horario: si la lista enlazada se llama de otra
     * forma, se renombra.
     */
    fun plan(conLinea: List<Horario.Asignatura>, soloEnClases: List<Horario.Asignatura>, mensajes: List<Mensaje>, chat: String?): List<Paso> {
        val pasos = mutableListOf<Paso>()
        for (a in conLinea) {
            val l = deAsignatura(mensajes, chat, a)
            when {
                l == null -> pasos += Paso.Crear(a.nombre)
                a.lista == l.id -> if (TodasLasTareas.nombreDeLista(l).trim() != a.nombre.trim()) pasos += Paso.Renombrar(l.id, a.nombre)
                else -> pasos += Paso.Enlazar(l.id, a.nombre)
            }
        }
        for (a in soloEnClases) if (lista(mensajes, chat, a.nombre) == null) pasos += Paso.Crear(a.nombre)
        return pasos
    }

    /**
     * **Cada asignatura con su lista** (2-nov-2026, `enlazar` del PC; el usuario: «que cada asignatura se
     * cree a la vez como asignatura y como grupo de tareas»): crea las que faltan, apunta su enlace
     * `lista:<id>` en `horario.md`, renombra la lista de una asignatura renombrada. No borra nada. Lo
     * llaman guardar el horario y el arranque. Devuelve el texto del horario como queda. Trabajo de disco.
     */
    fun enlazar(app: PixPinApp, original: String): String = synchronized(cerrojo) {
        val h = Horario.leer(original, com.forge.pixpin.ia.Momento.deMsLocales(AvisosDelHorario.ahoraLocal()))
        val todas = h.todasLasAsignaturas()
        val soloEnClases = todas.drop(h.asignaturas.size)
        val almacen = MensajesStore(app)
        val pasos = plan(h.asignaturas, soloEnClases, almacen.leer(), chat(app))
        val enlaces = HashMap<String, String>()
        for (p in pasos) when (p) {
            is Paso.Crear -> enlaces[Horario.norm(p.nombre.trim())] = asegurarLista(app, p.nombre)
            is Paso.Enlazar -> enlaces[Horario.norm(p.nombre.trim())] = p.codigo
            is Paso.Renombrar -> renombrar(app, p.codigo, p.nombre)
        }
        Horario.conEnlaces(original) { a -> enlaces[Horario.norm(a.nombre.trim())] }
    }

    /**
     * **La lista de la asignatura que se llama [nombre]** (sin mirar mayúsculas ni tildes; `lista_de_la_asignatura`
     * del PC): si está en el horario, la de su enlace; si no, o aún no tiene, la de su nombre, creándola. Su id.
     * Lo usan los pedidos (`anadir_tarea` con `asignatura`, `abrir_lista`). Trabajo de disco.
     */
    fun listaDeLaAsignatura(app: PixPinApp, nombre: String): String {
        val n = Horario.norm(nombre.trim())
        val a = AvisosDelHorario.leer(app).todasLasAsignaturas().firstOrNull { Horario.norm(it.nombre) == n }
        a?.let { deAsignatura(MensajesStore(app).leer(), chat(app), it)?.let { m -> return m.id } }
        return asegurarLista(app, a?.nombre ?: nombre.trim())
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
