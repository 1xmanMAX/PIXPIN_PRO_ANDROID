package com.forge.pixpin.guardados

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * **Vosk en vivo** (1-nov-2026, `pixpin_voz::vosk_vivo` del PC): oír la reunión de corrido, con lo que se
 * va oyendo mientras se habla.
 *
 * La escucha de la reunión ([EscuchaDeLaReunion]) pasa a texto con el reconocedor del sistema o, frase a
 * frase, con Whisper ligero (unos 3 s desde que se deja de hablar, porque su codificador oye siempre 30 s).
 * Vosk (Kaldi, Apache-2.0, los `vosk-model-small-*` de unos 40 MB que ya usa [MotorVosk]) es **un
 * reconocedor de corriente**: se le da el audio a trozos de [TROZO_MS] y devuelve **lo que va oyendo** (el
 * parcial, décimas después de decirlo) y, en cuanto se calla medio segundo, **la frase cerrada** con el
 * tiempo de cada palabra. Quien habla de corrido no se calla medio segundo: pasados [LARGO_MS], la frase se
 * cierra en la primera pausa de una coma ([PAUSA_MS]), y pasados [MAXIMO_MS], sin pausa.
 *
 * Medido en el PC (`docs/probar-ideas-sin-interfaz.md`, «vosk_en_vivo», la misma reunión de 186 s): con
 * Vosk la frase sale a los 0,40 s de media de callarse (p50 0,23 s) frente a los 3,24 s de Whisper *base*,
 * con cinco veces menos procesador y el mismo WER o mejor (7,8 % frente a 8,5 %). Lo que pierde: ni
 * mayúsculas ni puntuación, y por eso las frases las corta la pausa ([Vivo.partir] no tiene dónde partir).
 * **Las palabras del parcial se dejan apagadas**: con ellas Vosk tarda 1,5-2 s en dar el parcial.
 *
 * Aquí está lo puro, que se prueba sin Vosk ([Corriente] con un [Reconocedor] de mentira); el de verdad lo
 * da [MotorVosk.reconocedorEnVivo].
 */
object VoskEnVivo {
    /** Lo que se le da al reconocedor de una vez: 100 ms. */
    const val TROZO_MS = 100L
    /** Cada cuánto audio se le pide el parcial. */
    const val CADA_PARCIAL_MS = 200L
    /** Un hueco de hasta esto se rellena con silencio (como en [OidoEnVivo.Frasero]). */
    const val HUECO_MAXIMO_MS = 1_000L
    /**
     * **Quien habla de corrido**: Vosk solo cierra la frase al callarse medio segundo, o a los 20 s. Con
     * una frase de más de esto a medias, se cierra en la primera pausa de [PAUSA_MS] (la de una coma); así
     * a Jev le llegan frases de unos segundos, como con el [OidoEnVivo.Frasero], y no de veinte.
     */
    const val LARGO_MS = 4_000L
    /** Con una frase de más de esto a medias, se cierra aunque no haya pausa. */
    const val MAXIMO_MS = 10_000L
    /** Lo callado que ya vale para cerrar una frase larga. */
    const val PAUSA_MS = 200L

    private const val HERCIOS = OidoEnVivo.HERCIOS

    /** **Lo que se oye**, por orden. */
    sealed class Oido {
        /** Lo que va oyendo de la frase de ahora (vacío: ya no hay nada a medias). Solo se enseña. */
        data class Parcial(val texto: String) : Oido()
        /** Una frase cerrada. */
        data class Frase(val frase: FraseOida) : Oido()
    }

    /** Una frase cerrada por Vosk (o por [LARGO_MS]). */
    data class FraseOida(
        /** Su número, desde 0 y seguidos. */
        val n: Long,
        /** El milisegundo de la grabación en que empieza su primera palabra. */
        val ms: Long,
        /** El milisegundo de la grabación en que acaba su última palabra. */
        val finVozMs: Long,
        /** Lo oído después de [finVozMs] cuando se cerró: restado al momento en que sale, da cuándo se dejó de hablar. */
        val calladoAlFinalMs: Long,
        val texto: String
    )

    /**
     * **Un reconocedor de corriente**: lo que hace falta de Vosk (así la [Corriente] se prueba con uno de
     * mentira). Los tiempos de las palabras cuentan desde que se creó, **también después de [acabar]**
     * (como Vosk, medido en el PC el 1-nov-2026).
     */
    interface Reconocedor {
        /** Le da audio (mono, 16 kHz). true: cerró una frase (pedir [resultado]). */
        fun comer(muestras: ShortArray): Boolean
        /** La frase cerrada, en el JSON de Vosk. */
        fun resultado(): String
        /** Lo que va oyendo, en el JSON de Vosk (`{"partial": "…"}`). */
        fun parcial(): String
        /** Cierra lo que haya (y sigue oyendo). */
        fun acabar(): String
    }

    private fun msDe(marcos: Long, hz: Int): Long = marcos * 1000 / hz.coerceAtLeast(1)
    private fun muestrasDe(ms: Long): Int = (ms.coerceAtLeast(0) * HERCIOS / 1000).toInt()

    /** Si un trozo (16 kHz) tiene voz: algún pedazo de 20 ms por encima del umbral de [OidoEnVivo]. */
    internal fun conVoz(t: ShortArray): Boolean {
        val paso = HERCIOS / 50
        var i = 0
        while (i < t.size) {
            val fin = minOf(t.size, i + paso)
            var s = 0.0
            for (k in i until fin) { val x = t[k].toDouble(); s += x * x }
            if (sqrt(s / (fin - i).coerceAtLeast(1)) >= OidoEnVivo.RMS_VOZ) return true
            i = fin
        }
        return false
    }

    /**
     * **De los bloques de la grabación a lo oído**: junta los bloques (mono, con su posición en marcos, como
     * el [OidoEnVivo.Frasero]), los pasa a 16 kHz, se los da al reconocedor de 100 en 100 ms, mira el
     * parcial cada [CADA_PARCIAL_MS] y traduce lo que diga Vosk a [Oido]: un parcial nuevo o una frase con
     * el milisegundo de la grabación en que empieza y en que se dejó de hablar. Un hueco corto se rellena
     * con silencio; uno largo (la batería justa, la cámara del PC) cierra la frase y lo que sigue va en su
     * minuto. [idioma] («es-ES») es para tirar lo que no es creíble ([Transcriptor.creible]).
     */
    class Corriente(
        private val rec: Reconocedor,
        private val idioma: String,
        private val creible: (String, String) -> Boolean = { t, i -> Transcriptor.creible(t, i) }
    ) {
        /** El muestreo de los bloques (0: aún nada). */
        private var hz = 0
        /** Dónde debería empezar el siguiente bloque (marcos). */
        private var esperado: Long? = null
        /** Lo que hay que sumar a un tiempo de Vosk para tener el milisegundo de la grabación. */
        private var desfaseMs = 0L
        /** Lo dado a Vosk desde que se creó (muestras a 16 kHz). */
        private var dado = 0L
        /** Lo que espera a completar un trozo. */
        private var pendiente = ShortArray(0)
        /** Lo dado desde el último parcial. */
        private var desdeParcial = 0L
        private var ultimoParcial = ""
        /** Desde cuándo (ms de Vosk) hay algo a medias: el primer parcial con texto tras la última frase. */
        private var aMediasDesde: Long? = null
        /** Lo callado seguido al final de lo dado (ms). */
        private var calladoMs = 0L
        /** Se le dio audio desde la última frase cerrada (si no, no hay nada que cerrar). */
        private var sinCerrar = false
        private var n = 0L
        /** Lo tirado por no ser creíble. */
        var descartadas = 0L
            private set

        private fun oidoVoskMs(): Long = msDe(dado, HERCIOS)

        /** El milisegundo de la grabación hasta donde se ha oído. */
        fun oidoHastaMs(): Long = desfaseMs + msDe(dado + pendiente.size, HERCIOS)

        /** **Mete un bloque** (mono; [posicion] en marcos). Devuelve lo que salió (casi siempre nada). */
        fun meter(posicion: Long, muestreo: Int, muestras: ShortArray, cuantas: Int = muestras.size): List<Oido> {
            val salida = ArrayList<Oido>()
            if (muestreo <= 0 || cuantas <= 0) return salida
            if (hz != muestreo) {
                cerrar(salida)
                hz = muestreo
                esperado = null
            }
            var desde = 0
            var pos = posicion
            val e = esperado
            when {
                e == null -> {
                    cerrar(salida)
                    desfaseMs = msDe(pos, muestreo) - oidoVoskMs()
                }
                pos > e -> {
                    val hueco = msDe(pos - e, muestreo)
                    if (hueco <= HUECO_MAXIMO_MS) dar(ShortArray(muestrasDe(hueco)), salida)
                    else {
                        // Lo dicho se cierra ahí; lo que venga va en su minuto.
                        cerrar(salida)
                        desfaseMs = msDe(pos, muestreo) - oidoVoskMs()
                    }
                }
                pos < e -> {
                    // Lo repetido ya se oyó.
                    val saltar = e - pos
                    if (saltar >= cuantas) return salida
                    desde = saltar.toInt()
                    pos = e
                }
            }
            esperado = pos + (cuantas - desde)
            dar(OidoEnVivo.a16k(muestras, desde, cuantas, muestreo), salida)
            return salida
        }

        /** **Al acabar la grabación**: lo que quedara a medias. */
        fun acabar(): List<Oido> {
            val salida = ArrayList<Oido>()
            cerrar(salida)
            esperado = null
            return salida
        }

        /** Da audio a 16 kHz, de trozo en trozo. */
        private fun dar(a16: ShortArray, salida: MutableList<Oido>) {
            val todo = if (pendiente.isEmpty()) a16 else pendiente + a16
            val trozo = muestrasDe(TROZO_MS)
            var i = 0
            while (todo.size - i >= trozo) {
                comer(todo.copyOfRange(i, i + trozo), salida)
                i += trozo
            }
            pendiente = if (i == 0) todo else todo.copyOfRange(i, todo.size)
        }

        private fun comer(t: ShortArray, salida: MutableList<Oido>) {
            val cerrada = rec.comer(t)
            sinCerrar = true
            dado += t.size
            desdeParcial += t.size
            calladoMs = if (conVoz(t)) 0 else calladoMs + msDe(t.size.toLong(), HERCIOS)
            if (cerrada) {
                frase(rec.resultado(), salida)
                return
            }
            // Una frase larga se cierra en la primera pausa (o pasado el máximo).
            val desde = aMediasDesde
            if (desde != null) {
                val largo = oidoVoskMs() - desde
                if ((largo >= LARGO_MS && calladoMs >= PAUSA_MS) || largo >= MAXIMO_MS) {
                    frase(rec.acabar(), salida)
                    return
                }
            }
            if (desdeParcial >= muestrasDe(CADA_PARCIAL_MS)) {
                desdeParcial = 0
                val p = textoDelParcial(rec.parcial())
                if (p.isNotEmpty() && aMediasDesde == null) aMediasDesde = oidoVoskMs()
                parcial(p, salida)
            }
        }

        private fun parcial(p: String, salida: MutableList<Oido>) {
            if (p != ultimoParcial) {
                ultimoParcial = p
                salida += Oido.Parcial(p)
            }
        }

        /** Lo que quede a medias, cerrado. */
        private fun cerrar(salida: MutableList<Oido>) {
            if (hz == 0) return
            if (pendiente.isNotEmpty()) {
                val t = pendiente
                pendiente = ShortArray(0)
                comer(t, salida)
            }
            if (sinCerrar) frase(rec.acabar(), salida)
            parcial("", salida)
            desdeParcial = 0
        }

        /** Una frase cerrada, si dice algo creíble. */
        private fun frase(json: String, salida: MutableList<Oido>) {
            sinCerrar = false
            aMediasDesde = null
            // Ya no hay nada a medias.
            parcial("", salida)
            val l = leerResultado(json) ?: return
            if (!creible(l.texto, idioma)) {
                descartadas++
                return
            }
            val oido = oidoVoskMs()
            val inicio = (l.inicioMs ?: oido).coerceAtMost(oido)
            val fin = (l.finMs ?: oido).coerceIn(inicio, oido)
            salida += Oido.Frase(FraseOida(n, desfaseMs + inicio, desfaseMs + fin, oido - fin, l.texto))
            n++
        }
    }

    /** Lo leído de un resultado de Vosk: el texto y dónde empieza la primera palabra y acaba la última. */
    data class Leido(val texto: String, val inicioMs: Long?, val finMs: Long?)

    private val json = Json { ignoreUnknownKeys = true }

    private fun objeto(texto: String): JsonObject? =
        runCatching { json.parseToJsonElement(texto) as? JsonObject }.getOrNull()

    /** `{"text": "…", "result": [{"word", "start", "end", "conf"}]}` → lo leído; null si no dice nada. */
    fun leerResultado(texto: String): Leido? {
        val v = objeto(texto) ?: return null
        val t = (v["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
        if (t.isEmpty()) return null
        val palabras = v["result"] as? JsonArray
        fun ms(p: Any?, campo: String): Long? =
            ((p as? JsonObject)?.get(campo) as? JsonPrimitive)?.doubleOrNull?.let { (it * 1000).roundToLong() }
        return Leido(t, ms(palabras?.firstOrNull(), "start"), ms(palabras?.lastOrNull(), "end"))
    }

    /** `{"partial": "…"}` → el texto (vacío si no hay o no se entiende). */
    fun textoDelParcial(texto: String): String =
        (objeto(texto)?.get("partial") as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
}
