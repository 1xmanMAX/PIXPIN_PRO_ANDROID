package com.forge.pixpin.guardados

import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * **El reproductor de la aplicación: uno para todo.**
 *
 * Antes cada chat tenía su `MediaPlayer` y una nota de voz era un botón de play y poco
 * más: ni adelantar, ni atrasar, ni velocidad, y al salir del chat se cortaba. Es lo que
 * Telegram resuelve con su barra de reproducción arriba de la conversación, que sigue
 * mientras uno lee, y con controles de verdad. Aquí vive el estado —qué suena, por dónde
 * va, a qué velocidad— como un flujo, y lo pintan la barra ([BarraDelReproductor]), las
 * burbujas de audio, la biblioteca y la pantalla de la letra. Lo pidió el usuario
 * (5-sep-2026).
 */
object Reproductor {

    /** Lo que se sabe de lo que suena. [ruta] nula: no hay nada cargado. */
    data class Estado(
        val ruta: String? = null,
        val titulo: String = "",
        val sonando: Boolean = false,
        val posicionMs: Int = 0,
        val duracionMs: Int = 0,
        val velocidad: Float = 1f
    )

    val estado = MutableStateFlow(Estado())

    /** Cuánto se salta con los botones de adelante y atrás. */
    const val SALTO_MS = 10_000

    /** Las velocidades por las que pasa el botón, en orden. */
    val VELOCIDADES = listOf(1f, 1.25f, 1.5f, 2f, 0.75f)

    private var reproductor: MediaPlayer? = null
    private val reloj = Handler(Looper.getMainLooper())
    private val latido = object : Runnable {
        override fun run() {
            val p = reproductor ?: return
            val e = estado.value
            if (e.sonando) {
                estado.value = e.copy(posicionMs = runCatching { p.currentPosition }.getOrDefault(e.posicionMs))
                reloj.postDelayed(this, 250)
            }
        }
    }

    /** Toca lo que ya suena: pausa o sigue. Toca otra cosa: la carga y arranca. */
    fun alternar(ruta: String, titulo: String) {
        val e = estado.value
        if (e.ruta == ruta && reproductor != null) {
            if (e.sonando) pausar() else seguir()
            return
        }
        cargar(ruta, titulo, arrancar = true)
    }

    fun cargar(ruta: String, titulo: String, arrancar: Boolean) {
        parar()
        val p = runCatching {
            MediaPlayer().apply {
                setDataSource(ruta)
                prepare()
                setOnCompletionListener {
                    estado.value = estado.value.copy(sonando = false, posicionMs = 0)
                    runCatching { it.seekTo(0) }
                }
            }
        }.getOrNull() ?: return
        reproductor = p
        val velocidad = estado.value.velocidad
        estado.value = Estado(ruta, titulo, false, 0, runCatching { p.duration }.getOrDefault(0), velocidad)
        if (arrancar) seguir()
    }

    fun seguir() {
        val p = reproductor ?: return
        runCatching {
            // La velocidad se pone al arrancar: ponerla parado arranca solo en algunos aparatos.
            p.playbackParams = PlaybackParams().setSpeed(estado.value.velocidad)
            p.start()
        }
        estado.value = estado.value.copy(sonando = true)
        reloj.removeCallbacks(latido)
        reloj.post(latido)
    }

    fun pausar() {
        val p = reproductor ?: return
        runCatching { p.pause() }
        reloj.removeCallbacks(latido)
        estado.value = estado.value.copy(sonando = false, posicionMs = runCatching { p.currentPosition }.getOrDefault(0))
    }

    /** Descarga lo que hubiera: la barra desaparece. */
    fun parar() {
        reloj.removeCallbacks(latido)
        runCatching { reproductor?.release() }
        reproductor = null
        estado.value = Estado(velocidad = estado.value.velocidad)
    }

    /** Adelanta o atrasa [ms] (negativo para atrás). */
    fun saltar(ms: Int) {
        val p = reproductor ?: return
        val total = estado.value.duracionMs
        val destino = (estado.value.posicionMs + ms).coerceIn(0, total.coerceAtLeast(0))
        runCatching { p.seekTo(destino) }
        estado.value = estado.value.copy(posicionMs = destino)
    }

    /** Va a un punto, de 0 a 1. */
    fun irA(fraccion: Float) {
        val total = estado.value.duracionMs
        if (total <= 0) return
        val p = reproductor ?: return
        val destino = (total * fraccion.coerceIn(0f, 1f)).toInt()
        runCatching { p.seekTo(destino) }
        estado.value = estado.value.copy(posicionMs = destino)
    }

    /** La siguiente velocidad de la lista. */
    fun otraVelocidad() {
        val actual = estado.value.velocidad
        val i = VELOCIDADES.indexOf(actual)
        val nueva = VELOCIDADES[(i + 1) % VELOCIDADES.size]
        estado.value = estado.value.copy(velocidad = nueva)
        val p = reproductor ?: return
        if (estado.value.sonando) runCatching { p.playbackParams = PlaybackParams().setSpeed(nueva) }
    }

    /** Por dónde va, de 0 a 1. */
    fun fraccion(): Float {
        val e = estado.value
        return if (e.duracionMs <= 0) 0f else (e.posicionMs.toFloat() / e.duracionMs).coerceIn(0f, 1f)
    }
}
