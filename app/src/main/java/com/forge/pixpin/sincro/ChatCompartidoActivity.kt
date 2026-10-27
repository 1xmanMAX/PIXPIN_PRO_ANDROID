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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.forge.pixpin.ui.theme.PixPinTheme
import java.io.PushbackInputStream
import java.net.InetAddress
import java.net.Socket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * **Las personas de un chat compartido**, y entrar en uno (13-oct-2026). Ver [Compartidos].
 *
 * Con un chat ([abrir]): si aún no está compartido, compartirlo; si lo está, quién hay, **Añadir**
 * (un código y su QR, como emparejar un aparato), **Sincronizar** con quien esté en la Wi-Fi,
 * **Quitar** a alguien (solo quien lo creó) y **Salir**. Sin chat ([entrar]): escanear o teclear el
 * código que enseña quien invita, y traerse el chat.
 *
 * Lo de dentro —qué se comparte con quién, las firmas, la valla de archivos— está en [Compartidos],
 * [AccesoDePersona], [Invitacion] y [VueltaConPersona], sin Android y probado en la JVM.
 */
class ChatCompartidoActivity : ComponentActivity() {

    private val disco by lazy { Red.disco(this) }
    private val compartidos by lazy { CompartidosEnDisco(filesDir) }

    private var chat: String? = null
    private var nombreDelChat by mutableStateOf("")
    private var lista by mutableStateOf<ChatCompartido?>(null)
    private var yo by mutableStateOf<Yo?>(null)
    private var misAparatos by mutableStateOf<Set<String>>(emptySet())

    /** El código y el QR de la invitación abierta, o nada. */
    private var invitacion by mutableStateOf<Pair<String, android.graphics.Bitmap>?>(null)
    private var redDeLaInvitacion: Red? = null
    private var fallosDeCodigo = 0

    /** Lo que está pasando, en una línea; y si hay trabajo en marcha. */
    private var aviso by mutableStateOf<String?>(null)
    private var trabajando by mutableStateOf(false)
    private var trabajo: Job? = null

    /** Al entrar con un código: el chat ya traído, para abrirlo. */
    private var dentroDe by mutableStateOf<ChatCompartido?>(null)

    private var camaraPermitida by mutableStateOf(false)
    private val pedirCamara = registerForActivityResult(ActivityResultContracts.RequestPermission()) { camaraPermitida = it }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        chat = intent.getStringExtra(EXTRA_CHAT)
        nombreDelChat = intent.getStringExtra(EXTRA_NOMBRE).orEmpty()
        camaraPermitida = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        setContent { PixPinTheme { Pantalla() } }
    }

    override fun onDestroy() {
        super.onDestroy()
        cerrarInvitacion()
    }

    private suspend fun releer() = withContext(Dispatchers.IO) {
        val id = disco.identidad.leer(android.os.Build.MODEL.orEmpty().ifBlank { "Este aparato" })
        yo = compartidos.yo(id)
        misAparatos = id.miembros.mapTo(HashSet()) { it.id } + id.yo.id
        lista = chat?.let { compartidos.de(it) }
    }

    // ------------------------------------------------------------------ acciones

    private fun enFondo(texto: String, hacer: suspend () -> Unit) {
        if (trabajo?.isActive == true) return
        trabajo = lifecycleScope.launch(Dispatchers.IO) {
            trabajando = true
            aviso = texto
            try {
                hacer()
            } catch (e: Exception) {
                aviso = e.message ?: e.javaClass.simpleName
            } finally {
                trabajando = false
            }
        }
    }

    private fun compartir() = enFondo("Compartiendo…") {
        val c = chat ?: return@enFondo
        val y = yo ?: return@enFondo
        val nombre = nombreDelChat.ifBlank { disco.chats().firstOrNull { it.id == c }?.nombre ?: "Chat" }
        compartidos.cambiar { l -> if (l.any { it.chat == c }) l else l + Compartidos.crear(c, nombre, y, System.currentTimeMillis()) }
        // Desde ahora hay que escuchar en la Wi-Fi aunque no haya grupo.
        Presencia.reiniciar()
        aviso = "Compartido. Pulsa Añadir para invitar a alguien."
    }

    /**
     * **Abre la puerta para invitar**: un servidor propio mientras esta pantalla está delante,
     * anunciado con la etiqueta del código, y el QR con la dirección. Atiende la invitación y,
     * detrás, la conversación de persona con la que el otro se trae el chat.
     */
    private fun abrirInvitacion() {
        val c = chat ?: return
        if (redDeLaInvitacion != null) return
        val codigo = Invitacion.nuevoCodigo()
        fallosDeCodigo = 0
        val r = Red(this).also { redDeLaInvitacion = it }
        r.escuchar(0, enParalelo = true) { socket, entrada ->
            val conMagia = entrada as? PushbackInputStream ?: return@escuchar
            val magia = ByteArray(4)
            var n = 0
            while (n < 4) { val k = conMagia.read(magia, n, 4 - n); if (k < 0) break; n += k }
            if (n > 0) conMagia.unread(magia, 0, n)
            val respondedor = Respondedor(disco, estado = { aviso = it }, miPuerto = Presencia.red.puerto.takeIf { it > 0 } ?: 0,
                alSaludar = { otro, puerto -> disco.apuntarDireccion(otro.id, socket.inetAddress?.hostAddress.orEmpty(), puerto) })
            try {
                if (magia.contentEquals(Canal.MAGIA_DE_INVITACION)) {
                    val quien = respondedor.atenderInvitacion(conMagia, socket.getOutputStream(), codigo, c)
                    aviso = "${quien.nombre} ha entrado. Se está trayendo el chat…"
                    Presencia.reiniciar()
                } else if (magia.contentEquals(Canal.MAGIA_DE_PERSONA)) {
                    respondedor.atenderPersona(conMagia, socket.getOutputStream())
                    aviso = "Listo: ya tiene el chat."
                }
            } catch (e: Canal.CodigoDistinto) {
                // Alguien probando códigos: a los pocos fallos, la invitación se cierra.
                if (++fallosDeCodigo >= Envio.INTENTOS) { aviso = "Demasiados códigos equivocados: invitación cerrada."; runOnUiThread { cerrarInvitacion() } }
            } catch (e: Exception) {
                aviso = "Se cortó: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        lifecycleScope.launch {
            val etiqueta = withContext(Dispatchers.Default) { Invitacion.etiqueta(codigo) }
            r.anunciar(Invitacion.TIPO, "PixPin ${yo?.nombre.orEmpty()}", mapOf("g" to etiqueta))
            val qr = withContext(Dispatchers.Default) { Qr.imagen(Invitacion.textoDelQr(codigo, Red.miDireccion(this@ChatCompartidoActivity), r.puerto)) }
            if (redDeLaInvitacion === r) invitacion = codigo to qr
        }
    }

    private fun cerrarInvitacion() {
        redDeLaInvitacion?.cerrar()
        redDeLaInvitacion = null
        invitacion = null
    }

    private fun quitar(id: String) = enFondo("Quitando…") {
        val c = chat ?: return@enFondo
        val y = yo ?: return@enFondo
        compartidos.cambiar { l -> l.map { if (it.chat == c) Compartidos.quitar(it, y, id, System.currentTimeMillis()) else it } }
        aviso = if (id == y.id) "Has salido. Se lo diremos a los demás al sincronizar." else "Quitado. Se enterará al sincronizar."
        if (id == y.id) Presencia.reiniciar()
        // Que se enteren ya los que estén cerca.
        sincronizarAhora()
    }

    private fun sincronizar() = enFondo("Buscando a las personas del chat…") { sincronizarAhora() }

    /**
     * **Sincroniza este chat con cada persona que esté en la Wi-Fi**: por la última dirección donde
     * se la vio, o buscándola en la red por su id. Mis propios aparatos no: esos van por el grupo.
     */
    private suspend fun sincronizarAhora() {
        val c = chat ?: return
        val l = compartidos.de(c) ?: return
        val y = yo ?: return
        // También quien ya no está: así se entera de que salió o de que lo quitaron.
        val otros = l.personas.filter { it.id != y.id && it.id !in misAparatos }
        if (otros.isEmpty()) { aviso = "Aún no hay nadie más. Pulsa Añadir."; return }
        val dirs = disco.direcciones().toMutableMap()
        val faltan = otros.filter { p -> dirs[p.id]?.let { (h, puerto) -> Red.sondear(h, puerto) } != true }.map { it.id }.toSet()
        if (faltan.isNotEmpty()) dirs.putAll(buscarEnLaRed(faltan))
        val hechos = ArrayList<String>()
        for (p in otros) {
            val (host, puerto) = dirs[p.id] ?: continue
            aviso = "Sincronizando con ${p.nombre}…"
            try {
                val r = Red.conectar(InetAddress.getByName(host), puerto).use { s ->
                    VueltaConPersona(disco, Presencia.red.puerto).con(s.getInputStream(), s.getOutputStream(), aQuien = p.id) { aviso = it }
                }
                if (r.puerto > 0) disco.apuntarDireccion(p.id, host, r.puerto)
                hechos += p.nombre
            } catch (e: Exception) {
                if (e.message == Protocolo.OCUPADO) hechos += "${p.nombre} (ocupado)"
            }
        }
        aviso = if (hechos.isEmpty()) "No hay nadie del chat en esta Wi-Fi ahora mismo."
        else "Sincronizado con " + hechos.distinct().joinToString(", ") + "."
    }

    /** Busca en la red, un rato, a los aparatos de [ids]. */
    private suspend fun buscarEnLaRed(ids: Set<String>): Map<String, Pair<String, Int>> {
        val r = Red(this)
        val hallados = HashMap<String, Pair<String, Int>>()
        val todos = CompletableDeferred<Unit>()
        withContext(Dispatchers.Main) {
            r.buscar(Red.TIPO, { it["id"] in ids }) { vecinos ->
                for (v in vecinos) v.host.hostAddress?.let { hallados[v.id] = it to v.puerto }
                if (hallados.keys.containsAll(ids)) todos.complete(Unit)
            }
        }
        withTimeoutOrNull(8_000) { todos.await() }
        withContext(Dispatchers.Main) { r.pararBusqueda() }
        return HashMap(hallados)
    }

    /** **Entrar con un código**: con la dirección del QR, o buscando a quien invita en la red. */
    private fun entrarCon(codigo: String, host: String?, puerto: Int) = enFondo("Buscando a quien invita…") {
        val (h, p) = if (host != null && puerto > 0) host to puerto else buscarInvitacion(codigo)
            ?: throw IllegalStateException("No se encontró la invitación. Comprueba el código y que estáis en la misma Wi-Fi.")
        aviso = "Entrando…"
        val entrada = Red.conectar(InetAddress.getByName(h), p).use { s ->
            Invitacion.entrar(s.getInputStream(), s.getOutputStream(), disco, codigo)
        }
        if (entrada.puerto > 0) disco.apuntarDireccion(entrada.quien.id, h, entrada.puerto)
        aviso = "Trayendo «${entrada.chat.nombre}»…"
        Red.conectar(InetAddress.getByName(h), p).use { s ->
            VueltaConPersona(disco, Presencia.red.puerto).con(s.getInputStream(), s.getOutputStream(), aQuien = entrada.quien.id) { aviso = it }
        }
        Presencia.reiniciar()
        aviso = null
        dentroDe = entrada.chat
    }

    private suspend fun buscarInvitacion(codigo: String): Pair<String, Int>? {
        val etiqueta = withContext(Dispatchers.Default) { Invitacion.etiqueta(codigo) }
        val r = Red(this)
        val hallado = CompletableDeferred<Red.Vecino>()
        withContext(Dispatchers.Main) { r.buscar(Invitacion.TIPO, { it["g"] == etiqueta }) { l -> l.firstOrNull()?.let { hallado.complete(it) } } }
        val v = withTimeoutOrNull(20_000) { hallado.await() }
        withContext(Dispatchers.Main) { r.pararBusqueda() }
        return v?.let { (it.host.hostAddress ?: return@let null) to it.puerto }
    }

    // ------------------------------------------------------------------ pantalla

    @Composable
    private fun Pantalla() {
        val version by CompartidosEnDisco.version.collectAsState()
        LaunchedEffect(version) { releer() }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { finish() }) { Icon(Icons.Filled.Close, contentDescription = "Cerrar") }
                    Column(Modifier.weight(1f)) {
                        Text(if (chat == null) Invitacion.UNIRSE else "Personas", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        if (chat != null && nombreDelChat.isNotBlank()) {
                            Text("«$nombreDelChat»", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                MiNombre()
                Spacer(Modifier.height(16.dp))
                if (chat == null) Entrar() else Personas()
                aviso?.let {
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (trabajando) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)) }
                        Text(it, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    /** El nombre con el que te ven los demás. */
    @Composable
    private fun MiNombre() {
        val actual = yo?.nombre.orEmpty()
        var texto by remember(actual) { mutableStateOf(actual) }
        OutlinedTextField(
            value = texto,
            onValueChange = { texto = it.take(Compartidos.NOMBRE_MAX) },
            label = { Text("Tu nombre en los chats compartidos") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            trailingIcon = {
                if (texto.isNotBlank() && texto.trim() != actual) IconButton(onClick = {
                    val nuevo = texto.trim()
                    lifecycleScope.launch(Dispatchers.IO) { compartidos.ponerNombre(nuevo) }
                }) { Icon(Icons.Filled.Check, contentDescription = "Guardar") }
            },
            modifier = Modifier.fillMaxWidth()
        )
        // Lo que ya firmé con otro nombre se queda así: el nombre nuevo vale para lo que venga.
    }

    @Composable
    private fun Personas() {
        val c = lista
        val y = yo
        if (c == null || y == null || c.personas.none { it.id == y.id }) {
            Caja {
                Column {
                    Text("Comparte este chat con otras personas.", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Solo viaja este chat: sus mensajes, sus archivos, sus lienzos y lo anotado. " +
                            "Tus demás chats y proyectos no se ven. Cada uno sincroniza con los demás en la misma Wi-Fi.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = { compartir() }, enabled = !trabajando, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Icon(Icons.Filled.Groups, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("Compartir", maxLines = 1)
            }
            return
        }
        val estoyDentro = Compartidos.dentro(c, y.id)
        if (!estoyDentro) {
            Caja { Text("Ya no estás en este chat. Lo que tenías se queda aquí, pero no se sincroniza.", style = MaterialTheme.typography.bodyMedium) }
            Spacer(Modifier.height(16.dp))
        }
        var quitando by remember { mutableStateOf<Persona?>(null) }
        // Una fila por aparato: cada uno firma sus mensajes con su código.
        for (p in c.personas.sortedWith(compareByDescending<Persona> { it.dentro }.thenBy { it.desde })) {
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(
                        if (p.dentro) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                    ),
                    contentAlignment = Alignment.Center
                ) { Text(p.nombre.take(1).uppercase(), fontWeight = FontWeight.SemiBold) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.nombre + if (p.id == y.id) " (tú)" else "", maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (p.dentro) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        listOfNotNull(
                            p.codigo,
                            "creó el chat".takeIf { p.id == c.creador },
                            "tu otro aparato".takeIf { p.id != y.id && p.id in misAparatos },
                            "salió".takeIf { !p.dentro && p.quito == p.id },
                            "quitado".takeIf { !p.dentro && p.quito != p.id }
                        ).joinToString(" · "),
                        fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (p.id != y.id && Compartidos.puedeQuitar(c, y.id, p.id)) {
                    TextButton(onClick = { quitando = p }, enabled = !trabajando) { Text("Quitar", maxLines = 1) }
                }
            }
            HorizontalDivider()
        }
        quitando?.let { p ->
            AlertDialog(
                onDismissRequest = { quitando = null },
                title = { Text(if (p.id == y.id) "¿Salir del chat?" else "¿Quitar a ${p.nombre}?") },
                text = {
                    Text(
                        if (p.id == y.id) "Lo que tienes se queda aquí, pero dejará de sincronizarse con los demás."
                        else "Lo que ya tiene se le queda, pero no recibirá nada nuevo de este chat."
                    )
                },
                confirmButton = { TextButton(onClick = { quitando = null; quitar(p.id) }) { Text(if (p.id == y.id) "Salir" else "Quitar") } },
                dismissButton = { TextButton(onClick = { quitando = null }) { Text("Cancelar") } }
            )
        }
        if (!estoyDentro) return
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { if (invitacion == null) abrirInvitacion() else cerrarInvitacion() }, modifier = Modifier.weight(1f).height(48.dp)) {
                Icon(Icons.Filled.PersonAdd, contentDescription = null); Spacer(Modifier.width(6.dp))
                Text(if (invitacion == null) "Añadir" else "Cerrar", maxLines = 1)
            }
            Button(onClick = { sincronizar() }, enabled = !trabajando, modifier = Modifier.weight(1f).height(48.dp)) {
                Icon(Icons.Filled.Sync, contentDescription = null); Spacer(Modifier.width(6.dp)); Text("Sincronizar", maxLines = 1)
            }
        }
        invitacion?.let { (codigo, qr) ->
            Spacer(Modifier.height(16.dp))
            Caja {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    // El camino de verdad (27-oct-2026): el «⋮ → Entrar en chat» que decía ya no existía
                    // fuera de «Mensajes guardados». Ver [Invitacion.COMO_UNIRSE].
                    Text(Invitacion.COMO_UNIRSE,
                        style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    Image(qr.asImageBitmap(), contentDescription = "Código QR de la invitación",
                        modifier = Modifier.widthIn(max = 260.dp).fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)))
                    Spacer(Modifier.height(12.dp))
                    Text(Invitacion.legible(codigo), fontSize = 30.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text("Vale mientras esta pantalla esté abierta. Tenéis que estar en la misma Wi-Fi.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        TextButton(onClick = { quitando = c.personas.first { it.id == y.id } }, enabled = !trabajando) {
            Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(6.dp))
            Text("Salir del chat", color = MaterialTheme.colorScheme.error, maxLines = 1)
        }
    }

    @Composable
    private fun Entrar() {
        dentroDe?.let { c ->
            Caja {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text("Ya estás en «${c.nombre}»", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(6.dp))
                    Text("Con: " + Compartidos.nombresDentro(c).joinToString(", "), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = {
                com.forge.pixpin.guardados.MensajesActivity.abrirChatDe(this, c.chat, c.nombre)
                finish()
            }, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Abrir chat", maxLines = 1) }
            return
        }
        Caja {
            Text(Invitacion.COMO_INVITAR,
                style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(16.dp))
        var escaneando by remember { mutableStateOf(false) }
        if (escaneando && camaraPermitida) {
            Box(Modifier.widthIn(max = 320.dp).fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(18.dp))) {
                Qr.Escaner(Modifier.fillMaxSize()) { texto ->
                    val leido = Invitacion.leerQr(texto) ?: return@Escaner
                    if (!trabajando) { escaneando = false; entrarCon(leido.codigo, leido.host, leido.puerto) }
                }
            }
        } else {
            OutlinedButton(onClick = {
                escaneando = true
                if (!camaraPermitida) pedirCamara.launch(Manifest.permission.CAMERA)
            }, enabled = !trabajando, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Icon(Icons.Filled.QrCodeScanner, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("Escanear", maxLines = 1)
            }
        }
        Spacer(Modifier.height(18.dp))
        Text("o escribe el código", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        var codigo by remember { mutableStateOf("") }
        // **Pegar el texto del QR también vale** (27-oct-2026): si llega por mensaje, trae la dirección
        // y se entra sin buscar en la red. Ver [Invitacion.deLoEscrito].
        var delQrPegado by remember { mutableStateOf<Invitacion.DelQr?>(null) }
        OutlinedTextField(
            value = Invitacion.legible(codigo),
            onValueChange = { tecleado ->
                val pegado = Invitacion.leerQr(tecleado)
                delQrPegado = pegado
                codigo = pegado?.codigo ?: Invitacion.limpiar(tecleado)
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            textStyle = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center),
            modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                val q = delQrPegado?.takeIf { it.codigo == codigo }
                entrarCon(codigo, q?.host, q?.puerto ?: 0)
            },
            enabled = Invitacion.valido(codigo) && !trabajando,
            modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth().height(48.dp)
        ) { Text("Entrar", maxLines = 1) }
    }

    companion object {
        private const val EXTRA_CHAT = "chat"
        private const val EXTRA_NOMBRE = "nombre"

        /** Las personas del chat de [proyecto]. */
        fun abrir(context: Context, proyecto: String, nombre: String) {
            context.startActivity(Intent(context, ChatCompartidoActivity::class.java).putExtra(EXTRA_CHAT, proyecto).putExtra(EXTRA_NOMBRE, nombre))
        }

        /**
         * Entrar en un chat que otro comparte, con su código. Se llega desde el «+» de la lista de
         * chats («Unirse a un chat compartido»), desde Sincronizar y desde el ⋮ de «Mensajes guardados».
         */
        fun entrar(context: Context) {
            context.startActivity(Intent(context, ChatCompartidoActivity::class.java))
        }
    }
}
