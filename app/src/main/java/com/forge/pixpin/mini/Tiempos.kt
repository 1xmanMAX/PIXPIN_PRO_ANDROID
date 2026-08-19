package com.forge.pixpin.mini

/**
 * Las mini-apps que miden tiempo: el cronómetro, el temporizador y la alarma.
 *
 * ## Por qué las tres juntas
 *
 * Porque son la misma cuenta mirada desde tres sitios. Un cronómetro es tiempo que sube
 * desde un instante; un temporizador, tiempo que baja hacia otro; y una alarma, un
 * instante al que se llega. Las tres se guardan igual y las tres se leen igual.
 *
 * ## Lo que se guarda es el instante, no el número que se ve
 *
 * Esta es **la** decisión de este fichero. Un cronómetro corriendo no guarda «llevo 42
 * segundos»: guarda cuándo se puso en marcha. Guardar el número obligaría a estar
 * escribiéndolo en disco todo el rato para que sobreviva a cerrar la aplicación —y aun
 * así se quedaría parado en cuanto el sistema matara el proceso—. Con el instante, el
 * número se calcula al mirarlo y **es correcto aunque nadie estuviera mirando**: el
 * teléfono puede estar apagado tres horas, que al volver el cronómetro dice las tres
 * horas. Es la diferencia entre medir el tiempo y contar fotogramas.
 *
 * ## El formato
 *
 * Líneas de `clave: valor` bajo el título, que es texto plano como el resto de las
 * mini-apps: se busca con el buscador de la conversación, se copia y se pega, y una
 * versión futura que no entienda una clave se la salta en vez de romperse.
 */

/** Un cronómetro: lo que ya llevaba acumulado y desde cuándo corre, si es que corre. */
data class Cronometro(
    /** Lo acumulado en las vueltas anteriores, en milisegundos. */
    val llevado: Long = 0L,
    /** Cuándo se puso en marcha esta vez. Null si está parado. */
    val desde: Long? = null,
    /** Las vueltas marcadas, en milisegundos desde el arranque. */
    val vueltas: List<Long> = emptyList()
) {
    val corriendo: Boolean get() = desde != null

    /** Cuánto lleva ahora mismo, mirándolo en el instante [ahora]. */
    fun transcurrido(ahora: Long): Long =
        llevado + (desde?.let { (ahora - it).coerceAtLeast(0L) } ?: 0L)
}

/** Un temporizador: cuánto se pidió y cuándo termina, si está en marcha. */
data class Temporizador(
    /** Lo que dura, en milisegundos. Es lo que se repite al volver a lanzarlo. */
    val duracion: Long = 5 * 60 * 1000L,
    /** El instante en que suena. Null si está parado. */
    val finEn: Long? = null
) {
    val corriendo: Boolean get() = finEn != null

    /**
     * Lo que falta. Cero cuando ya pasó su hora.
     *
     * No se deja bajar de cero a propósito: un temporizador vencido tiene que decir
     * «se acabó», no llevar la cuenta de lo tarde que vas.
     */
    fun restante(ahora: Long): Long =
        finEn?.let { (it - ahora).coerceAtLeast(0L) } ?: duracion

    fun vencido(ahora: Long): Boolean = finEn != null && ahora >= finEn
}

/** Una alarma: a qué hora, y si está puesta. */
data class Alarma(
    val hora: Int = 8,
    val minuto: Int = 0,
    val activa: Boolean = false
)

/** Leer y escribir las tres. Todo puro: se comprueba sin reloj y sin dispositivo. */
object Tiempos {

    // ---- El formato de clave y valor ------------------------------------

    private fun valores(documento: String): Map<String, String> =
        Cabecera.cuerpo(documento).lineSequence()
            .mapNotNull { linea ->
                val limpia = linea.trim().removePrefix("-").trim()
                val corte = limpia.indexOf(':')
                if (corte <= 0) return@mapNotNull null
                limpia.take(corte).trim().lowercase() to limpia.drop(corte + 1).trim()
            }
            .toMap()

    private fun documento(titulo: String, pares: List<Pair<String, String>>): String =
        Cabecera.linea(titulo) + pares.joinToString("\n") { "- ${it.first}: ${it.second}" }

    // ---- Cronómetro ------------------------------------------------------

    fun leerCronometro(documento: String): Cronometro {
        val v = valores(documento)
        return Cronometro(
            llevado = v["llevado"]?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L,
            desde = v["desde"]?.toLongOrNull(),
            vueltas = v["vueltas"].orEmpty().split(' ')
                .mapNotNull { it.toLongOrNull() }
        )
    }

    fun escribirCronometro(titulo: String, c: Cronometro): String = documento(
        titulo,
        listOfNotNull(
            "llevado" to c.llevado.toString(),
            c.desde?.let { "desde" to it.toString() },
            if (c.vueltas.isEmpty()) null else "vueltas" to c.vueltas.joinToString(" ")
        )
    )

    /** Arranca, si no estaba ya arrancado. Volver a arrancar no reinicia nada. */
    fun arrancar(c: Cronometro, ahora: Long): Cronometro =
        if (c.corriendo) c else c.copy(desde = ahora)

    /** Para y **guarda lo llevado**: si no, parar sería reiniciar sin avisar. */
    fun parar(c: Cronometro, ahora: Long): Cronometro =
        if (!c.corriendo) c else Cronometro(llevado = c.transcurrido(ahora), vueltas = c.vueltas)

    fun reiniciar(c: Cronometro): Cronometro = Cronometro()

    /** Marca una vuelta. Parado no marca nada: no hay nada que marcar. */
    fun vuelta(c: Cronometro, ahora: Long): Cronometro =
        if (!c.corriendo) c else c.copy(vueltas = c.vueltas + c.transcurrido(ahora))

    // ---- Temporizador ----------------------------------------------------

    fun leerTemporizador(documento: String): Temporizador {
        val v = valores(documento)
        return Temporizador(
            duracion = v["duracion"]?.toLongOrNull()?.coerceAtLeast(0L) ?: (5 * 60 * 1000L),
            finEn = v["finen"]?.toLongOrNull()
        )
    }

    fun escribirTemporizador(titulo: String, t: Temporizador): String = documento(
        titulo,
        listOfNotNull(
            "duracion" to t.duracion.toString(),
            t.finEn?.let { "finEn" to it.toString() }
        )
    )

    /** Lo lanza desde [ahora]. Con duración cero no se lanza: sonaría en el acto. */
    fun lanzar(t: Temporizador, ahora: Long): Temporizador =
        if (t.duracion <= 0L) t else t.copy(finEn = ahora + t.duracion)

    fun detener(t: Temporizador): Temporizador = t.copy(finEn = null)

    /** Cambia lo que dura. Estando en marcha, además lo relanza con la duración nueva. */
    fun conDuracion(t: Temporizador, duracion: Long, ahora: Long): Temporizador {
        val nueva = duracion.coerceIn(0L, TOPE_DE_DURACION)
        val cambiado = t.copy(duracion = nueva)
        return if (t.corriendo) lanzar(cambiado, ahora) else cambiado
    }

    // ---- Alarma ----------------------------------------------------------

    fun leerAlarma(documento: String): Alarma {
        val v = valores(documento)
        val hhmm = v["hora"].orEmpty().split(':')
        return Alarma(
            hora = hhmm.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 8,
            minuto = hhmm.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 0,
            activa = v["activa"] == "sí"
        )
    }

    fun escribirAlarma(titulo: String, a: Alarma): String = documento(
        titulo,
        listOf(
            "hora" to "${a.hora}:${a.minuto.toString().padStart(2, '0')}",
            "activa" to if (a.activa) "sí" else "no"
        )
    )

    // ---- Cómo se leen los tiempos ----------------------------------------

    /**
     * `1:05,3` mientras es corto y `12:04:05` cuando hay horas.
     *
     * Las décimas solo por debajo de una hora: en un cronómetro de cocina son lo que se
     * mira, y en uno de tres horas son un dígito que baila y no dice nada.
     */
    fun comoSeLee(ms: Long): String {
        val total = ms.coerceAtLeast(0L)
        val segundos = total / 1000
        val horas = segundos / 3600
        val minutos = (segundos % 3600) / 60
        val seg = segundos % 60
        val dosCifras = { n: Long -> n.toString().padStart(2, '0') }
        return if (horas > 0) {
            "$horas:${dosCifras(minutos)}:${dosCifras(seg)}"
        } else {
            "$minutos:${dosCifras(seg)},${(total % 1000) / 100}"
        }
    }

    /** Sin décimas, para lo que no corre: una duración elegida, una alarma. */
    fun comoSeLeeCorto(ms: Long): String {
        val segundos = ms.coerceAtLeast(0L) / 1000
        val horas = segundos / 3600
        val minutos = (segundos % 3600) / 60
        val seg = segundos % 60
        val dosCifras = { n: Long -> n.toString().padStart(2, '0') }
        return if (horas > 0) "$horas:${dosCifras(minutos)}:${dosCifras(seg)}"
        else "$minutos:${dosCifras(seg)}"
    }

    /** Más de un día no es un temporizador, es una cita: para eso está la alarma. */
    const val TOPE_DE_DURACION = 24 * 60 * 60 * 1000L
}
