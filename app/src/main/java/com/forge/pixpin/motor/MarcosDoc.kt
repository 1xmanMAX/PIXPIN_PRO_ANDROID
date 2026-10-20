package com.forge.pixpin.motor

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/**
 * **Los marcos numerados de los documentos** (20-oct-2026, puerto de `marcos_doc.rs` del PC; manual
 * `docs/sincronizar-marcos-vistas-y-tinta.md` de ese repo). La misma lógica que los «Marcos para
 * imprimir» del plano, para que en todos los visores se usen igual:
 *
 * - El botón del marco del riel entra y sale del modo; pulsado, azul.
 * - En el modo, **arrastrar** pone un marco (más de 8 px de ancho y de alto en la pantalla; menos es
 *   un toque y no cuenta). Va al final: su número es su sitio (1, 2, 3…) y al quitar uno los demás
 *   se renumeran solos.
 * - Quitar el último; «Borrar los marcos», todos.
 * - Imprimir todos en grupo: una hoja por marco, en su orden.
 *
 * Los marcos van **en las coordenadas del documento** de cada visor (la hoja de un PDF a 1400 de
 * ancho, el píxel de una hoja de cálculo al 100 %, el píxel CSS de una página…): se mueven con él,
 * no con la pantalla.
 *
 * **Los de un adjunto del chat viajan**: `anot-<uid>.marcos.json` (lista JSON de
 * `{"caja":[x0,y0,x1,y1],"parte":0,"vista":"…"}`; el orden es el número). Vacíos quedan `[]`,
 * nunca borrados: un fichero borrado volvería del otro aparato (no hay lápidas de ficheros). Se
 * escriben por un temporal y solo si cambian (cada fecha nueva es un envío).
 *
 * Sin Android: se prueba en la JVM con los JSON del PC.
 */
data class MarcoDoc(val caja: List<Double>, val parte: Int = 0, val vista: String = "") {
    val x0 get() = caja[0]
    val y0 get() = caja[1]
    val x1 get() = caja[2]
    val y1 get() = caja[3]
    val ancho get() = x1 - x0
    val alto get() = y1 - y0
    val centro get() = doubleArrayOf((x0 + x1) / 2, (y0 + y1) / 2)
}

/**
 * **Dónde se guarda algo de un documento** y si viaja: el de un adjunto del chat (`anot-<uid>…`) no
 * se borra nunca (vacío se escribe vacío); el de un documento suelto (o de una sesión) sí.
 */
class GuardadoDoc(val archivo: File, val viaja: Boolean) {
    fun leer(): String? = runCatching { if (archivo.isFile) archivo.readText() else null }.getOrNull()

    /**
     * Escribe [texto] por un temporal, y solo si cambia. [vacio]: no dice nada; si no viaja se quita
     * el archivo, y si viaja se escribe tal cual solo si ya estaba (abrir sin marcos no crea nada).
     */
    fun escribir(texto: String, vacio: Boolean) {
        if (vacio && (!viaja || !archivo.exists())) {
            if (!viaja) archivo.delete()
            return
        }
        if (leer() == texto) return
        runCatching {
            archivo.parentFile?.mkdirs()
            val tmp = File(archivo.parentFile, archivo.name + ".tmp")
            tmp.writeText(texto)
            if (!tmp.renameTo(archivo)) { tmp.copyTo(archivo, overwrite = true); tmp.delete() }
        }
    }
}

/** Los marcos de un documento y el modo de ponerlos. [guardado] null: solo esta sesión. */
class MarcosDoc(val guardado: GuardadoDoc? = null, lista: List<MarcoDoc> = emptyList()) {
    var lista: List<MarcoDoc> = lista
        private set
    /** Si se están poniendo (el botón del riel pulsado). */
    var activo = false
        private set
    /** El que se está arrastrando: sus dos esquinas en el documento y su parte. */
    var nuevo: Triple<DoubleArray, DoubleArray, Int>? = null
        private set

    fun alternar() { activo = !activo; nuevo = null }
    fun poner(activo: Boolean) { this.activo = activo; nuevo = null }

    fun arrastrando() = nuevo != null

    /** Empieza un marco en [p] (coordenadas del documento) si el modo está puesto. */
    fun pulsar(p: DoubleArray, parte: Int = 0): Boolean {
        if (!activo) return false
        nuevo = Triple(p, p, parte)
        return true
    }

    fun mover(p: DoubleArray): Boolean {
        val n = nuevo ?: return false
        nuevo = Triple(n.first, p, n.third)
        return true
    }

    /** La caja del que se arrastra, ordenada; null si no hay. */
    fun cajaNueva(): MarcoDoc? = nuevo?.let { (a, b, parte) -> MarcoDoc(ordenada(a, b), parte) }

    /**
     * Soltar: el marco queda si mide más de 8 px de ancho y de alto en la pantalla ([px] = píxeles
     * de pantalla por unidad del documento). Devuelve si se añadió (y entonces se guardó).
     */
    fun soltar(p: DoubleArray, px: Double): Boolean {
        val (a, _, parte) = nuevo ?: return false
        nuevo = null
        val caja = ordenada(a, p)
        val k = px.coerceAtLeast(1e-9)
        if ((caja[2] - caja[0]) * k <= MINIMO_PX || (caja[3] - caja[1]) * k <= MINIMO_PX) return false
        lista = lista + MarcoDoc(caja, parte)
        guardar()
        return true
    }

    fun cancelar() { nuevo = null }

    fun quitarUltimo(): Boolean {
        if (lista.isEmpty()) return false
        lista = lista.dropLast(1)
        guardar()
        return true
    }

    fun quitar(i: Int): Boolean {
        if (i !in lista.indices) return false
        lista = lista.filterIndexed { j, _ -> j != i }
        guardar()
        return true
    }

    fun borrar() { lista = emptyList(); guardar() }

    /** Pone la lista entera (la que llega de fuera, p. ej. al volver a leer). No guarda. */
    fun recargar() { guardado?.let { lista = deTexto(it.leer()) } }

    fun guardar() { guardado?.escribir(aTexto(lista), lista.isEmpty()) }

    companion object {
        /** Lo mínimo que tiene que medir un marco en la pantalla, de ancho y de alto. */
        const val MINIMO_PX = 8.0
        /** El azul de los marcos del plano. */
        const val AZUL = 0xFF0078D4.toInt()
        /** El azul de un botón pulsado en barras y riel. */
        const val AZUL_PULSADO = 0xFF0060DF.toInt()

        private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

        fun de(guardado: GuardadoDoc?): MarcosDoc = MarcosDoc(guardado, deTexto(guardado?.leer()))

        private fun ordenada(a: DoubleArray, b: DoubleArray) =
            listOf(minOf(a[0], b[0]), minOf(a[1], b[1]), maxOf(a[0], b[0]), maxOf(a[1], b[1]))

        /** Lee la lista; lo que no se entiende se salta (nunca tira el resto). */
        fun deTexto(t: String?): List<MarcoDoc> {
            if (t.isNullOrBlank()) return emptyList()
            val arr = runCatching { JSON.parseToJsonElement(t).jsonArray }.getOrNull() ?: return emptyList()
            return arr.mapNotNull { e ->
                runCatching {
                    val o = e.jsonObject
                    val c = o["caja"]!!.jsonArray.map { it.jsonPrimitive.doubleOrNull!! }
                    if (c.size != 4 || c.any { !it.isFinite() }) return@runCatching null
                    MarcoDoc(
                        listOf(minOf(c[0], c[2]), minOf(c[1], c[3]), maxOf(c[0], c[2]), maxOf(c[1], c[3])),
                        o["parte"]?.jsonPrimitive?.intOrNull ?: 0,
                        (o["vista"] as? JsonPrimitive)?.contentOrNull ?: ""
                    )
                }.getOrNull()
            }
        }

        /** Como el PC (`serde_json::to_string_pretty`): `[]` vacío; números con punto. */
        fun aTexto(lista: List<MarcoDoc>): String {
            if (lista.isEmpty()) return "[]"
            val arr = buildJsonArray {
                for (m in lista) add(buildJsonObject {
                    put("caja", buildJsonArray { m.caja.forEach { add(JsonPrimitive(it)) } })
                    put("parte", m.parte)
                    if (m.vista.isNotEmpty()) put("vista", m.vista)
                })
            }
            return Json { prettyPrint = true }.encodeToString(JsonArray.serializer(), arr)
        }

        /**
         * **Cuánto acercarse para que el marco llene la ventana** (con un poco de aire): píxeles de
         * pantalla por unidad del documento.
         */
        fun pxParaLlenar(m: MarcoDoc, ancho: Double, alto: Double): Double {
            val aire = 0.94
            return minOf(ancho * aire / m.ancho.coerceAtLeast(1e-9), alto * aire / m.alto.coerceAtLeast(1e-9))
        }

        /** La pista de abajo con el modo puesto. */
        const val PISTA = "Arrastra para poner un marco · toca un número para ir"
    }
}

/**
 * **Las vistas de los marcadores** de un documento (`anot-<uid>.vistas.json`): por cada marcador (su
 * id), lo que hace falta para volver justo ahí además de su sitio. En los documentos, `"zoom x y"`
 * (el zoom y la esquina de arriba a la izquierda en unidades del documento). El marcador sigue en su
 * `.marcas`; uno sin vista vuelve solo a su sitio, como antes. Vacío: `{}`.
 */
class VistasDoc(val guardado: GuardadoDoc? = null, de: Map<String, String> = emptyMap()) {
    private val mapa = java.util.TreeMap(de)
    val de: Map<String, String> get() = mapa

    fun poner(id: String, vista: String) { mapa[id] = vista; guardar() }
    fun quitar(id: String) { if (mapa.remove(id) != null) guardar() }
    fun deMarca(id: String): String? = mapa[id]

    /** Quita las vistas de marcadores que ya no existen. */
    fun soloDe(ids: Set<String>) {
        if (mapa.keys.removeAll { it !in ids }) guardar()
    }

    fun guardar() { guardado?.escribir(aTexto(mapa), mapa.isEmpty()) }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

        fun de(guardado: GuardadoDoc?): VistasDoc = VistasDoc(guardado, deTexto(guardado?.leer()))

        fun deTexto(t: String?): Map<String, String> {
            if (t.isNullOrBlank()) return emptyMap()
            val o = runCatching { JSON.parseToJsonElement(t) as? JsonObject }.getOrNull() ?: return emptyMap()
            return o.entries.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { k to it } }.toMap()
        }

        fun aTexto(m: Map<String, String>): String {
            if (m.isEmpty()) return "{}"
            val o = buildJsonObject { for ((k, v) in java.util.TreeMap(m)) put(k, v) }
            return Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), o)
        }

        /** Una vista sencilla «zoom x y» a texto (como `zoom_a_texto` del PC: sin ceros de cola). */
        fun zoomATexto(zoom: Double, x: Double, y: Double) = "${num(zoom)} ${num(x)} ${num(y)}"

        fun zoomDeTexto(t: String?): Triple<Double, Double, Double>? {
            val v = t?.trim()?.split(Regex("\\s+"))?.map { it.toDoubleOrNull() ?: return null } ?: return null
            if (v.size < 3 || v.take(3).any { !it.isFinite() }) return null
            return Triple(v[0], v[1], v[2])
        }

        /** Como el `{}` de Rust para un f64: `1.75`, `120`, `3400.5`. */
        private fun num(v: Double): String =
            if (v == kotlin.math.floor(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString()
    }
}
