package com.forge.pixpin.guardados

import com.forge.pixpin.guardados.PorSuHora.Decision
import com.forge.pixpin.guardados.PorSuHora.Desfases
import com.forge.pixpin.guardados.PorSuHora.FotoConHora
import com.forge.pixpin.guardados.PorSuHora.Grabacion
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Las fotos por su hora** (28-oct-2026): las pruebas de `crates/pixpin-proyecto/src/por_su_hora.rs`
 * del PC, una a una y con los mismos números. Las del cuaderno en disco del PC van aquí sobre una
 * lista de [Mensaje], que es lo que reescribe el teléfono.
 */
class PorSuHoraTest {

    private val s = 1000L
    private val pared = 1_761_640_000_000L

    /** Graba el PC «PC01» 100 s, con una pausa de 30 s en el 40. */
    private fun reloj() = RelojDePared(
        aparato = "PC01", inicio = pared, fin = pared + 130 * s, pausas = listOf(listOf(pared + 40 * s, pared + 70 * s))
    )

    @Test
    fun `un hueco cuenta como una pausa`() {
        // Pausa de 40 a 70 y el equipo suspendido de 100 a 110; cortada en el 130 (último latido).
        val r = RelojDePared(
            aparato = "PC01", inicio = pared, pausas = listOf(listOf(pared + 40 * s, pared + 70 * s)),
            vistoEn = pared + 130 * s, huecos = listOf(listOf(pared + 100 * s, pared + 110 * s))
        )
        // Antes del hueco, sin la pausa; dentro, al minuto en que empezó; después, sin los dos.
        assertEquals(60 * s, PorSuHora.msEnLaGrabacion(pared + 90 * s, r))
        assertEquals(70 * s, PorSuHora.msEnLaGrabacion(pared + 105 * s, r))
        assertEquals(80 * s, PorSuHora.msEnLaGrabacion(pared + 120 * s, r))
        // Después del último latido: fuera.
        assertNull(PorSuHora.msEnLaGrabacion(pared + 131 * s, r))
        // Acabada: lo mismo que el diario del PC (`Diario::ms_en`).
        val acabada = r.copy(fin = pared + 125 * s, vistoEn = 0)
        assertEquals(85 * s, PorSuHora.msHasta(acabada, pared + 999 * s))
    }

    @Test
    fun `dentro de la ventana el minuto sin lo pausado`() {
        val r = reloj()
        assertEquals(0L, PorSuHora.msEnLaGrabacion(pared, r))
        assertEquals(10 * s, PorSuHora.msEnLaGrabacion(pared + 10 * s, r))
        assertEquals(60 * s, PorSuHora.msEnLaGrabacion(pared + 90 * s, r))
        assertEquals(100 * s, PorSuHora.msEnLaGrabacion(pared + 130 * s, r))
        assertEquals(100 * s, PorSuHora.duracion(r, pared + 999 * s))
    }

    @Test
    fun `en una pausa va al minuto en que se paro`() {
        val r = reloj()
        assertEquals(40 * s, PorSuHora.msEnLaGrabacion(pared + 40 * s, r))
        assertEquals(40 * s, PorSuHora.msEnLaGrabacion(pared + 55 * s, r))
        assertEquals(40 * s, PorSuHora.msEnLaGrabacion(pared + 70 * s, r))
        // Una pausa sin acabar (se acabó en pausa): hasta el final.
        val r2 = r.copy(pausas = r.pausas + listOf(listOf(pared + 120 * s, 0L)))
        assertEquals(90 * s, PorSuHora.msEnLaGrabacion(pared + 125 * s, r2))
    }

    @Test
    fun `caso negativo fuera de la ventana nada`() {
        val r = reloj()
        assertNull(PorSuHora.msEnLaGrabacion(pared - 1, r))
        assertNull(PorSuHora.msEnLaGrabacion(pared + 130 * s + 1, r))
        assertNull(PorSuHora.msEnLaGrabacion(pared, RelojDePared()))
        // Con el margen, lo de justo antes y justo después entra en los bordes; más allá, no.
        assertEquals(0L, PorSuHora.lugar(pared - 20 * s, r, null))
        assertEquals(100 * s, PorSuHora.lugar(pared + 150 * s, r, null))
        assertNull(PorSuHora.lugar(pared - 31 * s, r, null))
        assertNull(PorSuHora.lugar(pared + 161 * s, r, null))
        // La nota duró menos que el reloj: manda la nota.
        assertEquals(98 * s, PorSuHora.lugar(pared + 129 * s, r, 98 * s))
    }

    @Test
    fun `una grabacion cortada acaba en lo ultimo visto y una viva no acaba`() {
        var r = reloj().copy(fin = 0, vistoEn = pared + 100 * s)
        assertEquals(70 * s, PorSuHora.msEnLaGrabacion(pared + 100 * s, r))
        assertNull(PorSuHora.msEnLaGrabacion(pared + 101 * s, r))
        r = r.copy(vistoEn = 0)
        assertEquals(9_970 * s, PorSuHora.msEnLaGrabacion(pared + 10_000 * s, r))
    }

    @Test
    fun `el reloj va y vuelve en la nota`() {
        val nota = Mensaje(id = "voz1", cuando = 1, clase = Clase.VOZ)
        assertNull(RelojDePared.de(nota))
        val conReloj = nota.copy(reloj = reloj().aJson())
        // Byte a byte lo del PC (claves en orden alfabético): sin vistoEn, huecos ni grabacion.
        assertEquals(
            """{"aparato":"PC01","fin":${pared + 130 * s},"inicio":$pared,"pausas":[[${pared + 40 * s},${pared + 70 * s}]]}""",
            conReloj.reloj.toString()
        )
        assertEquals(reloj(), RelojDePared.de(conReloj))
        // Uno roto o sin inicio no es un reloj.
        assertNull(RelojDePared.de(nota.copy(reloj = kotlinx.serialization.json.buildJsonObject { put("aparato", JsonPrimitive("X")) })))
    }

    @Test
    fun `el desfase es la medida mas cercana a la hora de la foto`() {
        var d = Desfases()
        d = d.medir("TEL1", pared, 5 * s)
        d = d.medir("TEL1", pared + 86_400 * s, 9 * s)
        assertEquals(5 * s, d.cercano("TEL1", pared + 3600 * s))
        assertEquals(9 * s, d.cercano("TEL1", pared + 80_000 * s))
        assertNull(d.cercano("OTRO", pared))
        // Dos del mismo minuto: la última.
        d = d.medir("TEL1", pared + 10 * s, 6 * s)
        assertEquals(2, d.aparatos.getValue("TEL1").size)
        assertEquals(6 * s, d.cercano("TEL1", pared))
        // Y no crece sin fin.
        for (i in 0L until 100L) d = d.medir("TEL1", pared + i * 120 * s, i)
        assertEquals(PorSuHora.MEDIDAS_MAXIMAS, d.aparatos.getValue("TEL1").size)
        d = d.medir("", pared, 1)
        assertFalse(d.aparatos.containsKey(""))
    }

    @Test
    fun `la hora del EXIF con su zona`() {
        // 28-oct-2026 10:05:03 en Madrid (+01:00) = 09:05:03 UTC.
        val ms = PorSuHora.msDeExif("2026:10:28 10:05:03", "+01:00")!!
        assertEquals(1_793_178_303_000L, ms)
        assertEquals(ms, PorSuHora.msDeExif("2026:10:28 09:05:03", "Z"))
        assertEquals(ms, PorSuHora.msDeExif("2026-10-28T04:05:03", "-05:00"))
        assertEquals(0L, PorSuHora.msDeExif("1970:01:01 00:00:00", "+00:00"))
        // Casos negativos: sin zona, o una fecha rota.
        assertNull(PorSuHora.msDeExif("2026:10:28 10:05:03", ""))
        assertNull(PorSuHora.msDeExif("2026:13:28 10:05:03", "+01:00"))
        assertNull(PorSuHora.msDeExif("ayer", "+01:00"))
    }

    private fun foto(id: String, aparato: String, tomadaEn: Long) = FotoConHora(id, "u-$id", aparato, tomadaEn)

    private fun grabacion() = Grabacion(voz = "voz1", carpeta = "car1", reloj = reloj(), duracionMs = 100 * s)

    private val mios = setOf("PC01", "TEL1")

    @Test
    fun `la foto de mi movil con su desfase va a su minuto`() {
        // El móvil va 8 s adelantado.
        val d = Desfases().medir("TEL1", pared - 3600 * s, 8 * s)
        val g = listOf(grabacion())
        // Su hora +98 s es la +90 del PC: el 60 (sin la pausa).
        assertEquals(Decision.Colocar("voz1", 60 * s, false), PorSuHora.decidir(foto("f1", "TEL1", pared + 98 * s), g, "PC01", mios, d))
        // Una del propio PC, sin desfase.
        assertEquals(Decision.Colocar("voz1", 10 * s, false), PorSuHora.decidir(foto("f2", "PC01", pared + 10 * s), g, "PC01", mios, d))
    }

    @Test
    fun `caso negativo fuera, ya puesta, de otro dueno o sin invitar`() {
        val d = Desfases()
        val g = grabacion()
        // Fuera de la ventana: se queda como un mensaje normal.
        assertEquals(Decision.Fuera, PorSuHora.decidir(foto("f1", "TEL1", pared + 3600 * s), listOf(g), "PC01", mios, d))
        // Llegó dos veces (por su id o por su uid): una sola.
        g.ya += "u-f2"
        assertEquals(Decision.Ya, PorSuHora.decidir(foto("f2", "TEL1", pared + 5 * s), listOf(g), "PC01", mios, d))
        // La grabación es de otro aparato: aquí no se coloca.
        assertEquals(Decision.Fuera, PorSuHora.decidir(foto("f3", "TEL1", pared + 5 * s), listOf(g), "TEL1", mios, d))
        // De otra persona sin invitar: no.
        assertEquals(Decision.NoAdmitida, PorSuHora.decidir(foto("f4", "ANA1", pared + 5 * s), listOf(g), "PC01", mios, d))
        // Para otra grabación concreta: no va en esta.
        assertEquals(Decision.Fuera, PorSuHora.decidir(foto("f5", "TEL1", pared + 5 * s).copy(para = "otra"), listOf(g), "PC01", mios, d))
    }

    @Test
    fun `el colaborador sin medida entra con la hora sin comprobar`() {
        var nota = Mensaje(id = "voz1", cuando = 1, clase = Clase.VOZ)
        nota = PorSuHora.invitar(nota, "ANA1", true)!!
        assertNull("ya estaba", PorSuHora.invitar(nota, "ANA1", true))
        var g = grabacion().copy(colaboradores = PorSuHora.colaboradoresDe(nota))
        assertEquals(
            Decision.Colocar("voz1", 20 * s, true),
            PorSuHora.decidir(foto("f1", "ANA1", pared + 20 * s), listOf(g), "PC01", mios, Desfases())
        )
        // Con su medida (su aparato habló con este alguna vez), comprobada.
        val d = Desfases().medir("ANA1", pared, -4 * s)
        assertEquals(
            Decision.Colocar("voz1", 20 * s, false),
            PorSuHora.decidir(foto("f1", "ANA1", pared + 16 * s), listOf(g), "PC01", mios, d)
        )
        // Todos los del chat.
        nota = PorSuHora.invitar(nota, "ANA1", false)!!
        assertNull(nota.colaboradores)
        nota = PorSuHora.invitar(nota, PorSuHora.TODOS, true)!!
        g = g.copy(colaboradores = PorSuHora.colaboradoresDe(nota))
        assertTrue(PorSuHora.admitido(g, "EVA9", mios))
        assertTrue(PorSuHora.abiertaATodos(nota))
    }

    @Test
    fun `de dos grabaciones que se tocan la que empezo despues`() {
        val primera = grabacion()
        val segunda = grabacion().copy(
            voz = "voz2", reloj = reloj().copy(inicio = pared + 140 * s, fin = pared + 300 * s, pausas = emptyList()), duracionMs = 160 * s
        )
        // En el margen de después de la primera y dentro de la segunda.
        assertEquals(
            Decision.Colocar("voz2", 10 * s, false),
            PorSuHora.decidir(foto("f1", "PC01", pared + 150 * s), listOf(primera, segunda), "PC01", mios, Desfases())
        )
    }

    private fun mensajeFoto(id: String, aparato: String, tomadaEn: Long) = Mensaje(
        id = id, cuando = if (tomadaEn > 0) tomadaEn else 1, clase = Clase.IMAGEN, ruta = "$id.jpg", nombre = "$id.jpg", bytes = 4,
        proyecto = "p1", uid = "u-$id", aparato = aparato, tomadaEn = tomadaEn.takeIf { it > 0 }
    )

    private fun chatConGrabacion(): MutableList<Mensaje> {
        val voz = Mensaje(
            id = "voz1", cuando = pared, clase = Clase.VOZ, ruta = "Clase.m4a", nombre = "Clase.m4a", bytes = 9, duracionMs = 100_000,
            proyecto = "p1", aparato = "PC01", reloj = reloj().aJson()
        )
        val carpeta = CarpetaDePaginas.grabacion("voz1", 100 * s).anadirAlMinuto("voz1", "", 0)!!
        val c = CarpetaDePaginas.mensajeNuevo("Clase", pared + 1, "p1", carpeta).copy(id = "car1")
        // Y otro chat sin grabación, que no se toca.
        val otro = Mensaje(id = "n1", cuando = 1, clase = Clase.NOTA, texto = "hola")
        return mutableListOf(voz, c, otro)
    }

    private fun MutableList<Mensaje>.cambiar(id: String, f: (Mensaje) -> Mensaje) {
        val i = indexOfFirst { it.id == id }
        this[i] = f(this[i])
    }

    @Test
    fun `el repaso pone cada foto una vez en la nota y en la carpeta`() {
        val todos = chatConGrabacion()
        val d = Desfases().medir("TEL1", pared, 8 * s)
        // Hecha sin conexión en el móvil; llega tarde por la sincronización.
        todos += mensajeFoto("f1", "TEL1", pared + 98 * s)
        // De una colaboradora invitada, sin medida de su reloj.
        todos += mensajeFoto("f2", "ANA1", pared + 20 * s)
        // Fuera de la ventana y sin hora: se quedan como están.
        todos += mensajeFoto("f3", "TEL1", pared + 7200 * s)
        todos += mensajeFoto("f4", "TEL1", 0)
        todos.cambiar("voz1") { PorSuHora.invitar(it, "ANA1", true)!! }
        val antesDelOtro = todos.first { it.id == "n1" }

        assertEquals(2, PorSuHora.repasar(todos, "p1", "PC01", mios, d))
        // Otra vez (o la misma foto llegada otra vez): nada.
        assertEquals(0, PorSuHora.repasar(todos, "p1", "PC01", mios, d))
        // Desde otro aparato mío que no grabó: tampoco.
        assertEquals(0, PorSuHora.repasar(todos, "p1", "TEL1", mios, d))
        assertEquals(mapOf<String?, Int>(), PorSuHora.repasarTodos(todos, "PC01", mios, d))

        val voz = todos.first { it.id == "voz1" }
        assertEquals(listOf(GrabarClase.Enlace(20 * s, "f2"), GrabarClase.Enlace(60 * s, "f1")), GrabarClase.capturasDe(voz))
        assertTrue(PorSuHora.sinComprobar(voz, "f2"))
        assertFalse(PorSuHora.sinComprobar(voz, "f1"))
        assertEquals("ANA1", PorSuHora.autorEnLaNota(voz, "f2"))
        // La entrada, con las claves en el orden del PC.
        assertEquals(
            """{"aparato":"ANA1","horaSinComprobar":true,"id":"f2","ms":20000,"uid":"u-f2"}""",
            voz.capturas!![0].toString()
        )
        val car = CarpetaDePaginas.deMensaje(todos.first { it.id == "car1" })!!
        assertEquals(listOf("voz1", "f2", "f1"), car.paginas.map { it.id })
        // La mía lleva su enlace; la de la otra persona no se tocó.
        val f1 = todos.first { it.id == "f1" }
        assertEquals(60 * s, f1.enGrabacion!!["ms"]!!.jsonPrimitive.long)
        assertTrue(Presencial.esDelMovil(f1))
        assertEquals(mensajeFoto("f2", "ANA1", pared + 20 * s), todos.first { it.id == "f2" })
        assertNull(todos.first { it.id == "f3" }.enGrabacion)
        assertEquals(antesDelOtro, todos.first { it.id == "n1" })
    }

    @Test
    fun `la que entro por la camara en directo no se repite al sincronizar`() {
        val todos = chatConGrabacion()
        // La cámara en directo la copió con su uid…
        todos += mensajeFoto("copia", "PC01", 0).copy(fotoUid = "u-f1")
        todos.cambiar("voz1") { it.copy(capturas = GrabarClase.ponerCaptura(it.capturas, 5 * s, "copia")) }
        // …y luego llega la original por la sincronización.
        todos += mensajeFoto("f1", "TEL1", pared + 5 * s)
        assertEquals(0, PorSuHora.repasar(todos, "p1", "PC01", mios, Desfases()))
    }

    @Test
    fun `a mano entra aunque no tenga hora o caiga fuera`() {
        val todos = chatConGrabacion()
        todos += mensajeFoto("f1", "EVA9", 0)
        todos += mensajeFoto("f2", "PC01", pared + 10 * s)
        val d = Desfases()
        assertEquals(33 * s, PorSuHora.aMano(todos, "p1", "f1", "voz1", 33 * s, mios, d))
        // Con hora dentro, la suya y no la elegida.
        assertEquals(10 * s, PorSuHora.aMano(todos, "p1", "f2", "voz1", 80 * s, mios, d))
        // Sin minuto elegido, al final.
        todos += mensajeFoto("f3", "EVA9", 0)
        assertEquals(100 * s, PorSuHora.aMano(todos, "p1", "f3", "voz1", null, mios, d))
        assertEquals(3, GrabarClase.capturasDe(todos.first { it.id == "voz1" }).size)
        // Caso negativo: una foto que ya no está.
        assertNull(PorSuHora.aMano(todos, "p1", "nada", "voz1", null, mios, d))
    }

    @Test
    fun `la foto sin tomadaEn usa la hora del EXIF`() {
        var m = mensajeFoto("f1", "TEL1", 0)
        assertNull(PorSuHora.fotoDe(m))
        m = m.copy(tomadaEnExif = "2026:10:28 10:05:03", desfaseExif = "+01:00")
        assertEquals(1_793_178_303_000L, PorSuHora.fotoDe(m)!!.tomadaEn)
        // Caso negativo: lo que no es una IMAGEN no es una foto.
        assertNull(PorSuHora.fotoDe(m.copy(clase = Clase.NOTA)))
    }

    @Test
    fun `buscar por id y por hora en cualquier chat`() {
        val todos = chatConGrabacion()
        todos.cambiar("voz1") { it.copy(reloj = reloj().copy(grabacion = "g-7").aJson()) }
        assertEquals("p1", PorSuHora.buscarPorId(todos, "g-7", "PC01")!!.first)
        assertNull(PorSuHora.buscarPorId(todos, "g-7", "OTRO"))
        assertNull(PorSuHora.buscarPorId(todos, "", "PC01"))
        val (chat, g, ms) = PorSuHora.buscarPorHora(todos, pared + 90 * s, "PC01")!!
        assertEquals(Triple("p1", "voz1", 60 * s), Triple(chat, g.voz, ms))
        assertNull(PorSuHora.buscarPorHora(todos, pared + 3600 * s, "PC01"))
    }

    @Test
    fun `los desfases van y vuelven en disco como el PC`() {
        val f = java.io.File.createTempFile("desfases", ".json")
        try {
            Desfases().medir("K7Q2", 5, -3).medir("A2B3", 7, 4).guardar(f)
            assertEquals("""{"aparatos":{"A2B3":[{"cuando":7,"desfase":4}],"K7Q2":[{"cuando":5,"desfase":-3}]}}""", f.readText())
            assertEquals(-3L, Desfases.leer(f).cercano("K7Q2", 0))
            f.writeText("roto")
            assertEquals(Desfases(), Desfases.leer(f))
        } finally { f.delete() }
    }

    @Test
    fun `poner en la nota respeta lo que ya habia`() {
        val antes = JsonArray(listOf(kotlinx.serialization.json.buildJsonObject {
            put("id", JsonPrimitive("a")); put("ms", JsonPrimitive(50)); put("raro", JsonPrimitive(1))
        }))
        val l = PorSuHora.ponerEnLaNota(antes, 10, foto("b", "", 1), false)!!
        assertEquals(listOf("b", "a"), l.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        assertEquals(1L, l[1].jsonObject["raro"]!!.jsonPrimitive.long)
        assertNull(PorSuHora.ponerEnLaNota(l, 99, foto("b", "", 1), false))
    }
}
