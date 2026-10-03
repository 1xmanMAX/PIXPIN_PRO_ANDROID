package com.forge.pixpin.lecciones

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Las lecciones aprendidas: el reparto del dictado, las etiquetas solas, el buscador y el repaso. */
class LeccionesTest {

    private fun l(id: String, titulo: String, quePaso: String = "", proxima: String = "", etiquetas: List<String> = emptyList(), area: String = "", repes: Int = 0) =
        Leccion(id = id, creada = 1000, titulo = titulo, quePaso = quePaso, proxima = proxima, etiquetas = etiquetas,
            area = area, repeticiones = List(repes) { 2000L + it })

    @Test
    fun `una frase sin marcas es lo aprendido`() {
        val c = Dictado.repartir("revisar la escala antes de imprimir")
        assertEquals("Revisar la escala antes de imprimir", c.titulo)
        assertEquals("", c.quePaso)
    }

    @Test
    fun `el dictado de corrido se reparte en sus campos`() {
        val c = Dictado.repartir("pasó que se vació la losa sin revisar el encofrado porque el maestro tenía prisa, la próxima vez reviso los puntales antes del vaciado")
        assertEquals("Se vació la losa sin revisar el encofrado", c.quePaso)
        assertEquals("El maestro tenía prisa", c.porQue)
        assertEquals("Reviso los puntales antes del vaciado", c.proxima)
        // Sin «aprendí que», lo que se hará distinto hace de título.
        assertEquals("Reviso los puntales antes del vaciado", c.titulo)
    }

    @Test
    fun `lo que va delante de las marcas es el titulo`() {
        val c = Dictado.repartir("Pedir todo por escrito. Pasó que el cliente cambió el acuerdo de palabra")
        assertEquals("Pedir todo por escrito", c.titulo)
        assertEquals("El cliente cambió el acuerdo de palabra", c.quePaso)
    }

    @Test
    fun `las palabras se comparan sin acentos ni plurales`() {
        assertEquals(Texto.raiz(Texto.normal("Estructuras")), Texto.raiz(Texto.normal("estructura")))
        assertEquals(Texto.raiz(Texto.normal("hormigón")), Texto.raiz("hormigon"))
        assertEquals(Texto.raiz("construccion"), Texto.raiz("construcciones"))
        assertEquals("ñandu", Texto.normal("Ñandú"))
    }

    @Test
    fun `propone etiquetas area tipo y causas`() {
        val p = Etiquetador.proponer("No revisé el encofrado y la losa se fisuró al vaciar, error por prisa #obra")
        assertTrue(p.etiquetas.toString(), "obra" in p.etiquetas)
        assertTrue(p.etiquetas.toString(), "concreto" in p.etiquetas)
        assertTrue(p.etiquetas.toString(), "encofrado" in p.etiquetas)
        assertEquals("Construcción", p.area)
        assertEquals(Leccion.TIPO_ERROR, p.tipo)
        assertTrue(p.causas.toString(), "No revisé" in p.causas)
        assertTrue(p.causas.toString(), "Prisa" in p.causas)
    }

    @Test
    fun `mal no salta dentro de otra palabra`() {
        assertNull(Etiquetador.proponer("El resultado normal del animal").tipo)
    }

    @Test
    fun `lo quitado no vuelve`() {
        val p = Etiquetador.proponer("la losa se fisuró", quitadas = listOf("concreto"))
        assertFalse("concreto" in p.etiquetas)
    }

    @Test
    fun `aprende las etiquetas de cada uno`() {
        val hechas = listOf(
            l("1", "Revisar puntales del encofrado", etiquetas = listOf("losas")),
            l("2", "Encofrado con puntales firmes", etiquetas = listOf("losas"))
        )
        val p = Etiquetador.proponer("Los puntales del encofrado cedieron", Etiquetador.aprender(hechas))
        assertTrue(p.etiquetas.toString(), "losas" in p.etiquetas)
    }

    @Test
    fun `las etiquetas dichas se recogen y se quitan del texto`() {
        assertEquals(listOf("tesis", "asesor"), Etiquetador.escritas("Mandar el capítulo #Tesis etiqueta asesor"))
        assertEquals("Mandar el capítulo", Etiquetador.sinEtiquetas("Mandar el capítulo #Tesis etiqueta asesor"))
    }

    private val todas = listOf(
        l("a", "Revisar la escala antes de imprimir los planos", etiquetas = listOf("planos")),
        l("b", "Pedir todo por escrito al cliente", area = "Trabajo"),
        l("c", "Revisar puntales del encofrado antes del vaciado", area = "Construcción"),
        l("d", "Dormir antes del examen final", area = "Estudio")
    ).map { Buscador.Indice(it) }

    @Test
    fun `busca con erratas y sin acentos`() {
        assertEquals("a", Buscador.buscar(todas, "escla").first().leccion.id)
        assertEquals("b", Buscador.buscar(todas, "ESCRITO").first().leccion.id)
        assertEquals("d", Buscador.buscar(todas, "exámenes").first().leccion.id)
    }

    @Test
    fun `una palabra encuentra por su concepto aunque no salga`() {
        // «obra» no sale en ninguna; el encofrado es de Construcción.
        val r = Buscador.buscar(todas, "obra")
        assertEquals("c", r.first().leccion.id)
    }

    @Test
    fun `las que se repiten suben`() {
        val dos = listOf(
            Buscador.Indice(l("x", "Revisar la escala")),
            Buscador.Indice(l("y", "Revisar la escala", repes = 3))
        )
        assertEquals("y", Buscador.buscar(dos, "escala").first().leccion.id)
    }

    @Test
    fun `avisa si ya hay una parecida`() {
        val p = Buscador.parecidas(todas, "otra vez no revisé la escala al imprimir planos")
        assertEquals("a", p.first().leccion.id)
        assertTrue(Buscador.parecidas(todas, "comprar pan en la tienda").isEmpty())
    }

    @Test
    fun `el repaso se aleja al recordar y vuelve al olvidar o repetirse`() {
        val dia = Leccion.DIA
        var x = l("r", "algo")
        x = Repaso.recordada(x, 0)
        assertEquals(1, x.caja); assertEquals(3 * dia, x.repasar)
        x = Repaso.recordada(x, 0)
        assertEquals(7 * dia, x.repasar)
        assertEquals(0, Repaso.olvidada(x, 0).caja)
        val rep = Repaso.repetida(x, 5)
        assertEquals(0, rep.caja)
        assertEquals(2, rep.vecesQuePaso)
        assertEquals(2, rep.gravedad)
        assertEquals(3, Repaso.repetida(rep, 6).gravedad)
    }

    @Test
    fun `el archivo se lee aunque traiga campos de una version nueva`() {
        val txt = Leccion.escribir(l("j", "Algo")).replace("\"titulo\"", "\"campoNuevo\": 1, \"titulo\"")
        assertEquals("Algo", Leccion.leer(txt)?.titulo)
    }
}
