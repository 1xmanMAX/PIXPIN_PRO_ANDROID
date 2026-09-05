package com.forge.pixpin.motor

import android.content.Context
import android.graphics.Bitmap
import com.forge.pixpin.motormd.MarkdownHtml
import java.io.File

/**
 * **Un proyecto entero como una sola página web.**
 *
 * Un proyecto no es un dibujo: son sus hojas —lienzos, láminas de un lienzo con marcos,
 * notas, páginas de un PDF anotado, croquis del espacio—. Mandarlas de una en una obliga a
 * quien las recibe a ir abriendo archivos y a perder el orden. Aquí van todas en un `.html`
 * que se abre en cualquier navegador, sin conexión, con su menú para pasar de una a otra.
 * Cada clase de hoja se lleva lo que sabe hacer: los dibujos se pasean, se amplían y se
 * anotan; los croquis del espacio se giran y se miden; las notas se leen como texto de
 * verdad, que el navegador reparte al ancho que haya. Ver [ExportarHtml].
 *
 * ## Lo que cuesta
 *
 * El peso no manda —un archivo grande se manda igual—, pero la fluidez sí. Lo que se paga es
 * al **abrir** la página: el navegador tiene que analizar todos los SVG de golpe. Lo que se
 * mira después va suelto, porque **la hoja que no se mira lleva `hidden`** y el navegador no
 * la pinta. Ver [MUCHAS_HOJAS].
 */
object ExportarProyectoWeb {

    /**
     * **Cuántas hojas caben antes de que se note.**
     *
     * Lo medido (3-sep-2026, en este equipo y con el motor de JavaScript de Chrome):
     *
     * - **Lo que se pinta no depende de lo que haya dibujado, sino de la pantalla.** El visor
     *   del espacio salta los puntos que no separan medio píxel del anterior, así que las
     *   órdenes al lienzo las acota el cristal. Y proyectar, que sí depende del croquis, va a
     *   **264 millones de puntos por segundo**: un croquis de un millón de puntos son cuatro
     *   milésimas por fotograma aquí y unas quince en un teléfono de gama media. O sea que un
     *   croquis grande **se pasea suelto**.
     * - **Lo que sí cuesta es abrir.** El croquis se lee de un JSON: 200.000 puntos son 3,8 MB
     *   y **58 ms** de análisis aquí; los dibujos planos son SVG, y el navegador tiene que
     *   construir el árbol de **todas** las páginas al cargar, aunque solo enseñe una.
     * - **Lo que no cuesta nada es tener páginas de más**: la que no se mira lleva `hidden` y
     *   el navegador no la pinta, así que pasar de una a otra es instantáneo.
     *
     * De ahí el número: con hojas corrientes —unos cientos de elementos cada una— cuarenta
     * páginas se abren en torno a un segundo en un teléfono y se usan sin un tirón. Pasado
     * eso, lo que crece es **la espera al abrir**, no la fluidez de lo que ya está abierto.
     * Con dibujos muy cargados —miles de elementos por hoja— el mismo segundo se alcanza
     * antes, así que el aviso mira las dos cosas: cuántas hojas y cuánto ocupa.
     */
    const val MUCHAS_HOJAS = 40

    /**
     * El proyecto entero escrito a `cache/share`, o `null` si no había nada que escribir.
     *
     * [marcadas] son las claves de [HojasDelProyecto.Pagina]; vacío significa todas.
     * [escenaDe] carga la escena de un lienzo por su id. [imagenDeRuta] lee una imagen de
     * disco —**sin esto las fotos de los lienzos se pierden**—: lo que se le pasa es una
     * ruta, y quién tiene esa ruta es la escena que la usa, no quien exporta.
     * [paginaDelPdf] devuelve la página [Int] del PDF del proyecto ya compuesta con lo
     * anotado encima. [croquisComoHoja] convierte un croquis del espacio en página; entra
     * como función porque el motor no puede depender del croquis en el espacio.
     */
    fun aArchivo(
        context: Context,
        proyecto: Proyecto,
        marcadas: Set<String>,
        escenaDe: (String) -> Scene?,
        imagenDeRuta: (String) -> Bitmap? = { null },
        paginaDelPdf: (Int, String?) -> Bitmap? = { _, _ -> null },
        /**
         * La misma página, **rasterizada al detalle**: es la que se ve en la web al ampliar.
         * Ver [DrawSvg.aTexto] y [PdfDoc.ANCHO_PARA_LA_WEB].
         */
        paginaFinaDelPdf: (Int, String?) -> Bitmap? = { _, _ -> null },
        /**
         * **La misma página leída como líneas**, si el PDF es vectorial: es lo que hace que un
         * plano se vea nítido a cualquier aumento en vez de emborronarse. Ver [PlanoWeb].
         */
        planoDelPdf: (Int) -> String? = { null },
        croquisComoHoja: (String) -> ExportarHtml.HojaWeb? = { null }
    ): File? = runCatching {
        val hojas = paginas(
            context, proyecto, marcadas, escenaDe, imagenDeRuta, paginaDelPdf,
            paginaFinaDelPdf, planoDelPdf, croquisComoHoja
        )
        if (hojas.isEmpty()) return null
        val pagina = ExportarHtml.paginas(
            hojas,
            titulo = proyecto.nombre.ifBlank { "Proyecto" },
            nombre = ExportarProyecto.nombreDeArchivo(proyecto.nombre)
        )
        val carpeta = File(context.cacheDir, "share").apply { mkdirs() }
        File(carpeta, ExportarProyecto.nombreDeArchivo(proyecto.nombre) + ".html")
            .also { it.writeText(pagina) }
    }.getOrNull()

    /** Las páginas del documento, en el orden del proyecto y con los croquis al final. */
    fun paginas(
        context: Context,
        proyecto: Proyecto,
        marcadas: Set<String>,
        escenaDe: (String) -> Scene?,
        imagenDeRuta: (String) -> Bitmap? = { null },
        paginaDelPdf: (Int, String?) -> Bitmap? = { _, _ -> null },
        /**
         * La misma página, **rasterizada al detalle**: es la que se ve en la web al ampliar.
         * Ver [DrawSvg.aTexto] y [PdfDoc.ANCHO_PARA_LA_WEB].
         */
        paginaFinaDelPdf: (Int, String?) -> Bitmap? = { _, _ -> null },
        /** La página como geometría, si se pudo leer. Ver [PlanoWeb]. */
        planoDelPdf: (Int) -> String? = { null },
        croquisComoHoja: (String) -> ExportarHtml.HojaWeb? = { null }
    ): List<ExportarHtml.HojaWeb> {
        // **Lo que el dibujante de SVG pide es el id del archivo, no su ruta**, y la ruta la
        // sabe la escena que lo usa: el mapa va por escena, no por proyecto.
        fun proveedorDe(escena: Scene): (String) -> Bitmap? =
            { id -> escena.files[id]?.path?.let(imagenDeRuta) }
        val todas = HojasDelProyecto.paginas(proyecto, escenaDe)
        val elegidas = if (marcadas.isEmpty()) todas else todas.filter { it.clave in marcadas }
        val salida = ArrayList<ExportarHtml.HojaWeb>(elegidas.size + proyecto.croquis.size)
        for (p in elegidas) {
            val hoja = p.hoja
            val nombre = p.nombre.ifBlank { hoja.nombre.ifBlank { "Hoja" } }
            when {
                // **Una nota va como texto, no como dibujo.** Es lo que la deja legible en un
                // teléfono y buscable con la lupa del navegador.
                hoja.nota != null -> {
                    val texto = p.texto ?: hoja.nota
                    if (!texto.isNullOrBlank()) {
                        // Las imágenes de la nota viajan dentro, pequeñas: ver [imagenLigera].
                        val html = MarkdownHtml.deTexto(texto) { ruta ->
                            audioEnDatos(ruta) ?: imagenDeRuta(ruta)?.let { imagenLigera(it) }
                        }
                        salida += ExportarHtml.HojaWeb.Nota(nombre, html, PAPEL)
                    }
                }
                // **Una página del PDF es su papel más lo anotado encima.**
                hoja.pagina != null -> {
                    val papel = paginaDelPdf(hoja.pagina!!, hoja.dibujo)
                    val escena = hoja.dibujo?.let(escenaDe) ?: Scene()
                    // **Si el PDF son líneas, van las líneas**: el papel lo pinta el visor en
                    // su lienzo y no viaja ninguna fotografía. Si no —una página escaneada—,
                    // la página al detalle, como hasta ahora.
                    val plano = planoDelPdf(hoja.pagina!!)
                    val svg = DrawSvg.aTexto(
                        context, escena, proveedorDe(escena), papel,
                        papelFino = if (plano == null) paginaFinaDelPdf(hoja.pagina!!, hoja.dibujo) else null,
                        papelAparte = plano != null
                    )
                    if (svg != null) {
                        salida += ExportarHtml.HojaWeb.Dibujo(nombre, svg, fondoDe(escena), plano, escala = escena.escala)
                    }
                }
                // **Un croquis del espacio va entero, para poder girarlo.** En el proyecto
                // solo hay láminas congeladas de él, y una lámina no es el croquis.
                hoja.croquis != null && hoja.vista == null -> {
                    croquisComoHoja(hoja.croquis!!)?.let { salida += it }
                }
                hoja.dibujo != null -> {
                    val escena = escenaDe(hoja.dibujo!!) ?: continue
                    val marco = p.marco?.let { id -> escena.marcos.firstOrNull { it.id == id } }
                    val svg = DrawSvg.aTexto(
                        context, escena, proveedorDe(escena), soloEstaHoja = marco
                    )
                    if (svg != null) {
                        salida += ExportarHtml.HojaWeb.Dibujo(nombre, svg, fondoDe(escena), escala = escena.escala)
                    }
                }
            }
        }
        return salida
    }

    private fun fondoDe(escena: Scene): String = Svg.hex(parseColor(escena.backgroundColor))

    /**
     * Una imagen de una nota, lista para ir dentro de la página: a lo sumo [LADO_DE_LA_IMAGEN]
     * de lado y en WEBP, que pesa una fracción del PNG y vale con transparencia y sin ella.
     * Una foto de doce megapíxeles no tiene sentido dentro de un HTML que se manda por chat.
     */
    /** Un audio de la nota, entero y en `data:`, si es de los tipos que un navegador toca y no pesa de más. */
    private const val TOPE_DE_AUDIO = 12L * 1024 * 1024
    internal fun audioEnDatos(ruta: String): String? {
        val mime = when (ruta.substringAfterLast('.', "").lowercase()) {
            "m4a", "aac", "mp4" -> "audio/mp4"
            "mp3" -> "audio/mpeg"
            "ogg", "oga", "opus" -> "audio/ogg"
            "wav" -> "audio/wav"
            "flac" -> "audio/flac"
            else -> return null
        }
        val archivo = java.io.File(ruta)
        if (!archivo.exists() || archivo.length() > TOPE_DE_AUDIO) return null
        return runCatching {
            "data:$mime;base64," + android.util.Base64.encodeToString(archivo.readBytes(), android.util.Base64.NO_WRAP)
        }.getOrNull()
    }

    internal fun imagenLigera(bmp: Bitmap): String? = runCatching {
        val mayor = maxOf(bmp.width, bmp.height)
        val ajustada = if (mayor <= LADO_DE_LA_IMAGEN) bmp else {
            val f = LADO_DE_LA_IMAGEN.toDouble() / mayor
            Bitmap.createScaledBitmap(
                bmp, maxOf(1, (bmp.width * f).toInt()), maxOf(1, (bmp.height * f).toInt()), true
            )
        }
        val salida = java.io.ByteArrayOutputStream()
        val formato =
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R)
                Bitmap.CompressFormat.WEBP_LOSSY
            else Bitmap.CompressFormat.WEBP
        ajustada.compress(formato, CALIDAD_DE_LA_IMAGEN, salida)
        "data:image/webp;base64," + android.util.Base64.encodeToString(salida.toByteArray(), android.util.Base64.NO_WRAP)
    }.getOrNull()

    private const val LADO_DE_LA_IMAGEN = 1280
    private const val CALIDAD_DE_LA_IMAGEN = 84

    /** El papel de una nota: blanco, que es como se lee un texto. */
    private const val PAPEL = "#ffffff"
}
