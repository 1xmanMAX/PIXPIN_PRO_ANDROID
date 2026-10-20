package com.forge.pixpin.ia

import com.forge.pixpin.motor.Graficas
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * **Cronogramas** (`cronograma`, PC `pixpin-ia/src/cronograma.rs`): un diagrama de Gantt limpio en
 * el lienzo —escala de tiempo, una fila por tarea con su barra, hitos como rombos y flechas de
 * dependencia— hecho de figuras normales en un grupo. No es el elemento cronograma retirado: es
 * dibujo, y por eso se ve igual en todas partes.
 *
 * Las fechas las cuenta el teléfono, no el modelo: que no estén al revés, que las dependencias
 * existan y no hagan un círculo.
 */
object CronogramaIa {
    const val NOMBRE = "cronograma"
    const val MAX_TAREAS = 40
    const val MAX_PERIODOS = 3700L
    private val COLORES = listOf("#1971c2", "#2f9e44", "#f08c00", "#6741d9", "#0c8599", "#c2255c", "#e03131", "#868e96")
    private const val GRIS = "#868e96"
    private const val NEGRO = "#1e1e1e"

    data class Tarea(val nombre: String, val inicio: Long, val fin: Long, val hito: Boolean, val depende: MutableList<Int>, val color: String)
    data class Cronograma(val titulo: String, val tareas: List<Tarea>, val fechas: Boolean, val unidad: String)

    private fun mal(m: String) = ErrorHerramienta.Parametro(m)

    /** Días desde 1970-01-01 (Howard Hinnant, `days_from_civil`). */
    fun diasDe(a0: Long, m: Long, d: Long): Long {
        val a = if (m <= 2) a0 - 1 else a0
        val era = (if (a >= 0) a else a - 399) / 400
        val ae = a - era * 400
        val dm = (153 * (m + if (m > 2) -3 else 9) + 2) / 5 + d - 1
        val de = ae * 365 + ae / 4 - ae / 100 + dm
        return era * 146_097 + de - 719_468
    }

    fun fechaDe(z0: Long): Triple<Long, Long, Long> {
        val z = z0 + 719_468
        val era = (if (z >= 0) z else z - 146_096) / 146_097
        val de = z - era * 146_097
        val ae = (de - de / 1460 + de / 36_524 - de / 146_096) / 365
        val a = ae + era * 400
        val da = de - (365 * ae + ae / 4 - ae / 100)
        val mp = (5 * da + 2) / 153
        val d = da - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        return Triple(if (m <= 2) a + 1 else a, m, d)
    }

    /** `2026-11-02`, `2026/11/02` o `02/11/2026`. */
    fun fecha(t: String): Long? {
        val p = t.trim().split('-', '/', '.').map { it.trim().toLongOrNull() ?: return null }
        if (p.size != 3) return null
        val (a, m, d) = when {
            p[0] > 999 -> Triple(p[0], p[1], p[2])
            p[2] > 999 -> Triple(p[2], p[1], p[0])
            else -> return null
        }
        if (m !in 1..12 || d !in 1..31 || a !in 1900..2200) return null
        val dias = diasDe(a, m, d)
        return dias.takeIf { fechaDe(it) == Triple(a, m, d) }
    }

    private sealed class Momento { data class Fecha(val d: Long) : Momento(); data class Numero(val d: Long) : Momento() }

    private fun momento(v: JsonElement?, donde: String, campo: String): Momento? = when {
        v == null || v is JsonNull -> null
        v is JsonPrimitive && !v.isString -> {
            val x = v.doubleOrNull?.takeIf { it.isFinite() && abs(it) <= 1e5 } ?: throw mal("$donde: «$campo» fuera de rango")
            Momento.Numero(Math.round(x))
        }
        v is JsonPrimitive -> v.content.trim().toDoubleOrNull()?.let { momento(JsonPrimitive(it), donde, campo) }
            ?: fecha(v.content)?.let { Momento.Fecha(it) }
            ?: throw mal("$donde: «$campo» = «${v.content}» no es una fecha (AAAA-MM-DD) ni un numero")
        else -> throw mal("$donde: «$campo» tiene que ser una fecha o un numero")
    }

    private fun texto(v: JsonElement?) = (v as? JsonPrimitive)?.takeIf { it.isString }?.content

    fun validar(args: JsonObject, avisos: MutableList<String>): Cronograma {
        val lista = args["tareas"] as? JsonArray ?: throw mal("falta «tareas» (una lista con nombre, inicio y fin)")
        if (lista.isEmpty()) throw mal("«tareas» esta vacia")
        if (lista.size > MAX_TAREAS) throw mal("como mucho $MAX_TAREAS tareas; agrupa las pequenas")
        var fechas: Boolean? = null
        val tareas = mutableListOf<Tarea>()
        val depsCrudas = mutableListOf<List<JsonElement>>()
        for ((i, v) in lista.withIndex()) {
            val donde = "tarea ${i + 1}"
            val o = v as? JsonObject ?: throw mal("$donde: tiene que ser un objeto")
            val nombre = listOf("nombre", "tarea", "texto", "name").firstNotNullOfOrNull { k -> texto(o[k]) }?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { s -> s.codePoints().limit(80).toArray().let { String(it, 0, it.size) } }
                ?: throw mal("$donde: falta «nombre»")
            val inicio = momento(o["inicio"], donde, "inicio") ?: throw mal("$donde («$nombre»): falta «inicio»")
            val fin = momento(o["fin"], donde, "fin")
            val esFecha = inicio is Momento.Fecha
            if (fin != null && (fin is Momento.Fecha) != esFecha) throw mal("$donde: «inicio» y «fin» tienen que ser los dos fechas o los dos numeros")
            when (fechas) {
                null -> fechas = esFecha
                else -> if (fechas != esFecha) throw mal("todas las tareas tienen que usar fechas, o todas numeros")
            }
            val a = when (inicio) { is Momento.Fecha -> inicio.d; is Momento.Numero -> inicio.d }
            val hitoPedido = (o["hito"] as? JsonPrimitive)?.booleanOrNull ?: false
            val duracion = (o["duracion"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }
            // Las fechas cuentan el último día entero: del 2 al 6 son 5 días.
            val b = when {
                fin is Momento.Fecha -> fin.d + 1
                fin is Momento.Numero -> fin.d
                duracion != null && duracion >= 0.0 && duracion <= MAX_PERIODOS -> a + Math.round(duracion)
                duracion != null -> throw mal("$donde: «duracion» = ${numeroRust(duracion)} no vale")
                hitoPedido -> a
                else -> throw mal("$donde («$nombre»): falta «fin» (o «duracion», o «hito»:true)")
            }
            val (bb, hito) = when {
                hitoPedido -> a to true
                b < a || (esFecha && b <= a) -> throw mal("$donde («$nombre»): las fechas estan al reves: termina antes de empezar")
                b == a -> { avisos += "$donde («$nombre»): dura cero, se pone como hito"; a to true }
                else -> b to false
            }
            val color = texto(o["color"])?.let(Herramientas::color) ?: COLORES[i % COLORES.size]
            depsCrudas += when (val d = o["depende_de"] ?: o["dependencias"] ?: o["despues_de"]) {
                is JsonArray -> d.toList()
                null, is JsonNull -> emptyList()
                else -> listOf(d)
            }
            tareas += Tarea(nombre, a, bb, hito, mutableListOf(), color)
        }
        // Las dependencias, por nombre (sin mayúsculas) o por número (de 1).
        val porNombre = HashMap<String, Int>()
        for ((i, t) in tareas.withIndex()) porNombre[t.nombre.lowercase()] = i
        for ((i, deps) in depsCrudas.withIndex()) {
            for (d in deps) {
                val j = when {
                    d is JsonPrimitive && !d.isString -> d.longOrNull?.takeIf { it in 1..tareas.size }?.let { (it - 1).toInt() }
                    d is JsonPrimitive -> porNombre[d.content.trim().lowercase()]
                        ?: d.content.trim().toIntOrNull()?.takeIf { it in 1..tareas.size }?.let { it - 1 }
                    else -> null
                } ?: throw mal("tarea ${i + 1} («${tareas[i].nombre}»): depende de $d, que no es ninguna tarea")
                if (j == i) throw mal("tarea ${i + 1} («${tareas[i].nombre}») depende de si misma")
                if (j !in tareas[i].depende) tareas[i].depende += j
            }
        }
        // Sin círculos (Kahn).
        val n = tareas.size
        val entrantes = tareas.map { it.depende.size }.toMutableList()
        val listas = (0 until n).filter { entrantes[it] == 0 }.toMutableList()
        var vistas = 0
        while (listas.isNotEmpty()) {
            val k = listas.removeAt(listas.size - 1)
            vistas++
            for ((i, t) in tareas.withIndex()) if (k in t.depende) { entrantes[i]--; if (entrantes[i] == 0) listas += i }
        }
        if (vistas < n) throw mal("las dependencias hacen un circulo (A depende de B y B de A)")
        for (t in tareas) for (j in t.depende) {
            if ((t.inicio < tareas[j].fin && !tareas[j].hito) || t.inicio < tareas[j].inicio) {
                avisos += "«${t.nombre}» empieza antes de que acabe «${tareas[j].nombre}», de la que depende"
            }
        }
        val desde = tareas.minOf { it.inicio }; val hasta = tareas.maxOf { it.fin }
        if (hasta - desde > MAX_PERIODOS) throw mal("el cronograma dura ${hasta - desde} periodos; como mucho $MAX_PERIODOS")
        val unidad = when (texto(args["unidad"])?.let(Acciones::normalizar)) {
            "semanas", "semana" -> "semanas"
            "meses", "mes" -> "meses"
            else -> "dias"
        }
        val titulo = (texto(args["titulo"]) ?: "").trim().let { s -> s.codePoints().limit(120).toArray().let { String(it, 0, it.size) } }
        return Cronograma(titulo, tareas, fechas ?: false, unidad)
    }

    /** Un `f64` como lo escribe Rust (`{d}`): sin «.0» si es entero. */
    private fun numeroRust(d: Double) = if (d == Math.floor(d) && abs(d) < 1e15) d.toLong().toString() else d.toString()

    private val MESES = listOf("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic")

    /** Las marcas de la escala: (periodo, rótulo). */
    fun marcas(c: Cronograma, desde: Long, hasta: Long): List<Pair<Long, String>> {
        val largo = max(hasta - desde, 1)
        val v = mutableListOf<Pair<Long, String>>()
        if (c.fechas) {
            if (largo <= 21) {
                for (d in desde..hasta) { val (_, m, dd) = fechaDe(d); v += d to "$dd/$m" }
            } else if (largo <= 120) {
                // Los lunes (el 1970-01-01 fue jueves).
                var d = desde + (7 - Math.floorMod(desde + 3, 7L)) % 7
                while (d <= hasta) { val (_, m, dd) = fechaDe(d); v += d to "$dd/$m"; d += 7 }
            } else {
                var (a, m, _) = fechaDe(desde)
                while (true) {
                    val d = diasDe(a, m, 1)
                    if (d > hasta) break
                    if (d >= desde) v += d to (if (m == 1L || v.isEmpty()) "${MESES[(m - 1).toInt()]} $a" else MESES[(m - 1).toInt()])
                    m++
                    if (m > 12) { m = 1; a++ }
                }
            }
            return v
        }
        val paso = max(Graficas.pasoBonito(largo.toDouble()), 1.0).toLong()
        val letra = when (c.unidad) { "semanas" -> "S"; "meses" -> "M"; else -> "D" }
        var p = desde - Math.floorMod(desde, paso)
        while (p <= hasta) { if (p >= desde) v += p to "$letra$p"; p += paso }
        return v
    }

    /** **El Gantt** en el lienzo de 800×600 (PC `elementos`). */
    internal fun elementos(f: Lienzo.Fabrica, c: Cronograma) {
        val antes = f.grupo
        f.grupo = f.nombreDeGrupo("cronograma")
        val desde = c.tareas.minOf { it.inicio }
        val hasta = max(c.tareas.maxOf { it.fin + if (it.hito && c.fechas) 1 else 0 }, desde + 1)
        val x0 = 210.0; val x1 = 780.0
        val arriba = if (c.titulo.isEmpty()) 20.0 else 64.0
        val yEscala = arriba
        val yFilas = arriba + 34.0
        val n = c.tareas.size.toDouble()
        val fila = ((580.0 - yFilas) / n).coerceIn(18.0, 40.0)
        fun xDe(p: Long) = x0 + (p - desde).toDouble() / (hasta - desde).toDouble() * (x1 - x0)
        fun liso() {
            val e = f.salida.lastOrNull() ?: return
            e["roughness"] = JsonPrimitive(0)
            if ((e["type"] as? JsonPrimitive)?.content == "text") e["fontFamily"] = JsonPrimitive(2)
        }
        fun ultimo(k: String, v: JsonElement) { f.salida.lastOrNull()?.set(k, v) }
        if (c.titulo.isNotEmpty()) { f.texto(400.0, 14.0, c.titulo, 26.0, NEGRO, "center"); liso() }
        val yFin = yFilas + fila * n
        // Las bandas de las filas, una sí y otra no.
        for (i in c.tareas.indices step 2) {
            f.caja("rectangle", doubleArrayOf(20.0, yFilas + fila * i, x1 - 20.0, fila), "#f1f3f5", "#f1f3f5")
            liso(); ultimo("strokeWidth", JsonPrimitive(1))
        }
        // La escala: rayas finas y sus rótulos.
        val ms = marcas(c, desde, hasta)
        val cada = max(ceil(ms.size * 46.0 / (x1 - x0)).toInt(), 1)
        for ((k, par) in ms.withIndex()) {
            val (p, rot) = par
            val x = xDe(p)
            f.trazo("line", listOf(x to yEscala + 22.0, x to yFin), "#ced4da", null)
            liso(); ultimo("strokeWidth", JsonPrimitive(1))
            if (k % cada == 0) { f.texto(x, yEscala, rot, 12.0, GRIS, "center"); liso() }
        }
        f.trazo("line", listOf(x0 to yEscala + 22.0, x1 to yEscala + 22.0), GRIS, null)
        liso()
        // Las tareas: su nombre a la izquierda y su barra (o su rombo).
        val extremos = mutableListOf<Pair<Pair<Double, Double>, Pair<Double, Double>>>()
        for ((i, t) in c.tareas.withIndex()) {
            val y = yFilas + fila * i
            val medio = y + fila / 2.0
            val tam = (fila * 0.42).coerceIn(10.0, 15.0)
            val maximo = ((x0 - 34.0) / (tam * 0.6)).toInt()
            val cps = t.nombre.codePoints().toArray()
            val rotulo = if (cps.size > maximo) String(cps, 0, max(maximo - 1, 0)) + "…" else t.nombre
            f.texto(28.0, medio - tam * 0.62, rotulo, tam, NEGRO, "left")
            liso()
            if (t.hito) {
                // Un hito con fecha cae al final de su día (cuando se entrega).
                val x = xDe(t.inicio + if (c.fechas) 1 else 0)
                val lado = min(fila * 0.6, 18.0)
                f.caja("diamond", doubleArrayOf(x - lado / 2.0, medio - lado / 2.0, lado, lado), NEGRO, NEGRO)
                liso()
                extremos += (x - lado / 2.0 to medio) to (x + lado / 2.0 to medio)
            } else {
                val xa = xDe(t.inicio); val xb = xDe(t.fin)
                val alto = fila * 0.56
                f.caja("rectangle", doubleArrayOf(xa, medio - alto / 2.0, max(xb - xa, 3.0), alto), t.color, t.color)
                liso()
                ultimo("roundness", buildJsonObject { put("type", 3) })
                ultimo("strokeWidth", JsonPrimitive(1))
                extremos += (xa to medio) to (xb to medio)
            }
        }
        // Las dependencias: del final de una al principio de la otra, en codo.
        for ((i, t) in c.tareas.withIndex()) {
            for (j in t.depende) {
                val a = extremos[j].second
                val b = extremos[i].first
                val codo = a.first + 8.0
                val puntos = when {
                    t.hito && abs(b.first - a.first) < 24.0 -> {
                        // Un hito justo al acabar lo anterior: una flecha que baja.
                        val x = (extremos[i].first.first + extremos[i].second.first) / 2.0
                        listOf(x to a.second + fila * 0.3, x to b.second - fila * 0.3)
                    }
                    b.first - a.first >= 16.0 -> listOf(a, codo to a.second, codo to b.second, b.first - 1.0 to b.second)
                    else -> {
                        // La siguiente empieza antes: se baja por detrás.
                        val yPasa = (a.second + b.second) / 2.0
                        listOf(a, a.first + 8.0 to a.second, a.first + 8.0 to yPasa, b.first - 10.0 to yPasa, b.first - 10.0 to b.second, b.first - 1.0 to b.second)
                    }
                }
                f.trazo("arrow", puntos, "#495057", null)
                liso()
                ultimo("strokeWidth", JsonPrimitive(1.5))
                ultimo("endArrowhead", JsonPrimitive("triangle"))
            }
        }
        f.grupo = antes
    }
}
