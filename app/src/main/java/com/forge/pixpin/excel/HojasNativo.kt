package com.forge.pixpin.excel

/**
 * **La entrada a las hojas de Excel de `libpixpinoffice.so`**: la lectura del PC
 * (`crates/pixpin-hojas`), en el mismo .so que Word y PowerPoint
 * (`herramientas/pixpin-office-android/src/hojas.rs`).
 *
 * Un libro abierto es un número ([abrir]) que hay que [cerrar] una vez. Todas las llamadas se
 * hacen desde el hilo propio de [LibroExcel] (el .so protege cada libro con un candado y recoge
 * cualquier fallo de la lectura: aquí llega como 0, -1 o null).
 */
internal object HojasNativo {
    /** Si el .so se cargó (no en la JVM de las pruebas, ni en un teléfono que no sea arm64). */
    val disponible: Boolean get() = com.forge.pixpin.office.OfficeNativo.disponible

    /**
     * [regional]: un renglón por campo —idioma (`es`/`en`), separador decimal, de miles, de
     * fechas y la fecha corta en código de Excel—. 0 si no se pudo (el porqué, en [ultimoError]).
     */
    @JvmStatic external fun abrir(ruta: String, regional: String): Long

    @JvmStatic external fun ultimoError(): String?

    /** PXHL. */
    @JvmStatic external fun libro(h: Long): ByteArray?

    /** Abre la hoja [i] y lee hasta la fila [hasta] (la primera pantalla). */
    @JvmStatic external fun abrirHoja(h: Long, i: Int, hasta: Int): Boolean

    /** Lee unas [celdas] más de la hoja: 1 ya entera, 0 falta, -1 no se pudo. */
    @JvmStatic external fun cargar(h: Long, i: Int, celdas: Int): Int

    /** PXHG. */
    @JvmStatic external fun geometria(h: Long, i: Int): ByteArray?

    /** PXHT de las filas [f0]..[f1]; [conocidos]: cuántos estilos de esta hoja se tienen ya. */
    @JvmStatic external fun trozo(h: Long, i: Int, f0: Int, f1: Int, conocidos: Int): ByteArray?

    /** Lo elegido como texto con tabuladores (lo que se ve en cada celda). */
    @JvmStatic external fun copiar(h: Long, i: Int, f0: Int, c0: Int, f1: Int, c1: Int): String?

    /** Los valores de la lista desplegable de la celda, uno por renglón (solo para ver); null si no tiene. */
    @JvmStatic external fun lista(h: Long, i: Int, f: Int, c: Int): String?

    /** Los bytes de una imagen del libro tal como vienen (PNG, JPEG, EMF…). */
    @JvmStatic external fun imagen(h: Long, ruta: String): ByteArray?

    @JvmStatic external fun cerrar(h: Long)
}
