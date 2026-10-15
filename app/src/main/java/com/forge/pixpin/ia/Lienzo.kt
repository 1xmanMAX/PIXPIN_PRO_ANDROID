package com.forge.pixpin.ia

import com.forge.pixpin.motor.Element
import com.forge.pixpin.motor.ExcalidrawJson
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * **De una llamada validada a elementos del lienzo, en formato Excalidraw** (`lienzo.rs` del PC).
 *
 * Excalidraw es lo que ya comparten el lienzo del PC y el del teléfono: lo que dibuja el asistente
 * sale como cualquier otro trazo —se mueve, se cambia de color, se exporta y viaja—. Mismos ids
 * que el PC (`ia-<semilla hex>-<n>`), mismos grupos y `customData.origen = "pixpin-ia"`; la
 * única diferencia es la que ya hace el PC al mandar al móvil: los puntos van como `{x, y}` y no
 * como `[x, y]` (`para_el_movil.rs`).
 *
 * Las coordenadas del modelo son de un lienzo de 800×600; aquí solo se corren a `origen` (donde
 * mira el usuario, o a la derecha de lo que ya había). Sin Android: probado en la JVM leyendo el
 * resultado con el mismo [ExcalidrawJson] del lienzo.
 */
object Lienzo {
    /** Colores de las series, de la paleta de Excalidraw. */
    val SERIES = listOf("#1971c2", "#e03131", "#2f9e44", "#f08c00", "#6741d9", "#0c8599", "#c2255c", "#868e96")

    private const val MARGEN = 60.0
    private const val NEGRO = "#1e1e1e"

    /** Fábrica de elementos con identificadores únicos y estables (`Fabrica` del PC). */
    internal class Fabrica(val semilla: Long, val ox: Double, val oy: Double) {
        var n = 0L
        val salida = mutableListOf<MutableMap<String, JsonElement>>()
        var grupo: String? = null
        private val hex = java.lang.Long.toHexString(semilla)

        fun nombreDeGrupo(que: String): String { n++; return "ia-$hex-$que-$n" }
        fun id(): String { n++; return "ia-$hex-$n" }

        fun base(tipo: String, x: Double, y: Double, ancho: Double, alto: Double, trazo: String, relleno: String?): MutableMap<String, JsonElement> {
            val id = id()
            return linkedMapOf(
                "id" to JsonPrimitive(id),
                "type" to JsonPrimitive(tipo),
                "x" to JsonPrimitive(ox + x),
                "y" to JsonPrimitive(oy + y),
                "width" to JsonPrimitive(ancho),
                "height" to JsonPrimitive(alto),
                "angle" to JsonPrimitive(0),
                "strokeColor" to JsonPrimitive(trazo),
                "backgroundColor" to JsonPrimitive(relleno ?: "transparent"),
                "fillStyle" to JsonPrimitive("solid"),
                "strokeWidth" to JsonPrimitive(2),
                "strokeStyle" to JsonPrimitive("solid"),
                "roughness" to JsonPrimitive(1),
                "opacity" to JsonPrimitive(100),
                // Como `wrapping_add(n) % 2_000_000_000` del PC, sin pasar de Int.
                "seed" to JsonPrimitive(java.lang.Long.remainderUnsigned(semilla + n, 2_000_000_000L).toInt()),
                "version" to JsonPrimitive(1),
                "isDeleted" to JsonPrimitive(false),
                "groupIds" to JsonArray(listOfNotNull(grupo).map { JsonPrimitive(it) }),
                "boundElements" to JsonNull,
                "locked" to JsonPrimitive(false),
                "customData" to buildJsonObject { put("origen", "pixpin-ia") }
            )
        }

        fun caja(tipo: String, c: DoubleArray, trazo: String, relleno: String?) {
            salida += base(tipo, c[0], c[1], c[2], c[3], trazo, relleno)
        }

        private fun pt(x: Double, y: Double) = buildJsonObject { put("x", x); put("y", y) }

        /** Una polilínea; los puntos, relativos al primero (como los quiere Excalidraw). */
        fun trazo(tipo: String, puntos: List<Pair<Double, Double>>, color: String, relleno: String?) {
            val (x0, y0) = puntos[0]
            var minx = x0; var miny = y0; var maxx = x0; var maxy = y0
            for ((x, y) in puntos) { minx = min(minx, x); miny = min(miny, y); maxx = max(maxx, x); maxy = max(maxy, y) }
            val e = base(tipo, x0, y0, maxx - minx, maxy - miny, color, relleno)
            e["points"] = JsonArray(puntos.map { (x, y) -> pt(x - x0, y - y0) })
            e["startArrowhead"] = JsonNull
            e["endArrowhead"] = if (tipo == "arrow") JsonPrimitive("arrow") else JsonNull
            salida += e
        }

        /** Una mancha rellena (`pixpin-region`, la del bote): el polígono que rellenan los dos lienzos. */
        fun region(contorno: List<Pair<Double, Double>>, trazo: String, relleno: String, huecos: List<List<Pair<Double, Double>>> = emptyList()) {
            if (contorno.size < 3) return
            trazo("line", contorno, trazo, relleno)
            val e = salida.last()
            e["type"] = JsonPrimitive("pixpin-region")
            e.remove("startArrowhead"); e.remove("endArrowhead")
            val (x0, y0) = contorno[0]
            e["huecos"] = JsonArray(huecos.map { h -> JsonArray(h.map { (x, y) -> pt(x - x0, y - y0) }) })
        }

        fun texto(x: Double, y: Double, texto: String, tam: Double, color: String, alinear: String) {
            val ancho = tam * 0.6 * max(1, texto.codePointCount(0, texto.length))
            val alto = tam * 1.25
            val xx = when (alinear) { "center" -> x - ancho / 2; "right" -> x - ancho; else -> x }
            val e = base("text", xx, y, ancho, alto, color, null)
            e["text"] = JsonPrimitive(texto)
            e["originalText"] = JsonPrimitive(texto)
            e["fontSize"] = JsonPrimitive(tam)
            // 1 = Virgil, la letra a mano de Excalidraw.
            e["fontFamily"] = JsonPrimitive(1)
            e["textAlign"] = JsonPrimitive(alinear)
            e["verticalAlign"] = JsonPrimitive("top")
            e["lineHeight"] = JsonPrimitive(1.25)
            e["containerId"] = JsonNull
            salida += e
        }

        /** Un trazo a mano alzada (`freedraw`) con la presión simulada, y su material. */
        fun lapiz(puntos: List<Pair<Double, Double>>, color: String, material: String?, dureza: String?) {
            if (puntos.size < 2) return
            trazo("freedraw", puntos, color, null)
            val e = salida.last()
            e.remove("startArrowhead"); e.remove("endArrowhead")
            e["pressures"] = JsonArray(emptyList())
            e["simulatePressure"] = JsonPrimitive(true)
            e["roughness"] = JsonPrimitive(0)
            material?.let { e["material"] = JsonPrimitive(it) }
            dureza?.takeIf { it != "HB" }?.let { e["dureza"] = JsonPrimitive(it) }
        }

        /** Pone el estilo pedido en los elementos desde [desde]. */
        fun estilo(desde: Int, est: EstiloFigura) {
            for (v in salida.drop(desde)) {
                est.grosor?.let { v["strokeWidth"] = JsonPrimitive(it) }
                est.trazo?.let { v["strokeStyle"] = JsonPrimitive(it) }
                // El lienzo del teléfono guarda opacidad y rugosidad como enteros.
                est.opacidad?.let { v["opacity"] = JsonPrimitive(Math.round(it).toInt()) }
                est.relleno?.let { v["fillStyle"] = JsonPrimitive(it) }
                est.rugosidad?.let { v["roughness"] = JsonPrimitive(Math.round(it).toInt()) }
                if ((v["type"] as? JsonPrimitive)?.content != "text") {
                    est.material?.let { v["material"] = JsonPrimitive(it) }
                    est.dureza?.takeIf { it != "HB" }?.let { v["dureza"] = JsonPrimitive(it) }
                }
            }
        }
    }

    /**
     * **Los elementos de una llamada**, en JSON de Excalidraw. El OCR no dibuja: su resultado es
     * texto. [origen]: dónde cae el (0,0) del modelo. [semilla]: distinta en cada llamada (la hora
     * vale), para que los ids no choquen con los de otra respuesta.
     */
    fun elementos(llamada: Llamada, origen: Pair<Double, Double>, semilla: Long): List<JsonObject> {
        val f = Fabrica(semilla, origen.first, origen.second)
        when (llamada) {
            // Por capas: las de capa mayor, encima. Estable: a igual capa, el orden de la lista.
            is Llamada.Dibujar -> llamada.figuras.sortedBy { it.capa }.forEach { figura(f, it) }
            is Llamada.Grafica -> grafica(f, llamada.grafica)
            is Llamada.DibujoSvg -> Svg.elementos(f, llamada.dibujo)
            else -> {}
        }
        return f.salida.map { JsonObject(it) }
    }

    /**
     * **Lo calcado, al lienzo** (`elementos_de_calcado` del PC): las manchas como `pixpin-region`
     * con sus agujeros, las líneas como `line` y el fondo como un rectángulo lleno; todo en un grupo,
     * sin rugosidad, y las manchas con borde 2 del mismo color (tapa las rendijas).
     */
    fun elementosDeCalcado(c: Calcar.Calcado, origen: Pair<Double, Double>, semilla: Long): List<JsonObject> {
        val f = Fabrica(semilla, origen.first, origen.second)
        f.grupo = "ia-${java.lang.Long.toHexString(semilla)}-calco"
        fun pares(v: List<Trazado.P>) = v.map { it.x to it.y }
        for (t in c.trazos) {
            val antes = f.salida.size
            when (t) {
                is Calcar.Trazo.Fondo -> f.caja("rectangle", t.caja, t.color, t.color)
                is Calcar.Trazo.Mancha -> f.region(pares(t.contorno), t.color, t.color, t.huecos.map(::pares))
                is Calcar.Trazo.Linea -> f.trazo("line", pares(t.puntos), t.color, null)
            }
            if (f.salida.size > antes) {
                val e = f.salida.last()
                e["roughness"] = JsonPrimitive(0)
                if (t !is Calcar.Trazo.Linea) e["strokeWidth"] = JsonPrimitive(2)
            }
        }
        return f.salida.map { JsonObject(it) }
    }

    /** Los mismos elementos, ya como [Element] del motor del lienzo (lo que lee `ExcalidrawStore`). */
    fun aElementos(json: List<JsonObject>): List<Element> =
        ExcalidrawJson.decodeFromJsonElement(ListSerializer(Element.serializer()), JsonArray(json))

    private fun figura(f: Fabrica, fig: Figura) {
        val trazo = fig.color
        val relleno = fig.relleno
        val conRotulo = fig.tipo != TipoFigura.TEXTO && fig.texto.isNotEmpty()
        val hex = java.lang.Long.toHexString(f.semilla)
        // El grupo que pidió el modelo; si no, una figura con su rótulo va agrupada con él.
        f.grupo = when {
            fig.grupo != null -> "ia-$hex-g-${fig.grupo}"
            conRotulo -> "ia-$hex-r-${f.n + 1}"
            else -> null
        }
        val caja = doubleArrayOf(fig.x, fig.y, fig.ancho, fig.alto)
        val puntos = fig.puntos.ifEmpty { listOf(fig.x to fig.y, fig.x2 to fig.y2) }
        val desde = f.salida.size
        when (fig.tipo) {
            TipoFigura.RECTANGULO -> f.caja("rectangle", caja, trazo, relleno)
            TipoFigura.LAPIZ -> f.lapiz(puntos, trazo, fig.estilo.material, fig.estilo.dureza)
            TipoFigura.PUNTO -> {
                f.caja("pixpin-point", doubleArrayOf(fig.x, fig.y, 0.0, 0.0), trazo, trazo)
                f.salida.last().let { it["text"] = JsonPrimitive(fig.texto); it["roughness"] = JsonPrimitive(0) }
            }
            TipoFigura.INSTRUMENTO -> fig.instrumento?.let { instrumento(f, caja, it, trazo) }
            TipoFigura.ELIPSE -> f.caja("ellipse", caja, trazo, relleno)
            TipoFigura.ROMBO -> f.caja("diamond", caja, trazo, relleno)
            TipoFigura.LINEA -> f.trazo("line", puntos, trazo, null)
            TipoFigura.FLECHA -> f.trazo("arrow", puntos, trazo, null)
            // Un texto que se quedó sin texto no se pinta.
            TipoFigura.TEXTO -> if (fig.texto.isNotEmpty()) f.texto(fig.x, fig.y, fig.texto, fig.tamLetra, trazo, "left")
            TipoFigura.CURVA -> {
                f.trazo("line", puntos, trazo, null)
                // `roundness` en una línea: la curva suave que pasa por sus puntos.
                f.salida.last()["roundness"] = buildJsonObject { put("type", 2) }
            }
            // Lleno: la mancha del bote. Vacío: una línea cerrada.
            TipoFigura.POLIGONO -> if (relleno != null) f.region(puntos, trazo, relleno)
            else f.trazo("line", puntos + puntos[0], trazo, null)
        }
        f.estilo(desde, fig.estilo)
        if (conRotulo && fig.tipo != TipoFigura.PUNTO && fig.tipo != TipoFigura.INSTRUMENTO) {
            when (fig.tipo) {
                // Encima de la flecha o la línea, en su punto medio.
                TipoFigura.LINEA, TipoFigura.FLECHA, TipoFigura.CURVA -> {
                    val a = puntos.first(); val b = puntos.last()
                    val tam = min(fig.tamLetra, 20.0)
                    f.texto((a.first + b.first) / 2, (a.second + b.second) / 2 - tam * 1.25 - 4, fig.texto, tam, NEGRO, "center")
                }
                // Dentro, centrado, y que quepa.
                else -> {
                    val letras = max(1, fig.texto.codePointCount(0, fig.texto.length)).toDouble()
                    val tam = max(min(fig.tamLetra, fig.ancho * 0.9 / (0.6 * letras)), 8.0)
                    val color = if (relleno != null && esOscuro(relleno)) "#ffffff" else NEGRO
                    f.texto(fig.x + fig.ancho / 2, fig.y + fig.alto / 2 - tam * 1.25 / 2, fig.texto, tam, color, "center")
                }
            }
        }
        f.grupo = null
    }

    /**
     * **El instrumento de graficar con los puntos de su tabla.** El PC lo pide a su motor
     * (`instrumento::elemento_de_fabrica`); aquí son los mismos tipos del lienzo del teléfono
     * (`pixpin-axes`, `pixpin-number-line`, `pixpin-space`) con su unidad, cada punto de la tabla
     * como un punto con su letra donde cae ([com.forge.pixpin.motor.puntoDelPlano]), y la tabla
     * escrita al lado. Todo en un grupo. En el espacio de tres ejes no se clavan los puntos (su
     * proyección depende del giro): va solo la tabla.
     */
    private fun instrumento(f: Fabrica, c: DoubleArray, p: PedidoInstrumento, color: String) {
        val antes = f.grupo
        if (f.grupo == null) f.grupo = f.nombreDeGrupo("instrumento")
        val tipo = when (p.ejes) { 1 -> "pixpin-number-line"; 3 -> "pixpin-space"; else -> "pixpin-axes" }
        val ancho = max(c[2], 40.0)
        val alto = if (p.ejes == 1) max(c[3], com.forge.pixpin.motor.ALTO_DE_LA_RECTA) else max(c[3], 40.0)
        val e = f.base(tipo, c[0], c[1], ancho, alto, color, null)
        e["roughness"] = JsonPrimitive(0)
        e["strokeWidth"] = JsonPrimitive(1.5)
        e["unidad"] = JsonPrimitive(p.unidad)
        e["pasoDeNumeros"] = JsonPrimitive(com.forge.pixpin.motor.pasoUtil(1.0, p.unidad, 34.0))
        e["pasoDeCuadros"] = JsonPrimitive(1.0)
        f.salida += e
        val letras = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        if (p.ejes != 3) for ((k, xy) in p.tabla.withIndex()) {
            // El origen del plano y de la recta es el centro de su caja (`origenDelPlano`).
            val px = c[0] + ancho / 2 + xy.first * p.unidad
            val py = c[1] + alto / 2 - (if (p.ejes == 1) 0.0 else xy.second) * p.unidad
            val punto = f.base("pixpin-point", px, py, 0.0, 0.0, color, color)
            punto["text"] = JsonPrimitive(letras[k % 26].toString())
            punto["roughness"] = JsonPrimitive(0)
            punto["etiquetaAngulo"] = JsonPrimitive(-Math.PI / 4)
            punto["etiquetaRadio"] = JsonPrimitive(14.0)
            f.salida += punto
        }
        if (p.tabla.isNotEmpty()) {
            val filas = listOf("   x   y") + p.tabla.mapIndexed { k, (x, y) -> "${letras[k % 26]}  ${escrito(x)}  ${escrito(y)}" }
            f.texto(c[0] + ancho + 24, c[1], filas.joinToString("\n"), 16.0, color, "left")
            f.salida.last()["height"] = JsonPrimitive(16.0 * 1.25 * filas.size)
        }
        f.grupo = antes
    }

    private fun escrito(v: Double): String =
        if (abs(v - Math.rint(v)) < 1e-9) Math.rint(v).toLong().toString()
        else (Math.round(v * 100.0) / 100.0).toString().trimEnd('0').trimEnd('.').replace('.', ',')

    /** Si un `#rrggbb` es oscuro (para escribir en blanco encima). */
    internal fun esOscuro(hex: String): Boolean {
        val h = hex.trimStart('#')
        fun c(i: Int) = h.getOrNull(i)?.let { h.substring(i, min(i + 2, h.length)).toIntOrNull(16) }?.toDouble() ?: 255.0
        return 0.299 * c(0) + 0.587 * c(2) + 0.114 * c(4) < 110.0
    }

    private fun grafica(f: Fabrica, g: Grafica) {
        val arriba = if (g.titulo.isEmpty()) MARGEN else {
            f.texto(Herramientas.ANCHO_LIENZO / 2, 16.0, g.titulo, 28.0, NEGRO, "center")
            MARGEN + 20
        }
        val x0 = MARGEN + 20; val x1 = Herramientas.ANCHO_LIENZO - MARGEN
        val y0 = arriba; val y1 = Herramientas.ALTO_LIENZO - MARGEN
        when (g.tipo) {
            TipoGrafica.PASTEL -> pastel(f, g, (x0 + x1) / 2, (y0 + y1) / 2, min((y1 - y0) / 2, 220.0))
            TipoGrafica.BARRAS, TipoGrafica.LINEAS -> {
                val minimo = g.valores.fold(0.0, ::min)
                val maximo = g.valores.fold(0.0, ::max)
                val rango = if (maximo - minimo > 0) maximo - minimo else 1.0
                fun aY(v: Double) = y1 - (v - minimo) / rango * (y1 - y0)
                val cero = aY(0.0)
                // Ejes.
                f.trazo("line", listOf(x0 to y0, x0 to y1), NEGRO, null)
                f.trazo("line", listOf(x0 to cero, x1 to cero), NEGRO, null)
                f.texto(x0 - 8, aY(maximo) - 10, bonito(maximo), 16.0, NEGRO, "right")
                if (minimo < 0) f.texto(x0 - 8, aY(minimo) - 10, bonito(minimo), 16.0, NEGRO, "right")
                val paso = (x1 - x0) / g.valores.size
                val puntos = mutableListOf<Pair<Double, Double>>()
                for ((i, v) in g.valores.withIndex()) {
                    val centro = x0 + paso * (i + 0.5)
                    val y = aY(v)
                    if (g.tipo == TipoGrafica.BARRAS) {
                        val ancho = paso * 0.6
                        val color = SERIES[i % SERIES.size]
                        f.caja("rectangle", doubleArrayOf(centro - ancho / 2, min(y, cero), ancho, max(abs(cero - y), 1.0)), color, color)
                    } else puntos += centro to y
                    f.texto(centro, min(y, cero) - 24, bonito(v), 16.0, NEGRO, "center")
                    f.texto(centro, y1 + 8, g.etiquetas[i], 16.0, NEGRO, "center")
                }
                if (puntos.size >= 2) f.trazo("line", puntos, SERIES[0], null)
                else puntos.firstOrNull()?.let { (x, y) -> f.caja("ellipse", doubleArrayOf(x - 5, y - 5, 10.0, 10.0), SERIES[0], SERIES[0]) }
            }
        }
    }

    private fun pastel(f: Fabrica, g: Grafica, cx: Double, cy: Double, r: Double) {
        val total = g.valores.sum()
        var angulo = -Math.PI / 2
        for ((i, v) in g.valores.withIndex()) {
            val barrido = v / total * 2 * Math.PI
            if (barrido <= 0) continue
            val color = SERIES[i % SERIES.size]
            // La cuña como polígono cerrado: centro, arco muestreado, centro.
            val pasos = max(Math.ceil(barrido / 0.1).toInt(), 2)
            val puntos = mutableListOf(cx to cy)
            for (k in 0..pasos) {
                val a = angulo + barrido * k / pasos
                puntos += (cx + r * cos(a)) to (cy + r * sin(a))
            }
            puntos += cx to cy
            f.trazo("line", puntos, "#ffffff", color)
            val medio = angulo + barrido / 2
            val porcentaje = v / total * 100
            f.texto(cx + (r + 30) * cos(medio), cy + (r + 30) * sin(medio) - 10,
                "${g.etiquetas[i]} (${Math.round(porcentaje)}%)", 16.0, NEGRO, "center")
            angulo += barrido
        }
    }

    /** Un número sin decimales de sobra. */
    internal fun bonito(v: Double): String =
        if (abs(v - Math.rint(v)) < 1e-9) Math.rint(v).toLong().toString()
        else String.format(java.util.Locale.ROOT, "%.2f", v).trimEnd('0').trimEnd('.')

    /**
     * **Dónde poner lo nuevo sin tapar lo que había** (`meter_al_lado` del PC): a la derecha de
     * todo lo que hay, con un margen, a la altura de lo más alto. Lienzo vacío: en el (0, 0).
     */
    fun alLado(existentes: List<Element>, margen: Double = 80.0): Pair<Double, Double> {
        val vivos = existentes.filter { !it.isDeleted }
        if (vivos.isEmpty()) return 0.0 to 0.0
        val derecha = vivos.maxOf { it.x + max(it.width, 0.0) }
        val arriba = vivos.minOf { it.y + min(it.height, 0.0) }
        return (derecha + margen) to arriba
    }
}

/** Para comparar con el PC: los puntos `{x, y}` de vuelta a `[x, y]`. */
internal fun puntosComoElPc(e: JsonObject): JsonObject {
    fun pares(pts: JsonArray) = buildJsonArray {
        for (p in pts) {
            val o = p as JsonObject
            add(buildJsonArray { add(o["x"]!!); add(o["y"]!!) })
        }
    }
    var r = e
    (e["points"] as? JsonArray)?.let { r = JsonObject(r + ("points" to pares(it))) }
    (e["huecos"] as? JsonArray)?.let { h -> r = JsonObject(r + ("huecos" to JsonArray(h.map { pares(it as JsonArray) }))) }
    return r
}
