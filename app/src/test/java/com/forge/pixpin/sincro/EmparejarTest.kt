package com.forge.pixpin.sincro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** **El enlace de emparejar** (3-nov-2026): las pruebas de `emparejar.rs` del PC. */
class EmparejarTest {
    private fun ejemplo() = Emparejar.Enlace("K7Q2M9XMPA", listOf("192.168.1.20", "10.0.0.5"), 47474, "PinDesk Casa & Ñu", 1_793_180_000_000)

    @Test
    fun `el enlace del qr tiene su forma y se lee igual`() {
        val t = ejemplo().texto()
        assertEquals("pindesk://emparejar?v=1&c=K7Q2M9XMPA&h=192.168.1.20,10.0.0.5&p=47474&n=PinDesk%20Casa%20%26%20%C3%91u&e=1793180000000", t)
        assertEquals(ejemplo(), Emparejar.Enlace.leer(t))
        val e = Emparejar.Enlace.leer("PIXPIN://Emparejar?c=k7q2m-9xmpa&p=47474")!!
        assertEquals("K7Q2M9XMPA", e.codigo)
        assertTrue(e.hosts.isEmpty() && e.nombre.isEmpty() && e.expira == null)
        val m = Emparejar.Enlace.leer("pindesk://emparejar?c=K7Q2M9XMPA&n=Mi+PC")!!
        assertEquals(47474 to "Mi PC", m.puerto to m.nombre)
    }

    @Test
    fun `caso negativo lo que no es un enlace de emparejar no se lee`() {
        for (malo in listOf("", "https://emparejar?c=K7Q2M9XMPA", "pindesk://atajo/tareas?c=K7Q2M9XMPA", "pindesk://emparejar?c=K7Q2",
            "pindesk://emparejar?p=47474", "pindesk://emparejar?v=2&c=K7Q2M9XMPA", "pindesk://emparejar?c=K7Q2M9XMPA&p=0",
            "pindesk://emparejar?c=K7Q2M9XMPA&p=99999", "pixpin-envio:1:482913:1.2.3.4:5")) assertNull(malo, Emparejar.Enlace.leer(malo))
        assertEquals(listOf("192.168.1.9"), Emparejar.Enlace.leer("pindesk://emparejar?c=K7Q2M9XMPA&h=nada,192.168.1.9,::1")!!.hosts)
    }

    @Test
    fun `caduca con margen y solo lleva direcciones de la red local`() {
        val e = ejemplo()
        val fin = e.expira!!
        assertTrue(e.vigente(fin + Emparejar.MARGEN_RELOJ_MS))
        assertFalse(e.vigente(fin + Emparejar.MARGEN_RELOJ_MS + 1))
        assertEquals(
            listOf("192.168.1.20", "100.101.7.2", "10.1.1.1", "172.16.0.9"),
            Emparejar.direccionesParaElEnlace(listOf("192.168.1.20", "8.8.8.8", "192.168.1.20", "100.101.7.2", "127.0.0.1", "10.1.1.1", "172.16.0.9", "172.17.0.9"))
        )
    }
}
