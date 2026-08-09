package com.forge.pixpin.motor

import kotlinx.serialization.Serializable

/**
 * Puntos metidos **por coordenadas**, no a pulso.
 *
 * Hay dibujos que no se hacen mirando: un levantamiento, unos ejes, una nube de
 * medidas tomadas en obra. Ahí el dedo sobra —el dato ya existe, está en una
 * libreta— y lo que hace falta es teclearlo y que el punto aparezca donde le
 * toca, exacto.
 *
 * **El eje es uno solo para todo el dibujo.** Las tablas se distinguen por el
 * color —un perfil en rojo, otro en azul— pero todas cuentan desde el mismo
 * origen, que es lo que hace que sus puntos se puedan comparar entre sí. Con un
 * origen por tabla, dos series con las mismas coordenadas podían acabar en
 * sitios distintos del papel, y entonces el color dejaba de decir «otra serie»
 * para decir «otro mundo».
 *
 * Los puntos **no son elementos del dibujo**. Son referencias: se ven, se
 * enganchan y se editan desde su tabla, pero no se arrastran ni se borran de
 * uno en uno. Lo dibujado encima —las líneas que los unen— sí son elementos
 * normales, y esa separación es lo que permite rehacer el trazado sin volver a
 * teclear las coordenadas.
 */
@Serializable
data class TablaDeCoordenadas(
    val id: String,
    val nombre: String = "",
    /** `#rrggbb`, el mismo con el que se pintan sus puntos. */
    val color: String = COLORES_DE_TABLA.first(),
    val puntos: List<PuntoDeTabla> = emptyList(),
    /** Se puede apagar para dejar de verla sin perder lo tecleado. */
    val visible: Boolean = true
)

/** Una fila de la tabla. */
@Serializable
data class PuntoDeTabla(val x: Double = 0.0, val y: Double = 0.0)

/**
 * Los colores que se ofrecen, en orden.
 *
 * Se reparten por el círculo cromático a propósito: dos series contiguas tienen
 * que distinguirse **de un vistazo y de lejos**, que es como se miran cuando
 * hay tres encima del mismo plano.
 */
val COLORES_DE_TABLA: List<String> = listOf(
    "#e03131", "#1971c2", "#2f9e44", "#f08c00", "#9c36b5", "#0c8599"
)

/** El siguiente color libre, para que dos tablas nuevas no nazcan iguales. */
fun colorLibreDeTabla(usados: List<String>): String =
    COLORES_DE_TABLA.firstOrNull { it !in usados } ?: COLORES_DE_TABLA.first()

/**
 * Dónde cae un punto de la tabla en la escena.
 *
 * Dos conversiones, y las dos importan:
 *
 * - **La Y va hacia arriba.** En la pantalla crece hacia abajo, pero quien
 *   teclea coordenadas piensa en ejes cartesianos, donde subir es más Y. Meter
 *   un punto en (0, 10) y verlo aparecer por debajo del origen sería, con toda
 *   razón, un error.
 * - **Las unidades son las de la escala del dibujo**, si se ha calibrado: si un
 *   píxel vale 0,02 m, teclear 4 pone el punto a 200 px. Sin calibrar, un punto
 *   es una unidad, que es lo único honrado que se puede suponer.
 */
fun puntoEnEscena(origen: Pt, punto: PuntoDeTabla, escala: Escala?): Pt {
    val porUnidad = if (escala != null && escala.valida) 1.0 / escala.unidadesPorPixel else 1.0
    return Pt(
        origen.x + punto.x * porUnidad,
        origen.y - punto.y * porUnidad
    )
}

/** Todos los de una tabla, en el orden en que se teclearon. */
fun puntosEnEscena(tabla: TablaDeCoordenadas, origen: Pt, escala: Escala?): List<Pt> =
    tabla.puntos.map { puntoEnEscena(origen, it, escala) }

/**
 * El camino inverso: qué coordenadas tendría un punto de la escena.
 *
 * Hace falta para colocar el origen con el dedo y seguir viendo números que
 * cuadran, y para decir en qué coordenadas ha caído algo que se dibujó a mano.
 */
fun coordenadasDe(origen: Pt, escena: Pt, escala: Escala?): PuntoDeTabla {
    val porUnidad = if (escala != null && escala.valida) 1.0 / escala.unidadesPorPixel else 1.0
    if (porUnidad == 0.0) return PuntoDeTabla(0.0, 0.0)
    return PuntoDeTabla(
        (escena.x - origen.x) / porUnidad,
        (origen.y - escena.y) / porUnidad
    )
}

/**
 * Los anclajes que aportan las tablas.
 *
 * Van con prioridad de [TipoAnclaje.ESQUINA] porque son exactamente igual de
 * intencionados: alguien tecleó ese punto para que estuviera ahí. Trazar una
 * línea de un punto a otro tiene que pegarse sin pelearse con el dedo — es la
 * mitad de para lo que sirve meter coordenadas.
 */
fun anclajesDeTablas(
    tablas: List<TablaDeCoordenadas>, origen: Pt?, escala: Escala?
): List<Anclaje> {
    if (origen == null) return emptyList()
    return tablas.filter { it.visible }.flatMap { tabla ->
        puntosEnEscena(tabla, origen, escala).map { Anclaje(it, TipoAnclaje.ESQUINA, tabla.id) }
    }
}

/**
 * Los anclajes del **eje**: su origen y sus dos rectas.
 *
 * Las rectas no se pueden dar como una lista fija de puntos —son infinitas—, así
 * que lo que se ofrece es **la proyección del dedo sobre cada una**: el punto de
 * la recta que le queda más cerca. Como el buscador de anclajes descarta por
 * distancia, esa proyección se acepta justo cuando el dedo está a menos del
 * radio de la recta, que es exactamente lo que se quiere decir con «pegarse al
 * eje».
 *
 * [p] es dónde está el dedo, sin enganchar todavía.
 */
fun anclajesDelEje(
    origen: Pt?,
    p: Pt,
    /** Radio de captura en unidades de escena; el mismo que usa el buscador. */
    radio: Double = 0.0,
    activo: Boolean = true
): List<Anclaje> {
    if (origen == null || !activo) return emptyList()

    // **Cerca del cero manda el cero**, y no se ofrecen siquiera las rectas.
    //
    // Hace falta decirlo aquí porque el buscador elige por distancia, y a un
    // paso del origen la proyección sobre la horizontal siempre queda algo más
    // cerca que el origen mismo — es un cateto contra su hipotenusa. Sin esta
    // regla, apuntar al cero daba «encima del eje, un poco a la derecha», que
    // es lo que uno estaba tratando de evitar.
    val alOrigen = kotlin.math.hypot(p.x - origen.x, p.y - origen.y)
    if (radio > 0.0 && alOrigen <= radio) {
        return listOf(Anclaje(origen, TipoAnclaje.EJE, EJE_ID))
    }

    return listOf(
        Anclaje(origen, TipoAnclaje.EJE, EJE_ID),
        // Sobre la horizontal: se conserva la X y se toma la Y del eje.
        Anclaje(Pt(p.x, origen.y), TipoAnclaje.EJE, EJE_ID),
        // Sobre la vertical, al revés.
        Anclaje(Pt(origen.x, p.y), TipoAnclaje.EJE, EJE_ID)
    )
}

/** El eje no es un elemento, pero un anclaje tiene que decir de dónde sale. */
const val EJE_ID = "eje"

/**
 * Lee un número tecleado, con coma o con punto.
 *
 * Y admite el signo: media tabla de coordenadas vive en negativo.
 */
fun leerCoordenada(texto: String): Double? =
    texto.trim().replace(',', '.').toDoubleOrNull()

// -------------------------------------------------------------------------
// Pegar desde una hoja de cálculo
// -------------------------------------------------------------------------

/**
 * Una tabla de coordenadas copiada de Excel, de Google Sheets o de un PDF.
 *
 * **Teclear coordenadas a mano en un móvil es lo más lento que hace esta app.**
 * Cincuenta puntos son cien números en un teclado numérico, y el que los tiene
 * ya los tiene escritos en algún sitio: en una hoja de cálculo, en un correo, en
 * la tabla de un PDF. Copiar y pegar convierte diez minutos en dos toques.
 *
 * ## Qué se admite
 *
 * Lo que sale del portapapeles no viene en un formato, viene en el que sea:
 *
 * - **Excel y Google Sheets** separan por tabulador. Es el caso corriente.
 * - Un **CSV** europeo separa por `;`, y uno inglés por `,`.
 * - Una tabla **copiada de un PDF** suele venir con varios espacios.
 * - Y muchas traen una **columna de nombre** delante —`P1`, `A`, `Est. 3`— y una
 *   **cabecera** arriba.
 *
 * De cada fila se cogen **las dos primeras columnas que sean números**, así que
 * la columna de nombres se salta sola y no hay que preparar nada antes de
 * copiar. Una fila sin dos números —la cabecera, una línea en blanco, un total—
 * se descarta en silencio: avisar de cada una convertiría pegar en un
 * interrogatorio.
 */
fun leerTablaPegada(texto: String?): List<PuntoDeTabla> {
    val lineas = texto?.lineSequence()?.map { it.trim() }?.filter { it.isNotEmpty() }?.toList()
    if (lineas.isNullOrEmpty()) return emptyList()
    val separador = separadorDe(lineas) ?: return emptyList()
    return lineas.mapNotNull { linea ->
        val numeros = linea.split(separador)
            .map { it.trim().trim('|') }
            .mapNotNull { leerNumeroDeHoja(it) }
        if (numeros.size >= 2) PuntoDeTabla(numeros[0], numeros[1]) else null
    }
}

/**
 * Con qué se separan las columnas.
 *
 * Se prueban por orden de **cuán seguro es cada uno**, no de cuán común:
 *
 * El tabulador no puede ser otra cosa, así que va primero — y es el que usan
 * Excel y Sheets, o sea el caso que importa. El punto y coma y la barra
 * tampoco. Los espacios sí podrían ser parte de un nombre (`Est. 3`), pero solo
 * se aceptan de dos en adelante, que es como quedan las columnas de un PDF.
 *
 * **La coma va la última y con condiciones**, porque en español es el separador
 * decimal: `12,5` es un número, no dos. Solo se toma como separador si la línea
 * parte en tres trozos o más —ahí ya no hay duda— o si los trozos llevan punto
 * decimal, que es la pinta de un CSV inglés. Con `12,5` a secas gana el número,
 * que es lo que quiso escribir quien lo escribió.
 */
private fun separadorDe(lineas: List<String>): Regex? {
    if (lineas.any { it.contains('\t') }) return Regex("\t")
    if (lineas.any { it.contains(';') }) return Regex(";")
    if (lineas.any { it.contains('|') }) return Regex("\\|")
    if (lineas.any { Regex("\\S {2,}\\S").containsMatchIn(it) }) return Regex(" {2,}")
    if (lineas.any { linea ->
            val trozos = linea.split(',')
            trozos.size >= 3 || (trozos.size == 2 && trozos.any { it.contains('.') })
        }
    ) {
        return Regex(",")
    }
    // Sin separador que valga, cada línea es una sola columna: no hay pares que
    // formar. Devolver un separador que no aparece daría filas de un número y
    // todas se caerían igual, pero más tarde y habiendo trabajado.
    return null
}

/**
 * Un número tal como lo escribe una hoja de cálculo, venga del país que venga.
 *
 * `1.234,56` y `1,234.56` son el mismo número escrito por un español y por un
 * inglés, y del portapapeles llegan los dos. La regla que los separa es simple y
 * no falla: **manda el último signo de puntuación**. El que va detrás es el
 * decimal y el otro es de los miles, porque los miles nunca van después de la
 * coma decimal.
 *
 * Con uno solo hay que decidir. `1,5` es decimal en español y `1.5` en inglés,
 * los dos con la misma pinta y el mismo valor si se leen como decimal — así que
 * se leen como decimal y se acabó. El único caso que sale mal es `1.234`
 * queriendo decir mil doscientos treinta y cuatro, y quien pega coordenadas de
 * un plano prefiere que un metro con doscientos treinta y cuatro se lea bien.
 */
internal fun leerNumeroDeHoja(campo: String): Double? {
    val texto = campo.trim().trim('"', '\'').trim()
    if (texto.isEmpty()) return null

    // **Tiene que empezar por número.** Antes se filtraban las letras de todo el
    // campo para poder leer «12,5 m», y eso convertía la columna de nombres en
    // números: `P1` se quedaba en `1` y `E2` en `2`, así que la primera columna
    // de una libreta de campo entraba como coordenada y todos los puntos caían
    // en el sitio equivocado. Un nombre no empieza por cifra y una medida sí, y
    // esa es toda la diferencia que hace falta.
    if (!(texto[0].isDigit() || texto[0] == '-' || texto[0] == '+' ||
            texto[0] == '.' || texto[0] == ',')
    ) {
        return null
    }

    // De ahí se coge el número y se tira lo que venga detrás, que es la unidad.
    val limpio = texto.takeWhile {
        it.isDigit() || it == '.' || it == ',' || it == '-' || it == '+'
    }
    if (limpio.isEmpty() || limpio.none { it.isDigit() }) return null

    val ultimaComa = limpio.lastIndexOf(',')
    val ultimoPunto = limpio.lastIndexOf('.')
    val normalizado = when {
        ultimaComa >= 0 && ultimoPunto >= 0 ->
            if (ultimaComa > ultimoPunto) {
                limpio.replace(".", "").replace(',', '.')
            } else {
                limpio.replace(",", "")
            }
        ultimaComa >= 0 -> limpio.replace(',', '.')
        else -> limpio
    }
    return normalizado.toDoubleOrNull()
}
