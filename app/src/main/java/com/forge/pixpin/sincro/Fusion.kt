package com.forge.pixpin.sincro

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * **Juntar dos versiones de lo mismo sin que ninguna mande** (15-sep-2026).
 *
 * El usuario lo pidió así: si en un aparato el lienzo es A+B y en el otro A+C, al sincronizar tiene
 * que quedar **A+B+C**, no «el del maestro». Es la fusión a tres bandas de git —mirar qué cambió cada
 * uno desde lo último que tuvieron en común (la *base*) y sumar los dos cambios—, pero sobre el JSON
 * de las cosas y no sobre sus líneas: un lienzo se junta **figura por figura** (por su `id`), una
 * tabla **celda por celda**, una nota **párrafo por párrafo** y un croquis **trazo por trazo**.
 *
 * Cómo lo resuelven otros, y de dónde sale cada regla:
 *
 * - **Propiedad por propiedad** (Figma, Wallace 2019, «How Figma's multiplayer technology works»):
 *   si en un lado se mueve una figura y en el otro se le cambia el color, queda movida *y* con el
 *   color nuevo. Solo choca la misma propiedad cambiada en los dos; entonces gana **el último**.
 * - **El último, sin fiarse del reloj** (Excalidraw, `data/reconcile.ts`: gana la `version` mayor y,
 *   a igual versión, el `versionNonce` menor). Aquí primero la hora `updated` **corregida con el
 *   desfase medido al conectar** —lo que se acerca a un reloj híbrido (Kulkarni et al., OPODIS
 *   2014) sin tocar cada escritura—, y a igualdad, la regla de Excalidraw.
 * - **Lo modificado gana a lo borrado** (el OR-Set de Bieniusa et al. 2012, «add-wins»): borrar
 *   quita lo que el que borró llegó a ver; un cambio que no vio sobrevive. Borrado contra intacto sí
 *   se borra. Así nunca se pierde trabajo en silencio, que es lo que el usuario pidió.
 * - **Los párrafos, con diff3** (Khanna, Kunal y Pierce, FSTTCS 2007): cada lado contra la base, y
 *   solo choca un tramo tocado en los dos. La versión que pierde queda en la copia de seguridad que
 *   se hace antes de sincronizar ([Copias]).
 * - **Solo viajan los cambios** ([diferencia] y [aplicar]): los dos aparatos guardan la base, así que
 *   basta mandar qué figuras se añadieron, cambiaron o quitaron (los «delta» de Almeida, Shoker y
 *   Baquero, JPDC 2018).
 *
 * Sin Android: entra JSON y sale JSON, y se prueba entero en la JVM.
 */
object Fusion {

    /**
     * Quién gana cuando los dos cambiaron lo mismo.
     *
     * [mioMasNuevo] decide cuando las cosas no llevan hora propia (el archivo que se tocó más
     * tarde). [desfase] es cuánto va adelantado el reloj del otro: se le resta a sus horas antes de
     * compararlas, o un teléfono con la hora adelantada ganaría siempre.
     */
    data class Criterio(val mioMasNuevo: Boolean = true, val desfase: Long = 0L)

    /** Las claves de texto que se juntan por párrafos y no enteras. */
    val PARRAFOS = setOf("nota", "texto", "transcripcion")

    /** Lo que cuenta de una fusión, para decirlo al acabar. */
    class Cuenta {
        /** La misma cosa cambiada en los dos lados y resuelta por «gana el último». */
        var choques = 0
        /** Algo borrado en un lado que vuelve porque en el otro se cambió. */
        var rescatados = 0
    }

    /**
     * **La fusión a tres bandas.** [base] es lo último que tuvieron en común (null si nunca se
     * sincronizó: entonces se junta todo, que perder algo es peor que tener de más). Devuelve null
     * si el resultado es «no está».
     */
    fun json(
        base: JsonElement?,
        mio: JsonElement?,
        suyo: JsonElement?,
        criterio: Criterio = Criterio(),
        cuenta: Cuenta = Cuenta()
    ): JsonElement? = juntar(nulo(base), nulo(mio), nulo(suyo), criterio.mioMasNuevo, null, criterio, cuenta)

    private fun nulo(e: JsonElement?): JsonElement? = if (e is JsonNull) null else e

    private fun juntar(
        b: JsonElement?, m: JsonElement?, s: JsonElement?,
        ganaMio: Boolean, clave: String?, c: Criterio, cuenta: Cuenta
    ): JsonElement? {
        if (m == s) return m
        if (m == b) return s
        if (s == b) return m
        // Cambiado en los dos, y distinto.
        if (m == null || s == null) {
            // Borrado en uno y cambiado en el otro: **se queda lo cambiado**.
            return m ?: s
        }
        if (m is JsonObject && s is JsonObject) return objeto(b as? JsonObject, m, s, ganaMio, c, cuenta)
        if (m is JsonArray && s is JsonArray) {
            val bb = b as? JsonArray
            if (conIds(m) && conIds(s) && (bb == null || conIds(bb))) return listaConIds(bb, m, s, ganaMio, c, cuenta)
            if (deTextos(m) && deTextos(s) && (bb == null || deTextos(bb))) return JsonArray(conjunto(bb, m, s))
        }
        if (clave in PARRAFOS && m is JsonPrimitive && s is JsonPrimitive && m.isString && s.isString) {
            val bt = (b as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
            return JsonPrimitive(parrafos(bt, m.content, s.content, ganaMio, cuenta))
        }
        cuenta.choques++
        return if (ganaMio) m else s
    }

    /**
     * Un objeto, clave por clave. Si lleva su propia hora (`updated` en una figura, `tocado` en una
     * tabla), esa decide los choques de dentro; si no, lo que venga de fuera.
     */
    private fun objeto(b: JsonObject?, m: JsonObject, s: JsonObject, ganaDeFuera: Boolean, c: Criterio, cuenta: Cuenta): JsonObject {
        val gana = desempate(m, s, c) ?: ganaDeFuera
        val salida = LinkedHashMap<String, JsonElement>()
        for (k in m.keys + s.keys.filter { it !in m }) {
            val v = juntar(b?.get(k), m[k], s[k], gana, k, c, cuenta) ?: continue
            salida[k] = v
        }
        val junto = JsonObject(salida)
        // **Una figura hecha de las dos** no es ninguna de las dos: sube de versión, como si se
        // hubiera editado, para que nadie la tome por la vieja.
        if (junto != m && junto != s && m["id"] != null && m["version"] is JsonPrimitive) {
            val v = maxOf(numero(m["version"]) ?: 0, numero(s["version"]) ?: 0) + 1
            salida["version"] = JsonPrimitive(v)
            val hora = maxOf(numero(m["updated"]) ?: 0, (numero(s["updated"]) ?: 0) - c.desfase)
            if (m["updated"] != null || s["updated"] != null) salida["updated"] = JsonPrimitive(hora)
            return JsonObject(salida)
        }
        return junto
    }

    /** `true` si gana el mío, `false` si el suyo, null si estos objetos no dicen nada de su hora. */
    internal fun desempate(m: JsonObject, s: JsonObject, c: Criterio): Boolean? {
        for (campo in listOf("updated", "tocado")) {
            val hm = numero(m[campo]) ?: continue
            val hs = numero(s[campo]) ?: continue
            val suya = hs - c.desfase
            if (hm != suya) return hm > suya
        }
        val vm = numero(m["version"])
        val vs = numero(s["version"])
        if (vm != null && vs != null) {
            if (vm != vs) return vm > vs
            val nm = numero(m["versionNonce"])
            val ns = numero(s["versionNonce"])
            if (nm != null && ns != null && nm != ns) return nm < ns
        }
        return null
    }

    private fun numero(e: JsonElement?): Long? = (e as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
        ?: (e as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()?.toLong()

    private fun idDe(e: JsonElement): String? = ((e as? JsonObject)?.get("id") as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** Una lista de cosas con `id` distinto cada una: figuras, trazos, hojas. */
    internal fun conIds(a: JsonArray): Boolean {
        if (a.isEmpty()) return true
        val vistos = HashSet<String>()
        return a.all { e -> idDe(e)?.let { vistos.add(it) } == true }
    }

    private fun deTextos(a: JsonArray) = a.all { it is JsonPrimitive && it.isString }

    /**
     * **Figuras, trazos: por su id.** Lo añadido en cualquier lado entra; lo quitado en un lado se
     * quita si el otro no lo tocó, y se queda si el otro lo cambió. El orden (qué figura va encima)
     * es el del mío con lo nuevo del otro colocado detrás de su vecina.
     */
    private fun listaConIds(b: JsonArray?, m: JsonArray, s: JsonArray, gana: Boolean, c: Criterio, cuenta: Cuenta): JsonArray {
        val enM = m.associateBy { idDe(it)!! }
        val enS = s.associateBy { idDe(it)!! }
        val enB = b?.associateBy { idDe(it)!! } ?: emptyMap()
        val orden = ordenJunto(m.map { idDe(it)!! }, s.map { idDe(it)!! }, b?.map { idDe(it)!! }) { k ->
            val em = enM[k]; val es = enS[k]; val eb = enB[k]
            when {
                em != null && es != null -> true
                b == null -> true
                // Solo en uno: si no estaba en la base, alguien lo añadió.
                eb == null -> true
                // Estaba y en un lado ya no: se borró allí. Se queda solo si aquí se cambió.
                em != null -> (em != eb).also { if (it) cuenta.rescatados++ }
                es != null -> (es != eb).also { if (it) cuenta.rescatados++ }
                else -> false
            }
        }
        return JsonArray(orden.mapNotNull { k -> juntar(enB[k], enM[k], enS[k], gana, null, c, cuenta) })
    }

    /** Una lista de textos (grupos de una figura, croquis de un proyecto) como un conjunto a tres bandas. */
    private fun conjunto(b: JsonArray?, m: JsonArray, s: JsonArray): List<JsonElement> {
        val base = b?.map { (it as JsonPrimitive).content }?.toSet()
        val cm = m.map { (it as JsonPrimitive).content }
        val cs = s.map { (it as JsonPrimitive).content }
        return ordenJunto(cm, cs, base?.toList()) { k ->
            val em = k in cm; val es = k in cs
            when {
                em && es -> true
                base == null -> true
                else -> k !in base
            }
        }.map { JsonPrimitive(it) }
    }

    /**
     * **El orden de una lista juntada**: la del mío, con lo que solo tiene el otro metido detrás de
     * la que tenía delante en la suya. Así lo añadido en medio en la tableta cae en medio también
     * aquí, y si los dos añadieron al final, va primero lo mío y luego lo suyo, sin intercalar (lo
     * que Kleppmann et al., PaPoC 2019, llaman «interleaving»).
     */
    fun ordenJunto(mio: List<String>, suyo: List<String>, base: List<String>?, sigue: (String) -> Boolean): List<String> {
        val enMio = mio.toHashSet()
        val enSuyo = suyo.toHashSet()
        val enBase = base?.toHashSet() ?: emptySet()
        val salida = ArrayList<String>()
        val puestos = HashSet<String>()
        for (k in mio) if (k !in puestos && sigue(k)) { puestos += k; salida += k }
        var anterior: String? = null
        for (k in suyo) {
            if (k !in enMio && k !in salida && sigue(k)) {
                var donde = if (anterior == null) 0 else salida.indexOf(anterior) + 1
                while (donde < salida.size && salida[donde] !in enSuyo && salida[donde] !in enBase) donde++
                salida.add(donde.coerceIn(0, salida.size), k)
            }
            if (k in salida) anterior = k
        }
        return salida
    }

    // ------------------------------------------------------------------ párrafos

    /**
     * **Una nota juntada párrafo por párrafo**, con diff3: se buscan los párrafos que siguen iguales
     * en los tres, y entre ellos, cada tramo se toma del lado que lo cambió. Si los dos cambiaron el
     * mismo tramo, gana el más reciente.
     */
    fun parrafos(base: String, mio: String, suyo: String, ganaMio: Boolean, cuenta: Cuenta = Cuenta()): String {
        if (mio == suyo) return mio
        if (mio == base) return suyo
        if (suyo == base) return mio
        val o = base.split('\n')
        val a = mio.split('\n')
        val b = suyo.split('\n')
        // Una nota enorme no se compara línea a línea en el teléfono: gana entera la más reciente.
        if (o.size.toLong() * maxOf(a.size, b.size) > TOPE_DE_COMPARAR) {
            cuenta.choques++
            return if (ganaMio) mio else suyo
        }
        val ma = parejasLcs(o, a)
        val mb = parejasLcs(o, b)
        val salida = ArrayList<String>()
        var i = 0; var j = 0; var k = 0
        fun tramo(ao: List<String>, aa: List<String>, ab: List<String>) {
            when {
                aa == ao -> salida += ab
                ab == ao -> salida += aa
                aa == ab -> salida += aa
                else -> { cuenta.choques++; salida += if (ganaMio) aa else ab }
            }
        }
        while (true) {
            while (i < o.size && ma[i] == j && mb[i] == k) { salida += o[i]; i++; j++; k++ }
            if (i >= o.size && j >= a.size && k >= b.size) break
            var ancla = i
            while (ancla < o.size && !(ma[ancla] >= j && mb[ancla] >= k)) ancla++
            if (ancla >= o.size) {
                tramo(o.subList(i, o.size), a.subList(j, a.size), b.subList(k, b.size))
                break
            }
            tramo(o.subList(i, ancla), a.subList(j, ma[ancla]), b.subList(k, mb[ancla]))
            i = ancla; j = ma[ancla]; k = mb[ancla]
        }
        return salida.joinToString("\n")
    }

    private const val TOPE_DE_COMPARAR = 4_000_000L

    /** Para cada línea de [o], con qué línea de [x] se empareja en la subsecuencia común más larga (-1 si con ninguna). */
    private fun parejasLcs(o: List<String>, x: List<String>): IntArray {
        val n = o.size; val m = x.size
        val t = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0)
            t[i][j] = if (o[i] == x[j]) t[i + 1][j + 1] + 1 else maxOf(t[i + 1][j], t[i][j + 1])
        val r = IntArray(n) { -1 }
        var i = 0; var j = 0
        while (i < n && j < m) {
            when {
                o[i] == x[j] -> { r[i] = j; i++; j++ }
                t[i + 1][j] >= t[i][j + 1] -> i++
                else -> j++
            }
        }
        return r
    }

    // ------------------------------------------------------------------ parches

    /**
     * **Qué cambió de [antes] a [despues]**, para mandar solo eso. En una lista de figuras: las
     * nuevas enteras, las cambiadas solo en lo que cambió, las quitadas por su id, y el orden solo
     * si se movió algo. Null si son iguales.
     *
     * Formato: `{"v": valor}` sustituye; `{"o": {clave: parche}, "d": [claves quitadas]}` cambia un
     * objeto; `{"l": {"c": {id: parche}, "n": [figuras nuevas], "d": [ids], "i": [orden]}}` cambia una
     * lista con ids.
     */
    fun diferencia(antes: JsonElement?, despues: JsonElement?): JsonElement? {
        val a = nulo(antes); val d = nulo(despues)
        if (a == d) return null
        if (d == null) return JsonObject(mapOf("x" to JsonPrimitive(true)))
        if (a is JsonObject && d is JsonObject) {
            val cambios = LinkedHashMap<String, JsonElement>()
            for ((k, v) in d) diferencia(a[k], v)?.let { cambios[k] = it }
            val quitadas = a.keys.filter { it !in d }
            val p = LinkedHashMap<String, JsonElement>()
            if (cambios.isNotEmpty()) p["o"] = JsonObject(cambios)
            if (quitadas.isNotEmpty()) p["d"] = JsonArray(quitadas.map { JsonPrimitive(it) })
            return JsonObject(p)
        }
        if (a is JsonArray && d is JsonArray && a.isNotEmpty() && conIds(a) && conIds(d)) {
            val enA = a.associateBy { idDe(it)!! }
            val idsD = d.map { idDe(it)!! }
            val enD = idsD.toHashSet()
            val cambiadas = LinkedHashMap<String, JsonElement>()
            val nuevas = ArrayList<JsonElement>()
            for (e in d) {
                val id = idDe(e)!!
                val viejo = enA[id]
                if (viejo == null) nuevas += e else diferencia(viejo, e)?.let { cambiadas[id] = it }
            }
            val quitadas = a.map { idDe(it)!! }.filter { it !in enD }
            val l = LinkedHashMap<String, JsonElement>()
            if (cambiadas.isNotEmpty()) l["c"] = JsonObject(cambiadas)
            if (nuevas.isNotEmpty()) l["n"] = JsonArray(nuevas)
            if (quitadas.isNotEmpty()) l["d"] = JsonArray(quitadas.map { JsonPrimitive(it) })
            // El orden viaja solo si no es el que sale de quitar lo quitado y añadir lo nuevo al final.
            val supuesto = a.map { idDe(it)!! }.filter { it in enD } + nuevas.map { idDe(it)!! }
            if (supuesto != idsD) l["i"] = JsonArray(idsD.map { JsonPrimitive(it) })
            return JsonObject(mapOf("l" to JsonObject(l)))
        }
        return JsonObject(mapOf("v" to d))
    }

    /** Lo contrario de [diferencia]: [antes] con el [parche] puesto. */
    fun aplicar(antes: JsonElement?, parche: JsonElement?): JsonElement? {
        if (parche == null || parche is JsonNull) return antes
        val p = parche as? JsonObject ?: throw IllegalArgumentException("Parche mal formado")
        p["x"]?.let { return null }
        p["v"]?.let { return it }
        p["l"]?.let { l ->
            l as JsonObject
            val a = antes as? JsonArray ?: JsonArray(emptyList())
            val quitadas = (l["d"] as? JsonArray)?.map { (it as JsonPrimitive).content }?.toSet().orEmpty()
            val cambios = l["c"] as? JsonObject
            val porId = LinkedHashMap<String, JsonElement>()
            for (e in a) {
                val id = idDe(e) ?: continue
                if (id in quitadas) continue
                porId[id] = cambios?.get(id)?.let { aplicar(e, it) } ?: e
            }
            for (e in (l["n"] as? JsonArray).orEmpty()) porId[idDe(e) ?: continue] = e
            val orden = (l["i"] as? JsonArray)?.map { (it as JsonPrimitive).content } ?: porId.keys.toList()
            return JsonArray(orden.mapNotNull { porId[it] })
        }
        val a = antes as? JsonObject ?: JsonObject(emptyMap())
        val salida = LinkedHashMap<String, JsonElement>(a)
        (p["d"] as? JsonArray)?.forEach { salida.remove((it as JsonPrimitive).content) }
        (p["o"] as? JsonObject)?.forEach { (k, sub) ->
            val v = aplicar(a[k], sub)
            if (v == null) salida.remove(k) else salida[k] = v
        }
        return JsonObject(salida)
    }
}
