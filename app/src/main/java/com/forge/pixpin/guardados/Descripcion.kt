package com.forge.pixpin.guardados

/**
 * **La descripción de una foto** (5-oct-2026, la del PC: «cuando envío un texto y una imagen pasa
 * que solo se envía la imagen y el texto desaparece»).
 *
 * Se guarda en [Mensaje.texto] del propio mensaje IMAGEN (o ARCHIVO), no en una nota aparte: es
 * lo que el chat ya enseñaba debajo de la foto, dentro de la burbuja (`alPie` de la burbuja), y
 * lo que el PC escribe igual (`ventana_chat/descripcion.rs`). Así una foto con su texto viaja
 * como un solo mensaje y en los dos aparatos se ve igual. Sin Android: `DescripcionTest`.
 */
object Descripcion {
    /**
     * Si a [m] se le puede poner o cambiar la descripción: una foto o un archivo con su fichero.
     * Una lección no, que su `texto` es su resumen, ni lo del buzón, que caduca.
     */
    fun sePuede(m: Mensaje): Boolean =
        (m.clase == Clase.IMAGEN || m.clase == Clase.ARCHIVO) && !m.enBuzon && m.ruta != null &&
            !com.forge.pixpin.lecciones.LeccionesStore.esLeccion(m)

    /** [m] con [texto] de descripción, o nulo si no cambia nada. Vacío la quita: es la forma de borrarla. */
    fun conDescripcion(m: Mensaje, texto: String): Mensaje? {
        val limpio = texto.trim()
        if (limpio == m.texto.trim()) return null
        return m.copy(texto = limpio)
    }

    /**
     * Lo escrito vuelve a la caja al cancelar el cuadro de enviar: se sacó de ahí como pie, y
     * cancelar no puede ser perderlo. Si la caja ya tiene algo, no se pisa.
     */
    fun pieDeVuelta(borrador: String, pie: String): String =
        if (borrador.isBlank() && pie.isNotBlank()) pie else borrador

    /**
     * El pie de cada cosa de un envío de [clases], en su orden: **va con la primera** que pueda
     * llevarlo (una foto o un archivo), como el pie de una foto en Telegram. Si ninguna puede
     * —un envío de solo audios—, todos nulos y quien llama lo deja como nota, que es como no se
     * pierde.
     */
    fun pies(clases: List<Clase>, pie: String): List<String?> {
        val limpio = pie.trim()
        val cual = if (limpio.isEmpty()) -1 else clases.indexOfFirst { it == Clase.IMAGEN || it == Clase.ARCHIVO }
        return clases.indices.map { if (it == cual) limpio else null }
    }
}
