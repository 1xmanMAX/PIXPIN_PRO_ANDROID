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

    /** Cómo se llama un lienzo sin nombre propio, como en el PC (`chat-lienzo`) y el Mac. */
    const val DE_SERIE = "Lienzo"

    /** Lo que se enseña de [DE_SERIE], en el idioma de la app («Canvas» en inglés). */
    private fun deSerieVisible(): String = com.forge.pixpin.ui.Textos.t(com.forge.pixpin.R.string.vistazo_lienzo, DE_SERIE)

    /**
     * **La extensión vieja del lienzo, que ya no se enseña** (1-nov-2026). El PC y el Mac guardan el
     * lienzo de un chat en `lienzos/<código>.excalidraw` y su mensaje `DIBUJO` sale con ese fichero
     * de nombre (`ventana_chat::escribir_lienzo`, `Nucleo.crearLienzo`); allí la fila se llama
     * «Lienzo» (`nombre_de_la_fila`). Aquí llegaba tal cual: «k7q2m9x3ab.excalidraw · 2 kB
     * EXCALIDRAW» en la burbuja (el usuario: «Excalidraw es una extensión que ya quitamos»). Lo que
     * hay en disco no cambia —el PC tampoco lo renombra y la sincronización empareja por la ruta—:
     * solo deja de verse.
     */
    private val EXTENSIONES_VIEJAS = listOf(".excalidraw.gz", ".excalidraw")

    /** [nombre] sin la extensión vieja del lienzo (`.excalidraw`, o `.excalidraw.gz` de este aparato). */
    fun sinExtension(nombre: String): String {
        val n = nombre.trim()
        val fin = EXTENSIONES_VIEJAS.firstOrNull { n.endsWith(it, ignoreCase = true) } ?: return n
        return n.dropLast(fin.length).trim()
    }

    /** Si [nombre] (de un mensaje o un fichero) es un `.excalidraw` suelto. */
    fun esExcalidraw(nombre: String?): Boolean =
        nombre != null && EXTENSIONES_VIEJAS.any { nombre.trim().endsWith(it, ignoreCase = true) }

    /**
     * **Si es el nombre de serie de un lienzo**: vacío, «Lienzo» (el del teléfono) o el fichero con
     * que lo crean el PC y el Mac (`<referencia>.excalidraw`). Es la regla de `nombre_de_la_fila` del
     * PC, y además un `.excalidraw` sin nada delante.
     */
    fun esDeSerie(nombre: String, referencia: String?): Boolean {
        val n = nombre.trim()
        if (n.isEmpty() || n == DE_SERIE) return true
        if (!esExcalidraw(n)) return false
        val base = sinExtension(n)
        return base.isEmpty() || base == referencia
    }

    /**
     * **El nombre que se enseña de un mensaje** (1-nov-2026): el de un lienzo, «Lienzo» si es el de
     * serie y si no el suyo sin `.excalidraw`; el de cualquier otro, sin un `.excalidraw` que traiga
     * detrás. [siVacio]: lo que se pone a lo que no es un lienzo y no tiene nombre.
     */
    fun visible(m: Mensaje, siVacio: String = ""): String = when {
        m.clase == Clase.DIBUJO -> if (esDeSerie(m.nombre, m.referencia)) deSerieVisible() else sinExtension(m.nombre)
        esExcalidraw(m.nombre) -> sinExtension(m.nombre).ifBlank { deSerieVisible() }
        else -> m.nombre.ifBlank { siVacio }
    }

    /**
     * **La etiqueta de un fichero en la sincronización** («#47·K7Q2 · k7q2m9x3ab.excalidraw», la
     * que da el otro aparato), con el lienzo dicho como en el chat: «#47·K7Q2 · Lienzo». [rel] es la
     * ruta del fichero (`pins/draw/<código>.excalidraw.gz`), que dice el código del lienzo.
     */
    fun deEtiqueta(etiqueta: String, rel: String): String {
        if (!esExcalidraw(etiqueta)) return etiqueta
        val dibujo = sinExtension(rel.substringAfterLast('/'))
        val sena = etiqueta.substringBeforeLast(" · ", "").let { if (it.isEmpty()) "" else "$it · " }
        val nombre = etiqueta.substringAfterLast(" · ")
        return sena + if (esDeSerie(nombre, dibujo)) deSerieVisible() else sinExtension(nombre)
    }

    /**
     * El nombre con que un lienzo entra como hoja de un proyecto: el suyo sin `.excalidraw`, o
     * «Lienzo» si es el de serie (no «k7q2m9x3ab.excalidraw»).
     */
    fun deLaHoja(m: Mensaje): String = if (esDeSerie(m.nombre, m.referencia)) DE_SERIE else sinExtension(m.nombre)

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
