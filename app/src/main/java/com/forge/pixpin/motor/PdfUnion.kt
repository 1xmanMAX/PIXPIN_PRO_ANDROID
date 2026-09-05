package com.forge.pixpin.motor

import java.util.ArrayDeque

/**
 * **Pegar las páginas de un PDF detrás de las de otro, sin tocar ni rasterizar nada.**
 *
 * Un proyecto tiene *un* documento, y sus hojas de PDF son estáticas y vectoriales porque
 * son páginas de ese documento. Cuando al chat de un proyecto que ya tiene documento llega
 * otro PDF, las páginas nuevas se metían como fotos en lienzos —movibles, en píxeles—, que
 * no es lo mismo (lo reportó el usuario el 5-sep-2026). Aquí se hace lo que toca: las
 * páginas del segundo PDF se **copian dentro del primero**, como páginas de verdad, y el
 * proyecto sigue teniendo un documento con más hojas.
 *
 * Se hace con la misma escritura incremental que las anotaciones ([PdfEscritura]): ni un
 * byte del original se mueve, se añaden detrás los objetos copiados —cada página con todo
 * lo que alcanza desde ella: contenido, recursos, fuentes, imágenes— con números nuevos, un
 * nodo de páginas que los agrupa, y el árbol de páginas del original reescrito con ese
 * nodo de más. Lo que una página heredaba de sus padres en el segundo PDF (tamaño,
 * recursos, giro) se le escribe dentro, porque sus padres no viajan.
 *
 * Con un PDF cifrado —cualquiera de los dos— no se puede, y se dice.
 */
object PdfUnion {

    /** Lo que se hereda por el árbol de páginas y hay que dejar escrito en cada página. */
    private val HEREDABLES = listOf("Resources", "MediaBox", "CropBox", "Rotate")

    /** Tope de objetos copiados, por si un PDF roto se referencia a sí mismo sin fin. */
    private const val MAX_OBJETOS = 200_000

    /** El primer archivo con las páginas del segundo detrás, o null si no se pudo. */
    fun anadirPaginas(primero: ByteArray, segundo: ByteArray): ByteArray? = runCatching {
        val a = leerPdf(primero) ?: return null
        val b = leerPdf(segundo) ?: return null
        if (a.cifrado || b.cifrado) return null
        val raizA = a.diccDe(a.trailer.entradas["Root"]) ?: return null
        val refPaginasA = raizA.ref("Pages") ?: return null
        val paginasA = a.diccDe(refPaginasA) ?: return null
        val paginasB = b.paginas()
        if (paginasB.isEmpty()) return null

        var siguiente = PdfEscritura.primerNumeroLibre(a)
        val nuevos = ArrayList<ObjetoPdf>()
        val numeroDe = HashMap<Int, Int>()          // objeto de B → número en A
        val pendientes = ArrayDeque<Int>()
        fun numeroPara(deB: Int): Int = numeroDe.getOrPut(deB) { pendientes += deB; siguiente++ }

        // Los objetos que se copian llevan sus referencias renumeradas.
        fun trasladar(v: PdfValor?): PdfValor = when (v) {
            null -> PdfValor.Nulo
            is PdfValor.Ref -> PdfValor.Ref(numeroPara(v.numero), 0)
            is PdfValor.Lista -> PdfValor.Lista(v.valores.map { trasladar(it) })
            is PdfValor.Dicc -> PdfValor.Dicc(v.entradas.mapValues { trasladar(it.value) })
            is PdfValor.Flujo -> PdfValor.Flujo(trasladar(v.dicc) as PdfValor.Dicc, v.datos)
            else -> v
        }

        val nodo = siguiente++
        val refsDePaginas = ArrayList<PdfValor>()
        for (numero in paginasB) {
            val pagina = b.diccDe(PdfValor.Ref(numero, 0)) ?: continue
            val entradas = LinkedHashMap<String, PdfValor>()
            for ((k, v) in pagina.entradas) {
                // El padre es el nodo nuevo; las anotaciones no viajan, que apuntan a
                // formularios y destinos de un archivo que se queda atrás.
                if (k == "Parent" || k == "Annots") continue
                entradas[k] = trasladar(v)
            }
            // Lo heredado, escrito dentro: los padres de B no se copian.
            for (clave in HEREDABLES) {
                if (entradas.containsKey(clave)) continue
                heredado(b, pagina, clave)?.let { entradas[clave] = trasladar(it) }
            }
            entradas["Type"] = PdfValor.Nombre("Page")
            entradas["Parent"] = PdfValor.Ref(nodo, 0)
            val mio = siguiente++
            nuevos += ObjetoPdf(mio, PdfValor.Dicc(entradas))
            refsDePaginas += PdfValor.Ref(mio, 0)
        }
        if (refsDePaginas.isEmpty()) return null

        // Todo lo que las páginas alcanzan, hasta que no quede nada por copiar.
        while (pendientes.isNotEmpty()) {
            if (nuevos.size > MAX_OBJETOS) return null
            val deB = pendientes.removeFirst()
            val valor = b.objeto(deB) ?: PdfValor.Nulo
            nuevos += ObjetoPdf(numeroDe.getValue(deB), trasladar(valor))
        }

        // El nodo que agrupa las páginas nuevas, colgado del árbol del primero…
        nuevos += ObjetoPdf(
            nodo,
            PdfValor.Dicc(
                mapOf(
                    "Type" to PdfValor.Nombre("Pages"),
                    "Parent" to refPaginasA,
                    "Kids" to PdfValor.Lista(refsDePaginas),
                    "Count" to PdfValor.Numero(refsDePaginas.size.toDouble())
                )
            )
        )
        // …y el árbol del primero, con un hijo más y la cuenta al día.
        val kids = (a.resolver(paginasA.entradas["Kids"]) as? PdfValor.Lista)?.valores.orEmpty()
        val cuenta = paginasA.entero("Count") ?: a.paginas().size
        nuevos += ObjetoPdf(
            refPaginasA.numero,
            PdfValor.Dicc(
                paginasA.entradas +
                    ("Kids" to PdfValor.Lista(kids + PdfValor.Ref(nodo, 0))) +
                    ("Count" to PdfValor.Numero((cuenta + refsDePaginas.size).toDouble()))
            )
        )
        PdfEscritura.incremental(a, nuevos)
    }.getOrNull()

    /** El valor de [clave] en la página o, si no lo tiene, en el primer padre que lo tenga. */
    private fun heredado(b: PdfArchivo, pagina: PdfValor.Dicc, clave: String): PdfValor? {
        var d: PdfValor.Dicc? = pagina
        var saltos = 0
        while (d != null && saltos++ < 64) {
            d.entradas[clave]?.let { return it }
            d = b.diccDe(d.entradas["Parent"])
        }
        return null
    }
}
