package com.forge.pixpin.guardados

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.forge.pixpin.jev.Cliente
import com.forge.pixpin.jev.ErrorJev
import com.forge.pixpin.jev.Jev
import com.forge.pixpin.jev.RepasoOral
import com.forge.pixpin.ui.theme.PixPinTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * **Repasar en voz alta** (29-oct-2026; `apps/pixpin/src/ventana_chat/repaso_oral.rs` del PC, `5282d2f5`):
 * desde «Estudiar la clase», el alumno explica la clase de viva voz y ve, punto clave a punto clave,
 * cuál cubre **✓**, cuál dice mal **✗**, cuál falta **·** y cuál no queda claro **?**. Lo dicho mal y lo
 * que falta llevan **«▶ 12:34»**: un toque vuelve a la clase en ese minuto (la pantalla de estudiar,
 * con su audio ahí).
 *
 * **Los puntos clave**: [RepasarClase.puntosClave] (los de examen y decisión de `importantes`, los
 * marcadores con nota y los temas tratados; doce como mucho). **Lo que se dice** entra por el
 * reconocedor del teléfono, frase a frase ([EscuchaFraseAFrase]); cada frase se suma a lo explicado y va
 * a [RepasoOral.preguntar] (lo explicado y dos Nouls por punto aún no «mal»), y la tabla
 * [RepasoOral.decidir] mueve los estados. Una petición en vuelo como mucho; lo dicho mientras tanto va en
 * la siguiente.
 *
 * **Nada se guarda** (es un ejercicio) y nada nuevo viaja. Sin clave o con el interruptor apagado el botón
 * no sale en «Estudiar la clase»; si se llega aquí igual, se dice por qué y no se escucha. Si Jev falla
 * (sin red…), se dice abajo y los puntos se quedan como estaban: un fallo nunca es un «falta».
 */
class RepasarActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(LA_NOTA).orEmpty()
        setContent {
            PixPinTheme(darkTheme = com.forge.pixpin.office.Oscuro.activo(this)) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Pantalla(id)
                }
            }
        }
    }

    /** Lo leído de la clase: su nota, su nombre y sus puntos clave. */
    private class Clase(val nota: Mensaje?, val nombre: String, val puntos: List<RepasarClase.PuntoClave>)

    private fun leer(id: String): Clase {
        val m = MensajesStore(this).leer().lastOrNull { it.id == id } ?: return Clase(null, "", emptyList())
        val puntos = RepasarClase.puntosClave(
            EstudiarClase.lineasDeLaNota(m),
            Importantes.de(m).orEmpty(),
            EstudiarClase.marcadoresDe(m.marcadores),
            RepasarClase.temasDeLaNota(this, id)
        )
        return Clase(m, tituloDeAudio(this, m), puntos)
    }

    /** El cliente del repaso (6 s por petición, como el PC), o null sin clave o apagado. Lee el disco. */
    private fun cliente(): Cliente? {
        if (!Jev.encendida(this, Jev.Funcion.REPASO)) return null
        val clave = Jev.clave(this) ?: return null
        return Cliente(clave, tiempoMs = TIEMPO_MS)
    }

    /** A la clase, en ese minuto: el audio ahí y de vuelta a «Estudiar la clase». */
    private fun volverA(m: Mensaje, ms: Long) {
        val ruta = m.ruta
        if (ruta != null) {
            if (Reproductor.estado.value.ruta != ruta) Reproductor.cargar(ruta, tituloDeAudio(this, m), arrancar = false)
            val total = Reproductor.estado.value.duracionMs
            if (total > 0) Reproductor.irA(ms.toFloat() / total)
        }
        finish()
    }

    @Composable
    private fun Pantalla(id: String) {
        val clase by produceState<Clase?>(null, id) { value = withContext(Dispatchers.IO) { leer(id) } }
        val hayJev by produceState<Boolean?>(null) {
            value = withContext(Dispatchers.IO) { Jev.hayClave(this@RepasarActivity) && Jev.encendida(this@RepasarActivity, Jev.Funcion.REPASO) }
        }
        val c = clase
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                "Repasar en voz alta" + (c?.nombre?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                "Explica la clase con tus palabras, como si se la contaras a alguien. Jev va marcando cada punto clave: ✓ lo cubres, ✗ lo dices mal, · falta, ? no queda claro. Lo que dices mal o falta lleva su minuto para volver a la clase.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp)
            )
            when {
                c == null || hayJev == null -> Text("Leyendo la clase…", modifier = Modifier.padding(top = 16.dp))
                hayJev == false -> Text(
                    "Para repasar hace falta la clave de TypeSafe y «Repasar en voz alta» encendido (Ajustes › Asistente › TypeSafe (Jev)). Sin ellos no se escucha ni se marca nada.",
                    modifier = Modifier.padding(top = 16.dp)
                )
                c.nota == null -> Text("La clase ya no está en el chat.", modifier = Modifier.padding(top = 16.dp))
                c.puntos.isEmpty() -> Text(
                    "Esta clase aún no tiene puntos clave. Salen de lo que Jev marca como importante al pasarla a texto, de los marcadores con nota y de los temas tratados.",
                    modifier = Modifier.padding(top = 16.dp)
                )
                else -> Repaso(c, c.nota)
            }
            if (c == null || hayJev != true || c.nota == null || c.puntos.isEmpty()) {
                Box(Modifier.weight(1f))
                TextButton(onClick = { finish() }, modifier = Modifier.align(Alignment.End)) { Text("Cerrar") }
            }
        }
    }

    @Composable
    private fun androidx.compose.foundation.layout.ColumnScope.Repaso(c: Clase, nota: Mensaje) {
        // El repaso entero; «Empezar otra vez» lo cambia por uno nuevo (y lo que estuviera en vuelo se tira).
        var vuelta by remember { mutableIntStateOf(0) }
        val repaso = remember(vuelta) { RepasoOral(c.nombre, c.puntos.map { it.texto }) }
        var version by remember { mutableIntStateOf(0) }
        var hablando by remember { mutableStateOf(false) }
        var oyendo by remember { mutableStateOf(false) }
        var provisional by remember { mutableStateOf("") }
        var aviso by remember { mutableStateOf<String?>(null) }
        var enVuelo by remember { mutableStateOf(false) }
        var pendiente by remember { mutableStateOf(false) }

        // El repaso de ahora, también para lo que se lanzó antes de «Empezar otra vez».
        val actual by rememberUpdatedState(repaso)

        fun preguntar() {
            if (enVuelo) { pendiente = true; return }
            val deEste = actual
            val puntos = deEste.porPreguntar()
            if (puntos.isEmpty() || deEste.explicacion.isBlank()) return
            pendiente = false
            enVuelo = true
            val explicado = deEste.explicacion
            lifecycleScope.launch {
                val r = withContext(Dispatchers.IO) {
                    val cl = cliente() ?: return@withContext Result.failure<List<Pair<String, RepasoOral.SenalPunto>>>(ErrorJev.SinClave())
                    runCatching { RepasoOral.preguntar(cl, deEste.clase, explicado, puntos) }
                }
                enVuelo = false
                // De un repaso que ya se tiró («Empezar otra vez»): no cuenta.
                if (deEste === actual) {
                    r.onSuccess { s -> if (deEste.decidir(s).isNotEmpty()) version++ }
                    r.onFailure { e ->
                        runCatching { android.util.Log.i("PixPinJev", "el repaso no se preguntó: ${e.message}") }
                        aviso = "Jev no contestó: los puntos se quedan como estaban." +
                            (if (e is ErrorJev.Red) " Sin conexión." else e.message?.let { " ($it)" } ?: "")
                    }
                    if (pendiente) preguntar()
                }
            }
        }

        val escucha = remember {
            EscuchaFraseAFrase(
                this@RepasarActivity,
                alFrase = { t ->
                    actual.oir(t)
                    provisional = ""
                    version++
                    preguntar()
                },
                alParcial = { provisional = it },
                alFallo = { razon -> hablando = false; aviso = "No se pudo escuchar: $razon" },
                alOir = { si -> oyendo = si; if (!si) hablando = false }
            )
        }
        DisposableEffect(Unit) { onDispose { escucha.parar() } }
        LaunchedEffect(aviso) {
            if (aviso != null) { kotlinx.coroutines.delay(6_000); aviso = null }
        }

        // `version` se lee para repintar cuando cambian los estados (la lista es del repaso, no de Compose).
        val estados = remember(version, vuelta) { repaso.estados.toList() }
        LazyColumn(Modifier.weight(1f).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(repaso.puntos) { i, (_, texto) ->
                val estado = estados.getOrElse(i) { RepasoOral.Estado.FALTA }
                val ms = c.puntos.getOrNull(i)?.ms
                val (simbolo, color) = marca(estado)
                Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                        Box(Modifier.size(24.dp).background(color, CircleShape), contentAlignment = Alignment.Center) {
                            Text(simbolo, fontSize = 14.sp, color = Color.White)
                        }
                        Text(
                            texto, fontSize = 14.sp,
                            color = if (estado == RepasoOral.Estado.FALTA) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f).padding(horizontal = 10.dp)
                        )
                        if (ms != null && (estado == RepasoOral.Estado.MAL || estado == RepasoOral.Estado.FALTA)) {
                            OutlinedButton(onClick = { volverA(nota, ms) }) { Text("▶ " + RepasarClase.minuto(ms), fontSize = 12.sp) }
                        }
                    }
                }
            }
        }
        // Abajo: el recuento, lo último dicho (o lo que se va oyendo) y los botones.
        fun cuenta(e: RepasoOral.Estado) = estados.count { it == e }
        Text(
            "${cuenta(RepasoOral.Estado.CUBIERTO)} de ${estados.size} cubiertos · ${cuenta(RepasoOral.Estado.MAL)} mal · " +
                "${cuenta(RepasoOral.Estado.FALTA)} faltan · ${cuenta(RepasoOral.Estado.DUDOSO)} dudosos",
            fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp).align(Alignment.CenterHorizontally)
        )
        val dicho = remember(version, provisional) {
            provisional.ifBlank { RepasarClase.loUltimo(repaso.explicacion, RepasarClase.LARGO_DICHO) }
        }
        Text(
            when {
                dicho.isNotBlank() -> dicho
                hablando && oyendo -> "Te escucho: empieza a explicar."
                hablando -> "Preparando el micrófono…"
                else -> "Pulsa «Empezar a hablar» y explica la clase."
            },
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp).fillMaxWidth()
        )
        aviso?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp).fillMaxWidth()) {
            Button(onClick = {
                if (hablando) escucha.parar()
                else {
                    val tiene = androidx.core.content.ContextCompat.checkSelfPermission(
                        this@RepasarActivity, android.Manifest.permission.RECORD_AUDIO
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (!tiene) androidx.core.app.ActivityCompat.requestPermissions(
                        this@RepasarActivity, arrayOf(android.Manifest.permission.RECORD_AUDIO), 1
                    ) else {
                        provisional = ""
                        hablando = escucha.empezar()
                    }
                }
            }) { Text(if (hablando) "Parar" else "Empezar a hablar") }
            OutlinedButton(onClick = {
                enVuelo = false
                pendiente = false
                provisional = ""
                vuelta++
                version++
            }) { Text("Empezar otra vez") }
            Box(Modifier.weight(1f))
            // «Reportar respuesta» (30-oct-2026, Google Play): cómo marcó Jev cada punto.
            com.forge.pixpin.privacidad.BotonReportarIa(com.forge.pixpin.privacidad.ReportesDeIa.Funcion.REPASO, {
                repaso.puntos.mapIndexed { i, (_, texto) -> marca(estados.getOrElse(i) { RepasoOral.Estado.FALTA }).first + " " + texto }
                    .joinToString("\n") + "\n\nLo explicado: " + repaso.explicacion
            })
            TextButton(onClick = { finish() }) { Text("Cerrar") }
        }
    }

    /** El símbolo y el color de cada estado. */
    private fun marca(e: RepasoOral.Estado): Pair<String, Color> = when (e) {
        RepasoOral.Estado.CUBIERTO -> "✓" to Color(0xFF22A06B)
        RepasoOral.Estado.MAL -> "✗" to Color(0xFFE5534B)
        RepasoOral.Estado.FALTA -> "·" to Color(0xFF8A8F98)
        RepasoOral.Estado.DUDOSO -> "?" to Color(0xFFD4A72C)
    }

    companion object {
        private const val LA_NOTA = "repasar_nota"

        /** Lo que se espera a Jev por petición (el PC, 6 s: lo explicado crece). */
        const val TIEMPO_MS = 6_000L

        /** **Abre el repaso** de la clase de la nota de voz [nota]. */
        fun abrir(context: Context, nota: String) {
            context.startActivity(Intent(context, RepasarActivity::class.java).putExtra(LA_NOTA, nota))
        }

        /** Si sale el botón «Repasar en voz alta»: con la clave de TypeSafe y el interruptor. Lee el disco. */
        fun disponible(context: Context): Boolean = Jev.hayClave(context) && Jev.encendida(context, Jev.Funcion.REPASO)
    }
}
