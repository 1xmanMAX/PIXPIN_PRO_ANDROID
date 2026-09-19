package com.forge.pixpin

import com.forge.pixpin.data.Abiertos
import com.forge.pixpin.data.LienzoAbierto
import com.forge.pixpin.motor.HaciaDonde
import com.forge.pixpin.ui.Multilienzo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** La multitarea de dentro: la rueda de lienzos y cómo se reparte la pantalla. */
class MultilienzoTest {

    private fun l(id: String) = LienzoAbierto(id = id, nombre = id)
    private val tres = listOf(l("a"), l("b"), l("c"))

    // ---- La rueda ----

    @Test
    fun `solo se enlistan los que se abren, y abrir otra vez no cambia el orden`() {
        var lista = Abiertos.con(emptyList(), l("a"))
        lista = Abiertos.con(lista, l("b"))
        assertEquals(listOf("a", "b"), lista.map { it.id })
        lista = Abiertos.con(lista, l("a").copy(nombre = "Planta"))
        assertEquals(listOf("a", "b"), lista.map { it.id })
        assertEquals("Planta", lista.first().nombre)
    }

    @Test
    fun `es una lista y no un carrusel, al final no hay más`() {
        assertEquals("b", Abiertos.alLado(tres, "a", 1)?.id)
        assertEquals("c", Abiertos.alLado(tres, "b", 1)?.id)
        // Al final de la fila no se sigue por el otro extremo: hay que volver.
        assertNull(Abiertos.alLado(tres, "c", 1))
        assertNull(Abiertos.alLado(tres, "a", -1))
        assertEquals("a", Abiertos.alLado(tres, "b", -1)?.id)
        // Con uno solo no hay a dónde ir por ningún lado: ahí se ofrece abrir otro.
        assertNull(Abiertos.alLado(listOf(l("a")), "a", 1))
        assertNull(Abiertos.alLado(listOf(l("a")), "a", -1))
    }

    @Test
    fun `los vecinos son el de antes y el de después`() {
        val (antes, despues) = Abiertos.vecinos(tres, "b")
        assertEquals("a", antes?.id)
        assertEquals("c", despues?.id)
        assertNull(Abiertos.vecinos(tres, "a").first)
        assertNull(Abiertos.vecinos(tres, "c").second)
    }

    @Test
    fun `se ordenan a mano y se cierran`() {
        assertEquals(listOf("b", "a", "c"), Abiertos.mover(tres, 0, 1).map { it.id })
        assertEquals(listOf("c", "a", "b"), Abiertos.mover(tres, 2, 0).map { it.id })
        assertEquals(tres, Abiertos.mover(tres, 0, 9))
        assertEquals(listOf("a", "c"), Abiertos.sin(tres, "b").map { it.id })
    }

    @Test
    fun `no se guardan más de los que caben`() {
        var lista = emptyList<LienzoAbierto>()
        for (i in 1..Abiertos.CUANTOS + 5) lista = Abiertos.con(lista, l("x$i"))
        assertEquals(Abiertos.CUANTOS, lista.size)
        assertEquals("x${Abiertos.CUANTOS + 5}", lista.last().id)
    }

    // ---- La tira ----

    private val pantalla = 1000
    private val pestana = 20
    private val aire = 6

    @Test
    fun `la pantalla se parte en huecos iguales y cada columna ocupa uno, dos o tres`() {
        val unidad = Multilienzo.unidad(pantalla, pestana, aire, porPantalla = 3)
        assertEquals((960 - 12) / 3, unidad)
        assertEquals(unidad, Multilienzo.tamano(1, unidad, aire, 3))
        assertEquals(2 * unidad + aire, Multilienzo.tamano(2, unidad, aire, 3))
        assertEquals(3 * unidad + 2 * aire, Multilienzo.tamano(3, unidad, aire, 3))
        // Nunca más huecos de los que caben, ni menos de uno.
        assertEquals(Multilienzo.tamano(3, unidad, aire, 3), Multilienzo.tamano(9, unidad, aire, 3))
        assertEquals(unidad, Multilienzo.tamano(0, unidad, aire, 3))
    }

    @Test
    fun `el tamaño es libre, con topes, y con un imán suave en los huecos justos`() {
        val unidad = 300
        // Nunca menos de dos pestañas ni más que la pantalla menos **las dos** pestañas.
        assertEquals(40, Multilienzo.tamanoLibre(5, pantalla, pestana))
        assertEquals(960, Multilienzo.tamanoLibre(5000, pantalla, pestana))
        // En columna la pestaña de arriba lleva la barra encima: el tope baja lo que mida.
        assertEquals(1000 - 120 - 20, Multilienzo.tamanoLibre(5000, pantalla, pestana, antes = 120))
        // El primero y el último solo tienen vecino a un lado: menos **una** pestaña, la suya.
        assertEquals(980, Multilienzo.tamanoLibre(5000, pantalla, pestana, i = 0, cuantos = 3))
        assertEquals(980, Multilienzo.tamanoLibre(5000, pantalla, pestana, i = 2, cuantos = 3))
        // En columna, el primero no guarda la pestaña alta de arriba; el último sí.
        assertEquals(980, Multilienzo.tamanoLibre(5000, pantalla, pestana, antes = 120, i = 0, cuantos = 2))
        assertEquals(880, Multilienzo.tamanoLibre(5000, pantalla, pestana, antes = 120, i = 1, cuantos = 2))
        // Con las dos barras puestas crecen las dos pestañas; la pantalla es la misma.
        assertEquals(1000 - 120 - 150, Multilienzo.tamanoLibre(5000, pantalla, pestana, antes = 120, i = 1, cuantos = 3, despues = 150))
        assertEquals(1000 - 150, Multilienzo.tamanoLibre(5000, pantalla, pestana, antes = 120, i = 0, cuantos = 3, despues = 150))
        // El elástico: cede cada vez menos, nunca pasa de su límite y tira hacia los dos lados.
        assertEquals(0f, Multilienzo.elastico(0f, 50f))
        assertEquals(25f, Multilienzo.elastico(50f, 50f))
        assertTrue(Multilienzo.elastico(100000f, 50f) < 50f)
        assertEquals(-25f, Multilienzo.elastico(-50f, 50f))
        assertEquals(500, Multilienzo.tamanoLibre(500, pantalla, pestana))
        // Cerca de un hueco justo se clava; lejos, se queda como está.
        assertEquals(300, Multilienzo.imantar(310, unidad, aire, 3))
        assertEquals(606, Multilienzo.imantar(595, unidad, aire, 3))
        assertEquals(450, Multilienzo.imantar(450, unidad, aire, 3))
    }

    @Test
    fun `las columnas van una detrás de otra y la tira se pega a la pestaña de la que manda`() {
        val t = intArrayOf(960, 400, 960)
        assertEquals(listOf(0, 966, 1372), Multilienzo.sitios(t, aire).toList())
        assertEquals(1372 + 960, Multilienzo.largo(t, aire))
        assertEquals(966f - 20f, Multilienzo.paraVer(1, t, aire, pestana))
        // El primero y el último llegan al canto: a ese lado no hay a quién dejarle sitio.
        assertEquals(0f, Multilienzo.limitar(-5000f, t, aire, pestana, pantalla))
        assertEquals((Multilienzo.largo(t, aire) - pantalla).toFloat(), Multilienzo.limitar(99999f, t, aire, pestana, pantalla))
        // Y si toda la tira cabe en la pantalla, no se corre.
        assertEquals(0f, Multilienzo.limitar(300f, intArrayOf(300, 300), aire, pestana, pantalla))
    }

    @Test
    fun `en el abanico las de en medio se apartan de la que va en el dedo`() {
        // La 4 camino de la 1: la 1, la 2 y la 3 se corren una a la derecha.
        assertEquals(listOf(0, 2, 3, 4, 1), (0..4).map { Multilienzo.ranuraDelAbanico(it, enElDedo = 4, hueco = 1) })
        // La 0 camino de la 2: la 1 y la 2 se corren una a la izquierda.
        assertEquals(listOf(2, 0, 1, 3, 4), (0..4).map { Multilienzo.ranuraDelAbanico(it, enElDedo = 0, hueco = 2) })
        // Sin nadie en el dedo, cada una en su sitio.
        assertEquals(listOf(0, 1, 2), (0..2).map { Multilienzo.ranuraDelAbanico(it, -1, 0) })
    }

    @Test
    fun `los dedos saltan entero, según hacia dónde fue el gesto`() {
        assertEquals(2, Multilienzo.alSoltar(desde = 1, corridoPorElDedo = -200f, umbral = 40f, cuantos = 3))
        assertEquals(0, Multilienzo.alSoltar(desde = 1, corridoPorElDedo = 200f, umbral = 40f, cuantos = 3))
        assertEquals(1, Multilienzo.alSoltar(1, -10f, 40f, 3))
        assertEquals(2, Multilienzo.alSoltar(2, -500f, 40f, 3))
        assertEquals(0, Multilienzo.alSoltar(0, 500f, 40f, 3))
    }

    @Test
    fun `de pie va en columna y tumbada caben dos o tres`() {
        assertTrue(Multilienzo.enColumna(anchoDp = 400, altoDp = 900))
        assertEquals(1, Multilienzo.porPantalla(400, 900))
        assertEquals(3, Multilienzo.porPantalla(1000, 700))
        assertEquals(2, Multilienzo.porPantalla(700, 400))
        assertEquals(1, Multilienzo.porPantalla(560, 320))
    }

    // ---- El gesto ----

    @Test
    fun `los cuatro dedos deciden por el eje en el que más se han ido`() {
        assertEquals(HaciaDonde.DERECHA, com.forge.pixpin.motor.haciaDonde(120f, 20f, 50f))
        assertEquals(HaciaDonde.IZQUIERDA, com.forge.pixpin.motor.haciaDonde(-120f, 20f, 50f))
        assertEquals(HaciaDonde.ARRIBA, com.forge.pixpin.motor.haciaDonde(10f, -120f, 50f))
        // Hacia abajo no hace nada, y un temblor tampoco.
        assertNull(com.forge.pixpin.motor.haciaDonde(10f, 120f, 50f))
        assertNull(com.forge.pixpin.motor.haciaDonde(20f, 20f, 50f))
    }

    // ---- La lupa en una columna estrecha ----

    @Test
    fun `la lupa no sale donde no cabe`() {
        // Un lienzo de dos pestañas (36 dp) es más pequeño que la lupa: fue un fallo real.
        assertEquals(false, com.forge.pixpin.motor.cabeLaLupa(ancho = 100f, alto = 2000f, lado = 200f, margen = 8f))
        assertTrue(com.forge.pixpin.motor.cabeLaLupa(ancho = 1000f, alto = 2000f, lado = 200f, margen = 8f))
    }

    @Test
    fun `la pestaña se puede tocar y hay cinco lienzos como mucho`() {
        assertTrue(Multilienzo.PESTANA >= 24f)
        assertEquals(5, Abiertos.CUANTOS)
    }
}
