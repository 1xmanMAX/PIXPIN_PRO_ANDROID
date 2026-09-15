package com.forge.pixpin.sincro

import com.forge.pixpin.guardados.Clase
import com.forge.pixpin.guardados.Mensaje
import com.forge.pixpin.guardados.reenviado
import com.forge.pixpin.motor.Hoja
import com.forge.pixpin.motor.Proyecto
import com.forge.pixpin.motor.Proyectos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Los tres códigos de cada cosa. Ver [Codigos]. */
class CodigosTest {

    private val m = Mensaje("id-1", 1_726_000_000_000, Clase.DIBUJO, numero = 47, aparato = "K7Q2", uid = "ABCDEFGHJK")

    @Test
    fun `el codigo de chat es el numero y el aparato, o la letra de antes`() {
        assertEquals("47·K7Q2", Codigos.deChat(m))
        assertEquals("47a", Codigos.deChat(m.copy(aparato = null, letra = "a")))
        assertNull(Codigos.deChat(m.copy(numero = 0)))
    }

    @Test
    fun `solo es lo mismo si coinciden los tres`() {
        assertTrue(Codigos.mismos(m, m.copy(id = "otro-id-aqui", texto = "cambiado")))
        assertFalse("otro código único", Codigos.mismos(m, m.copy(uid = "ZZZZZZZZZZ")))
        assertFalse("otro número", Codigos.mismos(m, m.copy(numero = 48)))
        assertFalse("otro aparato", Codigos.mismos(m, m.copy(aparato = "T9AB")))
        assertFalse("otra fecha", Codigos.mismos(m, m.copy(cuando = m.cuando + 1)))
    }

    @Test
    fun `lo de antes saca su codigo de su id y sale igual en todos los aparatos`() {
        val viejo = Mensaje("uuid-de-antes", 1, Clase.NOTA, numero = 3, letra = "a")
        val aqui = Codigos.sellar(viejo, "K7Q2")
        val alli = Codigos.sellar(viejo, "T9AB")
        assertEquals(aqui.uid, alli.uid)
        assertEquals(Codigos.LARGO, aqui.uid!!.length)
        // Lo que ya tiene letra no recibe aparato: su código de chat ya es el de antes.
        assertNull(aqui.aparato)
        // Lo que no tiene ni letra ni aparato es de este aparato.
        assertEquals("K7Q2", Codigos.sellar(viejo.copy(letra = null), "K7Q2").aparato)
        // Y sellar dos veces no cambia nada.
        assertEquals(aqui, Codigos.sellar(aqui, "K7Q2"))
    }

    @Test
    fun `los codigos nuevos no se repiten`() {
        val muchos = (1..20_000).map { Codigos.nuevo() }.toSet()
        assertEquals(20_000, muchos.size)
        assertTrue(muchos.all { c -> c.length == Codigos.LARGO && c.all { it in Grupo.SIGNOS } })
    }

    @Test
    fun `una copia a proposito lleva codigos nuevos`() {
        val copia = reenviado(m, "otro-proyecto", 5, "id-copia")
        assertNull(copia.uid)
        assertNull(copia.aparato)
        assertEquals(0, copia.numero)
        assertNotEquals(Codigos.unico(m), Codigos.unico(copia))
        val renovado = Codigos.renovar(m)
        assertNotEquals(m.uid, renovado.uid)
    }

    @Test
    fun `guardar una copia vieja del proyecto no le quita los codigos`() {
        val sellado = Codigos.sellar(Proyecto("pr-1726000000000", "Obra", hojas = listOf(Hoja("h1"))), "K7Q2")
        assertEquals(1_726_000_000_000, sellado.creado)
        assertEquals("K7Q2", sellado.aparato)
        val guardado = Proyectos.actualizada(listOf(sellado), Proyecto("pr-1726000000000", "Obra renombrada", hojas = listOf(Hoja("h1"), Hoja("h2"))))
            .single()
        assertEquals(sellado.uid, guardado.uid)
        assertEquals("K7Q2", guardado.aparato)
        assertEquals(sellado.hojas.single().uid, guardado.hojas.first().uid)
    }

    @Test
    fun `quitar una hoja a mano deja marca y volver a ponerla la quita`() {
        val p = Proyecto("p", "Obra", hojas = listOf(Hoja("h1"), Hoja("h2")))
        val sin = Proyectos.sinHoja(p, "h2", 5)
        assertEquals(listOf("h2"), sin.quitadas)
        // Un guardado cualquiera que pierde una hoja no deja marca: eso no es una decisión.
        assertTrue(Proyectos.actualizada(listOf(p), p.copy(hojas = listOf(Hoja("h2")))).single().quitadas.isEmpty())
        val vuelve = Proyectos.actualizada(listOf(sin), sin.copy(hojas = sin.hojas + Hoja("h2"))).single()
        assertTrue(vuelve.quitadas.isEmpty())
    }

    @Test
    fun `al recibir un proyecto cada hoja se reconoce por su codigo unico`() {
        val aqui = listOf(Hoja("hoja-1726000000001", uid = "UNOUNOUNO2"), Hoja("hoja-1726000000002", uid = "DOSDOSDOS3"))
        val sufijo = "-1726999999999"
        // Llegan con otros ids pero sus códigos: se emparejan por el código, no por el nombre.
        val llegan = listOf(Hoja("hoja-9$sufijo", uid = "DOSDOSDOS3"), Hoja("hoja-1726000000001$sufijo", uid = "OTRAOTRA45"))
        val r = Recepcion.parejas(aqui, llegan, sufijo)
        assertEquals("hoja-1726000000002", r["hoja-9$sufijo"]?.id)
        assertNull("mismo nombre pero otro código: es otra hoja", r["hoja-1726000000001$sufijo"])
    }
}
