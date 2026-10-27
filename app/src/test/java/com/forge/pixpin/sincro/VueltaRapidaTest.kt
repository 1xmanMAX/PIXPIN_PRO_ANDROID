package com.forge.pixpin.sincro

import com.forge.pixpin.guardados.Clase
import com.forge.pixpin.guardados.Mensaje
import com.forge.pixpin.motor.Proyecto
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Cuánto más rápida es la vuelta con el diario** (27-oct-2026): una medida, no una prueba de
 * velocidad fina. Dos aparatos con 30 chats de 120 mensajes, 6 lienzos y 6 archivos cada uno; se
 * mide una vuelta sin cambios y otra con un mensaje y un lienzo cambiados, con el diario y sin él
 * (como antes: copia de seguridad, inventario, resumir cada archivo y cerrar, en cada chat y en los
 * dos lados). Imprime los números; exige solo que con el diario sea varias veces más rápida.
 */
class VueltaRapidaTest {

    private val raiz: File = Files.createTempDirectory("vuelta-rapida").toFile()
    private val a = Disco(File(raiz, "a/files").apply { mkdirs() })
    private val b = Disco(File(raiz, "b/files").apply { mkdirs() })
    private var reloj = 1_000_000L

    @After
    fun desmontar() {
        Diario.activo = true
        raiz.deleteRecursively()
    }

    private fun conectado(uso: (Sesion) -> Unit): Long {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val hilo = Thread {
            runCatching { server.accept().use { s -> Respondedor(b, ahora = { reloj }).atender(s.getInputStream(), s.getOutputStream()) } }
            server.close()
        }.apply { start() }
        var bytes = 0L
        Socket(InetAddress.getLoopbackAddress(), server.localPort).use { s ->
            val sesion = Sesion.conectar(s.getInputStream(), s.getOutputStream(), a, ahora = { reloj })
            uso(sesion)
            bytes = sesion.enviados + sesion.recibidos
            sesion.adios(); sesion.soltar()
        }
        hilo.join(10_000)
        return bytes
    }

    private val chats = (1..30).map { "p$it" }

    private class Medida(val ms: Double, val bytes: Long, val hecho: Sesion.Hecho)

    private fun vuelta(): Medida {
        val hecho = Sesion.Hecho()
        val t0 = System.nanoTime()
        val bytes = conectado { it.vuelta(chats, hecho) }
        return Medida((System.nanoTime() - t0) / 1e6, bytes, hecho)
    }

    private fun lienzo(d: Disco, id: String, texto: String) {
        val elementos = (1..120).joinToString(",") { """{"id":"e$it","type":"rectangle","x":$it,"y":${it * 2},"text":"$texto $it"}""" }
        File(d.filesDir, "pins/draw/$id.excalidraw.gz").apply { parentFile!!.mkdirs() }.outputStream().use { o ->
            GZIPOutputStream(o).use { it.write("""{"elements":[$elementos],"files":{}}""".toByteArray()) }
        }
    }

    private fun montar() {
        val codigo = a.crearGrupo(codigo = "ABCDE23456", ahora = reloj)
        b.identidad.guardar(Identidad(Aparato("id-b", "B")))
        a.identidad.leer().let { a.identidad.guardar(it) }
        // B se une al grupo de A.
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val hilo = Thread { runCatching { server.accept().use { s -> Respondedor(a).atender(s.getInputStream(), s.getOutputStream()) } }; server.close() }.apply { start() }
        Socket(InetAddress.getLoopbackAddress(), server.localPort).use { s ->
            Sesion.conectar(s.getInputStream(), s.getOutputStream(), b, unirme = true, codigo = codigo).also { it.adios(); it.soltar() }
        }
        hilo.join(5000)
        val lineas = StringBuilder()
        val rnd = java.util.Random(3)
        for (c in chats) {
            a.guardarProyecto(Proyecto(id = c, nombre = "Chat $c"))
            for (i in 1..120) lineas.append(Disco.JSON.encodeToString(Mensaje.serializer(),
                Mensaje(id = "$c-m$i", cuando = reloj++, clase = Clase.NOTA, texto = "Nota $i del chat $c, con algo de texto para que pese como una de verdad.", proyecto = c, numero = i, letra = "a"))).append('\n')
            for (i in 1..6) {
                lienzo(a, "$c-l$i", "chat $c")
                lineas.append(Disco.JSON.encodeToString(Mensaje.serializer(),
                    Mensaje(id = "$c-d$i", cuando = reloj++, clase = Clase.DIBUJO, referencia = "$c-l$i", proyecto = c, numero = 200 + i, letra = "a"))).append('\n')
                val f = File(a.filesDir, "guardados/$c-f$i.bin").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(64 * 1024).also { rnd.nextBytes(it) }) }
                lineas.append(Disco.JSON.encodeToString(Mensaje.serializer(),
                    Mensaje(id = "$c-a$i", cuando = reloj++, clase = Clase.ARCHIVO, ruta = f.absolutePath, proyecto = c, numero = 300 + i, letra = "a"))).append('\n')
            }
        }
        File(a.filesDir, "guardados.jsonl").writeText(lineas.toString())
        // La primera: lo pasa todo y deja los cursores.
        vuelta()
    }

    @Test
    fun `con el diario, la vuelta sin cambios o con pocos es varias veces mas rapida`() {
        montar()
        fun mejorDe(n: Int, f: () -> Medida): Medida = (1..n).map { f() }.minByOrNull { it.ms }!!

        Diario.activo = false
        val completaQuieta = mejorDe(2) { vuelta() }
        Diario.activo = true
        vuelta() // la primera con el diario otra vez cuenta la vuelta
        val diarioQuieta = mejorDe(3) { vuelta() }
        assertEquals(chats.size, diarioQuieta.hecho.quietos)

        // Un poco cambiado: un mensaje (como la app) y un lienzo reescrito sin avisar, en dos chats.
        fun cambiarUnPoco(i: Int) {
            val antes = a.leerMensajes()
            val despues = antes.map { if (it.id == "p$i-m1") it.copy(texto = "cambiado $i") else it }
            File(a.filesDir, "guardados.jsonl").writeText(despues.joinToString("") { Disco.JSON.encodeToString(Mensaje.serializer(), it) + "\n" })
            a.diario.mensajes(antes, despues)
            lienzo(a, "p${i + 1}-l1", "retocado $i")
        }
        cambiarUnPoco(1)
        val diarioPoco = vuelta()
        assertEquals(chats.size - 2, diarioPoco.hecho.quietos)
        cambiarUnPoco(5)
        Diario.activo = false
        val completaPoco = vuelta()
        Diario.activo = true
        assertTrue(b.leerMensajes().any { it.texto == "cambiado 5" } && b.leerMensajes().any { it.texto == "cambiado 1" })

        println("== Vuelta con 30 chats × (120 mensajes + 6 lienzos + 6 archivos de 64 KB) ==")
        println("  sin cambios   · sin diario: %7.1f ms, %8d bytes · con diario: %6.1f ms, %7d bytes · ×%.1f".format(
            completaQuieta.ms, completaQuieta.bytes, diarioQuieta.ms, diarioQuieta.bytes, completaQuieta.ms / diarioQuieta.ms))
        println("  2 chats tocados · sin diario: %7.1f ms, %8d bytes · con diario: %6.1f ms, %7d bytes · ×%.1f".format(
            completaPoco.ms, completaPoco.bytes, diarioPoco.ms, diarioPoco.bytes, completaPoco.ms / diarioPoco.ms))
        assertTrue("sin cambios: ${diarioQuieta.ms} ms con diario frente a ${completaQuieta.ms} sin él", diarioQuieta.ms * 3 < completaQuieta.ms)
        assertTrue("pocos cambios: ${diarioPoco.ms} ms con diario frente a ${completaPoco.ms} sin él", diarioPoco.ms * 2 < completaPoco.ms)
        assertTrue(diarioQuieta.bytes * 5 < completaQuieta.bytes)
    }
}
