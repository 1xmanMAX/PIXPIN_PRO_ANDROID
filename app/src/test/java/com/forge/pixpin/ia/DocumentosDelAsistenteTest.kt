package com.forge.pixpin.ia

import com.forge.pixpin.asistente.DocumentosDelAsistente
import com.forge.pixpin.guardados.Clase
import com.forge.pixpin.guardados.Mensaje
import com.forge.pixpin.guardados.MensajesStore
import com.forge.pixpin.motor.ElementType
import com.forge.pixpin.motor.ExcalidrawStore
import com.forge.pixpin.sincro.AnotacionesDelAdjunto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.Deflater

/**
 * **Anotar y buscar en los documentos del chat** (PC `documentos_acciones.rs`): el texto de la hoja
 * con su sitio, lo anotado en la MISMA capa que el lector (`anot-<uid>-p<i>` con su `.hoja`), y los
 * casos que se dicen (texto que no está, sin tipo, varios archivos que encajan).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DocumentosDelAsistenteTest {
    private val c get() = RuntimeEnvironment.getApplication()

    private fun pdf(): File {
        val contenido = "BT /F1 24 Tf 100 700 Td (Memoria de calculo) Tj ET\nBT /F1 12 Tf 100 660 Td (El cemento Portland tipo I) Tj ET\n".toByteArray()
        val d = Deflater(); d.setInput(contenido); d.finish()
        val buf = ByteArray(contenido.size + 256); val n = d.deflate(buf); d.end()
        val flujo = buf.copyOf(n)
        val objs = listOf("<< /Type /Catalog /Pages 2 0 R >>", "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>")
        val out = java.io.ByteArrayOutputStream()
        out.write("%PDF-1.7\n".toByteArray())
        val offs = ArrayList<Int>()
        objs.forEachIndexed { i, o -> offs += out.size(); out.write("${i + 1} 0 obj\n$o\nendobj\n".toByteArray()) }
        offs += out.size()
        out.write("4 0 obj\n<< /Length ${flujo.size} /Filter /FlateDecode >>\nstream\n".toByteArray()); out.write(flujo); out.write("\nendstream\nendobj\n".toByteArray())
        offs += out.size()
        out.write("5 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n".toByteArray())
        val xref = out.size()
        out.write("xref\n0 6\n0000000000 65535 f \n".toByteArray())
        for (o in offs) out.write(String.format("%010d 00000 n \n", o).toByteArray())
        out.write("trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n".toByteArray())
        return File(c.filesDir, "memoria.pdf").apply { writeBytes(out.toByteArray()) }
    }

    private fun conElPdf(): Pair<DocumentosDelAsistente, Mensaje> {
        val f = pdf()
        val m = Mensaje(id = "m1", cuando = 1000, clase = Clase.ARCHIVO, ruta = f.path, nombre = "Memoria de calculo.pdf", proyecto = "obra", uid = "u123")
        val almacen = MensajesStore(c); almacen.anadir(m)
        return DocumentosDelAsistente(c, almacen, "obra") to m
    }

    private fun j(s: String) = Json.parseToJsonElement(s) as JsonObject

    @Test fun `busca el texto en la hoja con su sitio`() {
        val (d, m) = conElPdf()
        val cand = d.elegir(null, DocumentosDelAsistente.Sirve.DOCUMENTO)
        assertEquals(m.id, cand.mensaje.id)
        val p = d.paginas(cand.ruta, cand.nombre)
        assertEquals(1, p.textos.size)
        val s = d.buscarEnPaginas(p, "cemento portland", null, 5)
        assertEquals(1, s.size)
        val caja = s[0].cajas.single()
        // A la altura del renglón de abajo (y = 660 en el PDF → hacia abajo, ~132 pt desde arriba de 792).
        val alto = p.altos[0]
        assertTrue("${caja.toList()}", caja[1] / alto in 0.13..0.19)
        assertTrue(s[0].contexto.contains("Portland"))
        assertTrue(d.buscarEnPaginas(p, "acero", null, 5).isEmpty())
    }

    @Test fun `resaltar escribe en la capa del lector con su hoja`() {
        val (d, _) = conElPdf()
        val cand = d.elegir("memoria", DocumentosDelAsistente.Sirve.DOCUMENTO)
        val hecho = d.anotarEn(cand, j("""{"tipo":"resaltar","texto":"cemento"}"""))
        assertEquals(0, hecho.pagina); assertEquals(1, hecho.sitios)
        val id = AnotacionesDelAdjunto.dePagina("u123", 0)
        val escena = ExcalidrawStore.cargar(ExcalidrawStore.rutaDe(c, id))
        assertNotNull(escena)
        val r = escena!!.elements.single()
        assertEquals(ElementType.RECTANGLE, r.type)
        assertEquals(listOf(DocumentosDelAsistente.GRUPO), r.groupIds)
        assertTrue(AnotacionesDelAdjunto.hoja(c.filesDir, id).isFile)
        // Una nota y un marco, a la misma hoja.
        d.anotarEn(cand, j("""{"tipo":"nota","texto":"cemento","nota":"revisar el tipo"}"""))
        assertEquals(3, ExcalidrawStore.cargar(ExcalidrawStore.rutaDe(c, id))!!.elements.size)
    }

    @Test fun `lo que no se puede se dice`() {
        val (d, _) = conElPdf()
        val cand = d.elegir(null, DocumentosDelAsistente.Sirve.DOCUMENTO)
        for ((a, palabra) in listOf("""{"tipo":"resaltar","texto":"acero"}""" to "no aparece", """{"texto":"cemento"}""" to "tipo", """{"tipo":"subrayar"}""" to "texto")) {
            try { d.anotarEn(cand, j(a)); fail(a) } catch (e: IllegalStateException) { assertTrue(e.message!!, e.message!!.contains(palabra)) }
        }
        try { d.elegir("planos de estructuras", DocumentosDelAsistente.Sirve.DOCUMENTO); fail() } catch (e: IllegalStateException) { assertTrue(e.message!!.contains("no hay ningun archivo")) }
    }

    @Test fun `anotar con cabeza pone las notas en la hoja del lector segun llegan`() {
        val (d, _) = conElPdf()
        val cand = d.elegir(null, DocumentosDelAsistente.Sirve.ANOTABLE)
        val respuesta = "{\"tipo\":\"importante\",\"pag\":1,\"ancla\":\"cemento Portland\",\"l\":2,\"nota\":\"El tipo de cemento manda en la mezcla.\"}\n" +
            "{\"tipo\":\"definicion\",\"ancla\":\"Memoria de calculo\",\"nota\":\"memoria: el documento que justifica el diseño\"}\n" +
            "{\"tipo\":\"importante\",\"ancla\":\"acero corrugado\",\"nota\":\"no esta\"}"
        val motor = com.forge.pixpin.ia.MotorGuionado(listOf(com.forge.pixpin.ia.Respuesta(respuesta)))
        val a = com.forge.pixpin.asistente.AnotarInteligente(c, d, java.util.concurrent.atomic.AtomicBoolean(false))
        val h = a.anotarEn(cand, j("""{"pedido":"señala lo importante"}"""), motor)
        assertEquals(2, h.puestas)
        assertTrue(h.resumen, h.resumen.contains("acero corrugado"))
        // El modelo vio el texto renglón a renglón.
        val visto = motor.recibido.single().mensajes.last().toString()
        assertTrue(visto, visto.contains("L2: El cemento Portland tipo I"))
        val escena = ExcalidrawStore.cargar(ExcalidrawStore.rutaDe(c, AnotacionesDelAdjunto.dePagina("u123", 0)))!!
        assertTrue(escena.elements.isNotEmpty() && escena.elements.all { e -> e.groupIds.any { it.startsWith(com.forge.pixpin.ia.Anotacion.GRUPO_TURNO) } })
        assertTrue(escena.elements.any { it.type == ElementType.TEXT && it.text!!.contains("cemento") })
        // Sin la nube no se hace (el local no se toca).
        try { a.hacer(j("""{"pedido":"x"}"""), motor, esNube = false); fail() } catch (e: IllegalStateException) { assertTrue(e.message!!.contains("nube")) }
    }

    @Test fun `en el plano busca entera o por palabras y el grande primero`() {
        val (d, _) = conElPdf()
        fun t(s: String, x: Double, alto: Double) = s to doubleArrayOf(x, 10.0, alto)
        val ts = listOf(t("ver Jr Antisuyo en nota", 1.0, 1.0), t("JR. ANTISUYO", 50.0, 5.0), t("Av. Tahuantinsuyo", 9.0, 5.0))
        val h = d.buscarEnElPlano(ts, "jr antisuyo", 5)
        assertEquals(2, h.size); assertEquals("JR. ANTISUYO", h[0].first)
        assertEquals(1, d.buscarEnElPlano(listOf(t("ANTISUYO (JR.)", 0.0, 2.0)), "Jr. Antisuyo", 5).size)
        assertTrue(d.buscarEnElPlano(ts, "Collasuyo", 5).isEmpty())
        assertTrue(d.buscarEnElPlano(ts, "  ", 5).isEmpty())
    }
}
