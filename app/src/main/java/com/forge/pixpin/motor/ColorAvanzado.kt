package com.forge.pixpin.motor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forge.pixpin.R

/**
 * **Lo avanzado del color** (2-nov-2026, el desplegable de color del PC, `panel_lateral.rs`): los
 * **recientes** (los cinco últimos elegidos a mano), y el **personalizado**: el código de 3, 6 u 8
 * cifras (las dos últimas, la opacidad), una muestra con su **contraste** con el papel, la
 * **opacidad** y el **cuentagotas** (copiar un color de la pantalla). Las cuentas, en [SelectorDeColor].
 */
@Composable
fun ColorAvanzado(
    actual: String,
    opacidad: Int,
    recientes: List<String>,
    papelOscuro: Boolean,
    onElegir: (String) -> Unit,
    onOpacidad: ((Int) -> Unit)?,
    onCuentagotas: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (recientes.isNotEmpty()) {
            Text(stringResource(R.string.lc_recientes), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (c in recientes) {
                    val esEste = c.equals(actual, true)
                    Box(
                        Modifier.size(22.dp).clip(CircleShape).background(Color(parseColor(c, 255)))
                            .border(if (esEste) 2.dp else 1.dp, if (esEste) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape)
                            .clickable { onElegir(c) }
                    )
                }
            }
        }
        Text(stringResource(R.string.lc_personalizado), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val rgb = SelectorDeColor.deHex(actual) ?: SelectorDeColor.Rgb(0f, 0f, 0f)
            val papel = if (papelOscuro) SelectorDeColor.Rgb(0.08f, 0.09f, 0.11f) else SelectorDeColor.Rgb(1f, 1f, 1f)
            val ratio = SelectorDeColor.contraste(rgb, papel)
            // La muestra sobre el papel, con «Aa» en el color: se ve si se va a leer.
            Box(
                Modifier.size(width = 44.dp, height = 30.dp).clip(RoundedCornerShape(6.dp))
                    .background(if (papelOscuro) Color(0xFF15171C) else Color.White)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center
            ) { Text("Aa", color = Color(parseColor(actual, 255)), fontWeight = FontWeight.Bold, fontSize = 15.sp) }
            var escrito by remember { mutableStateOf(SelectorDeColor.escribirCodigo(rgb, opacidad / 100f)) }
            LaunchedEffect(actual, opacidad) { escrito = SelectorDeColor.escribirCodigo(rgb, opacidad / 100f) }
            val bien = SelectorDeColor.leerCodigo(escrito) != null
            fun aplicar() {
                val (c, a) = SelectorDeColor.leerCodigo(escrito) ?: return
                onElegir(SelectorDeColor.aHex(c))
                if (a != null) onOpacidad?.invoke((a * 100).toInt().coerceIn(0, 100))
            }
            Row(
                Modifier.width(104.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, if (bien) MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.error, RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("#", color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                BasicTextField(
                    value = escrito,
                    onValueChange = { v -> escrito = v.filter { it.isLetterOrDigit() || it == '#' }.removePrefix("#").take(8); if (escrito.length in setOf(6, 8)) aplicar() },
                    singleLine = true,
                    textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { aplicar() })
                )
            }
            Text(
                stringResource(R.string.lc_contraste) + " " + "%.1f".format(ratio) + ":1" + if (ratio >= 4.5f) " ✓" else if (ratio < 3f) " ⚠" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (onCuentagotas != null) Box(
                Modifier.size(30.dp).clip(CircleShape).clickable { onCuentagotas() },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.Colorize, stringResource(R.string.lc_cuentagotas), Modifier.size(18.dp)) }
        }
        if (onOpacidad != null) Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.lc_opacidad) + " $opacidad %", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(96.dp))
            Slider(value = opacidad.toFloat(), onValueChange = { onOpacidad(it.toInt()) }, valueRange = 0f..100f, modifier = Modifier.width(160.dp))
        }
    }
}
