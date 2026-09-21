package com.forge.pixpin

import com.forge.pixpin.ui.Cuerpo
import com.forge.pixpin.ui.NodoDelChat
import com.forge.pixpin.ui.Universos
import com.forge.pixpin.ui.desdeElChat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **El universo tiene la forma del chat**: proyecto = universo, archivo = sistema solar, lo que
 * le contesta = sus planetas, y en un PDF las páginas son planetas y lo que se comenta en una
 * página son sus satélites. Ver [desdeElChat].
 */
class UniversoDesdeElChatTest {
    private val chat = listOf(
        NodoDelChat("pdf", "Plano.pdf", esDocumento = true),
        NodoDelChat("p1", "Plano · página 1", respondeA = "pdf", pagina = 1),
        NodoDelChat("p2", "Plano · página 2", respondeA = "pdf", pagina = 2),
        NodoDelChat("c1", "ojo con la cota", respondeA = "p1", esTexto = true),
        NodoDelChat("c2", "corregido", respondeA = "c1", esTexto = true),
        NodoDelChat("foto", "IMG_2031.jpg"),
        NodoDelChat("suelto", "hay que pedir el acero", esTexto = true)
    )

    private fun armado() = Universos().desdeElChat("pr-1", chat, ahora = 1000)

    private fun dentroDe(u: Universos, espacio: String, ref: String): String? =
        u.espacio(espacio).cuerpos.firstOrNull { it.ref == ref }?.espacio

    @Test fun `los archivos del chat son los soles del proyecto`() {
        val u = armado()
        val raiz = u.espacio(Universos.deProyecto("pr-1"))
        assertEquals(setOf("pdf", "foto", "suelto"), raiz.cuerpos.mapNotNull { it.ref }.toSet())
        assertTrue("todos son mensajes", raiz.cuerpos.all { it.clase == Cuerpo.MENSAJE })
        // Un comentario suelto no puede parecer un archivo: nace pequeño.
        assertTrue(raiz.cuerpos.first { it.ref == "suelto" }.tamano < 1f)
        assertEquals(1.2f, raiz.cuerpos.first { it.ref == "pdf" }.tamano, 1e-6f)
    }

    @Test fun `las paginas son planetas del pdf y lo comentado en una pagina, su satelite`() {
        val u = armado()
        val raiz = Universos.deProyecto("pr-1")
        val sistemaDelPdf = dentroDe(u, raiz, "pdf")
        assertNotNull("el PDF abre su sistema solar", sistemaDelPdf)
        val planetas = u.espacio(sistemaDelPdf!!).cuerpos.mapNotNull { it.ref }
        assertEquals(listOf("p1", "p2"), planetas)
        // El sol de ese espacio es el propio PDF.
        assertEquals("pdf", u.espacio(sistemaDelPdf).sol?.ref)

        val dePagina1 = dentroDe(u, sistemaDelPdf, "p1")
        assertNotNull("la página comentada abre el suyo", dePagina1)
        assertEquals(listOf("c1"), u.espacio(dePagina1!!).cuerpos.mapNotNull { it.ref })
        // Y un comentario al comentario sigue hacia dentro: la regla no se acaba en la luna.
        val deComentario = dentroDe(u, dePagina1, "c1")
        assertEquals(listOf("c2"), u.espacio(deComentario!!).cuerpos.mapNotNull { it.ref })
        // Lo que no tiene respuestas no abre nada: un planeta vacío no es un sistema.
        assertNull(dentroDe(u, sistemaDelPdf, "p2"))
        assertNull(dentroDe(u, raiz, "foto"))
    }

    @Test fun `volver a armarlo no duplica ni mueve lo que el usuario colocó`() {
        val antes = armado()
        val raiz = Universos.deProyecto("pr-1")
        // El usuario mueve y agranda el PDF.
        val suyo = antes.espacio(raiz).cuerpos.first { it.ref == "pdf" }.copy(x = 7f, y = -3f, tamano = 3f)
        val movido = antes.con(antes.espacio(raiz).conCuerpo(suyo))
        val despues = movido.desdeElChat("pr-1", chat, ahora = 2000)
        val ahora = despues.espacio(raiz).cuerpos.filter { it.ref == "pdf" }
        assertEquals("no se mete dos veces", 1, ahora.size)
        assertEquals(7f, ahora[0].x, 1e-6f)
        assertEquals(3f, ahora[0].tamano, 1e-6f)
    }

    @Test fun `lo nuevo del chat entra donde le toca`() {
        val antes = armado()
        val despues = antes.desdeElChat(
            "pr-1", chat + NodoDelChat("c3", "y la escala", respondeA = "p2", esTexto = true), ahora = 3000
        )
        val sistema = dentroDe(despues, Universos.deProyecto("pr-1"), "pdf")!!
        val dePagina2 = dentroDe(despues, sistema, "p2")
        assertNotNull("la página 2 ya tiene quien le hable", dePagina2)
        assertEquals(listOf("c3"), despues.espacio(dePagina2!!).cuerpos.mapNotNull { it.ref })
    }

    @Test fun `lo que se quitó a mano no vuelve solo`() {
        val u = Universos().desdeElChat("pr-1", chat, ahora = 1000, quitados = setOf("foto"))
        val raiz = u.espacio(Universos.deProyecto("pr-1"))
        assertTrue("la foto se quedó fuera", raiz.cuerpos.none { it.ref == "foto" })
        assertTrue("las demás siguen", raiz.cuerpos.any { it.ref == "pdf" })
    }

    @Test fun `un hilo que se responde a si mismo no da vueltas para siempre`() {
        val enBucle = listOf(
            NodoDelChat("a", "a"),
            NodoDelChat("b", "b", respondeA = "a"),
            NodoDelChat("a2", "a otra vez", respondeA = "b")
        )
        // No se cuelga y arma lo que tiene sentido.
        val u = Universos().desdeElChat("pr-1", enBucle, ahora = 1000)
        assertTrue(u.espacios.size in 2..15)
    }
}
