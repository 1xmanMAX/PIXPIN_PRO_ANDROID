package com.forge.pixpin.sincro

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.forge.pixpin.guardados.tamanoLegible
import com.forge.pixpin.ui.theme.PixPinTheme
import java.io.EOFException
import java.net.InetAddress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * **Sincronizar: tus aparatos, y pasar algo a otra persona.**
 *
 * Mientras PixPin está abierto el aparato se deja encontrar por los de su grupo (ver
 * [Presencia]); esta pantalla, además, **busca** a los demás y comprueba a los recordados, para
 * que aparezcan al momento. Nada es automático: pasa cuando se pulsa. Ver
 * `docs/plan-sincronizacion.md`.
 */
class SincronizarActivity : ComponentActivity() {

    private lateinit var disco: Disco
    private val red get() = Presencia.red

    // ------------------------------------------------------------------ estado

    private sealed interface Fase {
        data object Nada : Fase
        data class Conectando(val texto: String) : Fase
        data class Eligiendo(val otro: Aparato, val filas: List<Fila>, val listo: CompletableDeferred<Pair<Set<String>, Maestro>?>) : Fase
        data class Trabajando(val texto: String, val hechos: Long = 0, val total: Long = 0, val velocidad: String = "") : Fase
        data class Preguntando(val otro: Aparato, val chat: String, val preguntas: List<Sesion.Pregunta>, val listo: CompletableDeferred<Map<String, Boolean>?>) : Fase
        data class Terminado(val titulo: String, val texto: String, val aviso: String? = null) : Fase
        data class Fallo(val texto: String) : Fase
    }

    /**
     * **Qué gana en lo que cambió en los dos aparatos** (lo pidió el usuario el 13-sep-2026: «llevo la
     * tableta a la universidad y al volver quiero que mande ella»). Lo nuevo de cada lado se suma
     * siempre; esto solo decide los empates.
     */
    enum class Maestro(val texto: String) { PREGUNTAR("Preguntar"), ESTE("Este aparato"), OTRO("El otro"), RECIENTE("Lo más reciente") }

    /** Un chat en la lista de elegir: dónde está y cuándo se tocó en cada lado. */
    private data class Fila(val id: String, val nombre: String, val aqui: Chat?, val alli: Chat?, val marcado: Boolean)

    /** Un aparato del grupo en la lista. */
    private data class Miembro(val id: String, val nombre: String, val letra: String?, val host: String?, val puerto: Int, val cerca: Boolean)

    private var fase by mutableStateOf<Fase>(Fase.Nada)
    private val encontrados = mutableStateListOf<Red.Vecino>()
    private val responden = mutableStateMapOf<String, Boolean>()
    private var trabajo: Job? = null
    private var version by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        disco = Red.disco(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { PixPinTheme { Pantalla() } }
    }

    override fun onStart() {
        super.onStart()
        Presencia.encender()
        buscar()
        sondearMientrasEsteAbierta()
    }

    override fun onStop() {
        super.onStop()
        red.pararBusqueda()
    }

    /** Busca por la red a los del grupo. Lo encontrado se recuerda para la próxima vez. */
    private fun buscar() {
        val id = disco.identidad.leer(nombrePorOmision())
        if (!id.enGrupo) return
        lifecycleScope.launch(Dispatchers.Default) {
            val etiqueta = Grupo.etiqueta(Grupo.clave(id.codigo!!))
            withContext(Dispatchers.Main) {
                red.buscar(Red.TIPO, { it["g"] == etiqueta && it["id"] != id.yo.id }) { lista ->
                    encontrados.clear(); encontrados.addAll(lista)
                    lifecycleScope.launch(Dispatchers.IO) {
                        lista.forEach { v -> v.host.hostAddress?.let { disco.apuntarDireccion(v.id, it, v.puerto) } }
                    }
                }
            }
        }
    }

    /**
     * **Los recordados, comprobados cada pocos segundos**: un «¿estás?» directo a su última
     * dirección. Así un aparato ya conocido aparece al instante, sin esperar al anuncio de la red.
     */
    private fun sondearMientrasEsteAbierta() {
        lifecycleScope.launch(Dispatchers.IO) {
            while (isActive && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                val id = disco.identidad.leer()
                if (id.enGrupo) {
                    val dirs = disco.direcciones()
                    for (m in id.miembros) {
                        if (m.id == id.yo.id) continue
                        val (host, puerto) = dirs[m.id] ?: continue
                        responden[m.id] = Red.sondear(host, puerto)
                    }
                }
                delay(4_000)
            }
        }
    }

    private fun nombrePorOmision(): String {
        val modelo = android.os.Build.MODEL.orEmpty().trim()
        return if (modelo.isBlank()) "Este aparato" else modelo
    }

    private fun miembros(identidad: Identidad): List<Miembro> {
        val dirs = disco.direcciones()
        val porId = LinkedHashMap<String, Miembro>()
        for (m in identidad.miembros) {
            if (m.id == identidad.yo.id) continue
            val d = dirs[m.id]
            porId[m.id] = Miembro(m.id, m.nombre, m.letra, d?.first, d?.second ?: 0, responden[m.id] == true)
        }
        for (v in encontrados) {
            val antes = porId[v.id]
            porId[v.id] = Miembro(v.id, v.nombre, v.datos["l"]?.ifBlank { null } ?: antes?.letra, v.host.hostAddress, v.puerto, true)
        }
        return porId.values.sortedWith(compareByDescending<Miembro> { it.cerca }.thenBy { it.nombre })
    }

    // ---------------------------------------------------------- sincronizar

    private fun sincronizarCon(host: String, puerto: Int, nombre: String) {
        if (trabajo?.isActive == true) return
        trabajo = lifecycleScope.launch(Dispatchers.IO) {
            fase = Fase.Conectando("Conectando con $nombre…")
            try {
                Red.conectar(InetAddress.getByName(host), puerto).use { socket ->
                    val sesion = try {
                        Sesion.conectar(socket.getInputStream(), socket.getOutputStream(), disco, miPuerto = red.puerto)
                    } catch (e: EOFException) {
                        throw IllegalStateException("$nombre no aceptó la conexión: ¿tiene el mismo código de grupo?")
                    }
                    try {
                        val otro = sesion.otro
                        disco.apuntarDireccion(otro.id, host, sesion.puertoDelOtro.takeIf { it > 0 } ?: puerto)
                        val mios = disco.chats().associateBy { it.id }
                        val suyos = sesion.catalogo().associateBy { it.id }
                        val antes = disco.elegidos(otro.id)
                        val ids = (mios.keys + suyos.keys).distinct()
                        val filas = ids.map { id ->
                            val m = mios[id]; val s = suyos[id]
                            Fila(id, (m ?: s)!!.nombre, m, s, antes?.contains(id) ?: true)
                        }
                        val eleccion = CompletableDeferred<Pair<Set<String>, Maestro>?>()
                        fase = Fase.Eligiendo(otro, filas, eleccion)
                        val elegido = eleccion.await()
                        if (elegido == null) { sesion.adios(); fase = Fase.Nada; return@use }
                        val (elegidos, maestro) = elegido
                        maestroDeEstaVuelta = maestro
                        disco.guardarElegidos(otro.id, elegidos)

                        val hecho = Sesion.Hecho()
                        val empezo = System.currentTimeMillis()
                        for (chat in ids.filter { it in elegidos }) {
                            val nombreDelChat = filas.first { it.id == chat }.nombre
                            fase = Fase.Trabajando("Comparando «$nombreDelChat»…")
                            val prep = sesion.preparar(chat)
                            val decisiones = preguntar(otro, nombreDelChat, prep.preguntas) ?: run { sesion.adios(); fase = Fase.Nada; return@use }
                            fase = Fase.Trabajando("Juntando «$nombreDelChat»…")
                            sesion.aplicar(prep, decisiones, hecho)
                            val archivos = sesion.prepararArchivos(prep)
                            val decisiones2 = preguntar(otro, nombreDelChat, archivos.preguntas) ?: run { sesion.adios(); fase = Fase.Nada; return@use }
                            val total = archivos.bytes
                            var hechos = 0L
                            var ultimo = 0L
                            val t0 = System.currentTimeMillis()
                            sesion.aplicarArchivos(archivos, decisiones2, hecho) { n ->
                                hechos += n
                                val ahora = System.currentTimeMillis()
                                if (ahora - ultimo > 150) {
                                    ultimo = ahora
                                    val seg = (ahora - t0).coerceAtLeast(1) / 1000.0
                                    fase = Fase.Trabajando("Pasando archivos de «$nombreDelChat»", hechos, total, tamanoLegible((hechos / seg).toLong()) + "/s")
                                }
                            }
                            fase = Fase.Trabajando("Terminando «$nombreDelChat»…")
                            sesion.cerrar(prep)
                        }
                        sesion.adios()
                        val segundos = (System.currentTimeMillis() - empezo) / 1000.0
                        val partes = listOfNotNull(
                            "${hecho.traidos} traídos".takeIf { hecho.traidos > 0 },
                            "${hecho.enviados} enviados".takeIf { hecho.enviados > 0 },
                            "${hecho.borrados} borrados".takeIf { hecho.borrados > 0 },
                            "${hecho.archivos} archivos".takeIf { hecho.archivos > 0 }
                        )
                        val movido = sesion.enviados + sesion.recibidos
                        val texto = (if (partes.isEmpty()) "Ya estaban iguales." else partes.joinToString(" · ") + ".") +
                            "\n${tamanoLegible(movido).ifBlank { "0 B" }} en ${"%.1f".format(segundos)} s."
                        val avisos = listOfNotNull(
                            hecho.saltados.takeIf { it.isNotEmpty() }?.let {
                                "No se pasó ${it.distinct().joinToString { n -> "«$n»" }} porque se estaba guardando en ese momento. Vuelve a sincronizar."
                            },
                            avisoDelReloj(otro, sesion.desfase)
                        )
                        fase = Fase.Terminado("Al día con ${otro.nombre}", texto, avisos.joinToString("\n\n").ifBlank { null })
                    } finally {
                        sesion.soltar()
                    }
                }
            } catch (e: Exception) {
                fase = Fase.Fallo(explicar(e, nombre))
            }
            version++
        }
    }

    private var maestroDeEstaVuelta = Maestro.PREGUNTAR

    private suspend fun preguntar(otro: Aparato, chat: String, preguntas: List<Sesion.Pregunta>): Map<String, Boolean>? {
        if (preguntas.isEmpty()) return emptyMap()
        // Con un aparato maestro no se pregunta: manda él (o lo más reciente).
        when (maestroDeEstaVuelta) {
            Maestro.ESTE -> return preguntas.associate { it.clave to true }
            Maestro.OTRO -> return preguntas.associate { it.clave to false }
            Maestro.RECIENTE -> return preguntas.associate { it.clave to ((it.mio?.cuando ?: 0L) >= (it.suyo?.cuando ?: 0L)) }
            Maestro.PREGUNTAR -> {}
        }
        val listo = CompletableDeferred<Map<String, Boolean>?>()
        fase = Fase.Preguntando(otro, chat, preguntas, listo)
        return listo.await()
    }

    /**
     * **Los relojes no hace falta que coincidan para decidir** —se compara el contenido—, pero sí
     * ordenan el chat. Si el otro va desfasado se dice, con lo que hay que hacer.
     */
    private fun avisoDelReloj(otro: Aparato, desfase: Long): String? {
        val minutos = kotlin.math.abs(desfase) / 60_000
        if (minutos < 2) return null
        val sentido = if (desfase > 0) "adelantado" else "atrasado"
        return "El reloj de ${otro.nombre} va $minutos min $sentido. Pon «fecha y hora automáticas» en los dos: con la hora mal, lo que llega se coloca en el chat donde no toca."
    }

    private fun explicar(e: Throwable, nombre: String): String = when (e) {
        is java.net.ConnectException, is java.net.SocketTimeoutException, is java.net.NoRouteToHostException ->
            "No se pudo llegar a $nombre. Comprueba que está en la misma Wi-Fi y con PixPin abierto."
        is IllegalStateException -> e.message ?: "Algo salió mal"
        is EOFException -> "$nombre cortó la conexión a medias. Lo que ya se había pasado está bien; vuelve a sincronizar para terminar."
        is java.io.IOException ->
            if (e.message == Protocolo.OCUPADO) "$nombre está sincronizando con otro aparato. Prueba otra vez en un momento."
            else "Se cortó la conexión con $nombre: ${e.message ?: e.javaClass.simpleName}. Vuelve a sincronizar para terminar."
        else -> "Algo salió mal: ${e.message ?: e.javaClass.simpleName}"
    }

    // ------------------------------------------------------------- unirse

    private fun unirseCon(codigo: String, direccion: String?) {
        if (trabajo?.isActive == true) return
        trabajo = lifecycleScope.launch(Dispatchers.IO) {
            try {
                fase = Fase.Conectando("Buscando un aparato del grupo en esta Wi-Fi…\nEn el otro aparato, ten PixPin abierto.")
                val (host, puerto, nombre) = if (direccion != null) {
                    val (h, p) = partirDireccion(direccion) ?: throw IllegalStateException("La dirección tiene que ser como 192.168.1.20:47474")
                    Triple(h, p, h)
                } else {
                    val etiqueta = Grupo.etiqueta(Grupo.clave(codigo))
                    val encontrado = CompletableDeferred<Red.Vecino>()
                    withContext(Dispatchers.Main) { red.buscar(Red.TIPO, { it["g"] == etiqueta }) { l -> l.firstOrNull()?.let { encontrado.complete(it) } } }
                    val v = kotlinx.coroutines.withTimeoutOrNull(25_000) { encontrado.await() }
                        ?: throw IllegalStateException("No apareció ningún aparato con ese código. Comprueba el código, que los dos estáis en la misma Wi-Fi y que el otro tiene PixPin abierto. También puedes escribir su dirección.")
                    Triple(v.host.hostAddress ?: "", v.puerto, v.nombre)
                }
                fase = Fase.Conectando("Uniéndome al grupo con $nombre…")
                Red.conectar(InetAddress.getByName(host), puerto).use { s ->
                    val sesion = try {
                        Sesion.conectar(s.getInputStream(), s.getOutputStream(), disco, unirme = true, codigo = codigo, miPuerto = Red.PUERTO)
                    } catch (e: EOFException) {
                        throw IllegalStateException("$nombre no aceptó el código. Revísalo.")
                    }
                    sesion.adios()
                    disco.apuntarDireccion(sesion.otro.id, host, sesion.puertoDelOtro.takeIf { it > 0 } ?: puerto)
                    val id = disco.identidad.leer()
                    fase = Fase.Terminado(
                        "Dentro del grupo",
                        "Este aparato es la letra «${id.yo.letra}»: sus mensajes se nombran #1${id.yo.letra}, #2${id.yo.letra}…\n\nYa puedes sincronizar con ${sesion.otro.nombre}."
                    )
                }
                withContext(Dispatchers.Main) { Presencia.reiniciar(); buscar() }
            } catch (e: Exception) {
                fase = Fase.Fallo(explicar(e, "el otro aparato"))
            }
            version++
        }
    }

    private fun partirDireccion(texto: String): Pair<String, Int>? {
        val t = texto.trim()
        if (t.isBlank()) return null
        val host = t.substringBefore(':')
        val puerto = if (':' in t) t.substringAfter(':').toIntOrNull() ?: return null else Red.PUERTO
        return host to puerto
    }

    // ------------------------------------------------------------ pantalla

    @Composable
    private fun Pantalla() {
        val cambioDeIdentidad by Presencia.version.collectAsState()
        val identidad = remember(cambioDeIdentidad, version) { disco.identidad.leer(nombrePorOmision()) }

        BackHandler(enabled = fase is Fase.Eligiendo || fase is Fase.Preguntando || fase is Fase.Terminado || fase is Fase.Fallo) {
            cerrarFase()
        }

        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when (val f = fase) {
                is Fase.Eligiendo -> Eligiendo(identidad, f)
                is Fase.Preguntando -> Preguntas(f)
                else -> Portada(identidad)
            }
        }
        Dialogos()
    }

    @Composable
    private fun Portada(identidad: Identidad) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver") }
                Text("Sincronizar", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            }
            Text(
                "Por la Wi-Fi, sin internet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, bottom = 16.dp)
            )

            EsteAparato(identidad)

            Titulo("Tus aparatos")
            if (identidad.enGrupo) ConGrupo(identidad) else SinGrupo()

            Titulo("Pasar algo a otra persona")
            EnviarORecibir()

            val registro by Presencia.registro.collectAsState()
            val atendiendo by Presencia.atendiendo.collectAsState()
            if (atendiendo != null || registro.isNotEmpty()) {
                Titulo("Actividad")
                Caja {
                    atendiendo?.let {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(it, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    for (r in registro.take(5)) Text(r, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }

    @Composable
    private fun Titulo(texto: String) {
        Text(
            texto, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, top = 22.dp, bottom = 8.dp)
        )
    }

    @Composable
    private fun EsteAparato(identidad: Identidad) {
        var renombrando by remember { mutableStateOf(false) }
        Caja {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Letra(identidad.yo.letra, grande = true)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Este aparato", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(identidad.yo.nombre, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { renombrando = true }) { Icon(Icons.Filled.Edit, contentDescription = "Cambiar el nombre") }
            }
        }
        if (renombrando) {
            var nombre by remember { mutableStateOf(identidad.yo.nombre) }
            AlertDialog(
                onDismissRequest = { renombrando = false },
                title = { Text("Nombre de este aparato") },
                text = {
                    Column {
                        Text("Es como lo verán tus otros aparatos.", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = nombre, onValueChange = { nombre = it.take(40) }, singleLine = true,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
                        )
                    }
                },
                confirmButton = {
                    TextButton(enabled = nombre.isNotBlank(), onClick = {
                        renombrando = false
                        disco.renombrar(nombre.trim()); Presencia.reiniciar(); version++
                    }) { Text("Guardar") }
                },
                dismissButton = { TextButton(onClick = { renombrando = false }) { Text("Cancelar") } }
            )
        }
    }

    @Composable
    private fun SinGrupo() {
        var uniendo by remember { mutableStateOf(false) }
        Caja {
            Text(
                "Crea un grupo en uno de tus aparatos y únete desde los demás con su código. Solo los del grupo se sincronizan entre sí.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(14.dp))
            Button(onClick = {
                lifecycleScope.launch(Dispatchers.IO) {
                    disco.crearGrupo(nombre = disco.identidad.leer(nombrePorOmision()).yo.nombre)
                    withContext(Dispatchers.Main) { Presencia.reiniciar(); buscar(); version++ }
                }
            }, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Crear un grupo") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { uniendo = true }, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Unirme con un código") }
        }
        if (uniendo) {
            var codigo by remember { mutableStateOf("") }
            var direccion by remember { mutableStateOf("") }
            val limpio = Grupo.limpiar(codigo)
            AlertDialog(
                onDismissRequest = { uniendo = false },
                title = { Text("Unirme a un grupo") },
                text = {
                    Column {
                        Text("Escribe el código que sale en «Añadir otro aparato» en uno del grupo. Ese aparato tiene que tener PixPin abierto y estar en tu misma Wi-Fi.")
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = codigo, onValueChange = { codigo = it.take(14) }, singleLine = true,
                            label = { Text("Código") }, placeholder = { Text("ABCDE-23456") },
                            textStyle = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters)
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = direccion, onValueChange = { direccion = it.take(40) }, singleLine = true,
                            label = { Text("Dirección (solo si no lo encuentra)") }, placeholder = { Text("192.168.1.20:47474") }
                        )
                    }
                },
                confirmButton = {
                    TextButton(enabled = Grupo.valido(limpio), onClick = {
                        uniendo = false
                        unirseCon(limpio, direccion.ifBlank { null })
                    }) { Text("Unirme") }
                },
                dismissButton = { TextButton(onClick = { uniendo = false }) { Text("Cancelar") } }
            )
        }
    }

    @Composable
    private fun ConGrupo(identidad: Identidad) {
        var verCodigo by remember { mutableStateOf(false) }
        var saliendo by remember { mutableStateOf(false) }
        var aMano by remember { mutableStateOf(false) }
        val lista = miembros(identidad)

        Caja(relleno = PaddingValues(vertical = 6.dp)) {
            if (lista.isEmpty()) {
                Text(
                    "Todavía no hay otro aparato en el grupo. Añade uno con el código de abajo.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }
            lista.forEachIndexed { i, m ->
                if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Letra(m.letra)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(m.nombre, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Punto(if (m.cerca) VERDE else MaterialTheme.colorScheme.outline)
                            Spacer(Modifier.width(6.dp))
                            val cuando = disco.ultimaVez(m.id)
                            Text(
                                (if (m.cerca) "Disponible" else "No encontrado") +
                                    (if (cuando > 0) " · sincronizado ${haceCuanto(cuando)}" else ""),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    if (m.cerca && m.host != null) {
                        Button(onClick = { sincronizarCon(m.host, m.puerto, m.nombre) }, contentPadding = PaddingValues(horizontal = 14.dp)) {
                            Icon(Icons.Filled.Sync, contentDescription = null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Sincronizar")
                        }
                    } else if (m.host != null) {
                        OutlinedButton(onClick = { sincronizarCon(m.host, m.puerto, m.nombre) }) { Text("Probar") }
                    }
                }
            }
            if (lista.any { !it.cerca }) {
                Text(
                    "Si no aparece: que tenga PixPin abierto y esté en esta Wi-Fi.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }
            TextButton(onClick = { aMano = true }, modifier = Modifier.padding(horizontal = 6.dp)) { Text("Conectar con una dirección") }
        }

        Spacer(Modifier.height(12.dp))
        Caja {
            Text("Añadir otro aparato", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text("En el otro aparato: Sincronizar → «Unirme con un código».", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { verCodigo = !verCodigo }.padding(vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (verCodigo) Grupo.legible(identidad.codigo!!) else "Toca para ver el código",
                    fontFamily = if (verCodigo) FontFamily.Monospace else FontFamily.Default,
                    fontSize = if (verCodigo) 26.sp else 15.sp, fontWeight = FontWeight.Bold
                )
            }
            Grupo.choques(identidad.miembros).forEach { (letra, quienes) ->
                Spacer(Modifier.height(8.dp))
                Text(
                    "Hay dos aparatos con la letra «$letra» (${quienes.joinToString { it.nombre }}). Sal del grupo en el que entró después y vuelve a unirte.",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = { saliendo = true }) { Text("Salir del grupo", color = MaterialTheme.colorScheme.error) }
        }

        if (aMano) {
            var texto by remember { mutableStateOf("") }
            var mia by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(Unit) { mia = withContext(Dispatchers.IO) { Red.miDireccion(this@SincronizarActivity) } }
            AlertDialog(
                onDismissRequest = { aMano = false },
                title = { Text("Conectar con una dirección") },
                text = {
                    Column {
                        Text("Escribe la dirección que sale en el otro aparato, en esta misma ventana.")
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(value = texto, onValueChange = { texto = it.take(40) }, singleLine = true, placeholder = { Text("192.168.1.20:47474") })
                        Spacer(Modifier.height(12.dp))
                        Text("La de este aparato:", style = MaterialTheme.typography.labelMedium)
                        Text(mia?.let { "$it:${red.puerto.takeIf { p -> p > 0 } ?: Red.PUERTO}" } ?: "Sin Wi-Fi", fontFamily = FontFamily.Monospace, fontSize = 18.sp)
                    }
                },
                confirmButton = {
                    TextButton(enabled = partirDireccion(texto) != null, onClick = {
                        aMano = false
                        val (h, p) = partirDireccion(texto)!!
                        sincronizarCon(h, p, h)
                    }) { Text("Conectar") }
                },
                dismissButton = { TextButton(onClick = { aMano = false }) { Text("Cerrar") } }
            )
        }
        if (saliendo) {
            AlertDialog(
                onDismissRequest = { saliendo = false },
                title = { Text("¿Salir del grupo?") },
                text = { Text("Este aparato dejará de sincronizarse con los demás. No se borra nada. Si vuelves a unirte, la primera vez te preguntará por lo que haya cambiado en los dos lados.") },
                confirmButton = { TextButton(onClick = { saliendo = false; disco.salirDelGrupo(); encontrados.clear(); Presencia.reiniciar(); version++ }) { Text("Salir") } },
                dismissButton = { TextButton(onClick = { saliendo = false }) { Text("Cancelar") } }
            )
        }
    }

    @Composable
    private fun EnviarORecibir() {
        Caja {
            Text(
                "Una sola vez, a cualquiera con PixPin en tu misma Wi-Fi. No hace falta grupo.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { RecibirActivity.abrir(this@SincronizarActivity) }, modifier = Modifier.weight(1f).height(48.dp)) {
                    Icon(Icons.Filled.Download, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Recibir")
                }
                OutlinedButton(onClick = { EnviarActivity.elegirArchivos(this@SincronizarActivity) }, modifier = Modifier.weight(1f).height(48.dp)) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Enviar")
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "También desde «Compartir» en cualquier app, y desde el menú de cada proyecto.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    // ------------------------------------------------------- elegir qué

    /**
     * **Qué sincronizar**, en dos pestañas: lo de este aparato y lo del otro. El punto verde es lo
     * que está en los dos; el rojo, lo que solo está en uno y se copiará al otro. Debajo, cuál de
     * las dos versiones es la más reciente, sin horas.
     */
    @Composable
    private fun Eligiendo(identidad: Identidad, f: Fase.Eligiendo) {
        val marcados = remember(f) { mutableStateMapOf<String, Boolean>().apply { f.filas.forEach { put(it.id, it.marcado) } } }
        var pestana by remember(f) { mutableIntStateOf(0) }
        var maestro by remember(f) { mutableStateOf(Maestro.PREGUNTAR) }
        val cuantos = marcados.count { it.value }
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { f.listo.complete(null) }) { Icon(Icons.Filled.Close, contentDescription = "Cancelar") }
                Column(Modifier.weight(1f)) {
                    Text("¿Qué sincronizar?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text("Con ${f.otro.nombre}. Lo marcado queda igual en los dos.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Punto(VERDE); Spacer(Modifier.width(6.dp)); Text("En los dos", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.width(18.dp))
                Punto(ROJO); Spacer(Modifier.width(6.dp)); Text("Solo en uno", style = MaterialTheme.typography.bodySmall)
            }
            val lados = listOf(identidad.yo.nombre to true, f.otro.nombre to false)
            PrimaryTabRow(selectedTabIndex = pestana) {
                lados.forEachIndexed { i, (nombre, esMio) ->
                    val n = f.filas.count { if (esMio) it.aqui != null else it.alli != null }
                    Tab(selected = pestana == i, onClick = { pestana = i }, text = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(nombre, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                            Text((if (esMio) "Este aparato · " else "") + "$n", style = MaterialTheme.typography.labelSmall)
                        }
                    })
                }
            }
            val esMio = pestana == 0
            val filas = f.filas.filter { if (esMio) it.aqui != null else it.alli != null }
            val otroNombre = if (esMio) f.otro.nombre else identidad.yo.nombre
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 6.dp)) {
                items(filas, key = { it.id }) { fila ->
                    val suyo = if (esMio) fila.aqui!! else fila.alli!!
                    val delOtro = if (esMio) fila.alli else fila.aqui
                    Row(
                        Modifier.fillMaxWidth().clickable { marcados[fila.id] = marcados[fila.id] != true }.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = marcados[fila.id] == true, onCheckedChange = { marcados[fila.id] = it })
                        Punto(if (delOtro != null) VERDE else ROJO, tamano = 10.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(fila.nombre, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val gris = MaterialTheme.colorScheme.onSurfaceVariant
                            val (texto, color) = when {
                                delOtro == null -> "Solo aquí · se copiará a $otroNombre" to gris
                                suyo.tocado > delOtro.tocado -> "Más reciente" to VERDE
                                suyo.tocado < delOtro.tocado -> "Anterior" to gris
                                else -> "Igual en los dos" to gris
                            }
                            Text(texto, style = MaterialTheme.typography.bodySmall, color = color, fontWeight = if (color == VERDE) FontWeight.SemiBold else FontWeight.Normal)
                        }
                    }
                }
            }
            HorizontalDivider()
            // **Qué manda si algo cambió en los dos.** Lo nuevo de cada lado se suma siempre.
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("Si algo cambió en los dos, gana:", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (m in Maestro.entries) {
                        val puesto = m == maestro
                        val texto = if (m == Maestro.OTRO) f.otro.nombre else m.texto
                        Box(
                            Modifier.clip(RoundedCornerShape(10.dp))
                                .background(if (puesto) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { maestro = m }
                                .padding(horizontal = 10.dp, vertical = 7.dp)
                        ) {
                            Text(texto, style = MaterialTheme.typography.labelMedium, maxLines = 1,
                                color = if (puesto) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Text("Lo que solo está en uno se copia al otro: nada se pierde.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { f.filas.forEach { marcados[it.id] = true } }) { Text("Todo") }
                TextButton(onClick = { f.filas.forEach { marcados[it.id] = false } }) { Text("Nada") }
                Spacer(Modifier.weight(1f))
                Button(enabled = cuantos > 0, onClick = { f.listo.complete(marcados.filterValues { it }.keys.toSet() to maestro) }, modifier = Modifier.height(48.dp)) {
                    Icon(Icons.Filled.Sync, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Sincronizar $cuantos")
                }
            }
        }
    }

    /**
     * **Cuál conservar.** Solo sale cuando los dos aparatos tocaron lo mismo desde la última vez,
     * o uno lo borró. Viene marcado lo último que se editó —o el borrado, que es una decisión— y
     * lo elegido se copia a los dos lados.
     */
    @Composable
    private fun Preguntas(f: Fase.Preguntando) {
        val eleccion = remember(f) { mutableStateMapOf<String, Boolean>().apply { f.preguntas.forEach { put(it.clave, it.porOmision) } } }
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text("Cambiado en los dos", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("«${f.chat}». Elige cuál se queda: quedará igual en este aparato y en ${f.otro.nombre}.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(f.preguntas, key = { it.clave }) { p ->
                    Caja {
                        Text(p.titulo, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            when (p.porque) {
                                Diferencia.Choque.LOS_DOS_CAMBIARON -> "Editado en los dos"
                                Diferencia.Choque.BORRADO_CONTRA_INTACTO -> "Borrado en uno"
                                Diferencia.Choque.BORRADO_CONTRA_CAMBIADO -> "Borrado en uno y editado en el otro"
                            },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))
                        val cuandoMio = p.mio?.cuando ?: 0
                        val cuandoSuyo = p.suyo?.cuando ?: 0
                        for ((esMio, vista) in listOf(true to p.mio, false to p.suyo)) {
                            val elegido = eleccion[p.clave] == esMio
                            val masNuevo = if (esMio) cuandoMio > cuandoSuyo else cuandoSuyo > cuandoMio
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(12.dp))
                                    .background(if (elegido) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                    .clickable { eleccion[p.clave] = esMio }.padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = elegido, onClick = { eleccion[p.clave] = esMio })
                                Column(Modifier.weight(1f)) {
                                    Text(if (esMio) "El de este aparato" else "El de ${f.otro.nombre}", fontWeight = FontWeight.Medium)
                                    val detalle = when {
                                        vista == null -> ""
                                        vista.borrado -> "Borrado"
                                        else -> listOf(
                                            if (masNuevo) "Más reciente" else "Anterior",
                                            tamanoLegible(vista.bytes)
                                        ).filter { it.isNotBlank() }.joinToString(" · ")
                                    }
                                    Text(detalle, style = MaterialTheme.typography.bodySmall,
                                        color = if (masNuevo && vista?.borrado != true) VERDE else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { f.listo.complete(null) }) { Text("Parar") }
                Spacer(Modifier.weight(1f))
                Button(onClick = { f.listo.complete(eleccion.toMap()) }, modifier = Modifier.height(48.dp)) { Text("Seguir") }
            }
        }
    }

    private fun cerrarFase() {
        when (val f = fase) {
            is Fase.Eligiendo -> f.listo.complete(null)
            is Fase.Preguntando -> f.listo.complete(null)
            else -> fase = Fase.Nada
        }
    }

    @Composable
    private fun Dialogos() {
        when (val f = fase) {
            is Fase.Conectando -> AlertDialog(
                onDismissRequest = {},
                title = { Text("Un momento") },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(22.dp))
                        Spacer(Modifier.width(14.dp))
                        Text(f.texto)
                    }
                },
                confirmButton = { TextButton(onClick = { trabajo?.cancel(); fase = Fase.Nada }) { Text("Cancelar") } }
            )
            is Fase.Trabajando -> AlertDialog(
                onDismissRequest = {},
                title = { Text("Sincronizando") },
                text = {
                    Column {
                        Text(f.texto)
                        Spacer(Modifier.height(12.dp))
                        if (f.total > 0) {
                            LinearProgressIndicator(progress = { (f.hechos.toFloat() / f.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(6.dp))
                            Text("${tamanoLegible(f.hechos).ifBlank { "0 B" }} de ${tamanoLegible(f.total)} · ${f.velocidad}", style = MaterialTheme.typography.bodySmall)
                        } else {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    }
                },
                confirmButton = {}
            )
            is Fase.Terminado -> AlertDialog(
                onDismissRequest = { fase = Fase.Nada },
                title = { Text(f.titulo) },
                text = {
                    Column {
                        Text(f.texto)
                        f.aviso?.let { Spacer(Modifier.height(10.dp)); Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                },
                confirmButton = { TextButton(onClick = { fase = Fase.Nada }) { Text("Vale") } }
            )
            is Fase.Fallo -> AlertDialog(
                onDismissRequest = { fase = Fase.Nada },
                title = { Text("No se pudo") },
                text = { Text(f.texto) },
                confirmButton = { TextButton(onClick = { fase = Fase.Nada }) { Text("Vale") } }
            )
            else -> {}
        }
    }

    companion object {
        fun abrir(context: Context) {
            context.startActivity(Intent(context, SincronizarActivity::class.java))
        }
    }
}

internal val VERDE = Color(0xFF2E9E4F)
internal val ROJO = Color(0xFFE0453A)

/** Una caja que se nota: borde, fondo propio y esquinas redondas. */
@Composable
internal fun Caja(relleno: PaddingValues = PaddingValues(16.dp), contenido: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(relleno)) { contenido() }
    }
}

@Composable
internal fun Punto(color: Color, tamano: Dp = 8.dp) {
    Box(Modifier.size(tamano).background(color, CircleShape))
}

/** La letra del aparato en un círculo: lo que lo nombra en las señas (#12b). */
@Composable
internal fun Letra(letra: String?, grande: Boolean = false) {
    val lado = if (grande) 48.dp else 36.dp
    Box(
        Modifier.size(lado).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            letra?.uppercase() ?: "·",
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            fontWeight = FontWeight.Bold, fontSize = if (grande) 22.sp else 16.sp
        )
    }
}

/** «hace 5 min», «hace 2 h», «ayer»: sin horas exactas, que no hacen falta para decidir. */
internal fun haceCuanto(cuando: Long, ahora: Long = System.currentTimeMillis()): String {
    val min = (ahora - cuando).coerceAtLeast(0) / 60_000
    return when {
        min < 1 -> "ahora mismo"
        min < 60 -> "hace $min min"
        min < 24 * 60 -> "hace ${min / 60} h"
        min < 48 * 60 -> "ayer"
        else -> "hace ${min / (24 * 60)} días"
    }
}
