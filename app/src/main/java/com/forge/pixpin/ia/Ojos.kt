package com.forge.pixpin.ia

import com.forge.pixpin.motor.Element
import com.forge.pixpin.motor.Scene
import com.forge.pixpin.motor.getElementBounds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * **Los ojos del asistente sobre el lienzo** (PC `pixpin-ia/src/ojos.rs`, 15-oct-2026): «dale los ojos
 * al canvas: que puedan ver el canvas, para revisar y corregir sus anotaciones».
 *
 * - La herramienta [NOMBRE] (`ver_lienzo`): una [Mirada] con su zona. La contesta el ejecutor
 *   pintando con **el mismo Renderer del lienzo** una imagen de [LADO_MAXIMO] px como mucho, con una
 *   rejilla numerada en las coordenadas del modelo y una etiqueta `#n` en cada elemento; la imagen
 *   va al modelo en un mensaje del usuario detrás de los resultados ([mensajeDeVista]).
 * - **La revisión**: tras un turno que dibujó o editó, el agente le enseña al modelo el resultado y
 *   le deja corregir; si contesta [TODO_BIEN], no se dice nada más.
 * - **Mirar al empezar** ([pideMirar]): «corrige», «alinea», «¿qué falta?» dependen de lo que se ve.
 *
 * Solo con un motor que ve imágenes (la nube; el local no, como en el PC).
 */
object Ojos {
    const val NOMBRE = "ver_lienzo"
    /** Con qué empieza el mensaje que lleva la vista: el agente no lo toma por el pedido. */
    const val MARCA = "[Vista del lienzo]"
    const val LADO_MAXIMO = 1024
    const val AUMENTO_MAXIMO = 2.0
    const val MAX_ETIQUETAS = 40
    const val MARGEN = 30.0
    const val TODO_BIEN = "TODO_BIEN"
    const val VISTAS_POR_TURNO = 3
    const val REVISIONES_MAXIMAS = 2

    sealed class Zona {
        data object Todo : Zona()
        data class Caja(val c: DoubleArray) : Zona() {
            override fun equals(other: Any?) = other is Caja && c.contentEquals(other.c)
            override fun hashCode() = c.contentHashCode()
        }
        data object Elegido : Zona()
        data object Ultimo : Zona()
        data class Elementos(val que: String) : Zona()
        /** Lo que tocó este turno, con lo de alrededor (la revisión). */
        data object Turno : Zona()
        data object Documento : Zona()
    }

    data class Mirada(val zona: Zona = Zona.Todo, val marcas: Boolean = true)

    /** Lo que devuelve el ejecutor: la imagen y su leyenda. */
    class Vista(val png: ByteArray, val ancho: Int, val alto: Int, val texto: String)

    class Fallo(m: String) : Exception(m)

    /** **La herramienta** para el catálogo del turno (la misma descripción que el PC). */
    fun herramienta() = Herramienta(
        NOMBRE,
        "Mira el lienzo: devuelve una imagen de lo que hay (o de una zona), con una rejilla numerada en tus coordenadas " +
            "y la etiqueta #id de cada elemento. Usala para comprobar lo dibujado o anotado (textos cortados o encima de otros, " +
            "flechas que no apuntan, cosas fuera de sitio) y corregirlo con las herramientas de editar, o para entender un " +
            "lienzo antes de cambiarlo. «documento»: la hoja del PDF o la foto que acabas de anotar.",
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("zona") {
                    put("type", "string")
                    putJsonArray("enum") { listOf("todo", "caja", "elegido", "ultimo", "elementos", "documento").forEach { add(JsonPrimitive(it)) } }
                    put("description", "que mirar (omitir: todo)")
                }
                putJsonObject("caja") { put("type", "array"); putJsonObject("items") { put("type", "number") }; put("maxItems", 4); put("description", "con zona=caja: [x, y, ancho, alto]") }
                putJsonObject("que") { put("type", "string"); put("description", "con zona=elementos: «#12», «el circulo rojo»...") }
            }
        }
    )

    /** **Lee los argumentos** del modelo: lo que falte es «todo»; solo es error lo que no se entiende. */
    fun leer(argumentos: String): Mirada {
        val v = if (argumentos.isBlank()) JsonObject(emptyMap())
        else runCatching { Json.parseToJsonElement(argumentos) as JsonObject }.getOrNull() ?: throw Fallo("los argumentos de ver_lienzo no son JSON")
        fun texto(k: String) = (v[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
        val caja = (v["caja"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }
        val zona = when (texto("zona")?.let(Acciones::normalizar)) {
            null, "todo", "todo el lienzo" -> when {
                caja != null && caja.size == 4 -> Zona.Caja(caja.toDoubleArray())
                texto("que") != null -> Zona.Elementos(texto("que")!!)
                else -> Zona.Todo
            }
            "caja" -> if (caja != null && caja.size == 4 && caja[2] > 0 && caja[3] > 0) Zona.Caja(caja.toDoubleArray())
            else throw Fallo("con zona=caja hace falta «caja»: [x, y, ancho, alto] con ancho y alto positivos")
            "elegido", "seleccion" -> Zona.Elegido
            "ultimo", "lo ultimo" -> Zona.Ultimo
            "elementos", "elemento" -> Zona.Elementos(texto("que") ?: throw Fallo("con zona=elementos hace falta «que» (#id o descripcion)"))
            "documento", "pagina", "foto", "plano" -> Zona.Documento
            else -> throw Fallo("zona «${texto("zona")}» no existe: usa todo, caja, elegido, ultimo, elementos o documento")
        }
        return Mirada(zona)
    }

    /** Una caja `[x0, y0, x1, y1]`. */
    data class Caja(val x0: Double, val y0: Double, val x1: Double, val y1: Double) {
        val ancho get() = x1 - x0
        val alto get() = y1 - y0
    }

    private fun unir(es: List<Element>): Caja? {
        if (es.isEmpty()) return null
        val b = es.map(::getElementBounds).filter { it.x1.isFinite() && it.y1.isFinite() && it.x2.isFinite() && it.y2.isFinite() }
        if (b.isEmpty()) return null
        return Caja(b.minOf { it.x1 }, b.minOf { it.y1 }, b.maxOf { it.x2 }, b.maxOf { it.y2 })
    }

    /**
     * **La caja que se mira** en la escena, con [MARGEN] (PC `caja_de`). [Zona.Documento] no es de la
     * escena: la resuelve quien llama.
     */
    fun cajaDe(escena: Scene, m: Mirada, ctx: Edicion.Contexto, num: Edicion.Numeracion): Caja {
        val vivos = escena.elements.filter { !it.isDeleted }
        fun deIds(ids: List<String>) = unir(vivos.filter { it.id in ids })
        val c: Caja = when (val z = m.zona) {
            Zona.Todo, Zona.Documento -> unir(vivos) ?: throw Fallo("el lienzo esta vacio: no hay nada que ver")
            is Zona.Caja -> {
                val x0 = z.c[0] + ctx.marco.x; val y0 = z.c[1] + ctx.marco.y
                return Caja(x0, y0, x0 + max(kotlin.math.abs(z.c[2]), 1.0), y0 + max(kotlin.math.abs(z.c[3]), 1.0))
            }
            Zona.Elegido -> deIds(ctx.elegidos) ?: throw Fallo("el usuario no tiene nada elegido en el lienzo")
            Zona.Ultimo -> deIds(ctx.ultimos) ?: throw Fallo("aun no has dibujado ni tocado nada en este turno")
            Zona.Turno -> {
                val c = deIds(ctx.ultimos) ?: throw Fallo("aun no has dibujado ni tocado nada en este turno")
                // Si todo el lienzo no es mucho mayor, todo: lo nuevo suele ir con lo que ya había.
                val todo = unir(vivos) ?: c
                if (max(todo.ancho, todo.alto) <= 1600 || (todo.ancho <= 3 * max(c.ancho, 200.0) && todo.alto <= 3 * max(c.alto, 200.0))) todo
                else { val aire = 0.25 * max(max(c.ancho, c.alto), 80.0); Caja(c.x0 - aire, c.y0 - aire, c.x1 + aire, c.y1 + aire) }
            }
            is Zona.Elementos -> {
                val e = try { Edicion.resolver(escena, z.que, ctx, num) } catch (x: Edicion.Fallo) { throw Fallo(x.message ?: "no encaja") }
                val c = deIds(e.ids) ?: throw Fallo("no hay nada que encaje con «${z.que}»")
                val aire = 0.5 * max(max(c.ancho, c.alto), 60.0)
                Caja(c.x0 - aire, c.y0 - aire, c.x1 + aire, c.y1 + aire)
            }
        }
        return Caja(c.x0 - MARGEN, c.y0 - MARGEN, c.x1 + MARGEN, c.y1 + MARGEN)
    }

    /** **El tamaño de la imagen y su escala** (PC `tamano`): el lado mayor a [lado] como mucho, sin ampliar de más. */
    fun tamano(c: Caja, lado: Int = LADO_MAXIMO): Triple<Int, Int, Double> {
        val w = max(c.ancho, 1.0); val h = max(c.alto, 1.0)
        val escala = min(lado / max(w, h), AUMENTO_MAXIMO)
        return Triple(max((w * escala).roundToInt(), 16), max((h * escala).roundToInt(), 16), escala)
    }

    /** **El paso de la rejilla**: un número redondo que deje entre 4 y 10 rayas en el lado mayor. */
    fun pasoDeRejilla(c: Caja): Double {
        val lado = max(max(c.ancho, c.alto), 1.0)
        val crudo = lado / 8.0
        val potencia = 10.0.pow(floor(log10(crudo)))
        for (f in listOf(1.0, 2.0, 2.5, 5.0, 10.0)) if (f * potencia >= crudo) return f * potencia
        return 10.0 * potencia
    }

    /** **Dónde va cada etiqueta `#n`** (los de encima primero, hasta [MAX_ETIQUETAS]). */
    fun etiquetas(escena: Scene, c: Caja, num: Edicion.Numeracion): List<Pair<Int, Pair<Double, Double>>> {
        val vivos = escena.elements.filter { !it.isDeleted && !it.reference }.filter {
            val b = getElementBounds(it); b.x2 >= c.x0 && b.x1 <= c.x1 && b.y2 >= c.y0 && b.y1 <= c.y1
        }
        return vivos.drop(max(vivos.size - MAX_ETIQUETAS, 0)).map { e ->
            val b = getElementBounds(e)
            num.numero(e.id) to (max(b.x1, c.x0) to max(b.y1, c.y0))
        }
    }

    /** **La leyenda de la imagen**: qué zona es (en las coordenadas del modelo), la escala y cómo leer la rejilla. */
    fun leyenda(c: Caja, marco: com.forge.pixpin.motor.Pt, ancho: Int, alto: Int, escala: Double, marcas: Boolean): String {
        fun r(v: Double) = Math.round(v)
        val sb = StringBuilder("$MARCA Zona [${r(c.x0 - marco.x)}, ${r(c.y0 - marco.y)}, ${r(c.ancho)}, ${r(c.alto)}] en tus coordenadas, imagen de ${ancho}x$alto px (1 unidad = ${"%.2f".format(java.util.Locale.ROOT, escala)} px).")
        if (marcas) sb.append(" Rejilla gris cada ${r(pasoDeRejilla(c))} unidades con sus numeros en los bordes; cada elemento lleva su #id en una etiqueta amarilla arriba a la izquierda (las etiquetas y la rejilla no son del dibujo).")
        return sb.toString()
    }

    /** **El mensaje que lleva la vista** al modelo. */
    fun mensajeDeVista(v: Vista, extra: String = ""): MensajeIa =
        MensajeIa.usuarioConImagen(if (extra.isEmpty()) v.texto else "${v.texto}\n$extra", v.png, "image/png")

    fun esVista(m: MensajeIa) = m.textoEntero.trimStart().startsWith(MARCA)

    /** **Lo que se le pide en la revisión**, con el pedido original. */
    fun pedidoDeRevision(pedido: String, ronda: Int): String =
        "Revision $ronda: esta es la imagen de como quedo el lienzo tras tus cambios. Comparala con lo que pidio el usuario: «${pedido.take(600).trim()}». " +
            "Busca solo fallos que se vean: texto cortado, encima de otra cosa o fuera de su figura; flechas o lineas que no llegan a lo que senalan; " +
            "cosas solapadas, fuera de sitio o desalineadas; una anotacion que no cae sobre lo senalado; algo pedido que falta. " +
            "Si esta bien, contesta solo $TODO_BIEN. Si no, corrigelo ya con las herramientas de editar (usa los #id de las etiquetas y las " +
            "coordenadas de la rejilla; mueve o cambia, no lo vuelvas a dibujar entero) y luego di en una frase corta que corregiste."

    fun esTodoBien(texto: String): Boolean {
        val t = Acciones.normalizar(texto.trim())
        return t.isEmpty() || t.contains(TODO_BIEN.lowercase()) || t.contains("todo_bien") || t.trim { !it.isLetterOrDigit() } == "ok"
    }

    private val CLAVES_MIRAR = listOf(
        "corrige", "corregir", "arregla", "mejora", "mejorar", "que falta", "falta algo", "alinea", "alinear", "ordena",
        "solap", "se tapa", "encima de", "revisa", "mira el lienzo", "mira lo", "como quedo", "que hay en el lienzo",
        "que ves", "acomoda", "reorganiza", "centra", "etiqueta a cada", "etiquetas a", "rotula", "ponle nombre",
        "nombra cada", "a cada figura", "a cada uno", "cada elemento", "distribuye", "espacia", "endereza"
    )

    /** **Si el pedido depende de lo que se ve** del lienzo: entonces la vista va pegada al pedido. */
    fun pideMirar(pedido: String): Boolean = Acciones.normalizar(pedido).let { q -> CLAVES_MIRAR.any { q.contains(it) } }

    /** **Si una llamada cambió el lienzo** (lo que merece una revisión): dibujar o editar (menos elegir). */
    fun cambiaLoQueSeVe(l: Llamada): Boolean = l.nombre in Herramientas.DIBUJAN || l is Llamada.Geometria || l is Llamada.Calcar ||
        (l is Llamada.Editar && l.pedido.herramienta != Edicion.SELECCIONAR)
}
