package com.forge.pixpin.ia

/**
 * **Las conexiones del asistente del PC, en el teléfono** (`pixpin_ia::conexiones`, 17/18-oct-2026):
 * Google Drive (buscar, leer, bajar y subir, preguntando antes) y Google Calendar (solo ver), con el
 * mismo OAuth (PKCE y vuelta a `127.0.0.1`), el mismo ID de cliente para los dos y lo guardado cifrado.
 * El trabajo es el del PC, compilado en `libpixpinia.so`; la red, el cifrado, el navegador y el chat
 * los pone el teléfono por el [Puente].
 */
object ConexionesNativas {
    val disponible: Boolean get() = HojaNativa.disponible

    const val DRIVE = "google_drive"
    const val CALENDAR = "google_calendar"

    interface Puente {
        /** `[u32 código][u32 largo][JSON {ubicacion?, esperar?, error?}][cuerpo]`; [cabeceras]: `[[nombre, valor], …]`. */
        fun http(metodo: String, url: String, cabeceras: String, cuerpo: ByteArray, limite: Int): ByteArray
        /** Vacío si no se pudo. */
        fun cifrar(datos: ByteArray): ByteArray
        fun descifrar(datos: ByteArray): ByteArray
        /** Abre el navegador en [url]; "" o el motivo. */
        fun abrir(url: String): String
        fun parado(): Boolean
        /** Un archivo del chat por su nombre o descripción ("" el último): `{nombre, ruta}` o `{error}`. */
        fun archivo(descripcion: String): String
        /** El texto de un documento: `{texto}` o `{error}`. */
        fun textoDe(ruta: String): String
        /** Deja [ruta] en el chat con [frase]; "" o el motivo. */
        fun alChat(ruta: String, frase: String): String
        fun confirmar(pregunta: String): Boolean
        fun carpeta(): String
    }

    /** `{"ok": "…"}` o `{"error": "…"}` (en todas). */
    @JvmStatic external fun hacer(herramienta: String, args: String, carpeta: String, puente: Puente): String
    @JvmStatic external fun estado(servicio: String, carpeta: String, puente: Puente): String
    @JvmStatic external fun guardarCliente(servicio: String, pegado: String, carpeta: String, puente: Puente): String
    @JvmStatic external fun conectar(servicio: String, carpeta: String, puente: Puente): String
    @JvmStatic external fun desconectar(servicio: String, carpeta: String, puente: Puente): String
    @JvmStatic external fun eventos(desdeMs: Long, hastaMs: Long, carpeta: String, puente: Puente): String

    /** Lo que trae `ok`, o el fallo. */
    fun ok(json: String): Result<kotlinx.serialization.json.JsonElement> = runCatching {
        val o = kotlinx.serialization.json.Json.parseToJsonElement(json) as kotlinx.serialization.json.JsonObject
        o["ok"] ?: error((o["error"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "la conexion no contesto")
    }

    val NOMBRES = setOf("buscar_en_conexion", "leer_de_conexion", "bajar_de_conexion", "subir_a_conexion", "ver_calendario")
}
