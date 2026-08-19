package com.forge.pixpin.guardados

/**
 * La lista de conversaciones: la general y la de cada proyecto.
 *
 * ## Copiado de cómo ordena Telegram sus chats
 *
 * En Telegram la lista de chats **se ordena por el último mensaje**, no por el nombre ni
 * por cuándo se creó el chat (`DialogsActivity` ordena por `dialog.last_message_date`). El
 * motivo es que una conversación en marcha vuelve arriba sola: no hay que buscarla, ni
 * fijarla, ni acordarse de dónde estaba. Aquí pasa lo mismo con los proyectos — el que se
 * está anotando esta semana es al que se vuelve veinte veces.
 *
 * Y de cada chat se enseña **su último mensaje**, que es lo que dice si hay algo nuevo o
 * es donde uno lo dejó. Un nombre a secas obliga a entrar para saberlo.
 *
 * ## Por qué en una sola pasada
 *
 * Porque esto se calcula con **todos** los mensajes de todas las conversaciones. Filtrar
 * la lista una vez por proyecto es recorrerla tantas veces como proyectos haya: con veinte
 * proyectos y mil mensajes, veinte mil comparaciones cada vez que se abre el menú. Un solo
 * recorrido agrupando por conversación da lo mismo y se nota.
 */
data class Conversacion(
    /** El proyecto del que es, o null para la general. */
    val proyecto: String?,
    val nombre: String,
    /** Lo último que se dejó ahí, para la línea de debajo. */
    val ultimo: Mensaje?,
    val cuantos: Int
) {
    /** Cuándo se tocó por última vez. Sin nada dentro, no tiene fecha. */
    val cuando: Long get() = ultimo?.cuando ?: 0L
}

/**
 * Las conversaciones que hay, **la general primero y el resto por lo más reciente**.
 *
 * La general no compite por el orden: es el nexo, el sitio desde el que se llega a los
 * demás, así que se queda arriba aunque lleve una semana sin tocarse. Es la misma decisión
 * que toma Telegram al fijar arriba lo que no puede perderse entre lo demás.
 *
 * @param nombres cómo se llama cada proyecto, por su identificador. Los que no estén —un
 *  proyecto borrado que dejó mensajes— **no desaparecen**: se les da su identificador como
 *  nombre, porque lo escrito ahí sigue existiendo y esconderlo sería perderlo.
 */
fun conversaciones(
    mensajes: List<Mensaje>,
    nombres: Map<String, String>,
    nombreDeLaGeneral: String
): List<Conversacion> {
    val ultimos = HashMap<String?, Mensaje>()
    val cuentas = HashMap<String?, Int>()
    for (m in mensajes) {
        if (m.enBuzon) continue
        val clave = m.proyecto
        cuentas[clave] = (cuentas[clave] ?: 0) + 1
        val actual = ultimos[clave]
        if (actual == null || m.cuando > actual.cuando) ultimos[clave] = m
    }

    val general = Conversacion(
        proyecto = null,
        nombre = nombreDeLaGeneral,
        ultimo = ultimos[null],
        cuantos = cuentas[null] ?: 0
    )

    // Se listan **todos los proyectos**, tengan mensajes o no: una conversación vacía es
    // una invitación a usarla, y esconderla hasta que tenga algo dentro es la forma de
    // que nunca lo tenga.
    val claves = LinkedHashSet<String>().apply {
        addAll(nombres.keys)
        addAll(mensajes.mapNotNull { it.proyecto })
    }

    val delResto = claves.map { id ->
        Conversacion(
            proyecto = id,
            nombre = nombres[id] ?: id,
            ultimo = ultimos[id],
            cuantos = cuentas[id] ?: 0
        )
    }.sortedWith(compareByDescending<Conversacion> { it.cuando }.thenBy { it.nombre })

    return listOf(general) + delResto
}
