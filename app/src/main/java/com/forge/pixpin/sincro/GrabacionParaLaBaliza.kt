package com.forge.pixpin.sincro

import com.forge.pixpin.guardados.GrabarClase

/**
 * **Lo que cuenta de su grabación un servicio que graba** (28-oct-2026), para la baliza
 * ([BalizaDelMovil]): «Grabar clase» ([com.forge.pixpin.guardados.GrabarClaseService]) y la reunión
 * presencial ([com.forge.pixpin.guardados.ReunionPresencialService]). Cada uno lo engancha con tres
 * llamadas y nada más:
 *
 * - al arrancar el reloj, [BalizaDelMovil.empezo];
 * - al pausar o seguir, [BalizaDelMovil.cambio];
 * - al terminar (o tirarla), [BalizaDelMovil.acabo], y ya guardada, [BalizaDelMovil.guardada].
 *
 * Con eso el PC oye «el teléfono graba» al momento y cada segundo (con `ms_actual` y el id), y la
 * sincronización automática se aparta mientras tanto ([ModoPrioritario]).
 */
interface GrabacionParaLaBaliza {
    /** El id de la grabación (`m-<hora de empezar>`), como el `g-…` del PC. */
    val id: String
    val enPausa: Boolean
    /** En qué milisegundo de la grabación va. */
    fun msActual(): Long
    /** Si acepta fotos y clips de otros aparatos (`camara1`): de momento, no. */
    val camara: Boolean get() = false

    companion object {
        /** La de un servicio con su [GrabarClase.Cronometro] (null mientras arranca). */
        fun de(idDeLaGrabacion: String, cronometro: () -> GrabarClase.Cronometro?): GrabacionParaLaBaliza = object : GrabacionParaLaBaliza {
            override val id: String = idDeLaGrabacion
            override val enPausa: Boolean get() = cronometro()?.enPausa == true
            override fun msActual(): Long = cronometro()?.llevadoMs() ?: 0L
        }

        /** El id de una grabación que empieza [ahora]. */
        fun idNuevo(ahora: Long = System.currentTimeMillis()) = "m-$ahora"
    }
}
