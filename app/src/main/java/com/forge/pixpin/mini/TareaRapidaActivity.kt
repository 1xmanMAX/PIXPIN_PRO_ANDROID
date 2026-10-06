package com.forge.pixpin.mini

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.forge.pixpin.guardados.Clase
import com.forge.pixpin.guardados.Mensaje
import com.forge.pixpin.guardados.MensajesStore
import com.forge.pixpin.ui.theme.PixPinTheme
import java.util.UUID

/**
 * **Apuntar una tarea sin abrir nada** (como `anadir_tarea` del lanzador del PC): desde el icono
 * o el buscador del teléfono sale un campo; lo escrito va, con su fecha, a la lista **«Inbox»**
 * de «Mensajes guardados» (que se crea si no está), como en el PC desde el 3-oct-2026: de ahí se
 * reparte con «Mover a…» en la pantalla de Tareas ([TodasLasTareasActivity]). Se puede apuntar una
 * tras otra: Intro añade y deja el campo listo para la siguiente.
 */
class TareaRapidaActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra(EXTRA_TEXTO)?.takeIf { it.isNotBlank() }?.let { anadir(this, it); finish(); return }
        setContent { PixPinTheme(cielo = false) { Hoja() } }
    }

    @Composable
    private fun Hoja() {
        var texto by remember { mutableStateOf("") }
        var hechas by remember { mutableIntStateOf(0) }
        val foco = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { foco.requestFocus() } }
        AlertDialog(
            onDismissRequest = { finish() },
            title = { Text(if (hechas == 0) "Nueva tarea" else "Nueva tarea · $hechas apuntadas") },
            text = {
                OutlinedTextField(
                    value = texto, onValueChange = { texto = it }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(foco),
                    placeholder = { Text("¿Qué hay que hacer?") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (texto.isNotBlank()) { anadir(this@TareaRapidaActivity, texto); texto = ""; hechas++ } })
                )
            },
            confirmButton = {
                TextButton(onClick = { if (texto.isNotBlank()) anadir(this@TareaRapidaActivity, texto); finish() }) { Text("Añadir") }
            },
            dismissButton = { TextButton(onClick = { finish() }) { Text("Cerrar") } }
        )
    }

    companion object {
        private const val EXTRA_TEXTO = "tarea_texto"

        fun abrir(context: Context, texto: String? = null) = context.startActivity(
            Intent(context, TareaRapidaActivity::class.java).putExtra(EXTRA_TEXTO, texto).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )

        /**
         * Añade [texto]: sin [proyecto], al Inbox de «Mensajes guardados» ([alInbox]); con él, a la
         * última lista de ese chat (o a una nueva «Tareas»), como `anadir_tarea` del PC. Trabajo de
         * disco, rápido.
         */
        fun anadir(context: Context, texto: String, proyecto: String? = null) {
            if (proyecto == null) {
                alInbox(context, texto)
                Toast.makeText(context, "Tarea apuntada en el Inbox", Toast.LENGTH_SHORT).show()
                return
            }
            val almacen = MensajesStore(context)
            val lista = almacen.leer().lastOrNull {
                it.clase == Clase.MINIAPP && it.miniapp == MiniApp.TAREAS.id && it.proyecto == proyecto && !it.enBuzon
            }
            if (lista != null) {
                almacen.actualizar(lista.id) { m ->
                    val t = Tareas.leer(m.texto)
                    m.copy(texto = Tareas.escribir(Cabecera.titulo(m.texto), Tareas.anadir(t, texto)))
                }
            } else {
                almacen.anadir(
                    Mensaje(
                        id = UUID.randomUUID().toString(), cuando = System.currentTimeMillis(), clase = Clase.MINIAPP,
                        miniapp = MiniApp.TAREAS.id, texto = Tareas.escribir("Tareas", Tareas.anadir(emptyList(), texto)), proyecto = proyecto
                    )
                )
            }
            Toast.makeText(context, "Tarea apuntada", Toast.LENGTH_SHORT).show()
        }

        /**
         * Apunta [texto], con su fecha, en el **Inbox** de «Mensajes guardados»; si aún no está, lo
         * crea con esa tarea dentro (`tareas::apuntar_con` del PC). Lo vacío no apunta nada. Sin
         * avisos: los da quien llama. Trabajo de disco.
         */
        fun alInbox(context: Context, texto: String) {
            if (Tareas.saneado(texto).isEmpty()) return
            val almacen = MensajesStore(context)
            val inbox = TodasLasTareas.inboxEn(almacen.leer())
            if (inbox != null) {
                almacen.actualizar(inbox.id) { m -> m.copy(texto = TodasLasTareas.conTarea(m.texto, texto)) }
            } else {
                almacen.anadir(
                    Mensaje(
                        id = UUID.randomUUID().toString(), cuando = System.currentTimeMillis(), clase = Clase.MINIAPP,
                        miniapp = MiniApp.TAREAS.id,
                        texto = TodasLasTareas.conTarea(Tareas.escribir(TodasLasTareas.INBOX, emptyList()), texto)
                    )
                )
            }
        }
    }
}
