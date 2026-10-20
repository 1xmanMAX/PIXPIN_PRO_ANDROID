package com.forge.pixpin.ia

/**
 * **Cuándo piensa el asistente** (PC `pixpin-ia/src/complejidad.rs`, 15-oct-2026; el usuario: «que esté
 * apagado por defecto pero que piense en tareas complejas»). En «Automático» se piensa **solo si la
 * tarea es compleja**, y eso lo decide [clasificar]: reglas fijas sobre el texto, sin modelo, las mismas
 * para el modelo local, Claude y DeepSeek. «Siempre» y «Nunca» mandan.
 *
 * Compleja es, por orden: que lo pida («piensa», «paso a paso»; «sin pensar» la apaga), resolver un
 * problema, planificar o analizar, una hoja o un documento enteros, varios pasos encadenados, o un
 * pedido de más de [LARGO] palabras. Sin Android.
 */
object Complejidad {
    enum class Motivo { PEDIDO, PROBLEMA, PLANIFICAR, ENTERO, PASOS, LARGO }

    const val LARGO = 60

    /** **Si piensa este turno.** [ajuste]: true siempre, false nunca, null automático. */
    fun decidir(ajuste: Boolean?, pedido: String): Boolean = ajuste ?: (clasificar(pedido) != null)

    fun clasificar(pedido: String): Motivo? {
        val q = Acciones.normalizar(pedido)
        val palabras = q.split(Regex("[^\\p{L}\\p{N}%^]+")).filter { it.isNotEmpty() }
        fun tiene(frases: List<String>) = frases.any { q.contains(it) }
        fun es(lista: List<String>) = palabras.any { it in lista }
        if (tiene(NO_PENSAR)) return null
        if (es(PENSAR) || tiene(PENSAR_FRASES)) return Motivo.PEDIDO
        val cifras = palabras.count { it.firstOrNull()?.isDigit() == true && it.first() in '0'..'9' }
        val cuentas = '=' in q || q.any { it in "^√∫²³" }
        val deCiencias = tiene(CIENCIAS)
        if (empiezaPor(palabras, RESOLVER)) return Motivo.PROBLEMA
        if (empiezaPor(palabras, CALCULAR) && (deCiencias || cuentas || palabras.size > 14)) return Motivo.PROBLEMA
        val pregunta = es(listOf("cuanto", "cuanta", "cuantos", "cuantas", "cuando", "cual", "cuales", "donde")) || '?' in q
        if (cifras >= 2 && pregunta && palabras.size >= 12 && (deCiencias || tiene(UNIDADES))) return Motivo.PROBLEMA
        if (empiezaPor(palabras, PLANIFICAR) || tiene(PLANIFICAR_FRASES)) return Motivo.PLANIFICAR
        if (tiene(ENTEROS)) return Motivo.ENTERO
        val ordenes = palabras.count { esOrden(it) }
        val encadena = tiene(CONECTORES)
        val numerada = q.lines().count { l -> val t = l.trimStart(); t.firstOrNull()?.let { it in '0'..'9' } == true && t.getOrNull(1)?.let { it in ".)-" } == true }
        if (ordenes >= 3 || (ordenes >= 2 && encadena && palabras.size >= 20) || numerada >= 3) return Motivo.PASOS
        if (palabras.size > LARGO) return Motivo.LARGO
        return null
    }

    private fun empiezaPor(palabras: List<String>, raices: List<String>) = palabras.any { p -> raices.any { p.startsWith(it) } }

    private val PEGADOS = listOf("", "lo", "la", "los", "las", "le", "les", "me", "nos", "te")

    /** Una orden: el verbo tal cual o con un pronombre pegado («ponle», «dibujalo»). */
    fun esOrden(p: String) = ORDENES.any { v -> p.startsWith(v) && p.removePrefix(v) in PEGADOS }

    private val NO_PENSAR = listOf("sin pensar", "no pienses", "no lo pienses", "no razones", "contesta rapido", "responde rapido", "rapidito")
    private val PENSAR = listOf("piensa", "piensalo", "pensalo", "piensatelo", "razona", "razonalo", "razonando", "reflexiona", "think")
    private val PENSAR_FRASES = listOf("paso a paso", "pasito a pasito", "en profundidad", "detenidamente", "con calma", "step by step", "think hard", "piensa bien")
    private val RESOLVER = listOf("resuelv", "resolver", "demuestr", "demostrar", "despej", "halla", "deduc", "determina")
    private val CALCULAR = listOf("calcul", "encuentra", "obten", "simplific", "factoriz", "deriva", "integra")
    private val CIENCIAS = listOf(
        "fuerza", "aceleracion", "velocidad", "masa", "energia", "potencia", "friccion", "rozamiento", "inclinado",
        "proyectil", "pendulo", "polea", "circuito", "resistencia", "voltaje", "corriente", "presion", "caudal",
        "momento", "torque", "integral", "derivada", "limite", "ecuacion", "sistema de", "matriz", "vector",
        "probabilidad", "logaritmo", "volumen", "perimetro", " area ", "hipotenusa", "angulo", "trigonometr",
        "interes compuesto", "estadistic", "promedio", "varianza", "viga", "esfuerzo", "deflexion"
    )
    private val UNIDADES = listOf(
        "km/h", "m/s", " km", " kg", " cm", " mm", " m ", " m.", " m,", " s ", "segundo", "minuto", " hora", "metro",
        "kilo", "litro", "newton", "julio", "watt", "voltio", "ohm", "grados", "°", "%", "soles", "euros", "dolares"
    )
    private val PLANIFICAR = listOf("planific", "planea", "analiz", "compara", "disena", "optimiz", "evalua", "justifica")
    private val PLANIFICAR_FRASES = listOf(
        "haz un plan", "hazme un plan", "un plan de", "plan para", "estrategia", "organiza mi", "organizame", "pros y contras", "ventajas y desventajas"
    )
    private val ENTEROS = listOf(
        "toda la hoja", "hoja entera", "hoja completa", "todas las hojas", "todo el plano", "plano entero",
        "plano completo", "todo el lienzo", "lienzo entero", "todo el documento", "documento entero",
        "documento completo", "todas las paginas", "toda la pagina", "pagina entera", "todo el pdf", "pdf entero",
        "todo el libro", "libro entero", "todo el excel", "toda la tabla", "tabla entera"
    )
    private val CONECTORES = listOf(" luego ", " despues ", "a continuacion", "por ultimo", "finalmente", "primero ", "y entonces", "al final")
    private val ORDENES = listOf(
        "dibuja", "pinta", "pon", "anade", "agrega", "escribe", "calcula", "haz", "crea", "abre", "busca", "cambia",
        "mueve", "borra", "quita", "colorea", "traza", "marca", "resume", "traduce", "convierte", "guarda", "envia",
        "manda", "copia", "ordena", "genera", "rellena", "grafica", "exporta", "inserta", "dime", "explica", "lee",
        "rota", "gira", "agranda", "achica", "alinea", "une", "separa", "duplica", "renombra"
    )
}
