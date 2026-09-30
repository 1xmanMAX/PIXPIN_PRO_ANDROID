package com.forge.pixpin.motor

/**
 * Los dibujos que PixPin pintó en la hoja [i]: los formularios `PxT…` de sus recursos, en el orden
 * en que se añadieron. Desde el 30-sep-2026 van en el contenido de la página y no en un sello
 * (ver [PdfAnotado.anotar]).
 */
internal fun formasDePixPin(a: PdfArchivo, i: Int): List<PdfValor.Flujo> {
    val recursos = a.diccDe(a.pagina(i)!!.entradas["Resources"]) ?: return emptyList()
    val xo = a.diccDe(recursos.entradas["XObject"]) ?: return emptyList()
    return xo.entradas.filterKeys { it.startsWith("${PdfAnotado.PREFIJO}T") }
        .toSortedMap(compareBy { it.removePrefix("${PdfAnotado.PREFIJO}T").toInt() })
        .values.map { a.resolver(it) as PdfValor.Flujo }
}
