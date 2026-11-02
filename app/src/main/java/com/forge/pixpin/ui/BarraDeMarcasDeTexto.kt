package com.forge.pixpin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.filled.ContentCopy
import com.forge.pixpin.R
import com.forge.pixpin.motormd.AnclaDeTexto

/**
 * **La barrita de marcar el texto** (2-nov-2026, la `barra-marcas.js` del PC): los cinco colores, las
 * cinco marcas (resaltar, ondulada, subrayar, tachar, tapar), cada una con una «A» que se ve como
 * quedará, y quitar la marca.
 */
@Composable
fun BarraDeMarcasDeTexto(
    color: Int,
    onColor: (Int) -> Unit,
    onMarca: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** «Copiar» lo elegido, delante de los colores (como el PC); null, no sale. */
    onCopiar: (() -> Unit)? = null
) {
    val colores = AnclaDeTexto.COLORES.map { Color(0xFF000000 or it.substring(1).toLong(16)) }
    val elegido = colores.getOrElse(color) { colores[4] }
    val rotulos = mapOf(
        "resaltar" to stringResource(R.string.mt_resaltar), "ondulada" to stringResource(R.string.mt_ondulada),
        "subrayar" to stringResource(R.string.mt_subrayar), "tachar" to stringResource(R.string.mt_tachar),
        "tapar" to stringResource(R.string.mt_tapar), "quitar" to stringResource(R.string.mt_quitar)
    )
    val rotuloColor = stringResource(R.string.mt_color)
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 6.dp,
        shadowElevation = 6.dp
    ) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            if (onCopiar != null) {
                val rotuloCopiar = stringResource(R.string.mt_copiar)
                Box(
                    Modifier.size(38.dp).clip(RoundedCornerShape(8.dp)).clickable { onCopiar() }.semantics { contentDescription = rotuloCopiar },
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.ContentCopy, rotuloCopiar, Modifier.size(18.dp))
                }
                Spacer(Modifier.width(4.dp).height(22.dp).padding(horizontal = 1.5.dp).background(MaterialTheme.colorScheme.outlineVariant))
            }
            colores.forEachIndexed { i, c ->
                Box(
                    Modifier.size(34.dp).clip(CircleShape).clickable { onColor(i) }.semantics { contentDescription = rotuloColor },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        Modifier.size(18.dp).clip(CircleShape).background(c)
                            .then(if (i == color) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                    )
                }
            }
            Spacer(Modifier.width(4.dp).height(22.dp).padding(horizontal = 1.5.dp).background(MaterialTheme.colorScheme.outlineVariant))
            for (m in listOf("resaltar", "ondulada", "subrayar", "tachar", "tapar", "quitar")) {
                Box(
                    Modifier.size(38.dp).clip(RoundedCornerShape(8.dp)).clickable { onMarca(m) }
                        .semantics { contentDescription = rotulos.getValue(m) },
                    contentAlignment = Alignment.Center
                ) {
                    when (m) {
                        "quitar" -> Box(
                            Modifier.size(18.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f))
                                .drawBehind {
                                    drawLine(Color.White, Offset(size.width * 0.2f, size.height * 0.8f), Offset(size.width * 0.8f, size.height * 0.2f), 2.dp.toPx())
                                }
                        )
                        "tapar" -> Box(Modifier.width(18.dp).height(10.dp).clip(RoundedCornerShape(2.dp)).background(elegido))
                        else -> Text(
                            "A",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            textDecoration = when (m) {
                                "subrayar", "ondulada" -> TextDecoration.Underline
                                "tachar" -> TextDecoration.LineThrough
                                else -> null
                            },
                            color = if (m == "resaltar") MaterialTheme.colorScheme.onSurface else elegido,
                            modifier = if (m == "resaltar") Modifier.background(elegido.copy(alpha = 0.55f), RoundedCornerShape(3.dp)).padding(horizontal = 3.dp) else Modifier
                        )
                    }
                }
            }
        }
    }
}

/** Para alinear la barrita bajo el texto de la nota: el ancho entero, centrada. */
fun Modifier.barraDeMarcasAbajo(): Modifier = this.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)
