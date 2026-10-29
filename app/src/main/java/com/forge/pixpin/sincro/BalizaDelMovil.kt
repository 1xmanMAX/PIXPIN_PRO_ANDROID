package com.forge.pixpin.sincro

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * **La baliza de este teléfono** (28-oct-2026, §11.8 de `docs/movil-como-camara.md` del PC; lo del
 * PC es `apps/pixpin/src/sincronizar/baliza.rs`). El cable y las cuentas son [Baliza]; esto son los
 * dos hilos y cuándo viven.
 *
 * **Cuándo**: mientras haya algún motivo ([VISTA]: PixPin a la vista, lo dice [Presencia];
 * [GRABANDO]: «Grabar clase» o la reunión presencial; [SUBIDA]: el servicio que sube las fotos al
 * PC). Sin ninguno, los hilos acaban, se cierran los sockets y se suelta el
 * `WifiManager.MulticastLock` (sin él, muchos teléfonos tiran la difusión con la pantalla apagada;
 * con él, se gasta batería: solo mientras se oye).
 *
 * - **`pixpin-baliza`** manda: una **con pregunta** al abrir PixPin y al volver la red; cada
 *   [Baliza.CADA_EN_REPOSO_MS] sin grabar; **cada segundo y al momento** al empezar, pausar, seguir o
 *   acabar cuando este teléfono graba ([GrabacionParaLaBaliza]), con `ms_actual` y el id. Por
 *   difusión y multidifusión en cada red, directa a la última dirección de cada aparato del grupo y,
 *   grabando sin oír a nadie, a la red entera ([Ritmo.tocaBarrer], §11.4). Contesta al momento, y
 *   directa, a quien pregunta.
 * - **`pixpin-baliza-oye`** oye en UDP [Baliza.PUERTO]: comprueba ([Baliza.leer], [Oidas]) y hace lo
 *   de §11.5 ([oido]): apunta la dirección **del datagrama** (un cambio de DHCP se arregla en un
 *   segundo), lo da por presente a la sincronización automática y, si el PC graba, enseña la tira de
 *   la cámara y abre la conexión caliente ([CamaraDelPc.alOirBaliza], [ConexionCaliente]).
 *
 * **El modo prioritario** ([grabandoAlguien]): este teléfono o uno del grupo oído con «grabando» hace
 * menos de [Baliza.VIGENCIA_GRABANDO_MS]. Ver [ModoPrioritario].
 */
object BalizaDelMovil {
    const val VISTA = "vista"
    const val GRABANDO = "grabando"
    const val SUBIDA = "subida"
    private const val TAG = "PixPinBaliza"

    /** Lo oído de un aparato del grupo: su baliza, de qué IP llegó y cuándo (`elapsedRealtime`). */
    data class Oido(val baliza: Baliza, val ip: String, val cuando: Long)

    /** Lo que se sabe del grupo, para el que oye: lo pone el que manda. */
    private class DelGrupo(val clave: ByteArray, val yo: String, val miembros: Map<String, String>)

    @Volatile private var app: Application? = null
    private val motivos: MutableSet<String> = ConcurrentHashMap.newKeySet()
    @Volatile private var grabacion: GrabacionParaLaBaliza? = null
    private val cambio = AtomicBoolean(false)
    private val preguntar = AtomicBoolean(true)
    private val redesNuevas = AtomicBoolean(false)
    private val oyenteDeNuevo = AtomicBoolean(false)
    /** Acabó la grabación: el motivo [GRABANDO] se suelta tras mandar la última («ya no grabo»). */
    private val soltarGrabando = AtomicBoolean(false)
    private val oidos = ConcurrentHashMap<String, Oido>()
    /** Quién ya tiene el anuncio de la grabación de aquí, y en qué dirección (§11.5 punto 5). */
    private val avisados = ConcurrentHashMap<String, String>()
    private val aContestar = LinkedBlockingQueue<Long>()
    private val despertar = Semaphore(0)
    @Volatile private var mandador: Thread? = null
    @Volatile private var oidor: Thread? = null
    @Volatile private var grupo: DelGrupo? = null
    @Volatile private var redesActuales: List<Interfaz> = emptyList()
    private val claves = ConcurrentHashMap<String, ByteArray>()

    fun instalar(aplicacion: Application) {
        app = aplicacion
        // **Al volver la red** (o cambiar de dirección): se vuelven a leer las redes y se pregunta.
        runCatching {
            aplicacion.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = cambioDeRed()
                    override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = cambioDeRed()
                }
            )
        }
    }

    /** PixPin a la vista (lo dice [Presencia]). Al abrir, una con pregunta. */
    fun aLaVista(si: Boolean) {
        if (si) preguntar.set(true)
        motivo(VISTA, si)
    }

    /** Un servicio que debe tener la baliza viva ([SUBIDA]) arranca o para. */
    fun servicio(nombre: String, vivo: Boolean) = motivo(nombre, vivo)

    /** **Este teléfono empieza a grabar**: la baliza sale ya, y cada segundo. */
    fun empezo(g: GrabacionParaLaBaliza) {
        grabacion = g
        soltarGrabando.set(false)
        cambio.set(true)
        motivo(GRABANDO, true)
        despertar.release()
    }

    /** Se pausó o siguió: la baliza sale al momento. */
    fun cambio() {
        cambio.set(true)
        despertar.release()
    }

    /** **Acabó** (terminada o tirada): sale al momento sin «grabando», y se suelta. */
    fun acabo(g: GrabacionParaLaBaliza) {
        if (grabacion !== g) return
        grabacion = null
        cambio.set(true)
        soltarGrabando.set(true)
        despertar.release()
        SincronizacionAutomatica.despertarYa()
    }

    /** **Ya está guardada** en el chat de [proyecto] (null, el general): sale primero (§11.6). */
    fun guardada(proyecto: String?) = SincronizacionAutomatica.grabacionGuardada(proyecto ?: Disco.GENERAL)

    /** Volvió la red o cambió de dirección. */
    fun cambioDeRed() {
        redesNuevas.set(true)
        preguntar.set(true)
        despertar.release()
    }

    fun grabandoAqui(): Boolean = grabacion != null

    /** Los aparatos del grupo que graban ahora, según su baliza. */
    fun otrosGrabando(ahora: Long = SystemClock.elapsedRealtime()): List<Oido> =
        oidos.values.filter { it.baliza.graba && ahora - it.cuando < Baliza.VIGENCIA_GRABANDO_MS }

    /** **El modo prioritario**: este teléfono o uno del grupo graba. */
    fun grabandoAlguien(): Boolean = grabandoAqui() || otrosGrabando().isNotEmpty()

    /** El aparato del grupo que graba (el último oído), o null. */
    fun quienGraba(): Oido? = otrosGrabando().maxByOrNull { it.cuando }

    private fun oidoHace(ahora: Long): Long? = oidos.values.minOfOrNull { ahora - it.cuando }

    private fun motivo(nombre: String, si: Boolean) {
        if (app == null) return
        if (si) motivos.add(nombre) else motivos.remove(nombre)
        if (motivos.isNotEmpty()) arrancar() else despertar.release()
    }

    @Synchronized private fun arrancar() {
        if (mandador?.isAlive != true) mandador = Thread(::mandar, "pixpin-baliza").apply { isDaemon = true; start() }
        if (oidor?.isAlive != true) oidor = Thread(::oir, "pixpin-baliza-oye").apply { isDaemon = true; start() }
    }

    private fun leerGrupo(c: Context): DelGrupo? = runCatching {
        val id = Disco(c.filesDir).identidad.leer()
        val codigo = id.codigo?.takeIf { id.enGrupo && it.isNotBlank() } ?: return null
        DelGrupo(claves.getOrPut(codigo) { Grupo.clave(codigo) }, id.yo.id, id.miembros.associate { it.id to it.nombre })
    }.getOrNull()

    /** Las últimas direcciones conocidas de los demás del grupo (IPv4). */
    private fun conocidas(c: Context, g: DelGrupo): List<Long> =
        Disco(c.filesDir).direcciones().filter { (id, _) -> id != g.yo && id in g.miembros }
            .mapNotNull { (_, d) -> d.first.takeIf { IPV4.matches(it) }?.let(Ipv4::de) }

    private val IPV4 = Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$""")

    // ------------------------------------------------------------ el que manda

    private fun mandar() {
        val c = app ?: return
        val ritmo = Ritmo()
        var secuencia = 0L
        var leido = 0L
        var redesLeidas = 0L
        var redesPedidas: List<Interfaz>? = null
        var emisor: EmisorDeBalizas? = null
        var grababa = false
        // Lo de la grabación de este teléfono: desde cuándo, si se oyó a alguien y si ya se dijo.
        var desde: Long? = null
        var oido = false
        var diagnosticada = false
        try {
            while (motivos.isNotEmpty()) {
                val ahora = SystemClock.elapsedRealtime()
                if (leido == 0L || ahora - leido > 2_000) { leido = ahora; grupo = leerGrupo(c) }
                val nuevas = redesNuevas.getAndSet(false)
                if (nuevas || redesLeidas == 0L || ahora - redesLeidas > 30_000) {
                    redesLeidas = ahora
                    val r = RedesDelAparato.leer()
                    if (nuevas || emisor == null || r != redesPedidas) {
                        emisor?.cerrar()
                        emisor = runCatching { EmisorDeBalizas.abrir(Baliza.PUERTO, r) }.getOrNull()
                        redesPedidas = r
                        redesActuales = r
                        // Red nueva: se pregunta otra vez quién está, y el oyente se apunta en las nuevas.
                        preguntar.set(true)
                        oyenteDeNuevo.set(true)
                        Log.i(TAG, "redes: " + r.joinToString { Ipv4.texto(it.ip) + "/" + it.prefijo })
                    }
                }
                val local = grabacion
                val graba = local != null
                val huboCambio = cambio.getAndSet(false) || graba != grababa
                grababa = graba
                val g = grupo
                val e = emisor
                if (g == null || e == null) {
                    // Sin grupo no hay clave con que firmar ni a quién llamar.
                    aContestar.clear()
                    if (soltarGrabando.getAndSet(false) && grabacion == null) motivos.remove(GRABANDO)
                    esperar(1_000)
                    continue
                }
                val miPuerto = runCatching { Presencia.red.puerto }.getOrDefault(0)
                fun paquete(pregunta: Boolean): ByteArray {
                    secuencia = (secuencia + 1) and 0xffff_ffffL
                    var banderas = if (pregunta) Baliza.PREGUNTA else 0
                    if (local != null) {
                        banderas = banderas or Baliza.GRABANDO
                        if (local.enPausa) banderas = banderas or Baliza.EN_PAUSA
                        if (local.camara) banderas = banderas or Baliza.CAMARA
                    }
                    return Baliza(
                        etiqueta = Baliza.etiquetaDe(g.clave), id = g.yo, puerto = miPuerto,
                        reloj = System.currentTimeMillis(), secuencia = secuencia, banderas = banderas,
                        grabacion = local?.id.orEmpty(), msActual = local?.msActual() ?: 0L
                    ).escribir(g.clave)
                }
                // A quien preguntó, directa.
                val quienes = ArrayList<Long>().also { aContestar.drainTo(it) }
                if (quienes.isNotEmpty()) e.a(paquete(false), quienes.distinct())
                val pregunta = preguntar.get()
                if (ritmo.toca(ahora, graba, huboCambio || pregunta)) {
                    preguntar.set(false)
                    val p = paquete(pregunta)
                    e.difundir(p)
                    e.a(p, conocidas(c, g))
                    if (soltarGrabando.getAndSet(false) && grabacion == null) motivos.remove(GRABANDO)
                }
                if (ritmo.tocaBarrer(ahora, graba, oidoHace(ahora))) {
                    val conocidas = conocidas(c, g)
                    val p = paquete(true)
                    var n = 0
                    for (red in e.redes) n += e.a(p, Baliza.barrido(red, conocidas))
                    Log.i(TAG, "grabando sin oír a nadie: se barre la red ($n)")
                }
                if (graba) {
                    val d0 = desde ?: ahora.also { desde = it; oido = false; diagnosticada = false }
                    oido = oido || oidoHace(ahora)?.let { it < ahora - d0 } == true
                    if (!diagnosticada) {
                        val d = Baliza.diagnosticar(ahora - d0, oido, e.redes, conocidas(c, g))
                        if (d != Baliza.Diagnostico.BIEN) { diagnosticada = true; decir(d) }
                    }
                } else {
                    desde = null
                }
                esperar(if (graba) 200 else 1_000)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "la baliza dejó de mandar", t)
        } finally {
            emisor?.cerrar()
            mandador = null
            // Si entró un motivo justo al salir, que no se quede sin hilo.
            if (motivos.isNotEmpty()) arrancar()
        }
    }

    private fun esperar(ms: Long) {
        runCatching { despertar.tryAcquire(ms, TimeUnit.MILLISECONDS); despertar.drainPermits() }
    }

    /** Se dice una vez por grabación que no aparece nadie del grupo («Actividad» de Sincronizar). */
    private fun decir(d: Baliza.Diagnostico) {
        val texto = when (d) {
            Baliza.Diagnostico.BIEN -> return
            Baliza.Diagnostico.OTRA_RED -> "No aparece tu PC: está en otra red que este teléfono. Conéctalos a la misma Wi-Fi (o el PC al punto de acceso del teléfono). Lo grabado se queda aquí y se sincroniza al encontrarse."
            Baliza.Diagnostico.AISLADOS -> "No aparece tu PC, y estáis en la misma red: puede que esta Wi-Fi aísle a los aparatos (las de invitados, hotel o empresa lo hacen) o que un cortafuegos bloquee PixPin. Conecta el PC al punto de acceso del teléfono, o los dos a otra Wi-Fi."
        }
        Log.w(TAG, "grabando y sin nadie del grupo a la vista: $d")
        Presencia.apuntar(texto)
    }

    // ------------------------------------------------------------ el que oye

    private fun oir() {
        val c = app ?: return
        val oidas = Oidas()
        // A quién ya se contestó hace poco: una pregunta llega por tres caminos.
        val contestados = HashMap<Long, Long>()
        val buf = ByteArray(Baliza.TOPE)
        var oyente: OyenteDeBalizas? = null
        var fallo = 0L
        var candado: WifiManager.MulticastLock? = null
        try {
            while (motivos.isNotEmpty()) {
                if (oyenteDeNuevo.getAndSet(false)) { oyente?.cerrar(); oyente = null }
                if (oyente == null) {
                    if (fallo != 0L && SystemClock.elapsedRealtime() - fallo < 30_000) { Thread.sleep(500); continue }
                    oyente = try {
                        OyenteDeBalizas.abrir(Baliza.PUERTO, redesActuales)
                    } catch (t: Throwable) {
                        // Otro programa en el puerto: sin oír, se sigue mandando, y quedan el mDNS y la sonda.
                        Log.w(TAG, "no se puede oír en ${Baliza.PUERTO}", t)
                        fallo = SystemClock.elapsedRealtime()
                        null
                    } ?: continue
                    fallo = 0L
                    if (candado == null) candado = tomarCandado(c)
                }
                val o = oyente ?: continue
                val (n, de) = try {
                    o.oir(buf) ?: continue
                } catch (t: java.io.IOException) {
                    Log.i(TAG, "el oyente falló: se vuelve a abrir", t)
                    o.cerrar(); oyente = null; fallo = SystemClock.elapsedRealtime()
                    continue
                }
                val g = grupo ?: continue
                val b = (Baliza.leer(buf, g.clave, n) as? Baliza.Valida)?.baliza ?: continue
                if (b.id == g.yo || !oidas.admitir(b, System.currentTimeMillis())) continue
                val ahora = SystemClock.elapsedRealtime()
                if (b.pregunta && contestados[de]?.let { ahora - it >= 1_000 } != false) {
                    contestados[de] = ahora
                    aContestar.add(de)
                    despertar.release()
                }
                // Firmada con la clave del grupo pero de alguien que aún no está en la lista (se acaba
                // de unir en otro aparato): que lo vea la sonda.
                if (b.id !in g.miembros) { SincronizacionAutomatica.aparecio(); continue }
                runCatching { oido(c, b, Ipv4.texto(de), g) }.onFailure { Log.w(TAG, "baliza oída", it) }
            }
        } catch (_: InterruptedException) {
        } catch (t: Throwable) {
            Log.w(TAG, "la baliza dejó de oír", t)
        } finally {
            oyente?.cerrar()
            runCatching { candado?.release() }
            oidor = null
            if (motivos.isNotEmpty()) arrancar()
        }
    }

    private fun tomarCandado(c: Context): WifiManager.MulticastLock? = runCatching {
        (c.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
            ?.createMulticastLock("pixpin-baliza")?.apply { setReferenceCounted(false); acquire() }
    }.getOrNull()

    /** **Lo que se hace con la baliza de uno del grupo** (§11.5). */
    private fun oido(c: Context, b: Baliza, ip: String, g: DelGrupo) {
        val ahora = SystemClock.elapsedRealtime()
        val puerto = if (b.puerto == 0) Red.PUERTO else b.puerto
        val antes = oidos.put(b.id, Oido(b, ip, ahora))
        // 1. Su dirección de verdad, aunque el DHCP le haya dado otra.
        val disco = Disco(c.filesDir)
        val ya = disco.direcciones()[b.id] == (ip to puerto)
        if (!ya) {
            Log.i(TAG, "dirección nueva de ${b.id}: $ip:$puerto")
            disco.apuntarDireccion(b.id, ip, puerto)
        }
        // 2. Presente: la automática no espera a la siguiente sonda. Una vez por aparición o cambio.
        if (antes == null || ahora - antes.cuando > 30_000 || !ya) SincronizacionAutomatica.aparecio()
        val nombre = g.miembros[b.id]?.takeIf { it.isNotBlank() } ?: b.id
        // 4. Graba: la tira de la cámara al momento y la conexión caliente (§11.7).
        if (b.graba && (antes == null || !antes.baliza.graba || antes.baliza.grabacion != b.grabacion)) {
            Presencia.apuntar("Grabando en $nombre")
        }
        if (b.graba && b.camara) {
            CamaraDelPc.alOirBaliza(b.id, nombre, b.grabacion, b.enPausa, b.msActual)
            ConexionCaliente.alOirBaliza()
        }
        // 5. **Este teléfono graba**: quien aparece (o cambia de sitio) recibe el anuncio de la
        // grabación al momento (`camara_remota` con `suya`), sin esperar al siguiente aviso.
        if (grabandoAqui()) {
            if (avisados[b.id] != ip) {
                avisados[b.id] = ip
                GrabacionDelMovil.aparecio(c, b.id)
            }
        } else {
            avisados.clear()
        }
        // Acabó de grabar: lo aplazado sale ya, y la conexión caliente se despide.
        if (antes?.baliza?.graba == true && !b.graba) {
            SincronizacionAutomatica.despertarYa()
            ConexionCaliente.alOirBaliza()
        }
    }
}
