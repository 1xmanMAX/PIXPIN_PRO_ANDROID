package com.forge.pixpin.croquis3d

import com.forge.pixpin.motor.Pt
import com.forge.pixpin.motor.Pt3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/**
 * El croquis en el espacio.
 *
 * Lo que se comprueba aquí es lo único que puede estar mal de verdad en una aplicación de
 * dibujar en 3D: **dónde cae el dedo**. Todo lo demás —cómo se ve, qué color tiene— se
 * mira en la pantalla; que un toque acabe en el punto que uno cree, no.
 */
class Croquis3DTest {

    private val tol = 1e-6
    private val ancho = 1000.0
    private val alto = 2000.0

    private fun camara(giro: Double = 0.0, inclinacion: Double = 0.0, zoom: Double = 1.0) =
        Camara3D(giro = giro, inclinacion = inclinacion, zoom = zoom)

    // ---- La cámara ----

    /** El centro de la cámara cae en el centro de la pantalla, mire desde donde mire. */
    @Test
    fun `el centro siempre cae en el centro`() {
        for (g in listOf(0.0, 1.0, 2.5, -3.0)) {
            for (i in listOf(-1.0, 0.0, 0.9)) {
                val c = camara(g, i)
                val p = c.aPantalla(c.centro, ancho, alto)
                assertEquals(ancho / 2, p.x, tol)
                assertEquals(alto / 2, p.y, tol)
            }
        }
    }

    /** Los tres ejes de la cámara son perpendiculares entre sí y de largo uno. */
    @Test
    fun `los ejes de la camara estan sanos`() {
        for (g in listOf(0.0, 0.7, -2.2)) {
            for (i in listOf(-1.2, 0.0, 1.2)) {
                val c = camara(g, i)
                assertEquals(1.0, largo(c.adelante), 1e-9)
                assertEquals(1.0, largo(c.derecha), 1e-9)
                assertEquals(1.0, largo(c.arriba), 1e-9)
                assertEquals(0.0, escalar(c.adelante, c.derecha), 1e-9)
                assertEquals(0.0, escalar(c.adelante, c.arriba), 1e-9)
                assertEquals(0.0, escalar(c.derecha, c.arriba), 1e-9)
            }
        }
    }

    /**
     * **Ir y volver cierra.** Un punto de la pantalla, llevado al plano de la pantalla y
     * proyectado de vuelta, tiene que caer donde estaba: es lo que hace que el trazo salga
     * bajo el dedo y no dos centímetros al lado.
     */
    @Test
    fun `del dedo al mundo y de vuelta`() {
        for (g in listOf(0.0, 0.9, -2.0)) {
            for (i in listOf(-0.8, 0.3, 1.1)) {
                val c = camara(g, i, zoom = 1.7)
                val plano = Plano3D(c.centro, c.adelante)
                for (p in listOf(Pt(10.0, 10.0), Pt(500.0, 1200.0), Pt(999.0, 1999.0))) {
                    val corte = plano.corte(c.rayo(p, ancho, alto))
                    assertNotNull("$g/$i/$p", corte)
                    val vuelta = c.aPantalla(corte!!.first, ancho, alto)
                    assertEquals(p.x, vuelta.x, 1e-6)
                    assertEquals(p.y, vuelta.y, 1e-6)
                }
            }
        }
    }

    /** La vista no se puede poner justo en el cenit: ahí el «arriba» deja de existir. */
    @Test
    fun `la inclinacion tiene tope`() {
        val arriba = camara().girada(0.0, 10.0)
        assertTrue(arriba.inclinacion <= Camara3D.TOPE_DE_INCLINACION + tol)
        assertEquals(1.0, largo(arriba.arriba), 1e-9)
        val abajo = camara().girada(0.0, -10.0)
        assertEquals(1.0, largo(abajo.arriba), 1e-9)
    }

    /** Y el aumento tampoco se dispara ni se apaga. */
    @Test
    fun `el zoom tiene topes`() {
        assertEquals(Camara3D.ZOOM_MAXIMO, camara().conZoom(1e6).zoom, tol)
        assertEquals(Camara3D.ZOOM_MINIMO, camara().conZoom(1e-6).zoom, tol)
    }

    // ---- Dónde cae el dedo ----

    /**
     * Sin nada delante, el rayo cae en el plano de la pantalla.
     *
     * Es lo que usa el **plano** al trazarse, que es la única herramienta que puede
     * dibujar sin apoyo: si no pudiera, no habría forma de crear el primero.
     */
    @Test
    fun `sin laminas se dibuja en la pantalla`() {
        val c = camara(zoom = 2.0)
        val impacto = dondeCae(Croquis(), c.rayo(Pt(300.0, 400.0), ancho, alto), c)
        assertNull(impacto.lamina)
        // Está en el plano de la cámara: su distancia al plano es cero.
        assertEquals(0.0, escalar(menos(impacto.punto, c.centro), c.adelante), 1e-6)
    }

    /**
     * **Y con una lámina delante, cae en ella.** Esta es la prueba que dice que la
     * aplicación funciona: se pone un plano, se gira, y al tocar encima se dibuja sobre
     * él y no en el aire.
     */
    @Test
    fun `con una lamina delante se dibuja en ella`() {
        val deFrente = camara(giro = 0.0, inclinacion = 0.0)
        // Una lámina trazada mirando de frente: barrida hacia dentro.
        val lamina = Lamina3D(
            id = "l",
            perfil = listOf(Pt3(-100.0, 0.0, 0.0), Pt3(100.0, 0.0, 0.0)),
            direccion = deFrente.adelante,
            fondo = 200.0,
            color = "#1e1e1e",
            grosor = 4.0
        )
        // Se gira un cuarto: ahora la lámina se ve de plano.
        val deLado = camara(giro = Math.PI / 2, inclinacion = 0.3)
        val impacto = dondeCae(
            Croquis(laminas = listOf(lamina)),
            deLado.rayo(Pt(ancho / 2, alto / 2), ancho, alto),
            deLado
        )
        assertEquals("l", impacto.lamina)
        // Y el punto está sobre la lámina: su altura es la del perfil.
        assertEquals(0.0, impacto.punto.z, 1e-6)
    }

    /** De dos superficies, el rayo se queda con la que está delante. Lo usa el borrador. */
    @Test
    fun `se dibuja sobre la mas cercana`() {
        val c = camara(giro = 0.0, inclinacion = 0.0)
        fun lamina(id: String, y: Double) = Lamina3D(
            id, listOf(Pt3(-100.0, y, -100.0), Pt3(100.0, y, -100.0)),
            Pt3(0.0, 0.0, 1.0), 200.0, "#1e1e1e", 4.0
        )
        // La cámara mira hacia `+y`: la de `y` menor está delante.
        val croquis = Croquis(laminas = listOf(lamina("lejos", 300.0), lamina("cerca", -300.0)))
        val impacto = dondeCae(croquis, c.rayo(Pt(ancho / 2, alto / 2), ancho, alto), c)
        assertEquals("cerca", impacto.lamina)
    }

    // ---- El trazo ----

    /** La regla se queda con las dos puntas y tira lo de en medio. */
    @Test
    fun `la regla endereza`() {
        val curvo = listOf(Pt3(0.0, 0.0, 0.0), Pt3(5.0, 9.0, 0.0), Pt3(10.0, 0.0, 0.0))
        assertEquals(listOf(curvo.first(), curvo.last()), enderezado(curvo))
    }

    /** Y el aligerado tira los puntos pegados pero **nunca la última punta**. */
    @Test
    fun `el aligerado conserva el final`() {
        val muchos = (0..100).map { Pt3(it * 0.1, 0.0, 0.0) }
        val pocos = aligerado(muchos, 1.0)
        assertTrue("${pocos.size}", pocos.size < muchos.size)
        assertEquals(muchos.first(), pocos.first())
        assertEquals(muchos.last(), pocos.last())
    }

    /**
     * **Una curva se queda con los puntos que la hacen curva.**
     *
     * Con el aligerado por distancia, una curva cerrada perdía la misma proporción de puntos
     * que una recta y salía como una tira de rectas cosidas. Ahora lo que decide es cuánto
     * se movería la línea sin ese punto: la recta se queda en dos y la curva conserva forma.
     */
    @Test
    fun `el aligerado tira la recta entera y conserva la curva`() {
        val recta = (0..200).map { Pt3(it * 0.5, 0.0, 0.0) }
        assertEquals("una recta son dos puntos", 2, aligerado(recta, 0.1).size)

        // Media vuelta de radio 50: cien puntos repartidos por el arco.
        val arco = (0..100).map {
            val t = Math.PI * it / 100
            Pt3(50 * kotlin.math.cos(t), 50 * kotlin.math.sin(t), 0.0)
        }
        val quedan = aligerado(arco, 0.1)
        assertTrue("la curva se quedó en ${quedan.size} puntos", quedan.size > 30)
        // Y ninguno de los tirados se aparta de la línea más de lo que se dijo.
        for (p in arco) {
            val cerca = quedan.zipWithNext().minOf { (a, b) -> distanciaAlTramo(p, a, b) }
            assertTrue("un punto se apartó $cerca", cerca <= 0.1 + 1e-6)
        }
    }

    /** Y aligerar es idempotente: lo ya aligerado no pierde nada más. */
    @Test
    fun `aligerar dos veces no quita mas`() {
        val garabato = (0..300).map {
            val t = it * 0.05
            Pt3(t * 10, kotlin.math.sin(t * 3) * 8, kotlin.math.cos(t * 2) * 4)
        }
        val una = aligerado(garabato, 0.2)
        assertEquals(una.size, aligerado(una, 0.2).size)
    }

    /** Un trazo de dos puntos no se toca: ya es lo mínimo. */
    @Test
    fun `un trazo minimo se queda como esta`() {
        val dos = listOf(Pt3(0.0, 0.0, 0.0), Pt3(1.0, 0.0, 0.0))
        assertEquals(dos, aligerado(dos, 100.0))
    }

    // ---- La lámina ----

    /** Barre hacia los dos lados: el trazo queda en su mitad, donde uno lo puso. */
    @Test
    fun `la lamina es simetrica respecto del trazo`() {
        val l = Lamina3D(
            "l", listOf(Pt3(0.0, 0.0, 0.0), Pt3(10.0, 0.0, 0.0)),
            Pt3(0.0, 1.0, 0.0), 50.0, "#1e1e1e", 4.0
        )
        val ys = l.esquinas().map { it.y }
        assertEquals(-50.0, ys.min(), tol)
        assertEquals(50.0, ys.max(), tol)
    }

    /** Un perfil de un solo punto no es una lámina: no hay nada que barrer. */
    @Test
    fun `un perfil de un punto no da tiras`() {
        val l = Lamina3D("l", listOf(Pt3(0.0, 0.0, 0.0)), Pt3(0.0, 1.0, 0.0), 50.0, "#1e1e1e", 4.0)
        assertTrue(l.tiras().isEmpty())
        assertTrue(l.contorno().isEmpty())
    }

    /** Una lámina de tres tramos son tres tiras. */
    @Test
    fun `cada tramo del perfil es una tira`() {
        val l = Lamina3D(
            "l",
            listOf(Pt3(0.0, 0.0, 0.0), Pt3(10.0, 0.0, 0.0), Pt3(20.0, 5.0, 0.0), Pt3(30.0, 0.0, 0.0)),
            Pt3(0.0, 1.0, 0.0), 50.0, "#1e1e1e", 4.0
        )
        assertEquals(3, l.tiras().size)
        assertTrue(l.tiras().all { it.size == 4 })
    }

    // ---- Un plano, y solo sobre el plano ----

    private fun controlador(): Croquis3DControlador =
        Croquis3DControlador().apply { medida(ancho, alto) }

    /** Gira un cuarto de vuelta, que es lo que hace falta para ver el plano de cara. */
    private fun Croquis3DControlador.girarUnCuarto() =
        navegar(VUELTA_ENTERA / 4.0, 0.0, 1.0, ancho / 2, alto / 2, Gesto.GIRAR)

    /**
     * Una lámina **con forma**, y por tanto con bordes: se traza en arco.
     *
     * Hace falta donde lo que se comprueba son los bordes de la hoja. Una raya recta ya no
     * da una hoja sino el plano entero, y un plano no tiene de dónde salirse.
     */
    private fun Croquis3DControlador.laminaConForma(desde: Pt, hasta: Pt) {
        herramienta = Herramienta3D.LAMINA
        tocar(desde, Fase.BAJA)
        for (i in 1..8) {
            val t = i / 8.0
            tocar(
                Pt(
                    desde.x + (hasta.x - desde.x) * t,
                    desde.y + (hasta.y - desde.y) * t - kotlin.math.sin(t * Math.PI) * 120.0
                ),
                Fase.MUEVE
            )
        }
        tocar(hasta, Fase.LEVANTA)
    }

    /** Traza algo de punta a punta con la herramienta que esté puesta. */
    private fun Croquis3DControlador.trazar(desde: Pt, hasta: Pt) {
        tocar(desde, Fase.BAJA)
        for (i in 1..8) {
            val t = i / 8.0
            tocar(Pt(desde.x + (hasta.x - desde.x) * t, desde.y + (hasta.y - desde.y) * t), Fase.MUEVE)
        }
        tocar(hasta, Fase.LEVANTA)
    }

    /**
     * **Un plano a la vez.** El segundo sustituye al primero en vez de sumarse: es una
     * mesa de dibujo que se recoloca, no una pila de capas donde haya que elegir.
     */
    @Test
    fun `el plano nuevo sustituye al anterior`() {
        val c = controlador()
        c.herramienta = Herramienta3D.LAMINA
        c.trazar(Pt(200.0, 900.0), Pt(800.0, 900.0))
        assertEquals(1, c.croquis.laminas.size)
        val primero = c.croquis.laminas.first().id

        c.trazar(Pt(200.0, 1200.0), Pt(800.0, 1400.0))
        assertEquals(1, c.croquis.laminas.size)
        assertTrue(c.croquis.laminas.first().id != primero)
    }

    /**
     * **Sin plano, el lápiz no deja nada.** Un trazo suelto en el plano de la cámara se ve
     * perfecto desde donde se hizo y se descoloca en cuanto uno gira: mejor que no exista.
     */
    @Test
    fun `sin plano el lapiz no dibuja`() {
        val c = controlador()
        c.trazar(Pt(200.0, 900.0), Pt(800.0, 1100.0))
        assertTrue(c.croquis.trazos.isEmpty())
        assertNull(c.apoyo)
    }

    /**
     * **Y con plano, el lápiz cae en él aunque el dedo se salga del borde.**
     *
     * Se dibuja a un lado de donde está la lámina a propósito: prolongada, la superficie
     * sigue mandando. Es lo que impide que medio dibujo se quede a otra profundidad.
     *
     * Hay que girar antes, y no es un apaño de la prueba: recién trazado, el plano está de
     * canto —nace barrido hacia donde uno mira— y de canto no se puede dibujar en él.
     */
    @Test
    fun `todo lo dibujado se apoya en el plano`() {
        val c = controlador()
        c.herramienta = Herramienta3D.LAMINA
        c.trazar(Pt(300.0, 1000.0), Pt(700.0, 1000.0))
        val lamina = c.croquis.laminas.single()
        c.girarUnCuarto()
        val normal = normalDeTira(lamina.tiras().first())
        val enElPlano = lamina.perfil.first()

        c.herramienta = Herramienta3D.LAPIZ
        c.trazar(Pt(100.0, 300.0), Pt(900.0, 1800.0))
        val puntos = c.croquis.trazos.single().puntos
        assertTrue(puntos.size >= 2)
        for (p in puntos) {
            assertEquals(0.0, escalar(menos(p, enElPlano), normal), 1e-6)
        }
    }

    /** Quitada la hoja, se vuelve a estar sin ella: el lápiz calla otra vez. */
    @Test
    fun `quitar el plano deja el lapiz sin apoyo`() {
        val c = controlador()
        c.herramienta = Herramienta3D.LAMINA
        c.trazar(Pt(300.0, 1000.0), Pt(700.0, 1000.0))
        assertTrue(c.hayPlano)

        c.girarUnCuarto()
        c.quitarLaHoja()
        assertTrue(!c.hayPlano)
        assertNull(c.base)

        c.herramienta = Herramienta3D.LAPIZ
        c.trazar(Pt(200.0, 900.0), Pt(800.0, 1100.0))
        assertTrue(c.croquis.trazos.isEmpty())
    }

    /**
     * **Y el borrador no se lleva la hoja por delante.**
     *
     * Se la llevaba en cuanto una pasada no acertaba una raya, y es el peor sitio para un
     * descuido: la hoja está debajo de todo y ocupa la pantalla entera, así que borrar dos
     * trazos seguidos dejaba el croquis sin mesa de dibujo sin que nadie lo hubiera pedido.
     */
    @Test
    fun `el borrador no se lleva la hoja`() {
        val c = conUnTrazo()
        c.herramienta = Herramienta3D.BORRADOR
        // El de raya entera, que es de lo que va esta prueba. El fino tiene la suya.
        c.borradorFino = false

        // Una pasada por un hueco, donde solo está la hoja: no se lleva nada.
        c.tocar(Pt(250.0, 1500.0), Fase.BAJA)
        assertTrue(c.hayPlano)
        assertEquals(1, c.croquis.trazos.size)

        // Y por encima del trazo se lleva el trazo, y la hoja se queda.
        c.tocar(c.dondeSeVeElTrazo(), Fase.BAJA)
        assertTrue(c.croquis.trazos.isEmpty())
        assertTrue(c.hayPlano)
    }

    /**
     * **El borrador fino se lleva lo que toca y deja lo demás.**
     *
     * Es lo que hace un borrador de verdad: se pasa por la esquina que sobra y desaparece esa
     * esquina, no la línea entera. Aquí se traza una raya larga, se borra por el medio y se
     * comprueba lo único que importa: que **queda trazo a los dos lados** y que lo que había
     * debajo del borrador ya no está.
     */
    @Test
    fun `el borrador fino parte la raya y deja los lados`() {
        val c = conUnTrazo()
        val cuantosTenia = c.croquis.trazos.single().puntos.size
        assertTrue("hace falta una raya con puntos de sobra", cuantosTenia >= 5)

        c.herramienta = Herramienta3D.BORRADOR
        c.borradorFino = true
        // Justo por el medio de la raya, que es donde se ve.
        c.tocar(c.dondeSeVeElTrazo(), Fase.BAJA)
        c.tocar(c.dondeSeVeElTrazo(), Fase.LEVANTA)

        // Queda raya —no se la ha llevado entera— y le falta lo que había debajo.
        assertTrue("se ha llevado la raya entera", c.croquis.trazos.isNotEmpty())
        assertTrue(
            "el borrador no ha quitado nada",
            c.croquis.trazos.sumOf { it.puntos.size } < cuantosTenia
        )
        // Y la hoja sigue donde estaba: el borrador es de trazos.
        assertTrue(c.hayPlano)
    }

    // ---- La hoja recorta ----

    /**
     * **Fuera de la hoja no se dibuja: el trazo se queda en el canto.**
     *
     * Se traza a propósito muchísimo más largo que la hoja y cruzándola entera. Antes esto
     * prolongaba el plano hasta el infinito y el trazo salía flotando al lado de la hoja;
     * ahora todo lo que se guarda cabe dentro de ella.
     */
    @Test
    fun `el trazo no se sale de la hoja`() {
        val c = controlador()
        c.herramienta = Herramienta3D.LAMINA
        c.trazar(Pt(400.0, 1000.0), Pt(600.0, 1000.0))
        val hoja = c.croquis.laminas.single()
        c.girarUnCuarto()

        c.herramienta = Herramienta3D.LAPIZ
        c.trazar(Pt(-4000.0, -6000.0), Pt(5000.0, 8000.0))
        val puntos = c.croquis.trazos.single().puntos

        // El cajón de la hoja, con un pelo de holgura por las cuentas en coma flotante.
        val esquinas = hoja.esquinas()
        val holgura = 1e-6
        for (p in puntos) {
            assertTrue(p.x >= esquinas.minOf { it.x } - holgura && p.x <= esquinas.maxOf { it.x } + holgura)
            assertTrue(p.y >= esquinas.minOf { it.y } - holgura && p.y <= esquinas.maxOf { it.y } + holgura)
            assertTrue(p.z >= esquinas.minOf { it.z } - holgura && p.z <= esquinas.maxOf { it.z } + holgura)
        }
    }

    /**
     * **Paseando por fuera, el trazo se queda pegado al canto de la hoja.**
     *
     * Por fuera **a lo ancho**, que es por donde la hoja se acaba: a lo hondo no se sale uno
     * porque por ahí no hay final. Lo que se comprueba es que nada de lo dibujado se va más
     * allá del ancho que puso el trazo, por lejos que se lleve el dedo.
     */
    @Test
    fun `pasear por fuera no saca el trazo de la hoja`() {
        val c = controlador()
        c.herramienta = Herramienta3D.LAMINA
        c.trazar(Pt(480.0, 1000.0), Pt(520.0, 1000.0))
        c.girarUnCuarto()

        val hoja = c.croquis.laminas.single()
        val aLoAncho = normalizado(menos(hoja.perfil.last(), hoja.perfil.first()))
        val medio = largo(menos(hoja.perfil.last(), hoja.perfil.first())) / 2
        val enMedio = por(mas(hoja.perfil.first(), hoja.perfil.last()), 0.5)

        c.herramienta = Herramienta3D.LAPIZ
        c.trazar(Pt(500.0, 1000.0), Pt(500.0, 9000.0))

        val puntos = c.croquis.trazos.singleOrNull()?.puntos ?: return
        for (p in puntos) {
            val cuanto = abs(escalar(menos(p, enMedio), aLoAncho))
            assertTrue("se ha salido de la hoja por $cuanto", cuanto <= medio + 1e-6)
        }
    }

    // ---- Navegar ----


    /** Y con la mano puesta no se dibuja ni con lápiz: la mano es para mover la vista. */
    @Test
    fun `la mano no dibuja`() {
        val c = controlador()
        c.herramienta = Herramienta3D.LAMINA
        c.trazar(Pt(300.0, 1000.0), Pt(700.0, 1000.0))
        c.girarUnCuarto()

        c.herramienta = Herramienta3D.MANO
        c.trazar(Pt(350.0, 900.0), Pt(650.0, 1100.0))
        assertTrue(c.croquis.trazos.isEmpty())
    }

    /** Tres dedos abren la lente y ladean la cámara, sin mover nada de sitio. */
    @Test
    fun `tres dedos tocan la camara y no la escena`() {
        val c = controlador()
        c.herramienta = Herramienta3D.LAMINA
        c.trazar(Pt(300.0, 1000.0), Pt(700.0, 1000.0))
        val antes = c.croquis

        c.navegar(120.0, -260.0, 1.0, 500.0, 1000.0, Gesto.LENTE)
        assertTrue(c.camara.lente > 0.0)
        assertTrue(c.camara.balanceo != 0.0)
        assertEquals(antes, c.croquis)

        c.quitarLaLente()
        assertEquals(0.0, c.camara.lente, 0.0)
        assertEquals(0.0, c.camara.balanceo, 0.0)
    }

    // ---- La lente ----

    /**
     * **La lente proyecta y desproyecta por el mismo sitio.**
     *
     * Es lo único que de verdad importa de una óptica en una aplicación de dibujar: el
     * punto de la hoja que hay bajo la punta del lápiz tiene que ser el que se ve bajo la
     * punta del lápiz. Con una fórmula aproximada, dibujar con el gran angular iría corrido
     * hacia los bordes —y los bordes son justo donde se nota una lente.
     */
    @Test
    fun `con lente el rayo y la pantalla son el mismo sitio`() {
        for (lente in listOf(0.0, 0.15, 0.5, 1.0)) {
            val c = camara(giro = 0.7, inclinacion = 0.4, zoom = 1.6).copy(lente = lente)
            for (p in listOf(
                Pt(ancho / 2, alto / 2), Pt(80.0, 200.0), Pt(920.0, 1850.0), Pt(500.0, 120.0)
            )) {
                val rayo = c.rayo(p, ancho, alto)
                val enElMundo = mas(rayo.origen, por(rayo.direccion, 300.0))
                val vuelta = c.aPantalla(enElMundo, ancho, alto)
                assertEquals("lente $lente en $p", p.x, vuelta.x, 1e-6)
                assertEquals("lente $lente en $p", p.y, vuelta.y, 1e-6)
            }
        }
    }

    /** Y en el plano del centro la escala no cambia al abrirla: la vista no se aleja sola. */
    @Test
    fun `abrir la lente no cambia el tamano de lo que hay en el centro`() {
        val recta = camara(giro = 0.0, inclinacion = 0.0, zoom = 2.0)
        val punto = Pt3(30.0, 0.0, 0.0)
        val conOrto = recta.aPantalla(punto, ancho, alto)
        for (lente in listOf(0.2, 0.6, 1.0)) {
            val conLente = recta.copy(lente = lente).aPantalla(punto, ancho, alto)
            // A treinta unidades del centro el error de un ojo de pez es real, pero pequeño:
            // lo que no puede es cambiar de escala de golpe al tocar la lente.
            assertEquals(conOrto.x, conLente.x, 4.0)
            assertEquals(conOrto.y, conLente.y, 4.0)
        }
    }

    /**
     * Lo que queda a la espalda sale por los bordes y no se da la vuelta.
     *
     * Es la razón de usar el mapeo equidistante y no el del agujero de alfiler: con la
     * tangente, un punto detrás de la cámara aparece **invertido** dentro de la pantalla,
     * que es de los errores más desconcertantes que puede tener una vista.
     */
    @Test
    fun `lo de detras no se da la vuelta`() {
        val c = camara(giro = 0.0, inclinacion = 0.0, zoom = 1.0).copy(lente = 1.0)
        // Muy detrás del ojo, y desviado hacia la derecha.
        val detras = mas(por(c.adelante, -20000.0), por(c.derecha, 50.0))
        val donde = c.aPantalla(detras, ancho, alto)
        assertTrue("tenía que salir por la derecha", donde.x > ancho / 2)
        assertTrue("y muy lejos del centro", donde.x - ancho / 2 > alto / 4)
    }

    /**
     * **La lente rectilínea es un agujero de alfiler**: un punto a θ de la mirada cae a
     * `f·tan θ` del centro, con `f` sacada de que el borde de la pantalla sea el borde del
     * campo. Es la de la cámara del teléfono, y por eso la usa el croquis puesto en el
     * sitio. Ver [Camara3D.rectilinea].
     */
    @Test
    fun `la lente rectilinea proyecta con la tangente`() {
        val campo = Math.toRadians(70.0)
        val c = camara(zoom = 1.0).copy(lente = campo / Camara3D.CAMPO_MAXIMO, rectilinea = true)
        val ojo = c.ojo(alto)!!
        val f = (alto / 2) / kotlin.math.tan(campo / 2)
        for (grados in listOf(10.0, 25.0, 34.0)) {
            val angulo = Math.toRadians(grados)
            // Un punto a ese ángulo de la mirada, hacia arriba, a cualquier distancia.
            val p = mas(ojo, mas(por(c.adelante, 500.0 * kotlin.math.cos(angulo)),
                por(c.arriba, 500.0 * kotlin.math.sin(angulo))))
            val q = c.aPantalla(p, ancho, alto)
            assertEquals("a $grados grados", ancho / 2, q.x, 1e-6)
            assertEquals("a $grados grados", alto / 2 - f * kotlin.math.tan(angulo), q.y, 1e-6)
        }
        // Y el borde del campo cae justo en el borde de la pantalla.
        val borde = mas(ojo, mas(por(c.adelante, 500.0 * kotlin.math.cos(campo / 2)),
            por(c.arriba, 500.0 * kotlin.math.sin(campo / 2))))
        assertEquals(0.0, c.aPantalla(borde, ancho, alto).y, 1e-6)
    }

    /** Con la rectilínea, el rayo y la pantalla siguen siendo el mismo sitio. */
    @Test
    fun `con lente rectilinea el rayo y la pantalla son el mismo sitio`() {
        val c = camara(giro = 0.7, inclinacion = 0.4, zoom = 1.6).copy(lente = 0.45, rectilinea = true)
        for (p in listOf(
            Pt(ancho / 2, alto / 2), Pt(80.0, 200.0), Pt(920.0, 1850.0), Pt(500.0, 120.0)
        )) {
            val rayo = c.rayo(p, ancho, alto)
            val enElMundo = mas(rayo.origen, por(rayo.direccion, 300.0))
            val vuelta = c.aPantalla(enElMundo, ancho, alto)
            assertEquals("en $p", p.x, vuelta.x, 1e-6)
            assertEquals("en $p", p.y, vuelta.y, 1e-6)
        }
    }

    /** Tampoco con la rectilínea lo de la espalda se da la vuelta: se queda en el borde. */
    @Test
    fun `con lente rectilinea lo de detras no se da la vuelta`() {
        val c = camara(zoom = 1.0).copy(lente = 0.45, rectilinea = true)
        val detras = mas(por(c.adelante, -20000.0), por(c.derecha, 50.0))
        val donde = c.aPantalla(detras, ancho, alto)
        assertTrue("tenía que salir por la derecha", donde.x > ancho)
        assertTrue(donde.x.isFinite() && donde.y.isFinite())
    }

    /** La base congelada da exactamente lo mismo también con la rectilínea. */
    @Test
    fun `la base congelada calca la rectilinea`() {
        val c = camara(giro = 0.3, inclinacion = 0.2, zoom = 1.3).copy(lente = 0.4, rectilinea = true)
        val base = c.base(ancho, alto)
        for (p in listOf(Pt3(10.0, 20.0, 5.0), Pt3(-300.0, 40.0, 900.0), Pt3(0.0, -5000.0, 0.0))) {
            val a = c.aPantalla(p, ancho, alto)
            val b = base.aPantalla(p)
            assertEquals(a.x, b.x, 0.0)
            assertEquals(a.y, b.y, 0.0)
        }
    }

    /** Ladear la cámara mueve la pantalla, no el dibujo. */
    @Test
    fun `el balanceo ladea la vista`() {
        val recta = camara(giro = 0.0, inclinacion = 0.0)
        val punto = Pt3(50.0, 0.0, 0.0)
        val sinLadear = recta.aPantalla(punto, ancho, alto)
        val ladeada = recta.copy(balanceo = Math.PI / 2).aPantalla(punto, ancho, alto)
        // Un cuarto de vuelta manda lo que estaba a la derecha a estar abajo.
        assertEquals(ancho / 2, ladeada.x, 1e-6)
        assertEquals(alto / 2 + (sinLadear.x - ancho / 2), ladeada.y, 1e-6)
    }

    // ---- La bolita de seleccionar ----

    private fun conUnTrazo(): Croquis3DControlador {
        val c = controlador()
        c.herramienta = Herramienta3D.LAMINA
        c.trazar(Pt(200.0, 1000.0), Pt(800.0, 1000.0))
        c.girarUnCuarto()
        c.herramienta = Herramienta3D.LAPIZ
        c.trazar(Pt(400.0, 900.0), Pt(600.0, 1100.0))
        return c
    }

    /** Dónde se ve, en la pantalla, el punto de en medio del primer trazo. */
    private fun Croquis3DControlador.dondeSeVeElTrazo(): Pt {
        val puntos = croquis.trazos.first().puntos
        return camara.aPantalla(puntos[puntos.size / 2], ancho, alto)
    }

    /**
     * **La bolita enciende lo que toca y lo apaga si vuelve a tocarlo.**
     *
     * Un pase la coge, otro la suelta. Es lo que deja quitar uno que sobra sin cambiar de
     * modo ni empezar la selección de cero.
     */
    @Test
    fun `la bolita enciende y apaga`() {
        val c = conUnTrazo()
        val id = c.croquis.trazos.single().id
        val encima = c.dondeSeVeElTrazo()

        c.herramienta = Herramienta3D.SELECCION
        c.tocar(encima, Fase.BAJA)
        c.tocar(encima, Fase.LEVANTA)
        assertEquals(setOf(id), c.seleccion)

        c.tocar(encima, Fase.BAJA)
        c.tocar(encima, Fase.LEVANTA)
        assertTrue(c.seleccion.isEmpty())
    }

    /** Y dentro de un mismo pase no parpadea: cada trazo cambia una vez y se queda. */
    @Test
    fun `un pase de la bolita no parpadea`() {
        val c = conUnTrazo()
        val id = c.croquis.trazos.single().id
        val encima = c.dondeSeVeElTrazo()

        c.herramienta = Herramienta3D.SELECCION
        c.tocar(encima, Fase.BAJA)
        for (i in 1..30) c.tocar(Pt(encima.x + (i % 3), encima.y), Fase.MUEVE)
        c.tocar(encima, Fase.LEVANTA)
        assertEquals(setOf(id), c.seleccion)
    }

    // ---- El mando ----

    private fun Croquis3DControlador.elegirElTrazo() {
        herramienta = Herramienta3D.SELECCION
        val encima = dondeSeVeElTrazo()
        tocar(encima, Fase.BAJA)
        tocar(encima, Fase.LEVANTA)
    }

    /** Mover lleva lo elegido **por el plano de la hoja**, no por el de la pantalla. */
    @Test
    fun `mover no saca lo elegido del plano`() {
        val c = conUnTrazo()
        val hoja = c.croquis.laminas.single()
        val normal = normalDeTira(hoja.tiras().first())
        val antes = c.croquis.trazos.single().puntos
        c.elegirElTrazo()

        c.empezarAManejar()
        repeat(10) { c.moverLaSeleccion(6.0, -3.0) }

        val despues = c.croquis.trazos.single().puntos
        assertTrue(largo(menos(despues.first(), antes.first())) > 1.0)
        for (i in antes.indices) {
            assertEquals(
                escalar(menos(antes[i], hoja.perfil.first()), normal),
                escalar(menos(despues[i], hoja.perfil.first()), normal),
                1e-6
            )
        }
    }

    /** Girar no cambia el tamaño de nada: solo la postura. */
    @Test
    fun `girar conserva las distancias`() {
        val c = conUnTrazo()
        val antes = c.croquis.trazos.single().puntos
        c.elegirElTrazo()
        c.empezarAManejar()
        c.girarLaSeleccion(Math.PI / 3)

        val despues = c.croquis.trazos.single().puntos
        assertEquals(
            largo(menos(antes.last(), antes.first())),
            largo(menos(despues.last(), despues.first())),
            1e-6
        )
        assertTrue(largo(menos(despues.first(), antes.first())) > 1e-6)
    }

    /** Escalar multiplica lo que mide, y por el factor exacto. */
    @Test
    fun `escalar multiplica el tamano`() {
        val c = conUnTrazo()
        val antes = c.croquis.trazos.single().puntos
        c.elegirElTrazo()
        c.empezarAManejar()
        c.escalarLaSeleccion(2.0)

        val despues = c.croquis.trazos.single().puntos
        assertEquals(
            largo(menos(antes.last(), antes.first())) * 2,
            largo(menos(despues.last(), despues.first())),
            1e-6
        )
    }

    /** Copiar deja **la copia** elegida: copiar y colocar son un solo gesto seguido. */
    @Test
    fun `copiar deja elegida la copia`() {
        val c = conUnTrazo()
        val original = c.croquis.trazos.single().id
        c.elegirElTrazo()
        c.copiarLaSeleccion()

        assertEquals(2, c.croquis.trazos.size)
        assertEquals(1, c.seleccion.size)
        assertTrue(original !in c.seleccion)
    }

    /** Y todo el manejo cabe en un solo paso del historial. */
    @Test
    fun `manejar cabe en un solo deshacer`() {
        val c = conUnTrazo()
        val antes = c.croquis.trazos.single().puntos
        c.elegirElTrazo()

        c.empezarAManejar()
        repeat(20) { c.moverLaSeleccion(4.0, 4.0) }
        c.deshacer()

        assertEquals(antes, c.croquis.trazos.single().puntos)
    }

    /** Y borrar lo elegido se lo lleva, sin tocar la hoja. */
    @Test
    fun `borrar lo elegido no toca la hoja`() {
        val c = conUnTrazo()
        c.elegirElTrazo()
        c.borrarLaSeleccion()

        assertTrue(c.croquis.trazos.isEmpty())
        assertEquals(1, c.croquis.laminas.size)
        assertTrue(c.seleccion.isEmpty())
    }

    // ---- La hoja curva ----

    /**
     * **Una hoja curva la corta el rayo dos veces, y el trazo tiene que quedarse en la
     * cara por la que iba.**
     *
     * Es el fallo que se veía como «a la línea se le pasa un tramo»: sin nada que diga por
     * dónde iba el trazo, se cogía siempre el corte más cercano al ojo, y en el punto donde
     * la hoja se dobla hacia el otro lado el corte bueno pasaba a ser el otro de un
     * fotograma al siguiente. Un tramo entero se iba a la cara de atrás y cruzaba la hoja
     * por dentro.
     */
    @Test
    fun `en una hoja curva el trazo no se pasa a la cara de atras`() {
        // Un perfil en «C» tumbado: va hacia la derecha y vuelve. Mirando de frente, un
        // rayo lo atraviesa por dos sitios.
        val hoja = Lamina3D(
            "c",
            listOf(Pt3(0.0, 0.0, 0.0), Pt3(40.0, -30.0, 0.0), Pt3(0.0, -60.0, 0.0)),
            Pt3(0.0, 0.0, 1.0), 60.0, "#1e1e1e", 4.0
        )
        // La cámara mira hacia `+y`, así que de los dos cortes —en `y = −15` y en
        // `y = −45`— el que se ve es el de `−45`.
        val c = camara(giro = 0.0, inclinacion = 0.0)
        val enPantalla = c.aPantalla(Pt3(20.0, -15.0, 0.0), ancho, alto)

        // Sin guía manda lo que se ve: el corte de delante.
        val deFrente = dondeCaeEnElPlano(hoja, c, enPantalla, ancho, alto)!!
        assertEquals(-45.0, deFrente.punto.y, 1e-6)

        // Y con el trazo viniendo por la otra cara, el corte bueno es el otro. Esto es el
        // arreglo: sin ello, el trazo saltaba aquí a la cara de delante.
        val porLaOtraCara = dondeCaeEnElPlano(
            hoja, c, enPantalla, ancho, alto, cerca = Pt3(18.0, -16.0, 0.0)
        )!!
        assertEquals(-15.0, porLaOtraCara.punto.y, 1e-6)
    }

    /** Y de punta a punta, ningún salto: el trazo sale continuo. */
    @Test
    fun `sobre una hoja curva el trazo sale continuo`() {
        val c = controlador()
        c.herramienta = Herramienta3D.LAMINA
        // Una hoja bien curvada, trazada a pulso.
        c.tocar(Pt(250.0, 1100.0), Fase.BAJA)
        for (i in 1..24) {
            val t = i / 24.0
            c.tocar(Pt(250.0 + 500.0 * t, 1100.0 - 260.0 * kotlin.math.sin(Math.PI * t)), Fase.MUEVE)
        }
        c.tocar(Pt(750.0, 1100.0), Fase.LEVANTA)
        assertEquals(1, c.croquis.laminas.size)
        c.girarUnCuarto()

        c.herramienta = Herramienta3D.LAPIZ
        c.trazar(Pt(300.0, 800.0), Pt(700.0, 1300.0))
        val puntos = c.croquis.trazos.singleOrNull()?.puntos ?: return

        // Ningún tramo puede medir varias veces lo que mide el vecino: eso es un salto.
        val tramos = puntos.zipWithNext { a, b -> largo(menos(b, a)) }.filter { it > 1e-9 }
        if (tramos.size > 2) {
            val mediana = tramos.sorted()[tramos.size / 2]
            assertTrue(
                "hay un salto: ${tramos.max()} contra una mediana de $mediana",
                tramos.max() < mediana * 8
            )
        }
    }

    // ---- Las puntas ----

    /** La punta se guarda con el trazo y se le puede cambiar a lo elegido. */
    @Test
    fun `la punta se queda en el trazo`() {
        val c = conUnTrazo()
        assertEquals(Pincel.REDONDO, c.croquis.trazos.single().pincel)

        c.pincel = Pincel.PLANO
        c.herramienta = Herramienta3D.LAPIZ
        c.trazar(Pt(420.0, 950.0), Pt(580.0, 1050.0))
        assertEquals(Pincel.PLANO, c.croquis.trazos.last().pincel)

        c.elegirElTrazo()
        c.empincelarLaSeleccion(Pincel.CUADRADO)
        assertEquals(Pincel.CUADRADO, c.croquis.trazos.first().pincel)
    }

    // ---- Pararse endereza la raya ----

    /**
     * **Se para el dedo y el trazo se pone recto**, y sigue recto mientras se estira.
     *
     * Es el gesto que hace que en el espacio salga una arista de verdad: a pulso, sobre
     * una pantalla y girando la vista, una raya recta no sale ni queriendo. Se comprueba
     * con la hora puesta a mano y no esperando medio segundo de reloj.
     */
    @Test
    fun `parar el dedo endereza el trazo`() {
        val c = controlador()
        c.herramienta = Herramienta3D.LAMINA
        c.trazar(Pt(200.0, 1000.0), Pt(800.0, 1000.0))
        c.girarUnCuarto()
        c.herramienta = Herramienta3D.LAPIZ

        var reloj = 1_000L
        c.tocar(Pt(460.0, 960.0), Fase.BAJA, reloj)
        // **Una raya a pulso**: ocho puntos en línea con el temblor de una mano.
        //
        // Era una parábola de las de verdad —un doce por ciento de flecha— y eso ya no es
        // el pulso de nadie, es un arco que alguien ha querido hacer. Desde que una curva
        // trazada a propósito se respeta (ver [yaEsUnaCurva]), la prueba tiene que trazar
        // lo que dice su cabecera: la raya recta que a pulso no sale.
        //
        // **Y cae dentro de la hoja.** El trazo de antes empezaba fuera de ella y el
        // controlador pega al borde lo que se sale, así que lo que quedaba grabado era un
        // gancho por mucho que el dedo fuera recto — y con razón se tomaba por curva.
        for (i in 1..8) {
            reloj += 16
            val temblor = if (i == 8) 0.0 else if (i % 2 == 0) 4.0 else -4.0
            c.tocar(Pt(460.0 + i * 10.0, 960.0 + i * 11.25 + temblor), Fase.MUEVE, reloj)
        }
        assertTrue("todavía no toca enderezar", !c.enderezandoSolo)
        assertTrue("una curva a pulso trae sus puntos", c.trazoEnCurso.size > 5)

        // Y ahora el dedo se para en el mismo sitio más de medio segundo.
        val quieto = Pt(540.0, 1050.0)
        for (i in 1..40) {
            reloj += 16
            c.tocar(quieto, Fase.MUEVE, reloj)
        }
        assertTrue("parado, la raya se endereza", c.enderezandoSolo)
        assertEquals("y el trazo pasa a ser dos puntos", 2, c.trazoEnCurso.size)

        // Estirando desde ahí, sigue siendo una raya y la punta va con el dedo.
        reloj += 16
        c.tocar(Pt(900.0, 1500.0), Fase.MUEVE, reloj)
        assertEquals(2, c.trazoEnCurso.size)

        reloj += 16
        c.tocar(Pt(900.0, 1500.0), Fase.LEVANTA, reloj)
        assertEquals("y lo guardado son dos puntas", 2, c.croquis.trazos.single().puntos.size)
        assertTrue("y el gesto no se queda pegado al siguiente trazo", !c.enderezandoSolo)
    }

    /** Sin pararse, el trazo se queda como se dibujó. */
    @Test
    fun `sin pararse el trazo no se endereza`() {
        val c = controlador()
        c.herramienta = Herramienta3D.LAMINA
        c.trazar(Pt(200.0, 1000.0), Pt(800.0, 1000.0))
        c.girarUnCuarto()
        c.herramienta = Herramienta3D.LAPIZ

        var reloj = 1_000L
        c.tocar(Pt(300.0, 800.0), Fase.BAJA, reloj)
        for (i in 1..20) {
            reloj += 16
            c.tocar(Pt(300.0 + i * 25.0, 800.0 + i * i * 3.0), Fase.MUEVE, reloj)
        }
        reloj += 16
        c.tocar(Pt(800.0, 2000.0), Fase.LEVANTA, reloj)
        assertTrue("no se endereza", !c.enderezandoSolo)
        assertTrue(
            "y la curva conserva sus puntos",
            c.croquis.trazos.single().puntos.size > 2
        )
    }

    /** Y un croquis guardado antes de que hubiera puntas se abre con la redonda. */
    @Test
    fun `un croquis viejo se abre con la punta redonda`() {
        val viejo = """{"trazos":[{"id":"t","puntos":[{"x":0.0,"y":0.0,"z":0.0},""" +
            """{"x":1.0,"y":0.0,"z":0.0}],"color":"#1e1e1e","grosor":4.0}],"laminas":[]}"""
        val croquis = kotlinx.serialization.json.Json.decodeFromString<Croquis>(viejo)
        assertEquals(Pincel.REDONDO, croquis.trazos.single().pincel)
    }

    // ---- La altura, aparte del plano ----

    /**
     * **Sin eje se coloca dentro de la hoja; con la `z` se despega.**
     *
     * Son dos cosas distintas y por eso hay que pedir la segunda. Mezcladas, cada intento
     * de colocar algo lo levantaba un poco de la hoja sin querer y el croquis se descuadraba
     * solo.
     */
    @Test
    fun `sin eje se coloca en el plano y con la z se despega`() {
        val c = conUnTrazo()
        val hoja = c.croquis.laminas.single()
        val normal = normalDeTira(hoja.tiras().first())
        fun altura() = escalar(
            menos(c.croquis.trazos.single().puntos.first(), hoja.perfil.first()), normal
        )
        val antes = altura()
        c.elegirElTrazo()

        c.empezarAManejar()
        c.moverLaSeleccionEnEje(EjeDelMundo.LIBRE, 30.0, 20.0)
        assertEquals(antes, altura(), 1e-6)

        c.moverLaSeleccionEnEje(EjeDelMundo.Z, 0.0, -60.0)
        assertTrue("tenía que haberse despegado", abs(altura() - antes) > 1.0)
    }

    /**
     * **Por un eje se va solo por ese eje, y lo que se arrastra es lo que anda.**
     *
     * Las dos mitades del trato: la figura no se sale del eje ni un pelo, y lo que recorre
     * en la pantalla es lo que ha recorrido el dedo en la dirección del eje. Sin la segunda,
     * el mando volvería a ser adivinar.
     */
    @Test
    fun `un eje mueve solo por ese eje y lo que se arrastra`() {
        val c = conUnTrazo()
        c.elegirElTrazo()
        val antes = c.croquis.trazos.single().puntos.first()
        val centro = c.centroDeLaSeleccion()!!
        val enPantalla = c.enPantallaDelEje(centro, EjeDelMundo.X.direccion!!)!!

        c.empezarAManejar()
        // Se arrastra justo en la dirección en la que se ve el eje, cien píxeles.
        val largoDelEje = hypot(enPantalla.x, enPantalla.y)
        c.empezarAManejar()
        c.moverLaSeleccionEnEje(
            EjeDelMundo.X, enPantalla.x / largoDelEje * 100, enPantalla.y / largoDelEje * 100
        )

        val despues = c.croquis.trazos.single().puntos.first()
        // Ni en `y` ni en `z`: solo por la `x`.
        assertEquals(antes.y, despues.y, 1e-9)
        assertEquals(antes.z, despues.z, 1e-9)
        // Y cien píxeles de pantalla, ni más ni menos.
        assertEquals(100.0, abs(despues.x - antes.x) * largoDelEje, 1e-6)
    }

    /** Arrastrando en cruz al eje no se mueve nada: un eje es un eje. */
    @Test
    fun `en cruz al eje no se mueve`() {
        val c = conUnTrazo()
        c.elegirElTrazo()
        val antes = c.croquis.trazos.single().puntos.first()
        val centro = c.centroDeLaSeleccion()!!
        val v = c.enPantallaDelEje(centro, EjeDelMundo.X.direccion!!)!!
        val largoDelEje = hypot(v.x, v.y)

        c.empezarAManejar()
        c.moverLaSeleccionEnEje(EjeDelMundo.X, -v.y / largoDelEje * 200, v.x / largoDelEje * 200)

        val despues = c.croquis.trazos.single().puntos.first()
        assertEquals(antes.x, despues.x, 1e-9)
    }

    /** Y girar alrededor de un eje del mundo gira alrededor de ese eje. */
    @Test
    fun `girar por un eje respeta el eje`() {
        val c = conUnTrazo()
        c.elegirElTrazo()
        val antes = c.croquis.trazos.single().puntos.first()
        val centro = c.centroDeLaSeleccion()!!

        c.empezarAManejar()
        c.girarLaSeleccion(Math.PI / 2, EjeDelMundo.Z)

        val despues = c.croquis.trazos.single().puntos.first()
        // Girando alrededor de la vertical, la altura no cambia y la distancia al eje sí se
        // conserva: es lo que define ese giro y no otro.
        assertEquals(antes.z, despues.z, 1e-9)
        assertEquals(
            hypot(antes.x - centro.x, antes.y - centro.y),
            hypot(despues.x - centro.x, despues.y - centro.y),
            1e-9
        )
    }

    /**
     * **Pasado el borde, el trazo no se cruza a la otra cara.**
     *
     * Es la raya que salía de vez en cuando. Una hoja recién trazada se ve de canto: sus dos
     * caras —la de delante del barrido y la de detrás— **se proyectan una encima de otra**,
     * así que el canto de la de atrás se ve tan cerca del dedo como el de la de delante. Con
     * el dedo fuera de la hoja, cualquier temblor decidía a cuál de las dos se pegaba el
     * trazo, y saltar de una a otra son cuatrocientas unidades de un fotograma al siguiente:
     * una raya cruzando el croquis entero.
     *
     * Se prueba con el dedo dando la vuelta completa por fuera, que es lo que uno hace sin
     * darse cuenta al pasarse del borde.
     */
    @Test
    fun `pasarse del borde no cruza a la otra cara`() {
        val c = camara(giro = 0.35, inclinacion = 0.25)
        val fondo = 200.0
        val hoja = Lamina3D(
            "h",
            listOf(Pt3(-100.0, 0.0, 0.0), Pt3(0.0, 0.0, 30.0), Pt3(100.0, 0.0, 0.0)),
            c.adelante, fondo, "#1e1e1e", 4.0
        )
        // El trazo venía por la cara de delante del barrido, la del `menos`.
        val cerca = menos(hoja.perfil.last(), por(normalizado(hoja.direccion), fondo))
        val enElBorde = c.aPantalla(cerca, ancho, alto)

        for (angulo in 0 until 24) {
            val a = angulo * Math.PI / 12
            val fuera = Pt(
                enElBorde.x + 900 * kotlin.math.cos(a),
                enElBorde.y + 900 * kotlin.math.sin(a)
            )
            val donde = dondeCaeEnElPlano(hoja, c, fuera, ancho, alto, cerca) ?: continue
            assertTrue(
                "se cruzó a la otra cara desde el ángulo $angulo",
                largo(menos(donde.punto, cerca)) < fondo
            )
        }
    }

    /**
     * **En una hoja curva vista de perfil, el trazo no salta al canto.**
     *
     * Justo en el punto donde la curva se dobla, la superficie es casi paralela al rayo y
     * este se cuela entre dos tiras sin cortar ninguna: falla **en medio de la hoja**.
     * Buscando el sitio más cercano solo por el contorno, el resultado era el canto de
     * arriba o el de abajo, y el lápiz dejaba una raya recta hasta el borde. Buscando por
     * toda la hoja, lo más cercano es el propio pliegue.
     */
    @Test
    fun `en el pliegue el trazo no salta al canto`() {
        // Una hoja doblada en «V» y mirada de perfil: el pliegue queda en el medio.
        val hoja = Lamina3D(
            "v",
            listOf(Pt3(-80.0, 0.0, 60.0), Pt3(0.0, 0.0, 0.0), Pt3(80.0, 0.0, 60.0)),
            Pt3(0.0, 1.0, 0.0), 150.0, "#1e1e1e", 4.0
        )
        val c = camara(giro = 0.0, inclinacion = 0.0)
        val pliegue = Pt3(0.0, 0.0, 0.0)

        // Justo en el pliegue, y un pelo por debajo: por ahí ya no hay hoja.
        for (bajo in listOf(0.0, 2.0, 6.0, 14.0)) {
            val donde = c.aPantalla(Pt3(0.0, 0.0, -bajo), ancho, alto)
            val cae = dondeCaeEnElPlano(hoja, c, donde, ancho, alto, cerca = pliegue)!!
            assertTrue(
                "se fue al canto por $bajo: ${cae.punto}",
                largo(menos(cae.punto, pliegue)) < 40.0
            )
        }
    }

    // ---- El plano y la lente ----

    /**
     * **Con lente abierta, la hoja sigue pasando por su trazo.**
     *
     * Nacer de canto ya no puede: una hoja que no se acaba a lo hondo no se esconde detrás
     * de la raya que la creó, se barra como se barra. Lo que sí tiene que seguir siendo
     * verdad —y es lo que de verdad importa— es que **la hoja pase por donde se trazó**, o
     * lo que se dibuje después caería en otro sitio.
     */
    @Test
    fun `con lente la hoja pasa por su trazo`() {
        val c = controlador()
        c.navegar(0.0, -400.0, 1.0, ancho / 2, alto / 2, Gesto.LENTE)
        assertTrue("hacía falta lente abierta", c.camara.lente > 0.5)

        c.herramienta = Herramienta3D.LAMINA
        c.trazar(Pt(60.0, 1400.0), Pt(940.0, 700.0))
        // El perfil se queda en el plano de la pantalla, que es donde se trazó. Con la
        // lente abierta no sale recto —las visuales van en abanico, así que una raya de la
        // pantalla se curva al caer—, pero plano sí: no se despega de donde se dibujó.
        val hoja = c.croquis.laminas.single()
        for (p in hoja.perfil) {
            assertEquals(0.0, escalar(menos(p, hoja.perfil.first()), c.camara.adelante), 1e-6)
        }
    }

    /** Y no se guarda ninguna visual: una sola dirección de barrido vale. */
    @Test
    fun `sin lente el plano se guarda con una sola direccion`() {
        val c = controlador()
        c.herramienta = Herramienta3D.LAMINA
        c.trazar(Pt(200.0, 1000.0), Pt(800.0, 1000.0))
        assertNull(c.croquis.laminas.single().direcciones)
    }

    // ---- Quitar la hoja ----

    /**
     * La hoja se quita **desde la barra**, con el plano puesto, y deja lo dibujado: ya vive
     * en el espacio.
     *
     * El botón vivía pegado a la esquina de la propia hoja, y una esquina que se va de la
     * pantalla en cuanto uno gira es un botón al que hay que perseguir. Ahora está siempre
     * en el mismo sitio, debajo, que es donde uno ya mira para salir de las cosas.
     */
    @Test
    fun `quitar la hoja no se lleva lo dibujado`() {
        val c = conUnTrazo()
        assertTrue(c.hayPlano)

        c.quitarLaHoja()
        assertTrue(!c.hayPlano)
        assertEquals(1, c.croquis.trazos.size)

        c.deshacer()
        assertTrue(c.hayPlano)
    }

    // ---- Encajar la vista ----

    /**
     * **El doble toque pone recta la vista que ya se tenía casi recta.**
     *
     * La de fábrica mira desde una esquina, un poco por encima y girada a la izquierda:
     * de todas las caras, la que más de frente se tiene es el alzado. Y encajar la deja
     * exacta, que es de lo que se trata —a un pelo del alzado, las paralelas dejan de ser
     * paralelas y lo que se mide sobre el dibujo no vale—.
     */
    @Test
    fun `el doble toque encaja en la vista mas cercana`() {
        val c = controlador()
        c.encajarEnLaVistaMasCercana()
        assertNotNull("hay viaje que andar", c.viaje)

        c.andarElViaje(1f)
        assertEquals(0.0, c.camara.giro, tol)
        assertEquals(0.0, c.camara.inclinacion, tol)
        assertNull("y el viaje se acaba", c.viaje)
    }

    /** Mirando casi desde arriba, la vista más cercana es la planta. */
    @Test
    fun `desde arriba se encaja en la planta`() {
        val c = controlador()
        c.orbitar(0.0, 1.3, 1.0)
        c.encajarEnLaVistaMasCercana()
        c.andarElViaje(1f)
        assertEquals(Camara3D.TOPE_DE_INCLINACION, c.camara.inclinacion, tol)
    }

    /**
     * **Y se va por el lado corto de la vuelta.**
     *
     * Girando un rato, el giro de la cámara se va acumulando y acaba valiendo cinco o seis
     * radianes. Restando a lo bruto, encajar en una vista de ángulo pequeño da la vuelta
     * entera por el lado largo: la escena pega un volantazo para acabar donde estaba.
     */
    @Test
    fun `la vista encaja por el camino corto`() {
        val c = controlador()
        // Se gira casi una vuelta entera y se endereza la inclinación.
        c.orbitar(6.0, -Math.PI / 7, 1.0)
        c.encajarEnLaVistaMasCercana()

        val viaje = c.viaje!!
        assertTrue(
            "da el rodeo: de ${viaje.desde.giro} a ${viaje.hasta.giro}",
            abs(viaje.hasta.giro - viaje.desde.giro) <= Math.PI
        )

        c.andarElViaje(1f)
        // Y acaba mirando el perfil derecho de verdad, aunque el número no sea -π/2.
        assertEquals(1.0, escalar(c.camara.adelante, Pt3(-1.0, 0.0, 0.0)), 1e-9)
    }

    /** A media transición, la vista va a medio camino y el balanceo ya se está yendo. */
    @Test
    fun `a medio viaje la vista va a medio camino`() {
        val c = controlador()
        // Ladeada con tres dedos: encajar tiene que enderezarla también.
        c.navegar(200.0, 0.0, 1.0, ancho / 2, alto / 2, Gesto.LENTE)
        val ladeada = c.camara.balanceo
        assertTrue("hacía falta balanceo", abs(ladeada) > 0.1)

        val salida = c.camara.giro
        c.encajarEnLaVistaMasCercana()
        val destino = c.viaje!!.hasta.giro

        c.andarElViaje(0.5f)
        assertEquals((salida + destino) / 2, c.camara.giro, tol)
        assertEquals(ladeada / 2, c.camara.balanceo, tol)
        assertNotNull("y a medias el viaje sigue", c.viaje)

        c.andarElViaje(1f)
        assertEquals("una vista técnica no va ladeada", 0.0, c.camara.balanceo, tol)
    }

    /** Tocar la vista mientras se encaja corta el viaje: manda la mano. */
    @Test
    fun `girar con el dedo corta el viaje`() {
        val c = controlador()
        c.encajarEnLaVistaMasCercana()
        c.andarElViaje(0.3f)
        c.girarUnCuarto()
        assertNull(c.viaje)

        // Y lo que quede de animación ya no mueve nada.
        val donde = c.camara
        c.andarElViaje(1f)
        assertEquals(donde, c.camara)
    }

    // ---- Mantener pulsado, sin mover nada ----

    /**
     * **El dedo clavado endereza la raya, sin que llegue ni un movimiento.**
     *
     * Era el agujero del gesto: pararse a enderezar se medía dentro del movimiento del
     * dedo, y un dedo de verdad quieto no manda movimientos —Android no tiene nada que
     * contar—. Así que el contador no corría y la raya solo se enderezaba si la mano
     * temblaba lo justo. Ahora la pantalla le trae la hora fotograma a fotograma.
     */
    @Test
    fun `mantener pulsado endereza aunque no llegue ni un movimiento`() {
        val c = controlador()
        c.herramienta = Herramienta3D.LAMINA
        c.trazar(Pt(200.0, 1000.0), Pt(800.0, 1000.0))
        c.girarUnCuarto()
        c.herramienta = Herramienta3D.LAPIZ

        var reloj = 1_000L
        c.tocar(Pt(460.0, 960.0), Fase.BAJA, reloj)
        // Una raya a pulso, como en la prueba de arriba: un arco de verdad ya no se endereza.
        for (i in 1..8) {
            reloj += 16
            val temblor = if (i == 8) 0.0 else if (i % 2 == 0) 4.0 else -4.0
            c.tocar(Pt(460.0 + i * 10.0, 960.0 + i * 11.25 + temblor), Fase.MUEVE, reloj)
        }
        val arranque = c.trazoEnCurso.first()
        val punta = c.trazoEnCurso.last()

        // Y ahora el dedo se queda clavado: no llega ni un movimiento más, solo la hora.
        repeat(40) {
            reloj += 16
            c.latido(reloj)
        }
        assertTrue("clavado, la raya se endereza", c.enderezandoSolo)
        assertEquals("y el trazo pasa a ser dos puntos", 2, c.trazoEnCurso.size)
        assertEquals("saliendo de donde salía", arranque, c.trazoEnCurso.first())
        assertEquals("y acabando donde está el dedo", punta, c.trazoEnCurso.last())
    }

    // ---- El trazo es un sólido: mide lo que mide en el mundo ----

    /**
     * **Lo gordo que es un trazo se guarda en el mundo, no en la pantalla.**
     *
     * Es lo que hace que acercarse enseñe algo: el trazo está ahí, con su grueso, y se ve
     * más gordo de cerca como cualquier otra cosa. Midiéndolo en la pantalla, ampliar hacía
     * crecer el dibujo y dejaba las líneas igual de finas: el croquis se quedaba de alambre.
     */
    @Test
    fun `el trazo se guarda con lo que mide en el mundo`() {
        val c = conUnTrazo()
        val primero = c.croquis.trazos.single()
        assertEquals("a aumento uno, el calibre es el grosor", primero.grosor, primero.calibre!!, tol)

        // **Y al doble de aumento, lo mismo.** El número de la barra dice cuánto mide la raya
        // en el croquis, no cuánta pantalla ocupa mientras se traza: dividiéndolo por el
        // aumento, con el mismo número puesto una raya hecha de cerca salía mucho más fina
        // que una hecha de lejos, y al alejarse para ver las dos juntas no se parecían.
        c.orbitar(0.0, 0.0, 2.0)
        assertEquals(2.0, c.camara.zoom, tol)
        c.herramienta = Herramienta3D.LAPIZ
        c.trazar(Pt(430.0, 930.0), Pt(560.0, 1060.0))
        val cercano = c.croquis.trazos.last()
        assertEquals("el mismo número da la misma raya", c.grosor, cercano.calibre!!, tol)
        assertEquals("y el número de la barra no cambia", c.grosor, cercano.grosor, tol)
        assertEquals("las dos miden lo mismo", primero.calibre!!, cercano.calibre!!, tol)
    }

    /** Y engrosar lo elegido lo vuelve a medir **en el mundo**, mire uno desde donde mire. */
    @Test
    fun `engrosar lo elegido lo vuelve a medir`() {
        val c = conUnTrazo()
        c.orbitar(0.0, 0.0, 4.0)
        c.elegirElTrazo()
        c.engrosarLaSeleccion(14.0)
        val trazo = c.croquis.trazos.single()
        assertEquals(14.0, trazo.grosor, tol)
        assertEquals(14.0, trazo.calibre!!, tol)
    }

    /** Un croquis de antes del calibre se abre sin él: se pinta como se dibujó. */
    @Test
    fun `un trazo viejo no trae medida del mundo`() {
        val c = controlador()
        c.cargar(
            Croquis(
                trazos = listOf(
                    Trazo3D("t", listOf(Pt3(0.0, 0.0, 0.0), Pt3(10.0, 0.0, 0.0)), "#1e1e1e", 6.0)
                )
            )
        )
        assertNull(c.croquis.trazos.single().calibre)
    }

    // ---- Tres tintas, y el pulso de la mano ----

    /** Las puntas que se ofrecen, y las dos retiradas, que se abren como la redonda. */
    @Test
    fun `las tintas que hay`() {
        // Cuatro **secciones**: la redonda, el listón, la cuchilla y el rodillo. La luz ya
        // no está aquí: no era una punta, era un material. Ver [MaterialDeLaTinta].
        assertEquals(4, Pincel.LAS_QUE_HAY.size)
        assertFalse(Pincel.LUZ in Pincel.LAS_QUE_HAY)
        assertTrue(Pincel.CUCHILLA in Pincel.LAS_QUE_HAY)
        assertEquals(Pincel.CUCHILLA, Pincel.CUCHILLA.vigente)
        assertEquals(Pincel.REDONDO, Pincel.PLANO.vigente)
        assertEquals(Pincel.REDONDO, Pincel.LAPIZ.vigente)
        assertEquals(Pincel.RESALTADOR, Pincel.RESALTADOR.vigente)

        // Y un croquis guardado con una punta retirada se abre con la redonda puesta.
        val c = controlador()
        c.cargar(
            Croquis(
                trazos = listOf(
                    Trazo3D(
                        "t", listOf(Pt3(0.0, 0.0, 0.0), Pt3(10.0, 0.0, 0.0)),
                        "#1e1e1e", 6.0, Pincel.PLANO
                    )
                )
            )
        )
        assertEquals(Pincel.REDONDO, c.croquis.trazos.single().pincel)
    }

    /**
     * **El resaltador tiene el ancho que tiene.**
     *
     * **Hace caso al selector, y sale mucho más ancho que el número.** Un rotulador de
     * subrayar es ancho —a los grosores de dibujar no subraya nada, solo pinta una raya de
     * color por encima— pero el ancho se elige: en el espacio la misma tinta sirve para
     * marcar una zona pequeña y para darle una mano de color a una cara entera.
     */
    @Test
    fun `el resaltador multiplica el selector`() {
        val c = conUnTrazo()
        c.pincel = Pincel.RESALTADOR
        c.grosor = 2.0
        c.trazar(Pt(420.0, 950.0), Pt(580.0, 1050.0))
        val marcado = c.croquis.trazos.last()
        val esperado = 2.0 * Croquis3DControlador.ENGORDE_DEL_RESALTADOR
        assertEquals(esperado, marcado.grosor, tol)
        assertEquals(esperado, marcado.calibre!!, tol)

        // Y subiendo el selector sube con él: eso es lo que faltaba.
        c.grosor = 20.0
        c.trazar(Pt(420.0, 1150.0), Pt(580.0, 1250.0))
        assertEquals(
            20.0 * Croquis3DControlador.ENGORDE_DEL_RESALTADOR,
            c.croquis.trazos.last().grosor,
            tol
        )
    }

    /**
     * El pulso se guarda **punto por punto**, y aligerar el trazo se lleva los dos a la vez.
     *
     * Es lo único que puede romperse aquí de verdad: si las dos listas se descolocan, el
     * grueso del trazo deja de corresponderse con lo que hizo la mano.
     */
    @Test
    fun `el pulso se guarda con cada punto`() {
        val c = conUnTrazo()
        c.herramienta = Herramienta3D.LAPIZ

        var reloj = 1_000L
        c.tocar(Pt(400.0, 900.0), Fase.BAJA, reloj, presion = 0.2)
        for (i in 1..12) {
            reloj += 16
            c.tocar(Pt(400.0 + i * 14.0, 900.0 + i * 9.0), Fase.MUEVE, reloj, presion = i / 12.0)
        }
        reloj += 16
        c.tocar(Pt(570.0, 1010.0), Fase.LEVANTA, reloj, presion = 1.0)

        val trazo = c.croquis.trazos.last()
        assertEquals(
            "hay un pulso por punto",
            trazo.puntos.size, trazo.presiones!!.size
        )
        assertTrue("y va de menos a más", trazo.presiones!!.last() > trazo.presiones!!.first())
    }

    /**
     * **El rodillo se queda tumbado en la hoja.**
     *
     * Guarda la normal de la superficie sobre la que se dio la pasada, que es lo que deja
     * pintarlo escorzado en vez de siempre de cara al que mira. Sin ella, la banda gira con
     * la cámara y parece que se despega del dibujo.
     */
    @Test
    fun `el rodillo se queda tumbado en la hoja`() {
        val c = conUnTrazo()
        val trazo = c.croquis.trazos.single()
        val normal = trazo.normal!!
        assertEquals("es una dirección, no un vector cualquiera", 1.0, largo(normal), 1e-9)
        // El trazo está tumbado en la hoja, así que no se sale de ella por ningún lado.
        for ((a, b) in trazo.puntos.zipWithNext()) {
            assertEquals(0.0, escalar(normal, menos(b, a)), 1e-6)
        }
    }

    // ---- Esconder ----

    /**
     * **Lo escondido sigue ahí, pero no se ve ni se toca.**
     *
     * Es lo que hace falta para dibujar dentro de algo: se aparta lo que tapa, se trabaja en
     * lo de debajo y se vuelve a enseñar. Y se suelta al esconderlo, porque un mando
     * encendido moviendo algo invisible es la mejor manera de perder un trazo.
     */
    @Test
    fun `ocultar lo elegido lo esconde sin borrarlo`() {
        val c = conUnTrazo()
        c.elegirElTrazo()
        c.ocultarLaSeleccion()

        assertEquals("sigue en el croquis", 1, c.croquis.trazos.size)
        assertTrue(c.croquis.trazos.single().oculto)
        assertTrue("y suelto", c.seleccion.isEmpty())
        assertTrue(c.croquis.hayOcultos)
        // No cuenta para encuadrar: lo que no se ve no se mira.
        assertEquals(c.croquis.laminas.single().esquinas().size, c.croquis.puntos().size)

        c.enseñarLoOculto()
        assertTrue(!c.croquis.trazos.single().oculto)
        assertTrue(!c.croquis.hayOcultos)
    }

    /** Y el borrador no se lleva lo que no se ve: no se puede borrar lo que no se apunta. */
    @Test
    fun `lo escondido no se borra ni se elige`() {
        val c = conUnTrazo()
        val encima = c.dondeSeVeElTrazo()
        c.elegirElTrazo()
        c.ocultarLaSeleccion()

        c.herramienta = Herramienta3D.BORRADOR
        c.tocar(encima, Fase.BAJA)
        assertEquals(1, c.croquis.trazos.size)

        c.herramienta = Herramienta3D.SELECCION
        c.tocar(encima, Fase.BAJA)
        c.tocar(encima, Fase.LEVANTA)
        assertTrue(c.seleccion.isEmpty())
    }

    /** Cambiar de selección pone a cero la cuenta de lo volteado: era de la otra figura. */
    @Test
    fun `elegir otra cosa olvida lo volteado`() {
        val c = conUnTrazo()
        c.elegirElTrazo()
        c.girarLaSeleccionConLaMano(120.0, 0.0)
        assertTrue(abs(c.vueltaDeLoElegido.giro) > 0.1)

        c.soltarLaSeleccion()
        assertEquals(0.0, c.vueltaDeLoElegido.giro, tol)
        assertEquals(0.0, c.vueltaDeLoElegido.inclinacion, tol)
    }

    // ---- La rueda, y el plano grande ----

    /**
     * **Se clava el dedo y sale una rueda**, y desde ahí se abre tirando.
     *
     * Es el compás: se para el dedo en un sitio sin haber dibujado nada, ahí queda el centro
     * y el radio lo dice hasta dónde se arrastre. Antes había que dar la vuelta entera a
     * pulso y esperar a que el programa reconociera un lazo —o sea, hacer justo lo que uno
     * quería no hacer, y encima sin saber si iba a salir—.
     */
    @Test
    fun `clavar el dedo y tirar saca una rueda`() {
        val c = controlador()
        c.herramienta = Herramienta3D.LAMINA
        c.trazar(Pt(200.0, 1000.0), Pt(800.0, 1000.0))
        c.girarUnCuarto()
        c.herramienta = Herramienta3D.LAPIZ

        var reloj = 1_000L
        val centro = Pt(500.0, 1000.0)
        c.tocar(centro, Fase.BAJA, reloj)
        // Un pelo de temblor, que es lo que hace una mano de verdad apoyada.
        reloj += 16
        c.tocar(Pt(centro.x + 1, centro.y), Fase.MUEVE, reloj)
        assertTrue("todavía no toca", !c.redondeandoSolo)

        repeat(40) {
            reloj += 16
            c.latido(reloj)
        }
        assertTrue("clavado en un punto, sale una rueda", c.redondeandoSolo)
        assertTrue("y no una recta", !c.enderezandoSolo)

        // Y se abre tirando: el radio lo dice el dedo.
        reloj += 16
        c.tocar(Pt(centro.x + 200, centro.y), Fase.MUEVE, reloj)
        val rueda = c.trazoEnCurso
        assertTrue("con puntos de sobra para verse redonda", rueda.size > 30)

        // Es un círculo de verdad: todos sus puntos a la misma distancia del centro.
        // El último punto repite el primero —así se cierra—, y contarlo dos veces
        // descoloca el centro: se deja fuera para medir.
        val vuelta = rueda.dropLast(1)
        val medio = vuelta.fold(Pt3(0.0, 0.0, 0.0)) { a, b -> mas(a, b) }.let {
            Pt3(it.x / vuelta.size, it.y / vuelta.size, it.z / vuelta.size)
        }
        val radios = vuelta.map { largo(menos(it, medio)) }
        assertEquals(radios.max(), radios.min(), radios.max() * 0.01)

        // Y es plana: toda ella se apoya en la hoja.
        val normal = normalDe(c.croquis.laminas.single())
        assertEquals(0.0, escalar(menos(rueda[0], rueda[10]), normal), 1e-6)

        reloj += 16
        c.tocar(Pt(centro.x + 200, centro.y), Fase.LEVANTA, reloj)
        val guardada = c.croquis.trazos.single()
        assertTrue(guardada.puntos.size > 30)
        assertNull("una figura de compás no lleva pulso", guardada.presiones)
        assertTrue("y el gesto no se queda pegado", !c.redondeandoSolo)
    }

    /** Habiendo dibujado un recorrido, pararse endereza: eso no ha cambiado. */
    @Test
    fun `parar el dedo despues de trazar sigue enderezando`() {
        val c = controlador()
        c.herramienta = Herramienta3D.LAMINA
        c.trazar(Pt(200.0, 1000.0), Pt(800.0, 1000.0))
        c.girarUnCuarto()
        c.herramienta = Herramienta3D.LAPIZ

        var reloj = 1_000L
        c.tocar(Pt(300.0, 1000.0), Fase.BAJA, reloj)
        for (i in 1..10) {
            reloj += 16
            c.tocar(Pt(300.0 + i * 20, 1000.0 + i * 7), Fase.MUEVE, reloj)
        }
        repeat(40) {
            reloj += 16
            c.latido(reloj)
        }
        assertTrue("lo trazado se endereza", c.enderezandoSolo)
        assertTrue("y no sale una rueda", !c.redondeandoSolo)
    }

    // ---- La bola y la hoja que une dos trazos ----

    /**
     * **La bola: se clava el centro y se abre**, y luego se dibuja encima.
     *
     * Lo que hay que comprobar no es que exista la esfera: es que **el lápiz se pega a
     * ella**. Una superficie sobre la que no se puede dibujar no es una hoja, es un adorno.
     */
    @Test
    fun `la bola es una hoja y el lapiz se le pega`() {
        val c = controlador()
        c.herramienta = Herramienta3D.ESFERA
        c.trazar(Pt(500.0, 1000.0), Pt(700.0, 1000.0))

        val bola = c.croquis.laminas.single().esfera
        assertNotNull("se ha puesto una bola", bola)
        assertTrue("y con radio", bola!!.radio > 0)

        c.herramienta = Herramienta3D.LAPIZ
        c.trazar(Pt(480.0, 980.0), Pt(520.0, 1010.0))
        val trazo = c.croquis.trazos.single()
        // Todo lo dibujado está **sobre** la bola, a un radio de su centro.
        for (p in trazo.puntos) {
            assertEquals(bola.radio, largo(menos(p, bola.centro)), bola.radio * 0.05)
        }
    }

    /**
     * **El cilindro, el cono y el anillo son la bola con otra forma**: mismo gesto, y el
     * lápiz se les pega igual. En el cilindro, todo lo dibujado está a un radio del eje.
     */
    @Test
    fun `el cilindro es una hoja y el lapiz se le pega`() {
        val c = controlador()
        c.herramienta = Herramienta3D.ESFERA
        c.formaDeBola = FormaDeBola.CILINDRO
        c.trazar(Pt(500.0, 1000.0), Pt(700.0, 1000.0))

        val cuerpo = c.croquis.laminas.single().esfera
        assertNotNull(cuerpo)
        assertEquals(FormaDeBola.CILINDRO, cuerpo!!.forma)

        c.herramienta = Herramienta3D.LAPIZ
        c.trazar(Pt(480.0, 980.0), Pt(520.0, 1010.0))
        val trazo = c.croquis.trazos.single()
        for (p in trazo.puntos) {
            val d = menos(p, cuerpo.centro)
            val alEje = kotlin.math.hypot(escalar(d, cuerpo.unos), escalar(d, cuerpo.otros))
            assertEquals("a un radio del eje", cuerpo.radio, alEje, cuerpo.radio * 0.06)
        }
    }

    /** Los cuatro cuerpos caben en su caja y se recorren enteros con los dos ángulos. */
    @Test
    fun `los cuatro cuerpos caben en la caja unidad`() {
        for (forma in FormaDeBola.entries) {
            val e = Esfera3D(Pt3(0.0, 0.0, 0.0), 10.0, forma = forma)
            for (tira in e.malla()) for (p in tira) {
                assertTrue("$forma se sale: $p", abs(p.x) <= 10.0 + 1e-9 && abs(p.y) <= 10.0 + 1e-9 && abs(p.z) <= 10.0 + 1e-9)
            }
            assertEquals(Esfera3D.MERIDIANOS * Esfera3D.PARALELOS, e.malla().size)
        }
        // El cono acaba en punta arriba y el anillo tiene agujero.
        val cono = Esfera3D(Pt3(0.0, 0.0, 0.0), 10.0, forma = FormaDeBola.CONO)
        assertEquals(0.0, kotlin.math.hypot(cono.punto(1.0, Math.PI / 2).x, cono.punto(1.0, Math.PI / 2).y), 1e-9)
        val anillo = Esfera3D(Pt3(0.0, 0.0, 0.0), 10.0, forma = FormaDeBola.ANILLO)
        val interior = anillo.punto(0.0, 0.0) // theta = π: el lado de dentro del tubo
        assertTrue(kotlin.math.hypot(interior.x, interior.y) > 3.0)
    }

    /** Dos trazos elegidos se unen con una hoja reglada, y la hoja va de uno a otro. */
    @Test
    fun `unir dos trazos deja una hoja entre ellos`() {
        val c = conDosTrazos()
        assertTrue("hacen falta dos elegidos", !c.sePuedenUnir)
        c.herramienta = Herramienta3D.SELECCION
        for (t in c.croquis.trazos) {
            val encima = c.camara.aPantalla(t.puntos[t.puntos.size / 2], ancho, alto)
            c.tocar(encima, Fase.BAJA)
            c.tocar(encima, Fase.LEVANTA)
        }
        assertTrue(c.sePuedenUnir)

        assertTrue(c.unirLosTrazos())
        val hoja = c.croquis.laminas.single()
        assertNotNull("es una hoja de dos rieles", hoja.otroPerfil)
        assertEquals(hoja.perfil.size, hoja.otroPerfil!!.size)
        assertTrue("y tiene tiras para apoyar el lápiz", hoja.tiras().isNotEmpty())

        // Sus dos bordes son los dos trazos: un lado empieza en uno y el otro en el otro.
        val unos = c.croquis.trazos.map { it.puntos.first() }
        assertTrue(unos.any { largo(menos(it, hoja.unLado(0))) < 1e-6 })
    }

    /** La bola nace elegida, y el mando le cambia el tamaño y la forma. */
    @Test
    fun `la bola nace elegida y se le da forma`() {
        val c = controlador()
        c.herramienta = Herramienta3D.ESFERA
        c.trazar(Pt(500.0, 1000.0), Pt(700.0, 1000.0))

        val hoja = c.croquis.laminas.single()
        assertEquals(Herramienta3D.SELECCION, c.herramienta)
        assertTrue("la bola nace elegida", hoja.id in c.seleccion)

        val antes = hoja.esfera!!.radio
        c.escalarLaSeleccion(2.0)
        assertEquals(antes * 2, c.croquis.laminas.single().esfera!!.radio, antes * 0.01)

        // Y estirada por un lado: deja de ser una bola y pasa a ser un huevo.
        c.deformarLaSeleccion(2.0, 1.0)
        val escala = c.croquis.laminas.single().esfera!!.escala
        assertTrue("se ha estirado por algún eje", largo(escala) > largo(Pt3(1.0, 1.0, 1.0)))
    }

    /**
     * **Estirar no es agrandar**: lo ancho crece y lo alto se queda.
     */
    @Test
    fun `deformar estira solo por donde se tira`() {
        val c = conUnTrazo()
        c.herramienta = Herramienta3D.SELECCION
        val t = c.croquis.trazos.first()
        val encima = c.camara.aPantalla(t.puntos[t.puntos.size / 2], ancho, alto)
        c.tocar(encima, Fase.BAJA)
        c.tocar(encima, Fase.LEVANTA)

        val centro = c.centroDeLaSeleccion()!!
        fun extremos(): Pair<Double, Double> {
            val puntos = c.croquis.trazos.first().puntos
            return puntos.maxOf { kotlin.math.abs(escalar(menos(it, centro), c.camara.derecha)) } to
                puntos.maxOf { kotlin.math.abs(escalar(menos(it, centro), c.camara.arriba)) }
        }
        val (anchoAntes, altoAntes) = extremos()
        c.deformarLaSeleccion(2.0, 1.0)
        val (anchoAhora, altoAhora) = extremos()
        assertEquals(anchoAntes * 2, anchoAhora, anchoAntes * 0.02 + 1e-9)
        assertEquals(altoAntes, altoAhora, altoAntes * 0.02 + 1e-9)
    }

    /**
     * **Un plano es exactamente lo que se trazó.**
     *
     * Se alargaba un poco por las dos puntas siguiendo por donde iba la curva, para que un
     * plumazo corto no diera una mesa de dibujo corta. Pero eso es adivinar el plano de
     * alguien: se traza una raya de un palmo y al levantar el dedo aparece medio palmo más
     * por cada lado que uno no ha dibujado. El plano es la raya; si hace falta más ancho, se
     * traza más ancho.
     */
    @Test
    fun `el plano mide lo que se traza`() {
        val c = controlador()
        c.herramienta = Herramienta3D.LAMINA
        c.trazar(Pt(300.0, 900.0), Pt(500.0, 900.0))

        val hoja = c.croquis.laminas.single()
        val enPantalla = hoja.perfil.map { c.camara.aPantalla(it, ancho, alto) }
        assertEquals("el plano se ha estirado por la izquierda", 300.0, enPantalla.minOf { it.x }, 1.0)
        assertEquals("el plano se ha estirado por la derecha", 500.0, enPantalla.maxOf { it.x }, 1.0)
    }

    // ---- El corte de la punta ----

    /** Si un punto cae dentro de una figura cerrada. El lanzado de rayo de toda la vida. */
    private fun dentro(figura: List<Pt>, p: Pt): Boolean {
        var si = false
        var j = figura.size - 1
        for (i in figura.indices) {
            val a = figura[i]
            val b = figura[j]
            if ((a.y > p.y) != (b.y > p.y) &&
                p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x
            ) si = !si
            j = i
        }
        return si
    }

    /**
     * **Una punta en uve deja el hueco de la uve.**
     *
     * Es el fallo que tenía envolver lo dibujado en su contorno: el contorno de una uve es un
     * triángulo, así que dibujabas una uve y salía un triángulo macizo. Y una punta en uve no
     * es un triángulo — el hueco **es** lo que la hace una punta en uve.
     *
     * La cinta va por un lado del rasgo, da la vuelta a la punta y vuelve por el otro, así
     * que respeta lo que se trazó: el centro de la uve queda dentro del triángulo que la
     * envolvería y fuera de la tinta, que es donde tiene que quedar.
     */
    @Test
    fun `la cinta de una uve deja su hueco`() {
        val uve = listOf(Pt(-0.8, -0.6), Pt(0.0, 0.6), Pt(0.8, -0.6))
        val cinta = cintaDelRasgo(uve, 0.05, 5)
        assertTrue("la cinta se ha cerrado", cinta.size >= 6)

        val enElHueco = Pt(0.0, -0.2)
        assertTrue("el hueco cae dentro del triángulo que la envolvería", dentro(uve, enElHueco))
        assertTrue("la uve se ha rellenado", !dentro(cinta, enElHueco))
        // Y los brazos sí son tinta: la cinta está donde se trazó.
        assertTrue("el brazo no es tinta", dentro(cinta, Pt(-0.4, 0.0)))
    }

    /** Y la cinta de un rasgo recto mide su grueso: es la raya con su ancho, no una línea. */
    @Test
    fun `la cinta de un rasgo lleva su grueso`() {
        val raya = listOf(Pt(-0.5, 0.0), Pt(0.5, 0.0))
        val cinta = cintaDelRasgo(raya, 0.1, 4)
        assertEquals(0.1, cinta.maxOf { abs(it.y) }, 1e-9)
        // Las puntas van cortadas a escuadra medio radio hacia fuera, no en pico.
        assertEquals(0.6, cinta.maxOf { it.x }, 1e-9)
        assertTrue("el centro de la raya es tinta", dentro(cinta, Pt(0.0, 0.0)))
    }

    /**
     * **El sentido de giro del corte, que es de lo que depende que se vea algo.**
     *
     * La cara que hay entre dos puntos del corte sale de un producto vectorial, así que hacia
     * dónde mira lo decide el sentido en que da la vuelta la figura. Y del sólido se pintan
     * **solo las caras que miran hacia aquí**: un lazo del revés tiene todas las suyas
     * mirando hacia dentro y no se pinta ni una — la tinta no sale a trozos, no sale.
     *
     * La cinta que envuelve un rasgo sale con la vuelta al revés por cómo se construye —va
     * por un lado y vuelve por el otro—, así que esto no es una comprobación de adorno: es la
     * que evita que un cambio en `cintaDelRasgo` deje la aplicación pintando en invisible.
     */
    @Test
    fun `el corte da la vuelta siempre en el mismo sentido`() {
        // La del cuadrado del listón, que es la de referencia: viene bien dada.
        assertTrue(
            "la sección de fábrica está del revés",
            areaConSigno(SeccionDePunta.CUADRADO.puntos()) > 0
        )
        // Y la cinta de un rasgo, que sale al contrario y hay que enderezar.
        val cinta = cintaDelRasgo(listOf(Pt(-0.5, 0.0), Pt(0.5, 0.0)), 0.1, 4)
        assertTrue("la cinta ya no sale del revés; sobra enderezarla", areaConSigno(cinta) < 0)
        assertTrue(
            "enderezada, sigue del revés",
            areaConSigno(if (areaConSigno(cinta) < 0) cinta.reversed() else cinta) > 0
        )
    }

    /** El mismo recorrido con otro número de puntos, repartidos por distancia. */
    @Test
    fun `remuestrear reparte los puntos parejos`() {
        val raya = listOf(Pt3(0.0, 0.0, 0.0), Pt3(10.0, 0.0, 0.0))
        val cinco = remuestreado(raya, 5)
        assertEquals(5, cinco.size)
        assertEquals(0.0, cinco.first().x, 1e-9)
        assertEquals(10.0, cinco.last().x, 1e-9)
        assertEquals(5.0, cinco[2].x, 1e-9)
    }

    // ---- Las vistas congeladas ----

    /**
     * **Congelar y volver: la cámara entera, no solo los ángulos.**
     *
     * Es lo que separa volver de aproximarse: una vista es desde dónde se mira, a qué se
     * mira, con cuánto aumento y con qué lente, y todo a la vez. A pulso no se recupera.
     */
    @Test
    fun `una vista congelada se recupera entera`() {
        val c = conUnTrazo()
        c.orbitar(0.8, 0.4, 3.0)
        c.desplazar(120.0, -60.0)
        val laBuena = c.camara

        c.congelarLaVista()
        val vista = c.croquis.vistas.single()
        assertEquals(laBuena, vista.camara)

        // Se sigue croquizando desde otro lado, y se vuelve.
        c.orbitar(-2.5, -0.9, 0.2)
        c.desplazar(-400.0, 300.0)
        assertTrue(c.camara != laBuena)

        c.irALaVista(vista.id)
        c.andarElViaje(1f)
        assertEquals(laBuena.zoom, c.camara.zoom, 1e-9)
        assertEquals(laBuena.inclinacion, c.camara.inclinacion, 1e-9)
        assertEquals(laBuena.centro.x, c.camara.centro.x, 1e-9)
        assertEquals(laBuena.centro.y, c.camara.centro.y, 1e-9)
        assertEquals(laBuena.centro.z, c.camara.centro.z, 1e-9)
        // El giro puede haber dado vueltas de más, pero se mira al mismo sitio.
        assertEquals(1.0, escalar(laBuena.adelante, c.camara.adelante), 1e-9)
    }

    /** El aumento se interpola multiplicando: a mitad de viaje, la media geométrica. */
    @Test
    fun `el aumento del viaje va multiplicando`() {
        val c = conUnTrazo()
        c.congelarLaVista()
        c.orbitar(0.0, 0.0, 4.0)
        assertEquals(4.0, c.camara.zoom, tol)

        c.irALaVista(c.croquis.vistas.single().id)
        c.andarElViaje(0.5f)
        // De cuatro a uno, la mitad del camino es dos y no dos y medio.
        assertEquals(2.0, c.camara.zoom, 1e-9)
    }

    /** Y se tiran de la galería, con su deshacer. */
    @Test
    fun `una vista se tira`() {
        val c = conUnTrazo()
        c.congelarLaVista()
        c.congelarLaVista()
        assertEquals(2, c.croquis.vistas.size)

        c.borrarLaVista(c.croquis.vistas.first().id)
        assertEquals(1, c.croquis.vistas.size)

        c.deshacer()
        assertEquals(2, c.croquis.vistas.size)
    }

    // ---- El taller de la punta ----

    /**
     * **La punta se guarda con el trazo, y se le puede cambiar a lo elegido.**
     *
     * Es lo que convierte tres tintas en las que uno quiera: el listón alargado y ladeado
     * para que las verticales salgan gruesas, el rotulador sin pulso para que una arista
     * salga pareja.
     */
    @Test
    fun `la punta se queda con el trazo`() {
        val c = conUnTrazo()
        assertEquals(PuntaDelPincel(), c.croquis.trazos.single().punta)

        c.punta = PuntaDelPincel(ancho = 1.6, largo = 3.0, angulo = Math.PI / 2, pulso = false)
        c.trazar(Pt(420.0, 950.0), Pt(580.0, 1050.0))
        val alargado = c.croquis.trazos.last().punta
        assertEquals(3.0, alargado.largo, tol)
        assertTrue("alargada es de canto", alargado.deCanto)
        assertTrue(!alargado.pulso)

        // Y a lo ya dibujado se le cambia como el color.
        c.elegirElTrazo()
        c.empezarARetocar()
        c.empuntarLaSeleccion(PuntaDelPincel(largo = 2.0))
        assertEquals(2.0, c.croquis.trazos.first().punta.largo, tol)

        c.deshacer()
        assertEquals(PuntaDelPincel(), c.croquis.trazos.first().punta)
    }

    /**
     * **El perfil dibujado manda sobre lo demás.**
     *
     * Es la forma general de una punta: redonda, listón y plumilla no son tres cosas, son
     * tres secciones. Con una dibujada, el trazo sale de barrerla por el recorrido.
     */
    @Test
    fun `la seccion dibujada se queda con el trazo`() {
        val c = conUnTrazo()
        assertTrue(!c.punta.tienePerfil)

        // Una cuña: cuatro puntos, que ya es una sección.
        val cuña = listOf(Pt(-1.0, -0.2), Pt(1.0, 0.0), Pt(-1.0, 0.2), Pt(-0.8, 0.0))
        c.punta = PuntaDelPincel(perfil = cuña)
        assertTrue(c.punta.tienePerfil)

        c.trazar(Pt(420.0, 950.0), Pt(580.0, 1050.0))
        assertEquals(cuña, c.croquis.trazos.last().punta.perfil)

        // Y quitarla deja la punta de su tinta.
        c.elegirElTrazo()
        c.empuntarLaSeleccion(PuntaDelPincel())
        assertTrue(!c.croquis.trazos.first().punta.tienePerfil)
    }

    /**
     * **Las secciones de fábrica son figuras cerradas y del tamaño de la caja.**
     *
     * Es lo primero que se elige de un pincel: de qué está cortada la punta. Van sin tamaño
     * —lo pone el grosor— así que todas caben en el cuadrado de lado dos y ninguna se queda
     * en una raya.
     */
    @Test
    fun `las secciones de fabrica sirven de punta`() {
        for (seccion in SeccionDePunta.entries) {
            val puntos = seccion.puntos()
            assertTrue("${'$'}seccion no es una figura", puntos.size >= 3)
            assertTrue(
                "${'$'}seccion se sale de su caja",
                puntos.all { abs(it.x) <= 1.0 + 1e-9 && abs(it.y) <= 1.0 + 1e-9 }
            )
            // Y ocupa: una sección aplastada del todo no barre nada.
            val ancho = puntos.maxOf { it.x } - puntos.minOf { it.x }
            val alto = puntos.maxOf { it.y } - puntos.minOf { it.y }
            assertTrue("${'$'}seccion está aplastada", ancho > 0.5 && alto > 0.5)
            assertTrue(PuntaDelPincel(perfil = puntos).tienePerfil)
        }
        // Y cada tinta trae la suya, para poder enseñarla sin haber elegido ninguna.
        assertEquals(SeccionDePunta.CUADRADO, SeccionDePunta.deLaTinta(Pincel.CUADRADO))
        assertEquals(SeccionDePunta.RECTANGULO, SeccionDePunta.deLaTinta(Pincel.RESALTADOR))
        assertEquals(SeccionDePunta.CIRCULO, SeccionDePunta.deLaTinta(Pincel.LAPIZ))
    }

    /** Con menos de tres puntos no hay sección que barrer: eso es una raya, no una punta. */
    @Test
    fun `una seccion de dos puntos no cuenta`() {
        assertTrue(!PuntaDelPincel(perfil = listOf(Pt(0.0, 0.0), Pt(1.0, 0.0))).tienePerfil)
        assertTrue(
            PuntaDelPincel(perfil = listOf(Pt(0.0, 0.0), Pt(1.0, 0.0), Pt(0.0, 1.0))).tienePerfil
        )
    }

    /** Una punta sin alargar no es de canto: el trazo sale igual vaya por donde vaya. */
    @Test
    fun `una punta redonda no es de canto`() {
        assertTrue(!PuntaDelPincel().deCanto)
        assertTrue(!PuntaDelPincel(largo = 1.02).deCanto)
        assertTrue(PuntaDelPincel(largo = 1.5).deCanto)
    }

    /**
     * **Cada vista congelada es una lámina distinta.**
     *
     * Iban todas al mismo dibujo por un fallo de escritura —el nombre del archivo se
     * armaba mal y salía el mismo para todas—, así que cada vista nueva pisaba a la
     * anterior y el proyecto acababa con cinco hojas enseñando la última.
     */
    @Test
    fun `dos vistas congeladas no comparten dibujo`() {
        val c = conUnTrazo()
        c.congelarLaVista()
        c.orbitar(0.7, 0.2, 1.0)
        c.congelarLaVista()

        val vistas = c.croquis.vistas
        assertEquals(2, vistas.size)
        assertTrue("dos vistas, dos identificadores", vistas[0].id != vistas[1].id)
        assertEquals("1", vistas[0].nombre)
        assertEquals("2", vistas[1].nombre)
    }

    // ---- Las dos capas del lienzo ----

    /**
     * **La escena no mira el trazo en curso, y de eso depende que el croquis vaya fluido.**
     *
     * El lienzo pinta en dos capas: lo dibujado por un lado y lo que está en la mano por
     * otro, para que trazar una raya no obligue a volver a montar los quinientos trazos que
     * ya había. Lo único de la mano que tiene que ir con la escena es el rodillo —se pinta
     * unido a su montón—, así que esto comprueba lo que hace que la separación sirva: **con
     * cualquier otra punta no se llega ni a mirar el trazo en curso**.
     *
     * Si alguien hace que esto devuelva algo con el lápiz puesto, no se romperá nada — se
     * volverá lento, que es peor, porque no lo dice ninguna prueba. Por eso lo dice esta.
     */
    @Test
    fun `el rodillo en la mano solo existe con el rodillo puesto`() {
        val c = conUnTrazo()

        // Con el lápiz y media raya trazada, nada: la escena ni se entera de que hay un
        // trazo saliendo, que es de lo que se trata.
        c.pincel = Pincel.REDONDO
        c.tocar(Pt(400.0, 900.0), Fase.BAJA)
        c.tocar(Pt(500.0, 1000.0), Fase.MUEVE)
        c.tocar(Pt(600.0, 1100.0), Fase.MUEVE)
        assertTrue(c.elRodilloEnLaMano().isEmpty())
        c.tocar(Pt(600.0, 1100.0), Fase.LEVANTA)

        // Con el rodillo puesto, uno: el que se está pasando, con su color y su ancho, para
        // que se una a su montón en vez de sumarse con lo de debajo.
        c.pincel = Pincel.RESALTADOR
        c.color = "#ffd43b"
        c.tocar(Pt(400.0, 900.0), Fase.BAJA)
        c.tocar(Pt(500.0, 1000.0), Fase.MUEVE)
        c.tocar(Pt(600.0, 1100.0), Fase.MUEVE)
        val enLaMano = c.elRodilloEnLaMano()
        assertEquals(1, enLaMano.size)
        assertEquals(Pincel.RESALTADOR, enLaMano.first().pincel)
        assertEquals("#ffd43b", enLaMano.first().color)
        assertEquals(c.grosorDeVerdad, enLaMano.first().calibre)

        // Y en cuanto se suelta, tampoco: ya es un trazo del croquis como los demás.
        c.tocar(Pt(600.0, 1100.0), Fase.LEVANTA)
        assertTrue(c.elRodilloEnLaMano().isEmpty())
    }

    // ---- La galería de tintas ----

    /** La galería nunca está vacía: trae las cinco de siempre, y esas no se tiran. */
    @Test
    fun `las tintas de fabrica estan en la galeria y no se borran`() {
        val c = conUnTrazo()
        assertEquals(5, c.lasTintas.size)
        assertTrue(c.lasTintas.all { it.deFabrica })

        c.tirarLaPunta("fabrica-redondo")
        assertEquals(5, c.lasTintas.size)

        // **Las dos de material, guardadas como lo que son.** La luz es una redonda hecha
        // de luz y la rayada es una redonda con grano: ni una ni otra es una punta aparte.
        val luz = c.lasTintas.single { it.id == "fabrica-luz" }
        assertEquals(Pincel.REDONDO, luz.pincel)
        assertTrue(luz.punta.alumbra)
        val rayada = c.lasTintas.single { it.id == "fabrica-rayado" }
        assertEquals(Trama.RAYADO, rayada.punta.trama)

        // Ponerse una de fábrica cambia la punta pero **respeta el color de uno**.
        c.color = "#e03131"
        c.usarLaPunta("fabrica-resaltador")
        assertEquals(Pincel.RESALTADOR, c.pincel)
        assertEquals("#e03131", c.color)

        // Y las de uno se guardan enteras, con su color y su pincel.
        c.grosor = 7.0
        c.guardarLaPunta()
        val mia = c.croquis.puntas.single()
        assertEquals("#e03131", mia.color)
        assertEquals(Pincel.RESALTADOR, mia.pincel)
        c.tirarLaPunta(mia.id)
        assertTrue(c.croquis.puntas.isEmpty())
    }

    // ---- La luz y la luna ----

    /** El cero **es** apagada: un mando y no un interruptor con un mando al lado. */
    @Test
    fun `el brillo de la luz baja hasta apagarse`() {
        val c = conUnTrazo()
        c.alumbrar(0.4)
        assertEquals(0.4, c.luz, 1e-9)
        c.alumbrar(-1.0)
        assertEquals(0.0, c.luz, 1e-9)
        c.alumbrar(9.0)
        assertEquals(1.0, c.luz, 1e-9)

        // Con algo elegido, se le cambia también a eso.
        c.herramienta = Herramienta3D.SELECCION
        val t = c.croquis.trazos.first()
        val encima = c.camara.aPantalla(t.puntos[t.puntos.size / 2], ancho, alto)
        c.tocar(encima, Fase.BAJA)
        c.tocar(encima, Fase.LEVANTA)
        c.alumbrar(0.25)
        assertEquals(0.25, c.croquis.trazos.first().luz, 1e-9)
    }

    /** La luna va aparte del sol: apagar una no apaga la otra. */
    @Test
    fun `la luna y el sol son dos luces distintas`() {
        val c = conUnTrazo()
        c.ponerElSol(Sol3D())
        c.ponerLaLuna(Sol3D(altura = 0.35))
        assertNotNull(c.croquis.sol)
        assertNotNull(c.croquis.luna)

        c.ponerElSol(null)
        assertNull(c.croquis.sol)
        assertNotNull("apagar el sol no apaga la luna", c.croquis.luna)

        // Y alumbra como el sol: tira la sombra en el suelo.
        val sombra = c.croquis.luna!!.sombraDe(Pt3(0.0, 0.0, 10.0))
        assertNotNull(sombra)
        assertEquals(0.0, sombra!!.z, 1e-9)
    }

    // ---- Los colores que se van usando ----

    /** El último, primero, y sin repetidos: la paleta se hace sola con lo que uno usa. */
    @Test
    fun `los colores usados se recuerdan`() {
        val c = conUnTrazo()
        assertTrue(c.croquis.favoritos.isEmpty())

        c.recordarElColor("#e03131")
        c.recordarElColor("#1971c2")
        assertEquals(listOf("#1971c2", "#e03131"), c.croquis.favoritos)

        // Volver a uno lo sube al principio en vez de duplicarlo.
        c.recordarElColor("#e03131")
        assertEquals(listOf("#e03131", "#1971c2"), c.croquis.favoritos)

        // Y no se llena de colores: se queda con unos pocos.
        repeat(20) { c.recordarElColor("#%06x".format(it * 1234 + 1)) }
        assertTrue(c.croquis.favoritos.size <= 8)
    }

    // ---- Las capas ----

    /**
     * **Lo que se dibuja va a la capa que esté puesta.**
     *
     * Es lo que convierte los grupos en algo que se usa **mientras** se dibuja —el armazón
     * en una, el detalle en otra— en vez de en algo que solo sirve para ordenar después.
     */
    @Test
    fun `lo dibujado va a la capa puesta`() {
        val c = conUnTrazo()
        assertNull(c.capaActiva)
        assertNull("suelto, sin capa", c.croquis.trazos.single().grupo)

        c.capaNueva()
        val capa = c.croquis.grupos.single()
        assertEquals(capa.id, c.capaActiva)

        c.herramienta = Herramienta3D.LAPIZ
        c.trazar(Pt(420.0, 950.0), Pt(580.0, 1050.0))
        assertEquals(capa.id, c.croquis.trazos.last().grupo)

        // Y una capa vacía no desaparece mientras se esté dibujando en ella.
        c.capaNueva()
        assertEquals(2, c.croquis.grupos.size)

        // Tocar la que ya está puesta la suelta: se vuelve a dibujar fuera de capas.
        c.dibujarEnLaCapa(c.capaActiva)
        assertNull(c.capaActiva)
    }

    /** Deshacer la capa en la que se dibuja deja de dibujar en ella. */
    @Test
    fun `deshacer una capa suelta el pincel`() {
        val c = conUnTrazo()
        c.capaNueva()
        val capa = c.croquis.grupos.single().id
        c.desagrupar(capa)
        assertNull(c.capaActiva)
        assertTrue(c.croquis.grupos.isEmpty())
    }

    // ---- La galería de tintas ----

    /**
     * **Una tinta guardada se guarda entera**: la punta, el color, el grueso y lo que tapa.
     *
     * Guardando solo la forma habría que reconstruir de memoria las otras tres cada vez, que
     * es exactamente lo que hace que nadie use una galería de pinceles.
     */
    @Test
    fun `una tinta se guarda entera y se recupera`() {
        val c = conUnTrazo()
        c.color = "#e03131"
        c.grosor = 9.0
        c.opacidad = 0.5
        c.punta = PuntaDelPincel(perfil = SeccionDePunta.CUÑA.puntos())
        c.guardarLaPunta()

        val guardada = c.croquis.puntas.single()
        assertEquals("#e03131", guardada.color)
        assertEquals(9.0, guardada.grosor, tol)
        assertEquals(0.5, guardada.opacidad, tol)
        assertTrue(guardada.punta.tienePerfil)

        // Se cambia todo y se vuelve a ella de un toque.
        c.color = "#1971c2"
        c.grosor = 2.0
        c.opacidad = 1.0
        c.punta = PuntaDelPincel()
        c.usarLaPunta(guardada.id)
        assertEquals("#e03131", c.color)
        assertEquals(9.0, c.grosor, tol)
        assertEquals(0.5, c.opacidad, tol)
        assertTrue(c.punta.tienePerfil)

        c.tirarLaPunta(guardada.id)
        assertTrue(c.croquis.puntas.isEmpty())
    }

    /** Y de noche se dibuja con tinta clara: una casi negra sobre fondo oscuro no se ve. */
    @Test
    fun `la tinta de fabrica depende del tema`() {
        assertEquals("#1e1e1e", Croquis3DControlador.tintaDeFabrica(deNoche = false))
        assertEquals("#f1f3f5", Croquis3DControlador.tintaDeFabrica(deNoche = true))
    }

    // ---- El sol ----

    /**
     * **La sombra dice dónde está apoyada una cosa.**
     *
     * Es lo que un croquis no puede decir sin girar la vista: un trazo flotando a un metro
     * del suelo y otro apoyado en él se ven igual desde casi cualquier ángulo. Con el sol,
     * la sombra cae en el suelo y se ve de un vistazo.
     */
    @Test
    fun `el sol tira la sombra en el suelo`() {
        val sol = Sol3D(azimut = 0.0, altura = Math.PI / 4)
        // A cuarenta y cinco grados, la sombra se aleja tanto como alto esté el punto.
        val arriba = Pt3(0.0, 0.0, 10.0)
        val sombra = sol.sombraDe(arriba)!!
        assertEquals(0.0, sombra.z, 1e-9)
        assertEquals(10.0, largo(Pt3(sombra.x, sombra.y, 0.0)), 1e-6)

        // Lo que está en el suelo tira su sombra en su sitio.
        val enElSuelo = Pt3(3.0, 4.0, 0.0)
        val suya = sol.sombraDe(enElSuelo)!!
        assertEquals(enElSuelo.x, suya.x, 1e-9)
        assertEquals(enElSuelo.y, suya.y, 1e-9)

        // Y lo que está por debajo del suelo no tira sombra hacia arriba: eso es un reflejo.
        assertNull(sol.sombraDe(Pt3(0.0, 0.0, -5.0)))
    }

    /** Cuanto más bajo el sol, más larga la sombra. Y nunca infinita. */
    @Test
    fun `el sol bajo estira la sombra`() {
        val punto = Pt3(0.0, 0.0, 10.0)
        val alto = Sol3D(altura = Math.PI / 3).sombraDe(punto)!!
        val bajo = Sol3D(altura = 0.2).sombraDe(punto)!!
        assertTrue(largo(bajo) > largo(alto))
        assertTrue("no puede irse al infinito", largo(Sol3D(altura = 0.0).sombraDe(punto)!!) < 1e6)
    }

    /** El sol se enciende y se apaga, y no se mete en el deshacer: mover el sol es mirar. */
    @Test
    fun `el sol no entra en el historial`() {
        val c = conUnTrazo()
        assertNull(c.croquis.sol)
        val antes = c.puedeDeshacer

        c.ponerElSol(Sol3D())
        assertNotNull(c.croquis.sol)
        assertEquals(antes, c.puedeDeshacer)

        c.ponerElSol(null)
        assertNull(c.croquis.sol)
    }

    // ---- Los candados ----

    /** De fábrica no hay nada clavado: se abre la aplicación y se dibuja. */
    @Test
    fun `el candado nace suelto`() {
        assertTrue(!controlador().zoomBloqueado)
    }

    /**
     * **La forma la pone el trazo; la profundidad no se acaba.**
     *
     * A lo ancho, la hoja es lo que se trazó y se acaba donde se acabó la raya. A lo hondo
     * no hay nada que decidir —es hacia donde se estaba mirando—, así que llega hasta donde
     * haga falta: dibujar un poco más adentro no puede quedarse sin apoyo por una frontera
     * que nadie ha puesto y que no se ve.
     */
    @Test
    fun `la hoja no se acaba a lo hondo`() {
        val c = controlador()
        c.herramienta = Herramienta3D.LAMINA
        c.trazar(Pt(400.0, 1000.0), Pt(600.0, 1000.0))
        val hoja = c.croquis.laminas.single()
        assertTrue(hoja.infinita)
        assertTrue("llega lejísimos a lo hondo: ${'$'}{hoja.fondo}", hoja.fondo > 30000)

        // Y a lo ancho es **exactamente lo que se trazó**: ni treinta pantallas, ni un palmo
        // de regalo por cada punta.
        val ancho = largo(menos(hoja.perfil.last(), hoja.perfil.first()))
        assertEquals("a lo ancho no es lo que se trazó", 200.0, ancho, 1.0)

        // Se dibuja igual de lejos que se quiera hacia dentro de la pantalla.
        c.girarUnCuarto()
        c.herramienta = Herramienta3D.LAPIZ
        c.trazar(Pt(60.0, 200.0), Pt(940.0, 1800.0))
        assertEquals(1, c.croquis.trazos.size)
    }

    /** Y con el del aumento, los dedos desplazan pero no acercan. */
    @Test
    fun `el candado del aumento no deja acercar`() {
        val c = conUnTrazo()
        c.zoomBloqueado = true
        val aumento = c.camara.zoom
        val centro = c.camara.centro

        c.navegar(30.0, 20.0, 1.6, ancho / 2, alto / 2, Gesto.DESPLAZAR)
        assertEquals(aumento, c.camara.zoom, tol)
        assertTrue("pero desplazar sí", largo(menos(c.camara.centro, centro)) > 1e-6)

        c.orbitar(0.2, 0.1, 2.0)
        assertEquals(aumento, c.camara.zoom, tol)

        c.zoomBloqueado = false
        c.orbitar(0.0, 0.0, 2.0)
        assertEquals(aumento * 2, c.camara.zoom, tol)
    }

    // ---- Las imágenes ----

    /**
     * **Una imagen se maneja con el mando como cualquier otra cosa.**
     *
     * Se guarda por sus cuatro esquinas, así que moverla, girarla o escalarla es mover sus
     * puntos: el mando no tiene que saber que es una imagen.
     */
    @Test
    fun `una imagen se pone y se maneja como un trazo`() {
        val c = conUnTrazo()
        c.ponerImagen("/tmp/una.png", 0.5)

        val imagen = c.croquis.imagenes.single()
        assertEquals(4, imagen.esquinas.size)
        assertEquals("queda cogida al ponerla", setOf(imagen.id), c.seleccion)
        // Con la proporción que se le pidió: la mitad de alta que de ancha.
        val ancho = largo(menos(imagen.esquinas[1], imagen.esquinas[0]))
        val alto = largo(menos(imagen.esquinas[3], imagen.esquinas[0]))
        assertEquals(0.5, alto / ancho, 1e-6)

        val antes = imagen.esquinas
        c.empezarAManejar()
        c.moverLaSeleccionPorElEje(EjeDelMundo.Z, 100.0)
        val despues = c.croquis.imagenes.single().esquinas
        // Sube por la vertical, entera y sin deformarse.
        assertTrue(despues.first().z > antes.first().z)
        for (i in antes.indices) {
            assertEquals(antes[i].x, despues[i].x, 1e-9)
            assertEquals(antes[i].y, despues[i].y, 1e-9)
            assertEquals(despues.first().z - antes.first().z, despues[i].z - antes[i].z, 1e-9)
        }

        // Y girar no la deforma: sigue midiendo lo mismo de lado a lado.
        c.girarLaSeleccionConLaMano(60.0, 30.0)
        val girada = c.croquis.imagenes.single()
        assertEquals(ancho, largo(menos(girada.esquinas[1], girada.esquinas[0])), 1e-6)
        assertEquals(alto, largo(menos(girada.esquinas[3], girada.esquinas[0])), 1e-6)
    }

    /** Se esconde, se borra y se cuenta como lo demás. */
    @Test
    fun `una imagen se esconde y se borra`() {
        val c = conUnTrazo()
        c.ponerImagen("/tmp/una.png", 1.0)
        c.ocultarLaSeleccion()
        assertTrue(c.croquis.imagenes.single().oculto)
        assertTrue(c.croquis.hayOcultos)

        c.enseñarLoOculto()
        assertTrue(!c.croquis.imagenes.single().oculto)

        c.elegirLaImagen()
        c.borrarLaSeleccion()
        assertTrue(c.croquis.imagenes.isEmpty())
    }

    /** Elige la imagen apuntándole al centro, como haría el dedo. */
    private fun Croquis3DControlador.elegirLaImagen() {
        val imagen = croquis.imagenes.single()
        val centro = por(
            imagen.esquinas.fold(Pt3(0.0, 0.0, 0.0)) { a, b -> mas(a, b) }, 0.25
        )
        val donde = camara.aPantalla(centro, ancho, alto)
        herramienta = Herramienta3D.SELECCION
        tocar(donde, Fase.BAJA)
        tocar(donde, Fase.LEVANTA)
    }

    // ---- Los grupos ----

    /**
     * Un croquis con dos trazos sobre la hoja, **bien separados en la pantalla**.
     *
     * Separados a propósito: la bolita coge lo que le pasa cerca, así que dos trazos
     * pisándose se encienden y se apagan el uno al otro y la prueba mediría eso en vez de
     * los grupos.
     */
    private fun conDosTrazos(): Croquis3DControlador {
        val c = conUnTrazo()
        c.herramienta = Herramienta3D.LAPIZ
        c.trazar(Pt(250.0, 1400.0), Pt(400.0, 1650.0))
        return c
    }

    /**
     * **Agrupar hace que varios trazos sean una cosa**: tocar uno los coge a todos.
     *
     * Es para lo que sirve un grupo. Sin eso habría que volver a pasarle la bolita a los
     * catorce trazos del respaldo cada vez que se quiere mover el respaldo.
     */
    @Test
    fun `un grupo se elige entero tocando uno`() {
        val c = conDosTrazos()
        c.herramienta = Herramienta3D.SELECCION
        // Se eligen los dos a mano y se juntan.
        for (t in c.croquis.trazos) {
            val encima = c.camara.aPantalla(t.puntos[t.puntos.size / 2], ancho, alto)
            c.tocar(encima, Fase.BAJA)
            c.tocar(encima, Fase.LEVANTA)
        }
        assertEquals(2, c.seleccion.size)
        c.agruparLaSeleccion()
        assertEquals(1, c.croquis.grupos.size)
        assertTrue(c.croquis.trazos.all { it.grupo != null })

        // Y ahora basta tocar uno.
        c.soltarLaSeleccion()
        val uno = c.croquis.trazos.first()
        val encima = c.camara.aPantalla(uno.puntos[uno.puntos.size / 2], ancho, alto)
        c.tocar(encima, Fase.BAJA)
        c.tocar(encima, Fase.LEVANTA)
        assertEquals("tocando uno se cogen los dos", 2, c.seleccion.size)
    }

    /** Se esconde y se enseña de una pieza, y se deshace de una pieza. */
    @Test
    fun `un grupo se esconde entero`() {
        val c = conDosTrazos()
        c.agruparATodos()
        val grupo = c.croquis.grupos.single().id

        c.ocultarElGrupo(grupo, true)
        assertTrue(c.croquis.trazos.all { it.oculto })
        assertTrue(c.seleccion.isEmpty())

        c.ocultarElGrupo(grupo, false)
        assertTrue(c.croquis.trazos.none { it.oculto })

        c.elegirElGrupo(grupo)
        assertEquals(2, c.seleccion.size)

        c.desagrupar(grupo)
        assertTrue(c.croquis.grupos.isEmpty())
        assertTrue("los trazos se quedan", c.croquis.trazos.size == 2)
        assertTrue(c.croquis.trazos.all { it.grupo == null })
    }

    /** Y un grupo que se queda sin trazos se va solo: una caja vacía no es una pieza. */
    @Test
    fun `un grupo vacio desaparece solo`() {
        val c = conDosTrazos()
        c.agruparATodos()
        assertEquals(1, c.croquis.grupos.size)

        c.elegirElGrupo(c.croquis.grupos.single().id)
        c.borrarLaSeleccion()
        assertTrue(c.croquis.trazos.isEmpty())
        assertTrue(c.croquis.grupos.isEmpty())
    }

    /** Junta todo lo que hay en un grupo, para las pruebas. */
    private fun Croquis3DControlador.agruparATodos() {
        herramienta = Herramienta3D.SELECCION
        for (t in croquis.trazos) {
            val encima = camara.aPantalla(t.puntos[t.puntos.size / 2], ancho, alto)
            tocar(encima, Fase.BAJA)
            tocar(encima, Fase.LEVANTA)
        }
        agruparLaSeleccion()
        soltarLaSeleccion()
    }

    // ---- El espejo ----

    /** Un croquis con hoja, eje de simetría trazado y nada más. */
    private fun conEspejo(): Croquis3DControlador {
        val c = controlador()
        c.herramienta = Herramienta3D.LAMINA
        c.trazar(Pt(200.0, 1000.0), Pt(800.0, 1000.0))
        c.girarUnCuarto()
        c.herramienta = Herramienta3D.ESPEJO
        c.trazar(Pt(500.0, 700.0), Pt(500.0, 1300.0))
        return c
    }

    /**
     * **Lo que se dibuja de un lado sale también del otro.**
     *
     * Y sale como un trazo más, no como una copia atada al original: se puede borrar o
     * repintar por separado en cuanto la pieza deja de ser simétrica del todo.
     */
    @Test
    fun `el espejo dibuja el otro lado`() {
        val c = conEspejo()
        val eje = c.croquis.espejo!!
        assertTrue("el eje no es dibujo", c.croquis.trazos.isEmpty())
        // El propio eje se queda quieto al reflejarlo: es la charnela.
        assertEquals(eje.punto, eje.reflejo(eje.punto))

        c.herramienta = Herramienta3D.LAPIZ
        c.trazar(Pt(380.0, 850.0), Pt(440.0, 1150.0))

        assertEquals("uno dibujado y su reflejo", 2, c.croquis.trazos.size)
        val (dibujado, reflejado) = c.croquis.trazos
        assertEquals(eje.reflejo(dibujado.puntos), reflejado.puntos)
        assertTrue("y son dos trazos distintos", dibujado.id != reflejado.id)

        // Y los dos se van con un solo deshacer: fue un solo gesto.
        c.deshacer()
        assertTrue(c.croquis.trazos.isEmpty())
    }

    /**
     * **Sin hoja no hay eje.** El eje de simetría es una raya sobre la mesa de dibujo:
     * quitada la mesa no queda de qué colgarlo, y seguir reflejando contra él sería
     * prometer una simetría que ya no se apoya en nada.
     */
    @Test
    fun `quitar la hoja se lleva el eje del espejo`() {
        val c = conEspejo()
        assertNotNull(c.croquis.espejo)

        c.quitarLaHoja()
        assertNull(c.croquis.espejo)
        assertTrue(!c.espejaAhora)

        // Y se recupera con el mismo deshacer que la hoja: fue un solo gesto.
        c.deshacer()
        assertNotNull(c.croquis.espejo)
        assertTrue(c.hayPlano)
    }

    /** Y una hoja nueva tampoco se queda con el eje de la vieja: estaba trazado en ella. */
    @Test
    fun `una hoja nueva no hereda el eje`() {
        val c = conEspejo()
        c.herramienta = Herramienta3D.LAMINA
        c.trazar(Pt(300.0, 600.0), Pt(700.0, 600.0))
        assertNull(c.croquis.espejo)
    }

    /** Con el espejo apagado se dibuja de uno en uno, sin perder el eje. */
    @Test
    fun `apagando el espejo no se duplica`() {
        val c = conEspejo()
        c.espejoPuesto = false
        c.herramienta = Herramienta3D.LAPIZ
        c.trazar(Pt(380.0, 850.0), Pt(440.0, 1150.0))
        assertEquals(1, c.croquis.trazos.size)
        assertNotNull("el eje se queda por si vuelve a hacer falta", c.croquis.espejo)
        assertTrue(!c.espejaAhora)
    }

    /** Reflejar dos veces devuelve al sitio: es un espejo, no una deformación. */
    @Test
    fun `reflejar dos veces no mueve nada`() {
        val eje = conEspejo().croquis.espejo!!
        val p = Pt3(37.0, -12.0, 5.5)
        val ida = eje.reflejo(p)
        val vuelta = eje.reflejo(ida)
        assertEquals(p.x, vuelta.x, 1e-9)
        assertEquals(p.y, vuelta.y, 1e-9)
        assertEquals(p.z, vuelta.z, 1e-9)
        assertTrue("y de ida sí se ha movido", largo(menos(ida, p)) > 1.0)
    }

    // ---- La transparencia ----

    /** Lo que tapa la tinta se guarda con el trazo y se le cambia a lo elegido. */
    @Test
    fun `la tinta se transparenta`() {
        val c = conUnTrazo()
        assertEquals("de fábrica tapa del todo", 1.0, c.croquis.trazos.single().opacidad, tol)

        c.opacidad = 0.4
        c.trazar(Pt(420.0, 950.0), Pt(580.0, 1050.0))
        assertEquals(0.4, c.croquis.trazos.last().opacidad, tol)

        c.elegirElTrazo()
        c.empezarARetocar()
        c.transparentarLaSeleccion(0.75)
        assertEquals(0.75, c.croquis.trazos.first().opacidad, tol)

        // Y todo el arrastre de la tira cabe en un deshacer.
        c.transparentarLaSeleccion(0.7)
        c.transparentarLaSeleccion(0.65)
        c.deshacer()
        assertEquals(1.0, c.croquis.trazos.first().opacidad, tol)
    }

    // ---- Las flechas del mando ----

    /** Tirar de una flecha mueve por su eje y por ninguno más. */
    @Test
    fun `la flecha mueve solo por su eje`() {
        val c = conUnTrazo()
        c.elegirElTrazo()
        val antes = c.croquis.trazos.single().puntos.first()

        c.empezarAManejar()
        c.moverLaSeleccionPorElEje(EjeDelMundo.X, 100.0)

        val despues = c.croquis.trazos.single().puntos.first()
        assertEquals(antes.y, despues.y, 1e-9)
        assertEquals(antes.z, despues.z, 1e-9)
        assertTrue(abs(despues.x - antes.x) > 1e-6)
    }

    /**
     * **Y la `z` se mueve aunque se la esté viendo de punta.**
     *
     * Mirando desde arriba, la vertical apunta al ojo: no se ve nada de ella en la pantalla.
     * Es justo cuando más falta hace despegar algo de la hoja, así que ahí manda el aumento
     * en vez de lo que mide el eje en la pantalla, que es cero.
     */
    @Test
    fun `por la z se mueve aunque se vea de punta`() {
        val c = conUnTrazo()
        c.elegirElTrazo()
        // Vista de planta: la z va derecha a la cámara.
        c.encajarEnLaVistaMasCercana()
        c.orbitar(0.0, 3.0, 1.0)
        val antes = c.croquis.trazos.single().puntos.first()

        c.empezarAManejar()
        c.moverLaSeleccionPorElEje(EjeDelMundo.Z, 120.0)

        val despues = c.croquis.trazos.single().puntos.first()
        assertEquals(120.0 / c.camara.zoom, despues.z - antes.z, 1e-6)
    }

    /**
     * **De frente a un plano solo hay dos direcciones, y solo se enseñan dos flechas.**
     *
     * Puesto de alzado, la `y` va derecha al ojo: su flecha se queda en un punto en el
     * centro, apuntando a donde caiga el redondeo, y tirar de ella mueve la figura hacia
     * dentro de la pantalla sin que se note. Lo que hay en esa postura son las dos
     * direcciones del plano que se está mirando.
     */
    @Test
    fun `de frente a un plano solo salen dos flechas`() {
        val c = conUnTrazo()
        c.elegirElTrazo()
        // De alzado: mirando por la `y`.
        c.pedirVista(CaraDelCubo.FRENTE.postura)
        c.andarElViaje(1f)

        val flechas = flechasDe(c)
        assertEquals(2, flechas.size)
        assertTrue(
            "la que va al ojo no se pinta",
            flechas.none { it.agarre.eje == EjeDelMundo.Y }
        )

        // Y girando un poco vuelve a haber tres: ya se ve algo de ella.
        c.orbitar(0.6, 0.3, 1.0)
        assertEquals(3, flechasDe(c).size)
    }

    /** Arrastrando en cruz a una flecha no se pide nada: una flecha es una flecha. */
    @Test
    fun `en cruz a la flecha no se pide nada`() {
        val angulo = Math.PI / 6
        val cos = kotlin.math.cos(angulo)
        val sen = kotlin.math.sin(angulo)
        // Cien píxeles por la flecha son cien píxeles pedidos.
        assertEquals(100.0, avanceDeLaFlecha(Pt(cos * 100, sen * 100), angulo), 1e-9)
        // Y cien en cruz, ninguno.
        assertEquals(0.0, avanceDeLaFlecha(Pt(-sen * 100, cos * 100), angulo), 1e-9)
    }

    /**
     * **El cubo, con algo elegido, voltea lo elegido con libertad.**
     *
     * Y girar no deforma: lo que se comprueba es que las distancias al centro se conservan,
     * que es lo que separa un giro de un estropicio.
     */
    @Test
    fun `girar con la mano voltea lo elegido sin deformarlo`() {
        val c = conUnTrazo()
        c.elegirElTrazo()
        val centro = c.centroDeLaSeleccion()!!
        val antes = c.croquis.trazos.single().puntos.map { largo(menos(it, centro)) }

        c.empezarAManejar()
        c.girarLaSeleccionConLaMano(90.0, 40.0)

        val trazo = c.croquis.trazos.single()
        val ahora = c.centroDeLaSeleccion()!!
        // El centro se queda donde estaba: se gira sobre sí mismo, no se va de viaje.
        assertEquals(centro.x, ahora.x, 1e-6)
        assertEquals(centro.y, ahora.y, 1e-6)
        assertEquals(centro.z, ahora.z, 1e-6)
        for ((i, p) in trazo.puntos.withIndex()) {
            assertEquals(antes[i], largo(menos(p, centro)), 1e-6)
        }
        // Y algo se ha movido de verdad.
        assertTrue(largo(menos(trazo.puntos.first(), conUnTrazo().croquis.trazos.single().puntos.first())) > 1e-6)
    }

    /** Sin nada elegido, el cubo no toca el dibujo: entonces gira la vista. */
    @Test
    fun `girar con la mano sin eleccion no hace nada`() {
        val c = conUnTrazo()
        val antes = c.croquis
        c.girarLaSeleccionConLaMano(120.0, 60.0)
        assertEquals(antes, c.croquis)
    }

    // ---- El color del espacio ----

    /**
     * **El fondo va en el croquis: se guarda con él y se deshace como un trazo.**
     *
     * Y de fábrica no hay ninguno, que es lo que deja que el espacio siga el tema del
     * sistema: un blanco puesto a mano se quedaría blanco de noche.
     */
    @Test
    fun `el fondo se elige y se deshace`() {
        val c = conUnTrazo()
        assertNull("de fábrica, el del tema", c.croquis.colorDelFondo)

        c.empezarAPintarElFondo()
        c.pintarElFondo("#1b1f24")
        assertEquals("#1b1f24", c.croquis.colorDelFondo)

        c.deshacer()
        assertNull(c.croquis.colorDelFondo)
        assertEquals("y no se ha llevado el dibujo por delante", 1, c.croquis.trazos.size)
    }

    /** Vaciar tira el dibujo, no cambia de papel. */
    @Test
    fun `vaciar no se lleva el fondo`() {
        val c = conUnTrazo()
        c.empezarAPintarElFondo()
        c.pintarElFondo("#f4efe4")

        c.vaciar()
        assertTrue(c.croquis.vacio)
        assertEquals("#f4efe4", c.croquis.colorDelFondo)
    }

    /** Y sin dedo apoyado, el latido no hace nada: no hay nada que enderezar. */
    @Test
    fun `el latido con la mano en el aire no hace nada`() {
        val c = conUnTrazo()
        val antes = c.croquis
        c.latido(9_999_999L)
        assertTrue(!c.enderezandoSolo)
        assertTrue(c.trazoEnCurso.isEmpty())
        assertEquals(antes, c.croquis)
    }

    /**
     * **El plano compuesto: una línea lo crea y la otra lo dobla.**
     *
     * Es la superficie de dos rieles —la que ya daba «unir dos trazos»— pero dada como se
     * da un plano: trazando. Lo que se comprueba aquí es el turno, que es lo único que hay
     * que aprender de la herramienta: crea, dobla, y la siguiente vuelve a crear.
     */
    @Test
    fun `el plano compuesto se hace con dos lineas`() {
        val c = controlador()
        c.herramienta = Herramienta3D.PLANO_COMPUESTO

        // La primera línea deja un plano como cualquier otro, para poder verlo y girarlo.
        c.trazar(Pt(200.0, 900.0), Pt(800.0, 900.0))
        val primera = c.croquis.laminas.single()
        assertNull("todavía no tiene el otro riel", primera.otroPerfil)
        assertTrue("y está esperando la segunda línea", c.esperandoLaSegundaLinea)

        // Se gira para ver por dónde va, y se traza la otra arista.
        c.girarUnCuarto()
        c.trazar(Pt(200.0, 1400.0), Pt(800.0, 1400.0))
        val hecha = c.croquis.laminas.single()
        assertEquals("es la misma hoja, doblada", primera.id, hecha.id)
        assertNotNull("y ya tiene sus dos rieles", hecha.otroPerfil)
        assertEquals(hecha.perfil.size, hecha.otroPerfil!!.size)
        assertTrue("con tiras para apoyar el lápiz", hecha.tiras().isNotEmpty())
        // Ya no barre hacia el fondo: la hoja llega hasta la segunda arista y ahí se acaba.
        assertFalse("deja de ser infinita", hecha.infinita)
        assertFalse("y deja de esperar nada", c.esperandoLaSegundaLinea)

        // Y la siguiente línea empieza otro plano en vez de volver a doblar este.
        c.trazar(Pt(300.0, 1000.0), Pt(900.0, 1000.0))
        val tercera = c.croquis.laminas.single()
        assertTrue("otra hoja", tercera.id != hecha.id)
        assertNull(tercera.otroPerfil)
        assertTrue(c.esperandoLaSegundaLinea)
    }

    /**
     * **Las dos líneas se trazan sobre la pantalla, no sobre la hoja recién puesta.**
     *
     * Es la diferencia con hacerlo a mano —dos trazos del lápiz y «unir»—: el lápiz se apoya
     * siempre en la hoja que haya, así que la segunda arista salía pegada a la primera
     * superficie y la hoja no tenía fondo. Aquí, girando entre línea y línea, los dos rieles
     * quedan a distinta profundidad, que es lo que hace que la superficie sea una superficie.
     */
    @Test
    fun `los dos rieles del plano compuesto no caen en el mismo sitio`() {
        val c = controlador()
        c.herramienta = Herramienta3D.PLANO_COMPUESTO
        c.trazar(Pt(200.0, 900.0), Pt(800.0, 900.0))
        c.girarUnCuarto()
        c.trazar(Pt(200.0, 1400.0), Pt(800.0, 1400.0))

        val hoja = c.croquis.laminas.single()
        val separacion = largo(menos(hoja.otroLado(0), hoja.unLado(0)))
        assertTrue("los rieles están separados de verdad", separacion > 1.0)
    }
    /**
     * **Una raya a pulso se endereza; un arco trazado a propósito, no.**
     *
     * Es lo que hacía imposible el plano curvo: al pararse el dedo, lo trazado se cambiaba
     * por la cuerda de sus puntas **fuera lo que fuera**, y una curva se traza despacio, así
     * que uno se para. La hoja nacía plana sin decir por qué.
     */
    @Test
    fun `una raya con pulso se endereza y un arco no`() {
        // Una raya de diez, con el temblor de una mano: se aparta menos de un uno por ciento.
        val aPulso = (0..10).map { Pt(it.toDouble(), if (it % 2 == 0) 0.03 else -0.03) }
        assertFalse("una raya temblona sigue siendo una raya", yaEsUnaCurva(aPulso))

        // Un cuarto de circunferencia de radio diez: eso lo ha querido alguien.
        val arco = (0..12).map {
            val a = Math.PI / 2 * it / 12
            Pt(kotlin.math.cos(a) * 10, kotlin.math.sin(a) * 10)
        }
        assertTrue("un cuarto de vuelta es una curva", yaEsUnaCurva(arco))

        // La medida es de forma y no de tamaño: el mismo arco a otra escala decide igual.
        assertTrue("y a treinta metros también", yaEsUnaCurva(arco.map { Pt(it.x * 300, it.y * 300) }))

        // Dos puntos no tienen de qué apartarse.
        assertFalse(yaEsUnaCurva(listOf(Pt(0.0, 0.0), Pt(5.0, 0.0))))

        // Ida y vuelta: la cuerda mide cero, pero raya no es.
        assertTrue("ida y vuelta no es una raya",
            yaEsUnaCurva(listOf(Pt(0.0, 0.0), Pt(4.0, 0.0), Pt(0.0, 0.0))))
    }

}
