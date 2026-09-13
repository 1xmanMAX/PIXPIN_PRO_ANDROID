package com.forge.pixpin.sincro

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * **La conversación entre dos aparatos.**
 *
 * Uno **dirige** —el que pulsó «sincronizar», con su [Sesion]— y el otro **responde** —con su
 * [Respondedor]—: el que dirige pregunta qué hay, decide y manda o pide; el otro contesta y
 * escribe lo que le llega. Así las preguntas al usuario salen en una sola pantalla, la de quien
 * lo pidió, y el otro aparato no tiene que estar pendiente de nada.
 *
 * Cada chat se sincroniza en dos pasos:
 *
 * 1. **Los mensajes y el proyecto.** Se comparan las señas; lo que cambió en un solo lado pasa
 *    solo, lo que cambió en los dos se pregunta. El proyecto se junta sin preguntar ([Mezcla]).
 * 2. **Los archivos**, ya con los dos chats iguales: los adjuntos, los lienzos, las tablas, el PDF.
 *    Un lienzo dibujado en los dos lados se pregunta, nombrado por su mensaje.
 *
 * Al acabar, los dos guardan **lo acordado** ([Base]), que es lo que deja saber la próxima vez
 * quién se movió.
 */
object Protocolo {
    /** 2: cada archivo lleva detrás su resumen, y lo acordado se apunta solo si quedó igual en los dos. */
    const val VERSION = 2
    const val JSON_: Byte = 1
    const val TROZO: Byte = 2

    val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    /** Cuántos mensajes van en cada ida: un chat de miles de notas no cabe en un tramo. */
    const val MENSAJES_POR_TANDA = 200

    /**
     * **Una sincronización a la vez en cada aparato.** Si dos aparatos pulsan «sincronizar» a la
     * vez uno con otro, cada uno sería a la vez el que dirige y el que responde, escribiendo el
     * mismo chat desde dos hilos. El segundo que llega recibe «ocupado» y lo dice.
     */
    private val ocupados: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Por carpeta y no para todo el proceso: en las pruebas, los dos «aparatos» viven en la misma JVM. */
    fun ocupar(disco: Disco): Boolean = ocupados.add(disco.filesDir.absolutePath)
    fun soltar(disco: Disco) { ocupados.remove(disco.filesDir.absolutePath) }
    const val OCUPADO = "Está sincronizando con otro aparato. Prueba otra vez en un momento."

    @Serializable
    data class Hola(
        val yo: Aparato,
        val miembros: List<Aparato> = emptyList(),
        val reloj: Long = 0,
        val version: Int = VERSION,
        /** Viene a unirse al grupo: todavía no tiene letra. */
        val unirme: Boolean = false,
        /** Dónde escucha, para recordarlo y llamarle directamente la próxima vez. */
        val puerto: Int = 0
    )

    @Serializable
    data class Peticion(
        val t: String,
        val hola: Hola? = null,
        val chat: String? = null,
        val senas: List<String> = emptyList(),
        val poner: List<String> = emptyList(),
        val borrar: List<String> = emptyList(),
        val proyecto: String? = null,
        val ruta: String? = null,
        val bytes: Long = 0,
        val base: Base? = null
    )

    @Serializable
    data class Respuesta(
        val error: String? = null,
        val hola: Hola? = null,
        val chats: List<Chat> = emptyList(),
        val apuntes: List<Diferencia.Apunte> = emptyList(),
        val proyecto: String? = null,
        val selloDeBase: String? = null,
        val mensajes: List<String> = emptyList(),
        val archivos: List<ArchivoInfo> = emptyList(),
        val bytes: Long = 0,
        /** Detrás de un archivo: el resumen de lo que se mandó. */
        val resumen: String? = null,
        /** El archivo se estaba escribiendo mientras se mandaba: no vale, se deja para la próxima. */
        val saltado: Boolean = false
    )

    internal fun enviar(c: Canal, p: Peticion) = c.enviar(JSON_, json.encodeToString(Peticion.serializer(), p).toByteArray())
    internal fun enviar(c: Canal, r: Respuesta) = c.enviar(JSON_, json.encodeToString(Respuesta.serializer(), r).toByteArray())

    internal fun leerPeticion(c: Canal): Peticion {
        val (tipo, datos) = c.recibir()
        if (tipo != JSON_) throw IOException("Se esperaba una petición")
        return json.decodeFromString(Peticion.serializer(), datos.decodeToString())
    }

    internal fun leerRespuesta(c: Canal): Respuesta {
        val (tipo, datos) = c.recibir()
        if (tipo != JSON_) throw IOException("Se esperaba una respuesta")
        val r = json.decodeFromString(Respuesta.serializer(), datos.decodeToString())
        r.error?.let { throw IOException(it) }
        return r
    }

    /** Un archivo listo para salir: cuánto ocupa por el cable y cómo se manda en trozos. [mandar] devuelve el resumen de lo mandado, o `null` si no vale porque cambió mientras se leía. */
    internal class Salida(val largo: Long, val mandar: (Canal, (Long) -> Unit) -> String?)

    /**
     * Prepara un archivo para mandarlo. Los de texto (lienzos, croquis, tablas) salen ya
     * portátiles y descomprimidos; los demás, tal cual y leídos a trozos, que un PDF de 300 MB no
     * cabe en memoria.
     *
     * **Detrás de los trozos va siempre una cola** con el resumen de lo mandado. Y si el archivo
     * cambió mientras se leía —el editor terminando de guardar, el PDF del proyecto rehaciéndose
     * al cerrar una página— la cola dice «saltado»: lo que salió puede ser medio archivo viejo y
     * medio nuevo, y eso no se escribe en el otro lado. Sin esto, un PDF podía llegar roto y
     * quedar apuntado como ya sincronizado.
     */
    internal fun salida(disco: Disco, rel: String): Salida {
        if (disco.esTexto(rel)) {
            val bytes = disco.textoDe(rel).toByteArray()
            return Salida(bytes.size.toLong()) { c, avance ->
                var i = 0
                while (i < bytes.size) {
                    val n = minOf(Canal.TOPE_DE_TRAMO, bytes.size - i)
                    c.enviar(TROZO, bytes, i, n); i += n; avance(n.toLong())
                }
                val resumen = sha256(bytes)
                enviar(c, Respuesta(resumen = resumen))
                resumen
            }
        }
        return salidaDeArchivo(disco.archivo(rel))
    }

    /** Un archivo cualquiera, tal cual y a trozos, con su cola. */
    internal fun salidaDeArchivo(archivo: java.io.File): Salida {
        val largo = archivo.length()
        val fecha = archivo.lastModified()
        return Salida(largo) { c, avance ->
            val md = java.security.MessageDigest.getInstance("SHA-256")
            var corto = false
            archivo.inputStream().use { entrada ->
                val buf = ByteArray(Canal.TOPE_DE_TRAMO)
                var quedan = largo
                while (quedan > 0) {
                    var n = 0
                    val quiero = minOf(buf.size.toLong(), quedan).toInt()
                    while (n < quiero) {
                        val r = entrada.read(buf, n, quiero - n)
                        if (r < 0) break
                        n += r
                    }
                    // Se acortó a medias: se rellena para cumplir lo prometido y la cola lo invalida.
                    if (n < quiero) { corto = true; java.util.Arrays.fill(buf, n, quiero, 0); n = quiero }
                    md.update(buf, 0, n)
                    c.enviar(TROZO, buf, 0, n)
                    quedan -= n
                    avance(n.toLong())
                }
            }
            val cambio = corto || archivo.length() != largo || archivo.lastModified() != fecha
            val resumen = hex(md.digest())
            enviar(c, Respuesta(resumen = resumen, saltado = cambio))
            if (cambio) null else resumen
        }
    }

    /**
     * Recibe [largo] bytes en trozos hacia [salida] y su cola. Devuelve el resumen de lo llegado,
     * o `null` si el que manda dice que no vale (cambió mientras se leía); lanza si lo llegado no
     * es lo que se mandó.
     */
    internal fun recibirTrozos(c: Canal, largo: Long, salida: OutputStream, avance: (Long) -> Unit): String? {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        var quedan = largo
        while (quedan > 0) {
            val (tipo, datos) = c.recibir()
            if (tipo != TROZO) throw IOException("Se esperaba un trozo de archivo")
            salida.write(datos)
            md.update(datos)
            quedan -= datos.size
            avance(datos.size.toLong())
        }
        val cola = leerRespuesta(c)
        if (cola.saltado) return null
        val resumen = hex(md.digest())
        if (cola.resumen != null && cola.resumen != resumen) throw IOException("Un archivo llegó distinto de como salió")
        return resumen
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}

/**
 * **El lado que responde.** Atiende una conexión entera y la cierra al acabar. Si viene alguien a
 * unirse al grupo, le da la primera letra libre.
 */
class Respondedor(
    private val disco: Disco,
    /** Qué está pasando, en palabras, para enseñarlo en la pantalla del que responde. */
    private val estado: (String) -> Unit = {},
    private val ahora: () -> Long = System::currentTimeMillis,
    /** Dónde escucha este aparato, para decírselo al otro. */
    private val miPuerto: Int = 0,
    /** Quién vino y en qué puerto escucha él: se recuerda para llamarle directamente. */
    private val alSaludar: (Aparato, Int) -> Unit = { _, _ -> }
) {
    fun atender(entrada: InputStream, salida: OutputStream) {
        val id = disco.identidad.leer()
        val codigo = id.codigo ?: throw IOException("Este aparato no está en un grupo")
        val canal = Canal.abrir(entrada, salida, Grupo.clave(codigo), inicia = false)
        val primera = Protocolo.leerPeticion(canal)
        val hola = primera.hola ?: throw IOException("Faltó el saludo")
        val otro: Aparato
        if (hola.unirme) {
            // Se une: la primera letra que no tenga nadie que este aparato conozca.
            val actual = disco.identidad.leer()
            val miembros = Grupo.juntar(actual.miembros.ifEmpty { listOf(actual.yo) }, hola.miembros)
            // Si vuelve alguien que ya estuvo, recupera su letra: lo que selló con ella sigue siendo suyo.
            val libre = miembros.firstOrNull { it.id == hola.yo.id }?.letra?.firstOrNull()
                ?: Sena.libre(miembros.filter { it.id != hola.yo.id }.mapNotNull { it.letra?.firstOrNull() }.toSet())
                ?: throw IOException("El grupo ya tiene 26 aparatos")
            otro = hola.yo.copy(letra = libre.toString(), desde = ahora())
            val juntos = Grupo.juntar(miembros.filter { it.id != otro.id }, emptyList()) + otro
            disco.identidad.guardar(actual.copy(miembros = juntos))
            // La letra que se le dio va en su propia ficha dentro de los miembros.
            Protocolo.enviar(canal, Protocolo.Respuesta(hola = Protocolo.Hola(actual.yo, juntos, ahora(), puerto = miPuerto)))
            disco.avisar(Disco.Cambio.IDENTIDAD)
            estado("${otro.nombre} se unió al grupo con la letra ${otro.letra}")
        } else {
            val actual = disco.identidad.leer()
            val juntos = Grupo.juntar(actual.miembros.ifEmpty { listOf(actual.yo) }, hola.miembros, quienHabla = hola.yo)
            disco.identidad.guardar(actual.copy(miembros = juntos))
            disco.avisar(Disco.Cambio.IDENTIDAD)
            otro = hola.yo
            Protocolo.enviar(canal, Protocolo.Respuesta(hola = Protocolo.Hola(actual.yo, juntos, ahora(), puerto = miPuerto)))
        }
        alSaludar(otro, hola.puerto)
        if (!Protocolo.ocupar(disco)) {
            Protocolo.enviar(canal, Protocolo.Respuesta(error = Protocolo.OCUPADO))
            canal.vaciar()
            return
        }
        try {
            responder(canal, otro)
        } finally {
            Protocolo.soltar(disco)
        }
    }

    private fun responder(canal: Canal, otro: Aparato) {
        val yo = disco.identidad.leer()
        // Lo que es de cada chat, calculado al preguntar por sus archivos: sin esto, cada archivo
        // pedido volvería a recorrer el chat y sus lienzos enteros.
        var alcance: Pair<String, Set<String>>? = null
        while (true) {
            val p = runCatching { Protocolo.leerPeticion(canal) }.getOrElse { if (it is java.io.EOFException) return else throw it }
            try {
                when (p.t) {
                    "adios" -> { Protocolo.enviar(canal, Protocolo.Respuesta()); canal.vaciar(); return }
                    "catalogo" -> Protocolo.enviar(canal, Protocolo.Respuesta(chats = disco.chats()))
                    "inventario" -> {
                        val chat = p.chat!!
                        estado("Comparando «${nombreDe(chat)}» con ${otro.nombre}…")
                        yo.letra?.let { disco.sellar(it) }
                        disco.adoptarDocumentos()
                        Protocolo.enviar(canal, Protocolo.Respuesta(
                            apuntes = disco.apuntes(chat),
                            proyecto = disco.proyectoPortatil(chat),
                            selloDeBase = disco.base(otro.id, chat)?.sello
                        ))
                    }
                    "mensajes" -> {
                        val suyos = disco.mensajesPorSena(p.chat!!)
                        Protocolo.enviar(canal, Protocolo.Respuesta(mensajes = p.senas.mapNotNull { suyos[it]?.let(disco::portatil) }))
                    }
                    "aplicar" -> {
                        val chat = p.chat!!
                        disco.aplicarMensajes(chat, p.poner, p.borrar)
                        disco.proyectoDe(p.proyecto)?.let { disco.guardarProyecto(it) }
                        Protocolo.enviar(canal, Protocolo.Respuesta())
                    }
                    "archivos" -> {
                        val lista = disco.archivos(p.chat!!)
                        alcance = p.chat to lista.map { it.ruta }.toSet()
                        Protocolo.enviar(canal, Protocolo.Respuesta(archivos = lista))
                    }
                    "pon" -> {
                        val rel = p.ruta!!
                        estado("Recibiendo ${rel.substringAfterLast('/')}")
                        val entero = disco.escribirArchivo(rel) { salida -> Protocolo.recibirTrozos(canal, p.bytes, salida) {} != null }
                        Protocolo.enviar(canal, Protocolo.Respuesta(saltado = !entero))
                    }
                    "dame" -> {
                        val rel = p.ruta!!
                        val suyos = alcance?.takeIf { it.first == p.chat }?.second ?: disco.alcance(p.chat!!).keys
                        require(disco.permitida(rel) && rel in suyos) { "No es de este chat: $rel" }
                        estado("Mandando ${rel.substringAfterLast('/')}")
                        val sale = Protocolo.salida(disco, rel)
                        Protocolo.enviar(canal, Protocolo.Respuesta(bytes = sale.largo))
                        sale.mandar(canal) {}
                    }
                    "base" -> {
                        disco.guardarBase(otro.id, p.chat!!, p.base ?: Base())
                        disco.apuntarVez(otro.id, ahora())
                        Protocolo.enviar(canal, Protocolo.Respuesta())
                        estado("«${nombreDe(p.chat)}» al día con ${otro.nombre}")
                    }
                    else -> Protocolo.enviar(canal, Protocolo.Respuesta(error = "No sé qué es «${p.t}»"))
                }
            } catch (e: IOException) {
                throw e
            } catch (e: Exception) {
                Protocolo.enviar(canal, Protocolo.Respuesta(error = e.message ?: e.javaClass.simpleName))
            }
        }
    }

    private fun nombreDe(chat: String) = disco.chats().firstOrNull { it.id == chat }?.nombre ?: chat
}

/**
 * **El lado que dirige.** Se usa paso a paso desde la pantalla, en un hilo aparte: entre
 * [preparar] y [aplicar] puede haber que preguntarle al usuario.
 */
class Sesion private constructor(
    private val canal: Canal,
    private val disco: Disco,
    /** El otro aparato, tal como se presentó. */
    val otro: Aparato,
    /** Cuánto va adelantado el reloj del otro, en milisegundos (negativo si va atrasado). */
    val desfase: Long,
    /** En qué puerto escucha el otro. */
    val puertoDelOtro: Int = 0
) {
    val enviados get() = canal.enviados
    val recibidos get() = canal.recibidos

    /** Una pregunta para el usuario. [clave] es la seña o la ruta. */
    data class Pregunta(
        val clave: String,
        val porque: Diferencia.Choque,
        val titulo: String,
        val mio: Vista?,
        val suyo: Vista?,
        /** Lo que viene marcado: `true` es quedarse con lo de este aparato. */
        val porOmision: Boolean
    )

    data class Vista(val texto: String, val cuando: Long, val bytes: Long = 0, val borrado: Boolean = false)

    class Preparado internal constructor(
        val chat: String,
        val pasos: List<Diferencia.Paso>,
        val preguntas: List<Pregunta>,
        internal val base: Base,
        internal val suyoProyecto: String?,
        internal val mios: Map<String, Diferencia.Apunte>,
        internal val suyos: Map<String, Diferencia.Apunte>
    )

    class PreparadoArchivos internal constructor(
        val chat: String,
        val pasos: List<Diferencia.Paso>,
        val preguntas: List<Pregunta>,
        internal val base: Base,
        internal val proyecto: String?,
        internal val mios: Map<String, ArchivoInfo>,
        internal val suyos: Map<String, ArchivoInfo>
    ) {
        /** Cuántos bytes hay que mover como mucho, para la barra. */
        val bytes: Long get() = pasos.sumOf { p -> (mios[p.sena]?.bytes ?: 0L).coerceAtLeast(suyos[p.sena]?.bytes ?: 0L) }
    }

    /**
     * **Lo que pasó de un lado a otro en esta vuelta, con su resumen**: `m:<chat>:<seña>` y
     * `f:<chat>:<ruta>`. Si al cerrar un lado ya lo ha vuelto a tocar, lo acordado es esto —lo
     * que los dos tuvieron— y así la vuelta siguiente sabe quién se movió después.
     */
    private val pasados = HashMap<String, String>()

    data class Hecho(
        var traidos: Int = 0, var enviados: Int = 0, var borrados: Int = 0, var archivos: Int = 0,
        /** Lo que se estaba guardando mientras se mandaba y queda para la próxima vuelta. */
        val saltados: MutableList<String> = ArrayList()
    )

    private fun nombreDe(prep: PreparadoArchivos, rel: String) =
        (prep.mios[rel]?.etiqueta ?: prep.suyos[rel]?.etiqueta).orEmpty().ifBlank { rel.substringAfterLast('/') }

    fun catalogo(): List<Chat> {
        Protocolo.enviar(canal, Protocolo.Peticion("catalogo"))
        return Protocolo.leerRespuesta(canal).chats
    }

    /** Paso 1: qué pasa con los mensajes de [chat], y qué hay que preguntar. */
    fun preparar(chat: String): Preparado {
        disco.identidad.leer().letra?.let { disco.sellar(it) }
        disco.adoptarDocumentos()
        Protocolo.enviar(canal, Protocolo.Peticion("inventario", chat = chat))
        val r = Protocolo.leerRespuesta(canal)
        val mia = disco.base(otro.id, chat)
        // **Lo acordado solo vale si los dos recuerdan lo mismo.** Si una vuelta se cortó a medias
        // uno lo guardó y el otro no: entonces se hace como la primera vez, que pregunta de más
        // pero nunca pisa nada.
        val base = if (mia != null && mia.sello == r.selloDeBase) mia else Base()
        val mios = disco.apuntes(chat).associateBy { it.sena }
        val suyos = r.apuntes.associateBy { it.sena }
        val pasos = Diferencia.plan(mios.values.toList(), suyos.values.toList(), base.mensajes)
        val dudosas = pasos.filterIsInstance<Diferencia.Paso.Preguntar>()
        val deAqui = disco.mensajesPorSena(chat)
        val deAlli = if (dudosas.isEmpty()) emptyMap() else {
            dudosas.map { it.sena }.chunked(Protocolo.MENSAJES_POR_TANDA).flatMap { tanda ->
                Protocolo.enviar(canal, Protocolo.Peticion("mensajes", chat = chat, senas = tanda))
                Protocolo.leerRespuesta(canal).mensajes
            }.mapNotNull { j ->
                runCatching { Disco.JSON.decodeFromString(com.forge.pixpin.guardados.Mensaje.serializer(), j) }.getOrNull()
            }.associateBy { disco.senaDe(it) ?: "" }
        }
        val preguntas = dudosas.map { p ->
            val a = mios.getValue(p.sena)
            val b = suyos.getValue(p.sena)
            fun vista(ap: Diferencia.Apunte, m: com.forge.pixpin.guardados.Mensaje?) =
                if (ap.borrado) Vista("Borrado", ap.tocado, borrado = true)
                else Vista(textoDe(m), m?.cuando ?: ap.creado, m?.bytes ?: 0)
            val m = deAqui[p.sena] ?: deAlli[p.sena]
            Pregunta(
                clave = p.sena,
                porque = p.porque,
                titulo = "#${p.sena} · " + textoDe(m),
                mio = vista(a, deAqui[p.sena]),
                suyo = vista(b, deAlli[p.sena]),
                // Borrar es una decisión: por omisión gana el borrado. Si no, lo de este aparato.
                porOmision = when {
                    a.borrado -> true
                    b.borrado -> false
                    else -> true
                }
            )
        }
        return Preparado(chat, pasos, preguntas, base, r.proyecto, mios, suyos)
    }

    /**
     * Aplica el paso 1 con lo que eligió el usuario: [decisiones] es seña → `true` para quedarse con
     * lo de este aparato. Una pregunta sin responder se queda con lo que venía marcado.
     */
    fun aplicar(prep: Preparado, decisiones: Map<String, Boolean>, hecho: Hecho) {
        val traer = ArrayList<String>()
        val mandar = ArrayList<String>()
        for (p in prep.pasos) when (p) {
            is Diferencia.Paso.Traer -> traer += p.sena
            is Diferencia.Paso.Mandar -> mandar += p.sena
            is Diferencia.Paso.Preguntar -> when (decisiones[p.sena] ?: prep.preguntas.firstOrNull { it.clave == p.sena }?.porOmision) {
                true -> mandar += p.sena
                false -> traer += p.sena
                null -> {}
            }
            is Diferencia.Paso.Borrar -> {}
        }
        val chat = prep.chat
        // Lo que me traigo: los vivos se piden; los borrados allí se borran aquí.
        val traerVivos = traer.filter { prep.suyos[it]?.borrado == false }
        val borrarAqui = traer.filter { prep.suyos[it]?.borrado == true }
        val llegan = traerVivos.chunked(Protocolo.MENSAJES_POR_TANDA).flatMap { tanda ->
            Protocolo.enviar(canal, Protocolo.Peticion("mensajes", chat = chat, senas = tanda))
            Protocolo.leerRespuesta(canal).mensajes
        }
        disco.aplicarMensajes(chat, llegan, borrarAqui)
        hecho.traidos += llegan.size
        hecho.borrados += borrarAqui.size

        // Lo que mando.
        val mios = disco.mensajesPorSena(chat)
        val poner = mandar.filter { prep.mios[it]?.borrado == false }.mapNotNull { mios[it]?.let(disco::portatil) }
        val borrarAlli = mandar.filter { prep.mios[it]?.borrado == true }

        // El proyecto, juntado.
        val base = disco.proyectoDe(prep.base.proyecto)
        val mio = disco.leerProyectos().firstOrNull { it.id == chat }
        val suyo = disco.proyectoDe(prep.suyoProyecto)
        val junto = Mezcla.proyecto(mio, suyo, base)
        if (junto != null && junto != mio) disco.guardarProyecto(junto)
        val proyectoParaAlla = junto?.takeIf { it != suyo }?.let(disco::aPortatil)

        val tandas = poner.chunked(Protocolo.MENSAJES_POR_TANDA)
        if (tandas.size > 1) for (tanda in tandas.dropLast(1)) {
            Protocolo.enviar(canal, Protocolo.Peticion("aplicar", chat = chat, poner = tanda))
            Protocolo.leerRespuesta(canal)
        }
        if (poner.isNotEmpty() || borrarAlli.isNotEmpty() || proyectoParaAlla != null) {
            Protocolo.enviar(canal, Protocolo.Peticion("aplicar", chat = chat, poner = tandas.lastOrNull().orEmpty(), borrar = borrarAlli, proyecto = proyectoParaAlla))
            Protocolo.leerRespuesta(canal)
        }
        hecho.enviados += poner.size
        hecho.borrados += borrarAlli.size
        val ahoraAqui = disco.apuntes(chat).associateBy { it.sena }
        for (sena in traer + mandar) ahoraAqui[sena]?.let { pasados["m:$chat:$sena"] = it.resumen }
    }

    /** Paso 2: con los chats ya iguales, qué archivos hay que mover. */
    fun prepararArchivos(prep: Preparado): PreparadoArchivos {
        val chat = prep.chat
        Protocolo.enviar(canal, Protocolo.Peticion("archivos", chat = chat))
        val suyos = Protocolo.leerRespuesta(canal).archivos.associateBy { it.ruta }
        val mios = disco.archivos(chat).associateBy { it.ruta }
        fun apunte(a: ArchivoInfo) = Diferencia.Apunte(a.ruta, a.tocado, a.tocado, a.resumen)
        val pasos = Diferencia.plan(mios.values.map(::apunte), suyos.values.map(::apunte), prep.base.archivos)
            .filter { disco.permitida(it.sena) }
        val preguntas = pasos.filterIsInstance<Diferencia.Paso.Preguntar>().map { p ->
            val a = mios.getValue(p.sena)
            val b = suyos.getValue(p.sena)
            Pregunta(
                clave = p.sena,
                porque = p.porque,
                titulo = (a.etiqueta.ifBlank { b.etiqueta }).ifBlank { p.sena.substringAfterLast('/') },
                mio = Vista(p.sena.substringAfterLast('/'), a.tocado, a.bytes),
                suyo = Vista(p.sena.substringAfterLast('/'), b.tocado, b.bytes),
                // **El último que se editó**, que es lo que el usuario dijo que esperaba ver marcado.
                porOmision = a.tocado >= b.tocado
            )
        }
        return PreparadoArchivos(chat, pasos, preguntas, prep.base, disco.proyectoPortatil(chat), mios, suyos)
    }

    fun aplicarArchivos(prep: PreparadoArchivos, decisiones: Map<String, Boolean>, hecho: Hecho, avance: (Long) -> Unit = {}) {
        for (p in prep.pasos) {
            val mandar = when (p) {
                is Diferencia.Paso.Mandar -> true
                is Diferencia.Paso.Traer -> false
                is Diferencia.Paso.Preguntar -> decisiones[p.sena] ?: prep.preguntas.firstOrNull { it.clave == p.sena }?.porOmision ?: continue
                is Diferencia.Paso.Borrar -> continue
            }
            val rel = p.sena
            if (mandar) {
                val sale = Protocolo.salida(disco, rel)
                Protocolo.enviar(canal, Protocolo.Peticion("pon", chat = prep.chat, ruta = rel, bytes = sale.largo))
                val resumen = sale.mandar(canal, avance)
                if (Protocolo.leerRespuesta(canal).saltado || resumen == null) { hecho.saltados += nombreDe(prep, rel); continue }
                pasados["f:${prep.chat}:$rel"] = resumen
            } else {
                Protocolo.enviar(canal, Protocolo.Peticion("dame", chat = prep.chat, ruta = rel))
                val cabecera = Protocolo.leerRespuesta(canal)
                var resumen: String? = null
                val entero = disco.escribirArchivo(rel) { salida -> Protocolo.recibirTrozos(canal, cabecera.bytes, salida, avance).also { resumen = it } != null }
                if (!entero) { hecho.saltados += nombreDe(prep, rel); continue }
                pasados["f:${prep.chat}:$rel"] = resumen!!
            }
            hecho.archivos++
        }
    }

    /**
     * **Guarda lo acordado en los dos lados**, mirando otra vez cómo quedaron.
     *
     * Solo se apunta lo que de verdad está igual en los dos. Lo que no —un archivo que se saltó
     * porque se estaba guardando, algo que el usuario tocó mientras tanto— se queda con lo que se
     * había acordado antes. Apuntar lo de este aparato sin mirar el otro hacía creer que el otro
     * «se había movido» y la vuelta siguiente **traía su versión vieja encima de la nueva**.
     */
    fun cerrar(prep: Preparado, ahora: Long = System.currentTimeMillis()) {
        val chat = prep.chat
        val antes = prep.base
        Protocolo.enviar(canal, Protocolo.Peticion("inventario", chat = chat))
        val inv = Protocolo.leerRespuesta(canal)
        Protocolo.enviar(canal, Protocolo.Peticion("archivos", chat = chat))
        val suyos = Protocolo.leerRespuesta(canal).archivos.associate { it.ruta to it.resumen }
        val mios = disco.archivos(chat).associate { it.ruta to it.resumen }
        fun acordado(tipo: String, m: Map<String, String>, s: Map<String, String>, viejo: Map<String, String>): Map<String, String> {
            val salida = HashMap<String, String>()
            for (k in m.keys + s.keys + viejo.keys) {
                val a = m[k]; val b = s[k]
                val pasado = pasados["$tipo:$chat:$k"]
                when {
                    a != null && a == b -> salida[k] = a
                    pasado != null && (a == pasado || b == pasado) -> salida[k] = pasado
                    viejo[k] != null -> salida[k] = viejo.getValue(k)
                }
            }
            return salida
        }
        val miProyecto = disco.proyectoPortatil(chat)
        val base = Base(
            mensajes = acordado("m", disco.apuntes(chat).associate { it.sena to it.resumen }, inv.apuntes.associate { it.sena to it.resumen }, antes.mensajes),
            archivos = acordado("f", mios, suyos, antes.archivos),
            proyecto = if (miProyecto != null && inv.proyecto != null && Canonico.de(miProyecto) == Canonico.de(inv.proyecto)) miProyecto else antes.proyecto
        )
        Protocolo.enviar(canal, Protocolo.Peticion("base", chat = chat, base = base))
        Protocolo.leerRespuesta(canal)
        disco.guardarBase(otro.id, chat, base)
        disco.apuntarVez(otro.id, ahora)
    }

    /** Se despide y deja libre el aparato para otra sincronización. Se puede llamar más de una vez. */
    fun adios() {
        if (terminada) return
        terminada = true
        try {
            runCatching {
                Protocolo.enviar(canal, Protocolo.Peticion("adios"))
                Protocolo.leerRespuesta(canal)
            }
        } finally {
            Protocolo.soltar(disco)
        }
    }

    /** Suelta el aparato sin despedirse, cuando la conexión ya se rompió. */
    fun soltar() {
        if (terminada) return
        terminada = true
        Protocolo.soltar(disco)
    }

    @Volatile private var terminada = false

    private fun textoDe(m: com.forge.pixpin.guardados.Mensaje?): String {
        if (m == null) return ""
        return m.nombre.ifBlank { m.texto.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty() }.take(80)
            .ifBlank { m.clase.name.lowercase() }
    }

    companion object {
        /**
         * Se presenta al otro aparato. Con [unirme], este aparato aún no está en el grupo: llega
         * con el [codigo] tecleado y sale con su letra y la lista de miembros guardadas.
         */
        fun conectar(
            entrada: InputStream, salida: OutputStream, disco: Disco,
            unirme: Boolean = false, codigo: String? = null, ahora: () -> Long = System::currentTimeMillis,
            miPuerto: Int = 0
        ): Sesion {
            val id = disco.identidad.leer()
            val elCodigo = codigo ?: id.codigo ?: throw IOException("Este aparato no está en un grupo")
            if (!Protocolo.ocupar(disco)) throw IOException(Protocolo.OCUPADO)
            try {
                return abrir(entrada, salida, disco, unirme, elCodigo, id, ahora, miPuerto)
            } catch (e: Throwable) {
                Protocolo.soltar(disco)
                throw e
            }
        }

        private fun abrir(
            entrada: InputStream, salida: OutputStream, disco: Disco,
            unirme: Boolean, elCodigo: String, id: Identidad, ahora: () -> Long, miPuerto: Int
        ): Sesion {
            val canal = Canal.abrir(entrada, salida, Grupo.clave(elCodigo), inicia = true)
            val antes = ahora()
            Protocolo.enviar(canal, Protocolo.Peticion("hola", hola = Protocolo.Hola(id.yo, id.miembros, antes, unirme = unirme, puerto = miPuerto)))
            val r = Protocolo.leerRespuesta(canal)
            val despues = ahora()
            val hola = r.hola ?: throw IOException("El otro aparato no saludó")
            if (hola.version != Protocolo.VERSION) throw IOException("El otro aparato tiene otra versión de PixPin: actualiza los dos")
            val desfase = hola.reloj - (antes + despues) / 2
            if (unirme) {
                val yoConLetra = hola.miembros.firstOrNull { it.id == id.yo.id }
                    ?: throw IOException("El otro aparato no me dio letra")
                disco.identidad.guardar(Identidad(yo = yoConLetra, codigo = elCodigo, miembros = hola.miembros))
                yoConLetra.letra?.firstOrNull()?.let { disco.sellar(it) }
            } else {
                val juntos = Grupo.juntar(id.miembros.ifEmpty { listOf(id.yo) }, hola.miembros, quienHabla = hola.yo)
                disco.identidad.guardar(id.copy(miembros = juntos))
            }
            disco.avisar(Disco.Cambio.IDENTIDAD)
            return Sesion(canal, disco, hola.yo, desfase, hola.puerto)
        }
    }
}
