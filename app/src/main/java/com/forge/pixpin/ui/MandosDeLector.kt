package com.forge.pixpin.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * **Los mandos de los lectores rápidos, uno para todos** (23-sep-2026, pedido por el usuario:
 * «todos son visores rápidos: tienen que llevar el mismo lenguaje de diseño y las mismas
 * funciones»). El de Word y libros ([VisorHtmlActivity]) y el de PDF
 * ([com.forge.pixpin.pdf.LectorPdfActivity]) se hacen con estas piezas: la pastilla de arriba,
 * el engranaje por apartados, los mandos de los lados y la barra de la página que sube sola.
 *
 * El criterio del rediseño: lo que es sí o no, **interruptor**; lo que es cuánto, **deslizador**;
 * elegir entre pocas, **fichas**; hacer algo, **botón de texto**. Todo de tamaño mesurado.
 */

private val FONDO_DE_PASTILLA = Color(0x8C14182B)
private val AZUL = Color(0xFF8AB4F8)
private val AMBAR = Color(0xFFFFC440)

/** Un botón de la pastilla: su icono, lo que dice a quien no ve y qué hace. */
class BotonDeLector(val icono: ImageVector, val descripcion: String, val alTocar: () -> Unit)

/**
 * **La pastilla de arriba**: el nombre del documento y los botones. Siempre semitransparente;
 * sale con [visible] (al entrar y al tocar) y se va sola. [alTocarElNombre] deja cambiarlo.
 */
@Composable
fun PastillaDeLector(
    visible: Boolean,
    nombre: String,
    botones: List<BotonDeLector>,
    modifier: Modifier = Modifier,
    alTocarElNombre: (() -> Unit)? = null,
    nombreEditable: (@Composable () -> Unit)? = null
) {
    AnimatedVisibility(
        visible = visible, enter = fadeIn(), exit = fadeOut(),
        modifier = modifier.statusBarsPadding().padding(top = 8.dp, start = 24.dp, end = 24.dp)
    ) {
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(FONDO_DE_PASTILLA).padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (nombreEditable != null) nombreEditable()
            else Text(
                nombre, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
                    .then(if (alTocarElNombre != null) Modifier.clickable(onClick = alTocarElNombre) else Modifier)
                    .padding(vertical = 10.dp)
            )
            if (botones.isEmpty()) Spacer(Modifier.size(width = 10.dp, height = 1.dp))
            for (b in botones) {
                IconButton(onClick = b.alTocar, modifier = Modifier.size(38.dp)) {
                    Icon(b.icono, contentDescription = b.descripcion, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(19.dp))
                }
            }
        }
    }
}

/** **El engranaje**: una hoja de abajo, oscura, que se desplaza si no cabe. Dentro van los apartados. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EngranajeDeLector(onCerrar: () -> Unit, contenido: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onCerrar,
        containerColor = Color(0xF214182B),
        contentColor = Color.White,
        scrimColor = Color.Transparent
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp).padding(bottom = 14.dp),
            content = contenido
        )
    }
}

/** El título de un apartado del engranaje: pequeño, en gris, con aire encima. */
@Composable
fun ApartadoDeLector(titulo: String) {
    Text(
        titulo.uppercase(), color = Color.White.copy(alpha = 0.5f),
        style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp)
    )
}

/** Un deslizador de pasos, con su nombre a la izquierda y el valor a la derecha. Se aplica al soltar. */
@Composable
fun DeslizadorDeLector(nombre: String, valor: String, actual: Float, ultimo: Int, activo: Boolean, onCambio: (Float) -> Unit, onSoltar: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(nombre, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(64.dp))
        Slider(
            value = actual, onValueChange = { onCambio(Math.round(it).toFloat()) }, onValueChangeFinished = onSoltar,
            valueRange = 0f..ultimo.toFloat(), steps = (ultimo - 1).coerceAtLeast(0), enabled = activo,
            colors = SliderDefaults.colors(
                thumbColor = Color.White, activeTrackColor = AZUL, inactiveTrackColor = Color.White.copy(alpha = 0.18f),
                activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent
            ),
            modifier = Modifier.weight(1f)
        )
        Text(valor, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium, maxLines = 1,
            modifier = Modifier.width(62.dp).padding(start = 8.dp))
    }
}

/** Sí o no: el nombre, una línea pequeña de qué hace, y el interruptor (algo menor que el de serie). */
@Composable
fun InterruptorDeLector(nombre: String, nota: String?, puesto: Boolean, onCambio: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onCambio(!puesto) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(nombre, style = MaterialTheme.typography.bodyMedium)
            if (nota != null) Text(nota, color = Color.White.copy(alpha = 0.55f), style = MaterialTheme.typography.labelSmall)
        }
        Switch(checked = puesto, onCheckedChange = onCambio, modifier = Modifier.graphicsLayer { scaleX = 0.8f; scaleY = 0.8f })
    }
}

/** Una fila que abre o hace algo: el nombre, lo que hay puesto y una flechita. */
@Composable
fun FilaDeLector(nombre: String, valor: String?, onToque: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onToque),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(nombre, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (valor != null) Text(valor, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium)
        Text("  ›", color = Color.White.copy(alpha = 0.6f))
    }
}

/** Un − y un + con el nombre y cuántos pasos hay; el − solo si hay algo que quitar, el + apagado en el tope. */
@Composable
fun MasMenosDeLector(nombre: String, pasos: Int, tope: Int, onMenos: () -> Unit, onMas: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(nombre, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium)
        IconButton(onClick = onMenos, enabled = pasos > 0, modifier = Modifier.size(34.dp)) {
            Icon(Icons.Filled.Remove, contentDescription = "Menos espacio a la ${nombre.lowercase()}", modifier = Modifier.size(18.dp))
        }
        Text("$pasos", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        IconButton(onClick = onMas, enabled = pasos < tope, modifier = Modifier.size(34.dp)) {
            Icon(Icons.Filled.Add, contentDescription = "Más espacio a la ${nombre.lowercase()}", modifier = Modifier.size(18.dp))
        }
    }
}

/** Un botón que hace algo, solo texto, con el tamaño justo. */
@Composable
fun BotonDeTextoDeLector(texto: String, onToque: () -> Unit) {
    TextButton(onClick = onToque, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
        Text(texto, color = AZUL, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * **Los mandos de los lados**, abajo y pequeños: a la izquierda, el espacio de la izquierda (−, ⟵,
 * +); en medio, el candado del desplazamiento de lado; a la derecha, el espacio de la derecha.
 * [pasosIzq]/[pasosDer] son los pasos puestos y [tope] los que caben.
 */
@Composable
fun MandosDeLosLadosDeLector(
    pasosIzq: Int, pasosDer: Int, tope: Int, sinLado: Boolean,
    onIzquierda: (mas: Boolean) -> Unit, onDerecha: (mas: Boolean) -> Unit, onCandado: () -> Unit,
    modifier: Modifier = Modifier
) {
    val blanco = Color.White
    @Composable
    fun Lado(izquierda: Boolean, pasos: Int, cambiar: (Boolean) -> Unit) {
        val nombre = if (izquierda) "izquierda" else "derecha"
        Row(Modifier.height(36.dp).clip(RoundedCornerShape(50)).background(FONDO_DE_PASTILLA), verticalAlignment = Alignment.CenterVertically) {
            if (pasos > 0) Box(
                Modifier.size(36.dp).clickable(onClickLabel = "Menos espacio a la $nombre") { cambiar(false) },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.Remove, contentDescription = null, tint = blanco, modifier = Modifier.size(16.dp)) }
            Text(if (izquierda) "⟵" else "⟶", color = blanco.copy(alpha = 0.8f), modifier = Modifier.padding(horizontal = if (pasos > 0) 0.dp else 10.dp))
            val lleno = pasos >= tope
            Box(
                Modifier.size(36.dp).clickable(enabled = !lleno, onClickLabel = "Más espacio a la $nombre") { cambiar(true) },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.Add, contentDescription = null, tint = blanco.copy(alpha = if (lleno) 0.3f else 1f), modifier = Modifier.size(16.dp)) }
        }
    }
    Row(modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Lado(true, pasosIzq, onIzquierda)
        Spacer(Modifier.weight(1f))
        Row(
            Modifier.height(36.dp).clip(RoundedCornerShape(50)).background(FONDO_DE_PASTILLA)
                .clickable(onClickLabel = if (sinLado) "Desbloquear el desplazamiento de lado" else "Bloquear el desplazamiento de lado", onClick = onCandado)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(if (sinLado) Icons.Filled.Lock else Icons.Filled.LockOpen, contentDescription = null, tint = if (sinLado) AMBAR else blanco, modifier = Modifier.size(16.dp))
            Text(" ⟷", color = blanco)
        }
        Spacer(Modifier.weight(1f))
        Lado(false, pasosDer, onDerecha)
    }
}

/**
 * **La barra de la página que sube sola**, abajo y de cristal: más lento, la velocidad en
 * palabras por minuto, más rápido, pausa y cerrar; debajo, [quedan] («Terminas en 12 min…»).
 */
@Composable
fun BarraDeAutoDesplazarDeLector(
    palabrasPorMinuto: Int, enMarcha: Boolean, quedan: String,
    onMenos: () -> Unit, onMas: () -> Unit, onAlternar: () -> Unit, onCerrar: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tinta = com.forge.pixpin.ui.theme.Cristal.tinta
    Box(modifier.navigationBarsPadding().padding(bottom = 10.dp, start = 12.dp, end = 12.dp)) {
        com.forge.pixpin.ui.theme.SuperficieDeCristal(Modifier, RoundedCornerShape(22.dp)) {
            Column(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onMenos) { Icon(Icons.Filled.Remove, contentDescription = "Más despacio", tint = tinta) }
                    Text("$palabrasPorMinuto palabras/min", color = tinta, fontWeight = FontWeight.Bold)
                    IconButton(onClick = onMas) { Icon(Icons.Filled.Add, contentDescription = "Más deprisa", tint = tinta) }
                    IconButton(onClick = onAlternar) {
                        Icon(if (enMarcha) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = if (enMarcha) "Pausa" else "Seguir", tint = tinta, modifier = Modifier.size(30.dp))
                    }
                    IconButton(onClick = onCerrar) { Icon(Icons.Filled.Close, contentDescription = "Dejar de desplazar", tint = tinta) }
                }
                Text(quedan, color = tinta.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 6.dp, start = 10.dp, end = 10.dp))
            }
        }
    }
}

/** «Terminas en 12 min · a las 18:40», para [palabras] a [ppm] con [progreso] de 0 a 1. */
fun rotuloDeLoQueQueda(palabras: Int, progreso: Float, ppm: Int): String {
    val minutos = com.forge.pixpin.motor.AutoDesplazar.minutosQueQuedan(palabras, progreso, ppm)
    val cal = java.util.Calendar.getInstance()
    val ahora = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
    return if (minutos <= 0f) "Terminado"
    else "Terminas en " + com.forge.pixpin.motor.AutoDesplazar.rotulo(minutos) + " · a las " + com.forge.pixpin.motor.AutoDesplazar.horaDeTerminar(ahora, minutos)
}
