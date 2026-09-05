package com.forge.pixpin.motor

import java.io.File

/**
 * **Las hojitas de un plano, guardadas en el teléfono como archivos normales.**
 *
 * Sacar un plano del PDF es lo único caro de todo esto, y el PDF no cambia: hacerlo otra vez
 * cada vez que se abre el dibujo sería absurdo. Así que cada cuadro, ya rasterizado y
 * comprimido ([MosaicoDePdf.CALIDAD_DEL_CUADRO]), se escribe **en el almacenamiento**, y
 * volver a abrir ese plano —hoy, o la semana que viene— es leerlos: el PDF no se toca.
 *
 * Lo pidió el usuario así (4-sep-2026): «una vez extraído y guardado, que se quede guardado en
 * memoria de almacenamiento como un archivo normal».
 *
 * ## Cómo se ordena
 *
 * Una carpeta por plano —[carpetaDe]— y dentro un archivo por cuadro, `columna_fila.webp`. La
 * carpeta lleva en el nombre **de qué archivo, qué página y a qué resolución** son esos
 * cuadros, y también **cuándo se modificó el PDF y cuánto ocupa**: si el archivo cambia, la
 * carpeta que le toca es otra y los cuadros viejos no se cuelan.
 *
 * ## Qué pasa cuando se llena
 *
 * Hay un techo ([TECHO]) y, al pasarlo, se borran las carpetas de los planos **que hace más
 * que no se abren** ([limpiar]), nunca la del que se está mirando. Borrar no pierde nada
 * irrecuperable: volver a ese plano lo vuelve a extraer.
 *
 * Todo esto son archivos y cuentas, no toca Android: se comprueba sin dispositivo.
 */
object CuadrosEnDisco {

    /** Dónde van todos los planos, dentro de la carpeta que le den. */
    const val CARPETA = "planos"

    /**
     * **Cuánto se le deja ocupar a todos los planos juntos.**
     *
     * Medio giga: un A0 entero comprimido anda por los treinta megas, así que caben más de
     * diez planos grandes. Va en la carpeta de caché, así que si al teléfono le falta sitio
     * Android puede llevárselo por delante sin romper nada —volvería a extraerse—.
     */
    const val TECHO: Long = 512L * 1024 * 1024

    /**
     * **La carpeta de este plano.** El nombre sale del archivo, la página y la resolución, y
     * de lo que mide y cuándo se tocó el PDF: dos planos distintos nunca caen en la misma, y
     * el mismo plano cae siempre en la suya.
     */
    fun carpetaDe(base: File, pdf: File, pagina: Int, fineza: Double): File {
        val seña = buildString {
            append(pdf.absolutePath).append('|')
            append(pdf.length()).append('|')
            append(pdf.lastModified()).append('|')
            append(pagina).append('|')
            append(Math.round(fineza * 1000))
        }
        val nombre = "%08x%08x".format(seña.hashCode(), seña.length * 31 + pagina)
        return File(File(base, CARPETA), nombre)
    }

    /** El archivo de un cuadro: `columna_fila.webp`. */
    fun nombre(c: MosaicoDePdf.Cuadro): String = "${c.columna}_${c.fila}.webp"

    /** Y al revés, para saber qué hay ya hecho leyendo la carpeta. */
    fun cuadroDe(nombre: String): MosaicoDePdf.Cuadro? {
        if (!nombre.endsWith(".webp")) return null
        val partes = nombre.removeSuffix(".webp").split('_')
        if (partes.size != 2) return null
        val columna = partes[0].toIntOrNull() ?: return null
        val fila = partes[1].toIntOrNull() ?: return null
        return MosaicoDePdf.Cuadro(columna, fila)
    }

    /** **Qué cuadros de este plano ya están hechos.** Se lee una vez, al abrir. */
    fun losQueHay(carpeta: File): MutableSet<MosaicoDePdf.Cuadro> {
        val salida = HashSet<MosaicoDePdf.Cuadro>()
        val hay = runCatching { carpeta.listFiles() }.getOrNull() ?: return salida
        for (f in hay) {
            if (!f.isFile || f.length() <= 0) continue
            cuadroDe(f.name)?.let { salida += it }
        }
        return salida
    }

    /**
     * Guarda un cuadro. **Se escribe aparte y se mueve al sitio de un tirón**: si la app se
     * muere a medias, lo que queda es un archivo suelto que se descarta, no un cuadro a medio
     * escribir que se leería como una mancha.
     */
    fun escribir(carpeta: File, c: MosaicoDePdf.Cuadro, bytes: ByteArray): Boolean =
        runCatching {
            if (!carpeta.exists() && !carpeta.mkdirs()) return false
            val destino = File(carpeta, nombre(c))
            val aMedias = File(carpeta, nombre(c) + ".medias")
            aMedias.writeBytes(bytes)
            if (!aMedias.renameTo(destino)) {
                aMedias.delete()
                return false
            }
            true
        }.getOrDefault(false)

    /** Lee un cuadro, o null si no está o no se deja. */
    fun leer(carpeta: File, c: MosaicoDePdf.Cuadro): ByteArray? = runCatching {
        val f = File(carpeta, nombre(c))
        if (!f.isFile || f.length() <= 0) null else f.readBytes()
    }.getOrNull()

    /** Lo que ocupa una carpeta de plano, en bytes. */
    fun peso(carpeta: File): Long =
        runCatching { carpeta.listFiles()?.sumOf { it.length() } ?: 0L }.getOrDefault(0L)

    /**
     * **Borra planos viejos hasta bajar del techo**, sin tocar [salvo] —el que se está
     * mirando—. El orden es por cuándo se usó cada uno: el primero en irse es el que hace más
     * que no se abre.
     */
    fun limpiar(base: File, salvo: File? = null, techo: Long = TECHO) {
        val raiz = File(base, CARPETA)
        val planos = runCatching { raiz.listFiles() }.getOrNull()?.filter { it.isDirectory }
            ?: return
        var total = planos.sumOf { peso(it) }
        if (total <= techo) return
        for (plano in planos.sortedBy { it.lastModified() }) {
            if (total <= techo) break
            if (salvo != null && plano.absolutePath == salvo.absolutePath) continue
            total -= peso(plano)
            plano.deleteRecursively()
        }
    }

    /** Que este plano cuente como usado ahora: es lo que decide a quién se borra antes. */
    fun tocar(carpeta: File) {
        runCatching { carpeta.setLastModified(System.currentTimeMillis()) }
    }
}
