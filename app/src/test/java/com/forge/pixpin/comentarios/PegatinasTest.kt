package com.forge.pixpin.comentarios

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PegatinasTest {
    private fun q(a: String) = Quien(a, a)

    private fun base(): Pair<Pegatinas, String> {
        val (p, id) = Pegatinas().poner(Tipo.MEDIA, Ancla.pagina(0, 10.0, 10.0), q("pc"), 100)
        return p.escribir(id, q("pc"), 110, "hola").first to id
    }

    @Test
    fun dos_aparatos_escriben_a_la_vez_y_quedan_las_dos_entradas() {
        val (b, id) = base()
        val pc = b.escribir(id, q("pc"), 200, "desde el PC").first.mover(id, Ancla.pagina(0, 50.0, 50.0), 250)
        val movil = b.escribir(id, q("and"), 150, "desde el movil").first.cambiarTipo(id, Tipo.URGENTE, 300)
        val j = Fusion.juntar(pc, movil)
        val c = j.get(id)!!
        assertEquals(listOf("hola", "desde el movil", "desde el PC"), c.entradas().map { it.texto })
        assertEquals(Tipo.URGENTE, c.tipo)
        assertEquals(Ancla.pagina(0, 10.0, 10.0), c.ancla)
        assertEquals(j, Fusion.juntar(movil, pc))
        assertEquals(j, Fusion.juntar(j, j))
        assertEquals(j, Fusion.juntar(j, pc))
    }

    @Test
    fun lo_quitado_no_vuelve_salvo_que_se_escriba_despues() {
        val (b, id) = base()
        val pc = b.quitar(id, 500)
        val j = Fusion.juntar(pc, b)
        assertNull(j.get(id))
        assertEquals(1, j.lista.size)
        val movil = b.escribir(id, q("and"), 600, "aun no!").first
        val j2 = Fusion.juntar(pc, movil)
        val c = j2.get(id)
        assertNotNull(c)
        assertEquals(listOf("aun no!"), c!!.entradas().map { it.texto })
        assertEquals(j2, Fusion.juntar(movil, pc))
    }

    @Test
    fun cambiado_siempre_avanza_e_ids_unicos() {
        val (b, id) = base()
        val r = b.resolver(id, true, 50) // reloj para atrás
        assertTrue(r.get(id)!!.cambiado > b.get(id)!!.cambiado)
        val (p2, id2) = r.poner(Tipo.NOTA, Ancla.escena(1.0, 2.0), q("pc"), 100)
        assertTrue(id2 != id)
        assertEquals("pc-64-0", id)
        assertEquals("pc-64-1", id2)
        assertEquals(1, p2.abiertas())
    }

    @Test
    fun el_fichero_del_pc_se_lee_y_se_reescribe_sin_perder_nada() {
        val pc = """
        {
          "version": 1,
          "comentarios": [{"id": "n1", "texto": "de la nota"}],
          "otra": true,
          "pegatinas": [
            {
              "id": "pc1-18f2a3b4c5d-0", "tipo": "urgente",
              "ancla": {"sitio": "pagina", "pagina": 2, "x": 120.5, "y": 700.0, "raro": 1},
              "autor": "PC de Max", "aparato": "pc1", "creado": 1793582613265, "cambiado": 1793582613265,
              "resuelto": false, "tarea": "K7Q2M9X4PA", "futuro": {"a": 1},
              "hilo": [
                {"id": "pc1-18f2a3b4c5e-0", "autor": "PC de Max", "aparato": "pc1", "cuando": 1793582614000,
                 "cambiado": 1793582614000, "texto": "La viga del eje B no cuadra",
                 "adjuntos": [
                   {"tipo": "imagen", "ruta": "pins/draw/anot-ABCDE23456.a9f1c2d3e4f5a6b7c.adjunto", "nombre": "foto.jpg"},
                   {"tipo": "elemento", "lienzo": "d1", "elemento": "e", "punto": [320.5, 1040.0], "rotulo": "Viga B"},
                   {"tipo": "sonido", "ruta": "x"}
                 ]}
              ]
            }
          ]
        }
        """.trimIndent()
        val p = Fichero.leer(pc)!!
        val c = p.lista.single()
        assertEquals(Tipo.URGENTE, c.tipo)
        assertEquals(2, c.ancla.pagina)
        assertEquals(120.5, c.ancla.x!!, 0.0)
        assertEquals("K7Q2M9X4PA", c.tarea)
        assertEquals(3, c.hilo.single().adjuntos.size)
        assertEquals("sonido", c.hilo.single().adjuntos[2].tipo)
        val texto = Fichero.escribir(pc, p)
        assertTrue(texto.endsWith("\n"))
        assertTrue(texto.contains("\"de la nota\""))
        assertTrue(texto.contains("\"otra\""))
        assertTrue(texto.contains("\"futuro\""))
        assertTrue(texto.contains("\"raro\""))
        assertEquals(p, Fichero.leer(texto))
        // Lo que llega igual a lo de aquí no se reescribe.
        assertNull(Fusion.alLlegar(texto, pc))
        // Un JSON roto no se lee (y por tanto no se pisa).
        assertNull(Fichero.leer("{roto"))
    }

    @Test
    fun al_llegar_junta_lo_de_los_dos() {
        val (b, id) = base()
        val aqui = Fichero.escribir(null, b.escribir(id, q("and"), 300, "aquí").first)
        val llega = Fichero.escribir(null, b.escribir(id, q("pc"), 200, "allí").first)
        val junto = Fusion.alLlegar(aqui, llega)!!
        assertEquals(listOf("hola", "allí", "aquí"), Fichero.leer(junto)!!.get(id)!!.entradas().map { it.texto })
    }

    @Test
    fun la_huella_es_fnv1a_de_64() {
        assertEquals("cbf29ce484222325", EstadoDePegatinas.huella(ByteArray(0)))
        assertEquals("af63dc4c8601ec8c", EstadoDePegatinas.huella("a".toByteArray()))
    }
}
