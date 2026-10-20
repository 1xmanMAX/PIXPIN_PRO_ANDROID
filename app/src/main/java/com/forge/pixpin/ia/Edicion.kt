package com.forge.pixpin.ia

import com.forge.pixpin.motor.Crop
import com.forge.pixpin.motor.DrawController
import com.forge.pixpin.motor.DurezaDeGrafito
import com.forge.pixpin.motor.Element
import com.forge.pixpin.motor.ElementType
import com.forge.pixpin.motor.FreedrawTuning
import com.forge.pixpin.motor.ItemStyle
import com.forge.pixpin.motor.MaterialDeTinta
import com.forge.pixpin.motor.Pt
import com.forge.pixpin.motor.Scene
import com.forge.pixpin.motor.StrokeStyle
import com.forge.pixpin.motor.Tool
import com.forge.pixpin.motor.absolutePoints
import com.forge.pixpin.motor.duplicateElements
import com.forge.pixpin.motor.escaladasAlrededorDelCentro
import com.forge.pixpin.motor.extenderArco
import com.forge.pixpin.motor.extenderEn
import com.forge.pixpin.motor.flipHorizontal
import com.forge.pixpin.motor.flipVertical
import com.forge.pixpin.motor.getElementBounds
import com.forge.pixpin.motor.giradasAlrededorDelCentro
import com.forge.pixpin.motor.groupElements
import com.forge.pixpin.motor.isLinear
import com.forge.pixpin.motor.moveAllLeft
import com.forge.pixpin.motor.moveAllRight
import com.forge.pixpin.motor.recorridoDe
import com.forge.pixpin.motor.recortarEn
import com.forge.pixpin.motor.cortesDe
import com.forge.pixpin.motor.segmentosDe
import com.forge.pixpin.motor.touched
import com.forge.pixpin.motor.ungroupElements
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * **Editar lo que ya hay en el lienzo** (PC `pixpin-ia/src/edicion.rs`, `edicion/referencia.rs` y
 * `edicion/hacer.rs`, 14-oct-2026): elegir por descripción, mover, escalar, girar, duplicar, borrar,
 * agrupar, ordenar y voltear; el estilo; trazar a mano con el lápiz, el grafito o el resaltador;
 * cortar y extender líneas como en CAD; pegar y recortar imágenes. La geometría exacta va aparte
 * ([GeometriaPrecisa]).
 *
 * Las mismas reglas que el PC: **el mismo motor del lienzo y las mismas operaciones que el dedo**
 * (el lápiz nace de un [DrawController] sin pantalla al que se le dan pulsar, mover y soltar; cortar
 * y extender son [recortarEn] y [extenderEn], los de la herramienta), una tabla de campos ([FILAS])
 * que valida todas igual, y como todo va a la escena por su camino de siempre, se guarda y se
 * sincroniza igual que lo dibujado a mano.
 *
 * Los `#n` del modelo son los de [Numeracion]: el orden en el lienzo al empezar el turno, y los
 * nuevos detrás; no cambian dentro del turno aunque se borre algo (en el PC son ids fijos).
 */
object Edicion {
    const val SELECCIONAR = "seleccionar_en_lienzo"
    const val TRANSFORMAR = "transformar_elementos"
    const val ESTILO = "cambiar_estilo"
    const val TRAZAR = "trazar_a_mano"
    const val CORTAR = "cortar_linea"
    const val EXTENDER = "extender_linea"
    const val IMAGEN = "imagen_en_lienzo"

    class Fallo(m: String) : Exception(m)

    private fun mal(m: String): Nothing = throw Fallo(m)

    // ------------------------------------------------------------------------------- la tabla

    sealed class Tipo {
        data object Texto : Tipo()
        data object Numero : Tipo()
        data object Numeros : Tipo()
        data object Referencia : Tipo()
        data class Opciones(val o: List<String>) : Tipo()
    }

    data class Campo(val nombre: String, val tipo: Tipo, val obligatorio: Boolean = false)

    /** Una herramienta: sus campos y las raíces (sin tildes) que la delatan en el pedido (para la selección). */
    data class Fila(val nombre: String, val campos: List<Campo>, val claves: List<String> = emptyList())

    private fun o(vararg v: String) = Tipo.Opciones(v.toList())
    private val COLOR = Campo("color", Tipo.Texto)
    private val GROSOR = Campo("grosor", Tipo.Numero)
    private val TRAZO = Campo("trazo", o("solido", "discontinuo", "punteado", "raya_punto"))
    private val DUREZA = Campo("dureza", o("4H", "2H", "HB", "2B", "4B", "6B", "8B"))

    /** Las filas del PC (`FILAS`, sin la geometría): nombres, tipos, obligatorios y opciones. */
    val FILAS = listOf(
        Fila(SELECCIONAR, listOf(Campo("que", Tipo.Referencia, true), Campo("zona", Tipo.Numeros)), listOf(
            "elige", "selecciona", "busca en el lienzo", "cual es", "que hay en el lienzo"
        )),
        Fila(TRANSFORMAR, listOf(
            Campo("que", Tipo.Referencia, true),
            Campo("accion", o("mover", "escalar", "girar", "duplicar", "borrar", "agrupar", "desagrupar", "al_frente", "al_fondo",
                "voltear_horizontal", "voltear_vertical"), true),
            Campo("dx", Tipo.Numero), Campo("dy", Tipo.Numero), Campo("a", Tipo.Numeros),
            Campo("factor", Tipo.Numero), Campo("grados", Tipo.Numero)
        ), listOf(
            "mueve", "mover", "desplaza", "escala", "agranda", "achica", "encoge", "gira", "rota", "duplica", "copia el",
            "copia la", "borra", "elimina", "quita", "agrupa", "desagrupa", "voltea", "espejo", "al frente", "al fondo",
            "detras", "delante", "mas grande", "mas pequen"
        )),
        Fila(ESTILO, listOf(
            Campo("que", Tipo.Referencia, true), COLOR, Campo("relleno", Tipo.Texto), GROSOR, TRAZO,
            Campo("opacidad", Tipo.Numero), Campo("material", o("lisa", "tiza", "grafito")), DUREZA,
            // De la nube (`CAMPOS_NUBE`): el local no lo ve en su esquema y no lo manda.
            Campo("tam_letra", Tipo.Numero)
        ), listOf(
            "color", "pinta de", "pintalo", "pintala", "rellena", "relleno", "grosor", "mas grueso", "mas fino",
            "discontinu", "punteado", "opacidad", "transparente", "cambia"
        )),
        Fila(TRAZAR, listOf(
            Campo("herramienta", o("lapiz", "grafito", "resaltador"), true), Campo("puntos", Tipo.Numeros),
            Campo("sobre", Tipo.Referencia), Campo("como", o("subrayar", "resaltar", "rodear", "tachar", "repasar")),
            COLOR, GROSOR, DUREZA
        ), listOf(
            "subraya", "resalta", "marca", "rodea", "encierra", "tacha", "repasa", "a mano", "lapiz", "grafito",
            "resaltador", "marcador", "fosforito"
        )),
        Fila(CORTAR, listOf(
            Campo("que", Tipo.Referencia, true), Campo("con", Tipo.Referencia),
            Campo("quitar", o("inicio", "fin", "medio", "corto", "largo")), Campo("punto", Tipo.Numeros)
        ), listOf(
            "corta", "cortar", "recorta la", "recorta el", "trim", "quita el trozo", "quita lo que sobra", "en el cruce", "interseccion"
        )),
        Fila(EXTENDER, listOf(Campo("que", Tipo.Referencia, true), Campo("hasta", Tipo.Referencia), Campo("extremo", o("inicio", "fin"))), listOf(
            "extiende", "extender", "alarga", "prolonga", "estira", "hasta que toque", "extend"
        )),
        Fila(IMAGEN, listOf(
            Campo("accion", o("pegar", "recortar"), true), Campo("imagen", Tipo.Texto), Campo("caja", Tipo.Numeros),
            Campo("que", Tipo.Referencia), Campo("conservar", Tipo.Numeros),
            Campo("quitar", o("arriba", "abajo", "izquierda", "derecha")), Campo("cuanto", Tipo.Numero)
        ), listOf(
            "pega", "pegar", "inserta", "recorta", "recortar", "foto", "imagen", "portapapeles", "captura"
        ))
    )

    /** Las que hace [aplicar] (la geometría exacta tiene su propio camino, [GeometriaPrecisa]). */
    val NOMBRES: Set<String> = FILAS.map { it.nombre }.toSet()

    const val GEOMETRIA = GeometriaPrecisaNombre

    /** Las claves de la geometría exacta: es de la tabla de editar en el PC (`FILAS`). */
    private val FILA_GEOMETRIA = Fila(GEOMETRIA, emptyList(), listOf(
        "recta", "circulo", "circunferencia", "arco", "perpendicular", "paralela", "radio", "centro", "angulo",
            "grados", "tres puntos", "exact", "segmento", "semicircunferencia", "semicirculo", "cuadrante", "sector",
            "cuerda", "diametro", "tangente", "marca", "angulo recto", "degradado", "punteada", "raya punto",
            "lamina", "geometria"
    ))

    /** Una fila de la tabla del PC, la geometría incluida (para la selección). */
    fun fila(nombre: String) = FILAS.firstOrNull { it.nombre == nombre } ?: FILA_GEOMETRIA.takeIf { it.nombre == nombre }

    /** Todas las filas del PC, en su orden (la geometría va detrás de trazar). */
    val TODAS: List<Fila> get() = FILAS.take(4) + FILA_GEOMETRIA + FILAS.drop(4)

    /** Una llamada ya validada: su herramienta y sus argumentos limpios. */
    data class Pedido(val herramienta: String, val args: Map<String, JsonElement>) {
        fun texto(k: String) = (args[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        fun numero(k: String) = (args[k] as? JsonPrimitive)?.doubleOrNull
        fun numeros(k: String) = (args[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }
        fun con(k: String, v: JsonElement) = copy(args = args + (k to v))
        fun falta(k: String) = k !in args
    }

    /** Un número, aceptando `"120"` (los modelos pequeños lo hacen). */
    private fun comoNumero(v: JsonElement): Double? = (v as? JsonPrimitive)?.let {
        if (it.isString) it.content.trim().replace(',', '.').toDoubleOrNull() else it.doubleOrNull
    }?.takeIf { it.isFinite() }

    private fun textoDe(v: JsonElement): String = (v as? JsonPrimitive)?.takeIf { it.isString }?.content ?: v.toString()

    /** **Valida una llamada** contra su fila (PC `validar`): tipos, obligatorios y opciones; lo demás se ignora. */
    fun validar(nombre: String, args: JsonObject): Pedido {
        val f = fila(nombre) ?: mal("la herramienta «$nombre» no es de editar")
        val limpio = LinkedHashMap<String, JsonElement>()
        for (c in f.campos) {
            val v = args[c.nombre]?.takeIf { it !is JsonNull }
            if (v == null) { if (c.obligatorio) mal("falta «${c.nombre}»"); continue }
            val n = c.nombre
            val bueno: JsonElement = when (val t = c.tipo) {
                Tipo.Texto, Tipo.Referencia -> when {
                    v is JsonPrimitive && v.isString && v.content.isNotBlank() -> JsonPrimitive(v.content.trim().take(300))
                    v is JsonPrimitive && !v.isString && v.doubleOrNull != null -> JsonPrimitive("#${numeroComoTexto(v)}")
                    // Una lista de ids: [12, 13] -> «#12 #13».
                    v is JsonArray && t == Tipo.Referencia && v.isNotEmpty() -> JsonPrimitive(v.joinToString(" ") { x ->
                        if (x is JsonPrimitive && !x.isString) "#${numeroComoTexto(x)}" else textoDe(x)
                    })
                    c.obligatorio -> mal("«$n» tiene que ser un texto")
                    else -> continue
                }
                Tipo.Numero -> JsonPrimitive(comoNumero(v) ?: mal("«$n» tiene que ser un numero"))
                Tipo.Numeros -> {
                    val lista: List<Double> = when (v) {
                        is JsonArray -> v.flatMap { x -> if (x is JsonArray) x.mapNotNull(::comoNumero) else listOfNotNull(comoNumero(x)) }
                        is JsonPrimitive -> if (v.isString) v.content.split(',', ';', ' ', '\t', '\n', '[', ']').mapNotNull { it.trim().toDoubleOrNull() } else listOfNotNull(comoNumero(v))
                        else -> emptyList()
                    }
                    if (lista.isEmpty()) mal("«$n» tiene que ser una lista de numeros")
                    if (lista.size > Herramientas.MAX_NUMEROS_PUNTOS) mal("«$n» tiene demasiados numeros")
                    JsonArray(lista.map { JsonPrimitive(it) })
                }
                is Tipo.Opciones -> {
                    val q = Acciones.normalizar(((v as? JsonPrimitive)?.content ?: "").trim()).replace(' ', '_')
                    val x = t.o.firstOrNull { Acciones.normalizar(it) == q }
                    when {
                        x != null -> JsonPrimitive(x)
                        c.obligatorio -> mal("«$n» tiene que ser una de: ${t.o.joinToString(", ")}")
                        else -> continue
                    }
                }
            }
            limpio[n] = bueno
        }
        return Pedido(nombre, limpio)
    }

    private fun numeroComoTexto(p: JsonPrimitive): String {
        val d = p.doubleOrNull ?: return p.content
        return if (d == Math.floor(d) && abs(d) < 1e15) d.toLong().toString() else d.toString()
    }

    // ------------------------------------------------------------------ lo que se sabe del turno

    /**
     * **Los `#n` de un lienzo** durante un turno: el orden de lo que se ve al empezar (1, 2…), y lo
     * que nace después, detrás. Un `#n` borrado no se reutiliza.
     */
    class Numeracion(inicial: List<Element>) {
        private val porId = LinkedHashMap<String, Int>()
        private var siguiente = 1

        init { for (e in inicial) if (!e.isDeleted) numero(e.id) }

        fun numero(id: String): Int = porId.getOrPut(id) { siguiente++ }

        fun id(n: Int): String? = porId.entries.firstOrNull { it.value == n }?.key

        /** Les da número a los que no lo tienen (lo nuevo), en el orden de la escena. */
        fun ponerAlDia(elementos: List<Element>) { for (e in elementos) if (!e.isDeleted) numero(e.id) }
    }

    /** Desde dónde se miden las coordenadas del modelo, lo elegido por el usuario y lo último tocado. */
    data class Contexto(val marco: Pt = Pt(0.0, 0.0), val elegidos: List<String> = emptyList(), val ultimos: List<String> = emptyList()) {
        fun aEscena(x: Double, y: Double) = Pt(marco.x + x, marco.y + y)
    }

    /** **El marco para un lienzo** (PC `marco_de`): la esquina de lo dibujado menos 40, o el cero. */
    fun marcoDe(elementos: List<Element>): Pt {
        val vivos = elementos.filter { !it.isDeleted }
        if (vivos.isEmpty()) return Pt(0.0, 0.0)
        val b = vivos.map(::getElementBounds)
        return Pt(Math.floor(b.minOf { it.x1 } - 40.0), Math.floor(b.minOf { it.y1 } - 40.0))
    }

    /** Lo que hizo una edición: el texto para el modelo y los ids que tocó o creó («lo último»). */
    data class Hecho(val texto: String, val ids: List<String>)

    // ---------------------------------------------------------------------------- el resumen

    const val MAX_RESUMEN = 30

    /** Cómo va pegado el resumen detrás del pedido (PC `anadir_resumen`): `\n\n[Lienzo destino…]`. */
    const val MARCA_DEL_RESUMEN = "\n\n[Lienzo destino"

    private fun vivosDe(escena: Scene) = escena.elements.filter { !it.isDeleted && !it.reference }

    private fun esCirculo(e: Element) = abs(e.width - e.height) <= 0.02 * max(e.width, e.height)

    /** El nombre de una figura para el modelo (PC `nombre_de`). */
    fun nombreDe(e: Element): String = when (e.type) {
        ElementType.FREEDRAW -> when {
            e.material == MaterialDeTinta.CUADRITOS -> "trazo de grafito"
            e.opacity <= OPACIDAD_DEL_RESALTADOR -> "resaltado"
            else -> "trazo a lapiz"
        }
        ElementType.LINE -> if ((e.points?.size ?: 0) > 3) "polilinea" else "recta"
        ElementType.ARROW -> "flecha"
        ElementType.RECTANGLE -> "rectangulo"
        ElementType.DIAMOND -> "rombo"
        ElementType.ELLIPSE -> if (esCirculo(e)) "circulo" else "elipse"
        ElementType.ARC -> "arco"
        ElementType.TEXT -> "texto"
        ElementType.IMAGE -> "imagen"
        ElementType.MEASURE -> "cota"
        ElementType.PUNTO -> "punto"
        ElementType.REGION -> "relleno"
        ElementType.SERIAL -> "numero"
        ElementType.FRAME -> "marco"
        else -> "figura"
    }

    /** La del marcador del lienzo (`DrawController.HIGHLIGHTER_OPACITY`): el lápiz translúcido. */
    private const val OPACIDAD_DEL_RESALTADOR = 40

    private val PALETA = listOf(
        "negro" to floatArrayOf(0.12f, 0.12f, 0.12f), "blanco" to floatArrayOf(1f, 1f, 1f),
        "rojo" to floatArrayOf(0.88f, 0.19f, 0.19f), "verde" to floatArrayOf(0.18f, 0.62f, 0.27f),
        "azul" to floatArrayOf(0.10f, 0.44f, 0.76f), "amarillo" to floatArrayOf(0.94f, 0.55f, 0.0f),
        "naranja" to floatArrayOf(0.91f, 0.35f, 0.05f), "morado" to floatArrayOf(0.40f, 0.25f, 0.85f),
        "rosa" to floatArrayOf(0.76f, 0.15f, 0.36f), "gris" to floatArrayOf(0.53f, 0.56f, 0.59f),
        "marron" to floatArrayOf(0.55f, 0.35f, 0.17f), "celeste" to floatArrayOf(0.05f, 0.52f, 0.60f)
    )

    private fun rgb(hex: String): FloatArray? {
        val h = hex.trim().removePrefix("#")
        if (h.length < 6) return null
        val v = h.substring(0, 6).toIntOrNull(16) ?: return null
        return floatArrayOf(((v shr 16) and 255) / 255f, ((v shr 8) and 255) / 255f, (v and 255) / 255f)
    }

    private fun alfa(hex: String): Float {
        val h = hex.trim().removePrefix("#")
        return if (h.length == 8) (h.substring(6, 8).toIntOrNull(16) ?: 255) / 255f else 1f
    }

    /** El nombre del color de la paleta más parecido (PC `nombre_de_color`). */
    fun nombreDeColor(hex: String): String {
        val c = rgb(hex) ?: return "negro"
        if (c[0] > 0.85f && c[1] > 0.7f && c[2] < 0.45f) return "amarillo"
        return PALETA.minBy { (_, p) -> (p[0] - c[0]) * (p[0] - c[0]) + (p[1] - c[1]) * (p[1] - c[1]) + (p[2] - c[2]) * (p[2] - c[2]) }.first
    }

    private fun conRelleno(e: Element) = e.backgroundColor != Element.TRANSPARENT && rgb(e.backgroundColor) != null && alfa(e.backgroundColor) > 0.05f

    /** Una línea del resumen: `#12 recta roja (100,40)->(300,40)` (PC `describir`). */
    fun describir(e: Element, num: Numeracion, marco: Pt): String {
        fun r(v: Double) = Math.round(v).toString()
        val sb = StringBuilder("#${num.numero(e.id)} ${nombreDe(e)} ${nombreDeColor(e.strokeColor)}")
        if (conRelleno(e)) sb.append(" relleno ${nombreDeColor(e.backgroundColor)}")
        val pts = if (e.isLinear) absolutePoints(e) else emptyList()
        if (pts.size >= 2) {
            val a = pts.first(); val b = pts.last()
            sb.append(" (${r(a.x - marco.x)},${r(a.y - marco.y)})->(${r(b.x - marco.x)},${r(b.y - marco.y)})")
        } else {
            val b = getElementBounds(e)
            sb.append(" [${r(b.x1 - marco.x)},${r(b.y1 - marco.y)},${r(b.width)},${r(b.height)}]")
        }
        if (e.type == ElementType.ELLIPSE) {
            val b = getElementBounds(e)
            sb.append(" centro (${r(b.midX - marco.x)},${r(b.midY - marco.y)})")
        }
        if (e.type == ElementType.TEXT) sb.append(" \"${(e.text ?: "").take(40).replace('\n', ' ')}\"")
        if (e.material == MaterialDeTinta.CUADRITOS) e.dureza?.let { sb.append(" ${it.rotulo}") }
        if (e.groupIds.isNotEmpty()) sb.append(" (en grupo)")
        return sb.toString()
    }

    /** La lista corta de unos ids, una línea por elemento. */
    fun listar(escena: Scene, ids: List<String>, num: Numeracion, marco: Pt): String {
        val porId = escena.elements.associateBy { it.id }
        return ids.mapNotNull { porId[it]?.takeIf { e -> !e.isDeleted } }.joinToString("\n") { describir(it, num, marco) }
    }

    /** **El resumen compacto del lienzo destino** para el modelo (PC `resumen`). */
    fun resumen(escena: Scene, num: Numeracion, ctx: Contexto): String {
        val vivos = vivosDe(escena)
        if (vivos.isEmpty()) return "Lienzo destino: vacio."
        num.ponerAlDia(escena.elements)
        val sb = StringBuilder("Lienzo destino: ${vivos.size} elementos (coordenadas como en dibujar_en_lienzo; usa sus #id para editarlos).")
        val elegidos = ctx.elegidos.filter { id -> vivos.any { it.id == id } }.map { "#${num.numero(it)}" }
        if (elegidos.isNotEmpty()) sb.append(" Elegido por el usuario: ${elegidos.joinToString(" ")}.")
        val desde = max(vivos.size - MAX_RESUMEN, 0)
        for (e in vivos.subList(desde, vivos.size)) sb.append('\n').append(describir(e, num, ctx.marco))
        if (desde > 0) sb.append("\n(+$desde mas antiguos sin listar)")
        return sb.toString()
    }

    /** **El «hecho» de lo dibujado** (PC `resumen_de_lo_anadido`): cuántos y sus `#id`, para editarlos después. */
    fun resumenDeLoAnadido(escena: Scene, ids: List<String>, num: Numeracion, ctx: Contexto): String {
        num.ponerAlDia(escena.elements)
        val sb = StringBuilder("hecho: ${ids.size} elementos anadidos al lienzo")
        val lista = listar(escena, ids.take(12), num, ctx.marco)
        if (lista.isNotEmpty()) sb.append('\n').append(lista)
        if (ids.size > 12) sb.append("\n(+${ids.size - 12} mas)")
        return sb.toString()
    }

    /** **Si el resumen del lienzo viene a cuento** en este pedido (PC `viene_a_cuento`). */
    fun vieneACuento(pedido: String): Boolean {
        if (Acciones.normalizar(pedido).contains("lienzo")) return true
        return Seleccion.paraElPedido(pedido, false).any { it.nombre in NOMBRES || it.nombre == GeometriaPrecisaNombre || it.nombre == Herramientas.DIBUJAR || it.nombre == Herramientas.SVG || it.nombre == "graficar_funcion" }
    }

    // --------------------------------------------------------------------------- referencias

    data class Encontrado(val ids: List<String>, val plural: Boolean)

    private fun tipoDePalabra(p: String): ((Element) -> Boolean)? {
        var raiz = p.trimEnd('s')
        raiz.removeSuffix("e").takeIf { raiz.endsWith("e") && (it.endsWith("l") || it.endsWith("n")) }?.let { raiz = it }
        return when (raiz) {
            "recta", "linea", "raya", "segmento" -> { e -> e.type == ElementType.LINE || e.type == ElementType.MEASURE }
            "flecha" -> { e -> e.type == ElementType.ARROW }
            "circulo", "circunferencia", "elipse", "ovalo" -> { e -> e.type == ElementType.ELLIPSE }
            "arco" -> { e -> e.type == ElementType.ARC }
            "rectangulo", "cuadrado", "caja", "recuadro" -> { e -> e.type == ElementType.RECTANGLE }
            "rombo" -> { e -> e.type == ElementType.DIAMOND }
            "texto", "titulo", "letrero", "palabra", "frase", "etiqueta", "rotulo", "resultado" -> { e -> e.type == ElementType.TEXT }
            "imagen", "image", "foto", "fotografia", "captura" -> { e -> e.type == ElementType.IMAGE }
            "trazo", "lapiz", "garabato", "dibujo" -> { e -> e.type == ElementType.FREEDRAW && nombreDe(e) != "resaltado" }
            "grafito" -> { e -> e.type == ElementType.FREEDRAW && e.material == MaterialDeTinta.CUADRITOS }
            "resaltado", "marcador" -> { e -> e.type == ElementType.FREEDRAW && nombreDe(e) == "resaltado" }
            "punto" -> { e -> e.type == ElementType.PUNTO }
            "triangulo", "poligono", "estrella", "polilinea" -> { e -> e.type == ElementType.LINE && (e.points?.size ?: 0) > 3 }
            "cota" -> { e -> e.type == ElementType.MEASURE }
            else -> null
        }
    }

    private fun colorDePalabra(p: String): String? = Herramientas.color(p)?.let(::nombreDeColor)

    private enum class Extremo { ARRIBA, ABAJO, IZQUIERDA, DERECHA, GRANDE, PEQUENO, PRIMERO, SEGUNDO }

    private fun extremoDe(p: String) = when (p) {
        "arriba", "superior", "alto", "alta" -> Extremo.ARRIBA
        "abajo", "inferior", "bajo", "baja" -> Extremo.ABAJO
        "izquierda", "izquierdo" -> Extremo.IZQUIERDA
        "derecha", "derecho" -> Extremo.DERECHA
        "grande", "mayor", "larga", "largo", "gorda", "gordo" -> Extremo.GRANDE
        "pequena", "pequeno", "menor", "corta", "corto", "chica", "chico" -> Extremo.PEQUENO
        "primera", "primero", "primer" -> Extremo.PRIMERO
        "segunda", "segundo" -> Extremo.SEGUNDO
        else -> null
    }

    /** El texto citado: entre comillas de cualquier clase, o tras «que dice». */
    private fun citado(q: String): String? {
        for ((abre, cierra) in listOf('"' to '"', '«' to '»', '“' to '”', '\'' to '\'')) {
            val i = q.indexOf(abre)
            if (i < 0) continue
            val resto = q.substring(i + 1)
            val j = resto.indexOf(cierra)
            if (j >= 0) resto.substring(0, j).trim().takeIf { it.isNotEmpty() }?.let { return it }
        }
        for (marca in listOf("que dice ", "que pone ", "texto:", "con el texto ")) {
            val i = q.indexOf(marca)
            if (i >= 0) q.substring(i + marca.length).trim().takeIf { it.isNotEmpty() }?.let { return it }
        }
        return null
    }

    /** Los `#12` (o `12` suelto) de una referencia. */
    private fun idsEscritos(q: String): List<Int> = q.split(Regex("[^A-Za-z0-9#]")).mapNotNull { t0 ->
        var t = t0.trimStart('#')
        if (t.startsWith("e") && t.length > 1 && t.drop(1).all { it.isDigit() }) t = t.drop(1)
        t.takeIf { it.isNotEmpty() && it.all { c -> c.isDigit() } && it.length < 12 }?.toIntOrNull()
    }

    /** **Resuelve una referencia** contra la escena (PC `referencia::resolver`). */
    fun resolver(escena: Scene, que: String, ctx: Contexto, num: Numeracion, zona: DoubleArray? = null): Encontrado {
        num.ponerAlDia(escena.elements)
        val vivos = vivosDe(escena)
        if (vivos.isEmpty()) mal("el lienzo esta vacio: no hay nada que elegir")
        val q = Acciones.normalizar(que.trim())
        fun enZona(e: Element): Boolean {
            val z = zona ?: return true
            val b = getElementBounds(e)
            return b.x1 >= z[0] - 1 && b.y1 >= z[1] - 1 && b.x2 <= z[0] + z[2] + 1 && b.y2 <= z[1] + z[3] + 1
        }
        fun todoLoQueHay() = listar(escena, vivos.reversed().take(12).map { it.id }, num, ctx.marco)

        // 1. Los #id.
        val escritos = idsEscritos(q)
        val soloNumeros = q.split(Regex("[\\s,y]")).filter { it.isNotEmpty() }.all { it.trimStart('#').all { c -> c.isDigit() } }
        if (escritos.isNotEmpty() && (q.contains('#') || soloNumeros)) {
            val ids = escritos.map { n -> num.id(n)?.takeIf { id -> vivos.any { it.id == id } } }
            val faltan = escritos.filterIndexed { i, _ -> ids[i] == null }.map { "#$it" }
            // Cuatro números que no son ids: una caja [x, y, ancho, alto]. Lo que cae dentro.
            if (faltan.isNotEmpty() && escritos.size == 4) {
                val (x, y) = ctx.aEscena(escritos[0].toDouble(), escritos[1].toDouble())
                val w = escritos[2].toDouble(); val h = escritos[3].toDouble()
                val margen = 0.1 * max(w, h) + 4
                val dentro = vivos.filter { val b = getElementBounds(it); b.x1 >= x - margen && b.y1 >= y - margen && b.x2 <= x + w + margen && b.y2 <= y + h + margen }.map { it.id }
                if (dentro.isNotEmpty()) return Encontrado(dentro, false)
            }
            if (faltan.isNotEmpty()) mal("no existe ${faltan.joinToString(", ")} en el lienzo. Lo que hay:\n${todoLoQueHay()}")
            return Encontrado(ids.filterNotNull(), escritos.size > 1)
        }
        val palabras = q.split(Regex("[^\\p{L}\\p{N}]")).filter { it.isNotEmpty() }
        fun dice(w: String) = w in palabras

        // 2. Lo elegido, lo último, todo.
        if (dice("elegido") || dice("elegida") || dice("elegidos") || dice("seleccionado") || dice("seleccionada") || q.contains("seleccion")) {
            val ids = ctx.elegidos.filter { id -> vivos.any { it.id == id } }
            if (ids.isEmpty()) mal("el usuario no tiene nada elegido en el lienzo: nombralo por su #id o descripcion")
            return Encontrado(ids, ids.size > 1)
        }
        val pluralUltimo = dice("ultimos") || dice("ultimas")
        if ((dice("ultimo") || dice("ultima") || pluralUltimo) && palabras.all { tipoDePalabra(it) == null }) {
            val ids = ctx.ultimos.filter { id -> vivos.any { it.id == id } }
            if (ids.isNotEmpty()) return Encontrado(ids, ids.size > 1)
            return Encontrado(listOf(vivos.last().id), false)
        }
        val hayTipo = palabras.any { tipoDePalabra(it) != null }
        val hayColor = palabras.any { colorDePalabra(it) != null }
        val todos = dice("todo") || dice("todos") || dice("todas")
        if (todos && !hayTipo && !hayColor && citado(que) == null) return Encontrado(vivos.filter(::enZona).map { it.id }, true)

        // 3. Una descripción.
        val tipos = palabras.mapNotNull { tipoDePalabra(it) }
        val plural = todos || palabras.any { it.length > 3 && it.endsWith("s") && tipoDePalabra(it) != null }
        val colores = palabras.mapNotNull { colorDePalabra(it) }
        val cita = citado(que)?.let { Acciones.normalizar(it) }
        val extremo = palabras.reversed().firstNotNullOfOrNull { extremoDe(it) }
        val titulo = dice("titulo") || dice("encabezado")
        var candidatos = vivos.filter(::enZona)
            .filter { e -> tipos.isEmpty() || tipos.any { it(e) } }
            .filter { e -> colores.isEmpty() || colores.any { c -> c == nombreDeColor(e.strokeColor) || (conRelleno(e) && c == nombreDeColor(e.backgroundColor)) } }
            .filter { e -> cita == null || (e.type == ElementType.TEXT && Acciones.normalizar(e.text ?: "").contains(cita)) }
        if (tipos.isEmpty() && colores.isEmpty() && cita == null && zona == null && extremo == null) {
            mal("no entiendo «$que» como algo del lienzo: nombralo por su #id, tipo o color. Lo que hay:\n${todoLoQueHay()}")
        }
        if (candidatos.isEmpty()) mal("no hay nada que encaje con «$que» en el lienzo. Lo que hay:\n${todoLoQueHay()}")
        if (titulo && !plural && candidatos.size > 1) {
            fun tam(e: Element) = if (e.type == ElementType.TEXT) e.fontSize ?: 0.0 else 0.0
            val mayor = candidatos.maxOf(::tam)
            candidatos = candidatos.filter { tam(it) >= mayor - 0.5 }.sortedBy { getElementBounds(it).y1 }.take(1)
        }
        if (tipos.isNotEmpty() && candidatos.size > 1 && !plural) {
            palabras.firstNotNullOfOrNull { it.toIntOrNull() }?.let { n ->
                val porId = num.id(n)?.let { id -> candidatos.firstOrNull { it.id == id } }
                (porId ?: candidatos.getOrNull(n - 1))?.let { candidatos = listOf(it) }
            }
        }
        if (extremo != null && candidatos.size > 1 && !plural) {
            fun medida(e: Element): Double {
                val b = getElementBounds(e)
                return when (extremo) {
                    Extremo.ARRIBA -> b.midY
                    Extremo.ABAJO -> -b.midY
                    Extremo.IZQUIERDA -> b.midX
                    Extremo.DERECHA -> -b.midX
                    Extremo.GRANDE -> -(b.width * b.width + b.height * b.height)
                    Extremo.PEQUENO -> b.width * b.width + b.height * b.height
                    else -> 0.0
                }
            }
            candidatos = listOfNotNull(when (extremo) {
                Extremo.PRIMERO -> candidatos.firstOrNull()
                Extremo.SEGUNDO -> candidatos.getOrNull(1)
                else -> candidatos.minByOrNull(::medida)
            })
        }
        return Encontrado(candidatos.map { it.id }, plural)
    }

    /** **Uno solo** (PC `uno`): si da varios, la lista corta para que el modelo elija por su `#id`. */
    fun uno(escena: Scene, que: String, ctx: Contexto, num: Numeracion, vale: Pair<String, (Element) -> Boolean>? = null): String {
        var e = resolver(escena, que, ctx, num)
        val porId = escena.elements.associateBy { it.id }
        if (vale != null) {
            val ok = e.ids.filter { porId[it]?.let(vale.second) == true }
            if (ok.isNotEmpty()) e = e.copy(ids = ok)
            else mal("«$que» es un ${e.ids.firstOrNull()?.let { porId[it] }?.let(::nombreDe) ?: "figura"}: aqui hace falta ${vale.first}")
        }
        return when (e.ids.size) {
            1 -> e.ids[0]
            0 -> mal("no hay nada que encaje con «$que»")
            else -> mal("«$que» es ambiguo: encajan ${e.ids.size}. No elijas tu: pregunta al usuario cual (con esta lista), o usa el #id si ya lo dijo:\n${listar(escena, e.ids, num, ctx.marco)}")
        }
    }

    /** **Uno o varios** (PC `varios`): un singular que encaja con varios es ambiguo. */
    fun varios(escena: Scene, que: String, ctx: Contexto, num: Numeracion): List<String> {
        val e = resolver(escena, que, ctx, num)
        if (e.ids.size > 1 && !e.plural) {
            mal("«$que» es ambiguo: encajan ${e.ids.size}. No elijas tu: pregunta al usuario cual (con esta lista), o usa el #id si ya lo dijo (en plural si son todos):\n${listar(escena, e.ids, num, ctx.marco)}")
        }
        if (e.ids.isEmpty()) mal("no hay nada que encaje con «$que»")
        return e.ids
    }

    // ------------------------------------------------------------------------- ajustar al pedido

    /** **Lo que el modelo olvidó y el pedido dice** (PC `ajustar_al_pedido`): solo rellena lo que falta. */
    fun ajustarAlPedido(e0: Pedido, pedido: String): Pedido {
        var e = e0
        val q = Acciones.normalizar(pedido)
        val palabras = q.split(Regex("[^\\p{L}\\p{N}]")).filter { it.isNotEmpty() }
        fun empieza(raices: List<String>) = palabras.any { p -> raices.any { p.startsWith(it) } }
        if (e.herramienta == TRAZAR && "sobre" in e.args && e.falta("como")) {
            val como = when {
                empieza(listOf("rode", "encierr", "circul")) -> "rodear"
                empieza(listOf("tach")) -> "tachar"
                empieza(listOf("repas")) -> "repasar"
                empieza(listOf("subray")) -> "subrayar"
                empieza(listOf("resalt")) -> "resaltar"
                else -> null
            }
            como?.let { e = e.con("como", JsonPrimitive(it)) }
        }
        if (e.herramienta == TRAZAR && e.falta("dureza")) {
            pedido.split(Regex("[^\\p{L}\\p{N}]")).firstNotNullOfOrNull { p -> DurezaDeGrafito.entries.firstOrNull { it.rotulo == p.uppercase() } }
                ?.let { e = e.con("dureza", JsonPrimitive(it.rotulo)) }
        }
        if (e.herramienta == IMAGEN && e.texto("accion") == "recortar" && e.falta("conservar") && e.falta("quitar")) {
            val lado = palabras.firstNotNullOfOrNull {
                when (it) {
                    "izquierda", "izquierdo" -> "izquierda"
                    "derecha", "derecho" -> "derecha"
                    "arriba", "superior" -> "arriba"
                    "abajo", "inferior" -> "abajo"
                    else -> null
                }
            }
            val c = if (q.contains("tercio")) 1.0 / 3.0 else if (q.contains("cuarto")) 0.25 else 0.5
            if (lado != null) {
                e = if (empieza(listOf("quit", "elimin", "borr", "sac"))) e.con("quitar", JsonPrimitive(lado)).con("cuanto", JsonPrimitive(c))
                else {
                    val caja = when (lado) {
                        "izquierda" -> listOf(0.0, 0.0, c, 1.0)
                        "derecha" -> listOf(1.0 - c, 0.0, c, 1.0)
                        "arriba" -> listOf(0.0, 0.0, 1.0, c)
                        else -> listOf(0.0, 1.0 - c, 1.0, c)
                    }
                    e.con("conservar", JsonArray(caja.map { JsonPrimitive(it) }))
                }
            }
        }
        if (e.herramienta == TRAZAR && e.falta("color")) {
            palabras.firstOrNull { Herramientas.color(it) != null }?.let { e = e.con("color", JsonPrimitive(it)) }
        }
        return e
    }

    // -------------------------------------------------------------------------------- hacer

    /** Lo que hace falta de quien tiene las imágenes (el chat): el motor no sabe de bitmaps. */
    interface Anfitrion {
        /** Mete la imagen [fuente] (adjunto por su nombre; vacío = la última) y da el elemento listo en (0, 0). */
        fun imagenNueva(escena: Scene, fuente: String): Pair<Scene, Element>
        /** El tamaño en píxeles del mapa de una imagen del lienzo, si se sabe. */
        fun tamanoDe(fileId: String): Pair<Int, Int>? = null
    }

    object SinImagenes : Anfitrion {
        override fun imagenNueva(escena: Scene, fuente: String): Pair<Scene, Element> = mal("aqui no se pueden pegar imagenes")
    }

    /**
     * **Aplica una edición** y devuelve la escena nueva y lo hecho. Si falla, lanza [Fallo] con el
     * motivo para el modelo, y la escena de quien llama queda como estaba (todo va sobre copias).
     */
    fun aplicar(escena: Scene, e: Pedido, ctx: Contexto, num: Numeracion, an: Anfitrion = SinImagenes): Pair<Scene, Hecho> {
        num.ponerAlDia(escena.elements)
        val r = when (e.herramienta) {
            SELECCIONAR -> seleccionar(escena, e, ctx, num)
            TRANSFORMAR -> transformar(escena, e, ctx, num)
            ESTILO -> cambiarEstilo(escena, e, ctx, num)
            TRAZAR -> trazar(escena, e, ctx, num)
            CORTAR -> cortar(escena, e, ctx, num)
            EXTENDER -> extender(escena, e, ctx, num)
            IMAGEN -> imagen(escena, e, ctx, num, an)
            else -> mal("la herramienta «${e.herramienta}» no es de editar")
        }
        num.ponerAlDia(r.first.elements)
        return r
    }

    private fun hecho(escena: Scene, que: String, ids: List<String>, ctx: Contexto, num: Numeracion): Hecho {
        num.ponerAlDia(escena.elements)
        val lista = listar(escena, ids, num, ctx.marco)
        return Hecho(if (lista.isEmpty()) "hecho: $que" else "hecho: $que\n$lista", ids)
    }

    private fun colorDe(t: String): String = Herramientas.color(t) ?: mal("no conozco el color «$t»")

    private fun punto(e: Pedido, k: String, ctx: Contexto) = e.numeros(k)?.takeIf { it.size >= 2 }?.let { ctx.aEscena(it[0], it[1]) }

    private fun trazoDe(t: String) = when (t) {
        "discontinuo", "raya_punto", "rayapunto" -> StrokeStyle.DASHED
        "punteado" -> StrokeStyle.DOTTED
        else -> StrokeStyle.SOLID
    }

    private fun mapa(escena: Scene, ids: Collection<String>, f: (Element) -> Element): Scene =
        escena.copy(elements = escena.elements.map { if (it.id in ids) f(it).touched() else it })

    private fun seleccionar(escena: Scene, e: Pedido, ctx: Contexto, num: Numeracion): Pair<Scene, Hecho> {
        val que = e.texto("que") ?: "todo"
        val zona = e.numeros("zona")?.takeIf { it.size >= 4 }?.let { z -> doubleArrayOf(z[0] + ctx.marco.x, z[1] + ctx.marco.y, z[2], z[3]) }
        val r = resolver(escena, que, ctx, num, zona)
        if (r.ids.isEmpty()) mal("no hay nada que encaje con «$que» ahi")
        val n = r.ids.size
        return escena to hecho(escena, "$n encontrado${if (n == 1) "" else "s"}", r.ids, ctx, num)
    }

    private fun transformar(escena: Scene, e: Pedido, ctx: Contexto, num: Numeracion): Pair<Scene, Hecho> {
        val porId = escena.elements.associateBy { it.id }
        // Lo bloqueado se ve pero no se toca (como con el dedo).
        val ids = varios(escena, e.texto("que") ?: "", ctx, num).filter { porId[it]?.locked == false }
        if (ids.isEmpty()) mal("esta bloqueado: el usuario lo dejo quieto a proposito")
        val accion = e.texto("accion") ?: "mover"
        val elegidos = escena.elements.filter { it.id in ids }
        val caja = elegidos.map(::getElementBounds)
        val x0 = caja.minOf { it.x1 }; val y0 = caja.minOf { it.y1 }
        fun conLosElegidos(f: (List<Element>) -> List<Element>): Scene {
            val nuevos = f(escena.elements.filter { it.id in ids }).associateBy { it.id }
            return escena.copy(elements = escena.elements.map { nuevos[it.id] ?: it })
        }
        return when (accion) {
            "mover" -> {
                val a = punto(e, "a", ctx)
                val dx = a?.let { it.x - x0 } ?: e.numero("dx") ?: 0.0
                val dy = a?.let { it.y - y0 } ?: e.numero("dy") ?: 0.0
                if (dx == 0.0 && dy == 0.0) mal("para mover hace falta «dx»/«dy» o «a»")
                val c = DrawController(escena).apply { setSelection(ids.toSet()); moverLaSeleccion(dx, dy, 1.0) }
                c.scene to hecho(c.scene, "movido", ids, ctx, num)
            }
            "escalar" -> {
                val f = e.numero("factor") ?: mal("para escalar hace falta «factor»")
                if (f !in 0.05..20.0) mal("«factor» tiene que ir de 0.05 a 20")
                val s = conLosElegidos { escaladasAlrededorDelCentro(it, f, f) }
                s to hecho(s, "escalado x${numeroBonito(f)}", ids, ctx, num)
            }
            "girar" -> {
                val g = e.numero("grados") ?: mal("para girar hace falta «grados»")
                // En pantalla la y va hacia abajo: antihorario es restar.
                val s = conLosElegidos { giradasAlrededorDelCentro(it, -Math.toRadians(g)) }
                s to hecho(s, "girado ${numeroBonito(g)} grados", ids, ctx, num)
            }
            "duplicar" -> {
                val a = punto(e, "a", ctx)
                val dx = a?.let { it.x - x0 } ?: e.numero("dx") ?: 20.0
                val dy = a?.let { it.y - y0 } ?: e.numero("dy") ?: 20.0
                val copias = duplicateElements(elegidos, 0.0).map { it.copy(x = it.x + dx, y = it.y + dy) }
                val s = escena.copy(elements = escena.elements + copias)
                s to hecho(s, "duplicado", copias.map { it.id }, ctx, num)
            }
            "borrar" -> {
                val texto = "borrados ${ids.size}: ${listar(escena, ids, num, ctx.marco).replace('\n', ';').replace(";", "; ")}"
                val c = DrawController(escena).apply { setSelection(ids.toSet()); deleteSelection() }
                c.scene to Hecho("hecho: $texto", emptyList())
            }
            "agrupar" -> {
                if (ids.size < 2) mal("para agrupar hacen falta dos o mas")
                val s = escena.copy(elements = groupElements(escena.elements, ids.toSet()))
                s to hecho(s, "agrupados", ids, ctx, num)
            }
            "desagrupar" -> {
                val s = escena.copy(elements = ungroupElements(escena.elements, ids.toSet()))
                s to hecho(s, "desagrupados", ids, ctx, num)
            }
            "al_frente" -> { val s = escena.copy(elements = moveAllRight(escena.elements, ids.toSet())); s to hecho(s, "al frente", ids, ctx, num) }
            "al_fondo" -> { val s = escena.copy(elements = moveAllLeft(escena.elements, ids.toSet())); s to hecho(s, "al fondo", ids, ctx, num) }
            "voltear_horizontal", "voltear_vertical" -> {
                val s = conLosElegidos { if (accion == "voltear_horizontal") flipHorizontal(it) else flipVertical(it) }
                s to hecho(s, "volteado", ids, ctx, num)
            }
            else -> mal("accion desconocida «$accion»")
        }
    }

    private fun numeroBonito(v: Double) = if (v == Math.floor(v) && abs(v) < 1e9) v.toLong().toString() else v.toString()

    private fun cambiarEstilo(escena: Scene, e: Pedido, ctx: Contexto, num: Numeracion): Pair<Scene, Hecho> {
        val ids = varios(escena, e.texto("que") ?: "", ctx, num)
        val color = e.texto("color")?.let(::colorDe)
        val relleno = e.texto("relleno")?.let { r ->
            if (Acciones.normalizar(r) in setOf("ninguno", "nada", "sin relleno", "transparente", "quitar")) Element.TRANSPARENT else colorDe(r)
        }
        val trazo = e.texto("trazo")?.let(::trazoDe)
        val opacidad = e.numero("opacidad")?.let { Math.round((it / 100.0).coerceIn(0.05, 1.0) * 100).toInt() }
        val material = e.texto("material")?.let { if (it == "grafito") MaterialDeTinta.CUADRITOS else if (it == "tiza") MaterialDeTinta.TIZA else MaterialDeTinta.LISA }
        val dureza = e.texto("dureza")?.let { d -> DurezaDeGrafito.entries.firstOrNull { it.rotulo == d } }
        val grosor = e.numero("grosor")?.coerceIn(0.5, 40.0)
        val tam = e.numero("tam_letra")?.coerceIn(6.0, 200.0)
        if (listOf(color, relleno, trazo, opacidad, material, dureza, grosor, tam).all { it == null }) {
            mal("no se pidio ningun cambio: di «color», «relleno», «grosor», «trazo», «opacidad», «material» o «dureza»")
        }
        if (tam != null && listOf(color, relleno, trazo, opacidad, material, dureza, grosor).all { it == null } &&
            escena.elements.none { it.id in ids && it.type == ElementType.TEXT }) {
            mal("«tam_letra» solo cambia textos y en lo elegido no hay ninguno")
        }
        val s = mapa(escena, ids) { x0 ->
            var x = x0
            if (x.locked) return@mapa x
            color?.let { x = x.copy(strokeColor = it) }
            relleno?.let { x = x.copy(backgroundColor = it) }
            trazo?.let { x = x.copy(strokeStyle = it) }
            opacidad?.let { x = x.copy(opacity = it) }
            if (x.type == ElementType.FREEDRAW) {
                material?.let { x = x.copy(material = it) }
                dureza?.let { x = x.copy(dureza = it) }
            }
            // El lápiz pinta a `grosor × SIZE_FACTOR` de ancho (como en el PC).
            grosor?.let { g -> x = x.copy(strokeWidth = if (x.type == ElementType.FREEDRAW) g / FreedrawTuning.SIZE_FACTOR else g) }
            if (tam != null && x.type == ElementType.TEXT) {
                val k = tam / (x.fontSize ?: tam)
                x = x.copy(fontSize = tam, width = x.width * k, height = x.height * k)
            }
            x
        }
        return s to hecho(s, "estilo cambiado", ids, ctx, num)
    }

    // ------------------------------------------------------------------------- trazos a mano

    /**
     * **Traza con el gesto del editor** (PC `con_gesto`): pulsar en el primero, mover por los demás y
     * soltar en el último, en un [DrawController] sin pantalla con la herramienta y el estilo dados.
     * El imán no: el modelo da coordenadas exactas y no un dedo a pulso.
     */
    fun conGesto(escena: Scene, herramienta: Tool, puntos: List<Pt>, estilo: (ItemStyle) -> ItemStyle = { it }): Pair<Scene, String>? {
        if (puntos.isEmpty()) return null
        val c = DrawController(escena.copy(style = estilo(escena.style)))
        c.enganche = c.enganche.copy(activo = false)
        c.selectTool(herramienta)
        val antes = escena.elements.map { it.id }.toSet()
        var t = 1_000L
        c.pointerDown(puntos.first(), cuando = t)
        for (p in puntos.drop(1)) { t += 8; c.pointerMove(p, cuando = t) }
        c.pointerUp(puntos.last())
        val nuevo = c.scene.elements.lastOrNull { it.id !in antes && !it.isDeleted } ?: return null
        return c.scene.copy(style = escena.style) to nuevo.id
    }

    /**
     * **El recorrido de una mano** (PC `a_mano`): los puntos dados, repartidos como los da un dedo
     * —más juntos al arrancar y al frenar— y con un temblor de menos de un píxel.
     */
    fun aMano(puntos: List<Pt>, semilla: Int): List<Pt> {
        if (puntos.size < 2) return puntos
        val largos = puntos.zipWithNext { a, b -> hypot(b.x - a.x, b.y - a.y).toFloat() }
        val total = largos.sum()
        if (total < 1f) return puntos
        val n = (total / 4f).toInt().coerceIn(8, 400)
        var azar = if (semilla == 0) 1 else semilla
        fun temblor(): Float {
            azar = azar xor (azar shl 13); azar = azar xor (azar ushr 17); azar = azar xor (azar shl 5)
            return Integer.remainderUnsigned(azar, 1000).toFloat() / 1000f - 0.5f
        }
        val salida = ArrayList<Pt>(n + 1)
        for (i in 0..n) {
            val u = i.toFloat() / n
            val s = total * (1f - cos(PI.toFloat() * u)) / 2f
            var recorrido = 0f
            var p = puntos.last()
            for ((k, l) in largos.withIndex()) {
                if (s <= recorrido + l || k == largos.size - 1) {
                    val t = if (l > 0f) ((s - recorrido) / l).coerceIn(0f, 1f) else 0f
                    val a = puntos[k]; val b = puntos[k + 1]
                    p = Pt(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
                    break
                }
                recorrido += l
            }
            if (i in 1 until n) p = Pt(p.x + temblor() * 0.8f, p.y + temblor() * 0.8f)
            salida += p
        }
        return salida
    }

    /** El ancho que pinta el resaltador por unidad de grosor (PC `ancho_del_resaltador(1)`). */
    private val ANCHO_DEL_RESALTADOR = 2.0 * FreedrawTuning.SIZE_FACTOR * sin(0.5 * PI / 2)

    private fun trazar(escena: Scene, e: Pedido, ctx: Contexto, num: Numeracion): Pair<Scene, Hecho> {
        val herramienta = e.texto("herramienta") ?: "lapiz"
        val tool = when (herramienta) { "grafito" -> Tool.GRAFITO; "resaltador" -> Tool.HIGHLIGHTER; else -> Tool.FREEDRAW }
        val sobre = e.texto("sobre")
        val pts = e.numeros("puntos")
        val (camino, altoTapado) = when {
            sobre != null -> {
                val id = uno(escena, sobre, ctx, num)
                val objetivo = escena.elements.first { it.id == id }
                caminoSobre(objetivo, e.texto("como") ?: if (tool == Tool.HIGHLIGHTER) "resaltar" else "subrayar")
            }
            pts != null && pts.size >= 4 -> pts.chunked(2).filter { it.size == 2 }.map { ctx.aEscena(it[0], it[1]) } to null
            else -> mal("hace falta «puntos» (al menos dos) o «sobre» (algo del lienzo)")
        }
        val color = e.texto("color")?.let(::colorDe) ?: if (tool == Tool.HIGHLIGHTER) "#ffd43b" else "#1e1e1e"
        val grosor = e.numero("grosor")?.coerceIn(0.5, 40.0)
        val dureza = e.texto("dureza")?.let { d -> DurezaDeGrafito.entries.firstOrNull { it.rotulo == d } }
        val recorrido = aMano(camino, (escena.elements.size * -1640531535 + 7))
        var (s, id) = conGesto(escena, tool, recorrido) { st ->
            var x = st.copy(strokeColor = color, roughness = 0)
            dureza?.let { x = x.copy(dureza = it) }
            x
        } ?: mal("el trazo no salio")
        // El grosor pedido (el lápiz pinta a `grosor × SIZE_FACTOR`); el resaltador tapa la altura de lo que resalta.
        val ancho = if (tool == Tool.HIGHLIGHTER) grosor ?: altoTapado else null
        when {
            tool == Tool.HIGHLIGHTER && ancho != null -> s = mapa(s, listOf(id)) { it.copy(strokeWidth = (ancho / ANCHO_DEL_RESALTADOR).coerceIn(0.5, 20.0)) }
            tool != Tool.HIGHLIGHTER && grosor != null -> s = mapa(s, listOf(id)) { it.copy(strokeWidth = (grosor / FreedrawTuning.SIZE_FACTOR).coerceIn(0.2, 8.0)) }
        }
        return s to hecho(s, "trazo con $herramienta", listOf(id), ctx, num)
    }

    /** Por dónde pasa la mano para subrayar, resaltar, rodear, tachar o repasar algo (PC `camino_sobre`). */
    fun caminoSobre(o: Element, como: String): Pair<List<Pt>, Double?> {
        val b = getElementBounds(o)
        val x0 = b.x1; val y0 = b.y1; val x1 = b.x2; val y1 = b.y2
        return when (como) {
            "subrayar" -> listOf(Pt(x0, y1 + 3), Pt(x1, y1 + 3)) to null
            "tachar" -> listOf(Pt(x0 - 2, (y0 + y1) / 2), Pt(x1 + 2, (y0 + y1) / 2)) to null
            "resaltar" -> listOf(Pt(x0 - 2, (y0 + y1) / 2), Pt(x1 + 2, (y0 + y1) / 2)) to max(b.height * 0.9, 8.0)
            "rodear" -> {
                val cx = b.midX; val cy = b.midY
                val rx = b.width / 2 * 1.25 + 8; val ry = b.height / 2 * 1.35 + 8
                val pasos = 48
                (0..pasos + 5).map { i ->
                    val t = PI * (0.8 + 2.0 * i / pasos)
                    val r = 1.0 + 0.03 * (i.toDouble() / pasos)
                    Pt(cx + rx * r * cos(t), cy + ry * r * sin(t))
                } to null
            }
            "repasar" -> {
                val segs = segmentosDe(o)
                if (segs.isEmpty()) mal("eso no tiene contorno que repasar")
                val v = mutableListOf(segs[0].first)
                for ((a, c) in segs) {
                    if (hypot(v.last().x - a.x, v.last().y - a.y) > 0.5) v += a
                    v += c
                }
                v.take(400) to null
            }
            else -> mal("«como» desconocido: $como")
        }
    }

    // ---------------------------------------------------------------------- cortar y extender

    private fun seRecorta(e: Element) = e.isLinear || e.type == ElementType.ELLIPSE || e.type == ElementType.ARC ||
        e.type == ElementType.RECTANGLE || e.type == ElementType.DIAMOND

    private fun idsDe(escena: Scene, que: String?, ctx: Contexto, num: Numeracion, menos: String): List<String>? {
        val q = que ?: return null
        val ids = resolver(escena, q, ctx, num).ids.filter { it != menos }
        if (ids.isEmpty()) mal("«$q» es la misma linea: hace falta otra figura")
        return ids
    }

    private fun lista(ids: List<String>?, num: Numeracion) = ids?.joinToString(", ") { "#${num.numero(it)}" } ?: "nada"

    /** Las paredes de un corte: lo que se ve (sin los marcos), o solo [contra]. */
    private fun paredes(escena: Scene, id: String, contra: List<String>?) =
        escena.visibleConReferencias.filter { it.id != id && it.type != ElementType.FRAME && (contra == null || it.id in contra) }

    private fun cortar(escena: Scene, e: Pedido, ctx: Contexto, num: Numeracion): Pair<Scene, Hecho> {
        val id = uno(escena, e.texto("que") ?: "", ctx, num, "una linea, flecha, trazo, circulo o rectangulo" to ::seRecorta)
        val contra = idsDe(escena, e.texto("con"), ctx, num, id)
        val victima = escena.elements.first { it.id == id }
        val otros = paredes(escena, id, contra)
        val p = punto(e, "punto", ctx) ?: run {
            if (!victima.isLinear) mal("para cortar un circulo o un rectangulo di el «punto» del trozo que se quita")
            val cortes = cortesDe(victima, otros)
            if (cortes.isEmpty()) mal("#${num.numero(id)} no se cruza con ${lista(contra, num)}: no hay donde cortarla (usa extender_linea si se queda corta)")
            val camino = recorridoDe(victima)
            val total = camino.zipWithNext { a, b -> hypot(b.x - a.x, b.y - a.y) }.sum()
            val primero = cortes.first(); val ultimo = cortes.last()
            val inicio = primero; val fin = total - ultimo
            val d = when (e.texto("quitar") ?: "corto") {
                "inicio" -> primero / 2
                "fin" -> (ultimo + total) / 2
                "medio" -> if (cortes.size >= 2) (cortes[0] + cortes[1]) / 2 else mal("para quitar el medio hacen falta dos cruces")
                "largo" -> if (inicio >= fin) primero / 2 else (ultimo + total) / 2
                else -> if (inicio <= fin) primero / 2 else (ultimo + total) / 2
            }
            puntoDelRecorrido(camino, d) ?: mal("no tiene recorrido")
        }
        val trozos = recortarEn(victima, otros, p) ?: mal("no hay nada que cortar de #${num.numero(id)} ahi")
        val elementos = escena.elements.flatMap {
            when {
                it.id != id -> listOf(it)
                trozos.isEmpty() -> listOf(it.copy(isDeleted = true).touched())
                else -> trozos
            }
        }
        val s = escena.copy(elements = elementos)
        val ids = (listOf(id) + trozos.map { it.id }).distinct().filter { i -> s.elements.any { it.id == i && !it.isDeleted } }
        return s to hecho(s, "cortada", ids, ctx, num)
    }

    private fun puntoDelRecorrido(camino: List<Pt>, d: Double): Pt? {
        if (camino.size < 2) return null
        var resto = d
        for ((a, b) in camino.zipWithNext()) {
            val l = hypot(b.x - a.x, b.y - a.y)
            if (resto <= l) { val t = if (l > 0) resto / l else 0.0; return Pt(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t) }
            resto -= l
        }
        return camino.last()
    }

    private fun extender(escena: Scene, e: Pedido, ctx: Contexto, num: Numeracion): Pair<Scene, Hecho> {
        val id = uno(escena, e.texto("que") ?: "", ctx, num, "una linea o flecha" to { x: Element -> x.isLinear || x.type == ElementType.ARC })
        val hasta = idsDe(escena, e.texto("hasta"), ctx, num, id)
        val victima = escena.elements.first { it.id == id }
        val camino = recorridoDe(victima)
        if (camino.size < 2) mal("no tiene puntas")
        val otros = paredes(escena, id, hasta)
        fun intentar(punta: Pt): Element? = if (victima.type == ElementType.ARC) extenderArco(victima, otros, punta) else extenderEn(victima, otros, punta)
        val estirada = when (e.texto("extremo")) {
            "inicio" -> intentar(camino.first())
            "fin" -> intentar(camino.last())
            // Sin decir cuál: primero el fin y, si no topa con nada, el inicio.
            else -> intentar(camino.last()) ?: intentar(camino.first())
        } ?: mal("#${num.numero(id)} no topa con ${lista(hasta, num)} en esa direccion: no hay hasta donde extenderla")
        val s = escena.copy(elements = escena.elements.map { if (it.id == id) estirada else it })
        return s to hecho(s, "extendida", listOf(id), ctx, num)
    }

    // -------------------------------------------------------------------------------- imágenes

    private fun imagen(escena: Scene, e: Pedido, ctx: Contexto, num: Numeracion, an: Anfitrion): Pair<Scene, Hecho> {
        return when (e.texto("accion") ?: "pegar") {
            "pegar" -> {
                val (conLaFoto, el) = an.imagenNueva(escena, e.texto("imagen") ?: "")
                val w0 = max(el.width, 1.0); val h0 = max(el.height, 1.0)
                val caja = e.numeros("caja").orEmpty()
                val vivos = escena.elements.filter { !it.isDeleted }
                val (x, y) = if (caja.size >= 2) ctx.aEscena(caja[0], caja[1]).let { it.x to it.y }
                else if (vivos.isNotEmpty()) vivos.map(::getElementBounds).let { b -> b.maxOf { it.x2 } + 40 to b.minOf { it.y1 } }
                else ctx.marco.x + 40 to ctx.marco.y + 40
                val (w, h) = when {
                    caja.size >= 4 && caja[2] > 1 && caja[3] > 1 -> caja[2] to caja[3]
                    caja.size >= 3 && caja[2] > 1 -> caja[2] to caja[2] * h0 / w0
                    else -> { val k = min(400.0 / max(w0, h0), 1.0); w0 * k to h0 * k }
                }
                val nuevo = el.copy(x = x, y = y, width = w, height = h)
                val s = conLaFoto.copy(elements = conLaFoto.elements + nuevo)
                s to hecho(s, "imagen pegada", listOf(nuevo.id), ctx, num)
            }
            "recortar" -> {
                fun esImagen(x: Element) = x.type == ElementType.IMAGE
                val id = e.texto("que")?.let { uno(escena, it, ctx, num, "una imagen" to ::esImagen) }
                    ?: ctx.ultimos.lastOrNull { i -> escena.elements.any { it.id == i && esImagen(it) && !it.isDeleted } }
                    ?: uno(escena, "imagen", ctx, num)
                val v = e.numeros("conservar")
                val lado = e.texto("quitar")
                val conservar = when {
                    v != null && v.size >= 4 -> { val k = if (v.take(4).any { it > 1.0 }) 100.0 else 1.0; DoubleArray(4) { v[it] / k } }
                    lado != null -> {
                        var f = e.numero("cuanto") ?: 0.5
                        if (f > 1.0) f /= 100.0
                        f = f.coerceIn(0.01, 0.99)
                        when (lado) {
                            "arriba" -> doubleArrayOf(0.0, f, 1.0, 1.0 - f)
                            "abajo" -> doubleArrayOf(0.0, 0.0, 1.0, 1.0 - f)
                            "izquierda" -> doubleArrayOf(f, 0.0, 1.0 - f, 1.0)
                            else -> doubleArrayOf(0.0, 0.0, 1.0 - f, 1.0)
                        }
                    }
                    else -> mal("para recortar di «conservar» [x, y, ancho, alto] en fracciones, o «quitar» un lado")
                }
                val el = escena.elements.first { it.id == id }
                val natural = el.fileId?.let { an.tamanoDe(it) }?.let { it.first.toDouble() to it.second.toDouble() }
                val recortada = recortarImagen(el, conservar, natural) ?: mal("ese recorte no deja nada: «conservar» va en fracciones de 0 a 1 y no puede ser vacio")
                val s = escena.copy(elements = escena.elements.map { if (it.id == id) recortada else it })
                s to hecho(s, "imagen recortada", listOf(id), ctx, num)
            }
            else -> mal("accion desconocida «${e.texto("accion")}»")
        }
    }

    /**
     * **Deja de [e] solo el trozo [conservar]** = `[x, y, ancho, alto]` en fracciones de lo que se ve
     * (PC `recorte_imagen::recortar_imagen`): el `crop` de Excalidraw en píxeles del original; recortar
     * dos veces compone, y lo que queda no se mueve. Null si no es una imagen o el trozo es vacío.
     */
    fun recortarImagen(e: Element, conservar: DoubleArray, natural: Pair<Double, Double>?): Element? {
        if (e.type != ElementType.IMAGE) return null
        val fx = conservar[0].coerceIn(0.0, 1.0); val fy = conservar[1].coerceIn(0.0, 1.0)
        val fw = min(conservar[2], 1.0 - fx); val fh = min(conservar[3], 1.0 - fy)
        if (listOf(fx, fy, fw, fh).any { !it.isFinite() } || fw < 0.01 || fh < 0.01) return null
        if (e.width <= 0 || e.height <= 0) return null
        val r = e.crop
        val (bx, by, bw, bh) = if (r != null && r.width > 0 && r.height > 0 && r.naturalWidth > 0 && r.naturalHeight > 0) listOf(r.x, r.y, r.width, r.height)
        else { val (nw, nh) = natural?.takeIf { it.first > 0 && it.second > 0 } ?: (e.width to e.height); listOf(0.0, 0.0, nw, nh) }
        val (nw, nh) = if (r != null && r.width > 0 && r.naturalWidth > 0) r.naturalWidth to r.naturalHeight
        else natural?.takeIf { it.first > 0 && it.second > 0 } ?: (e.width to e.height)
        val crop = Crop(bx + fx * bw, by + fy * bh, fw * bw, fh * bh, nw, nh)
        val cxViejo = e.x + e.width / 2; val cyViejo = e.y + e.height / 2
        val ancho = e.width * fw; val alto = e.height * fh
        var cx = e.x + e.width * fx + ancho / 2; var cy = e.y + e.height * fy + alto / 2
        if (e.angle != 0.0) {
            val dx = cx - cxViejo; val dy = cy - cyViejo
            cx = cxViejo + dx * cos(e.angle) - dy * sin(e.angle); cy = cyViejo + dx * sin(e.angle) + dy * cos(e.angle)
        }
        return e.copy(crop = crop, x = cx - ancho / 2, y = cy - alto / 2, width = ancho, height = alto).touched()
    }
}
