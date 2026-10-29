package com.forge.pixpin.jev

/**
 * **Fechas y horas dichas en voz alta**, resueltas en código (29-oct-2026; porte de
 * `crates/pixpin-typesafe/src/fechas.rs` del PC, `50e87978`).
 *
 * La regla de TypeSafe: las fechas no se le preguntan a Jev. El código busca en la frase **todas** las
 * expresiones que podrían ser un día o una hora ([buscar]: «el viernes», «mañana a las 9», «el martes a
 * las 3», «el 15 de noviembre», «la semana que viene», «a fin de mes»...) y las resuelve contra la fecha
 * de la reunión. Jev solo elige **cuál** es el plazo o la cita (una Choice sobre estas expresiones, con
 * un Noul de «hay alguna»): «la cotización **del viernes**» nombra un tema, no un plazo.
 *
 * Lo que decide el código, sin preguntar a nadie (lo mismo que el PC):
 *
 * - «el viernes» (o «el próximo viernes», «el viernes que viene»): el siguiente viernes después de hoy;
 *   dicho un viernes, el de dentro de una semana. «este viernes»: el de esta semana (hoy, si es viernes).
 * - «el martes de la semana que viene»: el martes de la semana siguiente. «el lunes pasado»: el anterior.
 * - «la semana que viene»: el lunes de la semana siguiente, **aproximada**.
 * - «a fin de mes»: el último día del mes.
 * - «el 15»: el próximo día 15 (este mes si no pasó, si no el siguiente); «el 15 de noviembre»: ese día
 *   (el año que viene si ya pasó).
 * - «a las 3» sin «de la mañana/tarde»: de 1 a 7, por la tarde (15:00); de 8 a 12, tal cual.
 * - Una hora sola («a las 5»): hoy a esa hora.
 * - «mañana» es un día salvo en «por la mañana», «esta mañana»...
 *
 * No es [com.forge.pixpin.ia.Fechas] (la del asistente, estricta con la frase entera): esta busca
 * dentro de una frase dicha y nunca falla, como la del PC.
 */
object FechasDichas {

    /** Un día del calendario. */
    data class Fecha(val anio: Int, val mes: Int, val dia: Int) : Comparable<Fecha> {
        override fun compareTo(other: Fecha): Int = compareValuesBy(this, other, { it.anio }, { it.mes }, { it.dia })

        /** Los días desde el 1-ene-1970 (el algoritmo de H. Hinnant). */
        fun dias(): Long {
            val y = anio.toLong() - (if (mes <= 2) 1 else 0)
            val era = Math.floorDiv(y, 400L)
            val yoe = y - era * 400
            val m = mes.toLong()
            val doy = (153 * (m + if (m > 2) -3 else 9) + 2) / 5 + dia - 1
            val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
            return era * 146_097 + doe - 719_468
        }

        fun mas(n: Long): Fecha = deDias(dias() + n)

        /** 0 = lunes ... 6 = domingo. */
        fun diaSemana(): Int = Math.floorMod(dias() + 3, 7L).toInt() // el 1-ene-1970 fue jueves (3)

        /** Los días de esta a [otra] (negativo si [otra] es antes). */
        fun hasta(otra: Fecha): Long = otra.dias() - dias()

        fun ultimoDelMes(): Fecha = copy(dia = diasDelMes(anio, mes))

        /** «2026-10-26». */
        override fun toString(): String = "%04d-%02d-%02d".format(anio, mes, dia)

        companion object {
            /** Una fecha que existe, o null. */
            fun nueva(anio: Int, mes: Int, dia: Int): Fecha? =
                if (mes in 1..12 && dia >= 1 && dia <= diasDelMes(anio, mes)) Fecha(anio, mes, dia) else null

            /** De «2026-10-26». */
            fun deTexto(s: String): Fecha? {
                val p = s.trim().split('-', limit = 3)
                if (p.size != 3) return null
                val a = p[0].toIntOrNull() ?: return null
                val m = p[1].toIntOrNull() ?: return null
                val d = p[2].toIntOrNull() ?: return null
                return nueva(a, m, d)
            }

            /** La fecha de tantos días desde el 1-ene-1970. */
            fun deDias(z0: Long): Fecha {
                val z = z0 + 719_468
                val era = Math.floorDiv(z, 146_097L)
                val doe = z - era * 146_097
                val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
                val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
                val mp = (5 * doy + 2) / 153
                val dia = (doy - (153 * mp + 2) / 5 + 1).toInt()
                val mes = (if (mp < 10) mp + 3 else mp - 9).toInt()
                val anio = (yoe + era * 400 + (if (mes <= 2) 1 else 0)).toInt()
                return Fecha(anio, mes, dia)
            }

            /** Hoy, en la hora local de este aparato. */
            fun hoy(): Fecha {
                val d = java.time.LocalDate.now()
                return Fecha(d.year, d.monthValue, d.dayOfMonth)
            }
        }
    }

    /** Una hora del día. */
    data class Hora(val h: Int, val m: Int) : Comparable<Hora> {
        override fun compareTo(other: Hora): Int = compareValuesBy(this, other, { it.h }, { it.m })

        /** «15:00». */
        override fun toString(): String = "%02d:%02d".format(h, m)
    }

    /** **Un momento dicho**: el día, la hora si se dijo, y si es aproximado («la semana que viene»). */
    data class Momento(val fecha: Fecha, val hora: Hora?, val aproximado: Boolean) {
        /** «mar 2026-10-27 15:00 (aprox.)», como el `Display` del PC. */
        override fun toString(): String = buildString {
            append(DIAS_CORTOS[fecha.diaSemana()]).append(' ').append(fecha)
            hora?.let { append(' ').append(it) }
            if (aproximado) append(" (aprox.)")
        }
    }

    /** **Una expresión de la frase** que podría ser un día o una hora: las palabras tal como se dijeron y lo que significan. */
    data class Expresion(val texto: String, val momento: Momento)

    private val DIAS_SEMANA = listOf("lunes", "martes", "miercoles", "jueves", "viernes", "sabado", "domingo")
    private val DIAS_CORTOS = listOf("lun", "mar", "mié", "jue", "vie", "sáb", "dom")
    private val MESES = listOf(
        "enero", "febrero", "marzo", "abril", "mayo", "junio",
        "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre"
    )

    private fun diasDelMes(anio: Int, mes: Int): Int = when (mes) {
        1, 3, 5, 7, 8, 10, 12 -> 31
        4, 6, 9, 11 -> 30
        else -> if ((anio % 4 == 0 && anio % 100 != 0) || anio % 400 == 0) 29 else 28
    }

    /** Una palabra: dónde está (en el original) y cómo se compara (sin mayúsculas ni tildes). */
    private class Pal(val ini: Int, val fin: Int, val n: String)

    internal fun sinTilde(c: Char): Char = when (c) {
        'á', 'à' -> 'a'
        'é', 'è' -> 'e'
        'í', 'ì' -> 'i'
        'ó', 'ò' -> 'o'
        'ú', 'ù', 'ü' -> 'u'
        'ñ' -> 'n'
        else -> c
    }

    private fun digito(c: Char) = c in '0'..'9'

    /** Las palabras de la frase. «10:30» y «15/11» quedan en una. */
    private fun palabras(s: String): List<Pal> {
        val v = ArrayList<Pal>()
        var i = 0
        while (i < s.length) {
            if (!s[i].isLetterOrDigit()) { i++; continue }
            val ini = i
            var j = i
            while (j < s.length) {
                val c = s[j]
                val union = (c == ':' || c == '/') && j > i && digito(s[j - 1]) && j + 1 < s.length && digito(s[j + 1])
                if (c.isLetterOrDigit() || union) j++ else break
            }
            val n = s.substring(ini, j).lowercase().map(::sinTilde).joinToString("")
            v += Pal(ini, j, n)
            i = j
        }
        return v
    }

    private fun es(p: List<Pal>, i: Int, w: String) = i in p.indices && p[i].n == w

    private fun sigue(p: List<Pal>, i: Int, ws: List<String>) = ws.withIndex().all { (k, w) -> es(p, i + k, w) }

    private fun numero(w: String): Int? {
        if (w.isNotEmpty() && w.all(::digito)) return w.toIntOrNull()
        return when (w) {
            "una", "uno", "primero" -> 1
            "dos" -> 2
            "tres" -> 3
            "cuatro" -> 4
            "cinco" -> 5
            "seis" -> 6
            "siete" -> 7
            "ocho" -> 8
            "nueve" -> 9
            "diez" -> 10
            "once" -> 11
            "doce" -> 12
            else -> null
        }
    }

    private fun diaSemana(w: String): Int? = DIAS_SEMANA.indexOf(w).takeIf { it >= 0 }

    private fun mes(w: String): Int? = MESES.indexOf(w).takeIf { it >= 0 }?.plus(1)

    private class EnFecha(val fin: Int, val fecha: Fecha, val aproximado: Boolean)

    /** Un día que empieza en la palabra [i]: hasta dónde llega (exclusivo), el día y si es aproximado. */
    private fun fechaEn(p: List<Pal>, i: Int, hoy: Fecha): EnFecha? {
        if (i !in p.indices) return null
        val w = p[i].n
        if (w == "hoy") return EnFecha(i + 1, hoy, false)
        if (sigue(p, i, listOf("pasado", "manana"))) return EnFecha(i + 2, hoy.mas(2), false)
        if (w == "manana") {
            val antes = if (i >= 1) p[i - 1].n else null
            if (antes !in setOf("la", "esta", "por", "de", "en", "cada")) return EnFecha(i + 1, hoy.mas(1), false)
            return null
        }
        // «la semana que viene», «la próxima semana».
        var j = if (w == "la") i + 1 else i
        for (f in listOf(listOf("semana", "que", "viene"), listOf("proxima", "semana"), listOf("semana", "proxima"))) {
            if (sigue(p, j, f)) {
                val lunes = hoy.mas(7L - hoy.diaSemana())
                return EnFecha(j + f.size, lunes, true)
            }
        }
        // «a fin de mes», «a final de mes».
        j = if (w == "a") i + 1 else i
        if (sigue(p, j, listOf("fin", "de", "mes")) || sigue(p, j, listOf("final", "de", "mes"))) {
            return EnFecha(j + 3, hoy.ultimoDelMes(), false)
        }
        // «el (próximo) viernes (que viene)», «este viernes».
        j = i
        var este = false
        if (w == "el" || w == "este" || w == "esta") {
            este = w != "el"
            j++
        }
        if (es(p, j, "proximo")) j++
        val d = if (j in p.indices) diaSemana(p[j].n) else null
        if (d != null) {
            j++
            val hd = hoy.diaSemana()
            if (sigue(p, j, listOf("de", "la", "semana", "que", "viene")) || sigue(p, j, listOf("de", "la", "proxima", "semana"))) {
                val lunes = hoy.mas(7L - hd)
                val n = if (es(p, j + 2, "semana")) 5 else 4
                return EnFecha(j + n, lunes.mas(d.toLong()), false)
            }
            if (es(p, j, "pasado")) {
                // «el lunes pasado»: el anterior (dicho un lunes, el de hace una semana).
                var atras = Math.floorMod((hd - d).toLong(), 7L)
                if (atras == 0L) atras = 7
                return EnFecha(j + 1, hoy.mas(-atras), false)
            }
            if (sigue(p, j, listOf("que", "viene"))) j += 2
            var delta = Math.floorMod((d - hd).toLong(), 7L)
            if (delta == 0L && !este) delta = 7
            return EnFecha(j, hoy.mas(delta), false)
        }
        // «15/11».
        if ('/' in w) {
            val dd = w.substringBefore('/').toIntOrNull() ?: return null
            val mm = w.substringAfter('/').toIntOrNull() ?: return null
            return proximoDia(hoy, dd, mm)?.let { EnFecha(i + 1, it, false) }
        }
        // «el 15», «el 15 de noviembre», «15 de noviembre».
        val conEl = w == "el"
        j = if (conEl) i + 1 else i
        if (j !in p.indices) return null
        val x = p[j].n
        val n = (if (x.isNotEmpty() && (x.all(::digito) || x == "primero")) numero(x) else null) ?: return null
        if (n !in 1..31) return null
        if (es(p, j + 1, "de")) {
            val m = if (j + 2 in p.indices) mes(p[j + 2].n) else null
            if (m != null) return proximoDia(hoy, n, m)?.let { EnFecha(j + 3, it, false) }
        }
        if (conEl) return proximoDia(hoy, n, null)?.let { EnFecha(j + 1, it, false) }
        return null
    }

    /** El próximo día [d] (del mes [m] si se dice), desde hoy incluido. */
    private fun proximoDia(hoy: Fecha, d: Int, m: Int?): Fecha? {
        if (m != null) {
            val f = Fecha.nueva(hoy.anio, m, d) ?: return null
            return if (f >= hoy) f else Fecha.nueva(hoy.anio + 1, m, d)
        }
        var a = hoy.anio
        var mm = hoy.mes
        repeat(3) {
            Fecha.nueva(a, mm, d)?.takeIf { it >= hoy }?.let { return it }
            mm++
            if (mm > 12) { mm = 1; a++ }
        }
        return null
    }

    /** Una hora que empieza en la palabra [i]: hasta dónde llega y la hora. */
    private fun horaEn(p: List<Pal>, i: Int): Pair<Int, Hora>? {
        if (sigue(p, i, listOf("al", "mediodia")) || sigue(p, i, listOf("a", "mediodia"))) return (i + 2) to Hora(12, 0)
        val j = if (sigue(p, i, listOf("a", "las")) || sigue(p, i, listOf("a", "la"))) i + 2 else return null
        if (j !in p.indices) return null
        val w = p[j].n
        var h: Int
        var m: Int
        if (':' in w) {
            h = w.substringBefore(':').toIntOrNull() ?: return null
            m = w.substringAfter(':').toIntOrNull() ?: return null
        } else {
            h = numero(w) ?: return null
            m = 0
        }
        if (h > 23 || m > 59) return null
        var k = j + 1
        if (sigue(p, k, listOf("y", "media"))) {
            m = 30; k += 2
        } else if (sigue(p, k, listOf("y", "cuarto"))) {
            m = 15; k += 2
        } else if (sigue(p, k, listOf("menos", "cuarto"))) {
            h = if (h == 1) 12 else h - 1
            m = 45; k += 2
        }
        if (sigue(p, k, listOf("de", "la", "manana"))) {
            k += 3
        } else if (sigue(p, k, listOf("de", "la", "tarde")) || sigue(p, k, listOf("de", "la", "noche"))) {
            if (h < 12) h += 12
            k += 3
        } else {
            if (sigue(p, k, listOf("en", "punto"))) k += 2
            if (h in 1..7) h += 12
        }
        return k to Hora(h, m)
    }

    /**
     * **Todas las expresiones de día u hora de la frase**, en orden, resueltas contra [hoy] (el día de
     * la reunión). Un día seguido de una hora («el martes a las 3») o una hora seguida de un día («a
     * las 3 el martes») es una sola expresión.
     */
    fun buscar(frase: String, hoy: Fecha): List<Expresion> {
        val p = palabras(frase)
        val v = ArrayList<Expresion>()
        var i = 0
        while (i < p.size) {
            var fin = -1
            var fecha: Fecha? = null
            var hora: Hora? = null
            var aproximado = false
            val f = fechaEn(p, i, hoy)
            if (f != null) {
                // «mañana por la mañana a las 9»: lo de «por la mañana» se salta.
                var k = f.fin
                if (sigue(p, k, listOf("por", "la"))) k += 3
                val h = horaEn(p, k)
                if (h != null) { fin = h.first; hora = h.second } else fin = f.fin
                fecha = f.fecha
                aproximado = f.aproximado
            } else {
                val h = horaEn(p, i)
                if (h != null) {
                    val g = fechaEn(p, h.first, hoy)
                    hora = h.second
                    if (g != null) { fin = g.fin; fecha = g.fecha; aproximado = g.aproximado } else { fin = h.first; fecha = hoy }
                }
            }
            if (fecha != null) {
                v += Expresion(frase.substring(p[i].ini, p[fin - 1].fin), Momento(fecha, hora, aproximado))
                i = fin
            } else i++
        }
        return v
    }
}
