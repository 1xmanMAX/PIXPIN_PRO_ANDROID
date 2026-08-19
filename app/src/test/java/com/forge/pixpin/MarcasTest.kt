package com.forge.pixpin.motor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Las marcas del deslizador: **volver a un valor tiene que ser un gesto, no puntería**.
 *
 * Lo que se comprueba aquí es lo que convierte la ayuda en estorbo si se hace mal: que el
 * imán tire solo cuando toca, que no se puedan guardar dos marcas encima —una marca que
 * no se puede quitar porque siempre agarras la otra— y que el orden no dependa de cómo se
 * fueron poniendo.
 */
class MarcasTest {

    @Test
    fun `sin marcas el valor pasa tal cual`() {
        assertEquals(0.42f, conIman(0.42f, emptyList()), 0.0001f)
    }

    /** Cerca se pega; lejos no. Esa es toda la promesa. */
    @Test
    fun `el iman tira solo de cerca`() {
        val marcas = listOf(0.5f)
        assertEquals("no se ha pegado", 0.5f, conIman(0.49f, marcas), 0.0001f)
        assertEquals("ha tirado de lejos", 0.30f, conIman(0.30f, marcas), 0.0001f)
    }

    /** Con dos marcas cerca gana **la más cercana**, no la primera de la lista. */
    @Test
    fun `gana la marca mas cercana`() {
        val marcas = listOf(0.50f, 0.53f)
        assertEquals(0.53f, conIman(0.525f, marcas, margen = 0.05f), 0.0001f)
        assertEquals(0.50f, conIman(0.505f, marcas, margen = 0.05f), 0.0001f)
    }

    /** Y da igual en qué orden estén guardadas. */
    @Test
    fun `el orden de la lista no cambia el resultado`() {
        val a = conIman(0.525f, listOf(0.50f, 0.53f), margen = 0.05f)
        val b = conIman(0.525f, listOf(0.53f, 0.50f), margen = 0.05f)
        assertEquals(a, b, 0.0001f)
    }

    @Test
    fun `poner una marca la deja guardada y ordenada`() {
        val marcas = conMarca(conMarca(emptyList(), 0.8f), 0.2f)
        assertEquals(listOf(0.2f, 0.8f), marcas)
    }

    /** Dos marcas pegadas serían una marca imposible de quitar. */
    @Test
    fun `no se guardan dos marcas encima`() {
        val marcas = conMarca(listOf(0.5f), 0.505f)
        assertEquals(1, marcas.size)
    }

    /** Ni más de las que caben: una barra llena de topes deja de ser continua. */
    @Test
    fun `hay un tope de marcas`() {
        var marcas = emptyList<Float>()
        for (i in 0 until 20) marcas = conMarca(marcas, i / 20f)
        assertEquals(MARCAS_POR_DESLIZADOR, marcas.size)
    }

    @Test
    fun `una marca se quita por donde se puso`() {
        val marcas = conMarca(emptyList(), 0.4f)
        assertEquals(0.4f, marcaEn(marcas, 0.41f)!!, 0.0001f)
        assertTrue(sinMarca(marcas, 0.41f).isEmpty())
        assertNull(marcaEn(emptyList(), 0.4f))
    }

    /** Quitar donde no hay nada no se lleva nada por delante. */
    @Test
    fun `quitar en el vacio no toca las demas`() {
        val marcas = listOf(0.2f, 0.8f)
        assertEquals(marcas, sinMarca(marcas, 0.5f))
    }

    // ---- Dónde se pinta ------------------------------------------------

    /**
     * La marca se pinta **donde está el mango**, no donde diría la cuenta corta.
     *
     * El mango mide lo suyo y no se sale por los topes, así que recorre el alto menos su
     * propio tamaño. Con `alto · (1 − f)` la marca queda hasta medio mango más arriba —y
     * una marca que no está donde se puso no sirve de nada.
     */
    @Test
    fun `la marca cae en el centro del mango`() {
        val alto = 400f
        val mango = 28f
        // Abajo del todo: el centro del mango está a medio mango del suelo.
        assertEquals(alto - mango / 2, yDelMango(0f, alto, mango), 0.01f)
        // Arriba del todo: a medio mango del techo.
        assertEquals(mango / 2, yDelMango(1f, alto, mango), 0.01f)
        // Y a la mitad, en el centro de la barra.
        assertEquals(alto / 2, yDelMango(0.5f, alto, mango), 0.01f)
    }

    /** Tocar el mango es tocar el mango: ni la barra entera ni un punto. */
    @Test
    fun `el mango se toca solo donde esta`() {
        val alto = 400f
        val mango = 28f
        val y = yDelMango(0.5f, alto, mango)
        assertTrue(tocaElMango(y, alto, 0.5f, mango))
        assertTrue(tocaElMango(y + 13f, alto, 0.5f, mango))
        assertTrue(!tocaElMango(y + 20f, alto, 0.5f, mango))
    }

    // ---- Guardar y recuperar -------------------------------------------

    /** Lo guardado se recupera igual: es lo único que se le pide al formato. */
    @Test
    fun `las marcas van y vuelven del texto`() {
        val marcas = mapOf(
            Deslizador.GROSOR to listOf(0.2f, 0.55f),
            Deslizador.OPACIDAD to listOf(0.8f)
        )
        assertEquals(marcas, marcasDeTexto(marcasATexto(marcas)))
    }

    /** Un texto roto no puede dejar la aplicación sin abrir: se ignora y ya. */
    @Test
    fun `un texto estropeado no rompe nada`() {
        assertTrue(marcasDeTexto("").isEmpty())
        assertTrue(marcasDeTexto("basura").isEmpty())
        assertTrue(marcasDeTexto("loquesea:0.5").isEmpty())
        assertTrue(marcasDeTexto("grosor:abc").isEmpty())
        // Y lo que sí se entiende, se conserva aunque lo demás sobre.
        assertEquals(listOf(0.5f), marcasDeTexto("basura|grosor:0.5,9,abc")[Deslizador.GROSOR])
    }

    /** Fuera de la barra no hay marcas que valgan. */
    @Test
    fun `las marcas se quedan dentro del recorrido`() {
        assertEquals(listOf(1f), conMarca(emptyList(), 3f))
        assertEquals(listOf(0f), conMarca(emptyList(), -2f))
    }
}
