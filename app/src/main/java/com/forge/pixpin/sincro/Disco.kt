package com.forge.pixpin.sincro

import com.forge.pixpin.guardados.Clase
import com.forge.pixpin.guardados.Mensaje
import com.forge.pixpin.motor.Proyecto
import com.forge.pixpin.motor.Proyectos
import com.forge.pixpin.motor.TablasEnDisco
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/** La llave del archivo del chat: la comparten el almacén de mensajes y la sincronización. */
object Cerrojos {
    val chat = Any()
}

/** Un chat que se puede sincronizar: la conversación general o la de un proyecto. */
@Serializable
data class Chat(
    val id: String,
    val nombre: String,
    val mensajes: Int = 0,
    /** Cuándo se tocó por última vez: el proyecto o su último mensaje. Dice cuál de los dos es más reciente. */
    val tocado: Long = 0
)

/** La marca que deja un mensaje borrado. Ver [Diferencia.Apunte.borrado]. */
@Serializable
data class Marca(
    val chat: String,
    /** El código de chat que tenía (`47·K7Q2` o `47a`). */
    val sena: String,
    val cuando: Long,
    /** Su código único. Nulo en las marcas de antes de los códigos, que se reconocen por la seña. */
    val uid: String? = null
)

/**
 * **La lápida de un proyecto entero**: se borró aquí, y eso tiene que viajar.
 *
 * Los mensajes ya tenían la suya ([Marca]); los proyectos no, y por eso borrar uno **no borraba
 * casi nada** —se iba de la lista y el resto de sus archivos y todos sus mensajes se quedaban— y
 * la siguiente sincronización lo traía de vuelta entero desde el otro aparato. Lo dijo el usuario
 * el 16-sep-2026 y eligió que borrar borre en todos sus aparatos, con la copia de seguridad
 * delante por si acaso. Ver [Disco.borrarChat].
 *
 * Lleva **cuándo** se borró porque de ahí sale la única regla que salva trabajo: si en el otro
 * aparato se tocó el proyecto **después** de borrarlo aquí, vuelve. Borrar no puede pisar lo que
 * alguien escribió después sin saberlo.
 */
@Serializable
data class LapidaDeChat(val chat: String, val cuando: Long, val aparato: String = "")

/** Lo que se acordó con un aparato la última vez que se sincronizó un chat. */
@Serializable
data class Base(
    val mensajes: Map<String, String> = emptyMap(),
    val archivos: Map<String, String> = emptyMap(),
    /** El proyecto tal como quedó, en JSON portátil. */
    val proyecto: String? = null
) {
    val sello: String get() = sha256(Canonico.json.encodeToString(serializer(), this).toByteArray())
}

/** Lo que se sabe de un archivo del chat sin mandarlo. */
@Serializable
data class ArchivoInfo(
    val ruta: String,
    /** En los de texto, del JSON en orden canónico: sale igual aunque cada aparato lo escriba a su manera. */
    val resumen: String,
    val bytes: Long,
    val tocado: Long,
    val etiqueta: String = "",
    /** El resumen de los bytes tal cual, que es lo que se acordaba antes del 15-sep-2026. */
    val crudo: String = ""
)

/**
 * **Todo lo que la sincronización lee y escribe del disco de un aparato.**
 *
 * Trabaja sobre la carpeta `files` de la aplicación y **sin Android**: sabe dónde guarda cada
 * cosa PixPin (el chat en `guardados.jsonl`, los proyectos en `proyectos/proyectos.json`, los
 * lienzos en `pins/draw`…) y nada más. Así las pruebas montan dos «aparatos» en dos carpetas y
 * los sincronizan de verdad, por un socket, sin teléfonos.
 *
 * [alCambiar] avisa a la aplicación de lo que se escribió, para que recargue lo que tenga en
 * memoria (la lista de proyectos, el chat abierto, un lienzo en pantalla).
 */
class Disco(val filesDir: File, private val alCambiar: (Cambio) -> Unit = {}) {

    enum class Cambio { MENSAJES, PROYECTOS, ARCHIVOS, IDENTIDAD, GALERIA }

    val identidad = IdentidadEnDisco(filesDir)

    /**
     * **Las capturas de la galería**, para que viajen (ver [GaleriaQueViaja]). null: este disco no
     * tiene galería (las pruebas de siempre) y se contesta como un aparato de antes.
     */
    var capturas: CapturasDelAparato? = null
    private val rutas = Rutas(filesDir)
    private val carpeta get() = File(filesDir, "sincro").apply { mkdirs() }

    /** **El diario de cambios** de esta carpeta: ver [Diario]. Todo lo que se escribe aquí se apunta. */
    val diario: Diario get() = Diario.de(filesDir)

    // ------------------------------------------------------------------ el chat

    private val archivoDelChat get() = File(filesDir, "guardados.jsonl")

    fun leerMensajes(): List<Mensaje> {
        val f = archivoDelChat
        if (!f.exists()) return emptyList()
        return f.readLines().mapNotNull { l ->
            if (l.isBlank()) null else runCatching { JSON.decodeFromString(Mensaje.serializer(), l) }.getOrNull()
        }
    }

    /** Reescribe el chat entero y apunta en el [diario] lo que cambió respecto a [antes]. */
    private fun escribirMensajes(lista: List<Mensaje>, antes: List<Mensaje>) {
        val tmp = File(filesDir, "guardados.jsonl.sincro")
        tmp.writeText(lista.joinToString("") { JSON.encodeToString(Mensaje.serializer(), it) + "\n" })
        if (!tmp.renameTo(archivoDelChat)) { tmp.copyTo(archivoDelChat, overwrite = true); tmp.delete() }
        diario.mensajes(antes, lista)
    }

    /**
     * **Dos aparatos que repararon su chat por separado** ponen el mismo mensaje con el mismo id
     * y señas distintas. Queda el de la seña menor, que es la misma elección en los dos lados: así
     * al acabar los dos tienen uno, el mismo. Ver `RegistroDelChat`.
     */
    private fun sinRegistrosRepetidos(lista: List<Mensaje>): List<Mensaje> {
        val registros = lista.filter { it.id.startsWith(com.forge.pixpin.guardados.RegistroDelChat.PREFIJO) }
        if (registros.size < 2) return lista
        val queda = registros.groupBy { chatDe(it) to it.id }.values
            .filter { it.size > 1 }
            .associate { grupo -> grupo.first().let { chatDe(it) to it.id } to grupo.minByOrNull { senaDe(it) ?: "" } }
        if (queda.isEmpty()) return lista
        return lista.filter { m -> queda[chatDe(m) to m.id]?.let { it === m } ?: true }
    }

    /**
     * **El mismo mensaje dos veces en el archivo** (mismo id, mismo chat): pasaba con una lección
     * guardada dos veces a la vez (v0.103.1). Viajaba una sola vez —va por su código único—, pero
     * aquí seguía doble. Se queda la primera línea. No es borrar nada: el id sigue y no deja marca.
     * Los registros del chat tienen su propia regla ([sinRegistrosRepetidos]).
     */
    internal fun sinLineasRepetidas(lista: List<Mensaje>): List<Mensaje> {
        val vistos = HashSet<Pair<String, String>>()
        return lista.filter { m ->
            m.id.startsWith(com.forge.pixpin.guardados.RegistroDelChat.PREFIJO) || vistos.add(chatDe(m) to m.id)
        }
    }

    /**
     * Vuelve a poner mensajes que faltan (por su id), sin tocar los que hay. Es lo que hace
     * restaurar una copia: lo escrito después de la copia se queda. Ver [Copias].
     */
    fun reponerMensajes(extra: List<Mensaje>): Int {
        if (extra.isEmpty()) return 0
        var puestos = 0
        synchronized(Cerrojos.chat) {
            val lista = leerMensajes()
            val hay = lista.mapTo(HashSet()) { it.id }
            val nuevos = extra.filter { it.id !in hay }
            if (nuevos.isEmpty()) return 0
            escribirMensajes((lista + nuevos).sortedBy { it.cuando }, lista)
            puestos = nuevos.size
        }
        alCambiar(Cambio.MENSAJES)
        return puestos
    }

    /**
     * **Cambia el chat entero de una vez** (28-oct-2026, para las fotos por su hora): [cambio] recibe
     * lo que hay y devuelve lo que tiene que quedar, o null si no hay nada que cambiar. Se apunta en el
     * [diario] como cualquier escritura, así que viaja. Devuelve si se escribió.
     */
    fun cambiarMensajes(cambio: (List<Mensaje>) -> List<Mensaje>?): Boolean {
        synchronized(Cerrojos.chat) {
            val lista = leerMensajes()
            val nueva = cambio(lista) ?: return false
            if (nueva == lista) return false
            escribirMensajes(nueva, lista)
        }
        alCambiar(Cambio.MENSAJES)
        return true
    }

    fun chats(): List<Chat> {
        val todos = leerMensajes()
        val mensajes = todos.groupingBy { chatDe(it) }.eachCount()
        val ultimo = HashMap<String, Long>()
        for (m in todos) { val c = chatDe(m); if (m.cuando > (ultimo[c] ?: 0L)) ultimo[c] = m.cuando }
        val general = Chat(GENERAL, "Conversación general", mensajes[GENERAL] ?: 0, ultimo[GENERAL] ?: 0L)
        return listOf(general) + leerProyectos().map {
            Chat(it.id, it.nombre, mensajes[it.id] ?: 0, maxOf(it.tocado, ultimo[it.id] ?: 0L))
        }
    }

    /**
     * **Le pone sus códigos a todo lo que no los tiene** (ver [Codigos]): número a lo de antes de
     * que hubiera números —el mismo que ya se ve en el chat, el de su sitio—, el código único sacado
     * de su id y el código de este aparato a lo que no trae ni aparato ni letra. Si dos mensajes
     * propios quedaran con el mismo código de chat, el segundo pasa al siguiente número libre. Lo
     * que trae los de otro aparato no se toca nunca. Los proyectos y sus hojas, igual.
     */
    fun sellar() {
        val aparato = identidad.leer().yo.codigo
        // **Lo ya sellado no se relee** (como `ya_sellado` del PC, 11-oct-2026): quien responde sella
        // dos veces por chat en cada vuelta, y con las tareas al instante hay muchas vueltas cortas.
        // Si el chat y los proyectos siguen con el tamaño y la fecha de tras el último sellado, no
        // hay nada sin códigos.
        if (selladoCon != null && selladoCon == huellaDeSellado(aparato)) return
        val chatSellado: String
        synchronized(Cerrojos.chat) {
            val leida = leerMensajes()
            val lista = sinLineasRepetidas(leida)
            var cambio = lista.size != leida.size
            val nuevos = HashMap<String, Mensaje>()
            for ((_, suyos) in lista.groupBy { chatDe(it) }) {
                var sinNumero = 0
                val ordenados = suyos.sortedBy { it.cuando }
                var mayor = ordenados.maxOfOrNull { it.numero } ?: 0
                val vistas = HashSet<String>()
                for (m in ordenados) {
                    val sellado = Codigos.sellar(m, aparato)
                    var n = if (m.numero > 0) m.numero else ++sinNumero
                    val quien = sellado.aparato ?: sellado.letra.orEmpty()
                    if ("$n|$quien" in vistas && quien == aparato) { n = maxOf(mayor, suyos.size) + 1 }
                    mayor = maxOf(mayor, n)
                    vistas += "$n|$quien"
                    val puesto = if (n != sellado.numero) sellado.copy(numero = n) else sellado
                    if (puesto != m) { nuevos[m.id] = puesto; cambio = true }
                }
            }
            if (cambio) escribirMensajes(lista.map { nuevos[it.id] ?: it }, leida)
            chatSellado = selloDe(archivoDelChat)
        }
        var proyectosSellado = ""
        val proyectosSellados = synchronized(PROYECTOS) {
            val lista = leerProyectos()
            val sellados = lista.map { Codigos.sellar(it) }
            (sellados != lista).also { if (it) escribirProyectos(sellados, lista) }
                .also { proyectosSellado = selloDe(archivoDeProyectos) }
        }
        selladoCon = "$aparato|$chatSellado|$proyectosSellado"
        alCambiar(Cambio.MENSAJES)
        if (proyectosSellados) alCambiar(Cambio.PROYECTOS)
    }

    @Volatile private var selladoCon: String? = null
    private fun selloDe(f: File) = if (f.exists()) "${f.length()}:${f.lastModified()}" else "-"
    private fun huellaDeSellado(aparato: String?) = "$aparato|${selloDe(archivoDelChat)}|${selloDe(archivoDeProyectos)}"

    /** Crea un grupo nuevo con este aparato dentro, con la letra `a`. Devuelve el código. */
    fun crearGrupo(nombre: String? = null, codigo: String = Grupo.nuevoCodigo(), ahora: Long = System.currentTimeMillis()): String {
        val actual = identidad.leer()
        val yo = actual.yo.copy(nombre = nombre ?: actual.yo.nombre, letra = "a", desde = ahora)
        identidad.guardar(Identidad(yo = yo, codigo = codigo, miembros = listOf(yo)))
        sellar()
        alCambiar(Cambio.IDENTIDAD)
        return codigo
    }

    /**
     * Sale del grupo. Lo sellado se queda con su letra —ya es su nombre—, pero lo acordado con
     * los demás se olvida: si vuelve a entrar, la primera vuelta pregunta como la primera vez.
     */
    fun salirDelGrupo() {
        val actual = identidad.leer()
        identidad.guardar(Identidad(yo = actual.yo.copy(letra = null, desde = 0)))
        File(carpeta, "base").deleteRecursively()
        // Y hasta dónde quedó igual cada chat con cada uno: sin lo acordado, no vale nada.
        diario.olvidarCursores()
        alCambiar(Cambio.IDENTIDAD)
    }

    fun renombrar(nombre: String) {
        val actual = identidad.leer()
        val yo = actual.yo.copy(nombre = nombre)
        identidad.guardar(actual.copy(yo = yo, miembros = actual.miembros.map { if (it.id == yo.id) yo else it }))
        alCambiar(Cambio.IDENTIDAD)
    }

    /**
     * Los apuntes de un chat: uno por mensaje, **por su código único**, y uno por cada marca de
     * borrado. Una marca de antes de los códigos va por su seña (ver [Diferencia.MARCA_VIEJA]).
     */
    fun apuntes(chat: String): List<Diferencia.Apunte> {
        val vistos = HashSet<String>()
        val vivos = leerMensajes().filter { chatDe(it) == chat }.mapNotNull { m ->
            val clave = Codigos.unico(m)
            if (!vistos.add(clave)) return@mapNotNull null
            Diferencia.Apunte(clave, m.cuando, m.cuando, resumenDe(m), alias = senaDe(m))
        }
        val señas = vivos.mapNotNullTo(HashSet()) { it.alias }
        val borrados = marcas().filter { it.chat == chat }
            .filter { mk -> if (mk.uid != null) mk.uid !in vistos else mk.sena !in señas }
            .map { mk -> Diferencia.Apunte(mk.uid ?: (Diferencia.MARCA_VIEJA + mk.sena), mk.cuando, mk.cuando, BORRADO, borrado = true, alias = mk.sena.ifBlank { null }) }
            .associateBy { it.sena }.values
        return vivos + borrados
    }

    /** Los mensajes de un chat por su código único. */
    fun mensajesPorClave(chat: String): Map<String, Mensaje> {
        val salida = LinkedHashMap<String, Mensaje>()
        for (m in leerMensajes()) if (chatDe(m) == chat) salida.putIfAbsent(Codigos.unico(m), m)
        return salida
    }

    /** El mensaje listo para viajar: sin rutas de este aparato. */
    fun portatil(m: Mensaje): String = rutas.aPortatil(JSON.encodeToString(Mensaje.serializer(), m))

    /**
     * El resumen de un mensaje. Sin el recordatorio, que es de este aparato, ni los códigos, que no
     * cambian nunca después de nacer y que lo guardado de antes recibe al sellarse: si contaran,
     * sellar haría parecer cambiado todo lo que ya se había sincronizado.
     */
    fun resumenDe(m: Mensaje): String = sha256(textoDeBase(m).toByteArray())

    /** El mensaje tal como se guarda de base para fusionar: portátil, canónico y sin lo que no cuenta. */
    fun textoDeBase(m: Mensaje): String = Canonico.de(portatil(m.copy(recuerdaEn = null, uid = null, aparato = null)))

    /**
     * Pone y quita mensajes de un chat. [poner] viene en JSON portátil; lo que ya hubiera con ese
     * código único se sustituye —conservando el recordatorio, que es de este aparato—. [borrar] son
     * códigos únicos: lo borrado deja su marca y se lleva su adjunto.
     */
    fun aplicarMensajes(
        chat: String, poner: List<String>, borrar: List<String>, ahora: Long = System.currentTimeMillis(),
        /**
         * Archivos que no se tiran aunque se vaya el mensaje que los nombra: los de los demás chats,
         * cuando quien borra es una persona de un chat compartido ([AccesoDePersona]). Sin esto, un
         * mensaje suyo que nombrara un adjunto de otro chat se lo llevaría al borrarse.
         */
        noBorrar: Set<String> = emptySet()
    ) {
        if (poner.isEmpty() && borrar.isEmpty()) return
        val adjuntosFuera = ArrayList<String>()
        val anotadosFuera = ArrayList<String>()
        synchronized(Cerrojos.chat) {
            val lista = leerMensajes().toMutableList()
            val quitar = borrar.toSet()
            val llegan = poner.mapNotNull { runCatching { JSON.decodeFromString(Mensaje.serializer(), rutas.aLocal(it)) }.getOrNull() }
                .filter { chatDe(it) == chat }
            val porClave = llegan.associateBy { Codigos.unico(it) }
            val puestos = HashSet<String>()
            val quitados = ArrayList<Mensaje>()
            val salida = ArrayList<Mensaje>(lista.size + llegan.size)
            for (m in lista) {
                val clave = if (chatDe(m) == chat) Codigos.unico(m) else null
                when {
                    clave != null && clave in porClave -> {
                        if (puestos.add(clave)) salida += porClave.getValue(clave).copy(recuerdaEn = m.recuerdaEn)
                    }
                    clave != null && clave in quitar -> {
                        quitados += m
                        m.ruta?.let { adjuntosFuera += it }
                        if (m.ruta != null || m.clase == Clase.NOTA) anotadosFuera += clave
                    }
                    else -> salida += m
                }
            }
            for ((clave, m) in porClave) if (clave !in puestos) salida += m.copy(recuerdaEn = null)
            escribirMensajes(sinRegistrosRepetidos(salida), lista)
            val marcasViejas = marcas()
            val señasPuestas = llegan.mapNotNullTo(HashSet()) { senaDe(it) }
            val nuevas = marcasViejas.filterNot { it.chat == chat && ((it.uid != null && it.uid in porClave) || (it.uid == null && it.sena in señasPuestas)) } +
                quitar.filter { k -> marcasViejas.none { it.chat == chat && it.uid == k } }.map { k ->
                    Marca(chat, quitados.firstOrNull { Codigos.unico(it) == k }?.let { senaDe(it) }.orEmpty(), ahora, uid = k)
                }
            escribirMarcas(nuevas)
        }
        val quitados = ArrayList<String>()
        for (ruta in adjuntosFuera) {
            val rel = rutas.relativa(ruta) ?: continue
            if (rel.startsWith("guardados/") && rel !in noBorrar && File(filesDir, rel).delete()) quitados += rel
        }
        // Y lo anotado sobre ellos, que sin su mensaje ya no viaja ni se abre. Ver [AnotacionesDelAdjunto].
        for (uid in anotadosFuera) AnotacionesDelAdjunto.todoDe(filesDir, uid)
            .filter { f -> noBorrar.isEmpty() || rutas.relativa(f.absolutePath) !in noBorrar }
            .forEach { f -> if (f.delete()) rutas.relativa(f.absolutePath)?.let { quitados += it } }
        diario.archivos(quitados)
        alCambiar(Cambio.MENSAJES)
    }

    // --------------------------------------------------------- marcas de borrado

    private val archivoDeMarcas get() = File(carpeta, "borrados.jsonl")

    // ------------------------------------------------- los proyectos borrados

    private val archivoDeLapidas get() = File(carpeta, "chatsborrados.jsonl")

    /** Los proyectos borrados en este aparato. Ver [LapidaDeChat]. */
    fun lapidas(): List<LapidaDeChat> {
        val f = archivoDeLapidas
        if (!f.exists()) return emptyList()
        // La última línea de cada chat manda: borrar, volver a recibirlo y volver a borrarlo
        // deja tres líneas y la que vale es la de ahora.
        return f.readLines().mapNotNull { l -> runCatching { JSON.decodeFromString(LapidaDeChat.serializer(), l) }.getOrNull() }
            .associateBy { it.chat }.values.toList()
    }

    fun lapidaDe(chat: String): LapidaDeChat? = lapidas().firstOrNull { it.chat == chat }

    fun anotarLapida(chat: String, cuando: Long = System.currentTimeMillis(), aparato: String = "") =
        synchronized(LAPIDAS) {
            archivoDeLapidas.parentFile?.mkdirs()
            archivoDeLapidas.appendText(JSON.encodeToString(LapidaDeChat.serializer(), LapidaDeChat(chat, cuando, aparato)) + "\n")
            diario.lapida(chat, Diario.ALTA)
        }

    /**
     * **Se levanta la lápida**: el proyecto vuelve a estar vivo aquí.
     *
     * Pasa cuando el otro aparato lo había tocado después de que se borrara —entonces se
     * conserva— y cuando llega otra vez a propósito, por Wi-Fi o abriendo un `.pixpin`: recibir
     * algo es decir que se quiere.
     */
    fun quitarLapida(chat: String) = synchronized(LAPIDAS) {
        val f = archivoDeLapidas
        if (!f.exists()) return
        val quedan = f.readLines().mapNotNull { l -> runCatching { JSON.decodeFromString(LapidaDeChat.serializer(), l) }.getOrNull() }
            .filter { it.chat != chat }
        val tmp = File(carpeta, "chatsborrados.jsonl.tmp")
        tmp.writeText(quedan.joinToString("") { JSON.encodeToString(LapidaDeChat.serializer(), it) + "\n" })
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        diario.lapida(chat, Diario.BAJA)
    }

    /**
     * **Borra el proyecto entero aquí**: copia de seguridad, sus mensajes a la papelera, la
     * lápida, y fuera de la lista.
     *
     * Los mensajes se borran por el camino de siempre ([aplicarMensajes]), que es el que deja
     * marca de cada uno: sin eso volverían solos en la vuelta siguiente aunque el proyecto no
     * volviera, y el proyecto se reharía a partir de ellos. **Los archivos no se tocan**: son lo
     * que la copia necesita para devolverlo si el usuario se arrepiente, y ya no los cuenta ni
     * los manda nadie porque nada los alcanza. Ver [Copias] y [alcance].
     */
    fun borrarChat(chat: String, motivo: String, cuando: Long = System.currentTimeMillis(), aparato: String = "") {
        Copias(this).hacer(chat, motivo, cuando)
        val claves = mensajesPorClave(chat).keys.toList()
        if (claves.isNotEmpty()) aplicarMensajes(chat, emptyList(), claves)
        synchronized(PROYECTOS) { val l = leerProyectos(); escribirProyectos(l.filter { it.id != chat }, l) }
        anotarLapida(chat, cuando, aparato)
        alCambiar(Cambio.PROYECTOS)
    }

    fun marcas(): List<Marca> {
        val f = archivoDeMarcas
        if (!f.exists()) return emptyList()
        return f.readLines().mapNotNull { l -> runCatching { JSON.decodeFromString(Marca.serializer(), l) }.getOrNull() }
    }

    fun anotarBorrados(nuevas: List<Marca>) {
        if (nuevas.isEmpty()) return
        synchronized(MARCAS) {
            archivoDeMarcas.appendText(nuevas.joinToString("") { JSON.encodeToString(Marca.serializer(), it) + "\n" })
            diario.marcas(nuevas)
        }
    }

    private fun escribirMarcas(lista: List<Marca>) = synchronized(MARCAS) {
        val tmp = File(carpeta, "borrados.jsonl.tmp")
        tmp.writeText(lista.joinToString("") { JSON.encodeToString(Marca.serializer(), it) + "\n" })
        if (!tmp.renameTo(archivoDeMarcas)) { archivoDeMarcas.delete(); tmp.renameTo(archivoDeMarcas) }
        diario.marcas(emptyList())
    }

    // ---------------------------------------------------------------- proyectos

    private val archivoDeProyectos get() = File(filesDir, "proyectos/proyectos.json")

    fun leerProyectos(): List<Proyecto> = runCatching {
        if (!archivoDeProyectos.exists()) emptyList()
        else Proyectos.json.decodeFromString<List<Proyecto>>(archivoDeProyectos.readText())
    }.getOrDefault(emptyList())

    fun proyectoPortatil(id: String): String? =
        leerProyectos().firstOrNull { it.id == id }?.let { rutas.aPortatil(Proyectos.json.encodeToString(Proyecto.serializer(), it)) }

    fun proyectoDe(portatil: String?): Proyecto? = portatil?.let {
        runCatching { Proyectos.json.decodeFromString(Proyecto.serializer(), rutas.aLocal(it)) }.getOrNull()
    }

    fun aPortatil(p: Proyecto): String = rutas.aPortatil(Proyectos.json.encodeToString(Proyecto.serializer(), p))

    fun guardarProyecto(p: Proyecto) {
        val antes: List<Proyecto>
        val despues: List<Proyecto>
        synchronized(PROYECTOS) {
            antes = leerProyectos()
            despues = Proyectos.actualizada(antes, p)
            escribirProyectos(despues, antes)
        }
        AnotacionesDelAdjunto.borrarLoDeHojasQuitadas(filesDir, antes, despues)
        borrarFotosSobrantes(antes, despues)
        alCambiar(Cambio.PROYECTOS)
    }

    /**
     * **La foto del chat vieja, fuera** (25-oct-2026, como `avatar::limpiar_tras_llegar` del PC):
     * cuando la foto de un proyecto cambia —aquí o porque llegó otra—, la imagen que ya no nombra
     * nadie se borra, sin lápida: al no nombrarla ningún proyecto ya no está en el [alcance] y no
     * viaja. Solo lo de dentro de `files` y con nombre de foto. Ver [com.forge.pixpin.guardados.FotoDelChat.sobrantes].
     */
    fun borrarFotosSobrantes(antes: List<Proyecto>, despues: List<Proyecto>) {
        for (r in com.forge.pixpin.guardados.FotoDelChat.sobrantes(antes, despues)) {
            val rel = rutas.relativa(r) ?: continue
            if (permitida(rel) && runCatching { File(filesDir, rel).delete() }.getOrDefault(false)) diario.archivo(rel)
        }
    }

    private fun escribirProyectos(lista: List<Proyecto>, antes: List<Proyecto>) {
        archivoDeProyectos.parentFile?.mkdirs()
        val tmp = File(archivoDeProyectos.parentFile, "proyectos.json.sincro")
        tmp.writeText(Proyectos.json.encodeToString(lista))
        if (!tmp.renameTo(archivoDeProyectos)) { tmp.copyTo(archivoDeProyectos, overwrite = true); tmp.delete() }
        diario.proyectos(antes, lista)
    }

    /**
     * **Mete dentro de la carpeta de PixPin el PDF de los proyectos que lo tengan fuera.**
     *
     * Un proyecto creado al compartir un PDF desde otra aplicación se quedaba con el documento en
     * la caché, y lo de fuera de `files` no viaja: al sincronizar llegaba el proyecto con sus
     * hojas pero **sin el PDF debajo**, que es lo que vio el usuario. Además la caché la vacía
     * Android cuando quiere. Se copia a `proyectos/` y se cambian las rutas en el proyecto y en
     * los mensajes que lo señalan. Si el de fuera ya no está pero hay copia limpia dentro, el
     * documento se repone desde ella. Devuelve cuántos documentos se movieron.
     */
    fun adoptarDocumentos(): Int {
        val movidos = HashMap<String, String>()
        synchronized(PROYECTOS) {
            val lista = leerProyectos()
            val nuevos = lista.map { p ->
                fun dentro(ruta: String?) = ruta != null && rutas.relativa(ruta) != null
                fun adoptar(ruta: String?, prefijo: String, reserva: String?): String? {
                    if (ruta == null || dentro(ruta)) return ruta
                    movidos[ruta]?.let { return it }
                    val fuente = File(ruta).takeIf { it.isFile } ?: reserva?.takeIf { dentro(it) }?.let { File(it) }?.takeIf { it.isFile } ?: return ruta
                    var destino = File(filesDir, "proyectos/$prefijo-${limpio(p.id)}.pdf")
                    var i = 2
                    while (destino.exists()) destino = File(filesDir, "proyectos/$prefijo-${limpio(p.id)}-${i++}.pdf")
                    destino.parentFile?.mkdirs()
                    runCatching { fuente.copyTo(destino) }.getOrElse { return ruta }
                    movidos[ruta] = destino.absolutePath
                    return destino.absolutePath
                }
                val limpioNuevo = adoptar(p.pdfLimpio, "limpio", null)
                p.copy(pdfLimpio = limpioNuevo, pdfOrigen = adoptar(p.pdfOrigen, "doc", limpioNuevo))
            }
            if (movidos.isEmpty()) return 0
            escribirProyectos(nuevos, lista)
            diario.archivos(movidos.values.mapNotNull { rutas.relativa(it) })
        }
        synchronized(Cerrojos.chat) {
            val lista = leerMensajes()
            if (lista.any { it.ruta in movidos }) escribirMensajes(lista.map { m -> m.ruta?.let { movidos[it] }?.let { m.copy(ruta = it) } ?: m }, lista)
        }
        alCambiar(Cambio.PROYECTOS)
        alCambiar(Cambio.MENSAJES)
        return movidos.size
    }

    // ------------------------------------------------------ los archivos del chat

    /**
     * **Qué archivos son de un chat**, con qué mensaje u hoja se nombran: los adjuntos de sus
     * mensajes, lo que señalan (un lienzo, una tabla, un croquis), lo del proyecto (su PDF, sus
     * hojas) y, dentro de cada lienzo o croquis, las fotos que lleva. Rutas relativas a `files`.
     */
    fun alcance(chat: String): Map<String, String> =
        alcanceCon(chat, leerMensajes().filter { chatDe(it) == chat }, leerProyectos(), AnotacionesDelAdjunto.porUid(filesDir))
            // Lo que es de cada chat, para el diario: un archivo cambiado dice así de qué chat es.
            .also { diario.indexar(chat, it.keys) }

    /**
     * **De qué chats son estos archivos**, para los que el índice del [diario] no conoce (uno
     * recién llegado): los chats cuyos mensajes o proyecto los nombran. No mira dentro de los
     * lienzos —una foto nueva dentro de un lienzo llega con el lienzo, que sí está en el índice—.
     * Lo que no nombra nadie no es de ningún chat.
     */
    fun chatsQueNombran(rels: Set<String>): Map<String, Set<String>> {
        if (rels.isEmpty()) return emptyMap()
        val salida = HashMap<String, MutableSet<String>>()
        val anotado = AnotacionesDelAdjunto.porUid(filesDir)
        for (m in leerMensajes()) for (r in referencias(m, anotado)) if (r in rels) salida.getOrPut(r) { HashSet() } += chatDe(m)
        for (p in leerProyectos()) for (r in referencias(p)) if (r in rels) salida.getOrPut(r) { HashSet() } += p.id
        return salida
    }

    /**
     * **Los grupos de archivos de un chat**, para ordenarlos ([Prioridad]): lo de una carpeta del
     * chat (una clase grabada: su voz, sus capturas y su vídeo) va junto, con la clave de la
     * carpeta; lo de un mensaje suelto (su adjunto y lo anotado), con la del mensaje. Y si lo nombra
     * una lista de tareas (o una tabla del chat). Ruta → (grupo, de tareas).
     */
    fun gruposDeArchivos(chat: String): Map<String, Pair<String, Boolean>> {
        val mensajes = leerMensajes().filter { chatDe(it) == chat }
        val anotado = AnotacionesDelAdjunto.porUid(filesDir)
        val porId = mensajes.associateBy { it.id }
        val grupoDe = HashMap<String, String>()
        for (m in mensajes) {
            val c = runCatching { com.forge.pixpin.guardados.CarpetaDePaginas.deMensaje(m) }.getOrNull() ?: continue
            for (p in c.paginas) if (p.id in porId) grupoDe.putIfAbsent(p.id, "carpeta:${m.id}")
        }
        val salida = HashMap<String, Pair<String, Boolean>>()
        for (m in mensajes) {
            val tareas = runCatching { com.forge.pixpin.mini.TodasLasTareas.esLista(m) || com.forge.pixpin.guardados.TablasDelChat.esTabla(m) }.getOrDefault(false)
            val grupo = grupoDe[m.id] ?: "mensaje:${m.id}"
            for (r in runCatching { referencias(m, anotado) }.getOrDefault(emptySet())) {
                val antes = salida[r]
                salida[r] = (antes?.first ?: grupo) to (tareas || antes?.second == true)
            }
        }
        return salida
    }

    /** Los chats que tienen alguna lista de tareas (o tabla del chat): van los primeros. */
    fun chatsConTareas(): Set<String> = leerMensajes().filter {
        runCatching { com.forge.pixpin.mini.TodasLasTareas.esLista(it) || com.forge.pixpin.guardados.TablasDelChat.esTabla(it) }.getOrDefault(false)
    }.mapTo(HashSet()) { chatDe(it) }

    /** La [Diario.Vista] de ahora, resolviendo con [chatsQueNombran]. */

    fun vistaDelDiario(desde: Long = 0): Diario.Vista = diario.vista(desde) { chatsQueNombran(it) }

    /**
     * **Lo que no es de [permitidos]**: los archivos de todos los demás chats (la conversación general
     * incluida). Es la valla de los chats compartidos ([AccesoDePersona]): una persona no puede pedir,
     * escribir ni hacer borrar nada de aquí, aunque se las arregle para nombrarlo desde un mensaje del
     * chat que sí comparte. Lee el chat y los proyectos una sola vez; los lienzos de cada chat, para
     * saber qué fotos llevan dentro, también.
     */
    fun prohibidosPara(permitidos: Set<String>): Set<String> {
        val porChat = leerMensajes().groupBy { chatDe(it) }
        val proyectos = leerProyectos()
        val anotado = AnotacionesDelAdjunto.porUid(filesDir)
        val otros = (porChat.keys + proyectos.map { it.id } + GENERAL) - permitidos
        val salida = HashSet<String>()
        for (c in otros) salida += alcanceCon(c, porChat[c].orEmpty(), proyectos, anotado).keys
        // Lo que está en los dos (un adjunto reenviado de un chat a otro) se queda fuera: ante la
        // duda, no se da.
        return salida
    }

    /** Los códigos únicos de los mensajes de los chats que no son [chat]. */
    fun unicosFueraDe(chat: String): Set<String> =
        leerMensajes().filter { chatDe(it) != chat }.mapTo(HashSet()) { Codigos.unico(it) }

    /**
     * **Qué archivos nombra un mensaje**, existan o no aquí: su adjunto, las rutas de dentro, el
     * lienzo, la tabla o el croquis que señala, sus tablas de libro y lo anotado con su código. Lo
     * mismo que mira [alcance], mensaje a mensaje.
     */
    fun referencias(m: Mensaje, anotado: Map<String, List<String>> = AnotacionesDelAdjunto.porUid(filesDir)): Set<String> {
        val salida = HashSet<String>()
        salida += rutas.enTexto(JSON.encodeToString(Mensaje.serializer(), m))
        m.ruta?.let { rutas.relativa(it) }?.let { salida += it }
        val tablas = TablasEnDisco.de(filesDir)
        fun tabla(id: String?) = id?.let { rutas.relativa(tablas.archivo(it).absolutePath) }
        when (m.clase) {
            Clase.DIBUJO, Clase.PAGINA -> m.referencia?.let { salida += "pins/draw/$it.excalidraw.gz" }
            Clase.IMAGEN -> salida += "pins/draw/${m.referencia ?: "foto-${m.id}"}.excalidraw.gz"
            Clase.TABLA -> tabla(m.referencia)?.let { salida += it }
            Clase.CROQUIS -> m.referencia?.let { salida += "croquis3d/$it.croquis.gz" }
            Clase.ARCHIVO -> {
                var i = 0
                while (true) {
                    val f = tablas.archivo("libro-${m.id}-$i")
                    if (!f.exists()) break
                    rutas.relativa(f.absolutePath)?.let { salida += it }; i++
                }
            }
            else -> {}
        }
        anotado[Codigos.unico(m)]?.let { salida += it }
        return salida
    }

    /** Lo mismo de un proyecto: su documento, sus hojas y lo anotado con su código o el de sus hojas. */
    fun referencias(p: Proyecto): Set<String> {
        val salida = HashSet<String>()
        salida += rutas.enTexto(Proyectos.json.encodeToString(Proyecto.serializer(), p))
        for (r in listOfNotNull(p.pdfOrigen, p.pdfLimpio)) rutas.relativa(r)?.let { salida += it }
        val tablas = TablasEnDisco.de(filesDir)
        val anotado = AnotacionesDelAdjunto.porUid(filesDir)
        anotado[Codigos.unico(p)]?.let { salida += it }
        for (h in p.hojas) {
            h.dibujo?.let { salida += "pins/draw/$it.excalidraw.gz" }
            h.tabla?.let { rutas.relativa(tablas.archivo(it).absolutePath) }?.let { salida += it }
            h.croquis?.let { salida += "croquis3d/$it.croquis.gz" }
            anotado[Codigos.unico(h)]?.let { salida += it }
        }
        for (c in p.croquis) salida += "croquis3d/$c.croquis.gz"
        return salida
    }

    /**
     * **De lo que manda una persona para [chat], lo que se puede poner** (JSON portátil): mensajes
     * de ese chat que no se hacen pasar por uno de otro chat (mismo código único) ni nombran nada de
     * [prohibidos]. Lo demás se tira sin decir nada: un PixPin honrado nunca lo manda.
     */
    fun soloDeEsteChat(chat: String, poner: List<String>, prohibidos: Set<String>): List<String> {
        if (poner.isEmpty()) return poner
        val ajenos = unicosFueraDe(chat)
        val anotado = AnotacionesDelAdjunto.porUid(filesDir)
        return poner.filter { j ->
            val m = runCatching { JSON.decodeFromString(Mensaje.serializer(), rutas.aLocal(j)) }.getOrNull() ?: return@filter false
            // **Ni las letras de los planos** (14-oct-2026): en este aparato `files/` es uno solo, así
            // que lo que una persona nombrara en `guardados/fuentes-shx/` caería en la carpeta de las
            // letras del usuario y el visor de planos lo usaría. Solo viajan entre los aparatos propios,
            // por el chat «Asistente». Ver [com.forge.pixpin.planos.LetrasQueViajan].
            chatDe(m) == chat && Codigos.unico(m) !in ajenos &&
                referencias(m, anotado).none { it in prohibidos || it.startsWith(CARPETA_DE_LETRAS) }
        }
    }

    /** El proyecto que manda una persona, si es el de [chat] y no nombra nada de [prohibidos]. */
    fun proyectoSeguro(chat: String, p: Proyecto?, prohibidos: Set<String>): Proyecto? =
        p?.takeIf { it.id == chat && referencias(it).none { r -> r in prohibidos } }

    private fun alcanceCon(chat: String, mensajes: List<Mensaje>, proyectos: List<Proyecto>, anotado: Map<String, List<String>>): Map<String, String> {
        val salida = LinkedHashMap<String, String>()
        val pendientes = ArrayDeque<Pair<String, String>>()
        fun poner(rel: String?, etiqueta: String) {
            if (rel == null || rel in salida || !permitida(rel)) return
            val f = File(filesDir, rel)
            if (!f.isFile) return
            salida[rel] = etiqueta
            if (esTexto(rel)) pendientes += rel to etiqueta
            // Los marcadores de un lienzo, con él. Ver [AnotacionesDelAdjunto.delLienzo].
            AnotacionesDelAdjunto.marcasJuntoA(rel)?.let { poner(it, etiqueta) }
            AnotacionesDelAdjunto.vistasJuntoA(rel)?.let { poner(it, etiqueta) }
        }
        fun dibujo(id: String?) = id?.let { "pins/draw/$it.excalidraw.gz" }
        fun croquis(id: String?) = id?.let { "croquis3d/$it.croquis.gz" }
        val tablas = TablasEnDisco.de(filesDir)
        fun tabla(id: String?) = id?.let { rutas.relativa(tablas.archivo(it).absolutePath) }
        // Lo anotado sobre los adjuntos (PDF suelto, Word, libro), nombrado por el código del
        // mensaje: tinta, marcadores, espacios. Ver [AnotacionesDelAdjunto].

        for (m in mensajes) {
            val etiqueta = etiquetaDe(m)
            for (rel in rutas.enTexto(JSON.encodeToString(Mensaje.serializer(), m))) poner(rel, etiqueta)
            // La ruta del adjunto, ENTERA: `enTexto` corta en `)` (por los enlaces de Markdown) y
            // «informe (1).pdf» se quedaba en «informe (1», que no es un archivo: no viajaba nunca y
            // el PC lo reenviaba en cada vuelta (1-oct-2026, arreglado igual en el PC).
            m.ruta?.let { rutas.relativa(it) }?.let { poner(it, etiqueta) }
            // Lo anotado: sobre un adjunto, y los comentarios de una nota (`anot-<uid>.comentarios.json`).
            if ((m.ruta != null || m.clase == Clase.NOTA) && anotado.isNotEmpty()) anotado[Codigos.unico(m)]?.forEach { poner(it, etiqueta) }
            when (m.clase) {
                Clase.DIBUJO, Clase.PAGINA -> poner(dibujo(m.referencia), etiqueta)
                Clase.IMAGEN -> poner(dibujo(m.referencia ?: "foto-${m.id}"), etiqueta)
                Clase.TABLA -> poner(tabla(m.referencia), etiqueta)
                Clase.CROQUIS -> poner(croquis(m.referencia), etiqueta)
                Clase.ARCHIVO -> {
                    var i = 0
                    while (true) {
                        val f = tablas.archivo("libro-${m.id}-$i")
                        if (!f.exists()) break
                        poner(rutas.relativa(f.absolutePath), etiqueta); i++
                    }
                }
                else -> {}
            }
        }
        proyectos.firstOrNull { it.id == chat }?.let { p ->
            for (rel in rutas.enTexto(Proyectos.json.encodeToString(Proyecto.serializer(), p))) poner(rel, p.nombre)
            // El documento del proyecto con su ruta entera (los paréntesis, como arriba).
            for (r in listOfNotNull(p.pdfOrigen, p.pdfLimpio)) rutas.relativa(r)?.let { poner(it, p.nombre) }
            // Los marcadores y espacios de su PDF, con el código del proyecto.
            anotado[Codigos.unico(p)]?.forEach { poner(it, p.nombre) }
            for (h in p.hojas) {
                val etiqueta = h.nombre.ifBlank { p.nombre }
                poner(dibujo(h.dibujo), etiqueta)
                poner(tabla(h.tabla), etiqueta)
                poner(croquis(h.croquis), etiqueta)
                // Los comentarios de una hoja nota, con el código de la hoja.
                if (anotado.isNotEmpty()) anotado[Codigos.unico(h)]?.forEach { poner(it, etiqueta) }
            }
            for (c in p.croquis) poner(croquis(c), p.nombre)
        }
        // Lo que llevan dentro los lienzos y los croquis: sus fotos.
        while (pendientes.isNotEmpty()) {
            val (rel, etiqueta) = pendientes.removeFirst()
            val texto = runCatching { textoDe(rel) }.getOrNull() ?: continue
            for (dentro in rutas.enTexto(texto)) poner(dentro, etiqueta)
        }
        return salida
    }

    private fun etiquetaDe(m: Mensaje): String {
        val sena = senaDe(m)?.let { "#$it · " }.orEmpty()
        return sena + m.nombre.ifBlank { m.texto.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(60) }
            .ifBlank { m.clase.name.lowercase() }
    }

    /** Resumen, tamaño y fecha de cada archivo del chat. Lo ya calculado se recuerda por tamaño y fecha. */
    fun archivos(
        chat: String,
        /** Lo ya calculado en esta vuelta: se reutiliza si el archivo sigue con el mismo tamaño y fecha. */
        conocidos: Map<String, ArchivoInfo> = emptyMap()
    ): List<ArchivoInfo> {
        val cache = leerCache()
        var tocada = false
        val salida = alcance(chat).map { (rel, etiqueta) ->
            val f = File(filesDir, rel)
            conocidos[rel]?.takeIf { it.bytes == f.length() && it.tocado == f.lastModified() }?.let { return@map it.copy(etiqueta = etiqueta) }
            val sello = "${f.length()}:${f.lastModified()}"
            val guardado = cache[rel]
            // **Solo lo grande se fía de la fecha.** Hay Android donde reescribir un archivo pequeño no
            // le cambia la fecha: con la caché, un lienzo retocado parecía el de antes y no se mandaba.
            // Medir lo pequeño cada vez cuesta nada; lo grande (un PDF de 300 MB) sí se recuerda.
            if (guardado != null && f.length() > UMBRAL_DE_CACHE && guardado.substringBefore('|') == sello) {
                ArchivoInfo(rel, guardado.substringAfter('|'), f.length(), f.lastModified(), etiqueta)
            } else if (esTexto(rel)) {
                val texto = textoDe(rel)
                val canonico = sha256(Canonico.de(texto).toByteArray())
                cache[rel] = "$sello|$canonico"; tocada = true
                ArchivoInfo(rel, canonico, f.length(), f.lastModified(), etiqueta, crudo = sha256(texto.toByteArray()))
            } else {
                val resumen = resumenDeArchivo(rel)
                cache[rel] = "$sello|$resumen"; tocada = true
                ArchivoInfo(rel, resumen, f.length(), f.lastModified(), etiqueta, crudo = resumen)
            }
        }
        if (tocada) guardarCache(cache)
        return salida
    }

    /** El resumen de un archivo como lo cuenta la sincronización: canónico si es de texto. */
    fun resumenDeArchivo(rel: String): String =
        if (esTexto(rel)) sha256(Canonico.de(textoDe(rel)).toByteArray())
        else File(filesDir, rel).inputStream().use { sha256De(it) }

    /**
     * Los lienzos, los croquis y las tablas viajan **como texto y sin rutas de este aparato**:
     * dentro llevan la ruta de sus fotos, y en el otro aparato la carpeta puede ser otra.
     */
    fun esTexto(rel: String) = rel.endsWith(".excalidraw.gz") || rel.endsWith(".croquis.gz") ||
        (rel.startsWith("tablas/") && rel.endsWith(".json"))

    /** El contenido de un archivo de texto, ya portátil. */
    fun textoDe(rel: String): String {
        val f = File(filesDir, rel)
        val crudo = if (rel.endsWith(".gz")) GZIPInputStream(f.inputStream()).use { it.readBytes().decodeToString() } else f.readText()
        return rutas.aPortatil(crudo)
    }

    /**
     * **El texto de la versión [resumen] de [rel]**: el archivo de ahora si es esa, o lo acordado
     * guardado ([objeto]). Null si no se tiene.
     */
    fun textoBase(rel: String, resumen: String): String? {
        objeto(resumen)?.let { return it }
        if (!File(filesDir, rel).isFile) return null
        val ahora = runCatching { textoDe(rel) }.getOrNull() ?: return null
        return ahora.takeIf { sha256(Canonico.de(it).toByteArray()) == resumen }
    }

    /** Escribe un archivo de texto (JSON portátil) en su sitio, comprimido si toca. */
    fun escribirTexto(rel: String, portatil: String): Boolean =
        escribirArchivo(rel) { salida -> salida.write(portatil.toByteArray()); true }

    fun abrir(rel: String): InputStream = File(filesDir, rel).inputStream()

    fun archivo(rel: String): File = File(filesDir, rel)

    /** La ruta [ruta] de este aparato, relativa a `files`; null si es de fuera. */
    fun relativa(ruta: String): String? = rutas.relativa(ruta)

    fun largo(rel: String): Long = File(filesDir, rel).length()

    /**
     * Escribe un archivo que llega: primero a un temporal, y se cambia al acabar. Si [llenar]
     * devuelve `false` (el otro dice que lo mandado no vale) o falla, el temporal se tira y el
     * archivo de antes se queda como estaba. Devuelve si se escribió.
     *
     * **Sin perder lo de antes ni descuadrar la conversación** (27-oct-2026):
     *
     * - Se renombra **encima** del de antes, de un paso. Antes se borraba primero y se renombraba
     *   después: morir entre las dos cosas dejaba el lienzo sin archivo.
     * - El temporal se baja a disco (`fsync`) antes de renombrar: un apagón no deja un archivo vacío
     *   con el nombre bueno.
     * - **Sin sitio** ([bytes] que no caben, o el disco que se llena a medias): lo que viene por el
     *   cable se lee y se tira ([SalidaQueAguanta]) para que la conversación siga en su sitio, el
     *   temporal se borra y se devuelve `false` (el que manda lo ve como «saltado»). [faltoEspacio]
     *   lo dice.
     */
    fun escribirArchivo(rel: String, bytes: Long = -1, llenar: (java.io.OutputStream) -> Boolean): Boolean {
        require(permitida(rel)) { "Ruta no permitida: $rel" }
        val destino = File(filesDir, rel)
        destino.parentFile?.mkdirs()
        val tmp = File(destino.parentFile, destino.name + ".sincro")
        if (bytes >= 0 && !cabe(destino.parentFile ?: filesDir, bytes)) {
            faltoEspacio = true
            llenar(java.io.OutputStream.nullOutputStream())
            return false
        }
        try {
            val fos = java.io.FileOutputStream(tmp)
            val aguanta = SalidaQueAguanta(java.io.BufferedOutputStream(fos, 1 shl 16))
            val entero = try {
                llenar(aguanta).also { aguanta.flush(); if (aguanta.fallo == null) runCatching { fos.fd.sync() } }
            } finally {
                aguanta.close()
            }
            if (aguanta.fallo != null) { tmp.delete(); faltoEspacio = true; return false }
            if (!entero) { tmp.delete(); return false }
            if (esTexto(rel)) {
                val local = rutas.aLocal(tmp.readText())
                if (rel.endsWith(".gz")) GZIPOutputStream(tmp.outputStream()).use { it.write(local.toByteArray()) }
                else tmp.writeText(local)
            }
            ponerEnSuSitio(tmp, destino)
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        }
        alCambiado(rel)
        return true
    }

    /** Renombra [tmp] encima de [destino], de un paso; si el sistema no deja, como antes. */
    private fun ponerEnSuSitio(tmp: File, destino: File) {
        if (tmp.renameTo(destino)) return
        if (destino.exists()) destino.delete()
        if (!tmp.renameTo(destino)) { tmp.copyTo(destino, overwrite = true); tmp.delete() }
    }

    private fun alCambiado(rel: String) {
        diario.archivo(rel)
        alCambiar(Cambio.ARCHIVOS)
        // **La biblioteca de figuras que llega se vuelve a leer** (25-oct-2026, §8 del manual del PC
        // «sincronizar lo nuevo»): el lienzo abierto enseña al momento la figura guardada en el otro.
        if (esBibliotecaDeFiguras(rel)) com.forge.pixpin.motor.BibliotecaStore.alLlegar()
    }

    /** Si en la carpeta [dir] caben [bytes] más un margen. Sin saberlo (0), se intenta. */
    internal fun cabe(dir: File, bytes: Long): Boolean {
        val libre = espacioLibre?.invoke() ?: runCatching { dir.usableSpace }.getOrDefault(0L)
        return libre == 0L || libre >= bytes + MARGEN_DE_ESPACIO
    }

    /** Para las pruebas: cuánto espacio libre fingir. */
    internal var espacioLibre: (() -> Long)? = null

    /** La última escritura que llegaba no cupo. Lo mira la vuelta para decirlo. */
    @Volatile var faltoEspacio = false

    // ------------------------------------------------------- lo que llega a medias

    @Serializable
    private data class Parcial(val resumen: String, val largo: Long, val cuando: Long = 0)

    private fun parcial(rel: String) = File(filesDir, rel + PARCIAL)
    private fun fichaDeParcial(rel: String) = File(filesDir, rel + PARCIAL + ".json")

    /**
     * **Cuánto hay ya de [rel] en la versión [resumen]** de [largo] bytes, de una vuelta que se cortó
     * a medias (27-oct-2026: se cortaba a mitad del vídeo y la vuelta siguiente empezaba de cero).
     * 0 si no hay nada de esa versión.
     */
    fun yaLlegado(rel: String, resumen: String, largo: Long): Long {
        if (!permitida(rel)) return 0
        val f = parcial(rel)
        val ficha = runCatching { JSON.decodeFromString(Parcial.serializer(), fichaDeParcial(rel).readText()) }.getOrNull()
        if (!f.isFile || ficha == null || ficha.resumen != resumen || ficha.largo != largo || f.length() > largo) return 0
        return f.length()
    }

    /**
     * **Escribe un archivo grande que puede llegar en varias vueltas.** Lo llegado se va guardando
     * en `<rel>.parcial` (con su ficha: qué versión y cuánto mide); si la conexión se corta se
     * queda ahí, y la vuelta siguiente pide solo lo que falta ([yaLlegado]). Al completarse se
     * comprueba el resumen **del archivo entero** contra [resumen] antes de ponerlo en su sitio:
     * dos trozos de versiones distintas no pasan nunca por bueno. [desde] es lo que ya había.
     * Devuelve si quedó escrito; lanza si se cortó la conexión (lo llegado se conserva).
     */
    fun escribirReanudable(rel: String, resumen: String, largo: Long, desde: Long, llenar: (java.io.OutputStream) -> Boolean): Boolean {
        require(permitida(rel) && !esTexto(rel)) { "Ruta no permitida: $rel" }
        val destino = File(filesDir, rel)
        destino.parentFile?.mkdirs()
        val f = parcial(rel)
        val ficha = fichaDeParcial(rel)
        if (desde == 0L || yaLlegado(rel, resumen, largo) != desde) {
            f.delete()
            ficha.writeText(JSON.encodeToString(Parcial.serializer(), Parcial(resumen, largo, System.currentTimeMillis())))
        }
        if (!cabe(destino.parentFile ?: filesDir, largo - f.length())) {
            faltoEspacio = true
            llenar(java.io.OutputStream.nullOutputStream())
            return false
        }
        val fos = java.io.FileOutputStream(f, true)
        val aguanta = SalidaQueAguanta(java.io.BufferedOutputStream(fos, 1 shl 16))
        val entero = try {
            llenar(aguanta)
        } finally {
            // Lo llegado se queda en disco aunque se corte: es lo que la vuelta siguiente no pedirá.
            aguanta.flush()
            runCatching { fos.fd.sync() }
            aguanta.close()
        }
        fun tirar(): Boolean { f.delete(); ficha.delete(); return false }
        if (aguanta.fallo != null) { faltoEspacio = true; return tirar() }
        if (!entero) return tirar()
        if (f.length() != largo || f.inputStream().use { sha256De(it) } != resumen) return tirar()
        ponerEnSuSitio(f, destino)
        ficha.delete()
        alCambiado(rel)
        return true
    }

    /**
     * **Los restos de una vuelta que no acabó** (la aplicación muerta a mitad): los temporales
     * `.sincro` se tiran siempre —con [Protocolo.ocupar] puesto no hay otra vuelta escribiéndolos—
     * y lo llegado a medias ([escribirReanudable]) cuando tiene más de [DIAS_DE_PARCIAL] días.
     * Devuelve cuántos tiró.
     */
    fun limpiarRestos(ahora: Long = System.currentTimeMillis()): Int {
        var n = 0
        for (r in CARPETAS.map { File(filesDir, it) }.filter { it.isDirectory }) r.walkTopDown().filter { it.isFile }.forEach { f ->
            val viejo = ahora - f.lastModified() > DIAS_DE_PARCIAL * 24L * 3600_000
            val tirar = f.name.endsWith(".sincro") || ((f.name.endsWith(PARCIAL) || f.name.endsWith("$PARCIAL.json")) && viejo)
            if (tirar && f.delete()) n++
        }
        for (nombre in listOf("guardados.jsonl.sincro", "proyectos/proyectos.json.sincro")) if (File(filesDir, nombre).delete()) n++
        return n
    }

    /**
     * **Solo se escribe dentro de las carpetas de PixPin**, y nunca los índices: un aparato del
     * grupo con un fallo no puede pisar `guardados.jsonl` a mano ni salirse con un `..`.
     */
    fun permitida(rel: String): Boolean {
        if (rel.isBlank() || rel.startsWith("/") || rel.contains("..") || rel.contains('\\')) return false
        if (rel.endsWith(".tmp") || rel.endsWith(".sincro") || rel.endsWith(PARCIAL) || rel.endsWith("$PARCIAL.json")) return false
        if (rel == "proyectos/proyectos.json") return false
        return CARPETAS.any { rel.startsWith(it) }
    }

    // ------------------------------------------------------------ base y demás

    private fun archivoDeBase(otro: String, chat: String) =
        File(carpeta, "base/${limpio(otro)}/${limpio(chat)}.json")

    fun base(otro: String, chat: String): Base? = runCatching {
        JSON.decodeFromString(Base.serializer(), archivoDeBase(otro, chat).readText())
    }.getOrNull()

    fun guardarBase(otro: String, chat: String, base: Base) {
        val f = archivoDeBase(otro, chat)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(JSON.encodeToString(Base.serializer(), base))
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    // ------------------------------------------------------ bases para fusionar

    private val carpetaDeObjetos get() = File(carpeta, "objetos").apply { mkdirs() }

    /**
     * **El contenido de lo acordado**, por su resumen (15-sep-2026). Fusionar a tres bandas necesita
     * la base entera, no solo su resumen, y mandar solo los cambios también: los dos aparatos la
     * tienen y basta decir qué cambió respecto a ella. Se guarda comprimida y una vez por contenido.
     */
    fun objeto(resumen: String): String? = runCatching {
        val f = File(carpetaDeObjetos, "$resumen.gz")
        if (!f.isFile) null else GZIPInputStream(f.inputStream()).use { it.readBytes().decodeToString() }
    }.getOrNull()

    fun hayObjeto(resumen: String) = File(carpetaDeObjetos, "$resumen.gz").isFile

    /** Guarda [texto] (JSON portátil) como objeto y devuelve su resumen canónico. */
    fun guardarObjeto(texto: String): String {
        val canonico = Canonico.de(texto)
        val resumen = sha256(canonico.toByteArray())
        escribirObjeto(resumen, canonico)
        return resumen
    }

    private fun escribirObjeto(resumen: String, canonico: String) {
        val f = File(carpetaDeObjetos, "$resumen.gz")
        if (f.isFile) return
        val tmp = File(carpetaDeObjetos, "$resumen.gz.tmp")
        GZIPOutputStream(tmp.outputStream()).use { it.write(canonico.toByteArray()) }
        if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }
    }

    /**
     * Guarda el contenido de lo acordado en [base] que siga aquí igual, y tira los objetos que ya no
     * señala ninguna base. Lo llaman los dos lados al cerrar una vuelta.
     */
    fun guardarObjetosDeBase(chat: String, base: Base) {
        runCatching {
            for ((rel, resumen) in base.archivos) {
                if (!esTexto(rel) || hayObjeto(resumen) || !File(filesDir, rel).isFile) continue
                val canonico = Canonico.de(textoDe(rel))
                if (sha256(canonico.toByteArray()) == resumen) escribirObjeto(resumen, canonico)
            }
            val porClave = mensajesPorClave(chat)
            for ((clave, resumen) in base.mensajes) {
                if (resumen == BORRADO || hayObjeto(resumen)) continue
                val m = porClave[clave] ?: continue
                val texto = textoDeBase(m)
                if (sha256(texto.toByteArray()) == resumen) escribirObjeto(resumen, texto)
            }
        }
        recogerObjetos()
    }

    private fun recogerObjetos() = runCatching {
        val vivos = HashSet<String>()
        File(carpeta, "base").walkTopDown().filter { it.isFile && it.name.endsWith(".json") }.forEach { f ->
            runCatching { JSON.decodeFromString(Base.serializer(), f.readText()) }.getOrNull()?.let { b ->
                vivos += b.archivos.values; vivos += b.mensajes.values
            }
        }
        carpetaDeObjetos.listFiles().orEmpty().forEach { f ->
            if (f.name.endsWith(".gz") && f.name.removeSuffix(".gz") !in vivos) f.delete()
        }
    }

    fun elegidos(otro: String): Set<String>? = runCatching {
        File(carpeta, "elegidos/${limpio(otro)}.txt").readLines().filter { it.isNotBlank() }.toSet()
    }.getOrNull()

    /**
     * Apunta los chats elegidos con [otro] y, si se da, **los que se ofrecieron** al elegir
     * ([ofrecidos]): con eso se sabe después qué chats son nuevos. Ver [elegidosAhora].
     */
    fun guardarElegidos(otro: String, chats: Set<String>, ofrecidos: Set<String>? = null) {
        val f = File(carpeta, "elegidos/${limpio(otro)}.txt")
        f.parentFile?.mkdirs()
        f.writeText(chats.joinToString("\n"))
        if (ofrecidos != null) File(carpeta, "elegidos/${limpio(otro)}.ofrecidos").writeText((ofrecidos + chats).joinToString("\n"))
    }

    /** Los chats que se ofrecieron la última vez que se eligió con [otro]; null si no se apuntó (lo de antes). */
    fun ofrecidos(otro: String): Set<String>? = runCatching {
        File(carpeta, "elegidos/${limpio(otro)}.ofrecidos").readLines().filter { it.isNotBlank() }.toSet()
    }.getOrNull()

    /**
     * **Los chats que van con [otro] ahora**, de entre [candidatos]: los elegidos la última vez y,
     * además, **los que nacieron después** (no se ofrecieron entonces). Null si nunca se eligió: van
     * todos.
     *
     * (26-oct-2026) Con la elección guardada, un chat nuevo —el proyecto donde el PC grabó la clase,
     * el chat que se creó en el otro aparato— no estaba en la lista y **no viajaba nunca** en la
     * sincronización automática; en la pantalla salía desmarcado. Al usuario le parecía que «no se
     * sincronizan las grabaciones». Lo que se desmarcó a propósito sigue fuera; lo que nadie llegó a
     * ver, entra. Sin la lista de ofrecidos (elegido con una versión de antes), como antes.
     */
    fun elegidosAhora(otro: String, candidatos: Collection<String>): Set<String>? {
        val elegidos = elegidos(otro) ?: return null
        val vistos = ofrecidos(otro) ?: return elegidos
        return elegidos + candidatos.filter { it !in vistos }
    }

    fun ultimaVez(otro: String): Long = File(carpeta, "elegidos/${limpio(otro)}.cuando").let { f ->
        runCatching { f.readText().trim().toLong() }.getOrDefault(0L)
    }

    fun apuntarVez(otro: String, cuando: Long) {
        val f = File(carpeta, "elegidos/${limpio(otro)}.cuando")
        f.parentFile?.mkdirs()
        f.writeText(cuando.toString())
    }

    // ---------------------------------------------------------------- direcciones

    private val archivoDeDirecciones get() = File(carpeta, "direcciones.txt")

    /**
     * **Dónde se vio por última vez a cada aparato del grupo**: id → (dirección, puerto). Con esto
     * un aparato aparece al momento al abrir Sincronizar, sin esperar a que el anuncio de la red
     * llegue —que en algunas Wi-Fi tarda uno o dos minutos—.
     */
    fun direcciones(): Map<String, Pair<String, Int>> = runCatching {
        archivoDeDirecciones.readLines().mapNotNull { l ->
            val p = l.split('\t')
            if (p.size < 3) null else p[0] to (p[1] to (p[2].toIntOrNull() ?: return@mapNotNull null))
        }.toMap()
    }.getOrDefault(emptyMap())

    fun apuntarDireccion(id: String, host: String, puerto: Int) {
        if (puerto <= 0 || host.isBlank()) return
        synchronized(DIRECCIONES) {
            val todas = direcciones().toMutableMap()
            if (todas[id] == (host to puerto)) return
            todas[id] = host to puerto
            archivoDeDirecciones.writeText(todas.entries.joinToString("") { "${it.key}\t${it.value.first}\t${it.value.second}\n" })
        }
    }

    private val archivoDeCache get() = File(carpeta, "resumenes.txt")

    private fun leerCache(): MutableMap<String, String> = runCatching {
        archivoDeCache.readLines().filter { '\t' in it }.associate { it.substringBefore('\t') to it.substringAfter('\t') }.toMutableMap()
    }.getOrDefault(HashMap())

    private fun guardarCache(m: Map<String, String>) = synchronized(CACHE) {
        val tmp = File(carpeta, "resumenes.txt.tmp")
        tmp.writeText(m.entries.joinToString("") { "${it.key}\t${it.value}\n" })
        if (!tmp.renameTo(archivoDeCache)) { archivoDeCache.delete(); tmp.renameTo(archivoDeCache) }
    }

    fun avisar(c: Cambio) = alCambiar(c)

    /** El código de chat de un mensaje (`47·K7Q2`, o `47a` en lo de antes). Ver [Codigos.deChat]. */
    fun senaDe(m: Mensaje): String? = Codigos.deChat(m)

    companion object {
        const val GENERAL = "general"
        /** Por encima de esto, el resumen de un archivo se recuerda por su tamaño y su fecha. */
        const val UMBRAL_DE_CACHE = 4L * 1024 * 1024
        const val BORRADO = "borrado"
        val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private val MARCAS = Any()
        private val LAPIDAS = Any()
        private val PROYECTOS = Any()
        private val CACHE = Any()
        private val DIRECCIONES = Any()
        /**
         * **Las letras de los planos del usuario** (14-oct-2026): viajan solo entre sus aparatos, por
         * el chat «Asistente»; una persona de un chat compartido no las lee ni las escribe. Ver
         * [com.forge.pixpin.planos.LetrasQueViajan].
         */
        const val CARPETA_DE_LETRAS = "guardados/fuentes-shx/"
        internal val CARPETAS = listOf("guardados/", "pins/", "proyectos/", "tablas/", "croquis3d/", "notas/", "voz/")
        /** Lo que llega a medias de un archivo grande: ver [escribirReanudable]. */
        const val PARCIAL = ".parcial"
        const val DIAS_DE_PARCIAL = 7
        /** Lo que se deja libre siempre al recibir: un teléfono lleno del todo no arranca bien. */
        const val MARGEN_DE_ESPACIO = 16L shl 20

        /**
         * **La biblioteca de figuras** ([com.forge.pixpin.motor.BibliotecaStore]; en el PC,
         * `disco::BIBLIOTECA_DE_FIGURAS`), señalada por el mensaje `auto-figuras` del chat «Asistente».
         * Para el cable sigue siendo un archivo binario (no es [esTexto]); solo cambia que, si cambió en
         * los dos aparatos, se junta figura a figura ([Fusion.biblioteca], 25-oct-2026) en vez de
         * quedarse la tocada más tarde.
         */
        const val BIBLIOTECA_DE_FIGURAS = "pins/draw/figuras.json"

        fun esBibliotecaDeFiguras(rel: String) = rel == BIBLIOTECA_DE_FIGURAS

        fun chatDe(m: Mensaje): String = m.proyecto ?: GENERAL

        private fun limpio(s: String) = s.replace(Regex("[^A-Za-z0-9._-]"), "_")

        fun sha256De(entrada: InputStream): String {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = entrada.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        /**
         * **Qué mensajes se fueron al reescribir el chat**, con su seña, para dejar su marca. Lo
         * llama el almacén de mensajes: es el único sitio por el que se borra.
         */
        fun borradosEntre(antes: List<Mensaje>, despues: List<Mensaje>, miLetra: Char?, ahora: Long): List<Marca> {
            val quedan = despues.mapTo(HashSet()) { it.id }
            return antes.filter { it.id !in quedan }.map { m ->
                val sena = Codigos.deChat(m) ?: miLetra?.let { Sena.de(m.numero, it) }
                Marca(chatDe(m), sena.orEmpty(), ahora, uid = Codigos.unico(m))
            }
        }
    }
}

/**
 * **Una salida que no se cae** (27-oct-2026): escribe en [dentro] hasta el primer fallo (el disco
 * lleno) y desde entonces tira lo que le llega sin lanzar. Así quien recibe sigue leyendo los
 * trozos del cable hasta su cola y la conversación no se descuadra; después mira [fallo].
 */
internal class SalidaQueAguanta(private val dentro: java.io.OutputStream) : java.io.OutputStream() {
    var fallo: java.io.IOException? = null; private set
    override fun write(b: Int) { if (fallo == null) try { dentro.write(b) } catch (e: java.io.IOException) { fallo = e } }
    override fun write(b: ByteArray, off: Int, len: Int) { if (fallo == null) try { dentro.write(b, off, len) } catch (e: java.io.IOException) { fallo = e } }
    override fun flush() { if (fallo == null) try { dentro.flush() } catch (e: java.io.IOException) { fallo = e } }
    override fun close() { try { dentro.close() } catch (e: java.io.IOException) { if (fallo == null) fallo = e } }
}

/**
 * **Las rutas de este aparato, fuera y dentro.** Lo guardado lleva rutas absolutas
 * (`/data/user/0/com.forge.pixpin/files/guardados/…`) y en otro aparato la carpeta puede ser
 * otra —otro usuario del teléfono, la carpeta segura, el escritorio—. Al salir se cambian por
 * `pixpin:files/` y al entrar por la carpeta de quien lo recibe.
 */
class Rutas(filesDir: File) {
    private val prefijos: List<String> = listOfNotNull(
        filesDir.absolutePath.trimEnd('/') + "/",
        runCatching { filesDir.canonicalPath.trimEnd('/') + "/" }.getOrNull()
    ).distinct().sortedByDescending { it.length }
    private val mio = prefijos.first()

    fun aPortatil(texto: String): String {
        var s = texto
        for (p in prefijos) s = s.replace(p, PORTATIL)
        return s
    }

    fun aLocal(texto: String): String = texto.replace(PORTATIL, mio)

    fun relativa(ruta: String): String? = prefijos.firstOrNull { ruta.startsWith(it) }?.let { ruta.removePrefix(it) }

    /** Las rutas de este aparato que aparecen en un texto (un JSON, una nota), relativas. */
    fun enTexto(texto: String): List<String> {
        val salida = ArrayList<String>()
        for (p in prefijos + PORTATIL) {
            var i = texto.indexOf(p)
            while (i >= 0) {
                var j = i + p.length
                while (j < texto.length && texto[j] !in FIN) j++
                if (j > i + p.length) salida += texto.substring(i + p.length, j)
                i = texto.indexOf(p, j)
            }
        }
        return salida
    }

    companion object {
        const val PORTATIL = "pixpin:files/"
        private val FIN = setOf('"', ')', '\\', '\n', '<', '>', '\'')
    }
}

/** JSON con las claves en orden y sin nulos: dos aparatos sacan el mismo resumen del mismo mensaje. */
object Canonico {
    val json = Json { encodeDefaults = true }

    fun de(texto: String): String = runCatching { ordenar(Json.parseToJsonElement(texto)).toString() }.getOrDefault(texto)

    private fun ordenar(e: JsonElement): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.entries.filter { it.value !is JsonNull }.sortedBy { it.key }.associate { it.key to ordenar(it.value) })
        is JsonArray -> JsonArray(e.map(::ordenar))
        else -> e
    }
}
