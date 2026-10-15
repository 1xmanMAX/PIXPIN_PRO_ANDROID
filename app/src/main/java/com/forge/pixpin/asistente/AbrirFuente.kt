package com.forge.pixpin.asistente

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.forge.pixpin.guardados.MensajesStore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * **Abre lo que citó el asistente** (el `pedido` de una entrada de `fuentesIa`, §3.1): un mensaje
 * se abre en su chat, igual en los dos aparatos; un fichero lleva una ruta del PC (o la portátil
 * `pixpin:files/…`), así que se busca el adjunto por el nombre del fichero y se abre en su visor.
 */
object AbrirFuente {
    fun abrir(c: Context, cita: JsonObject) {
        val que = (cita["pedido"] as? JsonObject)?.get("que") as? JsonObject ?: return
        fun s(k: String) = (que[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val almacen = MensajesStore(c)
        val mensaje = when (s("tipo")) {
            "mensaje" -> s("codigo")?.let { cod -> almacen.leer().firstOrNull { it.id == cod || it.uid == cod } }
            "fichero" -> s("ruta")?.let { ruta ->
                val nombre = ruta.substringAfterLast('/').substringAfterLast('\\')
                val local = if (ruta.startsWith("pixpin:files/")) File(c.filesDir, ruta.removePrefix("pixpin:files/")).path else ruta
                almacen.leer().lastOrNull { m -> m.ruta == local } ?: almacen.leer().lastOrNull { m ->
                    m.ruta != null && (File(m.ruta).name.equals(nombre, true) || m.nombre.equals(nombre, true))
                }
            }
            else -> null
        } ?: return
        Handler(Looper.getMainLooper()).post {
            com.forge.pixpin.guardados.MensajesActivity.abrirYAbrir(c, mensaje.id)
        }
    }
}
