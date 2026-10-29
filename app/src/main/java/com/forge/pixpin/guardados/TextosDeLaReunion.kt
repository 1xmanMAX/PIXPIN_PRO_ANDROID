package com.forge.pixpin.guardados

import com.forge.pixpin.jev.Urgencia

/**
 * **Lo que dice la reunión con Jev** (29-oct-2026): los textos del PC (`clase-temas-*`, `clase-vivo-*` y
 * `clase-acta-*` de `main.ftl`), en un solo sitio, para la barra, «Preparar», «Estudiar la clase» y la
 * página compartida.
 */
object TextosDeLaReunion {
    const val TITULO = "Temas de la reunión"
    const val NINGUNO = "Temas de la reunión (opcional)"
    fun conN(n: Int) = "Temas de la reunión · $n"
    const val SUB = "Uno por línea. Mientras grabas, Jev marca los que se traten."
    const val ESCRIBE = "Escribe un tema y pulsa Intro…"
    const val PEGAR = "Pegar"
    const val DE_UNA_LISTA = "De una lista"
    fun lista(lista: String, n: Int) = "Lista «$lista»: $n temas"
    const val SIN_LISTAS = "No hay listas de tareas con algo pendiente"
    fun pegados(n: Int) = "$n temas añadidos"
    const val NADA_QUE_PEGAR = "No hay texto en el portapapeles"
    const val VACIAR = "Vaciar"
    const val LISTO = "Listo"
    fun anteriores(n: Int) = "$n temas pendientes de la reunión anterior"
    fun tratado(n: Int, minuto: String) = "✓ Tema $n tratado · $minuto"
    const val PREPARANDO = "Preparando la escucha…"
    const val ESCUCHANDO = "Escuchando: Jev marca los temas que se traten"
    const val ESCUCHANDO_REUNION = "Escuchando la reunión"
    const val SIN_CLAVE = "Sin Jev (falta la clave de TypeSafe): marca los temas a mano"
    const val APAGADO = "Los temas con Jev están apagados en Ajustes: márcalos a mano"
    const val SIN_MODELO = "Sin el reconocedor de voz del teléfono ni el modelo de Whisper no se escucha en vivo: marca los temas a mano"
    const val SIN_PCM = "Esta grabación no da el sonido para escuchar en vivo: marca los temas a mano"
    const val JEV_FALLA = "Jev no contesta: marca los temas a mano"
    const val JUSTO = "Batería baja o teléfono caliente: se escucha menos para no frenar la grabación"
    const val CAMARA_DEL_PC = "Este teléfono es la cámara del PC: escucha el PC. Marca los temas a mano"
    const val SIN_TRATAR = "Sin tratar"
    const val PROXIMA = "Pasar a la próxima reunión"
    const val A_TAREAS = "Añadir a Tareas"
    fun pasados(n: Int) = "$n temas esperan a la próxima reunión"
    fun anadidos(n: Int) = "$n temas apuntados en el Inbox de Tareas"
    fun listaDe(nombre: String) = "Temas · $nombre"

    const val TAREA = "Tarea"
    const val EVENTO = "Evento"
    const val DESHACER = "Deshacer"
    fun decision(minuto: String) = "Decisión apuntada · $minuto"
    const val TAREA_APUNTADA = "Tarea apuntada en el Inbox de Tareas"
    const val EVENTO_APUNTADO = "Evento apuntado en Tareas (se ve en el Horario)"
    const val DESHECHO = "Deshecho: la tarea vuelve a como estaba"
    const val NO_SE_APUNTO = "No se pudo apuntar: mira Tareas"
    const val VIVO_FALLA = "Jev no contesta: por ahora no se propone nada"
    fun quedaPoco(minutos: Long, temas: String) = "Quedan $minutos min y falta: $temas"
    fun cumplido(temas: String) = "Se pasó la hora y falta: $temas"
    fun cierran(temas: String) = "Se están despidiendo y falta: $temas"
    fun fechaNueva(fecha: String) = "ahora para el $fecha"
    const val DURACION = "Duración prevista"
    fun duracionN(minutos: Int) = "⏱ $minutos min"

    fun prioridad(p: Urgencia.Prioridad) = when (p) {
        Urgencia.Prioridad.URGENTE -> "Urgente"
        Urgencia.Prioridad.ALTA -> "Alta"
        Urgencia.Prioridad.NORMAL -> "Normal"
        Urgencia.Prioridad.BAJA -> "Baja"
    }

    const val DECISIONES = "Decisiones"
    const val PREGUNTAS = "Preguntas sin respuesta"
    const val PROPUESTAS = "Propuestas sin aceptar"
    const val ACEPTAR = "Aceptar"
    const val ACEPTADA = "Apuntada en Tareas"
    fun preguntasAnadidas(n: Int) = "$n preguntas apuntadas en el Inbox de Tareas"
}
