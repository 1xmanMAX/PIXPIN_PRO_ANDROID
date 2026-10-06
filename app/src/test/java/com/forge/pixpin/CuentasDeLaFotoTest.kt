package com.forge.pixpin

import com.forge.pixpin.guardados.Clase
import com.forge.pixpin.guardados.CuentasDelLogo
import com.forge.pixpin.guardados.Descripcion
import com.forge.pixpin.guardados.EnvioDeVarios
import com.forge.pixpin.guardados.Giro
import com.forge.pixpin.guardados.GiroExif
import com.forge.pixpin.guardados.Mensaje
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lo del chat que llegó del PC el 4-oct-2026: descripción, EXIF, logo y envío de varias fotos. */
class CuentasDeLaFotoTest {

    private fun foto(texto: String = "") =
        Mensaje(id = "1", cuando = 1, clase = Clase.IMAGEN, ruta = "/g/guardados/1_foto.png", texto = texto)

    @Test fun seDescribeUnaFotoYUnArchivo() {
        assertTrue(Descripcion.sePuede(foto()))
        assertTrue(Descripcion.sePuede(foto().copy(clase = Clase.ARCHIVO)))
        // Casos negativos: una nota, una foto del buzón, una sin fichero y una lección.
        assertFalse(Descripcion.sePuede(foto("hola").copy(clase = Clase.NOTA)))
        assertFalse(Descripcion.sePuede(foto().copy(enBuzon = true)))
        assertFalse(Descripcion.sePuede(foto().copy(ruta = null)))
        assertFalse(Descripcion.sePuede(foto().copy(clase = Clase.ARCHIVO, ruta = "/g/guardados/lecciones/a.leccion")))
    }

    @Test fun laDescripcionSePoneSeCambiaYSeQuita() {
        val puesta = Descripcion.conDescripcion(foto(), "  La pared norte  ")!!
        assertEquals("La pared norte", puesta.texto)
        assertEquals("es el mismo mensaje, no uno nuevo", "1", puesta.id)
        val quitada = Descripcion.conDescripcion(puesta, "   ")!!
        assertTrue(quitada.texto.isEmpty())
        // Caso negativo: lo mismo de antes no reescribe nada.
        assertNull(Descripcion.conDescripcion(puesta, "La pared norte "))
        assertNull(Descripcion.conDescripcion(foto(), ""))
    }

    @Test fun cancelarDevuelveElPieALaCaja() {
        assertEquals("mira esto", Descripcion.pieDeVuelta("", "mira esto"))
        // Casos negativos: no pisa lo que ya hay ni pone espacios.
        assertEquals("otra cosa", Descripcion.pieDeVuelta("otra cosa", "mira esto"))
        assertEquals("", Descripcion.pieDeVuelta("", "  "))
    }

    @Test fun elPieVaConLaPrimeraQuePuedeLlevarlo() {
        assertEquals(listOf("hola", null), Descripcion.pies(listOf(Clase.IMAGEN, Clase.IMAGEN), " hola "))
        assertEquals(listOf(null, "hola"), Descripcion.pies(listOf(Clase.VOZ, Clase.ARCHIVO), "hola"))
        // Casos negativos: sin pie no va nada, y sin foto ni archivo tampoco (queda como nota).
        assertEquals(listOf(null, null), Descripcion.pies(listOf(Clase.IMAGEN, Clase.IMAGEN), "  "))
        assertEquals(listOf<String?>(null), Descripcion.pies(listOf(Clase.VOZ), "hola"))
    }

    @Test fun elGiroDeCadaEtiquetaExif() {
        assertTrue(GiroExif.de(1).nada)
        assertEquals(Giro(0, true), GiroExif.de(2))
        assertEquals(Giro(180, false), GiroExif.de(3))
        assertEquals(Giro(180, true), GiroExif.de(4))
        assertEquals(Giro(90, true), GiroExif.de(5))
        assertEquals(Giro(90, false), GiroExif.de(6))
        assertEquals(Giro(270, true), GiroExif.de(7))
        assertEquals(Giro(270, false), GiroExif.de(8))
        // Casos negativos: lo desconocido se deja tal cual, y solo 5 a 8 cambian ancho por alto.
        assertTrue(GiroExif.de(0).nada)
        assertTrue(GiroExif.de(42).nada)
        assertTrue(GiroExif.tumbada(6))
        assertFalse(GiroExif.tumbada(3))
        assertFalse(GiroExif.tumbada(9))
    }

    @Test fun elLogoSaleDelCentroYNoSeAgranda() {
        assertEquals(Triple(50, 0, 300), CuentasDelLogo.cuadradoCentral(400, 300))
        assertEquals(Triple(0, 50, 300), CuentasDelLogo.cuadradoCentral(300, 400))
        assertEquals(Triple(0, 0, 256), CuentasDelLogo.cuadradoCentral(256, 256))
        // Impar: el píxel de más se queda a la derecha.
        assertEquals(Triple(1, 0, 2), CuentasDelLogo.cuadradoCentral(5, 2))
        assertEquals(256, CuentasDelLogo.ladoGuardado(3000))
        // Caso negativo: una pequeña no se agranda.
        assertEquals(100, CuentasDelLogo.ladoGuardado(100))
    }

    @Test fun elMuestreoDejaSiempreSitioParaElLado() {
        assertEquals(1, CuentasDelLogo.muestreo(300, 400))
        assertEquals(8, CuentasDelLogo.muestreo(4000, 3000))
        // Lo reducido nunca baja de 256 por el lado corto.
        val m = CuentasDelLogo.muestreo(4000, 3000)
        assertTrue(3000 / m >= CuentasDelLogo.LADO)
        // Caso negativo: una más pequeña que el logo no se reduce.
        assertEquals(1, CuentasDelLogo.muestreo(100, 50))
    }

    @Test fun soltarEntreDosFotosLaMeteAhi() {
        assertEquals(listOf('c', 'a', 'b'), EnvioDeVarios.mover(listOf('a', 'b', 'c'), 2, 0))
        assertEquals(listOf('b', 'c', 'a'), EnvioDeVarios.mover(listOf('a', 'b', 'c'), 0, 3))
        // Casos negativos: soltarla en su sitio no cambia nada, y un índice fuera no rompe.
        val v = listOf('a', 'b', 'c')
        assertEquals(v, EnvioDeVarios.mover(v, 1, 1))
        assertEquals(v, EnvioDeVarios.mover(v, 1, 2))
        assertEquals(v, EnvioDeVarios.mover(v, 7, 0))
    }

    @Test fun elArrastrePasadaLaMitadCambiaDeSitio() {
        // Un paso a la derecha: detrás de la vecina.
        assertEquals(listOf('b', 'a', 'c'), EnvioDeVarios.mover(listOf('a', 'b', 'c'), 0, EnvioDeVarios.huecoTrasArrastrar(0, 0.6f, 3)))
        // Hasta el principio desde el final.
        assertEquals(listOf('c', 'a', 'b'), EnvioDeVarios.mover(listOf('a', 'b', 'c'), 2, EnvioDeVarios.huecoTrasArrastrar(2, -5f, 3)))
        // Caso negativo: menos de media miniatura no la mueve.
        assertEquals(listOf('a', 'b', 'c'), EnvioDeVarios.mover(listOf('a', 'b', 'c'), 1, EnvioDeVarios.huecoTrasArrastrar(1, 0.4f, 3)))
    }

    @Test fun anadirNoRepite() {
        assertEquals(listOf(1, 2, 3), EnvioDeVarios.anadir(listOf(1, 2), listOf(2, 3)))
        // Caso negativo: nada nuevo, nada cambia.
        assertEquals(listOf(1, 2), EnvioDeVarios.anadir(listOf(1, 2), listOf(1)))
    }
}
