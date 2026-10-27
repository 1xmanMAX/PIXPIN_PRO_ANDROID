package com.forge.pixpin.guardados

import com.forge.pixpin.guardados.ModoDePaso.Recibido
import com.forge.pixpin.guardados.ModoDePaso.Visor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **El modo de paso** (27-oct-2026): qué visor le toca a lo que llega de fuera, y cómo lo anotado
 * de paso pasa a ser del mensaje al guardarlo. Ver [ModoDePaso].
 */
class ModoDePasoTest {

    @Test
    fun `cada formato aceptado va a su visor por la extension`() {
        assertEquals(Visor.PDF, ModoDePaso.visorPara("informe.PDF", "application/octet-stream"))
        assertEquals(Visor.OFFICE, ModoDePaso.visorPara("carta.docx", null))
        assertEquals(Visor.OFFICE, ModoDePaso.visorPara("clase.pptx", null))
        assertEquals(Visor.EPUB, ModoDePaso.visorPara("libro.epub", "application/zip"))
        assertEquals(Visor.HTML, ModoDePaso.visorPara("pagina.htm", null))
        assertEquals(Visor.HTML, ModoDePaso.visorPara("logo.svg", "image/svg+xml"))
        assertEquals(Visor.HOJA, ModoDePaso.visorPara("cuentas.xlsx", null))
        assertEquals(Visor.HOJA, ModoDePaso.visorPara("datos.csv", "text/plain"))
        assertEquals(Visor.HOJA, ModoDePaso.visorPara("calc.ods", null))
        assertEquals(Visor.PLANO, ModoDePaso.visorPara("planta.DWG", null))
        assertEquals(Visor.PLANO, ModoDePaso.visorPara("planta.dxf", null))
        assertEquals(Visor.MODELO_3D, ModoDePaso.visorPara("edificio.ifc", null))
        assertEquals(Visor.MODELO_3D, ModoDePaso.visorPara("edificio.rvt", null))
        assertEquals(Visor.MARKDOWN, ModoDePaso.visorPara("notas.md", "text/markdown"))
        assertEquals(Visor.TEXTO, ModoDePaso.visorPara("leeme.txt", "text/plain"))
        assertEquals(Visor.IMAGEN, ModoDePaso.visorPara("foto.JPEG", null))
        assertEquals(Visor.VIDEO, ModoDePaso.visorPara("clip.mp4", null))
        assertEquals(Visor.NINGUNO, ModoDePaso.visorPara("programa.apk", null))
        assertEquals(Visor.NINGUNO, ModoDePaso.visorPara("cancion.mp3", "audio/mpeg"))
    }

    @Test
    fun `sin nombre manda el tipo, alias de planos incluidos`() {
        assertEquals(Visor.PDF, ModoDePaso.visorPara("", "application/pdf"))
        assertEquals(Visor.PLANO, ModoDePaso.visorPara(null, "application/acad"))
        assertEquals(Visor.PLANO, ModoDePaso.visorPara("1234", "image/vnd.dxf"))
        assertEquals(Visor.MODELO_3D, ModoDePaso.visorPara(null, "model/ifc"))
        assertEquals(Visor.OFFICE, ModoDePaso.visorPara(null, "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
        assertEquals(Visor.HOJA, ModoDePaso.visorPara(null, "application/vnd.ms-excel"))
        assertEquals(Visor.IMAGEN, ModoDePaso.visorPara(null, "image/x-rara"))
        assertEquals(Visor.VIDEO, ModoDePaso.visorPara(null, "video/x-desconocido"))
        assertEquals(Visor.HTML, ModoDePaso.visorPara(null, "text/html; charset=utf-8"))
        assertEquals("md", ModoDePaso.extensionDe(null, "text/x-markdown"))
        assertEquals("", ModoDePaso.extensionDe(null, "application/octet-stream"))
        // La ruta de un content:// sin nombre: la extensión es la del último trozo, no la de una carpeta.
        assertEquals("", ModoDePaso.extensionDe("carpeta.v2/documento", null))
    }

    @Test
    fun `lo compartido conocido se abre de paso y lo demas va al menu corto`() {
        assertEquals(Recibido.DE_PASO, ModoDePaso.queHacerConLoCompartido(1, "a.pdf", "application/pdf"))
        assertEquals(Recibido.DE_PASO, ModoDePaso.queHacerConLoCompartido(1, null, "image/png"))
        assertEquals(Recibido.IMPORTAR, ModoDePaso.queHacerConLoCompartido(1, "proyecto.pixpin", null))
        assertEquals(Recibido.MENU, ModoDePaso.queHacerConLoCompartido(1, "raro.xyz", "application/octet-stream"))
        assertEquals(Recibido.MENU, ModoDePaso.queHacerConLoCompartido(2, "a.pdf", "application/pdf"))
        // Solo texto o un enlace.
        assertEquals(Recibido.MENU, ModoDePaso.queHacerConLoCompartido(0, null, "text/plain"))
    }

    @Test
    fun `la sesion sale de la ruta del temporal y su codigo no es de ningun mensaje`() {
        val s = ModoDePaso.sesionDe(0x1234_5678_9abc_def0L)
        assertEquals("56789abcdef0", s)
        assertTrue(ModoDePaso.esSesion(s))
        assertEquals(s, ModoDePaso.sesionDeLaRuta("/data/user/0/com.forge.pixpin/cache/de-paso/$s/Informe final.pdf"))
        assertEquals(s, ModoDePaso.sesionDeLaRuta("/data/cache/de-paso/$s/libro/pagina.html"))
        assertEquals("paso$s", ModoDePaso.uidDeLaRuta("/c/de-paso/$s/a.docx"))
        // El código de paso no tiene el largo de un código de mensaje (10): no viaja.
        assertTrue(ModoDePaso.uidDeLaSesion(s).length != com.forge.pixpin.sincro.Codigos.LARGO)
        assertNull(ModoDePaso.sesionDeLaRuta("/data/files/guardados/a.pdf"))
        assertNull(ModoDePaso.sesionDeLaRuta("/c/de-paso/$s"))
        assertNull(ModoDePaso.sesionDeLaRuta("/c/de-paso/NO-ES-SESION/a.pdf"))
        assertFalse(ModoDePaso.esSesion("ABCDEF123456"))
    }

    @Test
    fun `lo anotado de paso pasa al codigo del mensaje al guardar`() {
        val s = "0123456789ab"
        val paso = ModoDePaso.uidDeLaSesion(s)
        assertEquals("anot-K7Q2M9X4PL-p3.excalidraw.gz", ModoDePaso.claveGuardada("anot-$paso-p3.excalidraw.gz", paso, "K7Q2M9X4PL"))
        assertEquals("anot-K7Q2M9X4PL.marcas", ModoDePaso.claveGuardada("anot-$paso.marcas", paso, "K7Q2M9X4PL"))
        assertEquals("anot-K7Q2M9X4PL.marcos.json", ModoDePaso.claveGuardada("anot-$paso.marcos.json", paso, "K7Q2M9X4PL"))
        assertEquals("anot-K7Q2M9X4PL-mvista1.excalidraw.gz", ModoDePaso.claveGuardada("anot-$paso-mvista1.excalidraw.gz", paso, "K7Q2M9X4PL"))
        // Ni lo de otra sesión que empiece igual, ni los temporales, ni lo de un mensaje.
        assertNull(ModoDePaso.claveGuardada("anot-${paso}ff.marcas", paso, "K7Q2M9X4PL"))
        assertNull(ModoDePaso.claveGuardada("anot-$paso.marcas.tmp", paso, "K7Q2M9X4PL"))
        assertNull(ModoDePaso.claveGuardada("anot-K7Q2M9X4PL.marcas", paso, "OTRO000000"))
    }

    @Test
    fun `al guardar se renombra todo lo de la sesion y el dibujo de la foto`() {
        val s = "0123456789ab"
        val nombres = listOf(
            "anot-paso$s-p0.excalidraw.gz", "anot-paso$s-p0.hoja", "anot-paso$s.espacios",
            "paso-foto-$s.excalidraw.gz", "paso-foto-$s.marcas",
            "anot-pasoffffffffffff.marcas", "anot-K7Q2M9X4PL.marcas", "foto-otro.excalidraw.gz"
        )
        val r = ModoDePaso.renombres(nombres, s, "K7Q2M9X4PL", "id-1").toMap()
        assertEquals(
            mapOf(
                "anot-paso$s-p0.excalidraw.gz" to "anot-K7Q2M9X4PL-p0.excalidraw.gz",
                "anot-paso$s-p0.hoja" to "anot-K7Q2M9X4PL-p0.hoja",
                "anot-paso$s.espacios" to "anot-K7Q2M9X4PL.espacios",
                "paso-foto-$s.excalidraw.gz" to "foto-id-1.excalidraw.gz",
                "paso-foto-$s.marcas" to "foto-id-1.marcas"
            ),
            r
        )
    }

    @Test
    fun `la limpieza reconoce lo de cada sesion`() {
        val s = "0123456789ab"
        assertTrue(ModoDePaso.esDeLaSesion("anot-paso$s-p2.excalidraw.gz", s))
        assertTrue(ModoDePaso.esDeLaSesion("paso-foto-$s.excalidraw.gz", s))
        assertFalse(ModoDePaso.esDeLaSesion("anot-paso${s}0.marcas", s))
        assertFalse(ModoDePaso.esDeLaSesion("anot-K7Q2M9X4PL.marcas", s))
        assertEquals(s, ModoDePaso.sesionDelNombre("anot-paso$s.vistas.json"))
        assertEquals(s, ModoDePaso.sesionDelNombre("paso-foto-$s.excalidraw.gz"))
        assertNull(ModoDePaso.sesionDelNombre("anot-K7Q2M9X4PL.marcas"))
        assertNull(ModoDePaso.sesionDelNombre("foto-123.excalidraw.gz"))
    }
}
