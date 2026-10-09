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

    /** Cómo cae la capa en el plano: x = [ceroX] + xCapa · [u]; y = [ceroY] − yCapa · [u] (la y de la capa baja). */
    data class Encaje(val u: Double, val ceroX: Double, val ceroY: Double) {
        fun aCapaX(x: Double) = (x - ceroX) / u
        fun aCapaY(y: Double) = -(y - ceroY) / u
        fun aPlanoX(x: Double) = ceroX + x * u
        fun aPlanoY(y: Double) = ceroY - y * u
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
        // Los marcos, en el orden en que se pusieron (son las hojas 1, 2, 3…).
        val orden = s.elements.filter { !it.isDeleted && GRUPO_MARCO in it.groupIds }.map { it.updated }
        return cotas to marcos.zip(orden).sortedBy { it.second }.map { it.first }
    }

    /**
     * La capa con estas [cotas] y estos [marcos]: lo que ya estaba igual se queda tal cual (mismo id,
     * misma versión); lo nuevo entra; lo que ya no está se marca borrado. El id sale de la forma,
     * así que dos aparatos que hacen la misma cota no la duplican.
     */
    fun con(s: Scene, e: Encaje, cotas: List<List<DoubleArray>>, marcos: List<ImprimirPlano.Marco>, ahora: Long): Scene {
        val queridos = LinkedHashMap<String, Element>()
        for (c in cotas) if (c.size >= 2) elementoDeCota(c, e, ahora).let { queridos[it.id] = it }
        marcos.forEachIndexed { i, m -> elementoDeMarco(m, e, ahora + i).let { queridos[it.id] = it } }
        val vistos = HashSet<String>()
        val fuera = s.elements.map { el ->
            if (!esDelPlano(el)) return@map el
            vistos += el.id
            when {
                el.id in queridos && el.isDeleted -> queridos.getValue(el.id).copy(version = el.version + 1)
                el.id in queridos || el.isDeleted -> el
                else -> el.copy(isDeleted = true, version = el.version + 1, updated = ahora)
            }
        }
        return s.copy(elements = fuera + queridos.values.filter { it.id !in vistos })
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

    private fun elementoDeMarco(m: ImprimirPlano.Marco, e: Encaje, ahora: Long): Element {
        val x = e.aCapaX(m.x0); val y = e.aCapaY(m.y1)
        return newElement(ElementType.FRAME, x, y, ItemStyle(strokeColor = "#3d8bff", strokeWidth = 1.0), m.ancho / e.u, m.alto / e.u)
            .copy(id = huella(GRUPO_MARCO, listOf(m.x0, m.y0, m.x1, m.y1)), groupIds = listOf(GRUPO_MARCO), updated = ahora)
    }
}
