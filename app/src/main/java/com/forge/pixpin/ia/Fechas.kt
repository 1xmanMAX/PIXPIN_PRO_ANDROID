package com.forge.pixpin.ia

/**
 * **Fechas dichas como las dice la gente** («mañana a las 10», «el viernes», «20/10», «el 3 de
 * noviembre»), para las tareas del asistente (`fechas.rs` del PC, portado tal cual).
 *
 * El formato de las tareas no cambia: la fecha de una tarea es su marca de recordatorio
 * `⏰ AAAA-MM-DD HH:MM` en hora local, al final de lo que se ve (`Tareas.marcaDeHora`). Aquí solo se
 * pasa del español a ese momento, **sin reloj propio**: quien llama da el «ahora» local.
 *
 * Estricto a propósito: [interpretar] tiene que entender la frase ENTERA. Una fecha que no existe
 * («31 de febrero», «25:00») es un error con su motivo, nunca un día de al lado; y una que ya pasó,
 * también.
 */
data class Momento(val dia: Long, val minuto: Int) : Comparable<Momento> {
    override fun compareTo(other: Momento): Int =
        compareValuesBy(this, other, { it.dia }, { it.minuto })

    /** La marca de las tareas: `⏰ 2026-10-09 10:00`. */
    fun marca(): String {
        val (y, m, d) = Fechas.fechaDe(dia)
        return "⏰ %04d-%02d-%02d %02d:%02d".format(y, m, d, minuto / 60, minuto % 60)
    }

    /** «mañana 10:00», «hoy 17:30», «lun 20/10 9:00»: para las burbujas. */
    fun legible(ahora: Momento): String {
        val hora = "%02d:%02d".format(minuto / 60, minuto % 60)
        val d = when (dia - ahora.dia) {
            0L -> "hoy"
            1L -> "mañana"
            -1L -> "ayer"
            else -> {
                val (y, m, dd) = Fechas.fechaDe(dia)
                val sem = listOf("lun", "mar", "mié", "jue", "vie", "sáb", "dom")[Fechas.diaDeLaSemana(dia).toInt()]
                val ya = Fechas.fechaDe(ahora.dia).first
                if (y == ya) "$sem $dd/$m" else "$sem $dd/$m/$y"
            }
        }
        return "$d $hora"
    }

    companion object {
        /** El momento de unos milisegundos locales desde 1970. */
        fun deMsLocales(ms: Long): Momento {
            val dia = 86_400_000L
            return Momento(Math.floorDiv(ms, dia), (Math.floorMod(ms, dia) / 60_000).toInt())
        }

        /** Ahora en la hora local del teléfono. */
        fun ahora(): Momento {
            val ms = System.currentTimeMillis()
            return deMsLocales(ms + java.util.TimeZone.getDefault().getOffset(ms))
        }

        /** Lee una marca `⏰ AAAA-MM-DD HH:MM` al final de un texto. */
        fun deMarca(texto: String): Momento? {
            val i = texto.lastIndexOf('⏰').takeIf { it >= 0 } ?: return null
            val r = texto.substring(i + 1).trim()
            fun n(a: Int, b: Int) = if (r.length >= b) r.substring(a, b).toLongOrNull() else null
            val y = n(0, 4) ?: return null
            val mo = n(5, 7) ?: return null
            val d = n(8, 10) ?: return null
            val h = n(11, 13) ?: return null
            val mi = n(14, 16) ?: return null
            if (!Fechas.existe(y, mo, d) || h !in 0..23 || mi !in 0..59) return null
            return Momento(Fechas.diasDe(y, mo, d), (h * 60 + mi).toInt())
        }
    }
}

/** Por qué no vale una fecha (el texto va al usuario o al modelo). */
sealed class ErrorFecha(val frase: String) : Exception() {
    class NoEntiendo(f: String) : ErrorFecha(f)
    class NoExiste(f: String) : ErrorFecha(f)
    class Pasada(f: String) : ErrorFecha(f)

    override val message: String
        get() = when (this) {
            is NoEntiendo -> "no entiendo la fecha «$frase»; dila como «mañana a las 10», «el viernes» o «20/10/2026»"
            is NoExiste -> "la fecha «$frase» no existe"
            is Pasada -> "la fecha «$frase» ya pasó"
        }
}

object Fechas {
    /** La hora por omisión cuando solo se dice el día. */
    const val HORA_POR_OMISION = 9 * 60

    /** Los días desde 1970-01-01 de una fecha (Howard Hinnant). */
    fun diasDe(y0: Long, m: Long, d: Long): Long {
        val y = if (m <= 2) y0 - 1 else y0
        val era = Math.floorDiv(y, 400L)
        val yoe = y - era * 400
        val mp = (m + 9) % 12
        val doy = (153 * mp + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }

    /** Al revés: (año, mes, día). */
    fun fechaDe(dias: Long): Triple<Long, Long, Long> {
        val z = dias + 719_468
        val era = Math.floorDiv(z, 146_097L)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        return Triple(yoe + era * 400 + (if (m <= 2) 1 else 0), m, d)
    }

    fun existe(y: Long, m: Long, d: Long): Boolean = m in 1..12 && d >= 1 && fechaDe(diasDe(y, m, d)) == Triple(y, m, d)

    /** 0 = lunes ... 6 = domingo. */
    fun diaDeLaSemana(dia: Long): Long = Math.floorMod(dia + 3, 7L)

    private val MESES = listOf(
        "enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre"
    )
    private val DIAS = listOf("lunes", "martes", "miercoles", "jueves", "viernes", "sabado", "domingo")
    private val PALABRAS = listOf(
        "cero", "uno", "dos", "tres", "cuatro", "cinco", "seis", "siete", "ocho", "nueve", "diez", "once", "doce", "trece", "catorce", "quince"
    )

    private fun numero(p: String): Long? {
        p.toLongOrNull()?.let { return it }
        return when (p) {
            "un", "una" -> 1
            "mediodia" -> null
            else -> PALABRAS.indexOf(p).takeIf { it >= 0 }?.toLong()
        }
    }

    private fun mes(p0: String): Long? {
        val p = if (p0 == "setiembre") "septiembre" else p0
        return MESES.indexOfFirst { it == p || (p.length >= 3 && it.startsWith(p) && p.length <= it.length) }
            .takeIf { it >= 0 }?.let { it + 1L }
    }

    private fun diaSemana(p: String): Long? = DIAS.indexOf(p).takeIf { it >= 0 }?.toLong()

    private fun enUnidad(p: String, sig: String?, unidad: String?): Boolean =
        p == "en" && sig?.let { numero(it) } != null &&
            unidad in setOf("dia", "dias", "semana", "semanas", "hora", "horas")

    private fun relleno(p: String) = p in setOf(
        "para", "el", "al", "la", "de", "del", "este", "esta", "dia", "en", "a", "las", "los", "y", "sobre", "hacia", "antes"
    )

    /** **La fecha de una frase entera.** [ahora]: el momento local de ahora. */
    fun interpretar(frase: String, ahora: Momento): Momento {
        val original = frase.trim().trimEnd('.', ',', ';').trim()
        val q = Acciones.normalizar(original)
        // «5pm» y «5:30pm» se leen como «5 pm» y «5:30 pm» (18-oct-2026, como el PC).
        val palabras = q.split(Regex("[\\s,]")).filter { it.isNotEmpty() }.flatMap { p ->
            val n = p.removeSuffix("pm").takeIf { it != p } ?: p.removeSuffix("am").takeIf { it != p }
            if (n != null && n.isNotEmpty() && n.all { it in '0'..'9' || it == ':' }) listOf(n, p.substring(n.length)) else listOf(p)
        }
        if (palabras.isEmpty()) throw ErrorFecha.NoEntiendo(original)
        fun noEntiendo() = ErrorFecha.NoEntiendo(original)
        fun noExiste() = ErrorFecha.NoExiste(original)
        var dia: Long? = null
        var minutoLeido: Int? = null
        var sinAnio = false
        // Si el día salió de un número solo («el 20»): del mes en curso, o del siguiente si ya pasó.
        var sinMes = false
        var i = 0
        var tarde: Boolean? = null
        fun ponerDia(d: Long) {
            if (dia != null) throw noEntiendo()
            dia = d
        }
        while (i < palabras.size) {
            val p = palabras[i]
            val sig = palabras.getOrNull(i + 1)
            if (p == "hoy") {
                ponerDia(ahora.dia)
            } else if (p == "pasado" && sig == "manana") {
                ponerDia(ahora.dia + 2); i++
            } else if (p == "manana") {
                val franja = i >= 2 && palabras[i - 1] == "la" && palabras[i - 2] == "de" && minutoLeido != null
                val porLa = i >= 2 && palabras[i - 1] == "la" && palabras[i - 2] == "por"
                if (franja || porLa) {
                    tarde = false
                    if (porLa && minutoLeido == null) minutoLeido = 9 * 60
                } else ponerDia(ahora.dia + 1)
            } else if (p == "tarde" || p == "noche") {
                val franjaDe = i >= 2 && palabras[i - 1] == "la" && palabras[i - 2] == "de"
                if (franjaDe && minutoLeido != null) {
                    tarde = true
                } else if (minutoLeido == null) {
                    minutoLeido = if (p == "tarde") 17 * 60 else 20 * 60
                    tarde = null
                    if (i >= 1 && palabras[i - 1] == "esta" && dia == null) dia = ahora.dia
                } else throw noEntiendo()
            } else if (p == "mediodia") {
                if (minutoLeido != null) throw noEntiendo()
                minutoLeido = 12 * 60
            } else if (p == "am" || p == "pm") {
                tarde = p == "pm"
            } else if (p == "por" && sig == "la") {
                // Lo hace la siguiente palabra.
            } else if (diaSemana(p) != null) {
                val ds = diaSemana(p)!!
                val hoy = diaDeLaSemana(ahora.dia)
                var salto = Math.floorMod(ds - hoy, 7L)
                if (salto == 0L) salto = 7
                if (sig == "que" && palabras.getOrNull(i + 2) == "viene") i += 2
                ponerDia(ahora.dia + salto)
            } else if (p == "proximo" || p == "proxima" || p == "siguiente") {
                // Lo pone la palabra que sigue.
            } else if (p == "semana") {
                val antes = i >= 1 && palabras[i - 1] in setOf("proxima", "siguiente")
                val despues = sig == "que" && palabras.getOrNull(i + 2) == "viene"
                if (!(antes || despues)) throw noEntiendo()
                if (despues) i += 2
                val hoy = diaDeLaSemana(ahora.dia)
                ponerDia(ahora.dia + (7 - hoy))
            } else if ((p == "dentro" && sig == "de") || enUnidad(p, sig, palabras.getOrNull(i + 2))) {
                val j = if (p == "dentro") i + 2 else i + 1
                val n = palabras.getOrNull(j)?.let { numero(it) } ?: throw noEntiendo()
                when (palabras.getOrNull(j + 1) ?: throw noEntiendo()) {
                    "dia", "dias" -> ponerDia(ahora.dia + n)
                    "semana", "semanas" -> ponerDia(ahora.dia + 7 * n)
                    "hora", "horas" -> {
                        val total = ahora.dia * 1440 + ahora.minuto + 60 * n
                        ponerDia(Math.floorDiv(total, 1440L))
                        if (minutoLeido != null) throw noEntiendo()
                        minutoLeido = Math.floorMod(total, 1440L).toInt()
                    }
                    else -> throw noEntiendo()
                }
                i = j + 1
            } else if (fechaNumerica(p, ahora) != null) {
                val (y, m, d) = fechaNumerica(p, ahora)!!
                if (!existe(y.first, m, d)) throw noExiste()
                sinAnio = sinAnio || y.second
                ponerDia(diasDe(y.first, m, d))
            } else if (':' in p || (p.endsWith('h') && p.length <= 3 && p.dropLast(1).toIntOrNull()?.let { it >= 0 } == true)) {
                val (h, mi) = if (':' in p) {
                    val (a, b) = p.split(':', limit = 2)
                    (a.toLongOrNull() ?: throw noEntiendo()) to (b.trimEnd('h').toLongOrNull() ?: throw noEntiendo())
                } else (p.dropLast(1).toLongOrNull() ?: throw noEntiendo()) to 0L
                if (h !in 0..23 || mi !in 0..59) throw noExiste()
                if (minutoLeido != null) throw noEntiendo()
                minutoLeido = (h * 60 + mi).toInt()
            } else if (numero(p) != null) {
                val n = numero(p)!!
                val trasLas = (i >= 1 && palabras[i - 1] in setOf("las", "la") && i >= 2 && palabras[i - 2] == "a") ||
                    (i >= 1 && palabras[i - 1] == "las")
                val mesDetras = when {
                    sig == "de" && palabras.getOrNull(i + 2) != null -> mes(palabras[i + 2])
                    sig != null -> mes(sig)
                    else -> null
                }
                if (mesDetras != null && !trasLas) {
                    i += if (sig == "de") 2 else 1
                    var y: Long? = null
                    val a = palabras.getOrNull(i + 1)
                    val b = palabras.getOrNull(i + 2)
                    val xa = a?.toLongOrNull()?.takeIf { it >= 1000 }
                    val xb = b?.toLongOrNull()?.takeIf { it >= 1000 }
                    if (xa != null) { y = xa; i += 1 }
                    else if ((a == "de" || a == "del") && xb != null) { y = xb; i += 2 }
                    val anio = y ?: fechaDe(ahora.dia).first
                    if (!existe(anio, mesDetras, n)) throw noExiste()
                    sinAnio = sinAnio || y == null
                    ponerDia(diasDe(anio, mesDetras, n))
                } else if (!trasLas && dia == null && i >= 1 && palabras[i - 1] in setOf("el", "dia") && n in 1L..31L) {
                    // «el 20 (a las 9)»: el día 20 del mes en curso (18-oct-2026, como el PC).
                    val (y, m, _) = fechaDe(ahora.dia)
                    val elDia = (0 until 12).map { k -> (y + (m - 1 + k) / 12) to ((m - 1 + k) % 12 + 1) }
                        .firstOrNull { (yy, mm) -> existe(yy, mm, n) }
                        ?.let { (yy, mm) -> diasDe(yy, mm, n) } ?: throw noExiste()
                    sinMes = true
                    ponerDia(elDia)
                } else if (trasLas || sig in setOf("de", "en", "am", "pm", "y") || i + 1 == palabras.size) {
                    if (n !in 0..24) throw noExiste()
                    if (minutoLeido != null) throw noEntiendo()
                    var minuto = (n * 60).toInt()
                    if (sig == "y") {
                        when (palabras.getOrNull(i + 2)) {
                            "media" -> minuto += 30
                            "cuarto" -> minuto += 15
                            else -> throw noEntiendo()
                        }
                        i += 2
                    }
                    if (minuto >= 24 * 60) throw noExiste()
                    minutoLeido = minuto
                } else throw noEntiendo()
            } else if (mes(p) != null && i >= 1 && palabras[i - 1] == "de" && dia == null) {
                throw noEntiendo()
            } else if (relleno(p) || p == "que" || p == "viene") {
                // nada
            } else throw noEntiendo()
            i++
        }
        if (dia == null && minutoLeido == null) throw noEntiendo()
        var minuto = minutoLeido ?: HORA_POR_OMISION
        if (tarde == true && minuto < 12 * 60) minuto += 12 * 60
        else if (tarde == false && minuto >= 12 * 60 && minuto < 13 * 60) minuto -= 12 * 60
        var d = dia ?: if (minuto > ahora.minuto) ahora.dia else ahora.dia + 1
        if (sinMes && d < ahora.dia) {
            // «el 3» dicho el 18: el 3 del mes que viene (el primero que lo tenga).
            val (y, m, dd) = fechaDe(d)
            (1..12).map { k -> (y + (m - 1 + k) / 12) to ((m - 1 + k) % 12 + 1) }
                .firstOrNull { (yy, mm) -> existe(yy, mm, dd) }
                ?.let { (yy, mm) -> d = diasDe(yy, mm, dd) }
        }
        if (sinAnio && d < ahora.dia) {
            val (y, m, dd) = fechaDe(d)
            if (existe(y + 1, m, dd)) d = diasDe(y + 1, m, dd)
        }
        val r = Momento(d, minuto)
        if (r < ahora) throw ErrorFecha.Pasada(original)
        return r
    }

    /** «2026-10-20», «20/10», «20/10/2026», «20-10-26», «20.10»: ((año, sin año), mes, día). */
    private fun fechaNumerica(p: String, ahora: Momento): Triple<Pair<Long, Boolean>, Long, Long>? {
        val sep = when {
            '/' in p -> '/'
            '-' in p -> '-'
            '.' in p -> '.'
            else -> return null
        }
        val trozos = p.split(sep)
        val n = trozos.map { it.toLongOrNull() ?: return null }
        val este = fechaDe(ahora.dia).first
        return when {
            n.size == 3 && trozos[0].length == 4 -> Triple(n[0] to false, n[1], n[2])
            n.size == 3 -> Triple((if (n[2] < 100) 2000 + n[2] else n[2]) to false, n[1], n[0])
            n.size == 2 -> Triple(este to true, n[1], n[0])
            else -> null
        }
    }

    /**
     * **Separa la fecha del final de una frase**: «llamar al banco mañana a las 10» → («llamar al
     * banco», mañana 10:00). Lanza [ErrorFecha] si la cola parece una fecha que no existe.
     */
    fun separar(frase: String, ahora: Momento): Pair<String, Momento?> {
        val palabras = frase.split(Regex("\\s+")).filter { it.isNotEmpty() }
        for (i in 1 until palabras.size) {
            val cola = palabras.subList(i, palabras.size).joinToString(" ")
            val cabeza = palabras.subList(0, i).joinToString(" ")
            val empieza = Acciones.normalizar(palabras[i])
            val parece = empieza in setOf(
                "para", "el", "al", "hoy", "manana", "pasado", "a", "en", "dentro", "este", "esta", "la", "proximo", "proxima", "antes", "sobre"
            ) || diaSemana(empieza) != null || empieza.firstOrNull()?.let { it in '0'..'9' } == true
            if (!parece) continue
            try {
                val m = interpretar(cola, ahora)
                return cabeza.trimEnd(',', ' ') to m
            } catch (e: ErrorFecha.NoEntiendo) {
                // sigue
            }
        }
        return frase.trim() to null
    }
}
