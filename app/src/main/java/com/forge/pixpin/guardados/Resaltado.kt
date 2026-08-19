package com.forge.pixpin.guardados

/**
 * Dónde pintar el subrayado dentro de un resultado de búsqueda.
 *
 * ## Por qué hace falta
 *
 * [buscar] deja la lista filtrada pero muda: enseña seis notas y no dice de ninguna **por
 * qué está ahí**. Con una consulta corta —«pra»— el ojo tiene que releer seis textos
 * enteros buscando tres letras, y a esa altura ya sale más barato no buscar. Telegram
 * resuelve lo mismo marcando el tramo que casó dentro de la celda
 * (`cell.setHighlightedText`, pintado con `chat_textSearchSelectionPaint`); esto calcula
 * ese tramo, y solo eso: aquí no se pinta nada, se devuelven índices.
 *
 * ## Por qué es un archivo aparte con pruebas propias
 *
 * Porque el resaltado trabaja con **índices**, y un índice mal calculado no falla: pinta.
 * Pinta el trozo de al lado, corrido un carácter, y eso no lanza ninguna excepción ni sale
 * en ningún registro — se ve raro en un nombre con tilde y nadie sabe decir por qué. Es
 * justo el tipo de error que solo caza una prueba que compruebe la longitud de lo
 * recortado, y por eso está aquí y no dentro de un composable.
 *
 * ## El punto delicado: [sinAcentos] **no** conserva la longitud
 *
 * La tentación es normalizar con [sinAcentos] y usar el índice resultante sobre el texto
 * original. No vale, y el motivo es un solo carácter en todo Unicode: [sinAcentos] empieza
 * por `lowercase()`, y `"İ".lowercase()` (U+0130, la I con punto del turco) devuelve **dos**
 * caracteres, la `i` más un punto combinante U+0307. Se comprobó recorriendo el juego
 * entero de caracteres asignados: es el único caso, pero basta. En «Café İstanbul mañana»
 * el texto normalizado mide un carácter más que el original, así que todo lo que va detrás
 * de la `İ` queda corrido y el resaltado de «mañana» cae sobre «añana ». Un nombre propio
 * extranjero en una nota guardada rompe el subrayado del resto de la línea, en silencio.
 *
 * El resto de sospechosos habituales resultaron inocentes y no hace falta defenderse de
 * ellos: la `ß` en minúsculas ya está, las ligaduras tipo `ﬁ` no se descomponen al bajar de
 * caja, y la sigma final griega cambia de forma pero no de longitud. Los pares subrogados
 * (emoji) también sobreviven, porque se recorre por unidad UTF-16 y cada una se copia tal
 * cual.
 *
 * La solución elegida es normalizar **carácter a carácter** con [sinAcentosUnoAUno], que
 * devuelve un `Char` por cada `Char` y por tanto conserva la longitud por construcción. Se
 * prefirió eso a construir un mapa de posiciones porque el mapa es estructura que hay que
 * mantener a la par del texto, mientras que la invariante «una entrada, una salida» se
 * enuncia en la firma y se prueba de una vez para todos los caracteres.
 *
 * ## Qué se le pide a este archivo
 *
 * Lógica pura, sin nada de Android, para que se pueda comprobar de verdad. Y rápido: esto
 * corre por cada fila visible y por cada tecla que se pulsa en el buscador, de modo que el
 * camino habitual —texto ASCII— no reserva memoria por carácter.
 */

/**
 * Los tramos del **texto original** donde aparece [consulta].
 *
 * Los rangos son inclusivos por los dos extremos, listos para `substring(r.first,
 * r.last + 1)` o para un `SpanStyle` sobre el texto tal cual está guardado — sin acentos
 * quitados ni nada: el texto que se pinta es el original.
 *
 * Compara como compara [buscar] —sin distinguir mayúsculas ni tildes, y recortando los
 * espacios de la consulta— porque las dos cosas tienen que decir siempre lo mismo. Si el
 * filtro deja pasar una nota y el resaltado no encuentra dónde, el usuario ve un resultado
 * sin marca y cree que la búsqueda se ha equivocado.
 *
 * Devuelve **todas** las apariciones, no solo la primera: una nota larga que repite la
 * palabra cinco veces y solo tiene una marcada obliga a leérsela entera igualmente, que era
 * el problema de partida.
 *
 * Las coincidencias **no se solapan**: tras cada una el cursor salta al final del tramo, de
 * modo que «aaa» buscando «aa» da un único tramo `0..1` y no también `1..2`. Solapadas no
 * significarían nada al pintarlas —dos subrayados encima del mismo carácter son un
 * subrayado— y en cambio abren la puerta a que un tramo empiece dentro de otro y el
 * dibujado tenga que ordenarlos y fundirlos.
 *
 * Devuelve lista vacía cuando la consulta está vacía o es solo espacios: sin nada que
 * buscar no se marca nada. Resaltar el texto entero, que es lo que saldría de tratar la
 * cadena vacía como coincidencia, dejaría toda la pantalla subrayada nada más abrir el
 * buscador.
 */
fun tramosQueCasan(texto: String, consulta: String): List<IntRange> {
    val q = sinAcentosUnoAUno(consulta.trim())
    // Ni consulta vacía ni consulta más larga que el texto: las dos se cortan aquí, antes
    // de normalizar el texto, que es el trabajo caro.
    if (q.isEmpty() || q.length > texto.length) return emptyList()

    val t = sinAcentosUnoAUno(texto)
    val tramos = mutableListOf<IntRange>()
    var desde = 0
    while (desde <= t.length - q.length) {
        val i = t.indexOf(q, desde)
        if (i < 0) break
        tramos.add(i until i + q.length)
        desde = i + q.length
    }
    return tramos
}

/**
 * Un trozo del texto alrededor de un tramo, con «…» donde se ha cortado.
 *
 * Para cuando la nota es larguísima y la palabra buscada está en el carácter 900: la fila
 * de resultados enseña el principio del texto, donde no hay nada marcado, y el usuario
 * concluye que la búsqueda falla. Esto devuelve el trozo que sí contiene la coincidencia y
 * **el tramo recolocado** sobre ese trozo, porque el índice viejo ya no vale una vez se ha
 * recortado por delante — y volver a buscar sobre el recorte sería repetir el trabajo y
 * arriesgarse a marcar otra aparición distinta.
 *
 * [margen] son los caracteres de contexto a cada lado. Se corta por carácter y no por
 * palabra: partir una palabra por la mitad se lee perfectamente detrás de unos puntos
 * suspensivos, y buscar el espacio más cercano haría que el ancho del recorte dependiera
 * del texto, que es peor para una lista donde todas las filas deberían medir parecido.
 */
fun recortadoAlrededor(texto: String, tramo: IntRange, margen: Int = 40): Pair<String, IntRange> {
    val primero = tramo.first.coerceIn(0, texto.length)
    val ultimo = (tramo.last + 1).coerceIn(primero, texto.length)
    val inicio = (primero - margen).coerceAtLeast(0)
    val fin = (ultimo + margen).coerceAtMost(texto.length)

    val puntosDelante = if (inicio > 0) PUNTOS else ""
    val puntosDetras = if (fin < texto.length) PUNTOS else ""
    val trozo = puntosDelante + texto.substring(inicio, fin) + puntosDetras

    // Lo que se ha quitado por delante menos lo que se ha añadido de puntos.
    val desplazamiento = puntosDelante.length - inicio
    return trozo to (primero + desplazamiento until ultimo + desplazamiento)
}

/** Los puntos suspensivos de [recortadoAlrededor]: **un** carácter, no tres. */
private const val PUNTOS = "…"

/**
 * [sinAcentos] con la garantía de que **sale un carácter por cada carácter que entra**.
 *
 * Esa garantía es lo único que hace que los índices calculados sobre el texto normalizado
 * valgan sobre el original, y no la da [sinAcentos]: ver el porqué al principio del archivo
 * (`"İ".lowercase()` mide dos).
 *
 * Delega en [sinAcentos] carácter a carácter en vez de repetir la tabla de vocales, para
 * que las dos no puedan separarse: el día que alguien añada la `ç` o las tildes checas allí,
 * el resaltado las hereda sin tocar nada. Cuando la conversión de un carácter suelto no
 * mide uno —el único caso conocido es la `İ`— se baja de caja sin más, que para ese
 * carácter da la `i` limpia y sigue casando con lo que encontró [buscar].
 *
 * El ASCII no pasa por [sinAcentos] y no es un atajo dudoso: para esos 128 caracteres
 * [sinAcentos] se reduce a bajar de caja, porque su tabla no tiene ninguna entrada ASCII.
 * Se hace así porque este bucle corre por cada fila visible en cada pulsación de tecla, y
 * reservar una cadena de un carácter para cada letra de cada nota es justo el gasto que se
 * nota al escribir deprisa en el buscador.
 */
fun sinAcentosUnoAUno(texto: String): String {
    val sb = StringBuilder(texto.length)
    for (c in texto) sb.append(sinAcentoDe(c))
    return sb.toString()
}

/** Un carácter normalizado como lo normaliza [sinAcentos], garantizando que sigue siendo uno. */
fun sinAcentoDe(c: Char): Char {
    if (c.code < 128) return c.lowercaseChar()
    val convertido = sinAcentos(c.toString())
    return if (convertido.length == 1) convertido[0] else c.lowercaseChar()
}
