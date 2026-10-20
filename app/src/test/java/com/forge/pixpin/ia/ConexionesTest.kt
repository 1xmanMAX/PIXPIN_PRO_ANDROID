package com.forge.pixpin.ia

import com.forge.pixpin.asistente.CalendarioGoogle
import com.forge.pixpin.asistente.ConexionesDelTelefono
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * **Las conexiones** (PC `conexiones`): la red del puente con la forma que espera la biblioteca del
 * PC (código, `Location`, `Retry-After`, el cuerpo de un error), y Google Calendar leído del caché
 * como el PC (`ver_calendario`, `para_el_modelo`, `civil`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConexionesTest {
    private val c get() = RuntimeEnvironment.getApplication()

    /** Un servidor de una sola respuesta en 127.0.0.1. */
    private fun servidor(respuesta: String): Pair<Int, Thread> {
        val s = ServerSocket(0)
        val t = Thread {
            s.use { srv ->
                srv.accept().use { k ->
                    val entra = k.getInputStream().bufferedReader()
                    var largo = 0
                    while (true) { val l = entra.readLine() ?: break; if (l.isEmpty()) break; if (l.lowercase().startsWith("content-length:")) largo = l.substringAfter(':').trim().toInt() }
                    repeat(largo) { entra.read() }
                    k.getOutputStream().write(respuesta.toByteArray()); k.getOutputStream().flush()
                }
            }
        }.also { it.start() }
        return s.localPort to t
    }

    private fun leer(b: ByteArray): Triple<Int, JsonObject, String> {
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        val codigo = bb.int; val n = bb.int
        val cabeza = Json.parseToJsonElement(String(b, 8, n)) as JsonObject
        return Triple(codigo, cabeza, String(b, 8 + n, b.size - 8 - n))
    }

    @Test fun `la red del puente da el codigo, la Location y el cuerpo`() {
        val (puerto, t) = servidor("HTTP/1.1 200 OK\r\nLocation: http://127.0.0.1/subida/9\r\nContent-Length: 11\r\nConnection: close\r\n\r\n{\"ok\":true}")
        val r = leer(ConexionesDelTelefono(c).http("POST", "http://127.0.0.1:$puerto/token", "[[\"Authorization\",\"Bearer x\"]]", "a=1".toByteArray(), 1 shl 20))
        t.join(5000)
        assertEquals(200, r.first)
        assertEquals("http://127.0.0.1/subida/9", r.second["ubicacion"]!!.toString().trim('"'))
        assertEquals("{\"ok\":true}", r.third)
    }

    @Test fun `un error vuelve con su cuerpo y lo que pide esperar`() {
        val (puerto, t) = servidor("HTTP/1.1 429 Too Many\r\nRetry-After: 7\r\nContent-Length: 19\r\nConnection: close\r\n\r\n{\"error\":\"despacio\"}")
        val r = leer(ConexionesDelTelefono(c).http("GET", "http://127.0.0.1:$puerto/x", "[]", ByteArray(0), 1 shl 20))
        t.join(5000)
        assertEquals(429, r.first)
        assertEquals("7", r.second["esperar"].toString())
        assertTrue(r.third.contains("despacio"))
        // Fuera de este aparato, solo https; sin servidor, un fallo de red con su motivo.
        assertEquals(0, leer(ConexionesDelTelefono(c).http("GET", "http://ejemplo.com/x", "[]", ByteArray(0), 10)).first)
    }

    private fun evento(id: String, inicio: Long, fin: Long, todo: Boolean = false, lugar: String = "") =
        """{"id":"$id","calendario":"Personal","titulo":"Evento $id","inicio":$inicio,"fin":$fin,"todo_el_dia":$todo,"lugar":"$lugar","color":"#0b8043","enlace":""}"""

    @Test fun `ver el calendario cuenta los eventos del tramo con su hora local`() {
        val zona = java.util.TimeZone.getTimeZone("America/Lima")
        java.util.TimeZone.setDefault(zona)
        // Martes 20-oct-2026, 10:00 de Lima (UTC-5).
        val ahora = java.time.ZonedDateTime.of(2026, 10, 20, 10, 0, 0, 0, zona.toZoneId()).toInstant().toEpochMilli()
        val a16 = java.time.ZonedDateTime.of(2026, 10, 20, 16, 0, 0, 0, zona.toZoneId()).toInstant().toEpochMilli()
        val jueves = java.time.LocalDate.of(2026, 10, 22).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        File(c.filesDir, "calendario-google.json").writeText(
            """{"leido":${ahora - 5 * 60_000},"eventos":[${evento("a", a16, a16 + 90 * 60_000, lugar = "Aula 12")},${evento("b", jueves, jueves + 86_400_000, todo = true)}]}"""
        )
        CalendarioGoogle.olvidarCache()
        val hoy = CalendarioGoogle.verCalendario(c, Json.parseToJsonElement("""{"desde":"hoy"}""") as JsonObject, ahora)
        assertTrue(hoy, hoy.startsWith("1 eventos de Google Calendar (leido hace 5 min)"))
        assertTrue(hoy, hoy.contains("- mar 20/10/2026 16:00–17:30 «Evento a» en Aula 12 (Personal)"))
        val jue = CalendarioGoogle.verCalendario(c, Json.parseToJsonElement("""{"desde":"jueves"}""") as JsonObject, ahora)
        assertTrue(jue, jue.contains("- jue 22/10/2026 todo el día «Evento b» (Personal)"))
        val semana = CalendarioGoogle.verCalendario(c, Json.parseToJsonElement("""{"desde":"semana"}""") as JsonObject, ahora)
        assertTrue(semana, semana.startsWith("2 eventos"))
        assertTrue(CalendarioGoogle.verCalendario(c, Json.parseToJsonElement("""{"desde":"2026-10-25"}""") as JsonObject, ahora).startsWith("No hay eventos"))
    }

    @Test fun `las fechas son las del PC`() {
        assertEquals(Triple(1970L, 1, 1), CalendarioGoogle.civil(0))
        assertEquals(Triple(2026L, 10, 20), CalendarioGoogle.civil(CalendarioGoogle.diasDesde1970(2026, 10, 20)))
        assertEquals(20_381L, CalendarioGoogle.diasDesde1970(2025, 10, 20))
        assertEquals(Triple(2024L, 2, 29), CalendarioGoogle.civil(CalendarioGoogle.diasDesde1970(2024, 2, 29)))
    }
}
