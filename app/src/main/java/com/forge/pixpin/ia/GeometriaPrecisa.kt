package com.forge.pixpin.ia

import com.forge.pixpin.motor.Arrowhead
import com.forge.pixpin.motor.CierreArco
import com.forge.pixpin.motor.Degradado
import com.forge.pixpin.motor.Element
import com.forge.pixpin.motor.ElementType
import com.forge.pixpin.motor.ItemStyle
import com.forge.pixpin.motor.MarcaAngulo
import com.forge.pixpin.motor.Pt
import com.forge.pixpin.motor.StrokeStyle
import com.forge.pixpin.motor.TipoDegradado
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * **Geometría exacta de lámina** para el asistente (PC `edicion/hacer.rs::geometria` y `de_lamina`,
 * 15-18 oct; esquema `geometria_precisa` del catálogo del PC). Con las piezas del motor del lienzo
 * ([com.forge.pixpin.motor.arcoExacto], [com.forge.pixpin.motor.marcaDeAngulo]…), las mismas que las
 * herramientas Arco y Ángulo: recta (con flecha), círculo, arco, semicircunferencia, cuadrante, sector
 * y segmento (rellenos, también con degradado), marca de ángulo y ángulo recto, punto con su letra,
 * perpendicular y paralela.
 *
 * Las coordenadas del modelo van en el marco de 800×600 que empieza en [marco] de la escena; «entre» y
 * «a» nombran figuras que ya están por su `#n` (el orden del resumen del lienzo, desde 1). Ángulos en
 * grados de matemáticas: 0 a la derecha, 90 arriba (en pantalla la y baja: el signo cambia). Sin Android.
 */
object GeometriaPrecisa {
    class Fallo(m: String) : Exception(m)

    private fun mal(m: String): Nothing = throw Fallo(m)

    /** Lo pedido: los argumentos del modelo, con acceso cómodo. */
    class Pedido(val args: JsonObject) {
        fun texto(k: String) = (args[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
        fun numero(k: String) = (args[k] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.content.trim().toDoubleOrNull() }
        fun numeros(k: String) = (args[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }
    }

    /** Las figuras visibles del lienzo en el orden en que se numeran (`#1`, `#2`…). */
    fun numeradas(escena: List<Element>) = escena.filter { !it.isDeleted }

    /**
     * **Hace la figura** y devuelve los elementos nuevos (ya en coordenadas de la escena). [existentes]:
     * lo que hay en el lienzo destino (para «entre», «a» y las letras libres).
     */
    fun hacer(args: JsonObject, existentes: List<Element>, marco: Pt, estiloBase: ItemStyle = ItemStyle()): List<Element> {
        val p = Pedido(args)
        val figura = p.texto("figura") ?: "recta"
        fun aEscena(x: Double, y: Double) = Pt(marco.x + x, marco.y + y)
        fun punto(k: String) = p.numeros(k)?.takeIf { it.size >= 2 }?.let { aEscena(it[0], it[1]) }
        val puntos = p.numeros("puntos")?.chunked(2)?.filter { it.size == 2 }?.map { aEscena(it[0], it[1]) }.orEmpty()
        val estilo = estiloDe(p, estiloBase)
        val vivos = numeradas(existentes)
        fun ref(t: String): Element {
            val n = Regex("#(\\d+)").find(t)?.groupValues?.get(1)?.toIntOrNull() ?: mal("«$t»: nombra la figura por su #n (del resumen del lienzo)")
            return vivos.getOrNull(n - 1) ?: mal("no hay ninguna figura #$n en el lienzo")
        }
        val nuevo: Element = when (figura) {
            "recta" -> {
                val (a, b) = when {
                    puntos.size >= 2 -> puntos[0] to puntos[1]
                    punto("desde") != null -> {
                        val a = punto("desde")!!
                        val l = p.numero("longitud") ?: mal("con «desde» hace falta «longitud»")
                        val t = Math.toRadians(p.numero("angulo") ?: 0.0)
                        a to Pt(a.x + l * cos(t), a.y - l * sin(t))
                    }
                    else -> mal("la recta necesita «puntos» [x1,y1,x2,y2] o «desde» con «angulo» y «longitud»")
                }
                recta(a, b, estilo, p.texto("flecha"), p.texto("punta"))
            }
            "circulo" -> {
                val c = punto("centro"); val r = p.numero("radio")
                val (cc, rr) = when {
                    c != null && r != null -> c to r
                    puntos.size >= 3 -> {
                        val k = com.forge.pixpin.motor.circuncentro(puntos[0], puntos[1], puntos[2]) ?: mal("los tres puntos estan en linea: no hay circulo que pase por ellos")
                        k to hypot(puntos[0].x - k.x, puntos[0].y - k.y)
                    }
                    else -> mal("el circulo necesita «centro» y «radio», o tres «puntos»")
                }
                if (rr <= 0.5) mal("el radio tiene que ser mayor que 0")
                com.forge.pixpin.motor.newElement(ElementType.ELLIPSE, cc.x - rr, cc.y - rr, estilo, 2 * rr, 2 * rr)
            }
            "arco" -> if (puntos.size >= 3) {
                val a = com.forge.pixpin.motor.arcoPorTresPuntos(puntos[0], puntos[1], puntos[2]) ?: mal("los tres puntos estan en linea: no hay arco que pase por ellos")
                com.forge.pixpin.motor.arcoExacto(Pt(a[0], a[1]), a[2], a[3], a[4], estilo)
            } else {
                val c = punto("centro") ?: mal("el arco necesita «centro»")
                val r = p.numero("radio") ?: mal("el arco necesita «radio»")
                val desde = p.numero("inicio") ?: 0.0
                val hasta = p.numero("fin") ?: 180.0
                if (abs(hasta - desde) < 0.5) mal("el arco no abarca nada: «inicio» y «fin» son iguales")
                com.forge.pixpin.motor.arcoExacto(c, r, aPantalla(desde), barrido(p, desde, hasta), estilo)
            }
            "semicircunferencia" -> {
                if (puntos.size < 2) mal("la semicircunferencia necesita «puntos» [x1, y1, x2, y2] del diametro")
                val (a, b) = puntos[0] to puntos[1]
                if (hypot(a.x - b.x, a.y - b.y) < 1.0) mal("el diametro no puede medir 0")
                val c = Pt((a.x + b.x) / 2, (a.y + b.y) / 2)
                val r = hypot(a.x - c.x, a.y - c.y)
                val inicio = com.forge.pixpin.motor.anguloDe(c, a)
                val una = com.forge.pixpin.motor.arcoExacto(c, r, inicio, PI, estilo)
                val otra = com.forge.pixpin.motor.arcoExacto(c, r, inicio, -PI, estilo)
                fun medio(e: Element) = com.forge.pixpin.motor.enLaCircunferencia(c, r, (e.arcStart ?: 0.0) + (e.arcSweep ?: 0.0) / 2)
                val lado = punto("lado")
                when {
                    lado != null -> if (dist(medio(otra), lado) < dist(medio(una), lado)) otra else una
                    medio(otra).y < medio(una).y -> otra
                    else -> una
                }
            }
            "cuadrante", "sector", "segmento" -> {
                val c = punto("centro") ?: mal("el $figura necesita «centro»")
                val cierre = if (figura == "segmento") CierreArco.SEGMENTO else CierreArco.SECTOR
                val base = if (puntos.size >= 2) {
                    val (inicio, b) = com.forge.pixpin.motor.anguloMenor(c, puntos[0], puntos[1])
                    com.forge.pixpin.motor.arcoExacto(c, dist(c, puntos[0]), inicio, b, estilo)
                } else {
                    val r = p.numero("radio") ?: mal("el $figura necesita «radio» (o «puntos»)")
                    if (r <= 0.5) mal("el radio tiene que ser mayor que 0")
                    val inicio = p.numero("inicio") ?: 0.0
                    val b = if (figura == "cuadrante") {
                        val s = if (p.texto("sentido") == "horario") -1.0 else 1.0
                        -s * PI / 2
                    } else barrido(p, inicio, p.numero("fin") ?: mal("el $figura necesita «fin»"))
                    com.forge.pixpin.motor.arcoExacto(c, r, aPantalla(inicio), b, estilo)
                }
                com.forge.pixpin.motor.conCierre(base, cierre)
            }
            "marca_angulo", "angulo_recto" -> {
                val entre = p.texto("entre")
                val (v, p1, p2) = if (entre != null) cruce(entre, ::ref, punto("cerca")) else {
                    val v = punto("vertice") ?: mal("la marca necesita «entre» (dos rectas: «#3 #5») o «vertice» [x, y]")
                    if (puntos.size < 2) mal("la marca necesita «puntos» [x1, y1, x2, y2]: un punto de cada lado")
                    Triple(v, puntos[0], puntos[1])
                }
                if (dist(p1, v) < 0.5 || dist(p2, v) < 0.5) mal("los puntos de los lados no pueden ser el vertice")
                val medido = run {
                    val ax = p1.x - v.x; val ay = p1.y - v.y; val bx = p2.x - v.x; val by = p2.y - v.y
                    Math.toDegrees(acos(((ax * bx + ay * by) / (hypot(ax, ay) * hypot(bx, by)).coerceAtLeast(1e-6)).coerceIn(-1.0, 1.0)))
                }
                val pedido = p.texto("valor")
                val recto = figura == "angulo_recto" || (entre != null && abs(medido - 90) < 1.5 && (pedido == null || pedido == "griega"))
                val valor = when (pedido) {
                    "griega" -> com.forge.pixpin.motor.siguienteLetraDeAngulo(com.forge.pixpin.motor.letrasDeAnguloUsadas(existentes))
                    "medida" -> "${Math.round(medido)}°"
                    else -> pedido
                }
                val r = p.numero("radio") ?: if (recto) 14.0 else com.forge.pixpin.motor.RADIO_MARCA
                val marca = MarcaAngulo(arcos = (p.numero("arcos") ?: 1.0).toInt().coerceIn(1, 3), recto = recto, valor = if (recto) null else valor)
                val m = com.forge.pixpin.motor.marcaDeAngulo(v, p1, p2, r, marca, estilo)
                p.texto("relleno")?.let { Herramientas.color(it) }?.let { m.copy(backgroundColor = it) } ?: m
            }
            "punto" -> {
                val entre = p.texto("entre")
                val donde = if (entre != null) cruce(entre, ::ref, null).first else (punto("centro") ?: puntos.firstOrNull()
                    ?: mal("el punto necesita «entre» (dos rectas), «puntos» [x, y] o «centro»"))
                val base = com.forge.pixpin.motor.nuevoPunto(com.forge.pixpin.motor.randomId(), donde, existentes, com.forge.pixpin.motor.SerieDePunto.MAYUSCULAS, estilo, (Math.random() * 1e9).toInt())
                val letra = when (val l = p.texto("letra")) {
                    "siguiente", null -> if (l == null && entre == null) "" else base.text
                    else -> l
                }
                val conLetra = base.copy(text = letra, fontSize = p.numero("tam_letra") ?: base.fontSize)
                p.texto("letra_hacia")?.let { lado ->
                    val g = when (lado) { "derecha" -> 0.0; "abajo_derecha" -> 45.0; "abajo" -> 90.0; "abajo_izquierda" -> 135.0; "izquierda" -> 180.0; "arriba_izquierda" -> 225.0; "arriba" -> 270.0; else -> 315.0 }
                    conLetra.copy(etiquetaAngulo = Math.toRadians(g))
                } ?: conLetra
            }
            "perpendicular", "paralela" -> {
                val base = ref(p.texto("a") ?: mal("hace falta «a»: la recta que ya esta"))
                val (a, b) = extremos(base)
                val l0 = dist(a, b).coerceAtLeast(1e-6)
                val d = Pt((b.x - a.x) / l0, (b.y - a.y) / l0)
                val n = Pt(d.y, -d.x)
                if (figura == "perpendicular") {
                    val por = punto("por") ?: Pt((a.x + b.x) / 2, (a.y + b.y) / 2)
                    val t = (por.x - a.x) * d.x + (por.y - a.y) * d.y
                    val pie = Pt(a.x + d.x * t, a.y + d.y * t)
                    val l = p.numero("longitud")
                    if (dist(pie, por) > 0.5 && l == null) recta(por, pie, estilo, null, null)
                    else {
                        val ll = (l ?: (l0 / 2)).coerceAtLeast(1.0)
                        recta(Pt(pie.x + n.x * ll / 2, pie.y + n.y * ll / 2), Pt(pie.x - n.x * ll / 2, pie.y - n.y * ll / 2), estilo, null, null)
                    }
                } else {
                    val por = punto("por")
                    val k = when {
                        por != null -> (por.x - a.x) * n.x + (por.y - a.y) * n.y
                        p.numero("distancia") != null -> p.numero("distancia")!!
                        else -> mal("la paralela necesita «por» [x, y] o «distancia»")
                    }
                    recta(Pt(a.x + n.x * k, a.y + n.y * k), Pt(b.x + n.x * k, b.y + n.y * k), estilo, null, null)
                }
            }
            else -> mal("figura desconocida «$figura»")
        }
        return listOf(conRelleno(nuevo, p, figura))
    }

    // ---------------------------------------------------------------------------------- piezas

    private fun dist(a: Pt, b: Pt) = hypot(a.x - b.x, a.y - b.y)

    /** Grados de matemáticas a radianes de pantalla (la y baja). */
    private fun aPantalla(g: Double) = -Math.toRadians(g)

    /** El barrido en pantalla de [inicio] a [fin]: con «sentido», la vuelta que toca. */
    private fun barrido(p: Pedido, inicio: Double, fin: Double): Double {
        val s = p.texto("sentido")
        var d = when (s) {
            "horario" -> -Math.floorMod((inicio - fin).toLong() * 1000, 360_000L) / 1000.0
            null -> fin - inicio
            else -> ((fin - inicio) % 360 + 360) % 360
        }
        if (d == 0.0 && s != null) d = 360.0
        return -Math.toRadians(d)
    }

    private fun estiloDe(p: Pedido, base: ItemStyle): ItemStyle {
        var s = base.copy(roughness = Element.ROUGHNESS_ARCHITECT, backgroundColor = Element.TRANSPARENT, endArrowhead = null, startArrowhead = null)
        p.texto("color")?.let { Herramientas.color(it) }?.let { s = s.copy(strokeColor = it) }
        p.numero("grosor")?.let { s = s.copy(strokeWidth = it.coerceIn(0.5, 40.0)) }
        when (p.texto("trazo")) {
            "discontinuo" -> s = s.copy(strokeStyle = StrokeStyle.DASHED)
            "punteado" -> s = s.copy(strokeStyle = StrokeStyle.DOTTED)
        }
        return s
    }

    /** Raya-punto, relleno y degradado, como `rematar_estilo` y `rematar_lamina` del PC. */
    private fun conRelleno(e: Element, p: Pedido, figura: String): Element {
        var x = e
        if (p.texto("trazo")?.let { it == "raya_punto" || it == "rayapunto" } == true) x = com.forge.pixpin.motor.conRayaPunto(x, true)
        if (figura != "marca_angulo" && figura != "angulo_recto") {
            val relleno = p.texto("relleno")?.let { Herramientas.color(it) }
            if (relleno != null) {
                x = x.copy(backgroundColor = relleno, fillStyle = com.forge.pixpin.motor.FillStyle.SOLID)
                val tipo = p.texto("degradado")?.let { TipoDegradado.desdePalabra(it) }
                if (tipo != null) {
                    val hasta = p.texto("relleno_hasta")?.let { Herramientas.color(it) }
                    val d = if (hasta == null) Degradado.desdeRelleno(tipo, relleno)
                        else if (tipo == TipoDegradado.RADIAL) Degradado.radial(relleno, hasta) else Degradado.lineal(relleno, hasta)
                    x = com.forge.pixpin.motor.conDegradado(x, d)
                }
            }
        }
        return x
    }

    /** Una recta (o flecha, con «flecha») de [a] a [b]. */
    private fun recta(a: Pt, b: Pt, s: ItemStyle, flecha: String?, punta: String?): Element {
        val conFlecha = flecha != null && flecha != "ninguna"
        val cabeza = if (punta == "abierta") Arrowhead.ARROW else Arrowhead.TRIANGLE
        val tipo = if (conFlecha) ElementType.ARROW else ElementType.LINE
        val e = com.forge.pixpin.motor.newElement(tipo, a.x, a.y, s, abs(b.x - a.x), abs(b.y - a.y))
        return e.copy(
            points = listOf(Pt(0.0, 0.0), Pt(b.x - a.x, b.y - a.y)),
            startArrowhead = if (flecha == "inicio" || flecha == "ambas") cabeza else null,
            endArrowhead = if (flecha == "fin" || flecha == "ambas") cabeza else null
        )
    }

    /** Los extremos de una recta que ya está. */
    private fun extremos(e: Element): Pair<Pt, Pt> {
        val pts = e.points?.takeIf { it.size >= 2 } ?: mal("eso no es una recta")
        return Pt(e.x + pts.first().x, e.y + pts.first().y) to Pt(e.x + pts.last().x, e.y + pts.last().y)
    }

    /**
     * **El cruce de dos rectas que ya están** (PC 18-oct): el vértice exacto y un punto de cada lado —el
     * extremo de cada recta del lado de [cerca]; sin él, el más lejano—. Si los trazos no se tocan, sus
     * prolongaciones.
     */
    private fun cruce(entre: String, ref: (String) -> Element, cerca: Pt?): Triple<Pt, Pt, Pt> {
        val ids = Regex("#\\d+").findAll(entre).map { it.value }.toList()
        if (ids.size != 2) mal("«entre» tiene que nombrar dos rectas, como «#3 #5» (encaja con ${ids.size})")
        val (a1, a2) = extremos(ref(ids[0]))
        val (b1, b2) = extremos(ref(ids[1]))
        val v = interseccionDeRectas(a1, a2, b1, b2) ?: mal("esas dos rectas son paralelas: no se cruzan")
        fun lado(p: Pt, q: Pt): Pt {
            if (cerca != null) {
                // El extremo hacia el que está el punto de dentro del ángulo.
                val dp = (p.x - v.x) * (cerca.x - v.x) + (p.y - v.y) * (cerca.y - v.y)
                val dq = (q.x - v.x) * (cerca.x - v.x) + (q.y - v.y) * (cerca.y - v.y)
                val elegido = if (dp >= dq) p else q
                if (dist(elegido, v) > 0.5) return elegido
            }
            val lejos = if (dist(p, v) >= dist(q, v)) p else q
            if (dist(lejos, v) > 0.5) return lejos
            // Los dos en el vértice: un punto a lo largo de la recta.
            return Pt(v.x + (q.x - p.x), v.y + (q.y - p.y))
        }
        return Triple(v, lado(a1, a2), lado(b1, b2))
    }

    private fun interseccionDeRectas(a1: Pt, a2: Pt, b1: Pt, b2: Pt): Pt? {
        val d = (a2.x - a1.x) * (b2.y - b1.y) - (a2.y - a1.y) * (b2.x - b1.x)
        if (abs(d) < 1e-9) return null
        val t = ((b1.x - a1.x) * (b2.y - b1.y) - (b1.y - a1.y) * (b2.x - b1.x)) / d
        return Pt(a1.x + t * (a2.x - a1.x), a1.y + t * (a2.y - a1.y))
    }

    /** El resumen de las figuras con su #n y sus medidas en el marco, para que el modelo las nombre. */
    fun resumen(existentes: List<Element>, marco: Pt): String = numeradas(existentes).withIndex().joinToString("\n") { (i, e) ->
        fun r(v: Double) = Math.round(v).toString()
        val t = e.type.name.lowercase()
        val dondeTxt = e.points?.takeIf { it.size >= 2 && (e.type == ElementType.LINE || e.type == ElementType.ARROW) }?.let { pts ->
            "de (${r(e.x + pts.first().x - marco.x)},${r(e.y + pts.first().y - marco.y)}) a (${r(e.x + pts.last().x - marco.x)},${r(e.y + pts.last().y - marco.y)})"
        } ?: "caja (${r(e.x - marco.x)},${r(e.y - marco.y)},${r(e.width)},${r(e.height)})"
        "#${i + 1} $t $dondeTxt" + (e.text?.takeIf { it.isNotBlank() }?.let { " «${it.take(40)}»" } ?: "")
    }
}
