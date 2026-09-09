package com.forge.pixpin.motor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Las miniaturas de un PDF, **hechas una vez y ya**.
 *
 * ## Por qué tardaba
 *
 * La lista de proyectos pedía cada página con [PdfDoc.render], y esa función
 * abre el documento, dibuja **una** página y lo cierra. Abrir un PDF no es
 * gratis: hay que leer su tabla de referencias cruzadas, que en un documento
 * largo son miles de entradas. Con doscientas páginas eso son **doscientas
 * aperturas del mismo archivo**, cada una tirando a la basura el trabajo de la
 * anterior. Y al volver a la lista, otra vez desde cero.
 *
 * ## Las cuatro cosas que se hacen ahora
 *
 * 1. **Se guardan en disco, comprimidas.** Una página a 220 px en WEBP con
 *    pérdida ocupa unos pocos kilobytes: la segunda vez que se abre la lista no
 *    hay PDF que abrir ni página que dibujar, solo un archivo diminuto que
 *    descodificar. Es la diferencia entre segundos y nada.
 * 2. **Y en memoria**, en una caché por tamaño. Volver atrás en la lista no
 *    debería ni tocar el disco.
 * 3. **Por tandas, con el documento abierto una sola vez.** Dibujar veinte
 *    páginas seguidas cuesta una apertura, no veinte.
 * 4. **De una en una.** `PdfRenderer` no admite dos páginas abiertas a la vez,
 *    así que un candado ordena la cola; sin él, dos miniaturas pedidas a la vez
 *    se pisan y una de las dos sale en blanco.
 *
 * 5. **Enteras o nada.** Lo que se guarda aparece con su nombre bueno de una
 *    vez, ya escrito. Media miniatura en disco no se nota al escribirla: se nota
 *    al leerla, y se lee como una página en blanco. Ver [escribirDeGolpe].
 *
 * La clave lleva **la fecha del archivo**: si el PDF cambia, sus miniaturas
 * dejan de valer solas y no hay que acordarse de borrarlas.
 */
object PdfMiniaturas {

    /**
     * Lo que se guarda en memoria, en kilobytes.
     *
     * Un octavo de lo que el sistema da a la aplicación es la proporción de
     * siempre para una caché de imágenes: suficiente para que hojear no toque
     * disco, y lo bastante poco como para no competir con el propio dibujo.
     */
    private val enMemoria = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt().coerceAtLeast(4 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    /** `PdfRenderer` no admite dos páginas a la vez: se atiende de una en una. */
    private val turno = Mutex()

    /** Calidad del WEBP. A 80 no se distingue de la original a este tamaño. */
    private const val CALIDAD = 80

    /**
     * Lo que puede ocupar la caché de disco, en bytes.
     *
     * Sin tope crecía para siempre: hojear tres documentos largos deja miles de
     * archivos que ya no se van a mirar y que nadie borra nunca. Cuando se pasa,
     * se tiran las más viejas —que es lo mismo que decir las que menos falta
     * hacen— hasta bajar holgadamente del límite.
     */
    private const val TOPE_EN_DISCO = 48L * 1024 * 1024

    /**
     * **Medio píxel en vez de uno entero.**
     *
     * Una miniatura es opaca —el fondo se pinta blanco a propósito— así que el
     * canal de transparencia es un cuarto de la memoria tirado. En 565 cada
     * página ocupa **la mitad**, o sea que en la misma caché caben el doble y
     * hojear toca el disco la mitad de veces. A este tamaño no se distingue.
     *
     * Solo al leer de disco: `PdfRenderer` exige 8888 para dibujar, y convertir
     * lo recién dibujado costaría una copia más de la que ahorra.
     *
     * **Una por lectura y no una compartida.** `BitmapFactory.Options` no es un
     * ajuste: el descodificador escribe dentro de él lo que va encontrando
     * (`outWidth`, `outHeight`, `outConfig`…), así que un único objeto usado
     * desde varios hilos de disco a la vez es dos descodificaciones pisándose
     * los datos. Crear uno cuesta nada al lado de leer el archivo.
     */
    private fun comoLeerlas() = BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.RGB_565
    }

    /**
     * La miniatura **solo si ya está en memoria**. No toca disco ni dibuja nada.
     *
     * Es lo que deja enseñar algo al instante mientras se prepara lo bueno: en
     * el carrusel se pinta de golpe la miniatura pequeña que ya estaba hecha y
     * se cambia por la grande cuando llega, en vez de dejar un hueco gris.
     */
    fun enMemoria(pdf: String, pagina: Int, ancho: Int = PdfDoc.THUMB_WIDTH): Bitmap? =
        enMemoria.get(clave(pdf, pagina, ancho))

    private fun carpeta(context: Context): File =
        File(context.cacheDir, "pdfthumbs").apply { mkdirs() }

    /**
     * La clave de una miniatura: qué archivo, de cuándo, qué página y de qué
     * ancho. Con la fecha dentro, un PDF que cambia invalida las suyas solo.
     */
    private fun clave(pdf: String, pagina: Int, ancho: Int): String {
        val f = File(pdf)
        return "${pdf.hashCode()}-${f.lastModified()}-$ancho-$pagina"
    }

    /**
     * La miniatura de una página, de donde antes esté: memoria, disco o el PDF.
     *
     * Devuelve null si la página no existe o el archivo no se puede leer. Hay
     * que llamarla fuera del hilo de la interfaz.
     */
    suspend fun de(
        context: Context,
        pdf: String,
        pagina: Int,
        ancho: Int = PdfDoc.THUMB_WIDTH
    ): Bitmap? {
        val k = clave(pdf, pagina, ancho)
        // La memoria se mira antes de cambiar de hilo: si ya está, saltar al
        // hilo de disco y volver costaría más que devolverla.
        enMemoria.get(k)?.let { return it }

        // **El cambio de hilo se hace aquí dentro, no en quien llama.** Leer un
        // archivo y dibujar una página del PDF son cosas de milisegundos largos;
        // sueltas en el hilo de la interfaz son fotogramas perdidos. Puesto
        // aquí, ningún sitio de la aplicación puede equivocarse.
        return withContext(Dispatchers.IO) {
            val enDisco = File(carpeta(context), k)
            // Un archivo de cero bytes no es una miniatura: es una que se quedó
            // a medias. `length()` responde lo mismo si no existe, así que la
            // misma pregunta sirve para los dos casos. Ver [escribirDeGolpe].
            if (enDisco.length() > 0L) {
                val leida = BitmapFactory.decodeFile(enDisco.absolutePath, comoLeerlas())
                if (leida != null) {
                    // **Lo de memoria manda sobre lo del disco.** Entre la
                    // comprobación de arriba y este punto hay un cambio de hilo,
                    // y en ese hueco la tanda de al lado ha podido dejar hecha
                    // justo esta página sin comprimir. Guardar la del disco
                    // encima sería cambiar la buena por una peor.
                    return@withContext enMemoria.get(k)
                        ?: leida.also { enMemoria.put(k, it) }
                }
                // No se pudo leer. Mientras siga ahí, [preparar] lo cuenta como
                // hecho y esta página no se dibuja nunca más, así que se tira:
                // una caché ilegible tiene que costar una lectura de más, no una
                // página perdida para siempre.
                enDisco.delete()
            }

            turno.withLock {
                // Se vuelve a mirar dentro del candado: mientras se esperaba
                // turno, la tanda de al lado ha podido dibujar justo esta.
                enMemoria.get(k) ?: PdfDoc.render(pdf, pagina, ancho)?.also {
                    enMemoria.put(k, it)
                    guardar(enDisco, it)
                }
            }
        }
    }

    /**
     * Deja hechas las miniaturas de [paginas], **con el documento abierto una
     * sola vez**.
     *
     * Es lo que convierte una lista larga en algo instantáneo: se pide la tanda
     * de lo que se va a ver, se dibuja de un tirón y las miniaturas ya están
     * cuando la interfaz las pregunta. Las que ya estuvieran hechas no se
     * repiten.
     */
    suspend fun preparar(
        context: Context,
        pdf: String,
        paginas: Iterable<Int>,
        ancho: Int = PdfDoc.THUMB_WIDTH
    ) = withContext(Dispatchers.IO) {
        // Igual que en [de]: el hilo se cambia aquí. Esto abría el PDF y
        // dibujaba veinte páginas **en el hilo de la interfaz**, que es lo que
        // dejaba la lista congelada justo al abrir un documento largo.
        val carpeta = carpeta(context)
        val faltan = paginas.filter { p ->
            val k = clave(pdf, p, ancho)
            // **Existir no es estar hecha.** Preguntando por `exists()`, un
            // archivo recién creado y todavía vacío contaba como miniatura
            // terminada y esa página no se volvía a dibujar jamás. `length()`
            // distingue las dos cosas y encima es una sola pregunta al disco.
            enMemoria.get(k) == null && File(carpeta, k).length() == 0L
        }
        if (faltan.isEmpty()) return@withContext

        turno.withLock {
            // `isActive` es la vía por la que se abandona una tanda que ya no
            // interesa: al hojear, cada movimiento pide una nueva y cancela la
            // anterior. Ver el parámetro `sigue` de [PdfDoc.porTandas].
            val contexto = currentCoroutineContext()
            PdfDoc.porTandas(pdf, faltan, ancho, sigue = { contexto.isActive }) { pagina, bitmap ->
                val k = clave(pdf, pagina, ancho)
                enMemoria.put(k, bitmap)
                guardar(File(carpeta, k), bitmap)
            }
        }
        podar(carpeta)
    }

    /** Se poda una vez por sesión: recorrer la carpeta en cada tanda sobra. */
    @Volatile
    private var podado = false

    /**
     * Tira las miniaturas más viejas si la carpeta se pasa de [TOPE_EN_DISCO].
     *
     * Por fecha de uso, que para esto es la de modificación: lo que hace meses
     * que no se abre es exactamente lo que no se va a volver a abrir.
     */
    private fun podar(carpeta: File) {
        if (podado) return
        podado = true
        runCatching {
            val archivos = carpeta.listFiles() ?: return
            var total = archivos.sumOf { it.length() }
            if (total <= TOPE_EN_DISCO) return
            archivos.sortedBy { it.lastModified() }.forEach { f ->
                if (total <= TOPE_EN_DISCO * 3 / 4) return
                total -= f.length()
                f.delete()
            }
        }
    }

    /**
     * Escribe la miniatura en disco, y si no se puede, no pasa nada.
     *
     * Una caché que no se puede escribir —disco lleno, permisos raros— tiene que
     * degradar a «va más lento», nunca a «no funciona». Y por eso se escribe
     * **de golpe**: media miniatura en disco no es ir más lento, es una página
     * en blanco. Ver [escribirDeGolpe].
     */
    private fun guardar(destino: File, bitmap: Bitmap) {
        escribirDeGolpe(destino) {
            @Suppress("DEPRECATION")
            bitmap.compress(Bitmap.CompressFormat.WEBP, CALIDAD, it)
        }
    }

    /** Tira lo guardado. Para cuando se borra un proyecto o se limpia la app. */
    fun limpiar(context: Context) {
        enMemoria.evictAll()
        podado = false
        runCatching { carpeta(context).deleteRecursively() }
    }
}

/**
 * Escribe [destino] **entero o nada**.
 *
 * ## La página que se quedaba en blanco
 *
 * Esta era la causa de que unas páginas del PDF salieran y otras no. Escribir
 * directamente sobre el nombre bueno parece inocente, pero deja un intervalo
 * observable en el que el archivo ya está y el contenido no:
 * `File.outputStream()` lo crea **vacío antes** de comprimir un solo píxel, y
 * `Bitmap.compress` lo va soltando a trozos de dieciséis kilobytes. Todo el que
 * pase por ahí durante ese rato se lo cree, y son dos:
 *
 * - **El que lee.** [PdfMiniaturas.de] lo descodifica, y `BitmapFactory` **no
 *   falla** con una imagen truncada: devuelve las filas que alcanzó a leer y
 *   deja el resto sin pintar. Esa media página —o esa página entera vacía—
 *   acababa metida en la caché de memoria **encima de la buena**, y de ahí no
 *   se movía en toda la sesión por mucho que se subiera y se bajara la lista.
 * - **El que escribe.** [PdfMiniaturas.preparar] daba por hecha toda página
 *   cuyo archivo existiera, así que ni la volvía a dibujar. Y si el proceso se
 *   va durante la compresión —matarlo desde la lista de recientes basta— ese
 *   archivo a medias se queda en el disco **para siempre**: la clave solo cambia
 *   si cambia la fecha del PDF, y el PDF no ha cambiado. Esa página no vuelve a
 *   verse nunca.
 *
 * Eso explica el «unas sí y otras no» sin tener que suponer nada raro en el
 * documento: falla exactamente la página que alguien estaba mirando en el
 * instante en que la tanda la estaba escribiendo, que son unas pocas y distintas
 * en cada arranque. Las demás salen bien.
 *
 * Con un nombre provisional mientras dura la escritura, un lector solo puede
 * encontrarse dos cosas: nada, o la miniatura completa. `renameTo` dentro del
 * mismo directorio es un único paso del sistema de archivos y no tiene estado
 * intermedio que nadie pueda ver.
 *
 * Vive fuera del objeto **a propósito**: así se puede comprobar sin `Bitmap` ni
 * dispositivo, que es donde estaba el fallo.
 *
 * @param contenido lo que escribe; devuelve si pudo.
 * @return si quedó escrito. Falso también cuando [contenido] dice que no
 *   —`Bitmap.compress` responde `false` ante tamaños que WEBP no admite—, y en
 *   ese caso **no queda ningún archivo fingiendo estar hecho**.
 */
/** Cuenta las escrituras para que dos provisionales del mismo instante jamás compartan nombre. */
private val numeroDeEscritura = java.util.concurrent.atomic.AtomicLong()

internal fun escribirDeGolpe(destino: File, contenido: (java.io.OutputStream) -> Boolean): Boolean {
    // El nombre lleva el instante **y una cuenta** dentro para que dos escrituras de la misma
    // página a la vez no se estropeen la una a la otra el archivo provisional: el instante
    // solo podía coincidir y entonces las dos escribían en el mismo provisional, mezcladas.
    val aMedias = File(
        destino.parentFile,
        "${destino.name}.amedias${System.nanoTime()}-${numeroDeEscritura.incrementAndGet()}"
    )
    val pudo = runCatching { aMedias.outputStream().use(contenido) }.getOrDefault(false)
    if (!pudo) {
        aMedias.delete()
        return false
    }
    // Sustituir de una vez y, si el sistema lo permite, atómicamente: quien lea a la vez solo
    // puede encontrarse «nada» o «la miniatura completa», nunca un trozo de cada escritura.
    val movido = runCatching {
        java.nio.file.Files.move(
            aMedias.toPath(), destino.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE
        )
    }.getOrElse {
        runCatching {
            java.nio.file.Files.move(
                aMedias.toPath(), destino.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            )
        }.getOrDefault(null)
    }
    if (movido != null) return true
    aMedias.delete()
    return false
}
