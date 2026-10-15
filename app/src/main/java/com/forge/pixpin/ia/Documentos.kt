package com.forge.pixpin.ia

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest

/**
 * **Buscar en mis cosas, sin internet** (`documentos/` del PC, portado tal cual): BM25 sobre las
 * palabras de los PDF, Word, Excel, notas y chats del usuario, para `buscar_en_mis_cosas`.
 *
 * - **Las palabras** ([palabras]): minúsculas y sin tildes, sin vacías, raíz ligera. Igual en la
 *   pregunta y en el texto; las dos listas copiadas del PC.
 * - **Trozos** de [PALABRAS_POR_TROZO] palabras con [SOLAPE] de solape, cada uno con su origen.
 * - **BM25** (k1 = 1,2; b = 0,75) con un empujón si la palabra está en el nombre de la fuente.
 * - **El índice es una caché** (`cacheDir/documentos`, mismo JSON que el PC): no se sincroniza y se
 *   puede borrar; se pone al día por firma (tamaño + fecha).
 *
 * Aquí no se lee ni un PDF: lo que hay que indexar lo da quien conoce los datos (la app), con un
 * [Lector].
 */
object Documentos {
    const val PALABRAS_POR_TROZO = 160
    const val SOLAPE = 30
    const val MAX_TROZOS_POR_FUENTE = 4000
    const val VERSION = 1L
    const val POR_FUENTE = 2
    const val LARGO_FRAGMENTO = 420
    const val RESULTADOS = 5
    const val MAX_CONSULTA = 300
    private const val K1 = 1.2f
    private const val B = 0.75f
    private const val EMPUJON_NOMBRE = 0.5f
    private const val CORTE_RELATIVO = 0.45f

    // --------------------------------------------------------------------------------- texto

    private val VACIAS = setOf(
        "a", "al", "algo", "como", "con", "cual", "cuando", "de", "del", "desde", "donde", "el", "ella", "ellos", "en",
        "entre", "era", "es", "esa", "ese", "eso", "esta", "este", "esto", "estos", "estas", "fue", "ha", "han", "hay",
        "la", "las", "le", "les", "lo", "los", "mas", "me", "mi", "mis", "muy", "ni", "no", "nos", "o", "para", "pero",
        "por", "que", "se", "sea", "ser", "si", "sin", "sobre", "son", "su", "sus", "tambien", "te", "tu", "tus", "un",
        "una", "uno", "unos", "unas", "y", "ya", "yo",
        "an", "and", "are", "as", "at", "be", "by", "for", "from", "in", "is", "it", "of", "on", "or", "the", "this",
        "to", "was", "with"
    )

    private val DE_LA_PREGUNTA = setOf(
        "busca", "buscar", "buscame", "encuentra", "dime", "dice", "decia", "dicen", "habla", "hablan", "menciona",
        "mencionan", "mande", "mandaste", "envie", "enviaste", "guarde", "puse", "tengo", "tenia", "quiero", "saber",
        "algun", "alguna", "documento", "documentos", "cosas", "archivo", "archivos"
    )

    /** Una letra sin tilde y en minúscula; null si no es letra ni número. */
    private fun plana(c: Int): String? = when (c.toChar()) {
        'á', 'à', 'ä', 'â', 'Á', 'À', 'Ä', 'Â' -> "a"
        'é', 'è', 'ë', 'ê', 'É', 'È', 'Ë', 'Ê' -> "e"
        'í', 'ì', 'ï', 'î', 'Í', 'Ì', 'Ï', 'Î' -> "i"
        'ó', 'ò', 'ö', 'ô', 'Ó', 'Ò', 'Ö', 'Ô' -> "o"
        'ú', 'ù', 'ü', 'û', 'Ú', 'Ù', 'Ü', 'Û' -> "u"
        'ñ', 'Ñ' -> "n"
        'ç', 'Ç' -> "c"
        else -> if (Character.isLetterOrDigit(c) || Character.isAlphabetic(c)) String(Character.toChars(Character.toLowerCase(c))) else null
    }

    /** El texto en minúsculas y sin tildes, con lo demás tal cual. */
    fun plano(texto: String): String = buildString { texto.codePoints().forEach { append(plana(it) ?: String(Character.toChars(it))) } }

    /** **La raíz ligera** de una palabra ya plana. */
    fun raiz(p: String): String {
        val n = p.codePointCount(0, p.length)
        if (n <= 3 || p.any { it in '0'..'9' }) return p
        fun quitar(p: String, fines: List<String>): String {
            for (fin in fines) if (p.endsWith(fin)) {
                val r = p.removeSuffix(fin)
                if (r.codePointCount(0, r.length) >= 3) return r
            }
            return p
        }
        val q = quitar(p, listOf("aciones", "amientos", "imientos", "amiento", "imiento", "acion", "ciones", "mente", "idades", "idad", "ables", "able", "ibles", "ible", "es", "s"))
        return quitar(q, listOf("a", "o", "e"))
    }

    private fun crudas(texto: String): List<String> {
        val v = ArrayList<String>()
        val actual = StringBuilder()
        texto.codePoints().forEach { c ->
            val p = plana(c)
            if (p != null) actual.append(p) else if (actual.isNotEmpty()) { v += actual.toString(); actual.setLength(0) }
        }
        if (actual.isNotEmpty()) v += actual.toString()
        return v
    }

    /** Las palabras de un texto para el índice: planas, sin vacías y en raíz. */
    fun palabras(texto: String): List<String> = crudas(texto).mapNotNull { p ->
        val largo = p.codePointCount(0, p.length)
        val numero = p.all { it in '0'..'9' }
        if ((largo >= 2 || numero) && largo <= 40 && p !in VACIAS) raiz(p) else null
    }

    /** Las palabras de una **pregunta**: sin las de preguntar y sin repetidas. */
    fun deLaConsulta(consulta: String): List<String> {
        val fuera = ArrayList<String>()
        for (p in crudas(consulta)) {
            if (p in DE_LA_PREGUNTA) continue
            for (r in palabras(p)) if (r !in fuera) fuera += r
        }
        return fuera
    }

    // ------------------------------------------------------------------------------- modelo

    enum class Tipo(val clave: String) {
        PDF("pdf"), WORD("word"), EXCEL("excel"), POWERPOINT("powerpoint"), NOTA("nota"), TEXTO("texto"), CHAT("chat");

        val nombreDeUnidad: String get() = if (this == POWERPOINT) "diapositiva" else "pág."

        companion object {
            fun deClave(s: String) = entries.firstOrNull { it.clave == s }
        }
    }

    /** **Cómo se abre lo encontrado**: un pedido `abrir` del protocolo. */
    sealed class Abrir {
        data class Fichero(val ruta: String, val pagina: Int?) : Abrir()
        data class Mensaje(val proyecto: String?, val codigo: String) : Abrir()

        fun pedido(): JsonObject = when (this) {
            is Fichero -> buildJsonObject {
                put("pixpin", 1); put("accion", "abrir")
                put("que", buildJsonObject { put("tipo", "fichero"); put("ruta", ruta); pagina?.let { put("pagina", it) } })
            }
            is Mensaje -> buildJsonObject {
                put("pixpin", 1); put("accion", "abrir")
                put("que", buildJsonObject { put("tipo", "mensaje"); put("proyecto", proyecto?.let { JsonPrimitive(it) } ?: JsonNull); put("codigo", codigo) })
            }
        }

        fun aJson(): JsonObject = when (this) {
            is Fichero -> buildJsonObject { put("ruta", ruta); put("pagina", pagina?.let { JsonPrimitive(it) } ?: JsonNull) }
            is Mensaje -> buildJsonObject { put("proyecto", proyecto?.let { JsonPrimitive(it) } ?: JsonNull); put("codigo", codigo) }
        }

        fun enPagina(p: Int?): Abrir = if (this is Fichero) copy(pagina = p) else this

        companion object {
            fun deJson(v: JsonElement?): Abrir? {
                val o = v as? JsonObject ?: return null
                (o["ruta"] as? JsonPrimitive)?.takeIf { it.isString }?.let { return Fichero(it.content, (o["pagina"] as? JsonPrimitive)?.intOrNull) }
                val codigo = (o["codigo"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
                return Mensaje((o["proyecto"] as? JsonPrimitive)?.takeIf { it.isString }?.content, codigo)
            }
        }
    }

    data class Firma(val bytes: Long, val modificado: Long) {
        companion object {
            fun de(f: File): Firma? = if (f.exists()) Firma(f.length(), f.lastModified()) else null
        }
    }

    /** **Una fuente que indexar**, como la describe la app. */
    data class Fuente(
        val clave: String,
        val proyecto: String,
        val proyectoNombre: String,
        val nombre: String,
        val tipo: Tipo,
        val ruta: String,
        val firma: Firma,
        val abrir: Abrir
    )

    /** Un pedazo de lo leído: una página, una hoja, un mensaje. */
    data class Unidad(
        val texto: String,
        val pagina: Int? = null,
        val seccion: String? = null,
        val abrir: Abrir? = null,
        val cuando: Long? = null
    )

    fun interface Lector {
        /** El texto de la fuente; lanza con el motivo si no se pudo leer. */
        fun leer(fuente: Fuente): List<Unidad>
    }

    /** A quién se busca: todo (null) o solo esos proyectos. */
    data class Alcance(val proyectos: Set<String>?) {
        fun deja(p: String) = proyectos == null || p in proyectos

        companion object {
            val TODO = Alcance(null)
            fun solo(p: String) = Alcance(setOf(p))
        }
    }

    data class Hallazgo(
        val nombre: String,
        val tipo: Tipo,
        val proyecto: String,
        val proyectoNombre: String,
        val pagina: Int?,
        val seccion: String?,
        val cuando: Long?,
        val fragmento: String,
        val abrir: Abrir,
        val puntos: Float
    )

    data class Informe(var leidas: Int = 0, var quitadas: Int = 0, var sinCambios: Int = 0, val rotas: MutableList<Pair<String, String>> = mutableListOf())

    private class Trozo(
        var fuente: Int,
        val texto: String,
        val pagina: Int?,
        val seccion: String?,
        val abrir: Abrir?,
        val cuando: Long?,
        var largo: Int = 0,
        var vivo: Boolean = true
    )

    private class Guardada(val fuente: Fuente, val fallo: String?, val trozos: List<Int>, val nombre: Set<String>, var viva: Boolean = true)

    /** **El índice.** No es seguro entre hilos: quien lo use, con su cerrojo. */
    class Indice(private val dir: File?) {
        private val fuentes = ArrayList<Guardada>()
        private val porClave = HashMap<String, Int>()
        private val trozos = ArrayList<Trozo>()
        private val terminos = HashMap<String, MutableList<IntArray>>()
        private var vivos = 0L
        private var sumaLargos = 0L
        private var sucio = false

        val tamano: Pair<Int, Int> get() = fuentes.count { it.viva } to vivos.toInt()

        fun alDia(f: Fuente): Boolean = porClave[f.clave]?.let { fuentes[it] }?.let { g ->
            g.viva && g.fuente.firma == f.firma && g.fuente.abrir == f.abrir && g.fuente.nombre == f.nombre && g.fuente.proyectoNombre == f.proyectoNombre
        } == true

        /** Las que leer (nuevas o cambiadas) y las claves que quitar. */
        fun pendientes(lista: List<Fuente>): Pair<List<Fuente>, List<String>> {
            val nuevas = lista.map { it.clave }.toSet()
            return lista.filter { !alDia(it) } to fuentes.filter { it.viva && it.fuente.clave !in nuevas }.map { it.fuente.clave }
        }

        /** **Mete lo leído de una fuente** (sustituye lo que hubiera). */
        fun aplicar(fuente: Fuente, leido: Result<List<Unidad>>) {
            quitar(fuente.clave)
            val fallo = leido.exceptionOrNull()?.let { it.message ?: "no se pudo leer" }
            val ts = leido.getOrNull()?.let(::trocear) ?: emptyList()
            dir?.let { d ->
                val arch = File(File(d, "f"), archivoDe(fuente.clave))
                if (fallo == null) runCatching {
                    escribir(arch, buildJsonObject { put("clave", fuente.clave); put("trozos", JsonArray(ts.map(::trozoAJson))) }.toString())
                } else arch.delete()
            }
            meter(fuente, fallo, ts)
            sucio = true
        }

        fun quitar(clave: String) {
            val i = porClave[clave] ?: return
            val g = fuentes[i]
            if (!g.viva) return
            g.viva = false
            for (t in g.trozos) {
                val tr = trozos[t]
                if (tr.vivo) { tr.vivo = false; vivos--; sumaLargos -= tr.largo }
            }
            dir?.let { File(File(it, "f"), archivoDe(clave)).delete() }
            porClave.remove(clave)
            sucio = true
            if (trozos.size > 2 * vivos + 1000) compactar()
        }

        /** **Pone al día** con un lector, todo de una vez. */
        fun ponerAlDia(lista: List<Fuente>, lector: Lector, parar: () -> Boolean = { false }): Informe {
            val (leer, quitar) = pendientes(lista)
            val inf = Informe(sinCambios = lista.size - leer.size, quitadas = quitar.size)
            quitar.forEach(::quitar)
            for (f in leer) {
                if (parar()) break
                val r = runCatching { lector.leer(f) }
                r.exceptionOrNull()?.let { inf.rotas += f.nombre to (it.message ?: "no se pudo leer") }
                inf.leidas++
                aplicar(f, r)
            }
            guardar()
            return inf
        }

        fun guardar() {
            if (!sucio) return
            val d = dir ?: run { sucio = false; return }
            val lista = fuentes.filter { it.viva }.map { g ->
                val v = fuenteAJson(g.fuente).toMutableMap()
                g.fallo?.let { v["fallo"] = JsonPrimitive(it) }
                JsonObject(v)
            }
            runCatching { escribir(File(d, "fuentes.json"), buildJsonObject { put("version", VERSION); put("fuentes", JsonArray(lista)) }.toString()) }
                .onSuccess { sucio = false }
        }

        private fun meter(fuente: Fuente, fallo: String?, ts: List<Trozo>) {
            val i = fuentes.size
            val ids = ArrayList<Int>(ts.size)
            for (t in ts) {
                t.fuente = i
                val id = trozos.size
                val cuenta = LinkedHashMap<String, Int>()
                var largo = 0
                for (p in palabras(t.texto)) { cuenta[p] = (cuenta[p] ?: 0) + 1; largo++ }
                t.seccion?.let { s -> for (p in palabras(s)) { cuenta[p] = (cuenta[p] ?: 0) + 1; largo++ } }
                for ((p, n) in cuenta) terminos.getOrPut(p) { ArrayList() }.add(intArrayOf(id, minOf(n, 65535)))
                t.largo = largo
                t.vivo = true
                vivos++
                sumaLargos += largo
                trozos += t
                ids += id
            }
            porClave[fuente.clave] = i
            fuentes += Guardada(fuente, fallo, ids, palabras(fuente.nombre).toSet())
        }

        private fun compactar() {
            val viejas = ArrayList(fuentes)
            val viejos = ArrayList(trozos)
            fuentes.clear(); trozos.clear(); porClave.clear(); terminos.clear(); vivos = 0; sumaLargos = 0
            for (g in viejas.filter { it.viva }) meter(g.fuente, g.fallo, g.trozos.map { viejos[it] })
        }

        /** **Busca.** Los [max] mejores trozos de lo que deja [alcance], [POR_FUENTE] por fuente. */
        fun buscar(consulta: String, alcance: Alcance, max: Int = RESULTADOS): List<Hallazgo> {
            val q = deLaConsulta(consulta)
            if (q.isEmpty() || vivos == 0L) return emptyList()
            val n = vivos.toFloat()
            val medio = maxOf(sumaLargos.toFloat() / n, 1.0f)
            fun deja(t: Trozo) = t.vivo && alcance.deja(fuentes[t.fuente].fuente.proyecto)
            fun idf(p: String): Float {
                val df = (terminos[p]?.count { trozos[it[0]].vivo } ?: 0).toFloat()
                return Math.log(((n - df + 0.5f) / (df + 0.5f) + 1.0f).toDouble()).toFloat()
            }
            val puntos = HashMap<Int, Float>()
            for (p in q) {
                val lista = terminos[p] ?: continue
                val i = idf(p)
                for (e in lista) {
                    val tr = trozos[e[0]]
                    if (!deja(tr)) continue
                    val tf = e[1].toFloat()
                    val s = i * tf * (K1 + 1.0f) / (tf + K1 * (1.0f - B + B * tr.largo.toFloat() / medio))
                    puntos[e[0]] = (puntos[e[0]] ?: 0f) + s
                }
            }
            val porNombre = HashMap<Int, Float>()
            fuentes.forEachIndexed { i, g ->
                if (!g.viva || !alcance.deja(g.fuente.proyecto)) return@forEachIndexed
                var e = 0f
                for (p in q) if (p in g.nombre) e += EMPUJON_NOMBRE * idf(p)
                if (e > 0f) porNombre[i] = e
            }
            val conTexto = HashSet<Int>()
            for (t in puntos.keys.toList()) {
                val f = trozos[t].fuente
                conTexto += f
                porNombre[f]?.let { puntos[t] = puntos[t]!! + it }
            }
            for ((f, e) in porNombre) {
                if (f in conTexto) continue
                fuentes[f].trozos.firstOrNull()?.let { puntos[it] = e }
            }
            val orden = puntos.entries.map { it.key to it.value }.sortedWith(compareByDescending<Pair<Int, Float>> { it.second }.thenBy { it.first })
            val tope = orden.firstOrNull()?.second?.times(CORTE_RELATIVO) ?: 0f
            val cuantos = HashMap<Int, Int>()
            val fuera = ArrayList<Hallazgo>()
            for ((t, s) in orden) {
                if (s < tope) continue
                val tr = trozos[t]
                val c = cuantos[tr.fuente] ?: 0
                if (c >= POR_FUENTE) continue
                cuantos[tr.fuente] = c + 1
                val g = fuentes[tr.fuente]
                fuera += Hallazgo(
                    g.fuente.nombre, g.fuente.tipo, g.fuente.proyecto, g.fuente.proyectoNombre, tr.pagina, tr.seccion, tr.cuando,
                    fragmento(tr.texto, q, LARGO_FRAGMENTO), tr.abrir ?: g.fuente.abrir.enPagina(tr.pagina), s
                )
                if (fuera.size >= max) break
            }
            return fuera
        }

        companion object {
            /** **Lee el índice de [dir].** Lo que no se entienda se tira (es una caché). */
            fun cargar(dir: File): Indice {
                val ix = Indice(dir)
                val lista = runCatching { Json.parseToJsonElement(File(dir, "fuentes.json").readText()) as JsonObject }.getOrNull() ?: return ix
                if ((lista["version"] as? JsonPrimitive)?.longOrNull != VERSION) return ix
                for (f in (lista["fuentes"] as? JsonArray).orEmpty()) {
                    val fuente = fuenteDeJson(f as? JsonObject ?: continue) ?: continue
                    val fallo = (f["fallo"] as? JsonPrimitive)?.content
                    val ts = if (fallo != null) emptyList() else runCatching {
                        val v = Json.parseToJsonElement(File(File(dir, "f"), archivoDe(fuente.clave)).readText()) as JsonObject
                        (v["trozos"] as JsonArray).mapNotNull { trozoDeJson(it as? JsonObject) }
                    }.getOrNull() ?: continue
                    ix.meter(fuente, fallo, ts)
                }
                ix.sucio = false
                return ix
            }
        }
    }

    private fun trocear(unidades: List<Unidad>): List<Trozo> {
        val v = ArrayList<Trozo>()
        for (u in unidades) {
            val ps = u.texto.split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (ps.isEmpty()) continue
            var desde = 0
            while (true) {
                val hasta = minOf(desde + PALABRAS_POR_TROZO, ps.size)
                v += Trozo(0, ps.subList(desde, hasta).joinToString(" "), u.pagina, u.seccion, u.abrir, u.cuando)
                if (hasta == ps.size || v.size >= MAX_TROZOS_POR_FUENTE) break
                desde = hasta - SOLAPE
            }
            if (v.size >= MAX_TROZOS_POR_FUENTE) break
        }
        return v
    }

    /** **El trozo recortado alrededor de lo buscado**, en palabras enteras. */
    fun fragmento(texto: String, consulta: List<String>, largo: Int): String {
        val letras = texto.codePoints().toArray()
        if (letras.size <= largo) return texto
        fun alnum(c: Int) = Character.isLetterOrDigit(c) || Character.isAlphabetic(c)
        val aciertos = ArrayList<Int>()
        var i = 0
        while (i < letras.size) {
            if (alnum(letras[i])) {
                val ini = i
                while (i < letras.size && alnum(letras[i])) i++
                val palabra = String(letras, ini, i - ini)
                if (palabras(palabra).any { it in consulta }) aciertos += ini
            } else i++
        }
        var mejor = 0 to 0
        for (a in aciertos) {
            val desde = maxOf(0, a - largo / 4)
            val n = aciertos.count { it >= desde && it < desde + largo }
            if (n > mejor.second) mejor = desde to n
        }
        var desde = minOf(mejor.first, maxOf(0, letras.size - largo))
        while (desde > 0 && !Character.isWhitespace(letras[desde - 1])) desde--
        var hasta = minOf(desde + largo, letras.size)
        while (hasta < letras.size && !Character.isWhitespace(letras[hasta]) && hasta - desde < largo + 30) hasta++
        val s = StringBuilder(String(letras, desde, hasta - desde))
        if (desde > 0) s.insert(0, '…')
        if (hasta < letras.size) s.append('…')
        return s.toString()
    }

    private fun archivoDe(clave: String): String {
        val h = MessageDigest.getInstance("SHA-256").digest(clave.toByteArray())
        return h.take(12).joinToString("") { "%02x".format(it) } + ".json"
    }

    private fun escribir(f: File, texto: String) {
        f.parentFile?.mkdirs()
        val tmp = File(f.path.substringBeforeLast('.') + ".tmp")
        tmp.writeText(texto)
        if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) error("no se pudo escribir ${f.name}") }
    }

    private fun fuenteAJson(f: Fuente) = buildJsonObject {
        put("clave", f.clave); put("proyecto", f.proyecto); put("proyectoNombre", f.proyectoNombre)
        put("nombre", f.nombre); put("tipo", f.tipo.clave); put("ruta", f.ruta)
        put("bytes", f.firma.bytes); put("modificado", f.firma.modificado); put("abrir", f.abrir.aJson())
    }

    private fun fuenteDeJson(v: JsonObject): Fuente? {
        fun s(k: String) = (v[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return Fuente(
            s("clave") ?: return null, s("proyecto") ?: return null, s("proyectoNombre") ?: "", s("nombre") ?: return null,
            Tipo.deClave(s("tipo") ?: return null) ?: return null, s("ruta") ?: return null,
            Firma((v["bytes"] as? JsonPrimitive)?.longOrNull ?: return null, (v["modificado"] as? JsonPrimitive)?.longOrNull ?: return null),
            Abrir.deJson(v["abrir"]) ?: return null
        )
    }

    private fun trozoAJson(t: Trozo) = buildJsonObject {
        put("t", t.texto)
        t.pagina?.let { put("p", it) }
        t.seccion?.let { put("s", it) }
        t.abrir?.let { put("a", it.aJson()) }
        t.cuando?.let { put("c", it) }
    }

    private fun trozoDeJson(v: JsonObject?): Trozo? {
        v ?: return null
        val t = (v["t"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        return Trozo(0, t, (v["p"] as? JsonPrimitive)?.intOrNull, (v["s"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
            Abrir.deJson(v["a"]), (v["c"] as? JsonPrimitive)?.longOrNull)
    }

    // ---------------------------------------------------------------------------- herramienta

    /** Lo que pidió el modelo, ya validado. */
    data class PeticionBusqueda(val consulta: String, val proyecto: String?)

    fun validar(args: JsonObject): PeticionBusqueda {
        fun texto(k: String) = (args[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
        val consulta = texto("consulta") ?: texto("query") ?: texto("pregunta")
            ?: throw ErrorHerramienta.Parametro("falta «consulta» (las palabras que buscar)")
        if (deLaConsulta(consulta).isEmpty()) throw ErrorHerramienta.Parametro("«consulta» no tiene palabras que buscar; pon las palabras clave del tema")
        val corta = if (consulta.codePointCount(0, consulta.length) <= MAX_CONSULTA) consulta else consulta.substring(0, consulta.offsetByCodePoints(0, MAX_CONSULTA))
        val proyecto = (texto("proyecto") ?: texto("chat"))?.takeIf { plano(it) !in setOf("todos", "todo", "*", "all", "ninguno") }
        return PeticionBusqueda(corta, proyecto)
    }

    data class Cita(val numero: Int, val hallazgo: Hallazgo) {
        /** «Tesis.pdf · pág. 12», o lo que dice un mensaje. */
        fun titulo(): String {
            val h = hallazgo
            if (h.tipo == Tipo.CHAT) {
                val f = h.fragmento.trim().trimStart('…')
                val sb = StringBuilder(if (f.codePointCount(0, f.length) <= 60) f else f.substring(0, f.offsetByCodePoints(0, 60)))
                if (h.fragmento.codePointCount(0, h.fragmento.length) > 60) sb.append('…')
                h.cuando?.let { fechaCorta(it) }?.let { sb.append(" · $it") }
                return sb.toString()
            }
            val sb = StringBuilder(h.nombre)
            if (h.pagina != null) sb.append(" · ${h.tipo.nombreDeUnidad} ${h.pagina}")
            else if (!h.seccion.isNullOrEmpty()) sb.append(" · ${h.seccion}")
            return sb.toString()
        }

        /** Como se guarda en la nota de la respuesta (`fuentesIa`), igual que el PC. */
        fun aJson(): JsonObject = buildJsonObject {
            put("n", numero); put("titulo", titulo()); put("proyecto", hallazgo.proyectoNombre); put("pedido", hallazgo.abrir.pedido())
        }
    }

    /** **Las citas de un turno**, numeradas seguidas. */
    class Citas {
        val lista = ArrayList<Cita>()

        fun anadir(consulta: String, hallazgos: List<Hallazgo>, aviso: String? = null): String {
            if (hallazgos.isEmpty()) {
                return "No encontre nada sobre «$consulta» en los documentos y chats del usuario. Diselo asi; no inventes." + (aviso?.let { " $it" } ?: "")
            }
            val s = StringBuilder("Esto es lo que hay en los documentos y chats del usuario. Contesta SOLO con lo que dicen estos trozos, en pocas frases, y cita cada dato con su numero entre corchetes, como [1]:\n")
            for (h in hallazgos) {
                val numero = lista.firstOrNull { it.hallazgo.abrir == h.abrir }?.numero ?: (lista.size + 1).also { lista += Cita(it, h) }
                if (h.tipo == Tipo.CHAT && h.seccion == "adjunto") {
                    val cuando = h.cuando?.let { fechaCorta(it) }?.let { " el $it" } ?: ""
                    s.append("\n[$numero] El usuario SI mando el archivo «${h.fragmento.trim()}» al chat «${h.proyectoNombre}»$cuando.\n")
                } else s.append("\n[$numero] ${origen(h)}\n«${h.fragmento.replace('\n', ' ')}»\n")
            }
            aviso?.let { s.append('\n').append(it) }
            return s.toString()
        }

        /** Las que el modelo nombró (`[2]`); si ninguna, la primera. */
        fun citadas(respuesta: String): List<Cita> {
            val nombradas = lista.filter { respuesta.contains("[${it.numero}]") }
            return nombradas.ifEmpty { listOfNotNull(lista.firstOrNull()) }
        }
    }

    private fun origen(h: Hallazgo): String {
        val que = if (h.tipo == Tipo.CHAT) when {
            h.seccion == "adjunto" -> "archivo que el usuario mando al chat «${h.proyectoNombre}»"
            !h.seccion.isNullOrEmpty() -> "${h.seccion} en el chat «${h.proyectoNombre}»"
            else -> "mensaje del chat «${h.proyectoNombre}»"
        } else {
            val sb = StringBuilder("«${h.nombre}»")
            if (h.pagina != null) sb.append(", ${h.tipo.nombreDeUnidad} ${h.pagina}")
            else if (!h.seccion.isNullOrEmpty()) sb.append(", ${h.seccion}")
            sb.append(" (chat «${h.proyectoNombre}»)").toString()
        }
        return h.cuando?.let { fechaCorta(it) }?.let { "$que, $it" } ?: que
    }

    /** «3-oct-2026» de unos milisegundos desde 1970 (UTC, como el PC). */
    fun fechaCorta(ms: Long): String? {
        if (ms < 978_307_200_000L) return null
        val (y, m, d) = Fechas.fechaDe(Math.floorDiv(ms, 86_400_000L))
        val meses = listOf("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic")
        return "$d-${meses[(m - 1).toInt()]}-$y"
    }
}
