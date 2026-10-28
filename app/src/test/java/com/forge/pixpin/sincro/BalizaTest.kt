package com.forge.pixpin.sincro

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **La baliza, byte a byte como el PC** (28-oct-2026): las mismas pruebas que `mod pruebas` de
 * `crates/pixpin-sincro/src/baliza.rs` (995fd94b), con su vector (calculado también aparte, con
 * Python, en el PC). Si el vector cambia, cambia el cable y hay que cambiarlo en los dos lados.
 */
class BalizaTest {

    companion object {
        /** `VECTOR` de `baliza.rs`, el de §11.2 de la guía. */
        const val VECTOR = "50584231010909df6bf6b40e77f0b9720000019a2520d4e0000000070000" +
            "0000000b81500b70632d706f72746174696c0f672d31373631353539353030" +
            "30303041e37ec235431670bb92572185a1c4e2"

        val clave: ByteArray by lazy { Grupo.clave("K7Q2M9XMPA") }

        fun ejemplo() = Baliza(
            etiqueta = Baliza.etiquetaDe(clave),
            id = "pc-portatil",
            puerto = 47474,
            reloj = 1_761_559_500_000,
            secuencia = 7,
            banderas = Baliza.GRABANDO or Baliza.CAMARA,
            grabacion = "g-1761559500000",
            msActual = 754_000
        )

        fun leida(bytes: ByteArray, k: ByteArray = clave): Baliza? = (Baliza.leer(bytes, k) as? Baliza.Valida)?.baliza
        fun rechazo(bytes: ByteArray, k: ByteArray = clave): Baliza.Rechazo? = (Baliza.leer(bytes, k) as? Baliza.Rechazada)?.por
        fun ip(t: String) = Ipv4.de(t)
    }

    @Test
    fun la_baliza_va_y_vuelve_igual() {
        val b = ejemplo()
        val bytes = b.escribir(clave)
        assertEquals(Baliza.CABEZA + 2 + 11 + 15 + Baliza.LARGO_FIRMA, bytes.size)
        assertEquals(b, leida(bytes))
        assertTrue(b.graba && b.camara && !b.enPausa && !b.pregunta)
    }

    @Test
    fun el_vector_del_pc_sale_igual() {
        val bytes = ejemplo().escribir(clave)
        assertEquals("la etiqueta del mDNS", "09df6bf6b40e77f0", Baliza.hex(bytes.copyOfRange(6, 14)))
        assertEquals("la misma que la «g» del anuncio", Grupo.etiqueta(clave), Baliza.etiquetaDe(clave))
        assertEquals(VECTOR, Baliza.hex(bytes))
        // Y al revés: los bytes del PC se leen en Kotlin.
        assertEquals(ejemplo(), leida(Baliza.deHex(VECTOR)))
    }

    @Test
    fun quien_no_es_del_grupo_no_puede_fingirla() {
        val bytes = ejemplo().escribir(clave)
        val otra = Grupo.clave("K7Q2M9XMPB")
        // Con otra clave ni siquiera es de este grupo.
        assertEquals(Baliza.Rechazo.OTRO_GRUPO, rechazo(bytes, otra))
        // Quien copia nuestra etiqueta (es pública) y firma con otra clave: la firma no cuadra.
        val falsa = ejemplo().copy(etiqueta = Baliza.etiquetaDe(clave))
        assertEquals(Baliza.Rechazo.FIRMA, rechazo(falsa.escribir(otra)))
        // Tocar un solo byte (el puerto) también la rompe.
        val tocada = bytes.copyOf().also { it[15] = (it[15].toInt() xor 1).toByte() }
        assertEquals(Baliza.Rechazo.FIRMA, rechazo(tocada))
        // Y cortada, o de otra cosa, o de otra versión.
        assertEquals(Baliza.Rechazo.ROTA, rechazo(bytes.copyOf(20)))
        assertEquals(Baliza.Rechazo.NO_ES, rechazo("PING".toByteArray()))
        assertEquals(Baliza.Rechazo.NO_ES, rechazo(ByteArray(0)))
        val v = bytes.copyOf().also { it[4] = 2 }
        assertEquals(Baliza.Rechazo.VERSION, rechazo(v))
        // Más larga que el tope: rota sin mirar nada más.
        assertEquals(Baliza.Rechazo.ROTA, rechazo(bytes + ByteArray(Baliza.TOPE)))
    }

    @Test
    fun los_largos_que_no_cuadran_y_el_id_vacio_no_valen_aunque_esten_firmados() {
        // Firmada de verdad pero sin id: rota.
        assertEquals(Baliza.Rechazo.ROTA, rechazo(ejemplo().copy(id = "").escribir(clave)))
        // Un largo de texto que se sale, re-firmado para que la firma no sea lo que la tira.
        val b = ejemplo().escribir(clave)
        val datos = b.copyOf(b.size - Baliza.LARGO_FIRMA).also { it[Baliza.CABEZA] = 60 }
        val refirmada = datos + Grupo.hmac(Baliza.claveBaliza(clave), datos).copyOf(Baliza.LARGO_FIRMA)
        assertEquals(Baliza.Rechazo.ROTA, rechazo(refirmada))
        // UTF-8 roto en el id, firmado: rota (como `String::from_utf8`).
        val malo = b.copyOf(b.size - Baliza.LARGO_FIRMA).also { it[Baliza.CABEZA + 1] = 0xff.toByte() }
        assertEquals(Baliza.Rechazo.ROTA, rechazo(malo + Grupo.hmac(Baliza.claveBaliza(clave), malo).copyOf(Baliza.LARGO_FIRMA)))
    }

    @Test
    fun la_mas_corta_y_la_mas_larga() {
        val corta = ejemplo().copy(id = "x", grabacion = "").escribir(clave)
        assertEquals("55 bytes, lo más corto que vale", 55, corta.size)
        assertNotNull(leida(corta))
        val larga = ejemplo().copy(id = "a".repeat(64), grabacion = "b".repeat(64)).escribir(clave)
        assertEquals(Baliza.TOPE, larga.size)
        assertEquals(182, Baliza.TOPE)
        assertNotNull(leida(larga))
    }

    @Test
    fun los_textos_largos_se_recortan_sin_partir_letras() {
        val b = ejemplo().copy(id = "ñ".repeat(40), grabacion = "x".repeat(100))
        val l = leida(b.escribir(clave))!!
        assertEquals("ñ".repeat(32), l.id)
        assertEquals(Baliza.TOPE_TEXTO, l.grabacion.length)
        assertTrue(b.escribir(clave).size <= Baliza.TOPE)
        // Una letra de tres bytes que cae en el corte no se parte.
        assertEquals(62, Baliza.recortar("ab" + "€".repeat(30)).size)
    }

    @Test
    fun los_numeros_enteros_sin_signo_y_negativos() {
        val b = ejemplo().copy(puerto = 65535, secuencia = 0xffff_ffffL, msActual = -1, reloj = 0, banderas = 0xff)
        val l = leida(b.escribir(clave))!!
        assertEquals(65535, l.puerto)
        assertEquals(0xffff_ffffL, l.secuencia)
        assertEquals(-1L, l.msActual)
        assertEquals(0xff, l.banderas)
    }

    @Test
    fun la_repetida_se_tira_y_la_vieja_tambien() {
        val o = Oidas()
        val b = ejemplo()
        val ahora = b.reloj + 7_000 // el otro va 7 s atrasado
        assertTrue(o.admitir(b, ahora))
        assertFalse("la misma por otro camino", o.admitir(b, ahora))
        assertTrue(o.admitir(b.copy(secuencia = 8), ahora))
        assertFalse("una anterior que llega tarde", o.admitir(b.copy(secuencia = 7), ahora))
        // Una grabada hace una hora y reenviada.
        assertFalse(o.admitir(b.copy(id = "otro", reloj = ahora - 3_600_000), ahora))
        // Un reloj que va hacia atrás más que el desfase (se cambió la hora) también entra.
        val atras = b.copy(reloj = b.reloj - Baliza.DESFASE_MAXIMO_MS, secuencia = 1)
        assertTrue(o.admitir(atras, atras.reloj))
    }

    @Test
    fun las_redes_y_su_difusion() {
        val r = Interfaz.de("192.168.1.20", 24)
        assertEquals("192.168.1.255", Ipv4.texto(r.difusion()))
        assertTrue(r.contiene(ip("192.168.1.99")))
        assertFalse(r.contiene(ip("192.168.2.99")))
        val vpn = Interfaz.de("10.8.0.2", 32)
        assertEquals(vpn.ip, vpn.difusion())
        assertArrayEquals(byteArrayOf(192.toByte(), 168.toByte(), 1, 20), Ipv4.bytes(r.ip))
    }

    @Test
    fun el_barrido_empieza_por_las_conocidas_y_sus_vecinas() {
        val r = Interfaz.de("192.168.1.20", 24)
        val v = Baliza.barrido(r, listOf(ip("192.168.1.37"), ip("10.0.0.5")))
        assertEquals(ip("192.168.1.37"), v[0])
        assertEquals(ip("192.168.1.38"), v[1])
        assertEquals(ip("192.168.1.36"), v[2])
        assertEquals("la /24 menos la propia, la de red y la de difusión", 253, v.size)
        assertFalse(r.ip in v)
        assertFalse(ip("192.168.1.255") in v)
        assertFalse(ip("192.168.1.0") in v)
        assertFalse("de otra red", ip("10.0.0.5") in v)
        // Una red enorme (una /16 de empresa): solo las vecinas.
        val grande = Interfaz.de("10.1.2.3", 16)
        assertEquals(17, Baliza.barrido(grande, listOf(ip("10.1.7.7"))).size)
        assertTrue(Baliza.barrido(grande, emptyList()).isEmpty())
        // Lo que dice el PC para esa misma (`pc-simulado baliza barrido 10.1.2.3 16 10.1.7.7`).
        assertEquals(
            "10.1.7.7,10.1.7.8,10.1.7.6,10.1.7.9,10.1.7.5,10.1.7.10,10.1.7.4,10.1.7.11,10.1.7.3,10.1.7.12," +
                "10.1.7.2,10.1.7.13,10.1.7.1,10.1.7.14,10.1.7.0,10.1.7.15,10.1.6.255",
            Baliza.barrido(grande, listOf(ip("10.1.7.7"))).joinToString(",") { Ipv4.texto(it) }
        )
    }

    @Test
    fun el_ritmo_grabando_es_de_un_segundo_y_al_momento_si_cambia() {
        val r = Ritmo()
        assertTrue("la primera, ya", r.toca(0, false, false))
        assertFalse(r.toca(1_000, false, false))
        assertTrue(r.toca(Baliza.CADA_EN_REPOSO_MS, false, false))
        // Empieza a grabar: al momento, sin esperar al reposo.
        assertTrue(r.toca(15_100, true, true))
        assertFalse(r.toca(15_600, true, false))
        assertTrue(r.toca(16_100, true, false))
        // Un reloj que va hacia atrás no la deja muda.
        assertTrue(r.toca(100, true, false))
    }

    @Test
    fun se_barre_solo_grabando_y_sin_oir_a_nadie() {
        val r = Ritmo()
        assertFalse(r.tocaBarrer(0, false, null))
        assertFalse("aún es pronto", r.tocaBarrer(0, true, null))
        assertTrue(r.tocaBarrer(Baliza.BARRER_TRAS_MS, true, null))
        assertFalse(r.tocaBarrer(Baliza.BARRER_TRAS_MS + 1_000, true, null))
        assertTrue(r.tocaBarrer(Baliza.BARRER_TRAS_MS + Baliza.BARRER_CADA_MS, true, null))
        // Se oye al PC: no se barre.
        assertFalse(r.tocaBarrer(60_000, true, 800))
        // Y si se deja de oír, se vuelve a esperar los 5 s antes de barrer.
        assertFalse(r.tocaBarrer(61_000, true, 6_000))
        assertTrue(r.tocaBarrer(66_000, true, 11_000))
    }

    @Test
    fun grabando_solo_va_la_camara_y_al_acabar_la_grabacion_primero() {
        val t = Baliza.Trabajo.entries
        assertEquals(Baliza.Trabajo.CAMARA, Baliza.queToca(t.reversed(), true))
        assertEquals(null, Baliza.queToca(listOf(Baliza.Trabajo.PERSONAS, Baliza.Trabajo.VUELTA_ENTERA, Baliza.Trabajo.TAREAS), true))
        assertEquals(
            Baliza.Trabajo.ARCHIVOS_DE_LA_GRABACION,
            Baliza.queToca(listOf(Baliza.Trabajo.PERSONAS, Baliza.Trabajo.VUELTA_ENTERA, Baliza.Trabajo.TAREAS, Baliza.Trabajo.ARCHIVOS_DE_LA_GRABACION), false)
        )
        assertEquals(Baliza.Trabajo.TAREAS, Baliza.queToca(listOf(Baliza.Trabajo.PERSONAS, Baliza.Trabajo.VUELTA_ENTERA, Baliza.Trabajo.TAREAS), false))
        assertEquals(null, Baliza.queToca(emptyList(), false))
    }

    @Test
    fun el_diagnostico_distingue_otra_red_de_una_wifi_que_aisla() {
        val wifi = listOf(Interfaz.de("192.168.1.20", 24))
        val pc = listOf(ip("192.168.1.37"))
        val fuera = listOf(ip("172.20.10.2"))
        assertEquals(Baliza.Diagnostico.BIEN, Baliza.diagnosticar(5_000, false, wifi, pc))
        assertEquals(Baliza.Diagnostico.BIEN, Baliza.diagnosticar(30_000, true, wifi, pc))
        assertEquals(Baliza.Diagnostico.AISLADOS, Baliza.diagnosticar(30_000, false, wifi, pc))
        assertEquals(Baliza.Diagnostico.OTRA_RED, Baliza.diagnosticar(30_000, false, wifi, fuera))
        assertEquals(Baliza.Diagnostico.BIEN, Baliza.diagnosticar(30_000, false, wifi, emptyList()))
    }

    @Test
    fun por_los_sockets_de_verdad_en_loopback() {
        // Lo que hace el teléfono: el emisor manda directa y el oyente (atado a 0.0.0.0) la oye.
        val oyente = OyenteDeBalizas.abrir(0, emptyList(), esperaMs = 2_000)
        val emisor = EmisorDeBalizas.abrir(oyente.puerto, emptyList())
        try {
            assertEquals(1, emisor.a(ejemplo().escribir(clave), listOf(ip("127.0.0.1"))))
            val buf = ByteArray(Baliza.TOPE)
            val (n, de) = oyente.oir(buf)!!
            assertEquals("127.0.0.1", Ipv4.texto(de))
            assertEquals(ejemplo(), (Baliza.leer(buf, clave, n) as Baliza.Valida).baliza)
        } finally {
            emisor.cerrar(); oyente.cerrar()
        }
    }

    @Test
    fun las_redes_de_datos_moviles_no_cuentan() {
        assertTrue(RedesDelAparato.esDeDatos("rmnet_data0"))
        assertTrue(RedesDelAparato.esDeDatos("ccmni1"))
        assertFalse(RedesDelAparato.esDeDatos("wlan0"))
        assertFalse(RedesDelAparato.esDeDatos("tun0"))
        assertTrue(RedesDelAparato.orden("wlan0") < RedesDelAparato.orden("tun0"))
    }
}
