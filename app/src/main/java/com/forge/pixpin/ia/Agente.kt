package com.forge.pixpin.ia

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * **Un turno del asistente** (`agente.rs` del PC): pedir al modelo, validar lo que pide, hacerlo
 * y devolverle el resultado, hasta que conteste con texto.
 *
 * ```text
 * usuario ─▶ modelo ─▶ ¿llamadas? ──no──▶ texto final
 *                         │ sí
 *                         ▼
 *                     validar ──mal──▶ error al modelo (reintento, máx. 2)
 *                         │ bien
 *                         ▼
 *                  Ejecutor (el chat) ──▶ resultado al modelo ──▶ otra vuelta
 * ```
 *
 * El bucle no sabe dibujar ni leer: eso lo hace el [Ejecutor] del chat. Así el mismo bucle sirve
 * para las pruebas (con un motor y un ejecutor de mentira) y para el teléfono. Mismos topes que el
 * PC: [VUELTAS] vueltas, [REINTENTOS] reintentos, listas acotadas
 * ([conTopes]) y respuesta que cabe en el contexto ([topeDeTokens]).
 */
object Agente {
    /** Cuántas veces se deja al modelo corregir una llamada mal hecha en un mismo turno. */
    const val REINTENTOS = 2

    /** Cuántas vueltas modelo→herramienta→modelo como mucho (un modelo pequeño puede quedarse en bucle). */
    const val VUELTAS = 4

    /**
     * **Las vueltas con la nube** (`VUELTAS_NUBE` del PC, 25-oct-2026): con Claude o DeepSeek el
     * tope es de seguridad, no el que decide cuándo acabar; agrandar, numerar y revisar no cabía en
     * 4+2 y acababa en «Hecho en parte». El teléfono solo contesta con la nube: lo pasa el chat.
     */
    const val VUELTAS_NUBE = 12

    /** Cuántas veces puede preguntar al usuario en un turno (`PREGUNTAS_POR_TURNO` del PC). */
    const val PREGUNTAS_POR_TURNO = 2

    const val SIN_PALABRAS = "No sé qué decir sobre eso. ¿Qué quieres que haga?"
    const val HECHO = "Hecho."
    const val HECHO_EN_PARTE = "Hecho en parte: lo demas no me salio con las herramientas."
    const val NO_SUPE = "No he sabido hacerlo con las herramientas; prueba a pedirlo de otra forma o por partes."

    /** Tope de tokens de cada vuelta, y lo mínimo aunque el contexto vaya lleno. */
    const val MAX_TOKENS_CON_HERRAMIENTAS = 3072
    const val MIN_TOKENS = 512

    /** Cuántas figuras puede llevar una llamada, y tras una cortada. Las listas de números, el doble. */
    const val TOPE_LISTAS = 16L
    const val TOPE_LISTAS_REINTENTO = 10L

    /** Lo más largo que puede ser un texto suelto dentro de una llamada. */
    const val TOPE_TEXTO = 120L

    /** Lo más largo que se devuelve al modelo de un resultado de herramienta. */
    const val RESULTADO_MAXIMO = 4000

    /** Lo que pasa durante un turno, para pintarlo en vivo. */
    sealed class Evento {
        data class Texto(val texto: String) : Evento()
        data class Llamando(val nombre: String) : Evento()
        data class Herramienta(val llamada: Llamada, val avisos: List<String>) : Evento()
        data class Resultado(val nombre: String, val ok: Boolean, val texto: String) : Evento()
        data class Reintento(val motivo: String) : Evento()
        /** Un trozo de lo que está pensando (la sección plegable «Pensando…»). */
        data class Pensando(val texto: String) : Evento()
        /** Acabó de pensar, tras tantos segundos («Pensó 12 s»). */
        data class PensoListo(val segundos: Float) : Evento()
        /** La petición mejorada, antes de actuar. */
        data class Peticion(val plan: Pensar.Plan) : Evento()
        /** Se aligeró el contexto al pasar del 70 % (tokens estimados). */
        data class Reinicio(val antes: Int, val despues: Int) : Evento()
        /** La lista de pasos del pedido, como está ahora ([Pasos]). */
        data class Tareas(val pasos: List<Pasos.Paso>) : Evento()
    }

    /** **El punto de enganche del chat**: hace de verdad cada herramienta. */
    fun interface Ejecutor {
        /** `Result.success` con un resumen corto para el modelo; `failure` con el motivo. */
        fun ejecutar(llamada: Llamada): Result<String>

        /**
         * Si hay alguien a quien preguntar ([Preguntar]): el chat sí; una automatización o un
         * ejecutor de pruebas, no. Sin esto el turno no ofrece `preguntar_usuario`.
         */
        fun puedePreguntar(): Boolean = false

        /** **Enseña la pregunta y espera** a que el usuario elija, escriba o se acabe el tiempo. */
        fun preguntar(p: Preguntar.Pregunta): Preguntar.Contestada = Preguntar.Contestada.SinRespuesta
    }

    data class Final(val texto: String, val herramientasHechas: Int, val reintentos: Int, val cortado: Boolean)

    /**
     * **Lo que el turno sabe hacer además de contestar** (`agente::Opciones` del PC): planear antes
     * de actuar (la petición mejorada), pensar en esa vuelta, la hoja de trabajo en el sistema,
     * vigilar el contexto al 70 % y `buscar_historial`. Por omisión, todo apagado.
     */
    data class Opciones(
        val planificar: Boolean = false,
        val pensar: Boolean = false,
        val hoja: HojaTrabajo.Hoja? = null,
        /** Los tokens de la ventana del motor; 0: no se mide. */
        val ventana: Int = 0,
        /** Buscar en la conversación guardada: con esto el modelo tiene `buscar_historial`. */
        val buscar: ((String) -> String)? = null,
        /** El catálogo entero: el plan puede añadir herramientas que la selección no dio. */
        val ampliar: List<com.forge.pixpin.ia.Herramienta>? = null,
        /** El camino rápido ([Rapido]) con esta hora local; null: siempre contesta el modelo. */
        val rapido: Momento? = null,
        /**
         * **La memoria de largo plazo** ([MemoriaLarga], 22-oct en el PC): con ella el sistema lleva
         * el perfil y el índice, y el modelo tiene la herramienta `memoria`.
         */
        val memoria: MemoriaLarga.Memoria? = null,
        /**
         * **Lo que el usuario escribe mientras se trabaja** (25-oct-2026, «como en Claude Code»):
         * se mira antes de cada vuelta y antes de dar la respuesta por acabada; lo que llega entra
         * al historial como un mensaje del usuario. «Para» a secas corta el turno.
         */
        val entrantes: (() -> List<String>)? = null,
        /** El tope de vueltas: [VUELTAS], o [VUELTAS_NUBE] con la nube. */
        val vueltas: Int = VUELTAS,
        /**
         * **No acabar anunciando** ([Guardas.anunciaPendiente], 25-oct): una respuesta sin llamadas
         * que solo anuncia trabajo recibe un empujón y el turno sigue. Con la nube.
         */
        val empujar: Boolean = false,
        /** **La lista de pasos del pedido** ([Pasos], el `TodoWrite` de Claude Code). Con la nube. */
        val pasos: Boolean = false
    )

    /**
     * **El camino rápido** (`turno_rapido` del PC): si la orden es simple la hace el ejecutor y
     * devuelve el final del turno; null si no era simple o no salió (entonces contesta el modelo,
     * sin que se haya dicho nada). La conversación queda como si el modelo la hubiera hecho.
     */
    private fun turnoRapido(historial: MutableList<MensajeIa>, ejecutor: Ejecutor, ahora: Momento, alEvento: (Evento) -> Seguir): Final? {
        val ultimo = historial.lastOrNull()?.takeIf { it.rol == Rol.USUARIO && !it.tieneImagen } ?: return null
        val orden = Rapido.interpretar(ultimo.textoEntero, ahora) ?: return null
        val cruda = LlamadaCruda("rapido-1", orden.herramienta, orden.args.toString())
        val v = runCatching { Herramientas.validar(cruda, Herramientas.catalogo()) }.getOrNull() ?: return null
        // Lo que pregunta antes nunca va por aquí (el intérprete no entiende «borra»), por si acaso.
        if ((v.llamada as? Llamada.App)?.accion?.confirmar != null) return null
        val r = ejecutor.ejecutar(v.llamada)
        val texto = r.getOrElse { e ->
            val motivo = e.message.orEmpty()
            // Se entendió pero no casa con lo que hay: el motivo ya es la respuesta.
            if (!motivo.contains(Tareas.NO_HICE_NADA)) return null
            alEvento(Evento.Texto(motivo))
            historial += MensajeIa.texto(Rol.ASISTENTE, motivo)
            return Final(motivo, 0, 0, false)
        }
        alEvento(Evento.Herramienta(v.llamada, emptyList()))
        alEvento(Evento.Resultado(v.llamada.nombre, true, texto))
        val respuesta = if (texto.isBlank() || texto.startsWith("hecho")) HECHO else texto
        alEvento(Evento.Texto(respuesta))
        historial += MensajeIa(Rol.ASISTENTE, listOf(Parte.Texto("")), llamadas = listOf(cruda))
        historial += MensajeIa.resultado(cruda.id, respuesta)
        historial += MensajeIa.texto(Rol.ASISTENTE, respuesta)
        return Final(respuesta, 1, 0, false)
    }

    /** Un motor que no hay: el camino rápido funciona sin modelo; lo demás dice por qué no. */
    class SinMotor(private val motivo: String) : Motor {
        override fun completar(peticion: Peticion, alTrozo: (Trozo) -> Seguir): Respuesta = throw ErrorIa.Nube(motivo)
    }

    /** Tope de la vuelta de planear: sin pensar, cinco líneas; pensando, algo más. */
    const val TOKENS_PLAN = 320
    const val TOKENS_PLAN_PENSANDO = 640

    /**
     * **Un turno.** [historial] trae el mensaje nuevo del usuario al final; al acabar trae también
     * lo que dijo el asistente (con sus llamadas y resultados), listo para la vuelta siguiente.
     * [contexto]: los tokens de la ventana del motor.
     */
    fun turno(
        motor: Motor,
        historial: MutableList<MensajeIa>,
        herramientas: List<com.forge.pixpin.ia.Herramienta>,
        ejecutor: Ejecutor,
        contexto: Int = Nube.VENTANA,
        opciones: Opciones = Opciones(),
        alEvento: (Evento) -> Seguir
    ): Final {
        val crono = Pensar.Cronometro()
        try {
            return turnoDentro(motor, historial, herramientas, ejecutor, contexto, opciones, crono, alEvento)
        } finally {
            crono.otraCosa()?.let { alEvento(Evento.PensoListo(it)) }
        }
    }

    /** Pasa un trozo del motor al turno: separa el pensamiento, lo cronometra y lo hace eventos. */
    private fun repartir(t: Trozo, sep: Pensar.Separador, crono: Pensar.Cronometro, plan: StringBuilder?, alEvento: (Evento) -> Seguir): Seguir {
        var seguir = Seguir.SI
        for (x in sep.meter(t)) {
            val s = when (x) {
                is Trozo.Pensando -> {
                    crono.pensando(x.texto)
                    if (crono.penso()) alEvento(Evento.Pensando(x.texto)) else Seguir.SI
                }
                else -> {
                    crono.otraCosa()?.let { alEvento(Evento.PensoListo(it)) }
                    when (x) {
                        is Trozo.Texto -> if (plan != null) { plan.append(x.texto); Seguir.SI } else alEvento(Evento.Texto(x.texto))
                        is Trozo.Llamando -> alEvento(Evento.Llamando(x.nombre))
                        else -> Seguir.SI
                    }
                }
            }
            if (s == Seguir.PARAR) seguir = Seguir.PARAR
        }
        return seguir
    }

    /** **La vuelta de planear.** null: el usuario paró. */
    private fun planear(
        motor: Motor, historial: List<MensajeIa>, herramientas: List<com.forge.pixpin.ia.Herramienta>,
        opciones: Opciones, crono: Pensar.Cronometro, alEvento: (Evento) -> Seguir
    ): Pensar.Plan? {
        val u = historial.indexOfLast { it.rol == Rol.USUARIO }
        val pedido = if (u >= 0) historial[u].textoEntero else ""
        val texto = StringBuilder()
        opciones.hoja?.takeIf { !it.estaVacia }?.let { texto.append(it.aTexto().trimEnd()).append("\n\n") }
        val antes = historial.subList(0, maxOf(u, 0)).filter { (it.rol == Rol.USUARIO || it.rol == Rol.ASISTENTE) && it.textoEntero.isNotBlank() }
        if (antes.isNotEmpty()) {
            texto.append("## Conversacion reciente\n")
            for (m in antes.takeLast(2)) texto.append("${if (m.rol == Rol.USUARIO) "Usuario" else "Asistente"}: ${Pensar.cortar(m.textoEntero, 400)}\n")
            texto.append('\n')
        }
        texto.append("## Mensaje nuevo\n").append(pedido)
        if (u >= 0 && historial[u].tieneImagen) texto.append("\n(adjunta una foto)")
        val p = Peticion(
            listOf(MensajeIa.sistema(Pensar.instruccionesDelPlan(herramientas)), MensajeIa.usuario(texto.toString())),
            maxTokens = if (opciones.pensar) TOKENS_PLAN_PENSANDO else TOKENS_PLAN, temperatura = 0.6, pensar = opciones.pensar
        )
        val sep = Pensar.Separador()
        val planTexto = StringBuilder()
        val r = try {
            motor.completar(p) { t -> repartir(t, sep, crono, planTexto, alEvento) }
        } catch (e: ErrorIa) {
            // Sin plan no se deja de contestar: se sigue con el pedido tal cual.
            val plan = Pensar.Plan.porOmision(pedido)
            alEvento(Evento.Peticion(plan))
            return plan
        } finally {
            for (t in sep.acabar()) repartir(t, Pensar.Separador(), crono, planTexto, alEvento)
        }
        if (!crono.penso() && r.pensamiento.isNotBlank()) { crono.pensando(r.pensamiento); alEvento(Evento.Pensando(r.pensamiento)) }
        if (r.fin == Fin.CORTADO) return null
        var plano = planTexto.toString().ifBlank { r.texto }
        if (plano.contains("</think>")) {
            val (pensado, resto) = Pensar.separar(plano)
            if (!crono.penso() && pensado.isNotBlank()) { crono.pensando(pensado); alEvento(Evento.Pensando(pensado)) }
            plano = resto
        }
        crono.otraCosa()?.let { alEvento(Evento.PensoListo(it)) }
        var plan = Pensar.Plan.leer(plano, herramientas)
        // Pensó hasta agotar su tope sin escribir el plan: una vuelta corta SIN pensar, con lo pensado.
        if (plan == null && crono.penso()) {
            val p2 = p.copy(pensar = false, maxTokens = TOKENS_PLAN, mensajes = p.mensajes.dropLast(1) + MensajeIa.usuario(
                "${p.mensajes.last().textoEntero}\n\n## Lo que ya pensaste\n${Pensar.resumir(crono.texto.toString(), 900)}\n\nAhora escribe SOLO las cinco lineas."
            ))
            val otro = StringBuilder()
            runCatching { motor.completar(p2) { t -> if (t is Trozo.Texto) otro.append(t.texto); Seguir.SI } }.getOrNull()?.let { r2 ->
                if (otro.isBlank()) otro.append(r2.texto)
                plan = Pensar.Plan.leer(Pensar.separar(otro.toString()).second, herramientas)
            }
        }
        val final = plan ?: Pensar.Plan.porOmision(pedido)
        if (alEvento(Evento.Peticion(final)) == Seguir.PARAR) return null
        return final
    }

    /** Si pasa del 70 %, poda los resultados viejos y, si no basta, reinicia. Avisa si cambió. */
    private fun vigilarContexto(historial: MutableList<MensajeIa>, herramientas: List<com.forge.pixpin.ia.Herramienta>, ventana: Int, alEvento: (Evento) -> Seguir) {
        val antes = Contexto.estimar(historial, herramientas)
        if (!Contexto.pasaDelUmbral(antes, Contexto.RESERVA_RESPUESTA, ventana)) return
        Contexto.podarEnTurno(historial)
        if (Contexto.pasaDelUmbral(Contexto.estimar(historial, herramientas), Contexto.RESERVA_RESPUESTA, ventana)) {
            val nuevo = Contexto.reiniciar(historial)
            historial.clear(); historial.addAll(nuevo)
        }
        val despues = Contexto.estimar(historial, herramientas)
        if (despues < antes) alEvento(Evento.Reinicio(antes, despues))
    }

    private fun turnoDentro(
        motor: Motor,
        historial: MutableList<MensajeIa>,
        base: List<com.forge.pixpin.ia.Herramienta>,
        ejecutor: Ejecutor,
        contexto: Int,
        opciones: Opciones,
        crono: Pensar.Cronometro,
        alEvento: (Evento) -> Seguir
    ): Final {
        // Antes que nada (ni plan ni modelo): la orden simple, al instante.
        opciones.rapido?.let { ahora -> turnoRapido(historial, ejecutor, ahora, alEvento)?.let { return it } }
        val herramientas = base.toMutableList()
        if (opciones.buscar != null && herramientas.none { it.nombre == Herramientas.BUSCAR_HISTORIAL }) herramientas += Contexto.herramientaBuscar()
        if (opciones.memoria != null && herramientas.none { it.nombre == MemoriaLarga.NOMBRE }) herramientas += MemoriaLarga.herramienta()
        // Preguntar, con alguien que conteste. La última de la lista: lo de antes sigue siendo el
        // mismo prefijo de la caché.
        herramientas.removeAll { it.nombre == Preguntar.NOMBRE }
        // La lista de pasos (25-oct): con la nube, que es quien la sigue y no acaba con pasos abiertos.
        herramientas.removeAll { it.nombre == Pasos.NOMBRE }
        if (opciones.pasos) herramientas += Pasos.herramienta()
        if (ejecutor.puedePreguntar()) herramientas += Preguntar.herramienta()
        var plan: Pensar.Plan? = null
        if (opciones.planificar) {
            val posibles = herramientas.toMutableList()
            opciones.ampliar.orEmpty().forEach { h -> if (posibles.none { it.nombre == h.nombre }) posibles += h }
            plan = planear(motor, historial, posibles, opciones, crono, alEvento) ?: return Final("", 0, 0, true)
            for (n in plan.herramientas) if (herramientas.none { it.nombre == n }) posibles.firstOrNull { it.nombre == n }?.let { herramientas += it }
        }
        if (opciones.hoja != null || plan != null || opciones.memoria != null) {
            // Solo lo fijo: si el sistema ya traía la hoja, no se pone dos veces.
            var instr = Nube.partirSistema(historial.firstOrNull()?.takeIf { it.rol == Rol.SISTEMA }?.textoEntero ?: instrucciones()).first
            opciones.memoria?.let { m -> instr = MemoriaLarga.conMemoria(instr, runCatching { m.bloqueParaElSistema() }.getOrDefault("")) }
            Contexto.ponerSistema(historial, Contexto.sistemaConHoja(instr, opciones.hoja ?: HojaTrabajo.Hoja(), plan))
        }
        var hechas = 0
        var reintentos = 0
        var cortado = false
        // Con la gramática de llama.cpp el modelo NO PUEDE pasarse de estas listas; bajan si una
        // llamada salió cortada.
        var tope = TOPE_LISTAS
        // `buscar_historial` vale UNA vuelta por turno (medido en el PC: si no, el 2B la llama seis veces).
        var yaBusco = false
        val preguntaAqui = herramientas.any { it.nombre == Preguntar.NOMBRE }
        var preguntas = 0
        var empujones = 0
        val conPasos = herramientas.any { it.nombre == Pasos.NOMBRE }
        var pasos: List<Pasos.Paso> = emptyList()
        // Preguntar no gasta vuelta, ni lo que escribe el usuario a media tarea, ni un empujón.
        var topeVueltas = opciones.vueltas + REINTENTOS
        var vuelta = 0
        while (vuelta < topeVueltas) {
            vuelta++
            if (recogerEntrantes(historial, opciones) == true) return final(hechas, reintentos, true)
            if (opciones.ventana > 0) vigilarContexto(historial, herramientas, opciones.ventana, alEvento)
            val ofrecidas = conTopes(herramientas.filter { !(yaBusco && it.nombre == Herramientas.BUSCAR_HISTORIAL) }, tope)
            var peticion = Peticion(historial.toList(), ofrecidas)
            peticion = peticion.copy(maxTokens = topeDeTokens(peticion, contexto))
            val sep = Pensar.Separador()
            val respuesta0 = try {
                motor.completar(peticion) { t -> repartir(t, sep, crono, null, alEvento) }
            } catch (e: ErrorIa.Protocolo) {
                // `llama-server` no deja pasar una llamada cuyo JSON no cierra (HTTP 500): es una
                // llamada cortada por el tope; se pide MÁS CORTA y la gramática se lo impone.
                if (!llamadaRota(e.detalle)) throw e
                reintentos++
                if (reintentos > REINTENTOS) return final(hechas, reintentos, cortado)
                tope = TOPE_LISTAS_REINTENTO
                val motivo = pedirMasCorto()
                if (alEvento(Evento.Reintento(motivo)) == Seguir.PARAR) return final(hechas, reintentos, true)
                historial += MensajeIa.usuario(motivo)
                continue
            } finally {
                for (t in sep.acabar()) repartir(t, Pensar.Separador(), crono, null, alEvento)
            }
            val respuesta = if (respuesta0.texto.contains("</think>")) respuesta0.copy(texto = Pensar.separar(respuesta0.texto).second) else respuesta0
            val texto = quitarPensado(respuesta.texto)
            historial += MensajeIa(Rol.ASISTENTE, listOf(Parte.Texto(texto)), respuesta.llamadas)
            if (respuesta.fin == Fin.CORTADO) return final(hechas, reintentos, true)
            if (respuesta.llamadas.isEmpty()) {
                // Escribió algo mientras contestaba: antes de acabar, se lee y se sigue.
                when (recogerEntrantes(historial, opciones)) {
                    true -> return final(hechas, reintentos, true)
                    false -> { topeVueltas++; if (texto.isNotBlank()) alEvento(Evento.Texto("\n\n")); continue }
                    null -> {}
                }
                // **No acabar anunciando**: «ahora voy a…» no cierra el turno; se le devuelve y sigue.
                // Ni con pasos de su lista sin cerrar ([Pasos]).
                val aviso = if (!opciones.empujar || herramientas.isEmpty() || empujones >= Guardas.EMPUJONES) null
                else Pasos.empujon(pasos) ?: Guardas.EMPUJON_ANUNCIO.takeIf { Guardas.anunciaPendiente(texto) }
                if (aviso != null) {
                    empujones++
                    topeVueltas++
                    historial += MensajeIa.usuario(aviso)
                    if (texto.isNotBlank()) alEvento(Evento.Texto("\n\n"))
                    continue
                }
                val dicho = when {
                    texto.isNotBlank() -> texto
                    hechas > 0 -> HECHO
                    else -> SIN_PALABRAS
                }
                return Final(dicho, hechas, reintentos, false)
            }
            var algunaMal = false
            var buscoBien = false
            // Lo que pidió el usuario (sin los avisos del turno ni lo escrito a media tarea), para la
            // guarda; y sin el resumen del lienzo, para los colores que el modelo olvide.
            val pedidoEntero = pedidoDelUsuario(historial)
            val pedido = Guardas.sinAnexos(pedidoEntero)
            // Apuntar los pasos no gasta vuelta: si solo hizo eso, la vuelta se devuelve.
            var soloPasos = true
            for (cruda in respuesta.llamadas) {
                // `pasos_del_pedido`: la lista la lleva el propio turno y se ve en la burbuja.
                if (conPasos && cruda.nombre.trim() == Pasos.NOMBRE) {
                    val t = Pasos.leer(cruda.argumentos).fold({ p ->
                        pasos = p
                        alEvento(Evento.Tareas(p))
                        Pasos.resultado(p)
                    }, { "ERROR: ${it.message}" })
                    val ok = !t.startsWith("ERROR")
                    algunaMal = algunaMal || !ok
                    alEvento(Evento.Resultado(Pasos.NOMBRE, ok, Pensar.cortar(t, 300)))
                    historial += MensajeIa.resultado(cruda.id, t)
                    continue
                }
                soloPasos = false
                // La memoria no es del chat: es un almacén de ficheros, el mismo para todos los modelos.
                val memoria = opciones.memoria
                if (memoria != null && cruda.nombre.trim() == MemoriaLarga.NOMBRE) {
                    val t = runCatching { memoria.ejecutar(cruda.argumentos) }.getOrElse { "ERROR: ${it.message ?: "no se pudo"}" }
                    val ok = !t.startsWith("ERROR")
                    algunaMal = algunaMal || !ok
                    if (ok) hechas++
                    alEvento(Evento.Resultado(MemoriaLarga.NOMBRE, ok, Pensar.cortar(t, 300)))
                    historial += MensajeIa.resultado(cruda.id, recortarResultado(t))
                    continue
                }
                // `preguntar_usuario`: el turno se para aquí hasta que conteste (lo hace el ejecutor
                // en la burbuja); lo dicho es el resultado.
                if (preguntaAqui && cruda.nombre.trim() == Preguntar.NOMBRE) {
                    val t = if (preguntas >= PREGUNTAS_POR_TURNO) "Ya preguntaste en este turno: sigue con lo que sabes y di en una línea lo que asumiste."
                    else Preguntar.leer(cruda.argumentos).fold({ lista ->
                        preguntas++
                        topeVueltas++
                        alEvento(Evento.Llamando(Preguntar.NOMBRE))
                        lista.joinToString("\n") { p -> Preguntar.paraElModelo(p, ejecutor.preguntar(p)) }
                    }, { e ->
                        algunaMal = true
                        val motivo = "ERROR: ${e.message}"
                        if (alEvento(Evento.Reintento(motivo)) == Seguir.PARAR) cortado = true
                        motivo
                    })
                    // Parar mientras esperaba la respuesta: no se sigue.
                    if (alEvento(Evento.Resultado(Preguntar.NOMBRE, !t.startsWith("ERROR"), Pensar.cortar(t, 300))) == Seguir.PARAR) cortado = true
                    historial += MensajeIa.resultado(cruda.id, t)
                    continue
                }
                // `buscar_historial` no es del chat: lo contesta el propio turno.
                val buscar = opciones.buscar
                if (buscar != null && cruda.nombre.trim() == Herramientas.BUSCAR_HISTORIAL) {
                    val consulta = Contexto.consultaDe(cruda.argumentos)
                    val t = when {
                        yaBusco -> "Ya buscaste en este turno: contesta ahora con lo que encontraste."
                        consulta != null -> buscar(consulta)
                        else -> "ERROR: falta «consulta». Llama a buscar_historial con {\"consulta\": \"que buscar\"}."
                    }
                    val ok = !t.startsWith("ERROR")
                    buscoBien = buscoBien || ok
                    algunaMal = algunaMal || !ok
                    alEvento(Evento.Resultado(Herramientas.BUSCAR_HISTORIAL, ok, Pensar.cortar(t, 300)))
                    historial += MensajeIa.resultado(cruda.id, recortarResultado(t))
                    continue
                }
                val validada = try {
                    Herramientas.validar(sanear(cruda), herramientas)
                } catch (e: ErrorHerramienta) {
                    algunaMal = true
                    // La siguiente, corta: una llamada larga que sale mal es la que más tarda en repetirse.
                    tope = TOPE_LISTAS_REINTENTO
                    val motivo = if (e is ErrorHerramienta.JsonRoto || respuesta.fin == Fin.LONGITUD) pedirMasCorto()
                    else e.paraElModelo(herramientas)
                    if (alEvento(Evento.Reintento(motivo)) == Seguir.PARAR) cortado = true
                    historial += MensajeIa.resultado(cruda.id, motivo)
                    continue
                }
                // **La guarda anti-duplicado** (25-oct): cambiar lo que hay no es dibujar otro al lado.
                val duplicado = Guardas.duplicaria(pedidoEntero, validada.llamada)
                if (duplicado != null) {
                    algunaMal = true
                    if (alEvento(Evento.Reintento(duplicado)) == Seguir.PARAR) cortado = true
                    historial += MensajeIa.resultado(cruda.id, duplicado)
                    continue
                }
                // **La guarda de la web** (28-oct-2026, `web::guarda` del PC): una dirección armada
                // por el modelo que no salió en la conversación no se abre (una página no puede
                // hacerle mandar datos del usuario dentro de una URL).
                val armada = com.forge.pixpin.asistente.web.Web.guarda(historial, validada.llamada)
                if (armada != null) {
                    algunaMal = true
                    if (alEvento(Evento.Reintento(armada)) == Seguir.PARAR) cortado = true
                    historial += MensajeIa.resultado(cruda.id, armada)
                    continue
                }
                val llamada = when (val l = validada.llamada) {
                    is Llamada.Dibujar -> l.copy(figuras = Herramientas.coloresDelPedido(l.figuras, pedido))
                    is Llamada.Calcar -> l.copy(parametros = Calcar.ajustarAlPedido(l.parametros, pedido))
                    else -> l
                }
                alEvento(Evento.Herramienta(llamada, validada.avisos))
                val r = runCatching { ejecutor.ejecutar(llamada) }.getOrElse { Result.failure(it) }
                val ok = r.isSuccess
                val dicho = r.getOrElse { "ERROR: ${it.message ?: "no se pudo"}" }
                if (ok) hechas++
                alEvento(Evento.Resultado(llamada.nombre, ok, dicho))
                // Una parte de página web cabe entera (con sus enlaces).
                var paraElModelo = if (com.forge.pixpin.asistente.web.Web.esDeLaWeb(llamada.nombre)) recortarResultado(dicho, com.forge.pixpin.asistente.web.Web.LETRAS_RESULTADO)
                else recortarResultado(dicho)
                if (validada.avisos.isNotEmpty()) paraElModelo += " (avisos: ${validada.avisos.joinToString("; ")})"
                historial += MensajeIa.resultado(cruda.id, paraElModelo)
            }
            yaBusco = yaBusco || buscoBien
            if (soloPasos && respuesta.llamadas.isNotEmpty()) topeVueltas++
            if (cortado) return final(hechas, reintentos, true)
            if (algunaMal) {
                reintentos++
                if (reintentos > REINTENTOS) return final(hechas, reintentos, false)
            }
        }
        return final(hechas, reintentos, cortado)
    }

    /**
     * **Mira si el usuario escribió algo** mientras se trabajaba: true si pidió parar, false si lo
     * escrito ya está en el historial, null si no escribió nada.
     */
    private fun recogerEntrantes(historial: MutableList<MensajeIa>, opciones: Opciones): Boolean? {
        val nuevos = opciones.entrantes?.invoke().orEmpty().filter { it.isNotBlank() }
        if (nuevos.isEmpty()) return null
        if (nuevos.size == 1 && Guardas.esParar(nuevos[0])) return true
        historial += Guardas.mensajeEntrante(nuevos)
        return false
    }

    /**
     * **El último pedido del usuario** (`pedido_del_usuario` del PC): el último mensaje suyo que no
     * es un aviso del turno, lo que escribió a media tarea ni un «hazlo más corto».
     */
    internal fun pedidoDelUsuario(historial: List<MensajeIa>): String =
        historial.lastOrNull { m ->
            m.rol == Rol.USUARIO && m.textoEntero.let { t -> !t.startsWith(Guardas.MARCA_DEL_TURNO) && !t.startsWith("[Mensaje nuevo del usuario") && !t.startsWith("ERROR: tu llamada") }
        }?.textoEntero.orEmpty()

    /** Se acabaron las vueltas o los reintentos (o se cortó): se dice claro en vez de una burbuja vacía. */
    private fun final(hechas: Int, reintentos: Int, cortado: Boolean): Final {
        val texto = when {
            cortado -> ""
            hechas > 0 -> HECHO_EN_PARTE
            else -> NO_SUPE
        }
        return Final(texto, hechas, reintentos, cortado)
    }

    /** Si el modelo escribe su `<think>…</think>` aunque no se le pida pensar: fuera. */
    internal fun quitarPensado(t: String): String {
        val cierre = t.lastIndexOf("</think>")
        return if (cierre >= 0) t.substring(cierre + "</think>".length).trimStart() else t
    }

    fun recortarResultado(texto: String, maximo: Int = RESULTADO_MAXIMO): String {
        if (texto.codePointCount(0, texto.length) <= maximo) return texto
        val cps = texto.codePoints().limit(maximo.toLong()).toArray()
        return String(cps, 0, cps.size) + "\n[...recortado: el usuario tiene el resultado entero]"
    }

    /**
     * **Cuánta respuesta se pide**: [MAX_TOKENS_CON_HERRAMIENTAS], o lo que quepa en el contexto con
     * lo que ya lleva la conversación (por lo alto: 3 letras por token y ~1000 por foto).
     */
    fun topeDeTokens(p: Peticion, contexto: Int): Int {
        val letras = p.mensajes.sumOf { m -> m.textoEntero.length + m.llamadas.sumOf { it.argumentos.length } } +
            p.herramientas.sumOf { it.aJson().toString().length }
        val fotos = p.mensajes.count { it.tieneImagen }
        val entrada = letras / 3 + 1000 * fotos + 200
        return (contexto - entrada).coerceAtLeast(0).coerceIn(MIN_TOKENS, MAX_TOKENS_CON_HERRAMIENTAS)
    }

    /**
     * **Las herramientas con sus listas y textos acotados** (`con_topes` del PC). `llama-server`
     * convierte el esquema en una gramática: con `maxItems` una llamada no puede crecer hasta
     * cortarse a medias. Solo se recorta lo de la petición; la validación usa los límites de verdad.
     */
    fun conTopes(herramientas: List<Herramienta>, tope: Long): List<Herramienta> =
        herramientas.map { it.copy(parametros = ordenado(acotar(it.parametros, tope)) as JsonObject) }

    private fun acotar(v: JsonElement, tope: Long): JsonElement {
        if (v is JsonArray) return JsonArray(v.map { acotar(it, tope) })
        if (v !is JsonObject) return v
        val o = LinkedHashMap<String, JsonElement>()
        for ((k, x) in v) o[k] = if (x is JsonObject || x is JsonArray) acotar(x, tope) else x
        when ((v["type"] as? JsonPrimitive)?.content) {
            "array" -> {
                val deObjetos = ((v["items"] as? JsonObject)?.get("type") as? JsonPrimitive)?.content == "object"
                val t = if (deObjetos) tope else 2 * tope
                val max = (v["maxItems"] as? JsonPrimitive)?.content?.toLongOrNull()?.let { minOf(it, t) } ?: t
                o["maxItems"] = JsonPrimitive(max)
            }
            // Un texto sin tope propio es una etiqueta o un título.
            "string" -> if ("enum" !in v && "maxLength" !in v) o["maxLength"] = JsonPrimitive(TOPE_TEXTO)
        }
        return JsonObject(o)
    }

    /**
     * **Lleva al borde las coordenadas que se salen un poco**, antes de validar (`sanear` del PC):
     * el 2B escribe a veces `x: 2010` en la figura 12 de 16 y se perdía la llamada entera.
     */
    fun sanear(cruda: LlamadaCruda): LlamadaCruda {
        val v = runCatching { Json.parseToJsonElement(cruda.argumentos) }.getOrNull() ?: return cruda
        var cambios = 0
        fun llevar(e: JsonElement): JsonElement = when (e) {
            is JsonObject -> JsonObject(e.mapValues { (k, x) ->
                val rango = when (k) {
                    "x", "y", "x2", "y2" -> Herramientas.COORDENADA_MINIMA to Herramientas.COORDENADA_MAXIMA
                    "ancho", "alto" -> -Herramientas.COORDENADA_MAXIMA to Herramientas.COORDENADA_MAXIMA
                    else -> null
                }
                val n = (x as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
                if (rango != null && n != null && n.isFinite() && (n < rango.first || n > rango.second)) {
                    cambios++
                    JsonPrimitive(n.coerceIn(rango.first, rango.second))
                } else llevar(x)
            })
            is JsonArray -> JsonArray(e.map { llevar(it) })
            else -> e
        }
        val nuevo = llevar(v)
        return if (cambios == 0) cruda else cruda.copy(argumentos = nuevo.toString())
    }

    private fun pedirMasCorto() =
        "ERROR: tu llamada a la herramienta salio cortada porque era demasiado larga. " +
            "Vuelve a llamarla con $TOPE_LISTAS_REINTENTO elementos como mucho, solo las formas principales; " +
            "si hace falta mas, haz varias llamadas seguidas."

    /** Si el error del motor es una llamada con el JSON roto (lo que contesta `llama-server`). */
    internal fun llamadaRota(m: String): Boolean {
        val t = m.lowercase()
        return "tool call" in t && ("json" in t || "parse" in t)
    }

    /**
     * **Cómo trabaja** (`agente::instrucciones` del PC, 22/25-oct-2026, la de la nube): principios,
     * no una lista de «si pide X usa Y». El usuario del PC: «no le digas lo que puede y no puede
     * hacer con reglas: enséñale». El entorno, el método (entender, mirar, planear, actuar,
     * comprobar, contar), el permiso de combinar herramientas con lo que el modelo ya sabe hacer
     * (traducir, redactar, razonar), la calidad, cuándo preguntar y ejemplos. Con tildes: el
     * modelo escribe como lee. Las frases que nombran herramientas salen solo si están en
     * [disponibles] (el teléfono no tiene `transformar_elementos`, `reorganizar_hoja` ni
     * `pasos_del_pedido`): hablarle de una que no tiene es invitarle a llamarla.
     */
    private fun comoTrabaja(disponibles: Set<String>): String {
        fun hay(n: String) = n in disponibles
        val s = StringBuilder()
        s.append("Eres el asistente de PixPin y trabajas dentro de la app del usuario, en su teléfono. ")
        s.append("Tus herramientas leen y cambian de verdad sus lienzos (figuras, textos), notas, tareas, chats y documentos, ")
        s.append("y cada resultado te dice qué pasó: los #id de lo creado o tocado, el estado, o un ERROR con su motivo. ")
        s.append("Cuando llega el «Lienzo destino», trae lo que ya hay con sus #id, textos y cajas.\n\n")
        s.append("Traducir, redactar, resumir, corregir textos, razonar y planear lo haces tú; las herramientas solo leen y escriben en la app. ")
        s.append("Combínalas: casi todo pedido sale de leer lo que hay, pensarlo tú y escribir el resultado con la herramienta que edita ese sitio. ")
        s.append("No digas «no tengo herramienta para eso» si una combinación lo consigue; si de verdad algo no se puede, haz la parte que sí y di qué falta.\n\n")
        s.append("Cómo trabajas:\n")
        s.append("1. Entiende el objetivo y qué haría falta para darlo por bueno.\n")
        s.append("2. Mira lo que ya hay (el lienzo, los adjuntos, la conversación) antes de crear nada.\n")
        s.append("3. Planea los pasos; los que no dependen entre sí, pídelos en la misma respuesta. ")
        s.append("Si el pedido tiene 3 o más pasos y tienes pasos_del_pedido, escríbelos ahí al empezar (solo lo pedido) y márcalos según los acabes.\n")
        s.append("4. Actúa con las herramientas, usando los #id que te devuelven.\n")
        s.append("5. Comprueba con los resultados (y la revisión automática, si la hay) que está todo y bien; corrige lo que falle.\n")
        s.append("6. Termina con una o dos frases de lo que hiciste, sin dar por hecho nada que ninguna herramienta confirmó. ")
        s.append("Contestar sin herramientas ACABA el turno: no anuncies lo que vas a hacer («ahora voy a…»), hazlo; y si algo quedó a medias, ")
        s.append("termínalo antes de contestar o di exactamente qué falta y por qué.\n\n")
        s.append("Calidad: completo (todo lo pedido), ordenado y alineado, sin solapes ni textos que se salgan, coherente con lo que ya había, ")
        s.append("y con la ortografía y las tildes correctas del idioma que toque. Contesta en el idioma del usuario, breve y claro.\n\n")
        s.append("Si falta un dato, búscalo primero en lo que ya tienes. Pregunta solo si las lecturas posibles dan resultados distintos y equivocarse cuesta ")
        s.append("(un trabajo largo, sobrescribir lo que hay): una sola cosa, concreta. Si es fácil de deshacer, elige lo más razonable y dilo en una línea ")
        s.append("(«Asumí X; dime si preferías Y»). Si el usuario dice «tú decide», decide.\n\n")
        s.append("Lo que ya existe se edita, no se rehace: si el pedido es sobre algo que ya está en el lienzo, cámbialo con sus propias piezas")
        if (hay(EditarTexto.NOMBRE)) s.append(" (sus textos, con editar_texto)")
        s.append("; nunca dibujes otra versión al lado ni la borres para redibujarla. Lo nuevo que se le añade va dentro, alineado y con su mismo estilo.\n")
        s.append("No inventes contenido que el usuario no dio (actividades, nombres, datos, fechas). ")
        s.append("Y nunca quites lo que ya tiene (filas, secciones, lo escrito) para que algo quepa sin preguntárselo.\n")
        s.append("Si el usuario escribe mientras trabajas, llega como «[Mensaje nuevo del usuario mientras trabajabas]»: léelo y ajusta lo que haces.\n\n")
        s.append("Ejemplos razonados (aprende el porqué, no las frases):\n")
        s.append("- «Esto se ve raro, arréglalo»: nombra 2 o 3 fallos concretos (desalineado, se pisan, un texto se sale), corrígelos y di qué cambiaste.\n")
        s.append("- «Rellénalo con las actividades de mi tesis» sin decir cuáles: pregunta cuáles son, o propón una lista para que la confirme.\n")
        if (hay(EditarTexto.NOMBRE)) s.append("- «Traduce esta figura al inglés»: lee sus textos en el lienzo, tradúcelos tú, cámbialos todos de una vez con editar_texto y comprueba que no quede ninguno sin traducir ni que se salga de su forma.\n")
        s.append("- «Saca las tareas de este PDF»: lee el documento, decide tú cuáles son tareas y apúntalas juntas en una lista con su fecha si la tienen.\n")
        if (hay(Herramientas.DIBUJAR)) s.append("- «Haz un diagrama del proceso de compra»: decide los pasos, dibújalos con la forma que corresponde a cada uno, alineados y unidos con flechas; lee la revisión automática y separa lo que se pise.\n")
        s.append("\nDónde va cada cosa: ")
        return s.toString()
    }

    /**
     * **Las instrucciones de sistema** (`agente::instrucciones` del PC + el resumen de la app):
     * cómo trabaja ([comoTrabaja]) y, debajo, la guía corta de qué herramienta lee o escribe cada
     * sitio, con solo las frases de las herramientas que hay en [disponibles].
     */
    fun instrucciones(disponibles: Set<String> = Herramientas.DISPONIBLES): String {
        fun hay(vararg n: String) = n.any { it in disponibles }
        val s = StringBuilder(comoTrabaja(disponibles))
        if (hay(EditarTexto.NOMBRE)) s.append("Para cambiar lo que dicen los textos que ya hay (traducir, corregir, renombrar), editar_texto con sus #id. ")
        if (hay(Herramientas.DIBUJAR)) s.append("Si pide dibujar algo, usa dibujar_en_lienzo, poniendo el color de cada figura. ")
        if (hay(Herramientas.CALCAR)) s.append("Si pide dibujar, copiar o calcar al lienzo lo de una imagen adjunta (este perro, la foto), usa calcar_imagen. ")
        val dibujos = listOfNotNull(
            "si da datos para graficar, usa grafica".takeIf { hay(Herramientas.GRAFICA) },
            "si da una funcion, graficar_funcion".takeIf { hay(Herramientas.FUNCION) },
            "una ecuacion, escribir_ecuacion".takeIf { hay(Herramientas.ECUACION) },
            "un cronograma, cronograma".takeIf { hay(Herramientas.CRONOGRAMA) },
            "una ilustracion con curvas, dibujar_svg".takeIf { hay(Herramientas.SVG) }
        )
        if (dibujos.isNotEmpty()) s.append(dibujos.joinToString("; ").replaceFirstChar { it.uppercase() }).append(". ")
        if (hay(Herramientas.FIGURAS)) s.append("Para guardar o poner figuras de su biblioteca, figuras_guardadas. ")
        if (hay(Herramientas.OCR)) s.append("Si quiere el texto de un documento escaneado o de una foto, usa ocr. ")
        if (Acciones.ACCIONES.any { it.herramienta in disponibles }) s.append("Si pide hacer algo en la app (notas, tareas, lienzos, ventanas, capturas, lecciones, abrir ficheros), usa su herramienta. ")
        val docs = listOfNotNull(
            "una tabla de una foto o un PDF, tabla_de_imagen".takeIf { hay(Acciones.TABLA) },
            "resaltar, anotar o encerrar algo, anotar_documento".takeIf { hay(Acciones.ANOTAR) },
            "«¿donde dice...?», buscar_en_documento".takeIf { hay(Acciones.BUSCAR_EN) },
            "abrir uno por su nombre, abrir_por_nombre".takeIf { hay(Acciones.ABRIR) },
            "otro formato, exportar_documento".takeIf { hay(Acciones.EXPORTAR) }
        )
        if (docs.isNotEmpty()) s.append("Con sus documentos y planos: ").append(docs.joinToString("; ")).append(". ")
        if (hay(Herramientas.BUSCAR)) s.append("Para lo que dicen sus documentos, notas y chats, buscar_en_mis_cosas, y cita las fuentes con [n]. ")
        s.append("Nunca pidas rutas ni codigos: nombra el archivo con las palabras del usuario; si te dicen que hay varios, pregunta cual con esa lista corta. ")
        if (hay(Herramientas.AYUDA)) s.append("Si pregunta donde esta algo o como se hace en PixPin, usa ayuda_app y contesta con lo que diga, sin inventar. ")
        s.append("Despues de usar una herramienta, di en una frase lo que hiciste. ")
        return s.toString() + Conocimiento.RESUMEN
    }
}
