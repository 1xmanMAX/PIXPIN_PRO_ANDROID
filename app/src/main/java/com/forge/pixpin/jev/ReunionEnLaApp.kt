package com.forge.pixpin.jev

import android.content.Context
import com.forge.pixpin.guardados.EstudiarClase
import com.forge.pixpin.guardados.Importantes
import com.forge.pixpin.guardados.Mensaje
import com.forge.pixpin.guardados.MensajesStore
import com.forge.pixpin.guardados.esMusica
import com.forge.pixpin.guardados.tituloDeAudio
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * **Lo importante de una clase o reunión, en el teléfono** (28-oct-2026, `apps/pixpin/src/jev/reunion.rs`
 * del PC, `4c144080`). Con la clave del usuario, al acabar de pasar a texto una nota de voz
 * ([com.forge.pixpin.guardados.PasarATexto]) cada renglón de la transcripción va a Jev con sus vecinos
 * y el título, en segundo plano y cuatro a la vez ([Reunion.buscar]); lo hallado se escribe en la nota
 * ([Mensaje.importantes], [Mensaje.importantesDe]), que viaja al PC. «Estudiar la clase» y la página
 * compartida lo enseñan con sus puntos de color y su filtro; las tareas se apuntan en Tareas ([aTareas]).
 *
 * **Sin clave, con la función apagada o si algo falla, la nota se queda como estaba** (sin
 * `importantes`): un fallo no se guarda como «no hay nada». Volver a pasarla a texto lo rehace entero,
 * y lo ya apuntado en Tareas sigue marcado.
 */
object ReunionEnLaApp {

    /** Cuántas preguntas van a la vez (una clase de una hora son unos 150 renglones). */
    private const val A_LA_VEZ = 4

    private val _enMarcha = MutableStateFlow<Set<String>>(emptySet())

    /** Las notas que se están mirando ahora (por id): «Buscando lo importante…». */
    val enMarcha: StateFlow<Set<String>> get() = _enMarcha

    /** Los renglones de la transcripción de una nota, como los lee «Estudiar la clase». */
    fun renglonesDe(m: Mensaje): List<Reunion.Renglon> =
        EstudiarClase.lineasDe(m.transcripcion).map { Reunion.Renglon(it.ms, it.texto) }.filter { it.texto.isNotBlank() }

    /** De lo hallado por Jev a lo que se guarda. */
    fun aGuardar(h: Reunion.Hallazgo): Importantes.Importante = Importantes.Importante(
        ms = h.ms,
        tipo = when (h.tipo) {
            Reunion.Tipo.TAREA -> Importantes.Tipo.TAREA
            Reunion.Tipo.DECISION -> Importantes.Tipo.DECISION
            Reunion.Tipo.PREGUNTA -> Importantes.Tipo.PREGUNTA
            Reunion.Tipo.EXAMEN -> Importantes.Tipo.EXAMEN
        },
        p = h.p, quien = h.quien, cuando = h.cuando
    )

    /**
     * **Lo nuevo sobre la nota de ahora** (el chat o la sincronización pudieron tocarla): solo si el
     * texto sigue siendo el que se miró, y con lo ya apuntado en Tareas aún marcado. null: no se toca.
     */
    fun escribirSobre(actual: Mensaje, transcripcionMirada: String?, v: List<Importantes.Importante>, modelo: String): Mensaje? {
        if (actual.transcripcion != transcripcionMirada) return null
        val antes = Importantes.de(actual).orEmpty().filter { it.enTareas }.map { it.ms }
        val nuevo = Importantes.ponerEn(actual, v, modelo)
        return Importantes.marcarEnTareas(nuevo.importantes, antes)?.let { nuevo.copy(importantes = it) } ?: nuevo
    }

    /**
     * **Busca lo importante de la nota [id]** en su hilo, si hay clave, la función está encendida y la
     * nota aún no se miró ([forzar]: aunque ya se mirara, tras volver a pasarla a texto). Vuelve en seguida.
     */
    fun lanzar(context: Context, id: String, forzar: Boolean) {
        val app = context.applicationContext
        var entro = false
        _enMarcha.update { if (id in it) it else { entro = true; it + id } }
        if (!entro) return
        Thread({
            try {
                val cliente = Jev.para(app, Jev.Funcion.REUNION)
                val empezo = System.currentTimeMillis()
                val n = cliente?.let { buscar(app, it, id, forzar) }
                if (n != null) android.util.Log.i("PixPinJev", "lo importante de la clase: $n en ${System.currentTimeMillis() - empezo} ms")
            } catch (e: Throwable) {
                // Un fallo es un error, nunca un «no hay nada»: la nota se queda como estaba.
                android.util.Log.w("PixPinJev", "no se buscó lo importante de la clase: ${e.message}")
            } finally {
                _enMarcha.update { it - id }
            }
        }, "pixpin-jev-reunion").apply { isDaemon = true }.start()
    }

    /** Lo de [lanzar], en el hilo. null: no había nada que hacer. */
    private fun buscar(app: Context, cliente: Cliente, id: String, forzar: Boolean): Int? {
        val almacen = MensajesStore(app)
        val m = almacen.leer().firstOrNull { it.id == id } ?: return null
        if (m.esMusica) return null
        if (!forzar && m.importantes != null) return null
        val renglones = renglonesDe(m)
        if (renglones.isEmpty()) return null
        val (hallado, modelo) = Reunion.buscar(cliente, tituloDeAudio(app, m), renglones, A_LA_VEZ)
        val v = hallado.map(::aGuardar)
        almacen.actualizar(id) { actual -> escribirSobre(actual, m.transcripcion, v, modelo) ?: actual }
        return v.size
    }

    /**
     * **Apunta en el Inbox de Tareas** las tareas halladas en la nota que aún no se apuntaron, cada una
     * con su nota `💬 🎙 <clase> ⏱ m:ss`, y las marca (`enTareas`). Devuelve cuántas. Trabajo de disco.
     * **No necesita clave**: lo hallado puede venir del PC.
     */
    fun aTareas(context: Context, id: String): Int {
        val app = context.applicationContext
        val almacen = MensajesStore(app)
        val m = almacen.leer().firstOrNull { it.id == id } ?: return 0
        val pendientes = Importantes.tareasSinApuntar(Importantes.de(m).orEmpty())
        if (pendientes.isEmpty()) return 0
        val renglones = renglonesDe(m)
        val titulo = tituloDeAudio(app, m)
        val hechas = mutableListOf<Long>()
        for (ms in pendientes) {
            val r = renglones.firstOrNull { it.ms == ms } ?: continue
            runCatching { com.forge.pixpin.mini.TareaRapidaActivity.alInbox(app, Importantes.textoDeTarea(r.texto, titulo, ms)) }
                .onSuccess { hechas += ms }
                .onFailure { android.util.Log.w("PixPinJev", "no se pudo apuntar una tarea de la clase", it) }
        }
        if (hechas.isNotEmpty()) almacen.actualizar(id) { it.copy(importantes = Importantes.marcarEnTareas(it.importantes, hechas) ?: it.importantes) }
        return hechas.size
    }
}
