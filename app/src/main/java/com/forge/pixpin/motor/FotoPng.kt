package com.forge.pixpin.motor

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * **Una imagen del PDF que no es JPEG, pasada a PNG** (30-sep-2026).
 *
 * [PlanoDePdf] solo sabía pasar tal cual las fotos en JPEG; las demás —las figuras de un artículo,
 * las capturas, casi todo lo que no es una fotografía, que el PDF guarda comprimido con Flate—
 * contaban como «algo que no se entiende», y con una sola la hoja entera se iba como foto. En un
 * artículo de cien hojas eso era media hoja de cada dos: pesado, borroso y sin texto que buscar.
 *
 * Aquí se descomprimen sus píxeles y se escriben como PNG, sin perder nada: lo entienden el
 * navegador (la página web y el SVG del documento) y `BitmapFactory` (el plano en pantalla).
 * Lo raro sigue fuera y la hoja, como antes, va como foto: más de 8 bits, un `/Decode` que
 * invierte los colores, espacios de color que no son gris, RGB, CMYK o una paleta de estos.
 */
internal object FotoPng {

    /** Más píxeles que esto no se pasan: la hoja iría como foto, como antes. */
    private const val TOPE_DE_PIXELES = 16_000_000

    /**
     * El PNG de la imagen [d] cuyos píxeles, ya sin comprimir, son [crudos]; null si no se sabe.
     * [mascara]: es la transparencia (`/SMask`) de otra, que va siempre en gris.
     */
    fun de(archivo: PdfArchivo, d: PdfValor.Dicc, crudos: ByteArray, mascara: Boolean = false): ByteArray? = runCatching {
        val ancho = (archivo.resolver(d.entradas["Width"]) as? PdfValor.Numero)?.valor?.toInt() ?: return null
        val alto = (archivo.resolver(d.entradas["Height"]) as? PdfValor.Numero)?.valor?.toInt() ?: return null
        val bits = (archivo.resolver(d.entradas["BitsPerComponent"]) as? PdfValor.Numero)?.valor?.toInt() ?: 8
        if (ancho <= 0 || alto <= 0 || ancho.toLong() * alto > TOPE_DE_PIXELES) return null
        if (bits !in intArrayOf(1, 2, 4, 8)) return null
        val espacio = if (mascara) Espacio.Gris else espacioDe(archivo, d.entradas["ColorSpace"]) ?: return null
        if (!decodeCorriente(archivo, d.entradas["Decode"], espacio, bits)) return null
        val componentes = espacio.componentes
        val porFila = (ancho * componentes * bits + 7) / 8
        if (crudos.size < porFila * alto) return null
        when (espacio) {
            Espacio.Gris -> png(ancho, alto, bits, 0, null, crudos, porFila)
            Espacio.Rgb -> if (bits == 8) png(ancho, alto, 8, 2, null, crudos, porFila) else null
            Espacio.Cmyk -> if (bits == 8) png(ancho, alto, 8, 2, null, cmykARgb(crudos, ancho * alto), ancho * 3) else null
            is Espacio.Paleta -> png(ancho, alto, bits, 3, espacio.rgb, crudos, porFila)
        }
    }.getOrNull()

    /**
     * Si un JPEG del PDF se puede pasar **tal cual** al navegador. Uno en CMYK no: los navegadores
     * lo pintan con los colores del revés u oscuros (30-sep-2026, figuras de un artículo sobre
     * fondo negro); tampoco uno con un `/Decode` que no sea el de siempre.
     */
    fun jpegTalCual(archivo: PdfArchivo, d: PdfValor.Dicc): Boolean {
        val espacio = espacioDe(archivo, d.entradas["ColorSpace"]) ?: return false
        if (espacio is Espacio.Cmyk || espacio is Espacio.Paleta) return false
        return decodeCorriente(archivo, d.entradas["Decode"], espacio, 8)
    }

    private sealed class Espacio(val componentes: Int) {
        object Gris : Espacio(1)
        object Rgb : Espacio(3)
        object Cmyk : Espacio(4)
        /** Una paleta, ya en RGB: tres bytes por color. */
        class Paleta(val rgb: ByteArray) : Espacio(1)
    }

    private fun espacioDe(archivo: PdfArchivo, v: PdfValor?, fondo: Int = 0): Espacio? {
        if (fondo > 4) return null
        return when (val cs = archivo.resolver(v)) {
            null -> null
            is PdfValor.Nombre -> when (cs.valor) {
                "DeviceGray", "G", "CalGray" -> Espacio.Gris
                "DeviceRGB", "RGB", "CalRGB" -> Espacio.Rgb
                "DeviceCMYK", "CMYK" -> Espacio.Cmyk
                else -> null
            }
            is PdfValor.Lista -> {
                val tipo = (archivo.resolver(cs.valores.firstOrNull()) as? PdfValor.Nombre)?.valor
                when (tipo) {
                    "CalGray" -> Espacio.Gris
                    "CalRGB" -> Espacio.Rgb
                    "ICCBased" -> {
                        val perfil = archivo.resolver(cs.valores.getOrNull(1)) as? PdfValor.Flujo ?: return null
                        when ((archivo.resolver(perfil.dicc.entradas["N"]) as? PdfValor.Numero)?.valor?.toInt()) {
                            1 -> Espacio.Gris
                            3 -> Espacio.Rgb
                            4 -> Espacio.Cmyk
                            else -> null
                        }
                    }
                    "Indexed", "I" -> {
                        val base = espacioDe(archivo, cs.valores.getOrNull(1), fondo + 1) ?: return null
                        if (base is Espacio.Paleta) return null
                        val tope = (archivo.resolver(cs.valores.getOrNull(2)) as? PdfValor.Numero)?.valor?.toInt() ?: return null
                        val tabla = when (val t = archivo.resolver(cs.valores.getOrNull(3))) {
                            is PdfValor.Cadena -> t.bytes
                            is PdfValor.Flujo -> archivo.descomprimir(t)
                            else -> null
                        } ?: return null
                        val colores = (tope + 1).coerceIn(1, 256)
                        if (tabla.size < colores * base.componentes) return null
                        val rgb = when (base) {
                            Espacio.Rgb -> tabla.copyOf(colores * 3)
                            Espacio.Gris -> ByteArray(colores * 3) { tabla[it / 3] }
                            Espacio.Cmyk -> cmykARgb(tabla, colores)
                            else -> return null
                        }
                        Espacio.Paleta(rgb)
                    }
                    else -> null
                }
            }
            else -> null
        }
    }

    /** Sin `/Decode`, o el que no cambia nada. Uno que invierte se deja fuera (la hoja va como foto). */
    private fun decodeCorriente(archivo: PdfArchivo, v: PdfValor?, espacio: Espacio, bits: Int): Boolean {
        val lista = (archivo.resolver(v) as? PdfValor.Lista)?.valores ?: return v == null || archivo.resolver(v) == null
        val n = lista.map { (archivo.resolver(it) as? PdfValor.Numero)?.valor ?: return false }
        val corriente = if (espacio is Espacio.Paleta) listOf(0.0, ((1 shl bits) - 1).toDouble())
        else List(espacio.componentes) { listOf(0.0, 1.0) }.flatten()
        return n.size == corriente.size && n.indices.all { Math.abs(n[it] - corriente[it]) < 1e-6 }
    }

    private fun cmykARgb(cmyk: ByteArray, cuantos: Int): ByteArray {
        val rgb = ByteArray(cuantos * 3)
        for (i in 0 until cuantos) {
            val c = cmyk[i * 4].toInt() and 0xFF
            val m = cmyk[i * 4 + 1].toInt() and 0xFF
            val y = cmyk[i * 4 + 2].toInt() and 0xFF
            val k = cmyk[i * 4 + 3].toInt() and 0xFF
            rgb[i * 3] = ((255 - c) * (255 - k) / 255).toByte()
            rgb[i * 3 + 1] = ((255 - m) * (255 - k) / 255).toByte()
            rgb[i * 3 + 2] = ((255 - y) * (255 - k) / 255).toByte()
        }
        return rgb
    }

    /** Un PNG sin más: cada fila con el filtro «ninguno», que el compresor ya aprieta. */
    private fun png(ancho: Int, alto: Int, bits: Int, tipo: Int, paleta: ByteArray?, filas: ByteArray, porFila: Int): ByteArray {
        val salida = ByteArrayOutputStream(filas.size / 3 + 256)
        salida.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        val cabecera = ByteArrayOutputStream(13).apply {
            entero(ancho); entero(alto); write(bits); write(tipo); write(0); write(0); write(0)
        }.toByteArray()
        trozo(salida, "IHDR", cabecera)
        if (paleta != null) trozo(salida, "PLTE", paleta)
        val conFiltro = ByteArray((porFila + 1) * alto)
        for (f in 0 until alto) System.arraycopy(filas, f * porFila, conFiltro, f * (porFila + 1) + 1, porFila)
        val compresor = Deflater(6)
        compresor.setInput(conFiltro); compresor.finish()
        val comprimido = ByteArrayOutputStream(conFiltro.size / 4 + 64)
        val buf = ByteArray(64 * 1024)
        while (!compresor.finished()) { val n = compresor.deflate(buf); comprimido.write(buf, 0, n) }
        compresor.end()
        trozo(salida, "IDAT", comprimido.toByteArray())
        trozo(salida, "IEND", ByteArray(0))
        return salida.toByteArray()
    }

    private fun ByteArrayOutputStream.entero(v: Int) {
        write(v ushr 24 and 0xFF); write(v ushr 16 and 0xFF); write(v ushr 8 and 0xFF); write(v and 0xFF)
    }

    private fun trozo(salida: ByteArrayOutputStream, tipo: String, datos: ByteArray) {
        salida.entero(datos.size)
        val t = tipo.toByteArray(Charsets.US_ASCII)
        salida.write(t); salida.write(datos)
        val crc = CRC32().apply { update(t); update(datos) }
        salida.entero(crc.value.toInt())
    }
}
