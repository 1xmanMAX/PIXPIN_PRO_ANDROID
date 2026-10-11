package com.forge.pixpin.motor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El cronograma, **retirado del lienzo pero no de los dibujos** (11-oct-2026).
 *
 * Ya no hay herramienta para ponerlo ni ajustes para editarlo. Lo que se comprueba es lo
 * que no se puede perder: que un dibujo que ya trae uno —hecho antes aquí, o en otro
 * aparato que aún lo tenga— **se abre, se pinta y se vuelve a guardar sin perder nada**:
 * ni sus filas, ni sus columnas, ni el color de cada barra, ni su sitio en el orden.
 * Es lo mismo que hace el PC desde 11bd1f6, que lo devuelve intacto por su carril ajeno.
 */
class CronogramaTest {

    private val tol = 1e-9

    /** El mismo dibujo que usa la prueba del PC: un rectángulo, el cronograma y un texto. */
    private val dibujoConCronograma = """
        {"elements":[
          {"id":"a1","type":"rectangle","x":0,"y":0,"width":50,"height":30,
           "strokeColor":"#1e1e1e","seed":7},
          {"id":"c1","type":"pixpin-gantt","x":10,"y":20,"width":400,"height":200,
           "strokeColor":"#1e1e1e","seed":11,"groupIds":["g2"],"periodos":8,"fontFamily":2,
           "fontSize":20,
           "tareas":[{"nombre":"Obra","desde":0,"cuanto":2.5},
                     {"nombre":"Acabados","desde":2.5,"cuanto":1,"color":"#e03131"}]},
          {"id":"t1","type":"text","x":5,"y":300,"width":80,"height":25,
           "text":"Hola","fontSize":20,"seed":3}
        ]}
    """.trimIndent()

    private fun comprobarElCronograma(escena: Scene) {
        assertEquals(listOf("a1", "c1", "t1"), escena.elements.map { it.id })
        val plan = escena.elements[1]
        assertEquals(ElementType.CRONOGRAMA, plan.type)
        assertEquals(8, plan.periodos)
        assertEquals(listOf("g2"), plan.groupIds)
        assertEquals(20.0, plan.fontSize!!, tol)
        assertEquals(
            listOf(
                TareaDelCronograma("Obra", 0.0, 2.5),
                TareaDelCronograma("Acabados", 2.5, 1.0, "#e03131")
            ),
            plan.tareas
        )
    }

    @Test
    fun `un dibujo con cronograma se abre y se vuelve a guardar sin perderlo`() {
        val leida = ExcalidrawJson.decodeFromString<Scene>(dibujoConCronograma)
        comprobarElCronograma(leida)

        // Guardar y volver a leer: es lo que pasa al cerrar el lienzo y al sincronizar.
        val texto = ExcalidrawJson.encodeToString(leida)
        assertTrue(texto.contains("\"pixpin-gantt\""))
        assertTrue(texto.contains("\"tareas\""))
        assertTrue(texto.contains("\"periodos\""))
        comprobarElCronograma(ExcalidrawJson.decodeFromString<Scene>(texto))
    }

    /** Y no se puede poner uno nuevo: no queda herramienta que lo cree. */
    @Test
    fun `ya no hay herramienta de cronograma`() {
        assertTrue(Tool.entries.none { it.name == "CRONOGRAMA" })
        assertTrue(Tool.entries.none { tipoQueCrea(it) == ElementType.CRONOGRAMA })
        assertTrue(GRUPOS_DE_FABRICA.flatten().none { it.name == "CRONOGRAMA" })
    }

    /** Un reparto de la barra guardado cuando existía se sigue leyendo, sin él. */
    @Test
    fun `un reparto guardado que lo nombraba se sigue leyendo`() {
        val grupos = leerGrupos(escribirGrupos(listOf(listOf(Tool.RECTANGLE))) + "|CRONOGRAMA")
        assertTrue(grupos.flatten().contains(Tool.RECTANGLE))
        assertTrue(grupos.flatten().none { it.name == "CRONOGRAMA" })
    }

    // ---- Se sigue pintando como antes ----

    private fun plan(ancho: Double = 400.0, tareas: List<TareaDelCronograma>? = null) =
        ExcalidrawJson.decodeFromString<Scene>(dibujoConCronograma).elements[1].let { e ->
            e.copy(width = ancho, tareas = tareas ?: e.tareas)
        }

    /** La rejilla se reparte dentro de la caja: la primera columna arranca tras los nombres. */
    @Test
    fun `la rejilla se reparte sola`() {
        val e = plan()
        val columnas = columnasDelCronograma(e)
        assertEquals(9, columnas.size)
        assertEquals(xDeLaEscala(e), columnas.first(), tol)
        assertEquals(e.x + e.width, columnas.last(), 1e-6)
    }

    /** Cada fila ocupa su parte del alto, y las barras no se salen de la suya. */
    @Test
    fun `cada barra se queda en su fila`() {
        val e = plan()
        val alto = altoDeFila(e)
        for (i in e.tareas.indices) {
            val b = barraDeTarea(e, i)!!
            val techo = yDeLasFilas(e) + i * alto
            assertTrue(b.y1 >= techo - tol && b.y2 <= techo + alto + tol)
        }
    }

    /**
     * **La cuenta que tiró la aplicación en un móvil de verdad**, y que sigue en el pintado.
     *
     * Era `coerceIn(1.0, alto * 0.8)`, y `coerceIn` no recorta cuando el máximo baja del
     * mínimo: lanza — y lanzaba al dibujar. Ver [letraDelCronograma].
     */
    @Test
    fun `la letra aguanta una fila diminuta`() {
        val e = plan().copy(fontSize = null)
        assertEquals(1.0, letraDelCronograma(e, 0.34375), tol)
        assertEquals(1.0, letraDelCronograma(e, 0.0), tol)
        assertEquals(1.0, letraDelCronograma(e, -5.0), tol)
        assertEquals(32.0, letraDelCronograma(plan().copy(fontSize = 200.0), 40.0), tol)
    }

    /** Uno guardado sin filas sigue siendo válido al pintarlo. */
    @Test
    fun `sin tareas la figura no revienta`() {
        val vacio = plan(tareas = emptyList())
        assertTrue(altoDeFila(vacio) > 0.0)
        assertNull(barraDeTarea(vacio, 0))
        assertNotNull(columnasDelCronograma(vacio))
    }
}
