package com.forge.pixpin.ia

import com.forge.pixpin.ia.Complejidad.Motivo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Los casos de `complejidad.rs` del PC. */
class ComplejidadTest {
    private val simples = listOf(
        "dibuja un circulo rojo", "Dibuja una casa con un árbol", "anota esto: llamar a Juan", "abre la galería",
        "busca mis notas de la tesis", "apunta comprar pan mañana", "hola", "¿qué hora es?",
        "¿cómo se cambia el color de fondo?", "pon un título que diga Plano de la casa", "dibuja una flecha de A a B y pon un texto",
        "calcula el 15% de 80", "pasa en limpio este problema", "haz una gráfica de barras con 3, 5 y 7",
        "dibuja un diagrama de cuerpo libre", "¿qué piensas de este color?", "crea una tarea para el lunes",
        "traduce hola al inglés", "resume este mensaje", "busca el problema 3 en mis notas"
    )
    private val complejos = listOf(
        "piensa bien cómo distribuir los muebles" to Motivo.PEDIDO,
        "explícame paso a paso la regla de la cadena" to Motivo.PEDIDO,
        "razona la respuesta" to Motivo.PEDIDO,
        "resuelve x^2 - 5x + 6 = 0" to Motivo.PROBLEMA,
        "resuélvelo" to Motivo.PROBLEMA,
        "Demuestra que la raíz de 2 es irracional" to Motivo.PROBLEMA,
        "calcula la aceleración de un bloque de 5 kg en un plano inclinado de 30°" to Motivo.PROBLEMA,
        "Un tren sale a las 8 a 80 km/h y otro a las 9 a 100 km/h por la misma vía, ¿cuándo lo alcanza?" to Motivo.PROBLEMA,
        "halla la derivada de sin(x)·x²" to Motivo.PROBLEMA,
        "planifica la obra de la casa en 12 semanas" to Motivo.PLANIFICAR,
        "compara estas dos vigas" to Motivo.PLANIFICAR,
        "hazme un plan de estudio para el examen" to Motivo.PLANIFICAR,
        "pasa toda la hoja al lienzo" to Motivo.ENTERO,
        "revisa el plano entero y marca los errores" to Motivo.ENTERO,
        "dibuja un rectángulo, ponle un título y colorea el fondo de azul" to Motivo.PASOS,
        "primero dibuja el plano de la planta baja con sus cuatro habitaciones y luego pon las cotas de cada pared en metros" to Motivo.PASOS,
        "1. abre el plano\n2. marca las puertas\n3. exporta a PDF" to Motivo.PASOS
    )

    @Test fun `lo simple no piensa`() { for (s in simples) { assertNull(s, Complejidad.clasificar(s)); assertFalse(Complejidad.decidir(null, s)) } }

    @Test fun `lo complejo piensa y dice por que`() { for ((s, m) in complejos) { assertEquals(s, m, Complejidad.clasificar(s)); assertTrue(Complejidad.decidir(null, s)) } }

    @Test fun `un pedido largo piensa`() {
        val largo = "quiero que me cuentes ".repeat(16)
        assertEquals(Motivo.LARGO, Complejidad.clasificar(largo))
        assertNull(Complejidad.clasificar("quiero que me cuentes"))
    }

    @Test fun `decir que no piense manda`() {
        assertNull(Complejidad.clasificar("resuelve x+1=2 sin pensar"))
        assertNull(Complejidad.clasificar("no pienses, dibuja una casa"))
        assertNull(Complejidad.clasificar("no pienses mucho"))
    }

    @Test fun `siempre y nunca mandan`() {
        assertTrue(Complejidad.decidir(true, "hola"))
        assertFalse(Complejidad.decidir(false, "resuelve x^2 = 4 paso a paso"))
        assertTrue(Complejidad.decidir(null, "resuelve x^2 = 4"))
    }

    @Test fun `las ordenes no se confunden`() {
        assertTrue(Complejidad.esOrden("ponle") && Complejidad.esOrden("dibujalo") && Complejidad.esOrden("crea"))
        assertFalse(Complejidad.esOrden("creativo") || Complejidad.esOrden("pone") || Complejidad.esOrden("dibujante"))
    }
}
