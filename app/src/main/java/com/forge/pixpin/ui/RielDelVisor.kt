package com.forge.pixpin.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * **El riel de la derecha, el mismo en todos los visores** (24-oct-2026, como el PC desde el 19-oct:
 * `lector::pintar_riel_con`, `riel_pin.rs`, `visor_hoja/riel.rs`, `visor_html/riel.rs`).
 *
 * El usuario: «en la barra lateral aparecen siempre tres opciones y las tres aparecen en todos los
 * formatos que se abren»:
 *
 * 1. **Marcador** (`BookmarkAdd`): la tira de emoticonos para plantar uno donde se está.
 * 2. **Lápiz** (`Edit`): anotar encima. Todo visor abre **mirando**; el lápiz pasa a editar con la
 *    barra entera de herramientas y otra vez vuelve a mirar.
 * 3. **Marco** (`CropFree`): los marcos numerados para imprimir ([CapaDeMarcos]).
 *
 * La isla de cristal oscuro del PC (`#26262C` al 92 %), botones de 40 dp, y lo puesto en el azul
 * `#0060DF` con el icono blanco. Debajo, los puntos de los marcadores ([RielDeMarcas]). Mantener el
 * dedo en un botón dice su nombre a su izquierda (el «globo» del PC).
 *
 * Un visor que no tiene una de las tres —un pin no tiene sitio al que volver— pasa null.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RielDelVisor(
    poniendoMarca: Boolean,
    anotando: Boolean,
    marcando: Boolean,
    onMarcador: (() -> Unit)?,
    onLapiz: (() -> Unit)?,
    onMarco: (() -> Unit)?,
    modifier: Modifier = Modifier,
    /** Lo que dice el lápiz: «Anotar» en los lectores, «Editar» en el lienzo. */
    rotuloDelLapiz: String = if (anotando) "Dejar de anotar" else "Anotar",
    marcas: Int = 0,
    emojiDe: (Int) -> String = { "" },
    alElegirMarca: (Int) -> Unit = {},
    visible: Boolean = true
) {
    var globo by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(globo) { if (globo != null) { kotlinx.coroutines.delay(1600); globo = null } }
    val vibrar = LocalHapticFeedback.current
    Column(modifier.padding(end = 6.dp), horizontalAlignment = Alignment.End) {
        AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
            Row(verticalAlignment = Alignment.Top) {
                globo?.let { g ->
                    Text(
                        g, color = Color.White, fontSize = 13.sp,
                        modifier = Modifier.padding(top = 8.dp, end = 6.dp).clip(RoundedCornerShape(50))
                            .background(Color(0xEB26262C)).padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
                Column(
                    Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xEB26262C)).padding(4.dp)
                ) {
                    @Composable
                    fun Boton(icono: ImageVector, nombre: String, puesto: Boolean, al: () -> Unit) {
                        Box(
                            Modifier.size(40.dp).clip(RoundedCornerShape(7.dp))
                                .background(if (puesto) Color(0xFF0060DF) else Color.Transparent)
                                .semantics { contentDescription = nombre }
                                .combinedClickable(
                                    onClick = al,
                                    onLongClick = { vibrar.performHapticFeedback(HapticFeedbackType.LongPress); globo = nombre }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(icono, contentDescription = null, tint = if (puesto) Color.White else Color(0xFFE4E2E6), modifier = Modifier.size(22.dp))
                        }
                    }
                    if (onMarcador != null) Boton(Icons.Filled.BookmarkAdd, "Marcador", poniendoMarca, onMarcador)
                    if (onLapiz != null) Boton(Icons.Filled.Edit, rotuloDelLapiz, anotando, onLapiz)
                    if (onMarco != null) Boton(Icons.Filled.CropFree, "Marcos para imprimir", marcando, onMarco)
                }
            }
        }
        if (marcas > 0) {
            Spacer(Modifier.height(10.dp))
            RielDeMarcas(marcas, emojiDe, Modifier, alElegirMarca)
        }
    }
}
