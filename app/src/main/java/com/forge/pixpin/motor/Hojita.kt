package com.forge.pixpin.motor

/**
 * **La hojita**: la nota adhesiva, rehecha (19-sep-2026). Antes era un papel amarillo arriba donde
 * solo se garabateaba con el dedo, y se pegaba como una foto. El usuario la quiere **de verdad**:
 *
 * - **Debajo de la barra de herramientas**, que sube para dejarle sitio. De tamaño limitado: ni
 *   inmensa ni diminuta.
 * - **Un lienzo como el grande**, con el mismo motor: lo que esté puesto en la barra —el lápiz, una
 *   figura, el borrador, el bote, el color y el grosor— es con lo que se dibuja aquí. No tiene
 *   barra propia: al posar el dedo en la hojita, coge la herramienta y el estilo del lienzo.
 * - **Borrar todo** de un toque, e **insertar**: la hojita entra en el lienzo como una hoja pequeña
 *   —su papel y, encima, lo anotado **en vectorial**, agrupado—, que se mueve y se estira entera
 *   y sigue siendo editable trazo a trazo si se desagrupa.
 *
 * Las herramientas que necesitan a la pantalla —traer una imagen, la zona, acotar dictando la
 * medida— no entran aquí: con una de esas puesta, la hojita se queda con la última que sí.
 */
object Hojita {
    /** Los papeles: claros, para que la tinta de siempre se lea. El primero, el amarillo de toda la vida. */
    val PAPELES = listOf("#fff3a3", "#ffffff", "#d7f5dd", "#dbeafe", "#ffe0e6")

    /** Lo que no se puede usar en la hojita: pide un diálogo o un sitio que aquí no hay. */
    val FUERA = setOf(Tool.IMAGE, Tool.ZONA, Tool.MEASURE, Tool.SCALE, Tool.FRAME, Tool.LUPA)

    /**
     * **Lo que entra en el lienzo al insertar**: un rectángulo con el color del papel y, encima, lo
     * anotado, todo en un grupo nuevo y llevado a [en] (la esquina de arriba a la izquierda).
     * [vista] es el trozo de la hojita que se veía, en sus coordenadas: ese es el papel.
     */
    fun paraInsertar(anotado: List<Element>, vista: Bounds, papel: String, en: Pt, estilo: ItemStyle): List<Element> {
        val vivos = anotado.filter { !it.isDeleted }
        val grupo = "hojita-" + randomId()
        val dx = en.x - vista.x1
        val dy = en.y - vista.y1
        val hoja = newElement(ElementType.RECTANGLE, en.x, en.y, estilo, vista.width, vista.height).copy(
            strokeColor = "#8a8f98", backgroundColor = papel, fillStyle = FillStyle.SOLID,
            strokeStyle = StrokeStyle.SOLID, strokeWidth = 1.0, roughness = 0, opacity = 100,
            roundness = Roundness(Roundness.ADAPTIVE_RADIUS), groupIds = listOf(grupo)
        )
        val encima = duplicateElements(vivos, offset = 0.0).map {
            it.copy(x = it.x + dx, y = it.y + dy, groupIds = it.groupIds + grupo)
        }
        return listOf(hoja) + encima
    }
}
