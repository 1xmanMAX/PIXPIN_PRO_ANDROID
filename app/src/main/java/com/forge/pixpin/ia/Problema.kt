package com.forge.pixpin.ia

import com.forge.pixpin.ia.Pizarra.Alinear
import com.forge.pixpin.ia.Pizarra.Companion.AZUL
import com.forge.pixpin.ia.Pizarra.Companion.GRIS
import com.forge.pixpin.ia.Pizarra.Companion.ROJO
import com.forge.pixpin.ia.Pizarra.Companion.TINTA
import com.forge.pixpin.ia.Pizarra.Companion.numero
import com.forge.pixpin.motor.Element
import com.forge.pixpin.motor.Formula
import com.forge.pixpin.motor.MedidaDeTexto
import com.forge.pixpin.motor.StrokeStyle
import com.forge.pixpin.motor.randomId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * **Un problema en limpio** (`problema_en_limpio`, PC `pixpin-ia/src/problema.rs`): de la foto de un
 * ejercicio (o de un enunciado dictado) a una hoja limpia en el lienzo. El modelo LEE (con su visión y,
 * de apoyo, el OCR del teléfono que se pega al mensaje: [pistaDeOcr]) y aquí se COMPONE siempre igual:
 * el enunciado, las fórmulas compuestas, los datos, la figura en su recuadro y el recuadro «Solución».
 * **Las cuentas de los pasos las hace el teléfono** ([calcular]); si el modelo puso otro resultado, se
 * corrige y se le avisa.
 */
object Problema {
    const val NOMBRE = "problema_en_limpio"
    const val MAX_ENUNCIADO = 1500
    const val MAX_ECUACIONES = 8
    const val MAX_DATOS = 12
    const val MAX_PASOS = 12
    /** El recuadro de la figura (en el lienzo de 800 de ancho). */
    const val FIGURA_ANCHO = 300.0
    const val FIGURA_ALTO = 260.0

    private fun mal(m: String) = ErrorHerramienta.Parametro(m)

    data class Paso(val texto: String, val calculo: String?, val resultado: Double?, val unidad: String)

    class Pedido(
        val titulo: String, val enunciado: String, val ecuaciones: List<String>, val datos: List<String>, val incognitas: List<String>,
        val figura: List<Figura>, val pasos: List<Paso>, val respuesta: String
    )

    private fun cp(s: String, n: Int) = s.codePoints().limit(n.toLong()).toArray().let { String(it, 0, it.size) }

    private fun textos(args: JsonObject, k: String, max: Int): List<String> {
        val v = when (val x = args[k]) {
            null, is JsonNull -> emptyList()
            is JsonPrimitive -> if (x.isString) x.content.split('\n') else throw mal("«$k» es una lista de textos")
            is JsonArray -> x.map { if (it is JsonPrimitive && it.isString) it.content else it.toString() }
            else -> throw mal("«$k» es una lista de textos")
        }.map { it.trim().trim('$').trim() }.filter { it.isNotEmpty() }
        if (v.size > max) throw mal("como mucho $max en «$k»")
        v.firstOrNull { it.codePointCount(0, it.length) > 300 }?.let { throw mal("«${cp(it, 30)}...» de «$k» es demasiado largo") }
        return v
    }

    /** La cuenta de un paso, hecha por el teléfono: solo números, constantes y funciones; `sin(30°)` en grados. */
    fun calcular(c: String): Double {
        val limpio = gradosARadianes(c.replace('×', '*').replace('·', '*').replace("\\cdot", "*").replace("\\times", "*"))
        val f = Formula.compilar(limpio) ?: throw IllegalArgumentException("la cuenta «$c» no se entiende: escribela solo con numeros, como 5*9.8*sin(30°)")
        f.variables.sorted().firstOrNull()?.let { throw IllegalArgumentException("la cuenta «$c» tiene la letra «$it»: pon los numeros de los datos, no las letras") }
        val v = f.en(0.0)
        if (!v.isFinite()) throw IllegalArgumentException("la cuenta «$c» no da un numero (division por cero o raiz de un negativo)")
        return v
    }

    /** `sin(30°)` → `sin((30*pi/180))`: un número seguido de `°`. */
    private fun gradosARadianes(s: String): String {
        val salida = StringBuilder()
        var i = 0
        while (i < s.length) {
            if (s[i] in '0'..'9' || s[i] == '.') {
                val ini = i
                while (i < s.length && (s[i] in '0'..'9' || s[i] == '.')) i++
                val n = s.substring(ini, i)
                if (s.getOrNull(i) == '°') { salida.append("($n*pi/180)"); i++ } else salida.append(n)
                continue
            }
            salida.append(s[i]); i++
        }
        return salida.toString()
    }

    fun validar(args: JsonObject, avisos: MutableList<String>, validarDibujo: (JsonObject, MutableList<String>) -> List<Figura>): Pedido {
        val enunciado = (listOf("enunciado", "texto", "problema").firstNotNullOfOrNull { (args[it] as? JsonPrimitive)?.takeIf { p -> p.isString }?.content } ?: "").trim()
        if (enunciado.count { it.isLetter() } < 8) {
            throw mal("falta el «enunciado» (o no se lee): si la foto no tiene un problema legible, no lo inventes y dile al usuario que no se lee")
        }
        if (enunciado.codePointCount(0, enunciado.length) > MAX_ENUNCIADO) throw mal("el enunciado pasa de $MAX_ENUNCIADO caracteres: resumelo")
        val ecuaciones = textos(args, "ecuaciones", MAX_ECUACIONES)
        for (e in ecuaciones) if (e.count { it == '{' } != e.count { it == '}' }) throw mal("las llaves de la ecuacion «$e» no cuadran")
        val datos = textos(args, "datos", MAX_DATOS)
        val incognitas = textos(args, "incognitas", MAX_DATOS)
        val figura = when (val v = args["figura"]) {
            null, is JsonNull -> emptyList()
            is JsonArray -> if (v.isEmpty()) emptyList() else try {
                validarDibujo(buildJsonObject { put("elementos", v) }, avisos)
            } catch (e: ErrorHerramienta) { throw mal("en la «figura»: ${e.message}") }
            else -> try { validarDibujo(buildJsonObject { put("elementos", v) }, avisos) } catch (e: ErrorHerramienta) { throw mal("en la «figura»: ${e.message}") }
        }
        val pasos = mutableListOf<Paso>()
        val cuentas = mutableListOf<String>()
        when (val l = args["pasos"]) {
            null, is JsonNull -> {}
            is JsonArray -> {
                if (l.size > MAX_PASOS) throw mal("como mucho $MAX_PASOS pasos")
                for ((k, p) in l.withIndex()) {
                    fun s(e: JsonElement?) = (e as? JsonPrimitive)?.takeIf { it.isString }?.content
                    val (texto, calculo, dicho, unidad) = when {
                        p is JsonPrimitive && p.isString -> Cuatro(p.content.trim(), null, null, "")
                        p is JsonObject -> Cuatro(
                            (s(p["texto"] ?: p["paso"]) ?: "").trim(),
                            s(p["calculo"] ?: p["cuenta"])?.trim()?.takeIf { it.isNotEmpty() },
                            (p["resultado"] as? JsonPrimitive)?.let { r -> if (r.isString) r.content.trim().replace(',', '.').toDoubleOrNull() else r.doubleOrNull },
                            cp((s(p["unidad"]) ?: "").trim(), 16)
                        )
                        else -> throw mal("cada paso es {\"texto\":...,\"calculo\":...}")
                    }
                    if (texto.isEmpty() && calculo == null) continue
                    val resultado = if (calculo != null) {
                        val v = try { calcular(calculo) } catch (e: IllegalArgumentException) { throw mal("paso ${k + 1}: ${e.message}") }
                        cuentas += if (dicho != null && abs(dicho - v) > 1e-3 * max(abs(v), 1.0)) "paso ${k + 1}: $calculo da ${numero(v)}, no ${numero(dicho)} (corregido)"
                        else "paso ${k + 1}: $calculo = ${numero(v)}"
                        v
                    } else dicho
                    pasos += Paso(texto, calculo, resultado, unidad)
                }
            }
            else -> throw mal("«pasos» es una lista")
        }
        if (cuentas.isNotEmpty()) avisos += "calculado por la app: ${cuentas.joinToString("; ")}"
        fun t(k: String, n: Int) = cp(((args[k] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "").trim(), n)
        return Pedido(t("titulo", 80), enunciado, ecuaciones, datos, incognitas, figura, pasos, t("respuesta", 300))
    }

    private data class Cuatro(val a: String, val b: String?, val c: Double?, val d: String)

    /** **Las piezas** (todo menos la figura) y dónde va la figura. */
    fun piezas(p: Pedido, medir: MedidaDeTexto): Pair<List<Element>, DoubleArray?> {
        val pz = Pizarra(medir)
        val x0 = 40.0; val ancho = 720.0
        var y = 20.0
        val (_, h0) = pz.texto(p.titulo.ifEmpty { "Problema" }, x0, y, 26.0, TINTA, Alinear.IZQUIERDA)
        y += h0 + 4.0
        pz.linea(listOf(x0 to y, x0 + ancho to y), AZUL, 2.0)
        y += 14.0
        val conFigura = p.figura.isNotEmpty()
        val anchoTexto = if (conFigura) ancho - FIGURA_ANCHO - 24.0 else ancho
        val yFigura = y
        // Una ecuación que va compuesta debajo no se repite cruda en el texto.
        y += pz.parrafo(sinLasEcuaciones(p.enunciado, p.ecuaciones), x0, y, anchoTexto, 17.0, TINTA) + 12.0
        for (e in p.ecuaciones) { val (_, h) = pz.ecuacion(e, x0 + 24.0, y, 24.0, TINTA); y += h + 12.0 }
        if (p.datos.isNotEmpty() || p.incognitas.isNotEmpty()) {
            y += 4.0
            val (_, h) = pz.texto("Datos", x0, y, 18.0, AZUL, Alinear.IZQUIERDA)
            y += h + 6.0
            for (d in p.datos.filter { !yaEsta(it, p.ecuaciones) }) {
                pz.texto("•", x0 + 6.0, y, 16.0, GRIS, Alinear.IZQUIERDA)
                val i = d.indexOf('=')
                val simbolo = if (i >= 0) d.substring(0, i) else null
                val hh = if (simbolo != null && simbolo.trim().isNotEmpty() && simbolo.trim().codePointCount(0, simbolo.trim().length) <= 12) {
                    val valor = d.substring(i + 1)
                    val (w, h1) = pz.ecuacion(simbolo.trim(), x0 + 24.0, y, 18.0, TINTA)
                    val x = x0 + 30.0 + w
                    val h2 = valorComoFormula(valor)?.let { f ->
                        val (we, _) = pz.texto("=", x, y + 1.0, 17.0, TINTA, Alinear.IZQUIERDA)
                        pz.ecuacion(f, x + we + 6.0, y, 18.0, TINTA).second
                    } ?: pz.texto("= ${aTexto(valor)}", x, y + 1.0, 17.0, TINTA, Alinear.IZQUIERDA).second
                    max(h1, h2)
                } else pz.texto(aTexto(d), x0 + 24.0, y, 17.0, TINTA, Alinear.IZQUIERDA).second
                y += max(hh, 20.0) + 6.0
            }
            if (p.incognitas.isNotEmpty() && p.incognitas.none(::esFormula)) {
                val (w, _) = pz.texto("Incógnita:", x0, y, 16.0, AZUL, Alinear.IZQUIERDA)
                val alto = pz.parrafo(p.incognitas.joinToString(", ") { aTexto(it) }, x0 + w + 10.0, y, ancho - w - 10.0, 17.0, TINTA)
                y += max(alto, 20.0) + 6.0
            } else if (p.incognitas.isNotEmpty()) {
                val (w, _) = pz.texto("Incógnita:", x0, y, 16.0, AZUL, Alinear.IZQUIERDA)
                var x = x0 + w + 10.0
                var alto = 20.0
                for ((k, inc) in p.incognitas.withIndex()) {
                    if (k > 0) { val (wc, _) = pz.texto(",", x, y + 1.0, 17.0, TINTA, Alinear.IZQUIERDA); x += wc + 8.0 }
                    val (wi, hi) = if (esFormula(inc)) pz.ecuacion(inc, x, y, 18.0, TINTA) else pz.texto(aTexto(inc), x, y + 1.0, 17.0, TINTA, Alinear.IZQUIERDA)
                    x += wi
                    alto = max(alto, hi)
                }
                y += alto + 6.0
            }
        }
        val cajaFigura = if (conFigura) {
            val x = x0 + ancho - FIGURA_ANCHO
            pz.rectangulo(doubleArrayOf(x - 8.0, yFigura - 8.0, FIGURA_ANCHO + 16.0, FIGURA_ALTO + 16.0), GRIS, null, 1.0)
            pz.el[pz.el.size - 1] = pz.el.last().copy(strokeStyle = StrokeStyle.DASHED)
            doubleArrayOf(x, yFigura, FIGURA_ANCHO, FIGURA_ALTO)
        } else null
        if (conFigura) y = max(y, yFigura + FIGURA_ALTO + 16.0)
        // El recuadro de la solución.
        y += 14.0
        val yCaja = y
        val (_, h) = pz.texto("Solución", x0 + 14.0, y + 10.0, 20.0, AZUL, Alinear.IZQUIERDA)
        y += 10.0 + h + 10.0
        if (p.pasos.isEmpty()) y += 150.0
        else for ((k, paso) in p.pasos.withIndex()) {
            var linea = "${k + 1}. ${paso.texto}"
            paso.resultado?.let { r ->
                val cuenta = paso.calculo?.let { "$it = " } ?: ""
                val unidad = if (paso.unidad.isEmpty()) "" else " ${paso.unidad}"
                linea += ": $cuenta${numero(r)}$unidad"
            }
            y += pz.parrafo(linea, x0 + 14.0, y, ancho - 28.0, 16.0, TINTA) + 6.0
        }
        if (p.respuesta.isNotEmpty()) { y += 4.0; y += pz.parrafo("Respuesta: ${p.respuesta}", x0 + 14.0, y, ancho - 28.0, 17.0, ROJO) + 6.0 }
        y += 10.0
        pz.rectangulo(doubleArrayOf(x0, yCaja, ancho, y - yCaja), AZUL, null, 2.0)
        return pz.el to cajaFigura
    }

    /** Si un texto es fórmula (lleva LaTeX o exponentes/índices) y se compone. */
    fun esFormula(s: String) = s.contains('\\') || s.contains('^') || s.contains('_')

    /** **El valor de un dato, si es fórmula de verdad**: una matriz como lista pasa a `bmatrix`; fracciones, raíces… se componen. */
    fun valorComoFormula(valor: String): String? {
        val v = valor.trim().trim('$').trim()
        matrizDeLista(v)?.let { return it }
        return v.takeIf { listOf("\\frac", "\\sqrt", "\\begin", "\\int", "\\sum", "\\lim", "\\vec").any { k -> v.contains(k) } }
    }

    /** `[[2,-1,0],[1,3,2]]` → `\begin{bmatrix} 2 & -1 & 0 \\ 1 & 3 & 2 \end{bmatrix}`. */
    fun matrizDeLista(s: String): String? {
        val t = s.trim()
        if (!t.startsWith("[[")) return null
        val v = runCatching { Json.parseToJsonElement(t) }.getOrNull() ?: run {
            // Con letras sueltas (`[[a, b], [c, d]]`): cada celda entre comillas.
            val out = StringBuilder(); val celda = StringBuilder()
            for (c in t) {
                if (c == '[' || c == ']' || c == ',') {
                    if (celda.isNotBlank()) out.append("\"${celda.toString().trim().replace("\"", "")}\"")
                    celda.clear(); out.append(c)
                } else celda.append(c)
            }
            runCatching { Json.parseToJsonElement(out.toString()) }.getOrNull()
        } ?: return null
        val filas = (v as? JsonArray)?.map { f ->
            (f as? JsonArray)?.map { x ->
                when {
                    x is JsonPrimitive && !x.isString -> x.doubleOrNull?.let(::numero) ?: x.content
                    x is JsonPrimitive -> x.content.trim()
                    else -> x.toString()
                }
            } ?: return null
        } ?: return null
        if (filas.isEmpty() || filas.any { it.isEmpty() }) return null
        return "\\begin{bmatrix} ${filas.joinToString(" \\\\ ") { it.joinToString(" & ") }} \\end{bmatrix}"
    }

    private fun normal(s: String) = s.replace("\\left", "").replace("\\right", "").replace("\\,", "").replace("\\displaystyle", "").filter { !it.isWhitespace() && it != '$' }

    private fun yaEsta(d: String, ecuaciones: List<String>): Boolean { val n = normal(d); return n.isNotEmpty() && ecuaciones.any { normal(it) == n } }

    /** **El enunciado sin las ecuaciones que van compuestas debajo** (salían dos veces). */
    fun sinLasEcuaciones(enunciado: String, ecuaciones: List<String>): String {
        if (ecuaciones.isEmpty()) return enunciado
        val normales = ecuaciones.map(::normal).filter { it.codePointCount(0, it.length) >= 3 }
        val t = StringBuilder()
        var resto = enunciado
        while (true) {
            val a = resto.indexOf('$'); if (a < 0) break
            val b = resto.indexOf('$', a + 1); if (b < 0) break
            t.append(resto, 0, a)
            if (normales.none { it == normal(resto.substring(a + 1, b)) }) t.append(resto, a, b + 1)
            resto = resto.substring(b + 1)
        }
        t.append(resto)
        var s = t.toString()
        for (e0 in ecuaciones) { val e = e0.trim(); if (e.codePointCount(0, e.length) >= 5 && s.contains(e)) s = s.replaceFirst(e, "") }
        val limpio = s.lines().map { l -> l.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ").replace(" .", ".").replace(" ,", ",") }.filter { it.isNotEmpty() }.joinToString("\n")
        return if (limpio.count { it.isLetter() } < 4) enunciado else limpio
    }

    /** Un trozo de LaTeX de bolsillo como texto llano: `20\ m/s^2` → `20 m/s²`. */
    fun aTexto(s: String): String {
        var t = s.trim()
        for ((de, a) in listOf("\\text{" to "", "\\mathrm{" to "", "\\," to " ", "\\ " to " ", "\\;" to " ", "^{\\circ}" to "°", "^\\circ" to "°",
            "\\circ" to "°", "\\cdot" to "·", "\\times" to "×", "\\theta" to "θ", "\\alpha" to "α", "\\mu" to "μ", "\\Omega" to "Ω",
            "\\pi" to "π", "\\Delta" to "Δ", "^2" to "²", "^3" to "³", "_0" to "₀", "_1" to "₁", "_2" to "₂", "{" to "", "}" to "", "$" to "")) t = t.replace(de, a)
        return t.replace("\\", "")
    }

    /** Encoge y coloca unos elementos (de la figura) dentro de [caja] (PC `encajar`). */
    fun encajar(els: List<Element>, caja: DoubleArray): List<Element> {
        if (els.isEmpty()) return els
        var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
        for (e in els) {
            var ex0 = e.x; var ey0 = e.y; var ex1 = e.x + e.width; var ey1 = e.y + e.height
            e.points?.forEach { p -> ex0 = min(ex0, e.x + p.x); ey0 = min(ey0, e.y + p.y); ex1 = max(ex1, e.x + p.x); ey1 = max(ey1, e.y + p.y) }
            x0 = min(x0, ex0); y0 = min(y0, ey0); x1 = max(x1, ex1); y1 = max(y1, ey1)
        }
        if (x0 > x1) return els
        val s = min(min(caja[2] / max(x1 - x0, 1.0), caja[3] / max(y1 - y0, 1.0)), 1.0)
        val dx = caja[0] + (caja[2] - (x1 - x0) * s) / 2.0; val dy = caja[1] + (caja[3] - (y1 - y0) * s) / 2.0
        return els.map { e ->
            e.copy(x = dx + (e.x - x0) * s, y = dy + (e.y - y0) * s, width = e.width * s, height = e.height * s,
                fontSize = e.fontSize?.times(s), unidad = e.unidad?.times(s), points = e.points?.map { com.forge.pixpin.motor.Pt(it.x * s, it.y * s) })
        }
    }

    /** Todo el problema (las piezas y la figura encajada), en un grupo. [figuraEnElementos]: de las figuras a elementos del lienzo. */
    fun elementos(p: Pedido, medir: MedidaDeTexto, figuraEnElementos: (List<Figura>) -> List<Element>): List<Element> {
        val (piezas, caja) = piezas(p, medir)
        val grupo = randomId()
        // La figura, con sus figuras de siempre, encajada en su recuadro (fuera del grupo, como en el PC).
        val figura = caja?.let { encajar(figuraEnElementos(p.figura), it) }.orEmpty()
        return piezas.map { it.copy(groupIds = listOf(grupo)) } + figura
    }

    /** **Si el pedido es pasar en limpio o resolver un problema** (con foto: se le pega el OCR). */
    fun pideProblema(pedido: String): Boolean {
        val q = Acciones.normalizar(pedido)
        return listOf("problema", "ejercicio", "enunciado", "en limpio", "a limpio", "reescrib", "transcrib", "resuelve", "resolver", "resuelvelo", "tarea de mate", "examen").any { q.contains(it) }
    }

    /** **Lo que el OCR leyó en la foto**, para pegarlo al mensaje del usuario (sin texto, se le dice: así no inventa). */
    fun pistaDeOcr(lineas: List<String>): String {
        val texto = lineas.map { it.trim() }.filter { it.isNotEmpty() }
        if (texto.sumOf { l -> l.count { it.isLetterOrDigit() } } < 8) return "\n\n[OCR de la foto: no se encontro texto legible. Si no ves un problema en la imagen, dilo; no lo inventes.]"
        return "\n\n[Texto de la foto leido por OCR (puede tener errores; las formulas, mejor leelas de la imagen):]\n${cp(texto.joinToString("\n"), 2000)}"
    }
}
