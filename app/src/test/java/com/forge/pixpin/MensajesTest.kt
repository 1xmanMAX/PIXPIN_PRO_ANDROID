package com.forge.pixpin.guardados

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lo que decide si uno **encuentra** lo que guardó.
 *
 * Las cuatro cosas de esta pantalla que, mal hechas, la vuelven inútil: en qué sección cae
 * cada cosa, qué encuentra la búsqueda, cómo se agrupa por días y qué se borra solo.
 */
class MensajesTest {

    private var n = 0
    private fun msg(
        clase: Clase = Clase.NOTA,
        cuando: Long = ++n * 1000L,
        texto: String = "",
        nombre: String = "",
        fijado: Boolean = false,
        buzon: Boolean = false
    ) = Mensaje(
        id = "m${n}", cuando = cuando, clase = clase, texto = texto,
        nombre = nombre, fijado = fijado, enBuzon = buzon
    )

    // ---- Secciones -------------------------------------------------------

    /** Una página de PDF cae en archivos: quien busca «aquel PDF» no distingue. */
    @Test
    fun `una pagina de pdf cuenta como archivo`() {
        assertTrue(Clase.PAGINA in clasesDe(Seccion.ARCHIVOS))
        assertTrue(Clase.ARCHIVO in clasesDe(Seccion.ARCHIVOS))
    }

    @Test
    fun `cada seccion se queda con lo suyo`() {
        val todos = listOf(
            msg(Clase.IMAGEN), msg(Clase.ARCHIVO), msg(Clase.VOZ), msg(Clase.NOTA)
        )
        assertEquals(1, deSeccion(todos, Seccion.FOTOS).size)
        assertEquals(1, deSeccion(todos, Seccion.VOZ).size)
        assertEquals(4, deSeccion(todos, Seccion.TODO).size)
    }

    /** La conversación se lee hacia abajo: lo último, al final. */
    @Test
    fun `la conversacion va del mas viejo al mas nuevo`() {
        val a = msg(cuando = 300); val b = msg(cuando = 100); val c = msg(cuando = 200)
        val orden = deSeccion(listOf(a, b, c), Seccion.TODO).map { it.cuando }
        assertEquals(listOf(100L, 200L, 300L), orden)
    }

    /** Los fijados no son conversación: manda lo reciente. */
    @Test
    fun `los fijados van del mas nuevo al mas viejo`() {
        val a = msg(cuando = 100, fijado = true)
        val b = msg(cuando = 300, fijado = true)
        val c = msg(cuando = 200)
        val orden = deSeccion(listOf(a, b, c), Seccion.FIJADOS).map { it.cuando }
        assertEquals(listOf(300L, 100L), orden)
    }

    // ---- Búsqueda --------------------------------------------------------

    /** Se busca también en el nombre del archivo, no solo en lo escrito. */
    @Test
    fun `la busqueda mira el texto y el nombre`() {
        val lista = listOf(
            msg(texto = "acordarse del taller"),
            msg(clase = Clase.ARCHIVO, nombre = "factura taller.pdf"),
            msg(texto = "otra cosa")
        )
        assertEquals(2, buscar(lista, "taller").size)
    }

    /**
     * **Sin tildes.** Uno escribe «practica» con prisa y lo que guardó era «Práctica 3».
     * Una búsqueda que no encuentra eso se abandona a la segunda vez.
     */
    @Test
    fun `la busqueda no se pelea con los acentos`() {
        val lista = listOf(msg(nombre = "Práctica 3.pdf"), msg(texto = "MAÑANA"))
        assertEquals(1, buscar(lista, "practica").size)
        assertEquals(1, buscar(lista, "manana").size)
        assertEquals(1, buscar(lista, "Mañana").size)
    }

    @Test
    fun `sin consulta no se filtra nada`() {
        val lista = listOf(msg(), msg())
        assertEquals(2, buscar(lista, "   ").size)
    }

    // ---- Días ------------------------------------------------------------

    /** Los separadores de día parten la lista donde cambia el día, ni antes ni después. */
    @Test
    fun `se agrupa por dias`() {
        val lista = listOf(
            msg(cuando = 1), msg(cuando = 2), msg(cuando = 100), msg(cuando = 101)
        )
        val tramos = porDias(lista) { if (it < 50) 1L else 2L }
        assertEquals(2, tramos.size)
        assertEquals(2, tramos[0].mensajes.size)
        assertEquals(2, tramos[1].mensajes.size)
        assertEquals(listOf(1L, 2L), tramos.map { it.dia })
    }

    @Test
    fun `sin mensajes no hay dias`() {
        assertTrue(porDias(emptyList()) { 1L }.isEmpty())
    }

    // ---- Buzón -----------------------------------------------------------

    /** Lo del buzón se va solo a la semana; lo rescatado y lo fijado, nunca. */
    @Test
    fun `el buzon se limpia solo`() {
        val ahora = 30L * 24 * 60 * 60 * 1000
        val viejo = ahora - 10L * 24 * 60 * 60 * 1000
        val lista = listOf(
            msg(cuando = viejo, buzon = true),
            msg(cuando = viejo, buzon = true, fijado = true),
            msg(cuando = viejo, buzon = false),
            msg(cuando = ahora, buzon = true)
        )
        val fuera = caducados(lista, ahora)
        assertEquals(1, fuera.size)
        assertTrue(fuera.first().enBuzon && !fuera.first().fijado)
    }

    // ---- El buzón: qué entra ---------------------------------------------

    /** Lo que tiene contenido entra; lo que es un trasto, no. */
    @Test
    fun `al buzon solo entra lo que tiene algo dentro`() {
        val conArchivo = delPin("a", 1, Clase.IMAGEN, null, "/x/foto.png", "foto.png")
        val conTexto = delPin("b", 1, Clase.NOTA, "acuérdate", null, "")
        val trasto = delPin("c", 1, Clase.NOTA, "   ", null, "")
        val sinClase = delPin("d", 1, null, "algo", null, "")

        assertTrue(conArchivo != null && conArchivo.enBuzon)
        assertTrue(conTexto != null && conTexto.enBuzon)
        assertTrue("un pin vacío no debería guardarse", trasto == null)
        assertTrue(sinClase == null)
    }

    /** Un dibujo entra por su referencia, aunque no tenga archivo. */
    @Test
    fun `un dibujo entra por su referencia`() {
        val d = delPin("e", 1, Clase.DIBUJO, null, null, "", referencia = "dib-1")
        assertTrue(d != null && d.referencia == "dib-1")
    }

    /** Editar diez veces el mismo dibujo no puede dejar diez entradas. */
    @Test
    fun `no se repite lo que ya esta en el buzon`() {
        val uno = delPin("e", 1, Clase.DIBUJO, null, null, "", referencia = "dib-1")!!
        assertEquals(uno, yaEnBuzon(listOf(uno), "dib-1"))
        assertNull(yaEnBuzon(listOf(uno), "dib-2"))
        // Lo ya rescatado no cuenta: si vuelve a editarse, entra de nuevo.
        assertNull(yaEnBuzon(listOf(uno.copy(enBuzon = false)), "dib-1"))
    }

    /**
     * Al rescatar se le pone la fecha de **ahora**.
     *
     * Con la fecha vieja se hundiría entre lo de la semana pasada justo al decidir
     * salvarlo, que es cuando uno lo va a buscar.
     */
    @Test
    fun `lo rescatado sale del buzon y se pone al dia`() {
        val viejo = msg(buzon = true, cuando = 100)
        val r = rescatado(viejo, 9_000)
        assertTrue(!r.enBuzon)
        assertEquals(9_000L, r.cuando)
    }

    // ---- Detalles --------------------------------------------------------

    @Test
    fun `el tamano se escribe como lo escribe el sistema`() {
        assertEquals("", tamanoLegible(0))
        assertEquals("512 B", tamanoLegible(512))
        assertEquals("4 kB", tamanoLegible(4_000))
        assertTrue(tamanoLegible(2_300_000).startsWith("2"))
        assertTrue(tamanoLegible(2_300_000).endsWith("MB"))
    }
}
