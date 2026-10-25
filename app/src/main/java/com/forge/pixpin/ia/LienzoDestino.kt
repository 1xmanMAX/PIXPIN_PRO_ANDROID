package com.forge.pixpin.ia

import com.forge.pixpin.motor.Element
import com.forge.pixpin.motor.ElementType
import com.forge.pixpin.motor.TextoRico
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * **Lo que el asistente ve del lienzo donde trabaja** (`edicion::resumen`, `describir` y
 * `textos::{caja_del_rotulo, rotulo_de, caja_que_lo_rodea}` del PC, portados el 25-oct-2026):
 * el resumen compacto que va pegado al pedido («[Lienzo destino: …]»), los `#id` con los que el
 * modelo nombra lo que hay, y las medidas de los textos que comparten [EditarTexto] y [Lint].
 *
 * **Los `#id` del teléfono son posiciones**: el PC numera sus elementos con un `u64` propio del
 * motor; aquí los elementos llevan el id de Excalidraw (`"ia-18f3-4"`), que es largo y el modelo
 * lo copia mal. El `#n` es el puesto del elemento en la lista de la escena (desde 1, contando los
 * borrados, que siguen en la lista): lo nuevo va al final y no mueve a nadie, así que un `#id` vale
 * entre una llamada y la siguiente del mismo turno. Si el usuario reordena capas a mano a media
 * conversación, cambian: el resumen del turno siguiente trae los de ahora.
 *
 * Sin Android: todo es sobre la lista de [Element], probado en la JVM.
 */
object LienzoDestino {
    /** Cuántos elementos se describen uno a uno (los de encima, que son los que más se nombran). */
    const val MAX_RESUMEN = 30
    /** Cuántas letras de cada texto lleva el resumen (las demás, con `editar_texto` accion listar). */
    const val MAX_LETRAS_RESUMEN = 200
    /** Letras como mucho de los textos de los elementos que no caben en las [MAX_RESUMEN] líneas. */
    private const val PRESUPUESTO_TEXTOS_VIEJOS = 1500

    /** El interlineado de Excalidraw (el de [com.forge.pixpin.motor.DrawFonts.medirTexto]). */
    const val INTERLINEADO = 1.25

    // ------------------------------------------------------------------------------------- #id

    /** El `#n` de cada elemento (por su id de Excalidraw). */
    fun numeros(elementos: List<Element>): Map<String, Int> {
        val m = HashMap<String, Int>(elementos.size * 2)
        elementos.forEachIndexed { i, e -> m.putIfAbsent(e.id, i + 1) }
        return m
    }

    /** El elemento vivo de un `#n`, o null. */
    fun porNumero(elementos: List<Element>, n: Int): Element? = elementos.getOrNull(n - 1)?.takeIf { !it.isDeleted }

    fun vivos(elementos: List<Element>): List<Element> = elementos.filter { !it.isDeleted }

    // ------------------------------------------------------------------------------- geometría

    /** Una caja `(x0, y0, x1, y1)`. */
    data class Caja(val x0: Double, val y0: Double, val x1: Double, val y1: Double) {
        val ancho get() = x1 - x0
        val alto get() = y1 - y0
        val area get() = max(ancho, 0.0) * max(alto, 0.0)
        fun cruce(o: Caja) = Caja(max(x0, o.x0), max(y0, o.y0), min(x1, o.x1), min(y1, o.y1))
        fun dentroDe(o: Caja, tol: Double) = x0 >= o.x0 - tol && y0 >= o.y0 - tol && x1 <= o.x1 + tol && y1 <= o.y1 + tol
    }

    /** La caja de un elemento: por sus puntos si los tiene (líneas, flechas, trazos), si no la suya. */
    fun caja(e: Element): Caja {
        val p = e.points
        if (!p.isNullOrEmpty() && e.type in setOf(ElementType.LINE, ElementType.ARROW, ElementType.FREEDRAW, ElementType.REGION, ElementType.MEASURE)) {
            var a = Double.MAX_VALUE; var b = Double.MAX_VALUE; var c = -Double.MAX_VALUE; var d = -Double.MAX_VALUE
            for (q in p) { a = min(a, e.x + q.x); b = min(b, e.y + q.y); c = max(c, e.x + q.x); d = max(d, e.y + q.y) }
            return Caja(a, b, c, d)
        }
        val x0 = min(e.x, e.x + e.width); val y0 = min(e.y, e.y + e.height)
        return Caja(x0, y0, x0 + abs(e.width), y0 + abs(e.height))
    }

    /** Los extremos de una línea o flecha, en coordenadas de escena. */
    fun extremos(e: Element): Pair<Pair<Double, Double>, Pair<Double, Double>>? {
        val p = e.points?.takeIf { it.size >= 2 } ?: return null
        return (e.x + p.first().x to e.y + p.first().y) to (e.x + p.last().x to e.y + p.last().y)
    }

    fun esTexto(e: Element) = e.type == ElementType.TEXT
    fun esCaja(e: Element) = e.type == ElementType.RECTANGLE || e.type == ElementType.ELLIPSE || e.type == ElementType.DIAMOND
    fun esMarco(e: Element) = e.type == ElementType.FRAME

    /** Lo que dice un texto, plano (el `text`; la fuente rica no cambia lo que se mide aquí). */
    fun textoDe(e: Element): String = e.text.orEmpty()

    // ---------------------------------------------------------------------------- medir textos

    /**
     * **Lo ancha que es una letra**, la misma cuenta que usa el asistente para dibujar
     * ([Lienzo.Fabrica.texto]: 0,6 de la letra por carácter), con las de ancho completo (chino,
     * japonés, coreano) a 1. Sin la fuente real: el lienzo vuelve a medir al pintar; esto basta
     * para saber si un texto cabe o se sale de su caja, que es lo que se pregunta.
     */
    fun anchoDeLetra(cp: Int): Double = when (cp) {
        in 0x1100..0x115F, in 0x2E80..0xA4CF, in 0xAC00..0xD7A3, in 0xF900..0xFAFF, in 0xFE30..0xFE4F, in 0xFF00..0xFF60, in 0xFFE0..0xFFE6 -> 1.0
        else -> 0.6
    }

    fun anchoDeRenglon(s: String, tam: Double): Double {
        var w = 0.0
        var i = 0
        while (i < s.length) { val cp = s.codePointAt(i); w += anchoDeLetra(cp) * tam; i += Character.charCount(cp) }
        return w
    }

    /** Lo que mide un texto (renglones por `\n`): ancho del más largo, alto con el interlineado. */
    fun medir(texto: String, tam: Double): Pair<Double, Double> {
        val r = texto.split('\n')
        return (r.maxOfOrNull { anchoDeRenglon(it.trimEnd(), tam) } ?: 0.0) to max(1, r.size) * tam * INTERLINEADO
    }

    /** **Parte un texto en renglones** que no pasen de [ancho] (por palabras; una palabra sola larga se queda entera). */
    fun repartir(texto: String, ancho: Double, tam: Double): List<String> {
        val salida = ArrayList<String>()
        for (parrafo in texto.split('\n')) {
            var linea = StringBuilder()
            for (palabra in parrafo.split(' ')) {
                val prueba = if (linea.isEmpty()) palabra else "$linea $palabra"
                if (linea.isNotEmpty() && anchoDeRenglon(prueba, tam) > ancho) { salida += linea.toString(); linea = StringBuilder(palabra) }
                else { linea.setLength(0); linea.append(prueba) }
            }
            salida += linea.toString()
        }
        return salida
    }

    /** El hueco de dentro de una caja para su rótulo: un óvalo o un rombo dejan menos que un rectángulo. */
    const val RELLENO_DEL_TEXTO = 5.0
    fun anchoQueCabe(c: Element): Double = when (c.type) {
        ElementType.ELLIPSE -> abs(c.width) / Math.sqrt(2.0) - 2 * RELLENO_DEL_TEXTO
        ElementType.DIAMOND -> abs(c.width) / 2 - 2 * RELLENO_DEL_TEXTO
        else -> abs(c.width) - 2 * RELLENO_DEL_TEXTO
    }.coerceAtLeast(1.0)
    fun altoQueCabe(c: Element): Double = when (c.type) {
        ElementType.ELLIPSE -> abs(c.height) / Math.sqrt(2.0) - 2 * RELLENO_DEL_TEXTO
        ElementType.DIAMOND -> abs(c.height) / 2 - 2 * RELLENO_DEL_TEXTO
        else -> abs(c.height) - 2 * RELLENO_DEL_TEXTO
    }.coerceAtLeast(1.0)

    // ----------------------------------------------------------------- de quién es cada texto

    /** **La caja de la que es rótulo atado** este texto (`containerId`), si lo es. */
    fun cajaDelRotulo(elementos: List<Element>, t: Element): Element? {
        val c = t.containerId?.takeIf { esTexto(t) } ?: return null
        return elementos.firstOrNull { it.id == c && !it.isDeleted }
    }

    /** **El rótulo atado de una caja**, si lo lleva. */
    fun rotuloDe(elementos: List<Element>, c: Element): Element? {
        if (!esCaja(c)) return null
        val atado = c.boundElements.orEmpty().firstOrNull { it.type == ElementType.TEXT }?.id
        return elementos.firstOrNull { !it.isDeleted && esTexto(it) && (it.id == atado || it.containerId == c.id) }
    }

    /**
     * **La caja que rodea a un texto suelto** (no atado): la figura cerrada más pequeña que tiene
     * dentro el centro del texto y es de su talla. Es como `dibujar_en_lienzo` pone los rótulos:
     * un texto centrado encima de un rectángulo, sin atar.
     */
    fun cajaQueLoRodea(elementos: List<Element>, t: Element): Element? {
        val k = caja(t)
        val cx = (k.x0 + k.x1) / 2; val cy = (k.y0 + k.y1) / 2
        val w = k.ancho; val h = k.alto
        return elementos.asSequence()
            .filter { !it.isDeleted && it.id != t.id && esCaja(it) && !it.reference }
            .filter { c ->
                val b = caja(c)
                cx >= b.x0 && cx <= b.x1 && cy >= b.y0 && cy <= b.y1 && b.alto >= h * 0.9 && b.ancho >= w * 0.3 && b.ancho * b.alto >= 0.5 * w * h
            }
            .minByOrNull { abs(it.width * it.height) }
    }

    /** **El texto que se cambia al nombrar [e]**: el mismo; su rótulo atado; o el único texto suelto encima. */
    fun textoPara(elementos: List<Element>, e: Element): Element? {
        if (e.isDeleted) return null
        if (esTexto(e)) return e
        rotuloDe(elementos, e)?.let { return it }
        if (!esCaja(e)) return null
        val encima = elementos.filter { !it.isDeleted && esTexto(it) && it.containerId == null && cajaQueLoRodea(elementos, it)?.id == e.id }
        return encima.singleOrNull()
    }

    // --------------------------------------------------------------------------------- resumen

    fun nombreDe(e: Element): String = when (e.type) {
        ElementType.FREEDRAW -> if (e.dureza != null) "trazo de grafito" else "trazo a lapiz"
        ElementType.LINE -> if ((e.points?.size ?: 0) > 3) "polilinea" else "recta"
        ElementType.ARROW -> "flecha"
        ElementType.RECTANGLE -> "rectangulo"
        ElementType.DIAMOND -> "rombo"
        ElementType.ELLIPSE -> if (abs(e.width - e.height) <= 0.02 * max(abs(e.width), abs(e.height))) "circulo" else "elipse"
        ElementType.ARC -> "arco"
        ElementType.TEXT -> "texto"
        ElementType.IMAGE -> "imagen"
        ElementType.MEASURE -> "cota"
        ElementType.PUNTO -> "punto"
        ElementType.REGION -> "relleno"
        ElementType.PLANO -> "plano cartesiano"
        ElementType.SERIAL -> "numero"
        ElementType.FRAME -> "marco"
        ElementType.CRONOGRAMA -> "cronograma"
        else -> "figura"
    }

    /** El nombre del color de la paleta más parecido (`nombre_de_color` del PC). */
    fun nombreDeColor(hex: String): String {
        val h = hex.trim().removePrefix("#")
        val rgb = when (h.length) {
            3 -> h.map { "$it$it" }.joinToString("")
            6, 8 -> h.take(6)
            else -> return "negro"
        }
        val n = rgb.toLongOrNull(16) ?: return "negro"
        val r = ((n shr 16) and 0xff) / 255.0; val g = ((n shr 8) and 0xff) / 255.0; val b = (n and 0xff) / 255.0
        if (r > 0.85 && g > 0.7 && b < 0.45) return "amarillo"
        val paleta = listOf(
            "negro" to Triple(0.12, 0.12, 0.12), "blanco" to Triple(1.0, 1.0, 1.0), "rojo" to Triple(0.88, 0.19, 0.19),
            "verde" to Triple(0.18, 0.62, 0.27), "azul" to Triple(0.10, 0.44, 0.76), "amarillo" to Triple(0.94, 0.55, 0.0),
            "naranja" to Triple(0.91, 0.35, 0.05), "morado" to Triple(0.40, 0.25, 0.85), "rosa" to Triple(0.76, 0.15, 0.36),
            "gris" to Triple(0.53, 0.56, 0.59), "marron" to Triple(0.55, 0.35, 0.17), "celeste" to Triple(0.05, 0.52, 0.60)
        )
        return paleta.minByOrNull { (_, c) -> (c.first - r) * (c.first - r) + (c.second - g) * (c.second - g) + (c.third - b) * (c.third - b) }!!.first
    }

    private fun r(v: Double) = v.roundToLong()

    /** **El marco de las coordenadas** que ve el modelo: la esquina de lo dibujado (0,0 si no hay nada). */
    fun marco(elementos: List<Element>): Pair<Double, Double> {
        val v = vivos(elementos)
        if (v.isEmpty()) return 0.0 to 0.0
        return v.minOf { caja(it).x0 } to v.minOf { caja(it).y0 }
    }

    /** Una línea del resumen: `#12 recta roja (100,40)->(300,40)`, `#3 texto negro [0,0,84,25] "Inicio"`. */
    fun describir(elementos: List<Element>, numeros: Map<String, Int>, e: Element, marco: Pair<Double, Double>): String {
        val (mx, my) = marco
        val s = StringBuilder("#${numeros[e.id]} ${nombreDe(e)} ${nombreDeColor(e.strokeColor)}")
        if (e.backgroundColor != Element.TRANSPARENT && e.backgroundColor.isNotBlank() && e.opacity > 5) s.append(" relleno ${nombreDeColor(e.backgroundColor)}")
        val ext = if (e.type == ElementType.LINE || e.type == ElementType.ARROW || e.type == ElementType.MEASURE) extremos(e) else null
        if (ext != null) {
            val (a, b) = ext
            s.append(" (${r(a.first - mx)},${r(a.second - my)})->(${r(b.first - mx)},${r(b.second - my)})")
        } else {
            val k = caja(e)
            s.append(" [${r(k.x0 - mx)},${r(k.y0 - my)},${r(k.ancho)},${r(k.alto)}]")
        }
        if (e.type == ElementType.ELLIPSE) { val k = caja(e); s.append(" centro (${r((k.x0 + k.x1) / 2 - mx)},${r((k.y0 + k.y1) / 2 - my)})") }
        if (esTexto(e)) {
            val t = textoDe(e)
            val n = t.codePointCount(0, t.length)
            val corto = if (n > MAX_LETRAS_RESUMEN) t.substring(0, t.offsetByCodePoints(0, MAX_LETRAS_RESUMEN)) else t
            s.append(" \"${corto.replace("\n", "\\n")}\"")
            if (n > MAX_LETRAS_RESUMEN) s.append(" (+${n - MAX_LETRAS_RESUMEN} letras: editar_texto accion listar)")
        }
        if (e.groupIds.isNotEmpty()) s.append(" (en grupo)")
        return s.toString()
    }

    /** La línea con lo que solo sabe la escena: de qué caja es rótulo un texto, y qué rótulo lleva una caja. */
    fun describirEn(elementos: List<Element>, numeros: Map<String, Int>, e: Element, marco: Pair<Double, Double>): String {
        val s = describir(elementos, numeros, e, marco)
        cajaDelRotulo(elementos, e)?.let { return "$s (rotulo de #${numeros[it.id]})" }
        rotuloDe(elementos, e)?.let { return "$s (rotulo #${numeros[it.id]})" }
        return s
    }

    /** **La lista corta de unos elementos**, una línea por elemento. */
    fun listar(elementos: List<Element>, cuales: List<Element>): String {
        val n = numeros(elementos)
        val m = marco(elementos)
        return cuales.filter { !it.isDeleted }.joinToString("\n") { describirEn(elementos, n, it, m) }
    }

    /**
     * **El resumen compacto del lienzo destino**, para el modelo: cuántos hay, las plantillas de
     * serie puestas ([plantillas]: sus nombres) y una línea por elemento (los de encima, hasta
     * [MAX_RESUMEN]); de los de debajo, solo sus textos con su `#id`.
     */
    fun resumen(elementos: List<Element>, plantillas: List<String> = emptyList()): String {
        val vivos = vivos(elementos).filter { !it.reference }
        if (vivos.isEmpty()) return "Lienzo destino: vacio."
        val numeros = numeros(elementos)
        val marco = marco(elementos)
        val s = StringBuilder("Lienzo destino: ${vivos.size} elementos (coordenadas como en dibujar_en_lienzo; usa sus #id para editarlos).")
        if (plantillas.isNotEmpty()) {
            s.append("\n${Guardas.MARCA_PLANTILLAS}: ${plantillas.joinToString(", ") { "«$it»" }}. ")
                .append("Es una pagina con bloques con nombre: cambiar sus textos es editar_texto; lo nuevo para ella va dentro, alineado y con su estilo. ")
                .append("Si el pedido la nombra y no queda claro si es cambiar esta o hacer otra, pregunta.")
        }
        val desde = maxOf(vivos.size - MAX_RESUMEN, 0)
        for (e in vivos.subList(desde, vivos.size)) s.append('\n').append(describirEn(elementos, numeros, e, marco))
        if (desde > 0) {
            s.append("\n(+$desde mas antiguos sin listar)")
            val viejos = vivos.subList(0, desde).filter(::esTexto)
            if (viejos.isNotEmpty()) {
                val linea = StringBuilder("\nTextos de esos:")
                var puestos = 0
                for (e in viejos) {
                    val t = textoDe(e)
                    val corto = (if (t.codePointCount(0, t.length) > 80) t.substring(0, t.offsetByCodePoints(0, 80)) + "..." else t).replace("\n", "\\n")
                    val trozo = " #${numeros[e.id]} \"$corto\""
                    if (linea.length + trozo.length > PRESUPUESTO_TEXTOS_VIEJOS) break
                    linea.append(trozo); puestos++
                }
                if (puestos < viejos.size) linea.append(" (+${viejos.size - puestos} textos mas: editar_texto accion listar)")
                s.append(linea)
            }
        }
        return s.toString()
    }

    /**
     * Las raíces que dicen que el pedido es de dibujar o de cambiar lo dibujado: las claves de
     * `dibujar_en_lienzo`, `dibujar_svg` y `editar_texto` en el PC (`seleccion.rs`, `edicion.rs`).
     */
    private val DE_LIENZO = listOf(
        "lienzo", "dibuj", "pinta", "traza", "figura", "diagrama", "esquema", "flecha", "caja", "recuadro", "rectangulo", "circulo",
        "triangulo", "linea", "plano", "mapa", "ilustra", "boceto", "croquis", "grafic", "texto", "rotulo", "titulo", "etiqueta",
        "traduc", "renombr", "tilde", "ortograf", "reescrib", "corrig", "arregl", "alinea", "al ingles", "al chino", "al espanol",
        "no cabe", "se sale", "agrand", "amplia", "reduc", "plantilla", "cronograma"
    )

    /**
     * **Si el resumen del lienzo viene a cuento** (`edicion::viene_a_cuento` del PC): solo si el
     * pedido es de dibujar o de cambiar lo dibujado. «¿Qué hora es?» no paga 30 líneas de lienzo.
     */
    fun vieneACuento(pedido: String): Boolean {
        val q = com.forge.pixpin.ia.Acciones.normalizar(pedido)
        return DE_LIENZO.any { q.contains(it) }
    }

    /** Pega el resumen detrás del pedido, como el PC (`"\n\n[" + resumen + "]"`). */
    fun conResumen(pedido: String, resumen: String): String = "$pedido\n\n[$resumen]"

    /** Lo que se escribe al cambiar el texto de [e]: el plano en `text` y la fuente rica si lleva marcas. */
    fun conTexto(e: Element, nuevo: String): Element {
        val esc = TextoRico.escrito(nuevo)
        return e.copy(text = esc.texto, textoRico = esc.fuente)
    }
}
