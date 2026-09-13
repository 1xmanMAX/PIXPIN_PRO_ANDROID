package com.forge.pixpin.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Polyline
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.forge.pixpin.PixPinApp
import com.forge.pixpin.guardados.paginaAnotada
import com.forge.pixpin.motor.DialogoDeFuncionesWeb
import com.forge.pixpin.motor.DrawExport
import com.forge.pixpin.motor.DrawSvg
import com.forge.pixpin.motor.ExcalidrawStore
import com.forge.pixpin.motor.ExportarHtml
import com.forge.pixpin.motor.ExportarProyecto
import com.forge.pixpin.motor.HojasDelProyecto
import com.forge.pixpin.motor.PaquetePixpin
import com.forge.pixpin.motor.PdfDoc
import com.forge.pixpin.motor.Proyecto
import com.forge.pixpin.motor.Scene
import com.forge.pixpin.motor.getElementBounds
import com.forge.pixpin.pin.ImageStore
import java.io.File
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * **Lo que se comparte de unos proyectos, con todos sus formatos.** Ver [HojaDeCompartir].
 *
 * Todo lo compartible de PixPin cabe en «unas páginas de unos proyectos»: lo marcado en
 * proyectos, un proyecto entero, el lienzo abierto en el editor o una foto anotada del chat
 * (estos dos, envueltos en un proyecto de una sola hoja). Por eso los generadores viven aquí
 * una sola vez y los usan todos: el mismo PDF, la misma imagen y la misma página web salgan de
 * donde salgan.
 */
object CompartirPaginas {

    /**
     * Arma lo compartible. [seleccion] es cada proyecto con las claves de sus páginas marcadas, o
     * `null` para todas. **Lee escenas del disco**: fuera del hilo de la pantalla.
     *
     * [wifi] añade «Enviar por Wi-Fi»; [editable] es el proyecto del `.pixpin` (solo con uno).
     */
    fun de(
        contexto: Context,
        titulo: String,
        seleccion: List<Pair<Proyecto, Set<String>?>>,
        wifi: (() -> Unit)? = null,
        editable: Proyecto? = seleccion.singleOrNull()?.first,
        extras: List<Compartible.Formato> = emptyList(),
        /** Antes de generar nada: guardar lo que haya a medio escribir. */
        antes: () -> Unit = {}
    ): Compartible {
        val app = contexto.applicationContext as PixPinApp
        val escenas = HashMap<String, Scene?>()
        val escenaDe: (String) -> Scene? = { id -> synchronized(escenas) { escenas.getOrPut(id) { ExcalidrawStore.cargar(ExcalidrawStore.rutaDe(contexto, id)) } } }
        fun olvidarEscenas() = synchronized(escenas) { escenas.clear() }

        val varios = seleccion.size > 1
        val paginas = ArrayList<Compartible.Pagina>()
        val porClave = HashMap<String, Pair<Proyecto, HojasDelProyecto.Pagina>>()
        for ((p, claves) in seleccion) {
            val todas = HojasDelProyecto.conEntero(p, escenaDe)
            val suyas = if (claves == null) todas.filterNot { it.marco == null && todas.any { o -> o.hoja.id == it.hoja.id && o.marco != null } }
            else todas.filter { it.clave in claves }
            for (pg in suyas) {
                val clave = "${p.id}|${pg.clave}"
                porClave[clave] = p to pg
                val tipo = when {
                    pg.hoja.nota != null -> "Nota"
                    pg.hoja.tabla != null -> "Tabla"
                    pg.hoja.croquis != null -> "Croquis 3D"
                    pg.hoja.pagina != null -> "Página del PDF"
                    pg.marco != null -> "Marco"
                    else -> "Lienzo"
                }
                val nombre = when {
                    pg.hoja.pagina != null -> "Página ${pg.hoja.pagina!! + 1}"
                    pg.marco != null -> "Página ${pg.nombre}".takeIf { pg.nombre.all(Char::isDigit) } ?: pg.nombre
                    else -> pg.nombre.ifBlank { pg.hoja.nombre.ifBlank { tipo } }
                }
                paginas += Compartible.Pagina(clave, nombre, if (varios) "${p.nombre} · $tipo" else tipo, nivel = if (pg.marco != null) 1 else 0)
            }
        }
        // Al abrir, lo marcado; sin marcas, las páginas «normales» (no el entero duplicado).
        val alAbrir = if (seleccion.all { it.second == null }) paginas.filter { pg ->
            val (_, h) = porClave.getValue(pg.clave)
            !(h.marco == null && h.hoja.dibujo != null && paginas.any { o -> o.clave != pg.clave && porClave.getValue(o.clave).second.hoja.id == h.hoja.id })
        }.map { it.clave }.toSet() else paginas.map { it.clave }.toSet()

        fun agrupar(claves: List<String>): List<Pair<Proyecto, Set<String>>> =
            seleccion.map { it.first }.mapNotNull { p ->
                val suyas = claves.mapNotNull { k -> porClave[k]?.takeIf { it.first.id == p.id }?.second?.clave }.toSet()
                if (suyas.isEmpty()) null else p to suyas
            }

        val nombreBase = ExportarProyecto.nombreDeArchivo(titulo)
        val carpeta = File(contexto.cacheDir, "share").apply { mkdirs() }
        val imagenDe: (String) -> Bitmap? = { ruta -> ImageStore.load(ruta) }
        fun admiteDibujo(k: String) = porClave[k]?.second?.hoja?.let { it.dibujo != null || it.pagina != null } == true
        fun preparar() { antes(); olvidarEscenas() }

        val formatos = buildList {
            add(Compartible.Formato("imagen", Icons.Filled.Image, "Imagen", Compartible.UNA, ::admiteDibujo, generar = { claves ->
                preparar()
                val (p, pg) = porClave[claves.firstOrNull() ?: return@Formato null] ?: return@Formato null
                val mapa = mapaDe(contexto, p, pg, escenaDe) ?: return@Formato null
                val formato = app.ajustes.copyFormat
                val destino = File(carpeta, "$nombreBase - ${nombreDeLaPagina(pg)}.${formato.extension}")
                destino.outputStream().use { mapa.compress(formato.compresor, 100, it) }
                if (!mapa.isRecycled) mapa.recycle()
                Compartible.Salida(destino, formato.mime)
            }))
            add(Compartible.Formato("pdf", Icons.Filled.PictureAsPdf, "PDF", Compartible.VARIAS, generar = { claves ->
                preparar()
                val grupos = agrupar(claves)
                ExportarProyecto.aArchivo(contexto, grupos, titulo, escenaDe, imagenDe)?.let {
                    Compartible.Salida(it, "application/pdf", "${claves.size} ${if (claves.size == 1) "página" else "páginas"}")
                }
            }))
            add(Compartible.Formato("svg", Icons.Filled.Polyline, "SVG", Compartible.UNA, ::admiteDibujo, generar = { claves ->
                preparar()
                val (p, pg) = porClave[claves.firstOrNull() ?: return@Formato null] ?: return@Formato null
                val texto = svgDe(contexto, p, pg, escenaDe) ?: return@Formato null
                val destino = File(carpeta, "$nombreBase - ${nombreDeLaPagina(pg)}.svg")
                destino.writeText(texto)
                Compartible.Salida(destino, DrawSvg.MIME_TYPE)
            }))
            add(Compartible.Formato(
                "web", Icons.Filled.Language, "Página web", Compartible.VARIAS,
                generar = { claves ->
                    preparar()
                    val marcadas = app.ajustes.funcionesWeb ?: ExportarHtml.Opciones.NOMBRES.toSet()
                    val calidad = ExportarHtml.calidadDeAudio(marcadas)
                    val deNoche = (contexto.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                        android.content.res.Configuration.UI_MODE_NIGHT_YES
                    ExportarWebDe.archivo(contexto, agrupar(claves), ExportarHtml.Opciones.de(marcadas), deNoche, calidad)?.let {
                        Compartible.Salida(it, ExportarHtml.MIME_TYPE, ExportarWebDe.resumenDe(it, calidad).removePrefix("Página web: "))
                    }
                },
                versionDeAjustes = {
                    val ajustes by (app.settings.settings).collectAsState(initial = app.ajustes)
                    ajustes.funcionesWeb
                },
                ajustes = { cerrar ->
                    val ajustes by (app.settings.settings).collectAsState(initial = app.ajustes)
                    val marcadas = ajustes.funcionesWeb ?: ExportarHtml.Opciones.NOMBRES.toSet()
                    val alcance = androidx.compose.runtime.rememberCoroutineScope()
                    DialogoDeFuncionesWeb(
                        marcadas = marcadas,
                        onCambio = { clave, puesta ->
                            val ahora = marcadas.toMutableSet()
                            if (puesta) ahora += clave else ahora -= clave
                            alcance.launch { app.settings.setFuncionesWeb(ahora) }
                        },
                        onCompartir = cerrar,
                        onCerrar = cerrar,
                        calidadDeAudio = ExportarHtml.calidadDeAudio(marcadas),
                        onCalidadDeAudio = { c -> alcance.launch { app.settings.setFuncionesWeb(ExportarHtml.conCalidadDeAudio(marcadas, c)) } },
                        hayAudio = seleccion.any { (p, _) -> p.hojas.any { it.nota?.contains("![audio]") == true } },
                        hayLienzo = false,
                        hayTabla = seleccion.any { (p, _) -> p.hojas.any { it.tabla != null } }
                    )
                }
            ))
            if (editable != null) {
                add(Compartible.Formato("editable", Icons.Filled.FolderZip, "Editable", Compartible.NINGUNA, generar = {
                    preparar()
                    PaquetePixpin.escribir(
                        contexto, editable,
                        File(carpeta, ExportarProyecto.nombreDeArchivo(editable.nombre) + "." + PaquetePixpin.EXTENSION),
                        croquisDe = { id -> com.forge.pixpin.croquis3d.Croquis3DAlmacen.jsonDe(contexto, id) }
                    )?.let { Compartible.Salida(it, PaquetePixpin.MIME_TYPE, ".pixpin: se sigue editando en otro PixPin") }
                }))
            }
            if (wifi != null) add(Compartible.Formato("wifi", Icons.Filled.Wifi, "Enviar por Wi-Fi", Compartible.NINGUNA, accion = { antes(); wifi() }))
            addAll(extras)
        }
        return Compartible(titulo, paginas, formatos, alAbrir)
    }

    private fun nombreDeLaPagina(pg: HojasDelProyecto.Pagina): String = ExportarProyecto.nombreDeArchivo(
        when {
            pg.hoja.pagina != null -> "página ${pg.hoja.pagina!! + 1}"
            pg.marco != null -> pg.nombre
            else -> pg.hoja.nombre.ifBlank { "lienzo" }
        }
    )

    /** La página como imagen: el lienzo (o su marco) a doble tamaño, o la página del PDF con lo anotado. */
    private suspend fun mapaDe(contexto: Context, p: Proyecto, pg: HojasDelProyecto.Pagina, escenaDe: (String) -> Scene?): Bitmap? {
        val hoja = pg.hoja
        val proveedor: (Scene) -> (String) -> Bitmap? = { e -> { id -> e.files[id]?.path?.let { ImageStore.load(it) } } }
        if (hoja.pagina != null) {
            // Sobre la copia limpia: el documento ya lleva lo anotado y saldría dos veces.
            val pdf = listOfNotNull(p.pdfLimpio, p.pdfOrigen).firstOrNull { File(it).exists() } ?: return null
            return paginaAnotada(contexto, pdf, hoja.pagina!!, hoja.dibujo, hoja.dibujo?.let { ExcalidrawStore.rutaDe(contexto, it) }, 2000)
        }
        val escena = hoja.dibujo?.let(escenaDe) ?: return null
        val marco = pg.marco?.let { id -> escena.marcos.firstOrNull { it.id == id } }
        return DrawExport.aBitmap(escena, 2.0, recorte = marco?.let { getElementBounds(it) }, imageProvider = proveedor(escena))
    }

    private fun svgDe(contexto: Context, p: Proyecto, pg: HojasDelProyecto.Pagina, escenaDe: (String) -> Scene?): String? {
        val hoja = pg.hoja
        if (hoja.pagina != null) {
            val pdf = listOfNotNull(p.pdfLimpio, p.pdfOrigen).firstOrNull { File(it).exists() } ?: return null
            val escena = hoja.dibujo?.let(escenaDe) ?: Scene()
            val papel = PdfDoc.render(pdf, hoja.pagina!!, PdfDoc.PAGE_WIDTH)
            return DrawSvg.aTexto(contexto, escena, { id -> escena.files[id]?.path?.let { ImageStore.load(it) } }, papel, PdfDoc.paraLaWeb(pdf, hoja.pagina!!))
        }
        val escena = hoja.dibujo?.let(escenaDe) ?: return null
        val marco = pg.marco?.let { id -> escena.marcos.firstOrNull { it.id == id } }
        return DrawSvg.aTexto(contexto, escena, { id -> escena.files[id]?.path?.let { ImageStore.load(it) } }, soloEstaHoja = marco)
    }
}
