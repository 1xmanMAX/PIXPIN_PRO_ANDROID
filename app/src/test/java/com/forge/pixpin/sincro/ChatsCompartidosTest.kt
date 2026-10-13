package com.forge.pixpin.sincro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Quién está en cada chat compartido y qué se cree de lo que llega**, sin red ni Android. Ver
 * [Compartidos]. La sincronización de verdad, con dos carpetas, está en `ChatsCompartidosDeVerdadTest`.
 */
class ChatsCompartidosTest {

    private val max = Yo("id-max", "Max", "MAXX", Llaves.nuevas())
    private val ana = Yo("id-ana", "Ana", "ANAA", Llaves.nuevas())
    private val eva = Yo("id-eva", "Eva", "EVAA", Llaves.nuevas())
    private val luis = Yo("id-luis", "Luis", "LUIS", Llaves.nuevas())

    private fun conAnaYEva(): ChatCompartido {
        var c = Compartidos.crear("p1", "Tesis", max, 100)
        c = Compartidos.invitar(c, max, ana.ficha(), 200)
        c = Compartidos.invitar(c, ana, eva.ficha(), 300)
        return c
    }

    @Test
    fun `crear e invitar deja dentro a todos y con firmas buenas`() {
        val c = conAnaYEva()
        assertEquals(listOf("id-max", "id-ana", "id-eva"), c.personas.map { it.id })
        assertTrue(c.personas.all { it.dentro })
        // Lo que llega a quien no tenía nada se cree entero: todas las firmas cuadran.
        assertEquals(c, Compartidos.juntar(null, c))
        assertEquals(setOf("p1"), Compartidos.enComun(listOf(c), "id-ana", "id-eva"))
        assertEquals(mapOf("MAXX" to "Max", "ANAA" to "Ana", "EVAA" to "Eva"), Compartidos.autores(c))
    }

    @Test
    fun `juntar da lo mismo en cualquier orden`() {
        val base = Compartidos.invitar(Compartidos.crear("p1", "Tesis", max, 100), max, ana.ficha(), 200)
        // Ana invita a Eva en su aparato; Max, a Luis en el suyo, a la vez.
        val deAna = Compartidos.invitar(base, ana, eva.ficha(), 300)
        val deMax = Compartidos.invitar(base, max, luis.ficha(), 310)
        val a = Compartidos.juntar(deAna, deMax)
        val b = Compartidos.juntar(deMax, deAna)
        assertEquals(a.personas.sortedBy { it.id }, b.personas.sortedBy { it.id })
        assertEquals(setOf("id-max", "id-ana", "id-eva", "id-luis"), a.personas.filter { it.dentro }.map { it.id }.toSet())
        // Y juntar dos veces no cambia nada.
        assertEquals(a, Compartidos.juntar(a, deAna))
    }

    @Test
    fun `solo el creador quita a otros y cada uno puede irse`() {
        val c = conAnaYEva()
        assertFalse(Compartidos.puedeQuitar(c, "id-ana", "id-eva"))
        assertTrue(Compartidos.puedeQuitar(c, "id-max", "id-eva"))
        assertTrue(Compartidos.puedeQuitar(c, "id-eva", "id-eva"))
        val sinEva = Compartidos.quitar(c, max, "id-eva", 400)
        assertFalse(Compartidos.dentro(sinEva, "id-eva"))
        assertEquals(emptySet<String>(), Compartidos.enComun(listOf(sinEva), "id-ana", "id-eva"))
        // Ana se entera juntando: la salida va firmada por el creador.
        val enAna = Compartidos.juntar(c, sinEva)
        assertFalse(Compartidos.dentro(enAna, "id-eva"))
        // Ana se va sola.
        val sinAna = Compartidos.quitar(enAna, ana, "id-ana", 500)
        assertFalse(Compartidos.dentro(Compartidos.juntar(sinEva, sinAna), "id-ana"))
    }

    @Test
    fun `una salida que no firma el creador no se cree`() {
        val c = conAnaYEva()
        // Ana intenta echar a Eva diciendo que fue el creador, con su propia firma.
        val eva0 = c.personas.first { it.id == "id-eva" }
        val falsa = eva0.copy(fuera = 400, quito = "id-max", firmaFuera = null)
        val firmada = falsa.copy(firmaFuera = ana.llaves.firmar(Compartidos.textoDeSalida("p1", falsa)))
        val trampa = c.copy(personas = c.personas.map { if (it.id == "id-eva") firmada else it })
        assertTrue(Compartidos.dentro(Compartidos.juntar(c, trampa), "id-eva"))
        // Ni con «quitó ella misma» firmado por otro.
        val otra = eva0.copy(fuera = 400, quito = "id-eva", firmaFuera = null)
        val otraFirmada = otra.copy(firmaFuera = ana.llaves.firmar(Compartidos.textoDeSalida("p1", otra)))
        assertTrue(Compartidos.dentro(Compartidos.juntar(c, c.copy(personas = c.personas.map { if (it.id == "id-eva") otraFirmada else it })), "id-eva"))
    }

    @Test
    fun `nadie le cambia la clave ni el nombre a otro`() {
        val c = conAnaYEva()
        // Alguien con la ficha de Eva pero otra clave: no entra, se queda la de verdad.
        val impostora = Yo("id-eva", "Eva", "EVAA", Llaves.nuevas())
        val trampa = Compartidos.invitar(Compartidos.crear("p1", "Tesis", max, 100), max, impostora.ficha(), 999)
        val junto = Compartidos.juntar(c, trampa)
        assertEquals(eva.clave, junto.personas.first { it.id == "id-eva" }.clave)
        // Cambiarle el nombre sin volver a firmar tampoco cuela.
        val renombrada = c.copy(personas = c.personas.map { if (it.id == "id-eva") it.copy(nombre = "Max", desde = 9_999) else it })
        assertEquals("Eva", Compartidos.juntar(c, renombrada).personas.first { it.id == "id-eva" }.nombre)
    }

    @Test
    fun `una lista inventada no entra aunque llegue a quien no tenia nada`() {
        val c = conAnaYEva()
        // Un creador que no firmó su entrada, o alguien invitado por un desconocido.
        val sinFirma = c.copy(personas = c.personas.map { it.copy(firma = "AAAA") })
        assertTrue(Compartidos.juntar(null, sinFirma).personas.isEmpty())
        val huerfana = c.copy(personas = c.personas.filter { it.id != "id-ana" })
        // Eva la invitó Ana, que no está: Eva no entra.
        assertEquals(listOf("id-max"), Compartidos.juntar(null, huerfana).personas.map { it.id })
    }

    @Test
    fun `quien vuelve invitado otra vez esta dentro`() {
        val c = Compartidos.quitar(conAnaYEva(), max, "id-eva", 400)
        val otraVez = Compartidos.invitar(c, ana, eva.ficha(), 350)
        assertTrue(Compartidos.dentro(otraVez, "id-eva"))
        // Lo de antes (fuera) y lo nuevo (dentro) se juntan como dentro, en cualquier orden.
        assertTrue(Compartidos.dentro(Compartidos.juntar(c, otraVez), "id-eva"))
        assertTrue(Compartidos.dentro(Compartidos.juntar(otraVez, c), "id-eva"))
    }

    @Test
    fun `quien ya no esta no invita`() {
        val c = Compartidos.quitar(conAnaYEva(), max, "id-ana", 400)
        try {
            Compartidos.invitar(c, ana, luis.ficha(), 500)
            throw AssertionError("No debería poder invitar")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `por clave solo se encuentra a quien esta en un chat conmigo`() {
        val c = conAnaYEva()
        assertEquals("id-eva", Compartidos.porClave(listOf(c), "id-ana", eva.clave)?.id)
        assertNull(Compartidos.porClave(listOf(c), "id-luis", eva.clave))
        assertNull(Compartidos.porClave(listOf(c), "id-ana", luis.clave))
        // Quien salió sigue constando: se le contesta para que se entere, pero no hay chats en común.
        val sinEva = Compartidos.quitar(c, max, "id-eva", 400)
        assertNotNull(Compartidos.porClave(listOf(sinEva), "id-ana", eva.clave))
        assertEquals(setOf("p1"), Compartidos.conRastro(listOf(sinEva), "id-ana", "id-eva"))
        assertTrue(Compartidos.enComun(listOf(sinEva), "id-ana", "id-eva").isEmpty())
    }

    @Test
    fun `entre aparatos del grupo el otro entra en mis chats compartidos`() {
        val c = Compartidos.invitar(Compartidos.crear("p1", "Tesis", max, 100), max, ana.ficha(), 200)
        val tabletaDeMax = Yo("id-max-tab", "Max", "MTAB", Llaves.nuevas())
        val enElTelefono = Compartidos.juntarDelGrupo(listOf(c), emptyList(), max, tabletaDeMax.ficha(), 300)
        assertTrue(Compartidos.dentro(enElTelefono.single(), "id-max-tab"))
        // La tableta, que no tenía nada, se queda con la lista entera.
        val enLaTableta = Compartidos.juntarDelGrupo(emptyList(), enElTelefono, tabletaDeMax, max.ficha(), 300)
        assertEquals(enElTelefono.single().personas.map { it.id }.toSet(), enLaTableta.single().personas.map { it.id }.toSet())
        // Y Ana se cree esa entrada: la firmó Max, que está dentro.
        assertTrue(Compartidos.dentro(Compartidos.juntar(c, enElTelefono.single()), "id-max-tab"))
    }

    @Test
    fun `invitaciones se leen del QR y el codigo se limpia`() {
        val q = Invitacion.leerQr(Invitacion.textoDelQr("K7Q2M9XP", "192.168.1.5", 4000))!!
        assertEquals("K7Q2M9XP", q.codigo)
        assertEquals("192.168.1.5", q.host)
        assertEquals(4000, q.puerto)
        assertNull(Invitacion.leerQr("pixpin-envio:1:123456::0"))
        assertEquals("K7Q2M9XP", Invitacion.limpiar("k7q2-m9xp"))
        assertEquals("K7Q2-M9XP", Invitacion.legible("K7Q2M9XP"))
        assertTrue(Invitacion.valido(Invitacion.nuevoCodigo()))
    }

    @Test
    fun `las claves acuerdan el mismo secreto y firman`() {
        val s1 = max.llaves.acordar(ana.llaves.publica)
        val s2 = ana.llaves.acordar(max.llaves.publica)
        assertTrue(s1.contentEquals(s2))
        val f = max.llaves.firmar("hola".toByteArray())
        assertTrue(Llaves.verificar(max.clave, "hola".toByteArray(), f))
        assertFalse(Llaves.verificar(ana.clave, "hola".toByteArray(), f))
        assertFalse(Llaves.verificar(max.clave, "hola".toByteArray(), "basura"))
    }
}
