package com.forge.pixpin.lecciones

/**
 * **«¿Qué aprendiste?» en una línea** (lo trae el PC, rediseño v2, 4-oct-2026; `rapida.rs`).
 *
 * La barra de arriba de la lista: se escribe (o se dicta) una frase y todo lo demás se rellena
 * solo, para cambiarlo con un toque si no acierta:
 * - los campos, repartidos como el dictado ([Dictado]): «pasó que…, porque…, la próxima vez…»;
 * - etiquetas, área, tipo y causas, del [Etiquetador] de siempre;
 * - la gravedad ([Etiquetador.proponerGravedad]);
 * - el proyecto: uno cuyo nombre sale en la frase o, si no, el de la lección que más se le parece.
 *
 * El resultado es una [Leccion] normal: el formato del archivo no cambia.
 */
object Rapida {

    /** Lo que se propone para una frase, antes de guardarla. [proyecto] es su id, si hay uno claro. */
    class Relleno(
        val propuesta: Etiquetador.Propuesta = Etiquetador.Propuesta(emptyList(), null, null, emptyList()),
        val gravedad: Int = 1,
        val proyecto: String? = null
    )

    /**
     * Lo que se propone para [frase]. [proyectos] son `(id, nombre)`; [deQuien] dice el proyecto
     * de cada lección por su id (para proponer el de la que más se parece).
     */
    fun rellenar(
        frase: String,
        aprendido: Etiquetador.Aprendido,
        indices: List<Buscador.Indice>,
        proyectos: List<Pair<String, String>>,
        deQuien: (String) -> String?
    ): Relleno {
        if (frase.isBlank()) return Relleno()
        val propuesta = Etiquetador.proponer(frase, aprendido)
        val gravedad = Etiquetador.proponerGravedad(frase, propuesta.tipo)
        val proyecto = proyectoPorNombre(frase, proyectos)
            ?: Buscador.parecidas(indices, frase, 0.3, 1).firstOrNull()?.let { deQuien(it.leccion.id) }
        return Relleno(propuesta, gravedad, proyecto)
    }

    /**
     * El proyecto cuyo nombre sale en la frase: todas sus palabras con significado, dos de ellas
     * o una larga están en ella. Entre varios, el que casa más.
     */
    fun proyectoPorNombre(frase: String, proyectos: List<Pair<String, String>>): String? {
        val mias = Texto.raices(frase)
        var mejor: Pair<String, Int>? = null
        for ((id, nombre) in proyectos) {
            val suyas = Texto.raices(nombre)
            if (suyas.isEmpty()) continue
            val casan = suyas.filter { it in mias }
            val comunes = casan.size
            // Una palabra larga basta (un sitio: «Miraflores»); una corta y común («obra») sola no.
            val vale = comunes == suyas.size || comunes >= 2 || casan.any { it.length >= 6 }
            if (vale && (mejor == null || comunes > mejor.second)) mejor = id to comunes
        }
        return mejor?.first
    }

    /**
     * La lección que sale de la frase con lo propuesto (y lo cambiado a mano encima: [area],
     * [gravedad]). [area] nula = la propuesta; vacía = sin área.
     */
    fun leccion(frase: String, id: String, ahora: Long, propuesta: Etiquetador.Propuesta, area: String?, gravedad: Int): Leccion {
        val escritas = Etiquetador.escritas(frase)
        val c = Dictado.repartir(Etiquetador.sinEtiquetas(frase))
        return Leccion(
            id = id,
            creada = ahora,
            titulo = c.titulo.trim(),
            quePaso = c.quePaso.trim(),
            porQue = c.porQue.trim(),
            // Si hace de título, no se repite.
            proxima = if (c.proxima != c.titulo) c.proxima.trim() else "",
            tipo = propuesta.tipo ?: Leccion.TIPO_LECCION,
            area = area ?: propuesta.area.orEmpty(),
            gravedad = gravedad.coerceIn(1, 3),
            etiquetas = escritas,
            etiquetasAuto = propuesta.etiquetas.filter { it !in escritas },
            causas = propuesta.causas
        )
    }
}

/**
 * **El repaso de hoy, de una en una** (lo trae el PC, v2; `lista/repaso.rs`): la cola, cuál va,
 * cuántas se contestaron y en qué paso está la de ahora. La respuesta escrita es para comparar
 * con lo apuntado y **no se guarda**: la lección no tiene dónde y su formato no cambia.
 */
class ColaDeRepaso(val cola: List<String>) {
    var i = 0
        private set
    /** Cuántas se contestaron (las saltadas no cuentan). */
    var hechas = 0
        private set

    /** El id de la de ahora; null al acabar. */
    val actual: String? get() = cola.getOrNull(i)

    /** Cuál va (desde 1) y de cuántas. */
    val progreso: Pair<Int, Int> get() = minOf(i + 1, cola.size) to cola.size

    /** A la siguiente. [contada]: si se contestó (y no se saltó). */
    fun pasar(contada: Boolean) {
        if (i < cola.size) {
            i++
            if (contada) hechas++
        }
    }

    companion object {
        /** En qué paso va: 1 pensar, 2 mirar (ya escribió algo), 3 contestar (respuesta a la vista). */
        fun paso(respuesta: String, mostrada: Boolean): Int = when {
            mostrada -> 3
            respuesta.isNotBlank() -> 2
            else -> 1
        }

        /** Lo que se pregunta: lo que pasó, o sus etiquetas, o el área, como siempre. */
        fun situacion(l: Leccion): String {
            if (l.quePaso.isNotBlank()) return l.quePaso
            val t = l.todasLasEtiquetas.joinToString(" ") { "#$it" }
            if (t.isNotEmpty()) return "Cuando $t…"
            if (l.area.isNotBlank()) return "Cuando ${l.area}…"
            return "Recuerda esta lección"
        }

        /** «Vuelve mañana», «Vuelve en 7 días». */
        fun cuandoVuelve(dias: Long): String = if (dias <= 1) "Vuelve mañana" else "Vuelve en $dias días"
    }
}
