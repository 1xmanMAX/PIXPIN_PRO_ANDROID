package com.forge.pixpin.sincro.nube

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.forge.pixpin.sincro.CompartidosEnDisco
import com.forge.pixpin.sincro.IdentidadEnDisco
import com.forge.pixpin.sincro.Red
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * **La nube de los chats compartidos, en el teléfono** (2-nov-2026, `sincronizar/nube.rs` del PC).
 *
 * - **Los ajustes**: la dirección de la web (`sincro/nube/ajustes.json`, que no viaja) y la llave
 *   `pxn_…` del aparato, **cifrada con el Android Keystore** en `noBackupFilesDir/claves/nube.clave`
 *   (como las de la IA): nunca en claro ni en la copia de seguridad.
 * - **El bucle**: cada 3 s mira si cambió algo; con 4 s quieto, una vuelta de cada chat de la nube
 *   (solo sube lo que cambió). Otro bucle espera las novedades con la conexión abierta
 *   (`novedades?espera=25`) y baja en cuanto otro sube. Sin red, espera creciente.
 * - **Lo que ve la pantalla**: el estado de cada chat ([estados]) para la nubecita de la lista.
 */
object NubeEnMarcha {
    enum class EstadoNube { SUBIENDO, AL_DIA, SIN_RED, LLAVE }

    private const val MIRAR_CADA = 3_000L
    private const val QUIETO = 4_000L
    private const val BAJAR_CADA = 10 * 60_000L

    private val _estados = MutableStateFlow<Map<String, EstadoNube>>(emptyMap())
    val estados: StateFlow<Map<String, EstadoNube>> = _estados
    /** Sube cada vez que cambia qué chats van por la nube (la lista lo mira). */
    val version = MutableStateFlow(0)

    private val despierta = Channel<Unit>(Channel.CONFLATED)
    private val pendientes = java.util.Collections.synchronizedSet(HashSet<String>())
    @Volatile private var arrancada = false

    // ------------------------------------------------------------------ ajustes

    @Serializable
    private data class Ajustes(val direccion: String = "")

    private fun rutaAjustes(c: Context) = File(carpetaDeLaNube(c.filesDir), "ajustes.json")

    fun direccion(c: Context): String = runCatching { Nube.JSON.decodeFromString(Ajustes.serializer(), rutaAjustes(c).readText()).direccion }.getOrDefault("")

    private const val ALIAS = "pixpin-nube-llave-v1"
    private fun ficheroLlave(c: Context) = File(c.noBackupFilesDir, "claves/nube.clave")

    private fun claveDelKeystore(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return g.generateKey()
    }

    @Synchronized
    private fun llave(c: Context): String? = runCatching {
        val b = ficheroLlave(c).takeIf { it.isFile }?.readBytes() ?: return null
        if (b.size < 3 || b[0].toInt() != 1) return null
        val n = b[1].toInt()
        val cif = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, claveDelKeystore(), GCMParameterSpec(128, b.copyOfRange(2, 2 + n))) }
        String(cif.doFinal(b, 2 + n, b.size - 2 - n), Charsets.UTF_8).takeIf { it.isNotBlank() }
    }.getOrNull()

    fun llaveALaVista(c: Context): String? = llave(c)?.let(Nube::enmascarar)

    /** **Guarda los ajustes**: la dirección y, si viene, la llave (cifrada). Lanza con el campo malo. */
    @Synchronized
    fun guardar(c: Context, direccion: String, llave: String?) {
        val d = direccion.trim().trimEnd('/')
        require(d.isEmpty() || Nube.direccionValida(d)) { "direccion" }
        val l = llave?.trim().orEmpty()
        if (l.isNotEmpty()) {
            require(Nube.llaveValida(l)) { "llave" }
            val cif = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, claveDelKeystore()) }
            val datos = cif.doFinal(l.toByteArray(Charsets.UTF_8))
            val iv = cif.iv
            escribirAtomico(ficheroLlave(c), byteArrayOf(1, iv.size.toByte()) + iv + datos)
        }
        escribirAtomico(rutaAjustes(c), Nube.JSON.encodeToString(Ajustes.serializer(), Ajustes(d)).toByteArray())
        despertar()
    }

    fun olvidarLlave(c: Context) { synchronized(this) { ficheroLlave(c).delete() }; despertar() }

    private fun conexion(c: Context): Pair<String, String>? {
        val d = direccion(c).ifEmpty { return null }
        return d to (llave(c) ?: return null)
    }

    fun configurada(c: Context) = conexion(c) != null

    // ---------------------------------------------------------------- transporte

    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).writeTimeout(90, TimeUnit.SECONDS).build()
    }

    /** **OkHttp**: el TLS y el proxy del sistema. */
    val okhttp = Transporte { metodo, url, cabeceras, cuerpo, segundos ->
        val tipo = cabeceras.firstOrNull { it.first == "Content-Type" }?.second?.toMediaTypeOrNull()
        val b = Request.Builder().url(url)
        for ((k, v) in cabeceras) b.header(k, v)
        val conCuerpo = metodo == "PUT" || metodo == "PATCH" || metodo == "POST"
        b.method(metodo, if (conCuerpo) cuerpo.toRequestBody(tipo) else null)
        val cliente = http.newBuilder().readTimeout(segundos + 5, TimeUnit.SECONDS).callTimeout(segundos + 30, TimeUnit.SECONDS).build()
        cliente.newCall(b.build()).execute().use { r -> Contestado(r.code, r.body?.bytes() ?: ByteArray(0)) }
    }

    // ------------------------------------------------------------------- modos

    fun modo(c: Context, chat: String): Modo = ChatsEnLaNube.leer(c.filesDir).modo(chat)

    /** Cambia por dónde va un chat compartido. */
    fun ponerModo(c: Context, chat: String, m: Modo) {
        ChatsEnLaNube.cambiar(c.filesDir) { it.poner(chat, m) }
        if (m == Modo.LOCAL) _estados.value = _estados.value - chat
        version.value++
        despertar()
    }

    /** El estado del chat [chat] en la nube, o null si no va por la nube. */
    fun estadoDe(c: Context, chat: String): EstadoNube? {
        if (modo(c, chat) != Modo.NUBE) return null
        return _estados.value[chat] ?: if (configurada(c)) EstadoNube.SUBIENDO else EstadoNube.SIN_RED
    }

    fun despertar() { despierta.trySend(Unit) }

    private fun ponerEstado(chat: String, e: EstadoNube) {
        if (_estados.value[chat] != e) _estados.value = _estados.value + (chat to e)
    }

    // --------------------------------------------------------------- el bucle

    /** Presenta la persona de este aparato si la nube aún no la conoce. */
    private fun presentarse(c: Context, n: ClienteNube, h: Hola) {
        val yo = runCatching { CompartidosEnDisco(c.filesDir).yo(IdentidadEnDisco(c.filesDir).leer()) }.getOrNull() ?: return
        if (h.persona == yo.id) return
        runCatching { n.presentar(yo.id, yo.clave, Nube.firmaDeYo(yo.llaves, h.aparatoId, yo.id)) }
    }

    /** Los compartidos que otros pusieron en la nube y donde estoy: entran solos. */
    private fun adoptar(c: Context, n: ClienteNube) {
        for (ch in n.chats()) {
            if (ch.compartido != true) continue
            VueltaNube.conocerEspacio(c.filesDir, ch.chat, ch.espacio)
            var nuevo = false
            ChatsEnLaNube.cambiar(c.filesDir) { l -> l.adoptar(ch.chat)?.also { nuevo = true } ?: l }
            if (nuevo) { pendientes += ch.chat; version.value++ }
        }
    }

    /** **Prueba la conexión** («Probar» de Sincronizar → Nube): el espacio y la persona, o el error. */
    fun probar(c: Context): Result<Pair<String, String?>> = runCatching {
        val (d, l) = conexion(c) ?: error("sin-configurar")
        val n = ClienteNube(d, l, okhttp)
        val h = n.hola()
        presentarse(c, n, h)
        despertar()
        h.espacio to h.persona
    }

    /** La firma barata de lo que puede cambiar: los mensajes, los proyectos y lo anotado. */
    private fun firma(c: Context): Long {
        var f = 0L
        for (r in listOf("guardados.jsonl", "proyectos/proyectos.json")) File(c.filesDir, r).let { f = f * 31 + it.lastModified() + it.length() }
        for (dir in listOf("pins/draw", "tablas", "guardados")) (File(c.filesDir, dir).listFiles() ?: emptyArray()).forEach { f += it.lastModified() xor it.length() }
        return f
    }

    /** **Arranca la nube** (al arrancar la aplicación). Una sola vez. */
    @Synchronized
    fun instalar(contexto: Context) {
        if (arrancada) return
        arrancada = true
        val c = contexto.applicationContext
        com.forge.pixpin.sincro.RedDelTelefono.instalar(c)
        val alcance = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        // **Al volver la red, enseguida** (6-nov-2026): sin red no se reintenta; se espera a que Android la dé.
        alcance.launch {
            var antes = com.forge.pixpin.sincro.RedDelTelefono.hayRed
            com.forge.pixpin.sincro.RedDelTelefono.tipo.collect { t ->
                val ahora = t != com.forge.pixpin.sincro.RedDelTelefono.Tipo.NINGUNA
                if (ahora && !antes) despertar()
                antes = ahora
            }
        }
        alcance.launch { bucle(c) }
        alcance.launch { novedades(c) }
    }

    private suspend fun bucle(c: Context) {
        var saludo: Pair<String, Boolean>? = null
        var firmaVista = 0L
        var desde = 0L
        var subida = Long.MIN_VALUE
        var bajado = System.currentTimeMillis()
        var fallos = 0
        while (true) {
            com.forge.pixpin.sincro.RedDelTelefono.esperarRed()
            val espera = if (fallos > 0) minOf(5 * 60_000L, 2_000L shl minOf(fallos, 7)) else MIRAR_CADA
            if (withTimeoutOrNull(espera) { despierta.receive() } != null) fallos = 0
            val con = conexion(c)
            if (con == null) { saludo = null; continue }
            val (direccion, llave) = con
            val chats = ChatsEnLaNube.leer(c.filesDir).chats
            val n = ClienteNube(direccion, llave, okhttp)
            if (saludo?.first != direccion) {
                try {
                    val h = n.hola()
                    presentarse(c, n, h)
                    runCatching { adoptar(c, n) }
                    saludo = direccion to h.sabe(Nube.DELTAS)
                } catch (e: FalloNube) {
                    fallos++
                    val est = if (e is FalloNube.Llave) EstadoNube.LLAVE else EstadoNube.SIN_RED
                    for (ch in chats) ponerEstado(ch, est)
                    continue
                }
            }
            if (chats.isEmpty()) continue
            val ahora = System.currentTimeMillis()
            val f = firma(c)
            if (f != firmaVista) { firmaVista = f; desde = ahora }
            val quieto = ahora - desde >= QUIETO
            val tocaBajar = ahora - bajado > BAJAR_CADA
            if (tocaBajar) bajado = ahora
            val cambio = f != subida && quieto
            val d = Red.disco(c)
            val vuelta = VueltaNube(d, n, saludo?.second == true, SinConexion(c.filesDir), ahora)
            var fallo = false
            for (chat in chats) {
                val pendiente = pendientes.remove(chat)
                if (!(pendiente || tocaBajar || cambio || EstadoDeChat.leer(c.filesDir, chat).revision == 0L)) continue
                ponerEstado(chat, EstadoNube.SUBIENDO)
                try {
                    vuelta.deChat(chat, pendiente || tocaBajar)
                    ponerEstado(chat, EstadoNube.AL_DIA)
                } catch (e: FalloNube) {
                    fallo = true
                    ponerEstado(chat, if (e is FalloNube.Llave) EstadoNube.LLAVE else EstadoNube.SIN_RED)
                    if (e is FalloNube.Llave) saludo = null
                } catch (e: Exception) {
                    fallo = true
                    ponerEstado(chat, EstadoNube.SIN_RED)
                }
            }
            if (fallo) fallos++ else { fallos = 0; if (cambio) subida = firma(c) }
        }
    }

    private suspend fun novedades(c: Context) {
        var fallos = 0
        while (true) {
            com.forge.pixpin.sincro.RedDelTelefono.esperarRed()
            val con = conexion(c)
            if (con == null) { delay(5_000); continue }
            val (direccion, llave) = con
            if (ChatsEnLaNube.leer(c.filesDir).chats.isEmpty() && fallos == 0) { delay(15_000) }
            try {
                val n = ClienteNube(direccion, llave, okhttp)
                val desde = ChatsEnLaNube.leer(c.filesDir).seq
                val nv = n.novedades(desde, 25)
                val mios = ChatsEnLaNube.leer(c.filesDir).chats
                var hay = false
                for (x in nv.cambios) {
                    if (x.chat in mios) { pendientes += x.chat; hay = true }
                    else if (x.compartido == true && x.borrado != true) {
                        VueltaNube.conocerEspacio(c.filesDir, x.chat, x.espacio)
                        var nuevo = false
                        ChatsEnLaNube.cambiar(c.filesDir) { l -> l.adoptar(x.chat)?.also { nuevo = true } ?: l }
                        if (nuevo) { pendientes += x.chat; hay = true; version.value++ }
                    }
                }
                if (nv.reiniciar) { pendientes += mios; hay = true }
                ChatsEnLaNube.cambiar(c.filesDir) { it.copy(seq = nv.seq) }
                if (hay) despertar()
                fallos = 0
            } catch (e: Exception) {
                fallos++
                // Sin red, a esperar que vuelva (arriba); con red, la espera creciente de siempre.
                if (com.forge.pixpin.sincro.RedDelTelefono.hayRed) delay(minOf(5 * 60_000L, 2_000L shl minOf(fallos, 7)))
            }
        }
    }
}
