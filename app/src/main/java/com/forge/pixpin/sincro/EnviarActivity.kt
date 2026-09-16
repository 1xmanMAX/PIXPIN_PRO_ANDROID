package com.forge.pixpin.sincro

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.forge.pixpin.PixPinApp
import com.forge.pixpin.guardados.tamanoLegible
import com.forge.pixpin.ui.theme.PixPinTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * **Enviar por Wi-Fi a los aparatos que quieras.** Ver [Envio].
 *
 * Llega de tres sitios: «Compartir» en cualquier aplicación (uno o varios archivos), el menú de un
 * proyecto (el proyecto entero) y el botón «Enviar» de Sincronizar (elegir archivos). Enseña un
 * código y su QR; cada aparato que lo use sale en la lista y se le manda cuando se aprueba. El
 * código vale mientras esta pantalla esté abierta: al cerrarla deja de existir. Ver [Destino].
 */
class EnviarActivity : ComponentActivity() {

    private sealed interface Estado {
        data object Preparando : Estado
        data class Esperando(val codigo: String, val qr: Bitmap, val intentosMalos: Int = 0) : Estado
        data class Fallo(val texto: String) : Estado
    }

    /**
     * **Un aparato que se ha conectado con el código** (16-sep-2026, pedido por el usuario: «que
     * se pueda con varios, que me muestre qué dispositivos están enlistados y poder aprobar el
     * envío a los que quiera»).
     *
     * Antes el código valía **una vez**: el primero que llegaba se lo llevaba y la puerta se
     * cerraba. Ahora la puerta sigue abierta hasta que se cierra la pantalla, cada uno que llega
     * sale en la lista **esperando aprobación**, y solo se le manda cuando se le da al botón. El
     * hilo que lo atiende se queda parado en [aprobacion] mientras tanto — por eso la escucha del
     * envío va en paralelo, ver [Red.escuchar].
     */
    private data class Destino(
        val id: Long,
        val nombre: String,
        val fase: Fase,
        val hechos: Long = 0,
        val total: Long = 0,
        val texto: String = ""
    ) {
        enum class Fase { PIDIENDO, ENVIANDO, HECHO, RECHAZADO, SIN_APROBAR, FALLO }
    }

    private var destinos by mutableStateOf<List<Destino>>(emptyList())
    /** Con la cámara abierta para escanear a quien va a recibir. Ver [mandarA]. */
    private var escaneando by mutableStateOf(false)
    private var camaraPermitida by mutableStateOf(false)
    /** Los códigos ya escaneados, para que pasar la cámara dos veces por el mismo no mande dos. */
    private val yaEscaneados = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val aprobaciones = java.util.concurrent.ConcurrentHashMap<Long, java.util.concurrent.CompletableFuture<Boolean>>()

    private fun cambiar(id: Long, como: (Destino) -> Destino) {
        runOnUiThread { destinos = destinos.map { if (it.id == id) como(it) else it } }
    }

    private var estado by mutableStateOf<Estado>(Estado.Preparando)
    private var cosas by mutableStateOf<List<Pair<Envio.Elemento, File>>>(emptyList())
    private var red: Red? = null
    private val carpeta by lazy { File(cacheDir, "envio/${System.currentTimeMillis()}").apply { mkdirs() } }

    private val elegir = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) { finish(); return@registerForActivityResult }
        lifecycleScope.launch { preparar { deUris(uris) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        camaraPermitida = androidx.core.content.ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        setContent { PixPinTheme { Pantalla() } }
        if (savedInstanceState != null) { finish(); return }
        val proyecto = intent.getStringExtra(EXTRA_PROYECTO)
        val hoja = intent.getStringExtra(EXTRA_HOJA)
        val hojas = intent.getStringArrayExtra(EXTRA_HOJAS)
        val ruta = intent.getStringExtra(EXTRA_RUTA)
        when {
            proyecto != null && hoja != null -> lifecycleScope.launch { preparar { deLienzo(proyecto, hoja) } }
            proyecto != null && hojas != null -> lifecycleScope.launch { preparar { hojas.distinct().flatMap { deLienzo(proyecto, it) } } }
            proyecto != null -> lifecycleScope.launch { preparar { deProyecto(proyecto) } }
            ruta != null -> lifecycleScope.launch { preparar { deRuta(File(ruta), intent.getStringExtra(EXTRA_NOMBRE)) } }
            intent.getBooleanExtra(EXTRA_ELEGIR, false) -> elegir.launch(arrayOf("*/*"))
            else -> {
                // **Se copia ya**: el permiso sobre lo compartido se va en cuanto esta pantalla muere.
                val uris = when (intent.action) {
                    Intent.ACTION_SEND -> listOfNotNull(uriDe(intent))
                    Intent.ACTION_SEND_MULTIPLE -> urisDe(intent)
                    else -> emptyList()
                }
                if (uris.isEmpty()) { estado = Estado.Fallo("No llegó ningún archivo que enviar."); return }
                lifecycleScope.launch { preparar { deUris(uris) } }
            }
        }
    }

    private val pedirCamara = registerForActivityResult(ActivityResultContracts.RequestPermission()) { camaraPermitida = it }

    /**
     * **Mandarle a quien está esperando** (16-sep-2026, idea del usuario): aquí los papeles se
     * cambian —quien recibe abrió la puerta y enseña su QR, y este aparato **llama**—. Para
     * mandarle algo a un ordenador o a tres personas concretas sin dictarle el código a nadie: se
     * pasa la cámara por sus pantallas y cada una se pone en la lista.
     *
     * Escanear **es** la aprobación: no se vuelve a preguntar. Ver [Envio.textoDelQrDeRecepcion].
     */
    private fun mandarA(leido: Envio.DelQr) {
        if (!yaEscaneados.add(leido.codigo)) return
        val lista = cosas
        if (lista.isEmpty()) return
        val id = System.nanoTime()
        destinos = destinos + Destino(id, "Aparato ${Envio.legible(leido.codigo)}", Destino.Fase.ENVIANDO)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val host = leido.host ?: throw IllegalStateException("Ese código no dice dónde está el aparato. Que lo enseñe otra vez.")
                Red.conectar(java.net.InetAddress.getByName(host), leido.puerto).use { socket ->
                    val final = Envio.Emisor(yo(), lista).atender(
                        socket.getInputStream(), socket.getOutputStream(), leido.codigo,
                        alConocer = { nombre ->
                            cambiar(id) { it.copy(nombre = nombre.ifBlank { it.nombre }) }
                            true
                        },
                        avance = { hechos, total -> cambiar(id) { it.copy(hechos = hechos, total = total) } },
                        inicia = true
                    )
                    cambiar(id) {
                        it.copy(fase = if (final == Envio.Final.ENVIADO) Destino.Fase.HECHO else Destino.Fase.RECHAZADO)
                    }
                }
            } catch (e: Exception) {
                yaEscaneados.remove(leido.codigo)
                cambiar(id) { it.copy(fase = Destino.Fase.FALLO, texto = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        red?.cerrar()
        red = null
        if (isFinishing) Thread { runCatching { carpeta.deleteRecursively() } }.start()
    }

    // --------------------------------------------------------------- qué

    private fun yo(): Aparato = IdentidadEnDisco(filesDir).leer(android.os.Build.MODEL.orEmpty().ifBlank { "Este aparato" }).yo

    private fun deUris(uris: List<Uri>): List<Pair<Envio.Elemento, File>> {
        val quien = yo()
        return uris.mapNotNull { uri ->
            runCatching {
                val nombre = Envio.nombreSano(nombreDe(uri))
                val destino = File(carpeta, "${System.nanoTime()}-$nombre")
                contentResolver.openInputStream(uri)?.use { entrada -> destino.outputStream().use { entrada.copyTo(it) } } ?: return@runCatching null
                Envio.Elemento(Envio.ARCHIVO, nombre, destino.length(), contentResolver.getType(uri), "archivo:${quien.id}:$nombre") to destino
            }.getOrNull()
        }
    }

    /** Un archivo que ya es de PixPin (del chat): se copia para mandarlo tal cual. */
    private fun deRuta(origen: File, nombre: String?): List<Pair<Envio.Elemento, File>> {
        if (!origen.exists()) return emptyList()
        val quien = yo()
        val bonito = Envio.nombreSano(nombre?.ifBlank { null } ?: origen.name)
        val destino = File(carpeta, "${System.nanoTime()}-$bonito")
        origen.copyTo(destino, overwrite = true)
        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(origen.extension.lowercase())
        return listOf(Envio.Elemento(Envio.ARCHIVO, bonito, destino.length(), mime, "archivo:${quien.id}:$bonito") to destino)
    }

    /**
     * **Un solo lienzo de un proyecto**: un `.pixpin` con esa hoja, y con qué proyecto es para que
     * al otro lado caiga en el mismo. Ver [Recepcion].
     */
    private fun deLienzo(proyectoId: String, hojaId: String): List<Pair<Envio.Elemento, File>> {
        val app = application as PixPinApp
        val p = sellado(proyectoId) ?: return emptyList()
        val h = p.hojas.firstOrNull { it.id == hojaId } ?: return emptyList()
        val conPdf = h.pagina != null
        val solo = p.copy(hojas = listOf(h), croquis = emptyList(), pdfOrigen = p.pdfOrigen.takeIf { conPdf }, pdfLimpio = p.pdfLimpio.takeIf { conPdf })
        val nombre = h.nombre.ifBlank { if (h.pagina != null) "Página ${h.pagina + 1}" else "Lienzo" }
        val destino = File(File(carpeta, "l-${System.nanoTime()}").apply { mkdirs() }, Envio.nombreSano("${p.nombre} - $nombre") + "." + com.forge.pixpin.motor.PaquetePixpin.EXTENSION)
        // **Con su chat**: el mensaje de ese lienzo, con su hora y su seña. Ver [ChatQueViaja].
        val chat = ChatQueViaja.preparar(p, ChatQueViaja.deLaHoja(this, p, h))
        com.forge.pixpin.motor.PaquetePixpin.escribir(this, solo, destino, chat = chat?.texto, adjuntosDelChat = chat?.adjuntos.orEmpty()) ?: return emptyList()
        val suMensaje = Recepcion.mensajeDeLaHoja(com.forge.pixpin.guardados.MensajesStore(this).leer(), p, h)
        val identidadDelProyecto = Recepcion.identidadDe(p)
        return listOf(
            Envio.Elemento(
                Envio.LIENZO, nombre, destino.length(), com.forge.pixpin.motor.PaquetePixpin.MIME_TYPE,
                identidad = "lienzo:${identidadDelProyecto.removePrefix("proyecto:")}:${h.origen ?: h.id}",
                proyecto = identidadDelProyecto, proyectoNombre = p.nombre, creado = suMensaje?.cuando ?: 0L,
                uid = Codigos.unico(h), codigoDeChat = suMensaje?.let { Codigos.deChat(it) }
            ) to destino
        )
    }

    /**
     * **El proyecto con sus códigos puestos** antes de empaquetarlo: lo que sale tiene que llevar los
     * tres, o quien lo recibe no podría ponerlo al día la próxima vez. Ver [Codigos].
     */
    private fun sellado(id: String): com.forge.pixpin.motor.Proyecto? {
        val app = application as PixPinApp
        runCatching { Red.disco(this).sellar() }
        app.proyectos.recargar()
        return app.proyectos.porId(id)
    }

    private fun deProyecto(id: String): List<Pair<Envio.Elemento, File>> {
        val p = sellado(id) ?: return emptyList()
        val destino = File(carpeta, Envio.nombreSano(p.nombre) + "." + com.forge.pixpin.motor.PaquetePixpin.EXTENSION)
        // **El proyecto con su chat entero**: el chat manda. Ver [ChatQueViaja].
        val chat = ChatQueViaja.preparar(p, ChatQueViaja.delProyecto(this, p))
        com.forge.pixpin.motor.PaquetePixpin.escribir(
            this, p, destino,
            croquisDe = { c -> com.forge.pixpin.croquis3d.Croquis3DAlmacen.jsonDe(this, c) },
            chat = chat?.texto, adjuntosDelChat = chat?.adjuntos.orEmpty()
        ) ?: return emptyList()
        return listOf(
            Envio.Elemento(
                Envio.PROYECTO, p.nombre, destino.length(), com.forge.pixpin.motor.PaquetePixpin.MIME_TYPE, Recepcion.identidadDe(p),
                creado = p.creado, uid = Codigos.unico(p), aparato = p.aparato
            ) to destino
        )
    }

    private suspend fun preparar(que: () -> List<Pair<Envio.Elemento, File>>) {
        estado = Estado.Preparando
        val lista = withContext(Dispatchers.IO) { runCatching(que).getOrDefault(emptyList()) }
        if (lista.isEmpty()) { estado = Estado.Fallo("No se pudo preparar lo que querías enviar."); return }
        cosas = lista
        abrirLaPuerta(lista)
    }

    // ------------------------------------------------------------- la red

    private suspend fun abrirLaPuerta(lista: List<Pair<Envio.Elemento, File>>) {
        val codigo = Envio.nuevoCodigo()
        val quien = yo()
        val etiqueta = withContext(Dispatchers.Default) { Envio.etiqueta(codigo) }
        val r = Red(this).also { red = it }
        var malos = 0
        r.escuchar(0, enParalelo = true) { socket, entrada ->
            if (red !== r) return@escuchar
            val id = System.nanoTime()
            try {
                val final = Envio.Emisor(quien, lista).atender(
                    entrada, socket.getOutputStream(), codigo,
                    alConocer = { nombre ->
                        val como = nombre.ifBlank { "Un aparato" }
                        runOnUiThread { destinos = destinos + Destino(id, como, Destino.Fase.PIDIENDO) }
                        val espera = java.util.concurrent.CompletableFuture<Boolean>()
                        aprobaciones[id] = espera
                        // Aquí se para este hilo hasta que se toque «Enviar» o «No». Media hora es
                        // lo que aguanta el socket; pasada, se cae solo y el otro lo verá.
                        val si = runCatching { espera.get(29, java.util.concurrent.TimeUnit.MINUTES) }.getOrDefault(false)
                        aprobaciones.remove(id)
                        if (si) cambiar(id) { it.copy(fase = Destino.Fase.ENVIANDO) }
                        si
                    },
                    avance = { hechos, total -> cambiar(id) { it.copy(fase = Destino.Fase.ENVIANDO, hechos = hechos, total = total) } }
                )
                cambiar(id) {
                    it.copy(fase = when (final) {
                        Envio.Final.ENVIADO -> Destino.Fase.HECHO
                        Envio.Final.RECHAZADO -> Destino.Fase.RECHAZADO
                        Envio.Final.SIN_APROBAR -> Destino.Fase.SIN_APROBAR
                    })
                }
                // **La puerta sigue abierta**: el mismo código vale para el siguiente aparato,
                // hasta que se cierre esta pantalla.
            } catch (e: Canal.CodigoDistinto) {
                malos++
                if (malos >= Envio.INTENTOS) {
                    estado = Estado.Fallo("Alguien probó varias veces con un código equivocado, así que este código ya no vale. Vuelve a enviar para sacar otro.")
                    runOnUiThread { r.cerrar() }
                } else (estado as? Estado.Esperando)?.let { estado = it.copy(intentosMalos = malos) }
            } catch (e: Exception) {
                // Que se caiga uno no tumba el envío: los demás siguen.
                aprobaciones.remove(id)
                if (destinos.any { it.id == id }) {
                    cambiar(id) { it.copy(fase = Destino.Fase.FALLO, texto = e.message ?: e.javaClass.simpleName) }
                } else {
                    estado = Estado.Fallo("Se cortó: ${e.message ?: e.javaClass.simpleName}")
                }
            }
        }
        r.anunciar(Envio.TIPO, "PixPin ${quien.nombre}", mapOf("g" to etiqueta, "n" to quien.nombre.take(40)))
        val qr = withContext(Dispatchers.Default) { Qr.imagen(Envio.textoDelQr(codigo, Red.miDireccion(this@EnviarActivity), r.puerto)) }
        if (estado is Estado.Preparando) estado = Estado.Esperando(codigo, qr)
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
                    Text("Enviar por Wi-Fi", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(8.dp))
                LoQueSeEnvia()
                Spacer(Modifier.height(16.dp))
                LosAparatos()
                ElEscaner()
                when (val e = estado) {
                    Estado.Preparando -> Cargando("Preparando…")
                    is Estado.Esperando -> Esperando(e)
                    is Estado.Fallo -> Final("No se pudo", e.texto, bien = false)
                }
            }
        }
    }

    /**
     * **Los aparatos que se han conectado con el código**, y el botón de aprobar a cada uno.
     *
     * El código **no caduca al primer envío**: mientras esta pantalla esté abierta puede
     * conectarse quien quiera, y se le manda solo si se aprueba. Ver [Destino].
     */
    @Composable
    private fun LosAparatos() {
        if (destinos.isEmpty()) return
        Caja {
            Text(
                if (destinos.size == 1) "Un aparato" else "${destinos.size} aparatos",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            for (d in destinos) {
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(d.nombre, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            when (d.fase) {
                                Destino.Fase.PIDIENDO -> "Quiere recibir lo que envías"
                                Destino.Fase.ENVIANDO ->
                                    if (d.total > 0) "Enviando · ${tamanoLegible(d.hechos)} de ${tamanoLegible(d.total)}"
                                    else "Esperando a que lo acepte…"
                                Destino.Fase.HECHO -> "Enviado"
                                Destino.Fase.RECHAZADO -> "No lo aceptó"
                                Destino.Fase.SIN_APROBAR -> "No aprobado"
                                Destino.Fase.FALLO -> "Se cortó: ${d.texto}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (d.fase == Destino.Fase.HECHO) VERDE else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (d.fase == Destino.Fase.ENVIANDO && d.total > 0) {
                            Spacer(Modifier.height(4.dp))
                            LinearProgressIndicator(
                                progress = { d.hechos.toFloat() / d.total },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    if (d.fase == Destino.Fase.PIDIENDO) {
                        androidx.compose.material3.TextButton(onClick = { responder(d.id, false) }) { Text("No") }
                        Spacer(Modifier.width(4.dp))
                        Button(onClick = { responder(d.id, true) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp)) {
                            Text("Enviar")
                        }
                    }
                }
            }
            if (destinos.count { it.fase == Destino.Fase.PIDIENDO } > 1) {
                Button(
                    onClick = { destinos.filter { it.fase == Destino.Fase.PIDIENDO }.forEach { responder(it.id, true) } },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                ) { Text("Enviar a todos los que esperan") }
            }
        }
        Spacer(Modifier.height(16.dp))
    }

    /**
     * **La cámara para escanear a quien recibe.** El otro camino —enseñar el código y que lo
     * tecleen— sigue debajo: son dos formas del mismo envío y se eligen sobre la marcha.
     */
    @Composable
    private fun ElEscaner() {
        if (cosas.isEmpty()) return
        if (!escaneando) {
            androidx.compose.material3.OutlinedButton(
                onClick = {
                    escaneando = true
                    if (!camaraPermitida) pedirCamara.launch(Manifest.permission.CAMERA)
                },
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Icon(Icons.Filled.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Escanear a quien recibe")
            }
            Spacer(Modifier.height(16.dp))
            return
        }
        Text("Pasa la cámara por el código de cada uno", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(
            "En el otro aparato: Recibir por Wi-Fi → «Enseñar mi código para que me envíen». Puedes escanear varios seguidos.",
            style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier.widthIn(max = 320.dp).fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (camaraPermitida) {
                Qr.Escaner(Modifier.fillMaxSize()) { texto ->
                    val leido = Envio.leerQr(texto) ?: return@Escaner
                    // Solo los que **esperan recibir**: el QR del otro sentido es para escanearlo
                    // desde «Recibir», no desde aquí.
                    if (leido.esperaRecibir) mandarA(leido)
                }
            } else {
                Text("Hace falta permiso de cámara.", textAlign = TextAlign.Center)
            }
        }
        Spacer(Modifier.height(8.dp))
        androidx.compose.material3.TextButton(onClick = { escaneando = false }) { Text("Dejar de escanear") }
        Spacer(Modifier.height(16.dp))
    }

    /** La respuesta a un aparato que espera: suelta el hilo que lo atiende. */
    private fun responder(id: Long, si: Boolean) {
        aprobaciones[id]?.complete(si)
        if (!si) destinos = destinos.map { if (it.id == id) it.copy(fase = Destino.Fase.SIN_APROBAR) else it }
    }

    @Composable
    private fun LoQueSeEnvia() {
        if (cosas.isEmpty()) return
        Caja {
            Text(if (cosas.size == 1) "Vas a enviar" else "Vas a enviar ${cosas.size} cosas", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            for ((e, _) in cosas.take(6)) {
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when (e.tipo) { Envio.PROYECTO -> "Proyecto «${e.nombre}»"; Envio.LIENZO -> "Lienzo «${e.nombre}»"; else -> e.nombre },
                        modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium
                    )
                    Text(tamanoLegible(e.bytes), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (cosas.size > 6) Text("y ${cosas.size - 6} más", style = MaterialTheme.typography.bodySmall)
        }
    }

    @Composable
    private fun Esperando(e: Estado.Esperando) {
        Text("En el otro teléfono: PixPin → Sincronizar → Recibir", style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(14.dp))
        Box(
            Modifier.widthIn(max = 300.dp).fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(18.dp)).background(Color.White).padding(14.dp)
        ) {
            Image(e.qr.asImageBitmap(), contentDescription = "Código QR del envío", modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.height(14.dp))
        Text("o escribe este código", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(Envio.legible(e.codigo), fontFamily = FontFamily.Monospace, fontSize = 40.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Caja {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Filled.Wifi, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("Los dos en la misma Wi-Fi", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Si no hay Wi-Fi, uno puede activar su zona Wi-Fi y el otro conectarse a ella. No se usa internet.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        if (e.intentosMalos > 0) {
            Spacer(Modifier.height(8.dp))
            Text("Alguien probó con un código equivocado (${e.intentosMalos} de ${Envio.INTENTOS}).", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                "Esperando… Pueden conectarse varios aparatos; a cada uno le das tú al botón. El código deja de valer al cerrar esta pantalla.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )
        }
    }

    @Composable
    private fun Cargando(texto: String) {
        Spacer(Modifier.height(20.dp))
        CircularProgressIndicator()
        Spacer(Modifier.height(14.dp))
        Text(texto, textAlign = TextAlign.Center)
    }

    @Composable
    private fun Final(titulo: String, texto: String, bien: Boolean) {
        Spacer(Modifier.height(20.dp))
        if (bien) Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = VERDE, modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(10.dp))
        Text(titulo, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(texto, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        Button(onClick = { finish() }, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Cerrar") }
    }

    // -------------------------------------------------------------- útiles

    @Suppress("DEPRECATION")
    private fun uriDe(i: Intent): Uri? = i.getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun urisDe(i: Intent): List<Uri> = i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()

    private fun nombreDe(uri: Uri): String = runCatching {
        contentResolver.query(uri, null, null, null, null)?.use { fila ->
            val i = fila.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && fila.moveToFirst()) fila.getString(i) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "archivo"

    companion object {
        private const val EXTRA_PROYECTO = "enviar_proyecto"
        private const val EXTRA_ELEGIR = "enviar_elegir"
        private const val EXTRA_HOJA = "enviar_hoja"
        private const val EXTRA_HOJAS = "enviar_hojas"
        private const val EXTRA_RUTA = "enviar_ruta"
        private const val EXTRA_NOMBRE = "enviar_nombre"

        /** Un solo lienzo (una hoja) de un proyecto. */
        fun enviarLienzo(context: Context, proyecto: String, hoja: String) {
            context.startActivity(Intent(context, EnviarActivity::class.java).putExtra(EXTRA_PROYECTO, proyecto).putExtra(EXTRA_HOJA, hoja))
        }

        /**
         * **Solo los lienzos marcados** de un proyecto, cada uno como un lienzo suelto que al otro
         * lado cae en el mismo proyecto. Es lo que tiene que pasar al marcar lienzos en Proyectos y
         * darle a Wi-Fi: antes se mandaba el proyecto entero (usuario, 15-sep-2026).
         */
        fun enviarLienzos(context: Context, proyecto: String, hojas: Collection<String>) {
            if (hojas.size == 1) return enviarLienzo(context, proyecto, hojas.first())
            context.startActivity(Intent(context, EnviarActivity::class.java).putExtra(EXTRA_PROYECTO, proyecto).putExtra(EXTRA_HOJAS, hojas.toTypedArray()))
        }

        /** Un archivo de PixPin, tal cual. */
        fun enviarArchivo(context: Context, ruta: String, nombre: String?) {
            context.startActivity(Intent(context, EnviarActivity::class.java).putExtra(EXTRA_RUTA, ruta).putExtra(EXTRA_NOMBRE, nombre))
        }

        fun enviarProyecto(context: Context, id: String) {
            context.startActivity(Intent(context, EnviarActivity::class.java).putExtra(EXTRA_PROYECTO, id))
        }

        fun elegirArchivos(context: Context) {
            context.startActivity(Intent(context, EnviarActivity::class.java).putExtra(EXTRA_ELEGIR, true))
        }
    }
}
