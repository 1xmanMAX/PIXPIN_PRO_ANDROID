package com.forge.pixpin.motor

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * **Compartir un archivo como enlace, no como archivo.**
 *
 * Mandar un `.html` o un PDF por WhatsApp es incómodo: llega como documento, hay que
 * bajarlo y abrirlo a mano, y muchas aplicaciones ni lo enseñan. Un enlace se toca y se
 * abre. Por eso cualquier cosa que se comparta —la página web, el PDF, el `.pixpin`, el
 * OBJ, una foto— se puede subir a un servicio de archivos temporales y mandar su
 * dirección. Lo pidió el usuario (6-sep-2026).
 *
 * ## Lo que hay que tener claro
 *
 * Esto es **lo único de toda la aplicación que manda algo fuera del teléfono**, y solo
 * cuando se pulsa el botón. El archivo se sube a un servicio público de otra gente:
 * **cualquiera con el enlace puede abrirlo** mientras dure, así que no vale para algo
 * privado. Los servicios no son nuestros y pueden caerse; por eso hay más de uno y, si el
 * elegido falla, se prueban los demás ([conReserva]).
 *
 * ## Solo lo que el servicio publica
 *
 * Lo que la aplicación dice que dura un enlace tiene que ser **lo que el servicio dice que
 * dura**, no lo que a nosotros nos parezca. Comprobado en sus propias páginas el
 * 6-sep-2026:
 *
 * - **litterbox** — «Temporary uploads up to 1 GB are allowed. Expire after: 1 Hour,
 *   12 Hours, 1 Day, 3 Days». Es el bueno: cuatro duraciones, todas cortas, y sirve el
 *   archivo con su tipo, así que un `.html` se abre **como página**.
 * - **temp.sh** — «Files expire after 3 days», hasta 4 GB. Sirve de reserva y para lo que
 *   no cabe en el otro; a cambio su enlace abre **una página con un botón de descarga**,
 *   así que un `.html` no se ve, se baja.
 *
 * Y los que se dejaron fuera, por lo mismo: **catbox** guarda «until they have 2 years of
 * inactivity» —eso no es un enlace temporal— y **kappa.lol** no dice en ninguna parte
 * cuánto lo guarda, solo que puede borrarlo cuando quiera; prometer una duración que el
 * servicio no promete es informar mal.
 *
 * ## Lo que no admiten
 *
 * Los dos de catbox rechazan `.exe`, `.scr`, `.cpl`, `.doc*` y `.jar`. Nada de lo que
 * exporta PixPin está en esa lista (comprobado con `.html`, `.pdf`, `.pixpin` y `.apk`).
 */
object SubirArchivo {


    /**
     * Un sitio donde dejar la página. [caduca] es lo que se le dice al usuario, con sus
     * palabras: es la mitad de la decisión.
     */
    class Servicio(
        val id: String,
        val nombre: String,
        val caduca: String,
        /** Lo más grande que admite, en MB. */
        val topeMb: Int,
        /**
         * Si su enlace abre **el archivo** con su tipo. Con `false` abre una página con un
         * botón de descarga, que para un PDF da igual pero para una página web no: lo que
         * se quería enseñar hay que bajarlo primero.
         */
        val abreElArchivo: Boolean,
        internal val url: String,
        internal val campo: String,
        internal val extras: List<Pair<String, String>> = emptyList()
    )

    private const val LITTERBOX = "https://litterbox.catbox.moe/resources/internals/api.php"

    /**
     * Los sitios, en el orden en que se ofrecen y en el que se prueban. Primero lo que
     * caduca antes: algo que se comparte en un chat se mira en el momento, y lo que no
     * dura no se queda por ahí.
     */
    val SERVICIOS: List<Servicio> = listOf(
        Servicio("litter1h", "1 hora", "se borra en 1 hora", 1000, true, LITTERBOX, "fileToUpload",
            listOf("reqtype" to "fileupload", "time" to "1h")),
        Servicio("litter12h", "12 horas", "se borra en 12 horas", 1000, true, LITTERBOX, "fileToUpload",
            listOf("reqtype" to "fileupload", "time" to "12h")),
        Servicio("litter24h", "1 día", "se borra en 24 horas", 1000, true, LITTERBOX, "fileToUpload",
            listOf("reqtype" to "fileupload", "time" to "24h")),
        Servicio("litter72h", "3 días", "se borra en 3 días", 1000, true, LITTERBOX, "fileToUpload",
            listOf("reqtype" to "fileupload", "time" to "72h")),
        // La reserva: dura lo mismo que la más larga, admite cuatro veces más, y a cambio
        // su enlace abre una página con un botón en vez del archivo.
        Servicio("tempsh", "3 días · otro servicio", "se borra en 3 días", 4000, false,
            "https://temp.sh/upload", "file")
    )

    /** El que se ofrece marcado: una hora es lo que dura mirar algo que te mandan. */
    val POR_DEFECTO: Servicio = SERVICIOS.first()

    fun porId(id: String?): Servicio = SERVICIOS.firstOrNull { it.id == id } ?: POR_DEFECTO

    sealed class Resultado {
        class Enlace(val url: String, val servicio: Servicio) : Resultado()
        /** [motivo] ya viene escrito para enseñárselo a alguien. */
        class Fallo(val motivo: String) : Resultado()
    }

    /** Si el archivo cabe en ese servicio. */
    fun cabe(servicio: Servicio, bytes: Long): Boolean = bytes <= servicio.topeMb * 1_000_000L

    /**
     * Sube [archivo] a [servicio] y devuelve su enlace. Bloquea: hilo de fondo. [avance] va
     * de 0 a 1 con lo que lleva subido.
     */
    fun subir(
        servicio: Servicio, archivo: File, nombre: String, mime: String,
        avance: (Float) -> Unit = {}
    ): Resultado {
        if (!archivo.exists() || archivo.length() == 0L) return Resultado.Fallo("no hay nada que subir")
        if (!cabe(servicio, archivo.length())) {
            return Resultado.Fallo("pesa ${archivo.length() / 1_000_000} MB y ${servicio.nombre.lowercase()} admite ${servicio.topeMb}")
        }
        val frontera = "----pixpin" + System.nanoTime().toString(16)
        val cabeza = StringBuilder()
        for ((k, v) in servicio.extras) {
            cabeza.append("--").append(frontera).append("\r\n")
                .append("Content-Disposition: form-data; name=\"").append(k).append("\"\r\n\r\n")
                .append(v).append("\r\n")
        }
        cabeza.append("--").append(frontera).append("\r\n")
            .append("Content-Disposition: form-data; name=\"").append(servicio.campo)
            .append("\"; filename=\"").append(sano(nombre, archivo.extension)).append("\"\r\n")
            .append("Content-Type: ").append(mime.ifBlank { "application/octet-stream" }).append("\r\n\r\n")
        val cola = "\r\n--$frontera--\r\n"
        val cabezaBytes = cabeza.toString().toByteArray()
        val colaBytes = cola.toByteArray()
        val total = cabezaBytes.size + archivo.length() + colaBytes.size

        return runCatching {
            val conexion = (URL(servicio.url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                instanceFollowRedirects = true
                connectTimeout = 20_000
                readTimeout = 120_000
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$frontera")
                // Sin esto, un archivo de cincuenta megas se guarda entero en memoria antes
                // de salir, y en un teléfono eso es quedarse sin memoria.
                setFixedLengthStreamingMode(total)
            }
            conexion.outputStream.buffered(64 * 1024).use { salida ->
                salida.write(cabezaBytes)
                var puestos = cabezaBytes.size.toLong()
                archivo.inputStream().buffered().use { entrada ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = entrada.read(buf)
                        if (n < 0) break
                        salida.write(buf, 0, n)
                        puestos += n
                        avance((puestos.toFloat() / total).coerceIn(0f, 0.99f))
                    }
                }
                salida.write(colaBytes)
            }
            val codigo = conexion.responseCode
            val cuerpo = (if (codigo in 200..299) conexion.inputStream else conexion.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            conexion.disconnect()
            if (codigo !in 200..299) return Resultado.Fallo("el servicio contestó $codigo")
            val enlace = enlaceDe(cuerpo)
                ?: return Resultado.Fallo(motivoDe(cuerpo))
            avance(1f)
            Resultado.Enlace(enlace, servicio)
        }.getOrElse { Resultado.Fallo(resumen(it)) }
    }

    /**
     * Lo intenta con [preferido] y, si falla, con los demás **que quepan**, avisando por
     * [alProbar] de cuál se está intentando. Los servicios son de otra gente y se caen sin
     * avisar; con uno solo, «compartir» dejaba de funcionar sin que nadie supiera por qué.
     */
    fun conReserva(
        preferido: Servicio,
        archivo: File,
        nombre: String,
        mime: String,
        avance: (Float) -> Unit = {},
        alProbar: (Servicio) -> Unit = {}
    ): Resultado {
        // Con una página web, los que la abren van antes que los que la hacen bajar: la
        // gracia del enlace era no tener que bajar nada.
        val esPagina = mime == ExportarHtml.MIME_TYPE
        val demas = SERVICIOS.filter { it.id != preferido.id }
            .sortedByDescending { if (esPagina && it.abreElArchivo) 1 else 0 }
        val cola = listOf(preferido) + demas
        var ultimo = "no se pudo subir"
        for (s in cola) {
            if (!cabe(s, archivo.length())) continue
            alProbar(s)
            when (val r = subir(s, archivo, nombre, mime, avance)) {
                is Resultado.Enlace -> return r
                is Resultado.Fallo -> ultimo = r.motivo
            }
        }
        return Resultado.Fallo(ultimo)
    }

    /**
     * El enlace que hay en la respuesta. Unos contestan la dirección pelada (catbox,
     * litterbox) y otros un JSON con `"link"` dentro (kappa). Puro, para poder probarlo.
     */
    internal fun enlaceDe(cuerpo: String): String? {
        val limpio = cuerpo.trim()
        limpio.lineSequence().map { it.trim() }
            .firstOrNull { it.startsWith("https://") && !it.contains(' ') && it.length < 300 }
            ?.let { return it }
        val marca = "\"link\":"
        val i = limpio.indexOf(marca)
        if (i < 0) return null
        val desde = limpio.indexOf('"', i + marca.length)
        val hasta = if (desde < 0) -1 else limpio.indexOf('"', desde + 1)
        if (desde < 0 || hasta < 0) return null
        return limpio.substring(desde + 1, hasta).replace("\\/", "/").takeIf { it.startsWith("https://") }
    }

    /** Lo que contestó el servicio, recortado, para poder decir por qué no salió. */
    internal fun motivoDe(cuerpo: String): String {
        val texto = cuerpo.trim().replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim()
        return if (texto.isBlank()) "el servicio no contestó nada" else texto.take(120)
    }

    private fun resumen(e: Throwable): String =
        when (e) {
            is java.net.UnknownHostException -> "sin conexión"
            is java.net.SocketTimeoutException -> "el servicio tardó demasiado"
            else -> (e::class.java.simpleName + ": " + (e.message ?: "")).take(120)
        }

    /**
     * Un nombre de archivo que no rompa la cabecera del multipart, **con su extensión**:
     * varios de estos servicios deciden por ella con qué tipo sirven el archivo después.
     */
    internal fun sano(nombre: String, extension: String): String {
        // Una tanda seguida de caracteres prohibidos es **un** guion, no uno por letra.
        val base = nombre.replace(Regex("[\\\\/:*?\"<>|\r\n]+"), "-").trim().ifBlank { "archivo" }
        val ext = extension.removePrefix(".").lowercase()
        return if (ext.isBlank() || base.endsWith(".$ext", true)) base else "$base.$ext"
    }
}
