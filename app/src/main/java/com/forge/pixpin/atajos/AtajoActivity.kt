package com.forge.pixpin.atajos

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.forge.pixpin.MainActivity
import com.forge.pixpin.PixPinApp
import com.forge.pixpin.capture.CaptureFlow
import com.forge.pixpin.floating.PinHostService
import com.forge.pixpin.guardados.MensajesActivity
import com.forge.pixpin.sincro.SincronizarActivity
import com.forge.pixpin.volverALosProyectos

/**
 * **Por donde entran los atajos**, los del icono y los del buscador del teléfono. Ver [Atajos].
 *
 * Sin pantalla: lee la seña, abre lo que toca y se va. Está exportada porque algunos
 * buscadores (los de Huawei y Honor, sobre todo) lanzan el intento tal cual en vez de pedírselo
 * al sistema; por eso solo entiende las señas de [Atajos] y cualquier otra cosa la ignora.
 */
class AtajoActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sena = intent?.data
        val partes = sena?.takeIf { it.scheme == Atajos.ESQUEMA && it.host == Atajos.ANFITRION }
            ?.pathSegments.orEmpty()
        val accion = partes.firstOrNull()
        val id = partes.getOrNull(1)
        consulta = sena

        if (accion != null) {
            runCatching { abrir(accion, id) }
            Atajos.usado(this, accion, id)
        }
        finish()
    }

    /** La seña entera: los pedidos de fuera llevan sus datos en la consulta (`?texto=…`). */
    private var consulta: android.net.Uri? = null
    private fun dato(nombre: String): String? = runCatching { consulta?.getQueryParameter(nombre) }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun abrir(accion: String, id: String?) {
        when (accion) {
            // **Los pedidos de fuera**, como los del PC (`docs/protocolo-pedidos.md`), para el
            // buscador, Tasker o cualquier otra app: `pixpin://atajo/tarea?texto=…` y compañía.
            Atajos.TAREA -> com.forge.pixpin.mini.TareaRapidaActivity.abrir(this, dato("texto"))
            Atajos.CAPTURAS -> com.forge.pixpin.ui.GaleriaDeCapturasActivity.abrir(this)
            Atajos.SOLTAR -> com.forge.pixpin.guardados.SoltarActivity.abrir(this, dato("proyecto"))
            Atajos.CHAT -> dato("texto")?.let { t ->
                Thread { com.forge.pixpin.guardados.AlChat.meterTexto(applicationContext, t, dato("proyecto")) }.start()
                android.widget.Toast.makeText(this, "Escrito en el chat", android.widget.Toast.LENGTH_SHORT).show()
            } ?: startActivity(Intent(this, MensajesActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Atajos.NOTA -> com.forge.pixpin.ui.MarkdownEditorActivity.abrir(this, "", dato("texto").orEmpty())
            Atajos.LISTA -> Thread {
                com.forge.pixpin.guardados.MensajesStore(applicationContext).anadir(
                    com.forge.pixpin.guardados.Mensaje(
                        id = java.util.UUID.randomUUID().toString(), cuando = System.currentTimeMillis(),
                        clase = com.forge.pixpin.guardados.Clase.MINIAPP, miniapp = com.forge.pixpin.mini.MiniApp.TAREAS.id,
                        texto = com.forge.pixpin.mini.Tareas.escribir(dato("titulo") ?: "Tareas", emptyList()), proyecto = dato("proyecto")
                    )
                )
            }.start()
            Atajos.CAPTURAR -> {
                // Lo mismo que el botón de ajustes rápidos: la bola en marcha y a capturar.
                // CaptureFlow ya avisa si falta el permiso de superponer.
                PinHostService.start(this)
                CaptureFlow.requestCapture(this)
            }
            Atajos.MENSAJES -> startActivity(
                Intent(this, MensajesActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            Atajos.PROYECTOS -> volverALosProyectos(this, null)
            Atajos.PROYECTO -> {
                // Si ya no existe —borrado en otro aparato y sincronizado—, a la lista.
                val app = application as? PixPinApp
                val existe = id != null && app?.proyectos?.porId(id) != null
                volverALosProyectos(this, if (existe) id else null)
            }
            // Un archivo del chat, abierto con su visor como al tocarlo allí. Si ya no está, el
            // chat se abre igual y no hace nada más: lo comprueba él, fuera del hilo principal.
            Atajos.ARCHIVO -> if (id != null) MensajesActivity.abrirYAbrir(this, id)
            // El micrófono flotante, como el pedido `grabar` del PC.
            Atajos.GRABAR -> com.forge.pixpin.pin.GrabadoraActivity.abrir(this)
            Atajos.LECCION -> com.forge.pixpin.lecciones.LeccionActivity.nueva(this)
            Atajos.LECCIONES -> com.forge.pixpin.lecciones.LeccionesActivity.abrir(this)
            Atajos.BUSCAR -> BuscarActivity.abrir(this, dato("texto"))
            Atajos.SINCRONIZAR -> startActivity(
                Intent(this, SincronizarActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
