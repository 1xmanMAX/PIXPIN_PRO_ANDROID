package com.forge.pixpin.guardados

import android.app.Activity
import android.content.Context
import android.widget.Toast
import com.forge.pixpin.motor.ExcalidrawStore
import com.forge.pixpin.motor.FigurasDeSerie
import com.forge.pixpin.motor.HojaDePlantilla
import com.forge.pixpin.motor.Scene
import java.util.UUID

/**
 * **Las carpetas de páginas en el disco del chat** (25-oct-2026, portado del PC: `ventana_chat::
 * carpetas` y `carpeta_de_paginas::cambiar_en_disco`). Lo puro está en [CarpetaDePaginas]; aquí,
 * leer y escribir el mensaje de la carpeta bajo la llave del chat ([MensajesStore.cambiar]), crear
 * una, meter una hoja y la «Nueva página» con la plantilla de la última.
 *
 * Todo lee y escribe el disco: **fuera del hilo de la pantalla**.
 */
object CarpetasDelChat {

    /** El nombre de una carpeta nueva, como en el PC (`carpeta-nombre-de-serie`). */
    const val NOMBRE_DE_SERIE = "Cuaderno"

    /** El título de serie de una página sin número que seguir (`carpeta-pagina`). */
    const val PAGINA = "Página"

    /**
     * El catálogo de plantillas por su título, hecho una vez: son las 627 de serie y mirarlas
     * todas en cada «Nueva página» sería tirar el tiempo.
     */
    @Volatile private var catalogo: HojaDePlantilla.Catalogo? = null

    fun catalogo(context: Context): HojaDePlantilla.Catalogo =
        catalogo ?: synchronized(this) {
            catalogo ?: HojaDePlantilla.Catalogo(FigurasDeSerie.todas(context.applicationContext)).also { catalogo = it }
        }

    /**
     * **Cambia la carpeta [id]**, con la llave del chat tomada: lee, deja que [cambio] la toque (null
     * es «no cambió») y, si cambió, la reescribe. Las páginas cuyo mensaje ya no está se quitan de
     * paso. Devuelve la carpeta como quedó, o null si el mensaje ya no está o no es una carpeta que
     * se entienda (entonces no se escribe nada: reescribir lo que no se entiende sería perderlo).
     */
    fun cambiar(
        context: Context,
        id: String,
        cambio: (CarpetaDePaginas) -> CarpetaDePaginas?
    ): CarpetaDePaginas? {
        var resultado: CarpetaDePaginas? = null
        MensajesStore(context).cambiar { todos ->
            val i = todos.indexOfFirst { it.id == id }
            val m = todos.getOrNull(i) ?: return@cambiar todos
            val leida = CarpetaDePaginas.deMensaje(m) ?: return@cambiar todos
            val existen = todos.mapTo(HashSet()) { it.id }
            val (podada, quitadas) = leida.podar { it in existen }
            val cambiada = cambio(podada)
            val final = cambiada ?: podada
            resultado = final
            if (cambiada == null && quitadas == 0) todos
            else todos.toMutableList().also { it[i] = m.copy(texto = final.escribir()) }
        }
        return resultado
    }

    /**
     * **El nombre con que entra una hoja**: el suyo sin extensión; vacío si es el de serie
     * («Lienzo», un `.excalidraw`), para que mande el título de la serie (`nombre_de_hoja` del PC).
     */
    fun nombreDeHoja(m: Mensaje): String {
        val n = m.nombre.ifBlank { m.ruta?.substringAfterLast('/').orEmpty() }.trim()
        if (n.isEmpty() || n == "Lienzo" || n.endsWith(".excalidraw")) return ""
        return n.replace(EXTENSION, "").ifBlank { n }
    }

    /** Una extensión con al menos una letra, como en [Renombrar]: «Plano v2.0» no la tiene. */
    private val EXTENSION = Regex("\\.(?=[A-Za-z0-9]*[A-Za-z])[A-Za-z0-9]{1,5}$")

    /**
     * **Una carpeta nueva al final del chat [chat]**, con [primera] dentro si se da («Nueva carpeta
     * con esta página»). Devuelve el mensaje escrito.
     */
    fun crear(context: Context, chat: String?, primera: Mensaje? = null): Mensaje {
        val almacen = MensajesStore(context)
        val todos = almacen.leer()
        val delChat = todos.filter { it.proyecto == chat }
        val nombre = CarpetaDePaginas.nombreLibre(NOMBRE_DE_SERIE, delChat)
        val carpeta = primera?.takeIf(CarpetaDePaginas::puedeIr)?.let { p ->
            val c = CarpetaDePaginas()
            c.anadir(p.id, c.tituloAlAnadir(nombreDeHoja(p), PAGINA))
        } ?: CarpetaDePaginas()
        // El id es el milisegundo, como en el PC, y no el de otro mensaje (de todos los chats:
        // el id es único en el archivo entero).
        val cuando = CarpetaDePaginas.cuandoLibre(System.currentTimeMillis(), todos)
        val m = CarpetaDePaginas.mensajeNuevo(nombre, cuando, chat, carpeta)
        almacen.anadir(m)
        return m
    }

    /** Lo que pasó al meter una hoja en una carpeta. */
    sealed interface Anadida {
        data class Puesta(val carpeta: String, val titulo: String) : Anadida
        data class YaEstaba(val carpeta: String) : Anadida
        data object NoEsta : Anadida
    }

    /** **Mete la hoja [m] al final de la carpeta [carpeta]**, con el título de la serie o el suyo. */
    fun anadir(context: Context, carpeta: Mensaje, m: Mensaje): Anadida {
        var titulo = ""
        var yaEstaba = false
        cambiar(context, carpeta.id) { c ->
            if (c.contiene(m.id)) { yaEstaba = true; return@cambiar null }
            // **A una grabación entra como adjunto a la clase**, con su nombre (25-oct-2026, PC
            // e1150e8): no es de lo grabado y no lleva minuto, así que se ve aparte.
            if (c.esGrabacion) {
                titulo = nombreDeHoja(m).ifBlank { m.nombre.trim() }
                return@cambiar c.adjuntar(m.id, titulo)
            }
            titulo = c.tituloAlAnadir(nombreDeHoja(m), PAGINA)
            c.anadir(m.id, titulo)
        } ?: return Anadida.NoEsta
        val nombre = carpeta.nombre.ifBlank { NOMBRE_DE_SERIE }
        return if (yaEstaba) Anadida.YaEstaba(nombre) else Anadida.Puesta(nombre, titulo)
    }

    /**
     * **«Adjuntar a la clase»** (25-oct-2026, `adjuntar_a_la_clase` del PC): el fichero [uri] entra en el
     * chat como si se compartiera a él y en la carpeta de la grabación [carpetaId] como adjunto, con
     * su nombre sin extensión. No sale suelto en el chat: está en la carpeta. Devuelve ese nombre, o
     * null si no entró.
     */
    fun adjuntarALaClase(context: Context, carpetaId: String, uri: android.net.Uri): String? {
        val carpeta = MensajesStore(context).leer().firstOrNull { it.id == carpetaId } ?: return null
        val m = AlChat.meterYDevolver(context, uri, carpeta.proyecto) ?: return null
        val titulo = m.nombre.replace(EXTENSION, "").ifBlank { m.nombre }
        cambiar(context, carpetaId) { it.adjuntar(m.id, titulo) } ?: return null
        return m.nombre
    }

    /** **El detalle de una grabación**: «1:30:00 · 5 capturas» (`carpeta-grabacion-detalle` del PC). */
    fun detalleDeGrabacion(c: CarpetaDePaginas): String {
        val n = c.capturas().size
        return GrabarClase.minuto(c.duracionMs) + " · " + if (n == 1) "1 captura" else "$n capturas"
    }

    /**
     * **La página siguiente de una carpeta** (`pagina_siguiente` del PC): un lienzo nuevo en el
     * chat con la plantilla de la última página que sea un lienzo, vacía
     * ([HojaDePlantilla.lienzoSiguiente]), o en blanco si no tiene; con el título siguiente («Día 3»
     * tras «Día 2»), y metido al final de la carpeta. Devuelve el mensaje del lienzo, o null si la
     * carpeta ya no está.
     */
    fun paginaSiguiente(context: Context, carpetaId: String): Mensaje? {
        val almacen = MensajesStore(context)
        val todos = almacen.leer()
        val carpeta = todos.firstOrNull { it.id == carpetaId } ?: return null
        val c = CarpetaDePaginas.deMensaje(carpeta) ?: return null
        val plantilla = c.vivas(todos).asReversed()
            .map { (i, _) -> todos[i] }
            .firstOrNull { it.clase == Clase.DIBUJO && it.referencia != null }
            ?.referencia
            ?.let { ExcalidrawStore.cargar(ExcalidrawStore.rutaDe(context, it)) }
            ?.let { HojaDePlantilla.lienzoSiguiente(it, catalogo(context)) }
        val titulo = c.podar { id -> todos.any { it.id == id } }.first.tituloSiguiente(PAGINA)
        val ahora = System.currentTimeMillis()
        val dibujo = "dib-$ahora"
        ExcalidrawStore.guardar(context, dibujo, plantilla ?: Scene()) ?: return null
        val m = Mensaje(
            id = UUID.randomUUID().toString(),
            cuando = ahora,
            clase = Clase.DIBUJO,
            nombre = titulo,
            referencia = dibujo,
            proyecto = carpeta.proyecto
        )
        almacen.anadir(m)
        cambiar(context, carpetaId) { it.anadir(m.id, titulo) }
        return m
    }

    /**
     * **«Añadir a carpeta…»** del menú de un mensaje (`menu_de_anadir` del PC): las carpetas del
     * chat —la que ya la lleva, marcada— y «Nueva carpeta con esta página». Un diálogo de los de
     * siempre, para no tocar la pantalla del chat más que con la entrada del menú.
     */
    fun preguntarDonde(activity: Activity, m: Mensaje, alAcabar: () -> Unit = {}) {
        if (!CarpetaDePaginas.puedeIr(m)) return
        Thread {
            val carpetas = runCatching { MensajesStore(activity).leer() }.getOrDefault(emptyList())
                .filter { CarpetaDePaginas.esCarpeta(it) && it.proyecto == m.proyecto }
            val dentro = carpetas.map { CarpetaDePaginas.deMensaje(it)?.contiene(m.id) == true }
            activity.runOnUiThread {
                if (activity.isFinishing) return@runOnUiThread
                val nombres = carpetas.mapIndexed { i, c ->
                    (if (dentro[i]) "✓ " else "") + c.nombre.ifBlank { NOMBRE_DE_SERIE }
                } + "＋ Nueva carpeta con esta página"
                android.app.AlertDialog.Builder(activity)
                    .setTitle("Añadir a carpeta")
                    .setItems(nombres.toTypedArray()) { _, cual ->
                        Thread {
                            val aviso = if (cual >= carpetas.size) {
                                val nueva = crear(activity, m.proyecto, m)
                                "Nueva carpeta «${nueva.nombre}»"
                            } else when (val r = anadir(activity, carpetas[cual], m)) {
                                is Anadida.Puesta -> "Añadida a «${r.carpeta}» como «${r.titulo}»"
                                is Anadida.YaEstaba -> "Ya está en «${r.carpeta}»"
                                Anadida.NoEsta -> "Esa carpeta ya no está en el chat"
                            }
                            activity.runOnUiThread {
                                Toast.makeText(activity, aviso, Toast.LENGTH_SHORT).show()
                                alAcabar()
                            }
                        }.start()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }.start()
    }
}
