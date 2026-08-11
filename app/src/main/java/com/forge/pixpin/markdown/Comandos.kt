package com.forge.pixpin.markdown

/**
 * Los comandos que se escriben con barra, como en el editor enriquecido de
 * Telegram (`RichCommandSuggestions` + `RichCommand.match`).
 *
 * Escribes `/tab`, aparece la lista filtrada, eliges y la barra desaparece
 * dejando la tabla puesta. Es la respuesta que tienen ellos al problema de tener
 * veintitantos tipos de bloque: no caben en botones, un menú de veintitantos no
 * se lee, y esconderlos en submenús los entierra. Tecleando, la lista se filtra
 * sola, y el que se sabe el atajo nunca llega a verla.
 *
 * Todo esto es matemática de índices sobre una cadena, así que vive aquí y no en
 * el composable: se comprueba en la JVM sin teclado delante.
 */
object Comandos {

    /**
     * Lo que se lleva tecleado tras la barra, o null si el cursor no está
     * escribiendo un comando.
     *
     * La barra solo cuenta **al principio de la línea o tras un espacio**. Sin
     * eso, cualquier fecha `12/03` o cualquier ruta `a/b` abriría la lista en
     * mitad de la frase, que es de las cosas que más molestan de un editor.
     */
    fun consulta(text: String, cursor: Int): String? {
        val pos = cursor.coerceIn(0, text.length)
        var i = pos
        while (i > 0 && text[i - 1].esDeComando()) i--
        if (i == 0 || text[i - 1] != '/') return null

        val barra = i - 1
        if (barra > 0) {
            val anterior = text[barra - 1]
            if (anterior != '\n' && anterior != ' ') return null
        }
        return text.substring(i, pos)
    }

    private fun Char.esDeComando(): Boolean = isLetterOrDigit() || this == '_'

    /**
     * Cambia el `/comando` a medio escribir por el bloque elegido.
     *
     * Si no había comando —porque se eligió desde el catálogo y no tecleando—,
     * el bloque se mete donde esté el cursor.
     */
    fun elegir(text: String, cursor: Int, tipo: TipoDeBloque): EditResult {
        val pos = cursor.coerceIn(0, text.length)
        val escrito = consulta(text, pos)
        val desde = if (escrito == null) pos else pos - escrito.length - 1
        return insertar(text, desde, pos, tipo)
    }

    /**
     * Escribe la plantilla del bloque entre [desde] y [hasta].
     *
     * Los bloques que ocupan líneas enteras se aseguran de empezar en una: meter
     * una tabla en mitad de un párrafo dejaría la mitad de la frase dentro de la
     * primera celda.
     */
    fun insertar(text: String, desde: Int, hasta: Int, tipo: TipoDeBloque): EditResult {
        val a = desde.coerceIn(0, text.length)
        val b = hasta.coerceIn(a, text.length)
        val plantilla = Bloques.plantilla(tipo)

        val antesDeTodo = text.substring(0, a)
        val salto = if (antesDeTodo.isEmpty() || antesDeTodo.endsWith("\n")) "" else "\n"

        val cabeza = antesDeTodo + salto + plantilla.antes
        val nuevo = cabeza + plantilla.despues + text.substring(b)
        return EditResult(nuevo, cabeza.length, cabeza.length)
    }
}
