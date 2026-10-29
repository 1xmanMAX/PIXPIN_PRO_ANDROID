package com.forge.pixpin.sincro

import android.content.Context
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * **La grabación de este teléfono, para el PC** (28-oct-2026, §13 de `docs/movil-como-camara.md` del
 * PC). Lo que llama la grabadora ([com.forge.pixpin.guardados.ReunionPresencialService] y
 * [com.forge.pixpin.guardados.GrabarClaseService]) en cuatro sitios: [empezar], [pausa] (al pausar o
 * seguir), [acabar] y [guardada] (o [tirar]). Lo demás es de aquí:
 *
 * - **Se lo dice a cada PC del grupo** (su última dirección): la `camara_remota` con la grabación en
 *   `suya` ([ConexionDeCamara.anunciarLaMia]) al empezar, pausar, seguir, acabar y **cada 30 s**
 *   mientras graba (el PC la da por perdida a los 90 s). De paso se mide su reloj
 *   ([FotosPorSuHora.medido]). En el PC sale «Captura para la grabación del móvil».
 * - **Recibe sus capturas** ([recepcion], para el [Respondedor] de [Presencia]) y las pone en su minuto
 *   ([GrabacionesDelMovil]).
 * - **Invitar a todos los del chat** ([abiertaATodos]): la reunión en curso aún no tiene nota; al
 *   guardarse, la nota lleva `colaboradores: ["*"]` (§14).
 */
object GrabacionDelMovil {

    /** Cada cuánto se repite el anuncio al PC mientras se graba (el PC la olvida a los 90 s). */
    const val CADA_MS = 30_000L

    private val cerrojo = Any()
    @Volatile private var grabaciones: GrabacionesDelMovil? = null
    @Volatile private var llevado: (() -> Pair<Long, Boolean>)? = null
    /** Lo que dice la baliza de la grabación en curso ([BalizaDelMovil]). */
    @Volatile private var baliza: GrabacionParaLaBaliza? = null

    /**
     * **Si este teléfono hace de cámara para los demás** (la bandera «cámara» de la baliza, como
     * `c.encendida` del PC): graba, su cámara está encendida ([GrabacionesDelMovil.encendida]) y dice
     * `camara1` ([Protocolo.CAPACIDADES]): el PC le puede mandar capturas, y otro teléfono, fotos.
     */
    val camaraEncendida: Boolean
        get() = grabaciones?.let { it.enCurso != null && it.encendida } == true && CamaraRemota.CAPACIDAD in Protocolo.CAPACIDADES

    /**
     * **Apareció uno del grupo mientras se graba** (28-oct-2026, §11.5 punto 5; lo dice la baliza al
     * oírlo nuevo en esta grabación o en otra dirección): se le manda el anuncio de la grabación
     * (`camara_remota` con `suya`) ya, sin esperar a los [CADA_MS] del siguiente aviso.
     */
    fun aparecio(c: Context, aparato: String) {
        if (!grabando) return
        val app = c.applicationContext
        Thread({ runCatching { anunciarA(app, setOf(aparato)) } }, "pixpin-grabacion-aparecio").apply { isDaemon = true; start() }
    }
    private val despertar = LinkedBlockingQueue<Boolean>()
    @Volatile private var anunciador: Thread? = null

    /** **Abierta a todos los del chat** (§14): la grabación en curso deja aportar fotos a los del chat compartido. */
    val abiertaATodos = MutableStateFlow(false)

    /** Si hay una grabación en curso en este teléfono. */
    val grabando: Boolean get() = grabaciones?.enCurso != null

    /** Las capturas del PC llegadas a la grabación en curso: la pantalla de la reunión las cuenta. */
    val delPc = MutableStateFlow(0)

    private fun de(c: Context): GrabacionesDelMovil = grabaciones ?: synchronized(cerrojo) {
        grabaciones ?: run {
            val app = c.applicationContext
            val disco = Red.disco(app)
            val yo = runCatching { disco.identidad.leer().yo }.getOrNull()
            GrabacionesDelMovil(File(app.filesDir, "sincro/camara-movil/de-otros"), yo?.nombre.orEmpty(), yo?.codigo.orEmpty())
                .also { it.almacen = disco; grabaciones = it }
        }
    }

    private fun <T> con(c: Context, f: (GrabacionesDelMovil) -> T): T {
        val g = de(c)
        return synchronized(g) {
            // El nombre y el código, al día (se pudo cambiar de nombre o entrar en un grupo).
            runCatching { Red.disco(c.applicationContext).identidad.leer().yo }.getOrNull()?.let { yo -> g.aparato = yo.nombre; g.codigo = yo.codigo }
            f(g)
        }
    }

    /**
     * **Empieza la grabación** [titulo]; [cronometro] dice por dónde va y si está en pausa (el reloj de
     * la grabadora, que es el de la nota). Devuelve su id (`m-…`), el que se anuncia al PC.
     */
    fun empezar(c: Context, titulo: String, cronometro: () -> Pair<Long, Boolean>): String {
        val id = "m-" + UUID.randomUUID().toString().take(8)
        llevado = cronometro
        abiertaATodos.value = false
        delPc.value = 0
        con(c) { it.empezar(id, titulo, System.currentTimeMillis(), runCatching { cronometro().first }.getOrDefault(0)) }
        arrancarAnunciador(c.applicationContext)
        runCatching { Presencia.grabacion(true) }
        // La baliza UDP (28-oct-2026, §11): el PC sabe en un segundo que se graba, y cada segundo.
        val paraLaBaliza = GrabacionParaLaBaliza.de(id, aceptaFotos = { camaraEncendida }) { runCatching { cronometro() }.getOrNull() }
        baliza = paraLaBaliza
        BalizaDelMovil.empezo(paraLaBaliza)
        // Y ocupado para sincronizar mientras dura (§11.6); la cámara y las capturas, igual.
        Protocolo.grabando(Red.disco(c.applicationContext), true)
        return id
    }

    /** Al pausar o seguir: se pone al día y se avisa al PC. */
    fun pausa(c: Context) {
        alDia(c)
        despertar.offer(true)
        BalizaDelMovil.cambio()
    }

    private fun alDia(c: Context) {
        val l = runCatching { llevado?.invoke() }.getOrNull() ?: return
        con(c) { it.seguirA(System.currentTimeMillis(), l.first, l.second) }
    }

    /** **Se acabó de grabar** (se guarde o no): el PC lo sabe en el acto y quita su botón. */
    fun acabar(c: Context) {
        alDia(c)
        con(c) { it.acabar(System.currentTimeMillis()) }
        llevado = null
        despertar.offer(true)
        runCatching { Presencia.grabacion(false) }
        baliza?.let { BalizaDelMovil.acabo(it) }
        Protocolo.grabando(Red.disco(c.applicationContext), false)
    }

    /**
     * **La grabación se guardó** en el chat [proyecto]: su nota [voz] (null si no entró), su carpeta y
     * lo que dura. Lo del PC que esperaba entra ya en su minuto, el reloj de pared entra en la nota (si
     * aún no lo lleva) y, si se abrió a todos, sus `colaboradores`. Luego, el repaso por su hora.
     */
    fun guardada(c: Context, proyecto: String?, voz: String?, carpeta: String?, duracion: Long?) {
        val disco = Red.disco(c.applicationContext)
        runCatching {
            con(c) { it.guardada(GrabacionesDelMovil.Destino(disco, proyecto, voz, carpeta), duracion, System.currentTimeMillis()) }
        }.onFailure { android.util.Log.w("PixPinGrabacion", "lo del PC no entró en la clase", it) }
        if (voz != null && abiertaATodos.value) FotosPorSuHora.invitarATodos(disco, proyecto, voz, true)
        abiertaATodos.value = false
        FotosPorSuHora.repasarEnFondo(disco)
        // Su chat sale el primero al sincronizar (§11.6).
        BalizaDelMovil.guardada(proyecto)
    }

    /** **La grabación se tiró** sin guardarse: lo del PC también. */
    fun tirar(c: Context) {
        llevado = null
        baliza?.let { BalizaDelMovil.acabo(it) }
        Protocolo.grabando(Red.disco(c.applicationContext), false)
        con(c) { it.tirar() }
        abiertaATodos.value = false
        despertar.offer(true)
    }

    /** **Quien graba**, para el [Respondedor]: la grabación de aquí y lo que le llega del PC. */
    fun recepcion(c: Context): QuienGraba = object : QuienGraba {
        override fun anuncio(): CamaraRemota.Anuncio? {
            alDia(c)
            return con(c) { it.anuncio(System.currentTimeMillis()) }
        }
        override fun aceptar(p: Protocolo.Peticion): String? = con(c) { it.aceptar(p.grabacion, p.tomadaEn, p.mime.orEmpty(), p.bytes) }
        override fun guardar(p: Protocolo.Peticion, fichero: File, otro: Aparato): Long {
            alDia(c)
            val ms = con(c) { it.recibir(GrabacionesDelMovil.Foto.de(p), fichero, otro.id, otro.nombre, System.currentTimeMillis()) }
            delPc.value = delPc.value + 1
            Presencia.apuntar("Captura de ${otro.nombre} en la grabación · ${CamaraRemota.minuto(ms)}")
            return ms
        }
    }

    // ------------------------------------------------------------ el anuncio al PC

    private fun arrancarAnunciador(app: Context) {
        if (anunciador?.isAlive == true) { despertar.offer(true); return }
        anunciador = Thread({
            while (true) {
                val grabando = grabaciones?.enCurso != null
                alDia(app)
                runCatching { anunciarATodos(app) }
                // Tras acabar, el último aviso (ya hecho arriba: «acabó») y fuera.
                if (!grabando) break
                despertar.poll(CADA_MS, TimeUnit.MILLISECONDS)
            }
            anunciador = null
        }, "pixpin-grabacion-al-pc").apply { isDaemon = true; start() }
    }

    /** **Dice a cada PC del grupo cómo va la grabación** y mide su reloj de paso. */
    private fun anunciarATodos(app: Context) = anunciarA(app, null)

    /** Lo mismo, solo a [soloA] (null: a todos). */
    private fun anunciarA(app: Context, soloA: Set<String>?) {
        val disco = Red.disco(app)
        val id = disco.identidad.leer()
        if (!id.enGrupo) return
        val anuncio = con(app) { it.anuncio(System.currentTimeMillis()) } ?: return
        val dirs = disco.direcciones()
        for (m in id.miembros.filter { it.id.isNotBlank() && it.id != id.yo.id && dirs[it.id] != null && (soloA == null || it.id in soloA) }.distinctBy { it.id }) {
            val (host, puerto) = dirs.getValue(m.id)
            runCatching {
                Socket().apply { connect(InetSocketAddress(host, puerto), 2_000); soTimeout = 10_000; tcpNoDelay = true }.use { s ->
                    val conexion = ConexionDeCamara.abrir(s.getInputStream(), s.getOutputStream(), id, runCatching { Presencia.red.puerto }.getOrDefault(0))
                    if (conexion.sabeDeCamara) {
                        val r = conexion.anunciarLaMia(anuncio)
                        // Lo que va adelantado su reloj sobre el de aquí.
                        if (r.anuncio.reloj > 0) {
                            FotosPorSuHora.medido(app.filesDir, m.id, -r.adelanto)
                            con(app) { it.medir(m.id, -r.adelanto) }
                        }
                    }
                    conexion.adios()
                }
            }
        }
    }
}
