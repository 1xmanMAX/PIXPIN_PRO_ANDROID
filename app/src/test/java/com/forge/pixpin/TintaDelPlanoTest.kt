package com.forge.pixpin

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.forge.pixpin.motor.DrawTheme
import com.forge.pixpin.motor.Element
import com.forge.pixpin.motor.ElementType
import com.forge.pixpin.motor.ExcalidrawStore
import com.forge.pixpin.motor.PapelDeLaHoja
import com.forge.pixpin.motor.Pt
import com.forge.pixpin.motor.Renderer
import com.forge.pixpin.motor.Scene
import com.forge.pixpin.planos.TintaDelPlano
import com.forge.pixpin.sincro.AnotacionesDelAdjunto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.GZIPOutputStream

/** Apunta el color de cada trazo que se pinta. */
private class LienzoQueApuntaColores(b: Bitmap) : Canvas(b) {
    val colores = mutableListOf<Int>()
    override fun drawPath(path: Path, paint: Paint) { colores += paint.color }
    override fun drawLine(x0: Float, y0: Float, x1: Float, y1: Float, paint: Paint) { colores += paint.color }
}

/**
 * **La tinta blanca de un plano anotado de noche en el PC llegaba negra al teléfono**
 * (27-oct-2026, el usuario: «cuando anoto un plano de AutoCAD en la computadora en modo noche, la
 * tinta es blanca; al sincronizar llega negra al teléfono, aunque está en modo noche»).
 *
 * Los dos aparatos guardan el color elegido tal cual. El PC lo pinta contra el papel del plano y
 * solo lo adapta si es de noche (`plano_cad::anotar::papel`, `dibujo::tema::tinta`); el móvil lo
 * adaptaba contra el papel de la escena, que en la capa del plano es el blanco de serie, y el
 * blanco (un gris) se daba la vuelta a negro. Ver [TintaDelPlano].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TintaDelPlanoTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private val blanco = rgb(255, 255, 255)
    private val negroDelMotor = rgb(0x1e, 0x1e, 0x1e)
    private val rojo = rgb(0xe0, 0x31, 0x31)
    private val amarillo = rgb(0xff, 0xeb, 0x3b)

    private fun seLee(tinta: Int, papel: Int) =
        DrawTheme.contraste(DrawTheme.luminancia(tinta), DrawTheme.luminancia(papel)) >= DrawTheme.CONTRASTE_QUE_VALE

    /**
     * **Una capa `anot-<uid>.excalidraw.gz` tal como la escribe el PC** (`excalidraw::escribir`
     * de `pixpin-motor2d`): sin `appState` ni `backgroundColor` (el PC no los pone en una capa
     * nueva), `escala` a null, el trazo con el color elegido, blanco, y sus puntos como objetos
     * (`puntos_hacia` con `objetos`: la forma del móvil, la que el PC usa en un lienzo que ya pasó
     * por el teléfono).
     */
    private val capaDelPc = """
        {
          "elements": [
            {
              "id": "pc1f3a",
              "type": "freedraw",
              "x": 120.0,
              "y": 80.0,
              "width": 180.0,
              "height": 40.0,
              "angle": 0.0,
              "strokeColor": "#ffffff",
              "backgroundColor": "transparent",
              "fillStyle": "solid",
              "strokeWidth": 2.0,
              "strokeStyle": "solid",
              "roughness": 1,
              "opacity": 100,
              "groupIds": [],
              "roundness": null,
              "seed": 917,
              "version": 4,
              "versionNonce": 18273645,
              "updated": 1793120000000,
              "isDeleted": false,
              "boundElements": null,
              "locked": false,
              "points": [{"x": 0.0, "y": 0.0}, {"x": 30.0, "y": 12.0}, {"x": 60.0, "y": 20.0}, {"x": 90.0, "y": 26.0}, {"x": 120.0, "y": 30.0}, {"x": 150.0, "y": 36.0}, {"x": 180.0, "y": 40.0}],
              "pressures": [],
              "simulatePressure": true
            }
          ],
          "type": "excalidraw",
          "escala": null
        }
    """.trimIndent()

    private fun gz(f: File, texto: String) {
        GZIPOutputStream(f.outputStream()).use { it.write(texto.toByteArray()) }
    }

    /** El color con el que el pintor de la capa pinta [e] con el papel del anfitrión [papel]. */
    private fun colorPintado(e: Element, papel: Int?, fondoDeLaEscena: String = "#ffffff"): Int {
        val lienzo = LienzoQueApuntaColores(Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888))
        val pintor = Renderer(dark = papel != null && DrawTheme.esDeNoche(papel), papelDelAnfitrion = papel)
        pintor.renderScene(lienzo, Scene(elements = listOf(e), backgroundColor = fondoDeLaEscena), 400.0, 300.0)
        assertTrue("se pintó el trazo", lienzo.colores.isNotEmpty())
        return lienzo.colores.first() or (0xFF shl 24)
    }

    private fun trazo(color: String) = Element(
        id = "t", type = ElementType.FREEDRAW, x = 50.0, y = 100.0, width = 200.0, height = 60.0,
        seed = 1, strokeWidth = 2.0, strokeColor = color,
        points = (0..40).map { i -> val t = i / 40.0; Pt(200 * t, 60 * kotlin.math.sin(t * Math.PI)) }
    )

    @Test
    fun `los papeles del plano son los del PC`() {
        // `plano_cad::anotar::papel`: (0,99; 0,988; 0,976) y (0,13; 0,15; 0,19), redondeados como `a8`.
        assertEquals(rgb(0xfc, 0xfc, 0xf9), TintaDelPlano.PAPEL_CLARO)
        assertEquals(rgb(0x21, 0x26, 0x30), TintaDelPlano.PAPEL_OSCURO)
        assertTrue(TintaDelPlano.deNoche(claro = false))
        assertFalse(TintaDelPlano.deNoche(claro = true))
    }

    @Test
    fun `la tinta blanca del PC de noche se ve blanca en el telefono de noche`() {
        assertEquals(blanco, TintaDelPlano.comoSeVe(blanco, claro = false))
        assertEquals(blanco, colorPintado(trazo("#ffffff"), TintaDelPlano.PAPEL_OSCURO))
    }

    @Test
    fun `antes salia negra porque se adaptaba contra el blanco de la escena`() {
        // Lo de antes, sin el papel del plano: el blanco sobre «#ffffff» se da la vuelta.
        val antes = colorPintado(trazo("#ffffff"), papel = null)
        assertTrue("antes: casi negra", DrawTheme.luminancia(antes) < 0.1)
    }

    @Test
    fun `de dia la tinta va tal cual como en el PC`() {
        // El PC no adapta sobre papel claro (`con_papel` solo fija el de noche): ni el blanco.
        for (c in listOf(blanco, negroDelMotor, rojo, amarillo)) {
            assertEquals(Integer.toHexString(c), c, TintaDelPlano.comoSeVe(c, claro = true))
        }
        assertEquals(blanco, colorPintado(trazo("#ffffff"), TintaDelPlano.PAPEL_CLARO))
        assertEquals(negroDelMotor, colorPintado(trazo("#1e1e1e"), TintaDelPlano.PAPEL_CLARO))
    }

    @Test
    fun `de noche la tinta oscura se aclara con la cuenta del PC contra el papel del plano`() {
        val visto = TintaDelPlano.comoSeVe(negroDelMotor, claro = false)
        // La misma cuenta que `dibujo::tema::adaptar` contra (0,13; 0,15; 0,19): no contra el #121212.
        assertEquals(DrawTheme.adaptar(negroDelMotor, TintaDelPlano.PAPEL_OSCURO), visto)
        assertTrue(seLee(visto, TintaDelPlano.PAPEL_OSCURO))
        // Lo que ya se lee sobre el oscuro, tal cual.
        assertTrue(seLee(rojo, TintaDelPlano.PAPEL_OSCURO))
        assertEquals(rojo, TintaDelPlano.comoSeVe(rojo, claro = false))
        assertEquals(amarillo, TintaDelPlano.comoSeVe(amarillo, claro = false))
        assertEquals(visto, colorPintado(trazo("#1e1e1e"), TintaDelPlano.PAPEL_OSCURO))
    }

    @Test
    fun `el color guardado no cambia ida y vuelta en los dos modos`() {
        // Ni el PC ni el móvil guardan otra cosa que lo elegido: pintarlo no lo toca.
        for (c in listOf(blanco, negroDelMotor, rojo, amarillo)) {
            val hex = "#%06x".format(c and 0xFFFFFF)
            val e = trazo(hex)
            colorPintado(e, TintaDelPlano.PAPEL_OSCURO)
            colorPintado(e, TintaDelPlano.PAPEL_CLARO)
            assertEquals(hex, e.strokeColor)
        }
    }

    @Test
    fun `la capa real del PC se lee y su blanco se pinta blanco de noche y tal cual de dia`() {
        val carpeta = tmp.newFolder("anotaciones")
        val capa = File(carpeta, "anot-8c1d2e.excalidraw.gz").also { gz(it, capaDelPc) }
        // Su `.hoja` al lado, como la escribe el PC (`MarcoDeLaHoja`): la caja del plano en la capa.
        val hoja = File(carpeta, "anot-8c1d2e.hoja").also { it.writeText("-5000.0000,-2500.0000,5000.0000,2500.0000\nv1\n") }
        assertNotNull(AnotacionesDelAdjunto.leerMarco(hoja))

        val escena = ExcalidrawStore.cargar(capa.absolutePath)
        assertNotNull("el móvil entiende la capa del PC", escena)
        escena!!
        // Sin papel en el fichero: el blanco de serie. Por eso no puede mandar él.
        assertEquals("#ffffff", escena.backgroundColor)
        val e = escena.elements.single()
        assertEquals("#ffffff", e.strokeColor)

        assertEquals(blanco, colorPintado(e, TintaDelPlano.PAPEL_OSCURO, escena.backgroundColor))
        assertEquals(blanco, colorPintado(e, TintaDelPlano.PAPEL_CLARO, escena.backgroundColor))
    }

    @Test
    fun `la tinta del telefono de noche llega al PC con su color y se ve igual`() {
        // En el teléfono, de noche, se elige blanco: se guarda «#ffffff» (no un color de día).
        val delTelefono = trazo("#ffffff")
        val texto = com.forge.pixpin.motor.ExcalidrawJson.encodeToString(Scene.serializer(), Scene(elements = listOf(delTelefono)))
        val vuelta = com.forge.pixpin.motor.ExcalidrawJson.decodeFromString(Scene.serializer(), texto)
        assertEquals("#ffffff", vuelta.elements.single().strokeColor)
        // El PC lo pinta con su regla, que es esta: blanco de noche, blanco de día. Y el teléfono, igual.
        assertEquals(blanco, colorPintado(vuelta.elements.single(), TintaDelPlano.PAPEL_OSCURO))
        assertEquals(blanco, colorPintado(vuelta.elements.single(), TintaDelPlano.PAPEL_CLARO))
    }

    @Test
    fun `sin papel del anfitrion el lienzo sigue con su regla`() {
        // Los demás lienzos no cambian: el blanco sobre papel blanco sigue dándose la vuelta.
        assertEquals(DrawTheme.adaptar(blanco, blanco), PapelDeLaHoja.tinta(PapelDeLaHoja.Mapa.VACIO, null, blanco, blanco))
        assertEquals(blanco, PapelDeLaHoja.tintaSoloDeNoche(PapelDeLaHoja.Mapa.VACIO, null, blanco, blanco))
    }
}
