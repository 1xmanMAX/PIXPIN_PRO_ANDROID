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
 * 1. **Los mensajes y el proyecto.** Se comparan por su código único ([Codigos]); lo que cambió en
 *    un solo lado pasa solo, lo que cambió en los dos **se fusiona** ([Fusion]). El proyecto se junta
 *    igual ([Mezcla]).
 * 2. **Los archivos**, ya con los dos chats iguales: los adjuntos, los lienzos, las tablas, el PDF.
 *    Un lienzo dibujado en los dos lados se junta figura por figura; una tabla, celda por celda.
 *
 * **No se pregunta nada ni manda ningún aparato** (15-sep-2026). Y **solo viajan los cambios**: de
 * un lienzo, tabla o croquis que ya se acordó, se manda qué figuras cambiaron respecto a lo acordado
 * ([Fusion.diferencia]), como hace git con las líneas; lo entero solo si el otro no tiene esa base.
 *
 * Al acabar, los dos guardan **lo acordado** ([Base]) y su contenido, que es lo que deja saber la
 * próxima vez quién se movió y juntar lo que se movió en los dos.
 */
object Protocolo {
    /**
     * 2: cada archivo lleva detrás su resumen, y lo acordado se apunta solo si quedó igual en los dos.
     * 3: códigos únicos, fusión sin preguntar y parches (15-sep-2026).
     * 4: borrar un proyecto viaja: lápidas de chat (16-sep-2026). Ver [Disco.LapidaDeChat].
     */
    const val VERSION = 4
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
        val base: Base? = null,
        /** Un parche ([Fusion.diferencia]) en JSON. */
        val parche: String? = null,
        /** El resumen de aquello sobre lo que va el parche: lo acordado, o lo que el otro tiene ahora. */
        val desde: String? = null,
        /** El resumen que tiene que salir al poner el parche. */
        val resumen: String? = null
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
        val saltado: Boolean = false,
        /** Lo que cambió, cuando se pidió así. */
        val parche: String? = null,
        /** No tengo aquello sobre lo que va el parche: hay que mandarlo entero. */
        val faltaBase: Boolean = false,
        /** Los proyectos borrados en el otro aparato. Ver [LapidaDeChat]. */
        val lapidas: List<LapidaDeChat> = emptyList()
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
        // Lo que es de cada chat, calculado al preguntar por sus archivos: sin esto, cada archivo
        // pedido volvería a recorrer el chat y sus lienzos enteros.
        var alcance: Pair<String, Set<String>>? = null
        // Los resúmenes ya calculados en esta conexión: la segunda vez que se piden no se releen
        // los lienzos. Lo que se escribe aquí se olvida.
        val conocidos = HashMap<String, ArchivoInfo>()
        while (true) {
            val p = runCatching { Protocolo.leerPeticion(canal) }.getOrElse { if (it is java.io.EOFException) return else throw it }
            try {
                when (p.t) {
                    "adios" -> { Protocolo.enviar(canal, Protocolo.Respuesta()); canal.vaciar(); return }
                    "catalogo" -> Protocolo.enviar(canal, Protocolo.Respuesta(chats = disco.chats()))
                    // **Qué proyectos se borraron aquí**, para que el otro no los devuelva.
                    "lapidas" -> Protocolo.enviar(canal, Protocolo.Respuesta(lapidas = disco.lapidas()))
                    // **Bórralo tú también.** Lo pide el que dirige cuando aquí está vivo pero
                    // allí se borró y aquí no se ha tocado desde entonces. Ver [Disco.borrarChat].
                    "borrarchat" -> {
                        val chat = p.chat!!
                        estado("Borrando «${nombreDe(chat)}», borrado en ${otro.nombre}…")
                        disco.borrarChat(chat, "Antes de borrarlo, borrado en ${otro.nombre}", ahora(), otro.id)
                        Protocolo.enviar(canal, Protocolo.Respuesta())
                    }
                    "inventario" -> {
                        val chat = p.chat!!
                        estado("Comparando «${nombreDe(chat)}» con ${otro.nombre}…")
                        // **Antes de tocar nada, cómo estaba.** Ver [Copias].
                        Copias(disco).hacer(chat, "Antes de sincronizar con ${otro.nombre}", ahora())
                        disco.sellar()
                        disco.adoptarDocumentos()
                        Protocolo.enviar(canal, Protocolo.Respuesta(
                            apuntes = disco.apuntes(chat),
                            proyecto = disco.proyectoPortatil(chat),
                            selloDeBase = disco.base(otro.id, chat)?.sello
                        ))
                    }
                    "mensajes" -> {
                        val suyos = disco.mensajesPorClave(p.chat!!)
                        Protocolo.enviar(canal, Protocolo.Respuesta(mensajes = p.senas.mapNotNull { suyos[it]?.let(disco::portatil) }))
                    }
                    "aplicar" -> {
                        val chat = p.chat!!
                        disco.aplicarMensajes(chat, p.poner, p.borrar)
                        disco.proyectoDe(p.proyecto)?.let {
                            // Si llega el proyecto es que allí decidieron que sigue vivo —lo
                            // tocaron después de que aquí se borrara—: se levanta la lápida o la
                            // vuelta siguiente lo borraría otra vez. Ver [Disco.quitarLapida].
                            disco.quitarLapida(chat)
                            disco.guardarProyecto(it)
                        }
                        Protocolo.enviar(canal, Protocolo.Respuesta())
                    }
                    "archivos" -> {
                        val lista = disco.archivos(p.chat!!, conocidos)
                        lista.forEach { conocidos[it.ruta] = it }
                        alcance = p.chat to lista.map { it.ruta }.toSet()
                        Protocolo.enviar(canal, Protocolo.Respuesta(archivos = lista))
                    }
                    "pon" -> {
                        val rel = p.ruta!!
                        estado("Recibiendo ${rel.substringAfterLast('/')}")
                        conocidos.remove(rel)
                        val entero = disco.escribirArchivo(rel) { salida -> Protocolo.recibirTrozos(canal, p.bytes, salida) {} != null }
                        Protocolo.enviar(canal, Protocolo.Respuesta(saltado = !entero))
                    }
                    "parche" -> {
                        val rel = p.ruta!!
                        require(disco.permitida(rel) && disco.esTexto(rel)) { "No se puede parchear: $rel" }
                        estado("Recibiendo cambios de ${rel.substringAfterLast('/')}")
                        conocidos.remove(rel)
                        val puesto = Parches.poner(disco, rel, p.desde, p.parche, p.resumen)
                        Protocolo.enviar(canal, Protocolo.Respuesta(faltaBase = !puesto))
                    }
                    "damecambios" -> {
                        val rel = p.ruta!!
                        val suyos = alcance?.takeIf { it.first == p.chat }?.second ?: disco.alcance(p.chat!!).keys
                        require(disco.permitida(rel) && disco.esTexto(rel) && rel in suyos) { "No es de este chat: $rel" }
                        estado("Mandando cambios de ${rel.substringAfterLast('/')}")
                        val hecho = Parches.sacar(disco, rel, p.desde)
                        Protocolo.enviar(canal, if (hecho == null) Protocolo.Respuesta(faltaBase = true) else Protocolo.Respuesta(parche = hecho.first, resumen = hecho.second))
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
                        val base = p.base ?: Base()
                        disco.guardarBase(otro.id, p.chat!!, base)
                        disco.guardarObjetosDeBase(p.chat, base)
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
    /** Los proyectos que el otro aparato tiene borrados. Ver [LapidaDeChat]. */
    /**
     * **Lo mío manda** (21-sep-2026): sincronizar de una sola dirección, para los casos raros. El
     * usuario vació su portátil creyendo que el teléfono lo volvería a llenar, y juntar —que es
     * lo normal— hizo lo contrario: los borrados del portátil eran lo más reciente y ganaron.
     * Con esto puesto, **aquí no se borra ni se cambia nada**: mis mensajes vivos vuelven al otro
     * aunque allí estén borrados, lo cambiado en los dos queda como aquí, el proyecto es el mío
     * y mis archivos pisan a los suyos. Lo que solo tiene el otro se conserva y se trae.
     */
    var loMioManda = false

    fun lapidas(): List<LapidaDeChat> {
        Protocolo.enviar(canal, Protocolo.Peticion("lapidas"))
        return Protocolo.leerRespuesta(canal).lapidas
    }

    /** Le dice al otro que borre ese proyecto, porque aquí se borró y allí no se ha tocado. */
    fun borrarAlla(chat: String) {
        Protocolo.enviar(canal, Protocolo.Peticion("borrarchat", chat = chat))
        Protocolo.leerRespuesta(canal)
    }

    val enviados get() = canal.enviados
    val recibidos get() = canal.recibidos

    class Preparado internal constructor(
        val chat: String,
        val pasos: List<Diferencia.Paso>,
        internal val base: Base,
        internal val suyoProyecto: String?,
        internal val mios: Map<String, Diferencia.Apunte>,
        internal val suyos: Map<String, Diferencia.Apunte>
    )

    class PreparadoArchivos internal constructor(
        val chat: String,
        val pasos: List<Diferencia.Paso>,
        /** Lo acordado de cada archivo, ya traducido a resúmenes canónicos. */
        internal val acordado: Map<String, String>,
        internal val mios: Map<String, ArchivoInfo>,
        internal val suyos: Map<String, ArchivoInfo>
    ) {
        /** Cuántos bytes hay que mover como mucho, para la barra. */
        val bytes: Long get() = pasos.sumOf { p -> (mios[p.sena]?.bytes ?: 0L).coerceAtLeast(suyos[p.sena]?.bytes ?: 0L) }
    }

    /**
     * **Lo que pasó de un lado a otro en esta vuelta, con su resumen**: `m:<chat>:<clave>` y
     * `f:<chat>:<ruta>`. Si al cerrar un lado ya lo ha vuelto a tocar, lo acordado es esto —lo
     * que los dos tuvieron— y así la vuelta siguiente sabe quién se movió después.
     */
    private val pasados = HashMap<String, String>()

    /** Los resúmenes de los archivos de este aparato ya calculados en esta vuelta. Ver [Disco.archivos]. */
    private val conocidos = HashMap<String, ArchivoInfo>()

    data class Hecho(
        var traidos: Int = 0, var enviados: Int = 0, var borrados: Int = 0, var archivos: Int = 0,
        /** Lo que cambió en los dos aparatos y se juntó. */
        var fusionados: Int = 0,
        /** Lo borrado en un lado que se quedó porque en el otro se cambió. */
        var rescatados: Int = 0,
        /** Bytes que no hizo falta mandar porque viajaron solo los cambios. */
        var ahorrados: Long = 0,
        /** Lo que se estaba guardando mientras se mandaba y queda para la próxima vuelta. */
        val saltados: MutableList<String> = ArrayList()
    )

    private fun nombreDe(prep: PreparadoArchivos, rel: String) =
        (prep.mios[rel]?.etiqueta ?: prep.suyos[rel]?.etiqueta).orEmpty().ifBlank { rel.substringAfterLast('/') }

    fun catalogo(): List<Chat> {
        Protocolo.enviar(canal, Protocolo.Peticion("catalogo"))
        return Protocolo.leerRespuesta(canal).chats
    }

    /** Paso 1: qué pasa con los mensajes de [chat]. */
    fun preparar(chat: String): Preparado {
        // **Antes de tocar nada, cómo estaba.** Ver [Copias].
        Copias(disco).hacer(chat, "Antes de sincronizar con ${otro.nombre}")
        disco.sellar()
        disco.adoptarDocumentos()
        conocidos.clear()
        chatDeLaVuelta = chat
        Protocolo.enviar(canal, Protocolo.Peticion("inventario", chat = chat))
        val r = Protocolo.leerRespuesta(canal)
        val mia = disco.base(otro.id, chat)
        // **Lo acordado solo vale si los dos recuerdan lo mismo.** Si una vuelta se cortó a medias
        // uno lo guardó y el otro no: entonces se hace como la primera vez, que junta todo y nunca
        // pisa nada.
        val base = if (mia != null && mia.sello == r.selloDeBase) mia else Base()
        // Las marcas de borrado de antes de los códigos, ya con el código único del mensaje que borran:
        // los pasos del plan van por ese código y aquí hay que encontrarlas por él.
        val deAqui = disco.apuntes(chat)
        val mios = Diferencia.conMarcasViejas(deAqui, r.apuntes).associateBy { it.sena }
        val suyos = Diferencia.conMarcasViejas(r.apuntes, deAqui).associateBy { it.sena }
        val pasos = Diferencia.plan(mios.values.toList(), suyos.values.toList(), base.mensajes)
        return Preparado(chat, pasos, base, r.proyecto, mios, suyos)
    }

    private fun pedirMensajes(chat: String, claves: List<String>): List<String> =
        claves.chunked(Protocolo.MENSAJES_POR_TANDA).flatMap { tanda ->
            Protocolo.enviar(canal, Protocolo.Peticion("mensajes", chat = chat, senas = tanda))
            Protocolo.leerRespuesta(canal).mensajes
        }

    /** Aplica el paso 1: trae, manda, borra y **fusiona** mensajes, y junta el proyecto. */
    fun aplicar(prep: Preparado, hecho: Hecho) {
        val traer = ArrayList<String>()
        val mandar = ArrayList<String>()
        val fusionar = ArrayList<String>()
        for (p in prep.pasos) when (p) {
            is Diferencia.Paso.Traer -> traer += p.sena
            is Diferencia.Paso.Mandar -> mandar += p.sena
            is Diferencia.Paso.Fusionar -> fusionar += p.sena
        }
        val chat = prep.chat
        // Lo mío manda: lo cambiado en los dos no se junta, se manda; y lo borrado allí que aquí
        // sigue vivo no se borra aquí: vuelve allí. Ver [loMioManda].
        if (loMioManda) { mandar += fusionar; fusionar.clear() }
        val vuelven = if (loMioManda) traer.filter { prep.suyos[it]?.borrado == true && prep.mios[it]?.borrado == false } else emptyList()
        if (vuelven.isNotEmpty()) { traer -= vuelven.toSet(); mandar += vuelven }
        // Lo que me traigo: los vivos se piden; los borrados allí se borran aquí.
        val traerVivos = traer.filter { prep.suyos[it]?.borrado == false }
        val borrarAqui = traer.filter { prep.suyos[it]?.borrado == true }
        val llegan = pedirMensajes(chat, traerVivos)

        // **Lo cambiado en los dos, junto** campo a campo, y el texto por párrafos.
        val juntos = ArrayList<String>()
        if (fusionar.isNotEmpty()) {
            val suyos = pedirMensajes(chat, fusionar).mapNotNull { j ->
                runCatching { Disco.JSON.decodeFromString(com.forge.pixpin.guardados.Mensaje.serializer(), j) }.getOrNull()?.let { Codigos.unico(it) to j }
            }.toMap()
            val mios = disco.mensajesPorClave(chat)
            val cuenta = Fusion.Cuenta()
            for (clave in fusionar) {
                val mio = mios[clave] ?: continue
                val suyo = suyos[clave] ?: continue
                val acordado = prep.base.mensajes[clave] ?: prep.mios[clave]?.alias?.let { prep.base.mensajes[it] }
                val base = acordado?.let { disco.objeto(it) }?.let(::json)
                val junto = Fusion.json(base, json(disco.portatil(mio)), json(suyo), Fusion.Criterio(mioMasNuevo = true, desfase = desfase), cuenta)
                    ?: continue
                juntos += junto.toString()
            }
            hecho.fusionados += juntos.size
            hecho.rescatados += cuenta.rescatados
        }
        disco.aplicarMensajes(chat, llegan + juntos, borrarAqui)
        hecho.traidos += llegan.size
        hecho.borrados += borrarAqui.size

        // Lo que mando.
        val mios = disco.mensajesPorClave(chat)
        val poner = mandar.filter { prep.mios[it]?.borrado == false }.mapNotNull { mios[it]?.let(disco::portatil) } + juntos
        val borrarAlli = mandar.filter { prep.mios[it]?.borrado == true }

        // El proyecto, juntado. Para saber qué hojas se cambiaron en cada lado hacen falta los
        // resúmenes de sus archivos.
        val mio = disco.leerProyectos().firstOrNull { it.id == chat }
        val suyo = disco.proyectoDe(prep.suyoProyecto)
        var junto = mio ?: suyo
        if (mio != null && suyo != null && mio != suyo && !loMioManda) {
            Protocolo.enviar(canal, Protocolo.Peticion("archivos", chat = chat))
            val deAlli = Protocolo.leerRespuesta(canal).archivos.associateBy { it.ruta }
            val deAqui = misArchivos(chat)
            val acordado = traducir(prep.base.archivos, deAqui, deAlli)
            junto = Mezcla.proyecto(
                mio, suyo, disco.proyectoDe(prep.base.proyecto),
                cambiadasAqui = Mezcla.cambiadas(mio, deAqui.mapValues { it.value.resumen }, acordado),
                cambiadasAlli = Mezcla.cambiadas(suyo, deAlli.mapValues { it.value.resumen }, acordado),
                desfase = desfase
            )
        }
        if (junto != null && junto != mio) disco.guardarProyecto(junto)
        val proyectoParaAlla = disco.leerProyectos().firstOrNull { it.id == chat }?.takeIf { it != suyo }?.let(disco::aPortatil)

        val tandas = poner.chunked(Protocolo.MENSAJES_POR_TANDA)
        if (tandas.size > 1) for (tanda in tandas.dropLast(1)) {
            Protocolo.enviar(canal, Protocolo.Peticion("aplicar", chat = chat, poner = tanda))
            Protocolo.leerRespuesta(canal)
        }
        if (poner.isNotEmpty() || borrarAlli.isNotEmpty() || proyectoParaAlla != null) {
            Protocolo.enviar(canal, Protocolo.Peticion("aplicar", chat = chat, poner = tandas.lastOrNull().orEmpty(), borrar = borrarAlli, proyecto = proyectoParaAlla))
            Protocolo.leerRespuesta(canal)
        }
        hecho.enviados += poner.size - juntos.size
        hecho.borrados += borrarAlli.size
        val ahoraAqui = disco.apuntes(chat).associateBy { it.sena }
        for (clave in traer + mandar + fusionar) ahoraAqui[clave]?.let { pasados["m:$chat:$clave"] = it.resumen }
    }

    private fun misArchivos(chat: String): Map<String, ArchivoInfo> =
        disco.archivos(chat, conocidos).onEach { conocidos[it.ruta] = it }.associateBy { it.ruta }

    /**
     * **Lo acordado antes del 15-sep-2026** se apuntó con el resumen de los bytes tal cual; ahora es
     * el del JSON canónico. Si lo acordado es el crudo de un lado, se cambia por su canónico.
     */
    private fun traducir(acordado: Map<String, String>, mios: Map<String, ArchivoInfo>, suyos: Map<String, ArchivoInfo>): Map<String, String> =
        acordado.mapValues { (rel, r) ->
            val m = mios[rel]; val s = suyos[rel]
            when {
                m != null && m.crudo == r -> m.resumen
                s != null && s.crudo == r -> s.resumen
                else -> r
            }
        }

    /**
     * **Qué tiene el otro de un solo archivo**, sin mover nada. Ver [com.forge.pixpin.sincro.AlDia].
     */
    fun infoDe(chat: String, rel: String): ArchivoInfo? {
        Protocolo.enviar(canal, Protocolo.Peticion("archivos", chat = chat))
        return Protocolo.leerRespuesta(canal).archivos.firstOrNull { it.ruta == rel }
    }

    /**
     * **Solo este archivo**: el plan de una sincronización normal, recortado a un lienzo.
     *
     * Es lo que hace rápido ponerse al día antes de escribir: no se comparan los mensajes del
     * chat, ni el proyecto, ni los demás archivos. Lo que se acordó la última vez se lee de la
     * base, igual que siempre, así que sigue viajando solo el parche.
     */
    fun soloEsteArchivo(chat: String, rel: String): PreparadoArchivos {
        Protocolo.enviar(canal, Protocolo.Peticion("archivos", chat = chat))
        val suyos = Protocolo.leerRespuesta(canal).archivos.filter { it.ruta == rel }.associateBy { it.ruta }
        val mios = misArchivos(chat).filterKeys { it == rel }
        val base = disco.base(otro.id, chat) ?: Base()
        val acordado = traducir(base.archivos.filterKeys { it == rel }, mios, suyos)
        fun apunte(a: ArchivoInfo) = Diferencia.Apunte(a.ruta, a.tocado, a.tocado, a.resumen)
        val pasos = Diferencia.plan(mios.values.map(::apunte), suyos.values.map(::apunte), acordado)
            .filter { disco.permitida(it.sena) }
        return PreparadoArchivos(chat, pasos, acordado, mios, suyos)
    }

    /**
     * **Apunta ese archivo como acordado**, si quedó igual en los dos.
     *
     * Sin esto, la sincronización entera de después vería los dos lados movidos respecto a lo
     * acordado y volvería a fusionarlo: saldría lo mismo, pero mandando el archivo entero en vez
     * del parche. Lo demás de la base no se toca.
     */
    fun apuntarArchivo(chat: String, rel: String) {
        val suyo = infoDe(chat, rel) ?: return
        val mio = disco.archivos(chat, HashMap()).firstOrNull { it.ruta == rel } ?: return
        if (mio.resumen != suyo.resumen) return
        val base = disco.base(otro.id, chat) ?: Base()
        val nueva = base.copy(archivos = base.archivos + (rel to mio.resumen))
        Protocolo.enviar(canal, Protocolo.Peticion("base", chat = chat, base = nueva))
        Protocolo.leerRespuesta(canal)
        disco.guardarBase(otro.id, chat, nueva)
        disco.guardarObjetosDeBase(chat, nueva)
    }

    /** Paso 2: con los chats ya iguales, qué archivos hay que mover. */
    fun prepararArchivos(prep: Preparado): PreparadoArchivos {
        val chat = prep.chat
        Protocolo.enviar(canal, Protocolo.Peticion("archivos", chat = chat))
        val suyos = Protocolo.leerRespuesta(canal).archivos.associateBy { it.ruta }
        val mios = misArchivos(chat)
        val acordado = traducir(prep.base.archivos, mios, suyos)
        fun apunte(a: ArchivoInfo) = Diferencia.Apunte(a.ruta, a.tocado, a.tocado, a.resumen)
        val pasos = Diferencia.plan(mios.values.map(::apunte), suyos.values.map(::apunte), acordado)
            .filter { disco.permitida(it.sena) }
        return PreparadoArchivos(chat, pasos, acordado, mios, suyos)
    }

    fun aplicarArchivos(prep: PreparadoArchivos, hecho: Hecho, avance: (Long) -> Unit = {}) {
        val cuenta = Fusion.Cuenta()
        for (p in prep.pasos) {
            val rel = p.sena
            val texto = disco.esTexto(rel)
            val mio = prep.mios[rel]
            val suyo = prep.suyos[rel]
            val acordado = prep.acordado[rel]
            val puesto: String? = when {
                // Lo mío manda: lo que tengo yo, va; solo se trae lo que aquí no existe.
                loMioManda && mio != null -> mandarArchivo(rel, suyo, acordado, avance, hecho)
                p is Diferencia.Paso.Fusionar && texto && mio != null && suyo != null -> fusionarArchivo(prep, rel, mio, suyo, acordado, cuenta, hecho)
                // Un PDF o una foto no se juntan: se queda la versión tocada más tarde (la otra sigue en la copia).
                p is Diferencia.Paso.Fusionar -> if ((mio?.tocado ?: 0L) >= (suyo?.tocado ?: 0L) - desfase) mandarArchivo(rel, suyo, acordado, avance, hecho) else traerArchivo(rel, mio, acordado, avance, hecho)
                p is Diferencia.Paso.Mandar -> mandarArchivo(rel, suyo, acordado, avance, hecho)
                else -> traerArchivo(rel, mio, acordado, avance, hecho)
            }
            if (puesto == null) { hecho.saltados += nombreDe(prep, rel); continue }
            conocidos.remove(rel)
            pasados["f:${prep.chat}:$rel"] = puesto
            hecho.archivos++
        }
        hecho.rescatados += cuenta.rescatados
    }

    /** Manda mi versión: solo los cambios si el otro tiene lo acordado, o entera. Devuelve el resumen puesto. */
    private fun mandarArchivo(rel: String, suyo: ArchivoInfo?, acordado: String?, avance: (Long) -> Unit, hecho: Hecho): String? {
        if (disco.esTexto(rel) && suyo != null && acordado != null) {
            val base = disco.textoBase(rel, acordado)
            if (base != null) {
                val actual = disco.textoDe(rel)
                val resumen = sha256(Canonico.de(actual).toByteArray())
                val parche = Fusion.diferencia(json(base), json(actual))?.toString() ?: "null"
                if (mandarParche(rel, acordado, parche, resumen)) {
                    hecho.ahorrados += (actual.length - parche.length).coerceAtLeast(0)
                    return resumen
                }
            }
        }
        val sale = Protocolo.salida(disco, rel)
        Protocolo.enviar(canal, Protocolo.Peticion("pon", chat = chatDeLaVuelta, ruta = rel, bytes = sale.largo))
        val resumen = sale.mandar(canal, avance)
        if (Protocolo.leerRespuesta(canal).saltado || resumen == null) return null
        return if (disco.esTexto(rel)) disco.resumenDeArchivo(rel) else resumen
    }

    private fun mandarParche(rel: String, desde: String, parche: String, resumen: String): Boolean {
        Protocolo.enviar(canal, Protocolo.Peticion("parche", chat = chatDeLaVuelta, ruta = rel, desde = desde, parche = parche, resumen = resumen))
        return !Protocolo.leerRespuesta(canal).faltaBase
    }

    /** Me traigo la suya: solo los cambios si tengo lo acordado, o entera. */
    private fun traerArchivo(rel: String, mio: ArchivoInfo?, acordado: String?, avance: (Long) -> Unit, hecho: Hecho): String? {
        if (disco.esTexto(rel) && mio != null && acordado != null && disco.textoBase(rel, acordado) != null) {
            val (suyo, resumen) = pedirCambios(rel, acordado) ?: (null to null)
            if (suyo != null && resumen != null && sha256(Canonico.de(suyo).toByteArray()) == resumen) {
                disco.escribirTexto(rel, suyo)
                hecho.ahorrados += suyo.length
                return resumen
            }
        }
        Protocolo.enviar(canal, Protocolo.Peticion("dame", chat = chatDeLaVuelta, ruta = rel))
        val cabecera = Protocolo.leerRespuesta(canal)
        val entero = disco.escribirArchivo(rel) { salida -> Protocolo.recibirTrozos(canal, cabecera.bytes, salida, avance) != null }
        return if (entero) disco.resumenDeArchivo(rel) else null
    }

    /** Su versión, reconstruida con lo acordado y los cambios que manda. Null si no tiene esa base. */
    private fun pedirCambios(rel: String, desde: String): Pair<String, String>? {
        val base = disco.textoBase(rel, desde) ?: return null
        Protocolo.enviar(canal, Protocolo.Peticion("damecambios", chat = chatDeLaVuelta, ruta = rel, desde = desde))
        val r = Protocolo.leerRespuesta(canal)
        if (r.faltaBase || r.resumen == null) return null
        val suyo = Fusion.aplicar(json(base), r.parche?.let(::json)) ?: return null
        return suyo.toString() to r.resumen
    }

    /** Su versión entera, a memoria. */
    private fun traerEntero(rel: String): String? {
        Protocolo.enviar(canal, Protocolo.Peticion("dame", chat = chatDeLaVuelta, ruta = rel))
        val cabecera = Protocolo.leerRespuesta(canal)
        val bytes = java.io.ByteArrayOutputStream()
        Protocolo.recibirTrozos(canal, cabecera.bytes, bytes) {} ?: return null
        return bytes.toString(Charsets.UTF_8.name())
    }

    /**
     * **Un lienzo, tabla o croquis cambiado en los dos: se junta** con lo acordado y queda igual en
     * los dos. Lo mío se escribe aquí, y al otro le va lo que le falta respecto a lo suyo.
     */
    private fun fusionarArchivo(prep: PreparadoArchivos, rel: String, mio: ArchivoInfo, suyo: ArchivoInfo, acordado: String?, cuenta: Fusion.Cuenta, hecho: Hecho): String? {
        val deAlli = (acordado?.let { pedirCambios(rel, it) }?.first) ?: traerEntero(rel) ?: return null
        val base = acordado?.let { disco.textoBase(rel, it) }?.let(::json)
        val aqui = disco.textoDe(rel)
        val criterio = Fusion.Criterio(mioMasNuevo = mio.tocado >= suyo.tocado - desfase, desfase = desfase)
        val junto = Fusion.json(base, json(aqui), json(deAlli), criterio, cuenta) ?: return null
        val textoJunto = junto.toString()
        val resumen = sha256(Canonico.de(textoJunto).toByteArray())
        disco.escribirTexto(rel, textoJunto)
        val suyoAhora = sha256(Canonico.de(deAlli).toByteArray())
        if (resumen != suyoAhora) {
            val parche = Fusion.diferencia(json(deAlli), junto)?.toString() ?: "null"
            if (!mandarParche(rel, suyoAhora, parche, resumen)) {
                if (mandarArchivo(rel, null, null, {}, hecho) == null) return null
            }
        }
        hecho.fusionados++
        return resumen
    }

    private var chatDeLaVuelta: String? = null

    private fun json(texto: String): kotlinx.serialization.json.JsonElement = kotlinx.serialization.json.Json.parseToJsonElement(texto)

    /**
     * **Guarda lo acordado en los dos lados**, mirando otra vez cómo quedaron, y su contenido para
     * fusionar la próxima vez.
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
        conocidos.clear()
        val mios = misArchivos(chat).mapValues { it.value.resumen }
        val misApuntes = disco.apuntes(chat)
        // Lo acordado antes de los códigos iba por seña: lo que ya va por código no se arrastra.
        val alias = (misApuntes + inv.apuntes).mapNotNullTo(HashSet()) { it.alias }
        fun acordado(tipo: String, m: Map<String, String>, s: Map<String, String>, viejo: Map<String, String>): Map<String, String> {
            val salida = HashMap<String, String>()
            for (k in m.keys + s.keys + viejo.keys) {
                val a = m[k]; val b = s[k]
                val pasado = pasados["$tipo:$chat:$k"]
                when {
                    a != null && a == b -> salida[k] = a
                    pasado != null && (a == pasado || b == pasado) -> salida[k] = pasado
                    viejo[k] != null && !(tipo == "m" && k in alias) -> salida[k] = viejo.getValue(k)
                }
            }
            return salida
        }
        val miProyecto = disco.proyectoPortatil(chat)
        val base = Base(
            mensajes = acordado("m", misApuntes.associate { it.sena to it.resumen }, inv.apuntes.associate { it.sena to it.resumen }, antes.mensajes),
            archivos = acordado("f", mios, suyos, antes.archivos),
            proyecto = if (miProyecto != null && inv.proyecto != null && Canonico.de(miProyecto) == Canonico.de(inv.proyecto)) miProyecto else antes.proyecto
        )
        Protocolo.enviar(canal, Protocolo.Peticion("base", chat = chat, base = base))
        Protocolo.leerRespuesta(canal)
        disco.guardarBase(otro.id, chat, base)
        disco.guardarObjetosDeBase(chat, base)
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
                disco.sellar()
            } else {
                val juntos = Grupo.juntar(id.miembros.ifEmpty { listOf(id.yo) }, hola.miembros, quienHabla = hola.yo)
                disco.identidad.guardar(id.copy(miembros = juntos))
            }
            disco.avisar(Disco.Cambio.IDENTIDAD)
            return Sesion(canal, disco, hola.yo, desfase, hola.puerto)
        }
    }
}

/**
 * **Poner y sacar cambios de un archivo de texto** (lienzo, tabla, croquis) respecto a una versión
 * que los dos tienen: lo acordado la última vez, o lo que el otro tiene ahora. Ver [Fusion.diferencia].
 */
internal object Parches {

    /**
     * Pone [parche] sobre la versión [desde] de [rel] y lo escribe, si sale lo que tiene que salir
     * ([resumen]). Devuelve `false` si aquí no está esa versión o no cuadra: hay que mandarlo entero.
     */
    fun poner(disco: Disco, rel: String, desde: String?, parche: String?, resumen: String?): Boolean {
        if (desde == null || parche == null || resumen == null) return false
        val base = disco.textoBase(rel, desde) ?: return false
        val puesto = runCatching {
            Fusion.aplicar(kotlinx.serialization.json.Json.parseToJsonElement(base), kotlinx.serialization.json.Json.parseToJsonElement(parche))
        }.getOrNull() ?: return false
        val texto = puesto.toString()
        if (sha256(Canonico.de(texto).toByteArray()) != resumen) return false
        return disco.escribirTexto(rel, texto)
    }

    /** Lo que cambió de la versión [desde] a la de ahora, y el resumen de la de ahora. Null si no está esa versión. */
    fun sacar(disco: Disco, rel: String, desde: String?): Pair<String, String>? {
        if (desde == null) return null
        val base = disco.textoBase(rel, desde) ?: return null
        val actual = disco.textoDe(rel)
        val parche = runCatching {
            Fusion.diferencia(kotlinx.serialization.json.Json.parseToJsonElement(base), kotlinx.serialization.json.Json.parseToJsonElement(actual))
        }.getOrNull()
        return (parche?.toString() ?: "null") to sha256(Canonico.de(actual).toByteArray())
    }
}
