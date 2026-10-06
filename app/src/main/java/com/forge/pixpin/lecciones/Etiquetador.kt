package com.forge.pixpin.lecciones

/**
 * **Las etiquetas que se ponen solas**, sin conexión y al momento.
 *
 * Tres fuentes, de más a menos fiable:
 * 1. Lo que uno escribió con `#` o dijo como «etiqueta obra» / «hashtag obra».
 * 2. **Lo que uno suele etiquetar así** ([aprender]): si las lecciones donde salía «encofrado»
 *    llevaban #losas, una nueva con «encofrado» propone #losas. Aprende de cada uno.
 * 3. Un diccionario de conceptos ([CONCEPTOS]) pensado para quien lo usa: estudiante de
 *    ingeniería, obra, estudio, trabajo y vida diaria. Cada concepto da una etiqueta y apunta a
 *    un área, así que «la losa se fisuró» propone #concreto y el área Construcción aunque no
 *    salga la palabra «construcción».
 *
 * También propone el tipo (error o acierto) y las causas por las palabras que se usan al contarlo
 * («no revisé», «me apuré», «supuse que»). Nada de esto se impone: sale como propuesta y se quita
 * con un toque, y lo quitado no vuelve ([Leccion.quitadas]).
 */
object Etiquetador {

    class Concepto(val etiqueta: String, val area: String, palabras: List<String>) {
        val raices: Set<String> = palabras.flatMap { Texto.raices(it) }.toSet()
    }

    private const val OBRA = "Construcción"
    private const val ESTUDIO = "Estudio"
    private const val TRABAJO = "Trabajo"
    private const val VIDA = "Vida diaria"

    val CONCEPTOS: List<Concepto> = listOf(
        Concepto("concreto", OBRA, listOf("concreto", "hormigón", "losa", "vaciado", "colado", "curado", "fraguado", "slump", "mezcla", "cemento", "fisura", "grieta", "fisuró", "agrietó")),
        Concepto("acero", OBRA, listOf("acero", "varilla", "fierro", "armado", "estribo", "traslape", "anclaje", "recubrimiento", "malla")),
        Concepto("encofrado", OBRA, listOf("encofrado", "cimbra", "puntal", "desencofrado", "formaleta", "molde")),
        Concepto("estructuras", OBRA, listOf("estructura", "viga", "columna", "zapata", "cimentación", "cimiento", "muro", "carga", "momento", "cortante", "deflexión", "sismo", "pórtico", "placa")),
        Concepto("suelos", OBRA, listOf("suelo", "excavación", "relleno", "compactación", "talud", "terreno", "nivel freático", "estudio de suelos", "geotecnia")),
        Concepto("soldadura", OBRA, listOf("soldadura", "soldar", "electrodo", "perno", "conexión metálica")),
        Concepto("instalaciones", OBRA, listOf("tubería", "eléctrica", "sanitaria", "desagüe", "agua", "cableado", "tablero", "instalación")),
        Concepto("seguridad", OBRA, listOf("seguridad", "epp", "casco", "arnés", "andamio", "caída", "accidente", "riesgo", "señalización", "lesión")),
        Concepto("planos", OBRA, listOf("plano", "escala", "cota", "detalle", "corte", "elevación", "dwg", "autocad", "revit", "lámina", "impresión", "imprimir")),
        Concepto("metrados", OBRA, listOf("metrado", "cómputo", "cantidad", "medición", "volumen", "área")),
        Concepto("presupuesto", OBRA, listOf("presupuesto", "costo", "precio", "cotización", "valorización", "partida", "adicional")),
        Concepto("materiales", OBRA, listOf("material", "ladrillo", "arena", "piedra", "agregado", "pintura", "madera", "proveedor", "pedido", "almacén")),
        Concepto("topografía", OBRA, listOf("topografía", "nivel", "replanteo", "estación total", "trazo", "eje")),
        Concepto("supervisión", OBRA, listOf("supervisión", "supervisor", "inspección", "residente", "maestro de obra", "cuaderno de obra", "contratista", "subcontrata", "obrero")),
        Concepto("calidad", OBRA, listOf("calidad", "ensayo", "prueba", "probeta", "control", "norma", "especificación", "tolerancia")),
        Concepto("cálculo", ESTUDIO, listOf("cálculo", "fórmula", "unidades", "ecuación", "integral", "derivada", "resultado", "decimal", "redondeo", "conversión")),
        Concepto("software", ESTUDIO, listOf("excel", "programa", "software", "sap2000", "etabs", "matlab", "python", "archivo", "guardar", "respaldo", "copia", "computadora", "laptop")),
        Concepto("exámenes", ESTUDIO, listOf("examen", "parcial", "final", "práctica", "prueba", "nota", "calificación", "estudiar", "repasar")),
        Concepto("tesis", ESTUDIO, listOf("tesis", "asesor", "capítulo", "bibliografía", "cita", "referencia", "sustentación", "investigación")),
        Concepto("entregas", ESTUDIO, listOf("entrega", "trabajo", "informe", "tarea", "plazo", "fecha límite", "profesor", "curso")),
        Concepto("reuniones", TRABAJO, listOf("reunión", "junta", "acta", "acuerdo", "llamada", "videollamada")),
        Concepto("comunicación", TRABAJO, listOf("correo", "mensaje", "whatsapp", "avisar", "aviso", "malentendido", "explicar", "preguntar", "confirmar", "por escrito")),
        Concepto("cliente", TRABAJO, listOf("cliente", "propietario", "jefe", "gerente", "compañero", "equipo", "contrato", "pago", "factura")),
        Concepto("tiempos", TRABAJO, listOf("tiempo", "retraso", "demora", "cronograma", "plazo", "puntual", "tarde", "atraso", "programación")),
        Concepto("documentos", TRABAJO, listOf("documento", "permiso", "licencia", "firma", "trámite", "expediente", "pdf", "versión")),
        Concepto("dinero", VIDA, listOf("dinero", "gasto", "ahorro", "deuda", "banco", "compra", "pagar", "préstamo")),
        Concepto("salud", VIDA, listOf("salud", "dormir", "sueño", "comida", "ejercicio", "médico", "cansancio", "estrés")),
        Concepto("relaciones", VIDA, listOf("familia", "amigo", "pareja", "padres", "promesa", "discusión", "confianza")),
        Concepto("viajes", VIDA, listOf("viaje", "transporte", "bus", "vuelo", "maleta", "tráfico"))
    )

    /** Palabras que delatan el tipo. */
    private val DE_ERROR = listOf("error", "equivoqué", "equivocación", "olvidé", "olvido", "falló", "falla", "mal", "problema", "no revisé", "perdí", "rompí", "se cayó", "rechazaron", "multa", "reclamo", "tuve que rehacer", "rehacer")
    private val DE_ACIERTO = listOf("funcionó", "salió bien", "acierto", "sirvió", "ahorré", "logré", "bien hecho", "buena idea", "resultó")

    private val DE_CAUSA: Map<String, List<String>> = mapOf(
        "Prisa" to listOf("prisa", "apuré", "apurado", "rápido", "corriendo", "última hora"),
        "No revisé" to listOf("no revisé", "sin revisar", "no verifiqué", "no comprobé", "no chequeé", "no leí"),
        "Comunicación" to listOf("no avisé", "no me avisaron", "no me dijeron", "malentendido", "no pregunté", "no entendí", "no quedó claro", "por escrito"),
        "No sabía" to listOf("no sabía", "desconocía", "no conocía", "primera vez", "nunca había"),
        "Supuse algo" to listOf("supuse", "asumí", "creí que", "pensé que", "di por hecho", "daba por hecho"),
        "Herramienta" to listOf("programa", "software", "se colgó", "se trabó", "excel", "autocad", "app", "batería", "impresora"),
        "Planificación" to listOf("no planifiqué", "sin planificar", "no calculé el tiempo", "a última hora", "dejé para", "cronograma"),
        "Cansancio" to listOf("cansado", "cansancio", "sueño", "desvelado", "agotado"),
        "Distracción" to listOf("distraído", "distracción", "celular", "me olvidé", "no presté atención")
    )

    /** Frases como expresiones de palabra entera: «mal» no puede saltar con «normal». */
    private fun frases(ps: List<String>): List<Regex> =
        ps.map { Regex("(?<![a-z0-9ñ])" + Regex.escape(Texto.normal(it)) + "(?![a-z0-9ñ])") }
    private fun dice(normal: String, rs: List<Regex>) = rs.any { it.containsMatchIn(normal) }
    private val ERROR_RX by lazy { frases(DE_ERROR) }
    private val ACIERTO_RX by lazy { frases(DE_ACIERTO) }
    private val CAUSA_RX by lazy { DE_CAUSA.mapValues { frases(it.value) } }

    private val HASHTAG = Regex("#([\\p{L}0-9_]{2,30})")
    private val ETIQUETA_DICHA = Regex("(?i)\\b(?:hashtag|etiqueta)\\s+([\\p{L}0-9_]{2,30})")

    class Propuesta(
        val etiquetas: List<String>,
        val area: String?,
        val tipo: String?,
        val causas: List<String>
    )

    /** Las etiquetas escritas a mano en el texto (#obra, «etiqueta obra»), sin el signo. */
    fun escritas(texto: String): List<String> =
        (HASHTAG.findAll(texto).map { it.groupValues[1] } + ETIQUETA_DICHA.findAll(texto).map { it.groupValues[1] })
            .map { it.lowercase() }.distinct().toList()

    /** El texto sin las etiquetas escritas, para guardarlo limpio. */
    fun sinEtiquetas(texto: String): String =
        ETIQUETA_DICHA.replace(HASHTAG.replace(texto, ""), "").replace(Regex("\\s{2,}"), " ").trim()

    /**
     * Lo que se propone para [texto]. [aprendido] es lo que sale de [aprender] con las lecciones
     * que ya hay; [quitadas], lo que uno ya rechazó.
     */
    fun proponer(texto: String, aprendido: Aprendido = Aprendido.VACIO, quitadas: Collection<String> = emptyList()): Propuesta {
        val normal = Texto.normal(texto)
        val raices = Texto.raices(texto).toSet()
        val porConcepto = CONCEPTOS.map { c -> c to raices.count { it in c.raices } }.filter { it.second > 0 }
            .sortedByDescending { it.second }
        val delUsuario = aprendido.proponer(raices)
        val fuera = quitadas.map { it.lowercase() }.toSet()
        val etiquetas = (escritas(texto) + delUsuario + porConcepto.map { it.first.etiqueta })
            .distinct().filter { it !in fuera }.take(6)
        val area = porConcepto.groupBy({ it.first.area }, { it.second }).mapValues { it.value.sum() }
            .maxByOrNull { it.value }?.key
        val tipo = when {
            dice(normal, ACIERTO_RX) -> Leccion.TIPO_ACIERTO
            dice(normal, ERROR_RX) -> Leccion.TIPO_ERROR
            else -> null
        }
        val causas = CAUSA_RX.filter { (_, rs) -> dice(normal, rs) }.keys.toList()
        return Propuesta(etiquetas, area, tipo, causas)
    }

    /**
     * **Lo que uno suele etiquetar.** Para cada etiqueta puesta a mano, qué raíces la acompañan;
     * una lección nueva que comparte dos o más raíces con las de una etiqueta la propone.
     */
    class Aprendido(private val raicesPorEtiqueta: Map<String, Map<String, Int>>) {
        fun proponer(raices: Set<String>): List<String> =
            raicesPorEtiqueta.mapNotNull { (etiqueta, cuenta) ->
                val comunes = raices.count { (cuenta[it] ?: 0) > 0 }
                if (comunes >= 2) etiqueta to comunes else null
            }.sortedByDescending { it.second }.map { it.first }.take(3)

        companion object { val VACIO = Aprendido(emptyMap()) }
    }

    fun aprender(lecciones: List<Leccion>): Aprendido {
        val mapa = HashMap<String, HashMap<String, Int>>()
        for (l in lecciones) {
            if (l.etiquetas.isEmpty()) continue
            val raices = Texto.raices(l.titulo + " " + l.quePaso + " " + l.porQue + " " + l.proxima).toSet()
            for (e in l.etiquetas) {
                val m = mapa.getOrPut(e) { HashMap() }
                for (r in raices) m[r] = (m[r] ?: 0) + 1
            }
        }
        return Aprendido(mapa)
    }

    /** Las etiquetas de concepto a las que apunta una palabra buscada: «obra» → las de Construcción. */
    fun conceptosDe(raiz: String): List<Concepto> = CONCEPTOS.filter { raiz in it.raices }

    /**
     * Palabras que delatan una lección grave o importante. **Lo añadió el PC** (v2, 4-oct-2026;
     * `proponer_gravedad` de `etiquetador.rs`, mismas listas): antes el teléfono no proponía la
     * gravedad y la dejaba en «Leve»; la barra rápida de la lista la rellena sola y se cambia
     * con un toque.
     */
    private val DE_GRAVE = listOf(
        "grave", "peligro", "peligroso", "accidente", "herido", "herida", "lesión", "me lastimé",
        "incendio", "inundación", "perdí todo", "lo perdí todo", "se perdió todo", "despido",
        "despidieron", "demanda", "denuncia", "multa", "hospital", "urgencias", "electrocutado",
        "electrocutó", "se derrumbó", "colapsó"
    )
    private val DE_IMPORTANTE = listOf(
        "importante", "rehacer", "tuve que rehacer", "repintar", "retraso", "se retrasó", "atraso",
        "costó", "caro", "dinero", "rechazaron", "reclamo", "resbala", "resbaló", "resbaladizo",
        "se cayó", "caída", "otra vez", "de nuevo", "volvió a pasar", "perdí", "se rompió", "rompí",
        "nota baja", "desaprobé"
    )
    private val GRAVE_RX by lazy { frases(DE_GRAVE) }
    private val IMPORTANTE_RX by lazy { frases(DE_IMPORTANTE) }

    /**
     * **La gravedad que se propone** para un texto: 3 si habla de algo grave (un accidente,
     * perderlo todo, una multa), 2 si de algo que costó (rehacer, un retraso, un resbalón) o es
     * un error, 1 si no. Palabras enteras: «gravedad» no es «grave».
     */
    fun proponerGravedad(textoEntero: String, tipo: String?): Int {
        val normal = Texto.normal(textoEntero)
        return when {
            dice(normal, GRAVE_RX) -> 3
            dice(normal, IMPORTANTE_RX) || tipo == Leccion.TIPO_ERROR -> 2
            else -> 1
        }
    }
}
