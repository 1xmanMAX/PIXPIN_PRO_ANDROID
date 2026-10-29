package com.forge.pixpin.guardados

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.forge.pixpin.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * **«Sincronizar sola»** (29-oct-2026, como el PC de 058ae2b7: `musica::sincronizar_sola`,
 * `tras_pegar`, `latido_sola`): Whisper oye la canción en su hilo ([OirCancion]) y la letra pegada
 * **se alinea** con lo oído ([AlinearLetra]); cada línea se lleva un minuto y se guarda en el mismo
 * `[m:ss] línea` de siempre, así que el PC la canta sin cambiar nada.
 *
 * - **El texto guardado es siempre el pegado**, nunca lo que oyó Whisper.
 * - **Arranca sola al pegar** una letra sin tiempos si el reconocedor ya está bajado ([trasPegar]);
 *   si no, el aviso dice que su botón lo baja (una vez, unos 160 MB). Bajar sin preguntar, no.
 * - **Su avance** se ve en una pastilla bajo la cabecera del karaoke ([enMarcha]: «Sincronizando la
 *   letra… 40 %», o «Bajando el reconocedor de voz… 40 %»).
 * - **Pulsar otra vez el botón la para** ([alternar]), y **cerrar la pantalla también** ([parar],
 *   desde [EstudiarClaseActivity.onDestroy]; girar el teléfono no).
 * - Al acabar, el aviso dice cuántas líneas se oyeron («12 de 30») y, con menos de un tercio, que
 *   **va a ojo** ([AlinearLetra.vaAOjo]). **Si la letra se editó mientras, no se guarda.**
 *
 * Una a la vez (el reconocedor no se reparte). El resultado se guarda desde aquí, no desde la
 * pantalla: así llega aunque la pantalla se haya rehecho al girar.
 */
object SincronizarSola {

    /** Lo que se está sincronizando: de qué nota, con qué letra y por dónde va. */
    class EnMarcha(val id: String, val original: String?, val lineas: List<String>, bajando: Boolean) {
        val avance = MutableStateFlow(0f)
        val bajando = MutableStateFlow(bajando)
        internal val cancelado = AtomicBoolean(false)
    }

    private val _enMarcha = MutableStateFlow<EnMarcha?>(null)

    /** La sincronización en marcha, si hay una. */
    val enMarcha: StateFlow<EnMarcha?> get() = _enMarcha

    private val principal = Handler(Looper.getMainLooper())

    /** Por qué no arrancó (cada uno con su aviso). */
    enum class NoArranca { SIN_LETRA, SIN_AUDIO, SIN_RECONOCEDOR, FALTA_MODELO, OCUPADA }

    /** Lo que pesa el reconocedor que se bajaría, en MB (el de Ajustes; 161 el `base`). */
    fun megasDelModelo(context: Context): Int = MotorWhisper.MODELOS[MotorWhisper.modelo(context)] ?: 160

    /**
     * **El botón de los subtítulos**: si ya se sincroniza esta canción, se para; si no, empieza (y
     * baja el reconocedor si falta). Devuelve el aviso que dar.
     */
    fun alternar(context: Context, m: Mensaje): String {
        val ahora = _enMarcha.value
        if (ahora != null) {
            if (ahora.id == m.id) { parar(); return context.getString(R.string.letra_sola_parada) }
            return context.getString(R.string.letra_sola_ocupada)
        }
        return when (val no = empezar(context, m, sePuedeBajar = true)) {
            null -> context.getString(R.string.letra_sola_empieza)
            else -> aviso(context, no)
        }
    }

    /**
     * **Tras pegar o escribir la letra de una canción**: si no lleva tiempos, se sincroniza sola, solo
     * si el reconocedor ya está bajado (bajar 160 MB sin preguntar, no; entonces se dice que su botón
     * lo hace). [guardada]: la letra tal como se guardó. Devuelve el aviso, si hay que dar alguno.
     */
    fun trasPegar(context: Context, m: Mensaje, guardada: String): String? {
        if (!m.esMusica || _enMarcha.value != null) return null
        if (guardada.isBlank() || LetraDeCancion.leer(guardada).conTiempos) return null
        return when (val no = empezar(context, m.copy(transcripcion = guardada), sePuedeBajar = false)) {
            null -> context.getString(R.string.letra_sola_empieza)
            NoArranca.FALTA_MODELO -> aviso(context, no)
            else -> null
        }
    }

    /** **Para** lo que se esté sincronizando (no se guarda nada). */
    fun parar() {
        _enMarcha.value?.cancelado?.set(true)
        _enMarcha.value = null
    }

    private fun aviso(context: Context, no: NoArranca): String = when (no) {
        NoArranca.SIN_LETRA -> context.getString(R.string.letra_sincronizar_primero)
        NoArranca.SIN_AUDIO -> context.getString(R.string.estudiar_sin_sonido)
        NoArranca.SIN_RECONOCEDOR -> context.getString(R.string.letra_sola_sin_reconocedor)
        NoArranca.FALTA_MODELO -> context.getString(R.string.letra_sola_falta_modelo, megasDelModelo(context))
        NoArranca.OCUPADA -> context.getString(R.string.letra_sola_ocupada)
    }

    /** Arranca el hilo; null si arrancó, o por qué no. */
    private fun empezar(context: Context, m: Mensaje, sePuedeBajar: Boolean): NoArranca? {
        val app = context.applicationContext
        val original = m.transcripcion
        val lineas = AlinearLetra.lineasDeLaLetra(original.orEmpty())
        if (lineas.isEmpty()) return NoArranca.SIN_LETRA
        val audio = m.ruta?.let { File(it) }?.takeIf { it.isFile } ?: return NoArranca.SIN_AUDIO
        if (!MotorWhisper.soportado()) return NoArranca.SIN_RECONOCEDOR
        val bajar = !MotorWhisper.modeloListo(app)
        if (bajar && !sePuedeBajar) return NoArranca.FALTA_MODELO
        val e = EnMarcha(m.id, original, lineas, bajar)
        synchronized(this) {
            if (_enMarcha.value != null) return NoArranca.OCUPADA
            _enMarcha.value = e
        }
        Thread({ trabajar(app, e, audio) }, "pixpin-letra-sola").apply { isDaemon = true }.start()
        return null
    }

    private fun trabajar(app: Context, e: EnMarcha, audio: File) {
        var aviso: String? = null
        try {
            var bajado = true
            if (e.bajando.value) {
                bajado = MotorWhisper.asegurarModelo(app) { e.avance.value = it } != null
                e.bajando.value = false
                e.avance.value = 0f
            }
            aviso = when {
                e.cancelado.get() -> null
                !bajado -> app.getString(R.string.letra_sola_sin_red)
                else -> oirYGuardar(app, e, audio)
            }
        } catch (t: Throwable) {
            android.util.Log.e("PixPinVoz", "sincronizar la letra sola", t)
            if (!e.cancelado.get()) aviso = app.getString(R.string.letra_sola_fallo)
        }
        val dar = aviso
        principal.post {
            if (_enMarcha.value === e) _enMarcha.value = null
            if (dar != null) Toast.makeText(app, dar, Toast.LENGTH_LONG).show()
        }
    }

    /** Oye, alinea y guarda (si la letra no cambió). El aviso de cómo fue, o null si se paró. */
    private fun oirYGuardar(app: Context, e: EnMarcha, audio: File): String? {
        val t0 = System.currentTimeMillis()
        val oido = OirCancion.oir(app, audio, e.lineas, { e.avance.value = it.coerceIn(0f, 1f) }, e.cancelado)
        if (e.cancelado.get()) return null
        val alineado = when (oido) {
            is OirCancion.Oido.Texto -> AlinearLetra.alinear(e.lineas, AlinearLetra.palabrasOidas(oido.trozos), oido.duracionMs.takeIf { it > 0 })
                .also { a ->
                    android.util.Log.i(
                        "PixPinVoz",
                        "letra sincronizada sola: ${(System.currentTimeMillis() - t0) / 1000} s, ${oido.trozos.size} tandas, " +
                            "${e.lineas.size} líneas, ${a?.ancladas ?: 0} ancladas"
                    )
                } ?: return app.getString(R.string.letra_sola_no_se_oye)
            OirCancion.Oido.Nada -> return app.getString(R.string.letra_sola_no_se_oye)
            OirCancion.Oido.Cancelada -> return null
            OirCancion.Oido.SinModelo -> return app.getString(R.string.letra_sola_sin_red)
            is OirCancion.Oido.Fallo -> {
                android.util.Log.w("PixPinVoz", "no se pudo sincronizar la letra sola: ${oido.codigo} ${oido.detalle}")
                return app.getString(R.string.letra_sola_fallo)
            }
        }
        val texto = LetraDeCancion.aGuardar(alineado.letra(e.lineas))
        var puesta = false
        MensajesStore(app).actualizar(e.id) {
            if (it.transcripcion != e.original) it
            else { puesta = true; it.copy(transcripcion = texto, estadoDelTexto = TEXTO_LETRA) }
        }
        if (!puesta) return app.getString(R.string.letra_sola_cambio)
        return app.getString(
            if (alineado.aOjo) R.string.letra_sola_hecha_poca else R.string.letra_sola_hecha,
            alineado.ancladas, alineado.lineas.size
        )
    }
}
