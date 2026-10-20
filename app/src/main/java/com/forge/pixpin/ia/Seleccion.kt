package com.forge.pixpin.ia

/**
 * **Qué herramientas se le enseñan al modelo local en cada turno** (`seleccion.rs` del PC, portado
 * tal cual; lo comprueba `SeleccionTest` contra lo que da el PC para 100 pedidos).
 *
 * Con todas (casi 60) un modelo de 2B se pierde y el catálogo solo se come ~3000 tokens de cada
 * vuelta: antes de preguntarle se eligen las pocas que vienen a cuento ([MAXIMO]) por palabras
 * clave del pedido. Las listas de claves son datos: se copian tal cual del PC.
 *
 * [disponibles]: las que sabe hacer este aparato. Las demás ni puntúan (un teléfono no ofrece
 * «pinear en el escritorio»); null = todas las del PC (para comparar con él).
 */
object Seleccion {
    const val MAXIMO = 6

    /** Las claves de las herramientas propias (`claves_base`). */
    fun clavesBase(nombre: String): List<String> = when (nombre) {
        Herramientas.DIBUJAR -> listOf(
            "dibuj", "pinta", "traza", "figura", "circulo", "cuadrado", "rectangulo", "triangulo", "estrella",
            "flecha", "linea", "diagrama", "flujo", "esquema", "casa", "muneco", "curva", "onda", "poligono",
            "titulo", "lienzo", "forma", "rombo", "elipse", "organigrama", "mapa", "croquis"
        )
        Herramientas.GRAFICA -> listOf("grafic", "barras", "pastel", "torta", "estadistic", "porcentaje", "chart", "encuesta", "datos")
        Herramientas.OCR -> listOf("ocr", "escanead", "texto de", "lee el", "leer el", "extrae", "reconoce")
        Herramientas.CALCAR -> listOf("calca", "copia", "imagen", "foto", "contorno", "pasa este", "pasa esta", "replica")
        Herramientas.AYUDA -> listOf("ayuda", "explica", "funciona", "sirve", "donde", "atajo", "menu")
        Herramientas.SVG -> listOf(
            "ilustra", "svg", "detall", "isometric", "realist", "persona", "animal", "perro", "gato", "arbol", "paisaje",
            "retrato", "a lapiz", "grafito", "boceto", "logo", "icono", "pajaro", "flor", "coche", "edificio"
        )
        Herramientas.FUNCION -> listOf(
            "funcion", "funciones", "f(x)", "y=", "y =", "parabola", "seno", "coseno", "plano cartesiano", "dominio",
            "x^", "x²", "sin(", "cos(", "recta y",
            "polar", "parametric", "cardioide", "elipse", "raices", "tangente", "asintota", "theta", "θ",
            "r =", "area entre", "extremos", "maximos", "minimos", "interseccion"
        )
        Herramientas.ECUACION -> listOf(
            "ecuacion", "ecuaciones", "formula", "latex", "fraccion", "raiz cuadrada", "integral", "teorema", "pitagoras",
            "cuadratica", "derivada"
        )
        Herramientas.CRONOGRAMA -> listOf(
            "cronograma", "gantt", "planificacion", "programacion de obra", "hitos", "plazos", "calendario de",
            "semanas", "fases"
        )
        Herramientas.FISICA -> listOf(
            "fisica", "fuerza", "cuerpo libre", "plano inclinado", "inclinado", "tiro", "parabolic", "proyectil", "lanzad",
            "circuito", "resistencia", "paralelo", "vector", "pendulo", "atwood", "polea", "mru", "mruv", "mrua",
            "velocidad", "aceleracion", "rozamiento", "friccion", "voltaje", "voltio", "ohm", "newton", "bloque"
        )
        Herramientas.PROBLEMA -> listOf(
            "problema", "ejercicio", "enunciado", "en limpio", "a limpio", "reescrib", "transcrib", "resuelv", "resolver",
            "examen"
        )
        Herramientas.FIGURAS -> listOf(
            "guarda esto", "guardalo", "guardala", "como figura", "biblioteca", "figura guardada", "figuras guardadas",
            "mis figuras", "pon el", "pon la", "inserta el", "inserta la", "estampa", "guarda el", "guarda la"
        )
        Herramientas.BUSCAR -> listOf(
            "busca en mis", "buscame", "mis notas", "mis documentos", "mis archivos", "mis cosas", "mis chats",
            "mis mensajes", "que dice", "dice la", "dice el", "segun mi", "segun la", "segun el", "tesis", "mande",
            "envie", "guarde", "apunte", "anote", "documento", "informe", "presupuesto", "plano", "memoria",
            "expediente", "contrato", "acta", "notas"
        )
        else -> emptyList()
    }

    /** Las palabras de un pedido ya normalizado. */
    fun palabras(q: String): List<String> = q.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    fun puntuar(claves: List<String>, q: String, palabras: List<String>): Int = claves.sumOf { c ->
        if (' ' in c || ':' in c) (if (q.contains(c)) 3 else 0)
        else if (palabras.any { it.startsWith(c) }) 2 else 0
    }

    /** «¿Qué dice la tesis…?», «¿dónde mandé…?»: sobre lo que el usuario tiene guardado. */
    fun esSobreSusDocumentos(q: String): Boolean = puntuar(clavesBase(Herramientas.BUSCAR), q, palabras(q)) >= 2

    fun esPreguntaDeAyuda(q0: String): Boolean {
        val q = q0.trimStart { !it.isLetterOrDigit() }
        return listOf("como ", "donde ", "que es ", "para que ", "cual ", "en que ", "por que ", "que hace", "se puede").any { q.startsWith(it) }
    }

    private fun esCharla(q: String, palabras: List<String>): Boolean =
        (palabras.size <= 4 && palabras.any { it in setOf("hola", "gracias", "buenas", "adios", "chao", "ok", "vale", "genial", "perfecto") }) ||
            q.contains("que tal")

    /** Si el pedido habla de la imagen adjunta: «cálcala», «copia esta imagen», «dibuja este perrito». */
    fun pideLaImagen(palabras: List<String>): Boolean {
        val verbos = listOf(
            "dibuj", "copi", "calc", "pasa", "pase", "pint", "replic", "pon", "mete", "lleva", "traza", "haz", "reproduc",
            "imita", "convierte", "vectoriz"
        )
        val esto = listOf("imagen", "foto", "adjunt", "captura", "este", "esta", "esto", "estos", "estas", "ese", "esa", "eso", "aqui")
        fun verbo(p: String) = verbos.any { p.startsWith(it) }
        val calca = palabras.any { it.startsWith("calc") || it.startsWith("replic") || it.startsWith("vectoriz") }
        val conPronombre = palabras.any { p -> verbo(p) && listOf("lo", "la", "los", "las").any { p.endsWith(it) } && p.toByteArray().size > 5 }
        val objeto = palabras.any { p -> esto.any { e -> p == e || (e.length > 4 && p.startsWith(e)) } }
        return calca || conPronombre || (palabras.any(::verbo) && objeto)
    }

    fun pideUnaTabla(palabras: List<String>) =
        palabras.any { it.startsWith("tabla") || it.startsWith("excel") || it == "xlsx" || it.startsWith("celda") }

    fun hablaDeDocumentos(palabras: List<String>) = palabras.any { p ->
        listOf("pdf", "documento", "plano", "archivo", "fichero", "word", "excel", "pagina", "adjunt", "docx", "informe").any { p.startsWith(it) }
    }

    fun pidePegarORecortar(palabras: List<String>) =
        palabras.any { it.startsWith("peg") || it.startsWith("recort") || it.startsWith("insert") }

    fun pideInterpretar(q: String) = listOf(
        "con figuras", "con formas", "a lapiz", "con lapiz", "grafito", "boceto", "bosquejo", "interpret", "ilustra",
        "estilo", "caricatura", "a mano", "dibujo de", "en svg", "isometric"
    ).any { q.contains(it) }

    /** La foto de un problema («pásalo en limpio», «resuelve este ejercicio»): `problema::pide_problema`. */
    fun pideProblema(pedido: String): Boolean {
        val q = Acciones.normalizar(pedido)
        return listOf(
            "problema", "ejercicio", "enunciado", "en limpio", "a limpio", "reescrib", "transcrib", "resuelve", "resolver", "resuelvelo",
            "tarea de mate", "examen"
        ).any { q.contains(it) }
    }

    /**
     * **Las herramientas para este pedido.** [conImagen]: si el mensaje trae una foto. Con una
     * imagen y un pedido que habla de ella, **solo `calcar_imagen`** (medido en el PC: con las dos,
     * el 2B dibujaba 4 triángulos en vez de calcar).
     */
    fun paraElPedido(pedido: String, conImagen: Boolean, disponibles: Set<String>? = null): List<Herramienta> {
        fun vale(n: String) = disponibles == null || n in disponibles
        val base = Herramientas.base().filter { vale(it.nombre) }
        fun de(n: String) = base.firstOrNull { it.nombre == n }
        val q = Acciones.normalizar(pedido)
        val palabras = palabras(q)
        if (conImagen && pideProblema(pedido)) {
            return listOf(Herramientas.PROBLEMA, Herramientas.FISICA, Herramientas.FUNCION).mapNotNull(::de)
        }
        if (esPreguntaDeAyuda(q)) {
            val dentro = Acciones.paraUnaPregunta(q).filter { vale(it.herramienta) }.map { Acciones.herramienta(it) }
            if (esSobreSusDocumentos(q) || dentro.isNotEmpty()) {
                return (dentro + listOf(Herramientas.BUSCAR, Herramientas.AYUDA).mapNotNull(::de)).take(MAXIMO)
            }
            return listOfNotNull(de(Herramientas.AYUDA))
        }
        if (conImagen && pideLaImagen(palabras) && !pideUnaTabla(palabras) && !pidePegarORecortar(palabras)) {
            if (pideInterpretar(q)) return listOf(Herramientas.SVG, Herramientas.DIBUJAR).mapNotNull(::de)
            return listOfNotNull(de(Herramientas.CALCAR))
        }
        if (esCharla(q, palabras)) return emptyList()
        val puntuadas = mutableListOf<Pair<Int, Herramienta>>()
        for (h in base) {
            if (h.nombre == Herramientas.CALCAR && !conImagen) continue
            puntuadas += puntuar(clavesBase(h.nombre), q, palabras) to h
        }
        // La hoja al lienzo es solo para la nube: esta selección es la del modelo local.
        for (a in Acciones.TODAS) {
            if (a.herramienta == Acciones.HOJA_A_LIENZO || Acciones.esSoloNube(a.herramienta) || !vale(a.herramienta)) continue
            puntuadas += puntuar(a.claves, q, palabras) to Acciones.herramienta(a)
        }
        for (f in Edicion.FILAS) {
            if (!vale(f.nombre)) continue
            puntuadas += puntuar(f.claves, q, palabras) to (Herramientas.delPc(f.nombre) ?: continue)
        }
        // Estable: a igual puntuación, el orden del catálogo.
        val ordenadas = puntuadas.sortedByDescending { it.first }.toMutableList()
        val edita = ordenadas.any { (s, h) -> s >= 2 && Edicion.fila(h.nombre) != null }
        if (edita && !hablaDeDocumentos(palabras)) ordenadas.removeAll { (_, h) -> Acciones.esDeDocumentos(h.nombre) }
        val elegidas = ordenadas.filter { it.first >= 2 }.take(MAXIMO).toMutableList()
        elegidas.firstOrNull { it.second.nombre == "anadir_tareas" }?.first?.let { varias ->
            elegidas.removeAll { (s, h) -> h.nombre == "anadir_tarea" && s <= varias }
        }
        if (elegidas.any { (s, h) -> s >= 4 && h.nombre in setOf(Herramientas.FISICA, Herramientas.FUNCION, Herramientas.PROBLEMA) }) {
            elegidas.removeAll { (s, h) -> h.nombre == Herramientas.DIBUJAR && s <= 2 }
        }
        if (elegidas.isEmpty()) return listOf(Herramientas.DIBUJAR, Herramientas.GRAFICA, Herramientas.AYUDA).mapNotNull(::de)
        return elegidas.map { it.second }
    }

    /**
     * **Si este pedido merece el modelo de mejor vista** (Gemma 4 E2B): hay una foto y lo que se
     * pide es MIRARLA, no hacer algo con ella.
     */
    fun pideMejorVista(pedido: String, conFoto: Boolean): Boolean {
        if (!conFoto) return false
        val q = Acciones.normalizar(pedido)
        val palabras = palabras(q)
        fun empieza(raices: List<String>) = palabras.any { p -> raices.any { p.startsWith(it) } }
        if (empieza(listOf(
                "dibuj", "calca", "copia", "pasa", "traza", "grafic", "lienzo", "anota", "tarea", "lista", "borra",
                "envia", "manda", "comparte", "guarda", "pinea", "recorta"
            ))) return false
        val frases = listOf(
            "que ves", "que hay", "que es esto", "que es eso", "que dice", "que pone", "que significa", "que marca",
            "de que color", "donde esta", "quien es", "quienes son", "que se ve", "que aparece", "que sale"
        )
        return frases.any { q.contains(it) } || empieza(listOf(
            "describ", "lee", "leer", "texto", "escena", "identific", "reconoc", "cuant", "analiz", "mira",
            "explica", "detall", "ocurr", "sucede", "letrero", "cartel", "etiqueta", "matricula", "placa"
        ))
    }
}

/**
 * **Las herramientas de editar lo que ya hay en el lienzo** (`edicion.rs` del PC): su nombre y sus
 * claves, para la selección. Sus esquemas vienen del catálogo del PC.
 */
object Edicion {
    data class Fila(val nombre: String, val claves: List<String>)

    const val SELECCIONAR = "seleccionar_en_lienzo"
    const val TRANSFORMAR = "transformar_elementos"
    const val ESTILO = "cambiar_estilo"
    const val TRAZAR = "trazar_a_mano"
    const val GEOMETRIA = "geometria_precisa"
    const val CORTAR = "cortar_linea"
    const val EXTENDER = "extender_linea"
    const val IMAGEN = "imagen_en_lienzo"

    val FILAS = listOf(
        Fila(SELECCIONAR, listOf("elige", "selecciona", "busca en el lienzo", "cual es", "que hay en el lienzo")),
        Fila(TRANSFORMAR, listOf(
            "mueve", "mover", "desplaza", "escala", "agranda", "achica", "encoge", "gira", "rota", "duplica", "copia el",
            "copia la", "borra", "elimina", "quita", "agrupa", "desagrupa", "voltea", "espejo", "al frente", "al fondo",
            "detras", "delante", "mas grande", "mas pequen"
        )),
        Fila(ESTILO, listOf(
            "color", "pinta de", "pintalo", "pintala", "rellena", "relleno", "grosor", "mas grueso", "mas fino",
            "discontinu", "punteado", "opacidad", "transparente", "cambia"
        )),
        Fila(TRAZAR, listOf(
            "subraya", "resalta", "marca", "rodea", "encierra", "tacha", "repasa", "a mano", "lapiz", "grafito",
            "resaltador", "marcador", "fosforito"
        )),
        Fila(GEOMETRIA, listOf(
            "recta", "circulo", "circunferencia", "arco", "perpendicular", "paralela", "radio", "centro", "angulo",
            "grados", "tres puntos", "exact", "segmento", "semicircunferencia", "semicirculo", "cuadrante", "sector",
            "cuerda", "diametro", "tangente", "marca", "angulo recto", "degradado", "punteada", "raya punto",
            "lamina", "geometria"
        )),
        Fila(CORTAR, listOf("corta", "cortar", "recorta la", "recorta el", "trim", "quita el trozo", "quita lo que sobra", "en el cruce", "interseccion")),
        Fila(EXTENDER, listOf("extiende", "extender", "alarga", "prolonga", "estira", "hasta que toque", "extend")),
        Fila(IMAGEN, listOf("pega", "pegar", "inserta", "recorta", "recortar", "foto", "imagen", "portapapeles", "captura"))
    )

    fun fila(nombre: String) = FILAS.firstOrNull { it.nombre == nombre }
}
