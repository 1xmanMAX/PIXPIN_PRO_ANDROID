package com.forge.pixpin.motor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Las cuentas de los controles que se manejan arrastrando.
 *
 * Lo que se vigila es lo de siempre en un deslizador: que **no se salga nunca**
 * del rango, que el mismo sitio del dedo dé siempre el mismo valor, y que ir y
 * volver no cambie nada. Un control que se pasa de rango no falla con un error:
 * falla poniendo un estilo que no existe.
 */
class DeslizadoresTest {

    // ---- La bolita que se arrastra ----

    @Test
    fun `sin moverse se queda en la primera`() {
        assertEquals(0, opcionArrastrada(0f, 40f, 3, haciaLaIzquierda = false))
    }

    @Test
    fun `cada paso avanza una opcion`() {
        assertEquals(1, opcionArrastrada(40f, 40f, 3, haciaLaIzquierda = false))
        assertEquals(2, opcionArrastrada(80f, 40f, 3, haciaLaIzquierda = false))
    }

    /** El centro de cada opción es su punto: media casilla ya cuenta. */
    @Test
    fun `se redondea a la opcion mas cercana`() {
        assertEquals(1, opcionArrastrada(25f, 40f, 3, haciaLaIzquierda = false))
        assertEquals(0, opcionArrastrada(15f, 40f, 3, haciaLaIzquierda = false))
    }

    /** Pasarse arrastrando deja en la última: la selección no da la vuelta. */
    @Test
    fun `pasarse no da la vuelta`() {
        assertEquals(2, opcionArrastrada(9999f, 40f, 3, haciaLaIzquierda = false))
        assertEquals(0, opcionArrastrada(-9999f, 40f, 3, haciaLaIzquierda = false))
    }

    /** Con el panel a la derecha se arrastra hacia la izquierda, y eso avanza. */
    @Test
    fun `hacia la izquierda avanza al reves`() {
        assertEquals(1, opcionArrastrada(-40f, 40f, 3, haciaLaIzquierda = true))
        assertEquals(0, opcionArrastrada(40f, 40f, 3, haciaLaIzquierda = true))
    }

    @Test
    fun `sin opciones no hay nada que elegir`() {
        assertEquals(-1, opcionArrastrada(50f, 40f, 0, haciaLaIzquierda = false))
    }

    /**
     * El margen que separa un toque de un arrastre: el dedo nunca se levanta
     * exactamente donde cayó, y sin esto cualquier toque elegiría algo.
     */
    @Test
    fun `un temblor no abre el desplegable`() {
        assertFalse(abreElDesplegable(3f, 8f))
        assertFalse(abreElDesplegable(-3f, 8f))
        assertTrue(abreElDesplegable(9f, 8f))
        assertTrue(abreElDesplegable(-9f, 8f))
    }

    // ---- El deslizador vertical ----

    /** Arriba es más: es como se lee un termómetro. */
    @Test
    fun `arriba es uno y abajo es cero`() {
        assertEquals(1f, fraccionVertical(0f, 100f), 0.001f)
        assertEquals(0f, fraccionVertical(100f, 100f), 0.001f)
        assertEquals(0.5f, fraccionVertical(50f, 100f), 0.001f)
    }

    @Test
    fun `el dedo fuera del recorrido se queda en el tope`() {
        assertEquals(1f, fraccionVertical(-50f, 100f), 0.001f)
        assertEquals(0f, fraccionVertical(300f, 100f), 0.001f)
    }

    @Test
    fun `sin alto no se divide por cero`() {
        assertEquals(0f, fraccionVertical(10f, 0f), 0.001f)
    }

    @Test
    fun `las casillas se reparten el recorrido`() {
        assertEquals(0, casillaDe(0f, 4))
        assertEquals(3, casillaDe(1f, 4))
        assertEquals(1, casillaDe(0.34f, 4))
        assertEquals(2, casillaDe(0.66f, 4))
    }

    /** Ir y volver: la casilla que sale de una fracción vuelve a esa fracción. */
    @Test
    fun `de casilla a fraccion y de vuelta`() {
        val cuantas = 4
        (0 until cuantas).forEach { i ->
            assertEquals(i, casillaDe(fraccionDeLaCasilla(i, cuantas), cuantas))
        }
    }

    @Test
    fun `una sola casilla no revienta`() {
        assertEquals(0, casillaDe(0.5f, 1))
        assertEquals(1f, fraccionDeLaCasilla(0, 1), 0.001f)
    }

    // ---- El valor con paso ----

    @Test
    fun `la opacidad salta de cinco en cinco`() {
        assertEquals(100, valorConPaso(1f, 10, 100, 5))
        assertEquals(10, valorConPaso(0f, 10, 100, 5))
        // 10 + 0.5*90 = 55, que ya es redondo
        assertEquals(55, valorConPaso(0.5f, 10, 100, 5))
        // 10 + 0.51*90 = 55,9 → 55
        assertEquals(55, valorConPaso(0.51f, 10, 100, 5))
    }

    @Test
    fun `el valor nunca se sale del rango`() {
        (0..20).forEach { i ->
            val v = valorConPaso(i / 10f - 0.5f, 10, 100, 5)
            assertTrue("$v fuera de rango", v in 10..100)
        }
    }

    @Test
    fun `de valor a fraccion y de vuelta`() {
        listOf(10, 30, 55, 100).forEach { v ->
            assertEquals(v, valorConPaso(fraccionDelValor(v, 10, 100), 10, 100, 5))
        }
    }

    @Test
    fun `un rango imposible no revienta`() {
        assertEquals(10, valorConPaso(0.5f, 10, 10, 5))
        assertEquals(0f, fraccionDelValor(5, 10, 10), 0.001f)
    }

    // ---- Encontrar el valor puesto ----

    @Test
    fun `encuentra el grosor exacto`() {
        val anchos = listOf(1.0, 2.0, 4.0, 8.0)
        assertEquals(0, masCercano(1.0, anchos))
        assertEquals(3, masCercano(8.0, anchos))
    }

    /**
     * Un dibujo abierto de fuera puede traer un grosor que no está en la lista.
     * El deslizador tiene que colocarse en algún sitio, no saltar al primero.
     */
    @Test
    fun `un grosor de fuera se coloca en el mas parecido`() {
        val anchos = listOf(1.0, 2.0, 4.0, 8.0)
        assertEquals(2, masCercano(3.6, anchos))
        assertEquals(3, masCercano(20.0, anchos))
        assertEquals(0, masCercano(0.1, anchos))
    }

    @Test
    fun `sin valores no hay ninguno cercano`() {
        assertEquals(-1, masCercano(3.0, emptyList()))
    }

    // ---- El grosor, ya continuo ----

    @Test
    fun `los extremos del deslizador son los extremos del grosor`() {
        assertEquals(GROSOR_MINIMO, grosorDeLaFraccion(0f), 0.001)
        assertEquals(GROSOR_MAXIMO, grosorDeLaFraccion(1f), 0.001)
    }

    @Test
    fun `el grosor nunca se sale de sus topes`() {
        (-5..15).forEach { i ->
            val g = grosorDeLaFraccion(i / 10f)
            assertTrue("$g fuera de rango", g in GROSOR_MINIMO..GROSOR_MAXIMO)
        }
    }

    @Test
    fun `subir el mango siempre engorda el trazo`() {
        var anterior = 0.0
        (0..20).forEach { i ->
            val g = grosorDeLaFraccion(i / 20f)
            assertTrue("$g no es mayor que $anterior", g >= anterior)
            anterior = g
        }
    }

    /**
     * A paso limpio: ni un grosor de 3,8172 ni dos valores para el mismo sitio.
     * De dos para arriba, a cuartos; por debajo el paso se afina, que es donde
     * viven los grosores de los planos.
     */
    @Test
    fun `el grosor sale a paso limpio`() {
        (0..40).forEach { i ->
            val g = grosorDeLaFraccion(i / 40f)
            assertEquals("$g no es un centimo limpio", 0.0, (g * 100) % 1, 0.0001)
            if (g >= 2.0) assertEquals("$g no es un cuarto", 0.0, (g * 4) % 1, 0.0001)
        }
    }

    /**
     * La mitad de abajo del recorrido se lleva los finos, que son los que hay
     * que poder afinar: con un reparto lineal, medio deslizador daría grosores
     * que a ojo son el mismo.
     */
    @Test
    fun `los grosores finos ocupan mas recorrido que los gordos`() {
        val mitad = grosorDeLaFraccion(0.5f)
        assertTrue("la mitad da $mitad", mitad < (GROSOR_MINIMO + GROSOR_MAXIMO) / 2)
    }

    @Test
    fun `de grosor a mango y de vuelta`() {
        listOf(0.5, 1.0, 2.0, 4.0, 8.0, GROSOR_MAXIMO).forEach { g ->
            // La tolerancia es el redondeo a cuartos y nada más.
            assertEquals(g, grosorDeLaFraccion(fraccionDelGrosor(g)), 0.13)
        }
    }

    /**
     * Lo que se pidió al probarlo: que no se dispare. A medio recorrido tiene
     * que salir un grosor de escribir, no uno de rotulador gordo.
     */
    @Test
    fun `a medio recorrido sale un grosor fino`() {
        assertTrue(grosorDeLaFraccion(0.5f) <= 4.0)
        assertTrue(grosorDeLaFraccion(0.25f) <= 1.5)
    }

    @Test
    fun `el grosor se escribe sin decimales de mas`() {
        assertEquals("4", grosorEscrito(4.0))
        assertEquals("0,5", grosorEscrito(0.5))
        assertEquals("2,25", grosorEscrito(2.25))
    }

    @Test
    fun `un grosor imposible se queda en el tope`() {
        assertEquals(0f, fraccionDelGrosor(-3.0), 0.001f)
        assertEquals(1f, fraccionDelGrosor(999.0), 0.001f)
    }

    // ---- El arrastre de arriba abajo, que es el del croquis en el espacio ----

    /**
     * **Parte de la que hay puesta, y no de la primera.**
     *
     * Es la diferencia que hace que el gesto sirva de reojo: el botón enseña lo que hay y se
     * sube o se baja desde ahí. Midiendo desde la primera, el mismo movimiento daría cosas
     * distintas según lo que estuviera elegido.
     */
    @Test
    fun `el arrastre vertical cuenta desde donde estaba`() {
        assertEquals(2, opcionArrastradaAbajo(0f, 40f, 5, desde = 2))
        assertEquals(3, opcionArrastradaAbajo(40f, 40f, 5, desde = 2))
        assertEquals(1, opcionArrastradaAbajo(-40f, 40f, 5, desde = 2))
        assertEquals(4, opcionArrastradaAbajo(85f, 40f, 5, desde = 2))
    }

    /** Y no se sale de la lista: empujar hasta el final deja en el último. */
    @Test
    fun `el arrastre vertical no se sale de la lista`() {
        assertEquals(4, opcionArrastradaAbajo(9999f, 40f, 5, desde = 2))
        assertEquals(0, opcionArrastradaAbajo(-9999f, 40f, 5, desde = 2))
        assertEquals(-1, opcionArrastradaAbajo(50f, 40f, 0, desde = 0))
        // Con un paso imposible se queda donde estaba, sin dividir por cero.
        assertEquals(2, opcionArrastradaAbajo(50f, 0f, 5, desde = 2))
    }

    // ---- Los mandos que se posan y se arrastran ----

    /**
     * **El tono sale de hacia dónde va el dedo, en ángulos de pantalla.**
     *
     * Es lo que hace que la rueda que se ve y el color que sale sean lo mismo: el barrido de
     * Compose reparte los tonos desde las tres y con las agujas del reloj, así que el tono
     * que se pide yendo hacia abajo tiene que ser el que está pintado abajo.
     */
    @Test
    fun `el tono sale del angulo del arrastre`() {
        assertEquals(0f, tonoDelArrastre(10f, 0f), 0.01f)
        // Hacia abajo, que en la pantalla es noventa grados con las agujas del reloj.
        assertEquals(90f, tonoDelArrastre(0f, -10f), 0.01f)
        assertEquals(180f, tonoDelArrastre(-10f, 0f), 0.01f)
        // Y hacia arriba: nunca sale un ángulo negativo, siempre la vuelta entera.
        assertEquals(270f, tonoDelArrastre(0f, 10f), 0.01f)
    }

    /** Y lo viva, de lo lejos que se ha ido: en el centro nada, en el borde a tope. */
    @Test
    fun `la viveza sale de lo lejos que va el dedo`() {
        assertEquals(0f, vivezaDelArrastre(0f, 0f, 66f), 0.001f)
        assertEquals(0.5f, vivezaDelArrastre(33f, 0f, 66f), 0.001f)
        assertEquals(1f, vivezaDelArrastre(66f, 0f, 66f), 0.001f)
        // Pasarse no da más de uno: fuera de la rueda el color ya está a tope.
        assertEquals(1f, vivezaDelArrastre(500f, 500f, 66f), 0.001f)
        assertEquals(0f, vivezaDelArrastre(10f, 10f, 0f), 0.001f)
    }

    /**
     * **El grosor va por veces y no a partes iguales.**
     *
     * Lo que se comprueba es justamente eso: el mismo trozo de recorrido multiplica lo mismo
     * esté donde esté el mando. Repartido a partes iguales, subir un dedo desde dos daría el
     * mismo salto que subirlo desde quince, y eso es lo que hacía imposible afinar los finos.
     */
    @Test
    fun `el grosor sube por veces`() {
        assertEquals(2.0, grosorArrastrado(2.0, 0f), 0.001)
        val desdeDos = grosorArrastrado(2.0, 32f)
        val desdeCuatro = grosorArrastrado(4.0, 32f)
        // El mismo recorrido, el doble de grueso de partida: el doble de resultado.
        assertEquals(desdeDos * 2, desdeCuatro, 0.26)
        // Y bajar deshace lo que sube.
        assertEquals(2.0, grosorArrastrado(grosorArrastrado(2.0, 40f), -40f), 0.26)
    }

    /** Ni más fino que lo más fino ni más gordo que lo más gordo, se arrastre lo que se arrastre. */
    @Test
    fun `el grosor no se sale de sus topes`() {
        assertEquals(GROSOR_MAXIMO, grosorArrastrado(2.0, 9999f), 0.001)
        assertEquals(GROSOR_MINIMO, grosorArrastrado(2.0, -9999f), 0.001)
        // Un dedo entero de recorrido cubre la escala de dibujar de siempre
        // (medio punto a veinte). Los finos de plano de por debajo son
        // recorrido extra: al bajar el suelo a 0,01 la escala completa ya no
        // cabe en un gesto, y apretarla toda ahí dejaría el mando sin pulso
        // justo en los grosores que más se afinan.
        assertTrue(grosorArrastrado(0.5, RECORRIDO_DEL_MANDO) > GROSOR_MAXIMO * 0.9)
    }

    /** Lo que tapa sí es una recta, y de cinco en cinco. */
    @Test
    fun `lo que tapa sube en recta y a saltos de cinco`() {
        assertEquals(50, opacidadArrastrada(50, 0f))
        assertEquals(100, opacidadArrastrada(50, RECORRIDO_DEL_MANDO / 2))
        assertEquals(0, opacidadArrastrada(50, -RECORRIDO_DEL_MANDO / 2))
        assertEquals(100, opacidadArrastrada(50, 9999f))
        assertEquals(0, opacidadArrastrada(50, -9999f))
        // Redondo, siempre: el mismo sitio del dedo da siempre el mismo número.
        assertEquals(0, opacidadArrastrada(50, RECORRIDO_DEL_MANDO * 0.07f) % 5)
    }

    // ---- La letra y las marcas de la rueda ----

    /** La letra sube por veces, como el grosor: de 8 a 10 se ve y de 80 a 82 no. */
    @Test
    fun `el tamano de letra sube por veces`() {
        assertEquals(20.0, tamanoDeLetraArrastrado(20.0, 0f), 1e-9)
        val desdeVeinte = tamanoDeLetraArrastrado(20.0, 48f)
        val desdeCuarenta = tamanoDeLetraArrastrado(40.0, 48f)
        assertEquals(desdeVeinte * 2, desdeCuarenta, 2.0)
        // Sale redonda: nadie ha pedido una letra de 23,7.
        assertEquals(desdeVeinte, kotlin.math.round(desdeVeinte), 1e-9)
    }

    /** Y no se sale de sus topes, se arrastre lo que se arrastre. */
    @Test
    fun `el tamano de letra no se sale de sus topes`() {
        assertEquals(LETRA_MAXIMA, tamanoDeLetraArrastrado(20.0, 9999f), 1e-9)
        assertEquals(LETRA_MINIMA, tamanoDeLetraArrastrado(20.0, -9999f), 1e-9)
        assertTrue(tamanoDeLetraArrastrado(LETRA_MINIMA, RECORRIDO_DEL_MANDO) > LETRA_MAXIMA * 0.9)
    }

    /**
     * **Una marca cae donde la rueda pinta su color**, y no en el espejo.
     *
     * El tono es el ángulo contado con las agujas del reloj desde las tres, que es como los
     * reparte el barrido de Compose y como los lee [tonoDelArrastre]. Si esto se midiera al
     * revés, el punto de una marca saldría en el tono contrario al suyo.
     */
    @Test
    fun `una marca cae donde esta pintado su tono`() {
        // El rojo, a las tres.
        val rojo = enLaRueda(0f, 1f, 60f)
        assertEquals(60.0, rojo.x, 1e-6)
        assertEquals(0.0, rojo.y, 1e-6)
        // Y a noventa grados, abajo: en la pantalla la `y` crece hacia abajo.
        val abajo = enLaRueda(90f, 1f, 60f)
        assertEquals(0.0, abajo.x, 1e-6)
        assertEquals(60.0, abajo.y, 1e-6)
        // En el centro no hay tono que valga: la viveza a cero lo deja en el medio.
        val medio = enLaRueda(210f, 0f, 60f)
        assertEquals(0.0, medio.x, 1e-6)
        assertEquals(0.0, medio.y, 1e-6)
        // Y lo que se pide es lo mismo que se lee: el ángulo de vuelta es el tono.
        assertEquals(210f, tonoDelArrastre(enLaRueda(210f, 1f, 60f).x.toFloat(),
            -enLaRueda(210f, 1f, 60f).y.toFloat()), 0.01f)
    }

    /** El imán coge la marca por la que se pasa cerca, y ninguna si no se pasa. */
    @Test
    fun `el iman coge la marca por la que pasa el dedo`() {
        val marcas = listOf(0f to 1f, 180f to 1f)
        // Justo encima de la primera: el rojo está a las tres, a un radio del centro.
        assertEquals(0, marcaImantada(60f, 0f, 60f, marcas))
        // Y de la segunda, enfrente.
        assertEquals(1, marcaImantada(-60f, 0f, 60f, marcas))
        // Cerca, pero no tanto: no se pega.
        assertEquals(-1, marcaImantada(0f, 0f, 60f, marcas))
        // Sin marcas no hay nada que coger.
        assertEquals(-1, marcaImantada(60f, 0f, 60f, emptyList()))
    }

    /**
     * **El disco pintado mide exactamente el radio con el que se mide el arrastre.**
     *
     * Aquí estuvo el fallo, y es de los que no se ven mirando el código de uno en uno: la
     * sombra iba en píxeles y se le **restaba al radio**, así que el disco salía un pelo más
     * pequeño que [RADIO_DE_LA_RUEDA] — que es contra lo que el arrastre divide para saber lo
     * viva que sale la tinta. Dos radios distintos para la misma rueda dan dos cosas raras, y
     * las dos se notaban:
     *
     * - el punto que elige no queda bajo el lápiz, y el hueco **crece** conforme te alejas
     *   del centro: cero en el medio y varios píxeles en el borde;
     * - y la tinta que sale no es la de debajo del dedo, sino una parecida.
     *
     * Con la sombra por fuera —sumada al tamaño en vez de restada al radio— el disco vuelve a
     * medir lo que dice. Esta prueba es lo que impide que alguien la vuelva a restar.
     */
    @Test
    fun `el disco de la rueda mide el radio con el que se mide el arrastre`() {
        assertEquals(
            RADIO_DE_LA_RUEDA,
            (LADO_DE_LA_RUEDA / 2 - SOMBRA_DE_LA_RUEDA).value,
            1e-4f
        )
    }

    /**
     * **Y el punto cae justo donde está el dedo**, en toda la rueda y no solo cerca del medio.
     *
     * Es la otra mitad de lo mismo: el dedo entra como posición y sale como tono y viveza, y
     * de ahí vuelve a salir una posición para pintar el punto. Si esa ida y vuelta no es la
     * identidad, el punto se despega del lápiz. Se prueba a varias distancias porque el fallo
     * que hubo **no se veía en el centro**: crecía con el radio, así que una prueba con un
     * solo punto cerca del medio lo habría dado por bueno.
     */
    @Test
    fun `el punto de la rueda cae bajo el dedo a cualquier distancia`() {
        val radio = RADIO_DE_LA_RUEDA
        for (lado in listOf(-60f, -33f, -5f, 5f, 33f, 60f)) {
            for (subida in listOf(-25f, 0f, 25f)) {
                if (kotlin.math.hypot(lado, subida) > radio) continue
                val tono = tonoDelArrastre(lado, subida)
                val viveza = vivezaDelArrastre(lado, subida, radio)
                val donde = enLaRueda(tono, viveza, radio)
                // `enLaRueda` devuelve en coordenadas de pantalla: la `y` crece hacia abajo,
                // y la subida del dedo va al revés.
                assertEquals("x en ($lado, $subida)", lado.toDouble(), donde.x, 1e-3)
                assertEquals("y en ($lado, $subida)", -subida.toDouble(), donde.y, 1e-3)
            }
        }
    }

    /** El brillo es una recta de apagada a tope, y no se sale de ahí. */
    @Test
    fun `el brillo va de apagado a tope`() {
        assertEquals(0.5, brilloArrastrado(0.5, 0f), 1e-9)
        assertEquals(1.0, brilloArrastrado(0.5, RECORRIDO_DEL_MANDO / 2), 1e-9)
        assertEquals(0.0, brilloArrastrado(0.5, -RECORRIDO_DEL_MANDO / 2), 1e-9)
        assertEquals(1.0, brilloArrastrado(0.5, 9999f), 1e-9)
        assertEquals(0.0, brilloArrastrado(0.5, -9999f), 1e-9)
    }
}
