package com.forge.pixpin

import com.forge.pixpin.motor.Proyecto
import com.forge.pixpin.motor.Proyectos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * De dónde se lee el documento de un proyecto cuando el archivo original ya no está.
 *
 * Esta es la cuenta que decidía si un proyecto enseña su portada o un hueco. El fallo que
 * la trajo era justo ese: proyectos con su copia limpia intacta al lado y sin miniatura,
 * porque solo se miraba el original.
 */
class RutaDelDocumentoTest {

    private fun proyecto(origen: String?, limpio: String?) = Proyecto(
        id = "pr-1",
        nombre = "Plano",
        pdfOrigen = origen,
        pdfLimpio = limpio,
        tocado = 0L
    )

    @Test
    fun `con el original en su sitio se usa el original`() {
        val p = proyecto("/pines/plano.pdf", "/proyectos/limpio.pdf")
        assertEquals("/pines/plano.pdf", Proyectos.rutaDelDocumento(p) { true })
    }

    @Test
    fun `sin el original se cae a la copia limpia`() {
        val p = proyecto("/pines/plano.pdf", "/proyectos/limpio.pdf")
        val ruta = Proyectos.rutaDelDocumento(p) { it != "/pines/plano.pdf" }
        assertEquals("/proyectos/limpio.pdf", ruta)
    }

    /** Los proyectos de antes de que existiera la copia: de esos no hay nada que leer. */
    @Test
    fun `sin original ni copia no hay documento`() {
        val p = proyecto("/pines/plano.pdf", null)
        assertNull(Proyectos.rutaDelDocumento(p) { false })
    }

    @Test
    fun `un proyecto sin pdf ninguno no devuelve nada`() {
        assertNull(Proyectos.rutaDelDocumento(proyecto(null, null)) { true })
    }

    /** Si los dos se perdieron, tampoco vale devolver una ruta que no se puede abrir. */
    @Test
    fun `con los dos archivos perdidos no se inventa una ruta`() {
        val p = proyecto("/pines/plano.pdf", "/proyectos/limpio.pdf")
        assertNull(Proyectos.rutaDelDocumento(p) { false })
    }

    /** Un proyecto en blanco puede tener copia y no original: se lee la copia. */
    @Test
    fun `sin original pero con copia se lee la copia`() {
        val p = proyecto(null, "/proyectos/limpio.pdf")
        assertEquals("/proyectos/limpio.pdf", Proyectos.rutaDelDocumento(p) { true })
    }
}
