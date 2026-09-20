package com.forge.pixpin.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import com.forge.pixpin.motor.DrawController
import com.forge.pixpin.motor.DrawFonts

/**
 * **Escribir un texto en un lienzo que no es el editor completo** (20-sep-2026): el editor rápido
 * de los lectores y la hojita.
 *
 * La herramienta de texto es del motor —planta el elemento y deja apuntado cuál hay que abrir
 * ([DrawController.pendingTextId])—, pero **escribir** lo hacía solo la pantalla del editor, con su
 * cuadro encima del dibujo; por eso el texto se había quedado fuera de estos sitios. Aquí va la
 * versión corta del mismo trato: un cuadro donde teclear y, al aceptar, el texto entra en el
 * elemento **medido con la misma letra** con la que se pinta ([DrawFonts.medirTexto]). Vacío, el
 * elemento se quita: un texto invisible solo serviría para robar toques.
 *
 * [tick] no se usa para nada más que para volver a mirar: quien hospeda esto lo sube cuando el
 * lienzo cambia, que es cuando puede haber un texto recién plantado.
 */
@Composable
fun EscribirEnElLienzo(controlador: DrawController, tick: Int, alAcabar: () -> Unit) {
    @Suppress("UNUSED_EXPRESSION") tick
    val id = controlador.pendingTextId ?: return
    val elemento = controlador.scene.byId(id)
    if (elemento == null) { controlador.clearPendingText(); return }
    val contexto = LocalContext.current
    var texto by remember(id) { mutableStateOf(elemento.text.orEmpty()) }
    val foco = remember(id) { FocusRequester() }
    LaunchedEffect(id) { runCatching { foco.requestFocus() } }

    fun cerrar(guardar: Boolean) {
        val limpio = texto.trimEnd()
        if (!guardar && elemento.text.isNullOrBlank() || guardar && limpio.isBlank()) {
            controlador.setSelection(setOf(id))
            controlador.deleteSelection()
        } else if (guardar) {
            val (ancho, alto) = DrawFonts.medirTexto(
                contexto, limpio,
                elemento.fontFamily ?: controlador.scene.style.fontFamily,
                elemento.fontSize ?: controlador.scene.style.fontSize
            )
            controlador.updateText(id, limpio, ancho, alto)
        }
        controlador.clearPendingText()
        alAcabar()
    }

    AlertDialog(
        onDismissRequest = { cerrar(false) },
        title = { Text("Texto") },
        text = { OutlinedTextField(texto, { texto = it }, minLines = 2, maxLines = 8, modifier = Modifier.focusRequester(foco)) },
        confirmButton = { TextButton(onClick = { cerrar(true) }) { Text("Poner") } },
        dismissButton = { TextButton(onClick = { cerrar(false) }) { Text("Cancelar") } }
    )
}
