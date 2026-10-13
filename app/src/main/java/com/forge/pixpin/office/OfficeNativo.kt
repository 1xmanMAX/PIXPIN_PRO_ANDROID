package com.forge.pixpin.office

/**
 * **La entrada a `libpixpinoffice.so`**: la maquetación de Word y PowerPoint del PC
 * (`crates/pixpin-office`), compilada para Android en `herramientas/pixpin-office-android/`.
 *
 * Un documento abierto es un número ([abrir]) que hay que [cerrar] una vez. Las llamadas sobre el
 * mismo documento no van a la vez (el .so lo protege con un candado), y todas se hacen desde
 * [DocumentoOffice], en su hilo propio: maquetar es trabajo y un documento raro puede pedir pila.
 * Un fallo dentro de la lectura no tumba la app: el .so lo recoge y aquí llega como 0 o null.
 */
internal object OfficeNativo {
    /** Si el .so se cargó (no lo hace en la JVM de las pruebas, ni en un teléfono que no sea arm64). */
    val disponible: Boolean by lazy { runCatching { System.loadLibrary("pixpinoffice") }.isSuccess }

    /** Dónde están las letras, un renglón por carpeta: `pixpin:<ruta>`, `usuario:<ruta>`, `sistema:<ruta>`. */
    @JvmStatic external fun configurar(carpetas: String)

    /** 0 si no se pudo (el porqué, en [ultimoError]). */
    @JvmStatic external fun abrir(ruta: String): Long

    @JvmStatic external fun ultimoError(): String?

    /** 0 Word, 1 PowerPoint, -1 nada. */
    @JvmStatic external fun tipo(h: Long): Int

    /** La página [n] (desde 0) en PXOF, maquetándola si hace falta; null si no la hay. */
    @JvmStatic external fun pagina(h: Long, n: Int): ByteArray?

    /** Páginas ya maquetadas (Word) o diapositivas. */
    @JvmStatic external fun listas(h: Long): Int

    /** El total si se sabe; si no, el que guardó Word. */
    @JvmStatic external fun total(h: Long): Int

    /** Si ya se sabe cuántas páginas hay. */
    @JvmStatic external fun terminado(h: Long): Boolean

    /** Las notas del orador de la diapositiva [n] (un párrafo por renglón); null si no tiene o no es un PowerPoint. */
    @JvmStatic external fun notas(h: Long, n: Int): String?

    /** Los bytes de una imagen tal como vienen en el documento (PNG, JPEG, EMF…). */
    @JvmStatic external fun imagen(h: Long, id: String): ByteArray?

    @JvmStatic external fun cerrar(h: Long)
}
