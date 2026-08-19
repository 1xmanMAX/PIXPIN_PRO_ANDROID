package com.forge.pixpin.motor

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.round
import kotlin.math.sqrt
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La proyección isométrica.
 *
 * Aquí se comprueban dos clases de cosas muy distintas. Las primeras son
 * evidentes en cuanto se miran —el origen en el cero, un cubo del tamaño que
 * toca— y están para que un cambio de convenio no pase de largo. Las segundas
 * no se ven mirando: que el ir y venir de la pantalla al suelo cierre exacto en
 * las cuatro vistas, y sobre todo que el imán enganche **de verdad** al nodo más
 * cercano. Esa última es la que hay que probar sí o sí, porque el atajo evidente
 * —redondear cada coordenada— está mal en casi una de cada cinco veces y por la
 * pantalla no se distingue de un dedo poco fino.
 */
class ProyeccionTest {

    private val tol = 1e-9

    /** Todas las vistas, que casi todo hay que comprobarlo en las cuatro. */
    private val vistas = Vista.entries

    // ---------------------------------------------------------------------
    // La cuenta de la proyección
    // ---------------------------------------------------------------------

    /** El cero del mundo es el cero de la escena, mire uno desde donde mire. */
    @Test
    fun `el origen del mundo cae en el cero de la pantalla`() {
        for (v in vistas) {
            val p = proyectar(0.0, 0.0, 0.0, v)
            assertEquals("vista $v", 0.0, p.x, tol)
            assertEquals("vista $v", 0.0, p.y, tol)
        }
    }

    /**
     * **La vertical sube y no se mueve de lado**, y sube el paso entero.
     *
     * En pantalla la `y` crece hacia abajo, así que subir en el mundo tiene que
     * restar. Es la única línea de la proyección que se lee al revés y por eso
     * la que más fácil se escribe con el signo cambiado — y con el signo
     * cambiado las cajas salen enterradas en vez de levantadas.
     */
    @Test
    fun `la altura sube en pantalla y no desplaza de lado`() {
        for (v in vistas) {
            val p = proyectar(0.0, 0.0, 1.0, v, paso = 20.0)
            assertEquals("vista $v", 0.0, p.x, tol)
            assertEquals("vista $v", -20.0, p.y, tol)
        }
    }

    /** Los dos ejes del suelo salen a 30° sobre la horizontal, uno a cada lado. */
    @Test
    fun `los ejes del suelo van a treinta grados`() {
        val x = proyectar(1.0, 0.0, 0.0, Vista.CERO, paso = 20.0)
        assertEquals(20.0 * sqrt(3.0) / 2, x.x, tol)
        assertEquals(10.0, x.y, tol)

        val y = proyectar(0.0, 1.0, 0.0, Vista.CERO, paso = 20.0)
        assertEquals(-20.0 * sqrt(3.0) / 2, y.x, tol)
        assertEquals(10.0, y.y, tol)

        // 30 grados clavados, y sin acortar: un paso de mundo mide un paso.
        assertEquals(Math.toRadians(30.0), atan2(x.y, x.x), tol)
        assertEquals(20.0, hypot(x.x, x.y), tol)
        assertEquals(20.0, hypot(y.x, y.y), tol)
    }

    /**
     * **Un cubo unidad ocupa un hexágono de `√3·paso` por `2·paso`.**
     *
     * Es la comprobación de tamaño de toda la proyección: sale de que los tres
     * ejes se dibujan sin acortar, y si alguien toca [AVANCE_H] o [AVANCE_V] por
     * su cuenta, esto es lo primero que deja de cuadrar.
     */
    @Test
    fun `un cubo unidad mide raíz de tres de ancho y dos de alto`() {
        val paso = 20.0
        for (v in vistas) {
            val esquinas = mutableListOf<Pt>()
            for (x in 0..1) for (y in 0..1) for (z in 0..1) {
                esquinas += proyectar(x.toDouble(), y.toDouble(), z.toDouble(), v, paso)
            }
            val ancho = esquinas.maxOf { it.x } - esquinas.minOf { it.x }
            val alto = esquinas.maxOf { it.y } - esquinas.minOf { it.y }
            assertEquals("ancho en $v", sqrt(3.0) * paso, ancho, tol)
            assertEquals("alto en $v", 2 * paso, alto, tol)
        }
    }

    /** El tamaño manda el [paso] y nada más: el doble de paso, el doble de todo. */
    @Test
    fun `el paso escala la proyección entera`() {
        val a = proyectar(3.0, -2.0, 1.5, Vista.CUARTO, paso = 10.0)
        val b = proyectar(3.0, -2.0, 1.5, Vista.CUARTO, paso = 20.0)
        assertEquals(2 * a.x, b.x, tol)
        assertEquals(2 * a.y, b.y, tol)
    }

    // ---------------------------------------------------------------------
    // Las cuatro vistas
    // ---------------------------------------------------------------------

    /**
     * Girar la vista un cuarto es girar el mundo un cuarto, exactamente.
     *
     * Con senos y cosenos esto fallaría por un `6e-17`; con la tabla de giros
     * cierra clavado, y de eso depende que el imán no baile al cambiar de vista.
     */
    @Test
    fun `un cuarto de vuelta gira el mundo un cuarto`() {
        val enX = proyectar(1.0, 0.0, 0.0, Vista.CUARTO)
        val enY = proyectar(0.0, 1.0, 0.0, Vista.CERO)
        assertEquals(enY.x, enX.x, 0.0)
        assertEquals(enY.y, enX.y, 0.0)

        // Media vuelta es el suelo del revés.
        val media = proyectar(2.0, -3.0, 1.0, Vista.MEDIA)
        val alReves = proyectar(-2.0, 3.0, 1.0, Vista.CERO)
        assertEquals(alReves.x, media.x, 0.0)
        assertEquals(alReves.y, media.y, 0.0)
    }

    /** **De ningún estado se sale sin volver**: cuatro toques y estás donde empezaste. */
    @Test
    fun `cuatro cuartos devuelven al punto de partida`() {
        for (v in vistas) {
            assertEquals(v, v.siguiente().siguiente().siguiente().siguiente())
            assertEquals(v, v.siguiente().anterior())
        }
        assertEquals(Vista.TRES_CUARTOS, Vista.de(-1))
        assertEquals(Vista.CERO, Vista.de(8))
        assertEquals(Vista.CUARTO, Vista.de(-7))
    }

    // ---------------------------------------------------------------------
    // La vuelta al suelo
    // ---------------------------------------------------------------------

    /**
     * **Proyectar y desproyectar sobre el suelo cierra exacto**, en las cuatro
     * vistas.
     *
     * Es lo que sostiene todo gesto: si el ir y venir se desvía, el sólido se
     * escapa del dedo mientras se arrastra, poquito a poco, que es de los fallos
     * más difíciles de atribuir mirando la pantalla.
     */
    @Test
    fun `ida y vuelta sobre el suelo es exacta en las cuatro vistas`() {
        val dado = Random(20260813)
        for (v in vistas) {
            repeat(500) {
                val x = dado.nextDouble(-500.0, 500.0)
                val y = dado.nextDouble(-500.0, 500.0)
                val p = proyectar(x, y, 0.0, v)
                val vuelta = desproyectar(p.x, p.y, v)
                assertEquals("x en $v", x, vuelta.x, 1e-9)
                assertEquals("y en $v", y, vuelta.y, 1e-9)
            }
        }
    }

    /** Y con otro paso también: la inversa tiene que llevar el mismo paso. */
    @Test
    fun `la vuelta respeta el paso`() {
        for (paso in listOf(1.0, 7.5, 20.0, 120.0)) {
            val p = proyectar(3.0, -4.0, 0.0, Vista.TRES_CUARTOS, paso)
            val vuelta = desproyectar(p.x, p.y, Vista.TRES_CUARTOS, paso)
            assertEquals(3.0, vuelta.x, 1e-9)
            assertEquals(-4.0, vuelta.y, 1e-9)
        }
    }

    /**
     * La ambigüedad que hace falta fijar un plano: **subir y alejarse por la
     * diagonal son el mismo movimiento en pantalla**.
     *
     * Por eso la vuelta trabaja solo sobre el suelo. Aquí se deja constancia de
     * que los dos puntos caen en el mismo píxel y de que lo único que los separa
     * es la profundidad.
     */
    @Test
    fun `un arrastre vertical es indistinguible de una diagonal por el suelo`() {
        val arriba = proyectar(0.0, 0.0, 1.0, Vista.CERO)
        val diagonal = proyectar(-1.0, -1.0, 0.0, Vista.CERO)
        assertEquals(diagonal.x, arriba.x, tol)
        assertEquals(diagonal.y, arriba.y, tol)
        // El mismo píxel, distinta distancia a quien mira: es lo que la vuelta
        // no puede adivinar.
        val subido = profundidad(0.0, 0.0, 1.0, Vista.CERO)
        val alejado = profundidad(-1.0, -1.0, 0.0, Vista.CERO)
        assertTrue("$subido vs $alejado", abs(subido - alejado) > 1.0)
    }

    /** Más cerca de quien mira, más profundidad. Es el orden de pintado. */
    @Test
    fun `la profundidad crece hacia quien mira`() {
        for (v in vistas) {
            val lejos = profundidad(0.0, 0.0, 0.0, v)
            // Subir siempre acerca, mire uno desde donde mire.
            assertTrue("vista $v", profundidad(0.0, 0.0, 3.0, v) > lejos)
        }
        // Y por el suelo, hacia donde la vista lo traiga.
        assertTrue(profundidad(1.0, 1.0, 0.0, Vista.CERO) > profundidad(0.0, 0.0, 0.0, Vista.CERO))
        assertTrue(profundidad(-1.0, -1.0, 0.0, Vista.MEDIA) > profundidad(0.0, 0.0, 0.0, Vista.MEDIA))
    }

    /** Con un paso degenerado se devuelve el origen y no un NaN que contamine la escena. */
    @Test
    fun `un paso imposible no produce NaN`() {
        for (paso in listOf(0.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            val p = desproyectar(30.0, 40.0, Vista.CERO, paso)
            assertTrue("paso $paso -> $p", p.x.isFinite() && p.y.isFinite())
            assertEquals(0.0, p.x, tol)
            assertEquals(0.0, p.y, tol)
        }
        val nada = desproyectar(Double.NaN, 10.0, Vista.CERO)
        assertTrue(nada.x.isFinite() && nada.y.isFinite())
    }

    // ---------------------------------------------------------------------
    // El imán
    // ---------------------------------------------------------------------

    /** Lo mínimo: lo que devuelve es un nodo de la retícula, no un punto cualquiera. */
    @Test
    fun `el imán siempre cae en un nodo`() {
        val dado = Random(5)
        for (celda in listOf(0.5, 1.0, 3.0)) {
            repeat(200) {
                val p = Pt(dado.nextDouble(-400.0, 400.0), dado.nextDouble(-400.0, 400.0))
                val nodo = imanDeReticula(p, Vista.CUARTO, celda = celda)
                assertEquals(0.0, nodo.x % celda, 1e-9)
                assertEquals(0.0, nodo.y % celda, 1e-9)
            }
        }
    }

    /** Un nodo exacto se engancha a sí mismo y no salta al de al lado. */
    @Test
    fun `un nodo exacto no se mueve`() {
        for (v in vistas) {
            for (i in -3..3) for (j in -3..3) {
                val p = proyectar(i.toDouble(), j.toDouble(), 0.0, v)
                val nodo = imanDeReticula(p, v)
                assertEquals("vista $v nodo ($i,$j)", i.toDouble(), nodo.x, 1e-9)
                assertEquals("vista $v nodo ($i,$j)", j.toDouble(), nodo.y, 1e-9)
            }
        }
    }

    /**
     * **El imán devuelve el nodo más cercano de verdad.**
     *
     * Se barre un montón de puntos —con dado de semilla fija, para que un fallo
     * se pueda repetir— y se compara con una búsqueda por fuerza bruta en una
     * ventana ancha de nodos alrededor, midiendo siempre **en pantalla**, que es
     * donde está el dedo. Se comparan distancias y no coordenadas porque en el
     * borde exacto entre dos nodos hay empate y cualquiera de los dos vale.
     */
    @Test
    fun `el imán da el nodo más cercano de verdad`() {
        val dado = Random(31337)
        for (v in vistas) {
            repeat(1000) {
                val p = Pt(dado.nextDouble(-400.0, 400.0), dado.nextDouble(-400.0, 400.0))
                val mio = imanDeReticula(p, v)
                val bruto = porFuerzaBruta(p, v)
                assertEquals(
                    "vista $v punto $p: el imán dio $mio y lo más cerca era $bruto",
                    distancia(bruto, p, v), distancia(mio, p, v), 1e-9
                )
            }
        }
    }

    /**
     * **Y redondeando cada coordenada por separado no salía.**
     *
     * Esta prueba deja constancia del fallo que se evitó, con números: sobre el
     * mismo barrido, el redondeo ingenuo engancha al nodo equivocado en una
     * parte nada pequeña de los casos, y se equivoca por más de un cuarto de
     * casilla. La zona mala es la de la diagonal larga del rombo: allí el nodo
     * que el redondeo elige está más lejos en pantalla que el de la esquina
     * opuesta, porque los ejes del suelo no forman ángulo recto sino 60°.
     */
    @Test
    fun `redondear cada coordenada por separado engancha al nodo equivocado`() {
        val dado = Random(31337)
        var fallos = 0
        var total = 0
        var peor = 0.0
        for (v in vistas) {
            repeat(1000) {
                val p = Pt(dado.nextDouble(-400.0, 400.0), dado.nextDouble(-400.0, 400.0))
                val bueno = imanDeReticula(p, v)
                val ingenuo = porRedondeo(p, v)
                total++
                val exceso = distancia(ingenuo, p, v) - distancia(bueno, p, v)
                if (exceso > 1e-9) {
                    fallos++
                    peor = maxOf(peor, exceso)
                }
            }
        }
        assertTrue("el redondeo ingenuo nunca falló: revisa la prueba", fallos > 0)
        // No es un caso raro de laboratorio: es una de cada varias.
        assertTrue("fallos $fallos de $total", fallos > total / 20)
        // Y cuando falla, falla por una distancia que se ve.
        assertTrue("peor error $peor px", peor > PASO_ISO / 4)

        // El caso concreto, para que se pueda mirar a mano: dentro de la casilla
        // (0,0)-(1,1), el punto (0.45, 0.55) está cerca de la diagonal larga.
        // El redondeo lo manda a (0,1); lo más cercano en pantalla es (0,0).
        val p = proyectar(0.45, 0.55, 0.0, Vista.CERO)
        assertEquals(Pt(0.0, 1.0), porRedondeo(p, Vista.CERO))
        assertEquals(Pt(0.0, 0.0), imanDeReticula(p, Vista.CERO))
        assertTrue(distancia(Pt(0.0, 0.0), p, Vista.CERO) < distancia(Pt(0.0, 1.0), p, Vista.CERO))
    }

    /** Con la retícula más basta, los nodos son los que tocan y sigue siendo el más cercano. */
    @Test
    fun `el imán funciona con otra celda`() {
        val dado = Random(99)
        repeat(300) {
            val p = Pt(dado.nextDouble(-300.0, 300.0), dado.nextDouble(-300.0, 300.0))
            val mio = imanDeReticula(p, Vista.MEDIA, celda = 4.0)
            val bruto = porFuerzaBruta(p, Vista.MEDIA, celda = 4.0)
            assertEquals(distancia(bruto, p, Vista.MEDIA), distancia(mio, p, Vista.MEDIA), 1e-9)
        }
    }

    /** Una celda imposible no engancha, pero tampoco rompe: devuelve el punto del suelo. */
    @Test
    fun `una celda imposible devuelve el suelo sin pegar`() {
        val p = Pt(37.0, -12.0)
        for (celda in listOf(0.0, -1.0, Double.NaN)) {
            val nodo = imanDeReticula(p, Vista.CERO, celda = celda)
            val suelo = desproyectar(p.x, p.y, Vista.CERO)
            assertEquals("celda $celda", suelo.x, nodo.x, tol)
            assertEquals("celda $celda", suelo.y, nodo.y, tol)
        }
    }

    // ---------------------------------------------------------------------
    // Herramientas de la prueba
    // ---------------------------------------------------------------------

    /** Distancia **en pantalla** de un nodo del mundo al dedo. */
    private fun distancia(nodo: Pt, dedo: Pt, vista: Vista, paso: Double = PASO_ISO): Double {
        val p = proyectar(nodo.x, nodo.y, 0.0, vista, paso)
        return hypot(p.x - dedo.x, p.y - dedo.y)
    }

    /** El nodo más cercano mirándolos todos: lento, tonto y sin discusión posible. */
    private fun porFuerzaBruta(dedo: Pt, vista: Vista, celda: Double = 1.0): Pt {
        val suelo = desproyectar(dedo.x, dedo.y, vista)
        val i = round(suelo.x / celda).toInt()
        val j = round(suelo.y / celda).toInt()
        var mejor = Pt(0.0, 0.0)
        var mejorD = Double.MAX_VALUE
        for (a in (i - 5)..(i + 5)) for (b in (j - 5)..(j + 5)) {
            val cand = Pt(a * celda, b * celda)
            val d = distancia(cand, dedo, vista)
            if (d < mejorD) {
                mejorD = d
                mejor = cand
            }
        }
        return mejor
    }

    /** El atajo que está mal: desproyectar y redondear cada coordenada por su cuenta. */
    private fun porRedondeo(dedo: Pt, vista: Vista, celda: Double = 1.0): Pt {
        val suelo = desproyectar(dedo.x, dedo.y, vista)
        return Pt(round(suelo.x / celda) * celda, round(suelo.y / celda) * celda)
    }

    /** Por si alguien cambia el convenio: las constantes son las de 30°. */
    @Test
    fun `las constantes son las de treinta grados`() {
        assertEquals(sqrt(3.0) / 2, AVANCE_H, tol)
        assertEquals(0.5, AVANCE_V, tol)
        // Y juntas dan un paso entero: la isométrica no acorta.
        assertEquals(1.0, hypot(AVANCE_H, AVANCE_V), tol)
        assertTrue(abs(PASO_ISO - PASO_DE_CUADRICULA) < tol)
    }
}
