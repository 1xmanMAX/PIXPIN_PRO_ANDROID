package com.forge.pixpin.asistente

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.forge.pixpin.ia.Nube
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * **Las claves de Claude y DeepSeek, cifradas y solo en este teléfono** (§1.5 del manual del PC,
 * `nube/claves.rs`): una clave AES-GCM del **Android Keystore** (no exportable, no sale del
 * aparato) cifra la de la API, que se guarda en `noBackupFilesDir/claves/<proveedor>.clave`.
 *
 * - Nunca en DataStore en claro, ni en `files/pins`, ni en un `.pixpin`, ni en la sincronización,
 *   ni en el registro de errores: aquí no se escribe ni un log con ella, y para enseñarla se usa
 *   [Nube.enmascarar].
 * - Fuera de la copia de seguridad: lo de `noBackupFilesDir` no entra nunca en la copia de Android
 *   ni en el traspaso a otro teléfono (el manifiesto tiene `allowBackup`, pero esa carpeta queda
 *   fuera por diseño del sistema), y la clave del Keystore no se puede exportar.
 * - El usuario la pega en cada aparato: no hay forma de pasarla de uno a otro, a propósito.
 */
object ClavesDeLaNube {
    private const val ALIAS = "pixpin-asistente-nube-v1"
    private const val TAG_BITS = 128

    private fun carpeta(c: Context) = File(c.noBackupFilesDir, "claves")
    private fun fichero(c: Context, p: Nube.Proveedor) = File(carpeta(c), "${p.id}.clave")

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

    /** **Guarda** la clave cifrada (escritura atómica). Lanza si no tiene pinta de clave. */
    @Synchronized
    fun guardar(c: Context, p: Nube.Proveedor, clave: String) {
        val limpia = clave.trim()
        require(Nube.pareceClave(limpia)) { "eso no parece una clave" }
        val cifrador = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, llave()) }
        val iv = cifrador.iv
        val datos = cifrador.doFinal(limpia.toByteArray(Charsets.UTF_8))
        val f = fichero(c, p)
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        tmp.outputStream().use { it.write(byteArrayOf(1, iv.size.toByte())); it.write(iv); it.write(datos) }
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    /** **La clave**, o null si no hay (o no se puede descifrar: el Keystore se borró). */
    @Synchronized
    fun leer(c: Context, p: Nube.Proveedor): String? = runCatching {
        val b = fichero(c, p).takeIf { it.isFile }?.readBytes() ?: return null
        if (b.size < 3 || b[0].toInt() != 1) return null
        val n = b[1].toInt()
        val iv = b.copyOfRange(2, 2 + n)
        val cifrador = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, llave(), GCMParameterSpec(TAG_BITS, iv)) }
        String(cifrador.doFinal(b, 2 + n, b.size - 2 - n), Charsets.UTF_8)
    }.getOrNull()

    fun hay(c: Context, p: Nube.Proveedor) = fichero(c, p).isFile

    @Synchronized
    fun borrar(c: Context, p: Nube.Proveedor) {
        fichero(c, p).delete()
    }
}
