package com.forge.pixpin.ia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** **La memoria de largo plazo** (pruebas de `memoria.rs` del PC, portadas tal cual). */
class MemoriaLargaTest {

    private fun memoria(): Pair<File, MemoriaLarga.Memoria> {
        val d = kotlin.io.path.createTempDirectory("memoria").toFile()
        return d to MemoriaLarga.Memoria(MemoriaLarga.EnCarpeta(d), "Claude Haiku 5.5", "2026-10-22")
    }

    private fun MemoriaLarga.Memoria.llamar(json: String) = ejecutar(json)

    @Test fun `las rutas no salen de la memoria`() {
        assertEquals("PERFIL.md", MemoriaLarga.normalizar("/memoria/PERFIL.md").getOrThrow())
        assertEquals("notas/x.md", MemoriaLarga.normalizar("/memories/notas/x.md").getOrThrow())
        assertEquals("", MemoriaLarga.normalizar("memoria").getOrThrow())
        assertEquals("", MemoriaLarga.normalizar("/").getOrThrow())
        assertEquals("a.md", MemoriaLarga.normalizar("./a.md").getOrThrow())
        assertEquals("a/b.md", MemoriaLarga.normalizar("a\\b.md").getOrThrow())
        assertTrue("sin tildes ni eñes: el nombre viaja tal cual", MemoriaLarga.normalizar("año.md").isFailure)
        for (mala in listOf("../x.md", "/memoria/../../secreto.md", "a/../../b.md", "..\\..\\win.ini", "%2e%2e%2fetc", "C:/Windows/x.md",
            "/memoria/.oculto.md", "con espacio.md", "a/b/c/d.md", "x\u0000.md")) {
            assertTrue("$mala tenia que fallar", MemoriaLarga.normalizar(mala).isFailure)
        }
    }

    @Test fun `los comandos crean cambian insertan renombran y borran`() {
        val (dir, m) = memoria()
        assertTrue(m.llamar("""{"command":"view","path":"/memoria"}""").contains("vacia"))
        val r = m.llamar("""{"command":"create","path":"/memoria/estilo.md","file_text":"# Le gustan las flechas azules\nLo dijo el 22-oct."}""")
        assertTrue(r, r.contains("creado"))
        val enDisco = File(dir, "estilo.md").readText()
        assertTrue(enDisco, enDisco.endsWith("<!-- fuente: Claude Haiku 5.5, 2026-10-22 -->\n"))
        assertTrue(m.llamar("""{"command":"str_replace","path":"/memoria/estilo.md","old_str":"azules","new_str":"verdes"}""").contains("cambiado"))
        assertTrue(m.llamar("""{"command":"str_replace","path":"/memoria/estilo.md","old_str":"rojas","new_str":"x"}""").startsWith("ERROR"))
        m.llamar("""{"command":"create","path":"/memoria/rep.md","file_text":"zz zz"}""")
        assertTrue(m.llamar("""{"command":"str_replace","path":"rep.md","old_str":"zz","new_str":"b"}""").contains("2 veces"))
        // insert al principio, y la marca de procedencia sigue sola al final.
        assertFalse(m.llamar("""{"command":"insert","path":"/memoria/estilo.md","insert_line":0,"insert_text":"Tema: estilo"}""").startsWith("ERROR"))
        val t = File(dir, "estilo.md").readText()
        assertTrue(t, t.startsWith("Tema: estilo\n# Le gustan las flechas verdes"))
        assertEquals(t, 1, Regex("<!-- fuente: ").findAll(t).count())
        val v = m.llamar("""{"command":"view","path":"/memoria/estilo.md","view_range":[2,2]}""")
        assertTrue(v, v.contains("     2\t# Le gustan") && !v.contains("Tema:"))
        // rename de fichero y de carpeta; el destino no puede existir.
        assertTrue(m.llamar("""{"command":"rename","old_path":"/memoria/rep.md","new_path":"/memoria/viejo/rep.md"}""").contains("ahora es"))
        assertTrue(File(dir, "viejo/rep.md").isFile && !File(dir, "rep.md").exists())
        assertTrue(m.llamar("""{"command":"rename","old_path":"/memoria/viejo","new_path":"/memoria/archivo"}""").contains("ahora es"))
        assertTrue(File(dir, "archivo/rep.md").isFile)
        assertTrue(m.llamar("""{"command":"rename","old_path":"/memoria/estilo.md","new_path":"/memoria/archivo/rep.md"}""").contains("ya existe"))
        // delete (y la carpeta vacía se va).
        assertTrue(m.llamar("""{"command":"delete","path":"/memoria/archivo"}""").contains("borrado"))
        assertFalse(File(dir, "archivo").exists())
        assertTrue(m.llamar("""{"command":"delete","path":"/memoria"}""").startsWith("ERROR"))
        assertTrue(m.llamar("""{"comando":"ver","ruta":"/memoria"}""").contains("estilo.md"))
        assertTrue(m.llamar("""{"command":"volar"}""").startsWith("ERROR"))
        assertTrue(m.ejecutar("{roto").startsWith("ERROR"))
        assertTrue(m.llamar("""{"command":"create","path":"/memoria/x.exe","file_text":"x"}""").startsWith("ERROR"))
        assertTrue(m.llamar("""{"command":"create","path":"/memoria/../x.md","file_text":"x"}""").startsWith("ERROR"))
        dir.deleteRecursively()
    }

    @Test fun `el indice se rehace solo con una linea por tema y su tope`() {
        val (dir, m) = memoria()
        m.llamar("""{"command":"create","path":"/memoria/PERFIL.md","file_text":"Habla espanol. Respuestas cortas."}""")
        m.llamar("""{"command":"create","path":"/memoria/casa.md","file_text":"# Su casa tiene 3 plantas\nmas detalle"}""")
        val i = File(dir, MemoriaLarga.INDICE).readText()
        assertTrue(i, i.contains("- /memoria/casa.md (2026-10-22): Su casa tiene 3 plantas"))
        assertFalse("el perfil no va en el indice: $i", i.contains("PERFIL"))
        assertTrue(m.llamar("""{"command":"create","path":"/memoria/INDICE.md","file_text":"x"}""").contains("se rehace solo"))
        for (k in 0 until MemoriaLarga.TOPE_INDICE + 5) m.almacen.escribir("t/%03d.md".format(k), "tema $k")
        m.rehacerIndice()
        val j = File(dir, MemoriaLarga.INDICE).readText()
        assertEquals(MemoriaLarga.TOPE_INDICE, j.lines().count { it.startsWith("- /memoria/") })
        assertTrue(j.lines().last { it.isNotEmpty() }, j.contains("y 6 temas mas"))
        for (k in 0 until MemoriaLarga.TOPE_FICHEROS) m.almacen.escribir("u/%03d.md".format(k), "x")
        assertTrue(m.llamar("""{"command":"create","path":"/memoria/otro.md","file_text":"x"}""").contains("ya hay"))
        assertTrue(m.llamar("""{"command":"create","path":"/memoria/casa.md","file_text":"# Su casa tiene 4 plantas"}""").contains("sustituido"))
        dir.deleteRecursively()
    }

    @Test fun `los secretos no se guardan`() {
        val (t, n) = MemoriaLarga.quitarSecretos("mi clave de Claude es sk-ant-api03-AbCdEfGhIjKlMnOpQrStUv y ya")
        assertTrue(t, !t.contains("sk-ant") && t.contains(MemoriaLarga.SECRETO_QUITADO) && n == 1)
        val (t2, n2) = MemoriaLarga.quitarSecretos("contraseña: perro\nPassword=Hunter2 fin\npin: 4477")
        assertEquals(t2, 3, n2)
        assertTrue(t2, !t2.contains("perro") && !t2.contains("Hunter2") && !t2.contains("4477"))
        assertTrue(MemoriaLarga.quitarSecretos("token AbCdEf0123456789GhIjKl0123456789MnOp aqui").first.contains(MemoriaLarga.SECRETO_QUITADO))
        assertEquals("[secreto quitado]\nsigue" to 1, MemoriaLarga.quitarSecretos("-----BEGIN RSA PRIVATE KEY-----\nMIIabc\n-----END RSA PRIVATE KEY-----\nsigue"))
        for (normal in listOf("Idea clave: usar flechas", "Prefiere el modo oscuro y textos de 14 px", "https://example.com/docs/guia", "commit a908daa del 22-oct"))
            assertEquals(normal to 0, MemoriaLarga.quitarSecretos(normal))
        val (dir, m) = memoria()
        val r = m.llamar("""{"command":"create","path":"/memoria/claves.md","file_text":"Su clave: sk-ant-api03-AbCdEfGhIjKlMnOpQrStUv"}""")
        assertTrue(r, r.contains("se quitaron 1"))
        assertFalse(File(dir, "claves.md").readText().contains("sk-ant"))
        dir.deleteRecursively()
    }

    @Test fun `el bloque del sistema cumple su presupuesto`() {
        val (dir, m) = memoria()
        assertEquals("sin memoria, nada", "", m.bloqueParaElSistema())
        m.llamar("""{"command":"create","path":"/memoria/PERFIL.md","file_text":"Habla espanol. ${"x".repeat(3000)}"}""")
        for (k in 0 until 60) m.llamar("""{"command":"create","path":"/memoria/tema%02d.md","file_text":"# Tema numero $k con una descripcion larga de verdad"}""".format(k))
        val b = m.bloqueParaElSistema(2000)
        assertTrue("${b.length}", b.length <= 2000 + 120)
        assertTrue(b, b.startsWith("# Memoria del usuario") && b.contains("Habla espanol"))
        assertTrue("lo que no cabe se dice", b.contains("temas mas"))
        val grande = m.bloqueParaElSistema(MemoriaLarga.PRESUPUESTO_SISTEMA * 4)
        assertTrue(grande.contains("tema00.md") && !grande.contains("temas mas"))
        // Una vez en el sistema, aunque se ponga dos veces; y va en lo fijo, que se cachea.
        val s = MemoriaLarga.conMemoria("Eres X.", b)
        assertEquals(s, MemoriaLarga.conMemoria(s, b))
        assertEquals("Eres X.", MemoriaLarga.conMemoria(s, ""))
        assertEquals(s, Nube.partirSistema(s).first)
        dir.deleteRecursively()
    }

    @Test fun `buscar encuentra el tema y dice de donde sale`() {
        val (dir, m) = memoria()
        m.llamar("""{"command":"create","path":"/memoria/obra.md","file_text":"# Obra de la calle Mayor\n\nEl presupuesto era de 12.000 euros."}""")
        m.llamar("""{"command":"create","path":"/memoria/PERFIL.md","file_text":"Prefiere euros con punto de miles."}""")
        val r = m.llamar("""{"command":"search","query":"presupuesto obra"}""")
        assertTrue(r, r.contains("[/memoria/obra.md]") && r.contains("12.000"))
        assertTrue(m.llamar("""{"command":"search","query":"avion"}""").contains("No hay nada"))
        assertTrue(m.llamar("""{"command":"search"}""").startsWith("ERROR"))
        dir.deleteRecursively()
    }

    @Test fun `la pasada de guardar usa solo la memoria y cuenta lo escrito`() {
        val (dir, m) = memoria()
        val motor = MotorGuionado(listOf(
            Respuesta.deLlamada(MemoriaLarga.NOMBRE, """{"command":"create","path":"/memoria/PERFIL.md","file_text":"Quiere las respuestas en espanol y cortas."}"""),
            Respuesta.deLlamada("dibujar_en_lienzo", "{}"),
            Respuesta.deTexto("Guardado su idioma.")
        ))
        val conv = MemoriaLarga.transcripcion(listOf(true to "contestame siempre corto y en espanol", false to "Vale."))
        assertEquals(1, MemoriaLarga.guardarLoDuradero(motor, m, conv))
        assertTrue(File(dir, MemoriaLarga.PERFIL).readText().contains("cortas"))
        assertTrue(motor.recibido.all { it.herramientas.size == 1 && it.herramientas[0].nombre == MemoriaLarga.NOMBRE })
        assertTrue(motor.recibido[0].mensajes[1].textoEntero.contains("contestame siempre corto"))
        assertTrue(motor.recibido[2].mensajes.any { it.textoEntero.contains("solo esta la herramienta") })
        val vacio = MotorGuionado(emptyList())
        assertEquals(0, MemoriaLarga.guardarLoDuradero(vacio, m, "  "))
        assertTrue(vacio.recibido.isEmpty())
        dir.deleteRecursively()
    }

    @Test fun `el turno lleva la memoria en el sistema y la herramienta`() {
        val (dir, m) = memoria()
        m.llamar("""{"command":"create","path":"/memoria/PERFIL.md","file_text":"Se llama Max y habla espanol."}""")
        val motor = MotorGuionado(listOf(
            Respuesta.deLlamada(MemoriaLarga.NOMBRE, """{"command":"create","path":"/memoria/gustos.md","file_text":"# Le gusta el azul"}"""),
            Respuesta.deTexto("Apuntado.")
        ))
        val h = mutableListOf(MensajeIa.sistema(Agente.instrucciones()), MensajeIa.usuario("recuerda que me gusta el azul"))
        val f = Agente.turno(motor, h, emptyList(), { Result.success("hecho") }, opciones = Agente.Opciones(memoria = m)) { Seguir.SI }
        assertEquals("Apuntado.", f.texto)
        val sistema = motor.recibido[0].mensajes[0].textoEntero
        assertTrue(sistema, sistema.contains("# Memoria del usuario") && sistema.contains("Se llama Max"))
        assertEquals(1, Regex("# Memoria del usuario").findAll(sistema).count())
        assertTrue(motor.recibido[0].herramientas.any { it.nombre == MemoriaLarga.NOMBRE })
        assertTrue(File(dir, "gustos.md").readText().contains("azul"))
        assertTrue(File(dir, MemoriaLarga.INDICE).readText().contains("Le gusta el azul"))
        // Sin memoria, ni bloque ni herramienta (el turno de siempre).
        val otro = MotorGuionado(listOf(Respuesta.deTexto("Hola.")))
        Agente.turno(otro, mutableListOf(MensajeIa.sistema("S"), MensajeIa.usuario("hola")), emptyList(), { Result.success("hecho") }) { Seguir.SI }
        val p = otro.recibido[0]
        assertTrue(!p.mensajes[0].textoEntero.contains("Memoria") && p.herramientas.none { it.nombre == MemoriaLarga.NOMBRE })
        dir.deleteRecursively()
    }

    @Test fun `la transcripcion se queda con lo ultimo y la fecha de una hora`() {
        val conv = (0 until 40).map { (it % 2 == 0) to "mensaje $it ${"bla ".repeat(200)}" }
        val t = MemoriaLarga.transcripcion(conv)
        assertTrue(t.length <= MemoriaLarga.LARGO_CONVERSACION + 1)
        assertTrue(t.contains("mensaje 39") && !t.contains("mensaje 0 "))
        assertEquals("1970-01-01", MemoriaLarga.fechaDeMs(0))
        assertEquals("2026-10-22", MemoriaLarga.fechaDeMs(1_792_627_200_000))
        assertEquals("2000-02-29", MemoriaLarga.fechaDeMs(951_782_400_000))
    }
}
