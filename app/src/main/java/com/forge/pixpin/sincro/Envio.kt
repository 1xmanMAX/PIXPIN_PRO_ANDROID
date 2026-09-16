package com.forge.pixpin.sincro

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import kotlinx.serialization.Serializable

/**
 * **Pasar algo a otra persona una sola vez, por la misma Wi-Fi.**
 *
 * No es sincronizar: no hace falta grupo, no queda nada vinculado y no aparece después en
 * Sincronizar. Quien envía enseña un **código de seis cifras** (y el mismo en un QR); quien
 * recibe lo teclea o lo escanea, ve qué le llega, acepta, y al acabar **el código deja de
 * existir** y la conexión se cierra.
 *
 * Usa las mismas piezas que la sincronización —el [Canal] cifrado con una clave sacada del
 * código, los trozos con su resumen detrás—, así que lo que llega es lo que salió.
 *
 * **Quien recibe elige** (15-sep-2026). Cada cosa viaja con sus tres códigos ([Codigos]). Si quien
 * recibe ya tiene algo con **los tres iguales**, elige entre **ponerlo al día** con lo que llega o
 * **crearlo como nuevo** (con códigos nuevos, que es otra cosa desde ese momento). Si no coinciden
 * los tres, se crea aparte: ante la duda, duplicar y no pisar.
 */
object Envio {

    const val TIPO = "_pixpinenvio._tcp."
    const val CIFRAS = 6
    /** Intentos con un código equivocado antes de dar el código por quemado. */
    const val INTENTOS = 5

    fun nuevoCodigo(azar: SecureRandom = SecureRandom()): String =
        (1..CIFRAS).joinToString("") { azar.nextInt(10).toString() }

    fun limpiar(tecleado: String): String = tecleado.filter { it.isDigit() }.take(CIFRAS)

    fun valido(codigo: String) = codigo.length == CIFRAS && codigo.all { it.isDigit() }

    /** `482 913`: en dos grupos de tres, que se dicta y se lee sin perderse. */
    fun legible(codigo: String) = if (codigo.length == CIFRAS) codigo.take(3) + " " + codigo.drop(3) else codigo

    fun clave(codigo: String): ByteArray = Grupo.clave("envio-$codigo")

    fun etiqueta(codigo: String): String = Grupo.etiqueta(clave(codigo))

    /** Lo que lleva el QR: el código y dónde está quien envía, para conectar sin buscar. */
    fun textoDelQr(codigo: String, host: String?, puerto: Int): String =
        "pixpin-envio:1:$codigo:${host.orEmpty()}:$puerto"

    /**
     * **Y el QR del otro sentido** (16-sep-2026, idea del usuario): el que enseña **quien va a
     * recibir**, para que quien envía lo escanee y le mande.
     *
     * Para qué: mandarle algo a un ordenador o a tres personas concretas. Con el sentido de
     * siempre hay que dictarles el código a todos y esperar a que lo tecleen; así cada uno enseña
     * su pantalla, quien manda pasa la cámara por ellas y ya está. Es el mismo protocolo con los
     * papeles cambiados: quien recibe **espera** y quien envía **llama**, así que el canal lo
     * empieza el que llama. Ver [Emisor.atender] y [Receptor.conectar], parámetro `inicia`.
     */
    const val TIPO_RECIBIR = "_pixpinrecibe._tcp."

    fun textoDelQrDeRecepcion(codigo: String, host: String?, puerto: Int): String =
        "pixpin-recibe:1:$codigo:${host.orEmpty()}:$puerto"

    data class DelQr(val codigo: String, val host: String?, val puerto: Int, val esperaRecibir: Boolean = false)

    fun leerQr(texto: String): DelQr? {
        val p = texto.trim().split(':')
        if (p.size < 5) return null
        val recibir = p[0] == "pixpin-recibe"
        if (p[0] != "pixpin-envio" && !recibir) return null
        val codigo = p[2].takeIf(::valido) ?: return null
        return DelQr(codigo, p[3].ifBlank { null }, p[4].toIntOrNull() ?: 0, esperaRecibir = recibir)
    }

    /** Una cosa que se manda. */
    @Serializable
    data class Elemento(
        /** [ARCHIVO] o [PROYECTO] (un `.pixpin`). */
        val tipo: String,
        val nombre: String,
        val bytes: Long,
        val mime: String? = null,
        /**
         * **Su seña entre envíos**: la misma cosa mandada otra vez lleva la misma. Un proyecto, la
         * suya ([com.forge.pixpin.motor.Proyecto.origen] o su id); un archivo, quién lo manda y
         * cómo se llama.
         */
        val identidad: String,
        /** Un [LIENZO] suelto: la identidad del proyecto del que es, para caer en el mismo. */
        val proyecto: String? = null,
        /** Y cómo se llama ese proyecto, para crearlo con el mismo nombre si no se tiene. */
        val proyectoNombre: String? = null,
        /** Cuándo se creó en el aparato que lo manda: ordena el chat igual en los dos. */
        val creado: Long = 0,
        /**
         * **Sus tres códigos** (15-sep-2026, ver [Codigos]): el único, el de chat (`47·K7Q2`) y la
         * fecha de [creado]; en un proyecto, el aparato donde nació en vez del código de chat. Quien
         * recibe solo puede poner al día lo que tenga con los tres iguales.
         */
        val uid: String? = null,
        val codigoDeChat: String? = null,
        val aparato: String? = null
    )

    const val ARCHIVO = "archivo"
    const val PROYECTO = "proyecto"
    /** Un lienzo (una hoja) de un proyecto, solo: un `.pixpin` con esa hoja. */
    const val LIENZO = "lienzo"

    @Serializable
    data class Oferta(
        val de: String,
        val deId: String,
        val elementos: List<Elemento>,
        val deCodigo: String = "",
        /**
         * **Quien envía no aprobó a este aparato.** Desde que un envío puede ir a varios
         * (16-sep-2026), quien manda ve la lista de los que se conectan y aprueba a los que quiere;
         * al que no, se le dice y se le cierra, en vez de cortarle sin más. Ver [Emisor.atender].
         */
        val rechazado: String? = null
    )

    @Serializable
    internal data class Aviso(val t: String, val nombre: String = "", val id: String = "", val bytes: Long = 0, val error: String? = null)

    private fun mandar(c: Canal, a: Aviso) = c.enviar(Protocolo.JSON_, Protocolo.json.encodeToString(Aviso.serializer(), a).toByteArray())

    private fun leer(c: Canal): Aviso {
        val (tipo, datos) = c.recibir()
        if (tipo != Protocolo.JSON_) throw IOException("Se esperaba un aviso")
        val a = Protocolo.json.decodeFromString(Aviso.serializer(), datos.decodeToString())
        a.error?.let { throw IOException(it) }
        return a
    }

    enum class Final {
        ENVIADO,
        /** El otro aparato dijo que no. */
        RECHAZADO,
        /** Este aparato no aprobó al otro: ni se le ofreció. */
        SIN_APROBAR
    }

    /**
     * **Quien envía.** Atiende una conexión: se presenta, dice qué manda, y si le aceptan, lo manda.
     * [alConocer] recibe el nombre de quien se conectó, en cuanto se sabe.
     */
    class Emisor(private val yo: Aparato, private val cosas: List<Pair<Elemento, File>>) {

        /**
         * [alConocer] recibe el nombre de quien se conectó y **decide si se le manda**: puede
         * quedarse esperando a que el usuario apruebe. Si dice que no, al otro se le contesta con
         * una oferta [Oferta.rechazado] —sabe que no le han aprobado, no que se cayó la red—.
         */
        fun atender(
            entrada: InputStream, salida: OutputStream, codigo: String,
            alConocer: (String) -> Boolean = { true }, avance: (hechos: Long, total: Long) -> Unit = { _, _ -> },
            /** Cierto cuando es **este** aparato el que llama, porque quien recibe estaba esperando. */
            inicia: Boolean = false
        ): Final {
            val canal = Canal.abrir(entrada, salida, clave(codigo), inicia = inicia)
            val hola = leer(canal)
            if (hola.t != "hola") throw IOException("Faltó el saludo")
            if (!alConocer(hola.nombre)) {
                canal.enviar(
                    Protocolo.JSON_,
                    Protocolo.json.encodeToString(
                        Oferta.serializer(),
                        Oferta(yo.nombre, yo.id, emptyList(), yo.codigo, rechazado = "${yo.nombre} no aprobó el envío a este aparato.")
                    ).toByteArray()
                )
                canal.vaciar()
                return Final.SIN_APROBAR
            }
            val oferta = Oferta(yo.nombre, yo.id, cosas.map { it.first }, yo.codigo)
            canal.enviar(Protocolo.JSON_, Protocolo.json.encodeToString(Oferta.serializer(), oferta).toByteArray())
            val respuesta = leer(canal)
            if (respuesta.t != "acepto") { canal.vaciar(); return Final.RECHAZADO }
            val total = cosas.sumOf { it.second.length() }
            var hechos = 0L
            for ((e, archivo) in cosas) {
                val sale = Protocolo.salidaDeArchivo(archivo)
                mandar(canal, Aviso("archivo", nombre = e.nombre, bytes = sale.largo))
                sale.mandar(canal) { n -> hechos += n; avance(hechos, total) }
                    ?: throw IOException("«${e.nombre}» cambió mientras se mandaba. Vuelve a intentarlo.")
            }
            if (leer(canal).t != "listo") throw IOException("El otro no confirmó")
            return Final.ENVIADO
        }
    }

    /** **Quien recibe.** [conectar] se presenta y trae la oferta; después, [aceptar] o [rechazar]. */
    class Receptor private constructor(private val canal: Canal, val oferta: Oferta) {

        fun rechazar() {
            runCatching { mandar(canal, Aviso("no")); canal.vaciar() }
        }

        /** Acepta y recibe todo en [carpeta]. Devuelve cada cosa con su archivo. */
        fun aceptar(carpeta: File, avance: (hechos: Long, total: Long) -> Unit = { _, _ -> }): List<Pair<Elemento, File>> {
            mandar(canal, Aviso("acepto"))
            carpeta.mkdirs()
            val total = oferta.elementos.sumOf { it.bytes }
            var hechos = 0L
            val salida = ArrayList<Pair<Elemento, File>>()
            for (e in oferta.elementos) {
                val cabecera = leer(canal)
                if (cabecera.t != "archivo") throw IOException("Se esperaba un archivo")
                val destino = libre(carpeta, nombreSano(e.nombre))
                val bien = destino.outputStream().buffered().use { out ->
                    Protocolo.recibirTrozos(canal, cabecera.bytes, out) { n -> hechos += n; avance(hechos, total) }
                }
                if (bien == null) { destino.delete(); throw IOException("«${e.nombre}» llegó a medias. Vuelve a intentarlo.") }
                salida += e to destino
            }
            mandar(canal, Aviso("listo"))
            canal.vaciar()
            return salida
        }

        companion object {
            fun conectar(
                entrada: InputStream, salida: OutputStream, codigo: String, yo: Aparato,
                /** Falso cuando es el otro el que llama: este aparato estaba esperando a que le enviaran. */
                inicia: Boolean = true
            ): Receptor {
                val canal = Canal.abrir(entrada, salida, clave(codigo), inicia = inicia)
                mandar(canal, Aviso("hola", nombre = yo.nombre, id = yo.id))
                val (tipo, datos) = canal.recibir()
                if (tipo != Protocolo.JSON_) throw IOException("Se esperaba la oferta")
                return Receptor(canal, Protocolo.json.decodeFromString(Oferta.serializer(), datos.decodeToString()))
            }
        }
    }

    fun nombreSano(nombre: String): String =
        nombre.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().take(120).ifBlank { "archivo" }

    private fun libre(carpeta: File, nombre: String): File {
        var f = File(carpeta, nombre)
        var i = 2
        val base = nombre.substringBeforeLast('.', nombre)
        val ext = nombre.substringAfterLast('.', "").let { if (it.isEmpty() || it == nombre) "" else ".$it" }
        while (f.exists()) { f = File(carpeta, "$base ($i)$ext"); i++ }
        return f
    }
}
