package com.forge.pixpin.ia

import com.forge.pixpin.ia.Pizarra.Alinear
import com.forge.pixpin.ia.Pizarra.Companion.AZUL
import com.forge.pixpin.ia.Pizarra.Companion.GRIS
import com.forge.pixpin.ia.Pizarra.Companion.NARANJA
import com.forge.pixpin.ia.Pizarra.Companion.ROJO
import com.forge.pixpin.ia.Pizarra.Companion.TINTA
import com.forge.pixpin.ia.Pizarra.Companion.VERDE
import com.forge.pixpin.ia.Pizarra.Companion.colorDeSerie
import com.forge.pixpin.ia.Pizarra.Companion.numero
import com.forge.pixpin.motor.Element
import com.forge.pixpin.motor.MedidaDeTexto
import com.forge.pixpin.motor.randomId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.util.TreeMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * **Diagramas de física** (`diagrama_fisica`, PC `pixpin-ia/src/fisica.rs`): plantillas con variables.
 * Una tabla ([PLANTILLAS]): cada fila es un tipo de diagrama (vectores, cuerpo libre, plano inclinado,
 * tiro parabólico, MRU/MRUV, circuito, péndulo, máquina de Atwood) con sus parámetros —unidad, valor
 * por omisión y **rango físico**— y la función que calcula y dibuja. Lo físicamente imposible se
 * devuelve al modelo con el motivo; las cuentas las hace el teléfono y vuelven como aviso.
 */
object Fisica {
    const val NOMBRE = "diagrama_fisica"
    const val G = 9.8
    const val MAX_LISTA = 8

    private fun mal(m: String) = ErrorHerramienta.Parametro(m)
    private class Imposible(m: String) : Exception(m)

    enum class Clase { NUMERO, LISTA, VECTORES, OPCION }

    data class Parametro(val nombre: String, val clase: Clase, val unidad: String, val porOmision: Double?, val min: Double, val max: Double, val abierto: Boolean, val ayuda: String = "")

    private fun num(nombre: String, unidad: String, porOmision: Double?, min: Double, max: Double, abierto: Boolean) = Parametro(nombre, Clase.NUMERO, unidad, porOmision, min, max, abierto)

    /** Una fila: lo que dibuja y calcula (sin pizarra solo calcula) y da los resultados para el modelo. */
    class Plantilla(val tipo: String, val ayuda: String, val parametros: List<Parametro>, val hacer: (Valores, Pizarra?) -> List<String>)

    private val GRAVEDAD = num("g", "m/s²", G, 0.0, 100.0, true)
    private val MASA = num("masa", "kg", 10.0, 0.0, 1e6, true)
    private val VECTORES = Parametro("vectores", Clase.VECTORES, "", null, 0.0, 1e6, false, "[{nombre, modulo, angulo°}]")
    private val FUERZAS = VECTORES.copy(nombre = "fuerzas", ayuda = "[{nombre, modulo N, angulo°}]")

    val PLANTILLAS: List<Plantilla> by lazy {
        listOf(
            Plantilla("vectores", "suma de vectores (paralelogramo o poligono)", listOf(VECTORES, Parametro("metodo", Clase.OPCION, "", null, 0.0, 0.0, false, "paralelogramo|poligono")), ::vectores),
            Plantilla("cuerpo_libre", "diagrama de cuerpo libre de un bloque", listOf(FUERZAS, MASA.copy(porOmision = 0.0, abierto = false)), ::cuerpoLibre),
            Plantilla("plano_inclinado", "bloque en un plano inclinado con el peso descompuesto y rozamiento",
                listOf(num("angulo", "°", null, 0.0, 90.0, true), MASA, num("mu", "", 0.0, 0.0, 2.0, false), GRAVEDAD), ::planoInclinado),
            Plantilla("tiro_parabolico", "trayectoria, altura maxima y alcance",
                listOf(num("v0", "m/s", null, 0.0, 2000.0, true), num("angulo", "°", null, 0.0, 90.0, true), num("altura", "m", 0.0, 0.0, 1e5, false), GRAVEDAD), ::tiroParabolico),
            Plantilla("mru", "graficas x-t, v-t y a-t a velocidad constante",
                listOf(num("x0", "m", 0.0, -1e6, 1e6, false), num("v", "m/s", null, -1e5, 1e5, false), num("t", "s", 10.0, 0.0, 1e5, true)), ::cinematica),
            Plantilla("mruv", "graficas x-t, v-t y a-t con aceleracion constante",
                listOf(num("x0", "m", 0.0, -1e6, 1e6, false), num("v0", "m/s", 0.0, -1e5, 1e5, false), num("a", "m/s²", null, -1e4, 1e4, false), num("t", "s", 10.0, 0.0, 1e5, true)), ::cinematica),
            Plantilla("circuito", "circuito con fuente y resistencias en serie o en paralelo",
                listOf(num("voltaje", "V", null, 0.0, 1e6, true), Parametro("resistencias", Clase.LISTA, "Ω", null, 0.0, 1e9, true, "[R1, R2, ...]"),
                    Parametro("conexion", Clase.OPCION, "", null, 0.0, 0.0, false, "serie|paralelo")), ::circuito),
            Plantilla("pendulo", "pendulo simple con sus fuerzas y su periodo",
                listOf(num("longitud", "m", null, 0.0, 1e4, true), num("angulo", "°", 20.0, 0.0, 90.0, true), MASA.copy(porOmision = 1.0), GRAVEDAD), ::pendulo),
            Plantilla("maquina_atwood", "dos masas colgando de una polea", listOf(num("m1", "kg", null, 0.0, 1e6, true), num("m2", "kg", null, 0.0, 1e6, true), GRAVEDAD), ::atwood)
        )
    }

    /** Otros nombres para los parámetros (los modelos pequeños mezclan inglés y español). */
    private val ALIAS = listOf(
        "voltage" to "voltaje", "v" to "voltaje", "tension" to "voltaje", "fem" to "voltaje", "resistances" to "resistencias",
        "resistencia" to "resistencias", "r" to "resistencias", "connection" to "conexion", "tipo_conexion" to "conexion",
        "configuracion" to "conexion", "velocidad" to "v0", "velocidad_inicial" to "v0", "vo" to "v0", "v_0" to "v0",
        "velocidad" to "v", "aceleracion" to "a", "tiempo" to "t", "angle" to "angulo", "theta" to "angulo",
        "inclinacion" to "angulo", "mass" to "masa", "m" to "masa", "coeficiente" to "mu", "rozamiento" to "mu",
        "friccion" to "mu", "μ" to "mu", "l" to "longitud", "length" to "longitud", "forces" to "fuerzas",
        "vectors" to "vectores", "altura_inicial" to "altura", "h" to "altura", "h0" to "altura"
    )

    /** La plantilla de un tipo (con alias: «mrua» es «mruv», «fuerzas» es «cuerpo_libre»...). */
    fun plantilla(tipo: String): Plantilla? {
        val t = Acciones.normalizar(tipo).replace(' ', '_').replace('-', '_')
        val tt = when (t) {
            "mrua", "mruv", "movimiento_uniformemente_acelerado", "mua" -> "mruv"
            "mu", "movimiento_rectilineo_uniforme" -> "mru"
            "fuerzas", "dcl", "diagrama_de_cuerpo_libre", "cuerpo" -> "cuerpo_libre"
            "plano", "rampa" -> "plano_inclinado"
            "tiro", "proyectil", "parabolico", "lanzamiento" -> "tiro_parabolico"
            "suma_de_vectores", "vector" -> "vectores"
            "electrico", "circuito_electrico", "resistencias" -> "circuito"
            "polea", "atwood" -> "maquina_atwood"
            else -> t
        }
        return PLANTILLAS.firstOrNull { it.tipo == tt }
    }

    data class Vector(val nombre: String, val modulo: Double, val angulo: Double) {
        val x get() = modulo * cos(Math.toRadians(angulo))
        val y get() = modulo * sin(Math.toRadians(angulo))
    }

    class Valores {
        val numeros = TreeMap<String, Double>()
        val listas = TreeMap<String, List<Double>>()
        var vectores: List<Vector> = emptyList()
        val opciones = TreeMap<String, String>()
        fun n(k: String) = numeros[k] ?: 0.0
        fun tiene(k: String) = k in numeros
    }

    class Diagrama(val tipo: String, val valores: Valores, val titulo: String, val resultados: List<String>)

    /** «20 m/s», «30°», «0,2»: el número de delante. */
    private fun unNumero(v: JsonElement?): Double? = when {
        v is JsonPrimitive && !v.isString -> v.doubleOrNull
        v is JsonPrimitive -> v.content.trim().replace(',', '.').takeWhile { it in '0'..'9' || it in ".-+e" }.toDoubleOrNull()
        else -> null
    }?.takeIf { it.isFinite() }

    private fun rem360(a: Double) = ((a % 360.0) + 360.0) % 360.0

    private fun vectoresDe(v: JsonElement, q: Parametro): List<Vector> {
        val l = v as? JsonArray ?: throw Imposible("«${q.nombre}» es una lista ${q.ayuda}")
        if (l.isEmpty() || l.size > MAX_LISTA) throw Imposible("«${q.nombre}» lleva de 1 a $MAX_LISTA")
        val letras = listOf("A", "B", "C", "D", "E", "F", "G", "H")
        return l.mapIndexed { k, x ->
            val (nombre, modulo, angulo) = when {
                x is JsonObject -> Triple((x["nombre"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
                    listOf("modulo", "valor", "magnitud", "fuerza", "f").firstNotNullOfOrNull { x[it] }?.let(::unNumero),
                    listOf("angulo", "direccion", "grados").firstNotNullOfOrNull { x[it] }?.let(::unNumero))
                x is JsonArray && x.size == 2 -> Triple(null, unNumero(x[0]), unNumero(x[1]))
                x is JsonArray && x.size == 3 -> Triple((x[0] as? JsonPrimitive)?.takeIf { it.isString }?.content, unNumero(x[1]), unNumero(x[2]))
                else -> Triple(null, null, null)
            }
            val m = modulo ?: throw Imposible("el ${k + 1} de «${q.nombre}» no tiene «modulo»")
            val a = angulo ?: throw Imposible("el ${k + 1} de «${q.nombre}» no tiene «angulo» (en grados)")
            if (m !in 0.0..q.max) throw Imposible("el modulo ${numero(m)} de «${q.nombre}» no tiene sentido: va de 0 a ${numero(q.max)}")
            val n = nombre?.trim()?.let { s -> s.codePoints().limit(12).toArray().let { String(it, 0, it.size) } }?.takeIf { it.isNotEmpty() }
            Vector(n ?: letras[k], m, rem360(a))
        }
    }

    fun validar(args: JsonObject, avisos: MutableList<String>): Diagrama {
        val tipoTxt = ((args["tipo"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "").trim()
        val tipos = PLANTILLAS.map { it.tipo }
        if (tipoTxt.isEmpty()) throw mal("falta «tipo»: ${tipos.joinToString(", ")}")
        var p = plantilla(tipoTxt) ?: throw mal("no hay plantilla «$tipoTxt»; las que hay: ${tipos.joinToString(", ")}")
        // Los parámetros, dentro de «parametros» o sueltos al lado del tipo (orden de BTreeMap, como el PC).
        val crudos = TreeMap<String, JsonElement>()
        for ((k, v) in args) if (k !in setOf("tipo", "parametros", "titulo")) crudos[k] = v
        (args["parametros"] as? JsonObject)?.let { for ((k, v) in it) crudos[k] = v }
        var dados = TreeMap<String, JsonElement>()
        for ((k, v) in crudos) dados[Acciones.normalizar(k).replace(' ', '_')] = v
        if (p.tipo == "cuerpo_libre" && "angulo" in dados && "fuerzas" !in dados && "vectores" !in dados) {
            avisos += "con el angulo de un plano y sin fuerzas sueltas, se dibuja el plano inclinado"
            p = plantilla("plano_inclinado")!!
        }
        val nombres = p.parametros.map { it.nombre }
        for ((alias, nombre) in ALIAS) {
            if (nombre in nombres && nombre !in dados) dados.remove(alias)?.let { dados[nombre] = it }
        }
        val v = Valores()
        for (q in p.parametros) {
            val x = dados[q.nombre]?.takeIf { it !is JsonNull }
            when (q.clase) {
                Clase.NUMERO -> {
                    val n = if (x != null) unNumero(x) ?: throw mal("«${q.nombre}» tiene que ser un numero (en ${q.unidad})")
                    else q.porOmision ?: throw mal("falta «${q.nombre}» (${q.unidad}) para ${p.tipo}")
                    val bajo = if (q.abierto) n <= q.min else n < q.min
                    if (bajo || n > q.max) {
                        throw mal(("«${q.nombre}» = ${numero(n)} ${q.unidad} no tiene sentido fisico en ${p.tipo}: tiene que estar entre ${numero(q.min)} " +
                            "${if (q.abierto) "(sin llegar)" else ""} y ${numero(q.max)} ${q.unidad}").replace("  ", " "))
                    }
                    if (x != null || q.porOmision != null) v.numeros[q.nombre] = n
                }
                Clase.LISTA -> {
                    val l = when (x) {
                        is JsonArray -> x.map { unNumero(it) ?: throw mal("«${q.nombre}» es una lista de numeros") }
                        null -> throw mal("falta «${q.nombre}» ${q.ayuda}")
                        else -> listOf(unNumero(x) ?: throw mal("«${q.nombre}» es una lista de numeros"))
                    }
                    if (l.isEmpty() || l.size > MAX_LISTA) throw mal("«${q.nombre}» lleva de 1 a $MAX_LISTA valores")
                    l.firstOrNull { (q.abierto && it <= q.min) || it < q.min || it > q.max }?.let {
                        throw mal("una resistencia de ${numero(it)} ${q.unidad} no tiene sentido fisico: tiene que ser mayor que ${numero(q.min)} y como mucho ${numero(q.max)}")
                    }
                    v.listas[q.nombre] = l
                }
                Clase.VECTORES -> {
                    val y = x ?: dados["vectores"] ?: dados["fuerzas"] ?: throw mal("falta «${q.nombre}» ${q.ayuda}")
                    v.vectores = try { vectoresDe(y, q) } catch (e: Imposible) { throw mal(e.message!!) }
                }
                Clase.OPCION -> {
                    val validas = q.ayuda.split('|')
                    if (x != null) {
                        val s = Acciones.normalizar((x as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "")
                        val elegida = validas.firstOrNull { s.startsWith(it.take(min(4, it.length))) } ?: throw mal("«${q.nombre}» es ${validas.joinToString(" o ")}")
                        v.opciones[q.nombre] = elegida
                    }
                }
            }
        }
        val sobran = dados.keys.filter { it !in nombres && it != "vectores" && it != "fuerzas" }
        if (sobran.isNotEmpty()) avisos += "no se usan ${sobran.joinToString(", ") { "«$it»" }} (los de ${p.tipo} son: ${nombres.joinToString(", ")})"
        val resultados = try { p.hacer(v, null) } catch (e: Imposible) { throw mal(e.message!!) }
        if (resultados.isNotEmpty()) avisos += "calculado por la app: ${resultados.joinToString("; ")}"
        val titulo = ((args["titulo"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "").trim().let { s -> s.codePoints().limit(100).toArray().let { String(it, 0, it.size) } }
        return Diagrama(p.tipo, v, titulo, resultados)
    }

    /** Las piezas del diagrama, en el lienzo de 800×600 (todo en un grupo). */
    fun piezas(d: Diagrama, medir: MedidaDeTexto): List<Element> {
        val pz = Pizarra(medir)
        plantilla(d.tipo)?.let { runCatching { it.hacer(d.valores, pz) } }
        // Los rótulos de las fuerzas, ya con todo dibujado: que no pisen el bloque, el plano ni otra flecha.
        pz.colocarRotulos()
        if (d.titulo.isNotEmpty()) {
            pz.moverDesde(0, 0.0, 36.0)
            pz.texto(Pizarra.aPizarra(d.titulo, false), 400.0, 0.0, 24.0, TINTA, Alinear.CENTRO)
        }
        val grupo = randomId()
        return pz.el.map { it.copy(groupIds = listOf(grupo)) }
    }

    private fun columna(pz: Pizarra, x: Double, y0: Double, lineas: List<String>) {
        var y = y0
        for (l in lineas) { val (_, h) = pz.texto(l, x, y, 16.0, TINTA, Alinear.IZQUIERDA); y += h + 6.0 }
    }

    private fun conUnidad(v: Double, u: String) = "${numero(v)} $u"

    private fun suma(vs: List<Vector>) = vs.fold(0.0 to 0.0) { (x, y), v -> (x + v.x) to (y + v.y) }

    private fun polarDe(p: Pair<Double, Double>): Pair<Double, Double> {
        val (x, y) = p
        val m = sqrt(x * x + y * y)
        val a = if (m < 1e-12) 0.0 else rem360(Math.toDegrees(atan2(y, x)))
        return m to a
    }

    /** El gris clarito de los bloques (`ColorRgba::opaco(0.93, 0.93, 0.95)` del PC). */
    private const val CLARITO = "#ededf2"

    private fun vectores(v: Valores, pz: Pizarra?): List<String> {
        val vs = v.vectores
        val (rx, ry) = suma(vs)
        val (r, ang) = polarDe(rx to ry)
        val resultados = listOf("Rx = ${numero(rx)}", "Ry = ${numero(ry)}", "R = ${numero(r)} a ${numero(ang)}°")
        pz ?: return resultados
        val poligono = v.opciones["metodo"] == "poligono" || vs.size != 2
        val cadena = mutableListOf(0.0 to 0.0)
        var punta = 0.0 to 0.0
        for (x in vs) { punta = (punta.first + x.x) to (punta.second + x.y); cadena += punta }
        val todos = cadena + vs.map { it.x to it.y }
        val x0 = todos.fold(0.0) { a, p -> min(a, p.first) }; val x1 = todos.fold(0.0) { a, p -> max(a, p.first) }
        val y0 = todos.fold(0.0) { a, p -> min(a, p.second) }; val y1 = todos.fold(0.0) { a, p -> max(a, p.second) }
        var s = min(460.0 / max(x1 - x0, 1e-9), 420.0 / max(y1 - y0, 1e-9))
        if (!s.isFinite()) s = 1.0
        val o = (60.0 + (-x0) * s + (460.0 - (x1 - x0) * s) / 2.0) to (70.0 + y1 * s + (420.0 - (y1 - y0) * s) / 2.0)
        fun aLienzo(p: Pair<Double, Double>) = (o.first + p.first * s) to (o.second - p.second * s)
        pz.discontinua(listOf(o.first - 30.0 to o.second, 560.0 to o.second), GRIS, 1.0)
        pz.discontinua(listOf(o.first to o.second + 30.0, o.first to 40.0), GRIS, 1.0)
        for ((k, x) in vs.withIndex()) {
            val c = colorDeSerie(k + 1)
            val fin = aLienzo(x.x to x.y)
            pz.flecha(o, fin, c, 2.5)
            pz.rotuloEn(fin, Math.toRadians(x.angulo), x.nombre, c)
        }
        if (poligono) {
            for (k in 1 until vs.size) pz.flechaDiscontinua(aLienzo(cadena[k]), aLienzo(cadena[k + 1]), colorDeSerie(k + 1), 2.0)
        } else {
            val a = vs[0]; val b = vs[1]
            val fin = aLienzo(rx to ry)
            pz.discontinua(listOf(aLienzo(a.x to a.y), fin), GRIS, 1.5)
            pz.discontinua(listOf(aLienzo(b.x to b.y), fin), GRIS, 1.5)
        }
        val fin = aLienzo(rx to ry)
        pz.flecha(o, fin, ROJO, 3.0)
        pz.rotuloEn(fin, Math.toRadians(ang), "R", ROJO)
        val lineas = vs.map { "${it.nombre} = ${numero(it.modulo)} a ${numero(it.angulo)}°" } + "" + resultados +
            "(${if (poligono) "metodo del poligono" else "metodo del paralelogramo"})"
        columna(pz, 590.0, 80.0, lineas)
        return resultados
    }

    private fun cuerpoLibre(v: Valores, pz: Pizarra?): List<String> {
        val fs = v.vectores
        val (fx, fy) = suma(fs)
        val (r, ang) = polarDe(fx to fy)
        val m = v.n("masa")
        val resultados = mutableListOf("ΣFx = ${numero(fx)} N", "ΣFy = ${numero(fy)} N", "F neta = ${numero(r)} N a ${numero(ang)}°")
        if (m > 0.0) resultados += "a = F/m = ${numero(r / m)} m/s²"
        if (r < 1e-9 * (1.0 + fs.sumOf { it.modulo })) resultados += "las fuerzas se equilibran: el bloque esta en reposo o a velocidad constante"
        pz ?: return resultados
        val c = 300.0 to 300.0
        pz.rectangulo(doubleArrayOf(c.first - 45.0, c.second - 45.0, 90.0, 90.0), TINTA, CLARITO, 2.0)
        if (m > 0.0) pz.texto("${numero(m)} kg", c.first, c.second - 10.0, 16.0, TINTA, Alinear.CENTRO)
        val mayor = max(fs.fold(0.0) { a, f -> max(a, f.modulo) }, 1e-9)
        for ((k, f) in fs.withIndex()) pz.vector(c, 60.0 + 110.0 * f.modulo / mayor, f.angulo, "${f.nombre} = ${numero(f.modulo)} N", colorDeSerie(k + 1), false)
        if (r > 1e-9) pz.vector(c, 60.0 + 110.0 * min(r / mayor, 1.5), ang, "F neta", ROJO, true)
        columna(pz, 560.0, 120.0, resultados)
        return resultados
    }

    private fun planoInclinado(v: Valores, pz: Pizarra?): List<String> {
        val th = v.n("angulo"); val m = v.n("masa"); val mu = v.n("mu"); val g = v.n("g")
        val t = Math.toRadians(th)
        val peso = m * g
        val px = peso * sin(t); val py = peso * cos(t)
        val normal = py
        val desliza = mu < tan(t)
        val (fr, a) = if (desliza) (mu * normal) to (g * (sin(t) - mu * cos(t))) else px to 0.0
        val resultados = mutableListOf(
            "P = m·g = ${conUnidad(peso, "N")}", "Px = P·sen ${numero(th)}° = ${conUnidad(px, "N")}",
            "Py = P·cos ${numero(th)}° = ${conUnidad(py, "N")}", "N = Py = ${conUnidad(normal, "N")}"
        )
        if (mu > 0.0) resultados += if (desliza) "Fr = μ·N = ${conUnidad(fr, "N")}" else "Fr estatica = Px = ${conUnidad(fr, "N")} (μ ≥ tan ${numero(th)}°: no desliza)"
        resultados += "a = ${conUnidad(a, "m/s²")}"
        pz ?: return resultados
        val base = min(450.0, 300.0 / max(tan(t), 1e-6))
        val a0 = 40.0 to 540.0
        val b0 = (a0.first + base) to a0.second
        val c0 = (a0.first + base) to (a0.second - base * tan(t))
        pz.poligono(listOf(a0, b0, c0), TINTA, 2.0)
        pz.arco(a0, 50.0, 0.0, th, TINTA, 1.5)
        pz.texto("${numero(th)}°", a0.first + 58.0, a0.second - 24.0, 15.0, TINTA, Alinear.IZQUIERDA)
        val largo = base / cos(t)
        val u = cos(t) to -sin(t)
        val n = -sin(t) to -cos(t)
        val lado = 64.0
        val mpt = (a0.first + u.first * largo * 0.55) to (a0.second + u.second * largo * 0.55)
        fun esq(aa: Double, bb: Double) = (mpt.first + u.first * aa + n.first * bb) to (mpt.second + u.second * aa + n.second * bb)
        pz.poligono(listOf(esq(-lado / 2, 0.0), esq(lado / 2, 0.0), esq(lado / 2, lado), esq(-lado / 2, lado)), TINTA, 2.0)
        val cg = esq(0.0, lado / 2)
        val escala = 120.0 / max(peso, 1e-9)
        val normalAng = 90.0 + th
        pz.vector(cg, peso * escala, 270.0, "P = ${conUnidad(peso, "N")}", ROJO, false)
        pz.vector(cg, normal * escala, normalAng, "N = ${conUnidad(normal, "N")}", AZUL, false)
        pz.vector(cg, px * escala, 180.0 + th, "Px = ${conUnidad(px, "N")}", NARANJA, true)
        pz.vector(cg, py * escala, normalAng + 180.0, "Py = ${conUnidad(py, "N")}", NARANJA, true)
        if (mu > 0.0 && fr > 0.0) pz.vector(esq(-lado / 2 + 6.0, 0.0), max(fr * escala, 30.0), th, "Fr = ${conUnidad(fr, "N")}", VERDE, false)
        val lineas = listOf("m = ${numero(m)} kg, μ = ${numero(mu)}, g = ${numero(g)} m/s²", "") + resultados
        columna(pz, 60.0, 30.0, lineas.take(1))
        columna(pz, 540.0, 70.0, lineas.drop(2))
        return resultados
    }

    private fun tiroParabolico(v: Valores, pz: Pizarra?): List<String> {
        val v0 = v.n("v0"); val th = v.n("angulo"); val h0 = v.n("altura"); val g = v.n("g")
        val t = Math.toRadians(th)
        var vx = v0 * cos(t); val vy = v0 * sin(t)
        if (abs(vx) < 1e-9) vx = 0.0
        val tSub = vy / g
        val hMax = h0 + vy * vy / (2.0 * g)
        val tVuelo = (vy + sqrt(vy * vy + 2.0 * g * h0)) / g
        val alcance = vx * tVuelo
        val resultados = listOf("v0x = ${numero(vx)} m/s, v0y = ${numero(vy)} m/s", "altura maxima = ${numero(hMax)} m (a los ${numero(tSub)} s)",
            "tiempo de vuelo = ${numero(tVuelo)} s", "alcance = ${numero(alcance)} m")
        pz ?: return resultados
        val ancho = max(max(alcance * 1.08, hMax * 0.3), 1.0); val alto = hMax * 1.15
        val s = min(560.0 / ancho, 380.0 / alto)
        val ejes = Pizarra.Ejes(doubleArrayOf(70.0, 70.0 + 380.0 - alto * s, ancho * s, alto * s), 0.0, ancho, 0.0, alto)
        ejes.dibujar(pz, "x (m)", "y (m)")
        fun yDe(x: Double) = if (vx == 0.0) Double.NaN else h0 + x * vy / vx - g * x * x / (2.0 * vx * vx)
        if (vx == 0.0) pz.linea(listOf(ejes.aLienzo(0.0, h0), ejes.aLienzo(0.0, hMax)), AZUL, 2.5)
        else pz.linea((0..200).map { alcance * it / 200.0 }.map { ejes.aLienzo(it, max(yDe(it), 0.0)) }, AZUL, 2.5)
        val xMax = vx * tSub
        val (hx, hy) = ejes.aLienzo(xMax, hMax)
        pz.discontinua(listOf(ejes.aLienzo(xMax, 0.0), hx to hy), GRIS, 1.5)
        pz.punto(hx, hy, "H = ${numero(hMax)} m", ROJO)
        val (rx, ry) = ejes.aLienzo(alcance, 0.0)
        pz.puntoCon(rx, ry, "R = ${numero(alcance)} m", ROJO, -2.4)
        val salida = ejes.aLienzo(0.0, h0)
        pz.vector(salida, 70.0, th, "v0 = ${numero(v0)} m/s", VERDE, false)
        pz.arco(salida, 34.0, 0.0, th, VERDE, 1.5)
        pz.texto("${numero(th)}°", salida.first + 40.0, salida.second - 22.0, 14.0, VERDE, Alinear.IZQUIERDA)
        columna(pz, 70.0, 480.0, resultados)
        return resultados
    }

    private fun cinematica(v: Valores, pz: Pizarra?): List<String> {
        val x0 = v.n("x0")
        val v0 = if (v.tiene("v")) v.n("v") else v.n("v0")
        val a = v.n("a")
        val tf = v.n("t")
        val x = { t: Double -> x0 + v0 * t + 0.5 * a * t * t }
        val vel = { t: Double -> v0 + a * t }
        val resultados = mutableListOf("x(t) = ${polinomio(listOf(x0 to "", v0 to "·t", a / 2.0 to "·t²"))}", "v(t) = ${polinomio(listOf(v0 to "", a to "·t"))}",
            "a los ${numero(tf)} s: x = ${numero(x(tf))} m, v = ${numero(vel(tf))} m/s", "desplazamiento = ${numero(x(tf) - x0)} m")
        if (a != 0.0 && v0 * a < 0.0 && -v0 / a < tf) resultados += "se detiene y da la vuelta en t = ${numero(-v0 / a)} s, en x = ${numero(x(-v0 / a))} m"
        pz ?: return resultados
        val paneles = listOf(Triple("x–t", "x (m)", x) to AZUL, Triple("v–t", "v (m/s)", vel) to ROJO, Triple("a–t", "a (m/s²)", { _: Double -> a }) to VERDE)
        for ((k, panel) in paneles.withIndex()) {
            val (datos, color) = panel
            val (titulo, eje, f) = datos
            val xCaja = 50.0 + 255.0 * k
            val ys = (0..100).map { f(tf * it / 100.0) }
            val ejes = Pizarra.Ejes.nuevos(doubleArrayOf(xCaja, 130.0, 190.0, 200.0), 0.0 to tf, ys.fold(Double.MAX_VALUE, ::min) to ys.fold(-Double.MAX_VALUE, ::max))
            pz.texto(titulo, xCaja + 95.0, 70.0, 18.0, TINTA, Alinear.CENTRO)
            ejes.dibujar(pz, "t", eje)
            ejes.curva(pz, f, color, 2.5)
        }
        columna(pz, 50.0, 400.0, resultados)
        return resultados
    }

    /** `3 + 2·t - 0.5·t²` sin los términos nulos ni el «+ -». */
    fun polinomio(terminos: List<Pair<Double, String>>): String {
        var s = ""
        for ((c, variable) in terminos) {
            if (c == 0.0) continue
            val cuerpo = if (abs(c) == 1.0 && variable.isNotEmpty()) variable.trimStart('·') else "${numero(abs(c))}$variable"
            s = if (s.isEmpty()) (if (c < 0.0) "-$cuerpo" else cuerpo) else "$s ${if (c < 0.0) "-" else "+"} $cuerpo"
        }
        return s.ifEmpty { "0" }
    }

    /** Una resistencia en zigzag de [a] a [b]. */
    private fun resistencia(pz: Pizarra, a: Pair<Double, Double>, b: Pair<Double, Double>, color: String) {
        val dx = b.first - a.first; val dy = b.second - a.second
        val largo = sqrt(dx * dx + dy * dy)
        val ux = dx / largo; val uy = dy / largo
        val nx = -uy; val ny = ux
        val zig = min(50.0, largo * 0.8)
        val ini = (largo - zig) / 2.0
        val pts = mutableListOf(a, (a.first + ux * ini) to (a.second + uy * ini))
        for (k in 1..6) {
            val d = ini + zig * (k - 0.5) / 6.0
            val lado = if (k % 2 == 0) -9.0 else 9.0
            pts += (a.first + ux * d + nx * lado) to (a.second + uy * d + ny * lado)
        }
        pts += (a.first + ux * (ini + zig)) to (a.second + uy * (ini + zig))
        pts += b
        pz.linea(pts, color, 2.0)
    }

    /** La fuente: dos rayas, la larga es el +. */
    private fun pila(pz: Pizarra, centro: Pair<Double, Double>, voltaje: Double) {
        val (x, y) = centro
        pz.linea(listOf(x - 22.0 to y - 6.0, x + 22.0 to y - 6.0), TINTA, 2.0)
        pz.linea(listOf(x - 12.0 to y + 6.0, x + 12.0 to y + 6.0), TINTA, 4.0)
        pz.texto("+", x + 26.0, y - 20.0, 14.0, TINTA, Alinear.IZQUIERDA)
        pz.texto("${numero(voltaje)} V", x - 30.0, y - 9.0, 16.0, TINTA, Alinear.DERECHA)
    }

    private fun circuito(v: Valores, pz: Pizarra?): List<String> {
        val volt = v.n("voltaje")
        val rs = v.listas["resistencias"].orEmpty()
        val paralelo = when (v.opciones["conexion"]) {
            "paralelo" -> true
            null -> if (rs.size == 1) false else throw Imposible("falta «conexion»: serie o paralelo")
            else -> false
        }
        val req = if (paralelo) 1.0 / rs.sumOf { 1.0 / it } else rs.sum()
        val i = volt / req
        val resultados = mutableListOf("R equivalente = ${numero(req)} Ω", "I total = ${numero(i)} A", "P = V·I = ${numero(volt * i)} W")
        for ((k, r) in rs.withIndex()) {
            resultados += if (paralelo) "R${k + 1} = ${numero(r)} Ω: V = ${numero(volt)} V, I = ${numero(volt / r)} A"
            else "R${k + 1} = ${numero(r)} Ω: V = ${numero(i * r)} V, I = ${numero(i)} A"
        }
        pz ?: return resultados
        val x0 = 120.0; val x1 = 560.0; val y0 = 110.0; val y1 = 330.0
        pila(pz, x0 to (y0 + y1) / 2.0, volt)
        pz.linea(listOf(x0 to y0, x0 to (y0 + y1) / 2.0 - 6.0), TINTA, 2.0)
        pz.linea(listOf(x0 to (y0 + y1) / 2.0 + 6.0, x0 to y1), TINTA, 2.0)
        val n = rs.size.toDouble()
        if (paralelo) {
            pz.linea(listOf(x0 to y0, x1 to y0), TINTA, 2.0)
            pz.linea(listOf(x0 to y1, x1 to y1), TINTA, 2.0)
            for ((k, r) in rs.withIndex()) {
                val x = x0 + (x1 - x0) * (k + 1.0) / n
                resistencia(pz, x to y0, x to y1, colorDeSerie(k + 1))
                pz.texto("R${k + 1} = ${numero(r)} Ω", x + 14.0, (y0 + y1) / 2.0 - 10.0, 15.0, TINTA, Alinear.IZQUIERDA)
            }
        } else {
            val paso = (x1 - x0) / n
            var x = x0
            for ((k, r) in rs.withIndex()) {
                val a = x + paso * 0.15; val b = x + paso * 0.85
                pz.linea(listOf(x to y0, a to y0), TINTA, 2.0)
                resistencia(pz, a to y0, b to y0, colorDeSerie(k + 1))
                pz.linea(listOf(b to y0, x + paso to y0), TINTA, 2.0)
                pz.texto("R${k + 1} = ${numero(r)} Ω", (a + b) / 2.0, y0 - 34.0, 15.0, TINTA, Alinear.CENTRO)
                x += paso
            }
            pz.linea(listOf(x1 to y0, x1 to y1, x0 to y1), TINTA, 2.0)
        }
        val xFlecha = if (paralelo) x0 + (x1 - x0) / n * 0.5 else (x0 + x1) / 2.0
        pz.flecha(xFlecha + 35.0 to y1, xFlecha - 35.0 to y1, ROJO, 2.5)
        pz.texto("I = ${numero(i)} A", xFlecha, y1 + 8.0, 15.0, ROJO, Alinear.CENTRO)
        columna(pz, 120.0, 380.0, listOf("Conexion en ${if (paralelo) "paralelo" else "serie"}") + resultados)
        return resultados
    }

    private fun pendulo(v: Valores, pz: Pizarra?): List<String> {
        val l = v.n("longitud"); val th = v.n("angulo"); val m = v.n("masa"); val g = v.n("g")
        val t = Math.toRadians(th)
        val periodo = 2.0 * PI * sqrt(l / g)
        val peso = m * g
        val tension = peso * cos(t)
        val vMax = sqrt(2.0 * g * l * (1.0 - cos(t)))
        val resultados = mutableListOf("periodo T = 2π·√(L/g) = ${numero(periodo)} s", "P = m·g = ${numero(peso)} N",
            "en el extremo: tension = P·cos ${numero(th)}° = ${numero(tension)} N, fuerza recuperadora = P·sen ${numero(th)}° = ${numero(peso * sin(t))} N",
            "velocidad maxima (abajo) = ${numero(vMax)} m/s")
        if (th > 15.0) resultados += "el periodo es el de oscilaciones pequenas: con angulos grandes es algo mayor"
        pz ?: return resultados
        val pivote = 300.0 to 70.0
        val largo = 300.0
        pz.linea(listOf(pivote.first - 50.0 to pivote.second, pivote.first + 50.0 to pivote.second), TINTA, 3.0)
        pz.discontinua(listOf(pivote, pivote.first to pivote.second + largo + 20.0), GRIS, 1.0)
        val masa = (pivote.first + largo * sin(t)) to (pivote.second + largo * cos(t))
        pz.linea(listOf(pivote, masa), TINTA, 2.0)
        pz.arco(pivote, 70.0, 270.0, 270.0 + th, TINTA, 1.5)
        pz.texto("${numero(th)}°", pivote.first + 18.0, pivote.second + 76.0, 14.0, TINTA, Alinear.IZQUIERDA)
        pz.texto("L = ${numero(l)} m", (pivote.first + masa.first) / 2.0 + 12.0, (pivote.second + masa.second) / 2.0 - 20.0, 15.0, TINTA, Alinear.IZQUIERDA)
        pz.discontinua((0..40).map { -t + 2.0 * t * it / 40.0 }.map { (pivote.first + largo * sin(it)) to (pivote.second + largo * cos(it)) }, GRIS, 1.0)
        pz.elipse(doubleArrayOf(masa.first - 18.0, masa.second - 18.0, 36.0, 36.0), TINTA, AZUL, 2.0)
        val escala = 110.0 / max(peso, 1e-9)
        pz.vector(masa, peso * escala, 270.0, "P = ${numero(peso)} N", ROJO, false)
        pz.vector(masa, tension * escala, 90.0 + th, "T = ${numero(tension)} N", AZUL, false)
        pz.vector(masa, peso * sin(t) * escala, 180.0 + th, "P·sen θ", NARANJA, true)
        pz.vector(masa, peso * cos(t) * escala, 270.0 + th, "P·cos θ", NARANJA, true)
        columna(pz, 470.0, 120.0, resultados)
        return resultados
    }

    private fun atwood(v: Valores, pz: Pizarra?): List<String> {
        val m1 = v.n("m1"); val m2 = v.n("m2"); val g = v.n("g")
        val a = abs(m1 - m2) * g / (m1 + m2)
        val tension = 2.0 * m1 * m2 * g / (m1 + m2)
        val resultados = listOf("a = |m1 − m2|·g/(m1 + m2) = ${numero(a)} m/s²", "T = 2·m1·m2·g/(m1 + m2) = ${numero(tension)} N",
            if (m1 > m2) "baja m1 y sube m2" else if (m2 > m1) "baja m2 y sube m1" else "masas iguales: equilibrio")
        pz ?: return resultados
        val c = 300.0 to 110.0
        val r = 40.0
        pz.linea(listOf(c.first to 40.0, c), TINTA, 3.0)
        pz.elipse(doubleArrayOf(c.first - r, c.second - r, 2.0 * r, 2.0 * r), TINTA, null, 2.5)
        val mayor = max(m1, m2)
        for ((k, par) in listOf(m1 to -1.0, m2 to 1.0).withIndex()) {
            val (m, lado) = par
            val x = c.first + lado * r
            val ladoCaja = 56.0 + 30.0 * (m / mayor)
            val y = 300.0 + if (m > min(m1, m2) && m1 != m2) 60.0 else 0.0
            pz.linea(listOf(x to c.second, x to y), TINTA, 2.0)
            pz.rectangulo(doubleArrayOf(x - ladoCaja / 2.0, y, ladoCaja, ladoCaja), TINTA, CLARITO, 2.0)
            val nombre = "m${k + 1}"; val valor = "${numero(m)} kg"
            var tam = 14.0
            while (tam > 8.0 && pz.medida(valor, tam).first > ladoCaja - 10.0) tam -= 1.0
            pz.texto(nombre, x, y + ladoCaja / 2.0 - tam * 1.3, tam, TINTA, Alinear.CENTRO)
            pz.texto(valor, x, y + ladoCaja / 2.0, tam, TINTA, Alinear.CENTRO)
            pz.vector(x to y + ladoCaja, 60.0 + 40.0 * m / mayor, 270.0, "P${k + 1} = ${numero(m * g)} N", ROJO, false)
            pz.vector(x to y, 60.0 + 40.0 * tension / (mayor * g), 90.0, "T", AZUL, false)
        }
        columna(pz, 470.0, 140.0, resultados)
        return resultados
    }
}
