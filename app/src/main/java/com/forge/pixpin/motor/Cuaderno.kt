package com.forge.pixpin.motor

/**
 * El cuaderno: **el lienzo infinito puesto en hojas**.
 *
 * ## Por qué no hay un tipo de elemento nuevo
 *
 * Una hoja **es un marco**. El marco ya existía y ya significaba exactamente esto —«esto
 * de aquí es el dibujo»—, ya recorta lo que enseña un pin, ya decide el encuadre al
 * exportar y, lo que más importa, [DrawPdf] ya saca **una página de PDF por marco**. Un
 * `ElementType` nuevo habría duplicado todo eso y habría dejado dos conceptos que se
 * parecen y no son el mismo, que es la peor forma de tener uno.
 *
 * Lo único que le faltaba al marco para ser una hoja de cuaderno son tres cosas, y las
 * tres son datos, no maquinaria: **de qué tamaño es** ([TamanoDePapel]), **qué pauta
 * trae impresa** ([PautaDeHoja]) y **dónde cae la siguiente**. Esto último es este módulo.
 *
 * ## Cómo se apilan
 *
 * Una debajo de otra, alineadas por la izquierda y separadas por un hueco fijo. Es la
 * disposición de cualquier aplicación de notas y la que hace que desplazarse hacia abajo
 * sea pasar páginas. No se colocan en rejilla ni al lado: hacia abajo hay sitio infinito
 * y hacia los lados se acaba la pantalla.
 *
 * Y no se guarda ningún índice ni ninguna lista: **el orden sale de dónde están**. Una
 * hoja arrastrada más arriba que otra pasa a ir antes, sin que haya que renumerar nada ni
 * mantener sincronizados dos sitios. Ver [hojasEnOrden].
 */

/** Lo que mide de ancho una hoja recién puesta, en píxeles de escena. */
const val ANCHO_DE_LA_HOJA = 820.0

/** El hueco entre dos hojas seguidas. Lo justo para que se lean como dos y no como una. */
const val HUECO_ENTRE_HOJAS = 60.0

/**
 * Lo que se ve la pauta al imprimirla, en tanto por ciento del trazo.
 *
 * Tenue: es papel, no dibujo. Si compite con lo escrito encima deja de ser una guía y
 * pasa a ser ruido — que es exactamente lo que le pasa a un cuaderno de rayas mal
 * impreso.
 */
const val OPACIDAD_DE_LA_PAUTA = 22

/** El paso de la pauta impresa. El mismo que el del papel pautado del lienzo. */
const val PASO_DE_LA_PAUTA = PASO_DE_CUADRICULA

/**
 * Las hojas del cuaderno, **en el orden en que se leen**: de arriba abajo.
 *
 * De arriba abajo y, a igualdad, de izquierda a derecha. Sale de la posición y no de una
 * lista guardada aparte a propósito: mover una hoja **es** reordenar el cuaderno, y no hay
 * dos sitios que puedan contradecirse.
 */
fun hojasEnOrden(scene: Scene): List<Element> =
    scene.marcos.sortedWith(compareBy({ it.y }, { it.x }))

/**
 * Dónde va la hoja siguiente: **debajo de la última**.
 *
 * Con el cuaderno vacío, donde diga [dondeSiNoHay] —que es el centro de lo que se está
 * mirando, para que la primera hoja aparezca delante y no en el origen del lienzo, que
 * puede estar a media pantalla de distancia.
 */
fun sitioDeLaHojaSiguiente(
    scene: Scene,
    tamano: TamanoDePapel,
    dondeSiNoHay: Pt
): Element {
    val ultima = hojasEnOrden(scene).lastOrNull()
    val ancho = ultima?.width ?: ANCHO_DE_LA_HOJA
    val alto = ancho * tamano.proporcion
    val x = ultima?.x ?: (dondeSiNoHay.x - ancho / 2)
    val y = ultima?.let { it.y + it.height + HUECO_ENTRE_HOJAS } ?: (dondeSiNoHay.y - alto / 2)
    return Element(
        id = randomId(),
        type = ElementType.FRAME,
        x = x,
        y = y,
        // **Del ancho de la anterior.** Un cuaderno con hojas de tamaños distintos no es
        // un cuaderno; y si alguien estira una, las siguientes la siguen, que es lo que
        // uno espera después de haberse molestado en cambiarla.
        width = ancho,
        height = alto,
        papel = tamano,
        pauta = ultima?.pauta ?: PautaDeHoja.LISA,
        seed = randomSeed(),
        versionNonce = randomVersionNonce(),
        updated = System.currentTimeMillis()
    )
}

/**
 * Las rayas de la pauta de una hoja, en coordenadas de escena.
 *
 * Devuelve segmentos y no un camino porque quien pinta —la pantalla, el SVG y el PDF— los
 * quiere de tres formas distintas, y porque así la cuenta se comprueba sin dispositivo.
 * Los puntos van como segmentos de largo cero: el que pinta decide si son un redondel o
 * una cruz.
 */
fun rayasDeLaPauta(hoja: Element, paso: Double = PASO_DE_LA_PAUTA): List<Pair<Pt, Pt>> {
    if (hoja.pauta == PautaDeHoja.LISA || paso <= 0.0) return emptyList()
    val c = getElementAbsoluteCoords(hoja)
    if (c.x2 - c.x1 <= paso || c.y2 - c.y1 <= paso) return emptyList()
    val salida = mutableListOf<Pair<Pt, Pt>>()

    // El margen deja el borde limpio: una raya pegada al canto se confunde con él.
    var y = c.y1 + paso
    while (y < c.y2 - 1e-9) {
        if (hoja.pauta == PautaDeHoja.PUNTOS) {
            var x = c.x1 + paso
            while (x < c.x2 - 1e-9) {
                salida += Pt(x, y) to Pt(x, y)
                x += paso
            }
        } else {
            salida += Pt(c.x1, y) to Pt(c.x2, y)
        }
        y += paso
    }
    if (hoja.pauta != PautaDeHoja.CUADROS) return salida

    var x = c.x1 + paso
    while (x < c.x2 - 1e-9) {
        salida += Pt(x, c.y1) to Pt(x, c.y2)
        x += paso
    }
    return salida
}

/**
 * El número de una hoja dentro del cuaderno, empezando en uno. Cero si no está.
 *
 * Se cuenta al vuelo desde la posición por lo mismo que [hojasEnOrden]: guardarlo obligaría
 * a renumerar al mover, y un número guardado que no se renumera es un número que miente.
 */
fun numeroDeHoja(scene: Scene, hoja: Element): Int =
    hojasEnOrden(scene).indexOfFirst { it.id == hoja.id } + 1
