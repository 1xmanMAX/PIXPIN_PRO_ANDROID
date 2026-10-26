package com.forge.pixpin.guardados

import com.forge.pixpin.motor.Hoja
import com.forge.pixpin.motor.Proyecto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * **Fusionar páginas desde lo marcado en el chat** (26-oct-2026): era de la caja de lo marcado de
 * la pantalla de Proyectos, que ya no está. Ver [FusionarPaginas.deMensajes].
 */
class FusionarDesdeElChatTest {

    private val plano = Proyecto(
        id = "p", nombre = "Plano", tocado = 0,
        hojas = (0..5).map { Hoja(id = "h$it", pagina = it) },
        pdfOrigen = "/doc/plano.pdf", pdfLimpio = "/doc/plano-limpio.pdf"
    )
    private val otro = Proyecto(id = "q", nombre = "Otro", tocado = 0, pdfOrigen = "/doc/otro.pdf")

    private fun pagina(n: Int, ruta: String = "/doc/plano.pdf") =
        Mensaje(id = "m$n-$ruta", cuando = n.toLong(), clase = Clase.PAGINA, ruta = ruta, pagina = n, proyecto = "chat")

    private val todo: (String) -> Boolean = { true }

    @Test
    fun `dos o mas paginas del mismo pdf se fusionan, en orden y sin repetir`() {
        val p = FusionarPaginas.deMensajes(listOf(otro, plano), listOf(pagina(5), pagina(3), pagina(3), pagina(4)), todo)!!
        assertEquals("p", p.proyecto.id)
        assertEquals("/doc/plano.pdf", p.pdf)
        assertEquals(listOf(3, 4, 5), p.paginas)
    }

    @Test
    fun `por la copia limpia tambien, y si el original no esta se lee la limpia`() {
        val marcadas = listOf(pagina(1, "/doc/plano-limpio.pdf"), pagina(2, "/doc/plano-limpio.pdf"))
        assertEquals("/doc/plano.pdf", FusionarPaginas.deMensajes(listOf(plano), marcadas, todo)!!.pdf)
        assertEquals("/doc/plano-limpio.pdf", FusionarPaginas.deMensajes(listOf(plano), marcadas) { it.endsWith("limpio.pdf") }!!.pdf)
    }

    @Test
    fun `una sola pagina, otra cosa marcada o dos documentos no`() {
        assertNull(FusionarPaginas.deMensajes(listOf(plano), listOf(pagina(1)), todo))
        assertNull(FusionarPaginas.deMensajes(listOf(plano), listOf(pagina(1), pagina(1)), todo))
        assertNull(FusionarPaginas.deMensajes(listOf(plano), listOf(pagina(1), Mensaje(id = "n", cuando = 0, clase = Clase.NOTA, texto = "hola")), todo))
        assertNull(FusionarPaginas.deMensajes(listOf(plano, otro), listOf(pagina(1), pagina(2, "/doc/otro.pdf")), todo))
    }

    @Test
    fun `sin proyecto de ese pdf o sin el archivo, no`() {
        assertNull(FusionarPaginas.deMensajes(listOf(otro), listOf(pagina(1), pagina(2)), todo))
        assertNull(FusionarPaginas.deMensajes(listOf(plano), listOf(pagina(1), pagina(2))) { false })
    }
}
