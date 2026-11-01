package com.forge.pixpin.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.forge.pixpin.R
import com.forge.pixpin.estadoia.Estado
import com.forge.pixpin.estadoia.EstadoDeLasIa
import com.forge.pixpin.estadoia.Ficha
import com.forge.pixpin.estadoia.Luz
import com.forge.pixpin.estadoia.Motivo
import com.forge.pixpin.estadoia.Servicio
import com.forge.pixpin.estadoia.hace

/**
 * **Los puntitos del estado de las IA** (1-nov-2026, `ventana_chat/estado_ia.rs` del PC; el
 * usuario: «unos puntitos rojo o verde, en un sitio donde no molesten, para avisar de que quizá tu
 * API de DeepSeek, Jev o Claude no funciona»).
 *
 * Una columna de tres puntitos de 5 dp (3 dp de hueco) pegada a la izquierda del botón redondo de
 * sincronizar de la lista de chats: Claude, DeepSeek y Jev, de arriba abajo, siempre los tres para
 * que «sin clave» se vea. Verde lleno, funciona; rojo lleno, falla; aro rojo, sin clave; gris
 * tenue, con clave y aún sin comprobar. Al tocarlos (o dejar el dedo), un menú con cada servicio
 * y cuándo se supo («Claude · funciona · hace 3 min»), «Comprobar ahora» y «Claves en Ajustes →
 * Asistente».
 *
 * Lo que se sabe vive en [EstadoDeLasIa] (y sus reglas, con pruebas, en
 * [com.forge.pixpin.estadoia.Tablero]); aquí solo se pinta y se arma el menú.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PuntitosDeLasIa(modifier: Modifier = Modifier) {
    val contexto = LocalContext.current
    val foto by EstadoDeLasIa.foto.collectAsState()
    val dia = MaterialTheme.colorScheme.background.luminance() > 0.5f
    val apagado = MaterialTheme.colorScheme.onSurfaceVariant
    var menu by remember { mutableStateOf(false) }
    val descripcion = stringResource(R.string.ia_estado_descripcion)
    Box(
        modifier
            .width(14.dp)
            .height(40.dp)
            .semantics { contentDescription = descripcion }
            .combinedClickable(onClick = { menu = true }, onLongClick = { menu = true }),
        contentAlignment = Alignment.Center
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            for (s in Servicio.entries) Puntito(foto.tablero.estado(s).luz, 5.dp, colorDeLuz(foto.tablero.estado(s).luz, dia, apagado))
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            val ahora = EstadoDeLasIa.ahoraMs()
            for (s in Servicio.entries) {
                val f = foto.tablero.ficha(s)
                DropdownMenuItem(
                    text = { Text(linea(s, f, ahora)) },
                    leadingIcon = { Puntito(f.estado.luz, 8.dp, colorDeLuz(f.estado.luz, dia, apagado)) },
                    onClick = { menu = false; com.forge.pixpin.ConfiguracionActivity.abrir(contexto, "ASISTENTE") }
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.ia_estado_comprobar)) },
                leadingIcon = { Icon(Icons.Filled.Refresh, null) },
                onClick = { menu = false; EstadoDeLasIa.comprobarAhora() }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.ia_estado_claves)) },
                leadingIcon = { Icon(Icons.Filled.Settings, null) },
                onClick = { menu = false; com.forge.pixpin.ConfiguracionActivity.abrir(contexto, "ASISTENTE") }
            )
        }
    }
}

/** **El color de cada luz**: los verdes y rojos de noche van un punto más claros para que se vean igual. */
private fun colorDeLuz(luz: Luz, dia: Boolean, apagado: Color): Color = when (luz) {
    Luz.VERDE -> if (dia) Color(0xFF43A047) else Color(0xFF66BB6A)
    Luz.ROJA, Luz.HUECA -> if (dia) Color(0xFFE53935) else Color(0xFFEF5350)
    Luz.GRIS -> apagado.copy(alpha = 0.55f)
}

/** Un puntito: lleno, o solo el aro si falta la clave (se ve que falta, sin gritar como un fallo). */
@Composable
private fun Puntito(luz: Luz, lado: Dp, color: Color) {
    Canvas(Modifier.size(lado)) {
        val radio = size.minDimension / 2
        if (luz == Luz.HUECA) {
            val grosor = (radio * 0.45f).coerceAtLeast(1f)
            drawCircle(color, radius = radio - grosor / 2, style = Stroke(grosor))
        } else {
            drawCircle(color, radius = radio)
        }
    }
}

/** La frase de un servicio: «Claude · funciona · hace 3 min». */
@Composable
private fun linea(s: Servicio, f: Ficha, ahora: Long): String {
    val comprobando = stringResource(R.string.ia_estado_comprobando)
    val e = f.estado
    val frase = when (e) {
        Estado.SinClave -> stringResource(R.string.ia_estado_sin_clave)
        Estado.Desconocido -> stringResource(R.string.ia_estado_desconocido)
        Estado.Funciona -> stringResource(R.string.ia_estado_funciona)
        is Estado.Falla -> when (val m = e.motivo) {
            is Motivo.ClaveMala -> stringResource(R.string.ia_estado_clave_mala, m.codigo)
            Motivo.SinSaldo -> stringResource(R.string.ia_estado_sin_saldo)
            Motivo.Limite -> stringResource(R.string.ia_estado_limite)
            Motivo.Red -> stringResource(R.string.ia_estado_red)
            is Motivo.Servidor -> stringResource(R.string.ia_estado_servidor, m.codigo)
            is Motivo.Otro -> stringResource(R.string.ia_estado_otro, m.codigo)
        }
    }
    val cuando = f.cuando
    val cuanto = if (f.hayClave && cuando != null) {
        val (clave, n) = hace(cuando, ahora)
        " · " + when (clave) {
            "ia-estado-hace-min" -> stringResource(R.string.ia_estado_hace_min, n.toInt())
            "ia-estado-hace-horas" -> stringResource(R.string.ia_estado_hace_horas, n.toInt())
            else -> stringResource(R.string.ia_estado_hace_nada)
        }
    } else ""
    // Sin `return` a medias en un composable (el tropiezo de R8): una sola expresión.
    return if (f.comprobando) "${s.nombre} · $comprobando" else "${s.nombre} · $frase$cuanto"
}
