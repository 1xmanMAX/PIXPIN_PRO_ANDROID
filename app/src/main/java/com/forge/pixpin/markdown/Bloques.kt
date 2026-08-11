package com.forge.pixpin.markdown

/**
 * El catálogo de bloques que se pueden insertar, portado de `RichCommand.get()`
 * del editor enriquecido de Telegram (`org.telegram.ui.iv.RichEditor`).
 *
 * ## La idea que se copia
 *
 * En su editor **no se buscan botones**: se escribe. Tecleas `/tabla` y sale una
 * tabla; tecleas `[]` y sale una casilla; tecleas `##` y la línea se vuelve un
 * subtítulo. Cada bloque tiene un atajo corto —el de Markdown de toda la vida
 * cuando existe— y además un `/nombre` con sinónimos, para que valga tanto si te
 * acuerdas del símbolo como si solo te acuerdas de la palabra.
 *
 * Eso resuelve el problema que tiene cualquier editor con veinte tipos de
 * bloque: veinte botones no caben, un menú de veinte entradas no se lee, y
 * esconderlos en submenús los mata. Escribiendo, la lista se filtra sola según
 * tecleas, y el que ya sabe el atajo nunca ve la lista.
 *
 * [atajos] va **de más específico a menos**, como en su lista: el primero es el
 * que se enseña a la derecha de cada fila del desplegable.
 */
data class Bloque(
    val tipo: TipoDeBloque,
    val nombre: String,
    val atajos: List<String>
) {
    /**
     * ¿Encaja [q] con este bloque? Es su `RichCommand.matches`: se compara
     * contra **cada palabra** del nombre y contra cada atajo, siempre por el
     * principio. Buscar por el principio y no por «contiene» es lo que hace que
     * escribir dos letras baste y no salga media lista.
     */
    fun encaja(q: String): Boolean {
        val busca = q.removePrefix("/").lowercase()
        if (busca.isEmpty()) return true
        if (nombre.lowercase().split(' ').any { it.startsWith(busca) }) return true
        return atajos.any { it.removePrefix("/").lowercase().startsWith(busca) }
    }
}

enum class TipoDeBloque {
    TITULO_1, TITULO_2, TITULO_3, TITULO_4, TITULO_5, TITULO_6,
    CITA, DESTACADO, CODIGO, PIE,
    LISTA, NUMERADA, TAREAS, PLEGABLE,
    TABLA, FORMULA, SEPARADOR,
    IMAGEN, VIDEO, AUDIO, ARCHIVO,
    CENTRAR, DERECHA
}

/**
 * La lista, en el orden de `RichCommand.get()`.
 *
 * Suyo tal cual: seis niveles de título, cita, destacado, código, pie, lista,
 * numerada, casillas, plegable, tabla, fórmula, separador, imagen, vídeo y
 * audio. Falta su mapa —necesitaría un SDK de mapas para una nota sobre una
 * captura— y sobran tres: archivo adjunto y las dos alineaciones, que en un
 * documento hacen más falta que en un artículo.
 */
object Bloques {

    val todos: List<Bloque> = listOf(
        Bloque(TipoDeBloque.TITULO_1, "Título 1", listOf("#", "/t1", "/titulo", "/encabezado")),
        Bloque(TipoDeBloque.TITULO_2, "Título 2", listOf("##", "/t2", "/subtitulo")),
        Bloque(TipoDeBloque.TITULO_3, "Título 3", listOf("###", "/t3")),
        Bloque(TipoDeBloque.TITULO_4, "Título 4", listOf("####", "/t4")),
        Bloque(TipoDeBloque.TITULO_5, "Título 5", listOf("#####", "/t5")),
        Bloque(TipoDeBloque.TITULO_6, "Título 6", listOf("######", "/t6")),
        Bloque(TipoDeBloque.CITA, "Cita", listOf(">", "/cita")),
        Bloque(TipoDeBloque.DESTACADO, "Destacado", listOf("/destacado", "/resaltado")),
        Bloque(TipoDeBloque.CODIGO, "Código", listOf("```", "/codigo", "/pre")),
        Bloque(TipoDeBloque.PIE, "Pie", listOf("/pie", "/nota")),
        Bloque(TipoDeBloque.LISTA, "Lista", listOf("-", "/lista")),
        Bloque(TipoDeBloque.NUMERADA, "Lista numerada", listOf("1.", "/numerada")),
        Bloque(TipoDeBloque.TAREAS, "Casillas", listOf("[]", "/tareas", "/casillas")),
        Bloque(TipoDeBloque.PLEGABLE, "Plegable", listOf("/plegable", "/detalles")),
        Bloque(TipoDeBloque.TABLA, "Tabla", listOf("/tabla")),
        Bloque(TipoDeBloque.FORMULA, "Fórmula", listOf("$$", "/formula", "/latex", "/mates")),
        Bloque(TipoDeBloque.SEPARADOR, "Separador", listOf("---", "/separador")),
        Bloque(TipoDeBloque.IMAGEN, "Imagen", listOf("/imagen", "/foto")),
        Bloque(TipoDeBloque.VIDEO, "Vídeo", listOf("/video")),
        Bloque(TipoDeBloque.AUDIO, "Audio", listOf("/audio", "/musica")),
        Bloque(TipoDeBloque.ARCHIVO, "Archivo", listOf("/archivo", "/adjunto")),
        Bloque(TipoDeBloque.CENTRAR, "Centrar", listOf("/centrar", "/centro")),
        Bloque(TipoDeBloque.DERECHA, "A la derecha", listOf("/derecha"))
    )

    /** Los que encajan con lo tecleado, en el orden del catálogo. */
    fun buscar(q: String): List<Bloque> = todos.filter { it.encaja(q) }

    fun de(tipo: TipoDeBloque): Bloque = todos.first { it.tipo == tipo }

    /**
     * El texto que se escribe al elegir el bloque, y dónde queda el cursor.
     *
     * Cada uno viene **con su ejemplo dentro**, no vacío. Una tabla en blanco es
     * un acertijo —¿cuántas barras?, ¿dónde van los guiones?— y una con dos
     * filas puestas se edita sin saber la sintaxis, que es justo lo que consigue
     * su editor por otro camino.
     */
    fun plantilla(tipo: TipoDeBloque): Plantilla = when (tipo) {
        TipoDeBloque.TITULO_1 -> Plantilla("# ")
        TipoDeBloque.TITULO_2 -> Plantilla("## ")
        TipoDeBloque.TITULO_3 -> Plantilla("### ")
        TipoDeBloque.TITULO_4 -> Plantilla("#### ")
        TipoDeBloque.TITULO_5 -> Plantilla("##### ")
        TipoDeBloque.TITULO_6 -> Plantilla("###### ")
        TipoDeBloque.CITA -> Plantilla("> ")
        TipoDeBloque.LISTA -> Plantilla("- ")
        TipoDeBloque.NUMERADA -> Plantilla("1. ")
        TipoDeBloque.TAREAS -> Plantilla("- [ ] ")
        TipoDeBloque.SEPARADOR -> Plantilla("---\n")
        TipoDeBloque.CODIGO -> Plantilla("```\n", "\n```")
        TipoDeBloque.FORMULA -> Plantilla("$$\n", "\n$$")
        TipoDeBloque.TABLA -> Plantilla(
            "| Columna | Columna |\n|---|---|\n| ", " |  |\n"
        )
        TipoDeBloque.PLEGABLE -> Plantilla(":::plegable ", "\ncontenido\n:::\n")
        TipoDeBloque.DESTACADO -> Plantilla(":::destacado\n", "\n:::\n")
        TipoDeBloque.PIE -> Plantilla(":::pie\n", "\n:::\n")
        TipoDeBloque.CENTRAR -> Plantilla(":::centro\n", "\n:::\n")
        TipoDeBloque.DERECHA -> Plantilla(":::derecha\n", "\n:::\n")
        TipoDeBloque.IMAGEN -> Plantilla("![imagen](", ")")
        TipoDeBloque.VIDEO -> Plantilla("![vídeo](", ")")
        TipoDeBloque.AUDIO -> Plantilla("![audio](", ")")
        TipoDeBloque.ARCHIVO -> Plantilla("![archivo](", ")")
    }

    /** ¿Este bloque necesita que se elija un archivo antes de escribirse? */
    fun pideArchivo(tipo: TipoDeBloque): Boolean = tipo in setOf(
        TipoDeBloque.IMAGEN, TipoDeBloque.VIDEO, TipoDeBloque.AUDIO, TipoDeBloque.ARCHIVO
    )
}

/** Lo que se escribe antes y después del cursor al insertar un bloque. */
data class Plantilla(val antes: String, val despues: String = "")
