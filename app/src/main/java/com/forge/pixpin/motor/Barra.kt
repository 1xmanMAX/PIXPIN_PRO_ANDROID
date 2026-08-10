package com.forge.pixpin.motor

/**
 * Cómo se reparte la barra en grupos.
 *
 * El problema que resuelve: el motor tiene diecinueve herramientas y la barra
 * vive flotando encima de lo que estás anotando. Enseñarlas todas es tapar la
 * pantalla; esconderlas detrás de un «⋯» es dos toques para coger un
 * rectángulo. La salida es **agrupar por parecido y enseñar una de cada
 * grupo**: la barra tiene tantos botones como grupos, y dentro de cada uno se
 * cambia sin salir de su sitio.
 *
 * Y quién decide los grupos es el usuario, arrastrando en los ajustes. Por eso
 * todo esto es lógica pura y comprobable: lo que se puede romper aquí no es que
 * se vea mal, es que una herramienta **desaparezca de la barra** y no haya
 * forma de llegar a ella.
 */

/** El separador entre grupos y el separador entre herramientas de un grupo. */
private const val SEPARA_GRUPO = "|"
private const val SEPARA_HERRAMIENTA = ","

/**
 * Los grupos de fábrica: lo que hace lo mismo, junto.
 *
 * Las formas en un grupo, lo que traza a mano en otro, lo que tapa en otro. Es
 * el reparto que uno haría con las manos si le dieran las piezas, y por eso es
 * el punto de partida — quien no toque nada tiene algo razonable, y quien lo
 * toque parte de algo entendible en vez de una lista plana.
 */
val GRUPOS_DE_FABRICA: List<List<Tool>> = listOf(
    listOf(Tool.SELECTION, Tool.LASSO, Tool.HAND),
    // Lo que pinta: el lápiz, el marcador y el bote. El bote no traza, pero lo
    // que se hace con él es dar color, que es de lo que va este grupo.
    listOf(Tool.FREEDRAW, Tool.HIGHLIGHTER, Tool.RELLENO),
    listOf(Tool.RECTANGLE, Tool.ELLIPSE, Tool.DIAMOND),
    listOf(Tool.ARROW, Tool.LINE),
    // Las dos que arreglan una raya ya trazada, juntas: se traza de largo y
    // luego se recorta lo que sobra y se estira lo que falta.
    listOf(Tool.RECORTAR, Tool.EXTENDER, Tool.NUDO),
    // El punto va con el texto y el numerito: los tres son para nombrar
    // cosas del dibujo, no para dibujarlas.
    listOf(Tool.TEXT, Tool.SERIAL, Tool.PUNTO),
    listOf(Tool.MOSAIC, Tool.SPOTLIGHT),
    listOf(Tool.MEASURE, Tool.SCALE, Tool.ESCALA_GRAFICA),
    listOf(Tool.FRAME, Tool.IMAGE),
    listOf(Tool.ERASER)
)

/**
 * Los grupos que tocan, quedándose solo con [permitidas].
 *
 * **Nada se pierde por el camino**: una herramienta permitida que no aparezca
 * en los grupos guardados se añade en un grupo propio al final. Es la red que
 * impide que añadir una herramienta al motor la deje inalcanzable para quien ya
 * tenía su reparto guardado.
 */
fun gruposDe(guardado: String?, permitidas: Set<Tool>): List<List<Tool>> {
    val base = if (guardado.isNullOrBlank()) GRUPOS_DE_FABRICA else leerGrupos(guardado)
    val vistas = LinkedHashSet<Tool>()
    val grupos = base.mapNotNull { grupo ->
        val limpio = grupo.filter { it in permitidas && vistas.add(it) }
        limpio.ifEmpty { null }
    }.toMutableList()

    // Las que el reparto guardado no menciona van al final, cada una en lo suyo.
    for (t in ALL_TOOLS) {
        if (t in permitidas && t !in vistas) {
            vistas += t
            grupos += listOf(t)
        }
    }
    return grupos
}

/** El reparto, escrito para guardarlo. */
fun escribirGrupos(grupos: List<List<Tool>>): String =
    grupos.filter { it.isNotEmpty() }
        .joinToString(SEPARA_GRUPO) { g -> g.joinToString(SEPARA_HERRAMIENTA) { it.name } }

/**
 * El reparto, leído. Los nombres desconocidos se ignoran en silencio: son de una
 * versión que los tenía, y tirar por eso el reparto entero sería peor.
 */
fun leerGrupos(texto: String): List<List<Tool>> =
    texto.split(SEPARA_GRUPO).mapNotNull { grupo ->
        val tools = grupo.split(SEPARA_HERRAMIENTA).mapNotNull { nombre ->
            runCatching { Tool.valueOf(nombre.trim()) }.getOrNull()
        }
        tools.ifEmpty { null }
    }

/**
 * Mueve [tool] al grupo [grupoDestino], en la posición [indice].
 *
 * Es lo que hace el arrastre de los ajustes, y va aquí y no en la pantalla
 * porque **es donde se pierden cosas**: quitar de un sitio y meter en otro con
 * los índices corriéndose por el camino es el error clásico, y sale gratis
 * comprobarlo sin dispositivo.
 *
 * [grupoDestino] igual al número de grupos significa «en uno nuevo al final»,
 * que es como se separa algo de su grupo. Los grupos que se quedan vacíos
 * desaparecen: un hueco en la barra no es una decisión, es un descuido.
 */
fun moverHerramienta(
    grupos: List<List<Tool>>,
    tool: Tool,
    grupoDestino: Int,
    indice: Int
): List<List<Tool>> {
    val destinoValido = grupoDestino.coerceIn(0, grupos.size)
    val nuevo: MutableList<MutableList<Tool>> =
        grupos.mapTo(mutableListOf()) { it.toMutableList() }
    if (destinoValido == grupos.size) nuevo.add(mutableListOf())

    // De dónde sale, para saber si el índice de destino se corre al sacarlo.
    // Si no estaba en ningún grupo, esto es una alta y no un movimiento: es lo
    // que pasa al devolver a la barra algo que estaba fuera de ella.
    val origen = nuevo.indexOfFirst { tool in it }
    val posicionOrigen = if (origen >= 0) nuevo[origen].indexOf(tool) else -1
    if (origen >= 0) nuevo[origen].removeAt(posicionOrigen)

    val destino = nuevo[destinoValido]
    // Dentro del mismo grupo y hacia la derecha, el hueco que deja al salir
    // adelanta una posición todo lo que viene después.
    val ajustado = if (origen == destinoValido && posicionOrigen < indice) indice - 1 else indice
    destino.add(ajustado.coerceIn(0, destino.size), tool)

    return nuevo.filter { it.isNotEmpty() }
}

/**
 * Dónde cae lo que se está arrastrando.
 *
 * [filas] es dónde está cada grupo en la pantalla: su borde de arriba, su borde
 * de abajo y el centro horizontal de cada una de sus herramientas. Con eso se
 * decide en qué grupo se suelta y en qué posición dentro de él.
 *
 * Va aquí, fuera de la pantalla, porque **es lo único del arrastre que puede
 * estar mal sin que se note**: soltar una herramienta y que aparezca en otro
 * sitio, o que se cuele en el grupo de al lado por un píxel. Lo demás —seguir
 * al dedo, dibujar la sombra— o funciona o se ve enseguida que no.
 *
 * Si el dedo cae por debajo de la última fila, el destino es un grupo nuevo:
 * es la forma de sacar algo de su grupo sin más gesto que arrastrarlo al final.
 */
fun destinoDeArrastre(x: Float, y: Float, filas: List<FilaDeGrupo>): Pair<Int, Int> {
    if (filas.isEmpty()) return 0 to 0
    val fila = filas.indexOfFirst { y >= it.arriba && y <= it.abajo }
    if (fila < 0) {
        // Por encima de la primera va al principio; por debajo de la última, a
        // un grupo nuevo, que es como se separa una herramienta de su grupo.
        return if (y < filas.first().arriba) 0 to 0 else filas.size to 0
    }
    val centros = filas[fila].centros
    // Cae delante de la primera cuyo centro queda a la derecha del dedo.
    val indice = centros.indexOfFirst { x < it }
    return fila to (if (indice < 0) centros.size else indice)
}

/** Dónde está un grupo en la pantalla mientras se arrastra encima. */
data class FilaDeGrupo(
    val arriba: Float,
    val abajo: Float,
    /** Centro horizontal de cada herramienta de la fila, en su orden. */
    val centros: List<Float>
)

/**
 * Qué herramienta enseña un grupo en la barra.
 *
 * La activa si está dentro, y si no la primera: al volver a un grupo se
 * encuentra uno lo último que usó de él, que es lo que la mano recuerda.
 */
fun caraDelGrupo(grupo: List<Tool>, activa: Tool, ultimas: Map<Int, Tool> = emptyMap()): Tool? {
    if (grupo.isEmpty()) return null
    if (activa in grupo) return activa
    val recordada = ultimas[grupo.hashCode()]
    return if (recordada != null && recordada in grupo) recordada else grupo.first()
}
