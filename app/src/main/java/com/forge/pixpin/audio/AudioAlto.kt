package com.forge.pixpin.audio

import android.content.Context
import android.media.MediaPlayer
import android.media.audiofx.LoudnessEnhancer
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.OutputStream
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * **Lo grabado bajito se oye más alto** (20-oct-2026, PC `volumen.rs`). En el PC se escribe una copia
 * realzada antes de abrirla; aquí `MediaPlayer` deja enganchar a su sesión un [LoudnessEnhancer], que
 * sube y limita en vivo: el audio dura lo mismo, la barra, saltar y la velocidad siguen igual, y no
 * hace falta esperar a ninguna copia.
 *
 * La ganancia sale de medir el audio ([Volumen]) **fuera del hilo de la pantalla**: la primera vez
 * empieza a sonar tal cual y sube en cuanto se sabe (unas décimas); se apunta por archivo (ruta, peso
 * y fecha) en `cache/pixpin-audio-alto/` y la vez siguiente sube desde el principio. Lo que ya suena
 * fuerte no se toca.
 */
object AudioAlto {
    private val enganchados = WeakHashMap<MediaPlayer, LoudnessEnhancer>()
    private val vivos = java.util.Collections.newSetFromMap(WeakHashMap<MediaPlayer, Boolean>())
    private val medidas = ConcurrentHashMap<String, Float>()
    private val hilo = Executors.newSingleThreadExecutor { r -> Thread(r, "audio-alto").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    private val principal = Handler(Looper.getMainLooper())
    @Volatile private var carpeta: File? = null

    fun iniciar(c: Context) { if (carpeta == null) carpeta = File(c.cacheDir, "pixpin-audio-alto").apply { mkdirs() } }

    private fun clave(ruta: String): String? {
        val f = File(ruta)
        if (!f.isFile) return null
        return "${ruta}|${f.length()}|${f.lastModified()}".hashCode().toUInt().toString(16)
    }

    /** La ganancia ya medida de [ruta], o null. */
    fun medida(ruta: String): Float? {
        val k = clave(ruta) ?: return null
        medidas[k]?.let { return it }
        val apunte = carpeta?.let { File(it, "$k.g") }?.takeIf { it.isFile } ?: return null
        return apunte.readText().trim().toFloatOrNull()?.also { medidas[k] = it }
    }

    /** Mide [ruta] (bloquea: llamar fuera de la pantalla) y lo apunta. */
    fun medir(ruta: String): Float {
        medida(ruta)?.let { return it }
        val k = clave(ruta) ?: return 1f
        val v = Volumen()
        val salida = object : OutputStream() {
            private var bajo = -1
            override fun write(b: Int) {
                if (bajo < 0) { bajo = b and 0xFF; return }
                v.anadir16(((b shl 8) or bajo).toShort())
                bajo = -1
            }
        }
        val g = runCatching { com.forge.pixpin.guardados.Transcriptor.decodificar(File(ruta), salida.buffered(64 * 1024)); v.ganancia() }.getOrDefault(1f)
        medidas[k] = g
        carpeta?.let { runCatching { File(it, "$k.g").writeText(g.toString()) } }
        return g
    }

    /**
     * **Engancha el realce a [p]**, ya preparado con [ruta]. Llamar justo después de `prepare()`; y
     * [soltar] antes de `release()`.
     */
    fun enganchar(p: MediaPlayer, ruta: String) { com.forge.pixpin.PixPinApp.instancia?.let { enganchar(it, p, ruta) } }

    fun enganchar(c: Context, p: MediaPlayer, ruta: String) {
        if (System.getProperty("pixpin.audio.sin_realce") != null) return
        iniciar(c.applicationContext)
        synchronized(vivos) { vivos.add(p) }
        val ya = medida(ruta)
        if (ya != null) { poner(p, ya); return }
        hilo.execute {
            val g = medir(ruta)
            principal.post { poner(p, g) }
        }
    }

    private fun poner(p: MediaPlayer, g: Float) {
        if (g < Volumen.GANANCIA_MINIMA || synchronized(vivos) { p !in vivos }) return
        runCatching {
            val e = synchronized(enganchados) { enganchados[p] } ?: LoudnessEnhancer(p.audioSessionId).also { synchronized(enganchados) { enganchados[p] = it } }
            e.setTargetGain(Volumen.aMilibelios(g))
            e.enabled = true
        }
    }

    fun soltar(p: MediaPlayer?) {
        p ?: return
        synchronized(vivos) { vivos.remove(p) }
        synchronized(enganchados) { enganchados.remove(p) }?.let { runCatching { it.release() } }
    }
}
