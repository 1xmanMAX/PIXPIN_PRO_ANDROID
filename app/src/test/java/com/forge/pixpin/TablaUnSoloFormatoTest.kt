package com.forge.pixpin.motor

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * **Hojas de cálculo: un solo formato** (18-oct-2026, PC `01dccd4`, `docs/manual-hojas-android.md`
 * del PC): los ejemplos de su §7 y las pruebas de `tabla.rs` (`el_formato_unico_va_y_vuelve…`,
 * `una_tabla_vieja_o_rara_se_rescata…`, `lo_que_no_es_un_objeto_json…`) con los mismos datos.
 */
class TablaUnSoloFormatoTest {

    private fun obj(texto: String) = Json.parseToJsonElement(texto).jsonObject
    private fun JsonElement?.o() = this!!.jsonObject

    /** §7.1: como la escribe el PC (con sangría). */
    private val delPc = """
        {
          "nombre": "Cuentas · Gastos",
          "celdas": {
            "A1": "Concepto",
            "B1": "Importe",
            "A2": "Cemento",
            "B2": "120",
            "B4": "=SUMA(B2:B3)"
          },
          "anchos": {
            "A": 180
          },
          "estilos": {
            "A1": { "n": true, "f": "#fff2cc" },
            "B4": { "n": true, "nf": "moneda", "c": "#c00000" }
          },
          "tocado": 1789500002001,
          "protegida": true
        }
    """.trimIndent()

    /** §7.2: la misma, como la escribe el móvil. */
    private val delMovil = """{"nombre":"Cuentas · Gastos","celdas":{"A1":"Concepto","B1":"Importe","A2":"Cemento","B2":"120","B4":"=SUMA(B2:B3)"},"anchos":{"A":180},"estilos":{"A1":{"n":true,"f":"#fff2cc"},"B4":{"n":true,"c":"#c00000","nf":"moneda"}},"tocado":1789500002001,"protegida":true}"""

    @Test
    fun `el formato unico va y vuelve igual desde el PC y desde Android`() {
        val pc = TablaDeCalculo.deJson(delPc)!!
        val movil = TablaDeCalculo.deJson(delMovil)!!
        assertEquals(movil, pc)
        assertEquals("=SUMA(B2:B3)", pc.celdas["B4"])
        assertEquals(180, pc.anchos["A"])
        assertEquals(EstiloDeCelda(n = true, nf = "moneda", c = "#c00000"), pc.estilos["B4"])
        assertTrue(pc.protegida)
        assertEquals(1_789_500_002_001, pc.tocado)
        // Lo que escribe el móvil es exactamente §7.2 (mismo orden de claves de la clase).
        assertEquals(obj(delMovil), obj(TablaDeCalculo.aJson(pc)))
        // Y la tabla vacía (`{}`) es una tabla vacía.
        assertEquals(TablaDeCalculo(), TablaDeCalculo.deJson("{}"))
    }

    /** §7.5 y `una_tabla_vieja_o_rara_se_rescata_sin_perder_nada` del PC. */
    private val vieja = """{
        "nombre": "Cuentas",
        "celdas": {"A1": "Total", "B1": 12.5, "C1": true, "D1": null, "E1": [1, 2]},
        "anchos": {"A": 120.4, "B": "90", "C": "ancho"},
        "estilos": {"A1": "negrita", "B1": "derecha cursiva", "C1": "brillante", "D1": {"n": 1, "k": "true", "f": 7}, "E1": 3},
        "tocado": "1789000000000",
        "protegida": true
    }"""

    @Test
    fun `una tabla vieja o rara se rescata sin perder nada`() {
        val t = TablaDeCalculo.deJson(vieja)
        assertNotNull("un objeto JSON siempre se abre", t)
        t!!
        assertEquals("Cuentas", t.nombre)
        assertEquals("Total", t.celdas["A1"])
        assertEquals("un número pasa a texto y suma igual", "12.5", t.celdas["B1"])
        assertEquals("VERDADERO", t.celdas["C1"])
        assertFalse("null es una celda vacía", "D1" in t.celdas)
        assertFalse("E1" in t.celdas)
        assertEquals(120, t.anchos["A"])
        assertEquals(90, t.anchos["B"])
        assertFalse("C" in t.anchos)
        assertTrue(t.estilos["A1"]!!.n)
        assertEquals("d", t.estilos["B1"]!!.a)
        assertTrue(t.estilos["B1"]!!.k)
        assertFalse("la palabra desconocida no es formato", "C1" in t.estilos)
        assertEquals(EstiloDeCelda(n = true, k = true), t.estilos["D1"])
        assertEquals(1_789_000_000_000, t.tocado)
        assertTrue(t.protegida)

        // Al guardar sale el formato de ahora y lo que no se pudo convertir, en `viejo_*`.
        val texto = TablaDeCalculo.aJsonSobre(t, vieja)
        val j = obj(texto)
        val estilos = j["estilos"].o()
        assertEquals(obj("""{"n":true}"""), estilos["A1"])
        assertEquals("la palabra que no se conoce se queda", obj("""{"viejo":"brillante"}"""), estilos["C1"])
        assertEquals("el campo conocido que no se entiende se queda con su nombre", obj("""{"n":true,"k":true,"f":7}"""), estilos["D1"])
        assertEquals("\"12.5\"", j["celdas"].o()["B1"].toString())
        assertEquals(Json.parseToJsonElement("[1,2]"), j["viejo_celdas"].o()["E1"])
        assertEquals("\"ancho\"", j["viejo_anchos"].o()["C"].toString())
        assertEquals("3", j["viejo_estilos"].o()["E1"].toString())
        assertEquals("1789000000000", j["tocado"].toString())
        assertEquals("true", j["protegida"].toString())
        // Ninguna clave sale dos veces (el `f` raro de D1 va solo en su estilo).
        assertEquals(1, Regex("\"f\"").findAll(texto).count())

        // Releído, da lo mismo, y guardarlo otra vez no cambia nada: la migración es estable.
        assertEquals(t, TablaDeCalculo.deJson(texto))
        assertEquals(j, obj(TablaDeCalculo.aJsonSobre(TablaDeCalculo.deJson(texto)!!, texto)))
    }

    @Test
    fun `el ejemplo 7_5 del manual queda como dice`() {
        val llega = """{"nombre":"Cuentas","celdas":{"A1":"Total","B1":12.5,"E1":[1,2]},
            "estilos":{"A1":"negrita","C1":"brillante"},"tocado":"1789000000000"}"""
        val t = TablaDeCalculo.deJson(llega)!!
        val j = obj(TablaDeCalculo.aJsonSobre(t, llega))
        assertEquals(obj("""{"A1":"Total","B1":"12.5"}"""), j["celdas"])
        assertEquals(obj("""{"A1":{"n":true},"C1":{"viejo":"brillante"}}"""), j["estilos"])
        assertEquals("1789000000000", j["tocado"].toString())
        assertEquals(obj("""{"E1":[1,2]}"""), j["viejo_celdas"])
        assertEquals("Cuentas", t.nombre)
    }

    @Test
    fun `lo que el usuario cambia manda sobre lo rescatado`() {
        val t = TablaDeCalculo.deJson(vieja)!!
        // Le pone un relleno de verdad a D1: el `f: 7` viejo ya no se escribe encima.
        val nueva = t.copy(estilos = t.estilos + ("D1" to EstiloDeCelda(f = "#ff0000")), protegida = false)
        val j = obj(TablaDeCalculo.aJsonSobre(nueva, vieja))
        assertEquals(obj("""{"f":"#ff0000"}"""), j["estilos"].o()["D1"])
        assertNull("protegida se quitó aquí: no vuelve de lo viejo", j["protegida"])
    }

    @Test
    fun `sies y anchos escritos de otra forma`() {
        val t = TablaDeCalculo.deJson("""{"celdas":{"A1":5,"A2":"","A3":false},"anchos":{"A":-3,"B":"12.6","C":null},"estilos":{"A1":{"w":"si","s":0,"t":"no","e":"1"},"A2":null},"tocado":12.9,"protegida":"true"}""")!!
        assertEquals(mapOf("A1" to "5", "A3" to "FALSO"), t.celdas)
        assertEquals(mapOf("B" to 13), t.anchos)
        assertEquals(EstiloDeCelda(w = true, e = true), t.estilos["A1"])
        assertFalse("A2" in t.estilos)
        assertEquals(12L, t.tocado)
        assertTrue(t.protegida)
        val j = obj(TablaDeCalculo.aJsonSobre(t, """{"anchos":{"A":-3},"celdas":"no es un mapa","nombre":5}"""))
        assertEquals("-3", j["viejo_anchos"].o()["A"].toString())
        assertEquals("\"no es un mapa\"", j["viejo_celdas"].toString())
        assertEquals("5", j["viejo_nombre"].toString())
    }

    @Test
    fun `lo que no es un objeto JSON no se toma por una tabla`() {
        for (malo in listOf("", "no es json", "[1,2]", "\"tabla\"", "12", "null", "{\"nombre\": \"a\"")) {
            assertNull(malo, TablaDeCalculo.deJson(malo))
        }
    }

    @Test
    fun `un archivo que no se lee no se abre ni se pisa`() {
        val raiz = Files.createTempDirectory("tablas").toFile()
        try {
            val disco = TablasEnDisco(java.io.File(raiz, "tablas"))
            assertEquals(TablasEnDisco.Lectura.NoHay, disco.leer("t-1"))
            disco.archivo("t-1").apply { parentFile!!.mkdirs() }.writeText("no es json")
            assertEquals(TablasEnDisco.Lectura.NoSeLee, disco.leer("t-1"))
            assertNull(disco.cargar("t-1"))
            assertFalse(disco.guardar("t-1", TablaDeCalculo(nombre = "encima")))
            assertEquals("el archivo roto sigue tal cual", "no es json", disco.archivo("t-1").readText())
            // Una vieja rara sí se abre, y al guardar no pierde nada.
            disco.archivo("t-2").writeText(vieja)
            val t = (disco.leer("t-2") as TablasEnDisco.Lectura.Hay).tabla
            assertTrue(disco.guardar("t-2", t.copy(celdas = t.celdas + ("A9" to "1"))))
            val j = obj(disco.archivo("t-2").readText())
            assertEquals("\"1\"", j["celdas"].o()["A9"].toString())
            assertTrue(j["viejo_celdas"] is JsonObject)
        } finally { raiz.deleteRecursively() }
    }

    @Test
    fun `la copia de trabajo de un libro no va a la carpeta que viaja`() {
        val raiz = Files.createTempDirectory("tablas").toFile()
        try {
            val disco = TablasEnDisco(java.io.File(raiz, "tablas"))
            assertTrue(disco.deTrabajo("libro-x-0", TablaDeCalculo(nombre = "Leída", celdas = mapOf("A1" to "1"))))
            assertFalse(disco.archivo("libro-x-0").exists())
            assertTrue(disco.leida("libro-x-0").exists())
            assertEquals("Leída", disco.cargar("libro-x-0")!!.nombre)
            assertTrue("no sale en la lista de tablas", disco.todas().isEmpty())
            // Lo de `tablas/` manda sobre la copia de trabajo.
            disco.enArchivo("libro-x-0", TablaDeCalculo(nombre = "Editada"))
            assertEquals("Editada", disco.cargar("libro-x-0")!!.nombre)
            disco.borrar("libro-x-0")
            assertEquals(TablasEnDisco.Lectura.NoHay, disco.leer("libro-x-0"))
        } finally { raiz.deleteRecursively() }
    }
}
