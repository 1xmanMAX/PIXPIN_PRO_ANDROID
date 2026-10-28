package com.forge.pixpin.sincro

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * **La baliza contra el PC de verdad** (28-oct-2026): `pixpin_sincro::baliza` en Rust, por la orden
 * `baliza` de `herramientas/pc-simulado`. Lo que escribe Android lo lee el PC y al revés, la firma
 * de uno se comprueba en el otro, y por sockets UDP de verdad en los dos sentidos (el `Emisor` y el
 * `Oyente` del PC contra [EmisorDeBalizas] y [OyenteDeBalizas]).
 *
 * Solo corre con `PIXPIN_PC_SIMULADO=<ruta del programa>` (ver `herramientas/pc-simulado`).
 */
class BalizaContraElPcTest {

    private val programa = System.getenv("PIXPIN_PC_SIMULADO")
    private val codigo = "K7Q2M9XMPA"
    private val clave by lazy { Grupo.clave(codigo) }

    @Before
    fun hayPc() {
        assumeTrue("Sin PIXPIN_PC_SIMULADO no hay PC con quien hablar", programa != null && File(programa).canExecute())
    }

    private fun pc(vararg a: String): String {
        val p = ProcessBuilder(listOf(programa, "baliza") + a).redirectErrorStream(true).start()
        val salida = p.inputStream.bufferedReader().readText().trim()
        p.waitFor(30, TimeUnit.SECONDS)
        return salida
    }

    /**
     * Un texto para la línea de órdenes del PC: la JVM de las pruebas codifica los argumentos con
     * el juego de caracteres del sistema (con `POSIX`, «ó» sale «?»), así que lo que no es ASCII va
     * en `hex:` con sus bytes UTF-8.
     */
    private fun enArgumento(t: String) = if (t.all { it.code < 128 }) t else "hex:" + Baliza.hex(t.toByteArray(Charsets.UTF_8))

    private fun json(t: String): JsonObject = Json.parseToJsonElement(t).jsonObject
    private fun JsonObject.texto(k: String) = getValue(k).jsonPrimitive.content

    private fun comoLaVe(o: JsonObject) = Baliza(
        etiqueta = o.texto("etiqueta"), id = o.texto("id"), puerto = o.getValue("puerto").jsonPrimitive.int,
        reloj = o.getValue("reloj").jsonPrimitive.long, secuencia = o.getValue("secuencia").jsonPrimitive.long,
        banderas = o.getValue("banderas").jsonPrimitive.int, grabacion = o.texto("grabacion"),
        msActual = o.getValue("ms_actual").jsonPrimitive.long
    )

    private val casos by lazy {
        listOf(
            BalizaTest.ejemplo(),
            // El teléfono grabando en pausa, con su id y una letra de dos bytes.
            Baliza(Baliza.etiquetaDe(clave), "móvil-de-Max", 47474, 1_793_178_005_123, 4_000_000_000, Baliza.GRABANDO or Baliza.EN_PAUSA, "m-1793178000000", 35_000),
            // Al abrir PixPin: pregunta, sin grabar, sin puerto.
            Baliza(Baliza.etiquetaDe(clave), "6f1c2d3e-aaaa-bbbb-cccc-0123456789ab", 0, 1_761_559_500_001, 1, Baliza.PREGUNTA, "", 0),
            // Lo más largo.
            Baliza(Baliza.etiquetaDe(clave), "a".repeat(64), 65535, 0, 0xffff_ffffL, 0x0f, "g".repeat(64), Long.MAX_VALUE)
        )
    }

    @Test
    fun lo_que_escribe_android_lo_lee_el_pc() {
        for (b in casos) {
            val dicho = json(pc("leer", codigo, Baliza.hex(b.escribir(clave))))
            assertEquals(b, comoLaVe(dicho))
        }
    }

    @Test
    fun lo_que_escribe_el_pc_lo_lee_android_byte_a_byte() {
        for (b in casos) {
            val hex = pc(
                "escribir", codigo, enArgumento(b.id), b.puerto.toString(), b.reloj.toString(), b.secuencia.toString(),
                b.banderas.toString(), if (b.grabacion.isEmpty()) "-" else enArgumento(b.grabacion), b.msActual.toString()
            )
            assertEquals("los mismos bytes", Baliza.hex(b.escribir(clave)), hex)
            assertEquals(b, (Baliza.leer(Baliza.deHex(hex), clave) as Baliza.Valida).baliza)
        }
        assertEquals(BalizaTest.VECTOR, pc("escribir", codigo, "pc-portatil", "47474", "1761559500000", "7", "9", "g-1761559500000", "754000"))
    }

    @Test
    fun el_pc_tira_lo_que_android_no_firma_bien() {
        val otra = Grupo.clave("K7Q2M9XMPB")
        val deOtroGrupo = BalizaTest.ejemplo().copy(etiqueta = Baliza.etiquetaDe(otra)).escribir(otra)
        assertEquals("OtroGrupo", json(pc("leer", codigo, Baliza.hex(deOtroGrupo))).texto("rechazo"))
        val falsa = BalizaTest.ejemplo().escribir(otra).also { System.arraycopy(Baliza.etiquetaCruda(clave), 0, it, 6, 8) }
        assertEquals("Firma", json(pc("leer", codigo, Baliza.hex(falsa))).texto("rechazo"))
        val tocada = BalizaTest.ejemplo().escribir(clave).also { it[15] = (it[15].toInt() xor 1).toByte() }
        assertEquals("Firma", json(pc("leer", codigo, Baliza.hex(tocada))).texto("rechazo"))
    }

    @Test
    fun por_udp_de_android_al_pc() {
        val puerto = 47_000 + (System.nanoTime() % 900).toInt()
        val p = ProcessBuilder(programa, "baliza", "oir", codigo, puerto.toString(), "10").start()
        val lector = p.inputStream.bufferedReader()
        assertEquals("oyendo", lector.readLine())
        val b = casos[1]
        val emisor = EmisorDeBalizas.abrir(puerto, emptyList())
        try {
            assertEquals(1, emisor.a(b.escribir(clave), listOf(Ipv4.de("127.0.0.1"))))
        } finally {
            emisor.cerrar()
        }
        val oido = json(lector.readLine())
        p.waitFor(15, TimeUnit.SECONDS)
        assertEquals("127.0.0.1", oido.texto("de"))
        assertEquals(b, comoLaVe(oido))
    }

    @Test
    fun por_udp_del_pc_a_android() {
        val oyente = OyenteDeBalizas.abrir(0, emptyList(), esperaMs = 5_000)
        try {
            assertEquals("1", pc("mandar", codigo, oyente.puerto.toString(), "pc-portatil", "1761559500000", "8", "9", "g-1761559500000", "755000"))
            val buf = ByteArray(Baliza.TOPE)
            val (n, de) = oyente.oir(buf)!!
            assertEquals("127.0.0.1", Ipv4.texto(de))
            val b = (Baliza.leer(buf, clave, n) as Baliza.Valida).baliza
            assertEquals(BalizaTest.ejemplo().copy(secuencia = 8, msActual = 755_000), b)
            assertEquals(true, Oidas().admitir(b, b.reloj + 7_000))
        } finally {
            oyente.cerrar()
        }
    }

    @Test
    fun el_barrido_es_el_mismo() {
        for ((red, conocidas) in listOf(
            Interfaz.de("192.168.1.20", 24) to listOf("192.168.1.37", "10.0.0.5"),
            Interfaz.de("192.168.1.20", 24) to listOf("192.168.1.1", "192.168.1.254"),
            Interfaz.de("10.1.2.3", 16) to listOf("10.1.7.7"),
            Interfaz.de("172.20.10.2", 28) to emptyList(),
            Interfaz.de("10.8.0.2", 32) to listOf("10.8.0.1"),
            Interfaz.de("192.168.0.9", 22) to listOf("192.168.3.250")
        )) {
            val delPc = pc("barrido", Ipv4.texto(red.ip), red.prefijo.toString(), conocidas.joinToString(",").ifEmpty { "-" })
            val aqui = Baliza.barrido(red, conocidas.map(Ipv4::de)).joinToString(",") { Ipv4.texto(it) }
            assertEquals("${Ipv4.texto(red.ip)}/${red.prefijo}", delPc, aqui)
        }
    }
}
