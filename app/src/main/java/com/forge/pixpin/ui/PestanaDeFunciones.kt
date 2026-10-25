package com.forge.pixpin.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forge.pixpin.R
import com.forge.pixpin.guardados.AdjuntarFigura
import com.forge.pixpin.guardados.Clase
import com.forge.pixpin.guardados.HojaDeAdjuntarFigura
import com.forge.pixpin.guardados.Mensaje
import com.forge.pixpin.guardados.MensajesStore
import com.forge.pixpin.mini.VistaDeTareas
import com.forge.pixpin.motor.ExcalidrawStore
import com.forge.pixpin.motor.Scene
import com.forge.pixpin.ui.Funciones.Funcion
import com.forge.pixpin.ui.theme.Cristal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * **La pestaña de Funciones** (25-oct-2026, la `ventana_chat/funciones.rs` del PC, `768b73f8`):
 * lo que enseña la lista de chats con «Funciones» puesto en su interruptor. En el teléfono ocupa la
 * pantalla entera debajo de la barra, como dice `docs/ui-funciones-y-chats.md`. Las cuentas
 * (columnas, alturas, flechas) son las del PC, en [Funciones]; aquí solo se pinta y se hace.
 *
 * Con un teclado (o el mando de una tele) se mueve con las flechas, Inicio y Fin; Intro o Espacio
 * hacen el elegido y Escape vuelve a Chat ([onChat]).
 */
@Composable
internal fun PestanaDeFunciones(onChat: () -> Unit, modifier: Modifier = Modifier) {
    val contexto = LocalContext.current
    val alcance = rememberCoroutineScope()
    var elegido by remember { mutableStateOf<Int?>(null) }
    var figuras by remember { mutableStateOf(false) }
    val todas = Funcion.entries
    fun hacer(f: Funcion) {
        if (f == Funcion.FIGURAS) figuras = true else alcance.launch { hacerFuncion(contexto, f) }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val ancho = maxWidth.value.toInt()
        val alto = maxHeight.value.toInt()
        val g = remember(ancho, alto) { Funciones.rejilla(Funciones.Caja(0, 0, ancho, alto), todas.size) }
        val fondo = (g.mosaicos.maxOfOrNull { it.abajo } ?: 0) + Funciones.MARGEN
        Box(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .onKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                    val paso = when (ev.key) {
                        Key.DirectionLeft -> Funciones.Paso.IZQUIERDA
                        Key.DirectionRight -> Funciones.Paso.DERECHA
                        Key.DirectionUp -> Funciones.Paso.ARRIBA
                        Key.DirectionDown -> Funciones.Paso.ABAJO
                        Key.MoveHome -> Funciones.Paso.INICIO
                        Key.MoveEnd -> Funciones.Paso.FIN
                        else -> null
                    }
                    when {
                        paso != null -> { elegido = Funciones.mover(elegido, paso, g.columnas, todas.size); true }
                        ev.key == Key.Enter || ev.key == Key.Spacebar || ev.key == Key.DirectionCenter ->
                            elegido?.let { hacer(todas[it]); true } ?: false
                        ev.key == Key.Escape -> { onChat(); true }
                        else -> false
                    }
                }
                .focusable()
        ) {
            Box(Modifier.fillMaxWidth().height(maxOf(fondo, alto).dp)) {
                if (!g.soloIconos) Box(
                    Modifier.offset(g.titulo.x.dp, g.titulo.y.dp).width(g.titulo.ancho.dp).height(g.titulo.alto.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(stringResource(R.string.funciones_titulo), fontSize = 17.sp, color = Cristal.tinta)
                }
                todas.forEachIndexed { i, f ->
                    val c = g.mosaicos[i]
                    Mosaico(
                        f, g.soloIconos, g.conDetalle, elegido == i,
                        Modifier.offset(c.x.dp, c.y.dp).width(c.ancho.dp).height(c.alto.dp)
                    ) { elegido = i; hacer(f) }
                }
            }
        }
    }

    if (figuras) HojaDeAdjuntarFigura(onCerrar = { figuras = false }) { f ->
        figuras = false
        // Elegida, entra en «Guardados» como un lienzo con la figura dentro, y se abre.
        alcance.launch {
            val almacen = MensajesStore(contexto)
            val m = withContext(Dispatchers.IO) { runCatching { AdjuntarFigura.adjuntar(contexto, almacen, null, f) }.getOrNull() }
            val ref = m?.referencia
            if (ref == null) Toast.makeText(contexto, "No se pudo crear el lienzo con la figura", Toast.LENGTH_SHORT).show()
            else com.forge.pixpin.motor.DrawEditorActivity.abrir(contexto, ref, ExcalidrawStore.rutaDe(contexto, ref), null)
        }
    }
}

/** El icono de cada función: los de Material del documento (`mic`, `draw`, `checklist`…). */
private fun iconoDe(f: Funcion): ImageVector = when (f) {
    Funcion.GRABAR -> Icons.Filled.Mic
    Funcion.LIENZO -> Icons.Filled.Draw
    Funcion.TAREAS -> Icons.Filled.Checklist
    Funcion.TIMELINE -> Icons.Filled.Timeline
    Funcion.HORARIO -> Icons.Filled.CalendarMonth
    Funcion.GALERIA -> Icons.Filled.PhotoLibrary
    Funcion.FIGURAS -> Icons.Filled.Layers
}

private fun tituloDe(f: Funcion): Int = when (f) {
    Funcion.GRABAR -> R.string.funciones_grabar
    Funcion.LIENZO -> R.string.funciones_lienzo
    Funcion.TAREAS -> R.string.funciones_tareas
    Funcion.TIMELINE -> R.string.funciones_timeline
    Funcion.HORARIO -> R.string.funciones_horario
    Funcion.GALERIA -> R.string.funciones_galeria
    Funcion.FIGURAS -> R.string.funciones_figuras
}

private fun detalleDe(f: Funcion): Int = when (f) {
    Funcion.GRABAR -> R.string.funciones_grabar_detalle
    Funcion.LIENZO -> R.string.funciones_lienzo_detalle
    Funcion.TAREAS -> R.string.funciones_tareas_detalle
    Funcion.TIMELINE -> R.string.funciones_timeline_detalle
    Funcion.HORARIO -> R.string.funciones_horario_detalle
    Funcion.GALERIA -> R.string.funciones_galeria_detalle
    Funcion.FIGURAS -> R.string.funciones_figuras_detalle
}

/**
 * **Un mosaico**: esquinas de 12, el fondo de los botones de cristal (el de la caja del buscador en
 * el PC), y dentro el círculo de color con el icono en blanco, el título y la descripción en una
 * línea. Bajo, sin descripción y con el icono a la izquierda del título. Pulsado o elegido con el
 * teclado, el color de lo puesto.
 */
@Composable
private fun Mosaico(f: Funcion, soloIconos: Boolean, conDetalle: Boolean, elegido: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val toques = remember { MutableInteractionSource() }
    val pulsado by toques.collectIsPressedAsState()
    val forma = RoundedCornerShape(Funciones.RADIO.dp)
    val titulo = stringResource(tituloDe(f))
    val fondo = when {
        elegido -> Cristal.puesto
        pulsado -> Cristal.puesto.copy(alpha = 0.35f)
        else -> Cristal.boton
    }
    val tinta = if (elegido) Cristal.tintaPuesta else Cristal.tinta
    Box(
        modifier.clip(forma).background(fondo, forma)
            .clickable(interactionSource = toques, indication = null, onClick = onClick)
            .padding(if (soloIconos) 0.dp else Funciones.RELLENO.dp),
        contentAlignment = if (soloIconos) Alignment.Center else Alignment.TopStart
    ) {
        val circulo: @Composable () -> Unit = {
            Box(
                Modifier.size(Funciones.ICONO_LADO.dp).clip(CircleShape).background(Color(f.color)),
                contentAlignment = Alignment.Center
            ) { Icon(iconoDe(f), if (soloIconos) titulo else null, tint = Color.White, modifier = Modifier.size(20.dp)) }
        }
        when {
            soloIconos -> circulo()
            conDetalle -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                circulo()
                Spacer(Modifier.height(2.dp))
                Text(titulo, fontSize = 13.sp, color = tinta, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(detalleDe(f)), fontSize = 11.sp, color = tinta.copy(alpha = 0.6f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            else -> Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                circulo()
                Spacer(Modifier.width(10.dp))
                Text(titulo, fontSize = 13.sp, color = tinta, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * **Lo que hace cada mosaico** (la tabla del documento). Desde la lista no hay chat abierto, así
 * que lo que va a un chat va a «Guardados». **Timeline** abre las lecciones: en el teléfono no hay
 * timeline del día (lo apunta el PC), y las lecciones son lo que el PC lleva en su pestaña dentro.
 * **Horario** abre Tareas en su vista Horario, donde vive el horario desde el 25-oct-2026.
 */
private suspend fun hacerFuncion(contexto: Context, f: Funcion) {
    when (f) {
        Funcion.GRABAR -> com.forge.pixpin.guardados.GrabarClaseActivity.abrir(contexto, null)
        Funcion.LIENZO -> {
            val id = withContext(Dispatchers.IO) { lienzoEnGuardados(contexto) }
            if (id == null) Toast.makeText(contexto, "No se pudo crear el lienzo", Toast.LENGTH_SHORT).show()
            else com.forge.pixpin.motor.DrawEditorActivity.abrir(contexto, id, ExcalidrawStore.rutaDe(contexto, id), null)
        }
        Funcion.TAREAS -> com.forge.pixpin.mini.TodasLasTareasActivity.abrir(contexto)
        Funcion.TIMELINE -> com.forge.pixpin.lecciones.LeccionesActivity.abrir(contexto)
        Funcion.HORARIO -> com.forge.pixpin.mini.TodasLasTareasActivity.abrir(contexto, VistaDeTareas.Vista.HORARIO)
        Funcion.GALERIA -> GaleriaDeCapturasActivity.abrir(contexto)
        Funcion.FIGURAS -> Unit // La hoja de figuras la abre la pestaña.
    }
}

/** **Un lienzo en blanco en «Guardados»**, como el «Lienzo» del «+» del chat: su id, o null si no se pudo escribir. */
private fun lienzoEnGuardados(contexto: Context): String? = runCatching {
    val ahora = System.currentTimeMillis()
    val id = "dib-$ahora"
    ExcalidrawStore.guardar(contexto, id, Scene()) ?: return@runCatching null
    MensajesStore(contexto).anadir(
        Mensaje(id = UUID.randomUUID().toString(), cuando = ahora, clase = Clase.DIBUJO, nombre = "Lienzo", referencia = id, proyecto = null)
    )
    id
}.getOrNull()
