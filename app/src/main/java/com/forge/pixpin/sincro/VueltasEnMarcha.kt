package com.forge.pixpin.sincro

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * **Cuántas vueltas de sincronizar hay en marcha y cómo acabó la última** (25-oct-2026, lo del PC
 * `0470294`; el usuario: «mientras se sincroniza, que el botón gire»).
 *
 * El botón de sincronizar ([com.forge.pixpin.ui.IconoDeSincronizar]) **gira** mientras haya
 * **cualquier** vuelta en marcha —la pulsada en la pantalla de Sincronizar, la automática, la de
 * las tareas al instante, la de los chats compartidos con otras personas y la que pide el otro
 * aparato al llamar a este— y lleva una **marca roja con «!»** si la última falló, hasta que se
 * pulsa (la pantalla de Sincronizar dice qué pasó) o empieza otra. Cancelar no cuenta como fallo,
 * ni «ocupado» (se intenta en la próxima). Las sondas de «¿estás ahí?» no cuentan: duran un instante.
 *
 * Se cuenta en un solo sitio, al ocupar y soltar este aparato ([Protocolo.ocupar] y
 * [Protocolo.soltar]), que es por donde pasa toda sesión, venga de donde venga. Cómo acabó lo
 * apunta quien lleva la vuelta ([apuntar]), que es quien lo sabe —también si ni llegó a conectar—;
 * la pantalla de Sincronizar, además, cuenta toda su ronda como una ([empezar] y [acabar]) para que
 * el botón no se pare entre aparato y aparato.
 *
 * La cuenta es [Cuenta], pura, para probarla sin Android (`VueltasEnMarchaTest`).
 */
object VueltasEnMarcha {
    /** Como acabó la última vuelta que dijo cómo acababa. */
    enum class Ultima { NADA, BIEN, MAL }

    /**
     * La cuenta, como `EnCurso` y `ULTIMA_VUELTA` del PC: una vuelta nueva cuando no había ninguna
     * tapa el fallo de la anterior (si vuelve a fallar, se dirá otra vez al acabar).
     */
    class Cuenta {
        var enCurso = 0
            private set
        var ultima = Ultima.NADA
            private set

        fun empezar() {
            if (enCurso++ == 0) ultima = Ultima.NADA
        }

        fun acabar() {
            if (enCurso > 0) enCurso--
        }

        fun apuntar(bien: Boolean) {
            ultima = if (bien) Ultima.BIEN else Ultima.MAL
        }

        /** Ya se vio el fallo (se pulsó el botón): vuelve a ser el de siempre. */
        fun olvidarFallo() {
            if (ultima == Ultima.MAL) ultima = Ultima.NADA
        }

        val gira: Boolean get() = enCurso > 0
        val fallo: Boolean get() = ultima == Ultima.MAL
    }

    data class Estado(val gira: Boolean = false, val fallo: Boolean = false)

    private val cuenta = Cuenta()
    private val _estado = MutableStateFlow(Estado())

    /** Lo que pinta el botón. */
    val estado: StateFlow<Estado> = _estado.asStateFlow()

    private fun publicar() {
        _estado.value = Estado(cuenta.gira, cuenta.fallo)
    }

    @Synchronized fun empezar() { cuenta.empezar(); publicar() }

    @Synchronized fun acabar() { cuenta.acabar(); publicar() }

    @Synchronized fun apuntar(bien: Boolean) { cuenta.apuntar(bien); publicar() }

    @Synchronized fun olvidarFallo() { cuenta.olvidarFallo(); publicar() }

    /**
     * **Cómo acabó una vuelta que lanzó una excepción**: «ocupado» (el otro o este estaban con otra)
     * y cancelar no son fallos; lo demás sí.
     */
    fun fallo(e: Throwable): Boolean =
        e !is kotlinx.coroutines.CancellationException && e !is InterruptedException && e.message != Protocolo.OCUPADO

    /** Apunta un fallo si [e] lo es ([fallo]). */
    fun apuntarSiFallo(e: Throwable) {
        if (fallo(e)) apuntar(false)
    }
}
