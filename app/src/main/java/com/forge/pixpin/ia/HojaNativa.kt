package com.forge.pixpin.ia

/**
 * **La hoja al lienzo del PC, en el teléfono** (`libpixpinia.so`, PC `pixpin_ia::hoja` y
 * `asistente/hoja_a_lienzo.rs`, 14-18-oct-2026): pasar una foto, un escaneo o una página de un PDF al
 * lienzo interpretada —texto como texto, tablas como tabla, fórmulas como ecuación, figuras exactas,
 * fotos recortadas y rayas— en su sitio y en orden de lectura.
 *
 * El seccionado, los recuadros, el montaje de cada zona, los diagramas exactos, el planificador, el
 * supervisor y la comprobación son los del PC, compilados (ver `herramientas/pixpin-ia-android`). Lo
 * que en Windows es del sistema lo pone el teléfono por el [Puente]. Solo con la nube.
 */
object HojaNativa {
    /** Si la biblioteca está (un APK sin ella, o otra arquitectura, no la tiene). */
    val disponible: Boolean by lazy { runCatching { System.loadLibrary("pixpinia") }.isSuccess }

    /** Lo que en Windows hace el sistema. Lo llaman los hilos de la hoja: tiene que poder con varios a la vez. */
    interface Puente {
        /** `[u32 largo][JSON {ancho, alto, trozos?, ancho_pt?} o {error}][RGBA]`. [pagina] desde 0 (−1: la primera). */
        fun leer(ruta: String, pagina: Int, ancho: Int): ByteArray
        /** El OCR: `[[[texto, x, y, ancho, alto], …], …]` (renglones de palabras) o `{"error": …}`. */
        fun ocr(rgba: ByteArray, ancho: Int, alto: Int): String
        /** `{"nube": bool, "ve": bool, "a_la_vez": n}`. */
        fun motor(): String
        /** La petición (`{mensajes: [{rol, partes: [{texto} | {imagen, mime}]}], max_tokens, temperatura}`) a `{texto}` o `{error}`. */
        fun completar(peticion: String): String
        fun parado(): Boolean
        fun progreso(hechas: Int, total: Int)
        /** Una zona hecha al lienzo; "" si entró, o el motivo. */
        fun entregar(documento: String, imagenes: ByteArray, en: String): String
        /** Lo hecho pintado a [ancho] píxeles: `[u32 ancho][u32 alto][RGBA]`. */
        fun pintar(documento: String, imagenes: ByteArray, tamAncho: Float, tamAlto: Float, ancho: Int): ByteArray
    }

    /** **Pasa la hoja** (`hoja_a_lienzo::hacer` del PC): `{"ok": "…"}` o `{"error": "…"}`. */
    @JvmStatic external fun hacer(args: String, adjuntos: String, semilla: Long, puente: Puente): String

    /** Si una imagen es una lámina (texto y figuras): lo que se pide calcar se pasa entonces como hoja. */
    @JvmStatic external fun esLamina(ruta: String, puente: Puente): Boolean

    /**
     * **Una lámina que se pidió calcar** (PC `ejecutor::lamina_al_lienzo`): si es lámina, pasada como
     * hoja con texto rico y figuras exactas al [ancho] (≤ 0: el de la imagen). `{"no": true}` si no lo es.
     */
    @JvmStatic external fun lamina(ruta: String, ancho: Double, semilla: Long, puente: Puente): String

    /**
     * **La tabla de una foto con su aspecto** (PC `documentos_acciones::tabla`): `{"filas", "cabecera",
     * "fondos"?, "anchos"?}` (fondos `#rrggbb` por celda y anchos de columna, si la rejilla cuadra) o `{"error"}`.
     */
    @JvmStatic external fun tabla(ruta: String, puente: Puente): String

    /** El texto (JSON) que devuelve [hacer]: lo hecho, o el fallo. */
    fun resultado(json: String): Result<String> = runCatching {
        val o = kotlinx.serialization.json.Json.parseToJsonElement(json) as kotlinx.serialization.json.JsonObject
        (o["ok"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            ?: error((o["error"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "la hoja al lienzo no contestó")
    }
}
