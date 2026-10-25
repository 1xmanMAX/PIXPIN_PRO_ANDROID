package com.forge.pixpin.motor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.FormatAlignRight
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatAlignJustify
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.TextDecrease
import androidx.compose.material.icons.filled.TextIncrease
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

/**
 * **La barra del texto rico** (25-oct-2026): negrita, cursiva, subrayado, tachado, color y tamaño
 * de lo elegido, la alineación de cada renglón y una fórmula `$...$`, sobre el cuadro de escribir
 * del lienzo.
 *
 * Como en el PC (`docs/texto-rico-del-lienzo.md`, «Editar»), **se escribe la fuente tal cual** y
 * cada botón pone sus marcas alrededor de lo elegido (`**X**`, `\color{rojo}{X}`...), o las pone
 * vacías con el cursor en medio si no hay nada elegido. Al cerrar el cuadro se compone. Sin un
 * editor «de verdad» con estilos ocultos: la fuente es lo que viaja al PC y lo que se vuelve a
 * abrir, así que enseñarla es no esconder nada, y vale para lo que el teclado no tiene botón
 * (`\frac`, `\overline{AB}`, `\sqrt`).
 *
 * Los botones no cogen el foco: el teclado sigue abierto y la selección del cuadro sigue viva.
 */
@Composable
fun BarraDeTextoRico(
    valor: TextFieldValue,
    onCambio: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier
) {
    fun aplicar(f: (TextoRico.Edicion) -> TextoRico.Edicion) {
        val r = f(TextoRico.Edicion(valor.text, valor.selection.start, valor.selection.end))
        onCambio(TextFieldValue(r.texto, TextRange(r.desde, r.hasta)))
    }
    fun envolver(abre: String, cierra: String) = aplicar { TextoRico.envolver(it, abre, cierra) }
    fun alinear(a: TextoRico.Alineado) = aplicar { TextoRico.alinear(it, a) }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        tonalElevation = 3.dp,
        shadowElevation = 3.dp
    ) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Boton(Icons.Filled.FormatBold, "Negrita") { envolver("**", "**") }
            Boton(Icons.Filled.FormatItalic, "Cursiva") { envolver("*", "*") }
            Boton(Icons.Filled.FormatUnderlined, "Subrayado") { envolver("<u>", "</u>") }
            Boton(Icons.Filled.FormatStrikethrough, "Tachado") { envolver("~~", "~~") }
            Separador()
            // Los colores de los atajos del PC (Ctrl+Mayús+1..6 y 0), por su nombre: así la
            // fuente se lee («\color{rojo}{X}») y el PC la entiende igual.
            for ((nombre, argb) in COLORES) {
                Box(
                    Modifier.padding(3.dp).size(22.dp).background(Color(argb), CircleShape)
                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                        .clickable { envolver("\\color{$nombre}{", "}") }
                )
            }
            Separador()
            Boton(Icons.Filled.TextDecrease, "Más pequeño") { envolver("\\tam{0.8}{", "}") }
            Boton(Icons.Filled.TextIncrease, "Más grande") { envolver("\\tam{1.5}{", "}") }
            Separador()
            Boton(Icons.AutoMirrored.Filled.FormatAlignLeft, "Renglón a la izquierda") {
                alinear(TextoRico.Alineado.IZQUIERDA)
            }
            Boton(Icons.Filled.FormatAlignCenter, "Renglón centrado") { alinear(TextoRico.Alineado.CENTRO) }
            Boton(Icons.AutoMirrored.Filled.FormatAlignRight, "Renglón a la derecha") {
                alinear(TextoRico.Alineado.DERECHA)
            }
            Boton(Icons.Filled.FormatAlignJustify, "Renglón justificado") {
                alinear(TextoRico.Alineado.JUSTIFICADO)
            }
            Separador()
            Boton(Icons.Filled.Functions, "Fórmula") { envolver("$", "$") }
        }
    }
}

@Composable
private fun Boton(icono: ImageVector, descripcion: String, alPulsar: () -> Unit) {
    Box(
        Modifier.size(36.dp).clickable(onClick = alPulsar),
        contentAlignment = Alignment.Center
    ) {
        Icon(icono, descripcion, Modifier.size(20.dp))
    }
}

@Composable
private fun Separador() {
    Spacer(Modifier.width(6.dp))
}

/** Los colores de la barra: los de los atajos del PC, con el valor de `colorDeNombre`. */
private val COLORES = listOf(
    "rojo" to 0xFFE03131, "azul" to 0xFF1971C2, "verde" to 0xFF2F9E44, "naranja" to 0xFFE8590C,
    "morado" to 0xFF6741D9, "gris" to 0xFF868E96, "negro" to 0xFF1E1E1E
)
