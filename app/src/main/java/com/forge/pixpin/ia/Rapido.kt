package com.forge.pixpin.ia

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * **El camino rápido** (`rapido.rs` del PC, portado tal cual): las órdenes simples —«abre la
 * galería», «apunta comprar pan mañana a las 10», «¿qué tengo pendiente?»— se convierten en su
 * llamada **sin preguntar al modelo** (milisegundos en vez de un minuto del 0.8B en un teléfono).
 *
 * No hace nada si duda: dos órdenes en una, una pregunta que no es de tareas, algo largo… y
 * **nunca lo que borra**. Entonces contesta el modelo, como siempre.
 */
object Rapido {
    data class Orden(val herramienta: String, val args: JsonObject)

    private const val LARGO_MAXIMO = 160

    private val VERBOS = listOf(
        "abre", "dibuja", "crea", "haz", "pon", "apunta", "anade", "agrega", "marca", "graba", "captura", "borra", "quita",
        "mueve", "manda", "envia", "busca", "pinea", "saca", "copia", "calca", "lee", "dime", "explica", "luego", "despues",
        "tambien", "ademas", "ensename", "muestra"
    )

    private fun orden(h: String, vararg args: Pair<String, Any>) = Orden(h, JsonObject(args.associate { (k, v) ->
        k to when (v) { is Boolean -> JsonPrimitive(v); is Number -> JsonPrimitive(v); else -> JsonPrimitive(v.toString()) }
    }))

    private class Frase(val original: String, val q: String) {
        fun tras(prefijos: List<String>): String? {
            var mejor: Int? = null
            for (p in prefijos) if (q.startsWith(p)) {
                val n = p.length
                val cortaPalabra = q.getOrNull(n)?.isLetterOrDigit() == true
                if (!cortaPalabra && (mejor == null || n > mejor)) mejor = n
            }
            return mejor?.let { original.substring(it).trim() }
        }
        fun es(opciones: List<String>) = q in opciones

        companion object {
            fun nueva(texto: String): Frase? {
                val q = buildString { for (c in texto) append(Acciones.normalizar(c.toString()).firstOrNull() ?: c) }
                return if (q.length == texto.length) Frase(texto, q) else null
            }
        }
    }

    private fun limpiar(texto: String): String {
        var s = texto.trim()
        while (true) {
            val antes = s
            var q = Acciones.normalizar(s)
            for (p in listOf("@asistente", "asistente,", "oye,", "oye", "hey", "por favor,", "por favor", "porfa", "porfavor")) {
                if (q.startsWith(p) && q.length > p.length && !q[p.length].isLetterOrDigit()) {
                    s = s.substring(p.length).trimStart(',', ' ', ':')
                    break
                }
            }
            q = Acciones.normalizar(s)
            for (p in listOf(", por favor", " por favor", " porfa", ", gracias", " gracias")) {
                if (q.endsWith(p)) { s = s.substring(0, s.length - p.length); break }
            }
            s = s.trim().trimEnd('.', '!', '¡').trimStart('¡', '¿').trim()
            if (s == antes) return s
        }
    }

    private fun hayOtraOrden(q: String): Boolean {
        if (q.split('.', ';', '\n').filter { it.isNotBlank() }.size > 1) return true
        for (sep in listOf(" y ", ", y ", ", luego ", ", despues ", " luego ", " despues ")) {
            var i = q.indexOf(sep)
            while (i >= 0) {
                val resto = q.substring(i + sep.length).trimStart()
                val palabra = resto.takeWhile { it.isLetterOrDigit() }
                if (VERBOS.any { palabra.startsWith(it) }) return true
                i = q.indexOf(sep, i + 1)
            }
        }
        return false
    }

    private fun sinConector(s0: String, conectores: List<String>): String {
        var s = s0.trim().trimStart(':', '-', ',').trim()
        val q = Acciones.normalizar(s)
        for (c in conectores) if (q.startsWith(c) && (q.length == c.length || !q[c.length].isLetterOrDigit())) { s = s.substring(c.length); break }
        return s.trim().trimStart(':', '-', ',').trim().trim('"', '«', '»', '“', '”').trim()
    }

    private fun listaAlFinal(s: String): Pair<String, String?> {
        val q = Acciones.normalizar(s)
        for (marca in listOf(" en la lista ", " a la lista ", " en mi lista ", " de la lista ", " en la lista de ", " a la lista de ")) {
            val i = q.lastIndexOf(marca)
            if (i >= 0) {
                val antes = s.substring(0, i)
                val lista = s.substring(i + marca.length).trim().trim('"', '«', '»')
                if (lista.isNotEmpty() && antes.isNotBlank()) return antes.trim() to lista
            }
        }
        for (marca in listOf(" en el inbox", " al inbox", " en la bandeja")) if (q.endsWith(marca)) return s.substring(0, s.length - marca.length).trim() to "Inbox"
        return s.trim() to null
    }

    private fun partirChat(lista: String?): Pair<String?, String?> {
        val l = lista ?: return null to null
        val q = Acciones.normalizar(l)
        for (marca in listOf(" del chat ", " en el chat ", " del proyecto ", " en el proyecto ", " de el chat ")) {
            val i = q.indexOf(marca)
            if (i >= 0) {
                val a = l.substring(0, i); val c = l.substring(i + marca.length)
                if (a.isNotBlank() && c.isNotBlank()) return a.trim() to c.trim()
            }
        }
        return l to null
    }

    /** **La orden de una frase**, o null si no es simple o si duda. [ahora]: hora local. */
    fun interpretar(texto: String, ahora: Momento): Orden? {
        val limpio0 = limpiar(texto)
        if (limpio0.isEmpty() || limpio0.codePointCount(0, limpio0.length) > LARGO_MAXIMO) return null
        val pregunta = limpio0.endsWith('?') || texto.trimStart().startsWith('¿')
        val limpio = limpio0.trimEnd('?').trim()
        val f = Frase.nueva(limpio) ?: return null
        val q = f.q
        if (hayOtraOrden(q)) return null
        consulta(q)?.let { return it }
        if (pregunta) return null

        for ((ventana, nombres) in listOf(
            "galeria" to listOf("galeria", "galeria de capturas", "las capturas", "mis capturas"),
            "tareas" to listOf("tareas", "ventana de tareas", "mis tareas", "lista de tareas"),
            "lecciones" to listOf("lecciones", "mis lecciones", "lecciones aprendidas"),
            "sincronizar" to listOf("sincronizar", "sincronizacion", "ventana de sincronizar")
        )) for (v in listOf("abre", "abrir", "muestra", "muestrame", "ensename", "ve a", "ir a", "abreme"))
            for (art in listOf("", "la ", "las ", "mis ", "la ventana de ", "la ventana de la ", "la ventana de las "))
                for (n0 in nombres) {
                    val n = if (art == "mis " && n0.startsWith("mis ")) n0.removePrefix("mis ") else n0
                    if (q == "$v $art$n") return orden("abrir_ventana", "cual" to ventana)
                }

        if (f.es(listOf(
                "captura", "capturar", "haz una captura", "haz una captura de pantalla", "captura la pantalla", "captura de pantalla",
                "haz una captura de una zona", "haz una captura de una zona de la pantalla", "pantallazo", "haz un pantallazo",
                "toma una captura", "saca una captura", "captura una zona", "recorta la pantalla"
            ))) return orden("capturar_pantalla")
        if (f.es(listOf(
                "pinea la ultima", "pinea la ultima captura", "saca la ultima captura", "saca como pin la ultima captura",
                "saca la ultima captura como pin", "pon la ultima captura como pin", "fija la ultima captura", "pin de la ultima captura",
                "pinear la ultima captura", "muestra la ultima captura como pin"
            ))) return orden("pinear_ultima_captura")

        f.tras(listOf(
            "crea un lienzo nuevo", "crea un lienzo", "crear un lienzo", "nuevo lienzo", "lienzo nuevo", "abre un lienzo nuevo",
            "haz un lienzo nuevo", "un lienzo nuevo"
        ))?.let { resto ->
            val nombre = sinConector(resto, listOf("llamado", "que se llame", "con el nombre", "de nombre", "nuevo llamado"))
            return if (nombre.isNotEmpty()) orden("lienzo_nuevo", "nombre" to nombre) else orden("lienzo_nuevo")
        }
        f.tras(listOf("crea una nota nueva", "crea una nota", "nueva nota", "nota nueva", "escribe una nota", "haz una nota", "anota en una nota"))?.let { resto ->
            val t = sinConector(resto, listOf("que diga", "que ponga", "con el texto", "con", "diciendo", "sobre"))
            return if (t.isNotEmpty()) orden("nota_nueva", "texto" to t) else orden("nota_nueva")
        }
        f.tras(listOf(
            "graba una nota de voz", "graba nota de voz", "grabar una nota de voz", "graba un audio", "graba audio", "nota de voz",
            "empieza a grabar", "graba un mensaje de voz", "graba"
        ))?.let { resto ->
            val nombre = sinConector(resto, listOf("llamada", "llamado", "que se llame", "con el nombre", "de nombre", "sobre", "de"))
            val r = Acciones.normalizar(resto)
            if (q.startsWith("graba ") && !q.contains("voz") && !q.contains("audio") && resto.isNotEmpty() && !r.startsWith("llamad") && !r.startsWith("que se llame")) return null
            return orden("grabar_voz", "nombre" to nombre.ifEmpty { "Nota de voz" })
        }
        f.tras(listOf(
            "crea una lista de tareas", "crea una lista", "crea la lista", "nueva lista de tareas", "nueva lista", "lista nueva",
            "haz una lista de tareas", "haz una lista", "crear una lista"
        ))?.let { resto ->
            val titulo = sinConector(resto, listOf("llamada", "que se llame", "con el nombre", "de nombre", "titulada", "para"))
            if (titulo.isEmpty() || titulo.codePointCount(0, titulo.length) > 60) return null
            return orden("lista_nueva", "titulo" to titulo)
        }
        marcar(f)?.let { return it }
        aplazar(f, ahora)?.let { return it }
        f.tras(listOf(
            "crea una tarea", "crea la tarea", "crea tarea", "anade una tarea", "anade la tarea", "anade tarea", "agrega una tarea",
            "agrega la tarea", "agrega tarea", "apunta la tarea", "apunta una tarea", "apunta tarea", "anota la tarea",
            "anota una tarea", "nueva tarea", "tarea nueva", "pon una tarea", "pon la tarea", "recuerdame", "recordarme",
            "apunta", "anota", "anade", "agrega"
        ))?.let { resto ->
            val r = Acciones.normalizar(resto)
            if (r.startsWith("en el chat") || r.startsWith("en una nota") || r.isEmpty()) return null
            val t0 = sinConector(resto, listOf("que diga", "de", "para", "llamada", "que"))
            val (t1, lista0) = listaAlFinal(t0)
            val (lista, chat) = partirChat(lista0)
            val (t2, fecha) = conFecha(t1, ahora) ?: return null
            val t = t2.trimEnd(',', ':').trim()
            if (t.isEmpty() || t.codePointCount(0, t.length) > 120) return null
            val args = mutableListOf<Pair<String, Any>>("texto" to t)
            lista?.let { args += "lista" to it }
            chat?.let { args += "chat" to it }
            fecha?.let { args += "fecha" to it }
            return orden("anadir_tarea", *args.toTypedArray())
        }
        return null
    }

    private fun conFecha(texto: String, ahora: Momento): Pair<String, String?>? = try {
        val (t, m) = Fechas.separar(texto, ahora)
        if (m != null) {
            val (y, mo, d) = Fechas.fechaDe(m.dia)
            t to "%04d-%02d-%02d %02d:%02d".format(y, mo, d, m.minuto / 60, m.minuto % 60)
        } else t to null
    } catch (e: ErrorFecha) {
        when (e) {
            is ErrorFecha.NoExiste, is ErrorFecha.Pasada -> {
                val t = texto.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ").trimEnd('.', ',', ';').trimEnd()
                if (t.endsWith(e.frase)) t.removeSuffix(e.frase).trimEnd() to e.frase else null
            }
            is ErrorFecha.NoEntiendo -> null
        }
    }

    private fun aplazar(f: Frase, ahora: Momento): Orden? {
        val resto0 = f.tras(listOf(
            "aplaza la tarea", "aplaza", "posterga la tarea", "posterga", "pospon la tarea", "pospon", "cambia la fecha de la tarea",
            "cambia la fecha de", "pasa la tarea", "mueve la tarea", "retrasa la tarea", "retrasa", "adelanta la tarea", "adelanta"
        )) ?: return null
        val resto = sinConector(resto0, listOf("la tarea"))
        val (tarea0, fecha) = conFecha(resto, ahora) ?: return null
        fecha ?: return null
        val tarea = tarea0.trimEnd(',', ':').trim()
        if (tarea.isEmpty() || tarea.codePointCount(0, tarea.length) > 120) return null
        return orden("editar_tarea", "tarea" to tarea, "fecha" to fecha)
    }

    private val HECHA = listOf(
        "marca como hecha la tarea", "marca como hecha", "marca como completada la tarea", "marca como completada",
        "marca como terminada", "marca la tarea", "marca", "tacha la tarea", "tacha", "he terminado la tarea", "he terminado",
        "ya termine", "termine la tarea", "termine", "ya hice", "he hecho", "completa la tarea", "completada la tarea"
    )
    private val PENDIENTE = listOf("desmarca la tarea", "desmarca", "marca como pendiente la tarea", "marca como pendiente")

    private fun marcar(f: Frase): Orden? {
        val primero = f.tras(HECHA)
        var hecha = primero != null
        val resto0 = primero ?: f.tras(PENDIENTE) ?: return null
        var resto = sinConector(resto0, listOf("la tarea", "que"))
        val q = Acciones.normalizar(resto)
        var explicito = !f.q.startsWith("marca la tarea") && !(f.q.startsWith("marca ") && !f.q.startsWith("marca como"))
        for ((fin, h) in listOf(" como hecha" to true, " como completada" to true, " como terminada" to true, " de hecha" to true, " hecha" to true, " como pendiente" to false, " como no hecha" to false)) {
            if (q.endsWith(fin)) { resto = resto.substring(0, resto.length - fin.length).trim(); hecha = h; explicito = true; break }
        }
        if (!explicito) return null
        val (tarea, lista0) = listaAlFinal(resto)
        val (lista, chat) = partirChat(lista0)
        if (tarea.isEmpty() || tarea.codePointCount(0, tarea.length) > 120) return null
        val args = mutableListOf<Pair<String, Any>>("tarea" to tarea, "hecha" to hecha)
        lista?.let { args += "lista" to it }
        chat?.let { args += "chat" to it }
        return orden("marcar_tarea", *args.toTypedArray())
    }

    private val EMPIEZAN = listOf(
        "que tengo pendiente", "que tengo por hacer", "que tengo que hacer", "que tareas tengo", "que me falta", "que me queda",
        "mis tareas", "tareas pendientes", "mis pendientes", "lo pendiente", "que hay pendiente", "que tareas me quedan",
        "que tareas hay", "que vencio", "que vencieron", "que ha vencido", "que han vencido", "que tareas vencieron",
        "que tareas han vencido", "que tareas vencen", "que vence", "tareas vencidas", "que tengo vencido", "que tareas me vencieron",
        "que tareas se me vencieron", "que se me vencio", "que me vencio", "que se me paso", "que tengo atrasado",
        "tareas atrasadas", "que hice", "que he hecho", "tareas hechas", "que tareas hice", "cuantas tareas tengo",
        "dime mis tareas", "dime que tengo pendiente", "lista mis tareas"
    )

    private fun consulta(q: String): Orden? {
        if (EMPIEZAN.none { q.startsWith(it) }) return null
        val estado = when { q.contains("venc") || q.contains("atrasad") -> "vencidas"; q.contains("hice") || q.contains("hecho") || q.contains("hechas") -> "hechas"; else -> "pendientes" }
        val periodo = when { q.contains("hoy") -> "hoy"; q.contains("semana") -> "semana"; q.contains(" mes") -> "mes"; else -> "todo" }
        val largo = EMPIEZAN.filter { q.startsWith(it) }.maxOf { it.length }
        var resto = " " + q.substring(largo)
        val args = linkedMapOf<String, String>("estado" to estado, "periodo" to periodo)
        while (true) {
            val marca = listOf(" en el chat " to "chat", " del chat " to "chat", " en el proyecto " to "chat", " del proyecto " to "chat", " en la lista " to "lista", " de la lista " to "lista")
                .mapNotNull { (m, campo) -> resto.lastIndexOf(m).takeIf { it >= 0 }?.let { Triple(it, m, campo) } }
                .maxByOrNull { it.first } ?: break
            val (i, m, campo) = marca
            val nombre = resto.substring(i + m.length).trim()
            if (nombre.isEmpty() || campo in args) return null
            args[campo] = nombre
            resto = resto.substring(0, i)
        }
        val sobra = resto.split(Regex("[^\\p{L}\\p{N}]+")).filter {
            it.isNotEmpty() && it !in setOf("hoy", "para", "esta", "este", "semana", "mes", "de", "que", "pendientes", "pendiente", "por", "hacer",
                "tengo", "aun", "todavia", "ya", "me", "las", "tareas", "en", "la", "el", "vencidas", "vencido")
        }
        if (sobra.isNotEmpty()) return null
        return Orden("consultar_tareas", JsonObject(args.mapValues { JsonPrimitive(it.value) }))
    }
}
