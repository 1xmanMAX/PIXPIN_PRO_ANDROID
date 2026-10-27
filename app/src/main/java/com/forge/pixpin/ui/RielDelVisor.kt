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
import androidx.compose.material.icons.filled.Print
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
 * La pastilla de cristal del visor de Revit ([CRISTAL_DE_VISOR], 25-oct-2026: «que los demás
 * visores copien ese estilo»), botones redondos de 42 dp, y lo puesto en el azul `#0060DF` con el
 * icono blanco. Lo propio de un formato que va a un lado (el 3D: piezas, medir, al croquis) entra
 * debajo, en [extras], con [BotonDelRiel]: una sola columna, nada que se pise. Debajo, los puntos de los marcadores ([RielDeMarcas]). Mantener el
 * dedo en un botón dice su nombre a su izquierda (el «globo» del PC).
 *
 * Un visor que no tiene una de las tres —un pin no tiene sitio al que volver— pasa null.
 *
 * **Mantener el marco imprime** (25-oct-2026, el usuario: «que al mantener presionado ese botón un
 * segundo me mande a imprimir los marcos, o si no, el documento entero»): con marcos puestos salen
 * las dos opciones; sin ninguno, el documento entero directamente. Así no hace falta otro botón de
 * imprimir en la barra de arriba.
 *
 * **Se va con las barras** (27-oct-2026, el usuario: «al mover el contenido se esconden la barra de
 * arriba y la de abajo, pero la lateral no»): cada visor le pasa en [visible] su mismo «a la vista»
 * ([RielALaVista.visible]), y con él se funden también los puntos de los marcadores, que antes se
 * quedaban sueltos en el canto. [alUsar] avisa de cada toque en el riel para que el visor reinicie
 * la cuenta que esconde las barras: si no, se iba bajo el dedo de quien estaba por tocarlo.
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
    visible: Boolean = true,
    /** Imprimir los marcos puestos (una hoja por marco); null si no hay ninguno. */
    imprimirMarcos: (() -> Unit)? = null,
    /** Imprimir el documento entero. */
    imprimirTodo: (() -> Unit)? = null,
    /** Los botones propios del formato, debajo de los tres y tras una raya. */
    extras: (@Composable () -> Unit)? = null,
    /** Cada toque en el riel (un botón o un marcador): el visor vuelve a contar para esconder las barras. */
    alUsar: () -> Unit = {}
) {
    var eligiendoQueImprimir by remember { mutableStateOf(false) }
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
                            .background(CRISTAL_FUERTE).padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
                Column(
                    Modifier.clip(RoundedCornerShape(50)).background(CRISTAL_DE_VISOR).padding(vertical = 4.dp, horizontal = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    @Composable
                    fun Boton(icono: ImageVector, nombre: String, puesto: Boolean, alMantener: (() -> Unit)? = null, al: () -> Unit) =
                        BotonDelRiel(icono, nombre, puesto, alMantener = {
                            vibrar.performHapticFeedback(HapticFeedbackType.LongPress)
                            alUsar()
                            if (alMantener != null) alMantener() else globo = nombre
                        }, al = { alUsar(); al() })
                    if (onMarcador != null) Boton(Icons.Filled.BookmarkAdd, "Marcador", poniendoMarca, al = onMarcador)
                    if (onLapiz != null) Boton(Icons.Filled.Edit, rotuloDelLapiz, anotando, al = onLapiz)
                    if (onMarco != null) Box {
                        val alMantener: (() -> Unit)? = when {
                            imprimirMarcos != null && imprimirTodo != null -> ({ eligiendoQueImprimir = true })
                            imprimirMarcos != null -> imprimirMarcos
                            imprimirTodo != null -> imprimirTodo
                            else -> null
                        }
                        Boton(Icons.Filled.CropFree, "Marcos para imprimir (mantener: imprimir)", marcando, alMantener, al = onMarco)
                        androidx.compose.material3.DropdownMenu(expanded = eligiendoQueImprimir, onDismissRequest = { eligiendoQueImprimir = false }) {
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("Imprimir los marcos") },
                                leadingIcon = { Icon(Icons.Filled.CropFree, null) },
                                onClick = { eligiendoQueImprimir = false; imprimirMarcos?.invoke() }
                            )
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("Imprimir todo") },
                                leadingIcon = { Icon(androidx.compose.material.icons.Icons.Filled.Print, null) },
                                onClick = { eligiendoQueImprimir = false; imprimirTodo?.invoke() }
                            )
                        }
                    }
                    if (extras != null) {
                        Box(Modifier.padding(vertical = 4.dp).size(width = 22.dp, height = 1.dp).background(Color.White.copy(alpha = 0.25f)))
                        extras()
                    }
                }
            }
        }
        // Los puntos de los marcadores, con el riel: se van y vuelven con él.
        if (marcas > 0) AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
            Column(horizontalAlignment = Alignment.End) {
                Spacer(Modifier.height(10.dp))
                RielDeMarcas(marcas, emojiDe, Modifier) { i -> alUsar(); alElegirMarca(i) }
            }
        }
    }
}

/** El cristal del globo con el nombre: el de las barras, algo más tupido para leerse sobre cualquier cosa. */
private val CRISTAL_FUERTE = Color(0xE014182B)

/**
 * Un botón del riel: redondo, 42 dp, el icono claro y, puesto, el fondo azul `#0060DF`. Público para
 * que lo propio de un formato ([RielDelVisor] `extras`) se vea igual que los tres de siempre.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BotonDelRiel(icono: ImageVector, nombre: String, puesto: Boolean = false, alMantener: (() -> Unit)? = null, al: () -> Unit) {
    Box(
        Modifier.size(42.dp).clip(androidx.compose.foundation.shape.CircleShape)
            .background(if (puesto) Color(0xFF0060DF) else Color.Transparent)
            .semantics { contentDescription = nombre }
            .combinedClickable(onClick = al, onLongClick = alMantener),
        contentAlignment = Alignment.Center
    ) {
        Icon(icono, contentDescription = null, tint = if (puesto) Color.White else Color.White.copy(alpha = 0.9f), modifier = Modifier.size(21.dp))
    }
}
