package com.forge.pixpin.guardados

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.TextFields
import com.forge.pixpin.anki.AnkiDelMensaje
import com.forge.pixpin.anki.Tarjetas
import com.forge.pixpin.motor.Detalle
import com.forge.pixpin.motor.PdfDeNota
import com.forge.pixpin.motor.PdfUnion
import com.forge.pixpin.motormd.ExportarDocx
import com.forge.pixpin.ui.Compartible
import com.forge.pixpin.ui.ExportarVisor
import com.forge.pixpin.ui.FormatosDeCompartir
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Calendar
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * **Compartir una grabación** (25-oct-2026, `compartir/clase.rs` del PC, 2921a1d): una nota de voz (una
 * clase, una reunión o una nota cualquiera) o la carpeta de su grabación entra en **la hoja de
 * compartir de toda la app** con lo suyo, sacado de [ClaseExportar]:
 *
 * - **Página web**: la de [ClaseExportar.html], con el reproductor, la transcripción que salta y se
 *   resalta, las capturas en su minuto y los marcadores. El audio va dentro hasta [AUDIO_DENTRO]; más
 *   grande, al lado, como el vídeo (siempre al lado: pesa demasiado para base64). En el PC «al lado»
 *   son ficheros sueltos en la carpeta elegida; aquí se comparte un solo archivo, así que entonces
 *   sale un ZIP con la página y lo de al lado.
 * - **PDF** y **Word**: el mismo Markdown (el título, los marcadores y la transcripción con las
 *   capturas en su minuto) por el PDF de las notas ([PdfDeNota]) y el `.docx` de las notas ([ExportarDocx]).
 * - **Markdown**: solo el `.md` sin capturas; con ellas, el ZIP con su carpeta (dcca06b).
 * - **Texto**: la transcripción con sus minutos.
 * - **Anki**: el fichero de «Archivo → Importar», una tarjeta por captura, con sus imágenes, en un ZIP.
 *
 * Lo demás de la hoja (el archivo tal cual, por Wi-Fi) lo pone quien la abre ([extras]).
 */
object ExportarGrabacion {

    /** El audio más grande que va dentro de la página (en base64 pesa un tercio más). */
    const val AUDIO_DENTRO = 30L * 1024 * 1024

    /** Una captura que pesa más que esto se vuelve a guardar en JPEG para la página. */
    private const val CAPTURA_LIGERA = 400L * 1024

    /** El lado más largo de una captura dentro de la página. */
    private const val LADO_CAPTURA = 2000

    /** La nota de una grabación: ella misma, o la de la carpeta de una grabación. Null si no hay. */
    fun notaDe(m: Mensaje, todos: List<Mensaje>): Mensaje? = when {
        ClaseExportar.esGrabacion(m) -> m
        else -> CarpetaDePaginas.deMensaje(m)?.takeIf { it.esGrabacion && it.voz.isNotEmpty() }
            ?.let { c -> todos.firstOrNull { it.id == c.voz && ClaseExportar.esGrabacion(it) } }
    }

    /** La fecha de la grabación: «25/10/2026 18:05», como `grabacion-fecha` del PC en español. */
    fun fecha(cuando: Long): String {
        if (cuando <= 0) return ""
        val c = Calendar.getInstance().apply { timeInMillis = cuando }
        return String.format(
            Locale.ROOT, "%02d/%02d/%d %02d:%02d",
            c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.MONTH) + 1, c.get(Calendar.YEAR),
            c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)
        )
    }

    /** Los trozos de la transcripción, como los lee «Estudiar la clase», ya sin marcas. */
    fun lineasDe(voz: Mensaje): List<Pair<Long?, String>> =
        EstudiarClase.lineasDe(voz.transcripcion)
            .map { it.ms to AnkiDelMensaje.sinMarcas(it.texto) }
            .filter { it.second.isNotEmpty() }

    /** **Lee la grabación de la nota [voz]** con el chat entero [todos]. */
    fun leer(voz: Mensaje, todos: List<Mensaje>): ClaseExportar.Datos =
        ClaseExportar.deLaNota(voz, todos, lineasDe(voz), fecha(voz.cuando)) { m -> m.ruta?.takeIf { File(it).isFile } }

    /**
     * **Lo que ofrece la hoja** para la grabación de [voz]. Lee el disco: fuera del hilo de la pantalla.
     * [extras], al final (el archivo tal cual, Wi-Fi).
     */
    fun compartible(c: Context, voz: Mensaje, todos: List<Mensaje>, extras: List<Compartible.Formato> = emptyList()): Compartible {
        val g = leer(voz, todos)
        val r = ClaseExportar.Rotulos()
        val titulo = g.titulo.ifBlank { "Grabación" }
        val base = ExportarVisor.nombre(titulo, "x").removeSuffix(".x")
        val chat = voz.proyecto?.let { (c.applicationContext as? com.forge.pixpin.PixPinApp)?.proyectos?.porId(it)?.nombre }.orEmpty()
        fun destino(n: String) = File(ExportarVisor.carpeta(c), n)
        val formatos = buildList {
            add(Compartible.Formato("web", Icons.Filled.Language, "Página web", Compartible.NINGUNA, generar = { web(c, g, r, base) }))
            add(Compartible.Formato("pdf", Icons.Filled.PictureAsPdf, "PDF", Compartible.NINGUNA, generar = {
                val (md, nombres) = markdownConNombres(g, r)
                val bytes = PdfDeNota.aniadir(PdfUnion.enBlanco(), md, Detalle.letrasParaPdf()) { s -> nombres[s]?.let(Detalle::medioParaPdf) }
                    ?: return@Formato null
                Compartible.Salida(destino("$base.pdf").also { it.writeBytes(bytes) }, "application/pdf", "El texto se busca y se copia, con las capturas en su minuto")
            }))
            add(Compartible.Formato("word", Icons.Filled.Article, "Word", Compartible.NINGUNA, generar = {
                val (md, nombres) = markdownConNombres(g, r)
                val bytes = ExportarDocx.exportar(ExportarDocx.Nota(md, g.titulo, imagen = { s -> nombres[s]?.let { runCatching { File(it).readBytes() }.getOrNull() } }))
                Compartible.Salida(
                    destino("$base.docx").also { it.writeBytes(bytes) },
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "Con las capturas dentro"
                )
            }))
            add(Compartible.Formato("md", Icons.AutoMirrored.Filled.Notes, "Markdown", Compartible.NINGUNA, generar = {
                if (g.capturas.isEmpty()) {
                    Compartible.Salida(destino("$base.md").also { it.writeText(ClaseExportar.markdown(g, r) { null }) }, "text/markdown", "Con sus títulos y sus minutos")
                } else {
                    val zip = ClaseExportar.paqueteMarkdown(g, r, base) { runCatching { File(it).readBytes() }.getOrNull() }
                    Compartible.Salida(destino("$base (Markdown).zip").also { it.writeBytes(zip) }, "application/zip", "El .md y la carpeta de sus capturas")
                }
            }))
            add(Compartible.Formato("texto", Icons.Filled.TextFields, "Texto", Compartible.NINGUNA, generar = {
                Compartible.Salida(destino("$base.txt").also { it.writeText(ClaseExportar.texto(g, r)) }, "text/plain", "La transcripción con sus minutos")
            }))
            if (g.capturas.isNotEmpty()) add(Compartible.Formato("anki", Icons.Filled.School, "Anki", Compartible.NINGUNA, generar = {
                val zip = anki(g, AnkiDelMensaje.mazoDe(chat), base) ?: return@Formato null
                Compartible.Salida(destino("$base (Anki).zip").also { it.writeBytes(zip) }, "application/zip", "Una tarjeta por captura: en Anki, Archivo → Importar")
            }))
            addAll(extras)
            // **Su `.pixpin`, el audio tal cual y la Wi-Fi** (27-oct-2026), como cualquier otra cosa de la
            // aplicación y venga de donde venga (el chat, su carpeta, Estudiar la clase). Lo que ya
            // trae quien la abre ([extras]) manda.
            voz.ruta?.takeIf { File(it).isFile }?.let { ruta ->
                val n = voz.nombre.ifBlank { File(ruta).name }
                listOf(ExportarVisor.pixpin(c, ruta, n), ExportarVisor.original(c, ruta, n, "La grabación tal cual"), ExportarVisor.wifi(c, ruta, n))
                    .filter { propio -> extras.none { it.id == propio.id } }
            }?.let(::addAll)
        }
        return Compartible(titulo, formatos = formatos, contenido = FormatosDeCompartir.Contenido.GRABACION)
    }

    /** El Markdown con cada captura por su nombre (`captura-01-0m05s.png`) y de qué fichero es cada nombre. */
    private fun markdownConNombres(g: ClaseExportar.Datos, r: ClaseExportar.Rotulos): Pair<String, Map<String, String>> {
        val nombres = g.capturas.mapIndexed { i, (ms, ruta) -> ruta to ClaseExportar.nombreDeCaptura(i, ms, ruta) }.toMap()
        val md = ClaseExportar.markdown(g, r) { nombres[it] }
        return md to nombres.entries.associate { (ruta, n) -> n to ruta }
    }

    /** Una captura para dentro de la página: tal cual si es ligera; si no, en JPEG y sin pasar de [LADO_CAPTURA]. */
    private fun capturaLigera(ruta: String): ClaseExportar.Medio? {
        val f = File(ruta)
        if (!f.isFile) return null
        val talCual = { ClaseExportar.Medio.Incrustado(ClaseExportar.mimeDe(ruta), f.readBytes()) }
        if (f.length() <= CAPTURA_LIGERA) return talCual()
        val medidas = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(ruta, medidas)
        var paso = 1
        while (maxOf(medidas.outWidth, medidas.outHeight) / (paso * 2) >= LADO_CAPTURA) paso *= 2
        val mapa = BitmapFactory.decodeFile(ruta, BitmapFactory.Options().apply { inSampleSize = paso }) ?: return talCual()
        val lado = maxOf(mapa.width, mapa.height)
        val final = if (lado > LADO_CAPTURA) {
            val k = LADO_CAPTURA.toFloat() / lado
            Bitmap.createScaledBitmap(mapa, (mapa.width * k).toInt().coerceAtLeast(1), (mapa.height * k).toInt().coerceAtLeast(1), true)
        } else mapa
        val opaca = !final.hasAlpha()
        val salida = ByteArrayOutputStream()
        final.compress(if (opaca) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, 85, salida)
        return ClaseExportar.Medio.Incrustado(if (opaca) "image/jpeg" else "image/png", salida.toByteArray())
    }

    /**
     * **La página web**: dentro el audio (hasta [AUDIO_DENTRO]) y las capturas; al lado el vídeo, un
     * audio muy largo y los adjuntos, y entonces todo en un ZIP con la página.
     */
    private fun web(c: Context, g: ClaseExportar.Datos, r: ClaseExportar.Rotulos, base: String): Compartible.Salida {
        val alLado = LinkedHashMap<String, String>()
        fun ponerAlLado(origen: String, que: String) {
            val ext = ClaseExportar.nombre(origen).substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
            alLado[origen] = "$base - $que$ext"
        }
        g.audio?.let { if (File(it).length() > AUDIO_DENTRO) ponerAlLado(it, r.audio.lowercase()) }
        g.video?.let { ponerAlLado(it, r.video.lowercase()) }
        for (a in g.adjuntos) ponerAlLado(a, ClaseExportar.nombre(a).substringBeforeLast('.'))
        val pagina = ClaseExportar.html(g, r) { ruta ->
            alLado[ruta]?.let { return@html ClaseExportar.Medio.AlLado(it) }
            if (g.capturas.any { it.second == ruta }) return@html capturaLigera(ruta)
            runCatching { ClaseExportar.Medio.Incrustado(ClaseExportar.mimeDe(ruta), File(ruta).readBytes()) }.getOrNull()
        }
        val carpeta = ExportarVisor.carpeta(c)
        if (alLado.isEmpty()) {
            val f = File(carpeta, "$base.html").also { it.writeText(pagina) }
            return Compartible.Salida(f, com.forge.pixpin.motor.ExportarHtml.MIME_TYPE, "Se abre en cualquier navegador, sin internet")
        }
        val f = File(carpeta, "$base (web).zip")
        ZipOutputStream(f.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("$base.html"))
            zip.write(pagina.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            for ((origen, nombre) in alLado) {
                val desde = File(origen)
                if (!desde.isFile) continue
                zip.putNextEntry(ZipEntry(nombre))
                desde.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return Compartible.Salida(f, "application/zip", "La página y, al lado, el vídeo y lo que no cabe dentro")
    }

    /** **Anki**: las tarjetas de la grabación (una por captura) y sus imágenes, en un ZIP. Null sin tarjetas. */
    fun anki(g: ClaseExportar.Datos, mazo: String, base: String): ByteArray? {
        val trozos = g.lineas.mapNotNull { (ms, t) -> ms?.let { it to t } }
        val prefijo = base.replace(' ', '-')
        val capturas = g.capturas.filter { File(it.second).isFile }.map { (ms, ruta) ->
            val ext = ruta.substringAfterLast('.', "png").lowercase()
            ms to Tarjetas.Imagen("pixpin-$prefijo-$ms.$ext", ruta)
        }
        val tarjetas = Tarjetas.deLaClase(g.titulo, AnkiDelMensaje.PREGUNTA_CLASE, trozos, capturas)
            .map { it.copy(etiquetas = listOf("pixpin")) }
        if (tarjetas.isEmpty()) return null
        val salida = ByteArrayOutputStream()
        ZipOutputStream(salida).use { zip ->
            zip.putNextEntry(ZipEntry("$base (Anki).txt"))
            zip.write(Tarjetas.fichero(mazo, tarjetas).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            for (i in tarjetas.flatMap { it.imagenes }.distinctBy { it.nombre }) {
                zip.putNextEntry(ZipEntry(i.nombre))
                File(i.ruta).inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return salida.toByteArray()
    }
}
