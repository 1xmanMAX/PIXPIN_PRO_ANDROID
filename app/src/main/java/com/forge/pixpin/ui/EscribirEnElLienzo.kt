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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
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
fun EscribirEnElLienzo(
    controlador: DrawController,
    tick: Int,
    /**
     * **Escribir encima, en su sitio**, sin cuadro (13-oct-2026, el usuario en el plano: «que no se
     * escriba con un cuadro de ingreso de texto sino directamente sobre el plano»): como el editor
     * completo, el texto se teclea donde va a quedar, con su letra, su tamaño y su color, y se acepta
     * tocando fuera. Solo donde el lienzo ocupa la pantalla entera desde la esquina (el plano): en
     * los lectores la capa va dentro de una lista y su vista no es la de la pantalla.
     */
    enSitio: Boolean = false,
    /** Modo noche del lienzo: el color del texto con su mismo filtro. */
    noche: Boolean = false,
    /** Lo que hay debajo, para tapar el texto viejo mientras se reescribe. */
    fondo: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Transparent,
    alAcabar: () -> Unit
) {
    @Suppress("UNUSED_EXPRESSION") tick
    val id = controlador.pendingTextId ?: return
    val elemento = controlador.scene.byId(id)
    if (elemento == null) { controlador.clearPendingText(); return }
    val contexto = LocalContext.current
    // Un texto rico se reescribe como fuente y se compone al aceptar (com.forge.pixpin.motor.TextoRico).
    var texto by remember(id) { mutableStateOf(com.forge.pixpin.motor.TextoRico.vigente(elemento) ?: elemento.text.orEmpty()) }
    val foco = remember(id) { FocusRequester() }
    LaunchedEffect(id) { runCatching { foco.requestFocus() } }

    fun cerrar(guardar: Boolean) {
        val limpio = texto.trimEnd()
        if (!guardar && elemento.text.isNullOrBlank() || guardar && limpio.isBlank()) {
            controlador.setSelection(setOf(id))
            controlador.deleteSelection()
        } else if (guardar && com.forge.pixpin.motor.TextoRico.tieneMarcas(limpio)) {
            val familia = elemento.fontFamily ?: controlador.scene.style.fontFamily
            val compuesto = com.forge.pixpin.motor.TextoRico.conFuente(
                elemento.copy(fontSize = elemento.fontSize ?: controlador.scene.style.fontSize, fontFamily = familia),
                limpio, DrawFonts.medidorRico(DrawFonts.provider(contexto), familia)
            )
            controlador.updateText(id, compuesto.text.orEmpty(), compuesto.width, compuesto.height, compuesto.textoRico)
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

    if (enSitio) {
        val vp = controlador.scene.viewport
        val esquina = vp.toScreen(com.forge.pixpin.motor.Pt(elemento.x, elemento.y))
        val tam = elemento.fontSize ?: controlador.scene.style.fontSize
        val familia = elemento.fontFamily ?: controlador.scene.style.fontFamily
        // Un toque fuera acepta, como en el editor. Va DEBAJO del texto para no robarle los toques.
        androidx.compose.foundation.layout.Box(
            Modifier.fillMaxSize().clickable(
                indication = null,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            ) { cerrar(true) }
        )
        androidx.compose.foundation.text.BasicTextField(
            value = texto,
            onValueChange = { texto = it },
            textStyle = androidx.compose.ui.text.TextStyle(
                fontFamily = com.forge.pixpin.motor.composeFontFamily(familia),
                // En píxeles de pantalla (no `sp`): lo que se teclea mide lo que va a quedar.
                fontSize = with(LocalDensity.current) { (tam * vp.zoom).toFloat().toSp() },
                color = androidx.compose.ui.graphics.Color(
                    com.forge.pixpin.motor.DrawTheme.filtrar(com.forge.pixpin.motor.parseColor(elemento.strokeColor), noche)
                )
            ),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color(0xFF3D8BFF)),
            // El intro hace otra línea; aceptar es tocar fuera.
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences
            ),
            modifier = Modifier
                .offset { IntOffset(esquina.x.toInt(), esquina.y.toInt()) }
                .widthIn(min = 120.dp)
                .background(fondo)
                .focusRequester(foco)
        )
        androidx.activity.compose.BackHandler { cerrar(true) }
        return
    }

    AlertDialog(
        onDismissRequest = { cerrar(false) },
        title = { Text("Texto") },
        text = { OutlinedTextField(texto, { texto = it }, minLines = 2, maxLines = 8, modifier = Modifier.focusRequester(foco)) },
        confirmButton = { TextButton(onClick = { cerrar(true) }) { Text("Poner") } },
        dismissButton = { TextButton(onClick = { cerrar(false) }) { Text("Cancelar") } }
    )
}
