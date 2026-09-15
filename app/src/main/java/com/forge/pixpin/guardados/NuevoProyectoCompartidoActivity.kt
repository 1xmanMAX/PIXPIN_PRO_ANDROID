package com.forge.pixpin.guardados

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.forge.pixpin.PixPinApp
import com.forge.pixpin.R
import com.forge.pixpin.motor.Element
import com.forge.pixpin.motor.ElementType
import com.forge.pixpin.motor.ExcalidrawStore
import com.forge.pixpin.motor.Hoja
import com.forge.pixpin.motor.PaquetePixpin
import com.forge.pixpin.motor.PdfDoc
import com.forge.pixpin.motor.Proyecto
import com.forge.pixpin.motor.Scene
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * **La tercera opción al compartir algo a PixPin: un proyecto nuevo con eso.**
 *
 * Al lado de «hacer un pin» y «guardarlo en mensajes». Un PDF se convierte en un proyecto con
 * sus páginas; una foto, en un proyecto con un lienzo que la lleva; un `.pixpin` —el paquete
 * de un proyecto entero, ver [PaquetePixpin]— se abre tal cual y se sigue editando. Al acabar
 * se va al proyecto recién hecho, dentro del montón. Lo pidió el usuario (5-sep-2026).
 */
class NuevoProyectoCompartidoActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
        val app = application as PixPinApp
        val uris = when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.streamUri())
            Intent.ACTION_SEND_MULTIPLE -> intent.streamUris()
            Intent.ACTION_VIEW -> listOfNotNull(intent?.data)
            else -> emptyList()
        }
        if (uris.isEmpty()) { avisar(false); finish(); return }
        lifecycleScope.launch {
            val hecho = withContext(Dispatchers.IO) { runCatching { proyectoDe(app, uris) }.getOrNull() }
            avisar(hecho != null)
            if (hecho != null) com.forge.pixpin.volverALosProyectos(this@NuevoProyectoCompartidoActivity, hecho.id)
            finishAndRemoveTask()
        }
    }

    /** El proyecto que sale de lo compartido, ya guardado; null si no se pudo. */
    private fun proyectoDe(app: PixPinApp, uris: List<Uri>): Proyecto? {
        val ahora = System.currentTimeMillis()
        val temporales = uris.mapNotNull { copiar(it) }
        if (temporales.isEmpty()) return null
        val (primero, nombre, tipo) = temporales.first()

        // Un paquete: se abre entero.
        if (nombre.endsWith("." + PaquetePixpin.EXTENSION, ignoreCase = true) ||
            PaquetePixpin.abrir(primero) != null
        ) {
            val contenido = PaquetePixpin.abrir(primero) ?: return null
            val p = PaquetePixpin.importar(
                this, contenido, ahora, File(filesDir, "proyectos"),
                guardarCroquis = { id, json -> com.forge.pixpin.croquis3d.Croquis3DAlmacen.guardarJson(this, id, json) }
            ) ?: return null
            app.proyectos.guardar(p)
            return p
        }
        // **Un libro de hojas de cálculo**: un proyecto con una tabla por hoja. Ver [ImportarHojas].
        if (com.forge.pixpin.motor.ImportarHojas.esLibro(nombre)) {
            val hojas = runCatching { com.forge.pixpin.motor.ImportarHojas.leer(primero, nombre, ahora) }.getOrNull() ?: return null
            val almacen = com.forge.pixpin.motor.TablasEnDisco.de(filesDir)
            val proyecto = app.proyectos.nuevo(nombre.substringBeforeLast('.').ifBlank { getString(R.string.proyecto_nuevo_nombre) }, ahora)
            var alguna = false
            for ((i, h) in hojas.withIndex()) {
                val tabla = "libro-$ahora-$i"
                if (!almacen.guardar(tabla, h.tabla)) continue
                val actual = app.proyectos.porId(proyecto.id) ?: break
                app.proyectos.conHoja(actual, Hoja(id = "hoja-$ahora-$i", nombre = h.nombre, tabla = tabla), ahora)
                alguna = true
            }
            return if (alguna) app.proyectos.porId(proyecto.id) else { app.proyectos.borrar(proyecto.id); null }
        }
        // **Una presentación de PowerPoint**: se convierte en PDF —una página por diapositiva— y
        // entra como cualquier PDF, lista para anotar y presentar. Ver [DiapositivasAPdf].
        if (com.forge.pixpin.motor.Diapositivas.esPresentacion(nombre)) {
            val documento = File(File(filesDir, "proyectos").apply { mkdirs() }, "doc-$ahora.pdf")
            val paginas = runCatching { DiapositivasAPdf.convertir(primero, nombre, documento) }.getOrElse { e ->
                val porque = (e as? com.forge.pixpin.motor.Diapositivas.NoSeLee)?.message ?: "No se pudo leer la presentación"
                runOnUiThread { android.widget.Toast.makeText(this, porque, android.widget.Toast.LENGTH_LONG).show() }
                documento.delete()
                return null
            }
            primero.delete()
            return app.proyectos.deEstePdf(documento.absolutePath, nombre.substringBeforeLast('.'), paginas, ahora)
        }
        // Un PDF: un proyecto con sus páginas, como al abrir uno desde dentro.
        if (tipo?.contains("pdf") == true || nombre.endsWith(".pdf", ignoreCase = true)) {
            val paginas = PdfDoc.pageCount(primero.absolutePath)
            if (paginas <= 0) return null
            // **Dentro de la carpeta de PixPin, no en la caché**: la caché la vacía Android cuando
            // quiere, y lo que está fuera de `files` no viaja al sincronizar (el proyecto llegaba
            // sin su PDF; lo vio el usuario el 13-sep-2026).
            val documento = File(File(filesDir, "proyectos").apply { mkdirs() }, "doc-$ahora.pdf")
            runCatching { primero.copyTo(documento, overwrite = true) }.getOrElse { return null }
            primero.delete()
            // **Primero se aligera**: las fotos de un escaneo, a la resolución de imprimir. Todo lo
            // que salga de este proyecto —la web, los envíos— pesa luego lo justo. Ver [ComprimirPdf].
            com.forge.pixpin.pdf.ComprimirPdf.enSuSitio(documento)
            return app.proyectos.deEstePdf(documento.absolutePath, nombre.removeSuffix(".pdf"), paginas, ahora)
        }
        // Fotos: un proyecto con un lienzo por foto.
        val proyecto = app.proyectos.nuevo(nombre.substringBeforeLast('.').ifBlank { getString(R.string.proyecto_nuevo_nombre) }, ahora)
        var alguna = false
        for ((i, t) in temporales.withIndex()) {
            val (archivo, nombreDeFoto, mime) = t
            if (mime?.startsWith("image/") != true) continue
            val foto = ExcalidrawStore.guardarImagen(this, archivo, mime) ?: continue
            val medidas = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(archivo.absolutePath, medidas)
            val ancho = medidas.outWidth.coerceAtLeast(1).toDouble()
            val alto = medidas.outHeight.coerceAtLeast(1).toDouble()
            val escala = minOf(1.0, 1400.0 / ancho)
            val escena = Scene(
                elements = listOf(
                    Element(
                        id = "foto-$ahora-$i", type = ElementType.IMAGE, x = 0.0, y = 0.0,
                        width = ancho * escala, height = alto * escala, seed = 1, fileId = foto.id
                    )
                ),
                files = mapOf(foto.id to foto)
            )
            val dibujo = "dib-$ahora-$i"
            ExcalidrawStore.guardar(this, dibujo, escena) ?: continue
            app.proyectos.conHoja(proyecto, Hoja(id = "hoja-$ahora-$i", nombre = nombreDeFoto.substringBeforeLast('.'), dibujo = dibujo), ahora)
            alguna = true
        }
        return if (alguna) app.proyectos.porId(proyecto.id) else { app.proyectos.borrar(proyecto.id); null }
    }

    /** Lo compartido, copiado a un temporal nuestro: la URI de otro proceso no se puede abrir después. */
    private fun copiar(uri: Uri): Triple<File, String, String?>? = runCatching {
        val nombre = nombreDe(uri)
        val tipo = contentResolver.getType(uri)
        val carpeta = File(cacheDir, "compartido").apply { mkdirs() }
        val destino = File(carpeta, "${System.nanoTime()}-$nombre")
        contentResolver.openInputStream(uri)?.use { entrada -> destino.outputStream().use { entrada.copyTo(it) } }
            ?: return null
        Triple(destino, nombre, tipo)
    }.getOrNull()

    private fun nombreDe(uri: Uri): String = runCatching {
        contentResolver.query(uri, null, null, null, null)?.use { fila ->
            val i = fila.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && fila.moveToFirst()) fila.getString(i) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: "archivo"

    private fun avisar(bien: Boolean) {
        Toast.makeText(
            this,
            if (bien) R.string.proyecto_desde_compartido_ok else R.string.pdf_no_se_pudo,
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun Intent.streamUri(): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else @Suppress("DEPRECATION") (getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)

    private fun Intent.streamUris(): List<Uri> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) ?: emptyList()
        else @Suppress("DEPRECATION") (getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: emptyList())
}
