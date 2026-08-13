package com.forge.pixpin.motor

/**
 * Qué se puede ajustar en cada momento.
 *
 * **El panel de estilos no se enseña entero siempre.** Antes era una parrilla
 * fija con trazo, fondo, relleno, línea, grosor, rugosidad y esquinas, todo a
 * la vez y siempre: con el lápiz activo salían el relleno y las esquinas, que
 * no hacen nada; con un texto seleccionado salía el rayado del fondo, que
 * tampoco. Ocupaba dos filas de pantalla para ofrecer, la mayor parte del
 * tiempo, cosas que no aplican.
 *
 * Ahora se pregunta aquí. Es una función pura a propósito: **qué aplica a qué**
 * es una tabla, no una decisión de interfaz, y así se puede comprobar sin
 * dispositivo que ninguna herramienta se queda sin sus ajustes ni ofrece los
 * que no usa.
 */
enum class Propiedad {
    TRAZO, FONDO, RELLENO, LINEA, GROSOR, RUGOSIDAD, ESQUINAS, PUNTAS, FUENTE, OPACIDAD,

    /** Recta, curva o de codos. Solo la flecha. */
    FORMA_FLECHA,

    /**
     * Pixelar o desenfocar. Solo el mosaico.
     *
     * El modelo lo tenía desde el principio (`Element.mosaicBlur`) pero **no
     * había forma de tocarlo**: era un campo que se guardaba, se serializaba y
     * se pintaba, y ninguna interfaz lo ponía nunca a true. Se pixelaba y punto.
     */
    MOSAICO
}

/**
 * Los ajustes que tienen sentido ahora mismo.
 *
 * **Manda la selección si la hay**, y si no, la herramienta activa: es lo que
 * espera la mano —tocar un rectángulo para cambiarle el color, o coger el
 * rectángulo para elegir con qué color va a nacer el siguiente—. Con varios
 * elementos se ofrece la unión: lo que le aplique a alguno se puede tocar, y a
 * quien no le afecte lo ignora.
 */
fun propiedadesPara(tool: Tool, seleccion: List<Element>): Set<Propiedad> {
    if (seleccion.isNotEmpty()) {
        return seleccion.flatMap { propiedadesDeTipo(it.type) }.toSet()
    }
    val tipo = tipoQueCrea(tool) ?: return emptySet()
    return propiedadesDeTipo(tipo)
}

/**
 * Qué crea cada herramienta, o null si no crea nada.
 *
 * La selección, el lazo, la mano y el borrador no dibujan, así que sin nada
 * seleccionado no hay ningún estilo que ajustar y el panel desaparece entero.
 */
fun tipoQueCrea(tool: Tool): ElementType? = when (tool) {
    Tool.PUNTO -> ElementType.PUNTO
    Tool.RECTANGLE -> ElementType.RECTANGLE
    Tool.DIAMOND -> ElementType.DIAMOND
    Tool.ELLIPSE -> ElementType.ELLIPSE
    Tool.ARROW -> ElementType.ARROW
    Tool.LINE -> ElementType.LINE
    Tool.FREEDRAW, Tool.HIGHLIGHTER -> ElementType.FREEDRAW
    Tool.TEXT -> ElementType.TEXT
    Tool.IMAGE -> ElementType.IMAGE
    Tool.MOSAIC -> ElementType.MOSAIC
    Tool.SPOTLIGHT -> ElementType.SPOTLIGHT
    Tool.SERIAL -> ElementType.SERIAL
    Tool.FRAME -> ElementType.FRAME
    Tool.MEASURE, Tool.SCALE -> ElementType.MEASURE
    Tool.RELLENO -> ElementType.REGION
    Tool.ESCALA_GRAFICA -> ElementType.ESCALA_GRAFICA
    // Recortar y extender no crean nada: arreglan lo que ya hay, y lo que
    // hacen no depende de ningún color ni de ningún grosor.
    Tool.SELECTION, Tool.LASSO, Tool.HAND, Tool.ERASER,
    Tool.RECORTAR, Tool.EXTENDER, Tool.NUDO -> null
}

private fun propiedadesDeTipo(tipo: ElementType): Set<Propiedad> = when (tipo) {
    // El punto se ve en negro sobre blanco siempre —para eso está— así que de
    // color solo manda el de su letra, y del tamaño, el de la letra también.
    ElementType.PUNTO -> setOf(Propiedad.TRAZO, Propiedad.FUENTE, Propiedad.OPACIDAD)

    ElementType.RECTANGLE, ElementType.DIAMOND -> setOf(
        Propiedad.TRAZO, Propiedad.FONDO, Propiedad.RELLENO, Propiedad.LINEA,
        Propiedad.GROSOR, Propiedad.RUGOSIDAD, Propiedad.ESQUINAS, Propiedad.OPACIDAD
    )

    // La elipse no tiene esquinas que redondear.
    ElementType.ELLIPSE -> setOf(
        Propiedad.TRAZO, Propiedad.FONDO, Propiedad.RELLENO, Propiedad.LINEA,
        Propiedad.GROSOR, Propiedad.RUGOSIDAD, Propiedad.OPACIDAD
    )

    ElementType.ARROW -> setOf(
        Propiedad.TRAZO, Propiedad.LINEA, Propiedad.GROSOR, Propiedad.RUGOSIDAD,
        Propiedad.PUNTAS, Propiedad.FORMA_FLECHA, Propiedad.OPACIDAD
    )

    // La línea sí admite fondo: cerrada sobre sí misma se rellena.
    ElementType.LINE -> setOf(
        Propiedad.TRAZO, Propiedad.FONDO, Propiedad.RELLENO, Propiedad.LINEA,
        Propiedad.GROSOR, Propiedad.RUGOSIDAD, Propiedad.ESQUINAS, Propiedad.OPACIDAD
    )

    // El lápiz no pasa por rough.js ni lleva línea discontinua: ni rugosidad ni
    // esquinas, que serían botones muertos.
    //
    // **Fondo y relleno sí**, y hacen falta justamente para poder apagarlos: un
    // garabato que se cierra sobre sí mismo se rellena solo —lo hace el
    // original— y sin estos dos controles no había forma de quitarle la trama.
    // Se quitaron una vez por eso mismo, por creer que sobraban, y lo que se
    // consiguió fue dejar la trama puesta y sin interruptor.
    ElementType.FREEDRAW -> setOf(
        Propiedad.TRAZO, Propiedad.FONDO, Propiedad.RELLENO,
        Propiedad.GROSOR, Propiedad.OPACIDAD
    )

    ElementType.TEXT -> setOf(Propiedad.TRAZO, Propiedad.FUENTE, Propiedad.OPACIDAD)

    ElementType.IMAGE -> setOf(Propiedad.ESQUINAS, Propiedad.OPACIDAD)

    // En el mosaico el grosor hace de tamaño de grano.
    ElementType.MOSAIC -> setOf(Propiedad.GROSOR, Propiedad.MOSAICO, Propiedad.OPACIDAD)

    // El foco solo gradúa cuánto oscurece, y eso es su opacidad.
    ElementType.SPOTLIGHT -> setOf(Propiedad.OPACIDAD)

    ElementType.SERIAL -> setOf(Propiedad.TRAZO, Propiedad.FUENTE, Propiedad.OPACIDAD)

    // El arco es una raya curva: ni fondo ni relleno, porque no encierra nada.
    ElementType.ARC -> setOf(
        Propiedad.TRAZO, Propiedad.LINEA, Propiedad.GROSOR,
        Propiedad.RUGOSIDAD, Propiedad.OPACIDAD
    )

    // La cota lleva línea y número: color, grosor y letra. Ni fondo ni relleno
    // —no encierra nada— ni rugosidad, que se pinta lisa a propósito para que
    // se vea exactamente dónde acaba la medida.
    ElementType.MEASURE -> setOf(
        Propiedad.TRAZO, Propiedad.GROSOR, Propiedad.FUENTE, Propiedad.OPACIDAD
    )

    // El relleno de un hueco es **solo mancha**: ni trazo, ni línea, ni esquinas.
    // Su borde lo dibujan las figuras que lo encierran, así que ofrecerle color
    // de trazo sería un botón que no puede cambiar nada de lo que se ve.
    ElementType.REGION -> setOf(Propiedad.FONDO, Propiedad.RELLENO, Propiedad.OPACIDAD)

    // La reglita: el color con el que se pinta y de qué tamaño sale la cifra.
    // Ni relleno ni rugosidad — una escala temblorosa no se lee.
    ElementType.ESCALA_GRAFICA -> setOf(
        Propiedad.TRAZO, Propiedad.FUENTE, Propiedad.OPACIDAD
    )

    // La hoja no tiene estilo: es un límite, no un dibujo. Solo se estira.
    ElementType.FRAME -> emptySet()
}

/**
 * Las acciones sobre lo seleccionado, agrupadas por lo que hacen.
 *
 * Iban sueltas en una fila de doce glifos bajo la barra superior, siempre
 * visible en cuanto tocabas algo. Agrupadas se pueden plegar en un solo botón y
 * la pantalla se queda para dibujar.
 */
enum class GrupoAcciones { ORDEN, VOLTEO, AGRUPAR, ALINEAR }

/**
 * Qué grupos de acciones tienen sentido con [seleccion].
 *
 * Agrupar y alinear necesitan **dos o más**: con uno solo son botones que no
 * pueden hacer nada, y enseñarlos apagados solo añade ruido. Desagrupar aparece
 * en cuanto hay algo que ya venga agrupado, aunque lo hayas seleccionado de uno
 * en uno.
 */
fun gruposPara(seleccion: List<Element>): Set<GrupoAcciones> {
    if (seleccion.isEmpty()) return emptySet()
    val grupos = mutableSetOf(GrupoAcciones.ORDEN, GrupoAcciones.VOLTEO)
    if (seleccion.size > 1) {
        grupos += GrupoAcciones.AGRUPAR
        grupos += GrupoAcciones.ALINEAR
    } else if (seleccion.first().groupIds.isNotEmpty()) {
        grupos += GrupoAcciones.AGRUPAR
    }
    return grupos
}
