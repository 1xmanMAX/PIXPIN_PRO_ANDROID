package com.forge.pixpin.jev

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * **Jev de TypeSafe en la app** (28-oct-2026, `apps/pixpin/src/jev.rs` del PC, `24fdfa97`): la clave de
 * Ajustes › Asistente › «TypeSafe (Jev)», el interruptor de cada función y el cliente de cada una:
 *
 * - [Funcion.REUNION]: lo importante de una clase o reunión al acabar de pasarla a texto ([ReunionEnLaApp]).
 * - [Funcion.ANKI]: solo las tarjetas de una clase que valen la pena ([AnkiEnLaApp]).
 * - [Funcion.URGENCIA]: la prioridad de cada tarea pendiente, en Tareas ([UrgenciaEnLaApp]).
 * - [Funcion.MODELO]: el modelo del asistente según lo difícil del pedido ([ModeloEnLaApp]).
 * - [Funcion.TEMAS] y las siete de la reunión ([Funcion.TAREAS_DICHAS]…[Funcion.FECHAS_AL_HORARIO]): lo que
 *   se oye mientras se graba una reunión (29-oct-2026, [com.forge.pixpin.guardados.ReunionConJev]).
 *
 * **Sin clave (o con la función apagada) [para] da null y cada función hace lo de siempre.** La clave
 * es de este teléfono: cifrada con una llave AES-GCM del **Android Keystore** (no exportable) en
 * `noBackupFilesDir/claves/typesafe.clave`, como las de Claude y DeepSeek
 * ([com.forge.pixpin.asistente.ClavesDeLaNube]): nunca en los ajustes, ni en lo que se sincroniza o
 * se exporta, ni en la copia de seguridad, ni en un registro. Los interruptores (`[jev]` del PC) son
 * también de este aparato y no viajan.
 */
object Jev {

    /** Una función que usa Jev, con su interruptor (encendida de fábrica; sin clave no hace nada). */
    enum class Funcion(val id: String) {
        REUNION("reunion"), ANKI("anki"), URGENCIA("urgencia"), MODELO("modelo"),
        /** Los temas de la reunión tratados, mientras se graba (29-oct-2026, `[jev] temas` del PC). */
        TEMAS("temas"),
        /** Lo que se oye en la reunión, frase a frase ([EnLaReunion], `[jev]` del PC). */
        TAREAS_DICHAS("tareas_dichas"), URGENCIA_EN_VIVO("urgencia_en_vivo"), TEMA_OLVIDADO("tema_olvidado"),
        TIEMPO_POR_TEMA("tiempo_por_tema"), DECISIONES("decisiones"), PREGUNTAS_PENDIENTES("preguntas_pendientes"),
        FECHAS_AL_HORARIO("fechas_al_horario")
    }

    /** La página donde se saca la clave. */
    const val PAGINA_DE_CLAVES = "https://console.typesafe.ai"

    private const val PREFS = "jev"
    private const val ALIAS = "pixpin-jev-typesafe-v1"
    private const val TAG_BITS = 128

    private val _version = MutableStateFlow(0)

    /** Sube cuando cambia la clave o un interruptor: la pantalla de Ajustes repinta. */
    val version: StateFlow<Int> get() = _version

    @Volatile private var enMemoria: String? = null
    @Volatile private var leida = false

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun encendida(c: Context, f: Funcion): Boolean = runCatching { prefs(c).getBoolean(f.id, true) }.getOrDefault(true)

    fun encender(c: Context, f: Funcion, si: Boolean) {
        runCatching { prefs(c).edit().putBoolean(f.id, si).apply() }
        _version.value++
    }

    /**
     * **El cliente para esta función**: con la clave guardada y la función encendida. null: lo de
     * siempre. Lee el disco la primera vez: llamarlo fuera del hilo principal.
     */
    fun para(c: Context, f: Funcion): Cliente? {
        if (!encendida(c, f)) return null
        val clave = clave(c) ?: return null
        return Cliente(clave)
    }

    /** Si hay clave guardada (sin descifrarla). */
    fun hayClave(c: Context): Boolean = fichero(c).isFile

    // ---------------------------------------------------------------------------- la clave

    private fun fichero(c: Context) = File(File(c.applicationContext.noBackupFilesDir, "claves"), "typesafe.clave")

    private fun llave(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return g.generateKey()
    }

    /** **La clave**, o null si no hay (o no se puede descifrar: el Keystore se borró). Se descifra una vez. */
    @Synchronized
    fun clave(c: Context): String? {
        if (leida) return enMemoria
        enMemoria = runCatching {
            val b = fichero(c).takeIf { it.isFile }?.readBytes() ?: return@runCatching null
            if (b.size < 3 || b[0].toInt() != 1) return@runCatching null
            val n = b[1].toInt()
            val iv = b.copyOfRange(2, 2 + n)
            val cifrador = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, llave(), GCMParameterSpec(TAG_BITS, iv)) }
            String(cifrador.doFinal(b, 2 + n, b.size - 2 - n), Charsets.UTF_8)
        }.getOrNull()
        leida = true
        return enMemoria
    }

    /** **Guarda** la clave cifrada (escritura atómica). Lanza si no tiene pinta de clave. */
    @Synchronized
    fun guardar(c: Context, clave0: String) {
        val clave = clave0.trim()
        require(com.forge.pixpin.ia.Nube.pareceClave(clave)) { "eso no parece una clave" }
        val cifrador = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, llave()) }
        val iv = cifrador.iv
        val datos = cifrador.doFinal(clave.toByteArray(Charsets.UTF_8))
        val f = fichero(c)
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        tmp.outputStream().use { it.write(byteArrayOf(1, iv.size.toByte())); it.write(iv); it.write(datos) }
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        enMemoria = clave; leida = true
        _version.value++
    }

    /** Borra la clave guardada: todo vuelve a lo de siempre. */
    @Synchronized
    fun borrar(c: Context) {
        fichero(c).delete()
        enMemoria = null; leida = true
        _version.value++
    }

    /**
     * **Prueba la clave** con `GET /v1/models` (no gasta tokens): «funciona (jev-latest)» o el motivo.
     * [escrita] vacía: la guardada. Trabajo de red.
     */
    fun probar(c: Context, escrita: String): String {
        val clave = escrita.trim().ifEmpty { clave(c).orEmpty() }
        if (clave.isEmpty()) return "Escribe la clave primero."
        return runCatching { Cliente(clave).modelos() }.fold(
            { m -> "Funciona (${m.firstOrNull { it == MODELO_JEV } ?: MODELO_JEV})." },
            { e -> "⚠️ " + (e.message ?: "no se pudo") }
        )
    }
}
