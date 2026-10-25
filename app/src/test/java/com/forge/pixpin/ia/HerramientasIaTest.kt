package com.forge.pixpin.ia

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * **Las herramientas, como en el PC**: el catálogo idéntico al suyo (byte a byte tras ordenar las
 * claves) y las pruebas de `herramientas.rs` portadas, con los mismos casos negativos y los
 * mismos textos de error (el modelo aprende de ellos).
 */
class HerramientasIaTest {

    private fun validar(nombre: String, args: String) = Herramientas.validar(LlamadaCruda("1", nombre, args), Herramientas.catalogo())

    private fun error(nombre: String, args: String): ErrorHerramienta =
        try { validar(nombre, args); fail("tenía que fallar: $args"); throw AssertionError() } catch (e: ErrorHerramienta) { e }

    private fun figuras(args: String) = (validar(Herramientas.DIBUJAR, args).llamada as Llamada.Dibujar).figuras

    private fun ordenado(e: JsonElement): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.entries.sortedBy { it.key }.associate { it.key to ordenado(it.value) })
        is JsonArray -> JsonArray(e.map(::ordenado))
        else -> e
    }

    @Test fun `el catalogo es el del PC, byte a byte tras ordenar las claves`() {
        // `con_topes(catalogo(), 16)` del PC (main 882d465), volcado con `herramientas/oraculo-del-pc`.
        val delPc = Json.parseToJsonElement(javaClass.getResource("/ia/herramientas-del-pc.json")!!.readText()) as JsonArray
        val porNombre = delPc.associateBy { it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content }
        // Las que llegaron después del volcado (`editar_texto`) se comprueban en su prueba.
        val nuestro = Agente.conTopes(Herramientas.catalogo().filter { h -> Herramientas.POSTERIORES.none { it.nombre == h.nombre } }, Agente.TOPE_LISTAS)
        assertEquals(Herramientas.DISPONIBLES.size, nuestro.size)
        for (h in nuestro) {
            // Igual también SIN ordenar: el PC escribe las claves en orden alfabético y la
            // gramática de llama.cpp sigue ese orden.
            assertEquals(h.nombre, porNombre.getValue(h.nombre).toString(), h.aJson().toString())
        }
        // Y en el mismo orden relativo que el del PC.
        val ordenPc = delPc.map { it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(nuestro.map { it.nombre }, ordenPc.filter { it in nuestro.map { h -> h.nombre } })
        // El catálogo entero del PC viaja tal cual (59 el 15-oct) y cada una de la tabla sale igual.
        assertEquals(ordenPc, Herramientas.DEL_PC.map { it.nombre })
        for (a in Acciones.TODAS) {
            assertEquals(a.herramienta, ordenado(porNombre.getValue(a.herramienta)).toString(),
                ordenado(Agente.conTopes(listOf(Acciones.herramienta(a)), Agente.TOPE_LISTAS)[0].aJson()).toString())
        }
    }

    @Test fun `lo que solo es de Windows no se ofrece en el telefono`() {
        for (n in listOf("caja_para_soltar", "sincronizar_movil", "enviar_al_movil", Acciones.HOJA_A_LIENZO))
            assertFalse(n, n in Herramientas.catalogo().map { it.nombre })
        assertTrue(Herramientas.DISPONIBLES.all { Herramientas.delPc(it) != null })
    }

    @Test fun `los topes acotan listas y textos como el PC`() {
        val d = Agente.conTopes(Herramientas.catalogo(), 10)[0].parametros.toString()
        assertTrue(d.contains("\"maxItems\":10"))
        assertTrue(d.contains("\"maxItems\":20"))
        assertTrue(d.contains("\"maxLength\":120"))
        // El catálogo de verdad no cambia: la validación usa los límites reales.
        assertTrue(Herramientas.catalogo()[0].parametros.toString().contains("\"maxItems\":60"))
    }

    @Test fun `la llamada medida del modelo 2B pasa`() {
        val v = validar(Herramientas.DIBUJAR, """{"elementos":[{"y": 200, "x": 100, "tipo": "elipse", "alto": 150, "ancho": 150}, {"y": 250, "x": 600, "tipo": "rectangulo", "alto": 100, "ancho": 150}]}""")
        val f = (v.llamada as Llamada.Dibujar).figuras
        assertEquals(2, f.size)
        assertEquals(TipoFigura.ELIPSE, f[0].tipo)
        assertEquals(750.0, f[1].x2, 0.0)
        assertTrue(v.avisos.isEmpty())
    }

    @Test fun `los colores con otro nombre tambien valen`() {
        val f = figuras("""{"elementos":[
            {"tipo":"elipse","x":1,"y":1,"fill":"red"},
            {"tipo":"rectangulo","x":1,"y":1,"stroke":"azul","fondo":"amarillo"},
            {"tipo":"rombo","x":1,"y":1,"relleno":"blanco"}]}""")
        assertEquals("#e03131", f[0].color); assertEquals("#e03131", f[0].relleno)
        assertEquals("#1971c2", f[1].color); assertEquals("#f08c00", f[1].relleno)
        // Un relleno blanco no deja el borde blanco (no se vería).
        assertEquals("#1e1e1e", f[2].color)
    }

    @Test fun `los colores olvidados salen del pedido`() {
        var f = figuras("""{"elementos":[{"tipo":"elipse","x":100,"y":150,"ancho":100,"alto":100},
            {"tipo":"rectangulo","x":600,"y":150,"ancho":100,"alto":100},
            {"tipo":"texto","x":300,"y":20,"texto":"CASA"},
            {"tipo":"rectangulo","x":1,"y":1,"color":"verde"}]}""")
        f = Herramientas.coloresDelPedido(f, "Dibuja un circulo rojo a la izquierda y un cuadrado azul a la derecha, con el titulo CASA arriba.")
        assertEquals("#e03131", f[0].color); assertEquals("#e03131", f[0].relleno)
        assertEquals("#1971c2", f[1].color)
        assertEquals("#1e1e1e", f[2].color)
        assertEquals("#2f9e44", f[3].color)
        assertEquals(f, Herramientas.coloresDelPedido(f, "dibuja una casa"))
    }

    @Test fun `un texto sin texto lo toma de las comillas del pedido`() {
        var f = figuras("""{"elementos":[{"y":150,"x":300,"tipo":"texto","color":"#ffffff"},{"tipo":"texto"}]}""")
        f = Herramientas.coloresDelPedido(f, "Escribe el titulo «Plan 2026» y debajo \"version 2\"")
        assertEquals("Plan 2026", f[0].texto); assertEquals("version 2", f[1].texto)
        assertEquals(60.0 to 40.0, f[1].x to f[1].y)
        val sin = Herramientas.coloresDelPedido(figuras("""{"elementos":[{"tipo":"texto","x":1,"y":1}]}"""), "pon un titulo")
        assertTrue(sin[0].texto.isEmpty())
        assertTrue(Lienzo.elementos(Llamada.Dibujar(sin), 0.0 to 0.0, 1).isEmpty())
        assertEquals(listOf("b", "d", "e"), Herramientas.citasDelPedido("a «b» c 'd' “e”"))
        assertTrue(Herramientas.citasDelPedido("sin «cerrar").isEmpty())
    }

    @Test fun `una forma sin sitio va al centro y los puntos de menos se arreglan`() {
        val f = figuras("""{"elementos":[{"tipo":"triangulo","color":"verde"},{"tipo":"estrella","puntos":[100,50],"x":200,"y":300},{"tipo":"curva","puntos":[0,50],"x":160,"y":300},{"tipo":"onda"}]}""")
        assertEquals(listOf(250.0 to 450.0, 400.0 to 150.0, 550.0 to 450.0), f[0].puntos)
        assertEquals(10, f[1].puntos.size)
        assertEquals(listOf(160.0 to 300.0, 0.0 to 50.0), f[2].puntos)
        assertEquals(17, f[3].puntos.size)
        error(Herramientas.DIBUJAR, """{"elementos":[{"tipo":"poligono","puntos":[1,2]}]}""")
    }

    @Test fun `herramienta desconocida dice las que hay`() {
        val e = error("borrar_disco", "{}")
        assertTrue(e is ErrorHerramienta.Desconocida)
        val m = e.paraElModelo(Herramientas.catalogo())
        assertEquals("ERROR: la herramienta «borrar_disco» no existe. Las que hay son: ${Herramientas.catalogo().joinToString(", ") { it.nombre }}. Usa una de ellas o contesta con texto.", m)
    }

    @Test fun `json roto sin arreglo se rechaza`() {
        assertTrue(error(Herramientas.GRAFICA, """{"tipo": "barras", "valores": [1, 2""") is ErrorHerramienta.JsonRoto)
        assertTrue(error(Herramientas.GRAFICA, "no es json") is ErrorHerramienta.JsonRoto)
        assertEquals("ERROR: los argumentos no son JSON valido. Vuelve a llamar con un objeto JSON correcto.",
            ErrorHerramienta.JsonRoto.paraElModelo(Herramientas.catalogo()))
    }

    @Test fun `json con vallas y comas colgantes se arregla`() {
        val v = validar(Herramientas.GRAFICA, "```json\n{\"tipo\": \"barras\", \"etiquetas\": [\"a\", \"b\",], \"valores\": [1, 2,],}\n```")
        assertEquals(listOf(1.0, 2.0), (v.llamada as Llamada.Grafica).grafica.valores)
        // Una coma dentro de una cadena no se toca.
        assertEquals("a, }", Herramientas.repararJson("""{"t": "a, }", }""")!!.jsonObject["t"]!!.jsonPrimitive.content)
    }

    @Test fun `coordenadas fuera de rango se rechazan con el texto del PC`() {
        assertEquals("elemento 1: «x» = 90000 esta fuera de rango (-200 a 2000)",
            error(Herramientas.DIBUJAR, """{"elementos":[{"tipo":"rectangulo","x":90000,"y":10}]}""").message)
        assertTrue(error(Herramientas.DIBUJAR, """{"elementos":[{"tipo":"rectangulo","x":1,"y":1,"ancho":-5}]}""").message!!.contains("«ancho»"))
        assertTrue(error(Herramientas.DIBUJAR, """{"elementos":[{"tipo":"texto","x":1,"y":1,"texto":"a","tam_letra":900}]}""").message!!.contains("tam_letra"))
        assertEquals("ERROR: elemento 1: «x» = 90000 esta fuera de rango (-200 a 2000). Corrige los argumentos y vuelve a llamar.",
            error(Herramientas.DIBUJAR, """{"elementos":[{"tipo":"rectangulo","x":90000,"y":10}]}""").paraElModelo(emptyList()))
    }

    @Test fun `demasiadas figuras o ninguna se rechazan`() {
        val una = """{"tipo":"elipse","x":1,"y":1}"""
        val muchas = List(Herramientas.MAX_ELEMENTOS + 1) { una }.joinToString(",")
        assertEquals("demasiadas figuras (61); el maximo es 60", error(Herramientas.DIBUJAR, """{"elementos":[$muchas]}""").message)
        error(Herramientas.DIBUJAR, """{"elementos":[]}""")
        error(Herramientas.DIBUJAR, "{}")
    }

    @Test fun `tipo inventado se rechaza`() {
        assertTrue(error(Herramientas.DIBUJAR, """{"elementos":[{"tipo":"dragon","x":1,"y":1}]}""").message!!.contains("dragon"))
    }

    @Test fun `los colores raros no rompen la llamada`() {
        val v = validar(Herramientas.DIBUJAR, """{"elementos":[{"tipo":"rectangulo","x":"10","y":"20px","color":"azul","relleno":"#F00"},{"tipo":"elipse","x":1,"y":1,"color":"color-de-ilusion"}]}""")
        val f = (v.llamada as Llamada.Dibujar).figuras
        assertEquals(10.0, f[0].x, 0.0); assertEquals(20.0, f[0].y, 0.0)
        assertEquals("#1971c2", f[0].color); assertEquals("#ff0000", f[0].relleno)
        assertEquals("#1e1e1e", f[1].color)
        assertEquals(1, v.avisos.size)
    }

    @Test fun `una linea acepta x2 y2 o ancho alto`() {
        val f = figuras("""{"elementos":[{"tipo":"linea","x":10,"y":10,"x2":110,"y2":60},{"tipo":"flecha","x":0,"y":100,"ancho":-50,"alto":0}]}""")
        assertEquals(100.0 to 50.0, f[0].ancho to f[0].alto)
        assertEquals(-50.0 to 100.0, f[1].x2 to f[1].y2)
        error(Herramientas.DIBUJAR, """{"elementos":[{"tipo":"linea","x":5,"y":5,"x2":5,"y2":5}]}""")
    }

    @Test fun `la grafica medida pasa y la descuadrada no`() {
        val g = (validar(Herramientas.GRAFICA, """{"tipo":"barras","etiquetas":["Enero", "Febrero", "Marzo"],"valores":[120, 95, 140]}""").llamada as Llamada.Grafica).grafica
        assertEquals(TipoGrafica.BARRAS, g.tipo)
        assertEquals("hay 1 etiquetas y 2 valores; tiene que haber el mismo numero",
            error(Herramientas.GRAFICA, """{"tipo":"barras","etiquetas":["a"],"valores":[1,2]}""").message)
        error(Herramientas.GRAFICA, """{"tipo":"pastel","etiquetas":["a","b"],"valores":[-1,2]}""")
        error(Herramientas.GRAFICA, """{"tipo":"radar","etiquetas":["a"],"valores":[1]}""")
        error(Herramientas.GRAFICA, """{"tipo":"barras","etiquetas":["a"],"valores":["mucho"]}""")
        val sin = validar(Herramientas.GRAFICA, """{"tipo":"lineas","valores":[1,2]}""")
        assertEquals(listOf("1", "2"), (sin.llamada as Llamada.Grafica).grafica.etiquetas)
        assertEquals(listOf("sin «etiquetas»: se numeran"), sin.avisos)
    }

    @Test fun `ocr lee paginas e idioma, y las imposibles no`() {
        val v = validar(Herramientas.OCR, """{"archivo":"tesis.pdf","paginas":"1-3, 5","idioma":"español"}""")
        assertEquals(Llamada.Ocr(PeticionOcr("tesis.pdf", listOf(1 to 3, 5 to 5), "es")), v.llamada)
        validar(Herramientas.OCR, "")
        for (mal in listOf("""{"paginas":"0-2"}""", """{"paginas":"5-2"}""", """{"paginas":"uno"}""", """{"paginas":"1-99999"}""", """{"idioma":"klingon"}"""))
            error(Herramientas.OCR, mal)
        assertTrue(Herramientas.leerPaginas("todas").isEmpty())
    }

    @Test fun `los argumentos que no son objeto se rechazan`() {
        val e = error(Herramientas.GRAFICA, "[1,2,3]")
        assertTrue(e is ErrorHerramienta.Parametro || e is ErrorHerramienta.JsonRoto)
    }

    @Test fun `figuras ricas con coordenadas compactas`() {
        val f = figuras("""{"origen":[100,50],"elementos":[
            {"tipo":"rectangulo","caja":[0,0,200,100],"texto":"Inicio","grupo":"a"},
            {"tipo":"poligono","puntos":[0,0,50,0,25,40],"relleno":"rojo"},
            {"tipo":"curva","puntos":[[0,0],[10,20],[20,0],[30,20]],"color":"azul"},
            {"tipo":"triangulo","caja":[0,0,100,80]},
            {"tipo":"estrella","centro":[50,50],"radio":40},
            {"tipo":"elipse","centro":[50,50],"radio":20},
            {"tipo":"flecha","puntos":[0,0,100,0],"texto":"si"}]}""")
        assertEquals(listOf(100.0, 50.0, 200.0, 100.0), listOf(f[0].x, f[0].y, f[0].ancho, f[0].alto))
        assertEquals("Inicio", f[0].texto); assertEquals("a", f[0].grupo)
        assertEquals(TipoFigura.POLIGONO, f[1].tipo)
        assertEquals(listOf(100.0 to 50.0, 150.0 to 50.0, 125.0 to 90.0), f[1].puntos)
        assertEquals("#e03131", f[1].color)
        assertEquals(4, f[2].puntos.size)
        assertEquals(listOf(100.0 to 130.0, 150.0 to 50.0, 200.0 to 130.0), f[3].puntos)
        assertEquals(10, f[4].puntos.size)
        assertEquals(130.0 to 40.0, f[5].x to f[5].ancho)
        assertEquals(200.0 to 50.0, f[6].x2 to f[6].y2)
    }

    @Test fun `puntos mal puestos se rechazan`() {
        for ((args, motivo) in listOf(
            """{"elementos":[{"tipo":"poligono","puntos":[1,2,3]}]}""" to "impar",
            """{"elementos":[{"tipo":"poligono","puntos":[1,2,3,4]}]}""" to "al menos 3",
            """{"elementos":[{"tipo":"poligono"}]}""" to "falta «puntos»",
            """{"elementos":[{"tipo":"poligono","puntos":[1,2,3,4,99999,1]}]}""" to "fuera de rango",
            """{"elementos":[{"tipo":"rectangulo","caja":[1,2,3]}]}""" to "«caja»",
            """{"elementos":[{"tipo":"rectangulo","caja":[1,2,-3,4]}]}""" to "fuera de rango",
            """{"origen":"aqui","elementos":[{"tipo":"rectangulo","caja":[1,2,3,4]}]}""" to "«origen»"
        )) {
            val e = error(Herramientas.DIBUJAR, args).message!!
            assertTrue("$args: $e", e.contains(motivo))
        }
        val muchos = (0 until Herramientas.MAX_NUMEROS_PUNTOS + 2).joinToString(",") { (it % 500).toString() }
        assertTrue(error(Herramientas.DIBUJAR, """{"elementos":[{"tipo":"curva","puntos":[$muchos]}]}""").message!!.contains("demasiados"))
    }

    @Test fun `colores en femenino y plural`() {
        assertEquals(Herramientas.color("rojo"), Herramientas.color("roja"))
        assertEquals(Herramientas.color("amarillo"), Herramientas.color("amarillas"))
        assertEquals(Herramientas.color("azul"), Herramientas.color("azules"))
        assertEquals("#c2255c", Herramientas.color("rosa"))
        assertNull(Herramientas.color("casa"))
        assertEquals("#aabbcc", Herramientas.color("#ABC"))
        assertNull(Herramientas.color("#12"))
        val f = Herramientas.coloresDelPedido(figuras("""{"elementos":[{"tipo":"estrella","caja":[1,1,50,50]}]}"""), "Dibuja una estrella de cinco puntas amarilla")
        assertEquals(Herramientas.color("amarillo"), f[0].relleno)
    }

    @Test fun `lapiz grafito instrumento y estilos`() {
        val f = figuras("""{"elementos":[
            {"tipo":"lapiz","puntos":[10,10,20,30,40,35],"material":"grafito","dureza":"6B","grosor":3},
            {"tipo":"grafito","puntos":[0,0,5,5],"dureza":"3h"},
            {"tipo":"instrumento","ejes":2,"caja":[100,100,320,320],"unidad":40,"tabla":[[1,2],[-3,1.5]]},
            {"tipo":"recta","caja":[0,0,300,60]},
            {"tipo":"punto","x":10,"y":20,"texto":"A"},
            {"tipo":"rectangulo","caja":[0,0,10,10],"trazo":"discontinuo","opacidad":0.5,"relleno_estilo":"rayado","capa":-2}]}""")
        assertEquals(TipoFigura.LAPIZ, f[0].tipo); assertEquals(3, f[0].puntos.size)
        assertEquals("cuadritos" to "6B", f[0].estilo.material to f[0].estilo.dureza)
        assertEquals(3.0, f[0].estilo.grosor!!, 0.0)
        assertEquals("cuadritos" to "4H", f[1].estilo.material to f[1].estilo.dureza)
        val i = f[2].instrumento!!
        assertEquals(Triple(2, 40.0, 2), Triple(i.ejes, i.unidad, i.tabla.size))
        assertEquals(1, f[3].instrumento!!.ejes)
        assertEquals("A", f[4].texto)
        assertEquals("dashed", f[5].estilo.trazo); assertEquals(50.0, f[5].estilo.opacidad!!, 0.0)
        assertEquals("hachure", f[5].estilo.relleno); assertEquals(-2, f[5].capa)
        assertEquals("8B", Herramientas.dureza("8b")); assertEquals("HB", Herramientas.dureza("HB")); assertNull(Herramientas.dureza("12Z"))
        for ((args, motivo) in listOf(
            """{"elementos":[{"tipo":"instrumento","tabla":[1,2,3]}]}""" to "impar",
            """{"elementos":[{"tipo":"instrumento","tabla":[[1,1e9]]}]}""" to "enorme",
            """{"elementos":[{"tipo":"instrumento","ejes":7}]}""" to "«ejes»",
            """{"elementos":[{"tipo":"lapiz","puntos":[1,1]}]}""" to "al menos 2",
            """{"elementos":[{"tipo":"rectangulo","caja":[0,0,9,9],"grosor":500}]}""" to "«grosor»"
        )) {
            val e = error(Herramientas.DIBUJAR, args).message!!
            assertTrue("$args: $e", e.contains(motivo))
        }
        val muchos = (0..Herramientas.MAX_TABLA).joinToString(",") { "[$it,1]" }
        error(Herramientas.DIBUJAR, """{"elementos":[{"tipo":"instrumento","tabla":[$muchos]}]}""")
        val v = validar(Herramientas.DIBUJAR, """{"elementos":[{"tipo":"lapiz","puntos":[0,0,9,9],"material":"oro","dureza":"ZZ"}]}""")
        assertEquals(2, v.avisos.size)
    }
}
