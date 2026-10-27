package com.forge.pixpin.planos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Architecture
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PictureAsPdf
import com.forge.pixpin.motor.Scene
import com.forge.pixpin.ui.Compartible
import com.forge.pixpin.ui.ExportarVisor
import com.forge.pixpin.ui.FormatosDeCompartir
import java.io.File

/**
 * **Exportar un plano DWG/DXF** (14-oct-2026; lo del PC del 13-oct, `plano_cad/exportar.rs`). Los
 * formatos del visor para la hoja de compartir de siempre ([com.forge.pixpin.ui.HojaDeCompartir]):
 *
 * - **Página web**: el plano en vectores **por capas** que se encienden y apagan, con grosores,
 *   cotas, marcos y lo anotado encima ([PaginaDelPlano]; la misma página que hace el PC).
 * - **PDF**: en vectores, una hoja por marco —o el plano entero—, con las cotas y lo anotado
 *   encima, como al imprimir ([ImprimirPlano]).
 * - **PNG / JPG**: una hoja (un marco o el plano entero), como al imprimir.
 * - **DXF** (de un DWG): lo escribe la lectura nativa (`opencadcodec`).
 * - **Original** y **.pixpin**: los comunes ([ExportarVisor]).
 *
 * Lo pesado —leer el plano por capas, pasarlo a DXF— va en el proceso aparte, como al abrir
 * ([LectorDePlanos]): un DWG roto solo tumba ese proceso.
 */
object ExportarPlano {

    /**
     * Lo que hace falta para exportar: el archivo y su modelo, **la capa en memoria** ([escena], con
     * su [encaje]; las cotas y los marcos dentro se quitan, que van con sus medidas), y las cotas y
     * los marcos de ahora (coordenadas respecto al origen del modelo).
     */
    class Plano(
        val archivo: File,
        val nombre: String,
        val modelo: ModeloCad,
        val escena: Scene?,
        val encaje: CapaDelPlano.Encaje,
        val cotas: List<List<DoubleArray>>,
        val marcos: List<ImprimirPlano.Marco>,
    ) {
        val titulo: String get() = nombre.substringBeforeLast('.').ifBlank { "Plano" }
        /** Lo anotado, sin lo del plano; null si no hay nada. */
        val anotado: Scene? get() = escena?.let(CapaDelPlano::sinLoDelPlano)?.takeIf { s -> s.elements.any { !it.isDeleted } }
    }

    private const val ENTERO = "entero"

    /** El lado largo de una hoja del PDF: un A3, en puntos (como el PC). */
    private const val LADO = 1190.55f
    private const val MARGEN = 28f
    /** El lado largo de la imagen, en píxeles. */
    private const val LADO_IMAGEN = 3200

    fun compartible(c: Context, p: Plano): Compartible {
        val paginas = buildList {
            add(Compartible.Pagina(ENTERO, "Plano entero", "Lo que importa del plano"))
            p.marcos.forEachIndexed { i, _ -> add(Compartible.Pagina("m$i", "Hoja ${i + 1}", "Marco para imprimir", nivel = 1)) }
        }
        val alAbrir = if (p.marcos.isNotEmpty()) p.marcos.indices.map { "m$it" }.toSet() else setOf(ENTERO)
        val formatos = buildList {
            add(Compartible.Formato("web", Icons.Filled.Language, "Página web", Compartible.NINGUNA, generar = { web(c, p) }))
            add(Compartible.Formato("pdf", Icons.Filled.PictureAsPdf, "PDF", Compartible.VARIAS, generar = { claves -> pdf(c, p, claves) }))
            addAll(ExportarVisor.imagenes(c, { claves -> p.titulo + nombreDeHoja(p, claves.firstOrNull()) }, Compartible.UNA) { claves ->
                imagen(c, p, claves.firstOrNull() ?: ENTERO)
            })
            if (p.nombre.lowercase().endsWith(".dwg")) add(Compartible.Formato("dxf", Icons.Filled.Architecture, "DXF", Compartible.NINGUNA, generar = { dxf(c, p) }))
            add(ExportarVisor.original(c, p.archivo.absolutePath, p.nombre, "El plano tal cual, sin cotas ni lo anotado"))
            add(ExportarVisor.pixpin(c, p.archivo.absolutePath, p.nombre))
            add(ExportarVisor.wifi(c, p.archivo.absolutePath, p.nombre))
        }
        return Compartible(p.titulo, paginas, formatos, alAbrir, FormatosDeCompartir.Contenido.PLANO)
    }

    private fun nombreDeHoja(p: Plano, clave: String?): String = marcoDe(p, clave)?.let { " - hoja ${p.marcos.indexOf(it) + 1}" } ?: ""

    private fun marcoDe(p: Plano, clave: String?): ImprimirPlano.Marco? = clave?.removePrefix("m")?.toIntOrNull()?.let { p.marcos.getOrNull(it) }

    /** La parte del plano de una página: su marco, o lo que importa del plano. */
    private fun parte(p: Plano, clave: String): ImprimirPlano.Marco =
        marcoDe(p, clave) ?: p.modelo.cajaUtil.let { u -> ImprimirPlano.Marco.entre(u[0].toDouble(), u[1].toDouble(), u[2].toDouble(), u[3].toDouble()) }

    /** Lo anotado en una hoja de papel, con el motor (lo mismo que al imprimir desde el visor). */
    private fun tinta(c: Context, p: Plano): ((Canvas, Double, Double, Double, Float, Float) -> Unit)? {
        val e = p.anotado ?: return null
        val enc = p.encaje
        val pintor = com.forge.pixpin.motor.Renderer(
            { f -> e.files[f]?.path?.let { com.forge.pixpin.pin.ImageStore.load(it) } },
            com.forge.pixpin.motor.DrawFonts.provider(c.applicationContext), dark = false
        )
        return { lienzo, x0, y1, s, w, h ->
            pintor.renderScene(
                lienzo,
                e.copy(viewport = com.forge.pixpin.motor.Viewport(scrollX = (enc.ceroX - x0) / enc.u, scrollY = (y1 - enc.ceroY) / enc.u, zoom = enc.u * s)),
                w.toDouble(), h.toDouble()
            )
        }
    }

    /** El tamaño de la hoja (puntos) para [m]: el lado largo, un A3, y el otro a su medida. */
    private fun hojaPara(m: ImprimirPlano.Marco): Pair<Float, Float> {
        val mw = m.ancho.coerceAtLeast(1e-9); val mh = m.alto.coerceAtLeast(1e-9)
        val s = (LADO - 2 * MARGEN) / maxOf(mw, mh)
        return (mw * s + 2 * MARGEN).toFloat() to (mh * s + 2 * MARGEN).toFloat()
    }

    private val SIN_LAMINA = ImprimirPlano.Lamina(conMembrete = false, escalaNormal = false)

    // ------------------------------------------------------------------ PDF

    private fun pdf(c: Context, p: Plano, claves: List<String>): Compartible.Salida? {
        val partes = (claves.ifEmpty { listOf(ENTERO) }).map { parte(p, it) }
        val t = tinta(c, p)
        val destino = File(ExportarVisor.carpeta(c), ExportarVisor.nombre(p.titulo, "pdf"))
        val doc = android.graphics.pdf.PdfDocument()
        try {
            partes.forEachIndexed { i, m ->
                val (w, h) = hojaPara(m)
                val hoja = doc.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(w.toInt(), h.toInt(), i + 1).create())
                ImprimirPlano.hoja(hoja.canvas, p.modelo, m, p.cotas, w.toInt().toFloat(), h.toInt().toFloat(), SIN_LAMINA, i + 1, partes.size, t)
                doc.finishPage(hoja)
            }
            destino.outputStream().buffered().use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
        val n = partes.size
        return Compartible.Salida(destino, "application/pdf", "En vectores · $n ${if (n == 1) "hoja" else "hojas"}" + if (t != null) " · con lo anotado" else "")
    }

    // ------------------------------------------------------------------ imagen

    private fun imagen(c: Context, p: Plano, clave: String): Bitmap? {
        val m = parte(p, clave)
        val (w, h) = hojaPara(m)
        val k = LADO_IMAGEN / maxOf(w, h)
        val mapa = runCatching { Bitmap.createBitmap((w * k).toInt().coerceAtLeast(1), (h * k).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888) }.getOrNull() ?: return null
        val lienzo = Canvas(mapa)
        lienzo.scale(k, k)
        ImprimirPlano.hoja(lienzo, p.modelo, m, p.cotas, w, h, SIN_LAMINA, 1, 1, tinta(c, p))
        return mapa
    }

    // ------------------------------------------------------------------ DXF

    private fun dxf(c: Context, p: Plano): Compartible.Salida? {
        val r = LectorDePlanos.aDxf(c, p.archivo)
        val hecho = (r as? LectorDePlanos.Companion.Resultado.Listo)?.archivo
            ?: throw IllegalStateException((r as? LectorDePlanos.Companion.Resultado.Fallo)?.motivo ?: "sin DXF")
        val destino = File(ExportarVisor.carpeta(c), ExportarVisor.nombre(p.titulo, "dxf"))
        hecho.copyTo(destino, overwrite = true)
        return Compartible.Salida(destino, "image/vnd.dxf", "Se abre en cualquier programa de CAD (sin cotas ni lo anotado)")
    }

    // ------------------------------------------------------------------ página web

    private fun web(c: Context, p: Plano): Compartible.Salida? {
        val m = p.modelo
        // Por capas, de la caché o del proceso aparte; si no se puede (una lectura de antes, un
        // archivo raro), el modelo del visor como una sola capa.
        val porCapas = (LectorDePlanos.leerPorCapas(c, p.archivo) as? LectorDePlanos.Companion.Resultado.Listo)
            ?.archivo?.let(PaginaDelPlano::leer)?.takeIf { it.grupos.isNotEmpty() }
        val grupos = porCapas?.grupos ?: PaginaDelPlano.sinCapas(m)
        val anotado = p.anotado?.let { e ->
            val svg = com.forge.pixpin.motor.DrawSvg.aTexto(
                c, e, { f -> e.files[f]?.path?.let { com.forge.pixpin.pin.ImageStore.load(it) } }, papelAparte = true
            )
            PaginaDelPlano.anotadoDe(svg, p.encaje.u, p.encaje.ceroX, p.encaje.ceroY)
        }
        val u = m.cajaUtil
        val o = PaginaDelPlano.Opciones(
            titulo = p.titulo,
            origenX = m.origenX, origenY = m.origenY,
            vista = doubleArrayOf(u[0].toDouble(), u[1].toDouble(), u[2].toDouble(), u[3].toDouble()),
            unidades = m.unidades,
            cotas = p.cotas, marcos = p.marcos, anotado = anotado,
            imagenes = porCapas?.let { PaginaDelPlano.imagenes(p.archivo, it.imagenes, m.origenX, m.origenY) }.orEmpty(),
            claro = true
        )
        val destino = File(ExportarVisor.carpeta(c), ExportarVisor.nombre(p.titulo, "html"))
        destino.outputStream().buffered(1 shl 16).use { PaginaDelPlano.escribir(grupos, o, it) }
        val capas = grupos.map { it.capa }.distinct().size
        return Compartible.Salida(
            destino, PaginaDelPlano.MIME_TYPE,
            if (porCapas != null) "$capas ${if (capas == 1) "capa" else "capas"} que se encienden y apagan · se abre sin internet"
            else "Sin capas (no se pudo leer por capas) · se abre sin internet"
        )
    }
}
