package com.forge.pixpin.ia

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import kotlin.math.ceil

/**
 * **Ver lo que piensa y la petición mejorada** (`pensar.rs` del PC, portado tal cual).
 *
 * - [Separador]: separa en vivo lo que el modelo escribe entre `<think>` y `</think>`.
 * - [Cronometro]: cuánto pensó («Pensó 12 s»).
 * - [Plan]: la vuelta corta antes de actuar, con cinco líneas `ENTIENDO:` / `HARE:` /
 *   `HERRAMIENTAS:` / `DATOS:` / `DECIDIDO:`; [Plan.leer] es tolerante con mayúsculas, tildes y
 *   Markdown.
 */
object Pensar {
    private const val ABRE = "<think>"
    private const val CIERRA = "</think>"

    class Separador {
        private var dentro = false
        private var cola = ""
        private var huboTexto = false

        fun meter(t: Trozo): List<Trozo> {
            if (t !is Trozo.Texto) return listOf(t)
            var buf = cola + t.texto
            cola = ""
            val salida = ArrayList<Trozo>()
            while (true) {
                if (dentro) {
                    val i = buf.indexOf(CIERRA)
                    if (i >= 0) {
                        empujar(salida, Trozo.Pensando(buf.substring(0, i)))
                        buf = buf.substring(i + CIERRA.length).trimStart()
                        dentro = false
                        continue
                    }
                    val guardar = prefijoAlFinal(buf, CIERRA)
                    val corte = buf.length - guardar
                    empujar(salida, Trozo.Pensando(buf.substring(0, corte)))
                    cola = buf.substring(corte)
                    break
                }
                val abre = buf.indexOf(ABRE).takeIf { it >= 0 }
                val cierra = buf.indexOf(CIERRA).takeIf { it >= 0 }
                if (cierra != null && (abre == null || cierra < abre)) {
                    if (huboTexto) {
                        empujar(salida, Trozo.Texto(buf.substring(0, cierra + CIERRA.length)))
                        buf = buf.substring(cierra + CIERRA.length)
                        continue
                    }
                    empujar(salida, Trozo.Pensando(buf.substring(0, cierra)))
                    buf = buf.substring(cierra + CIERRA.length).trimStart()
                    continue
                }
                if (abre != null) {
                    texto(salida, buf.substring(0, abre))
                    buf = buf.substring(abre + ABRE.length)
                    dentro = true
                    continue
                }
                val guardar = maxOf(prefijoAlFinal(buf, ABRE), prefijoAlFinal(buf, CIERRA))
                val corte = buf.length - guardar
                texto(salida, buf.substring(0, corte))
                cola = buf.substring(corte)
                break
            }
            return salida
        }

        fun acabar(): List<Trozo> {
            val c = cola
            cola = ""
            if (c.isEmpty()) return emptyList()
            return listOf(if (dentro) Trozo.Pensando(c) else Trozo.Texto(c))
        }

        private fun texto(salida: MutableList<Trozo>, s: String) {
            if (s.isNotEmpty()) {
                huboTexto = huboTexto || s.isNotBlank()
                empujar(salida, Trozo.Texto(s))
            }
        }
    }

    private fun empujar(salida: MutableList<Trozo>, t: Trozo) {
        if ((t is Trozo.Texto && t.texto.isEmpty()) || (t is Trozo.Pensando && t.texto.isEmpty())) return
        salida += t
    }

    private fun prefijoAlFinal(s: String, etiqueta: String): Int {
        for (n in minOf(etiqueta.length - 1, s.length) downTo 1) if (s.endsWith(etiqueta.substring(0, n))) return n
        return 0
    }

    /** (pensado, texto) de un texto entero. */
    fun separar(texto: String): Pair<String, String> {
        val s = Separador()
        val trozos = s.meter(Trozo.Texto(texto)) + s.acabar()
        val p = StringBuilder(); val t = StringBuilder()
        for (x in trozos) when (x) { is Trozo.Pensando -> p.append(x.texto); is Trozo.Texto -> t.append(x.texto); else -> {} }
        return p.toString() to t.toString()
    }

    /** Cuánto pensó: empieza con el primer trozo de pensamiento y se para con lo siguiente. */
    class Cronometro(private val reloj: () -> Long = System::currentTimeMillis) {
        private var empezo: Long? = null
        private var acabado = false
        val texto = StringBuilder()

        fun pensando(s: String) {
            if (s.isEmpty() || (texto.isEmpty() && s.isBlank())) return
            if (acabado) { acabado = false; texto.append("\n\n") }
            if (empezo == null) empezo = reloj()
            texto.append(s)
        }

        /** Llegó otra cosa: si estaba pensando, los segundos que lleva. */
        fun otraCosa(): Float? {
            if (acabado || texto.isBlank()) return null
            acabado = true
            return empezo?.let { (reloj() - it) / 1000f }
        }

        fun penso() = texto.isNotBlank()
    }

    /** Primera frase … última frase, sin llamar al modelo. */
    fun resumir(pensamiento: String, max: Int): String {
        val limpio = pensamiento.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
        if (limpio.codePointCount(0, limpio.length) <= max) return limpio
        val frases = Regex("[^.?!]*[.?!]|[^.?!]+$").findAll(limpio).map { it.value.trim() }.filter { it.isNotEmpty() }.toList()
        val primera = frases.firstOrNull().orEmpty()
        val ultima = frases.lastOrNull().orEmpty()
        return cortar(if (frases.size > 1) "$primera … $ultima" else primera, max)
    }

    fun cortar(s: String, max: Int): String {
        if (s.codePointCount(0, s.length) <= max) return s
        val n = maxOf(max - 1, 0)
        return s.substring(0, s.offsetByCodePoints(0, n)).trimEnd() + "…"
    }

    fun sinTildes(s: String): String = buildString {
        for (c in s) append(
            when (c) {
                'á', 'à', 'ä', 'â' -> 'a'; 'é', 'è', 'ë', 'ê' -> 'e'; 'í', 'ì', 'ï', 'î' -> 'i'; 'ó', 'ò', 'ö', 'ô' -> 'o'; 'ú', 'ù', 'ü', 'û' -> 'u'
                'Á', 'À', 'Ä', 'Â' -> 'A'; 'É', 'È', 'Ë', 'Ê' -> 'E'; 'Í', 'Ì', 'Ï', 'Î' -> 'I'; 'Ó', 'Ò', 'Ö', 'Ô' -> 'O'; 'Ú', 'Ù', 'Ü', 'Û' -> 'U'
                else -> c
            }
        )
    }

    data class Plan(
        val entiendo: String = "",
        val hare: String = "",
        val herramientas: List<String> = emptyList(),
        val datos: List<String> = emptyList(),
        val decidido: List<String> = emptyList(),
        val delModelo: Boolean = false
    ) {
        /** «Entiendo: …», «Haré: …», «Con: …», «Datos: …». */
        fun lineas(): String = buildList {
            add("Entiendo: $entiendo")
            if (hare.isNotEmpty()) add("Haré: $hare")
            if (herramientas.isNotEmpty()) add("Con: ${herramientas.joinToString(", ")}")
            if (datos.isNotEmpty()) add("Datos: ${datos.joinToString("; ")}")
        }.joinToString("\n")

        fun aJson(): JsonObject = buildJsonObject {
            put("entiendo", entiendo); put("hare", hare)
            put("herramientas", JsonArray(herramientas.map { JsonPrimitive(it) }))
            put("datos", JsonArray(datos.map { JsonPrimitive(it) }))
            put("decidido", JsonArray(decidido.map { JsonPrimitive(it) }))
            put("del_modelo", delModelo)
        }

        companion object {
            fun porOmision(pedido: String) = Plan(entiendo = cortar(pedido.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" "), 240))

            fun leer(texto: String, conocidas: List<Herramienta>): Plan? {
                var p = Plan(delModelo = true)
                var alguna = false
                for (l0 in texto.lines()) {
                    val linea = l0.trim().trimStart('-', '*', '#', ' ', '•').trim()
                    val i = linea.indexOf(':').takeIf { it >= 0 } ?: continue
                    val clave = sinTildes(linea.substring(0, i).replace("*", "").trim().lowercase())
                    val valor = linea.substring(i + 1).replace("**", "").trim()
                    val vacio = esNada(valor)
                    when (clave) {
                        "entiendo", "entendi", "objetivo", "peticion" -> { alguna = true; if (!vacio) p = p.copy(entiendo = cortar(valor, 300)) }
                        "hare", "pasos", "plan", "hago" -> { alguna = true; if (!vacio) p = p.copy(hare = cortar(valor, 300)) }
                        "herramientas", "herramienta", "con" -> {
                            alguna = true
                            if (!vacio) { val bajo = valor.lowercase(); p = p.copy(herramientas = conocidas.filter { bajo.contains(it.nombre.lowercase()) }.map { it.nombre }) }
                        }
                        "datos", "hechos" -> { alguna = true; if (!vacio) p = p.copy(datos = lista(valor)) }
                        "decidido", "decisiones", "preferencias" -> { alguna = true; if (!vacio) p = p.copy(decidido = lista(valor)) }
                    }
                }
                return p.takeIf { alguna && it.entiendo.isNotEmpty() }
            }

            fun deJson(v: JsonElement?): Plan? {
                val o = v as? JsonObject ?: return null
                fun s(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
                val p = Plan(s("entiendo"), s("hare"), cadenas(o["herramientas"]), cadenas(o["datos"]), cadenas(o["decidido"]),
                    (o["del_modelo"] as? JsonPrimitive)?.booleanOrNull ?: false)
                return p.takeIf { it.entiendo.isNotEmpty() }
            }
        }
    }

    internal fun cadenas(v: JsonElement?): List<String> =
        (v as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.orEmpty()

    private fun lista(valor: String) = valor.split(';', '|').map { it.trim().trimEnd('.').trim() }
        .filter { it.isNotEmpty() && !esNada(it) }.map { cortar(it, 120) }.take(6)

    private fun esNada(v0: String): Boolean {
        val v = sinTildes(v0.trim().trimEnd('.').lowercase())
        return v.isEmpty() || v in setOf("nada", "ninguna", "ninguno", "-", "no", "none", "n/a") ||
            listOf("no se ha", "aun no", "todavia no", "nada ", "ninguna ", "ninguno ", "sin decidir").any { v.startsWith(it) }
    }

    fun instruccionesDelPlan(herramientas: List<Herramienta>): String {
        val nombres = herramientas.map { it.nombre }
        return "Eres el planificador del asistente de PixPin. NO contestes al usuario ni hagas la tarea. " +
            "Lee la hoja de trabajo y el mensaje nuevo, y escribe SOLO estas cinco lineas:\n" +
            "ENTIENDO: que quiere el usuario, en una frase completa; si dice «eso», «otra vez» o «ahora», " +
            "di a que se refiere usando la hoja de trabajo.\n" +
            "HARE: los pasos, en una frase.\n" +
            "HERRAMIENTAS: las que usaras de esta lista, separadas por comas, o ninguna: ${if (nombres.isEmpty()) "ninguna" else nombres.joinToString(", ")}.\n" +
            "DATOS: cifras, nombres o colores concretos que haya que recordar, separados por punto y coma, o nada.\n" +
            "DECIDIDO: lo que el usuario eligio o prefiere, o nada."
    }
}

/**
 * **La hoja de trabajo y el registro de cada respuesta** (`hoja_trabajo.rs` del PC). El registro
 * va en `pins/draw/anot-<uid>.asistente.json` junto a su nota (el formato de §3.2, al byte), viaja
 * con ella y se borra con ella. Al leer se **recorta** (una hoja escrita a mano no puede llenar el
 * contexto), y una `version` desconocida o un fichero roto no se lee a medias.
 */
object HojaTrabajo {
    const val MAX_HECHOS = 8
    const val MAX_HECHO = 6
    const val MAX_FALTA = 4
    const val MAX_DECISIONES = 6
    const val MAX_TURNOS = 4
    const val LARGO_LINEA = 160
    const val LARGO_PENSADO = 220
    const val MAX_PENSAMIENTO = 20_000
    const val VERSION = 1L

    data class TurnoResumido(val n: Int, val peticion: String, val pensado: String, val resultado: String)

    data class Hoja(
        var objetivo: String = "",
        val hechos: MutableList<String> = mutableListOf(),
        val hecho: MutableList<String> = mutableListOf(),
        val falta: MutableList<String> = mutableListOf(),
        val decisiones: MutableList<String> = mutableListOf(),
        val turnos: MutableList<TurnoResumido> = mutableListOf(),
        var reinicioDesde: String? = null,
        var reinicios: Int = 0
    ) {
        val estaVacia get() = objetivo.isEmpty() && hechos.isEmpty() && hecho.isEmpty() && turnos.isEmpty()
        fun siguienteTurno() = (turnos.lastOrNull()?.n ?: 0) + 1

        fun copia() = Hoja(objetivo, hechos.toMutableList(), hecho.toMutableList(), falta.toMutableList(), decisiones.toMutableList(), turnos.toMutableList(), reinicioDesde, reinicios)

        fun reescribir(plan: Pensar.Plan, turno: ResumenDelTurno) {
            val n = siguienteTurno()
            if (plan.entiendo.isNotBlank()) objetivo = Pensar.cortar(plan.entiendo.trim(), 2 * LARGO_LINEA)
            plan.datos.forEach { anadirSinRepetir(hechos, it, MAX_HECHOS) }
            plan.decidido.forEach { anadirSinRepetir(decisiones, it, MAX_DECISIONES) }
            val primera = turno.respuesta.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
            val linea = StringBuilder("T$n:")
            if (turno.herramientas.isNotEmpty()) linea.append(" [${turno.herramientas.joinToString(", ")}]")
            if (primera.isNotEmpty()) linea.append(' ').append(primera)
            if (turno.incompleto) linea.append(" (sin acabar)")
            empujarConTope(hecho, Pensar.cortar(linea.toString(), LARGO_LINEA), MAX_HECHO)
            if (turno.incompleto) {
                val f = plan.hare.ifEmpty { plan.entiendo }
                if (f.isNotBlank()) anadirSinRepetir(falta, f, MAX_FALTA)
            } else falta.clear()
            empujarConTope(turnos, TurnoResumido(n, Pensar.cortar(plan.entiendo, LARGO_LINEA), Pensar.resumir(turno.pensamiento, LARGO_PENSADO), Pensar.cortar(primera, LARGO_LINEA)), MAX_TURNOS)
        }

        fun aTexto(): String {
            val s = StringBuilder("# Hoja de trabajo\n")
            if (objetivo.isNotEmpty()) s.append("Objetivo: $objetivo\n")
            for ((titulo, v) in listOf("Hechos" to hechos, "Hecho" to hecho, "Falta" to falta, "Decisiones" to decisiones)) {
                if (v.isEmpty()) continue
                s.append("$titulo:\n")
                v.forEach { s.append("- $it\n") }
            }
            turnos.lastOrNull { it.pensado.isNotEmpty() }?.let { s.append("Lo ultimo que pense (T${it.n}): ${it.pensado}\n") }
            return s.toString()
        }

        fun aJson(): JsonObject = buildJsonObject {
            put("objetivo", objetivo)
            put("hechos", JsonArray(hechos.map { JsonPrimitive(it) }))
            put("hecho", JsonArray(hecho.map { JsonPrimitive(it) }))
            put("falta", JsonArray(falta.map { JsonPrimitive(it) }))
            put("decisiones", JsonArray(decisiones.map { JsonPrimitive(it) }))
            put("turnos", buildJsonArray {
                turnos.forEach { t -> add(buildJsonObject { put("n", t.n); put("peticion", t.peticion); put("pensado", t.pensado); put("resultado", t.resultado) }) }
            })
            put("reinicio_desde", reinicioDesde?.let { JsonPrimitive(it) } ?: JsonNull)
            put("reinicios", reinicios)
        }

        companion object {
            fun deJson(v: JsonElement?): Hoja {
                val o = v as? JsonObject ?: throw IllegalArgumentException("la hoja no es un objeto")
                fun s(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
                fun recortar(l: List<String>, n: Int) = l.takeLast(n).map { Pensar.cortar(it, LARGO_LINEA) }.toMutableList()
                val turnos = (o["turnos"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map { t ->
                    fun ts(k: String) = (t[k] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
                    TurnoResumido(((t["n"] as? JsonPrimitive)?.longOrNull ?: 0L).toInt(), ts("peticion"), Pensar.cortar(ts("pensado"), LARGO_PENSADO), ts("resultado"))
                }
                return Hoja(
                    Pensar.cortar(s("objetivo"), 2 * LARGO_LINEA),
                    recortar(Pensar.cadenas(o["hechos"]), MAX_HECHOS), recortar(Pensar.cadenas(o["hecho"]), MAX_HECHO),
                    recortar(Pensar.cadenas(o["falta"]), MAX_FALTA), recortar(Pensar.cadenas(o["decisiones"]), MAX_DECISIONES),
                    turnos.takeLast(MAX_TURNOS).toMutableList(),
                    (o["reinicio_desde"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
                    ((o["reinicios"] as? JsonPrimitive)?.longOrNull ?: 0L).toInt()
                )
            }
        }
    }

    data class ResumenDelTurno(val herramientas: List<String>, val respuesta: String, val incompleto: Boolean, val pensamiento: String)

    private fun <T> empujarConTope(v: MutableList<T>, x: T, tope: Int) {
        v += x
        while (v.size > tope) v.removeAt(0)
    }

    private fun anadirSinRepetir(v: MutableList<String>, x0: String, tope: Int) {
        val x = Pensar.cortar(x0.trim(), LARGO_LINEA)
        if (x.isEmpty()) return
        val bajo = x.lowercase()
        v.removeAll { it.lowercase() == bajo }
        empujarConTope(v, x, tope)
    }

    /** **Lo que pensó y la hoja de una respuesta** (`pins/draw/anot-<uid>.asistente.json`). */
    data class Registro(
        val pensamiento: String = "",
        val segundos: Float? = null,
        val plan: Pensar.Plan? = null,
        val hoja: Hoja = Hoja(),
        val reinicio: Boolean = false
    ) {
        fun aJson(): JsonObject = buildJsonObject {
            put("version", VERSION)
            put("pensamiento", if (pensamiento.codePointCount(0, pensamiento.length) > MAX_PENSAMIENTO) Pensar.cortar(pensamiento, MAX_PENSAMIENTO) else pensamiento)
            put("segundos", segundos?.let { JsonPrimitive(it) } ?: JsonNull)
            put("plan", plan?.aJson() ?: JsonNull)
            put("hoja", hoja.aJson())
            put("reinicio", reinicio)
        }

        /** Escribe por un temporal (atómico), en claro y sin gzip, como el PC. */
        fun escribir(f: File) {
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp")
            tmp.writeText(JSON_BONITO.encodeToString(JsonObject.serializer(), aJson()))
            if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) { tmp.delete(); error("no se pudo escribir ${f.name}") } }
        }

        companion object {
            private val JSON_BONITO = Json { prettyPrint = true }

            fun deJson(v: JsonElement): Registro {
                val o = v as? JsonObject ?: throw IllegalArgumentException("el registro no es un objeto")
                val version = (o["version"] as? JsonPrimitive)?.longOrNull ?: 0L
                if (version == 0L || version > VERSION) throw IllegalArgumentException("version desconocida: $version")
                return Registro(
                    (o["pensamiento"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty(),
                    (o["segundos"] as? JsonPrimitive)?.doubleOrNull?.toFloat(),
                    Pensar.Plan.deJson(o["plan"]),
                    o["hoja"]?.takeIf { it !is JsonNull }?.let { Hoja.deJson(it) } ?: Hoja(),
                    (o["reinicio"] as? JsonPrimitive)?.booleanOrNull ?: false
                )
            }

            /** null si no hay; lanza si está roto (quien llama lo ignora y mira la anterior). */
            fun leer(f: File): Registro? {
                if (!f.isFile) return null
                return deJson(Json.parseToJsonElement(f.readText()))
            }
        }
    }
}

/**
 * **El contexto al 70 %** (`contexto.rs` del PC): estimar los tokens, reiniciar con el sistema, la
 * hoja y los dos últimos mensajes, podar los resultados viejos dentro de un turno largo, y
 * `buscar_historial` para lo que ya no está cargado.
 */
object Contexto {
    const val UMBRAL = 0.70f
    private const val LETRAS_POR_TOKEN = 3.2f
    private const val TOKENS_IMAGEN = 450
    private const val TOKENS_POR_MENSAJE = 6
    const val RESERVA_RESPUESTA = 1536
    const val QUEDAN_AL_REINICIAR = 2
    private const val RECORTADO = "(resultado recortado para ahorrar contexto; usa buscar_historial si lo necesitas)"

    fun tokensDeTexto(s: String): Int = ceil(s.codePointCount(0, s.length) / LETRAS_POR_TOKEN).toInt()

    fun estimar(mensajes: List<MensajeIa>, herramientas: List<Herramienta>): Int {
        var n = herramientas.sumOf { tokensDeTexto(it.aJson().toString()) }
        for (m in mensajes) {
            n += TOKENS_POR_MENSAJE
            for (p in m.partes) n += when (p) { is Parte.Texto -> tokensDeTexto(p.texto); is Parte.Imagen -> TOKENS_IMAGEN }
            for (l in m.llamadas) n += tokensDeTexto(l.nombre) + tokensDeTexto(l.argumentos) + 8
        }
        return n
    }

    fun pasaDelUmbral(tokens: Int, reserva: Int, ventana: Int) = ventana > 0 && (tokens + reserva).toFloat() > ventana * UMBRAL

    fun sistemaConHoja(instrucciones: String, hoja: HojaTrabajo.Hoja, plan: Pensar.Plan?): String {
        val s = StringBuilder(instrucciones.trimEnd())
        if (!hoja.estaVacia) {
            s.append("\n\n").append(hoja.aTexto().trimEnd())
            s.append("\nSi lo que te piden ya esta en la hoja de trabajo, contesta directamente con ello, sin herramientas.")
        }
        if (plan != null) s.append("\n\n# Peticion mejorada (lo que entendiste antes de actuar)\n").append(plan.lineas())
        if (hoja.reinicios > 0 || hoja.reinicioDesde != null) s.append("\n\nLa conversacion de antes ya no esta cargada: si necesitas algo de ella, usa buscar_historial.")
        return s.toString()
    }

    fun ponerSistema(historial: MutableList<MensajeIa>, texto: String) {
        if (historial.firstOrNull()?.rol == Rol.SISTEMA) historial[0] = MensajeIa.sistema(texto) else historial.add(0, MensajeIa.sistema(texto))
    }

    /** El sistema, los [QUEDAN_AL_REINICIAR] últimos mensajes de texto y lo de este turno. */
    fun reiniciar(historial: List<MensajeIa>): MutableList<MensajeIa> {
        val salida = ArrayList<MensajeIa>()
        val resto = if (historial.firstOrNull()?.rol == Rol.SISTEMA) { salida += historial[0]; historial.drop(1) } else historial
        val u = resto.indexOfLast { it.rol == Rol.USUARIO }
        if (u < 0) { salida += resto; return salida }
        val antes = resto.subList(0, u).filter { (it.rol == Rol.USUARIO || it.rol == Rol.ASISTENTE) && it.llamadas.isEmpty() && it.textoEntero.isNotBlank() }
        salida += antes.takeLast(QUEDAN_AL_REINICIAR)
        salida += resto.subList(u, resto.size)
        return salida
    }

    data class Preparado(val historial: MutableList<MensajeIa>, val reiniciado: Boolean, val tokens: Int, val primero: Int)

    fun preparar(sistema: String, anteriores: List<Pair<Boolean, String>>, nuevo: MensajeIa, herramientas: List<Herramienta>, ventana: Int, reserva: Int): Preparado {
        fun montar(desde: Int): MutableList<MensajeIa> {
            val h = mutableListOf(MensajeIa.sistema(sistema))
            for ((delUsuario, texto) in anteriores.drop(desde)) {
                if (texto.isBlank()) continue
                h += if (delUsuario) MensajeIa.usuario(texto) else MensajeIa.texto(Rol.ASISTENTE, texto)
            }
            h += nuevo
            return h
        }
        val entero = montar(0)
        val tokens = estimar(entero, herramientas)
        if (!pasaDelUmbral(tokens, reserva, ventana)) return Preparado(entero, false, tokens, 0)
        val conTexto = anteriores.indices.filter { anteriores[it].second.isNotBlank() }
        val primero = conTexto.getOrNull(maxOf(conTexto.size - QUEDAN_AL_REINICIAR, 0)) ?: anteriores.size
        val h = montar(primero)
        return Preparado(h, true, estimar(h, herramientas), primero)
    }

    /** Dentro de un turno largo: los resultados viejos de más de 200 letras se dejan en 160. */
    fun podarEnTurno(historial: MutableList<MensajeIa>): Boolean {
        val ultima = historial.indexOfLast { it.llamadas.isNotEmpty() }.takeIf { it >= 0 } ?: return false
        var cambio = false
        for (i in 0 until ultima) {
            val m = historial[i]
            if (m.rol != Rol.HERRAMIENTA) continue
            val t = m.textoEntero
            if (t.codePointCount(0, t.length) > 200 && !t.contains(RECORTADO)) {
                historial[i] = m.copy(partes = listOf(Parte.Texto("${Pensar.cortar(t, 160)} $RECORTADO")))
                cambio = true
            }
        }
        return cambio
    }

    // ------------------------------------------------------------------------ buscar_historial

    fun herramientaBuscar() = Herramienta(
        Herramientas.BUSCAR_HISTORIAL,
        "Busca en la conversacion guardada algo que ya no tienes delante (un dato, lo que se dijo o se hizo antes). Devuelve los trozos mas parecidos.",
        buildJsonObject {
            put("properties", buildJsonObject { put("consulta", buildJsonObject { put("description", "Que buscar, en pocas palabras"); put("type", "string") }) })
            put("required", buildJsonArray { add(JsonPrimitive("consulta")) })
            put("type", "object")
        }
    )

    data class Fuente(val etiqueta: String, val texto: String)

    fun consultaDe(argumentos: String): String? {
        val v = runCatching { Json.parseToJsonElement(argumentos.trim()) as? JsonObject }.getOrNull() ?: return null
        val c = ((v["consulta"] ?: v["query"]) as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
        return c?.takeIf { it.isNotEmpty() }
    }

    private fun palabras(s: String) = Pensar.sinTildes(s.lowercase()).split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { p -> p.codePointCount(0, p.length) >= 3 || (p.isNotEmpty() && p.all { it in '0'..'9' }) }

    fun buscar(fuentes: List<Fuente>, consulta: String, max: Int): String {
        val buscadas = palabras(consulta)
        if (buscadas.isEmpty()) return "ERROR: la consulta esta vacia."
        fun raiz(p: String) = p.take(5)
        val puntuadas = fuentes.mapIndexedNotNull { i, f ->
            val tiene = palabras(f.texto)
            var puntos = 0f
            for (b in buscadas) {
                if (tiene.any { it == b }) puntos += 1.0f else if (tiene.any { raiz(it) == raiz(b) }) puntos += 0.6f
            }
            if (puntos > 0f) Triple(puntos, i, f) else null
        }
        if (puntuadas.isEmpty()) return "No hay nada sobre «$consulta» en la conversacion guardada."
        return puntuadas.sortedWith(compareByDescending<Triple<Float, Int, Fuente>> { it.first }.thenByDescending { it.second })
            .take(maxOf(max, 1)).joinToString("\n") { (_, _, f) -> "[${f.etiqueta}] ${alrededor(f.texto, buscadas, 300)}" }.trimEnd()
    }

    private fun alrededor(texto: String, buscadas: List<String>, max: Int): String {
        val limpio = texto.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
        if (limpio.codePointCount(0, limpio.length) <= max) return limpio
        val bajo = Pensar.sinTildes(limpio.lowercase())
        val pos = buscadas.mapNotNull { b -> bajo.indexOf(b).takeIf { it >= 0 } }.minOrNull() ?: 0
        val inicio = maxOf(pos - max / 3, 0)
        val fin = minOf(inicio + max, limpio.length)
        val s = StringBuilder(limpio.substring(inicio, fin))
        if (inicio > 0) s.insert(0, '…')
        if (fin < limpio.length) s.append('…')
        return s.toString()
    }
}
