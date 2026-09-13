package com.forge.pixpin.motor

/**
 * **Lo nuevo de un proyecto, para contarlo en su chat.**
 *
 * El usuario lo pidió así el 11-sep-2026: «todo tiene que ir por el chat o pasar a través de
 * este». Crear un lienzo, escribir una nota, hacer una tabla o empezar un croquis en la zona
 * de proyectos deja también un mensaje en la conversación del proyecto, con su vista previa,
 * y el mensaje apunta **a lo mismo**, no a una copia: abrirlo desde el chat es abrir la hoja.
 *
 * Aquí solo se decide qué es nuevo comparando el proyecto antes y después de guardarlo; quien
 * escribe en el chat es `guardados/ChatDeLosProyectos`. Lo que **no** se cuenta:
 * - lo que ya vino del chat ([Hoja.deMensaje]): la foto, el PDF o el Excel unidos ya están
 *   allí, y contarlo otra vez daría el mensaje dos veces;
 * - las páginas de un PDF: un plano de cuarenta hojas no son cuarenta mensajes;
 * - una nota vacía: nace así al pulsar «Nota» y se cuenta cuando tiene algo escrito.
 */
object AvisosDelProyecto {

    sealed interface Novedad {
        /** Un lienzo nuevo, o la lámina de un croquis. */
        data class Lienzo(val hoja: Hoja) : Novedad
        /** Una nota con texto nuevo o cambiado: el mensaje se crea o se pone al día. */
        data class Nota(val hoja: Hoja) : Novedad
        data class Tabla(val hoja: Hoja) : Novedad
        /** Un croquis 3D nuevo; [numero] es su orden en el proyecto, para nombrarlo. */
        data class Croquis(val id: String, val numero: Int) : Novedad
    }

    fun novedades(antes: Proyecto?, despues: Proyecto): List<Novedad> {
        val viejas = antes?.hojas?.associateBy { it.id }.orEmpty()
        val out = ArrayList<Novedad>()
        for (h in despues.hojas) {
            if (h.deMensaje != null || h.pagina != null) continue
            val vieja = viejas[h.id]
            when {
                h.tabla != null -> if (vieja == null) out += Novedad.Tabla(h)
                h.nota != null -> if (h.nota.isNotBlank() && vieja?.nota != h.nota) out += Novedad.Nota(h)
                h.dibujo != null -> if (vieja?.dibujo == null) out += Novedad.Lienzo(h)
            }
        }
        val croquisDeAntes = antes?.croquis.orEmpty().toSet()
        despues.croquis.forEachIndexed { i, id -> if (id !in croquisDeAntes) out += Novedad.Croquis(id, i + 1) }
        return out
    }
}
