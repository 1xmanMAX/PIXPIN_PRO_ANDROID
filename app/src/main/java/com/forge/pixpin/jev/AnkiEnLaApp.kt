package com.forge.pixpin.jev

import android.content.Context
import com.forge.pixpin.anki.Tarjetas

/**
 * **A Anki solo las tarjetas de la clase que valen la pena** (28-oct-2026, `ventana_chat/anki.rs` del PC,
 * `6f70a416`): lo dicho en el tramo de cada captura se valora de 0 a 3 y si dice que entra en el examen
 * ([Anki.valorar]); la tabla de [Anki.elegir] decide. Lo usa «Enviar a Anki»
 * ([com.forge.pixpin.anki.EnviarAAnkiActivity]). **Sin clave, con la función apagada o si falla, van
 * todas, como siempre.** Se manda el título y lo dicho en cada tramo; las imágenes no.
 */
object AnkiEnLaApp {

    /** Cuántas tarjetas se valoran a la vez. */
    private const val A_LA_VEZ = 4

    /**
     * **El filtro de una clase** para [com.forge.pixpin.anki.AnkiDelMensaje.tarjetasYQuitadas], o null
     * sin Jev. Lo que devuelve da null si Jev falla (entonces van todas). Trabajo de red: fuera del hilo
     * principal.
     */
    fun valer(context: Context, titulo: String): ((List<Pair<Long, String>>) -> List<Boolean>?)? {
        val cliente = Jev.para(context, Jev.Funcion.ANKI) ?: return null
        return { tramos -> elegir(cliente, titulo, tramos) }
    }

    /** Valora y elige; null si algo falla (un fallo nunca deja fuera una tarjeta). */
    fun elegir(cliente: Cliente, titulo: String, tramos: List<Pair<Long, String>>): List<Boolean>? = runCatching {
        Anki.elegir(Anki.valorar(cliente, titulo, tramos.map { (ms, dicho) -> Anki.Tramo(Tarjetas.minuto(ms), dicho) }, A_LA_VEZ))
    }.onFailure { runCatching { android.util.Log.w("PixPinJev", "sin valorar las tarjetas, van todas: ${it.message}") } }.getOrNull()

    /** Lo que se dice al final del aviso (`anki-jev-quitadas` del PC); nada si no quitó ninguna. */
    fun aviso(quitadas: Int): String = if (quitadas > 0) " · Jev dejó fuera $quitadas capturas sin nada que estudiar" else ""
}
