package com.forge.pixpin.mini

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * **Lo que llama, guardado en el teléfono** (1-nov-2026; ver [LlamadasDeTareas]): las tareas con
 * hora que suenan como una llamada y las asignaturas del horario con la llamada apagada. En las
 * preferencias de la app, **sin viajar**: el PC no tiene dónde guardarlo (ver [LlamadasDeTareas]).
 *
 * [version] sube con cada cambio, para que Tareas se repinte.
 */
object LlamadasGuardadas {

    private const val PREFS = "llamadas"
    private const val TAREAS = "tareas"
    private const val APAGADAS = "clases-apagadas"

    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun leer(context: Context, clave: String): Set<String> =
        runCatching { prefs(context).getStringSet(clave, emptySet()).orEmpty().toSet() }.getOrDefault(emptySet())

    private fun guardar(context: Context, clave: String, v: Set<String>) {
        runCatching { prefs(context).edit().putStringSet(clave, HashSet(v)).apply() }
        _version.value = _version.value + 1
    }

    /** Las tareas que llaman ([LlamadasDeTareas.clave]). */
    fun tareas(context: Context): Set<String> = leer(context, TAREAS)

    /** Las asignaturas con la llamada apagada ([LlamadasDeTareas.nombreDeGrupo]). */
    fun apagadas(context: Context): Set<String> = leer(context, APAGADAS)

    /** Que la tarea [crudo] de la lista [mensaje] llame, o que no. */
    @Synchronized
    fun ponerTarea(context: Context, mensaje: String, crudo: String, llamar: Boolean) =
        guardar(context, TAREAS, LlamadasDeTareas.conLlamada(tareas(context), mensaje, crudo, llamar))

    /** Olvida las tareas de listas que ya no están ([LlamadasDeTareas.vigentes]). */
    @Synchronized
    fun olvidarLasDe(context: Context, listas: Set<String>) {
        val antes = tareas(context)
        val quedan = LlamadasDeTareas.vigentes(antes, listas)
        if (quedan.size != antes.size) guardar(context, TAREAS, quedan)
    }

    /** Enciende ([encender]) o apaga las llamadas de la asignatura [nombre], y rehace las del horario. */
    @Synchronized
    fun ponerGrupo(context: Context, nombre: String, encender: Boolean) {
        guardar(context, APAGADAS, LlamadasDeTareas.conGrupo(apagadas(context), nombre, encender))
        runCatching { com.forge.pixpin.horario.AvisosDelHorario.reprogramar(context) }
    }
}
