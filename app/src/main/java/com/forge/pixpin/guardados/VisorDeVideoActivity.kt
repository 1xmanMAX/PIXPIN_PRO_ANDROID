package com.forge.pixpin.guardados

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import java.io.File

/**
 * **El vídeo de un mensaje, aquí dentro** (26-oct-2026). El usuario, con la v0.142.0: «tiene que ser
 * parecido a Windows… para visualizar también». El `.mp4` de una grabación —o cualquier vídeo del
 * chat o de una carpeta— salía a otra aplicación con «Abrir con», y volver costaba deshacer el
 * camino; en el PC se ve en su ventana. Aquí, a pantalla completa sobre negro, con los mandos del
 * sistema (reproducir, pausa y la barra para ir a un minuto). Atrás vuelve a donde se estaba: el
 * chat o la carpeta.
 *
 * Un vídeo que el teléfono no sabe leer (un códec raro) sale con «Abrir con» como antes: así nunca
 * se queda sin poder verse.
 */
class VisorDeVideoActivity : Activity() {

    companion object {
        private const val EXTRA_RUTA = "ruta"
        private const val EXTRA_TITULO = "titulo"

        /** Las extensiones que se ven aquí; las demás, fuera. Las de [SeccionesDelChat.esVideo]. */
        fun seVeAqui(ruta: String): Boolean =
            ruta.substringAfterLast('.', "").lowercase() in setOf("mp4", "m4v", "3gp", "webm", "mkv", "mov")

        fun abrir(context: Context, ruta: String, titulo: String) {
            context.startActivity(
                Intent(context, VisorDeVideoActivity::class.java)
                    .putExtra(EXTRA_RUTA, ruta)
                    .putExtra(EXTRA_TITULO, titulo)
                    .apply { if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            )
        }
    }

    private var vista: VideoView? = null
    private var donde = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ruta = intent.getStringExtra(EXTRA_RUTA)
        if (ruta == null || !File(ruta).isFile) {
            Toast.makeText(this, "El vídeo aún no está en este aparato", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        title = intent.getStringExtra(EXTRA_TITULO).orEmpty()
        donde = savedInstanceState?.getInt("donde") ?: 0
        val marco = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val v = VideoView(this)
        marco.addView(v, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        setContentView(marco)
        val mandos = MediaController(this)
        mandos.setAnchorView(v)
        v.setMediaController(mandos)
        v.setOnPreparedListener {
            if (donde > 0) v.seekTo(donde)
            v.start()
            mandos.show(3000)
        }
        v.setOnErrorListener { _, _, _ ->
            // No lo sabe leer: a otra aplicación, como antes.
            com.forge.pixpin.ui.AbrirCon.abrir(this, File(ruta))
            finish()
            true
        }
        v.setVideoPath(ruta)
        vista = v
    }

    override fun onPause() {
        super.onPause()
        vista?.let { donde = it.currentPosition; it.pause() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("donde", vista?.currentPosition ?: donde)
    }
}
