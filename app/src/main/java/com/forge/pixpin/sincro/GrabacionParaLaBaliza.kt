package com.forge.pixpin.sincro

/**
 * **Lo que cuenta de su grabación este teléfono** (28-oct-2026), para la baliza ([BalizaDelMovil]).
 * Lo engancha [GrabacionDelMovil], por donde ya pasan «Grabar clase» y la reunión presencial:
 *
 * - al empezar, [BalizaDelMovil.empezo];
 * - al pausar o seguir, [BalizaDelMovil.cambio];
 * - al acabar (o tirarla), [BalizaDelMovil.acabo], y ya guardada, [BalizaDelMovil.guardada].
 *
 * Con eso el PC oye «el teléfono graba» al momento y cada segundo (con `ms_actual` y el id), y la
 * sincronización automática se aparta mientras tanto ([ModoPrioritario]).
 */
interface GrabacionParaLaBaliza {
    /** El id de la grabación (`m-…`, el mismo que va en `camara_remota.suya`), como el `g-…` del PC. */
    val id: String
    val enPausa: Boolean
    /** En qué milisegundo de la grabación va. */
    fun msActual(): Long
    /** Si acepta fotos y clips de otros aparatos (`camara1`): de momento, no. */
    val camara: Boolean get() = false

    companion object {
        /** La de [GrabacionDelMovil]: [llevado] dice por dónde va y si está en pausa (null si ya no). */
        fun de(idDeLaGrabacion: String, llevado: () -> Pair<Long, Boolean>?): GrabacionParaLaBaliza = object : GrabacionParaLaBaliza {
            override val id: String = idDeLaGrabacion
            override val enPausa: Boolean get() = llevado()?.second == true
            override fun msActual(): Long = llevado()?.first ?: 0L
        }
    }
}
