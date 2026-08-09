package com.forge.pixpin.motor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Los proyectos: varias hojas que se entregan juntas.
 *
 * Lo que se comprueba aquí es sobre todo **lo que no puede pasar**: que anotar
 * dos veces la misma página del PDF no dé dos hojas, que reabrir un plano lleve
 * al proyecto de ayer y no a uno nuevo, y que un proyecto nacido de un PDF no
 * acabe reconstruyéndose página a página al exportar — que es como se perdería
 * el texto del original, o sea lo que más costó conseguir.
 */
class ProyectosTest {

    private fun hoja(id: String, pagina: Int? = null, marco: String? = null) =
        Hoja(id = id, dibujo = "d$id", marco = marco, pagina = pagina)

    private fun proyecto(
        id: String = "p", nombre: String = "Obra", pdf: String? = null, tocado: Long = 0
    ) = Proyecto(id = id, nombre = nombre, pdfOrigen = pdf, tocado = tocado)

    // ---- Nombres ----

    /**
     * Pedir un nombre antes de dejarte empezar es una pregunta en el peor
     * momento: todavía no sabes qué va a ser esto.
     */
    @Test
    fun `el nombre que se propone no repite`() {
        assertEquals("Proyecto 1", Proyectos.nombreLibre(emptyList(), "Proyecto"))
        val uno = listOf(proyecto(nombre = "Proyecto 1"))
        assertEquals("Proyecto 2", Proyectos.nombreLibre(uno, "Proyecto"))
        // Con dos proyectos empieza a probar por el 3, y como está libre, ese.
        val lio = listOf(proyecto("a", "Proyecto 2"), proyecto("b", "otra cosa"))
        assertEquals("Proyecto 3", Proyectos.nombreLibre(lio, "Proyecto"))
        // Se empieza a probar por «los que hay + 1», así que los números pueden
        // saltar. Da igual: lo que no puede pasar nunca es que el propuesto sea
        // uno que ya existe, y eso es lo que se comprueba, sin adivinar cuál.
        val lleno = (1..6).map { proyecto("p$it", "Proyecto $it") }
        for (cuantos in 0..lleno.size) {
            val hay = lleno.take(cuantos)
            val propuesto = Proyectos.nombreLibre(hay, "Proyecto")
            assertTrue(propuesto, hay.none { it.nombre.equals(propuesto, ignoreCase = true) })
        }
    }

    @Test
    fun `un nombre en blanco no borra el que había`() {
        val p = Proyectos.renombrado(proyecto(nombre = "Obra"), "   ", 10)
        assertEquals("Obra", p.nombre)
    }

    // ---- El orden de la lista ----

    @Test
    fun `lo que está en marcha va primero y lo reciente arriba`() {
        val lista = listOf(
            proyecto("viejo", "Viejo", tocado = 100),
            proyecto("nuevo", "Nuevo", tocado = 900),
            Proyectos.archivado(proyecto("hecho", "Hecho", tocado = 999), true, 999)
        )
        assertEquals(
            listOf("nuevo", "viejo", "hecho"),
            Proyectos.ordenados(lista).map { it.id }
        )
    }

    // ---- Las hojas ----

    @Test
    fun `las hojas se guardan en su orden`() {
        var p = proyecto()
        p = Proyectos.conHoja(p, hoja("a"), 1)
        p = Proyectos.conHoja(p, hoja("b"), 2)
        assertEquals(listOf("a", "b"), p.hojas.map { it.id })
        assertEquals(2L, p.tocado)
    }

    /**
     * **La misma página del PDF no entra dos veces.**
     *
     * Anotar la página 3 el lunes y volver a ella el martes tiene que llevar a
     * la hoja que ya hay. Sin esto habría dos hojas 3, y la de después taparía a
     * la de antes al exportar sin que nadie se enterara.
     */
    @Test
    fun `una página del PDF no se mete dos veces`() {
        var p = proyecto(pdf = "plano.pdf")
        p = Proyectos.conHoja(p, hoja("a", pagina = 3), 1)
        p = Proyectos.conHoja(p, hoja("b", pagina = 3), 2)
        assertEquals(1, p.hojas.size)
        assertEquals("a", p.hojas[0].id)
    }

    /**
     * **El mismo marco tampoco entra dos veces.**
     *
     * La unidad que se guarda es un marco del lienzo, y uno retoca el marco y
     * vuelve a guardarlo. Sin esto, cada retoque dejaría otra hoja igual en el
     * proyecto y al exportar saldría la misma lámina diez veces.
     */
    @Test
    fun `el mismo marco no se guarda dos veces`() {
        var p = proyecto()
        p = Proyectos.conHoja(p, Hoja("a", dibujo = "lienzo", marco = "m1"), 1)
        p = Proyectos.conHoja(p, Hoja("b", dibujo = "lienzo", marco = "m1"), 2)
        assertEquals(1, p.hojas.size)
        // Pero otro marco del mismo lienzo sí es otra hoja: es otra lámina.
        p = Proyectos.conHoja(p, Hoja("c", dibujo = "lienzo", marco = "m2"), 3)
        assertEquals(2, p.hojas.size)
    }

    /** Y se puede encontrar la que ya estaba, para abrirla en vez de duplicarla. */
    @Test
    fun `se encuentra la hoja que señala a lo mismo`() {
        val p = Proyectos.conHoja(proyecto(), Hoja("a", dibujo = "l", marco = "m1"), 1)
        val otra = Hoja("nueva", dibujo = "l", marco = "m1")
        assertEquals("a", Proyectos.hojaComo(p, otra)?.id)
        assertNull(Proyectos.hojaComo(p, Hoja("x", dibujo = "l", marco = "m9")))
    }

    // ---- Un PDF entero como proyecto ----

    /**
     * Al pinear un plano de doce hojas uno no quiere doce decisiones: quiere
     * las doce ahí, poder abrir la que le interese y dibujar.
     */
    @Test
    fun `un PDF entra con todas sus páginas`() {
        val p = Proyectos.dePdf("p", "Plano", "plano.pdf", 12, 5) { "h$it" }
        assertEquals(12, p.hojas.size)
        assertEquals((0 until 12).toList(), p.hojas.map { it.pagina })
        // Nacen sin dibujo: todavía no hay nada anotado encima.
        assertTrue(p.hojas.all { it.dibujo == null })
        assertEquals("plano.pdf", p.pdfOrigen)
    }

    @Test
    fun `un PDF enorme se corta en el tope`() {
        val p = Proyectos.dePdf("p", "Tocho", "t.pdf", 5000, 1) { "h$it" }
        assertEquals(Proyectos.MAX_HOJAS, p.hojas.size)
    }

    /** Abrir una página y trazar la primera raya le pone dibujo a esa hoja. */
    @Test
    fun `una hoja del PDF recibe su dibujo`() {
        var p = Proyectos.dePdf("p", "Plano", "plano.pdf", 3, 1) { "h$it" }
        assertTrue(Proyectos.anotadas(p).isEmpty())
        p = Proyectos.conDibujo(p, "h1", "dibujo-1", 9)
        assertEquals(listOf("h1"), Proyectos.anotadas(p).map { it.id })
        assertEquals("dibujo-1", Proyectos.hojaDePagina(p, 1)?.dibujo)
        // Y las demás siguen como estaban.
        assertNull(Proyectos.hojaDePagina(p, 0)?.dibujo)
        assertEquals(9L, p.tocado)
    }

    @Test
    fun `poner dibujo a una hoja que no existe no toca nada`() {
        val p = Proyectos.dePdf("p", "Plano", "plano.pdf", 2, 1) { "h$it" }
        assertSame(p, Proyectos.conDibujo(p, "no-existe", "d", 9))
    }

    /** Pero dos hojas en blanco sí son dos hojas: no tienen número que repetir. */
    @Test
    fun `dos hojas en blanco son dos hojas`() {
        var p = proyecto()
        p = Proyectos.conHoja(p, hoja("a"), 1)
        p = Proyectos.conHoja(p, hoja("b"), 2)
        assertEquals(2, p.hojas.size)
    }

    @Test
    fun `no se pasa del tope de hojas`() {
        var p = proyecto()
        for (i in 0 until Proyectos.MAX_HOJAS + 10) p = Proyectos.conHoja(p, hoja("h$i"), i.toLong())
        assertEquals(Proyectos.MAX_HOJAS, p.hojas.size)
    }

    /** El orden de las hojas es el de las páginas, así que tiene que moverse. */
    @Test
    fun `una hoja se cambia de sitio`() {
        var p = proyecto()
        listOf("a", "b", "c").forEach { p = Proyectos.conHoja(p, hoja(it), 1) }
        assertEquals(listOf("c", "a", "b"), Proyectos.movida(p, "c", 0, 2).hojas.map { it.id })
        assertEquals(listOf("a", "c", "b"), Proyectos.movida(p, "b", 2, 2).hojas.map { it.id })
        // Dejarla donde ya estaba no es mover: no se toca ni la lista ni la fecha.
        assertSame(p, Proyectos.movida(p, "b", 1, 2))
    }

    @Test
    fun `mover algo que no está no rompe nada`() {
        val p = Proyectos.conHoja(proyecto(), hoja("a"), 1)
        assertSame(p, Proyectos.movida(p, "z", 0, 2))
    }

    @Test
    fun `moverla más allá del final la deja al final`() {
        var p = proyecto()
        listOf("a", "b", "c").forEach { p = Proyectos.conHoja(p, hoja(it), 1) }
        assertEquals(listOf("b", "c", "a"), Proyectos.movida(p, "a", 99, 2).hojas.map { it.id })
    }

    @Test
    fun `quitar una hoja deja las demás como estaban`() {
        var p = proyecto()
        listOf("a", "b", "c").forEach { p = Proyectos.conHoja(p, hoja(it), 1) }
        assertEquals(listOf("a", "c"), Proyectos.sinHoja(p, "b", 2).hojas.map { it.id })
    }

    // ---- Archivar ----

    /** Archivar no es borrar: el proyecto se queda entero, con sus hojas. */
    @Test
    fun `archivar conserva las hojas`() {
        val p = Proyectos.archivado(Proyectos.conHoja(proyecto(), hoja("a"), 1), true, 2)
        assertTrue(p.archivado)
        assertEquals(1, p.hojas.size)
        assertTrue(!Proyectos.archivado(p, false, 3).archivado)
    }

    // ---- A cuál va lo siguiente ----

    /**
     * Una hoja nueva tiene que ir a alguna parte **sin preguntar a cuál**: si
     * acabas de anotar la página 3 de un plano, la 7 va con ella.
     */
    @Test
    fun `el proyecto en curso es el más reciente sin archivar`() {
        val lista = listOf(
            proyecto("a", tocado = 100),
            proyecto("b", tocado = 900),
            Proyectos.archivado(proyecto("c", tocado = 999), true, 999)
        )
        assertEquals("b", Proyectos.enCurso(lista)?.id)
        assertNull(Proyectos.enCurso(lista.map { Proyectos.archivado(it, true, 1) }))
    }

    /** Y reabrir el mismo plano lleva al proyecto de ayer, no a uno nuevo. */
    @Test
    fun `un PDF ya empezado lleva a su proyecto`() {
        val lista = listOf(proyecto("a", pdf = "plano.pdf"), proyecto("b"))
        assertEquals("a", Proyectos.deEstePdf(lista, "plano.pdf")?.id)
        assertNull(Proyectos.deEstePdf(lista, "otro.pdf"))
    }

    @Test
    fun `actualizar mete el que no estaba y sustituye al que sí`() {
        val lista = listOf(proyecto("a", "Uno"))
        assertEquals(2, Proyectos.actualizada(lista, proyecto("b", "Dos")).size)
        val cambiada = Proyectos.actualizada(lista, proyecto("a", "Otro"))
        assertEquals(1, cambiada.size)
        assertEquals("Otro", cambiada[0].nombre)
    }

    // ---- Qué sale al exportar ----

    @Test
    fun `sin PDF de origen se escribe un PDF nuevo`() {
        var p = proyecto()
        p = Proyectos.conHoja(p, hoja("a"), 1)
        val salida = Proyectos.salidaDe(p)
        assertTrue(salida is SalidaDeProyecto.PdfNuevo)
        assertEquals(1, (salida as SalidaDeProyecto.PdfNuevo).hojas.size)
    }

    /**
     * **La regla que salva el texto del original.**
     *
     * Un proyecto nacido de un PDF no se reconstruye página a página: eso
     * convertiría el texto en dibujo. El PDF de origen ya lleva las capas
     * dentro, así que es el resultado, no una fuente que se copia.
     */
    @Test
    fun `con PDF de origen el resultado es ese mismo PDF`() {
        var p = proyecto(pdf = "plano.pdf")
        p = Proyectos.conHoja(p, hoja("a", pagina = 0), 1)
        p = Proyectos.conHoja(p, hoja("b", pagina = 4), 2)
        assertEquals(SalidaDeProyecto.ElPdfDeOrigen, Proyectos.salidaDe(p))
    }

    /** Y si además se añadieron hojas en blanco, van detrás. */
    @Test
    fun `las hojas en blanco de un PDF van al final`() {
        var p = proyecto(pdf = "plano.pdf")
        p = Proyectos.conHoja(p, hoja("a", pagina = 0), 1)
        p = Proyectos.conHoja(p, hoja("extra"), 2)
        val salida = Proyectos.salidaDe(p)
        assertTrue(salida is SalidaDeProyecto.ElPdfConHojasDetras)
        assertEquals(
            listOf("extra"),
            (salida as SalidaDeProyecto.ElPdfConHojasDetras).hojas.map { it.id }
        )
    }
}
