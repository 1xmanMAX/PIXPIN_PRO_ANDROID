package com.forge.pixpin.privacidad

import com.forge.pixpin.privacidad.ReportesDeIa.Funcion
import com.forge.pixpin.privacidad.ReportesDeIa.Motivo
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** «Reportar respuesta» de la IA (30-oct-2026, Google Play): la línea JSON, el archivo y el correo. */
class ReportesDeIaTest {

    private val r = ReportesDeIa.nuevo(
        Funcion.ASISTENTE, Motivo.FALSO, "  La capital de Perú es «Quito».\nSegunda línea  ", " está mal ",
        cuando = 1_793_404_800_000L, version = "0.152.0"
    )

    @Test fun laLineaEsUnaSolaLineaYVuelveIgual() {
        val linea = ReportesDeIa.aLinea(r)
        assertFalse("sin saltos de línea", linea.contains('\n'))
        assertTrue(linea.contains("\"funcion\":\"asistente\"") && linea.contains("\"motivo\":\"falso\""))
        assertEquals(r, ReportesDeIa.deLinea(linea))
        assertEquals("La capital de Perú es «Quito».\nSegunda línea", r.contenido)
        assertEquals("está mal", r.detalle)
    }

    @Test fun loQueNoSeEntiendeSeSalta() {
        assertNull(ReportesDeIa.deLinea("esto no es json"))
        assertEquals(Motivo.OTRO, Motivo.deId("de-otra-version"))
        // Un campo nuevo de otra versión no rompe la lectura.
        val conMas = ReportesDeIa.aLinea(r).dropLast(1) + ",\"nuevo\":1}"
        assertEquals(r, ReportesDeIa.deLinea(conMas))
    }

    @Test fun seRecortaLoLargo() {
        val largo = ReportesDeIa.nuevo(Funcion.OTRA, Motivo.OTRO, "x".repeat(20_000), "y".repeat(5_000))
        assertEquals(ReportesDeIa.LARGO_CONTENIDO, largo.contenido.length)
        assertEquals(ReportesDeIa.LARGO_DETALLE, largo.detalle.length)
    }

    @Test fun seApuntanAlFinalDelArchivo() {
        val dir = Files.createTempDirectory("reportes").toFile()
        try {
            val f = File(dir, ReportesDeIa.ARCHIVO)
            assertTrue(ReportesDeIa.leer(f).isEmpty())
            ReportesDeIa.guardar(f, r)
            val otro = r.copy(motivo = Motivo.OFENSIVO.id, funcion = Funcion.REPASO.id)
            ReportesDeIa.guardar(f, otro)
            f.appendText("basura\n")
            assertEquals(3, f.readLines().size)
            assertEquals(listOf(r, otro), ReportesDeIa.leer(f))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun elCorreoLlevaMotivoFuncionYContenido() {
        val asunto = ReportesDeIa.asunto(r)
        assertTrue(asunto, asunto.contains("Falso") && asunto.contains("Asistente"))
        val cuerpo = ReportesDeIa.cuerpo(r)
        assertTrue(cuerpo.contains("Motivo: Falso"))
        assertTrue(cuerpo.contains("Versión: 0.152.0"))
        assertTrue(cuerpo.contains("Comentario:\nestá mal"))
        assertTrue(cuerpo.contains("«Quito»"))
        assertEquals("2026-10-31 00:00 UTC", ReportesDeIa.fecha(1_793_404_800_000L))
    }

    @Test fun laPoliticaYElCorreoEstanEnUnSoloSitio() {
        assertTrue(Privacidad.URL_POLITICA.startsWith("https://"))
        assertTrue(Privacidad.CORREO_CONTACTO.contains('@'))
        assertTrue(Privacidad.DESTINOS.size >= 6)
        for (d in Privacidad.DESTINOS) assertTrue(d.que, d.que.isNotBlank() && d.adonde.isNotBlank() && d.cuando.isNotBlank())
        val resumen = Privacidad.resumen()
        assertTrue(resumen.contains("Anthropic") && resumen.contains("TypeSafe") && resumen.contains("Microsoft"))
    }
}
