package com.forge.pixpin.sincro

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * **La tubería entre dos aparatos: tramos cifrados con la clave del grupo.**
 *
 * Nada de lo que pasa por la Wi-Fi va en claro. Al abrirse, cada lado manda un número al azar y
 * de los dos y la clave del grupo sale la clave de esta conversación —distinta en cada una, y
 * distinta en cada sentido—. Cada tramo va con AES-GCM, que además de cifrar **firma**: un
 * aparato con otro código no consigue descifrar ni el primer tramo, y eso es la prueba de que
 * son del mismo grupo sin que el código viaje nunca.
 *
 * Sin Android: sockets y `javax.crypto`, lo mismo en el teléfono, en la JVM de las pruebas y en
 * escritorio.
 */
class Canal private constructor(
    entrada: InputStream,
    salida: OutputStream,
    private val claveDeSalida: ByteArray,
    private val claveDeEntrada: ByteArray
) {
    private val din = DataInputStream(BufferedInputStream(entrada, 1 shl 16))
    private val dout = DataOutputStream(BufferedOutputStream(salida, 1 shl 16))
    private val cifrador = Cipher.getInstance("AES/GCM/NoPadding")
    private val descifrador = Cipher.getInstance("AES/GCM/NoPadding")
    private val llaveSalida = SecretKeySpec(claveDeSalida, "AES")
    private val llaveEntrada = SecretKeySpec(claveDeEntrada, "AES")
    private var cuentaSalida = 0L
    private var cuentaEntrada = 0L

    /** Bytes que han pasado por aquí, en claro, para enseñar la velocidad. */
    @Volatile var enviados = 0L; private set
    @Volatile var recibidos = 0L; private set

    class CodigoDistinto : IOException("El otro aparato tiene otro código de grupo")

    fun enviar(tipo: Byte, datos: ByteArray, desde: Int = 0, largo: Int = datos.size) {
        val claro = ByteArray(largo + 1)
        claro[0] = tipo
        System.arraycopy(datos, desde, claro, 1, largo)
        cifrador.init(Cipher.ENCRYPT_MODE, llaveSalida, GCMParameterSpec(128, iv(cuentaSalida++)))
        val cifrado = cifrador.doFinal(claro)
        dout.writeInt(cifrado.size)
        dout.write(cifrado)
        enviados += largo
    }

    fun vaciar() = dout.flush()

    /** El siguiente tramo: su tipo y lo que trae. */
    fun recibir(): Pair<Byte, ByteArray> {
        vaciar()
        val largo = din.readInt()
        if (largo !in 17..TOPE_DE_MENSAJE) throw IOException("Tramo de $largo bytes")
        val cifrado = ByteArray(largo)
        din.readFully(cifrado)
        val claro = try {
            descifrador.init(Cipher.DECRYPT_MODE, llaveEntrada, GCMParameterSpec(128, iv(cuentaEntrada++)))
            descifrador.doFinal(cifrado)
        } catch (e: AEADBadTagException) {
            throw CodigoDistinto()
        }
        recibidos += claro.size - 1
        return claro[0] to claro.copyOfRange(1, claro.size)
    }

    private fun iv(n: Long): ByteArray = ByteBuffer.allocate(12).putInt(0).putLong(n).array()

    companion object {
        /** Lo que cabe en un tramo. Un archivo grande va en muchos. */
        const val TOPE_DE_TRAMO = 1 shl 20
        /** Lo más grande que se acepta de una vez: una lista de mensajes con notas largas dentro. */
        const val TOPE_DE_MENSAJE = 64 shl 20
        private val MAGIA = "PXS1".toByteArray()

        /**
         * Abre el canal. [inicia] es quien llamó: los dos lados tienen que saber cuál es cuál para
         * sacar la misma clave en cada sentido.
         */
        fun abrir(entrada: InputStream, salida: OutputStream, claveDelGrupo: ByteArray, inicia: Boolean): Canal {
            val mio = ByteArray(32).also { SecureRandom().nextBytes(it) }
            salida.write(MAGIA)
            salida.write(mio)
            salida.flush()
            val din = DataInputStream(entrada)
            val magia = ByteArray(4)
            din.readFully(magia)
            if (!magia.contentEquals(MAGIA)) throw IOException("No es PixPin")
            val suyo = ByteArray(32)
            din.readFully(suyo)
            val (delQueInicia, delQueResponde) = if (inicia) mio to suyo else suyo to mio
            val sesion = Grupo.hmac(claveDelGrupo, "sesion".toByteArray(), delQueInicia, delQueResponde)
            val ida = Grupo.hmac(sesion, "ida".toByteArray()).copyOf(32)
            val vuelta = Grupo.hmac(sesion, "vuelta".toByteArray()).copyOf(32)
            return if (inicia) Canal(entrada, salida, ida, vuelta) else Canal(entrada, salida, vuelta, ida)
        }
    }
}
