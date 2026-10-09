package com.forge.pixpin

import com.forge.pixpin.guardados.Clase
import com.forge.pixpin.guardados.Mensaje
import com.forge.pixpin.guardados.extensionPorContenido
import com.forge.pixpin.guardados.nombreConExtension
import com.forge.pixpin.mini.AlarmasDeTareas
import com.forge.pixpin.mini.ElegirHora
import com.forge.pixpin.mini.Tarea
import com.forge.pixpin.mini.Tareas
import com.forge.pixpin.mini.TodasLasTareas
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Las tareas con hora del PC** (`⏰ AAAA-MM-DD HH:MM` al final del texto, `tareas::RELOJ`,
 * 8-oct-2026) y el `.bin` que el PC no sabía abrir.
 */
class TareasConHoraTest {
    private val diez = LocalDateTime.of(2026, 10, 9, 10, 30)

    @Test fun `la hora va y vuelve por el texto como en el PC`() {
        assertEquals("⏰ 2026-10-09 10:30", Tareas.marcaDeHora(diez))
        val con = Tareas.conHora("Llamar al banco", diez)
        assertEquals("Llamar al banco ⏰ 2026-10-09 10:30", con)
        assertEquals(diez, Tareas.horaDe(con))
        assertEquals("Llamar al banco", Tareas.sinHora(con))
        // Cambiarla no la repite; null la quita.
        val otra = Tareas.conHora(con, diez.plusMinutes(1))
        assertEquals(1, otra.count { it == Tareas.RELOJ })
        assertEquals("Llamar al banco", Tareas.conHora(con, null))
        // Casos negativos (los mismos del PC): un reloj suelto o una fecha imposible no son hora.
        assertNull(Tareas.horaDe("despertar ⏰ pronto"))
        assertNull(Tareas.horaDe("x ⏰ 2026-13-01 10:00"))
        assertNull(Tareas.horaDe("x ⏰ 2026-02-30 10:00"))
        assertEquals("x ⏰ pronto", Tareas.sinHora("x ⏰ pronto"))
    }

    @Test fun `detras de la fecha de creacion, como la escribe el PC, se leen las dos`() {
        val delPc = "pan ➕ 2026-10-02 ⏰ 2026-10-09 10:30"
        val (visible, creada) = Tareas.partir(delPc)
        assertEquals("pan", visible)
        assertEquals(LocalDate.of(2026, 10, 2), creada)
        assertEquals("pan", Tareas.legible(delPc))
        assertEquals(diez, Tareas.horaDe(delPc))
    }

    @Test fun `corregir una tarea conserva su fecha y su hora`() {
        val t = listOf(Tarea("pan ➕ 2026-10-02 ⏰ 2026-10-09 10:30"))
        val r = Tareas.renombrar(t, 0, "pan integral")
        assertEquals("pan integral ➕ 2026-10-02 ⏰ 2026-10-09 10:30", r[0].texto)
        // Escribiendo otra hora, gana la escrita.
        val r2 = Tareas.renombrar(t, 0, "pan ⏰ 2026-10-10 08:00")
        assertEquals(LocalDateTime.of(2026, 10, 10, 8, 0), Tareas.horaDe(r2[0].texto))
        assertEquals(LocalDate.of(2026, 10, 2), Tareas.partir(r2[0].texto).second)
        // Añadir una con hora le pone la fecha delante de la hora.
        val n = Tareas.anadir(emptyList(), "yeso ⏰ 2026-10-09 10:30", hoy = LocalDate.of(2026, 10, 8))
        assertEquals("yeso ➕ 2026-10-08 ⏰ 2026-10-09 10:30", n.single().texto)
    }

    @Test fun `la hora para ensenarla`() {
        val ahora = LocalDateTime.of(2026, 10, 9, 8, 0)
        assertEquals("hoy 10:30", Tareas.textoDeHora(diez, ahora))
        assertEquals("mañana 10:30", Tareas.textoDeHora(diez.plusDays(1), ahora))
        assertEquals("ayer 10:30", Tareas.textoDeHora(diez.minusDays(1), ahora))
        assertEquals("12 oct 10:30", Tareas.textoDeHora(diez.plusDays(3), ahora))
        assertEquals("9 ene 2027 10:30", Tareas.textoDeHora(LocalDateTime.of(2027, 1, 9, 10, 30), ahora))
    }

    private fun lista(id: String, texto: String, enBuzon: Boolean = false) =
        Mensaje(id = id, cuando = 1, clase = Clase.MINIAPP, miniapp = "tareas", texto = texto, enBuzon = enBuzon)

    @Test fun `las alarmas son las de las pendientes con hora que aun no paso, con el id del PC`() {
        val doc = Tareas.escribir("Obra", listOf(
            Tarea("yeso ➕ 2026-10-08 ⏰ 2026-10-09 10:30"),
            Tarea("arena"),
            Tarea("hecha ⏰ 2026-10-09 11:00", hecha = true),
            Tarea("pasada ⏰ 2026-10-08 09:00")
        ))
        val ahora = LocalDateTime.of(2026, 10, 9, 8, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val a = AlarmasDeTareas.deLosMensajes(listOf(lista("m1", doc), lista("nota", "texto", false).copy(miniapp = null, clase = Clase.NOTA)), ahora, ZoneOffset.UTC)
        assertEquals(1, a.size)
        // El mismo id que `tareas::id_de_recordatorio` del PC: los ms locales de la hora.
        val local = (20_735L * 1440 + 10 * 60 + 30) * 60_000   // 2026-10-09: 20454 días hasta el 1 de enero + 281
        assertEquals("tarea:m1:$local", a[0].id)
        assertEquals(diez.toInstant(ZoneOffset.UTC).toEpochMilli(), a[0].cuando)
        assertEquals("☑ yeso", a[0].texto)
        assertEquals("m1" to diez, AlarmasDeTareas.deId(a[0].id))
        // Una lista en el buzón no suena; un id que no es de tarea no se entiende.
        assertTrue(AlarmasDeTareas.deLosMensajes(listOf(lista("m2", doc, enBuzon = true)), ahora, ZoneOffset.UTC).isEmpty())
        assertNull(AlarmasDeTareas.deId("msg:otra"))
        assertNull(AlarmasDeTareas.deId("tarea:sinhora"))
    }

    @Test fun `al sonar se quita la marca y solo la de esa tarea`() {
        val doc = Tareas.escribir("Obra", listOf(Tarea("yeso ⏰ 2026-10-09 10:30"), Tarea("arena ⏰ 2026-10-09 11:00")))
        val (nuevo, texto) = AlarmasDeTareas.sono(doc, diez)!!
        assertEquals("yeso", texto)
        val t = Tareas.leer(nuevo)
        assertNull(Tareas.horaDe(t[0].texto))
        assertEquals(LocalDateTime.of(2026, 10, 9, 11, 0), Tareas.horaDe(t[1].texto))
        // Caso negativo: si ya no está (la quitó el PC al sonar allí), nada.
        assertNull(AlarmasDeTareas.sono(nuevo, diez))
    }

    @Test fun `poner y quitar la hora desde la pantalla de tareas`() {
        val doc = Tareas.escribir("Obra", listOf(Tarea("yeso ➕ 2026-10-08")))
        val f = TodasLasTareas.reunir(listOf(lista("m1", doc)), emptyMap()).single().filas.single()
        val con = TodasLasTareas.ponerHora(doc, f, diez)!!
        assertEquals("yeso ➕ 2026-10-08 ⏰ 2026-10-09 10:30", Tareas.leer(con).single().texto)
        val f2 = TodasLasTareas.reunir(listOf(lista("m1", con)), emptyMap()).single().filas.single()
        assertEquals("yeso", f2.texto)
        assertEquals("yeso ➕ 2026-10-08", Tareas.leer(TodasLasTareas.ponerHora(con, f2, null)!!).single().texto)
        // Caso negativo: la lista cambió y la fila ya no es esa.
        assertNull(TodasLasTareas.ponerHora(con, f, null))
    }

    @Test fun `una hora pasada va al dia siguiente`() {
        val ahora = LocalDateTime.of(2026, 10, 9, 12, 0)
        assertEquals(LocalDateTime.of(2026, 10, 10, 9, 0), ElegirHora.alFuturo(LocalDateTime.of(2026, 10, 9, 9, 0), ahora))
        assertEquals(LocalDateTime.of(2026, 10, 9, 13, 0), ElegirHora.alFuturo(LocalDateTime.of(2026, 10, 9, 13, 0), ahora))
    }

    @Test fun `bin no se pega al nombre y lo de dentro da la extension`() {
        assertEquals("informe.pdf", nombreConExtension("informe.pdf", "bin"))
        assertEquals("informe", nombreConExtension("informe", "bin"))
        assertEquals("plano.rvt", nombreConExtension("plano.rvt", "bin"))
        assertEquals("informe.pdf", nombreConExtension("informe", "pdf"))
        assertEquals("pdf", extensionPorContenido("%PDF-1.7".toByteArray()))
        assertEquals("png", extensionPorContenido(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D)))
        assertEquals("jpg", extensionPorContenido(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())))
        assertEquals("webp", extensionPorContenido("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray(Charsets.ISO_8859_1)))
        // Casos negativos: corto, vacío o desconocido.
        assertNull(extensionPorContenido(byteArrayOf(0x25, 0x50)))
        assertNull(extensionPorContenido(ByteArray(0)))
        assertNull(extensionPorContenido("hola mundo".toByteArray()))
    }
}
