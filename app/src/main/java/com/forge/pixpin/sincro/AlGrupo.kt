package com.forge.pixpin.sincro

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * **Enviar a mis propios aparatos sin código** (25-oct-2026, porte de `sincronizar/enviar_wifi.rs`
 * y `sincronizar/al_movil.rs` del PC, 17-oct-2026).
 *
 * El usuario lo pidió en el PC: «enviar archivos automáticamente sin necesidad de escanear si están
 * dentro del mismo grupo». Hasta ahora el teléfono solo **recibía** así (`suelto` en el
 * [Respondedor]); su «Enviar por Wi-Fi» solo tenía el código y el QR, que son para los de fuera.
 * Ahora, encima del código, salen los aparatos del grupo con quién se ve en la Wi-Fi, y un toque
 * manda directo por **el canal cifrado del grupo** —el mismo puerto y el mismo saludo que
 * sincronizar—, sin QR ni aprobar nada. Llega a lo que el otro tenga abierto ([LoAbierto]: un chat
 * recibe cualquier cosa, un lienzo solo fotos) o a la conversación general.
 *
 * **El cable no cambia**: es el `suelto` que ya entienden el PC y este mismo teléfono, con su
 * «vale» antes de los trozos (ver `crates/pixpin-sincro/src/al_lienzo.rs` del PC). Por eso un
 * PixPin de antes contesta «No sé qué es «suelto»» y la conversación sigue sana: se le dice que
 * use el código de abajo, que sí entiende.
 *
 * Sin Android: lo prueba [com.forge.pixpin.sincro.AlGrupoTest] contra el [Respondedor] de verdad.
 */
object AlGrupo {

    /** El `destino` del 7-oct del PC: lo que el otro tenga abierto. */
    const val DESTINO_ABIERTO = "abierto"

    // ---- A quién ---------------------------------------------------------

    /**
     * **Un aparato del grupo en la lista de «Enviar»**: su ficha y si contestó ahora en la Wi-Fi
     * (y dónde). Sin [host], se enseña pero no se le puede mandar: «abre PixPin en ese aparato».
     */
    data class Fila(val aparato: Aparato, val host: String? = null, val puerto: Int = 0) {
        val seVe: Boolean get() = host != null && puerto > 0
    }

    /**
     * **Los del grupo menos yo**, cada uno con dónde contestó si está en [presentes] (lo que da
     * [AlDia.aparatos]: la dirección recordada que responde al PING o el anuncio de la red).
     *
     * Como `al_movil::aparatos_de` del PC: sin grupo, ninguno; fuera yo y los que no tienen id.
     * Primero los que se ven (es a quien se puede mandar), y entre ellos el orden del grupo. Un
     * presente que no es del grupo (otra lista vieja) no sale: el canal con el código lo
     * rechazaría igual.
     */
    fun filas(identidad: Identidad, presentes: List<AlDia.Estado>): List<Fila> {
        if (!identidad.enGrupo) return emptyList()
        val donde = presentes.filter { it.id.isNotBlank() && it.host.isNotBlank() && it.puerto > 0 }.associateBy { it.id }
        return identidad.miembros
            .filter { it.id.isNotBlank() && it.id != identidad.yo.id }
            .distinctBy { it.id }
            .map { m -> donde[m.id].let { Fila(m, it?.host, it?.puerto ?: 0) } }
            .sortedByDescending { it.seVe }
    }

    /**
     * **Solo archivos** van directos. Un proyecto o un lienzo de PixPin no van por `suelto`: allí
     * caería como un adjunto en el chat y no como proyecto (lo mismo que `grupo_para` del PC). Para
     * esos queda el código, que sí sabe ponerlos en su proyecto.
     */
    fun sePuede(tipos: List<String>): Boolean = tipos.isNotEmpty() && tipos.all { it == Envio.ARCHIVO }

    /** Cómo va lo mandado a un aparato: saliendo, llegado o fallado. Sin estado, aún nada. */
    sealed interface Estado {
        data class Saliendo(val hechos: Long = 0, val total: Long = 0) : Estado
        data class Hecho(val entregado: Entregado) : Estado
        data class Fallo(val fallo: AlGrupo.Fallo) : Estado
    }

    /**
     * **A quién va «Enviar a todos los que se ven»**: los que contestaron en la Wi-Fi y no tienen
     * ya algo saliendo o recién llegado (`a_todos` del PC). A uno que falló sí se le vuelve a probar.
     */
    fun aTodos(filas: List<Fila>, estados: Map<String, Estado>): List<String> =
        filas.filter { it.seVe }
            .filter { estados[it.aparato.id] !is Estado.Saliendo && estados[it.aparato.id] !is Estado.Hecho }
            .map { it.aparato.id }

    // ---- Qué pasó --------------------------------------------------------

    /** Dónde quedó cada archivo en el otro aparato ([Protocolo.SUELTO_EN_EL_LIENZO]…) y en qué chat. */
    data class Llegada(val donde: String, val chat: String? = null)

    /** Lo que llegó, y cuántos se negaron (no eran fotos y tenía un lienzo delante). */
    data class Entregado(val aparato: String, val llegadas: List<Llegada>, val negados: Int)

    /**
     * **Por qué no se pudo**, cada uno con lo que hay que HACER (abrir PixPin, actualizarlo), no
     * solo que falló. Los mismos casos que `al_movil::Fallo` del PC.
     */
    sealed class Fallo(val aparato: String) {
        /** Ni contesta ni se anuncia: PixPin no está a la vista allí, o no hay Wi-Fi. */
        class NoEsta(aparato: String) : Fallo(aparato)
        /** Un PixPin de antes: no conoce `suelto`. */
        class SinSoporte(aparato: String) : Fallo(aparato)
        class Ocupado(aparato: String) : Fallo(aparato)
        class OtraVersion(aparato: String) : Fallo(aparato)
        /** Tiene un lienzo delante y nada de lo mandado era una foto. */
        class SoloFotos(aparato: String) : Fallo(aparato)
        class Cortado(aparato: String, val motivo: String) : Fallo(aparato)
    }

    /** El aviso de cada fila, con los textos del PC (`al-movil-*`, `ew-grupo-*`). */
    fun texto(e: Estado?, seVe: Boolean): String = when (e) {
        null -> if (seVe) "En la misma Wi-Fi" else "No se ve ahora: abre PixPin en ese aparato"
        is Estado.Saliendo -> "Enviando…"
        is Estado.Hecho -> texto(e.entregado)
        is Estado.Fallo -> texto(e.fallo)
    }

    fun texto(e: Entregado): String {
        val n = e.llegadas.size
        val chat = e.llegadas.firstNotNullOfOrNull { it.chat }
        val base = when {
            n > 0 && e.llegadas.all { it.donde == Protocolo.SUELTO_EN_EL_LIENZO } ->
                if (n == 1) "Enviada al lienzo de ${e.aparato}" else "$n imágenes enviadas al lienzo de ${e.aparato}"
            n > 0 && chat != null && e.llegadas.all { it.donde == Protocolo.SUELTO_EN_EL_CHAT_ABIERTO } ->
                if (n == 1) "Enviado al chat «$chat» de ${e.aparato}" else "$n archivos enviados al chat «$chat» de ${e.aparato}"
            else -> "${e.aparato} lo guardó en la conversación general (no tenía un chat ni un lienzo abierto)"
        }
        if (e.negados == 0) return base
        return base + "\n" + if (e.negados == 1) "1 archivo no se envió: no era una foto y tiene un lienzo abierto"
        else "${e.negados} archivos no se enviaron: no eran fotos y tiene un lienzo abierto"
    }

    fun texto(f: Fallo): String = when (f) {
        is Fallo.NoEsta -> "${f.aparato} no contesta: abre PixPin en ese aparato"
        // El del PC dice «te abro Enviar por Wi-Fi»: aquí ya está abierta, con el código debajo.
        is Fallo.SinSoporte -> "Actualiza PixPin en ${f.aparato}: aún no recibe archivos directos. Que use el código de abajo."
        is Fallo.Ocupado -> "${f.aparato} está sincronizando con otro aparato. Prueba otra vez en un momento."
        is Fallo.OtraVersion -> "${f.aparato} tiene otra versión de PixPin: actualiza los dos"
        is Fallo.SoloFotos -> "${f.aparato} tiene un lienzo abierto: solo acepta fotos. Que abra un chat para recibir archivos."
        is Fallo.Cortado -> "No se pudo enviar a ${f.aparato}: ${f.motivo}"
    }

    // ---- El cable --------------------------------------------------------

    /**
     * **El tipo de un archivo por su extensión**, cuando el sistema no lo dio: quien recibe lo mira
     * para decidir (una imagen puede ir a un lienzo; lo demás, solo a un chat). La tabla de
     * `al_lienzo::mime_de` del PC.
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

    /** Un archivo para mandar: el fichero, con qué nombre y de qué tipo. */
    data class Archivo(val fichero: File, val nombre: String, val mime: String)

    /** Un error del otro, con su caso cuando lo tiene (`error_remoto` del PC). */
    private fun deError(e: String, aparato: String): Fallo = when {
        e == Protocolo.OCUPADO -> Fallo.Ocupado(aparato)
        e.startsWith("No sé qué es") && e.contains("suelto") -> Fallo.SinSoporte(aparato)
        e.endsWith(Protocolo.SOLO_FOTOS) -> Fallo.SoloFotos(aparato)
        else -> Fallo.Cortado(aparato, e)
    }

    /** Lo que se lanza dentro para salir con un [Fallo] ya decidido. */
    private class Corte(val fallo: Fallo) : IOException(fallo.aparato)

    /**
     * **Manda [archivos] por una conexión ya abierta** con un aparato del grupo: el `hola` de
     * siempre, un `suelto` con su «vale» por archivo y el `adios` (`Conexion` de `al_lienzo.rs`).
     *
     * **No ocupa** este aparato ([Protocolo.ocupar]) como una vuelta de sincronizar: mandar no
     * escribe nada de aquí, y no tiene sentido que espere a que acabe otra cosa. Quien recibe sí
     * se ocupa, y si está sincronizando lo dice ([Fallo.Ocupado]).
     *
     * [avance] recibe los bytes llevados del total. [alSaludar] recibe el puerto donde escucha el
     * otro, para apuntarlo. Devuelve lo entregado o el fallo; nunca lanza.
     */
    fun mandar(
        entrada: InputStream,
        salida: OutputStream,
        identidad: Identidad,
        nombre: String,
        archivos: List<Archivo>,
        miPuerto: Int = 0,
        ahora: () -> Long = System::currentTimeMillis,
        alSaludar: (Aparato, Int) -> Unit = { _, _ -> },
        avance: (Long, Long) -> Unit = { _, _ -> }
    ): Result<Entregado> {
        val codigo = identidad.codigo ?: return Result.failure(Corte(Fallo.Cortado(nombre, "este aparato no está en un grupo")))
        var quien = nombre
        return try {
            val canal = Canal.abrir(entrada, salida, Grupo.clave(codigo), inicia = true)
            Protocolo.enviar(canal, Protocolo.Peticion("hola", hola = Protocolo.Hola(identidad.yo, identidad.miembros, ahora(), puerto = miPuerto)))
            // El código no viaja: quien tiene otro no descifra el saludo y cuelga sin decir nada.
            // Un corte justo aquí es casi siempre eso (`traducir` de `al_movil.rs`).
            val r = try {
                Protocolo.leerRespuestaSinLanzar(canal)
            } catch (e: java.io.EOFException) {
                throw Canal.CodigoDistinto()
            } catch (e: java.net.SocketException) {
                throw Canal.CodigoDistinto()
            }
            r.error?.let { throw Corte(deError(it, quien)) }
            val hola = r.hola ?: throw Corte(Fallo.Cortado(quien, "no saludó"))
            quien = hola.yo.nombre.ifBlank { quien }
            if (hola.version != Protocolo.VERSION) throw Corte(Fallo.OtraVersion(quien))
            alSaludar(hola.yo, hola.puerto)
            val total = archivos.sumOf { it.fichero.length() }
            var llevados = 0L
            val llegadas = ArrayList<Llegada>()
            var negados = 0
            for (a in archivos) {
                // Antes de pedir nada: si el fichero no está, no se le promete al otro algo que no llegará.
                if (!a.fichero.isFile) throw Corte(Fallo.Cortado(quien, "«${a.nombre}» ya no está"))
                val sale = Protocolo.salidaDeArchivo(a.fichero)
                Protocolo.enviar(canal, Protocolo.Peticion("suelto", nombre = a.nombre, mime = a.mime, bytes = sale.largo, destino = DESTINO_ABIERTO))
                val vale = Protocolo.leerRespuestaSinLanzar(canal)
                val noVale = vale.error
                if (noVale != null) {
                    // Un lienzo delante y no es una foto: ese no, los demás sí (no salió ningún trozo).
                    val f = deError(noVale, quien)
                    if (f !is Fallo.SoloFotos) throw Corte(f)
                    negados++
                    llevados += sale.largo
                    avance(llevados, total)
                    continue
                }
                val desde = llevados
                val bien = sale.mandar(canal) { n -> llevados += n; avance(llevados, total) }
                val fin = Protocolo.leerRespuestaSinLanzar(canal)
                if (bien == null) throw Corte(Fallo.Cortado(quien, "«${a.nombre}» cambió mientras se mandaba"))
                fin.error?.let { throw Corte(deError(it, quien)) }
                llevados = desde + sale.largo
                llegadas += Llegada(fin.donde ?: Protocolo.SUELTO_EN_EL_CHAT, fin.chat)
            }
            runCatching {
                Protocolo.enviar(canal, Protocolo.Peticion("adios"))
                Protocolo.leerRespuestaSinLanzar(canal)
            }
            if (llegadas.isEmpty() && negados > 0) throw Corte(Fallo.SoloFotos(quien))
            Result.success(Entregado(quien, llegadas, negados))
        } catch (e: Corte) {
            Result.failure(e)
        } catch (e: Canal.CodigoDistinto) {
            Result.failure(Corte(Fallo.Cortado(quien, "¿tiene el mismo código de grupo?")))
        } catch (e: java.net.ConnectException) {
            Result.failure(Corte(Fallo.NoEsta(quien)))
        } catch (e: java.net.SocketTimeoutException) {
            Result.failure(Corte(Fallo.NoEsta(quien)))
        } catch (e: Exception) {
            Result.failure(Corte(Fallo.Cortado(quien, e.message ?: e.javaClass.simpleName)))
        }
    }

    /** El [Fallo] de un resultado de [mandar]. */
    fun falloDe(e: Throwable): Fallo = (e as? Corte)?.fallo ?: Fallo.Cortado("", e.message ?: e.javaClass.simpleName)

    /**
     * **Abre la conexión con [fila] y le manda [archivos]**, apuntando dónde escucha para la
     * próxima. En un hilo que no sea el de la pantalla: conectar puede tardar segundos.
     */
    fun mandarA(disco: Disco, fila: Fila, archivos: List<Archivo>, miPuerto: Int, avance: (Long, Long) -> Unit): Result<Entregado> {
        val nombre = fila.aparato.nombre.ifBlank { "Un aparato" }
        val host = fila.host ?: return Result.failure(Corte(Fallo.NoEsta(nombre)))
        val socket = try {
            Red.conectar(java.net.InetAddress.getByName(host), fila.puerto)
        } catch (e: Exception) {
            return Result.failure(Corte(Fallo.NoEsta(nombre)))
        }
        return socket.use { s ->
            mandar(
                s.getInputStream(), s.getOutputStream(), disco.identidad.leer(), nombre, archivos, miPuerto,
                alSaludar = { otro, puerto -> if (otro.id == fila.aparato.id) disco.apuntarDireccion(otro.id, host, puerto.takeIf { it > 0 } ?: fila.puerto) },
                avance = avance
            )
        }
    }
}
