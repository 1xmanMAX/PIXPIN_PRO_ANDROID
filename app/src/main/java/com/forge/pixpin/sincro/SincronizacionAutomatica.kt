package com.forge.pixpin.sincro

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * **Una vuelta sin nadie delante**: la de la sincronización automática. Es la de «Sincronizar con
 * todos» ([SincronizarActivity]) —lo elegido la última vez con ese aparato, o todo la primera—,
 * con una diferencia a propósito: **no borra proyectos**. Un proyecto con lápida en cualquiera de
 * los dos lados se deja para la sincronización a mano, que enseña la lista y pregunta antes de
 * borrar (v0.79, «lo mío manda»): que algo desaparezca sin que nadie lo vea pasar es justo lo
 * que costó lienzos el 15-sep. Lo demás —mensajes, lienzos, archivos, la galería— se junta igual
 * que siempre ([Fusion]).
 *
 * Con [con]`(soloEstos = …)` es la **vuelta de las tareas al instante** (`vuelta::solo_estos` del
 * PC, 11-oct-2026): de esos chats, solo lo que entraría en la entera (lo elegido con ese aparato y
 * sin lápida), sin la galería de capturas, que es lo que más tarda y no tiene que ver. El cable es
 * el mismo: quien responde atiende petición a petición y no sabe cuántos chats se juntan.
 */
class VueltaCallada(private val disco: Disco, private val miPuerto: Int = 0, private val ahora: () -> Long = System::currentTimeMillis) {
    class Resultado(val nombre: String, val hecho: Sesion.Hecho, val bytes: Long) {
        /** Si llegó algo del otro lado (lo único que merece decirse). */
        val trajoAlgo: Boolean get() = hecho.traidos > 0 || hecho.fusionados > 0 || hecho.borrados > 0 || hecho.capturas > 0
    }

    fun con(entrada: InputStream, salida: OutputStream, soloEstos: Set<String>? = null): Resultado {
        val sesion = Sesion.conectar(entrada, salida, disco, ahora = ahora, miPuerto = miPuerto)
        try {
            val otro = sesion.otro
            val mios = disco.chats().associateBy { it.id }
            val suyos = sesion.catalogo().associateBy { it.id }
            // Lo que tenga lápida en cualquiera de los dos, fuera: eso lo decide quien mire.
            val conLapida = disco.lapidas().map { it.chat }.toSet() + sesion.lapidas().map { it.chat }
            val candidatos = (mios.keys + suyos.keys).filter { it !in conLapida }
            // Lo elegido y lo nacido después de elegir (26-oct-2026): ver [Disco.elegidosAhora].
            val elegidos = disco.elegidosAhora(otro.id, candidatos) ?: candidatos.toSet()
            val hecho = Sesion.Hecho()
            // Por prioridad: tareas, compartidos, lo ligero; los archivos de todos juntos y lo
            // pesado al final. Lo quieto en los dos lados ni se mira ([Diario]).
            sesion.vuelta(candidatos.filter { it in elegidos && (soloEstos == null || it in soloEstos) }, hecho, contar = soloEstos == null)

            if (soloEstos == null) {
                sesion.galeria(hecho, ahora())
                // Las listas de los chats compartidos, con mi otro aparato. Ver [Compartidos].
                sesion.compartidosDelGrupo(ahora())
            }
            val bytes = sesion.enviados + sesion.recibidos
            sesion.adios()
            return Resultado(otro.nombre, hecho, bytes)
        } finally {
            sesion.soltar()
        }
    }
}

/**
 * **Cuándo toca sincronizar solo** (`Reloj::toca` de `sincronizar/automatica.rs` del PC), puro
 * para poder probarlo sin red. Las mismas tres razones que en el PC:
 *
 * - **Apareció** uno del grupo que antes no contestaba (entró en la Wi-Fi o abrió PixPin).
 * - **Cambió** algo aquí y lleva [QUIETO] sin cambiar más (escribir o dibujar son muchos cambios seguidos).
 * - **Periodo**: cada [PERIODO] mientras haya alguno, que es como llega lo hecho en el otro.
 */
class RelojDeSincro {
    enum class Motivo { APARECIO, CAMBIO, PERIODO }

    private var presentes: Set<String> = emptySet()
    private var firma: Firma? = null
    private var cambioEn = 0L
    private var pendiente = false
    private var ultima = 0L

    /** Lo que se mira de los chats: cuántos archivos, cuánto pesan y la suma de sus fechas. */
    data class Firma(val ficheros: Long, val bytes: Long, val tocados: Long)

    /**
     * Con quién hay que sincronizar ahora y por qué, o null. [presentesAhora]: los del grupo que
     * contestan; [firmaAhora]: la de los chats, o null si esta vez no se miró.
     */
    fun toca(ahora: Long, presentesAhora: Set<String>, firmaAhora: Firma?): Pair<Motivo, Set<String>>? {
        val nuevos = presentesAhora - presentes
        presentes = presentesAhora
        if (firmaAhora != null) {
            val antes = firma
            firma = firmaAhora
            if (antes != null && antes != firmaAhora) { pendiente = true; cambioEn = ahora }
        }
        if (presentesAhora.isEmpty()) return null
        val r = when {
            nuevos.isNotEmpty() -> Motivo.APARECIO to nuevos
            pendiente && ahora - cambioEn >= QUIETO -> Motivo.CAMBIO to presentesAhora
            ahora - ultima >= PERIODO -> Motivo.PERIODO to presentesAhora
            else -> return null
        }
        if (r.first != Motivo.APARECIO) pendiente = false
        ultima = ahora
        return r
    }

    /** Lo que se escribe al sincronizar también cambia la firma: no es un cambio nuestro. */
    fun despuesDeSincronizar(firmaAhora: Firma?) {
        if (firmaAhora != null) firma = firmaAhora
        pendiente = false
    }

    companion object {
        const val QUIETO = 30_000L
        const val PERIODO = 10 * 60_000L
        const val FALLOS_PARA_AVISAR = 3
    }
}

/**
 * **Cuándo volver a intentarlo con un aparato que falló** (27-oct-2026), pura para probarla sin red.
 *
 * Antes, una vuelta automática que fallaba —o que encontraba al otro ocupado— no se repetía hasta
 * el siguiente [RelojDeSincro.PERIODO]: diez minutos. Y si los dos aparatos se llamaban a la vez,
 * cada uno encontraba al otro ocupado ([Protocolo.OCUPADO]) y los dos esperaban esos diez minutos.
 *
 * Ahora: **ocupado** se reintenta enseguida, entre [OCUPADO_MIN] y [OCUPADO_MIN] + [OCUPADO_AZAR]
 * (con azar, para que dos que chocaron no vuelvan a chocar al mismo compás); **un fallo** de
 * verdad, con espera que se dobla —[BASE], el doble, el doble…— hasta [TOPE], con ±25 % de azar.
 * Salir bien lo borra todo.
 */
class Reintentos(private val azar: () -> Double = { kotlin.random.Random.nextDouble() }) {
    private val fallos = HashMap<String, Int>()
    private val cuando = HashMap<String, Long>()

    @Synchronized fun fallo(otro: String, ahora: Long, ocupado: Boolean): Long {
        val espera = if (ocupado) OCUPADO_MIN + (azar() * OCUPADO_AZAR).toLong() else {
            val n = (fallos[otro] ?: 0) + 1
            fallos[otro] = n
            val sinAzar = (BASE shl (n - 1).coerceAtMost(16)).coerceAtMost(TOPE)
            (sinAzar * (0.75 + azar() * 0.5)).toLong()
        }
        cuando[otro] = ahora + espera
        return espera
    }

    @Synchronized fun bien(otro: String) { fallos.remove(otro); cuando.remove(otro) }

    /** Los de [presentes] a los que ya toca volver a llamar. */
    @Synchronized fun tocan(ahora: Long, presentes: Collection<String>): Set<String> =
        presentes.filterTo(LinkedHashSet()) { cuando[it]?.let { t -> ahora >= t } == true }

    /** Fallos seguidos con [otro] (no cuenta «ocupado»). */
    @Synchronized fun seguidos(otro: String): Int = fallos[otro] ?: 0

    companion object {
        const val BASE = 5_000L
        const val TOPE = 5 * 60_000L
        const val OCUPADO_MIN = 1_000L
        const val OCUPADO_AZAR = 3_000L
    }
}

/**
 * **Las tareas que esperan para salir** (`Reloj::avisan_tareas/espera_tareas/siguiente` del PC,
 * `sincronizar/automatica.rs`, 11-oct-2026), pura para probarla sin red. Una casilla tachada no
 * puede esperar los [RelojDeSincro.QUIETO] de la vuelta entera: quien tacha en el móvil mira el PC
 * y la quiere tachada ya. Los avisos se juntan y salen tras [CALMA] sin más toques, o a los [TOPE]
 * del primero si no paran: tachar cuatro seguidas es una vuelta, no cuatro.
 */
class ColaDeTareas {
    enum class Paso { NADA, OLVIDAR, MANDAR }

    private val chats = LinkedHashSet<String>()
    private var desde = 0L
    private var ultimo = 0L

    /** Cambiaron las tareas de [chat] ([Disco.chatDe]). */
    @Synchronized fun avisan(chat: String, ahora: Long) {
        if (chats.isEmpty()) desde = ahora
        chats += chat
        ultimo = ahora
    }

    /** Cuánto falta para mandarlas (0: ya), o null si no espera ninguna. */
    @Synchronized fun espera(ahora: Long): Long? =
        if (chats.isEmpty()) null else maxOf(0L, minOf(ultimo + CALMA, desde + TOPE) - ahora)

    /**
     * Lo que toca [ahora]. Con [ocupado] (otra vuelta en marcha) nada: lo que espera se queda y sale
     * detrás, nunca a la par. Sin nadie que conteste, se olvida: la vuelta de «apareció» lo lleva.
     */
    @Synchronized fun siguiente(ahora: Long, ocupado: Boolean, hayQuien: Boolean): Paso = when {
        ocupado || espera(ahora) != 0L -> Paso.NADA
        !hayQuien -> Paso.OLVIDAR
        else -> Paso.MANDAR
    }

    /** Las que salen (o se olvidan, o se lleva una vuelta entera), y la cola vacía. */
    @Synchronized fun tomar(): Set<String> = LinkedHashSet(chats).also { chats.clear() }

    companion object {
        const val CALMA = 600L
        const val TOPE = 1_500L
        /** Tocaba y había otra vuelta en marcha: se mira otra vez enseguida. */
        const val REINTENTO = 250L
    }
}

/**
 * **Sincronizar solo, con PixPin abierto** (9-oct-2026, lo que el PC hace desde el 8-oct: «que
 * se sincronice todo con solo tener la app abierta»). Mientras haya alguna pantalla de PixPin a la
 * vista ([Presencia]), cada pocos segundos se pregunta «¿estás?» a la última dirección de cada
 * aparato del grupo y se mira la firma de los chats; [RelojDeSincro] decide. Con quien no contesta
 * no se intenta: cada intento con uno apagado cuesta agotar la conexión.
 *
 * **Sin ruido**: nada cuando sale bien y no trae nada; un aviso corto si llega algo del otro lado,
 * o si con un aparato falla [RelojDeSincro.FALLOS_PARA_AVISAR] veces seguidas (una vez). Si ya hay
 * una vuelta en marcha —a mano, o porque llamó el otro— espera ([Protocolo.ocupar]).
 * Se apaga en Sincronizar («Sincronizar sola»).
 *
 * **Tareas al instante** (11-oct-2026, como el PC): quien escribe tareas avisa ([avisarTareas],
 * desde `MensajesStore`) y el bucle, despierto al momento, manda una vuelta recortada a esos chats
 * ([VueltaCallada.con] con `soloEstos`) a los que contestaron al último sondeo. Callada del todo:
 * ni aviso si sale bien ni si falla, que la entera de [RelojDeSincro.QUIETO] sigue detrás y esa sí
 * avisa. Sin pantalla de PixPin (la casilla del widget, un recordatorio) sale un hilo suelto de una
 * vez ([lanzarSuelta]).
 */
object SincronizacionAutomatica {
    private const val LATIDO = 5_000L
    private const val LATIDOS_POR_SONDEO = 3
    private const val PREFS = "sincro_automatica"

    private lateinit var app: Application
    private val principal by lazy { Handler(Looper.getMainLooper()) }
    @Volatile private var hilo: Thread? = null
    @Volatile private var aLaVista = false
    private val fallos = HashMap<String, Int>()
    private val reintentos = Reintentos()
    /** Volvió la red: que se sondee ya, sin esperar al latido. */
    @Volatile private var sondearYa = false

    /**
     * **Encendida salvo que el usuario la apagara** (27-oct-2026: «que la sincronización sea
     * automática por defecto para los dispositivos y para los chats compartidos»). Sin nada
     * guardado —instalación nueva, aparato nuevo— está encendida, para los aparatos propios y para
     * las personas de los chats compartidos ([conPersonas]); lo que el usuario apagó a mano en
     * Sincronizar («Sincronizar sola») se respeta. Ver [encendidaSegun].
     */
    fun encendida(c: Context): Boolean {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return encendidaSegun(if (p.contains("encendida")) p.getBoolean("encendida", true) else null)
    }

    /** Lo guardado por el usuario, o null si nunca tocó el interruptor: entonces, encendida. */
    fun encendidaSegun(guardado: Boolean?): Boolean = guardado ?: true

    fun poner(c: Context, si: Boolean) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("encendida", si).apply()
    }

    fun instalar(aplicacion: Application) {
        app = aplicacion
        // **Al volver la red** (27-oct-2026): la Wi-Fi que se reconecta es justo cuando hay cosas
        // pendientes con el otro; se sondea ya en vez de esperar al latido.
        runCatching {
            val cm = aplicacion.getSystemService(android.net.ConnectivityManager::class.java)
            cm?.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) { sondearYa = true; despertar.release() }
            })
        }
    }

    /** PixPin a la vista (lo dice [Presencia]). */
    fun alaVista(si: Boolean) {
        if (!::app.isInitialized) return
        aLaVista = si
        if (si && hilo?.isAlive != true) hilo = Thread(::bucle, "sincro-automatica").apply { isDaemon = true; start() }
    }

    private val cola = ColaDeTareas()
    private val despertar = java.util.concurrent.Semaphore(0)
    @Volatile private var suelta: Thread? = null

    /**
     * Cambiaron las tareas de estos chats ([Disco.chatDe]): que salgan ya. Lo llama `MensajesStore`
     * al escribir; lo que escribe la sincronización va por [Disco] y no pasa por aquí, así que lo
     * que llega del PC no rebota.
     */
    fun avisarTareas(chats: Set<String>) {
        if (!::app.isInitialized || chats.isEmpty()) return
        try {
            if (!encendida(app) || !Red.disco(app).identidad.leer().enGrupo) return
        } catch (_: Throwable) { return }
        val ahora = System.currentTimeMillis()
        chats.forEach { cola.avisan(it, ahora) }
        despertar.release()
        if (hilo?.isAlive != true) lanzarSuelta()
    }

    private fun bucle() {
        val reloj = RelojDeSincro()
        // Las personas de los chats compartidos llevan su propio reloj: aparecer o cambiar va por cada uno.
        val relojDePersonas = RelojDeSincro()
        var sondeadas = 0L
        var sondeado = 0L
        // Los que contestaron al último sondeo: las tareas van a ellos sin volver a sondear (cada
        // aparato apagado cuesta 1-2 s de espera).
        var presentes: Map<String, Aparato> = emptyMap()
        var dirs: Map<String, Pair<String, Int>> = emptyMap()
        while (aLaVista) {
            try {
                val disco = Red.disco(app)
                val id = disco.identidad.leer()
                val ahora = System.currentTimeMillis()
                if (sondearYa) { sondearYa = false; sondeado = 0L; sondeadas = 0L }
                if (!id.enGrupo || !encendida(app)) {
                    cola.tomar()
                } else {
                    // **Los que fallaron y ya toca reintentar** ([Reintentos]), sin esperar al periodo.
                    for (otroId in reintentos.tocan(ahora, presentes.keys)) {
                        val (host, puerto) = dirs[otroId] ?: continue
                        vuelta(disco, otroId, presentes[otroId]?.nombre ?: otroId, host, puerto)
                    }
                    if (ahora - sondeado >= LATIDO * LATIDOS_POR_SONDEO) {
                        sondeado = ahora
                        dirs = disco.direcciones()
                        presentes = id.miembros.filter { it.id != id.yo.id }
                            .filter { m -> dirs[m.id]?.let { (h, p) -> Red.sondear(h, p) } == true }
                            .associateBy { it.id }
                        val decision = reloj.toca(System.currentTimeMillis(), presentes.keys, firma(disco.filesDir))
                        if (decision != null) {
                            // Lo que esperaba de tareas va dentro: la entera lee el disco de ahora.
                            cola.tomar()
                            for (otroId in decision.second) {
                                val (host, puerto) = dirs[otroId] ?: continue
                                vuelta(disco, otroId, presentes[otroId]?.nombre ?: otroId, host, puerto)
                            }
                            reloj.despuesDeSincronizar(firma(disco.filesDir))
                        }
                    }
                    mandarTareas(disco, presentes, dirs)
                }
                if (encendida(app) && ahora - sondeadas >= LATIDO * LATIDOS_POR_SONDEO) {
                    sondeadas = ahora
                    conPersonas(disco, id, relojDePersonas)
                }
            } catch (_: Throwable) {
            }
            // Lo justo para mandar las tareas a tiempo; si ya tocaban y no se pudo, se reintenta pronto.
            val espera = when (val e = cola.espera(System.currentTimeMillis())) {
                null -> LATIDO
                0L -> ColaDeTareas.REINTENTO
                else -> e.coerceAtMost(LATIDO)
            }
            try { despertar.tryAcquire(espera, TimeUnit.MILLISECONDS); despertar.drainPermits() } catch (_: InterruptedException) { return }
        }
    }

    /**
     * Si toca, la vuelta de las tareas con cada uno de [presentes]. Ocupado (otra vuelta aquí o
     * allí): vuelven a la cola como si ya estuvieran calmadas, para salir en cuanto se pueda.
     * **No** se toca el [RelojDeSincro]: lo demás que cambiara en los chats sigue pendiente para la
     * vuelta entera.
     */
    private fun mandarTareas(disco: Disco, presentes: Map<String, Aparato>, dirs: Map<String, Pair<String, Int>>) {
        val ahora = System.currentTimeMillis()
        when (cola.siguiente(ahora, ocupado = false, hayQuien = presentes.keys.any { it in dirs })) {
            ColaDeTareas.Paso.NADA -> return
            ColaDeTareas.Paso.OLVIDAR -> { cola.tomar(); return }
            ColaDeTareas.Paso.MANDAR -> {}
        }
        val chats = cola.tomar()
        for ((otroId, _) in presentes) {
            val (host, puerto) = dirs[otroId] ?: continue
            if (!vueltaDeTareas(disco, host, puerto, chats)) {
                // Con algo de azar: si los dos tacharon a la vez, cada uno encuentra al otro ocupado,
                // y reintentando al mismo compás no saldría nunca ninguno.
                val antes = System.currentTimeMillis() - ColaDeTareas.CALMA + kotlin.random.Random.nextLong(50, 400)
                chats.forEach { cola.avisan(it, antes) }
            }
        }
    }

    /** La vuelta recortada, sin decir nada. Devuelve false solo si estaba ocupado (hay que repetir). */
    private fun vueltaDeTareas(disco: Disco, host: String, puerto: Int, chats: Set<String>): Boolean = try {
        Red.conectar(InetAddress.getByName(host), puerto).use { s ->
            VueltaCallada(disco, Presencia.red.puerto).con(s.getInputStream(), s.getOutputStream(), soloEstos = chats)
        }
        VueltasEnMarcha.apuntar(true)
        true
    } catch (e: Throwable) {
        // Callada, pero el botón de sincronizar sí lo dice ([VueltasEnMarcha]).
        VueltasEnMarcha.apuntarSiFallo(e)
        e.message != Protocolo.OCUPADO
    }

    /**
     * **Sin PixPin a la vista** (la casilla del widget, un recordatorio que suena): un hilo de una
     * vez que espera la calma, sondea las direcciones apuntadas, manda las tareas a quien conteste
     * y termina. Ni escucha ni se anuncia: eso es de [Presencia]. Si mientras tanto se abre PixPin,
     * el bucle también las puede mandar; la cola es una sola y se vacía al tomarla.
     */
    private fun lanzarSuelta() {
        synchronized(this) {
            if (suelta?.isAlive == true) return
            suelta = Thread({
                try {
                    var intentos = 0
                    while (intentos++ < 40) {
                        val espera = cola.espera(System.currentTimeMillis()) ?: return@Thread
                        if (espera > 0) { Thread.sleep(espera); continue }
                        if (hilo?.isAlive == true) return@Thread
                        val disco = Red.disco(app)
                        val id = disco.identidad.leer()
                        val dirs = disco.direcciones()
                        val presentes = id.miembros.filter { it.id != id.yo.id }
                            .filter { m -> dirs[m.id]?.let { (h, p) -> Red.sondear(h, p) } == true }
                            .associateBy { it.id }
                        mandarTareas(disco, presentes, dirs)
                        if (cola.espera(System.currentTimeMillis()) != null) Thread.sleep(ColaDeTareas.REINTENTO)
                    }
                } catch (_: Throwable) {
                }
            }, "sincro-tareas").apply { isDaemon = true; start() }
        }
    }

    private fun vuelta(disco: Disco, otroId: String, nombre: String, host: String, puerto: Int) {
        try {
            val r = Red.conectar(InetAddress.getByName(host), puerto).use { s ->
                VueltaCallada(disco, Presencia.red.puerto).con(s.getInputStream(), s.getOutputStream())
            }
            fallos.remove(otroId)
            reintentos.bien(otroId)
            VueltasEnMarcha.apuntar(true)
            if (r.trajoAlgo) decir("Sincronizado con ${r.nombre}: " + resumen(r.hecho))
            if (r.hecho.sinEspacio) decir("No queda espacio en este aparato: algo de ${r.nombre} no se pudo recibir")
        } catch (e: Throwable) {
            // Ocupado (otra vuelta en marcha) no es un fallo: se reintenta enseguida, con azar.
            if (e.message == Protocolo.OCUPADO) { reintentos.fallo(otroId, System.currentTimeMillis(), ocupado = true); return }
            reintentos.fallo(otroId, System.currentTimeMillis(), ocupado = false)
            VueltasEnMarcha.apuntarSiFallo(e)
            val n = (fallos[otroId] ?: 0) + 1
            fallos[otroId] = n
            if (n == RelojDeSincro.FALLOS_PARA_AVISAR) decir("No se pudo sincronizar solo con $nombre (${e.message ?: e.javaClass.simpleName})")
        }
    }

    /**
     * **Con las personas de los chats compartidos** (13-oct-2026): las que contestan en la última
     * dirección donde se las vio, con las mismas tres razones que el grupo ([RelojDeSincro]). Mis
     * propios aparatos no cuentan: con ellos ya va la vuelta del grupo. Solo esos chats
     * ([VueltaConPersona]).
     */
    private fun conPersonas(disco: Disco, id: Identidad, reloj: RelojDeSincro) {
        val lista = CompartidosEnDisco(app.filesDir).leer()
        val mios = id.miembros.mapTo(HashSet()) { it.id } + id.yo.id
        val personas = lista.filter { Compartidos.dentro(it, id.yo.id) }
            .flatMap { it.personas }.filter { it.dentro && it.id !in mios }.associateBy { it.id }
        if (personas.isEmpty()) return
        val dirs = disco.direcciones()
        val presentes = personas.keys.filterTo(HashSet()) { p -> dirs[p]?.let { (h, puerto) -> Red.sondear(h, puerto) } == true }
        val decision = reloj.toca(System.currentTimeMillis(), presentes, firma(disco.filesDir))
        // Con quien falló y ya toca reintentar, aunque el reloj no diga nada ([Reintentos]).
        val otraVez = reintentos.tocan(System.currentTimeMillis(), presentes)
        if (decision == null && otraVez.isEmpty()) return
        for (otroId in (decision?.second.orEmpty() + otraVez)) {
            val (host, puerto) = dirs[otroId] ?: continue
            val nombre = personas[otroId]?.nombre ?: otroId
            try {
                val r = Red.conectar(InetAddress.getByName(host), puerto).use { s ->
                    VueltaConPersona(disco, Presencia.red.puerto).con(s.getInputStream(), s.getOutputStream(), aQuien = otroId)
                }
                if (r.puerto > 0) disco.apuntarDireccion(otroId, host, r.puerto)
                fallos.remove(otroId)
                reintentos.bien(otroId)
                VueltasEnMarcha.apuntar(true)
                if (r.trajoAlgo) decir("Chat compartido con ${r.quien.nombre}: " + resumen(r.hecho))
            } catch (e: Throwable) {
                if (e.message == Protocolo.OCUPADO) { reintentos.fallo(otroId, System.currentTimeMillis(), ocupado = true); continue }
                reintentos.fallo(otroId, System.currentTimeMillis(), ocupado = false)
                VueltasEnMarcha.apuntarSiFallo(e)

                val n = (fallos[otroId] ?: 0) + 1
                fallos[otroId] = n
                if (n == RelojDeSincro.FALLOS_PARA_AVISAR) decir("No se pudo sincronizar solo con $nombre (${e.message ?: e.javaClass.simpleName})")
            }
        }
        reloj.despuesDeSincronizar(firma(disco.filesDir))
    }

    private fun resumen(h: Sesion.Hecho): String = listOfNotNull(
        "${h.traidos} traídos".takeIf { h.traidos > 0 },
        "${h.fusionados} juntados".takeIf { h.fusionados > 0 },
        "${h.borrados} borrados".takeIf { h.borrados > 0 },
        "${h.capturas} capturas".takeIf { h.capturas > 0 },
    ).joinToString(" · ")

    private fun decir(texto: String) {
        principal.post { if (aLaVista) Toast.makeText(app, texto, Toast.LENGTH_SHORT).show() }
    }

    /** La firma de lo que viaja: el chat, los proyectos y los dibujos (no las cachés). */
    fun firma(raiz: File): RelojDeSincro.Firma {
        var n = 0L; var b = 0L; var t = 0L
        fun mirar(f: File) {
            if (f.isFile) { n++; b += f.length(); t += f.lastModified() }
            else f.listFiles()?.forEach(::mirar)
        }
        for (nombre in listOf("guardados.jsonl", "proyectos", "pins/draw", "chats")) File(raiz, nombre).takeIf { it.exists() }?.let(::mirar)
        return RelojDeSincro.Firma(n, b, t)
    }
}
