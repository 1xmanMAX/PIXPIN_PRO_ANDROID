package com.forge.pixpin.guardados

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Encontrar un enlace dentro de algo escrito por una persona.
 *
 * Todos los fallos de aquí son del mismo tipo: el enlace se abre con un carácter de más y
 * la página no existe. No revienta nada, simplemente no funciona, y quien lo toca cree que
 * el enlace estaba mal guardado.
 */
class EnlacesTest {

    @Test
    fun `una direccion suelta se reconoce`() {
        val e = primerEnlace("https://github.com/forge/pixpin")!!
        assertEquals("github.com", e.donde)
        assertEquals("forge / pixpin", e.deQue)
        assertEquals("https://github.com/forge/pixpin", e.url)
    }

    @Test
    fun `dentro de una frase tambien`() {
        val e = primerEnlace("mira esto https://ejemplo.com/cosa que está bien")!!
        assertEquals("ejemplo.com", e.donde)
        assertEquals("cosa", e.deQue)
    }

    /** El punto de acabar la frase no es del enlace. */
    @Test
    fun `el punto final no entra en la direccion`() {
        assertEquals("https://ejemplo.com/cosa", primerEnlace("mira https://ejemplo.com/cosa.")!!.url)
    }

    @Test
    fun `la coma y los dos puntos tampoco`() {
        assertEquals("https://ejemplo.com", primerEnlace("https://ejemplo.com, y luego")!!.url)
        assertEquals("https://ejemplo.com", primerEnlace("https://ejemplo.com: mira")!!.url)
    }

    /** Un enlace entre paréntesis: el que cierra es de la frase, no suyo. */
    @Test
    fun `el parentesis de la frase se queda fuera`() {
        assertEquals(
            "https://ejemplo.com/x",
            primerEnlace("lo puse (https://ejemplo.com/x) ahí")!!.url
        )
    }

    /** Pero el paréntesis que abre dentro del enlace hace suyo al que cierra. */
    @Test
    fun `un parentesis abierto dentro se conserva`() {
        val e = primerEnlace("https://es.wikipedia.org/wiki/Pin_(sujeción))")!!
        assertEquals("https://es.wikipedia.org/wiki/Pin_(sujeción)", e.url)
    }

    @Test
    fun `sin protocolo pero con www vale`() {
        val e = primerEnlace("entra en www.ejemplo.com/algo")!!
        assertEquals("ejemplo.com", e.donde)
        assertEquals("https://www.ejemplo.com/algo", e.url)
    }

    @Test
    fun `el primero es el que manda`() {
        val e = primerEnlace("https://uno.com y https://dos.com")!!
        assertEquals("uno.com", e.donde)
    }

    @Test
    fun `un texto sin enlaces no inventa ninguno`() {
        assertNull(primerEnlace("esto no lleva ninguna dirección"))
        assertNull(primerEnlace(""))
    }

    /** Una palabra con protocolo pero sin punto no es una dirección. */
    @Test
    fun `algo que parece un enlace pero no lo es`() {
        assertNull(primerEnlace("https://localhost"))
    }

    @Test
    fun `el dominio se lee en minusculas`() {
        assertEquals("ejemplo.com", primerEnlace("HTTPS://Ejemplo.COM/Cosa")!!.donde)
    }

    /** Los parámetros no van en lo que se enseña: son ruido que no dice de qué va. */
    @Test
    fun `la parte de las preguntas no se enseña`() {
        val e = primerEnlace("https://ejemplo.com/buscar?q=algo&x=1")!!
        assertEquals("buscar", e.deQue)
        assertEquals("https://ejemplo.com/buscar?q=algo&x=1", e.url)
    }

    @Test
    fun `una direccion sin camino se queda sin la segunda linea`() {
        val e = primerEnlace("https://ejemplo.com")!!
        assertEquals("ejemplo.com", e.donde)
        assertEquals("", e.deQue)
    }
}
