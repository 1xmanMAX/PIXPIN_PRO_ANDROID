package com.forge.pixpin.capture

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import com.forge.pixpin.sincro.CapturasDelAparato
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * **Las capturas de este teléfono, para que viajen** (`sincro/GaleriaQueViaja`): las de
 * `Pictures/PixPin` que lista [BarrenderoDeCapturas], y las que llegan de otro aparato se guardan
 * allí mismo, con su nombre de siempre y su fecha de verdad (`DATE_TAKEN`), para que la galería
 * las enseñe como cualquier otra. Trabajo de disco: nunca en el hilo principal.
 */
class CapturasEnElTelefono(context: Context) : CapturasDelAparato {
    companion object {
        /** Sube cuando la sincronización cambia la galería (llegan, se van, otras fechas). */
        val cambio = kotlinx.coroutines.flow.MutableStateFlow(0)
    }

    private val app = context.applicationContext

    override val raiz: File get() = BarrenderoDeCapturas.raiz(app)

    override fun listar(): List<GaleriaCompartida.Local>? =
        BarrenderoDeCapturas.listar(app)?.map { GaleriaCompartida.Local(it.nombre, it.cuando, it.bytes, it.mime) }

    /**
     * Una por nombre. Al pasar cien capturas se pregunta cien veces: se recuerda la lista unos
     * segundos, y se olvida en cuanto se guarda o se tira algo.
     */
    private fun buscar(nombre: String): BarrenderoDeCapturas.Captura? {
        val ahora = System.currentTimeMillis()
        val l = cache?.takeIf { ahora - cacheDe < 5_000 } ?: BarrenderoDeCapturas.listar(app)?.associateBy { it.nombre }?.also { cache = it; cacheDe = ahora }
        return l?.get(nombre)
    }

    @Volatile private var cache: Map<String, BarrenderoDeCapturas.Captura>? = null
    @Volatile private var cacheDe = 0L

    override fun abrir(nombre: String): Pair<Long, InputStream>? {
        val c = buscar(nombre) ?: return null
        val r = app.contentResolver
        // El largo de verdad, no el que apunta MediaStore (puede ir atrasado).
        val largo = runCatching { r.openFileDescriptor(c.uri, "r")?.use { it.statSize } }.getOrNull()?.takeIf { it >= 0 } ?: c.bytes
        val flujo = r.openInputStream(c.uri) ?: return null
        return largo to flujo
    }

    override fun guardar(e: GaleriaCompartida.Entrada, escribir: (OutputStream) -> Boolean): Boolean {
        val r = app.contentResolver
        val video = e.mime.startsWith("video/")
        val coleccion = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val valores = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, e.nombre)
            put(MediaStore.MediaColumns.MIME_TYPE, e.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, if (video) "Movies/PixPin" else "Pictures/PixPin")
            // La hora en que se hizo: la galería agrupa por día con ella.
            put(MediaStore.MediaColumns.DATE_TAKEN, e.cuando)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        cache = null
        val uri = r.insert(coleccion, valores) ?: return false
        val bien = try {
            r.openOutputStream(uri)?.use { escribir(it) } ?: false
        } catch (t: Throwable) {
            runCatching { r.delete(uri, null, null) }
            throw t
        }
        if (!bien) { runCatching { r.delete(uri, null, null) }; return false }
        r.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return true
    }

    override fun tirar(nombres: Collection<String>) {
        cache = null
        val quitar = nombres.toSet()
        val uris = BarrenderoDeCapturas.listar(app)?.filter { it.nombre in quitar }?.map { it.uri } ?: return
        BarrenderoDeCapturas.aLaPapelera(app, uris)
    }

    override fun dias(): Int = (app as? com.forge.pixpin.PixPinApp)?.ajustes?.diasCaducidad ?: CaducidadDeCapturas.DIAS
}
