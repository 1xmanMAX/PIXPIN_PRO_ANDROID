package com.forge.pixpin.motormd

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Los comentarios de las notas: **las mismas pruebas que el PC** (`md_comentarios::pruebas`),
 * para que una nota comentada en un aparato ancle igual en el otro.
 */
class ComentariosTest {
    private val yo = Comentarios.Quien("Portátil", "K7Q2")
    private val movil = Comentarios.Quien("Teléfono", "MOVI")
    private val NOTA = "# Obra\nLa losa del segundo piso ya esta hormigonada.\nFalta el curado de la losa.\n"

    private fun ancla(texto: String, cita: String, n: Int): Comentarios.Ancla {
        var i = -1
        repeat(n + 1) { i = texto.indexOf(cita, i + 1) }
        return Comentarios.anclaDe(texto, i, i + cita.length)!!
    }

    private fun trozo(t: String, r: IntRange) = t.substring(r.first, r.last + 1)

    @Test
    fun `crear un comentario guarda la cita su contexto y quien`() {
        val a = ancla(NOTA, "segundo piso", 0)
        assertEquals("segundo piso", a.cita)
        assertEquals("# Obra\nLa losa del ", a.antes)
        assertEquals(" ya esta hormigonada.\nFalta el c", a.despues)
        val (c, id) = Comentarios.nuevo(Comentarios.Fichero(), a, yo, 1000, "  ¿Seguro que es el segundo?  ")!!
        val h = c.hilo(id)!!
        assertEquals("¿Seguro que es el segundo?", h.texto)
        assertEquals(Triple("Portátil", "K7Q2", 1000L), Triple(h.autor, h.aparato, h.cuando))
        assertEquals(1, c.abiertos)
    }

    @Test
    fun `un comentario vacio o sin cita no se crea`() {
        val c = Comentarios.Fichero()
        assertNull(Comentarios.nuevo(c, ancla(NOTA, "losa", 0), yo, 1, "   "))
        assertNull(Comentarios.nuevo(c, Comentarios.Ancla(), yo, 1, "hola"))
        assertNull(Comentarios.anclaDe("a   b", 1, 4))
        assertNull(Comentarios.anclaDe("abc", 2, 2))
    }

    @Test
    fun `la cita no lleva los blancos de los bordes ni parte un emoji`() {
        val t = "ver 😀 la losa "
        assertEquals("😀 la losa", Comentarios.anclaDe(t, 3, 15)!!.cita)
        assertEquals("la", Comentarios.anclaDe(t, 5, 10)!!.cita)
    }

    @Test
    fun `responder editar resolver y borrar un hilo`() {
        var (c, h) = Comentarios.nuevo(Comentarios.Fichero(), ancla(NOTA, "curado", 0), yo, 10, "¿Cuantos dias?")!!
        val (c2, r) = Comentarios.responder(c, h, movil, 20, "Siete")!!
        assertNotEquals(r, h)
        c = Comentarios.editar(c2, r, "Siete, regando", 30)!!
        assertEquals("Siete, regando", c.hilo(h)!!.respuestas[0].texto)
        assertEquals(30L, c.hilo(h)!!.respuestas[0].editado)
        c = Comentarios.resolver(c, h, true, yo, 40)!!
        assertEquals(0, c.abiertos)
        assertEquals("Portátil", c.hilo(h)!!.resueltoPor)
        c = Comentarios.responder(c, h, yo, 50, "Al final ocho")!!.first
        assertFalse(c.hilo(h)!!.resuelto)
        c = Comentarios.borrar(c, r)!!
        assertEquals(1, c.hilo(h)!!.respuestas.size)
        c = Comentarios.borrar(c, h)!!
        assertTrue(c.comentarios.isEmpty())
    }

    @Test
    fun `lo que no esta no se responde ni se edita ni se borra`() {
        val (c, h) = Comentarios.nuevo(Comentarios.Fichero(), ancla(NOTA, "curado", 0), yo, 10, "x")!!
        assertNull(Comentarios.responder(c, "nadie", yo, 1, "hola"))
        assertNull(Comentarios.responder(c, h, yo, 1, "  "))
        assertNull(Comentarios.editar(c, "nadie", "y", 1))
        assertNull(Comentarios.editar(c, h, "", 1))
        assertNull(Comentarios.borrar(c, "nadie"))
        assertNull(Comentarios.resolver(c, "nadie", true, yo, 1))
    }

    @Test
    fun `dos comentarios en el mismo milisegundo no comparten id`() {
        val (c, a) = Comentarios.nuevo(Comentarios.Fichero(), ancla(NOTA, "losa", 0), yo, 5, "a")!!
        val (_, b) = Comentarios.nuevo(c, ancla(NOTA, "losa", 1), yo, 5, "b")!!
        assertNotEquals(a, b)
        assertEquals("K7Q2-5-0", a)
    }

    @Test
    fun `el json va y vuelve y conserva lo que no entiende`() {
        var (c, h) = Comentarios.nuevo(Comentarios.Fichero(), ancla(NOTA, "curado", 0), yo, 10, "¿Cuantos dias?")!!
        c = Comentarios.responder(c, h, movil, 20, "Siete")!!.first
        val json = Comentarios.escribir(c)
        assertTrue(json.contains("\"comentarios\""))
        assertFalse("lo que no hay no se escribe", json.contains("editado"))
        assertEquals(c, Comentarios.leer(json))
        val raro = """{"version":2,"queSeYo":1,"comentarios":[{"id":"a","texto":"t","ancla":{"cita":"x","peso":3},"color":"rojo","respuestas":[]}]}"""
        val l = Comentarios.leer(raro)!!
        assertEquals(2, l.version)
        val v = Json.parseToJsonElement(Comentarios.escribir(l)).jsonObject
        assertEquals("1", v["queSeYo"].toString())
        val hilo = v["comentarios"]!!.jsonArray[0].jsonObject
        assertEquals("\"rojo\"", hilo["color"].toString())
        assertEquals("3", hilo["ancla"]!!.jsonObject["peso"].toString())
    }

    @Test
    fun `un fichero vacio es sin comentarios y uno roto es un error`() {
        assertEquals(Comentarios.Fichero(), Comentarios.leer(""))
        assertEquals(Comentarios.Fichero(), Comentarios.leer("﻿  \n"))
        assertNull(Comentarios.leer("{\"comentarios\": ["))
        assertNull(Comentarios.leer("[1,2]"))
    }

    @Test
    fun `la cita repetida se encuentra por su contexto`() {
        val a = ancla(NOTA, "losa", 1)
        assertEquals(a.pos, Comentarios.ubicar(NOTA, a)!!.first)
        val nuevo = NOTA.replace("# Obra\n", "# Obra en Lima, segunda fase\n")
        val r = Comentarios.ubicar(nuevo, a)!!
        assertEquals("losa", trozo(nuevo, r))
        assertTrue(nuevo.substring(0, r.first).endsWith("curado de la "))
    }

    @Test
    fun `reanclar tras escribir dentro de la cita la sigue`() {
        val a = ancla(NOTA, "ya esta hormigonada", 0)
        val nuevo = NOTA.replace("ya esta hormigonada", "ya esta casi del todo hormigonada")
        val (a2, r) = Comentarios.reanclar(nuevo, a)
        assertEquals("ya esta casi del todo hormigonada", trozo(nuevo, r!!))
        assertEquals("ya esta casi del todo hormigonada", a2.cita)
        assertEquals(r.first, a2.pos)
        assertEquals(r, Comentarios.ubicar(nuevo, a2))
    }

    @Test
    fun `escribir dentro de la cita y en su contexto a la vez no la suelta`() {
        val a = ancla(NOTA, "ya esta hormigonada", 0)
        val nuevo = NOTA.replace("# Obra\n", "# Obra en Lima\nIntro.\n").replace("ya esta hormigonada", "ya esta casi hormigonada")
        assertEquals("ya esta casi hormigonada", trozo(nuevo, Comentarios.reanclar(nuevo, a).second!!))
    }

    @Test
    fun `una cita borrada entera queda sin ancla y vuelve si vuelve el texto`() {
        val a = ancla(NOTA, "del segundo piso", 0)
        val (a2, r) = Comentarios.reanclar(NOTA.replace("del segundo piso ", ""), a)
        assertNull(r)
        assertEquals("del segundo piso", a2.cita)
        assertNotNull(Comentarios.reanclar(NOTA, a2).second)
    }

    @Test
    fun `un contexto corto no ancla en cualquier sitio`() {
        val a = Comentarios.Ancla(cita = "zzz", antes = "a ", despues = " b", pos = 0)
        assertNull(Comentarios.ubicar("a xx b a yy b", a))
    }

    @Test
    fun `una cita al principio o al final se sigue por el borde`() {
        val texto = "Primera frase de la nota y algo mas aqui."
        val a = ancla(texto, "Primera", 0)
        assertEquals("", a.antes)
        val t = texto.replace("Primera", "Una primerisima")
        val r = Comentarios.reanclar(t, a).second!!
        assertEquals(0, r.first); assertEquals("Una primerisima", trozo(t, r))
        val b = ancla(texto, "aqui.", 0)
        val t2 = texto.replace("aqui.", "alli, al final.")
        assertEquals("alli, al final.", trozo(t2, Comentarios.reanclar(t2, b).second!!))
    }

    @Test
    fun `la palabra bajo el cursor`() {
        assertEquals(3..6, Comentarios.palabraEn("la losa, ya", 4))
        assertEquals(3..6, Comentarios.palabraEn("la losa, ya", 7))
        assertNull(Comentarios.palabraEn(" , ", 1))
        assertEquals(0..6, Comentarios.palabraEn("cañería", 2))
    }

    @Test
    fun `juntar conserva lo nuevo de los dos lados y respeta lo borrado`() {
        var base = Comentarios.Fichero()
        val (b1, a) = Comentarios.nuevo(base, ancla(NOTA, "losa", 0), yo, 1, "a")!!
        val (b2, b) = Comentarios.nuevo(b1, ancla(NOTA, "curado", 0), yo, 2, "b")!!
        base = b2
        var mio = Comentarios.borrar(base, a)!!
        mio = Comentarios.responder(mio, b, yo, 10, "desde el PC")!!.first
        val (m2, c) = Comentarios.nuevo(mio, ancla(NOTA, "Obra", 0), yo, 11, "c")!!
        mio = m2
        var disco = Comentarios.responder(base, b, movil, 12, "desde el movil")!!.first
        disco = Comentarios.resolver(disco, b, true, movil, 13)!!
        val (d2, d) = Comentarios.nuevo(disco, ancla(NOTA, "piso", 0), movil, 14, "d")!!
        disco = d2
        val j = Comentarios.fusionar(base, mio, disco)
        assertEquals(listOf(b, c, d), j.comentarios.map { it.id })
        assertEquals(2, j.hilo(b)!!.respuestas.size)
        assertTrue(j.hilo(b)!!.resuelto)
    }

    @Test
    fun `juntar borra lo borrado alli salvo que aqui se cambiara`() {
        var base = Comentarios.Fichero()
        val (b1, a) = Comentarios.nuevo(base, ancla(NOTA, "losa", 0), yo, 1, "a")!!
        val (b2, b) = Comentarios.nuevo(b1, ancla(NOTA, "curado", 0), yo, 2, "b")!!
        base = b2
        val mio = Comentarios.editar(base, b, "b cambiado", 5)!!
        val j = Comentarios.fusionar(base, mio, Comentarios.Fichero())
        assertNull(j.hilo(a))
        assertEquals("b cambiado", j.hilo(b)!!.texto)
        assertEquals(base, Comentarios.fusionar(base, base, base))
    }

    @Test
    fun `los colores de una celda van y vuelven como los escribe el PC`() {
        val html = "<table>\n  <tr>\n    <th colspan=\"2\" align=\"center\">Presupuesto</th>\n  </tr>\n  <tr>\n" +
            "    <td style=\"background:#ffc9c9;color:#e03131\">Hormigon</td>\n    <td align=\"right\" valign=\"middle\">3,20</td>\n  </tr>\n</table>"
        val t = Tablas.leer(html)!!
        val celda = t.filas[1][0]
        assertEquals(0xffc9c9, celda.fondo)
        assertEquals(0xe03131, celda.letra)
        assertEquals(Alineacion.DERECHA, t.filas[1][1].alineacion)
        assertEquals(html, Tablas.aTexto(t))
    }
}
