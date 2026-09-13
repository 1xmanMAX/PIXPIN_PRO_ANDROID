package com.forge.pixpin.sincro

import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * **Quién es este aparato y con quién se sincroniza.**
 *
 * Sin Android: es un archivo JSON en `files/sincro/identidad.json`, y así se prueba en la JVM y
 * se lleva tal cual a la versión de escritorio. Ver `docs/plan-sincronizacion.md`.
 */
@Serializable
data class Aparato(
    /** Al azar, al instalar. Nunca se enseña: sirve para reconocer al aparato aunque cambie de nombre. */
    val id: String,
    /** El que pone el usuario («Tablet de Max»). Es lo que se ve al sincronizar. */
    val nombre: String,
    /** Una letra de [Sena.LETRAS], o nula mientras no esté en un grupo. */
    val letra: String? = null,
    /** Cuándo entró al grupo: si dos acaban con la misma letra, el que llegó después es el que tiene que cambiar. */
    val desde: Long = 0
)

/**
 * **El código fijo del aparato**: cuatro signos sacados de su id, los mismos siempre, esté o no en
 * un grupo. Es lo que se ve junto al nombre en lo que llega por Wi-Fi («Max phone · K7Q2») para saber
 * de qué teléfono vino.
 */
val Aparato.codigo: String
    get() {
        val h = java.security.MessageDigest.getInstance("SHA-256").digest(id.toByteArray())
        return (0 until 4).joinToString("") { Grupo.SIGNOS[(h[it].toInt() and 0xff) % Grupo.SIGNOS.length].toString() }
    }

@Serializable
data class Identidad(
    val yo: Aparato,
    /** El código del grupo tal como se teclea, sin guiones. Nulo si no está en ninguno. */
    val codigo: String? = null,
    /** Los aparatos del grupo que se conocen, este incluido. Viaja en cada saludo y se junta. */
    val miembros: List<Aparato> = emptyList()
) {
    val enGrupo: Boolean get() = codigo != null && yo.letra != null
    val letra: Char? get() = yo.letra?.firstOrNull()
}

/**
 * **El grupo: un código corto que se teclea en cada aparato.**
 *
 * De él salen dos cosas: la **clave** con la que se cifra lo que viaja (que alguien en la misma
 * Wi-Fi no pueda leer los planos) y la **etiqueta** pública con la que un aparato se anuncia en
 * la red, que deja encontrar a los del grupo sin decir el código.
 */
object Grupo {

    /** Sin 0/O ni 1/I/L: se dicta en voz alta y se teclea sin dudar. 31 signos: diez dan casi 50 bits. */
    const val SIGNOS = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"
    const val LARGO = 10

    fun nuevoCodigo(azar: SecureRandom = SecureRandom()): String =
        String(CharArray(LARGO) { SIGNOS[azar.nextInt(SIGNOS.length)] })

    /** Lo tecleado, limpio: mayúsculas y sin guiones ni espacios. */
    fun limpiar(tecleado: String): String = tecleado.uppercase().filter { it in SIGNOS }

    fun valido(codigo: String): Boolean = codigo.length == LARGO && codigo.all { it in SIGNOS }

    /** `K7Q2M-9XMPA`: en dos mitades, que es como se lee y se copia sin perderse. */
    fun legible(codigo: String): String =
        if (codigo.length <= 5) codigo else codigo.take(5) + "-" + codigo.drop(5)

    /**
     * La clave del grupo. Con PBKDF2 y muchas vueltas para que probar códigos a ciegas contra
     * lo que se haya escuchado por la red cueste de verdad.
     */
    fun clave(codigo: String): ByteArray {
        val spec = PBEKeySpec(codigo.toCharArray(), "pixpin-sincro-grupo".toByteArray(), 60_000, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    /** Lo que se anuncia en la red: dieciséis cifras que no dejan sacar el código. */
    fun etiqueta(clave: ByteArray): String = hmac(clave, "etiqueta".toByteArray()).take(8).joinToString("") { "%02x".format(it) }

    fun hmac(clave: ByteArray, vararg partes: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(clave, "HmacSHA256"))
        for (p in partes) mac.update(p)
        return mac.doFinal()
    }

    /**
     * **Los miembros de dos aparatos, juntos.** Por id; de uno repetido gana el nombre del que
     * lo trae de primera mano (el propio aparato habla por sí mismo).
     */
    fun juntar(mios: List<Aparato>, suyos: List<Aparato>, quienHabla: Aparato? = null): List<Aparato> {
        val porId = LinkedHashMap<String, Aparato>()
        for (a in mios) porId[a.id] = a
        for (a in suyos) if (a.id !in porId) porId[a.id] = a
        if (quienHabla != null) porId[quienHabla.id] = quienHabla
        return porId.values.toList()
    }

    /** Letras repetidas entre aparatos distintos: no debería pasar, pero si pasa hay que decirlo. */
    fun choques(miembros: List<Aparato>): Map<String, List<Aparato>> =
        miembros.filter { it.letra != null }.groupBy { it.letra!! }.filterValues { it.size > 1 }
}

/** Dónde se guarda la identidad. */
class IdentidadEnDisco(private val filesDir: File) {

    private val carpeta get() = File(filesDir, "sincro")
    private val archivo get() = File(carpeta, "identidad.json")

    fun leer(nombrePorOmision: String = "Este aparato"): Identidad = synchronized(CERROJO) {
        val clave = archivo.path to archivo.lastModified()
        cache?.takeIf { it.first == clave }?.let { return it.second }
        val leida = runCatching { json.decodeFromString(Identidad.serializer(), archivo.readText()) }.getOrNull()
        if (leida != null) {
            cache = clave to leida
            return leida
        }
        val nueva = Identidad(Aparato(id = UUID.randomUUID().toString(), nombre = nombrePorOmision))
        guardar(nueva)
        nueva
    }

    fun guardar(i: Identidad) = synchronized(CERROJO) {
        carpeta.mkdirs()
        val tmp = File(carpeta, "identidad.json.tmp")
        tmp.writeText(json.encodeToString(Identidad.serializer(), i))
        if (!tmp.renameTo(archivo)) { archivo.delete(); tmp.renameTo(archivo) }
        cache = null
    }

    /** La letra de este aparato si está en un grupo, sin crear nada si aún no hay archivo. */
    fun letraSiHay(): Char? = if (!archivo.exists()) null else leer().takeIf { it.enGrupo }?.letra

    companion object {
        private val CERROJO = Any()
        @Volatile private var cache: Pair<Pair<String, Long>, Identidad>? = null
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}

/** Resumen corto de unos bytes. */
fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
