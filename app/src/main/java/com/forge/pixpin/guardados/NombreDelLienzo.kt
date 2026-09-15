package com.forge.pixpin.guardados

import com.forge.pixpin.PixPinApp

/**
 * **El nombre de un lienzo, en todos los sitios a la vez** (14-sep-2026).
 *
 * Un lienzo sale en tres sitios con nombre propio: la hoja del proyecto, la misma hoja en otro
 * proyecto al que se reenvió, y el mensaje del chat que lo trae. Cambiarlo en uno y no en los
 * otros hacía que el buscador del chat —que busca por el nombre del mensaje— no lo encontrara
 * por el nombre nuevo. Aquí se cambian todos.
 */
object NombreDelLienzo {

    /** El nombre de la hoja [hojaId] de [proyectoId], y el de todo lo que señala lo mismo. */
    fun deHoja(app: PixPinApp, proyectoId: String, hojaId: String, nombre: String) {
        val limpio = nombre.trim().take(80)
        if (limpio.isEmpty()) return
        val p = app.proyectos.porId(proyectoId) ?: return
        val hoja = p.hojas.firstOrNull { it.id == hojaId } ?: return
        val ahora = System.currentTimeMillis()
        when {
            hoja.dibujo != null -> deDibujo(app, hoja.dibujo, limpio)
            hoja.tabla != null -> {
                app.proyectos.proyectos.value.filter { q -> q.hojas.any { it.tabla == hoja.tabla } }.forEach { q ->
                    app.proyectos.guardar(q.copy(hojas = q.hojas.map { if (it.tabla == hoja.tabla) it.copy(nombre = limpio) else it }, tocado = ahora))
                }
                enElChat(app) { it.referencia == hoja.tabla && it.clase == Clase.TABLA || it.id == hoja.deMensaje }
                    .forEach { id -> MensajesStore(app).actualizar(id) { it.copy(nombre = limpio) } }
            }
            else -> {
                app.proyectos.guardar(p.copy(hojas = p.hojas.map { if (it.id == hojaId) it.copy(nombre = limpio) else it }, tocado = ahora))
                if (hoja.deMensaje != null) MensajesStore(app).actualizar(hoja.deMensaje) { it.copy(nombre = limpio) }
            }
        }
    }

    /** Todas las hojas que dibujan en [dibujo], y los mensajes del chat que lo traen. */
    fun deDibujo(app: PixPinApp, dibujo: String, nombre: String) {
        val limpio = nombre.trim().take(80)
        if (limpio.isEmpty()) return
        val ahora = System.currentTimeMillis()
        val hojas = HashSet<String>()
        app.proyectos.proyectos.value.filter { q -> q.hojas.any { it.dibujo == dibujo } }.forEach { q ->
            q.hojas.filter { it.dibujo == dibujo }.forEach { h -> h.deMensaje?.let { hojas += it } }
            app.proyectos.guardar(q.copy(hojas = q.hojas.map { if (it.dibujo == dibujo) it.copy(nombre = limpio) else it }, tocado = ahora))
        }
        val almacen = MensajesStore(app)
        enElChat(app) { (it.clase == Clase.DIBUJO && it.referencia == dibujo) || it.id in hojas }
            .forEach { id -> almacen.actualizar(id) { it.copy(nombre = limpio) } }
    }

    /** El nombre que lleva [dibujo] en los proyectos, si está en alguno. */
    fun actual(app: PixPinApp, dibujo: String): String? =
        app.proyectos.proyectos.value.firstNotNullOfOrNull { q ->
            q.hojas.firstOrNull { it.dibujo == dibujo }?.nombre?.takeIf { it.isNotBlank() }
        }

    private fun enElChat(app: PixPinApp, cual: (Mensaje) -> Boolean): List<String> =
        MensajesStore(app).leer().filter(cual).map { it.id }
}
