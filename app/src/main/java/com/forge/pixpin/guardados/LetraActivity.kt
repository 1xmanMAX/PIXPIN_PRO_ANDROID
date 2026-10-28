package com.forge.pixpin.guardados

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.forge.pixpin.R

/**
 * **La letra o el texto de un audio, a pantalla completa** (5-sep-2026): ahora **un desvío a «Estudiar
 * la clase»** (28-oct-2026).
 *
 * El usuario: «en las grabaciones de mi clase hay dos interfaces que se ven y funcionan parecido pero
 * son distintas, para la misma clase». Eran esta pantalla —el texto con su minuto, las banderitas, el
 * lápiz y, desde el 25-oct, la imagen del minuto y una ficha por captura— y [EstudiarClaseActivity],
 * que hace todo eso y más (el vídeo, la tira de capturas, los marcadores, los clips, el acercar). Se
 * llegaba a una o a otra según por dónde se entrara: dos toques en el texto de la nota, el título de
 * la barra del reproductor del chat o «Letra» del menú abrían esta; la tarjeta de la carpeta, la
 * pastilla «Estudiar» o el aviso al terminar, la otra.
 *
 * **Ahora hay una sola**: todo audio del chat (la nota de una clase o de una reunión, una nota de voz,
 * una canción) se estudia en [EstudiarClaseActivity], que sin imagen enseña arriba la línea que suena
 * en grande y que trae lo que solo tenía esta: el tamaño del texto y el lápiz ([editarElTextoDelAudio]).
 * Esta actividad se queda para lo que aún la abra por su nombre ([abrir], `MensajesActivity`, el
 * teleprónter): llega a la misma pantalla y se va.
 */
class LetraActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra(EL_MENSAJE)?.takeIf { it.isNotEmpty() }?.let { EstudiarClaseActivity.abrir(this, it) }
        finish()
    }

    companion object {
        private const val EL_MENSAJE = "letra_mensaje"

        /** Abre el audio [mensaje] para leerlo, cantarlo o estudiarlo: en [EstudiarClaseActivity]. */
        fun abrir(context: Context, mensaje: String) {
            EstudiarClaseActivity.abrir(context, mensaje)
        }

        /** El intento de siempre (por si algo lo guardó): llega a la pantalla de estudiar. */
        @Suppress("unused")
        fun intento(context: Context, mensaje: String): Intent =
            Intent(context, LetraActivity::class.java).putExtra(EL_MENSAJE, mensaje).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

/**
 * **El lápiz del texto de un audio** (lo que hacía el de [LetraActivity]): con hoja en un proyecto, se
 * edita la hoja (que es el `.md` de verdad); si no, lo escrito vuelve al mensaje. Ver
 * [com.forge.pixpin.ui.MarkdownEditorActivity] (`mensaje`).
 */
fun editarElTextoDelAudio(context: Context, m: Mensaje) {
    val app = context.applicationContext as? com.forge.pixpin.PixPinApp
    val hoja = m.hojaDelTexto
    val proyecto = m.proyecto?.let { app?.proyectos?.porId(it) }
    val laHoja = if (hoja != null) proyecto?.hojas?.firstOrNull { it.id == hoja } else null
    if (laHoja != null && proyecto != null) {
        com.forge.pixpin.ui.MarkdownEditorActivity.abrir(context, laHoja.id, laHoja.nota ?: "", desdeProyecto = proyecto.id, mensaje = m.id)
    } else {
        com.forge.pixpin.ui.MarkdownEditorActivity.abrir(context, "letra-${m.id}", m.transcripcion ?: "", mensaje = m.id)
    }
}

/** Cómo se llama un audio: su nombre de archivo si lo trae, o «Nota de voz» con su fecha. */
fun tituloDeAudio(context: Context, m: Mensaje): String =
    m.nombre.substringBeforeLast('.').ifBlank {
        context.getString(R.string.guardados_voz) + " · " +
            java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(m.cuando))
    }
