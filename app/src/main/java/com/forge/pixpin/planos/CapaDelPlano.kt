package com.forge.pixpin.planos

import com.forge.pixpin.motor.Element
import com.forge.pixpin.motor.ElementType
import com.forge.pixpin.motor.ItemStyle
import com.forge.pixpin.motor.Pt
import com.forge.pixpin.motor.Scene
import com.forge.pixpin.motor.newElement
import com.forge.pixpin.sincro.AnotacionesDelAdjunto

/**
 * **Lo hecho sobre un plano, en archivos que viajan al PC** (9-oct-2026, el usuario: «todo ponlo de
 * forma que sea sincronizable con la PC Windows»).
 *
 * No hay archivos nuevos: va en los de siempre de lo anotado sobre un adjunto, los que ya viajan en
 * los dos sentidos (`TERMINACIONES` de `pixpin-sincro/src/anotado.rs` del PC):
 *
 * - `anot-<uid>.excalidraw.gz` — la capa, con el motor de siempre: lo anotado (texto, trazos…), y
 *   también **las cotas** (rayas del grupo [GRUPO_COTA]) y **los marcos para imprimir** (marcos del
 *   motor, grupo [GRUPO_MARCO]). Como en el PC sus marcas de texto («marca-texto»): dibujo normal,
 *   reconocido por su grupo. Así se juntan al sincronizar como cualquier figura (por id y versión),
 *   y lo borrado viaja como borrado (con `isDeleted`), no desaparece sin más.
 * - `anot-<uid>.hoja` — **el marco de la tinta** (formato acordado con el PC el 29-sep): aquí, la
 *   caja del plano (sus coordenadas de verdad) **en unidades de la capa**. Quien lee lleva ese
 *   rectángulo a la caja del plano que ve él, y todo cae en su sitio aunque calcule el centro del
 *   plano un poco distinto o use otra escala de capa.
 *
 * Las coordenadas del plano aquí son **relativas al origen del modelo** ([ModeloCad.origenX]…).
 */
object CapaDelPlano {
    const val GRUPO_COTA = "plano-cota"
    const val GRUPO_MARCO = "plano-marco"
    private const val NARANJA = "#ff8a00"
    /** Lo que pueden diferir dos formas iguales (unidades de capa): los decimales del f32 del PC. */
    private const val TOLERANCIA = 1e-3

    /** Cómo cae la capa en el plano: x = [ceroX] + xCapa · [u]; y = [ceroY] − yCapa · [u] (la y de la capa baja). */
    data class Encaje(val u: Double, val ceroX: Double, val ceroY: Double) {
        fun aCapaX(x: Double) = (x - ceroX) / u
        fun aCapaY(y: Double) = -(y - ceroY) / u
        fun aPlanoX(x: Double) = ceroX + x * u
        fun aPlanoY(y: Double) = ceroY - y * u
    }

    /**
     * **Las unidades del fichero respecto de las de la memoria**: `fichero = memoria · k + (dx, dy)`
     * (`unidades_entre` del PC, 12-oct-2026).
     *
     * Por qué (13-oct-2026, el usuario: «antes las tintas estaban escaladas y ahora todo está
     * sobredimensionado, con una línea se tapa todo el plano»): una capa escrita con la unidad de
     * antes —la milésima del lado del plano, la del PC hasta el 12-oct— mide cientos de píxeles por
     * unidad al acercarse, y las medidas fijas del motor (radio mínimo del lápiz, el tramo final
     * que toma por ruido) engordan el trazo hasta tapar el plano. Como el PC: **en memoria la capa
     * va siempre en la unidad de la letra** ([EscalaDeLaCapa.deLasLetras]) y el fichero se queda en
     * la suya; lo que no se toca vuelve tal cual, número a número.
     */
    data class Unidades(val k: Double, val dx: Double, val dy: Double) {
        /** Casi las mismas: no hace falta convertir nada. */
        val iguales: Boolean get() = kotlin.math.abs(k - 1.0) < 1e-6 && kotlin.math.abs(dx) < 1e-6 && kotlin.math.abs(dy) < 1e-6

        /** De la memoria al fichero. */
        fun alFichero(e: Element): Element = transformar(e, k, dx, dy)

        /** Del fichero a la memoria. */
        fun aLaMemoria(e: Element): Element = transformar(e, 1.0 / k, -dx / k, -dy / k)
    }

    fun unidadesEntre(memoria: Encaje, fichero: Encaje): Unidades = Unidades(
        k = memoria.u / fichero.u,
        dx = (memoria.ceroX - fichero.ceroX) / fichero.u,
        dy = (fichero.ceroY - memoria.ceroY) / fichero.u
    )

    /** [e] con sus medidas por [k] y corrido ([dx], [dy]): posición, caja, puntos, grosor y letra. */
    private fun transformar(e: Element, k: Double, dx: Double, dy: Double): Element = e.copy(
        x = e.x * k + dx,
        y = e.y * k + dy,
        width = e.width * k,
        height = e.height * k,
        points = e.points?.map { Pt(it.x * k, it.y * k) },
        huecos = e.huecos?.map { anillo -> anillo.map { Pt(it.x * k, it.y * k) } },
        strokeWidth = e.strokeWidth * k,
        fontSize = e.fontSize?.times(k)
    )

    /**
     * La escena del fichero pasada a la memoria, y lo que hace falta para devolverla: cada elemento
     * tal como quedó en memoria al leerlo, junto al original. Al guardar, lo que siga igual que al
     * leerlo vuelve con sus números exactos (sin versión nueva ni ida y vuelta de decimales).
     */
    class EnMemoria(val escena: Scene, val unidades: Unidades, private val leidos: Map<String, Pair<Element, Element>>) {
        /** La escena de [enMemoria] en las unidades del fichero. */
        fun alFichero(enMemoria: Scene): Scene {
            if (unidades.iguales) return enMemoria
            return enMemoria.copy(elements = enMemoria.elements.map { el ->
                val (comoSeLeyo, original) = leidos[el.id] ?: return@map unidades.alFichero(el)
                if (el == comoSeLeyo) original else unidades.alFichero(el)
            })
        }
    }

    /** La escena [s], escrita en las unidades de [de], pasada a las de [a] (sin subir versiones). */
    fun cambiarDeUnidades(s: Scene, de: Encaje, a: Encaje): Scene {
        val u = unidadesEntre(a, de)
        return if (u.iguales) s else s.copy(elements = s.elements.map(u::aLaMemoria))
    }

    /**
     * **Lo que se guarda, juntado con lo que hay en el disco** si cambió desde que se leyó (13-oct-2026,
     * la guía del PC: el visor pisaba lo que la sincronización traía del PC con el plano abierto, y
     * la vuelta siguiente lo tomaba por borrado en los dos). Con la fusión de la sincronización:
     * [base] es el fichero tal como se leyó o escribió aquí la última vez, [mio] lo que se va a
     * escribir y [enDisco] lo que hay ahora; los tres en las mismas unidades.
     */
    fun juntarConElDisco(base: String?, mio: String, enDisco: String): String {
        fun j(t: String) = kotlinx.serialization.json.Json.parseToJsonElement(t)
        return runCatching {
            com.forge.pixpin.sincro.Fusion.json(base?.let(::j), j(mio), j(enDisco), com.forge.pixpin.sincro.Fusion.Criterio(mioMasNuevo = true))?.toString()
        }.getOrNull() ?: mio
    }

    fun aLaMemoria(fichero: Scene, unidades: Unidades): EnMemoria {
        if (unidades.iguales) return EnMemoria(fichero, unidades, emptyMap())
        val leidos = HashMap<String, Pair<Element, Element>>()
        val convertidos = fichero.elements.map { el -> unidades.aLaMemoria(el).also { leidos[el.id] = it to el } }
        return EnMemoria(fichero.copy(elements = convertidos), unidades, leidos)
    }

    /** El marco de la tinta: la caja del plano ([caja], relativa al origen) en unidades de la capa. */
    fun hojaDe(e: Encaje, caja: FloatArray): AnotacionesDelAdjunto.Marco =
        AnotacionesDelAdjunto.Marco(e.aCapaX(caja[0].toDouble()), e.aCapaY(caja[3].toDouble()), e.aCapaX(caja[2].toDouble()), e.aCapaY(caja[1].toDouble()))

    /** Al revés: el encaje que lleva el marco [hoja] (escrito aquí o en el PC) a la caja del plano. */
    fun encajeDe(hoja: AnotacionesDelAdjunto.Marco, caja: FloatArray): Encaje? {
        if (!hoja.valido()) return null
        val ancho = (caja[2] - caja[0]).toDouble()
        val alto = (caja[3] - caja[1]).toDouble()
        val u = when {
            ancho > 1e-12 -> ancho / hoja.ancho
            alto > 1e-12 -> alto / hoja.alto
            else -> return null
        }
        if (!(u > 0) || !u.isFinite()) return null
        return Encaje(u, caja[0] - hoja.x0 * u, caja[3] + hoja.y0 * u)
    }

    private fun esDelPlano(el: Element) = el.groupIds.any { it == GRUPO_COTA || it == GRUPO_MARCO }

    /** La capa sin las cotas ni los marcos (los pinta el visor a su manera, con sus medidas). */
    fun sinLoDelPlano(s: Scene): Scene = s.copy(elements = s.elements.filter { !esDelPlano(it) })

    /** Las cotas y los marcos guardados en la capa, en coordenadas del plano. */
    fun leer(s: Scene, e: Encaje): Pair<List<List<DoubleArray>>, List<ImprimirPlano.Marco>> {
        val cotas = ArrayList<List<DoubleArray>>()
        val marcos = ArrayList<ImprimirPlano.Marco>()
        for (el in s.elements) {
            if (el.isDeleted) continue
            if (GRUPO_COTA in el.groupIds) {
                val pts = el.points ?: continue
                if (pts.size >= 2) cotas += pts.map { p -> doubleArrayOf(e.aPlanoX(el.x + p.x), e.aPlanoY(el.y + p.y)) }
            } else if (GRUPO_MARCO in el.groupIds) {
                marcos += ImprimirPlano.Marco.entre(e.aPlanoX(el.x), e.aPlanoY(el.y), e.aPlanoX(el.x + el.width), e.aPlanoY(el.y + el.height))
            }
        }
        // Los marcos, en el orden de sus hojas (1, 2, 3…): el número va en el `name` del marco
        // (13-oct-2026, la guía del PC: dos marcos guardados a la vez salían con el mismo `updated`).
        // Los que no lo llevan (capas de antes) van detrás, por cuándo se pusieron.
        val orden = s.elements.filter { !it.isDeleted && GRUPO_MARCO in it.groupIds }
            .map { (it.name?.toIntOrNull() ?: Int.MAX_VALUE) to it.updated }
        return cotas to marcos.zip(orden).sortedWith(compareBy({ it.second.first }, { it.second.second })).map { it.first }
    }

    /**
     * La capa con estas [cotas] y estos [marcos]: lo que ya estaba igual se queda tal cual (mismo id,
     * misma versión); lo nuevo entra; lo que ya no está se marca borrado. El id sale de la forma,
     * así que dos aparatos que hacen la misma cota no la duplican.
     */
    fun con(s: Scene, e: Encaje, cotas: List<List<DoubleArray>>, marcos: List<ImprimirPlano.Marco>, ahora: Long): Scene {
        val queridos = LinkedHashMap<String, Element>()
        for (c in cotas) if (c.size >= 2) elementoDeCota(c, e, ahora).let { queridos[it.id] = it }
        marcos.forEachIndexed { i, m -> elementoDeMarco(m, e, ahora + i, i + 1).let { queridos[it.id] = it } }
        val vistos = HashSet<String>()
        // Las que casan por id, primero: una que case por forma no puede quitarles la suya.
        val porId = s.elements.filter { esDelPlano(it) && it.id in queridos }.mapTo(HashSet()) { it.id }
        val fuera = s.elements.map { el ->
            if (!esDelPlano(el)) return@map el
            // **Por el id o por la forma** (13-oct-2026, guía del PC): una cota llegada del PC se
            // guardó en f32 y, leída y vuelta a medir, su id puede salir otro por los decimales. Sin
            // esto se marcaba borrada y nacía otra igual en cada guardado. Si coincide con una querida
            // a menos de una milésima de unidad de capa, es ella, y se queda con su id.
            val id = if (el.id in queridos) el.id
            else queridos.entries.firstOrNull { (k, q) -> k !in vistos && k !in porId && mismaForma(el, q) }?.key
            if (id != null) vistos += id
            val querido = id?.let { queridos.getValue(it) }
            when {
                querido != null && el.isDeleted -> querido.copy(id = el.id, version = el.version + 1)
                // Sigue; solo cambia si cambió su número de hoja (se quitó un marco de delante).
                querido != null -> if (querido.name != el.name) el.copy(name = querido.name, version = el.version + 1, updated = ahora) else el
                el.isDeleted -> el
                else -> el.copy(isDeleted = true, version = el.version + 1, updated = ahora)
            }
        }
        return s.copy(elements = fuera + queridos.values.filter { it.id !in vistos })
    }

    /** Misma clase (cota o marco) y misma forma, a menos de [TOLERANCIA] unidades de capa. */
    private fun mismaForma(a: Element, b: Element): Boolean {
        if (a.type != b.type || a.groupIds != b.groupIds) return false
        fun cerca(x: Double, y: Double) = kotlin.math.abs(x - y) < TOLERANCIA
        if (GRUPO_MARCO in a.groupIds) return cerca(a.x, b.x) && cerca(a.y, b.y) && cerca(a.width, b.width) && cerca(a.height, b.height)
        val pa = a.points ?: return false
        val pb = b.points ?: return false
        if (pa.size != pb.size) return false
        return pa.indices.all { i -> cerca(a.x + pa[i].x, b.x + pb[i].x) && cerca(a.y + pa[i].y, b.y + pb[i].y) }
    }

    private fun huella(prefijo: String, numeros: List<Double>): String {
        var h = 0xcbf29ce484222325uL
        for (n in numeros) for (b in String.format(java.util.Locale.ROOT, "%.4f;", n).toByteArray()) {
            h = h xor b.toUByte().toULong(); h *= 0x100000001b3uL
        }
        return prefijo + "-" + h.toString(16)
    }

    private fun elementoDeCota(c: List<DoubleArray>, e: Encaje, ahora: Long): Element {
        val x0 = e.aCapaX(c[0][0]); val y0 = e.aCapaY(c[0][1])
        val pts = c.map { p -> Pt(e.aCapaX(p[0]) - x0, e.aCapaY(p[1]) - y0) }
        val minX = pts.minOf { it.x }; val maxX = pts.maxOf { it.x }; val minY = pts.minOf { it.y }; val maxY = pts.maxOf { it.y }
        return newElement(ElementType.LINE, x0, y0, ItemStyle(strokeColor = NARANJA, strokeWidth = 1.0), maxX - minX, maxY - minY)
            .copy(id = huella(GRUPO_COTA, c.flatMap { listOf(it[0], it[1]) }), points = pts, groupIds = listOf(GRUPO_COTA), updated = ahora)
    }

    private fun elementoDeMarco(m: ImprimirPlano.Marco, e: Encaje, ahora: Long, numero: Int): Element {
        val x = e.aCapaX(m.x0); val y = e.aCapaY(m.y1)
        return newElement(ElementType.FRAME, x, y, ItemStyle(strokeColor = "#3d8bff", strokeWidth = 1.0), m.ancho / e.u, m.alto / e.u)
            .copy(id = huella(GRUPO_MARCO, listOf(m.x0, m.y0, m.x1, m.y1)), groupIds = listOf(GRUPO_MARCO), updated = ahora, name = numero.toString())
    }
}
