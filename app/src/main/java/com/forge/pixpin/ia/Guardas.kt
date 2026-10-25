package com.forge.pixpin.ia

/**
 * **Las guardas deterministas del turno** (`guardas.rs` del PC, 25-oct-2026; portadas el mismo
 * día): comprobaciones sin modelo que el bucle del [Agente] hace en cada vuelta, como los hooks
 * de Claude Code. El análisis está en el PC, `docs/investigacion/2026-10-25-agente-como-claude-code.md`.
 *
 * - [sinAnexos]: el pedido tal como lo escribió el usuario, sin el resumen del lienzo que le pega
 *   la app ([LienzoDestino]). Lo que decide (los colores, la guarda) lee esto, no los anexos.
 * - [duplicaria]: si el pedido es cambiar lo que ya hay y una herramienta de crear desde cero va
 *   a añadir otro al lado, un `ERROR` que explica qué usar en su lugar, en vez de dibujar. Fue el
 *   fallo real del usuario en el PC: pidió agrandar el cronograma y le dibujó dos más al lado.
 * - [anunciaPendiente]: una respuesta final que solo anuncia trabajo («ahora voy a limpiar el
 *   resto…») o reconoce que está a medias **no cierra el turno**: se le devuelve [EMPUJON_ANUNCIO]
 *   y sigue (hasta [EMPUJONES] veces).
 * - [esParar] y [mensajeEntrante]: lo que el usuario escribe mientras se trabaja (25-oct, «como
 *   en Claude Code»).
 */
object Guardas {
    /** Cómo empieza el resumen del lienzo pegado al pedido (`"\n\n[" + resumen`). */
    const val MARCA_RESUMEN = "\n\n[Lienzo destino:"

    /** Lo que dice el resumen cuando una plantilla de serie ya está en el lienzo. */
    const val MARCA_PLANTILLAS = "Plantillas de serie que YA estan en el lienzo"

    /** Las notas que la app pega detrás del pedido. */
    private val MARCAS_DE_NOTAS = listOf("\n\n(Ahora:", "\n\n(Archivos adjuntos en este chat", "\n\n(La imagen adjunta es")

    /** Los avisos del turno empiezan así: no son el pedido del usuario (`MARCA_DEL_TURNO` del PC). */
    const val MARCA_DEL_TURNO = "[Aviso del turno]"

    /** **El pedido sin lo que le pega la app**: se corta en el primer anexo. */
    fun sinAnexos(texto: String): String {
        val corte = (listOf(MARCA_RESUMEN) + MARCAS_DE_NOTAS).mapNotNull { m -> texto.indexOf(m).takeIf { it >= 0 } }.minOrNull() ?: texto.length
        return texto.substring(0, corte).trimEnd()
    }

    /** Si el resumen pegado dice que el lienzo tiene algo. */
    fun lienzoConAlgo(texto: String): Boolean = texto.contains(MARCA_RESUMEN) && !texto.contains("[Lienzo destino: vacio")

    private fun palabras(q: String) = q.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    /** Si el usuario pide expresamente otro (además del que hay). */
    fun pideOtro(pedido: String): Boolean {
        val q = Acciones.normalizar(pedido)
        val ps = palabras(q)
        return ps.any { it in setOf("otro", "otra", "nuevo", "nueva", "aparte", "copia", "duplica", "duplicalo", "duplicala", "segundo", "segunda") } ||
            q.contains("al lado") || q.contains("desde cero") || q.contains("de nuevo")
    }

    /** **Si piden reorganizar lo que ya hay** (`seleccion::pide_reorganizar` del PC). */
    fun pideReorganizar(q: String, ps: List<String>): Boolean {
        val verbo = listOf("reorganiz", "reorden", "reacomod", "intercambi", "muev", "mover", "pon", "coloca", "pasa").any { v -> ps.any { it.startsWith(v) } }
        val que = listOf("recuadro", "seccion", "bloque", "apartado", "cuadro", "caja", "pagina", "hoja", "plantilla").any { c -> ps.any { it.startsWith(c) } }
        val sitio = listOf("arriba", "abajo", "debajo", "encima", "de sitio", "de lugar", "al final", "al principio").any { q.contains(it) }
        val fuerte = listOf("reorganiz", "reorden", "reacomod", "intercambi").any { v -> ps.any { it.startsWith(v) } }
        return (fuerte && que) || (verbo && que && sitio)
    }

    /** **Si piden agrandar (o reducir) algo que ya está** (`seleccion::pide_agrandar` del PC). */
    fun pideAgrandar(q: String, ps: List<String>): Boolean {
        val verbo = listOf("amplia", "ampli", "agrand", "extiend", "extend", "expand", "alarg", "aument", "reduc", "acort", "achic", "disminu")
            .any { v -> ps.any { it.startsWith(v) } } || q.contains("mas grande") || q.contains("que sean ") || q.contains("hasta la semana")
        val deQue = listOf("semana", "fila", "actividad", "tarea", "plantilla", "cronograma", "gantt", "hora", "dia", "esto", "esta", "este", "figura", "hoja")
            .any { c -> ps.any { it.startsWith(c) } }
        return verbo && deQue
    }

    /** **Si el pedido es cambiar lo que ya existe** (agrandarlo o reorganizarlo). */
    fun esCambiarLoQueHay(pedido: String): Boolean {
        val q = Acciones.normalizar(pedido)
        val ps = palabras(q)
        return pideAgrandar(q, ps) || pideReorganizar(q, ps)
    }

    /**
     * **La guarda anti-duplicado.** [pedido] es el texto entero del último mensaje del usuario (con
     * el resumen del lienzo, que dice si hay algo). El `ERROR` para el modelo si [llamada] haría
     * otro al lado de lo que el usuario pidió cambiar; null si se puede hacer.
     *
     * Solo salta si a la vez: el pedido es agrandar o reorganizar, el lienzo tiene algo, el usuario
     * no pidió «otro» y la llamada crea desde cero (un SVG, o poner otra plantilla de serie cuando
     * ya hay una); dibujar piezas sueltas nunca se para. El teléfono no tiene `cronograma`, ni
     * `alargar`/`reorganizar_hoja`/`transformar_elementos`: el consejo nombra lo que sí hay aquí.
     */
    fun duplicaria(pedido: String, llamada: Llamada): String? {
        val dicho = sinAnexos(pedido)
        if (!esCambiarLoQueHay(dicho) || !lienzoConAlgo(pedido) || pideOtro(dicho)) return null
        val hayPlantilla = pedido.contains(MARCA_PLANTILLAS)
        val crea = when (llamada) {
            is Llamada.DibujoSvg -> true
            is Llamada.Figuras -> llamada.pedido is PedidoFiguras.Poner && hayPlantilla
            else -> false
        }
        if (!crea) return null
        val como = "Cámbialo con sus propias piezas: editar_texto para sus textos, o añade solo las piezas que falten con dibujar_en_lienzo, alineadas con lo que hay. " +
            "En este teléfono aún no se puede alargar ni reorganizar una plantilla de serie: dilo si es lo que pide."
        return "ERROR: no se hizo ${llamada.nombre}: el usuario pidió cambiar lo que YA está en el lienzo (agrandar o reorganizar), y esto añadiría " +
            "otro al lado. $como No dibujes otra versión ni inventes contenido. Si de verdad hace falta uno nuevo además del que hay, " +
            "pregúntalo antes con preguntar_usuario."
    }

    // ------------------------------------------------------------------------- anunciar en vez de hacer

    /** Cuántos empujones como mucho en un turno: con dos o tres el modelo acaba o dice qué falta. */
    const val EMPUJONES = 3

    /** Lo que se le dice al modelo cuando contesta anunciando en vez de hacer. */
    const val EMPUJON_ANUNCIO = "$MARCA_DEL_TURNO Tu respuesta anuncia trabajo o dice que quedó a medias, pero el turno se acaba cuando " +
        "contestas sin herramientas: el usuario no verá nada más. No lo anuncies: hazlo AHORA con las herramientas y termina. " +
        "Si de verdad no puedes, di exactamente qué falta y por qué, sin prometer nada."

    private val ANUNCIOS = listOf(
        "voy a ", "ahora voy", "a continuacion", "acto seguido", "quedo a medias", "quedo incompleto", "quedo incompleta",
        "esta a medias", "todavia no esta", "aun no esta", "todavia no he", "aun no he", "todavia falta", "aun falta",
        "me falta ", "falta por hacer", "faltan por hacer", "lo hare", "la hare", "los hare", "las hare", "lo hago ahora",
        "procedo a", "procedere", "empezare", "comenzare", "continuare", "seguire con"
    )

    private val OFERTAS = listOf("si quieres", "si lo prefieres", "si prefieres", "si te parece", "si lo deseas", "cuando quieras")

    /**
     * **Si la respuesta anuncia trabajo pendiente o reconoce que no está acabado.** Se mira solo la
     * cola (las últimas 600 letras), frase a frase; una pregunta o una oferta no cuentan.
     */
    fun anunciaPendiente(texto: String): Boolean {
        val t = Acciones.normalizar(texto)
        val n = t.codePointCount(0, t.length)
        val cola = if (n > 600) t.substring(t.offsetByCodePoints(0, n - 600)) else t
        val frases = ArrayList<String>()
        val actual = StringBuilder()
        for (c in cola) {
            actual.append(c)
            if (c == '.' || c == '!' || c == '?' || c == '\n' || c == '…' || c == ';') { frases += actual.toString(); actual.setLength(0) }
        }
        frases += actual.toString()
        return frases.any { f0 ->
            val f1 = f0.trim()
            if (f1.isEmpty() || f1.endsWith('?') || f1.startsWith('¿')) return@any false
            val f = " $f1 "
            OFERTAS.none { f.contains(it) } && ANUNCIOS.any { f.contains(" $it") }
        }
    }

    // ------------------------------------------------------------------------- lo que escribe mientras trabaja

    /** Si lo escrito a media tarea es solo pedir que pare. */
    fun esParar(texto: String): Boolean {
        val t = Acciones.normalizar(texto.trim()).trim { !it.isLetterOrDigit() }
        return t in setOf("para", "parate", "detente", "stop", "basta", "alto", "cancela", "cancelalo", "no sigas", "deja", "dejalo", "para ya", "detenlo")
    }

    /** Lo que escribió el usuario a media tarea, como lo lee el modelo (el texto del PC). */
    fun mensajeEntrante(textos: List<String>): MensajeIa = MensajeIa.usuario(
        "[Mensaje nuevo del usuario mientras trabajabas]\n${textos.joinToString("\n")}\n\n" +
            "(Tenlo en cuenta ya: si corrige o anade algo, ajusta lo que haces y lo que queda; si contesta a algo que preguntaste, sigue con eso; si pide parar, para y di donde lo dejas.)"
    )
}
