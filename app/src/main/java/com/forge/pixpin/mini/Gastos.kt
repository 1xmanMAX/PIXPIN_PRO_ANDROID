package com.forge.pixpin.mini

import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/**
 * La mini-app de **gastos**: conceptos con importe, y el total.
 *
 * ## El dinero se guarda en enteros, nunca en `Double`
 *
 * Un `Double` **no puede** representar 0,10: lo más cerca que llega es
 * 0,1000000000000000055511151231257827…, y sumar diez de esos no da 1 sino
 * 0,9999999999999999. En una lista de gastos eso significa que el total sale a un céntimo
 * de lo que suma la columna, y el usuario ve una cuenta que no cuadra sin poder saber por
 * qué — el error no está en ninguna línea, está en la suma. Es el fallo clásico del dinero
 * en coma flotante y no tiene arreglo a base de redondear al final: redondear tapa unos
 * casos y deja otros.
 *
 * Aquí cada importe es un [Long] de **unidades menores**: céntimos en euros, pero también
 * yenes enteros o milésimas de dinar. Cuántas tiene cada moneda lo dice ella misma
 * ([Currency.getDefaultFractionDigits]) y no una constante escrita a mano, porque el yen no
 * tiene céntimos y el dinar tunecino tiene tres decimales: un `/100` fijo convertiría 500
 * yenes en 5. Con enteros, la suma es exacta por construcción y no hay redondeo que
 * discutir. El único redondeo del sistema ocurre **al escribir un importe a mano**, una
 * vez, sobre el número que el usuario acaba de teclear, y ahí sí se ve lo que pasa.
 *
 * ## La moneda sale de la configuración regional, y se guarda con los datos
 *
 * De la regional al **crear** ([monedaDe]); guardada **en el documento** a partir de ahí,
 * en la cabecera de la tabla: `| Concepto | Importe (EUR) |`. Las dos cosas importan. Si
 * solo se leyera la regional, unos gastos apuntados en un viaje cambiarían de moneda al
 * volver a casa: los mismos números pasarían de libras a euros sin que nadie los tocara, y
 * eso no es un fallo de presentación, es una cuenta falsa. Guardándola dentro, un
 * documento vale lo que decía el día que se escribió.
 *
 * Va en la propia cabecera y no en un campo escondido porque así se lee a simple vista y
 * se puede cambiar a mano, que es la promesa de guardar esto en Markdown.
 *
 * ## Es una tabla de Markdown, no un formato nuevo
 *
 * ```
 * # Viaje a Lisboa
 *
 * | Concepto | Importe (EUR) |
 * | --- | ---: |
 * | Cena | 42.50 |
 * | Tren | 18.00 |
 *
 * **Total: 60,50 €**
 * ```
 *
 * `Tablas.kt` ya sabe leer y pintar esto. Ver el apartado de formato en `MiniApps.kt`.
 *
 * ## Se escribe estricto y se lee tolerante
 *
 * Al **escribir**, el importe va siempre en forma canónica: punto decimal, sin separador de
 * miles, con tantos decimales como tenga la moneda (`42.50`). Es la única forma que
 * significa lo mismo en toda máquina: escribiendo «42,50» y leyéndolo con la regional
 * inglesa saldrían 4.250 €, o sea que cambiar de idioma multiplicaría los gastos por cien.
 *
 * Al **leer** se acepta lo que escribiría una persona: coma o punto, separadores de miles,
 * el símbolo de la moneda al lado, espacios. Quien edite el archivo a mano no tiene por qué
 * conocer la forma canónica.
 *
 * ## El total no se guarda
 *
 * La línea `**Total:**` del final se **vuelve a calcular** en cada escritura y al leer se
 * ignora. Un total guardado es un dato que puede contradecir a sus propias filas —basta
 * editar una línea con cualquier otro programa— y entonces hay dos verdades y ninguna
 * forma de saber cuál manda. Está ahí para que el documento se entienda al abrirlo por
 * fuera, no como fuente de nada.
 */

/** Una línea de la cuenta. [centimos] es en unidades menores de la moneda del [Libro]. */
data class Gasto(val concepto: String, val centimos: Long)

/**
 * Una cuenta entera: su nombre, en qué moneda está y sus líneas.
 *
 * La moneda vive en el libro y no en cada gasto porque una cuenta con líneas en monedas
 * distintas **no se puede sumar**, y un total que suma libras con euros es exactamente el
 * número que no hay que enseñar. Una sola moneda por cuenta hace que el total siempre
 * signifique algo.
 */
data class Libro(
    val titulo: String,
    val moneda: Currency,
    val gastos: List<Gasto>
) {
    /** Cuántos decimales enseña esta moneda. El yen: cero. */
    val decimales: Int get() = moneda.defaultFractionDigits.coerceIn(0, 6)

    /** La suma, exacta. Ver [Gastos.total]. */
    val total: Long get() = Gastos.total(gastos)
}

object Gastos {

    // ---- La moneda -------------------------------------------------------

    /**
     * La moneda de esa configuración regional.
     *
     * Se prueban tres cosas en orden porque [Currency.getInstance] **lanza** cuando la
     * regional no tiene país —`es` a secas, sin `ES`, que es una regional perfectamente
     * normal en un móvil— y una mini-app que revienta al crearse no la crea nadie dos
     * veces. El último recurso es el euro: no es una elección, es lo que queda cuando el
     * sistema no sabe decir nada, y en cuanto el documento se guarda deja de importar
     * porque la moneda ya viaja dentro.
     */
    fun monedaDe(locale: Locale = Locale.getDefault()): Currency =
        runCatching { Currency.getInstance(locale) }.getOrNull()
            ?: runCatching { Currency.getInstance(Locale.getDefault()) }.getOrNull()
            ?: Currency.getInstance("EUR")

    private fun monedaDeCodigo(codigo: String?): Currency? =
        codigo?.let { runCatching { Currency.getInstance(it.uppercase()) }.getOrNull() }

    // ---- Leer y escribir -------------------------------------------------

    /** `| Concepto | Importe (EUR) |` → `EUR`. */
    private val CODIGO = Regex("""\(\s*([A-Za-z]{3})\s*\)""")

    /** Una fila de guiones: la que separa la cabecera del cuerpo en una tabla de Markdown. */
    private val GUIONES = Regex("""^:?-{3,}:?$""")

    /**
     * El libro que hay escrito en [documento].
     *
     * La **primera** fila de la tabla es siempre la cabecera y no se lee como gasto: es de
     * donde sale la moneda. Las filas de guiones se saltan. De las demás, la primera celda
     * es el concepto y la última el importe — la última y no la segunda, para que una fila
     * con una columna de más pegada por alguien siga dando su importe.
     *
     * Un importe que no se entienda vale **cero**, y la fila se queda. Tirar la fila
     * borraría el concepto que el usuario sí escribió; dejarla a cero enseña el problema
     * donde se puede arreglar, que es en la propia lista.
     *
     * [locale] solo se usa si el documento no dice su moneda —una tabla pegada de fuera,
     * sin el `(EUR)` en la cabecera—.
     */
    fun leer(documento: String, locale: Locale = Locale.getDefault()): Libro {
        val filas = documento.split('\n').map { it.trim() }.filter { it.startsWith("|") }
        val cabecera = filas.firstOrNull()
        val moneda = monedaDeCodigo(cabecera?.let { CODIGO.find(it)?.groupValues?.get(1) })
            ?: monedaDe(locale)
        val decimales = moneda.defaultFractionDigits.coerceIn(0, 6)

        val gastos = filas.drop(1).mapNotNull { fila ->
            val celdas = celdas(fila)
            if (celdas.size < 2) return@mapNotNull null
            if (celdas.all { GUIONES.matches(it) }) return@mapNotNull null
            Gasto(
                concepto = enUnaLinea(celdas.first()),
                centimos = centimosDe(celdas.last(), decimales) ?: 0L
            )
        }
        return Libro(Cabecera.titulo(documento), moneda, gastos)
    }

    /**
     * El documento entero.
     *
     * [locale] es solo para la línea del total, que es la única parte que se escribe **para
     * leerla**: los importes de la tabla van en forma canónica pase lo que pase.
     */
    fun escribir(libro: Libro, locale: Locale = Locale.getDefault()): String {
        val sb = StringBuilder()
        sb.append(Cabecera.linea(libro.titulo))
        // Los rótulos de la cabecera son parte del documento, no de la interfaz: se leen
        // dentro del archivo. Cambiarlos algún día no rompe nada, porque al leer la
        // cabecera solo se mira buscando el código de la moneda.
        sb.append("| Concepto | Importe (").append(libro.moneda.currencyCode).append(") |\n")
        // La columna de importes a la derecha: los números se comparan por su última cifra,
        // y alineados a la izquierda hay que leer cada uno entero para ver cuál es mayor.
        sb.append("| --- | ---: |\n")
        for (g in libro.gastos) {
            sb.append("| ").append(celdaSegura(g.concepto))
                .append(" | ").append(canonico(g.centimos, libro.decimales)).append(" |\n")
        }
        sb.append("\n**Total: ")
            .append(textoDeImporte(libro.total, libro.moneda, locale)).append("**")
        return sb.toString()
    }

    // ---- Operaciones -----------------------------------------------------

    /**
     * Añade una línea.
     *
     * Un concepto vacío **sí entra** si trae importe, al revés que en las tareas: un gasto
     * de doce euros que uno no sabe cómo llamar sigue siendo doce euros que hay que sumar,
     * y rechazarlo descuadraría el total, que es lo único que esta mini-app promete. Lo que
     * no entra es una línea sin concepto **y** sin importe, que no es nada.
     */
    fun anadir(libro: Libro, concepto: String, centimos: Long): Libro {
        val limpio = enUnaLinea(concepto)
        if (limpio.isEmpty() && centimos == 0L) return libro
        return libro.copy(gastos = libro.gastos + Gasto(limpio, acotado(centimos)))
    }

    fun borrar(libro: Libro, indice: Int): Libro {
        if (indice !in libro.gastos.indices) return libro
        return libro.copy(gastos = libro.gastos.filterIndexed { i, _ -> i != indice })
    }

    fun cambiar(libro: Libro, indice: Int, concepto: String, centimos: Long): Libro {
        if (indice !in libro.gastos.indices) return libro
        val nuevos = libro.gastos.toMutableList()
        nuevos[indice] = Gasto(enUnaLinea(concepto), acotado(centimos))
        return libro.copy(gastos = nuevos)
    }

    /** Ver [Tareas.mover]: mismo criterio, [hasta] se recorta en vez de rechazarse. */
    fun mover(libro: Libro, desde: Int, hasta: Int): Libro {
        if (desde !in libro.gastos.indices) return libro
        val destino = hasta.coerceIn(0, libro.gastos.size - 1)
        if (destino == desde) return libro
        val nuevos = libro.gastos.toMutableList()
        nuevos.add(destino, nuevos.removeAt(desde))
        return libro.copy(gastos = nuevos)
    }

    /**
     * La suma.
     *
     * Se acumula acotando en cada paso en vez de sumar y ya. Los importes de uno en uno ya
     * están acotados ([acotado]), pero un documento pegado con cien mil filas del máximo
     * desbordaría el [Long] y el total saldría **negativo**: una cuenta de gastos que dice
     * que te deben dinero. Acotando, el total se queda en un número absurdo pero del signo
     * correcto, que al menos se ve que es absurdo.
     */
    fun total(gastos: List<Gasto>): Long =
        gastos.fold(0L) { acc, g -> (acc + g.centimos).coerceIn(-TOPE_TOTAL, TOPE_TOTAL) }

    // ---- El resumen de la burbuja ---------------------------------------

    /**
     * El total, que es la razón de existir de la mini-app.
     *
     * En la burbuja va el total y no «cinco conceptos»: lo que uno quiere saber de un
     * control de gastos sin abrirlo es cuánto lleva gastado. El número de líneas va en
     * [ResumenMini.de] por si la pantalla quiere enseñarlo también.
     */
    fun resumen(documento: String, locale: Locale = Locale.getDefault()): ResumenMini {
        val libro = leer(documento, locale)
        return ResumenMini(
            texto = textoDeImporte(libro.total, libro.moneda, locale),
            de = libro.gastos.size,
            vacia = libro.gastos.isEmpty()
        )
    }

    // ---- Importes: leer, escribir y pintar -------------------------------

    /**
     * El importe tal cual se guarda: `42.50`, `-5.00`, `1200` (yenes).
     *
     * [BigDecimal.valueOf] con escala construye el número **desde el entero**, sin pasar
     * por coma flotante en ningún momento: no hay ningún paso donde se pueda perder un
     * céntimo.
     */
    fun canonico(centimos: Long, decimales: Int): String =
        BigDecimal.valueOf(centimos, decimales.coerceIn(0, 6)).toPlainString()

    /**
     * El importe como se enseña: `42,50 €`, `¥1,200`.
     *
     * Los decimales se fijan a los de la moneda en vez de dejar los que traiga la regional,
     * porque son cosas distintas: la regional dice **cómo** se escribe un número —la coma,
     * el punto, dónde va el símbolo— y la moneda dice **cuántas** cifras tiene. Sin fijarlo,
     * una cuenta en yenes con la regional española saldría con dos decimales inventados.
     */
    fun textoDeImporte(
        centimos: Long,
        moneda: Currency,
        locale: Locale = Locale.getDefault()
    ): String {
        val decimales = moneda.defaultFractionDigits.coerceIn(0, 6)
        val formato = NumberFormat.getCurrencyInstance(locale).apply {
            runCatching { currency = moneda }
            maximumFractionDigits = decimales
            minimumFractionDigits = decimales
        }
        return formato.format(BigDecimal.valueOf(centimos, decimales))
    }

    /**
     * Lo que ha tecleado una persona, en unidades menores. Null si ahí no hay un número.
     *
     * ## Cómo se decide qué separador es el decimal
     *
     * Se mira **el último** punto o coma del texto: si detrás lleva entre una y [decimales]
     * cifras, es el separador decimal; si lleva más, es un separador de miles y el número
     * es entero. Con esa regla salen bien las cuatro formas que escribe la gente —`1234.5`,
     * `1.234,56`, `1,234.56`, `1234`— sin preguntarle a nadie en qué idioma está pensando.
     *
     * No es adivinación perfecta y no puede serlo: `1.005` es mil cinco para media Europa y
     * un euro con medio céntimo para la otra media. Se lee como mil cinco, que es lo que
     * dice la regla, y no importa demasiado porque **esto solo se usa con lo que teclea el
     * usuario y con lo editado a mano**: lo que escribe la aplicación es siempre canónico y
     * cae en el caso fácil.
     *
     * Las cifras de más se **truncan**, no se redondean: el usuario todavía está
     * escribiendo, y ver cómo el último dígito tecleado le cambia el anterior es
     * desconcertante. Redondear es cosa de quien convierte una vez, no de quien teclea.
     */
    fun centimosDe(texto: String, decimales: Int): Long? {
        val d = decimales.coerceIn(0, 6)
        // El menos de teclado y el menos tipográfico: el segundo llega copiando de una hoja
        // de cálculo o de una web, y sin tratarlo un reembolso se guardaría como un gasto.
        val limpio = texto.replace('−', '-').trim()
        val digitos = limpio.filter { it.isDigit() }
        if (digitos.isEmpty()) return null

        val negativo = limpio.substringBefore(limpio.first { it.isDigit() }).contains('-')

        val corte = limpio.indexOfLast { it == '.' || it == ',' }
        val esDecimal = corte >= 0 && limpio.substring(corte + 1).count { it.isDigit() } in 1..d

        val enteras = if (esDecimal) limpio.take(corte).filter { it.isDigit() } else digitos
        val fraccion = if (esDecimal) limpio.drop(corte + 1).filter { it.isDigit() } else ""

        val junto = enteras + fraccion.padEnd(d, '0').take(d)
        // `toLongOrNull` devuelve null si no cabe: doscientas cifras pegadas no son un
        // importe, son un accidente, y más vale no aceptarlo que aceptar su resto.
        val valor = junto.trimStart('0').ifEmpty { "0" }.toLongOrNull() ?: return null
        return acotado(if (negativo) -valor else valor)
    }

    /**
     * El importe recortado a algo que sea un importe.
     *
     * No es paranoia: el campo de texto acepta lo que sea y pegar una tira de cifras es un
     * segundo de trabajo. El tope son mil billones de unidades menores —diez billones de
     * euros—, muy por encima de cualquier cuenta real y muy por debajo de donde un [Long]
     * empieza a dar problemas al sumar. Lo que directamente no cabe en un [Long] ni llega
     * hasta aquí: [centimosDe] lo rechaza antes, porque doscientas cifras seguidas no son
     * un importe grande, son otra cosa.
     */
    fun acotado(centimos: Long): Long = centimos.coerceIn(-TOPE_IMPORTE, TOPE_IMPORTE)

    private const val TOPE_IMPORTE = 1_000_000_000_000_000L
    private const val TOPE_TOTAL = 100_000_000_000_000_000L

    // ---- Celdas ----------------------------------------------------------

    /**
     * Un concepto listo para meter en una celda.
     *
     * La barra vertical **parte la fila**: un gasto llamado «pan | leche» se guardaría como
     * tres columnas y al releerlo el importe sería «leche». Se escapa como `\|`, que es lo
     * que ya desescapa `Tablas.celdasDeFila` al leer: un documento de gastos se sigue
     * abriendo con el motor de notas y las barras se ven donde tienen que verse.
     *
     * La barra invertida también se escapa, y esta es la única parte donde se va un paso
     * más allá que `Tablas`. Sin hacerlo, un concepto que ya acabara en `\` se comería la
     * barra siguiente al releerlo y la fila se partiría igual: el escape solo funciona si
     * el carácter de escape también se escapa.
     */
    fun celdaSegura(texto: String): String =
        enUnaLinea(texto).replace("""\""", """\\""").replace("|", """\|""")

    /** Las celdas de una fila, deshaciendo el escape de [celdaSegura]. */
    private fun celdas(fila: String): List<String> {
        val cuerpo = fila.trim().removePrefix("|").removeSuffix("|")
        val salida = mutableListOf<String>()
        val actual = StringBuilder()
        var i = 0
        while (i < cuerpo.length) {
            val c = cuerpo[i]
            when {
                c == '\\' && i + 1 < cuerpo.length -> { actual.append(cuerpo[i + 1]); i += 2 }
                c == '|' -> { salida += actual.toString().trim(); actual.clear(); i++ }
                else -> { actual.append(c); i++ }
            }
        }
        salida += actual.toString().trim()
        return salida
    }
}
