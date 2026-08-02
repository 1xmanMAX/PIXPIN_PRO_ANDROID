package com.forge.pixpin.pin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.abs

/** Una línea del libro de cuentas ya interpretada. */
data class LedgerEntry(val amount: Double, val label: String)

/**
 * Interpreta el libro de cuentas: una línea por apunte, con signo delante.
 *
 * Es aritmética pura y va aparte del dibujado para poder comprobarla sin
 * dispositivo: equivocarse en un total es peor que equivocarse en un color.
 */
object Ledger {

    private val LINE = Regex("""^\s*([+-]?\s*\d+(?:[.,]\d+)?)\s*(.*)$""")

    fun parse(text: String?): List<LedgerEntry> =
        text.orEmpty().lines().mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            val m = LINE.find(line) ?: return@mapNotNull null
            val raw = m.groupValues[1].replace(" ", "").replace(',', '.')
            val value = raw.toDoubleOrNull() ?: return@mapNotNull null
            LedgerEntry(value, m.groupValues[2].trim())
        }

    fun total(entries: List<LedgerEntry>): Double = entries.sumOf { it.amount }

    /** Sin decimales cuando son redondos: un total de gastos no necesita «,00». */
    fun format(value: Double): String {
        val sign = if (value > 0) "+" else if (value < 0) "-" else ""
        val abs = abs(value)
        return if (abs == abs.toLong().toDouble()) {
            "$sign${abs.toLong()}"
        } else {
            sign + String.format("%.2f", abs)
        }
    }
}

/**
 * Reloj y cuenta atrás.
 *
 * Los minutos se fijan con la pegatina de emoji, que en este tipo de pin deja
 * de ser decoración y hace de mando. El vencimiento se guarda como instante
 * absoluto, así que la cuenta sigue siendo correcta aunque el pin se cierre.
 */
@Composable
fun TimerBody(
    widget: WidgetState,
    nowProvider: () -> Long,
    onFinished: () -> Unit
) {
    var now by remember { mutableLongStateOf(nowProvider()) }
    LaunchedEffect(widget.timerEndsAt) {
        while (true) {
            now = nowProvider()
            delay(250)
        }
    }
    val endsAt = widget.timerEndsAt
    val remaining = if (endsAt != null) endsAt - now else null

    LaunchedEffect(remaining != null && remaining <= 0L) {
        if (remaining != null && remaining <= 0L) onFinished()
    }

    Column(
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val label = when {
            remaining == null -> clockOf(now)
            remaining > 0 -> countdownOf(remaining)
            else -> "00:00"
        }
        Text(
            text = label,
            fontSize = 34.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            color = if (remaining != null && remaining <= 0) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurface
        )
        if (remaining == null) {
            Text(
                text = "⏱ 5 · 15 · 30 · 60",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun clockOf(nowMs: Long): String {
    val totalMinutes = (nowMs / 60000L) % (24 * 60)
    return "%02d:%02d".format(totalMinutes / 60, totalMinutes % 60)
}

private fun countdownOf(ms: Long): String {
    val s = (ms / 1000L).coerceAtLeast(0)
    return if (s >= 3600) {
        "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    } else {
        "%02d:%02d".format(s / 60, s % 60)
    }
}

/** Lista de tareas: una casilla por línea del texto. */
@Composable
fun ChecklistBody(
    text: String?,
    widget: WidgetState,
    onRowBounds: (Int, Float, Float) -> Unit = { _, _, _ -> }
) {
    val items = remember(text) { text.orEmpty().lines().filter { it.isNotBlank() } }
    Column(modifier = Modifier.padding(12.dp)) {
        items.forEachIndexed { index, item ->
            val done = widget.checked.getOrElse(index) { false }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
                    // Cada fila dice dónde está de verdad. Calcularlo con una
                    // altura estimada desfasaba el toque una fila, y el error se
                    // acumulaba según se bajaba por la lista.
                    // positionInRoot YA viene con la escala de la capa aplicada,
                    // así que es directamente la coordenada de ventana. El alto,
                    // en cambio, es de disposición y sin escalar: lo escala
                    // quien compara, que es el único que conoce el zoom.
                    .onGloballyPositioned { coords ->
                        onRowBounds(index, coords.positionInRoot().y, coords.size.height.toFloat())
                    }
            ) {
                Text(
                    text = if (done) "☑" else "☐",
                    fontSize = 17.sp,
                    color = if (done) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = item.trim(),
                    fontSize = 14.sp,
                    textDecoration = if (done) TextDecoration.LineThrough else null,
                    color = if (done) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

/** Contador. Toque suma, doble toque resta, pulsación larga pone a cero. */
@Composable
fun CounterBody(widget: WidgetState) {
    Column(
        modifier = Modifier.padding(horizontal = 26.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "${widget.count}",
            fontSize = 44.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "toque +1 · doble −1",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Gastos e ingresos: rojo lo que resta, verde lo que suma, y el total abajo. */
@Composable
fun LedgerBody(text: String?, totalLabel: String) {
    val entries = remember(text) { Ledger.parse(text) }
    val total = remember(entries) { Ledger.total(entries) }
    Column(modifier = Modifier.padding(12.dp)) {
        entries.forEach { e ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = e.label,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = Ledger.format(e.amount),
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    color = if (e.amount < 0) LEDGER_OUT else LEDGER_IN
                )
            }
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(totalLabel, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(
                text = Ledger.format(total),
                fontSize = 15.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = if (total < 0) LEDGER_OUT else LEDGER_IN
            )
        }
    }
}

/**
 * Tabla pegada de una hoja de cálculo.
 *
 * Va en monoespaciada y con las columnas del mismo ancho: en un pin estrecho lo
 * que salva la lectura es que las cifras queden alineadas unas bajo otras, no
 * que cada columna se ajuste a su contenido.
 */
@Composable
fun TableBody(text: String?) {
    val rows = remember(text) { com.forge.pixpin.clipboard.TableData.parse(text) }
    if (rows.isEmpty()) return
    Column(modifier = Modifier.padding(10.dp)) {
        rows.forEachIndexed { index, row ->
            val header = index == 0
            Row(modifier = Modifier.fillMaxWidth()) {
                row.forEach { cell ->
                    Text(
                        text = cell,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = if (header) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 2,
                        color = if (header) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }
            if (header) HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
        }
    }
}

private val LEDGER_OUT = Color(0xFFD32F2F)
private val LEDGER_IN = Color(0xFF2E7D32)

/** Minutos que ofrece la pegatina cuando el pin es un temporizador. */
val TIMER_PRESETS = listOf("5️⃣" to 5, "🕒" to 15, "🕧" to 30, "🕐" to 60)
