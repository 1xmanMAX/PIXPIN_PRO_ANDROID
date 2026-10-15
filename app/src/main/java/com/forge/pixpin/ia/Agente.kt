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
 * PC: [VUELTAS] vueltas, [REINTENTOS] reintentos, listas acotadas por la gramática de llama.cpp
 * ([conTopes]) y respuesta que cabe en el contexto ([topeDeTokens]).
 */
object Agente {
    /** Cuántas veces se deja al modelo corregir una llamada mal hecha en un mismo turno. */
    const val REINTENTOS = 2

    /** Cuántas vueltas modelo→herramienta→modelo como mucho (un modelo pequeño puede quedarse en bucle). */
    const val VUELTAS = 4

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
    }

    /** **El punto de enganche del chat**: hace de verdad cada herramienta. */
    fun interface Ejecutor {
        /** `Result.success` con un resumen corto para el modelo; `failure` con el motivo. */
        fun ejecutar(llamada: Llamada): Result<String>
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
        val ampliar: List<com.forge.pixpin.ia.Herramienta>? = null
    )

    /** Tope de la vuelta de planear: sin pensar, cinco líneas; pensando, algo más. */
    const val TOKENS_PLAN = 320
    const val TOKENS_PLAN_PENSANDO = 640

    /**
     * **Un turno.** [historial] trae el mensaje nuevo del usuario al final; al acabar trae también
     * lo que dijo el asistente (con sus llamadas y resultados), listo para la vuelta siguiente.
     * [contexto]: los tokens con los que se arrancó el modelo.
     */
    fun turno(
        motor: Motor,
        historial: MutableList<MensajeIa>,
        herramientas: List<com.forge.pixpin.ia.Herramienta>,
        ejecutor: Ejecutor,
        contexto: Int = ServidorLlama.CONTEXTO,
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
        val herramientas = base.toMutableList()
        if (opciones.buscar != null && herramientas.none { it.nombre == Herramientas.BUSCAR_HISTORIAL }) herramientas += Contexto.herramientaBuscar()
        var plan: Pensar.Plan? = null
        if (opciones.planificar) {
            val posibles = herramientas.toMutableList()
            opciones.ampliar.orEmpty().forEach { h -> if (posibles.none { it.nombre == h.nombre }) posibles += h }
            plan = planear(motor, historial, posibles, opciones, crono, alEvento) ?: return Final("", 0, 0, true)
            for (n in plan.herramientas) if (herramientas.none { it.nombre == n }) posibles.firstOrNull { it.nombre == n }?.let { herramientas += it }
        }
        if (opciones.hoja != null || plan != null) {
            val instr = historial.firstOrNull()?.takeIf { it.rol == Rol.SISTEMA }?.textoEntero ?: instrucciones()
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
        repeat(VUELTAS + REINTENTOS) {
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
                return@repeat
            } finally {
                for (t in sep.acabar()) repartir(t, Pensar.Separador(), crono, null, alEvento)
            }
            val respuesta = if (respuesta0.texto.contains("</think>")) respuesta0.copy(texto = Pensar.separar(respuesta0.texto).second) else respuesta0
            val texto = quitarPensado(respuesta.texto)
            historial += MensajeIa(Rol.ASISTENTE, listOf(Parte.Texto(texto)), respuesta.llamadas)
            if (respuesta.fin == Fin.CORTADO) return final(hechas, reintentos, true)
            if (respuesta.llamadas.isEmpty()) {
                val dicho = when {
                    texto.isNotBlank() -> texto
                    hechas > 0 -> HECHO
                    else -> SIN_PALABRAS
                }
                return Final(dicho, hechas, reintentos, false)
            }
            var algunaMal = false
            var buscoBien = false
            // Lo que pidió el usuario, para los colores que el modelo olvide.
            val pedido = historial.lastOrNull { it.rol == Rol.USUARIO }?.textoEntero.orEmpty()
            for (cruda in respuesta.llamadas) {
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
                var paraElModelo = recortarResultado(dicho)
                if (validada.avisos.isNotEmpty()) paraElModelo += " (avisos: ${validada.avisos.joinToString("; ")})"
                historial += MensajeIa.resultado(cruda.id, paraElModelo)
            }
            yaBusco = yaBusco || buscoBien
            if (cortado) return final(hechas, reintentos, true)
            if (algunaMal) {
                reintentos++
                if (reintentos > REINTENTOS) return final(hechas, reintentos, false)
            }
        }
        return final(hechas, reintentos, cortado)
    }

    /** Se acabaron las vueltas o los reintentos (o se cortó): se dice claro en vez de una burbuja vacía. */
    private fun final(hechas: Int, reintentos: Int, cortado: Boolean): Final {
        val texto = when {
            cortado -> ""
            hechas > 0 -> HECHO_EN_PARTE
            else -> NO_SUPE
        }
        return Final(texto, hechas, reintentos, cortado)
    }

    /** Qwen a veces escribe su `<think>…</think>` aunque no se le pida pensar: fuera. */
    internal fun quitarPensado(t: String): String {
        val cierre = t.lastIndexOf("</think>")
        return if (cierre >= 0) t.substring(cierre + "</think>".length).trimStart() else t
    }

    fun recortarResultado(texto: String): String {
        if (texto.codePointCount(0, texto.length) <= RESULTADO_MAXIMO) return texto
        val cps = texto.codePoints().limit(RESULTADO_MAXIMO.toLong()).toArray()
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
     * **Las instrucciones de sistema** (`agente::instrucciones` del PC + el resumen de la app),
     * con solo las frases de las herramientas que hay en [disponibles]: un modelo pequeño al que
     * se le habla de una herramienta que no tiene la intenta llamar igual. Cortas: cada token
     * cuenta en cada vuelta.
     */
    fun instrucciones(disponibles: Set<String> = Herramientas.DISPONIBLES): String {
        fun hay(vararg n: String) = n.any { it in disponibles }
        val s = StringBuilder("Eres el asistente de PixPin, una app de capturas, notas y lienzo. Contesta en el idioma del usuario, breve y claro. ")
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
