package com.forge.pixpin

import com.forge.pixpin.guardados.CarpetaDePaginas
import com.forge.pixpin.guardados.Clase
import com.forge.pixpin.guardados.Mensaje
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Las pruebas de `carpeta_de_paginas.rs` del PC, portadas** (25-oct-2026): el documento tiene que
 * leerse y escribirse igual en los dos lados, porque viaja en el `texto` de un mensaje.
 */
class CarpetaDePaginasTest {

    private fun lienzo(id: String) = Mensaje(id = id, cuando = 0, clase = Clase.DIBUJO, referencia = "dib-$id")
    private fun nota(id: String, texto: String) = Mensaje(id = id, cuando = 0, clase = Clase.NOTA, texto = texto)
    private fun ids(c: CarpetaDePaginas) = c.paginas.map { it.id }

    @Test fun una_carpeta_nueva_es_un_mensaje_que_el_movil_ya_sabe_leer() {
        val m = CarpetaDePaginas.mensajeNuevo("  Diario de campo ", 1_761_400_000_001, "p1")
        assertTrue(CarpetaDePaginas.esCarpeta(m))
        assertEquals("Diario de campo", m.nombre)
        assertEquals(Clase.MINIAPP, m.clase)
        assertEquals(CarpetaDePaginas.MINIAPP, m.miniapp)
        assertEquals("1761400000001", m.id)
        // Viaja por el hueco de las mini-apps: clase y palabra en el JSON.
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val linea = json.encodeToString(Mensaje.serializer(), m)
        assertTrue(linea, linea.contains("\"clase\":\"MINIAPP\""))
        assertTrue(linea, linea.contains("\"miniapp\":\"carpeta\""))
        // Y se vuelve a leer igual.
        val leido = json.decodeFromString(Mensaje.serializer(), linea)
        val c = CarpetaDePaginas.deMensaje(leido)!!
        assertTrue(c.isEmpty())
        assertEquals(CarpetaDePaginas.FORMATO, c.formato)
        assertEquals("""{"formato":1,"paginas":[]}""", m.texto)
        // Caso negativo: una tabla o una nota no son carpetas.
        val tabla = m.copy(miniapp = "tabla")
        assertFalse(CarpetaDePaginas.esCarpeta(tabla))
        assertNull(CarpetaDePaginas.deMensaje(tabla))
        assertFalse(CarpetaDePaginas.esCarpeta(nota("n", "hola")))
    }

    @Test fun se_escribe_como_el_pc() {
        val c = CarpetaDePaginas().anadir("1761400000000", "Día 1")!!.anadir("1761486400000", "Día 2")!!
        assertEquals(
            """{"formato":1,"paginas":[{"id":"1761400000000","titulo":"Día 1"},{"id":"1761486400000","titulo":"Día 2"}]}""",
            c.escribir()
        )
    }

    @Test fun anadir_quitar_reordenar_y_renombrar() {
        var c = CarpetaDePaginas()
        c = c.anadir("a", "Día 1")!!
        c = c.anadir("b", "Día 2")!!
        c = c.anadir("c", "Día 3")!!
        assertNull("la misma hoja no entra dos veces", c.anadir("b", "otra vez"))
        assertNull(c.anadir("", "sin id"))
        assertEquals(listOf("a", "b", "c"), ids(c))

        c = c.mover(2, 0)!!
        assertEquals(listOf("c", "a", "b"), ids(c))
        c = c.mover(0, 99)!!
        assertEquals("de más, al final", listOf("a", "b", "c"), ids(c))
        c = c.subir(1)!!
        assertEquals(listOf("b", "a", "c"), ids(c))
        c = c.bajar(1)!!
        assertEquals(listOf("b", "c", "a"), ids(c))
        // Casos negativos: subir la primera, bajar la última, mover fuera.
        assertNull(c.subir(0))
        assertNull(c.bajar(2))
        assertNull(c.mover(5, 0))

        c = c.renombrar(0, "  Día\ndos  ")!!
        assertEquals("Día dos", c.paginas[0].titulo)
        assertNull("igual no cambia nada", c.renombrar(0, "Día dos"))
        assertNull(c.renombrar(9, "x"))

        c = c.quitar("c")!!
        assertNull(c.quitar("c"))
        assertEquals(listOf("b", "a"), ids(c))
    }

    @Test fun el_titulo_siguiente_cuenta_el_ultimo_numero() {
        val s = CarpetaDePaginas::siguienteTitulo
        assertEquals("Día 3", s("Día 2"))
        assertEquals("Semana 5", s("Semana 4"))
        assertEquals("Página 8", s("Página 7"))
        assertEquals("Día 10", s("Día 09"))
        assertEquals("Día 10", s("Día 9"))
        assertEquals("Día 3 · lunes", s("Día 2 · lunes"))
        assertEquals("13", s("12"))
        // Casos negativos: sin número, o un número que no es una cuenta.
        assertNull(s("Prólogo"))
        assertNull(s(""))
        assertNull(s("Foto 20261024103000"))

        var c = CarpetaDePaginas()
        assertEquals("Página 1", c.tituloSiguiente("Página"))
        c = c.anadir("a", "Día 1")!!.anadir("b", "Día 2")!!
        assertEquals("Día 3", c.tituloSiguiente("Página"))
        c = c.anadir("c", "Epílogo")!!
        assertEquals("la última sin número: el de serie con su puesto", "Página 4", c.tituloSiguiente("Página"))
    }

    @Test fun una_hoja_que_ya_existia_entra_con_la_serie_o_con_su_nombre() {
        var c = CarpetaDePaginas()
        assertEquals("Plano de la casa", c.tituloAlAnadir("Plano de la casa", "Página"))
        assertEquals("Página 1", c.tituloAlAnadir("  ", "Página"))
        c = c.anadir("a", "Día 1")!!
        assertEquals("Día 2", c.tituloAlAnadir("Lienzo", "Página"))
    }

    @Test fun un_mensaje_borrado_desaparece_sin_romper_la_carpeta() {
        val c = CarpetaDePaginas().anadir("a", "Día 1")!!.anadir("b", "Día 2")!!.anadir("c", "Día 3")!!
        // El chat sin la «b» (se borró), y con otras cosas en medio.
        val mensajes = listOf(lienzo("c"), nota("n", "en medio"), lienzo("a"))
        val vivas = c.vivas(mensajes)
        assertEquals("en el orden de la carpeta", listOf("Día 1", "Día 3"), vivas.map { it.second.titulo })
        assertEquals("con su sitio en el chat", 2, vivas[0].first)
        assertEquals(0, vivas[1].first)
        val (podada, quitadas) = c.podar { id -> mensajes.any { it.id == id } }
        assertEquals(1, quitadas)
        assertEquals(2, podada.size)
        assertEquals("Día 4", podada.tituloSiguiente("Página"))
        // Caso negativo: sin nada borrado, podar no quita nada.
        assertEquals(0, podada.podar { true }.second)
    }

    @Test fun leer_y_escribir_conserva_lo_que_no_se_entiende() {
        val texto = """{"formato":2,"portada":"azul","paginas":[{"id":"a","titulo":"Día 1","color":"rojo"},{"id":"b"}]}"""
        var c = CarpetaDePaginas.leer(texto)
        assertEquals("una versión más nueva se respeta", 2, c.formato)
        assertEquals(2, c.size)
        assertEquals("", c.paginas[1].titulo)
        c = c.anadir("c", "Día 3")!!
        val escrito = c.escribir()
        val otra = CarpetaDePaginas.leer(escrito)
        assertEquals(c, otra)
        assertEquals(JsonPrimitive("azul"), otra.resto["portada"])
        assertEquals(JsonPrimitive("rojo"), otra.paginas[0].resto["color"])
        assertEquals(2, otra.formato)
        // Vacío es una carpeta vacía; lo que no es JSON, un error.
        assertTrue(CarpetaDePaginas.leer("  ").isEmpty())
        assertTrue(runCatching { CarpetaDePaginas.leer("no es json") }.isFailure)
        // Y un mensaje con el documento roto no es una carpeta que se pueda tocar.
        val rota = CarpetaDePaginas.mensajeNuevo("Rota", 1, null).copy(texto = "no es json")
        assertNull(CarpetaDePaginas.deMensaje(rota))
    }

    @Test fun que_puede_ir_en_una_carpeta() {
        assertTrue(CarpetaDePaginas.puedeIr(lienzo("a")))
        assertTrue(CarpetaDePaginas.puedeIr(nota("n", "una nota")))
        // Casos negativos: otra carpeta y un mensaje de sistema.
        assertFalse(CarpetaDePaginas.puedeIr(CarpetaDePaginas.mensajeNuevo("Otra", 2, null)))
        assertFalse(CarpetaDePaginas.puedeIr(lienzo("auto-figuras")))
        assertFalse(CarpetaDePaginas.puedeIr(lienzo("")))
    }

    @Test fun las_carpetas_de_una_conversacion() {
        val mensajes = listOf(
            lienzo("a"),
            CarpetaDePaginas.mensajeNuevo("Diario", 1, null),
            nota("x", "x"),
            CarpetaDePaginas.mensajeNuevo("Obra", 3, null)
        )
        assertEquals(listOf(1, 3), CarpetaDePaginas.carpetasDe(mensajes))
        assertTrue(CarpetaDePaginas.carpetasDe(mensajes.take(1)).isEmpty())
    }

    @Test fun el_id_de_una_carpeta_nueva_no_repite_el_de_otro_mensaje() {
        // 313032d del PC: el lienzo y la carpeta hechos en el mismo milisegundo.
        val mensajes = listOf(lienzo("1000"), lienzo("1001"), nota("1003", "x"))
        assertEquals(1002, CarpetaDePaginas.cuandoLibre(1000, mensajes))
        assertEquals("caso negativo: libre, el mismo", 999, CarpetaDePaginas.cuandoLibre(999, mensajes))
    }

    @Test fun el_nombre_de_una_carpeta_nueva_no_repite_el_de_otra() {
        val una = listOf(CarpetaDePaginas.mensajeNuevo("Cuaderno", 1, null))
        assertEquals("Cuaderno", CarpetaDePaginas.nombreLibre("Cuaderno", emptyList()))
        assertEquals("Cuaderno 2", CarpetaDePaginas.nombreLibre("Cuaderno", una))
        val dos = una + CarpetaDePaginas.mensajeNuevo("Cuaderno 2", 2, null)
        assertEquals("Cuaderno 3", CarpetaDePaginas.nombreLibre("Cuaderno", dos))
        // Un lienzo que se llame así no cuenta: solo las carpetas.
        assertEquals("Cuaderno", CarpetaDePaginas.nombreLibre("Cuaderno", listOf(lienzo("x").copy(nombre = "Cuaderno"))))
    }

    @Test fun cambiar_poda_lo_borrado_y_guarda() {
        // Lo de `cambiar_en_disco` sin disco: podar y luego cambiar, como hace `CarpetasDelChat.cambiar`.
        val c = CarpetaDePaginas().anadir("a", "Día 1")!!.anadir("fantasma", "Día 2")!!
        assertEquals(2, c.size)
        val (podada, quitadas) = c.podar { it == "a" }
        assertEquals(1, quitadas)
        val texto = podada.escribir()
        val leida = CarpetaDePaginas.leer(texto)
        assertEquals("Día 1", leida.paginas[0].titulo)
        assertNotNull(leida.anadir("b", leida.tituloSiguiente("Página")))
        assertEquals("Día 2", leida.tituloSiguiente("Página"))
    }

    @Test fun un_titulo_largo_se_corta_en_letras() {
        val largo = "😀".repeat(100)
        val c = CarpetaDePaginas().anadir("a", largo)!!
        assertEquals(CarpetaDePaginas.TOPE_TITULO, c.paginas[0].titulo.codePointCount(0, c.paginas[0].titulo.length))
    }
}
