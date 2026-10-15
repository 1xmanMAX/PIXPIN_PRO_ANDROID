package com.forge.pixpin.ia

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **El camino rápido** (pruebas de `rapido.rs` del PC, las mismas frases y lo mismo esperado):
 * las órdenes simples salen sin modelo y lo dudoso, lo doble y lo que borra va al modelo.
 */
class RapidoTest {
    private val ahora = Momento(Fechas.diasDe(2026, 10, 14), 630)

    private fun es(t: String, herramienta: String, args: String) {
        val o = Rapido.interpretar(t, ahora)
        assertEquals("«$t»", herramienta to Json.parseToJsonElement(args), o?.herramienta to o?.args)
    }

    @Test fun `las ordenes simples salen sin modelo`() {
        es("Apunta la tarea: llamar al ingeniero mañana.", "anadir_tarea", """{"texto": "llamar al ingeniero", "fecha": "2026-10-15 09:00"}""")
        es("crea una tarea comprar pan", "anadir_tarea", """{"texto": "comprar pan"}""")
        es("Añade la tarea revisar planos para el viernes a las 10", "anadir_tarea", """{"texto": "revisar planos", "fecha": "2026-10-16 10:00"}""")
        es("añade comprar clavos a la lista Compras", "anadir_tarea", """{"texto": "comprar clavos", "lista": "Compras"}""")
        es("Añade revisar grietas a la lista Compras del chat Casa Lima.", "anadir_tarea", """{"texto": "revisar grietas", "lista": "Compras", "chat": "Casa Lima"}""")
        es("recuérdame llamar a Juan mañana a las 5 de la tarde", "anadir_tarea", """{"texto": "llamar a Juan", "fecha": "2026-10-15 17:00"}""")
        es("por favor, apunta comprar pan y leche", "anadir_tarea", """{"texto": "comprar pan y leche"}""")
        es("Crea una lista de tareas que se llame Obra.", "lista_nueva", """{"titulo": "Obra"}""")
        es("Crea una nota nueva que diga: comprar cemento y arena.", "nota_nueva", """{"texto": "comprar cemento y arena"}""")
        es("nueva nota", "nota_nueva", """{}""")
        es("Crea un lienzo nuevo llamado Croquis.", "lienzo_nuevo", """{"nombre": "Croquis"}""")
        es("nuevo lienzo", "lienzo_nuevo", """{}""")
        es("Abre la ventana de tareas.", "abrir_ventana", """{"cual": "tareas"}""")
        es("abre la galería", "abrir_ventana", """{"cual": "galeria"}""")
        es("muéstrame mis lecciones", "abrir_ventana", """{"cual": "lecciones"}""")
        es("Haz una captura de una zona de la pantalla.", "capturar_pantalla", """{}""")
        es("captura", "capturar_pantalla", """{}""")
        es("Saca como pin la ultima captura.", "pinear_ultima_captura", """{}""")
        es("pinea la última", "pinear_ultima_captura", """{}""")
        es("Graba una nota de voz que se llame reunion.", "grabar_voz", """{"nombre": "reunion"}""")
        es("graba", "grabar_voz", """{"nombre": "Nota de voz"}""")
        es("marca como hecha la tarea comprar pan", "marcar_tarea", """{"tarea": "comprar pan", "hecha": true}""")
        es("tacha revisar encofrado", "marcar_tarea", """{"tarea": "revisar encofrado", "hecha": true}""")
        es("marca comprar pan como hecha", "marcar_tarea", """{"tarea": "comprar pan", "hecha": true}""")
        es("desmarca pedir cemento", "marcar_tarea", """{"tarea": "pedir cemento", "hecha": false}""")
        es("¿Qué tengo pendiente?", "consultar_tareas", """{"estado": "pendientes", "periodo": "todo"}""")
        es("¿qué tareas me vencieron esta semana?", "consultar_tareas", """{"estado": "vencidas", "periodo": "semana"}""")
        es("¿qué venció esta semana?", "consultar_tareas", """{"estado": "vencidas", "periodo": "semana"}""")
        es("mis tareas de hoy", "consultar_tareas", """{"estado": "pendientes", "periodo": "hoy"}""")
        es("¿Qué tareas tengo en el chat Casa Lima?", "consultar_tareas", """{"estado": "pendientes", "periodo": "todo", "chat": "casa lima"}""")
        es("¿qué tengo pendiente en la lista Compras del chat Obra?", "consultar_tareas", """{"estado": "pendientes", "periodo": "todo", "lista": "compras", "chat": "obra"}""")
        es("Aplaza la tarea llamar al banco al viernes.", "editar_tarea", """{"tarea": "llamar al banco", "fecha": "2026-10-16 09:00"}""")
        es("pasa la tarea comprar pan a mañana a las 12", "editar_tarea", """{"tarea": "comprar pan", "fecha": "2026-10-15 12:00"}""")
        es("Añade la tarea pagar alquiler para el 31 de febrero", "anadir_tarea", """{"texto": "pagar alquiler", "fecha": "para el 31 de febrero"}""")
    }

    @Test fun `si no esta seguro contesta el modelo`() {
        for (t in listOf(
            "Apunta la tarea comprar yeso y dibuja un círculo rojo",
            "crea un lienzo y luego dibuja una casa",
            "abre la galería. Después captura",
            "Borra la lista de tareas con codigo m-123.",
            "quita la tarea comprar pan",
            "¿Cómo hago una captura de pantalla?",
            "¿Dónde veo mis tareas pendientes?",
            "Hola, ¿qué tal?",
            "Dibuja un circulo rojo",
            "Busca en mis lecciones aprendidas las de encofrado.",
            "Abre el archivo C:\\Users\\yo\\Desktop\\plano.pdf",
            "marca la tarea comprar pan",
            "¿qué tengo pendiente con prisa?",
            "pasa la tarea comprar pan a la lista Compras",
            "graba la pantalla",
            "apunta en el chat que mañana no vengo",
            "crea una lista",
        )) assertNull("«$t» no deberia ir por el camino rapido", Rapido.interpretar(t, ahora))
    }

    @Test fun `el turno rapido hace la orden sin tocar el modelo`() {
        val hechas = mutableListOf<Llamada>()
        val historial = mutableListOf(MensajeIa.texto(Rol.SISTEMA, "s"), MensajeIa.usuario("Crea un lienzo nuevo llamado Croquis."))
        val f = Agente.turno(Agente.SinMotor("sin modelo"), historial, Herramientas.catalogo(), { hechas += it; Result.success("hecho: lienzo") }, 4096,
            Agente.Opciones(rapido = ahora)) { Seguir.SI }
        assertEquals(1, f.herramientasHechas)
        assertEquals(Agente.HECHO, f.texto)
        assertEquals("lienzo_nuevo", hechas.single().nombre)
        assertEquals(5, historial.size)
        // Lo que no es orden va al modelo (que aquí no hay: lo dice).
        val r = runCatching { Agente.turno(Agente.SinMotor("sin modelo"), mutableListOf(MensajeIa.usuario("Dibuja un circulo rojo")), Herramientas.catalogo(), { Result.success("") }, 4096, Agente.Opciones(rapido = ahora)) { Seguir.SI } }
        assertTrue(r.exceptionOrNull()?.message.orEmpty().contains("sin modelo"))
    }
}
