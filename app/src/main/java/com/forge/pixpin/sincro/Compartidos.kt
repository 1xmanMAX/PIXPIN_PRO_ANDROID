package com.forge.pixpin.sincro

import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.KeyAgreement
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * # Chats compartidos (13-oct-2026)
 *
 * Lo pidió el usuario: «chats compartidos, a los que se puedan añadir personas y hacer
 * sincronización con ellos también, solo en esos chats».
 *
 * Hasta aquí la sincronización era **entre los aparatos de uno mismo**: el grupo ([Grupo]) comparte
 * un código, y quien lo sabe lo ve todo. Eso no vale para otra persona —un compañero de la carrera
 * con quien se lleva un proyecto—: tiene que ver ese chat y **nada más**.
 *
 * ## Cómo
 *
 * - **Cada aparato tiene un par de claves** ([Llaves], P-256) que nace la primera vez que hace falta
 *   y no sale nunca de `files/sincro/persona.json`. La pública es por lo que se le reconoce.
 * - **Un chat compartido** ([ChatCompartido]) es un chat de proyecto con su **lista de personas**: de
 *   cada aparato que es miembro, su id, el nombre de la persona, su código fijo (`K7Q2`, el mismo
 *   que firma los mensajes que escribe: así se sabe quién escribió cada uno) y su clave pública.
 * - **Dos personas se hablan con sus claves** ([Canal.abrirEntrePersonas]), no con un código de
 *   grupo: el que responde solo acepta a quien está en la lista de algún chat que comparten, y solo
 *   le deja tocar esos chats ([AccesoDePersona]). Lo demás no se ve, ni se puede pedir ni escribir.
 * - **Entrar y salir van firmados.** Cada entrada la firma quien invita con su clave; cada salida, la
 *   propia persona (se fue) o quien creó el chat (la quitó). La lista viaja y se junta ([juntar]) en
 *   todos los aparatos sin servidor, y lo que no lleva una firma buena no se cree: un miembro no
 *   puede echar a otro haciéndose pasar por el creador, ni cambiarle la clave a nadie.
 * - **Para añadir a alguien**, una invitación con un código corto y su QR ([Invitacion]), como el
 *   emparejado del grupo; pero quien entra **no entra en el grupo**: recibe ese chat.
 *
 * Sin Android, para probarlo en la JVM ([ChatsCompartidosTest], `ChatsCompartidosDeVerdadTest`) y
 * llevarlo al PC. La guía para el PC está en `docs/chats-compartidos.md`.
 */
object Compartidos {

    /** Lo que firma quien mete a [p] en [chat]: todo lo que dice de ella, para que no se cambie. */
    fun textoDeEntrada(chat: String, p: Persona): ByteArray =
        "pixpin-chat|entra|$chat|${p.id}|${p.clave}|${p.desde}|${p.invito}|${p.nombre}|${p.codigo}".toByteArray()

    /** Lo que firma quien saca a [p] (ella misma, o el creador). */
    fun textoDeSalida(chat: String, p: Persona): ByteArray =
        "pixpin-chat|sale|$chat|${p.id}|${p.fuera}|${p.quito}".toByteArray()

    /**
     * **Si la entrada de [p] está firmada por quien dice.** El creador se firma a sí mismo; los
     * demás, quien los invitó, que tiene que estar ya en [conocidas].
     */
    fun entradaValida(c: ChatCompartido, p: Persona, conocidas: Map<String, Persona>): Boolean {
        val firmante = when {
            p.invito == p.id -> if (p.id == c.creador) p.clave else return false
            else -> conocidas[p.invito]?.clave ?: return false
        }
        return Llaves.verificar(firmante, textoDeEntrada(c.chat, p), p.firma)
    }

    /** Si la salida de [p] (si la tiene) está firmada por ella misma o por el creador. */
    fun salidaValida(c: ChatCompartido, p: Persona, conocidas: Map<String, Persona>): Boolean {
        if (p.fuera <= 0) return true
        val firma = p.firmaFuera ?: return false
        val firmante = when (p.quito) {
            p.id -> p.clave
            c.creador -> conocidas[c.creador]?.clave ?: return false
            else -> return false
        }
        return Llaves.verificar(firmante, textoDeSalida(c.chat, p), firma)
    }

    /**
     * **Junta la lista de [suyo] con la mía.** Por id de aparato: de la entrada se queda la más
     * reciente bien firmada, y de la salida igual; está dentro quien entró después de salir. Así da
     * lo mismo en qué orden lleguen las cosas y por qué aparato: todos acaban con la misma lista.
     *
     * Lo que no cuadra **se ignora**: una firma mala, alguien de quien no se conoce a quien lo invitó,
     * o una clave distinta para un aparato que ya se conoce (nadie le cambia la clave a otro).
     * El creador y el nombre del chat son los que ya había aquí; si [mio] es nulo (el primero que
     * llega, al aceptar una invitación), los de [suyo].
     */
    fun juntar(mio: ChatCompartido?, suyo: ChatCompartido): ChatCompartido {
        if (mio != null && mio.chat != suyo.chat) return mio
        val base = mio ?: suyo.copy(personas = emptyList())
        val personas = LinkedHashMap<String, Persona>()
        mio?.personas?.forEach { personas[it.id] = it }
        var pendientes = suyo.personas
        // Por vueltas: alguien invitado por otro que viene detrás en la lista se valida en la siguiente.
        while (pendientes.isNotEmpty()) {
            val quedan = ArrayList<Persona>()
            var cambio = false
            for (p in pendientes) {
                val actual = personas[p.id]
                if (actual != null && actual.clave != p.clave) continue
                val entra = entradaValida(base, p, personas)
                if (actual == null && !entra) { quedan += p; continue }
                val sale = salidaValida(base, p, if (actual == null) personas + (p.id to p) else personas)
                var r: Persona? = actual
                if (entra && (r == null || p.desde > r.desde)) {
                    r = (r ?: p.copy(fuera = 0, quito = null, firmaFuera = null))
                        .copy(nombre = p.nombre, codigo = p.codigo, desde = p.desde, invito = p.invito, firma = p.firma)
                }
                if (r != null && sale && p.fuera > r.fuera) r = r.copy(fuera = p.fuera, quito = p.quito, firmaFuera = p.firmaFuera)
                if (r != null && r != actual) { personas[p.id] = r; cambio = true }
            }
            if (!cambio || quedan.size == pendientes.size) break
            pendientes = quedan
        }
        return base.copy(personas = personas.values.toList())
    }

    /** Junta listas enteras, chat por chat. Lo de chats que no tengo solo entra con [admitirNuevos]. */
    fun juntarTodos(mios: List<ChatCompartido>, suyos: List<ChatCompartido>, admitirNuevos: (ChatCompartido) -> Boolean): List<ChatCompartido> {
        val porChat = LinkedHashMap<String, ChatCompartido>()
        mios.forEach { porChat[it.chat] = it }
        for (s in suyos) {
            val m = porChat[s.chat]
            if (m == null && !admitirNuevos(s)) continue
            porChat[s.chat] = juntar(m, s)
        }
        return porChat.values.toList()
    }

    /**
     * **Entre aparatos del grupo** (los de una misma persona): se juntan todas las listas —también
     * las de chats que aquí no había— y, si me llega la ficha del [otro] aparato, lo meto yo en los
     * chats compartidos donde estoy dentro y él aún no consta. Si consta y salió, no: se fue a
     * propósito.
     */
    fun juntarDelGrupo(mios: List<ChatCompartido>, suyos: List<ChatCompartido>, yo: Yo, otro: Persona?, ahora: Long): List<ChatCompartido> {
        val juntos = juntarTodos(mios, suyos) { true }
        if (otro == null || otro.clave.isBlank() || otro.id == yo.id) return juntos
        return juntos.map { c ->
            if (dentro(c, yo.id) && c.personas.none { it.id == otro.id }) runCatching { invitar(c, yo, otro, ahora) }.getOrDefault(c) else c
        }
    }

    // ---------------------------------------------------------------- crear, invitar, quitar

    /** **Compartir un chat**: la lista con una sola persona, quien lo comparte, que es su creador. */
    fun crear(chat: String, nombre: String, yo: Yo, ahora: Long): ChatCompartido {
        val c = ChatCompartido(chat, nombre, yo.id, ahora)
        val p = Persona(yo.id, yo.nombre, yo.codigo, yo.clave, ahora, invito = yo.id, firma = "")
        return c.copy(personas = listOf(p.copy(firma = yo.llaves.firmar(textoDeEntrada(chat, p)))))
    }

    /**
     * **[yo] mete a [nueva] en el chat**, firmando su entrada. Si ya estaba (con su misma clave) y se
     * había ido, vuelve; si está dentro, no cambia nada. Solo invita quien está dentro.
     */
    fun invitar(c: ChatCompartido, yo: Yo, nueva: Persona, ahora: Long): ChatCompartido {
        require(dentro(c, yo.id)) { "Ya no estás en este chat" }
        val antes = c.personas.firstOrNull { it.id == nueva.id }
        require(antes == null || antes.clave == nueva.clave) { "Ese aparato ya está con otra clave" }
        if (antes != null && antes.dentro) return c
        val desde = maxOf(ahora, (antes?.fuera ?: 0L) + 1)
        val p = Persona(nueva.id, nueva.nombre.ifBlank { "Sin nombre" }.take(NOMBRE_MAX), nueva.codigo, nueva.clave, desde,
            invito = yo.id, firma = "", fuera = antes?.fuera ?: 0, quito = antes?.quito, firmaFuera = antes?.firmaFuera)
        val firmada = p.copy(firma = yo.llaves.firmar(textoDeEntrada(c.chat, p)))
        return c.copy(personas = c.personas.filter { it.id != p.id } + firmada)
    }

    /** Si [yo] puede sacar a [id]: a sí mismo siempre (es irse), a otro solo el creador. */
    fun puedeQuitar(c: ChatCompartido, yo: String, id: String): Boolean =
        dentro(c, id) && (yo == id || (yo == c.creador && dentro(c, yo)))

    /** **[yo] saca a [id]** (o se va, si es él mismo), firmándolo. */
    fun quitar(c: ChatCompartido, yo: Yo, id: String, ahora: Long): ChatCompartido {
        require(puedeQuitar(c, yo.id, id)) { "Solo quien creó el chat puede quitar a otros" }
        val p = c.personas.first { it.id == id }
        val fuera = maxOf(ahora, p.desde + 1)
        val sin = p.copy(fuera = fuera, quito = yo.id, firmaFuera = null)
        val firmada = sin.copy(firmaFuera = yo.llaves.firmar(textoDeSalida(c.chat, sin)))
        return c.copy(personas = c.personas.map { if (it.id == id) firmada else it })
    }

    // ------------------------------------------------------------------------ preguntas

    fun dentro(c: ChatCompartido, id: String): Boolean = c.personas.any { it.id == id && it.dentro }

    /** Los chats en los que **los dos** están dentro: los únicos cuyo contenido viaja entre ellos. */
    fun enComun(lista: List<ChatCompartido>, yo: String, otro: String): Set<String> =
        lista.filter { dentro(it, yo) && dentro(it, otro) }.mapTo(LinkedHashSet()) { it.chat }

    /**
     * Los chats en los que los dos **constan**, dentro o no: de esos se junta la lista de personas,
     * para que se entere de que alguien se fue o fue quitado también quien estaba lejos entonces.
     */
    fun conRastro(lista: List<ChatCompartido>, yo: String, otro: String): Set<String> =
        lista.filter { c -> c.personas.any { it.id == yo } && c.personas.any { it.id == otro } }.mapTo(LinkedHashSet()) { it.chat }

    /** La persona que tiene esa clave pública, en cualquiera de los chats donde también estoy yo. */
    fun porClave(lista: List<ChatCompartido>, yo: String, clave: String): Persona? =
        lista.asSequence()
            .filter { c -> c.personas.any { it.id == yo } }
            .flatMap { it.personas.asSequence() }
            .firstOrNull { it.clave == clave && it.id != yo }

    /** **Quién escribió cada mensaje**: del código del aparato (`Mensaje.aparato`) al nombre de la persona. */
    fun autores(c: ChatCompartido): Map<String, String> =
        c.personas.sortedBy { it.desde }.associate { it.codigo to it.nombre }

    /** Las personas (no los aparatos) que están dentro, cada una una vez. */
    fun nombresDentro(c: ChatCompartido): List<String> =
        c.personas.filter { it.dentro }.map { it.nombre }.distinct()

    const val NOMBRE_MAX = 40
}

/** Un aparato de una persona en un chat compartido. Ver [Compartidos]. */
@Serializable
data class Persona(
    /** El id del aparato ([Aparato.id]). */
    val id: String,
    /** El nombre de la persona: «Ana». Lo pone ella. */
    val nombre: String,
    /** El código fijo de su aparato ([Aparato.codigo]): el que llevan los mensajes que escribe ahí. */
    val codigo: String,
    /** Su clave pública, X.509 en base64. */
    val clave: String,
    /** Cuándo entró (la última vez). */
    val desde: Long,
    /** El id de quien la metió; ella misma si creó el chat. */
    val invito: String,
    /** La firma de [invito] sobre la entrada ([Compartidos.textoDeEntrada]). */
    val firma: String,
    /** Cuándo salió o la quitaron, o 0. Está dentro si entró después de salir. */
    val fuera: Long = 0,
    /** Quién la sacó: ella misma, o el creador. */
    val quito: String? = null,
    /** La firma de [quito] sobre la salida ([Compartidos.textoDeSalida]). */
    val firmaFuera: String? = null
) {
    val dentro: Boolean get() = fuera < desde
}

/** **Un chat compartido**: qué chat (el id del proyecto), cómo se llama, quién lo creó y quién está. */
@Serializable
data class ChatCompartido(
    val chat: String,
    val nombre: String,
    val creador: String,
    val creado: Long,
    val personas: List<Persona> = emptyList()
)

/** **Este aparato como persona**: lo que hace falta para firmar y para presentarse. */
class Yo(val id: String, val nombre: String, val codigo: String, val llaves: Llaves) {
    val clave: String get() = Llaves.b64(llaves.publica)
    /** La ficha sin firmar que se le da a quien invita. */
    fun ficha(ahora: Long = 0): Persona = Persona(id, nombre, codigo, clave, ahora, invito = "", firma = "")
}

/**
 * **El par de claves de este aparato** (P-256). La privada no sale de aquí; con ella se firma
 * ([firmar]) y se saca el secreto de cada conversación con otra persona ([acordar]).
 */
class Llaves(val publica: ByteArray, private val privada: PrivateKey) {

    /** El secreto compartido con quien tiene la privada de [suya] (ECDH). */
    fun acordar(suya: ByteArray): ByteArray {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(privada)
        ka.doPhase(KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(suya)), true)
        return ka.generateSecret()
    }

    fun firmar(datos: ByteArray): String {
        val s = Signature.getInstance("SHA256withECDSA")
        s.initSign(privada)
        s.update(datos)
        return b64(s.sign())
    }

    /** Para guardarla en `persona.json`. */
    internal fun privadaB64(): String = b64(privada.encoded)

    companion object {
        fun nuevas(azar: SecureRandom = SecureRandom()): Llaves {
            val g = KeyPairGenerator.getInstance("EC")
            g.initialize(ECGenParameterSpec("secp256r1"), azar)
            val par = g.generateKeyPair()
            return Llaves(par.public.encoded, par.private)
        }

        fun de(publica: String, privada: String): Llaves =
            Llaves(desdeB64(publica), KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(desdeB64(privada))))

        /** Si [firma] es de quien tiene la privada de [clave] (base64) sobre [datos]. Nunca lanza. */
        fun verificar(clave: String, datos: ByteArray, firma: String): Boolean = runCatching {
            val s = Signature.getInstance("SHA256withECDSA")
            s.initVerify(KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(desdeB64(clave))))
            s.update(datos)
            s.verify(desdeB64(firma))
        }.getOrDefault(false)

        fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
        fun desdeB64(s: String): ByteArray = Base64.getDecoder().decode(s)
    }
}

/**
 * **Lo guardado de los chats compartidos**: la lista en `files/sincro/compartidos.json` y las claves
 * y el nombre de este aparato en `files/sincro/persona.json`.
 */
class CompartidosEnDisco(private val filesDir: File) {

    @Serializable
    private data class MiFicha(val publica: String, val privada: String, val nombre: String? = null)

    private val carpeta get() = File(filesDir, "sincro").apply { mkdirs() }
    private val archivo get() = File(carpeta, "compartidos.json")
    private val archivoDePersona get() = File(carpeta, "persona.json")

    fun leer(): List<ChatCompartido> = synchronized(CERROJO) {
        if (!archivo.exists()) return emptyList()
        runCatching { JSON.decodeFromString(LISTA, archivo.readText()) }.getOrDefault(emptyList())
    }

    fun de(chat: String): ChatCompartido? = leer().firstOrNull { it.chat == chat }

    fun guardar(lista: List<ChatCompartido>) = synchronized(CERROJO) {
        val tmp = File(carpeta, "compartidos.json.tmp")
        tmp.writeText(JSON.encodeToString(LISTA, lista))
        if (!tmp.renameTo(archivo)) { archivo.delete(); tmp.renameTo(archivo) }
        version.value = version.value + 1
    }

    /** Lee, cambia y guarda de una vez, sin que otro hilo escriba en medio. Devuelve lo guardado. */
    fun cambiar(f: (List<ChatCompartido>) -> List<ChatCompartido>): List<ChatCompartido> = synchronized(CERROJO) {
        val antes = leer()
        val despues = f(antes)
        if (despues != antes) guardar(despues)
        despues
    }

    /** Pone (o sustituye) la lista de un chat. */
    fun poner(c: ChatCompartido) { cambiar { l -> l.filter { it.chat != c.chat } + c } }

    /** Si este aparato está dentro de algún chat compartido: entonces hay que escuchar en la Wi-Fi. */
    fun hayAlguno(yo: String): Boolean = leer().any { Compartidos.dentro(it, yo) }

    // ------------------------------------------------------------------ yo, como persona

    private fun ficha(): MiFicha = synchronized(CERROJO) {
        runCatching { JSON.decodeFromString(MiFicha.serializer(), archivoDePersona.readText()) }.getOrNull()?.let { return it }
        val l = Llaves.nuevas()
        val nueva = MiFicha(Llaves.b64(l.publica), l.privadaB64())
        guardarFicha(nueva)
        nueva
    }

    private fun guardarFicha(f: MiFicha) {
        val tmp = File(carpeta, "persona.json.tmp")
        tmp.writeText(JSON.encodeToString(MiFicha.serializer(), f))
        if (!tmp.renameTo(archivoDePersona)) { archivoDePersona.delete(); tmp.renameTo(archivoDePersona) }
    }

    fun llaves(): Llaves = ficha().let { Llaves.de(it.publica, it.privada) }

    /** El nombre con el que me ven en los chats compartidos; si no lo puse, el del aparato. */
    fun nombre(): String? = ficha().nombre

    fun ponerNombre(nombre: String) = synchronized(CERROJO) {
        guardarFicha(ficha().copy(nombre = nombre.trim().take(Compartidos.NOMBRE_MAX).ifBlank { null }))
        version.value = version.value + 1
    }

    /** Yo, para firmar y presentarme: el id y el código del aparato, mi nombre y mis claves. */
    fun yo(identidad: Identidad): Yo =
        Yo(identidad.yo.id, nombre() ?: identidad.yo.nombre, identidad.yo.codigo, llaves())

    companion object {
        private val CERROJO = Any()
        val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val LISTA = ListSerializer(ChatCompartido.serializer())
        /** Sube cada vez que se guarda algo: las pantallas lo miran para releer. */
        val version = MutableStateFlow(0)
    }
}

/**
 * **Lo que una persona puede tocar en una conversación** con este aparato: los chats en los que
 * estamos dentro los dos, y la valla de archivos de todos los demás ([Disco.prohibidosPara]). Se
 * vuelve a mirar a cada pregunta: si a mitad de conversación la lista cambia (llegó que se fue),
 * deja de valer al momento.
 */
class AccesoDePersona(private val disco: Disco, private val compartidos: CompartidosEnDisco, val yo: String, val otro: String) {

    fun chats(): Set<String> = Compartidos.enComun(compartidos.leer(), yo, otro)

    private var prohibidosDe: Pair<Set<String>, Set<String>>? = null

    /** Los archivos de los chats que no compartimos, calculados una vez por conversación (y por lista). */
    @Synchronized
    fun prohibidos(): Set<String> {
        val c = chats()
        prohibidosDe?.takeIf { it.first == c }?.let { return it.second }
        val p = disco.prohibidosPara(c)
        prohibidosDe = c to p
        return p
    }

    fun puede(chat: String?): Boolean = chat != null && chat in chats()

    /** Si un archivo se puede leer: es de ese chat y de ninguno más. */
    fun puedeLeer(chat: String, rel: String, deEsteChat: Set<String>): Boolean =
        puede(chat) && disco.permitida(rel) && rel in deEsteChat && rel !in prohibidos()

    /**
     * Si un archivo se puede escribir: no es de otro chat y, si ya existe, es de este. Lo nuevo (un
     * adjunto que acaba de llegar con su mensaje) sí.
     */
    fun puedeEscribir(chat: String, rel: String): Boolean =
        puede(chat) && disco.permitida(rel) && rel !in prohibidos() &&
            (!disco.archivo(rel).exists() || rel in disco.alcance(chat))
}
