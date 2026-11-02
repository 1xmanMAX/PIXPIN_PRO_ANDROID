package com.forge.pixpin.motor

import com.forge.pixpin.motormd.AnclaDeTexto
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * **Las marcas sobre el texto de un Word, un libro o un PDF** (2-nov-2026, puerto de
 * `pindesk-motor2d/src/marcas_texto.rs` del PC, §3.9): no hay archivo nuevo, cada marca es dibujo
 * de la capa de su documento, en sus unidades, con el grupo `marca-texto-<ms>` en todos sus
 * elementos (uno por renglón). Así viaja, se exporta y se imprime como cualquier trazo.
 *
 * - Resaltar: rectángulo con un aire de 0,12 del alto, relleno del color al 40 %, sin borde.
 * - Tapar: lo mismo al 100 %.
 * - Subrayar: raya a `y + h + g` (`g = max(0,09h, 1,5)`); tachar, a `y + 0,55h`.
 * - Ondulada: una ola cada cuatro pasos de `g`, amplitud `g`, grosor `0,8g`.
 */
object MarcasDeTextoEnTinta {
    private const val AIRE = 0.12
    private const val OPACIDAD_RESALTADO = 40

    /** Una caja de renglón `[x0, y0, x1, y1]` en unidades de la capa. */
    data class Caja(val x0: Double, val y0: Double, val x1: Double, val y1: Double)

    fun grupoNuevo(ahoraMs: Long) = "${AnclaDeTexto.GRUPO_TINTA}-$ahoraMs"

    fun esMarca(e: Element) = e.groupIds.any { it.startsWith(AnclaDeTexto.GRUPO_TINTA) }

    /** Los puntos de la raya ondulada (`onda` del PC). */
    internal fun onda(x0: Double, x1: Double, y: Double, amplitud: Double, paso0: Double): List<Pair<Double, Double>> {
        val paso = max(paso0, 0.5)
        val n = max(ceil((x1 - x0) / paso).toInt(), 1)
        return (0..n).map { k ->
            val x = min(x0 + k * paso, x1)
            val fase = k / 4.0 * 2 * PI
            x to y + amplitud * sin(fase)
        }
    }

    /** **Los elementos de una marca** sobre [cajas] (una por renglón). [color]: `#RRGGBB`. */
    fun elementos(marca: String, color: String, cajas: List<Caja>, grupo: String, ahora: Long = System.currentTimeMillis()): List<Element> =
        cajas.filter { it.x1 > it.x0 && it.y1 > it.y0 && listOf(it.x0, it.y0, it.x1, it.y1).all(Double::isFinite) }.map { c ->
            val x = c.x0
            val y = c.y0
            val w = c.x1 - c.x0
            val h = c.y1 - c.y0
            val grosor = max(h * 0.09, 1.5)
            when (marca) {
                "resaltar", "tapar" -> {
                    val aire = h * AIRE
                    newElement(
                        ElementType.RECTANGLE, x - aire, y - aire,
                        ItemStyle(strokeColor = Element.TRANSPARENT, backgroundColor = color, fillStyle = FillStyle.SOLID, strokeWidth = 1.0, roughness = 0, opacity = if (marca == "tapar") 100 else OPACIDAD_RESALTADO, roundness = null),
                        w + 2 * aire, h + 2 * aire
                    ).copy(groupIds = listOf(grupo), updated = ahora)
                }
                else -> {
                    val altura = if (marca == "tachar") y + h * 0.55 else y + h + grosor
                    val puntos = if (marca == "ondulada") onda(x, x + w, altura, grosor, grosor) else listOf(x to altura, x + w to altura)
                    val arriba = puntos.minOf { it.second }
                    val abajo = puntos.maxOf { it.second }
                    newElement(
                        ElementType.LINE, x, arriba,
                        ItemStyle(strokeColor = color, strokeWidth = if (marca == "ondulada") grosor * 0.8 else grosor, roughness = 0, roundness = null, startArrowhead = null, endArrowhead = null),
                        w, abajo - arriba
                    ).copy(
                        points = puntos.map { Pt(it.first - x, it.second - arriba) },
                        groupIds = listOf(grupo), updated = ahora
                    )
                }
            }
        }

    /** Si [e] (una marca) toca alguna de las [cajas]. */
    fun toca(e: Element, cajas: List<Caja>): Boolean {
        val x0 = min(e.x, e.x + e.width)
        val x1 = max(e.x, e.x + e.width)
        val y0 = min(e.y, e.y + e.height)
        val y1 = max(e.y, e.y + e.height)
        return cajas.any { c -> x0 < c.x1 && c.x0 < x1 && y0 < c.y1 && c.y0 < y1 }
    }

    /** Los grupos de marca que tocan las [cajas]: se quitan enteros (todos sus renglones). */
    fun gruposQueTocan(elementos: List<Element>, cajas: List<Caja>): Set<String> =
        elementos.filter { !it.isDeleted && esMarca(it) && toca(it, cajas) }
            .flatMap { e -> e.groupIds.filter { it.startsWith(AnclaDeTexto.GRUPO_TINTA) } }.toSet()

    /** El color `#RRGGBB` del índice [k] (los cinco del PC; el amarillo de serie). */
    fun color(k: Int): String = AnclaDeTexto.COLORES.getOrElse(k) { AnclaDeTexto.COLORES[4] }
}
