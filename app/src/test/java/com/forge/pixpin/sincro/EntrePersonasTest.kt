package com.forge.pixpin.sincro

import com.forge.pixpin.guardados.Clase
import com.forge.pixpin.guardados.Mensaje
import com.forge.pixpin.guardados.sitios
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Un chat compartido como una conversación** (25-oct-2026): las pruebas del PC
 * (`pixpin-ui/conversacion.rs`, `historial.rs` y `en_el_chat` de `compartidos_ui.rs`, de `b89c058`
 * y `e3f1d60`), llevadas a la JVM. Ver [EntrePersonas].
 */
class EntrePersonasTest {

    private fun lados() = LadosDelChat.nuevo(
        listOf("PC01", "MOV1"),
        listOf(
            "PC01" to "Max",
            "MOV1" to "Max",
            "ANA1" to "Ana",
            "ANA2" to "Ana",
            "LUI1" to "Luis",
            "SIN1" to "  "
        )
    )

    @Test
    fun `lo de mis aparatos es mio, tambien lo del movil`() {
        val l = lados()
        assertEquals(Quien.Yo, l.quien("PC01"))
        // Mi móvil está en la lista del chat con mi nombre, pero es mío: si fuera «de otro» saldría
        // a la izquierda con mi propio nombre.
        assertEquals(Quien.Yo, l.quien("MOV1"))
    }

    @Test
    fun `lo de otra persona va con su nombre desde cualquiera de sus aparatos`() {
        val l = lados()
        assertEquals(Quien.Otro("Ana"), l.quien("ANA1"))
        assertEquals(Quien.Otro("Ana"), l.quien("ANA2"))
        assertEquals(Quien.Otro("Luis"), l.quien("LUI1"))
        assertEquals("Ana, Luis y el sin nombre", 3, l.otrasPersonas())
    }

    @Test
    fun `sin codigo o con uno que nadie reclama es mio`() {
        val l = lados()
        // Lo escrito antes de compartir, o en un aparato viejo: mi cuaderno.
        assertEquals(Quien.Yo, l.quien(null))
        assertEquals(Quien.Yo, l.quien(""))
        assertEquals(Quien.Yo, l.quien("ZZZZ"))
    }

    @Test
    fun `sin nombre se le llama por su codigo`() {
        assertEquals(Quien.Otro("SIN1"), lados().quien("SIN1"))
    }

    @Test
    fun `cada persona tiene su numero y lo mio el cero`() {
        val q = listOf(Quien.Yo, Quien.Otro("Ana"), Quien.Otro("Luis"), Quien.Otro("Ana"), Quien.Yo)
        assertEquals(listOf(0, 1, 2, 1, 0), EntrePersonas.numerar(q))
    }

    @Test
    fun `la inicial y el tono son estables`() {
        assertEquals("A", EntrePersonas.inicial(" ana"))
        assertEquals("", EntrePersonas.inicial(""))
        assertEquals(EntrePersonas.tono("Ana", 7), EntrePersonas.tono("Ana", 7))
        assertTrue(EntrePersonas.tono("Luis", 7) < 7)
        assertEquals(0, EntrePersonas.tono("x", 0))
    }

    /** El mismo color que en el PC: FNV-1a de 32 bits sobre los bytes UTF-8, módulo 7 (valores sacados de su fórmula). */
    @Test
    fun `el tono es el del PC para el mismo nombre`() {
        assertEquals(6, EntrePersonas.tono("Ana", 7))
        assertEquals(2, EntrePersonas.tono("Luis", 7))
        assertEquals(3, EntrePersonas.tono(" Max ", 7))
        assertEquals(3, EntrePersonas.tono("José", 7))
        assertEquals(4, EntrePersonas.tono("María", 7))
    }

    @Test
    fun `el codigo corto sale del id entero como en el PC`() {
        assertEquals("K7Q2", EntrePersonas.aparatoCorto(" K7Q2 "))
        assertEquals(null, EntrePersonas.aparatoCorto(null))
        val id = "6f1c2d0e-aaaa-bbbb-cccc-123456789abc"
        assertEquals(Aparato(id, "").codigo, EntrePersonas.aparatoCorto(id))
        assertEquals("IA", EntrePersonas.aparatoCorto("IA"))
    }

    private fun yo(id: String, nombre: String) = Yo(id, nombre, Aparato(id, "").codigo, Llaves.nuevas())

    @Test
    fun `en un chat compartido lo de mi movil es mio y lo de Ana de Ana`() {
        val pc = yo("pc-de-max", "Max")
        val movil = yo("movil-de-max", "Max")
        val ana = yo("movil-de-ana", "Ana")
        var c = Compartidos.crear("tesis", "Tesis", pc, 1)
        c = Compartidos.invitar(c, pc, movil.ficha(0), 2)
        c = Compartidos.invitar(c, pc, ana.ficha(0), 3)
        val v = EntrePersonas.enElChat(c, setOf("pc-de-max", "movil-de-max"))
        assertEquals(Quien.Yo, v.lados.quien(pc.codigo))
        assertEquals(Quien.Yo, v.lados.quien(movil.codigo))
        assertEquals(Quien.Otro("Ana"), v.lados.quien(ana.codigo))
        // Lo de antes de compartir (sin código) sigue siendo mío.
        assertEquals(Quien.Yo, v.lados.quien(null))
        assertEquals("Max (dos aparatos) y Ana", 2, v.personas)
        assertEquals(1, v.lados.otrasPersonas())
    }

    /** Del PC `cad5e20`: sin ningún chat compartido no se lee la identidad (leerla la crea). */
    @Test
    fun `sin chats compartidos no se pide la identidad`() {
        var leida = false
        val r = EntrePersonas.porChat(emptyList()) { leida = true; error("no hacía falta") }
        assertTrue(r.isEmpty())
        assertFalse(leida)
    }

    @Test
    fun `solo cuentan los chats en los que estoy dentro`() {
        val max = yo("pc-de-max", "Max")
        val ana = yo("movil-de-ana", "Ana")
        val mio = Compartidos.invitar(Compartidos.crear("p1", "Tesis", max, 1), max, ana.ficha(0), 2)
        val sinMi = Compartidos.quitar(Compartidos.invitar(Compartidos.crear("p2", "Otro", ana, 1), ana, max.ficha(0), 2), max, max.id, 3)
        val i = Identidad(Aparato(max.id, "Max"))
        val r = EntrePersonas.porChat(listOf(mio, sinMi)) { i }
        assertEquals(setOf("p1"), r.keys)
        assertEquals(Quien.Otro("Ana"), r.getValue("p1").lados.quien(ana.codigo))
    }

    // ---- Las tandas (historial.rs del PC, con los sitios de Android) ------

    private val dia = 86_400_000L
    private var n = 0
    private fun msg(cuando: Long = 10 * dia) = Mensaje(id = "m${++n}", cuando = cuando, clase = Clase.NOTA)

    @Test
    fun `el nombre va solo encima de la primera de cada tanda ajena`() {
        // Ana, Ana, Luis, yo, Luis (todo en el mismo rato: un tramo de día).
        val m = List(5) { msg() }
        val quien = listOf(Quien.Otro("Ana"), Quien.Otro("Ana"), Quien.Otro("Luis"), Quien.Yo, Quien.Otro("Luis"))
        val autores = EntrePersonas.numerar(quien)
        val s = sitios(m) { autores[m.indexOf(it)] }
        val conNombre = s.indices.map { EntrePersonas.conNombre(quien[it], !s[it].pegadoArriba) }
        // Ana abre (con nombre), Ana sigue (sin), Luis abre, yo nunca, Luis otra vez tras lo mío.
        assertEquals(listOf(true, false, true, false, true), conNombre)
        // Y en el tramo del día siguiente (con su separador), Luis abre otra vez.
        val otroDia = sitios(listOf(msg(11 * dia))) { 2 }
        assertTrue(EntrePersonas.conNombre(Quien.Otro("Luis"), !otroDia[0].pegadoArriba))
    }

    @Test
    fun `dos personas seguidas no se pegan aunque vayan al mismo lado`() {
        val m = List(3) { msg() }
        val autor = listOf(1, 1, 2)
        val s = sitios(m) { autor[m.indexOf(it)] }
        assertTrue(s[1].pegadoArriba)
        assertFalse(s[2].pegadoArriba)
        assertFalse(s[1].pegadoAbajo)
    }

    @Test
    fun `lo mio nunca lleva nombre`() {
        assertFalse(EntrePersonas.conNombre(Quien.Yo, true))
        assertFalse(EntrePersonas.conNombre(null, true))
    }

    @Test
    fun `en un chat normal se agrupa como siempre, solo por el rato`() {
        val m = List(3) { msg() }
        assertEquals(sitios(m), sitios(m) { 0 })
        assertTrue(sitios(m).drop(1).all { it.pegadoArriba })
    }

    @Test
    fun `lo ajeno recorta las esquinas de su lado`() {
        val s = com.forge.pixpin.guardados.Sitio(pegadoArriba = true, pegadoAbajo = false)
        val mio = com.forge.pixpin.guardados.radios(s)
        val suyo = com.forge.pixpin.guardados.radios(s, propio = false)
        assertEquals(com.forge.pixpin.guardados.RADIO_VECINO, mio.arribaDer)
        assertEquals(com.forge.pixpin.guardados.RADIO_VECINO, suyo.arribaIzq)
        assertEquals(com.forge.pixpin.guardados.RADIO_BURBUJA, suyo.arribaDer)
        assertEquals(18, com.forge.pixpin.guardados.relleno(s, propio = false).inicio)
    }
}
