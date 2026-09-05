package com.forge.pixpin

import com.forge.pixpin.croquis3d.Camara3D
import com.forge.pixpin.croquis3d.Croquis
import com.forge.pixpin.croquis3d.ExportarCroquisHtml
import com.forge.pixpin.croquis3d.Esfera3D
import com.forge.pixpin.croquis3d.Grupo3D
import com.forge.pixpin.croquis3d.Imagen3D
import com.forge.pixpin.croquis3d.Luces3D
import com.forge.pixpin.croquis3d.PuntaDelPincel
import com.forge.pixpin.croquis3d.Sol3D
import com.forge.pixpin.croquis3d.Lamina3D
import com.forge.pixpin.croquis3d.Trazo3D
import com.forge.pixpin.croquis3d.Vista3D
import com.forge.pixpin.motor.Pt3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El croquis del espacio como página web. Ver [ExportarCroquisHtml].
 *
 * La prueba que más vale es la última: **el visor proyecta igual que la aplicación**. El
 * visor es JavaScript y no se puede llamar desde aquí, así que lo que se compara son sus
 * números —sacados corriendo su proyección en node— contra los de [Camara3D.aPantalla].
 * Si alguien toca una de las dos cuentas y no la otra, esto se cae.
 */
class ExportarCroquisHtmlTest {

    private val recta = Trazo3D(
        id = "t1",
        puntos = listOf(Pt3(0.0, 0.0, 0.0), Pt3(10.0, 0.0, 0.0)),
        color = "#ff0000",
        grosor = 2.0,
        calibre = 3.0
    )

    private fun pagina(c: Croquis, cam: Camara3D = Camara3D()) =
        ExportarCroquisHtml.pagina(c, cam, "croquis de prueba")

    @Test
    fun `un croquis vacio no da pagina`() {
        assertNull(pagina(Croquis()))
        // Un trazo de un solo punto tampoco es nada que enseñar.
        assertNull(pagina(Croquis(trazos = listOf(recta.copy(puntos = listOf(Pt3(0.0, 0.0, 0.0)))))))
    }

    @Test
    fun `lo escondido no sale`() {
        assertNull(pagina(Croquis(trazos = listOf(recta.copy(oculto = true)))))
    }

    @Test
    fun `la pagina lleva el visor y los datos dentro`() {
        val html = pagina(Croquis(trazos = listOf(recta)))!!
        assertTrue("falta la página del espacio", html.contains("data-tipo=\"espacio\""))
        assertTrue("faltan los datos", html.contains("class=\"datos\""))
        assertTrue("falta el visor", html.contains("function proyectar"))
        // **Sin nada que traer de fuera**: es lo que lo hace valer sin conexión. Se mira lo
        // que de verdad se carga —fuentes, imágenes, guiones, estilos—, no cualquier texto
        // que parezca una dirección: el espacio de nombres del SVG es una y no se pide.
        assertFalse("trae algo de fuera", html.contains("src=\"http"))
        assertFalse("trae algo de fuera", html.contains("href=\"http"))
        assertFalse(html.contains("@import"))
        assertFalse(html.contains("url("))
        assertTrue("falta el punto del trazo", html.contains("\"p\":[0,0,0,10,0,0]"))
        assertTrue("falta el color", html.contains("\"c\":\"#ff0000\""))
        assertTrue("falta el calibre", html.contains("\"w\":3"))
    }

    @Test
    fun `los grupos que se usan salen con su nombre`() {
        val html = pagina(
            Croquis(
                trazos = listOf(recta.copy(grupo = "g1")),
                grupos = listOf(Grupo3D("g1", "Muros"), Grupo3D("g2", "Sin usar"))
            )
        )!!
        assertTrue(html.contains("{\"i\":\"g1\",\"n\":\"Muros\"}"))
        assertTrue("un grupo sin nada dentro no pinta nada", !html.contains("Sin usar"))
        assertTrue(html.contains("\"g\":\"g1\""))
    }

    @Test
    fun `las vistas guardadas viajan con el croquis`() {
        val html = pagina(
            Croquis(
                trazos = listOf(recta),
                vistas = listOf(Vista3D("v1", "Desde arriba", Camara3D(giro = 1.0, zoom = 3.0)))
            )
        )!!
        assertTrue(html.contains("\"n\":\"Desde arriba\""))
        assertTrue(html.contains("\"z\":3"))
    }

    @Test
    fun `una hoja sale como sus tiras y translucida`() {
        val hoja = Lamina3D(
            id = "l1",
            perfil = listOf(Pt3(0.0, 0.0, 0.0), Pt3(4.0, 0.0, 0.0)),
            direccion = Pt3(0.0, 1.0, 0.0),
            fondo = 2.0,
            color = "#00ff00",
            grosor = 1.0
        )
        val html = pagina(Croquis(laminas = listOf(hoja)))!!
        assertTrue("la hoja no salió", html.contains("\"c\":\"#00ff00\""))
        // Plana: va como su contorno, que pesa una décima parte que su malla, y con la
        // normal que decide lo opaca que se ve según desde dónde se mire.
        assertTrue("faltaba el contorno", html.contains("\"b\":["))
        assertTrue("faltaba la normal", html.contains("\"n\":["))
        assertFalse("una hoja plana no necesita sus tiras", html.contains("\"k\":1"))
    }

    @Test
    fun `la caja envuelve todo lo que se pinta`() {
        val html = pagina(
            Croquis(trazos = listOf(recta, recta.copy(id = "t2", puntos = listOf(Pt3(-5.0, 1.0, 2.0), Pt3(0.0, 3.0, 4.0)))))
        )!!
        assertTrue(html.contains("\"cj\":[-5,0,0,10,3,4]"))
    }

    @Test
    fun `el titulo no se cuela en el html`() {
        val html = ExportarCroquisHtml.pagina(
            Croquis(trazos = listOf(recta)), Camara3D(), "<script>malo</script>"
        )!!
        assertTrue("el título tenía que ir escapado", html.contains("&lt;script&gt;malo"))
    }

    @Test
    fun `un solido va como sus caras y una bola tambien`() {
        val bola = Lamina3D(
            id = "b1", perfil = emptyList(), direccion = Pt3(0.0, 0.0, 1.0), fondo = 1.0,
            color = "#3366ff", grosor = 1.0,
            esfera = Esfera3D(centro = Pt3(0.0, 0.0, 0.0), radio = 2.0)
        )
        val datos = ExportarCroquisHtml.datos(Croquis(laminas = listOf(bola)), Camara3D())!!
        assertTrue("un sólido va cara a cara", datos.contains("\"k\":1"))
        assertTrue(datos.contains("\"s\":[["))
    }

    @Test
    fun `las imagenes viajan con su textura y sus cuatro esquinas`() {
        val imagen = Imagen3D(
            id = "i1", ruta = "/tmp/foto.jpg",
            esquinas = listOf(
                Pt3(0.0, 0.0, 0.0), Pt3(4.0, 0.0, 0.0), Pt3(4.0, 0.0, 3.0), Pt3(0.0, 0.0, 3.0)
            )
        )
        val croquis = Croquis(imagenes = listOf(imagen))
        // Sin manera de leerla, la imagen no viaja y el croquis se queda sin nada que enseñar.
        assertNull(ExportarCroquisHtml.datos(croquis, Camara3D()))
        val datos = ExportarCroquisHtml.datos(croquis, Camara3D()) { "data:image/png;base64,AAA" }!!
        assertTrue(datos.contains("\"u\":\"data:image/png;base64,AAA\""))
        assertTrue(datos.contains("\"e\":[0,0,0,4,0,0,4,0,3,0,0,3]"))
    }

    /**
     * **El lápiz de anotar se exporta como lo que es: una raya.** Los demás trazos viajan con
     * su esqueleto cocido y el ancho de cada muestra —de ahí el afilado y el bulto—; el lápiz
     * no tiene ni una cosa ni la otra, y ese es justo su sentido.
     */
    @Test
    fun `el lapiz de anotar va como raya lisa`() {
        val conBulto = ExportarCroquisHtml.datos(
            Croquis(trazos = listOf(recta.copy(tiempos = listOf(0.0, 0.1)))), Camara3D()
        )!!
        assertTrue("un trazo normal lleva el ancho de cada muestra", conBulto.contains("\"a\":["))

        val lapiz = recta.copy(
            tiempos = listOf(0.0, 0.1),
            punta = PuntaDelPincel(plana = true)
        )
        val plano = ExportarCroquisHtml.datos(Croquis(trazos = listOf(lapiz)), Camara3D())!!
        assertFalse("el lápiz no lleva anchos", plano.contains("\"a\":["))
        assertTrue(plano.contains("\"p\":[0,0,0,10,0,0]"))
    }

    @Test
    fun `el sol viaja si alumbra`() {
        val con = ExportarCroquisHtml.datos(
            Croquis(trazos = listOf(recta), sol = Sol3D()), Camara3D()
        )!!
        assertTrue("faltaba el sol", con.contains("\"sol\":["))
        val sin = ExportarCroquisHtml.datos(
            Croquis(trazos = listOf(recta), sol = Sol3D(), luces = Luces3D(encendidas = false)),
            Camara3D()
        )!!
        assertFalse("las luces apagadas no mandan sol", sin.contains("\"sol\":["))
    }

    @Test
    fun `los numeros van cortos`() {
        assertEquals("0", ExportarCroquisHtml.n(0.0))
        assertEquals("0", ExportarCroquisHtml.n(-0.0001))
        assertEquals("12", ExportarCroquisHtml.n(12.0))
        assertEquals("1.235", ExportarCroquisHtml.n(1.23456))
        assertEquals("-3.5", ExportarCroquisHtml.n(-3.5))
        assertEquals("0", ExportarCroquisHtml.n(Double.NaN))
    }

    /**
     * **El visor y la aplicación proyectan igual.** Los números de la derecha salen de
     * correr `base()` y `proyectar()` del visor en node con estas mismas cámaras.
     */
    @Test
    fun `el visor proyecta como Camara3D`() {
        val p = Pt3(10.0, 20.0, 30.0)
        val orto = Camara3D(giro = -Math.PI / 5, inclinacion = Math.PI / 7, zoom = 2.0)
        comprobar(orto.aPantalla(p, 800.0, 600.0), 439.69175, 237.001704)

        val lente = Camara3D(
            giro = 0.3, inclinacion = 0.2, zoom = 1.5, balanceo = 0.1, lente = 0.4,
            centro = Pt3(1.0, 2.0, 3.0)
        )
        comprobar(lente.aPantalla(p, 800.0, 600.0), 409.068663, 256.905991)

        val rect = Camara3D(giro = 0.3, inclinacion = 0.2, zoom = 1.5, lente = 0.4, rectilinea = true)
        comprobar(rect.aPantalla(p, 800.0, 600.0), 405.20952, 251.686616)

        // En el eje de la mirada y delante: el centro de la pantalla.
        comprobar(orto.aPantalla(Pt3(0.0, 0.0, 0.0), 800.0, 600.0), 400.0, 300.0)
    }

    private fun comprobar(pt: com.forge.pixpin.motor.Pt, x: Double, y: Double) {
        assertEquals(x, pt.x, 1e-5)
        assertEquals(y, pt.y, 1e-5)
    }
}
