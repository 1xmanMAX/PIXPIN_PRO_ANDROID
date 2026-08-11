package com.forge.pixpin.markdown

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * El editor en vivo: **lo que escribes se ve ya hecho**.
 *
 * ## Cómo funciona
 *
 * El documento se parte en bloques —ver [trozosDe]— y cada uno se pinta ya
 * interpretado, con su tabla dibujada y su imagen puesta. El único que se ve en
 * crudo es **aquel donde está el cursor**: ahí hay un campo de texto normal, con
 * su teclado y su selección. En cuanto te vas a otro, el de antes se pinta.
 *
 * Es lo que hace Telegram en su `RichEditorListView`, donde cada bloque es una
 * fila de una lista y las de texto son editables. Y es lo que hace falta para
 * que no haya un botón de vista previa: no hay dos modos, hay un documento
 * pintado con un agujero donde estás escribiendo.
 *
 * ## Por qué el trozo activo se busca por posición y no por número
 *
 * Al escribir, el documento se vuelve a partir entero. Si el trozo activo fuera
 * «el número tres», bastaría con que una línea en blanco juntara dos bloques
 * para que el tres pasara a ser otro y el cursor apareciera en un sitio que
 * nadie tocó. Guardando **dónde está el cursor dentro del documento entero**,
 * eso no puede pasar: se vuelve a partir, se busca qué trozo contiene esa
 * posición, y sale el mismo sitio aunque los bloques de alrededor hayan cambiado
 * de forma o de número.
 */
@Composable
fun EditorVivo(
    valor: TextFieldValue,
    onValor: (TextFieldValue) -> Unit,
    baseSizeSp: Float = 16f,
    modifier: Modifier = Modifier,
    ocultosVisibles: Set<String> = emptySet()
) {
    val texto = valor.text
    val trozos = remember(texto) { trozosDe(texto) }
    val activo = remember(trozos, valor.selection) { trozoEn(trozos, valor.selection.start) }

    Column(modifier) {
        trozos.forEachIndexed { i, trozo ->
            val fuente = trozo.de(texto)
            if (i == activo) {
                TrozoEnEdicion(
                    fuente = fuente,
                    // La selección se lleva en coordenadas del documento entero
                    // y se traduce aquí: la barra de formato la necesita así, y
                    // el campo la necesita local.
                    seleccion = TextRange(
                        (valor.selection.start - trozo.desde).coerceIn(0, fuente.length),
                        (valor.selection.end - trozo.desde).coerceIn(0, fuente.length)
                    ),
                    baseSizeSp = baseSizeSp,
                    onCambio = { local ->
                        val entero = texto.substring(0, trozo.desde) +
                            local.text +
                            texto.substring(trozo.hasta)
                        onValor(
                            TextFieldValue(
                                entero,
                                TextRange(
                                    trozo.desde + local.selection.start,
                                    trozo.desde + local.selection.end
                                )
                            )
                        )
                    }
                )
            } else {
                TrozoPintado(
                    fuente = fuente,
                    baseSizeSp = baseSizeSp,
                    ocultosVisibles = ocultosVisibles,
                    clave = "t$i",
                    onTocar = {
                        // Al tocar un bloque pintado el cursor se va al final de
                        // su texto, que es donde se sigue escribiendo. Ponerlo en
                        // el punto exacto del toque obligaría a medir el texto ya
                        // pintado, y no lo vale.
                        val fin = trozo.de(texto).trimEnd('\n').length + trozo.desde
                        onValor(valor.copy(selection = TextRange(fin.coerceIn(0, texto.length))))
                    }
                )
            }
        }
    }
}

/**
 * El bloque donde está el cursor, en crudo.
 *
 * Se ve el Markdown tal cual —los asteriscos, las barras de la tabla— porque es
 * lo que se está editando. En monoespaciada: las marcas se cuentan con la vista
 * y en proporcional los asteriscos se esconden entre las letras.
 */
@Composable
private fun TrozoEnEdicion(
    fuente: String,
    seleccion: TextRange,
    baseSizeSp: Float,
    onCambio: (TextFieldValue) -> Unit
) {
    val foco = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { foco.requestFocus() } }

    BasicTextField(
        value = TextFieldValue(fuente, seleccion),
        onValueChange = { onCambio(it) },
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(foco)
            .padding(vertical = 2.dp),
        textStyle = TextStyle(
            fontSize = baseSizeSp.sp,
            lineHeight = (baseSizeSp * 1.5f).sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)
    )
}

/** Un bloque cualquiera, ya pintado. Al tocarlo, se abre para escribir. */
@Composable
private fun TrozoPintado(
    fuente: String,
    baseSizeSp: Float,
    ocultosVisibles: Set<String>,
    clave: String,
    onTocar: () -> Unit
) {
    val bloques = remember(fuente) { Markdown.parse(fuente) }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { onTocar() }
            .padding(vertical = 2.dp)
    ) {
        if (bloques.isEmpty()) {
            // Un trozo en blanco sigue ocupando su renglón: si no, tocarlo para
            // volver a escribir ahí sería imposible.
            BasicTextField(
                value = "",
                onValueChange = {},
                enabled = false,
                textStyle = TextStyle(fontSize = baseSizeSp.sp),
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            MarkdownText(
                blocks = bloques,
                baseSizeSp = baseSizeSp,
                ocultosVisibles = ocultosVisibles
            )
        }
    }
}
