package com.forge.pixpin.motormd

/**
 * El camino de vuelta: de texto limpio con estilos **a Markdown**.
 *
 * ## Por qué hace falta
 *
 * Telegram nunca enseña un asterisco porque no guarda ninguno: guarda el texto
 * por un lado y los estilos por otro. Para que aquí se vea igual hay que hacer
 * lo mismo **mientras se escribe**, y como lo que se guarda en disco sí es
 * Markdown —de él viven la exportación, el PDF, el SVG y los pines—, hace falta
 * traducir en los dos sentidos.
 *
 * [Markdown.parseInline] va de Markdown a texto limpio con tramos. Esto es la
 * vuelta. Que las dos encajen no se vigila leyendo: hay una prueba que coge un
 * texto con estilos, lo escribe, lo vuelve a leer y comprueba que sale lo mismo.
 *
 * ## El escapado
 *
 * Si alguien escribe `2 * 3 * 4` en un párrafo, ese texto limpio no lleva ningún
 * estilo. Pero al escribirlo tal cual y volver a leerlo, los dos asteriscos
 * parecerían una cursiva y el texto volvería en cursiva sin que nadie la pusiera.
 * Por eso las marcas que no son marcas salen **escapadas con barra invertida**,
 * que es lo que hace Markdown de toda la vida, y el parser las deshace al leer.
 */
object Inline {

    /** Los caracteres que hay que escapar para que no se lean como marca. */
    private const val PELIGROSOS = "*_~`|[]\\"

    /**
     * Escribe [texto] como Markdown, con sus marcas alrededor de cada tramo.
     *
     * Los tramos se piden aplanados —ver [tramosDe]—, así que no se pisan y cada
     * uno se envuelve entero. El orden de las marcas es siempre el mismo, de
     * fuera adentro: enlace, tapado, negrita, cursiva, tachado y código. Da igual
     * cuál sea mientras no cambie, porque al leerlas otra vez todas acaban
     * apuntando al mismo trozo.
     */
    fun aTexto(texto: InlineText): String {
        if (texto.text.isEmpty()) return ""
        val piezas = piezasDe(texto)
        return escribe(piezas, 0, piezas.size, texto.text, emptySet())
    }

    /** De fuera adentro. Fijo, para que la ida y la vuelta siempre coincidan. */
    private val ORDEN = listOf(
        SpanKind.LINK, SpanKind.SPOILER, SpanKind.BOLD,
        SpanKind.ITALIC, SpanKind.STRIKE, SpanKind.CODE
    )

    /** El texto entero partido en trozos, incluidos los que no llevan estilo. */
    private fun piezasDe(texto: InlineText): List<Tramo> {
        val conEstilo = texto.tramos()
        val salida = mutableListOf<Tramo>()
        var pos = 0
        conEstilo.forEach {
            if (it.inicio > pos) salida += Tramo(pos, it.inicio, 0)
            salida += it
            pos = it.fin
        }
        if (pos < texto.text.length) salida += Tramo(pos, texto.text.length, 0)
        return salida
    }

    /**
     * Escribe los trozos de [desde] a [hasta] reconstruyendo el anidamiento.
     *
     * Emitir las marcas trozo a trozo no vale. Con «muy» en negrita seguido de
     * «importante» en negrita y cursiva saldrían `**muy **` y `***importante***`
     * pegados, y cinco asteriscos seguidos no significan nada claro: al leerlos
     * otra vez la cursiva se escapa y aparece un asterisco suelto en el texto.
     *
     * Así que primero se busca **el estilo que tienen todos** y se envuelve el
     * grupo entero con él; luego se repite por dentro con lo que quede. Sale lo
     * mismo que escribiría una persona —`**muy _importante_**`— y se vuelve a
     * leer sin ambigüedad.
     */
    private fun escribe(
        piezas: List<Tramo>,
        desde: Int,
        hasta: Int,
        texto: String,
        aplicados: Set<SpanKind>
    ): String {
        if (desde >= hasta) return ""

        val comun = ORDEN.firstOrNull { kind ->
            kind !in aplicados &&
                (desde until hasta).all { piezas[it].tiene(kind) } &&
                // Dos enlaces distintos no se pueden envolver en uno solo.
                (kind != SpanKind.LINK ||
                    (desde until hasta).map { piezas[it].url }.distinct().size == 1)
        }

        if (comun != null) {
            val dentro = escribe(piezas, desde, hasta, texto, aplicados + comun)
            return marcar(comun, dentro, piezas[desde].url)
        }

        // Sin nada en común, cada trozo por su cuenta.
        return (desde until hasta).joinToString("") { i ->
            val pieza = piezas[i]
            val quedaAlgo = ORDEN.any { it !in aplicados && pieza.tiene(it) }
            if (!quedaAlgo) {
                escapar(texto.substring(pieza.inicio, pieza.fin))
            } else {
                escribe(piezas, i, i + 1, texto, aplicados)
            }
        }
    }

    /**
     * La cursiva se escribe con guion bajo y no con asterisco **a propósito**.
     *
     * Con asterisco, una cursiva pegada al cierre de una negrita deja tres
     * asteriscos seguidos y al leerlos otra vez no se sabe cuál cierra qué. Con
     * guion bajo no pueden chocar nunca, porque la negrita no lo usa.
     */
    private fun marcar(kind: SpanKind, cuerpo: String, url: String?): String = when (kind) {
        SpanKind.CODE -> "`$cuerpo`"
        SpanKind.STRIKE -> "~~$cuerpo~~"
        SpanKind.ITALIC -> "_${cuerpo}_"
        SpanKind.BOLD -> "**$cuerpo**"
        SpanKind.SPOILER -> "||$cuerpo||"
        SpanKind.LINK -> "[$cuerpo](${url.orEmpty()})"
    }

    /**
     * Pone una barra delante de lo que se leería como marca.
     *
     * Dentro de un tramo de código no haría falta —ahí no se interpreta nada—,
     * pero escapar de más no rompe nada y distinguir los dos casos sí puede
     * romperlo: el acento grave de dentro tendría que escaparse igual.
     */
    fun escapar(s: String): String {
        val salida = StringBuilder(s.length)
        s.forEach { c ->
            if (c in PELIGROSOS) salida.append('\\')
            salida.append(c)
        }
        return salida.toString()
    }

    // ---- Editar los estilos sin tocar marcas ----

    /**
     * Recoloca los tramos después de un cambio en el texto.
     *
     * Al escribir una letra en medio de una palabra en negrita, la negrita tiene
     * que crecer con ella; al borrar un trozo, encogerse. Sin esto los estilos se
     * quedarían clavados en posiciones que ya no significan lo mismo y el texto
     * saldría en negrita a partir del sitio equivocado.
     *
     * El cambio se deduce comparando lo que había con lo que hay: lo que
     * coincide por delante y por detrás no se ha tocado, y lo de en medio es lo
     * que ha cambiado. Es lo que hace cualquier editor y no necesita que el campo
     * de texto cuente nada.
     */
    fun desplazar(antes: String, despues: String, spans: List<InlineSpan>): List<InlineSpan> {
        if (antes == despues || spans.isEmpty()) return spans

        var cabeza = 0
        val minimo = minOf(antes.length, despues.length)
        while (cabeza < minimo && antes[cabeza] == despues[cabeza]) cabeza++

        var cola = 0
        while (cola < minimo - cabeza &&
            antes[antes.length - 1 - cola] == despues[despues.length - 1 - cola]
        ) {
            cola++
        }

        val quitados = antes.length - cabeza - cola
        val puestos = despues.length - cabeza - cola
        val diferencia = puestos - quitados
        val finDelHueco = cabeza + quitados

        return spans.mapNotNull { s ->
            val inicio = mover(s.start, cabeza, finDelHueco, diferencia)
            val fin = mover(s.end, cabeza, finDelHueco, diferencia)
            // Un tramo que se queda sin letras desaparece: borrar la única
            // palabra en negrita no puede dejar una negrita vacía esperando.
            if (fin <= inicio) null else s.copy(start = inicio, end = fin)
        }
    }

    private fun mover(pos: Int, desde: Int, hasta: Int, diferencia: Int): Int = when {
        pos <= desde -> pos
        pos >= hasta -> pos + diferencia
        // Dentro de lo borrado: se pega al principio del hueco.
        else -> desde
    }

    /**
     * Pone o quita un estilo sobre un trozo, sin escribir ni una marca.
     *
     * Es la versión en estilos de alternar por cobertura: si ya lo cubre entero,
     * lo quita; si no, lo pone. Igual que su `toggleStyleForSelection`, pero
     * operando sobre los tramos en vez de sobre los asteriscos.
     */
    fun alternar(
        spans: List<InlineSpan>,
        desde: Int,
        hasta: Int,
        kind: SpanKind,
        url: String? = null
    ): List<InlineSpan> {
        if (hasta <= desde) return spans
        val cubierto = cubre(spans, desde, hasta, kind)

        // Se recorta lo que hubiera de este mismo estilo en el trozo, y luego se
        // añade entero si tocaba ponerlo. Así no quedan dos tramos del mismo
        // estilo pisándose, que al escribirlos daría marcas duplicadas.
        val recortados = spans.flatMap { s ->
            if (s.kind != kind || s.end <= desde || s.start >= hasta) {
                listOf(s)
            } else {
                listOfNotNull(
                    if (s.start < desde) s.copy(end = desde) else null,
                    if (s.end > hasta) s.copy(start = hasta) else null
                )
            }
        }
        return if (cubierto) recortados else recortados + InlineSpan(desde, hasta, kind, url)
    }

    /** ¿Cubre [kind] todo el trozo? Es su `getCurrentStyle`, en estilos. */
    fun cubre(spans: List<InlineSpan>, desde: Int, hasta: Int, kind: SpanKind): Boolean {
        if (hasta <= desde) return false
        var alcanzado = desde
        var avanzo = true
        while (avanzo && alcanzado < hasta) {
            avanzo = false
            spans.forEach { s ->
                if (s.kind == kind && s.start <= alcanzado && s.end > alcanzado) {
                    alcanzado = s.end
                    avanzo = true
                }
            }
        }
        return alcanzado >= hasta
    }

    /** Los estilos que cubren el trozo entero, para encender los botones. */
    fun estilosDe(spans: List<InlineSpan>, desde: Int, hasta: Int): Set<SpanKind> =
        SpanKind.entries.filter { cubre(spans, desde, hasta, it) }.toSet()
}
