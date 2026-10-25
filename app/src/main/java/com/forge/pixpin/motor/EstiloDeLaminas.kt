package com.forge.pixpin.motor

/**
 * **Los mandos de las láminas, en la misma tabla que los demás** (25-oct-2026).
 *
 * El panel decide qué enseña con [propiedadesDe] y qué escribe con [conEstilo]: una sola tabla
 * para las dos cosas. Lo de las láminas del PC —el cierre del arco, el degradado, el raya-punto,
 * los arquitos y la letra de un ángulo, el festón de la nube— **depende del elemento y no solo de
 * su tipo** (un arco abierto no se rellena; una marca no se cierra; una línea nube no tiene
 * esquinas), así que va aquí aparte y entra en la tabla por un solo sitio: [conLasDeLamina].
 *
 * Puro, como el resto de la tabla: se comprueba en `LaminasDeGeometriaTest`.
 */

/**
 * [base] (lo que admite el tipo de [e]) con lo de lámina que le toque a **este** elemento.
 */
fun conLasDeLamina(e: Element, base: Set<Propiedad>): Set<Propiedad> {
    var s = base
    when (e.type) {
        ElementType.RECTANGLE, ElementType.DIAMOND, ElementType.ELLIPSE, ElementType.REGION ->
            s = s + Propiedad.DEGRADADO
        ElementType.ARC -> {
            val marca = Laminas.marcaDe(e)
            s = when {
                marca != null -> s + Propiedad.FONDO + Propiedad.ANGULO - Propiedad.RUGOSIDAD +
                    (if (!marca.recto) setOf(Propiedad.LETRA_ANGULO) else emptySet())
                Laminas.cierreDe(e) != null -> s + Propiedad.CIERRE_ARCO + Propiedad.FONDO + Propiedad.DEGRADADO
                else -> s + Propiedad.CIERRE_ARCO
            }
        }
        else -> Unit
    }
    if (e.esNube) s = s - Propiedad.ESQUINAS + Propiedad.NUBE
    return s
}

/** Lo de lámina que ofrece una herramienta con nada elegido. Ver [propiedadesPara]. */
fun lasDeLaminaDeHerramienta(tool: Tool, base: Set<Propiedad>): Set<Propiedad> = when (tool) {
    Tool.RECTANGLE, Tool.DIAMOND, Tool.ELLIPSE, Tool.RELLENO -> base + Propiedad.DEGRADADO
    Tool.NUBE -> base - Propiedad.ESQUINAS + Propiedad.NUBE
    Tool.ANGULO -> setOf(
        Propiedad.TRAZO, Propiedad.FONDO, Propiedad.LINEA, Propiedad.OPACIDAD,
        Propiedad.ANGULO, Propiedad.MODO_ANGULO
    )
    else -> base
}

/** El estilo de lámina que tiene [e] ahora mismo, sobre [s]. Ver [estiloDe]. */
fun estiloDeLamina(e: Element, s: ItemStyle): ItemStyle {
    val marca = Laminas.marcaDe(e)
    return s.copy(
        rayaPunto = Laminas.esRayaPunto(e),
        cierreArco = e.cierreArco,
        degradado = Laminas.degradadoDe(e)?.tipo?.palabra,
        arcosAngulo = marca?.arcos ?: s.arcosAngulo,
        letraAngulo = marca?.valor,
        nubeRadio = if (e.esNube) NubeDeRevision.radioUtil(e.nubeRadio) else s.nubeRadio,
        nubeBase = if (e.esNube) NubeDeRevision.baseDe(e)!!.palabra else s.nubeBase
    )
}

/** Lo de lámina que cambió entre [anterior] y [nuevo], puesto sobre [destino]. Ver [conCambios]. */
fun cambiosDeLamina(destino: ItemStyle, anterior: ItemStyle, nuevo: ItemStyle): ItemStyle {
    var out = destino
    if (nuevo.rayaPunto != anterior.rayaPunto) out = out.copy(rayaPunto = nuevo.rayaPunto)
    if (nuevo.cierreArco != anterior.cierreArco) out = out.copy(cierreArco = nuevo.cierreArco)
    if (nuevo.degradado != anterior.degradado) out = out.copy(degradado = nuevo.degradado)
    if (nuevo.arcosAngulo != anterior.arcosAngulo) out = out.copy(arcosAngulo = nuevo.arcosAngulo)
    if (nuevo.modoAngulo != anterior.modoAngulo) out = out.copy(modoAngulo = nuevo.modoAngulo)
    if (nuevo.letraAngulo != anterior.letraAngulo) out = out.copy(letraAngulo = nuevo.letraAngulo)
    if (nuevo.nubeRadio != anterior.nubeRadio) out = out.copy(nubeRadio = nuevo.nubeRadio)
    if (nuevo.nubeBase != anterior.nubeBase) out = out.copy(nubeBase = nuevo.nubeBase)
    return out
}

/**
 * [e] con lo de lámina de [s] en lo que le aplique ([aplican]). Ver [conEstilo].
 *
 * Lo que no se entiende se deja como venía: un cierre o un degradado de una versión futura solo
 * se cambia si aquí se elige otro, la misma regla que el PC (`laminas_hacia`).
 */
fun conEstiloDeLamina(e: Element, s: ItemStyle, aplican: Set<Propiedad>): Element {
    var out = e
    if (Propiedad.LINEA in aplican) {
        out = if (s.rayaPunto) out.copy(strokeStyle = StrokeStyle.DASHED, trazoRayaPunto = true)
        else out.copy(trazoRayaPunto = null)
    }
    if (Propiedad.CIERRE_ARCO in aplican && out.marcaAngulo == null) out = out.copy(cierreArco = s.cierreArco)
    if (Propiedad.DEGRADADO in aplican) {
        val tipo = Laminas.TipoDegradado.desdePalabra(s.degradado)
        val actual = Laminas.degradadoDe(out)
        out = when {
            tipo == null -> if (s.degradado == null && actual != null) out.copy(degradado = null) else out
            isTransparent(out.backgroundColor) -> out
            actual != null && actual.tipo == tipo && actual.desde.equals(out.backgroundColor, ignoreCase = true) -> out
            else -> Laminas.degradadoDesdeRelleno(tipo, out.backgroundColor)
                ?.let { out.copy(degradado = Laminas.degradadoJson(it)) } ?: out
        }
    }
    val marca = Laminas.marcaDe(out)
    if (marca != null) {
        var m = marca
        if (Propiedad.ANGULO in aplican && !m.recto) m = m.copy(arcos = s.arcosAngulo.coerceIn(1, 3))
        if (Propiedad.LETRA_ANGULO in aplican && !m.recto) m = m.copy(valor = s.letraAngulo)
        if (m != marca) out = out.copy(marcaAngulo = Laminas.marcaJson(m))
    }
    if (Propiedad.NUBE in aplican && out.esNube) {
        out = out.copy(
            nubeRadio = NubeDeRevision.radioUtil(s.nubeRadio),
            nubeBase = if (out.type == ElementType.LINE) NubeDeRevision.Base.desdePalabra(s.nubeBase).palabra else out.nubeBase
        )
    }
    return out
}
