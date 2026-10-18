package com.forge.pixpin.sincro

import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket

/**
 * **Enviar a tus aparatos del grupo sin escanear** (PC `ccab4f0`, 17-oct-2026; el usuario: «enviar
 * archivos automáticamente sin necesidad de escanear si están dentro del mismo grupo»).
 *
 * En «Enviar por Wi-Fi», encima del código sale la lista del grupo con quién se ve en la Wi-Fi, y
 * un toque manda **directo por el canal cifrado del grupo**, sin QR ni aprobar nada: la petición
 * `suelto` que el PC ya usaba para «p s» (`pixpin_sincro::al_lienzo` + `sincronizar/al_movil.rs`).
 * Llega a lo que el otro tenga abierto (un chat cualquier cosa; un lienzo solo fotos) o a su
 * conversación general. El cable es **exactamente** el del PC, sin subir el protocolo:
 *
 * ```text
 * yo  → hola (el de siempre, sin ocupar este aparato)
 * yo  → {"t":"suelto","nombre":"informe.pdf","mime":"application/pdf","bytes":N,"destino":"abierto"}
 * otro→ {}  (vale)   o  {"error":"…solo acepta fotos"}  (no sale ningún trozo)
 * yo  → TROZO… + {"resumen":"<sha256>"}
 * otro→ {"t":"listo","donde":"chat_abierto","chat":"Tesis"}   ("lienzo" / "chat")
 * ...otro archivo...
 * yo  → {"t":"adios"}   otro→ {}
 * ```
 *
 * Lo que recibe está en [Respondedor] (`recibirSuelto`) y [Presencia]. Aquí, sin Android, lo que
 * manda ([Conexion], [mandarYa]) y los textos de la pantalla y del aviso al recibirlo.
 */
object AlGrupo {

    /** Lo que se pide en cada `suelto`: lo que el otro tenga abierto (el «abierto» del PC del 7-oct). */
    const val DESTINO_ABIERTO = "abierto"

    /** Un aparato del grupo al que se le puede mandar, que no es este. */
    data class AparatoDelGrupo(
        val id: String,
        val nombre: String,
        val letra: String? = null,
        /** Dónde contestó la última vez. Que esté no quiere decir que esté encendido ahora. */
        val direccion: Pair<String, Int>? = null
    )

    /** Un archivo que sale: [nombre] es el que verá el otro (y por el que se saca el tipo). */
    data class Fichero(val archivo: File, val nombre: String, val mime: String? = null)

    /** Dónde quedó en el otro aparato: [Protocolo.SUELTO_EN_EL_LIENZO], `…_CHAT_ABIERTO` o `…_CHAT`. */
    data class Llegada(val donde: String, val chat: String? = null)

    /** Lo que sale de [mandarYa]: [Entregado] o un [Fallo]. */
    sealed interface Hecho

    /** Lo que llegó, y adónde; y cuántos se negaron (no eran fotos y tenía un lienzo delante). */
    data class Entregado(val movil: String, val donde: List<Llegada>, val negados: Int = 0) : Hecho

    /** Por qué no se pudo. Cada uno dice **qué hacer**, no solo que algo falló. */
    sealed interface Fallo : Hecho {
        data object SinGrupo : Fallo
        data object NoEsDelGrupo : Fallo
        data object SinFicheros : Fallo
        /** Ni contesta en su dirección ni se anuncia: PixPin no está delante allí, o no hay Wi-Fi. */
        data class NoEsta(val movil: String) : Fallo
        /** Un PixPin de antes del 6-oct: no conoce `suelto`. */
        data class SinSoporte(val movil: String) : Fallo
        data class Ocupado(val movil: String) : Fallo
        data class OtraVersion(val movil: String) : Fallo
        /** Tiene un lienzo delante y nada de lo mandado era una foto. */
        data class SoloFotos(val movil: String) : Fallo
        data class Cortado(val movil: String, val motivo: String) : Fallo
    }

    /** Lo que contestó el otro, con su clase. Ver [errorRemoto]. */
    class ErrorAlGrupo(val clase: Clase, mensaje: String) : IOException(mensaje) {
        enum class Clase { SIN_SOPORTE, SOLO_FOTOS, OCUPADO, OTRA_VERSION, REMOTO, PROTOCOLO }
    }

    // ------------------------------------------------------------ a quién

    /** Los del grupo menos yo, con su última dirección. Sin grupo (sin código), ninguno. */
    fun aparatos(disco: Disco): List<AparatoDelGrupo> {
        val id = runCatching { disco.identidad.leer() }.getOrNull() ?: return emptyList()
        if (id.codigo.isNullOrBlank()) return emptyList()
        val dirs = disco.direcciones()
        return id.miembros.filter { it.id != id.yo.id && it.id.isNotEmpty() }
            .map { AparatoDelGrupo(it.id, it.nombre, it.letra, dirs[it.id]) }
    }

    /**
     * A quién va «Enviar a todos»: los que se ven en la Wi-Fi ([cerca]) y no tienen ya algo saliendo
     * (`null` en [enCurso]) o recién llegado. El que falló sí se puede volver a intentar.
     */
    fun aTodos(grupo: List<AparatoDelGrupo>, cerca: Set<String>, enCurso: Map<String, Hecho?>): List<String> =
        grupo.filter { it.id in cerca }
            .filter { a -> !(enCurso.containsKey(a.id) && (enCurso[a.id] == null || enCurso[a.id] is Entregado)) }
            .map { it.id }

    // ------------------------------------------------------------ el cable

    /** Si un error del otro es el «No sé qué es «suelto»» de un PixPin de antes. */
    fun esSinSoporte(error: String): Boolean = error.startsWith("No sé qué es") && error.contains("suelto")

    /** Un error del otro, con su clase cuando la tiene (`error_remoto` del PC). */
    fun errorRemoto(e: String): ErrorAlGrupo = when {
        e == Protocolo.OCUPADO -> ErrorAlGrupo(ErrorAlGrupo.Clase.OCUPADO, e)
        esSinSoporte(e) -> ErrorAlGrupo(ErrorAlGrupo.Clase.SIN_SOPORTE, "el otro aparato aún no recibe archivos sueltos")
        e.endsWith(Protocolo.SOLO_FOTOS) -> ErrorAlGrupo(ErrorAlGrupo.Clase.SOLO_FOTOS, Protocolo.SOLO_FOTOS)
        else -> ErrorAlGrupo(ErrorAlGrupo.Clase.REMOTO, e)
    }

    /** Lo que no se conozca cuenta como la conversación general, que es donde acaba lo que llega sin sitio. */
    fun dondeDeTexto(t: String?): String = when (t) {
        Protocolo.SUELTO_EN_EL_LIENZO, Protocolo.SUELTO_EN_EL_CHAT_ABIERTO -> t
        else -> Protocolo.SUELTO_EN_EL_CHAT
    }

    /**
     * El tipo de un fichero por su extensión, con la misma tabla que el PC (`al_lienzo::mime_de`):
     * quien recibe lo mira para decidir (una imagen puede ir a un lienzo; lo demás, solo a un chat).
     */
    fun mimeDe(nombre: String): String = when (nombre.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "heic" -> "image/heic"
        "pdf" -> "application/pdf"
        "txt" -> "text/plain"
        "md" -> "text/markdown"
        "csv" -> "text/csv"
        "html", "htm" -> "text/html"
        "json" -> "application/json"
        "zip" -> "application/zip"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "ppt" -> "application/vnd.ms-powerpoint"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "epub" -> "application/epub+zip"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "wav" -> "audio/wav"
        "ogg", "opus" -> "audio/ogg"
        "mp4" -> "video/mp4"
        "mov" -> "video/quicktime"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        else -> "application/octet-stream"
    }

    /**
     * El tipo que viaja: el de la tabla del PC y, si por la extensión no se sabe, el que dio Android
     * al compartirlo (una foto sin extensión sigue pudiendo ir a un lienzo).
     */
    fun mimeQueViaja(f: Fichero): String =
        mimeDe(f.nombre).takeIf { it != "application/octet-stream" } ?: f.mime?.takeIf { it.isNotBlank() } ?: "application/octet-stream"

    /**
     * **Una conexión ya saludada con el otro**, lista para mandarle archivos (`al_lienzo::Conexion`).
     * **No ocupa** este aparato como una vuelta de sincronizar: mandar no escribe nada de aquí.
     */
    class Conexion private constructor(private val canal: Canal, val otro: Aparato, val puertoDelOtro: Int) {
        private var despedida = false

        /** Manda un archivo y dice dónde quedó. [ErrorAlGrupo.Clase.SIN_SOPORTE] y `SOLO_FOTOS` dejan la conexión sana. */
        fun mandar(f: Fichero): Llegada {
            // Antes de pedir nada: si el fichero no está, no se le promete al otro algo que no llegará.
            if (!f.archivo.isFile) throw java.io.FileNotFoundException(f.archivo.path)
            val sale = Protocolo.salidaDeArchivo(f.archivo)
            Protocolo.enviar(canal, Protocolo.Peticion(
                "suelto", nombre = f.nombre, mime = mimeQueViaja(f), bytes = sale.largo, destino = DESTINO_ABIERTO
            ))
            Protocolo.leerRespuestaSinLanzar(canal).error?.let { throw errorRemoto(it) }
            if (sale.mandar(canal) {} == null) {
                // La cola ya dijo «saltado»: el otro lo tira y contesta.
                runCatching { Protocolo.leerRespuestaSinLanzar(canal) }
                throw ErrorAlGrupo(ErrorAlGrupo.Clase.PROTOCOLO, "El archivo cambió mientras se mandaba")
            }
            val listo = Protocolo.leerRespuestaSinLanzar(canal)
            listo.error?.let { throw errorRemoto(it) }
            return Llegada(dondeDeTexto(listo.donde), listo.chat?.takeIf { it.isNotBlank() })
        }

        /** Se despide (`adios`). Sin esto, el otro se quedaría esperando la siguiente petición. */
        fun adios() {
            if (despedida) return
            despedida = true
            runCatching {
                Protocolo.enviar(canal, Protocolo.Peticion("adios"))
                Protocolo.leerRespuestaSinLanzar(canal)
            }
        }

        companion object {
            /** El saludo de siempre (`Sesion.abrir`), con el código del grupo y sin ocupar. */
            fun abrir(entrada: InputStream, salida: OutputStream, codigo: String, hola: Protocolo.Hola): Conexion {
                val canal = Canal.abrir(entrada, salida, Grupo.clave(codigo), inicia = true)
                Protocolo.enviar(canal, Protocolo.Peticion("hola", hola = hola))
                val r = Protocolo.leerRespuestaSinLanzar(canal)
                r.error?.let { throw errorRemoto(it) }
                val suyo = r.hola ?: throw ErrorAlGrupo(ErrorAlGrupo.Clase.PROTOCOLO, "El otro aparato no saludó")
                if (suyo.version != Protocolo.VERSION) {
                    throw ErrorAlGrupo(ErrorAlGrupo.Clase.OTRA_VERSION, "el otro aparato habla la versión ${suyo.version} del protocolo")
                }
                return Conexion(canal, suyo.yo, suyo.puerto)
            }
        }
    }

    /**
     * **Directo a un aparato del grupo, sin código** (`al_movil::mandar_ya` del PC). [localizar] dice
     * dónde llamarle (la dirección que contestó al PING o la del anuncio de la red); [miPuerto], dónde
     * escucha este aparato, para que el otro lo apunte como en una vuelta. Sin hilo: quien llama ya
     * está en uno.
     */
    fun mandarYa(
        disco: Disco,
        aparato: String,
        ficheros: List<Fichero>,
        miPuerto: Int = 0,
        localizar: (AparatoDelGrupo) -> Pair<String, Int>?,
        conectar: (String, Int) -> Socket = { h, p -> Red.conectar(InetAddress.getByName(h), p) },
        ahora: () -> Long = System::currentTimeMillis
    ): Hecho {
        val salen = ficheros.filter { it.archivo.isFile }
        if (salen.isEmpty()) return Fallo.SinFicheros
        val id = runCatching { disco.identidad.leer() }.getOrNull() ?: return Fallo.SinGrupo
        val codigo = id.codigo?.takeIf { it.isNotBlank() } ?: return Fallo.SinGrupo
        val otro = aparatos(disco).firstOrNull { it.id == aparato } ?: return Fallo.NoEsDelGrupo
        val nombre = otro.nombre
        val (host, puerto) = localizar(otro) ?: return Fallo.NoEsta(nombre)
        val socket = try { conectar(host, puerto) } catch (e: Exception) { return Fallo.NoEsta(nombre) }
        socket.use { s ->
            val c = try {
                Conexion.abrir(s.getInputStream(), s.getOutputStream(), codigo, Protocolo.Hola(id.yo, id.miembros, ahora(), puerto = miPuerto))
            } catch (e: Exception) {
                return traducir(e, nombre, alSaludar = true)
            }
            disco.apuntarDireccion(otro.id, host, c.puertoDelOtro.takeIf { it > 0 } ?: puerto)
            val donde = ArrayList<Llegada>()
            var negados = 0
            for (f in salen) {
                try {
                    donde += c.mandar(f)
                } catch (e: ErrorAlGrupo) {
                    // Un lienzo delante y no es una foto: ese no, los demás sí.
                    if (e.clase == ErrorAlGrupo.Clase.SOLO_FOTOS) negados++ else { c.adios(); return traducir(e, nombre) }
                } catch (e: Exception) {
                    return traducir(e, nombre)
                }
            }
            c.adios()
            if (donde.isEmpty() && negados > 0) return Fallo.SoloFotos(nombre)
            return Entregado(nombre, donde, negados)
        }
    }

    /**
     * Lo que se le dice al usuario de cada error. El código no viaja: un tramo que no descifra, o un
     * corte justo al saludar, es casi siempre otro código de grupo.
     */
    private fun traducir(e: Exception, movil: String, alSaludar: Boolean = false): Fallo = when {
        e is ErrorAlGrupo && e.clase == ErrorAlGrupo.Clase.SIN_SOPORTE -> Fallo.SinSoporte(movil)
        e is ErrorAlGrupo && e.clase == ErrorAlGrupo.Clase.OCUPADO -> Fallo.Ocupado(movil)
        e is ErrorAlGrupo && e.clase == ErrorAlGrupo.Clase.OTRA_VERSION -> Fallo.OtraVersion(movil)
        e is Canal.CodigoDistinto || (alSaludar && e is EOFException) -> Fallo.Cortado(movil, "¿tiene el mismo código de grupo?")
        else -> Fallo.Cortado(movil, e.message ?: e.javaClass.simpleName)
    }

    // ------------------------------------------------------------ lo que se dice

    /** El aviso de cómo fue (`al_movil::aviso`, los textos `al-movil-*` del PC). */
    fun aviso(h: Hecho): String = when (h) {
        is Entregado -> {
            val n = h.donde.size
            val chat = h.donde.firstNotNullOfOrNull { it.chat }
            val texto = when {
                h.donde.all { it.donde == Protocolo.SUELTO_EN_EL_LIENZO } ->
                    if (n == 1) "Enviada al lienzo de ${h.movil}" else "$n imágenes enviadas al lienzo de ${h.movil}"
                chat != null && h.donde.all { it.donde == Protocolo.SUELTO_EN_EL_CHAT_ABIERTO } ->
                    if (n == 1) "Enviado al chat «$chat» de ${h.movil}" else "$n archivos enviados al chat «$chat» de ${h.movil}"
                else -> "${h.movil} lo guardó en la conversación general (no tenía un chat ni un lienzo abierto)"
            }
            if (h.negados == 0) texto
            else texto + "\n" + if (h.negados == 1) "1 archivo no se envió: no era una foto y tiene un lienzo abierto"
            else "${h.negados} archivos no se enviaron: no eran fotos y tiene un lienzo abierto"
        }
        is Fallo.SoloFotos -> "${h.movil} tiene un lienzo abierto: solo acepta fotos. Abre un chat para mandarle archivos."
        Fallo.SinGrupo -> "Este aparato no está en un grupo: ábrelo en Sincronizar de PixPin"
        Fallo.NoEsDelGrupo -> "Ese aparato ya no está en el grupo de este aparato"
        Fallo.SinFicheros -> "No había nada que enviar"
        is Fallo.NoEsta -> "${h.movil} no está abierto: abre PixPin en ese aparato"
        // El del PC dice «te abro Enviar por Wi-Fi»: aquí ya está abierta (`ew-grupo-actualiza`).
        is Fallo.SinSoporte -> "Actualiza PixPin en ${h.movil}: aún no recibe archivos directos. Que use el código de abajo."
        is Fallo.Ocupado -> "${h.movil} está sincronizando con otro aparato. Prueba otra vez en un momento."
        is Fallo.OtraVersion -> "${h.movil} tiene otra versión de PixPin: actualiza los dos"
        is Fallo.Cortado -> "No se pudo enviar a ${h.movil}: ${h.motivo}"
    }

    enum class Tono { SUAVE, BIEN, MAL }

    /**
     * Lo que se dice de un aparato en su fila (`estado_en_grupo` del PC). [hecho]: sin entrada, nada
     * mandado aún; con entrada `null`, saliendo; si no, cómo acabó.
     */
    fun estadoEnFila(tieneEntrada: Boolean, hecho: Hecho?, seVe: Boolean): Pair<String, Tono> = when {
        tieneEntrada && hecho == null -> "Enviando…" to Tono.SUAVE
        hecho is Entregado -> aviso(hecho) to Tono.BIEN
        hecho is Fallo -> aviso(hecho) to Tono.MAL
        seVe -> "En la misma Wi-Fi" to Tono.BIEN
        else -> "No se ve ahora: abre PixPin en ese aparato" to Tono.SUAVE
    }

    const val TITULO_DEL_GRUPO = "Tus aparatos · sin código"
    const val COMO_VA_AL_GRUPO = "Están en tu grupo: se envía directo, sin escanear. Llega a lo que tengan abierto " +
        "(un chat; un lienzo solo acepta fotos) o a la conversación general."
    const val ENVIAR_A_TODOS = "Enviar a todos los que se ven"

    /**
     * **El aviso de lo que llegó** sin escanear nada (`aviso_de_llegada` del PC): de quién, qué y
     * dónde quedó. [donde] es lo que [Respondedor] contesta al que manda.
     */
    fun avisoDeLlegada(de: String, nombre: String, donde: String, chat: String?): String {
        val quien = de.ifBlank { "?" }
        return when {
            donde == Protocolo.SUELTO_EN_EL_LIENZO -> "$quien te envió «$nombre»: está en el lienzo abierto"
            donde == Protocolo.SUELTO_EN_EL_CHAT_ABIERTO && chat != null -> "$quien te envió «$nombre» al chat «$chat»"
            else -> "$quien te envió «$nombre»: está en la conversación general"
        }
    }
}
