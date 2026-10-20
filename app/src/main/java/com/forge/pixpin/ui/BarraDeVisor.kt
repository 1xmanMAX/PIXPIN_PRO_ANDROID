package com.forge.pixpin.ui

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay

/**
 * **La barra de arriba y el riel de los visores, como los pines del PC** (20-oct-2026, pedido por el
 * usuario: «en los pines de Windows hay una barra superior y una barra lateral; en Android también
 * quiero una barra superior y una lateral, similar, y que en el lateral aparezca editar, marcadores
 * y frames, igual que en Windows»). Manual del PC: `docs/sincronizar-marcos-vistas-y-tinta.md` §5.
 *
 * - **Barra** ([BarraDeVisor]): tipo y nombre, los botones propios del visor, luna/sol, pantalla
 *   completa, menú ⋮ (sin repetir lo que ya tiene botón) y cerrar. De cristal, a todo lo ancho.
 * - **Riel** ([RielDeVisor]): a la derecha, arriba, botones de 40 dp: marcador, lápiz (anotar) y
 *   marco (marcos para imprimir). Pulsado = azul `#0060DF` con el icono blanco. Un toque largo dice
 *   su nombre, que es lo que en el PC hace el globo al dejar el ratón encima.
 *
 * Editar el texto (una nota, una celda) usa otro icono (`EditNote`): el lápiz es solo anotar.
 */

private val CRISTAL = Color(0xD914182B)
private val AZUL_PULSADO = Color(com.forge.pixpin.motor.MarcosDoc.AZUL_PULSADO)

/** Una opción del menú ⋮. */
class OpcionDeMenu(val texto: String, val icono: ImageVector? = null, val alTocar: () -> Unit)

@Composable
fun BarraDeVisor(
    visible: Boolean,
    tipo: String,
    nombre: String,
    botones: List<BotonDeLector>,
    modifier: Modifier = Modifier,
    oscuro: Boolean? = null,
    onOscuro: (() -> Unit)? = null,
    pantallaCompleta: Boolean? = null,
    onPantallaCompleta: (() -> Unit)? = null,
    menu: List<OpcionDeMenu> = emptyList(),
    onCerrar: (() -> Unit)? = null
) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        Row(
            Modifier.fillMaxWidth().background(CRISTAL).statusBarsPadding().padding(start = 10.dp, end = 2.dp).padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                tipo.uppercase(), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(Color.White.copy(alpha = 0.16f)).padding(horizontal = 6.dp, vertical = 2.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                nombre, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontSize = 14.sp, modifier = Modifier.weight(1f)
            )
            for (b in botones) BotonDeBarra(b.icono, b.descripcion, false, alTocar = b.alTocar)
            if (oscuro != null && onOscuro != null)
                BotonDeBarra(if (oscuro) Icons.Filled.LightMode else Icons.Filled.DarkMode, if (oscuro) "Claro" else "Oscuro", false, alTocar = onOscuro)
            if (pantallaCompleta != null && onPantallaCompleta != null)
                BotonDeBarra(if (pantallaCompleta) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen, "Pantalla completa", pantallaCompleta, alTocar = onPantallaCompleta)
            if (menu.isNotEmpty()) {
                var abierto by remember { mutableStateOf(false) }
                Box {
                    BotonDeBarra(Icons.Filled.MoreVert, "Más", false, alTocar = { abierto = true })
                    DropdownMenu(expanded = abierto, onDismissRequest = { abierto = false }) {
                        for (o in menu) DropdownMenuItem(
                            text = { Text(o.texto) },
                            leadingIcon = o.icono?.let { i -> { Icon(i, contentDescription = null) } },
                            onClick = { abierto = false; o.alTocar() }
                        )
                    }
                }
            }
            if (onCerrar != null) BotonDeBarra(Icons.Filled.Close, "Cerrar", false, alTocar = onCerrar)
        }
    }
}

/** Un botón de la barra o del riel: pulsado, azul con el icono blanco; un toque largo dice su nombre. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BotonDeBarra(icono: ImageVector, nombre: String, pulsado: Boolean, lado: Int = 38, conFondo: Boolean = false, alTocar: () -> Unit) {
    var globo by remember { mutableStateOf(false) }
    val vibrar = LocalHapticFeedback.current
    LaunchedEffect(globo) { if (globo) { delay(1600); globo = false } }
    Box(contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(lado.dp).clip(RoundedCornerShape(if (conFondo) 10.dp else 50.dp))
                .background(if (pulsado) AZUL_PULSADO else if (conFondo) CRISTAL else Color.Transparent)
                .combinedClickable(
                    onClickLabel = nombre,
                    onLongClick = { vibrar.performHapticFeedback(HapticFeedbackType.LongPress); globo = true },
                    onClick = alTocar
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(icono, contentDescription = nombre, tint = Color.White.copy(alpha = if (pulsado) 1f else 0.92f), modifier = Modifier.size((lado * 0.5f).dp))
        }
        if (globo) Popup(alignment = Alignment.CenterStart, offset = androidx.compose.ui.unit.IntOffset(-16, 0)) {
            Text(
                nombre, color = Color.White, fontSize = 12.sp,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xF014182B)).padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
    }
}

/** Un botón del riel. */
class BotonDeRiel(val icono: ImageVector, val nombre: String, val pulsado: Boolean, val alTocar: () -> Unit)

/**
 * **El riel de los visores**: a la derecha, arriba. Los tres de siempre se hacen con
 * [botonesDelRiel]; un visor puede añadir los suyos al final.
 */
@Composable
fun RielDeVisor(botones: List<BotonDeRiel>, modifier: Modifier = Modifier) {
    Column(
        modifier.padding(end = 6.dp, top = 6.dp).clip(RoundedCornerShape(14.dp)).background(CRISTAL).padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        for (b in botones) BotonDeBarra(b.icono, b.nombre, b.pulsado, lado = 40, alTocar = b.alTocar)
    }
}

/**
 * Los tres del riel, en el orden del PC: marcador, anotar y marcos para imprimir. Un null no sale
 * (un visor que aún no anota, por ejemplo).
 */
fun botonesDelRiel(
    poniendoMarca: Boolean, onMarca: (() -> Unit)?,
    anotando: Boolean, onAnotar: (() -> Unit)?,
    enmarcando: Boolean, onMarcos: (() -> Unit)?
): List<BotonDeRiel> = listOfNotNull(
    onMarca?.let { BotonDeRiel(Icons.Filled.BookmarkAdd, "Marcador", poniendoMarca, it) },
    onAnotar?.let { BotonDeRiel(Icons.Filled.Edit, "Anotar", anotando, it) },
    onMarcos?.let { BotonDeRiel(Icons.Filled.CropFree, "Marcos para imprimir", enmarcando, it) }
)

/** **Pantalla completa** de verdad: sin la barra de estado ni la de navegación (salen al deslizar). */
fun ponerPantallaCompleta(a: Activity, si: Boolean) {
    val c = WindowCompat.getInsetsController(a.window, a.window.decorView)
    if (si) {
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
    } else c.show(WindowInsetsCompat.Type.systemBars())
}
