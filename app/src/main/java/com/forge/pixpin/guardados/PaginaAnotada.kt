package com.forge.pixpin.guardados

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.LruCache
import com.forge.pixpin.motor.ExcalidrawStore
import com.forge.pixpin.motor.PdfDoc
import com.forge.pixpin.motor.PdfMiniaturas
import com.forge.pixpin.motor.Renderer
import com.forge.pixpin.motor.Viewport
import com.forge.pixpin.pin.ImageStore

/**
 * Las hojas ya compuestas, para no rehacerlas al volver a pasar por delante.
 *
 * Sin esto, cada vez que una fila entraba a la vista se abría el PDF entero, se pintaba
 * la página, se descomprimía la escena y se copiaba el bitmap: unos cinco megas de
 * memoria y bastantes milisegundos **por reaparición**, con la lista moviéndose. La
 * caché se dimensiona con el montón disponible, como la de las miniaturas del proyecto.
 */
private val compuestas = object : LruCache<String, Bitmap>(
    ((Runtime.getRuntime().maxMemory() / 1024) / 12).toInt().coerceAtLeast(4 * 1024)
) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
}

/**
 * Qué hoja es esta: qué PDF, qué página, de qué ancho y **con el dibujo de cuándo**.
 *
 * La fecha del dibujo dentro de la clave es lo que hace que anotar algo y volver enseñe
 * lo nuevo: la entrada vieja deja de encontrarse sola, sin tener que ir a borrarla.
 */
private fun clave(pdf: String, pagina: Int, ancho: Int, rutaDelDibujo: String?): String {
    val cuando = rutaDelDibujo?.let { java.io.File(it).lastModified() } ?: 0L
    return "${pdf.hashCode()}-$pagina-$ancho-$cuando"
}

/**
 * Una página de un PDF **con lo dibujado encima**, en una sola imagen.
 *
 * ## Por qué hace falta
 *
 * Cuando se adjunta una página a la conversación, lo que se está guardando no es «la
 * página 7 de ese PDF»: es lo que uno señaló en ella. Enseñar la página limpia es
 * enseñar precisamente lo que no importa, y obliga a abrir el editor para saber si la
 * anotación de la que uno se acuerda está ahí o se perdió.
 *
 * ## Cómo se juntan las dos cosas
 *
 * El editor coloca la página en la escena ocupando el rectángulo `(0,0)`–`(ancho,alto)`
 * de la hoja dibujada a [PdfDoc.PAGE_WIDTH] —ver `DrawEditorActivity.encajarLaPagina`—,
 * así que las coordenadas de lo dibujado ya están en esas unidades. Juntar las dos capas
 * es entonces una regla de tres: se pinta la hoja al ancho que se quiera y se pasa al
 * dibujo un zoom de `ancho / PAGE_WIDTH`, sin desplazamiento.
 *
 * Esa relación es la única atadura entre este fichero y el editor. Si algún día la página
 * dejara de colocarse en el origen, aquí saldría todo corrido, y por eso está escrito.
 *
 * ## Lo que cuesta
 *
 * Descodificar una página de PDF y pintar una escena no es gratis, así que esto es
 * `suspend` y **no se puede llamar desde la composición**. Lo compuesto se guarda en una
 * caché en memoria, porque la lista pasa por delante de la misma hoja muchas veces.
 */
/**
 * La caja de la foto dentro de un dibujo, para recortar a ella.
 *
 * Cuando un dibujo nació de una foto —se anota una captura— la foto es el elemento de
 * imagen que hay dentro. Encuadrar por el contenido hace que un trazo que se sale la
 * agrande y aparezca con bandas alrededor; encuadrar por ella deja exactamente la foto,
 * con lo dibujado encima recortado a su borde, que es lo que se ve en el editor.
 *
 * Devuelve null si el dibujo no tiene ninguna foto dentro: entonces no hay nada a lo que
 * recortar y manda el contenido, como siempre.
 */
fun cajaDeLaFoto(escena: com.forge.pixpin.motor.Scene): com.forge.pixpin.motor.Bounds? {
    val fotos = escena.contenidoVisible.filter {
        it.type == com.forge.pixpin.motor.ElementType.IMAGE
    }
    if (fotos.isEmpty()) return null
    return com.forge.pixpin.motor.getCommonBounds(fotos)
}

suspend fun paginaAnotada(
    context: Context,
    pdf: String,
    pagina: Int,
    dibujo: String?,
    rutaDelDibujo: String?,
    ancho: Int
): Bitmap? {
    val k = clave(pdf, pagina, ancho, rutaDelDibujo)
    compuestas.get(k)?.let { return it }

    // **La hoja se pide por la caché de miniaturas del proyecto**, no dibujando el PDF a
    // pelo. Con eso se heredan tres cosas que aquí faltaban: la caché en memoria y en
    // disco, y sobre todo el cerrojo — `PdfRenderer` no admite dos páginas abiertas a la
    // vez, y con cuatro hojas a la vista se estaban abriendo cuatro.
    val hoja = PdfMiniaturas.de(context, pdf, pagina, ancho) ?: return null
    if (dibujo == null || rutaDelDibujo == null) return hoja

    val escena = runCatching { ExcalidrawStore.cargar(rutaDelDibujo) }.getOrNull()
        ?: return hoja
    // Sin nada dibujado se devuelve la hoja tal cual: copiarla para no pintar nada
    // gastaría el doble de memoria por página en una lista que puede tener veinte.
    if (escena.contenidoVisible.isEmpty()) return hoja

    return runCatching {
        // La hoja que devuelve la caché **la comparten todos**: pintar encima de ella
        // ensuciaría la miniatura que ve el carrusel de proyectos. Por eso se copia, y
        // por eso la copia se guarda aquí: para hacerla una sola vez.
        val junto = hoja.copy(Bitmap.Config.ARGB_8888, true) ?: return hoja
        val lienzo = Canvas(junto)
        val zoom = junto.width.toDouble() / PdfDoc.PAGE_WIDTH
        Renderer(
            { id -> escena.files[id]?.path?.let { ImageStore.load(it) } },
            paraExportar = true
        ).renderScene(
            lienzo,
            escena.copy(
                elements = escena.contenidoVisible,
                viewport = Viewport(scrollX = 0.0, scrollY = 0.0, zoom = zoom)
            ),
            junto.width.toDouble(),
            junto.height.toDouble()
        )
        compuestas.put(k, junto)
        junto
    }.getOrDefault(hoja)
}
