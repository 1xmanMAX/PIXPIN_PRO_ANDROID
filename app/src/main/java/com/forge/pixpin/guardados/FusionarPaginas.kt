package com.forge.pixpin.guardados

import android.content.Context
import android.graphics.Bitmap
import com.forge.pixpin.data.ProyectosRepository
import com.forge.pixpin.motor.Element
import com.forge.pixpin.motor.ElementType
import com.forge.pixpin.motor.ExcalidrawStore
import com.forge.pixpin.motor.FusionDePaginas
import com.forge.pixpin.motor.Hoja
import com.forge.pixpin.motor.PdfDoc
import com.forge.pixpin.motor.Proyecto
import com.forge.pixpin.motor.SceneFile
import com.forge.pixpin.motor.Scene
import java.io.File

/**
 * **Varias páginas del PDF de un proyecto, juntas en un lienzo nuevo.**
 *
 * Lo pidió el usuario el 13-sep-2026 —«fusionar páginas, por ejemplo la 4, la 5 y la 6, en un
 * mismo lienzo, separadas a una distancia razonable»— y es lo que se hace en papel cuando un
 * detalle se entiende mirando dos planos a la vez: se ponen en la mesa uno al lado del otro y se
 * dibuja por encima de los dos.
 *
 * La hoja que sale **es una más del proyecto**, con su marco por página, así que se comparte, se
 * exporta y se sincroniza como cualquier otra; y como cada página va en su marco, al entregarla
 * en PDF vuelve a salir una página por plano.
 *
 * Las páginas van como **imagen**, no como el PDF de fondo: un lienzo tiene un solo papel debajo
 * y aquí hay tres. Eso pone un techo a lo que se ve al acercarse —ver
 * [FusionDePaginas.PIXELES_EN_TOTAL], que reparte los píxeles entre las páginas que se fusionen—
 * y es el precio de tenerlas juntas; la página suelta sigue estando en el proyecto, vectorial y
 * sin techo, para el trabajo de detalle.
 *
 * Las cuentas —en fila o en columna, la separación, los píxeles de cada una— están en
 * [FusionDePaginas], sin Android y con pruebas.
 */
object FusionarPaginas {

    /** Lo que hace falta saber de una fusión antes de pedirla: cuántas páginas y de dónde. */
    class Peticion(val proyecto: Proyecto, val pdf: String, val paginas: List<Int>)

    /**
     * Si estas claves marcadas en un proyecto son **dos o más páginas de su PDF**, la petición
     * para fusionarlas; null si no (hay lienzos marcados, notas, o solo una página).
     *
     * Las claves son las de `HojasDelProyecto.Pagina.clave`, que es lo que marca la pantalla de
     * proyectos: `<hoja>/<marco>/<nota>`.
     */
    fun de(proyecto: Proyecto, claves: Set<String>): Peticion? {
        val pdf = proyecto.pdfOrigen?.takeIf { File(it).exists() } ?: return null
        val paginas = ArrayList<Int>()
        for (clave in claves) {
            val hoja = proyecto.hojas.firstOrNull { clave.startsWith(it.id + "/") } ?: return null
            val pagina = hoja.pagina ?: return null
            // Un lienzo con algo dibujado encima no es «la página»: se fusiona lo que es página.
            if (hoja.marco != null || hoja.nota != null || hoja.tabla != null) return null
            paginas += pagina
        }
        val limpias = paginas.distinct().sorted()
        if (limpias.size < 2 || limpias.size > FusionDePaginas.TOPE_DE_PAGINAS) return null
        return Peticion(proyecto, pdf, limpias)
    }

    /**
     * Monta el lienzo y lo deja en el proyecto. Devuelve la hoja nueva, o null si no se pudo
     * pintar ninguna página. Es trabajo de disco y de CPU: fuera del hilo de la pantalla.
     */
    fun enUnLienzo(context: Context, proyectos: ProyectosRepository, peticion: Peticion, ahora: Long): Hoja? {
        val medidas = peticion.paginas.map { PdfDoc.medidaEnPuntos(peticion.pdf, it) ?: (595.0 to 842.0) }
        val anchos = FusionDePaginas.anchos(medidas)
        val elementos = ArrayList<Element>()
        val archivos = HashMap<String, SceneFile>()
        // Primero se pintan todas y se apuntan sus tamaños de verdad; los sitios se reparten
        // después, porque la separación sale del mayor y no se sabe hasta tenerlas todas.
        val mapas = ArrayList<Pair<Int, SceneFile>>()   // ancho en píxeles, archivo
        val tamanos = ArrayList<Pair<Double, Double>>()
        for ((i, pagina) in peticion.paginas.withIndex()) {
            val bmp = PdfDoc.render(peticion.pdf, pagina, anchos[i]) ?: continue
            val temporal = File(context.cacheDir, "fusion-$ahora-$i.jpg")
            val escrito = runCatching {
                temporal.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            }.isSuccess
            val archivo = if (escrito) ExcalidrawStore.guardarImagen(context, temporal, "image/jpeg") else null
            temporal.delete()
            val an = bmp.width
            val al = bmp.height
            bmp.recycle()
            if (archivo == null) continue
            mapas += an to archivo
            tamanos += an.toDouble() to al.toDouble()
            archivos[archivo.id] = archivo
        }
        if (mapas.isEmpty()) return null
        val sitios = FusionDePaginas.sitios(tamanos, FusionDePaginas.enFila(tamanos))
        for ((i, m) in mapas.withIndex()) {
            val s = sitios[i]
            val pagina = peticion.paginas.getOrElse(i) { i }
            // Cada página en su marco: así la hoja vuelve a salir con una página por plano al
            // entregarla, y el marco se puede mover con su página dentro.
            // **Con el candado puesto.** Lo pidió el usuario (14-sep-2026): son el papel, no algo
            // que se dibuje, y un arrastre sin querer mueve la página con lo que ya haya encima.
            // Se quita con el mismo candado del panel de acciones cuando de verdad haga falta.
            elementos += Element(
                id = "marco-fus-$ahora-$i", type = ElementType.FRAME,
                x = s.x, y = s.y, width = s.ancho, height = s.alto, seed = 1,
                name = FusionDePaginas.rotulo(peticion.paginas, pagina), locked = true
            )
            elementos += Element(
                id = "pag-fus-$ahora-$i", type = ElementType.IMAGE,
                x = s.x, y = s.y, width = s.ancho, height = s.alto, seed = 2,
                fileId = m.second.id, locked = true
            )
        }
        val dibujo = "dib-fus-$ahora"
        ExcalidrawStore.guardar(context, dibujo, Scene(elements = elementos, files = archivos)) ?: return null
        val hoja = Hoja(
            id = "hoja-fus-$ahora",
            nombre = FusionDePaginas.nombre(peticion.paginas),
            dibujo = dibujo
        )
        return proyectos.conHoja(peticion.proyecto, hoja, ahora)
    }
}
