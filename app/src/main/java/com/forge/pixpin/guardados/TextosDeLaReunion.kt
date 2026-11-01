package com.forge.pixpin.guardados

import com.forge.pixpin.R
import com.forge.pixpin.jev.Urgencia
import com.forge.pixpin.ui.Textos

/**
 * **Lo que dice la reunión con Jev** (29-oct-2026): los textos del PC (`clase-temas-*`, `clase-vivo-*` y
 * `clase-acta-*` de `main.ftl`), en un solo sitio, para la barra, «Preparar», «Estudiar la clase» y la
 * página compartida.
 *
 * **En el idioma puesto** (1-nov-2026): cada texto es un recurso (`reunion_t_*`, en
 * `strings_idiomas.xml`) y aquí queda su español como respaldo, el que se ve sin aplicación —en las
 * pruebas—. Ver [Textos]. Ya no son `const`: se leen cada vez, así que cambian con el idioma.
 */
object TextosDeLaReunion {
    val TITULO get() = Textos.t(R.string.reunion_t_titulo, "Temas de la reunión")
    val NINGUNO get() = Textos.t(R.string.reunion_t_ninguno, "Temas de la reunión (opcional)")
    fun conN(n: Int) = Textos.t(R.string.reunion_t_con_n, "Temas de la reunión · %1\$d", n)
    val SUB get() = Textos.t(R.string.reunion_t_sub, "Uno por línea. Mientras grabas, Jev marca los que se traten.")
    val ESCRIBE get() = Textos.t(R.string.reunion_t_escribe, "Escribe un tema y pulsa Intro…")
    val PEGAR get() = Textos.t(R.string.reunion_t_pegar, "Pegar")
    val DE_UNA_LISTA get() = Textos.t(R.string.reunion_t_de_una_lista, "De una lista")
    fun lista(lista: String, n: Int) = Textos.t(R.string.reunion_t_lista, "Lista «%1\$s»: %2\$d temas", lista, n)
    val SIN_LISTAS get() = Textos.t(R.string.reunion_t_sin_listas, "No hay listas de tareas con algo pendiente")
    fun pegados(n: Int) = Textos.t(R.string.reunion_t_pegados, "%1\$d temas añadidos", n)
    val NADA_QUE_PEGAR get() = Textos.t(R.string.reunion_t_nada_que_pegar, "No hay texto en el portapapeles")
    val VACIAR get() = Textos.t(R.string.reunion_t_vaciar, "Vaciar")
    val LISTO get() = Textos.t(R.string.reunion_t_listo, "Listo")
    fun anteriores(n: Int) = Textos.t(R.string.reunion_t_anteriores, "%1\$d temas pendientes de la reunión anterior", n)
    fun tratado(n: Int, minuto: String) = Textos.t(R.string.reunion_t_tratado, "✓ Tema %1\$d tratado · %2\$s", n, minuto)
    val PREPARANDO get() = Textos.t(R.string.reunion_t_preparando, "Preparando la escucha…")
    val ESCUCHANDO get() = Textos.t(R.string.reunion_t_escuchando, "Escuchando: Jev marca los temas que se traten")
    val ESCUCHANDO_REUNION get() = Textos.t(R.string.reunion_t_escuchando_reunion, "Escuchando la reunión")
    val SIN_CLAVE get() = Textos.t(R.string.reunion_t_sin_clave, "Sin Jev (falta la clave de TypeSafe): marca los temas a mano")
    val APAGADO get() = Textos.t(R.string.reunion_t_apagado, "Los temas con Jev están apagados en Ajustes: márcalos a mano")
    val SIN_MODELO get() = Textos.t(R.string.reunion_t_sin_modelo, "Sin el reconocedor de voz del teléfono ni el modelo de Whisper no se escucha en vivo: marca los temas a mano")
    val SIN_PCM get() = Textos.t(R.string.reunion_t_sin_pcm, "Esta grabación no da el sonido para escuchar en vivo: marca los temas a mano")
    val JEV_FALLA get() = Textos.t(R.string.reunion_t_jev_falla, "Jev no contesta: marca los temas a mano")
    val JUSTO get() = Textos.t(R.string.reunion_t_justo, "Batería baja o teléfono caliente: se escucha menos para no frenar la grabación")
    val CAMARA_DEL_PC get() = Textos.t(R.string.reunion_t_camara_del_pc, "Este teléfono es la cámara del PC: escucha el PC. Marca los temas a mano")
    val SIN_TRATAR get() = Textos.t(R.string.reunion_t_sin_tratar, "Sin tratar")
    val PROXIMA get() = Textos.t(R.string.reunion_t_proxima, "Pasar a la próxima reunión")
    val A_TAREAS get() = Textos.t(R.string.reunion_t_a_tareas, "Añadir a Tareas")
    fun pasados(n: Int) = Textos.t(R.string.reunion_t_pasados, "%1\$d temas esperan a la próxima reunión", n)
    fun anadidos(n: Int) = Textos.t(R.string.reunion_t_anadidos, "%1\$d temas apuntados en el Inbox de Tareas", n)
    fun listaDe(nombre: String) = Textos.t(R.string.reunion_t_lista_de, "Temas · %1\$s", nombre)

    val TAREA get() = Textos.t(R.string.reunion_t_tarea, "Tarea")
    val EVENTO get() = Textos.t(R.string.reunion_t_evento, "Evento")
    val DESHACER get() = Textos.t(R.string.reunion_t_deshacer, "Deshacer")
    fun decision(minuto: String) = Textos.t(R.string.reunion_t_decision, "Decisión apuntada · %1\$s", minuto)
    val TAREA_APUNTADA get() = Textos.t(R.string.reunion_t_tarea_apuntada, "Tarea apuntada en el Inbox de Tareas")
    val EVENTO_APUNTADO get() = Textos.t(R.string.reunion_t_evento_apuntado, "Evento apuntado en Tareas (se ve en el Horario)")
    val DESHECHO get() = Textos.t(R.string.reunion_t_deshecho, "Deshecho: la tarea vuelve a como estaba")
    val NO_SE_APUNTO get() = Textos.t(R.string.reunion_t_no_se_apunto, "No se pudo apuntar: mira Tareas")
    val VIVO_FALLA get() = Textos.t(R.string.reunion_t_vivo_falla, "Jev no contesta: por ahora no se propone nada")
    fun quedaPoco(minutos: Long, temas: String) = Textos.t(R.string.reunion_t_queda_poco, "Quedan %1\$d min y falta: %2\$s", minutos, temas)
    fun cumplido(temas: String) = Textos.t(R.string.reunion_t_cumplido, "Se pasó la hora y falta: %1\$s", temas)
    fun cierran(temas: String) = Textos.t(R.string.reunion_t_cierran, "Se están despidiendo y falta: %1\$s", temas)
    fun fechaNueva(fecha: String) = Textos.t(R.string.reunion_t_fecha_nueva, "ahora para el %1\$s", fecha)
    val DURACION get() = Textos.t(R.string.reunion_t_duracion, "Duración prevista")
    fun duracionN(minutos: Int) = Textos.t(R.string.reunion_t_duracion_n, "⏱ %1\$d min", minutos)

    fun prioridad(p: Urgencia.Prioridad) = when (p) {
        Urgencia.Prioridad.URGENTE -> Textos.t(R.string.reunion_t_urgente, "Urgente")
        Urgencia.Prioridad.ALTA -> Textos.t(R.string.reunion_t_alta, "Alta")
        Urgencia.Prioridad.NORMAL -> Textos.t(R.string.reunion_t_normal, "Normal")
        Urgencia.Prioridad.BAJA -> Textos.t(R.string.reunion_t_baja, "Baja")
    }

    val DECISIONES get() = Textos.t(R.string.reunion_t_decisiones, "Decisiones")
    val PREGUNTAS get() = Textos.t(R.string.reunion_t_preguntas, "Preguntas sin respuesta")
    val PROPUESTAS get() = Textos.t(R.string.reunion_t_propuestas, "Propuestas sin aceptar")
    val ACEPTAR get() = Textos.t(R.string.reunion_t_aceptar, "Aceptar")
    val ACEPTADA get() = Textos.t(R.string.reunion_t_aceptada, "Apuntada en Tareas")
    fun preguntasAnadidas(n: Int) = Textos.t(R.string.reunion_t_preguntas_anadidas, "%1\$d preguntas apuntadas en el Inbox de Tareas", n)
}
