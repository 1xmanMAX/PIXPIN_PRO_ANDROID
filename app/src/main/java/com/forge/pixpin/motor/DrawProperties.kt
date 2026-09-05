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

    /**
     * **De qué está hecha la tinta**: lisa, encendida o con grano. Ver [MaterialDeTinta].
     *
     * Va con las que tienen trazo, que son las mismas que tienen [GROSOR]: el material es
     * cómo se pinta ese trazo, así que donde no hay raya no hay nada de lo que hablar.
     */
    MATERIAL,

    /**
     * Negrita, cursiva y tachado. **Solo el texto.**
     *
     * Va aparte de [FUENTE] —que es la familia y el tamaño— porque son cosas
     * distintas: la letra se elige una vez y esto se enciende y se apaga sobre
     * la marcha, resaltando una palabra de un esquema.
     */
    ESTILO_DE_TEXTO,

    /** Recta, curva o de codos. Solo la flecha. */
    FORMA_FLECHA,

    /**
     * **Qué pieza es un volumen y si va macizo o de alambre.** Solo el sólido.
     *
     * Las dos van juntas porque son la misma decisión repartida: qué estoy dibujando y
     * cómo quiero verlo. Y van en el lateral, con el color y el relleno, porque son de lo
     * que más se toca — metidas en el panel de acciones no había forma de dar con ellas.
     */
    VOLUMEN,

    /**
     * Pixelar o desenfocar. Solo el mosaico.
     *
     * El modelo lo tenía desde el principio (`Element.mosaicBlur`) pero **no
     * había forma de tocarlo**: era un campo que se guardaba, se serializaba y
     * se pintaba, y ninguna interfaz lo ponía nunca a true. Se pixelaba y punto.
     */
    MOSAICO,

    /**
     * Cuánto agranda la lupa, de qué forma es el cristal y si lleva flecha.
     *
     * Las tres van juntas en una sola propiedad porque son **la misma decisión
     * repartida**: qué enseña esta lupa y cómo. Separadas, el panel se llenaría
     * de mandos que solo salen para un tipo de elemento.
     */
    LUPA,

    /** Cuánto oscurece un foco lo que queda fuera. Solo el foco. */
    OSCURECER,

    /**
     * Lo grande que es la zona iluminada dentro de su marco. Solo el foco.
     *
     * Es el gemelo del aumento de la lupa: allí el mando mueve la ventana
     * dejando la zona, y aquí mueve la zona dejando el marco — que es lo que se
     * quiere de un foco, porque el marco es hasta dónde llega la sombra.
     */
    ZONA,

    /**
     * El trazo a mano sale firme, sin adelgazar por la presión.
     *
     * No tiene mando en el lateral: es un ajuste de cómo escribe uno, no algo
     * que se cambie figura a figura, así que vive en la configuración. Está aquí
     * para que el motor sepa a qué elementos se le escribe. Ver [conEstilo].
     */
    PRESION
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
        return seleccion.flatMap { propiedadesDe(it) }.toSet()
    }
    val tipo = tipoQueCrea(tool) ?: return emptySet()
    return propiedadesDeTipo(tipo)
}

/**
 * Lo que se le puede tocar **a este elemento en concreto**.
 *
 * Casi siempre es lo mismo que a los de su tipo, con una excepción que se veía
 * rara: una raya **abierta** no se puede rellenar. El tipo dice que sí —una
 * línea cerrada sobre sí misma sí se rellena, y es el mismo tipo— pero mientras
 * esté abierta no hay dentro que pintar. Y ahí estaban los mandos de relleno,
 * ofreciendo algo que no iba a pasar: se elegía un color y no cambiaba nada.
 */
fun propiedadesDe(e: Element): Set<Propiedad> {
    val base = propiedadesDeTipo(e.type)
    val abierta = (e.type == ElementType.LINE || e.type == ElementType.FREEDRAW) &&
        !isPathALoop(e.points)
    return if (!abierta) base else base - Propiedad.FONDO - Propiedad.RELLENO
}

/**
 * Qué crea cada herramienta, o null si no crea nada.
 *
 * La selección, el lazo, la mano y el borrador no dibujan, así que sin nada
 * seleccionado no hay ningún estilo que ajustar y el panel desaparece entero.
 */
fun tipoQueCrea(tool: Tool): ElementType? = when (tool) {
    // La flecha libre crea una flecha: se le ajusta el estilo como a cualquiera.
    Tool.FLECHA_LIBRE -> ElementType.ARROW
    // La bolita selecciona, no dibuja: como la selección, el lazo y la mano.
    Tool.BOLITA -> null
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
    Tool.LUPA -> ElementType.LUPA
    Tool.SPOTLIGHT -> ElementType.SPOTLIGHT
    Tool.SERIAL -> ElementType.SERIAL
    Tool.FRAME -> ElementType.FRAME
    Tool.MEASURE, Tool.SCALE -> ElementType.MEASURE
    Tool.RELLENO -> ElementType.REGION
    Tool.ESCALA_GRAFICA -> ElementType.ESCALA_GRAFICA
    Tool.SOLIDO -> ElementType.SOLIDO
    Tool.CRONOGRAMA -> ElementType.CRONOGRAMA
    // No crean figuras: transforman las que ya hay.
    Tool.EXTRUIR, Tool.REVOLUCION -> null
    // Recortar y extender no crean nada: arreglan lo que ya hay, y lo que
    // hacen no depende de ningún color ni de ningún grosor.
    Tool.SELECTION, Tool.LASSO, Tool.HAND, Tool.ERASER,
    Tool.RECORTAR, Tool.EXTENDER, Tool.NUDO -> null
}

/**
 * Qué se le puede tocar a un tipo de elemento.
 *
 * Es pública porque además de decidir qué controles salen, **decide qué se le
 * escribe encima** al aplicar un estilo: la misma tabla para las dos cosas, o un
 * panel acabaría ofreciendo algo que no se guarda, o guardando algo que no se
 * ofrece. Ver [conEstilo].
 */
fun propiedadesDeTipo(tipo: ElementType): Set<Propiedad> = when (tipo) {
    // El punto se ve en negro sobre blanco siempre —para eso está— así que de
    // color solo manda el de su letra, y del tamaño, el de la letra también.
    ElementType.PUNTO -> setOf(Propiedad.TRAZO, Propiedad.FUENTE, Propiedad.OPACIDAD)

    ElementType.RECTANGLE, ElementType.DIAMOND -> setOf(
        Propiedad.TRAZO, Propiedad.FONDO, Propiedad.RELLENO, Propiedad.LINEA,
        Propiedad.GROSOR, Propiedad.MATERIAL, Propiedad.RUGOSIDAD, Propiedad.ESQUINAS, Propiedad.OPACIDAD
    )

    // La elipse no tiene esquinas que redondear.
    ElementType.ELLIPSE -> setOf(
        Propiedad.TRAZO, Propiedad.FONDO, Propiedad.RELLENO, Propiedad.LINEA,
        Propiedad.GROSOR, Propiedad.MATERIAL, Propiedad.RUGOSIDAD, Propiedad.OPACIDAD
    )

    ElementType.ARROW -> setOf(
        Propiedad.TRAZO, Propiedad.LINEA, Propiedad.GROSOR, Propiedad.MATERIAL, Propiedad.RUGOSIDAD,
        Propiedad.PUNTAS, Propiedad.FORMA_FLECHA, Propiedad.OPACIDAD
    )

    // La línea sí admite fondo: cerrada sobre sí misma se rellena.
    ElementType.LINE -> setOf(
        Propiedad.TRAZO, Propiedad.FONDO, Propiedad.RELLENO, Propiedad.LINEA,
        Propiedad.GROSOR, Propiedad.MATERIAL, Propiedad.RUGOSIDAD, Propiedad.ESQUINAS, Propiedad.OPACIDAD
    )

    // El lápiz no lleva línea discontinua ni esquinas, que serían botones
    // muertos.
    //
    // **Fondo y relleno sí**, y hacen falta justamente para poder apagarlos: un
    // garabato que se cierra sobre sí mismo se rellena solo —lo hace el
    // original— y sin estos dos controles no había forma de quitarle la trama.
    // Se quitaron una vez por eso mismo, por creer que sobraban, y lo que se
    // consiguió fue dejar la trama puesta y sin interruptor.
    //
    // **Y la imperfección también**, aunque el lápiz no pase por rough.js.
    // Escribiendo a mano no hay nada que temblar —el trazo ya es el de tu
    // mano— así que ahí los tres niveles gradúan **cuánto se te corrige el
    // pulso**, que es la misma idea dicha al revés y se nota igual. Ver
    // [FreedrawTuning.streamlineDe].
    ElementType.FREEDRAW -> setOf(
        Propiedad.TRAZO, Propiedad.FONDO, Propiedad.RELLENO,
        Propiedad.GROSOR, Propiedad.MATERIAL, Propiedad.RUGOSIDAD, Propiedad.OPACIDAD, Propiedad.PRESION
    )

    ElementType.TEXT -> setOf(
        Propiedad.TRAZO, Propiedad.FUENTE, Propiedad.ESTILO_DE_TEXTO, Propiedad.OPACIDAD
    )

    ElementType.IMAGE -> setOf(Propiedad.ESQUINAS, Propiedad.OPACIDAD)

    // En el mosaico el grosor hace de tamaño de grano.
    ElementType.MOSAIC -> setOf(Propiedad.GROSOR, Propiedad.MATERIAL, Propiedad.MOSAICO, Propiedad.OPACIDAD)

    // El foco solo gradúa **cuánto oscurece lo de fuera**, que es lo único que
    // hace. Su opacidad no pinta nada: no dibuja tinta, apaga lo de alrededor.
    ElementType.SPOTLIGHT -> setOf(Propiedad.OSCURECER, Propiedad.ZONA)

    // La lupa: cuánto agranda, de qué forma es el cristal y si lleva flecha —eso
    // es [Propiedad.LUPA]— más el color y el grosor de su montura, que es un
    // borde como cualquier otro.
    ElementType.LUPA -> setOf(
        Propiedad.LUPA, Propiedad.TRAZO, Propiedad.GROSOR, Propiedad.MATERIAL, Propiedad.OPACIDAD
    )

    ElementType.SERIAL -> setOf(Propiedad.TRAZO, Propiedad.FUENTE, Propiedad.OPACIDAD)

    // El arco es una raya curva: ni fondo ni relleno, porque no encierra nada.
    ElementType.ARC -> setOf(
        Propiedad.TRAZO, Propiedad.LINEA, Propiedad.GROSOR, Propiedad.MATERIAL,
        Propiedad.RUGOSIDAD, Propiedad.OPACIDAD
    )

    // La cota lleva línea y número: color, grosor y letra. Ni fondo ni relleno
    // —no encierra nada— ni rugosidad, que se pinta lisa a propósito para que
    // se vea exactamente dónde acaba la medida.
    ElementType.MEASURE -> setOf(
        Propiedad.TRAZO, Propiedad.GROSOR, Propiedad.MATERIAL, Propiedad.FUENTE, Propiedad.OPACIDAD
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

    // El plano: el color de sus rayas y el tamaño de sus cifras. Ni relleno
    // —no encierra nada— ni imperfección: unos ejes temblorosos no se leen, y
    // menos con números encima.
    ElementType.PLANO, ElementType.RECTA, ElementType.ESPACIO -> setOf(
        Propiedad.TRAZO, Propiedad.GROSOR, Propiedad.MATERIAL, Propiedad.FUENTE, Propiedad.OPACIDAD
    )

    /**
     * La caja en volumen: **trazo, fondo, relleno, grosor e imperfección**.
     *
     * El fondo no es un adorno aquí, es el volumen: de él salen las tres
     * claridades de las caras (ver [aclarar]), y sin fondo la caja se queda en un
     * alambre que no se lee como bulto. Por eso [Propiedad.FONDO] es el mando más
     * importante que tiene.
     *
     * **Ni esquinas ni puntas**: no hay nada que redondear —una arista redondeada
     * dejaría de encajar con la de al lado— y no es una raya con extremos.
     */
    ElementType.SOLIDO -> setOf(
        Propiedad.TRAZO, Propiedad.FONDO, Propiedad.RELLENO,
        // **Y el tipo de línea**, que faltaba y no por ningún motivo: una caja a trazos
        // es lo que en un croquis dice «esto todavía no está» o «esto es lo que había
        // antes», y es la distinción que más se usa después del color.
        Propiedad.LINEA, Propiedad.VOLUMEN,
        Propiedad.GROSOR, Propiedad.MATERIAL, Propiedad.RUGOSIDAD, Propiedad.OPACIDAD
    )

    /**
     * El cronograma: **color, línea, grosor y letra**.
     *
     * El fondo es el color de las barras —es lo que se ve de él— y la letra importa
     * porque los nombres van dentro de la figura. Sin rugosidad a propósito: una rejilla
     * temblorosa hace que dos columnas no parezcan igual de anchas, y eso es justo lo que
     * la rejilla viene a decir.
     */
    ElementType.CRONOGRAMA -> setOf(
        Propiedad.TRAZO, Propiedad.FONDO, Propiedad.LINEA,
        Propiedad.GROSOR, Propiedad.MATERIAL, Propiedad.FUENTE, Propiedad.OPACIDAD
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
