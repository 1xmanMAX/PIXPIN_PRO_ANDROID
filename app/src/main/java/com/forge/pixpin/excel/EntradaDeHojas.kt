package com.forge.pixpin.excel

/**
 * **Por dónde entra cada hoja de cálculo al visor** (25-oct-2026, el usuario: «quita la visualización
 * de Excel antigua y todo lo incorporas en la nueva; de momento quita la edición de Excel y solo
 * ponlo en visualización normal»).
 *
 * Desde hoy **todo** se ve en [ExcelActivity], solo para mirar y anotar: los libros, los `.xls`, los
 * `.ods`, los `.csv`, las tablas del chat y las de los proyectos. La vista de Tablas
 * ([com.forge.pixpin.tabla.TablaActivity], la que edita) queda **desactivada, no quitada**: nadie la
 * abre ([ExcelActivity.EDITAR_EN_TABLAS]). Aquí solo lo que se decide, sin nada de Android, para
 * probarlo en la JVM.
 */
object EntradaDeHojas {

    /** Cómo se ve un archivo de hoja de cálculo. */
    enum class ComoSeVe {
        /** El archivo tal cual, con la lectura del PC (`libpixpinoffice.so`): lo más fiel. */
        ARCHIVO,
        /** Sus tablas de PixPin pasadas al visor ([TablasEnElVisor]). */
        TABLAS
    }

    /** Todo lo que el visor sabe enseñar: lo que lee el .so y lo que lee [com.forge.pixpin.motor.ImportarHojas]. */
    private val EXTENSIONES = setOf("xlsx", "xlsm", "xltx", "xltm", "xls", "xlt", "ods", "csv", "tsv")

    fun extension(nombre: String?): String = nombre?.substringAfterLast('.', "")?.lowercase().orEmpty()

    /** Si [nombre] o [ruta] es una hoja de cálculo que va al visor (y no a otra aplicación). */
    fun esHojaDeCalculo(nombre: String?, ruta: String?): Boolean =
        extension(nombre) in EXTENSIONES || extension(ruta) in EXTENSIONES

    /**
     * Un archivo de hoja de cálculo: el archivo con el .so si [seAbreNativo] y nadie editó sus hojas;
     * si no, sus tablas. Como `hoja_pin::Editable::libro_para_ver` del PC: **lo editado no se esconde**.
     *
     * - [hojasEditadas]: alguna hoja del libro es un mensaje `tabla` editado (aquí o en el PC) después
     *   de llegar ([editada]).
     * - [hayMensajesDeHojas]: el libro ya tiene sus hojas como mensajes (editadas o no).
     * - [hayTablasViejas]: hojas de antes del 13-oct-2026 editadas aquí, solo en `tablas/libro-….json`.
     */
    fun comoSeVe(seAbreNativo: Boolean, hojasEditadas: Boolean, hayMensajesDeHojas: Boolean, hayTablasViejas: Boolean): ComoSeVe =
        if (seAbreNativo && !hojasEditadas && (hayMensajesDeHojas || !hayTablasViejas)) ComoSeVe.ARCHIVO else ComoSeVe.TABLAS

    /**
     * Si la tabla de un mensaje se editó después de nacer: su `tocado` va más de dos segundos detrás
     * de la hora del mensaje (`hoja_pin::libro::editada` del PC; los relojes no van iguales).
     */
    fun editada(tocado: Long, cuando: Long): Boolean = tocado > cuando + 2_000

    /** A dónde va lo que se toca en el chat o en un proyecto. */
    sealed interface Destino {
        /** Un archivo, al visor ([ExcelActivity.abrir]); él decide [ComoSeVe]. */
        data object Archivo : Destino
        /** Unas tablas por su id, al visor ([ExcelActivity.abrirTablas]), con la [activa] delante. */
        data class Tablas(val ids: List<String>, val activa: String) : Destino
        /** No es una hoja de cálculo: lo de siempre. */
        data object OtraCosa : Destino
    }

    /**
     * **Lo que se toca y a dónde va.** [tabla]: el id de la tabla si es una (`mini-<mensaje>`,
     * `libro-<mensaje>-<hoja>`, `t-…` de un proyecto); [hermanas]: las demás hojas de su libro, en
     * orden (pestañas). [nombre], [ruta]: los de un archivo. Nunca la vista de Tablas.
     */
    fun destino(tabla: String?, hermanas: List<String> = emptyList(), nombre: String? = null, ruta: String? = null): Destino = when {
        tabla != null -> Destino.Tablas(hermanas.ifEmpty { listOf(tabla) }.let { if (tabla in it) it else listOf(tabla) }, tabla)
        ruta != null && esHojaDeCalculo(nombre, ruta) -> Destino.Archivo
        else -> Destino.OtraCosa
    }
}
