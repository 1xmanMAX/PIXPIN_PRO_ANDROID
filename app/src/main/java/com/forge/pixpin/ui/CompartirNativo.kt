package com.forge.pixpin.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File

/**
 * **El compartir del propio teléfono**: el panel de Huawei en un Huawei, el de Samsung en un
 * Samsung, el de Xiaomi en un Xiaomi.
 *
 * Lo pidió el usuario el 13-sep-2026: salía el selector de Android de siempre, que se ve antiguo al
 * lado del de su teléfono. Dos cosas lo cambian:
 *
 * - **En los teléfonos con panel propio, sin `createChooser`.** `Intent.createChooser` abre el
 *   selector de Android, y varias capas (EMUI/HarmonyOS, MIUI/HyperOS, ColorOS…) solo ponen el suyo
 *   cuando se les manda el `ACTION_SEND` a secas. En el resto de aparatos se sigue usando el selector,
 *   que ahí ya es el moderno.
 * - **Con `ClipData` y título**: es lo que deja al panel enseñar la vista previa y el nombre del
 *   archivo arriba, en vez de una lista pelada.
 *
 * Todo lo que PixPin comparte hacia otras aplicaciones pasa por aquí.
 */
object CompartirNativo {

    /** Las capas que ponen su propio panel si no se les pide el de Android. */
    private val CON_PANEL_PROPIO = setOf("huawei", "honor", "xiaomi", "redmi", "poco", "oppo", "realme", "oneplus", "vivo", "meizu")

    fun conPanelPropio(): Boolean {
        val marca = (Build.MANUFACTURER.orEmpty() + " " + Build.BRAND.orEmpty()).lowercase()
        return CON_PANEL_PROPIO.any { it in marca }
    }

    /** Un archivo de la carpeta que publica el proveedor (`cache/share`, `files/pins`). */
    fun archivo(context: Context, archivo: File, mime: String, titulo: String = archivo.name) {
        val uri = runCatching { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", archivo) }
            .getOrElse {
                // Fuera de lo publicado: se copia a `cache/share` y se comparte la copia.
                val copia = File(File(context.cacheDir, "share").apply { mkdirs() }, archivo.name)
                archivo.copyTo(copia, overwrite = true)
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", copia)
            }
        uris(context, listOf(uri), mime, titulo)
    }

    fun uris(context: Context, uris: List<Uri>, mime: String, titulo: String? = null) {
        if (uris.isEmpty()) return
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        intent.type = mime
        titulo?.let { intent.putExtra(Intent.EXTRA_TITLE, it) }
        intent.clipData = ClipData.newUri(context.contentResolver, titulo ?: "PixPin", uris[0]).apply {
            for (u in uris.drop(1)) addItem(ClipData.Item(u))
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        lanzar(context, intent, titulo)
    }

    fun texto(context: Context, texto: String, titulo: String? = null) {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, texto)
        titulo?.let { intent.putExtra(Intent.EXTRA_TITLE, it) }
        lanzar(context, intent, titulo)
    }

    private fun lanzar(context: Context, intent: Intent, titulo: String?) {
        val fuera = context !is android.app.Activity
        if (conPanelPropio()) {
            val directo = Intent(intent).apply { if (fuera) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            if (runCatching { context.startActivity(directo) }.isSuccess) return
        }
        val selector = Intent.createChooser(intent, titulo).apply { if (fuera) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        runCatching { context.startActivity(selector) }
    }
}
