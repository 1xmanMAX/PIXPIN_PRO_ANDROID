package com.forge.pixpin.sincro

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/**
 * **La baliza UDP: descubrir rápido y prioridad al grabar** (28-oct-2026), puerto byte a byte de
 * `crates/pixpin-sincro/src/baliza.rs` del PC (§11 de su `docs/movil-como-camara.md`).
 *
 * El usuario: «con las clases grabadas, el MÓVIL tarda demasiado en saber si el PC está grabando, y
 * no puedo hacer fotos rápido… que localice cualquier aparato de la red rápido y simple. Todo tiene
 * que ser rápido y fiable mientras se graba: perder algo sería perder una reunión».
 *
 * Por qué tardaba: el mDNS, que muchas Wi-Fi reparten con uno o dos minutos de retraso, y la sonda
 * TCP a la **última dirección conocida**, que no sirve si el DHCP le dio otra al aparato. La baliza es
 * un datagrama pequeño, firmado con la clave del grupo, que quien graba manda **cada segundo** (y al
 * momento al empezar, pausar, seguir o acabar) por difusión, multidifusión ([GRUPO_MULTICAST]) y
 * directa; sin grabar, cada [CADA_EN_REPOSO_MS]. **No abre nada**: solo dice dónde llamar y si se
 * graba; todo lo demás va por el canal cifrado de siempre ([Canal]).
 *
 * El cable (§11.2, big-endian):
 *
 * | Bytes | Campo |
 * | --- | --- |
 * | 0–3 | `PXB1` |
 * | 4 | versión = 1 |
 * | 5 | banderas: [GRABANDO], [EN_PAUSA], [PREGUNTA], [CAMARA] |
 * | 6–13 | la etiqueta del grupo en crudo ([etiquetaCruda]: la `g` del mDNS es esto en hex) |
 * | 14–15 | el puerto TCP de la sincronización |
 * | 16–23 | el reloj de quien la manda (ms desde 1970) |
 * | 24–27 | la secuencia (`u32`) |
 * | 28–35 | `ms_actual` |
 * | 36… | largo e id del aparato (1–64), largo e id de la grabación (0–64) |
 * | últimos 16 | la firma: `HMAC-SHA256(claveBaliza, todo lo anterior)` truncado |
 *
 * Puro, sin Android: lo prueba `BalizaTest` con los vectores del PC, y `BalizaContraElPcTest` contra
 * el Rust de verdad. Los sockets y el ritmo de este teléfono son de [BalizaDelMovil].
 */
data class Baliza(
    /** La etiqueta del grupo en crudo, en hex (16 cifras): la `g` del anuncio mDNS ([Grupo.etiqueta]). */
    val etiqueta: String,
    /** El id del aparato que la manda ([Aparato.id]). */
    val id: String,
    /** El puerto TCP donde escucha su sincronización. */
    val puerto: Int,
    /** El reloj de quien la manda (ms desde 1970). */
    val reloj: Long,
    /** Sube con cada baliza que manda el aparato (`u32`, de 0 a 2³²−1). */
    val secuencia: Long,
    /** [GRABANDO], [EN_PAUSA], [PREGUNTA], [CAMARA]. */
    val banderas: Int,
    /** El id de la grabación (`camara_remota.grabacion`), o vacío. */
    val grabacion: String = "",
    /** En qué milisegundo de la grabación va al mandarla. */
    val msActual: Long = 0
) {
    val graba: Boolean get() = banderas and GRABANDO != 0
    val enPausa: Boolean get() = banderas and EN_PAUSA != 0
    val pregunta: Boolean get() = banderas and PREGUNTA != 0
    val camara: Boolean get() = banderas and CAMARA != 0

    /**
     * **Los bytes**, firmados con la clave del grupo (`Baliza::escribir`). Los textos de más de
     * [TOPE_TEXTO] bytes se recortan sin partir una letra.
     */
    fun escribir(claveGrupo: ByteArray): ByteArray {
        val id = recortar(id)
        val grabacion = recortar(grabacion)
        val b = ByteBuffer.allocate(CABEZA + 2 + id.size + grabacion.size + LARGO_FIRMA)
        b.put(MAGIA)
        b.put(VERSION.toByte())
        b.put(banderas.toByte())
        b.put(deHex(etiqueta).copyOf(8))
        b.putShort(puerto.toShort())
        b.putLong(reloj)
        b.putInt(secuencia.toInt())
        b.putLong(msActual)
        b.put(id.size.toByte()); b.put(id)
        b.put(grabacion.size.toByte()); b.put(grabacion)
        val datos = b.array().copyOf(b.position())
        b.put(Grupo.hmac(claveBaliza(claveGrupo), datos), 0, LARGO_FIRMA)
        return b.array()
    }

    /** Por qué no vale una baliza (`Rechazo` del PC). */
    enum class Rechazo {
        /** No es una baliza (otra cosa por el mismo puerto). */
        NO_ES,
        /** De otra versión. */
        VERSION,
        /** Cortada o con largos que no cuadran. */
        ROTA,
        /** De otro grupo: la etiqueta no es la nuestra. */
        OTRO_GRUPO,
        /** La firma no cuadra: alguien la fingió o la tocó. */
        FIRMA
    }

    /** Lo que sale de [leer]: la baliza o por qué no. */
    sealed interface Lectura
    data class Valida(val baliza: Baliza) : Lectura
    data class Rechazada(val por: Rechazo) : Lectura

    companion object {
        /** El puerto UDP: el de la sincronización ([Red.PUERTO]) más uno. Fijo: una difusión no sabe de puertos efímeros. */
        const val PUERTO = 47475
        /** La multidifusión (ámbito local de la organización, RFC 2365), con TTL 1. */
        const val GRUPO_MULTICAST = "239.255.74.74"
        val MAGIA = byteArrayOf('P'.code.toByte(), 'X'.code.toByte(), 'B'.code.toByte(), '1'.code.toByte())
        const val VERSION = 1
        const val LARGO_FIRMA = 16
        /** Lo más largo que puede ser el id del aparato o el de la grabación, en bytes UTF-8. */
        const val TOPE_TEXTO = 64
        /** Lo que mide la parte fija, antes de los dos textos y la firma. */
        const val CABEZA = 36
        /** Lo más que puede medir una baliza: 182 bytes. */
        const val TOPE = CABEZA + 2 + 2 * TOPE_TEXTO + LARGO_FIRMA

        /** Cada cuánto se manda grabando. */
        const val CADA_GRABANDO_MS = 1_000L
        /** Cada cuánto sin grabar (PixPin a la vista o su servicio vivo). */
        const val CADA_EN_REPOSO_MS = 15_000L
        /** Cuánto vale una baliza de «grabando» sin otra detrás: tres perdidas seguidas y ya no. */
        const val VIGENCIA_GRABANDO_MS = 4_000L
        /** Cuánto se espera grabando sin oír a nadie antes de barrer la red. */
        const val BARRER_TRAS_MS = 5_000L
        /** Y cada cuánto se repite el barrido mientras siga sin oírse a nadie. */
        const val BARRER_CADA_MS = 10_000L
        /** Cuántas direcciones se barren como mucho por red (una /24 entera). */
        const val TOPE_BARRIDO = 254
        /** Lo más que puede ir desviado el reloj de quien la manda: más es una vieja que alguien reenvía. */
        const val DESFASE_MAXIMO_MS = 10 * 60_000L
        /** Cuánto se graba sin oír a nadie antes de decirlo ([diagnosticar]). */
        const val ESPERA_DIAGNOSTICO_MS = 20_000L

        const val GRABANDO = 1
        const val EN_PAUSA = 1 shl 1
        /** «Quien me oiga, que me conteste con la suya, directa»: al abrir PixPin o volver la red. */
        const val PREGUNTA = 1 shl 2
        /** Este aparato acepta fotos y clips en su grabación (`camara1` encendida). */
        const val CAMARA = 1 shl 3

        /** La clave con que se firman: derivada, para no usar la del grupo tal cual en algo que va en claro. */
        fun claveBaliza(claveGrupo: ByteArray): ByteArray = Grupo.hmac(claveGrupo, "baliza".toByteArray())

        /** La etiqueta del grupo en crudo (8 bytes). */
        fun etiquetaCruda(claveGrupo: ByteArray): ByteArray = Grupo.hmac(claveGrupo, "etiqueta".toByteArray()).copyOf(8)

        /** Lo mismo en hex, como va en [Baliza.etiqueta] (y en la `g` del mDNS). */
        fun etiquetaDe(claveGrupo: ByteArray): String = hex(etiquetaCruda(claveGrupo))

        /**
         * **Lee y comprueba** una baliza de este grupo (`Baliza::leer`), en el orden del PC: lo que
         * no es, la versión y los largos; la etiqueta **antes** que la firma (lo de otros grupos se
         * tira sin calcular nada); la firma en tiempo constante; y los textos.
         */
        fun leer(bytes: ByteArray, claveGrupo: ByteArray, largo: Int = bytes.size): Lectura {
            if (largo < 4 || !(0 until 4).all { bytes[it] == MAGIA[it] }) return Rechazada(Rechazo.NO_ES)
            if (largo < CABEZA + 2 + LARGO_FIRMA || largo > TOPE) return Rechazada(Rechazo.ROTA)
            if (bytes[4].toInt() != VERSION) return Rechazada(Rechazo.VERSION)
            val etiqueta = bytes.copyOfRange(6, 14)
            if (!etiqueta.contentEquals(etiquetaCruda(claveGrupo))) return Rechazada(Rechazo.OTRO_GRUPO)
            val datos = bytes.copyOfRange(0, largo - LARGO_FIRMA)
            val firma = bytes.copyOfRange(largo - LARGO_FIRMA, largo)
            val esperada = Grupo.hmac(claveBaliza(claveGrupo), datos).copyOf(LARGO_FIRMA)
            // En tiempo constante: no se le dice a nadie cuántos bytes acertó.
            if (!MessageDigest.isEqual(esperada, firma)) return Rechazada(Rechazo.FIRMA)
            val b = ByteBuffer.wrap(datos)
            var i = CABEZA
            fun texto(): String? {
                if (i >= datos.size) return null
                val n = datos[i].toInt() and 0xff
                if (i + 1 + n > datos.size) return null
                val t = utf8(datos, i + 1, n) ?: return null
                i += 1 + n
                return t
            }
            val id = texto() ?: return Rechazada(Rechazo.ROTA)
            val grabacion = texto() ?: return Rechazada(Rechazo.ROTA)
            if (i != datos.size || id.isEmpty()) return Rechazada(Rechazo.ROTA)
            return Valida(
                Baliza(
                    etiqueta = hex(etiqueta),
                    id = id,
                    puerto = b.getShort(14).toInt() and 0xffff,
                    reloj = b.getLong(16),
                    secuencia = b.getInt(24).toLong() and 0xffff_ffffL,
                    banderas = datos[5].toInt() and 0xff,
                    grabacion = grabacion,
                    msActual = b.getLong(28)
                )
            )
        }

        /** Los bytes UTF-8 de [t], recortados a [TOPE_TEXTO] sin partir una letra (`recortar` del PC). */
        fun recortar(t: String): ByteArray {
            val b = t.toByteArray(Charsets.UTF_8)
            if (b.size <= TOPE_TEXTO) return b
            var fin = TOPE_TEXTO
            // Un byte 10xxxxxx es la continuación de una letra: ahí no se corta.
            while (fin > 0 && (b[fin].toInt() and 0xc0) == 0x80) fin--
            return b.copyOf(fin)
        }

        /** UTF-8 estricto, como `String::from_utf8`: lo que no lo es, null. */
        private fun utf8(b: ByteArray, desde: Int, n: Int): String? = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(b, desde, n)).toString()
        } catch (_: CharacterCodingException) {
            null
        }

        fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it.toInt() and 0xff) }

        fun deHex(t: String): ByteArray = ByteArray(t.length / 2) { t.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

        // ------------------------------------------------------------ las redes

        /** **Las direcciones a las que barrer** si no se oye a nadie (`barrido` del PC). Ver [Interfaz]. */
        fun barrido(red: Interfaz, conocidas: List<Long>): List<Long> {
            val m = red.mascara()
            val base = red.ip and m
            val ultima = base or (m.inv() and IPV4)
            fun valida(x: Long) = x > base && x < ultima && x != red.ip
            val salida = ArrayList<Long>()
            fun poner(x: Long) {
                if (valida(x) && x !in salida && salida.size < TOPE_BARRIDO) salida += x
            }
            val propias = conocidas.filter { red.contiene(it) }
            for (c in propias) poner(c)
            for (d in 1L..8L) for (c in propias) {
                poner((c + d) and IPV4)
                poner((c - d) and IPV4)
            }
            if (red.prefijo >= 22) {
                var x = base + 1
                while (x < ultima && salida.size < TOPE_BARRIDO) { poner(x); x++ }
            }
            return salida
        }

        // ------------------------------------------------------------ la prioridad

        /** **Qué toca ahora** de lo que espera (`que_toca`): lo primero, y grabando alguien, solo la cámara. */
        fun queToca(espera: Collection<Trabajo>, grabandoAlguien: Boolean): Trabajo? =
            espera.filter { !grabandoAlguien || it.mientrasSeGraba }.minOrNull()

        // ------------------------------------------------------------ el diagnóstico

        /**
         * Puro (`diagnosticar` del PC): [grabandoMs], lo que lleva grabando; [oidos], si se oyó
         * alguna baliza o contestó alguna sonda del grupo desde que empezó; [conocidas], las últimas
         * direcciones de los demás del grupo. Espera [ESPERA_DIAGNOSTICO_MS] antes de decir nada.
         */
        fun diagnosticar(grabandoMs: Long, oidos: Boolean, redes: List<Interfaz>, conocidas: List<Long>): Diagnostico = when {
            oidos || grabandoMs < ESPERA_DIAGNOSTICO_MS || conocidas.isEmpty() -> Diagnostico.BIEN
            redes.isNotEmpty() && conocidas.none { c -> redes.any { it.contiene(c) } } -> Diagnostico.OTRA_RED
            else -> Diagnostico.AISLADOS
        }

        private const val IPV4 = 0xffff_ffffL
    }

    /** **Lo que puede querer hacer la sincronización**, de lo primero a lo último (`Trabajo` del PC). */
    enum class Trabajo {
        /** El anuncio de la grabación y su reloj, las fotos y los clips. */
        CAMARA,
        /** Los ficheros de una grabación recién acabada: solo ese chat. */
        ARCHIVOS_DE_LA_GRABACION,
        /** Las tareas tachadas, «al instante». */
        TAREAS,
        /** La vuelta entera con el grupo. */
        VUELTA_ENTERA,
        /** Los chats compartidos con otras personas. */
        PERSONAS;

        /** Mientras alguien del grupo graba, solo la cámara. */
        val mientrasSeGraba: Boolean get() = this == CAMARA
    }

    /** Lo que se le dice al usuario si grabando no aparece nadie. */
    enum class Diagnostico { BIEN, OTRA_RED, AISLADOS }
}

/** **Una red IPv4 de este aparato** (`Interfaz` del PC): la dirección como `u32` en un Long y el prefijo. */
data class Interfaz(val ip: Long, val prefijo: Int) {
    fun mascara(): Long = when {
        prefijo <= 0 -> 0L
        prefijo >= 32 -> 0xffff_ffffL
        else -> (0xffff_ffffL shl (32 - prefijo)) and 0xffff_ffffL
    }

    /** La dirección de difusión de esta red (`192.168.1.255`). */
    fun difusion(): Long = ip or (mascara().inv() and 0xffff_ffffL)

    fun contiene(otra: Long): Boolean = (ip and mascara()) == (otra and mascara())

    companion object {
        fun de(ip: String, prefijo: Int) = Interfaz(Ipv4.de(ip), prefijo)
    }
}

/** Una IPv4 como `u32` en un Long, y vuelta. */
object Ipv4 {
    fun de(texto: String): Long = texto.split('.').fold(0L) { a, p -> (a shl 8) or (p.toLong() and 0xff) }
    fun de(b: ByteArray): Long = b.fold(0L) { a, x -> (a shl 8) or (x.toLong() and 0xff) }
    fun texto(ip: Long): String = (3 downTo 0).joinToString(".") { ((ip shr (8 * it)) and 0xff).toString() }
    fun bytes(ip: Long): ByteArray = ByteArray(4) { ((ip shr (8 * (3 - it))) and 0xff).toByte() }
}

/**
 * **Lo que ya se oyó de cada aparato** (`Oidas` del PC), para tirar las repetidas: la misma llega por
 * la difusión, la multidifusión y directa, y alguien podría grabarla y reenviarla. Vale si su
 * `(reloj, secuencia)` es mayor que la última de ese aparato y su reloj no está a más de
 * [Baliza.DESFASE_MAXIMO_MS] del nuestro.
 */
class Oidas {
    private val ultimas = HashMap<String, Pair<Long, Long>>()

    @Synchronized fun admitir(b: Baliza, ahora: Long): Boolean {
        if (Math.abs(b.reloj - ahora) > Baliza.DESFASE_MAXIMO_MS) return false
        val antes = ultimas[b.id]
        // Un reloj que va hacia atrás más que el desfase (se cambió la hora) también entra: si no,
        // ese aparato quedaría mudo.
        val noEsNueva = antes != null &&
            (b.reloj < antes.first || (b.reloj == antes.first && b.secuencia <= antes.second))
        if (noEsNueva && antes!!.first - b.reloj < Baliza.DESFASE_MAXIMO_MS) return false
        ultimas[b.id] = b.reloj to b.secuencia
        return true
    }
}

/**
 * **Cuándo mandar la siguiente** (`Ritmo` del PC): cada [Baliza.CADA_GRABANDO_MS] grabando, cada
 * [Baliza.CADA_EN_REPOSO_MS] sin grabar y al momento si cambió la grabación; y si toca **barrer** la
 * red (§11.4). Puro: el tiempo lo pone quien llama.
 */
class Ritmo {
    private var ultima: Long? = null
    private var aSolasDesde: Long? = null
    private var barrido: Long? = null

    fun toca(ahora: Long, grabando: Boolean, cambio: Boolean): Boolean {
        val cada = if (grabando) Baliza.CADA_GRABANDO_MS else Baliza.CADA_EN_REPOSO_MS
        val u = ultima
        val toca = cambio || u == null || ahora - u >= cada || ahora < u
        if (toca) ultima = ahora
        return toca
    }

    /**
     * Si toca barrer: grabando, sin haber oído a nadie del grupo en [Baliza.BARRER_TRAS_MS]
     * ([oidoHace]: hace cuánto, o null si a nadie), y cada [Baliza.BARRER_CADA_MS] mientras siga así.
     */
    fun tocaBarrer(ahora: Long, grabando: Boolean, oidoHace: Long?): Boolean {
        val aSolas = grabando && (oidoHace == null || oidoHace > Baliza.BARRER_TRAS_MS)
        if (!aSolas) { aSolasDesde = null; return false }
        val desde = aSolasDesde ?: ahora.also { aSolasDesde = it }
        if (ahora - desde < Baliza.BARRER_TRAS_MS) return false
        val b = barrido
        if (b != null && ahora - b < Baliza.BARRER_CADA_MS) return false
        barrido = ahora
        return true
    }
}
