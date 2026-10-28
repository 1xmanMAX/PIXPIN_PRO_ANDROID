package com.forge.pixpin.asistente.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **La guarda contra SSRF** (28-oct-2026): las pruebas de `direccion.rs` de `pixpin-paginas` del
 * PC, todas, con los mismos casos.
 */
class DireccionWebTest {
    private fun ok(t: String) = Direccion.leerONull(t) != null
    private fun ip(t: String) = Direccion.Ip.de(t)!!

    @Test fun `lee las direcciones normales`() {
        var u = Direccion.leer("https://www.ugr.es/estudiantes/horarios?curso=2026#lunes")
        assertTrue(u.segura)
        assertEquals("www.ugr.es", u.servidor)
        assertEquals(443, u.puerto)
        assertEquals("/estudiantes/horarios?curso=2026", u.ruta)
        assertEquals("ugr.es", u.dominio())
        assertEquals("https://www.ugr.es/estudiantes/horarios?curso=2026", u.texto())
        u = Direccion.leer("HTTP://Example.COM:8080")
        assertFalse(u.segura)
        assertEquals(Triple("example.com", 8080, "/"), Triple(u.servidor, u.puerto, u.ruta))
        // Sin esquema: https.
        assertEquals("https://ugr.es/grados", Direccion.leer("ugr.es/grados").texto())
        // Tildes y espacios, en %XX.
        assertEquals("/wiki/F%C3%ADsica%20cu%C3%A1ntica", Direccion.leer("https://es.wikipedia.org/wiki/Física cuántica").ruta)
        // Una IP pública, sí.
        assertTrue(ok("http://93.184.215.14/"))
        assertTrue(ok("https://[2606:4700::1111]/"))
        assertEquals("https://[2606:4700::1111]/", Direccion.leer("https://[2606:4700::1111]/").texto())
    }

    @Test fun `solo http y https`() {
        for (mala in listOf(
            "file:///C:/Windows/win.ini", "javascript:alert(1)", "data:text/html,<b>x</b>", "ftp://ftp.ejemplo.com/a",
            "mailto:yo@ejemplo.com", "ms-settings:privacy", "", "hola que tal",
            // Las del teléfono, tampoco.
            "content://com.android.contacts/contacts", "intent://scan/#Intent;scheme=zxing;end", "file:///sdcard/Download/x.pdf"
        )) assertFalse(mala, ok(mala))
    }

    @Test fun `nada de este telefono ni de la red de casa`() {
        for (mala in listOf(
            "http://localhost/", "http://localhost:8080/admin", "http://app.localhost/", "http://127.0.0.1/", "http://127.1/",
            "http://2130706433/", "http://0x7f000001/", "http://0x7f.0.0.1/", "http://017700000001/", "http://0.0.0.0/",
            "http://10.0.0.5/", "http://172.16.3.4/", "http://172.31.255.255/", "http://192.168.1.1/",
            "http://169.254.169.254/latest/meta-data/", "http://100.64.0.1/", "http://224.0.0.1/", "http://255.255.255.255/",
            "http://[::1]/", "http://[::]/", "http://[fe80::1]/", "http://[fd00::1]/", "http://[::ffff:127.0.0.1]/",
            "http://[::ffff:192.168.0.1]/", "http://[64:ff9b::a00:1]/", "http://[2002:c0a8:101::]/", "http://router/",
            "http://nas.local/", "http://impresora.lan/", "http://srv.corp/", "http://casa.home.arpa/",
            "http://yo:clave@ejemplo.com/", "https://ejemplo.com@192.168.1.1/",
            // Más del teléfono: el emulador, la documentación y una IPv6 con zona.
            "http://10.0.2.2:8080/", "http://192.0.2.1/", "http://198.51.100.7/", "http://203.0.113.9/", "http://[2001:db8::1]/",
            "http://[fe80::1%25wlan0]/", "http://srv.internal/", "http://srv.intranet/"
        )) assertFalse(mala, ok(mala))
        // Lo público que se parece, sí.
        assertTrue(ok("http://172.32.0.1/"))
        assertTrue(ok("http://localhost.ejemplo.com/"))
        assertTrue(ok("https://www.local-news.com/"))
    }

    @Test fun `los motivos se dicen`() {
        val e = runCatching { Direccion.leer("http://192.168.1.1/") }.exceptionOrNull()
        assertTrue(e is Direccion.NoSeAbre && e.message!!.contains("red local"))
        assertTrue(runCatching { Direccion.leer("javascript:alert(1)") }.exceptionOrNull()!!.message!!.contains("http://"))
        assertTrue(runCatching { Direccion.leer("http://yo:clave@ejemplo.com/") }.exceptionOrNull()!!.message!!.contains("contrasena"))
    }

    @Test fun `las ip resueltas tambien cuentan`() {
        val publica = ip("93.184.215.14")
        val casa = ip("192.168.0.10")
        val v6 = ip("2606:4700::1111")
        Direccion.comprobarIps("ejemplo.com", listOf(publica, v6))
        assertTrue(runCatching { Direccion.comprobarIps("trampa.com", listOf(publica, casa)) }.isFailure)
        assertTrue(runCatching { Direccion.comprobarIps("trampa.com", listOf(ip("::1"))) }.isFailure)
        assertTrue(runCatching { Direccion.comprobarIps("nada.com", emptyList()) }.isFailure)
    }

    @Test fun `las ip se leen estrictas`() {
        assertNull(Direccion.Ip.de("127.1"))
        assertNull(Direccion.Ip.de("010.0.0.1"))
        assertNull(Direccion.Ip.de("256.0.0.1"))
        assertNull(Direccion.Ip.de("1:2:3:4:5:6:7:8:9"))
        assertNull(Direccion.Ip.de("fe80::1%wlan0"))
        assertNull(Direccion.Ip.de("ugr.es"))
        assertEquals("0:0:0:0:0:ffff:7f00:1", ip("::ffff:127.0.0.1").toString())
        assertEquals("1:0:0:0:0:0:0:0", ip("1::").toString())
        assertFalse(Direccion.ipPublica(ip("::ffff:10.1.2.3")))
        assertTrue(Direccion.ipPublica(ip("::ffff:8.8.8.8")))
        assertTrue(Direccion.ipPublica(ip("2a00:1450:4003:80b::200e")))
    }

    @Test fun `resuelve los enlaces de la pagina`() {
        val base = Direccion.leer("https://ugr.es/estudiantes/grados/index.html?x=1")
        fun r(e: String) = Direccion.resolver(base, e)
        assertEquals("https://otra.com/a", r("https://otra.com/a"))
        assertEquals("https://cdn.ugr.es/h.pdf", r("//cdn.ugr.es/h.pdf"))
        assertEquals("https://ugr.es/horarios", r("/horarios"))
        assertEquals("https://ugr.es/estudiantes/grados/index.html?x=2", r("?x=2"))
        assertEquals("https://ugr.es/estudiantes/grados/horario.pdf", r("horario.pdf"))
        assertEquals("https://ugr.es/estudiantes/becas/", r("../becas/"))
        assertEquals("https://ugr.es/estudiantes/grados/b", r("./a/../b#c"))
        assertEquals("https://ugr.es/x", r("../../../../x"))
        for (nada in listOf("#arriba", "mailto:a@b.c", "javascript:void(0)", "tel:+34", "")) assertNull(nada, r(nada))
    }

    @Test fun `codifica y decodifica`() {
        assertEquals("horario+ugr+2%C2%BA", Direccion.codificar("horario ugr 2º"))
        assertEquals("https://a.com/b?c=1", Direccion.decodificar("https%3A%2F%2Fa.com%2Fb%3Fc%3D1", false))
        assertEquals("a b%2", Direccion.decodificar("a+b%2", true))
        assertEquals("%zz", Direccion.decodificar("%zz", false))
    }

    @Test fun `compara sin esquema ni www ni barra`() {
        assertEquals(Direccion.paraComparar("https://www.UGR.es/horarios/#x"), Direccion.paraComparar("http://ugr.es/horarios"))
        assertNotEquals(Direccion.paraComparar("https://ugr.es/a"), Direccion.paraComparar("https://ugr.es/b"))
        assertNull(Direccion.paraComparar("http://localhost/"))
    }
}
