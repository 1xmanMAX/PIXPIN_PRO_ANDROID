package com.forge.pixpin.guardados

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lo que hace que una tanda de mensajes se lea como **un rato** y no como cinco sucesos.
 *
 * Dos cosas pueden salir mal aquí y las dos se ven a la primera: que se agrupe lo que no va
 * junto —una burbuja recortada apuntando a un vecino que no está— y que no se agrupe lo que
 * sí, que deja la lista llena de aire.
 */
class AgrupacionTest {

    private val minuto = 60_000L

    private var n = 0
    private fun msg(cuando: Long) = Mensaje(id = "m${++n}", cuando = cuando, clase = Clase.NOTA)

    /** Una lista a partir de los minutos en que se guardó cada cosa. */
    private fun enMinutos(vararg minutos: Long) = minutos.map { msg(it * minuto) }

    // ---- Quién va con quién ----------------------------------------------

    /** Un minuto es el mismo rato: la ventana de Telegram son cinco. */
    @Test
    fun `dos mensajes a un minuto se agrupan`() {
        val s = sitios(enMinutos(0, 1))
        assertTrue(s[0].pegadoAbajo)
        assertTrue(s[1].pegadoArriba)
    }

    /** Seis minutos ya es otra cosa: fuera de la ventana, cada uno con su burbuja entera. */
    @Test
    fun `dos mensajes a seis minutos no se agrupan`() {
        val s = sitios(enMinutos(0, 6))
        assertFalse(s[0].pegadoAbajo)
        assertFalse(s[1].pegadoArriba)
        assertTrue(s[0].suelto && s[1].suelto)
    }

    /** El borde de la ventana entra: `<=`, como el `Math.abs(...) <= 5 * 60` de Telegram. */
    @Test
    fun `los cinco minutos justos todavia agrupan`() {
        assertTrue(cabenJuntos(0, VENTANA_DE_GRUPO_MS))
        assertFalse(cabenJuntos(0, VENTANA_DE_GRUPO_MS + 1))
    }

    /** No por un caso especial: es que no tienen con quién. */
    @Test
    fun `el primero nunca esta pegado arriba y el ultimo nunca pegado abajo`() {
        val s = sitios(enMinutos(0, 1, 2, 3))
        assertFalse(s.first().pegadoArriba)
        assertFalse(s.last().pegadoAbajo)
    }

    @Test
    fun `sin mensajes no hay sitios`() {
        assertTrue(sitios(emptyList()).isEmpty())
    }

    /** Uno solo está suelto por definición, y lleva su cola. */
    @Test
    fun `un mensaje solo esta suelto`() {
        val s = sitios(enMinutos(0))
        assertEquals(listOf(Sitio(pegadoArriba = false, pegadoAbajo = false)), s)
        assertTrue(s[0].ultimoDelGrupo)
    }

    /**
     * Cuatro seguidos donde el tercero rompe la ventana: el corte tiene que caer **entre**
     * el segundo y el tercero y en ningún otro sitio.
     */
    @Test
    fun `una cadena de cuatro con el tercero fuera de la ventana`() {
        val s = sitios(enMinutos(0, 1, 20, 21))
        assertEquals(Sitio(pegadoArriba = false, pegadoAbajo = true), s[0])
        assertEquals(Sitio(pegadoArriba = true, pegadoAbajo = false), s[1])
        assertEquals(Sitio(pegadoArriba = false, pegadoAbajo = true), s[2])
        assertEquals(Sitio(pegadoArriba = true, pegadoAbajo = false), s[3])
    }

    /**
     * **El separador de día parte los grupos.** Calculando por tramo, dos mensajes a tres
     * minutos a caballo de la medianoche no se pegan: tienen la fecha en medio.
     */
    @Test
    fun `el separador de dia no deja agrupar aunque el reloj diga que si`() {
        val antes = msg(0)
        val despues = msg(3 * minuto)
        val tramos = porDias(listOf(antes, despues)) { if (it == 0L) 1L else 2L }
        val porId = sitiosPorId(tramos)

        assertEquals(2, tramos.size)
        assertTrue(porId.getValue(antes.id).suelto)
        assertTrue(porId.getValue(despues.id).suelto)
    }

    /** Dentro de un mismo día, sitiosPorId da lo mismo que sitios. */
    @Test
    fun `los sitios por identificador coinciden con los del tramo`() {
        val lista = enMinutos(0, 1, 2)
        val porId = sitiosPorId(listOf(Tramo(1L, lista)))
        val enOrden = sitios(lista)
        assertEquals(3, porId.size)
        for (i in lista.indices) assertEquals(enOrden[i], porId.getValue(lista[i].id))
    }

    // ---- Cómo se pinta ----------------------------------------------------

    /** Agrupado aprieta, suelto respira. */
    @Test
    fun `el hueco de encima se encoge al agrupar`() {
        assertEquals(2, espacioAntes(Sitio(pegadoArriba = true, pegadoAbajo = false)))
        assertEquals(8, espacioAntes(Sitio(pegadoArriba = false, pegadoAbajo = true)))
        assertEquals(8, espacioAntes(Sitio(pegadoArriba = false, pegadoAbajo = false)))
    }

    /** Una burbuja sola es redonda por los cuatro lados. */
    @Test
    fun `un mensaje suelto lleva los cuatro radios enteros`() {
        assertEquals(Radios(17, 17, 17, 17), radios(Sitio(pegadoArriba = false, pegadoAbajo = false)))
    }

    /**
     * El de en medio de un grupo se recorta **por la derecha**, arriba y abajo: es el lado
     * propio, por el que las burbujas se tocan. El izquierdo se queda entero.
     */
    @Test
    fun `el de en medio de un grupo se recorta por la derecha`() {
        val r = radios(Sitio(pegadoArriba = true, pegadoAbajo = true))
        assertEquals(3, r.arribaDer)
        assertEquals(3, r.abajoDer)
        assertEquals(17, r.arribaIzq)
        assertEquals(17, r.abajoIzq)
    }

    /** El de arriba del grupo solo recorta abajo; el de abajo, solo arriba. */
    @Test
    fun `las puntas del grupo recortan una sola esquina`() {
        assertEquals(Radios(17, 17, 3, 17), radios(Sitio(pegadoArriba = false, pegadoAbajo = true)))
        assertEquals(Radios(17, 3, 17, 17), radios(Sitio(pegadoArriba = true, pegadoAbajo = false)))
    }

    /** Los seis puntos de más del último son el sitio de la cola, no aire. */
    @Test
    fun `solo el ultimo del grupo deja hueco para la cola`() {
        val conCola = relleno(Sitio(pegadoArriba = true, pegadoAbajo = false))
        val agrupado = relleno(Sitio(pegadoArriba = true, pegadoAbajo = true))
        assertEquals(18, conCola.fin)
        assertEquals(12, agrupado.fin)
        assertEquals(Relleno(inicio = 12, arriba = 7, fin = 18, abajo = 7), conCola)
    }

    // ---- Rachas por origen ------------------------------------------------

    private fun m(
        id: String,
        clase: Clase,
        ruta: String? = null,
        referencia: String? = null,
        proyecto: String? = null
    ) = Mensaje(id = id, cuando = 0, clase = clase, ruta = ruta, referencia = referencia, proyecto = proyecto)

    @Test
    fun `tres paginas del mismo pdf se agrupan`() {
        val rachas = rachasDeOrigen(
            listOf(
                m("p1", Clase.PAGINA, ruta = "/plano.pdf"),
                m("p2", Clase.PAGINA, ruta = "/plano.pdf"),
                m("p3", Clase.PAGINA, ruta = "/plano.pdf")
            )
        )
        assertEquals(listOf(RachaDeOrigen("pdf:/plano.pdf", 0, 2)), rachas)
    }

    @Test
    fun `una foto en medio corta la racha del pdf`() {
        val rachas = rachasDeOrigen(
            listOf(
                m("p1", Clase.PAGINA, ruta = "/plano.pdf"),
                m("f1", Clase.IMAGEN),
                m("p2", Clase.PAGINA, ruta = "/plano.pdf")
            )
        )
        assertTrue("la foto rompe el grupo", rachas.isEmpty())
    }

    @Test
    fun `hojas de lienzos distintos son grupos distintos`() {
        val rachas = rachasDeOrigen(
            listOf(
                m("d1", Clase.DIBUJO, referencia = "escena-1"),
                m("d2", Clase.DIBUJO, referencia = "escena-1"),
                m("d3", Clase.DIBUJO, referencia = "escena-2"),
                m("d4", Clase.DIBUJO, referencia = "escena-2")
            )
        )
        assertEquals(
            listOf(RachaDeOrigen("lienzo:escena-1", 0, 1), RachaDeOrigen("lienzo:escena-2", 2, 3)),
            rachas
        )
    }

    @Test
    fun `las notas de voz van juntas aunque no lleven transcripcion`() {
        val rachas = rachasDeOrigen(
            listOf(
                m("v1", Clase.VOZ, ruta = "/a1.m4a", proyecto = "pr-1"),
                m("v2", Clase.VOZ, ruta = "/a2.m4a", proyecto = "pr-1"),
                m("v3", Clase.VOZ, ruta = "/a3.m4a", proyecto = "pr-1")
            )
        )
        assertEquals(1, rachas.size)
        assertEquals(0, rachas[0].desde)
        assertEquals(2, rachas[0].hasta)
    }

    @Test
    fun `una sola pagina no es un grupo`() {
        assertTrue(rachasDeOrigen(listOf(m("p1", Clase.PAGINA, ruta = "/x.pdf"))).isEmpty())
    }

    @Test
    fun `fotos y notas sueltas nunca se agrupan`() {
        assertTrue(
            rachasDeOrigen(
                listOf(m("n1", Clase.NOTA), m("i1", Clase.IMAGEN), m("n2", Clase.NOTA))
            ).isEmpty()
        )
    }

    // ---- Entradas de la lista (con las rachas plegadas) --------------------

    @Test
    fun `la lista pliega las rachas en una sola entrada cada una`() {
        val entradas = entradasDe(
            listOf(
                m("n1", Clase.NOTA),
                m("p1", Clase.PAGINA, ruta = "/plano.pdf"),
                m("p2", Clase.PAGINA, ruta = "/plano.pdf"),
                m("i1", Clase.IMAGEN),
                m("v1", Clase.VOZ),
                m("v2", Clase.VOZ),
                m("v3", Clase.VOZ)
            )
        )
        assertEquals(4, entradas.size)
        assertEquals(EntradaDeLista.MensajeSolo(m("n1", Clase.NOTA)), entradas[0])
        val pdf = entradas[1] as EntradaDeLista.GrupoDeOrigen
        assertEquals(listOf("p1", "p2"), pdf.mensajes.map { it.id })
        assertEquals(EntradaDeLista.MensajeSolo(m("i1", Clase.IMAGEN)), entradas[2])
        val voz = entradas[3] as EntradaDeLista.GrupoDeOrigen
        assertEquals(listOf("v1", "v2", "v3"), voz.mensajes.map { it.id })
    }

    @Test
    fun `sin rachas la lista de entradas es uno a uno`() {
        val lista = listOf(m("n1", Clase.NOTA), m("i1", Clase.IMAGEN))
        assertEquals(2, entradasDe(lista).size)
        assertTrue(entradasDe(emptyList()).isEmpty())
    }
}
