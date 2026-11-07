package com.forge.pixpin.ia

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtefactosTest {
    private fun args(s: String) = Json.parseToJsonElement(s) as JsonObject
    private fun validar(nombre: String, json: String) = Herramientas.validar(LlamadaCruda("1", nombre, json), Artefactos.herramientas())

    private val buena = """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
        <style>body{margin:0}</style></head><body><button id="b">+</button><script>
        let n = 0; const re = /a[}]b/g; document.getElementById('b').onclick = () => { n++; if (n > 2) { console.log(`n=${'$'}{n}`) } };
        </script></body></html>"""

    @Test fun crearUnaMiniappValidaYSinAvisos() {
        val v = validar(Artefactos.CREAR, Json.encodeToString(JsonObject.serializer(), args("""{"nombre":"Mi calculadora!.html","contenido":"x"}""")))
        val p = (v.llamada as Llamada.Artefacto).pedido as Artefactos.Pedido.Crear
        assertEquals("Mi calculadora_.html", p.nombre)
        assertEquals(emptyList<String>(), Artefactos.revisar("a.html", buena))
    }

    @Test fun elHtmlCortadoSeDetecta() {
        val cortado = buena.substringBefore("n++;")
        val r = Artefactos.revisar("a.html", cortado)
        assertTrue(r.toString(), r.any { it.contains("</html>") })
        assertTrue(r.toString(), r.any { it.contains("<script>") })
        val sinCierre = buena.replace("console.log(`n=${'$'}{n}`) }", "console.log(1)")
        assertTrue(Artefactos.revisar("a.html", sinCierre).toString(), Artefactos.revisar("a.html", sinCierre).any { it.contains("sin cerrar") })
    }

    @Test fun loQueLeFaltaAUnaMiniappDeTelefono() {
        val r = Artefactos.revisar("a.html", "<html><body><script>alert(1); localStorage.x=1</script></body></html>")
        assertTrue(r.any { it.contains("viewport") })
        assertTrue(r.any { it.contains("alert") })
        assertTrue(r.any { it.contains("localStorage") })
    }

    @Test fun unaExtensionQueNoEsDeTextoNoSeCrea() {
        val e = runCatching { validar(Artefactos.CREAR, """{"nombre":"x.exe","contenido":"a"}""") }.exceptionOrNull()
        assertTrue(e is ErrorHerramienta.Parametro)
        val pdf = runCatching { validar(Artefactos.CREAR, """{"nombre":"x.pdf","contenido":"a"}""") }.exceptionOrNull()
        assertTrue(pdf?.message.orEmpty().contains("crear_pdf"))
    }

    @Test fun editarCambiaTrozosExactosYTodoONada() {
        val t = "uno\ndos\ntres\ndos\n"
        val r = Artefactos.editar(t, listOf(Artefactos.Cambio("uno", "1", false))).getOrThrow()
        assertEquals("1\ndos\ntres\ndos\n" to 1, r)
        val repetido = Artefactos.editar(t, listOf(Artefactos.Cambio("dos", "2", false)))
        assertTrue(repetido.exceptionOrNull()!!.message!!.contains("2 veces"))
        assertEquals("uno\n2\ntres\n2\n" to 2, Artefactos.editar(t, listOf(Artefactos.Cambio("dos", "2", true))).getOrThrow())
        // El segundo falla: no se aplica ninguno.
        val mal = Artefactos.editar(t, listOf(Artefactos.Cambio("uno", "1", false), Artefactos.Cambio("cuatro", "4", false)))
        assertTrue(mal.isFailure)
        assertTrue(mal.exceptionOrNull()!!.message!!.contains("no aparece"))
    }

    @Test fun leerNumeraYDicePorDondeSeguir() {
        val t = (1..50).joinToString("\n") { "linea $it" }
        val r = Artefactos.numerar(t, 10, 12)
        assertTrue(r.startsWith("10│ linea 10\n11│ linea 11\n12│ linea 12\n"))
        assertTrue(r.contains("sigue con desde=13"))
        assertTrue(Artefactos.numerar(t, letras = 60).contains("sigue con desde="))
    }

    @Test fun elPlanoSaleComoDxfConSusCapasYCotas() {
        val v = validar(Artefactos.PLANO, """{"nombre":"Planta casa","unidades":"m",
            "capas":[{"nombre":"Muros","color":7},{"nombre":"Cotas","color":2}],
            "entidades":[
              {"tipo":"rectangulo","capa":"Muros","esquina":[0,0],"tam":[8,6]},
              {"tipo":"linea","capa":"Muros","puntos":[[4,0],[4,6]]},
              {"tipo":"arco","capa":"Puertas","centro":[4,1],"radio":0.9,"desde":90,"hasta":180},
              {"tipo":"texto","capa":"Textos","en":[1,3],"texto":"Dormitorio 24 m²"},
              {"tipo":"cota","capa":"Cotas","puntos":[[0,0],[8,0]],"separacion":-0.8}
            ]}""")
        val p = (v.llamada as Llamada.Artefacto).pedido as Artefactos.Pedido.Plano
        assertEquals("Planta casa.dxf", p.nombre)
        val dxf = Dxf.escribir(p.plano)
        assertTrue(dxf.startsWith("0\nSECTION\n2\nHEADER\n"))
        assertTrue(dxf.trimEnd().endsWith("0\nEOF"))
        for (e in listOf("POLYLINE", "LINE", "ARC", "TEXT", "LAYER\n2\nMUROS", "LAYER\n2\nPUERTAS", "8.00")) assertTrue(e, dxf.contains(e))
        assertTrue("las tildes van escapadas", dxf.contains("\\U+00B2") && dxf.lines().all { l -> l.all { it.code < 128 } })
        assertTrue(Dxf.resumen(p.plano).startsWith("mide 8.00 × 6.80 m") || Dxf.resumen(p.plano).startsWith("mide 8.00 × "))
    }

    @Test fun unPlanoMalDiceQueFalta() {
        val e = runCatching { validar(Artefactos.PLANO, """{"nombre":"x","entidades":[{"tipo":"circulo","centro":[0,0]}]}""") }.exceptionOrNull()
        assertTrue(e?.message.orEmpty().contains("radio"))
    }

    @Test fun cuandoElPedidoEsDeCrear() {
        assertTrue(Artefactos.pideCrear("Hazme una calculadora de notas para mi clase"))
        assertTrue(Artefactos.pideCrear("créame un PDF con el informe de la semana"))
        assertTrue(Artefactos.pideCrear("dibuja el plano de mi casa? no: genera un plano de una casa de 8x6"))
        assertFalse(Artefactos.pideCrear("¿qué hora es en Lima?"))
        assertFalse(Artefactos.pideCrear("añade leche a la lista de la compra"))
    }

    @Test fun lasDeCrearNoSeAcotanYPidenRespuestaLarga() {
        val acotadas = Agente.conTopes(Artefactos.herramientas() + Herramientas.catalogo().take(1), 16)
        val crear = acotadas.first { it.nombre == Artefactos.CREAR }
        assertEquals(Artefactos.herramientas().first { it.nombre == Artefactos.CREAR }, crear)
        val p = Peticion(listOf(MensajeIa.usuario("hola")), Artefactos.herramientas())
        assertEquals(Artefactos.TOKENS_AL_CREAR, Agente.topeDeTokens(p, 200_000))
        assertEquals(Agente.MAX_TOKENS_CON_HERRAMIENTAS, Agente.topeDeTokens(p.copy(herramientas = Herramientas.catalogo()), 200_000))
    }
}
