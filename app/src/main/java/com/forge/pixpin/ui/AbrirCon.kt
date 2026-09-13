package com.forge.pixpin.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/**
 * **Abrir un archivo con otra aplicación**, o instalarlo si es un APK.
 *
 * Lo pidió el usuario el 13-sep-2026: un APK recibido por Wi-Fi no se abría al tocarlo, y un PDF
 * del pin ya no se podía abrir en un editor de PDF, solo en PixPin. Tres causas:
 *
 * - Los archivos del chat viven en `files/guardados`, que el proveedor no publicaba: pedir su
 *   dirección fallaba en silencio y no pasaba nada. Ahora se publican (`file_paths.xml`), y si algo
 *   queda fuera se copia a `cache/share`.
 * - **Un APK no se «abre», se instala**: hace falta el permiso de instalar y, la primera vez, que
 *   el usuario lo permita para PixPin en Ajustes. Se le lleva ahí directamente.
 * - Como PixPin también sabe abrir PDF, el sistema se quedaba con PixPin. En «Abrir con» PixPin sale
 *   de la lista: para verlo aquí ya está el toque normal.
 */
object AbrirCon {

    const val MIME_APK = "application/vnd.android.package-archive"

    fun mimeDe(archivo: File): String {
        val ext = archivo.extension.lowercase()
        if (ext == "apk") return MIME_APK
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
    }

    fun uriDe(context: Context, archivo: File): Uri =
        runCatching { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", archivo) }
            .getOrElse {
                val copia = File(File(context.cacheDir, "share").apply { mkdirs() }, archivo.name)
                archivo.copyTo(copia, overwrite = true)
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", copia)
            }

    /** Abre [archivo] fuera de PixPin: instala si es un APK, y si no, ofrece las aplicaciones que lo abren. */
    fun abrir(context: Context, archivo: File, mime: String? = null) {
        if (!archivo.exists()) {
            Toast.makeText(context, "El archivo ya no está", Toast.LENGTH_SHORT).show()
            return
        }
        val tipo = mime?.takeIf { it != "*/*" } ?: mimeDe(archivo)
        val fuera = context !is android.app.Activity
        runCatching {
            val uri = uriDe(context, archivo)
            if (tipo == MIME_APK || archivo.extension.equals("apk", true)) {
                instalar(context, uri, fuera)
                return
            }
            val ver = Intent(Intent.ACTION_VIEW).setDataAndType(uri, tipo).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // Las de PixPin sí se ven sin declarar nada (son del propio paquete); las demás las lista el selector.
            val propios = context.packageManager.queryIntentActivities(ver, 0)
                .filter { it.activityInfo.packageName == context.packageName }
                .map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
            val elegir = Intent.createChooser(ver, "Abrir con").apply {
                if (propios.isNotEmpty()) putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, propios.toTypedArray())
                if (fuera) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(elegir)
        }.onFailure {
            Toast.makeText(context, "No se pudo abrir: ${it.javaClass.simpleName}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun instalar(context: Context, uri: Uri, fuera: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(context, "Permite «Instalar apps desconocidas» para PixPin y vuelve a tocar el APK", Toast.LENGTH_LONG).show()
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .apply { if (fuera) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            )
            return
        }
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, MIME_APK)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .apply { if (fuera) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        )
    }
}
