package com.forge.pixpin.motor

import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import org.junit.Test

/**
 * Volcado visual: rasteriza las figuras a PNG para poder MIRARLAS.
 *
 * No afirma nada; es una herramienta de inspección. Se salta si no se le dice
 * dónde escribir.
 */
class ShapeDumpTest {

    private fun element(
        type: ElementType,
        x: Double, y: Double, w: Double, h: Double,
        seed: Int,
        roundness: Roundness? = Roundness(Roundness.ADAPTIVE_RADIUS),
        roughness: Int = Element.ROUGHNESS_ARTIST
    ) = Element(
        id = "d$seed", type = type, x = x, y = y, width = w, height = h,
        seed = seed, roundness = roundness, roughness = roughness, strokeWidth = 2.0
    )

    private fun Path2D.Double.appendOps(ops: List<Op>) {
        for (op in ops) when (op) {
            is Op.Move -> moveTo(op.x, op.y)
            is Op.LineTo -> lineTo(op.x, op.y)
            is Op.CurveTo -> curveTo(op.x1, op.y1, op.x2, op.y2, op.x, op.y)
        }
    }

    @Test
    fun `volcado de figuras`() {
        val out = System.getenv("SHAPE_DUMP_DIR") ?: return
        File(out).mkdirs()

        val cases = buildList {
            for (seed in listOf(11111, 222222, 3333333, 44444444)) {
                add("cuadrado-$seed" to element(ElementType.RECTANGLE, 40.0, 40.0, 200.0, 200.0, seed))
                add("rect-$seed" to element(ElementType.RECTANGLE, 40.0, 40.0, 240.0, 140.0, seed))
                add("rombo-$seed" to element(ElementType.DIAMOND, 40.0, 40.0, 200.0, 200.0, seed))
                add("rombo-plano-$seed" to element(ElementType.DIAMOND, 40.0, 40.0, 240.0, 110.0, seed))
                add(
                    "cuadrado-pico-$seed" to
                        element(ElementType.RECTANGLE, 40.0, 40.0, 200.0, 200.0, seed, roundness = null)
                )
                add(
                    "rombo-pico-$seed" to
                        element(ElementType.DIAMOND, 40.0, 40.0, 200.0, 200.0, seed, roundness = null)
                )
            }
        }

        for ((name, e) in cases) {
            dump(File(out, "$name.png"), e, zoom = 1.0, w = 320, h = 260)
        }
        // Una esquina de cerca: a tamaño natural no se distingue si el chaflán
        // lo pone el muestreo del contorno o el trazo rugoso.
        dump(
            File(out, "zoom-esquina-cuadrado.png"),
            element(ElementType.RECTANGLE, 40.0, 40.0, 200.0, 200.0, 3333333),
            zoom = 4.0, w = 520, h = 420
        )
        dump(
            File(out, "zoom-esquina-rombo.png"),
            element(ElementType.DIAMOND, 40.0, 40.0, 200.0, 200.0, 3333333),
            zoom = 4.0, w = 520, h = 420, panX = -260.0
        )
        dumpLapiz(File(out, "lapiz.png"))
        println("volcadas ${cases.size + 3} figuras en $out")
    }

    /**
     * El lápiz: la mancha rellena que devuelve `getStroke`, no una línea.
     *
     * Se dibujan tres trazos con la misma forma y distinta velocidad —lo que
     * cambia es cuánto se separan los puntos de entrada— para ver el
     * adelgazamiento por presión simulada.
     */
    private fun dumpLapiz(file: File) {
        val img = BufferedImage(520, 420, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color.WHITE
        g.fillRect(0, 0, 520, 420)

        // Tres eses, muestreadas cada 4, 12 y 30 px: lento, medio y rápido.
        listOf(4.0, 12.0, 30.0).forEachIndexed { fila, paso ->
            val entrada = mutableListOf<Pt>()
            var s = 0.0
            while (s <= 400.0) {
                entrada += Pt(50 + s, 70 + fila * 130 + 45 * kotlin.math.sin(s / 60))
                s += paso
            }
            val contorno = getStroke(
                entrada, null,
                StrokeOptions(
                    size = 2.0 * FreedrawTuning.SIZE_FACTOR,
                    thinning = FreedrawTuning.THINNING,
                    smoothing = FreedrawTuning.SMOOTHING,
                    streamline = FreedrawTuning.STREAMLINE,
                    simulatePressure = true,
                    last = true
                )
            )
            val p = Path2D.Double()
            contorno.forEachIndexed { i, q ->
                if (i == 0) p.moveTo(q.x, q.y) else p.lineTo(q.x, q.y)
            }
            p.closePath()
            g.color = Color(30, 30, 30)
            g.fill(p)
        }
        g.dispose()
        ImageIO.write(img, "png", file)
    }

    private fun dump(
        file: File, e: Element, zoom: Double, w: Int, h: Int,
        panX: Double = 0.0, panY: Double = 0.0
    ) {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color.WHITE
        g.fillRect(0, 0, w, h)
        g.translate(panX, panY)
        g.scale(zoom, zoom)

        val geo = buildShapeGeometry(e)!!

        // El trazo rugoso primero, en negro...
        g.color = Color(30, 30, 30)
        g.stroke = BasicStroke((2.0 / zoom).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        val stroke = Path2D.Double()
        stroke.appendOps(geo.stroke)
        g.draw(stroke)

        // ...y encima la silueta lisa en rojo, con un punto por muestra: así se
        // ve si el contorno ya venía en chaflán o lo chaflana el trazo.
        g.color = Color(220, 40, 40)
        g.stroke = BasicStroke((0.8 / zoom).toFloat())
        val outline = Path2D.Double()
        geo.outline.forEachIndexed { i, p ->
            if (i == 0) outline.moveTo(p.x, p.y) else outline.lineTo(p.x, p.y)
        }
        outline.closePath()
        g.draw(outline)
        val r = 1.6 / zoom
        for (p in geo.outline) {
            g.fill(java.awt.geom.Ellipse2D.Double(p.x - r, p.y - r, r * 2, r * 2))
        }

        g.dispose()
        ImageIO.write(img, "png", file)
    }
}
