package com.forge.pixpin.guardados

import com.forge.pixpin.mini.Cabecera
import com.forge.pixpin.mini.Tarea
import com.forge.pixpin.mini.Tareas
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * **Los temas de una reunión** (29-oct-2026; porte de `crates/pixpin-proyecto/src/temas.rs` del PC,
 * `50e87978`, y de `docs/jev.md` §5; el usuario: «la persona crea una lista de TEMAS a tratar en la
 * reunión; durante la reunión el teléfono (o el PC) se queda escuchando y transcribiendo; eso se manda a
 * JEV y Jev marca qué temas ya se trataron»).
 *
 * Lo puro, sin pantalla ni red, con las mismas cuentas que el PC:
 *
 * - **Leer los temas** de lo escrito o pegado ([deTexto]: uno por línea, sin viñetas ni números) o de
 *   una lista de tareas del chat ([deDocumento]).
 * - **El formato** en la nota de voz, `temas` ([Mensaje.temas]), una lista en el orden de la agenda:
 *   `[{"id": "t1", "texto": "Presupuesto 2027", "hecho": true, "ms": 750000, "p": 0.86, "porJev": true,
 *   "aMano": false}, …]`. `id` es estable (`t1`, `t2`… en el orden en que se apuntaron; uno quitado no se
 *   reutiliza), `ms` el minuto en que se trató, `p` la probabilidad de Jev (dos decimales, solo si lo
 *   marcó él), `porJev` si lo marcó Jev y `aMano` si **la persona lo tocó** (marcado o desmarcado). Sin
 *   temas, la clave se quita: la nota sigue byte a byte como antes. `temasLista = {"proyecto", "id"}` es
 *   la lista de tareas del chat que lleva los mismos temas.
 * - **Lo hecho a mano manda** ([aMano], [porJev]): un tema tocado a mano no lo vuelve a tocar Jev, ni
 *   para marcarlo ni para desmarcarlo. Jev solo marca (nunca desmarca) y solo lo que sigue pendiente.
 *   **Ningún aparato deja que Jev cambie un tema que llegó con `aMano: true`.**
 * - **La lista de tareas** con los temas ([aDocumento]) y las marcas que vuelven de ella
 *   ([conLaLista], [marcasParaLaLista]).
 */
object TemasDeLaReunion {

    /** Cuántos temas como mucho: una agenda de más de cuarenta no es una agenda. */
    const val MAXIMO = 40
    /** Lo largo de un tema, en caracteres. */
    const val LARGO_MAXIMO = 200

    /** Un tema de la agenda. */
    data class Tema(
        val id: String,
        val texto: String,
        val hecho: Boolean = false,
        /** El milisegundo de la grabación en que se trató. */
        val ms: Long? = null,
        /** La probabilidad que dio Jev, si lo marcó él. */
        val p: Double? = null,
        /** Lo marcó Jev. */
        val porJev: Boolean = false,
        /** Lo tocó la persona: Jev ya no lo cambia. */
        val aMano: Boolean = false
    ) {
        /** Si Jev aún tiene que mirar este tema: pendiente y sin tocar a mano. */
        fun paraJev(): Boolean = !hecho && !aMano
    }

    private val VINETAS = setOf('•', '·', '–', '—', '▪', '◦', '○', '●')

    /** Un tema limpio: en una línea, sin viñeta, casilla ni número delante, y recortado. Vacío si no queda nada. */
    fun limpio(linea: String): String {
        var t = linea.trim()
        // Las viñetas que no son de Markdown pero llegan al pegar de Word o de un correo.
        while (t.isNotEmpty() && t[0] in VINETAS) t = t.substring(1).trimStart()
        val s = Tareas.saneado(t)
        val visible = Tareas.partir(s).first
        val r = visible.trim().trimEnd(':', ';', ',').trim()
        return if (r.codePointCount(0, r.length) > LARGO_MAXIMO) r.substring(0, r.offsetByCodePoints(0, LARGO_MAXIMO)) + "…" else r
    }

    /** La forma de comparar dos temas: sin mayúsculas ni blancos de más. */
    internal fun clave(texto: String): String = texto.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ").lowercase()

    /**
     * **Los temas de lo escrito o pegado**: uno por línea, sin las viñetas, las casillas (`- [ ]`) ni los
     * números (`1.`, `2)`) de delante. Se saltan las líneas vacías, los títulos de Markdown (`# Agenda`)
     * y los repetidos; como mucho [MAXIMO].
     */
    fun deTexto(texto: String): List<String> {
        val v = ArrayList<String>()
        for (linea in texto.lines()) {
            if (Cabecera.titulo(linea.trim()).isNotEmpty()) continue
            val t = limpio(linea)
            if (t.isEmpty() || v.any { clave(it) == clave(t) }) continue
            v += t
            if (v.size == MAXIMO) break
        }
        return v
    }

    /** **Los temas de una lista de tareas** del chat: su texto sin la fecha ni las imágenes, y si ya estaba hecha. */
    fun deDocumento(documento: String): List<Pair<String, Boolean>> {
        val v = ArrayList<Pair<String, Boolean>>()
        for (t in Tareas.leer(documento)) {
            val texto = limpio(Tareas.imagenes(Tareas.partir(t.texto).first).first)
            if (texto.isEmpty() || v.any { clave(it.first) == clave(texto) }) continue
            v += texto to t.hecha
            if (v.size == MAXIMO) break
        }
        return v
    }

    /** El siguiente `id` libre: `t` y uno más que el mayor que haya. */
    private fun siguienteId(temas: List<Tema>): String {
        val mayor = temas.mapNotNull { it.id.removePrefix("t").takeIf { r -> r != it.id }?.toIntOrNull() }.maxOrNull() ?: 0
        return "t${mayor + 1}"
    }

    /** **Apunta un tema** al final. Devuelve su `id`, o null si está vacío, repetido o ya hay [MAXIMO]. */
    fun anadir(temas: MutableList<Tema>, texto: String): String? {
        val t = limpio(texto)
        if (t.isEmpty() || temas.size >= MAXIMO || temas.any { clave(it.texto) == clave(t) }) return null
        val id = siguienteId(temas)
        temas += Tema(id, t)
        return id
    }

    /** Apunta varios (lo pegado, una lista): devuelve cuántos entraron. */
    fun anadirVarios(temas: MutableList<Tema>, textos: List<String>): Int = textos.count { anadir(temas, it) != null }

    /** Quita el tema [id]. Devuelve si estaba. */
    fun quitar(temas: MutableList<Tema>, id: String): Boolean = temas.removeAll { it.id == id }

    /**
     * **La persona marca o desmarca** el tema [id] (grabando o al estudiar). Manda siempre: queda `aMano`
     * y Jev ya no lo toca. Al marcar se apunta [ms] si se sabe; al desmarcar se olvidan el minuto y la
     * `p`. Devuelve si cambió algo.
     */
    fun aMano(temas: MutableList<Tema>, id: String, hecho: Boolean, ms: Long?): Boolean {
        val i = temas.indexOfFirst { it.id == id }
        if (i < 0) return false
        val antes = temas[i]
        val nuevo = antes.copy(
            aMano = true, porJev = false, p = null, hecho = hecho,
            ms = if (hecho) ms?.coerceAtLeast(0) ?: antes.ms else null
        )
        temas[i] = nuevo
        return nuevo != antes
    }

    /** **Jev marca el tema [id] como tratado** en [ms] con probabilidad [p]. Solo si sigue pendiente y nadie lo tocó a mano. */
    fun porJev(temas: MutableList<Tema>, id: String, ms: Long, p: Double): Boolean {
        val i = temas.indexOfFirst { it.id == id }
        if (i < 0 || !temas[i].paraJev()) return false
        temas[i] = temas[i].copy(hecho = true, porJev = true, ms = ms.coerceAtLeast(0), p = p.coerceIn(0.0, 1.0))
        return true
    }

    /** Los que Jev aún tiene que mirar, en orden. */
    fun paraJev(temas: List<Tema>): List<Tema> = temas.filter { it.paraJev() }

    /** (tratados, total). */
    fun cuenta(temas: List<Tema>): Pair<Int, Int> = temas.count { it.hecho } to temas.size

    /** Los que quedaron sin tratar, en orden: lo que se ofrece pasar a la próxima reunión. */
    fun sinTratar(temas: List<Tema>): List<Tema> = temas.filter { !it.hecho }

    /** Dos decimales, como `p` de las partes importantes. */
    private fun dosDecimales(p: Double): Double = Math.round(p.coerceIn(0.0, 1.0) * 100.0) / 100.0

    private fun JsonElement?.cadena(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonElement?.entero(): Long? = (this as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull }?.longOrNull
    private fun JsonElement?.numero(): Double? = (this as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull }?.doubleOrNull
    private fun JsonElement?.si(): Boolean = (this as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true

    /** **Los temas de una nota**, en el orden de la agenda. Vacío si no tiene; lo que no se entiende (sin `id` o sin texto) se salta. */
    fun temasDe(a: JsonArray?): List<Tema> {
        if (a == null) return emptyList()
        val v = ArrayList<Tema>()
        for (x in a) {
            val o = x as? JsonObject ?: continue
            val id = o["id"].cadena() ?: continue
            val texto = o["texto"].cadena() ?: continue
            if (id.isBlank() || texto.isBlank() || v.any { it.id == id }) continue
            v += Tema(
                id = id, texto = texto.trim(), hecho = o["hecho"].si(),
                ms = o["ms"].entero()?.coerceAtLeast(0), p = o["p"].numero()?.coerceIn(0.0, 1.0),
                porJev = o["porJev"].si(), aMano = o["aMano"].si()
            )
        }
        return v
    }

    fun temasDe(m: Mensaje?): List<Tema> = temasDe(m?.temas)

    /** **Los temas para la nota**. Sin temas, null (la clave se quita). */
    fun aJson(temas: List<Tema>): JsonArray? {
        if (temas.isEmpty()) return null
        return JsonArray(temas.map { t ->
            val o = LinkedHashMap<String, JsonElement>()
            o["id"] = JsonPrimitive(t.id)
            o["texto"] = JsonPrimitive(t.texto)
            o["hecho"] = JsonPrimitive(t.hecho)
            t.ms?.let { o["ms"] = JsonPrimitive(it.coerceAtLeast(0)) }
            t.p?.let { o["p"] = JsonPrimitive(dosDecimales(it)) }
            o["porJev"] = JsonPrimitive(t.porJev)
            o["aMano"] = JsonPrimitive(t.aMano)
            JsonObject(o)
        })
    }

    /** La lista de tareas enlazada, (proyecto, id del mensaje). El proyecto viaja como el del PC (`general` es «Mensajes guardados»). */
    fun listaDe(o: JsonObject?): Pair<String, String>? {
        val p = o?.get("proyecto").cadena() ?: return null
        val id = o?.get("id").cadena() ?: return null
        return p to id
    }

    /** `temasLista` para la nota. */
    fun listaEnJson(proyecto: String, id: String): JsonObject =
        JsonObject(linkedMapOf("proyecto" to JsonPrimitive(proyecto), "id" to JsonPrimitive(id)))

    /** El proyecto de la nota tal como va en `temasLista` (null, el chat general: `general`). */
    fun proyectoQueViaja(proyecto: String?): String = proyecto ?: com.forge.pixpin.sincro.Disco.GENERAL

    /** Y al revés: el de este aparato. */
    fun proyectoDeAqui(viaja: String): String? = viaja.takeIf { it != com.forge.pixpin.sincro.Disco.GENERAL && it.isNotEmpty() }

    /** **El documento de la lista de tareas** con los temas: `# titulo` y una casilla por tema (`- [x]` lo tratado). */
    fun aDocumento(titulo: String, temas: List<Tema>): String = Tareas.escribir(titulo, temas.map { Tarea(it.texto, it.hecho) })

    /**
     * **Lo marcado en la lista de tareas mientras tanto** (en Tareas, aquí o en el PC) entra en los temas
     * como hecho a mano. Lo desmarcado allí no desmarca un tema que marcó Jev (solo se lee lo marcado: la
     * lista puede ir por detrás). Devuelve cuántos cambiaron.
     */
    fun conLaLista(temas: MutableList<Tema>, documento: String): Int {
        val hechas = deDocumento(documento).filter { it.second }.map { clave(it.first) }
        var n = 0
        for (i in temas.indices) {
            val t = temas[i]
            if (!t.hecho && clave(t.texto) in hechas) {
                temas[i] = t.copy(hecho = true, aMano = true, porJev = false, p = null)
                n++
            }
        }
        return n
    }

    /** **Qué casillas de la lista hay que cambiar** para que diga lo mismo que los temas: (número de la tarea, hecha). Solo se marcan. */
    fun marcasParaLaLista(temas: List<Tema>, documento: String): List<Pair<Int, Boolean>> {
        val hechos = temas.filter { it.hecho }.map { clave(it.texto) }
        return Tareas.leer(documento).withIndex()
            .filter { (_, t) -> !t.hecha && clave(limpio(Tareas.imagenes(Tareas.partir(t.texto).first).first)) in hechos }
            .map { (i, _) -> i to true }
    }
}
