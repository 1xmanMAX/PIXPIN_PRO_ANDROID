package com.forge.pixpin.sincro

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.forge.pixpin.guardados.tamanoLegible
import com.forge.pixpin.ui.theme.PixPinTheme
import java.io.File
import java.net.InetAddress
import java.net.Socket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * **Recibir lo que alguien envía por Wi-Fi, una sola vez.** Ver [Envio] y [EnviarActivity].
 *
 * Se escanea el QR o se escribe el código de seis cifras; se ve qué llega y se acepta o no. De lo
 * que **ya se tiene con los mismos tres códigos** ([Codigos]) se elige, cosa por cosa, si ponerlo al
 * día o crearlo como nuevo (15-sep-2026).
 */
class RecibirActivity : ComponentActivity() {

    private sealed interface Estado {
        data object Pidiendo : Estado
        data class Buscando(val texto: String) : Estado
        /** [decision]: null si no se acepta; si sí, identidad → `true` para crearlo como nuevo. */
        data class Oferta(val oferta: Envio.Oferta, val sustituye: Map<String, String>, val decision: CompletableDeferred<Map<String, Boolean>?>) : Estado
        data class Recibiendo(val de: String, val hechos: Long, val total: Long) : Estado
        data class Hecho(val de: String, val guardados: List<Recepcion.Guardado>) : Estado
        data class Fallo(val texto: String) : Estado
    }

    private var estado by mutableStateOf<Estado>(Estado.Pidiendo)
    private var camaraPermitida by mutableStateOf(false)
    private var trabajo: Job? = null
    private var red: Red? = null

    private val pedirCamara = registerForActivityResult(ActivityResultContracts.RequestPermission()) { camaraPermitida = it }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        camaraPermitida = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        setContent { PixPinTheme { Pantalla() } }
    }

    override fun onDestroy() {
        super.onDestroy()
        (estado as? Estado.Oferta)?.decision?.complete(null)
        red?.cerrar()
    }

    private fun recibir(codigo: String, host: String?, puerto: Int) {
        if (trabajo?.isActive == true) return
        trabajo = lifecycleScope.launch(Dispatchers.IO) {
            try {
                estado = Estado.Buscando("Buscando a quien envía…")
                val socket = conectarCon(codigo, host, puerto)
                    ?: throw IllegalStateException("No se encontró a nadie enviando con ese código. Comprueba el código y que los dos estáis en la misma Wi-Fi (o en la zona Wi-Fi de uno de los dos).")
                socket.use { s ->
                    estado = Estado.Buscando("Conectando…")
                    val receptor = try {
                        Envio.Receptor.conectar(s.getInputStream(), s.getOutputStream(), codigo, IdentidadEnDisco(filesDir).leer(android.os.Build.MODEL.orEmpty().ifBlank { "Este aparato" }).yo)
                    } catch (e: java.io.EOFException) {
                        throw IllegalStateException("El código no es el bueno. Revísalo.")
                    }
                    val oferta = receptor.oferta
                    val sustituye = oferta.elementos.mapNotNull { e -> Recepcion.queSustituye(this@RecibirActivity, e)?.let { e.identidad to it } }.toMap()
                    val decision = CompletableDeferred<Map<String, Boolean>?>()
                    estado = Estado.Oferta(oferta, sustituye, decision)
                    val comoNuevo = decision.await()
                    if (comoNuevo == null) { receptor.rechazar(); finish(); return@use }
                    estado = Estado.Recibiendo(oferta.de, 0, oferta.elementos.sumOf { it.bytes })
                    var ultimo = 0L
                    val llegados = receptor.aceptar(File(cacheDir, "recibido/${System.currentTimeMillis()}")) { hechos, total ->
                        val ahora = System.currentTimeMillis()
                        if (ahora - ultimo > 120 || hechos == total) { ultimo = ahora; estado = Estado.Recibiendo(oferta.de, hechos, total) }
                    }
                    val guardados = llegados.mapNotNull { (e, archivo) -> Recepcion.guardar(this@RecibirActivity, e, archivo, oferta, comoNuevo = comoNuevo[e.identidad] == true) }
                    estado = Estado.Hecho(oferta.de, guardados)
                }
            } catch (e: Exception) {
                estado = Estado.Fallo(
                    when (e) {
                        is IllegalStateException -> e.message ?: "Algo salió mal"
                        else -> "Se cortó: ${e.message ?: e.javaClass.simpleName}"
                    }
                )
            }
        }
    }

    /** Directo a la dirección del QR si la hay; si no responde, buscando por la red. */
    private suspend fun conectarCon(codigo: String, host: String?, puerto: Int): Socket? {
        if (host != null && puerto > 0) {
            runCatching { return Red.conectar(InetAddress.getByName(host), puerto) }
        }
        val etiqueta = Envio.etiqueta(codigo)
        val hallado = CompletableDeferred<Red.Vecino>()
        val r = Red(this).also { red = it }
        withContext(Dispatchers.Main) { r.buscar(Envio.TIPO, { it["g"] == etiqueta }) { l -> l.firstOrNull()?.let { hallado.complete(it) } } }
        val v = withTimeoutOrNull(20_000) { hallado.await() }
        withContext(Dispatchers.Main) { r.pararBusqueda() }
        return v?.let { runCatching { Red.conectar(it.host, it.puerto) }.getOrNull() }
    }

    // ------------------------------------------------------------ pantalla

    @Composable
    private fun Pantalla() {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { finish() }) { Icon(Icons.Filled.Close, contentDescription = "Cerrar") }
                    Text("Recibir por Wi-Fi", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(8.dp))
                when (val e = estado) {
                    Estado.Pidiendo -> Pidiendo()
                    is Estado.Buscando -> { Spacer(Modifier.height(40.dp)); CircularProgressIndicator(); Spacer(Modifier.height(14.dp)); Text(e.texto, textAlign = TextAlign.Center) }
                    is Estado.Oferta -> Oferta(e)
                    is Estado.Recibiendo -> {
                        Spacer(Modifier.height(40.dp))
                        Text("Recibiendo de ${e.de}", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(progress = { if (e.total > 0) e.hechos.toFloat() / e.total else 0f }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(6.dp))
                        Text("${tamanoLegible(e.hechos).ifBlank { "0 B" }} de ${tamanoLegible(e.total)}", style = MaterialTheme.typography.bodySmall)
                    }
                    is Estado.Hecho -> Hecho(e)
                    is Estado.Fallo -> {
                        Spacer(Modifier.height(40.dp))
                        Text("No se pudo", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        Text(e.texto, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(20.dp))
                        Button(onClick = { estado = Estado.Pidiendo }, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Probar otra vez") }
                    }
                }
            }
        }
    }

    @Composable
    private fun Pidiendo() {
        var codigo by remember { mutableStateOf("") }
        Caja {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Filled.Wifi, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text("Tienes que estar en la misma Wi-Fi que quien envía (o conectado a su zona Wi-Fi).", style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(16.dp))
        Box(
            Modifier.widthIn(max = 320.dp).fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (camaraPermitida) {
                Qr.Escaner(Modifier.fillMaxSize()) { texto ->
                    val leido = Envio.leerQr(texto) ?: return@Escaner
                    if (estado is Estado.Pidiendo) recibir(leido.codigo, leido.host, leido.puerto)
                }
            } else {
                Caja {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.QrCodeScanner, contentDescription = null, modifier = Modifier.size(56.dp))
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { pedirCamara.launch(Manifest.permission.CAMERA) }) { Text("Escanear el QR") }
                    }
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Text("o escribe el código", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = codigo,
            onValueChange = { codigo = Envio.limpiar(it) },
            singleLine = true,
            placeholder = { Text("000 000") },
            textStyle = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.widthIn(max = 260.dp)
        )
        Spacer(Modifier.height(12.dp))
        Button(enabled = Envio.valido(codigo), onClick = { recibir(codigo, null, 0) }, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Recibir") }
    }

    @Composable
    private fun Oferta(e: Estado.Oferta) {
        // Por omisión, lo que coincide se pone al día.
        val comoNuevo = remember(e) { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }
        Spacer(Modifier.height(12.dp))
        Text("${e.oferta.de} quiere enviarte", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        if (e.oferta.deCodigo.isNotBlank()) Text("Aparato ${e.oferta.deCodigo}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Caja {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (el in e.oferta.elementos) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                when (el.tipo) { Envio.PROYECTO -> "Proyecto «${el.nombre}»"; Envio.LIENZO -> "Lienzo «${el.nombre}»"; else -> el.nombre },
                                modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis
                            )
                            Text(tamanoLegible(el.bytes), style = MaterialTheme.typography.bodySmall)
                        }
                        if (el.tipo == Envio.LIENZO && e.sustituye[el.identidad] == null) {
                            Recepcion.adonde(this@RecibirActivity, el)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        if (el.codigoDeChat != null || el.creado > 0) {
                            Text(
                                listOfNotNull(el.codigoDeChat?.let { "#$it" }, el.creado.takeIf { it > 0 }?.let { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(it)) }).joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        e.sustituye[el.identidad]?.let { suyo ->
                            Text("Ya tienes «$suyo» con los mismos códigos", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(6.dp))
                            val nuevo = comoNuevo[el.identidad] == true
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Eleccion("Actualizar el que tengo", !nuevo, Modifier.weight(1f)) { comoNuevo[el.identidad] = false }
                                Eleccion("Crear como nuevo", nuevo, Modifier.weight(1f)) { comoNuevo[el.identidad] = true }
                            }
                            Text(
                                if (nuevo) "Entra aparte, con códigos nuevos: desde ahora es otra cosa." else "Se escribe encima de lo tuyo. Lo de antes queda en «Copias de seguridad».",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
        if (e.sustituye.isEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                "Nada de esto coincide con lo que tienes: entra como nuevo, sin tocar nada tuyo.",
                style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { e.decision.complete(null) }, modifier = Modifier.weight(1f).height(48.dp)) { Text("No, gracias") }
            Button(onClick = { e.decision.complete(comoNuevo.toMap()) }, modifier = Modifier.weight(1f).height(48.dp)) { Text("Aceptar") }
        }
    }

    /** Una de dos opciones, como pastilla. */
    @Composable
    private fun Eleccion(texto: String, puesta: Boolean, modifier: Modifier, alTocar: () -> Unit) {
        Box(
            modifier.clip(RoundedCornerShape(10.dp))
                .background(if (puesta) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = alTocar)
                .padding(horizontal = 10.dp, vertical = 9.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(texto, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center,
                color = if (puesta) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    @Composable
    private fun Hecho(e: Estado.Hecho) {
        Spacer(Modifier.height(30.dp))
        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = VERDE, modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(10.dp))
        Text("Recibido de ${e.de}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        if (e.guardados.isEmpty()) Text("Llegó, pero no se pudo guardar.", color = MaterialTheme.colorScheme.error)
        for (g in e.guardados) Text(g.texto, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 2.dp))
        Spacer(Modifier.height(20.dp))
        val proyecto = e.guardados.firstNotNullOfOrNull { it.proyecto }
        Button(onClick = {
            if (proyecto != null) {
                startActivity(Intent(this, com.forge.pixpin.MainActivity::class.java)
                    .putExtra(com.forge.pixpin.EXTRA_PROYECTOS, true)
                    .putExtra(com.forge.pixpin.EXTRA_PROYECTO, proyecto))
            } else {
                startActivity(Intent(this, com.forge.pixpin.guardados.MensajesActivity::class.java))
            }
            finish()
        }, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text(if (proyecto != null) "Abrir el proyecto" else "Abrir la conversación") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { finish() }, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Cerrar") }
    }

    companion object {
        fun abrir(context: Context) {
            context.startActivity(Intent(context, RecibirActivity::class.java))
        }
    }
}
