package com.forge.pixpin

import com.forge.pixpin.guardados.ALTO_MAXIMO_DE_BARRA_DP
import com.forge.pixpin.guardados.GRUESO_DE_BARRA_DP
import com.forge.pixpin.guardados.MEDIO_ALTO_DE_BARRA_DP
import com.forge.pixpin.guardados.VueltaDeCarga
import com.forge.pixpin.guardados.altoDeBarraDp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **El reproductor de voz, en la parte que se puede comprobar sin pantalla.**
 *
 * La geometría de la barra y la vuelta del trazo de carga son los dos sitios donde un
 * error no da excepción ni falla la compilación: sale una onda plana, o un trazo que
 * parpadea cada 5,4 segundos. Los dos son números puros, así que se comprueban aquí.
 *
 * Los valores esperados salen de Telegram, leídos en la fuente el 8-sep-2026, y están
 * citados con su línea en `Onda.kt` y `VueltaDeCarga.kt`.
 */
class ReproductorDeVozTest {

    // ---- La barra: alto = 2·h + 2, con h de 0 a 7 ----

    @Test
    fun `el silencio no es una barra de cero, sino de dos puntos`() {
        // Una barra de 0 no se dibujaría y la fila quedaría con huecos, como a medio
        // cargar. Es la razón de que el grueso se sume a los dos lados.
        assertEquals(GRUESO_DE_BARRA_DP, altoDeBarraDp(0f), 0.001f)
    }

    @Test
    fun `la barra mas alta mide dieciseis, que es lo que mide la franja al pintar`() {
        assertEquals(16f, altoDeBarraDp(1f), 0.001f)
        assertEquals(16f, ALTO_MAXIMO_DE_BARRA_DP, 0.001f)
        // Y sale de 2·7 + 2, no de un 16 escrito a mano en otro sitio.
        assertEquals(2f * MEDIO_ALTO_DE_BARRA_DP + GRUESO_DE_BARRA_DP, ALTO_MAXIMO_DE_BARRA_DP, 0.001f)
    }

    @Test
    fun `a la mitad, la barra mide nueve`() {
        assertEquals(9f, altoDeBarraDp(0.5f), 0.001f)
    }

    @Test
    fun `un valor imposible no se sale de la franja`() {
        // Un pico corrupto en un mensaje viejo dibujaría una barra encima del texto de al
        // lado. Se recorta aquí, que es donde se sabe cuál es el tope.
        assertEquals(GRUESO_DE_BARRA_DP, altoDeBarraDp(-3f), 0.001f)
        assertEquals(ALTO_MAXIMO_DE_BARRA_DP, altoDeBarraDp(7f), 0.001f)
    }

    // ---- La curva FastOutSlowIn resuelta en vez de tabulada ----

    @Test
    fun `la curva empieza en cero, acaba en uno y no se sale nunca`() {
        assertEquals(0f, VueltaDeCarga.suave(0f), 0.0001f)
        assertEquals(1f, VueltaDeCarga.suave(1f), 0.0001f)
        var x = 0f
        while (x <= 1f) {
            val y = VueltaDeCarga.suave(x)
            assertTrue("suave($x) = $y se sale de 0..1", y in 0f..1f)
            x += 0.01f
        }
    }

    @Test
    fun `la curva no retrocede nunca`() {
        // Si retrocediera, el trazo se encogería a mitad de tramo: un tirón visible.
        var anterior = -1f
        var x = 0f
        while (x <= 1f) {
            val y = VueltaDeCarga.suave(x)
            assertTrue("suave baja en $x: $anterior -> $y", y >= anterior - 0.0001f)
            anterior = y
            x += 0.005f
        }
    }

    @Test
    fun `fuera del tramo la curva contesta lo unico razonable`() {
        // Le llegan tiempos negativos —tramos que no han empezado— y mayores que uno.
        assertEquals(0f, VueltaDeCarga.suave(-5f), 0.0001f)
        assertEquals(1f, VueltaDeCarga.suave(9f), 0.0001f)
    }

    @Test
    fun `es de verdad la bezier de Material, no una recta`() {
        // La (0,4 · 0 · 0,2 · 1) arranca despacio y termina muy adelantada: a la mitad del
        // tiempo lleva ya el 77,6 % del recorrido. Si aquí saliera 0,5 es que se ha colado
        // una interpolación lineal, y el trazo se movería a velocidad constante.
        assertEquals(0.776f, VueltaDeCarga.suave(0.5f), 0.005f)
        assertEquals(0.237f, VueltaDeCarga.suave(0.25f), 0.005f)
        assertEquals(0.959f, VueltaDeCarga.suave(0.75f), 0.005f)
    }

    // ---- La vuelta del trazo ----

    @Test
    fun `el trazo nunca se queda sin largo`() {
        // Este es el fallo que se vería: un parpadeo justo al cerrar el ciclo.
        for (ms in 0L until VueltaDeCarga.CICLO_MS step 7L) {
            val (a, b) = VueltaDeCarga.trazo(ms)
            assertTrue("en $ms ms el trazo mide ${b - a}", b - a >= VueltaDeCarga.TRAZO_MINIMO - 0.001f)
        }
    }

    @Test
    fun `y nunca da mas de una vuelta entera`() {
        // Si pasara de 360 habría que partirlo en tres trozos y el que pinta solo parte
        // en dos. Que no pase está garantizado por la cuenta, y esto lo fija.
        for (ms in 0L until VueltaDeCarga.CICLO_MS step 7L) {
            val (a, b) = VueltaDeCarga.trazo(ms)
            assertTrue("en $ms ms el trazo mide ${b - a}", b - a < 360f)
        }
    }

    @Test
    fun `el trazo avanza siempre hacia delante`() {
        var anterior = Float.NEGATIVE_INFINITY
        for (ms in 0L until VueltaDeCarga.CICLO_MS step 13L) {
            val cabeza = VueltaDeCarga.trazo(ms).second
            assertTrue("la cabeza retrocede en $ms ms", cabeza >= anterior - 0.001f)
            anterior = cabeza
        }
    }

    @Test
    fun `en un ciclo entero da las vueltas que dice Telegram`() {
        // 1.520° de base más cuatro estirones de 250 = 2.520°, o sea siete vueltas al
        // botón cada 5,4 segundos. Es la animación indeterminada de Material.
        //
        // **Se mide en la cola, no en la cabeza**, y esto no es un detalle: justo al
        // cerrar el ciclo la cabeza ha alcanzado a la cola —el trazo se ha encogido a
        // 20°— y entra el largo mínimo de 40, que la empuja 20° por delante de donde le
        // tocaría. La cola es la que lleva la cuenta limpia.
        val esperado = VueltaDeCarga.GIRO_POR_CICLO + 4 * VueltaDeCarga.ESTIRON
        val recorrido = VueltaDeCarga.trazo(VueltaDeCarga.CICLO_MS - 1).first -
            VueltaDeCarga.trazo(0).first
        assertEquals(esperado, recorrido, 2f)
        assertEquals(7f, esperado / 360f, 0.01f)
    }

    @Test
    fun `el trazo va de cuarenta a doscientos setenta grados`() {
        // Los dos extremos, medidos. El de arriba es el que hace que la comprobación de
        // «nunca da una vuelta entera» signifique algo: 270 es holgado, pero es real, no
        // un número que nadie alcanza.
        var menor = Float.MAX_VALUE
        var mayor = 0f
        for (ms in 0L until VueltaDeCarga.CICLO_MS) {
            val (a, b) = VueltaDeCarga.trazo(ms)
            menor = minOf(menor, b - a)
            mayor = maxOf(mayor, b - a)
        }
        assertEquals(VueltaDeCarga.TRAZO_MINIMO, menor, 0.001f)
        assertEquals(VueltaDeCarga.TRAZO_INICIAL + VueltaDeCarga.ESTIRON, mayor, 0.5f)
    }

    @Test
    fun `el ciclo se repite exactamente`() {
        // El módulo tiene que cerrar: si no, tras un minuto de espera la animación
        // iría por otro sitio que al principio.
        for (ms in 0L until VueltaDeCarga.CICLO_MS step 137L) {
            val uno = VueltaDeCarga.trazo(ms)
            val otro = VueltaDeCarga.trazo(ms + VueltaDeCarga.CICLO_MS * 3)
            assertEquals(uno.first, otro.first, 0.001f)
            assertEquals(uno.second, otro.second, 0.001f)
        }
    }
}
