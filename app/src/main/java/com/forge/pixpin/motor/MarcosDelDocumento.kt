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
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/**
 * **Los marcos numerados de los documentos** (24-oct-2026, porte de `apps/pixpin/src/marcos_doc.rs`
 * del PC, 19/20-oct). El usuario: «el frame se sujeta en la zona en la que se le pone y cuando se
 * mueve no tiene lag», y que esté en todos los formatos, en el riel de la derecha.
 *
 * La misma lógica que los «Marcos para imprimir» del plano:
 *
 * - El botón del marco del riel entra y sale del modo; puesto, azul.
 * - En el modo, **arrastrar** pone un marco (más de 8 px de ancho y de alto en la pantalla; menos
 *   es un toque y no cuenta). Va al final: su número es su sitio (1, 2, 3…), y al quitar uno los
 *   demás se renumeran solos.
 * - «Quitar el último» (el Supr del PC), «Borrar los marcos», e «Imprimir»: todos en grupo, una
 *   hoja por marco y en su orden.
 *
 * Los marcos van **en las coordenadas del documento** de cada visor (la hoja de un PDF, el píxel
 * CSS de una página, el de una hoja de cálculo al 100 %, el de una imagen sin girar): se pintan
 * con el documento, no con la pantalla, así que no se quedan atrás al moverlo.
 *
 * **Viajan**: `anot-<base>.marcos.json` (`[{"caja":[x0,y0,x1,y1],"parte":0}]`, el `Marco` del PC
 * con serde) y las vistas de los marcadores en `anot-<base>.vistas.json` (`{"<id>":"zoom x y"}`).
 * Vacíos se escriben `[]` y `{}`, nunca se borran: un archivo borrado volvería del otro aparato.
 * Se escriben por un temporal y solo si cambian. Ver `docs/sincronizar-marcos-vistas-y-tinta.md`
 * del PC.
 *
 * Sin Android: se prueba en `MarcosDelDocumentoTest`.
 */
object MarcosDelDocumento {
    /** Lo mínimo que tiene que medir un marco en la pantalla, de ancho y de alto. */
    const val MINIMO_PX = 8.0

    /** El azul de los marcos del plano en el PC (`0x0078D4`). */
    const val AZUL = 0xFF0078D4.toInt()

    /** El azul de un botón pulsado en el riel y las barras del PC (`#0060DF`). */
    const val ENCENDIDO = 0xFF0060DF.toInt()

    const val TERMINACION_MARCOS = ".marcos.json"
    const val TERMINACION_VISTAS = ".vistas.json"

    /** Un marco: su caja en el documento `[x0, y0, x1, y1]` (ordenada), su parte y su vista. */
    data class Marco(val x0: Double, val y0: Double, val x1: Double, val y1: Double, val parte: Int = 0, val vista: String = "") {
        val ancho get() = x1 - x0
        val alto get() = y1 - y0
        val centroX get() = (x0 + x1) / 2
        val centroY get() = (y0 + y1) / 2

        companion object {
            /** Entre dos esquinas, arrastre hacia donde arrastre. */
            fun entre(ax: Double, ay: Double, bx: Double, by: Double, parte: Int = 0) =
                Marco(minOf(ax, bx), minOf(ay, by), maxOf(ax, bx), maxOf(ay, by), parte)
        }
    }

    /**
     * Una lista más con el marco de [a] a [b] si mide más de [MINIMO_PX] en la pantalla de ancho y
     * de alto ([pxPorUnidad] = píxeles de pantalla por unidad del documento). Null si fue un toque.
     */
    fun conNuevo(lista: List<Marco>, ax: Double, ay: Double, bx: Double, by: Double, pxPorUnidad: Double, parte: Int = 0): List<Marco>? {
        val m = Marco.entre(ax, ay, bx, by, parte)
        val px = pxPorUnidad.coerceAtLeast(1e-9)
        if (m.ancho * px <= MINIMO_PX || m.alto * px <= MINIMO_PX) return null
        return lista + m
    }

    /** «Quitar el último». */
    fun sinElUltimo(lista: List<Marco>): List<Marco> = lista.dropLast(1)

    /**
     * **Cuánto acercarse para que el marco llene la ventana** (con un poco de aire, como el PC):
     * píxeles de pantalla por unidad del documento.
     */
    fun pxParaLlenar(m: Marco, ancho: Double, alto: Double): Double {
        val aire = 0.94
        return minOf(ancho * aire / m.ancho.coerceAtLeast(1e-9), alto * aire / m.alto.coerceAtLeast(1e-9))
    }

    // ---- Las unidades de un PDF ----

    /** El ancho de una hoja de PDF en las unidades del lector (`ANCHO_HOJA` del PC, `PdfDoc.PAGE_WIDTH`). */
    const val ANCHO_HOJA = 1400.0

    /** El aire entre dos hojas, en unidades (`HUECO_ENTRE_HOJAS` del PC). */
    const val HUECO_ENTRE_HOJAS = 18.0

    /**
     * **Las hojas de un PDF una debajo de otra, en unidades** (`Hojas::colocar` de
     * `pixpin-docs/src/vista.rs`): cada hoja mide [ANCHO_HOJA] de ancho y lo que le toque de alto,
     * con [HUECO_ENTRE_HOJAS] entre dos. Son las unidades de los marcos de un PDF o un Word por
     * hojas: las mismas en los dos aparatos aunque cada uno pinte la hoja a otro tamaño.
     * [medidas]: ancho y alto de cada página, en lo que sea (solo cuenta la proporción).
     */
    class ColumnaDeHojas(medidas: List<Pair<Double, Double>?>) {
        val arriba: DoubleArray
        val altos: DoubleArray

        init {
            arriba = DoubleArray(medidas.size)
            altos = DoubleArray(medidas.size)
            var y = 0.0
            medidas.forEachIndexed { i, m ->
                val p = m?.let { (w, h) -> if (w > 0 && h > 0 && (h / w).isFinite()) (h / w).coerceIn(0.05, 20.0) else null } ?: 1.414
                arriba[i] = y
                altos[i] = ANCHO_HOJA * p
                y += altos[i] + HUECO_ENTRE_HOJAS
            }
        }

        val cuantas get() = arriba.size

        /** La hoja que hay a la altura [y] (la de encima si cae en el hueco). */
        fun en(y: Double): Int {
            if (arriba.isEmpty()) return 0
            var i = 0
            while (i + 1 < arriba.size && arriba[i + 1] <= y) i++
            return i
        }
    }

    // ---- El archivo de los marcos ----

    private val JSON = Json { prettyPrint = true; prettyPrintIndent = "  " }

    fun aTexto(lista: List<Marco>): String {
        if (lista.isEmpty()) return "[]"
        val arr = buildJsonArray {
            for (m in lista) add(buildJsonObject {
                put("caja", buildJsonArray { add(JsonPrimitive(m.x0)); add(JsonPrimitive(m.y0)); add(JsonPrimitive(m.x1)); add(JsonPrimitive(m.y1)) })
                put("parte", m.parte)
                if (m.vista.isNotEmpty()) put("vista", m.vista)
            })
        }
        return JSON.encodeToString(JsonArray.serializer(), arr)
    }

    /**
     * Lo leído, o nada. Uno sin `caja` de cuatro números se salta; los que vienen al revés se
     * ordenan. Un archivo de un **modelo 3D** (`{"version":1,"marcos":[…]}`) no es de aquí: vacío.
     */
    fun deTexto(texto: String?): List<Marco> {
        if (texto.isNullOrBlank()) return emptyList()
        val raiz = runCatching { Json.parseToJsonElement(texto) }.getOrNull() as? JsonArray ?: return emptyList()
        return raiz.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val caja = runCatching { o["caja"]!!.jsonArray.map { it.jsonPrimitive.doubleOrNull!! } }.getOrNull()
                ?.takeIf { it.size == 4 && it.all { v -> v.isFinite() } } ?: return@mapNotNull null
            val parte = (o["parte"] as? JsonPrimitive)?.intOrNull ?: 0
            val vista = (o["vista"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            Marco.entre(caja[0], caja[1], caja[2], caja[3], parte).copy(vista = vista)
        }
    }

    // ---- Las vistas de los marcadores ----

    /** Una vista sencilla «zoom x y» (la de casi todos los visores) a texto, como `zoom_a_texto`. */
    fun zoomATexto(zoom: Double, x: Double, y: Double): String = "${num(zoom)} ${num(x)} ${num(y)}"

    /** Y de vuelta: (zoom, x, y), o null si no se entiende. */
    fun zoomDeTexto(t: String?): Triple<Double, Double, Double>? {
        val v = t.orEmpty().trim().split(Regex("\\s+")).map { it.toDoubleOrNull() }
        if (v.size < 3 || v.take(3).any { it == null || !it.isFinite() }) return null
        return Triple(v[0]!!, v[1]!!, v[2]!!)
    }

    fun vistasATexto(vistas: Map<String, String>): String {
        if (vistas.isEmpty()) return "{}"
        val o = buildJsonObject { for ((k, v) in vistas.toSortedMap()) put(k, v) }
        return JSON.encodeToString(JsonObject.serializer(), o)
    }

    /** Las de un documento (`{"id":"texto"}`); las de un plano o un modelo (con `version`) no. */
    fun vistasDeTexto(texto: String?): Map<String, String> {
        if (texto.isNullOrBlank()) return emptyMap()
        val o = runCatching { Json.parseToJsonElement(texto) }.getOrNull() as? JsonObject ?: return emptyMap()
        if ("version" in o) return emptyMap()
        return o.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { k to it } }.toMap()
    }

    private fun num(v: Double): String =
        if (v == Math.rint(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString()

    // ---- En disco ----

    /**
     * **Escribe [texto] en [f] por un temporal, y solo si cambia.** Con [vacio] y el archivo sin
     * crear no se crea (un archivo vacío más que mandar); si ya existe, se escribe vacío.
     */
    fun escribir(f: File, texto: String, vacio: Boolean) {
        if (vacio && !f.exists()) return
        if (f.isFile && runCatching { f.readText() }.getOrNull() == texto) return
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(texto)
        if (!tmp.renameTo(f)) { f.writeText(texto); tmp.delete() }
    }

    fun leer(f: File): List<Marco> = deTexto(runCatching { f.readText() }.getOrNull())

    fun guardar(f: File, lista: List<Marco>) = escribir(f, aTexto(lista), lista.isEmpty())

    fun leerVistas(f: File): Map<String, String> = vistasDeTexto(runCatching { f.readText() }.getOrNull())

    fun guardarVistas(f: File, vistas: Map<String, String>) = escribir(f, vistasATexto(vistas), vistas.isEmpty())

    /** Los de una base de lo anotado (`anot-<uid>`…): `pins/draw/<base>.marcos.json`. */
    fun archivo(filesDir: File, base: String) = File(filesDir, "pins/draw/$base$TERMINACION_MARCOS")

    fun archivoDeVistas(filesDir: File, base: String) = File(filesDir, "pins/draw/$base$TERMINACION_VISTAS")
}
