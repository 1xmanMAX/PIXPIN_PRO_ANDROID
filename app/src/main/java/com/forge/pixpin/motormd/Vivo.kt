package com.forge.pixpin.motormd

import androidx.compose.ui.text.TextRange

/**
 * Dónde se está escribiendo: en qué bloque y sobre qué trozo de su texto.
 *
 * [seleccion] va en **coordenadas del texto limpio del bloque**, no del Markdown
 * ni del documento. Es lo que ve el que escribe: si la nota dice «muy» en
 * negrita, el cursor detrás de la y está en la posición 3, no en la 5 contando
 * asteriscos. Todo lo que toca la barra de formato habla en estas coordenadas.
 */
data class Sitio(
    val bloque: Int,
    val seleccion: TextRange = TextRange.Zero,
    /** La celda de una tabla, contando por filas. -1 si el bloque no es tabla. */
    val celda: Int = -1
)

/**
 * El puente entre el documento en Markdown y lo que se edita sin marcas.
 *
 * El documento en disco sigue siendo Markdown —de él viven el PDF, el SVG y los
 * pines—, pero **nadie ve un asterisco**: se lee el bloque, se saca su texto
 * limpio con estilos, se edita eso, y se vuelve a escribir. Es el modelo de
 * Telegram, donde el texto y el formato son cosas separadas, con la diferencia
 * de que aquí la traducción ocurre en cada pulsación en vez de solo al enviar.
 *
 * Que la ida y la vuelta no pierdan nada es lo único que sostiene todo esto, y
 * está probado aparte en [Inline].
 */
object Vivo {

    /** El contenido editable del bloque [n], ya limpio de marcas. */
    fun contenido(texto: String, n: Int): InlineText? {
        val trozo = trozosDe(texto).getOrNull(n) ?: return null
        return contenidoDelTrozo(trozo.de(texto))
    }

    fun contenidoDelTrozo(fuente: String): InlineText? =
        when (val b = Markdown.parse(fuente).firstOrNull()) {
            is MarkdownBlock.Paragraph -> b.content
            is MarkdownBlock.Heading -> b.content
            is MarkdownBlock.Quote -> b.content
            is MarkdownBlock.Bullet -> b.content
            is MarkdownBlock.Numbered -> b.content
            is MarkdownBlock.Tarea -> b.content
            is MarkdownBlock.Code -> InlineText(b.text)
            is MarkdownBlock.Formula -> InlineText(b.latex)
            // Un bloque vacío se edita como un párrafo vacío: es lo que hace
            // falta para poder escribir en un renglón recién creado.
            null -> InlineText("")
            else -> null
        }

    /** El tipo del bloque [n], para saber con qué envolverlo al escribirlo. */
    fun tipo(texto: String, n: Int): TipoDeBloque? {
        val trozo = trozosDe(texto).getOrNull(n) ?: return null
        return Menus.tipoDe(trozo.de(texto))
    }

    /**
     * Escribe [nuevo] en el bloque [n] y devuelve el documento entero.
     *
     * Los saltos de línea del final se respetan: son la separación entre
     * bloques, no parte de lo escrito, y metiéndolos en la conversión cada
     * pulsación se iría comiendo uno.
     */
    fun conContenido(texto: String, n: Int, nuevo: InlineText): String {
        val trozos = trozosDe(texto)
        val trozo = trozos.getOrNull(n) ?: return texto
        val fuente = trozo.de(texto)
        val cola = fuente.takeLastWhile { it == '\n' }
        val tipo = Menus.tipoDe(fuente.trimEnd('\n'))

        val cuerpo = when (tipo) {
            // El código y la fórmula no llevan estilos dentro: lo que se escribe
            // es literal, y pasarlo por el escapador metería barras invertidas
            // en medio de una ruta o de una ecuación.
            TipoDeBloque.CODIGO, TipoDeBloque.FORMULA -> nuevo.text
            else -> Inline.aTexto(nuevo)
        }
        val escrito = if (tipo == null) cuerpo else Bloques.envuelve(tipo, cuerpo)

        return texto.substring(0, trozo.desde) + escrito + cola + texto.substring(trozo.hasta)
    }

    /**
     * Un bloque nuevo detrás del [n], y el sitio donde queda el cursor.
     *
     * Es lo que pasa al pulsar intro: en un editor por bloques eso no mete un
     * salto de línea, **abre un bloque**. La lista y las casillas siguen siendo
     * lista y casillas, que es lo que se espera al enumerar cosas; lo demás
     * vuelve a párrafo, porque nadie quiere dos títulos seguidos.
     */
    fun bloqueNuevo(texto: String, n: Int): Pair<String, Sitio> {
        val trozos = trozosDe(texto)
        val trozo = trozos.getOrNull(n) ?: return texto to Sitio(n)
        val tipo = Menus.tipoDe(trozo.de(texto).trimEnd('\n'))

        val sigue = when (tipo) {
            TipoDeBloque.LISTA, TipoDeBloque.NUMERADA, TipoDeBloque.TAREAS -> tipo
            else -> null
        }
        val vacio = if (sigue == null) "" else Bloques.envuelve(sigue, "")
        val nuevo = texto.substring(0, trozo.hasta).trimEnd('\n') + "\n\n" + vacio +
            "\n" + texto.substring(trozo.hasta).trimStart('\n')

        return nuevo to Sitio(n + 1, TextRange(0))
    }

    /**
     * Parte el bloque [n] por [pos]: lo de delante se queda, lo de detrás se va
     * a un bloque nuevo.
     *
     * Es lo que tiene que pasar al pulsar intro. En un editor por bloques el
     * intro **no mete un salto de línea**: abre un bloque. Meterlo dentro del
     * texto era lo que hacía que un título con dos renglones dejara el segundo
     * suelto como párrafo, porque la almohadilla solo envuelve al primero.
     *
     * La lista, la numerada y las casillas continúan siendo lo que eran —es lo
     * que se espera al enumerar—, y lo demás vuelve a párrafo, porque nadie
     * quiere dos títulos seguidos.
     */
    fun partir(texto: String, n: Int, contenido: InlineText, pos: Int): Pair<String, Sitio> {
        // **Intro en una viñeta vacía la quita.** Es como se sale de una lista
        // en cualquier editor, y aquí hacía más falta que en ninguno: era la
        // única forma segura de deshacerse de una viñeta, porque el retroceso
        // del teclado en pantalla no siempre llega cuando no hay nada que
        // borrar. Con esto siempre hay una salida que depende solo de nosotros.
        if (contenido.text.isEmpty()) {
            val tipo = tipo(texto, n)
            if (tipo == TipoDeBloque.LISTA || tipo == TipoDeBloque.NUMERADA ||
                tipo == TipoDeBloque.TAREAS
            ) {
                return quitarTipo(texto, n) to Sitio(n, TextRange(0))
            }
        }

        val corte = pos.coerceIn(0, contenido.text.length)
        val izquierda = InlineText(
            contenido.text.substring(0, corte),
            contenido.spans.mapNotNull { recortar(it, 0, corte) }
        )
        val derecha = InlineText(
            contenido.text.substring(corte),
            contenido.spans.mapNotNull { recortar(it, corte, contenido.text.length) }
                .map { it.copy(start = it.start - corte, end = it.end - corte) }
        )

        val conIzquierda = conContenido(texto, n, izquierda)
        val (conHueco, donde) = bloqueNuevo(conIzquierda, n)
        return conContenido(conHueco, donde.bloque, derecha) to
            Sitio(donde.bloque, TextRange(0))
    }

    private fun recortar(s: InlineSpan, desde: Int, hasta: Int): InlineSpan? {
        val a = s.start.coerceAtLeast(desde)
        val b = s.end.coerceAtMost(hasta)
        return if (b <= a) null else s.copy(start = a, end = b)
    }

    /**
     * Dónde seguir escribiendo al tocar el hueco de debajo del documento.
     *
     * Si el último bloque tiene algo, se abre uno nuevo detrás; si ya está
     * vacío, se va a él. Sin esto, tocar el espacio en blanco del final no hacía
     * nada, que es justo donde todo el mundo toca para seguir escribiendo.
     */
    fun alFinal(texto: String): Pair<String, Sitio> {
        val trozos = trozosDe(texto)
        if (trozos.isEmpty()) return texto to Sitio(0, TextRange(0))

        val ultimo = trozos.size - 1
        val contenido = contenidoDelTrozo(trozos[ultimo].de(texto))
        // Un bloque que no es de texto —una imagen, una raya— nunca está
        // «vacío»: detrás de él siempre se abre uno nuevo.
        if (contenido != null && contenido.text.isEmpty()) {
            return texto to Sitio(ultimo, TextRange(0))
        }
        return bloqueNuevo(texto, ultimo)
    }

    /**
     * Quita el bloque [n] entero.
     *
     * Hace falta porque hay bloques donde **no se puede poner el cursor**: una
     * imagen, una raya, un vídeo. Sin esto, insertar una imagen sin querer era
     * definitivo — no había ninguna tecla ni ningún botón que se la llevara.
     */
    fun borrarBloque(texto: String, n: Int): Pair<String, Sitio?> {
        val trozos = trozosDe(texto)
        val trozo = trozos.getOrNull(n) ?: return texto to null
        val sinEl = texto.substring(0, trozo.desde) + texto.substring(trozo.hasta)
        val quedan = trozosDe(sinEl)
        if (quedan.isEmpty()) return sinEl to null
        val donde = (n - 1).coerceIn(0, quedan.size - 1)
        val largo = contenidoDelTrozo(quedan[donde].de(sinEl))?.text?.length ?: 0
        return sinEl to Sitio(donde, TextRange(largo))
    }

    /**
     * Le quita el tipo al bloque [n] y lo deja en párrafo.
     *
     * Es el primer paso de borrar hacia atrás desde el principio de un bloque:
     * la casilla o la viñeta se van, el texto se queda. Un segundo retroceso ya
     * junta con el de arriba. Es lo que hace cualquier editor, y sin ello una
     * casilla puesta sin querer no había forma de quitarla.
     */
    fun quitarTipo(texto: String, n: Int): String {
        val trozos = trozosDe(texto)
        val trozo = trozos.getOrNull(n) ?: return texto
        val fuente = trozo.de(texto)
        val cola = fuente.takeLastWhile { it == '\n' }
        val nuevo = Menus.convertir(fuente.trimEnd('\n'), null) + cola
        return texto.substring(0, trozo.desde) + nuevo + texto.substring(trozo.hasta)
    }

    /**
     * Junta el bloque [n] con el de arriba. Es lo que pasa al borrar hacia atrás
     * estando al principio de un bloque.
     */
    fun juntarConElDeArriba(texto: String, n: Int): Pair<String, Sitio>? {
        if (n <= 0) return null
        val trozos = trozosDe(texto)
        val arriba = trozos.getOrNull(n - 1) ?: return null
        val abajo = trozos.getOrNull(n) ?: return null

        val textoArriba = contenidoDelTrozo(arriba.de(texto))?.text ?: return null
        val contenidoAbajo = contenidoDelTrozo(abajo.de(texto)) ?: return null

        // El de abajo se pega al de arriba conservando sus estilos, desplazados
        // por lo que ya había: si no, la negrita del de abajo saldría al
        // principio del de arriba.
        val juntos = InlineText(
            textoArriba + contenidoAbajo.text,
            (contenidoDelTrozo(arriba.de(texto))?.spans.orEmpty()) +
                contenidoAbajo.spans.map {
                    it.copy(start = it.start + textoArriba.length, end = it.end + textoArriba.length)
                }
        )

        val sinElDeAbajo = texto.substring(0, abajo.desde) + texto.substring(abajo.hasta)
        // Los saltos que quedan colgando al final no son un bloque: son el hueco
        // que dejó el que se acaba de ir. Sin recortarlos, borrar el último
        // renglón dejaba uno vacío detrás que había que volver a borrar.
        val resultado = conContenido(sinElDeAbajo, n - 1, juntos).trimEnd('\n')
        return resultado to Sitio(n - 1, TextRange(textoArriba.length))
    }
}
