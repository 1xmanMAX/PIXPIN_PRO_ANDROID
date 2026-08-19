package com.forge.pixpin.mini

import java.util.Currency
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Las dos mini-apps, por donde se rompen.
 *
 * Lo que se comprueba aquí no es que sumen: es que **lo escrito se vuelve a leer igual**.
 * Todo esto se guarda como texto dentro del mensaje, así que un carácter que se cuele mal
 * no da un error, da una lista que al abrirla tiene una línea de menos — y eso no se nota
 * hasta que hace falta lo que había en esa línea.
 */
class MiniAppsTest {

    private val euro: Currency = Currency.getInstance("EUR")
    private val yen: Currency = Currency.getInstance("JPY")
    private val es = Locale.forLanguageTag("es-ES")

    // ---- Tareas ---------------------------------------------------------

    @Test
    fun `lo escrito se lee igual`() {
        val tareas = listOf(Tarea("Pan", true), Tarea("Leche"), Tarea("Pilas AA"))
        val ida = Tareas.escribir("La compra", tareas)
        assertEquals(tareas, Tareas.leer(ida))
        assertEquals("La compra", Cabecera.titulo(ida))
    }

    @Test
    fun `una lista sin nada se lee como vacía`() {
        val doc = Tareas.escribir("Vacía", emptyList())
        assertTrue(Tareas.leer(doc).isEmpty())
        assertTrue(Tareas.resumen(doc).vacia)
    }

    /** Sin tareas no hay avance: 0 de 0 no es «nada hecho», es «nada que hacer». */
    @Test
    fun `sin tareas no hay barrita de avance`() {
        assertNull(Tareas.resumenDe(emptyList()).avance)
        assertNotNull(Tareas.resumenDe(listOf(Tarea("Algo"))).avance)
    }

    @Test
    fun `el resumen cuenta lo hecho`() {
        val r = Tareas.resumenDe(listOf(Tarea("a", true), Tarea("b"), Tarea("c", true)))
        assertEquals(2, r.hechas)
        assertEquals(3, r.de)
        assertEquals(2f / 3f, r.avance!!, 0.0001f)
    }

    @Test
    fun `una tarea en blanco no entra`() {
        val antes = listOf(Tarea("Pan"))
        assertEquals(antes, Tareas.anadir(antes, "   "))
        assertEquals(antes, Tareas.anadir(antes, ""))
    }

    /**
     * Pegar un párrafo de tres renglones en una tarea: los renglones de abajo saldrían
     * del documento sin su casilla delante y dejarían de ser tareas. Se juntan en una.
     */
    @Test
    fun `un texto de varias líneas no parte la lista`() {
        val conSaltos = "Llamar al taller\ny preguntar\r\npor la pieza"
        val lista = Tareas.anadir(emptyList(), conSaltos)
        assertEquals(1, lista.size)
        val leidas = Tareas.leer(Tareas.escribir("", lista))
        assertEquals(lista, leidas)
    }

    @Test
    fun `marcar y desmarcar no toca a las demás`() {
        val antes = listOf(Tarea("a"), Tarea("b"), Tarea("c"))
        val despues = Tareas.alternar(antes, 1)
        assertEquals(listOf(false, true, false), despues.map { it.hecha })
        assertEquals(antes, Tareas.alternar(despues, 1))
    }

    /** Un índice que no existe no puede tirar la lista ni inventarse una tarea. */
    @Test
    fun `un indice fuera de sitio no rompe nada`() {
        val antes = listOf(Tarea("a"))
        assertEquals(antes, Tareas.alternar(antes, 7))
        assertEquals(antes, Tareas.borrar(antes, -1))
        assertEquals(antes, Tareas.marcar(antes, 99, true))
    }

    /** Un título con almohadillas y saltos pegados de otro sitio no parte el documento. */
    @Test
    fun `un titulo pegado con basura no parte el documento`() {
        val doc = Tareas.escribir("## La compra\ndel sábado", listOf(Tarea("Pan")))
        assertEquals(1, Tareas.leer(doc).size)
        assertTrue(Cabecera.titulo(doc).isNotEmpty())
    }

    // ---- Gastos ---------------------------------------------------------

    @Test
    fun `un libro escrito se vuelve a leer con su moneda y sus importes`() {
        val libro = Libro(
            "Viaje", euro,
            listOf(Gasto("Tren", 4250), Gasto("Café", 180), Gasto("Devolución", -500))
        )
        val leido = Gastos.leer(Gastos.escribir(libro, es), es)
        assertEquals(libro.gastos, leido.gastos)
        assertEquals(euro, leido.moneda)
        assertEquals("Viaje", leido.titulo)
    }

    @Test
    fun `el total suma lo que hay, negativos incluidos`() {
        assertEquals(3930L, Gastos.total(listOf(Gasto("a", 4250), Gasto("b", -320))))
        assertEquals(0L, Gastos.total(emptyList()))
    }

    @Test
    fun `los importes se leen escritos como se escriben aquí`() {
        assertEquals(4250L, Gastos.centimosDe("42,50", 2))
        assertEquals(4250L, Gastos.centimosDe("42.50", 2))
        assertEquals(4200L, Gastos.centimosDe("42", 2))
        assertEquals(-500L, Gastos.centimosDe("-5", 2))
    }

    /** El menos tipográfico llega copiando de una hoja de cálculo; sin tratarlo, un
     * reembolso se guardaría como gasto y el total saldría al revés. */
    @Test
    fun `el menos tipografico tambien resta`() {
        assertEquals(-1250L, Gastos.centimosDe("−12,50", 2))
    }

    @Test
    fun `sin cifras no hay importe`() {
        assertNull(Gastos.centimosDe("", 2))
        assertNull(Gastos.centimosDe("  ", 2))
        assertNull(Gastos.centimosDe("café", 2))
    }

    /** El yen no tiene decimales: «1200» son mil doscientos yenes, no doce. */
    @Test
    fun `una moneda sin decimales no parte el numero`() {
        assertEquals(1200L, Gastos.centimosDe("1200", 0))
        val libro = Libro("Tokio", yen, listOf(Gasto("Metro", 1200)))
        assertEquals(libro.gastos, Gastos.leer(Gastos.escribir(libro, es), es).gastos)
    }

    /** Una tira de cifras pegada no es un importe grande, es un accidente. */
    @Test
    fun `un numero absurdo no se acepta a ciegas`() {
        val enorme = Gastos.centimosDe("9".repeat(40), 2)
        assertTrue(enorme == null || enorme == Gastos.acotado(enorme))
    }

    /**
     * Una barra vertical en el concepto partiría la fila de la tabla en dos celdas y el
     * importe se leería como otra cosa. Debe volver a leerse igual o no colarse.
     */
    @Test
    fun `un concepto con barras no rompe la tabla`() {
        val libro = Gastos.anadir(Libro("Obra", euro, emptyList()), "Tubo | codo | junta", 1500)
        val leido = Gastos.leer(Gastos.escribir(libro, es), es)
        assertEquals(1, leido.gastos.size)
        assertEquals(1500L, leido.gastos.first().centimos)
    }

    @Test
    fun `el resumen de gastos dice el total y cuantas lineas hay`() {
        val libro = Libro("Viaje", euro, listOf(Gasto("Tren", 4250), Gasto("Café", 180)))
        val r = Gastos.resumen(Gastos.escribir(libro, es), es)
        assertEquals(2, r.de)
        assertTrue(r.texto.any { it.isDigit() })
        assertTrue(!r.vacia)
    }

    // ---- Cronómetro, temporizador y alarma ------------------------------

    /**
     * Lo que se guarda es el instante de arranque, no el número que se ve. Por eso un
     * cronómetro puesto en marcha y abandonado sabe cuánto lleva aunque nadie mirara.
     */
    @Test
    fun `un cronometro corriendo cuenta aunque nadie mire`() {
        val arrancado = Tiempos.arrancar(Cronometro(), 1_000_000L)
        assertEquals(60_000L, arrancado.transcurrido(1_060_000L))
    }

    @Test
    fun `parar guarda lo llevado y no reinicia`() {
        val c = Tiempos.parar(Tiempos.arrancar(Cronometro(), 1_000L), 4_000L)
        assertEquals(3_000L, c.llevado)
        assertEquals(3_000L, c.transcurrido(9_999_999L))
        assertTrue(!c.corriendo)
    }

    @Test
    fun `arrancar dos veces no reinicia la cuenta`() {
        val uno = Tiempos.arrancar(Cronometro(), 1_000L)
        assertEquals(uno, Tiempos.arrancar(uno, 5_000L))
    }

    @Test
    fun `parado no se marcan vueltas`() {
        val quieto = Cronometro(llevado = 5_000L)
        assertEquals(quieto, Tiempos.vuelta(quieto, 9_000L))
    }

    @Test
    fun `el cronometro se lee igual despues de guardarlo`() {
        val c = Cronometro(llevado = 12_345L, desde = 999L, vueltas = listOf(1_000L, 5_500L))
        assertEquals(c, Tiempos.leerCronometro(Tiempos.escribirCronometro("Vueltas", c)))
    }

    /** Un temporizador vencido dice «se acabó», no lo tarde que vas. */
    @Test
    fun `lo que falta nunca baja de cero`() {
        val t = Tiempos.lanzar(Temporizador(duracion = 60_000L), 0L)
        assertEquals(0L, t.restante(999_999L))
        assertTrue(t.vencido(60_001L))
    }

    @Test
    fun `un temporizador de cero no se lanza`() {
        val t = Temporizador(duracion = 0L)
        assertTrue(!Tiempos.lanzar(t, 1_000L).corriendo)
    }

    /** Cambiarle la duración mientras corre lo relanza; parado, solo la cambia. */
    @Test
    fun `cambiar la duracion en marcha lo relanza`() {
        val corriendo = Tiempos.lanzar(Temporizador(duracion = 60_000L), 1_000L)
        val nuevo = Tiempos.conDuracion(corriendo, 120_000L, 5_000L)
        assertEquals(125_000L, nuevo.finEn)
        val parado = Tiempos.conDuracion(Temporizador(), 120_000L, 5_000L)
        assertTrue(!parado.corriendo)
    }

    @Test
    fun `una duracion absurda se acota a un dia`() {
        val t = Tiempos.conDuracion(Temporizador(), Long.MAX_VALUE, 0L)
        assertEquals(Tiempos.TOPE_DE_DURACION, t.duracion)
        assertEquals(0L, Tiempos.conDuracion(Temporizador(), -5_000L, 0L).duracion)
    }

    @Test
    fun `la alarma se lee igual despues de guardarla`() {
        val a = Alarma(hora = 7, minuto = 5, activa = true)
        assertEquals(a, Tiempos.leerAlarma(Tiempos.escribirAlarma("Despertar", a)))
    }

    /** Una hora imposible guardada a mano no puede dar una alarma imposible. */
    @Test
    fun `una hora fuera de rango se recorta`() {
        val leida = Tiempos.leerAlarma("# X\n- hora: 99:88\n- activa: sí")
        assertEquals(23, leida.hora)
        assertEquals(59, leida.minuto)
    }

    @Test
    fun `los tiempos se leen como se leen los relojes`() {
        assertEquals("0:05,0", Tiempos.comoSeLee(5_000L))
        assertEquals("1:05,3", Tiempos.comoSeLee(65_300L))
        assertEquals("1:00:00", Tiempos.comoSeLee(3_600_000L))
        assertEquals("5:00", Tiempos.comoSeLeeCorto(300_000L))
    }

    // ---- El registro de mini-apps ---------------------------------------

    @Test
    fun `cada mini-app nace vacía y con su nombre`() {
        MiniApp.entries.forEach { cual ->
            val doc = cual.documentoNuevo("Prueba", es)
            assertEquals("Prueba", Cabecera.titulo(doc))
            assertTrue("${cual.id} debería nacer vacía", cual.resumen(doc, es).vacia)
        }
    }

    /** Una mini-app de una versión posterior no puede tirar la conversación. */
    @Test
    fun `una mini-app desconocida no explota`() {
        assertNull(MiniApp.de("loQueVengaEnElFuturo"))
        assertNull(MiniApp.de(null))
    }

    /** Los identificadores guardados no se cambian nunca: atan lo escrito con su pantalla. */
    @Test
    fun `los identificadores son los esperados`() {
        assertEquals("tareas", MiniApp.TAREAS.id)
        assertEquals("gastos", MiniApp.GASTOS.id)
    }
}
