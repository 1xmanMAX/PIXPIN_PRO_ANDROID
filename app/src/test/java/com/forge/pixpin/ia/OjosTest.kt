package com.forge.pixpin.ia

import com.forge.pixpin.motor.Element
import com.forge.pixpin.motor.ElementType
import com.forge.pixpin.motor.Pt
import com.forge.pixpin.motor.Scene
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** **Los ojos del asistente** (PC `ojos.rs` y `ojos/pruebas_revision.rs`): las zonas, la rejilla y el bucle de la revisión. */
class OjosTest {
    private fun caja(x: Double, y: Double, w: Double, h: Double, id: String) =
        Element(id = id, type = ElementType.RECTANGLE, x = x, y = y, width = w, height = h, seed = 1)

    @Test fun `lee las zonas y lo que falta es todo`() {
        assertEquals(Ojos.Zona.Todo, Ojos.leer("").zona)
        assertEquals(Ojos.Zona.Todo, Ojos.leer("{}").zona)
        assertEquals(Ojos.Zona.Caja(doubleArrayOf(1.0, 2.0, 3.0, 4.0)), Ojos.leer("""{"zona":"caja","caja":[1,2,3,4]}""").zona)
        assertEquals(Ojos.Zona.Caja(doubleArrayOf(1.0, 2.0, 3.0, 4.0)), Ojos.leer("""{"caja":[1,2,3,4]}""").zona)
        assertEquals(Ojos.Zona.Elementos("#3"), Ojos.leer("""{"zona":"elementos","que":"#3"}""").zona)
        assertEquals(Ojos.Zona.Documento, Ojos.leer("""{"zona":"documento"}""").zona)
        assertEquals(Ojos.Zona.Ultimo, Ojos.leer("""{"zona":"último"}""").zona)
        for ((a, palabra) in listOf("""{"zona":"caja"}""" to "caja", """{"zona":"cielo"}""" to "cielo", """{"zona":"elementos"}""" to "que")) {
            try { Ojos.leer(a); fail(a) } catch (e: Ojos.Fallo) { assertTrue(e.message!!, e.message!!.contains(palabra)) }
        }
    }

    @Test fun `la caja de cada zona en la escena`() {
        val e = Scene(elements = listOf(caja(100.0, 100.0, 50.0, 50.0, "a"), caja(400.0, 300.0, 100.0, 20.0, "b")))
        val num = Edicion.Numeracion(e.elements)
        val ctx = Edicion.Contexto(marco = Pt(60.0, 60.0), elegidos = listOf("b"), ultimos = listOf("a"))
        val m = Ojos.MARGEN
        assertEquals(Ojos.Caja(100 - m, 100 - m, 500 + m, 320 + m), Ojos.cajaDe(e, Ojos.Mirada(), ctx, num))
        assertEquals(Ojos.Caja(60.0, 60.0, 70.0, 80.0), Ojos.cajaDe(e, Ojos.Mirada(Ojos.Zona.Caja(doubleArrayOf(0.0, 0.0, 10.0, 20.0))), ctx, num))
        val el = Ojos.cajaDe(e, Ojos.Mirada(Ojos.Zona.Elegido), ctx, num)
        assertTrue(el.x0 < 400 && el.x1 > 500)
        val ul = Ojos.cajaDe(e, Ojos.Mirada(Ojos.Zona.Ultimo), ctx, num)
        assertTrue(ul.x0 < 100 && ul.x1 < 400)
        val un = Ojos.cajaDe(e, Ojos.Mirada(Ojos.Zona.Elementos("#1")), ctx, num)
        assertTrue(un.x0 < 100 - m && un.x1 > 150 + m)
        fun falla(s: Scene, z: Ojos.Zona, c: Edicion.Contexto) = try { Ojos.cajaDe(s, Ojos.Mirada(z), c, num); "" } catch (x: Ojos.Fallo) { x.message!! }
        assertTrue(falla(Scene(), Ojos.Zona.Todo, ctx).contains("vacio"))
        assertTrue(falla(e, Ojos.Zona.Elegido, Edicion.Contexto()).isNotEmpty())
        assertTrue(falla(e, Ojos.Zona.Ultimo, Edicion.Contexto()).isNotEmpty())
    }

    @Test fun `el tamano no pasa del lado ni amplia de mas y la rejilla es redonda`() {
        val (w, h, s) = Ojos.tamano(Ojos.Caja(0.0, 0.0, 4000.0, 1000.0))
        assertEquals(1024 to 256, w to h); assertEquals(0.256, s, 1e-3)
        assertEquals(Triple(200, 100, Ojos.AUMENTO_MAXIMO), Ojos.tamano(Ojos.Caja(0.0, 0.0, 100.0, 50.0)))
        assertEquals(100.0, Ojos.pasoDeRejilla(Ojos.Caja(0.0, 0.0, 800.0, 600.0)), 0.0)
        assertEquals(20.0, Ojos.pasoDeRejilla(Ojos.Caja(0.0, 0.0, 160.0, 40.0)), 0.0)
        assertEquals(500.0, Ojos.pasoDeRejilla(Ojos.Caja(0.0, 0.0, 3000.0, 600.0)), 0.0)
        val e = Scene(elements = listOf(caja(100.0, 100.0, 50.0, 50.0, "a"), caja(900.0, 900.0, 10.0, 10.0, "b")))
        val num = Edicion.Numeracion(e.elements)
        assertEquals(listOf(1 to (100.0 to 100.0)), Ojos.etiquetas(e, Ojos.Caja(0.0, 0.0, 500.0, 500.0), num))
        assertTrue(Ojos.etiquetas(e, Ojos.Caja(2000.0, 2000.0, 2100.0, 2100.0), num).isEmpty())
    }

    @Test fun `la revision reconoce el todo bien y los pedidos que dependen de lo que se ve`() {
        assertTrue(Ojos.esTodoBien("TODO_BIEN")); assertTrue(Ojos.esTodoBien("todo_bien.")); assertTrue(Ojos.esTodoBien("OK"))
        assertFalse(Ojos.esTodoBien("Movi la etiqueta #4 para que no tape el circulo."))
        assertTrue(Ojos.pedidoDeRevision("dibuja una casa", 1).contains("dibuja una casa"))
        assertTrue(Ojos.pideMirar("Corrige lo que se solapa")); assertTrue(Ojos.pideMirar("¿qué falta en el diagrama?"))
        assertTrue(Ojos.pideMirar("pon una etiqueta a cada figura")); assertTrue(Ojos.pideMirar("alinea los rectángulos"))
        assertFalse(Ojos.pideMirar("dibuja una casa")); assertFalse(Ojos.pideMirar("apunta comprar pan mañana"))
        val v = Ojos.Vista(byteArrayOf(1, 2), 2, 1, Ojos.leyenda(Ojos.Caja(0.0, 0.0, 100.0, 50.0), Pt(0.0, 0.0), 2, 1, 0.02, true))
        val m = Ojos.mensajeDeVista(v)
        assertTrue(m.tieneImagen); assertTrue(Ojos.esVista(m)); assertFalse(Ojos.esVista(MensajeIa.usuario("dibuja")))
        assertTrue(m.textoEntero.contains("#id"))
    }

    // ------------------------------------------------------------------- el bucle (pruebas_revision.rs)

    private class ConOjos(val ciego: Boolean = false) : Agente.Ejecutor {
        val hechas = mutableListOf<Llamada>()
        val miradas = mutableListOf<Ojos.Mirada>()
        override fun ejecutar(llamada: Llamada): Result<String> {
            hechas += llamada
            return Result.success("hecho: 1 elementos anadidos al lienzo\n#1 texto negro [0,0,80,20] \"Hola\"")
        }
        override fun ver(mirada: Ojos.Mirada): Result<Ojos.Vista> {
            miradas += mirada
            if (ciego) return Result.failure(Ojos.Fallo("la ventana del chat no contesto"))
            return Result.success(Ojos.Vista(byteArrayOf(0x89.toByte(), 'P'.code.toByte()), 400, 300, "${Ojos.MARCA} Zona [0, 0, 400, 300]"))
        }
    }

    private val DIBUJA = """{"elementos":[{"tipo":"texto","x":10,"y":10,"texto":"Hola"}]}"""
    private val MUEVE = """{"que":"#1","accion":"mover","dx":40}"""

    private class Corrida(val f: Agente.Final, val ev: List<Agente.Evento>, val h: List<MensajeIa>, val motor: MotorGuionado)

    private fun correr(guion: List<Respuesta>, vision: Boolean, revisar: Int, ej: ConOjos): Corrida {
        val motor = MotorGuionado(guion, vision)
        val h = mutableListOf(MensajeIa.sistema(Agente.instrucciones()), MensajeIa.usuario("escribe Hola en el lienzo"))
        val ev = mutableListOf<Agente.Evento>()
        val f = Agente.turno(motor, h, Herramientas.catalogo(), ej, opciones = Agente.Opciones(ojos = true, revisar = revisar)) { ev += it; Seguir.SI }
        return Corrida(f, ev, h, motor)
    }

    private fun textos(ev: List<Agente.Evento>) = ev.filterIsInstance<Agente.Evento.Texto>().joinToString("") { it.texto }

    @Test fun `la revision ve lo dibujado y corrige en el mismo turno`() {
        val ej = ConOjos()
        val c = correr(listOf(Respuesta.deLlamada("dibujar_en_lienzo", DIBUJA), Respuesta.deTexto("Escribi Hola."),
            Respuesta.deLlamada("transformar_elementos", MUEVE), Respuesta.deTexto("Movi #1 para que no tape el borde.")), true, 1, ej)
        assertEquals(2, ej.hechas.size)
        assertEquals(listOf(Ojos.Mirada(Ojos.Zona.Turno)), ej.miradas)
        assertTrue(c.f.texto, c.f.texto.startsWith("Escribi Hola.") && c.f.texto.endsWith("Movi #1 para que no tape el borde."))
        val t = textos(c.ev)
        assertTrue(t, t.startsWith("Escribi Hola.") && t.contains("Movi #1"))
        assertEquals(4, c.motor.recibido.size)
        val vista = c.motor.recibido[2].mensajes.last()
        assertEquals(Rol.USUARIO, vista.rol); assertTrue(vista.tieneImagen)
        assertTrue(vista.textoEntero.contains("escribe Hola en el lienzo"))
        assertTrue(c.motor.recibido[0].herramientas.any { it.nombre == Ojos.NOMBRE })
        assertTrue(c.h.any(Ojos::esVista))
    }

    @Test fun `si esta bien no dice nada ni deja la imagen`() {
        val ej = ConOjos()
        val c = correr(listOf(Respuesta.deLlamada("dibujar_en_lienzo", DIBUJA), Respuesta.deTexto("Escribi Hola."), Respuesta.deTexto(Ojos.TODO_BIEN)), true, 2, ej)
        assertEquals("Escribi Hola.", c.f.texto)
        assertFalse(textos(c.ev).contains(Ojos.TODO_BIEN))
        assertEquals(1, ej.miradas.size)
        assertEquals(3, c.motor.recibido.size)
        assertFalse(c.h.any(Ojos::esVista))
    }

    @Test fun `la segunda ronda solo si la primera corrigio`() {
        val ej = ConOjos()
        val c = correr(listOf(Respuesta.deLlamada("dibujar_en_lienzo", DIBUJA), Respuesta.deTexto("Escribi Hola."),
            Respuesta.deLlamada("transformar_elementos", MUEVE), Respuesta.deTexto("Movi #1."), Respuesta.deTexto(Ojos.TODO_BIEN)), true, 2, ej)
        assertEquals(2, ej.miradas.size)
        assertEquals(5, c.motor.recibido.size)
        assertEquals("Escribi Hola.\n\nMovi #1.", c.f.texto)
    }

    @Test fun `sin vision ni se ofrece ver ni se revisa y un turno que no dibuja no se revisa`() {
        val ej = ConOjos()
        val c = correr(listOf(Respuesta.deLlamada("dibujar_en_lienzo", DIBUJA), Respuesta.deTexto("Escribi Hola.")), false, 2, ej)
        assertEquals("Escribi Hola.", c.f.texto)
        assertTrue(ej.miradas.isEmpty())
        assertEquals(2, c.motor.recibido.size)
        assertFalse(c.motor.recibido[0].herramientas.any { it.nombre == Ojos.NOMBRE })
        val ej2 = ConOjos()
        val c2 = correr(listOf(Respuesta.deTexto("Hola, ¿que dibujo?")), true, 2, ej2)
        assertEquals("Hola, ¿que dibujo?", c2.f.texto)
        assertTrue(ej2.miradas.isEmpty()); assertEquals(1, c2.motor.recibido.size)
    }

    @Test fun `si no se puede mirar o el modelo falla al revisar se queda lo de antes`() {
        val ej = ConOjos(ciego = true)
        val c = correr(listOf(Respuesta.deLlamada("dibujar_en_lienzo", DIBUJA), Respuesta.deTexto("Escribi Hola.")), true, 1, ej)
        assertEquals("Escribi Hola.", c.f.texto)
        assertEquals(1, ej.miradas.size)
        assertEquals(2, c.motor.recibido.size)
        assertFalse(c.h.any(Ojos::esVista))
        // El guion se acaba justo en la revisión: el motor da error.
        val c2 = correr(listOf(Respuesta.deLlamada("dibujar_en_lienzo", DIBUJA), Respuesta.deTexto("Escribi Hola.")), true, 1, ConOjos())
        assertEquals("Escribi Hola.", c2.f.texto); assertFalse(c2.f.cortado); assertFalse(c2.h.any(Ojos::esVista))
    }

    @Test fun `ver lienzo pinta despues de dibujar y la imagen va detras de los resultados`() {
        val ej = ConOjos()
        val dos = Respuesta.deLlamada("dibujar_en_lienzo", DIBUJA).let { it.copy(llamadas = it.llamadas + LlamadaCruda("v1", Ojos.NOMBRE, """{"zona":"ultimo"}""")) }
        val c = correr(listOf(dos, Respuesta.deTexto("Listo y comprobado.")), true, 0, ej)
        assertEquals("Listo y comprobado.", c.f.texto)
        assertEquals(listOf(Ojos.Mirada(Ojos.Zona.Ultimo)), ej.miradas)
        assertTrue(c.ev.any { it is Agente.Evento.Resultado && it.nombre == Ojos.NOMBRE && it.ok })
        val m = c.motor.recibido[1].mensajes
        val n = m.size
        assertEquals(Rol.HERRAMIENTA, m[n - 3].rol); assertEquals(Rol.HERRAMIENTA, m[n - 2].rol)
        assertTrue(m[n - 2].textoEntero.contains("imagen va en el mensaje siguiente"))
        assertTrue(m[n - 1].tieneImagen && Ojos.esVista(m[n - 1]))
    }

    @Test fun `una zona mal dicha se reintenta`() {
        val ej = ConOjos()
        val c = correr(listOf(Respuesta.deLlamada(Ojos.NOMBRE, """{"zona":"cielo"}"""), Respuesta.deTexto("Vale.")), true, 0, ej)
        assertEquals(1, c.f.reintentos)
        assertTrue(ej.miradas.isEmpty())
        assertTrue(c.motor.recibido[1].mensajes.last().textoEntero.startsWith("ERROR"))
    }
}
