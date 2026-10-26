package com.forge.pixpin.ocr.indra

import com.forge.pixpin.guardados.qwen3.OrtPorJna
import com.forge.pixpin.ocr.ParrafosOcr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File

/**
 * **Indra con los pesos de verdad, en la JVM** (26-oct-2026, como `Qwen3DeVerdadTest` y
 * `lee_con_los_pesos_de_verdad` del PC): `PIXPIN_INDRA` (la carpeta con los ocho ficheros de
 * [com.forge.pixpin.ocr.CatalogoIndra]) y `PIXPIN_ORT` (la ruta de un `libonnxruntime.so` de esta
 * máquina). Sin ellos no corre.
 *
 * Prueba el canal entero del teléfono —preparar, enderezar, las cuatro sesiones por JNA, el LM, el
 * orden, la corrección— salvo el `.so` de ARM, sobre páginas pintadas aquí con la Carlito del APK.
 */
class IndraDeVerdadTest {

    private fun env(n: String) = System.getenv(n)?.takeIf { it.isNotBlank() }?.let(::File)

    private fun fuente(tam: Float): Font {
        val f = listOf(File("src/main/assets/fuentes-ttf/Carlito-Regular.ttf"), File("app/src/main/assets/fuentes-ttf/Carlito-Regular.ttf")).first { it.isFile }
        return Font.createFont(Font.TRUETYPE_FONT, f).deriveFont(tam)
    }

    /** Una página pintada: [lineas] de texto oscuro sobre [fondo], con [tam] px de letra. */
    private fun pagina(lineas: List<String>, ancho: Int, alto: Int, tam: Float, fondo: Color = Color.WHITE, tinta: Color = Color(20, 20, 20), grados: Double = 0.0): Gris {
        val img = BufferedImage(ancho, alto, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = fondo; g.fillRect(0, 0, ancho, alto)
        g.rotate(Math.toRadians(grados), ancho / 2.0, alto / 2.0)
        g.color = tinta; g.font = fuente(tam)
        for ((i, t) in lineas.withIndex()) g.drawString(t, (tam * 2).toInt(), (tam * 3 + i * tam * 1.8f).toInt())
        g.dispose()
        val argb = IntArray(ancho * alto)
        img.getRGB(0, 0, ancho, alto, argb, 0, ancho)
        return Gris.deArgb(ancho, alto, argb)
    }

    @Test
    fun `indra de verdad`() {
        val dir = env("PIXPIN_INDRA"); val ort = env("PIXPIN_ORT")
        assumeTrue("pide PIXPIN_INDRA y PIXPIN_ORT", dir != null && ort != null)
        assertTrue("faltan ficheros en $dir", MotorIndra.listo(dir!!))
        val t0 = System.nanoTime()
        MotorIndra.cargar(OrtPorJna.cargar(ort!!.absolutePath), dir, MotorIndra.Config(corregir = true, vocabulario = "Arequipa, E030", hilos = 4)).use { c ->
            System.err.println("carga ${(System.nanoTime() - t0) / 1_000_000} ms, ONNX Runtime ${c.modelos.versionDelRuntime}, LM ${c.canal.lm != null}, diccionario ${c.canal.lexico?.tamano}")
            val casos = listOf(
                Triple("captura clara", pagina(listOf("Ajustes de la captura", "Copiar el texto de una imagen", "La norma E030 de Arequipa"), 900, 400, 32f), listOf("Ajustes", "captura", "Copiar", "imagen", "E030", "Arequipa")),
                Triple("modo oscuro", pagina(listOf("fun leer(texto: String): Int", "return texto.length"), 900, 300, 30f, Color(30, 30, 30), Color(220, 220, 220)), listOf("leer", "texto", "return")),
                Triple("torcida 3 grados y gris", pagina(listOf("El análisis de la estructura", "con concreto armado para la", "edificación del colegio"), 1000, 500, 34f, Color(200, 200, 190), grados = 3.0), listOf("análisis", "estructura", "concreto", "armado")),
                Triple("en blanco", Gris.lisa(400, 100, 255), emptyList())
            )
            for ((nombre, g, espera) in casos) {
                val t = System.nanoTime()
                val p = c.canal.leer(c.modelos, g)
                p.aLaImagen(g.ancho, g.alto, 1f, 1f)
                val texto = ParrafosOcr.texto(MotorIndra.aLineas(p))
                System.err.println("$nombre (${(System.nanoTime() - t) / 1_000_000} ms, giro ${p.giro}, inclinación ${"%.1f".format(p.inclinacion)}, ${p.ms}): «$texto»")
                if (espera.isEmpty()) assertEquals("$nombre: «$texto»", "", texto)
                for (w in espera) assertTrue("$nombre: falta «$w» en «$texto»", texto.contains(w))
            }
        }
        val pico = File("/proc/self/status").takeIf { it.isFile }?.readLines()?.firstOrNull { it.startsWith("VmHWM") }.orEmpty()
        System.err.println(pico)
    }
}
