package com.forge.pixpin.motor

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * **Cómo se leen y cómo se escriben los números de una tabla.**
 *
 * Va aparte de las fórmulas porque lo usan tres sitios que tienen que coincidir al dígito:
 * la tabla de la aplicación, la página web que se exporta —que lo repite en JavaScript, ver
 * [VisorTabla]— y el pegado desde Excel. Si la aplicación enseña `0.3333333333` y la página
 * `0.333333333333`, quien reciba el archivo pensará que las cuentas no cuadran. Por eso cada
 * cuenta de aquí está escrita de forma que su gemela en JS dé lo mismo, y
 * `CalculoEnNodeTest` lo comprueba corriendo las dos.
 *
 * ## Leer: lo que se pega de una hoja en español o en inglés
 *
 * Un número llega escrito como lo escribió quien lo tecleó: `1,234.50`, `1.234,50`, `S/ 120`,
 * `15%`, `(300)`. [numero] entiende todos. Con los dos separadores, **el último es el
 * decimal**; con uno solo repetido, es de miles; con una sola coma seguida de tres cifras
 * también es de miles (`1,500` son mil quinientos, como en Perú y en Estados Unidos), y con
 * cualquier otra cosa es decimal (`12,5`).
 *
 * ## Escribir: como la vista «General» de Excel
 *
 * Diez cifras significativas, sin ceros de cola, y en notación científica lo que no cabe.
 * Los enteros van enteros hasta 10^15.
 */
object CalculoFormato {

    /** Días entre el 30-dic-1899 —el cero de las fechas de Excel— y el 1-ene-1970. */
    const val DIAS_HASTA_1970 = 25569

    private val MONEDA = Regex("^(S/\\.?|US\\$|\\$|€|£)\\s*")
    private val CIFRAS = Regex("^[0-9][0-9.,]*([eE][+-]?[0-9]+)?$|^[.,][0-9]+([eE][+-]?[0-9]+)?$")
    private val FECHA_DMA = Regex("^([0-9]{1,2})/([0-9]{1,2})/([0-9]{4})$")
    private val FECHA_AMD = Regex("^([0-9]{4})-([0-9]{1,2})-([0-9]{1,2})$")

    /** Un número escrito a mano, o null si no lo es. Ver la cabecera. */
    fun numero(texto: String): Double? {
        var t = texto.trim()
        if (t.isEmpty()) return null
        var negativo = false
        if (t.length > 2 && t.startsWith("(") && t.endsWith(")")) {
            negativo = true
            t = t.substring(1, t.length - 1).trim()
        }
        if (t.startsWith("-")) { negativo = !negativo; t = t.substring(1).trim() }
        else if (t.startsWith("+")) t = t.substring(1).trim()
        t = MONEDA.replace(t, "")
        var porCiento = false
        if (t.endsWith("%")) { porCiento = true; t = t.substring(0, t.length - 1).trim() }
        t = t.replace(" ", "").replace(" ", "").replace(" ", "")
        if (t.startsWith("-")) { negativo = !negativo; t = t.substring(1) }
        if (!CIFRAS.matches(t)) return null
        val e = t.indexOfFirst { it == 'e' || it == 'E' }
        var cuerpo = if (e >= 0) t.substring(0, e) else t
        val exponente = if (e >= 0) t.substring(e) else ""
        val puntos = cuerpo.count { it == '.' }
        val comas = cuerpo.count { it == ',' }
        if (puntos > 0 && comas > 0) {
            if (cuerpo.lastIndexOf('.') > cuerpo.lastIndexOf(',')) {
                cuerpo = cuerpo.replace(",", "")
                if (puntos > 1) return null
            } else {
                cuerpo = cuerpo.replace(".", "")
                if (comas > 1) return null
                cuerpo = cuerpo.replace(',', '.')
            }
        } else if (comas > 0) {
            val i = cuerpo.indexOf(',')
            cuerpo = if (comas > 1) {
                cuerpo.replace(",", "")
            } else {
                val antes = i
                val despues = cuerpo.length - i - 1
                if (despues == 3 && antes in 1..3 && !cuerpo.startsWith("0")) cuerpo.replace(",", "")
                else cuerpo.replace(',', '.')
            }
        } else if (puntos > 1) {
            cuerpo = cuerpo.replace(".", "")
        }
        if (cuerpo.startsWith(".")) cuerpo = "0$cuerpo"
        if (cuerpo.endsWith(".")) cuerpo = cuerpo.substring(0, cuerpo.length - 1)
        if (cuerpo.isEmpty()) return null
        val v = (cuerpo + exponente).toDoubleOrNull() ?: return null
        var r = if (porCiento) v / 100 else v
        if (negativo) r = -r
        return if (r.isFinite()) r else null
    }

    /** Una fecha escrita `d/m/aaaa` o `aaaa-m-d`, como número de serie de Excel. */
    fun fecha(texto: String): Double? {
        val t = texto.trim()
        val dma = FECHA_DMA.matchEntire(t)
        val amd = FECHA_AMD.matchEntire(t)
        val (d, m, a) = when {
            dma != null -> Triple(dma.groupValues[1].toInt(), dma.groupValues[2].toInt(), dma.groupValues[3].toInt())
            amd != null -> Triple(amd.groupValues[3].toInt(), amd.groupValues[2].toInt(), amd.groupValues[1].toInt())
            else -> return null
        }
        if (m !in 1..12 || d < 1 || d > diasDelMes(a, m)) return null
        return serial(a, m, d)
    }

    private fun diasDelMes(a: Int, m: Int): Int = when (m) {
        2 -> if ((a % 4 == 0 && a % 100 != 0) || a % 400 == 0) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }

    /**
     * El número de serie de una fecha. Meses y días fuera de rango **se desbordan** hacia
     * delante o atrás, como hace `FECHA` en Excel y `Date.UTC` en JavaScript.
     */
    fun serial(anio: Int, mes: Int, dia: Int): Double {
        val base = java.time.LocalDate.of(anio, 1, 1).plusMonths((mes - 1).toLong()).plusDays((dia - 1).toLong())
        return aSerie(base.year.toLong(), base.monthValue.toLong(), base.dayOfMonth.toLong()).toDouble()
    }

    /**
     * Año, mes y día de un número de serie, con el 29-feb-1900 de Excel (el 60). Los negativos,
     * que Excel no tiene, siguen el calendario de verdad hacia atrás.
     */
    fun partes(serial: Double): IntArray {
        val s = Math.floor(serial).toLong()
        if (s >= 0) return fechaDeSerie(s)
        val d = java.time.LocalDate.ofEpochDay(s - DIAS_HASTA_1970)
        return intArrayOf(d.year, d.monthValue, d.dayOfMonth)
    }

    // ---- Fechas como el PC (`formula/fecha.rs`) ----

    private fun diasCiviles(a0: Long, m: Long, d: Long): Long {
        val a = if (m <= 2) a0 - 1 else a0
        val era = Math.floorDiv(a, 400L)
        val yoe = Math.floorMod(a, 400L)
        val mp = (m + 9) % 12
        val doy = (153 * mp + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }

    private fun civilDeDias(z0: Long): LongArray {
        val z = z0 + 719_468
        val era = Math.floorDiv(z, 146_097L)
        val doe = Math.floorMod(z, 146_097L)
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val a = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        return longArrayOf(if (m <= 2) a + 1 else a, m, d)
    }

    private const val BASE = -25_569L

    /** El serial de Excel del día [a]-[m]-[d] (sistema 1900, con su 29-feb-1900). */
    fun aSerie(a: Long, m: Long, d: Long): Long {
        val dias = diasCiviles(a, m, d) - BASE
        return if (dias < 61) dias - 1 else dias
    }

    /** Año, mes y día del serial [s], o null si es negativo. El 0 es el `0/1/1900`. */
    fun desdeSerie(s: Long): LongArray? = when {
        s < 0 -> null
        s == 0L -> longArrayOf(1900, 1, 0)
        s < 60 -> civilDeDias(BASE + s + 1)
        s == 60L -> longArrayOf(1900, 2, 29)
        else -> civilDeDias(BASE + s)
    }

    /**
     * **Un texto con forma de fecha**, como serial: tres partes con `/`, `-` o `.`, de una a cuatro
     * cifras; con el año de cuatro cifras delante (`2000-01-01`) o detrás y el día primero
     * (`1/1/2000`), y que el día exista. Es `serie_de_texto` del PC: lo usan `VALOR`, `AÑO`…
     */
    fun serieDeTexto(texto: String): Double? {
        val t = texto.trim()
        val partes = t.split('/', '-', '.')
        if (partes.size != 3 || partes.any { it.isEmpty() || it.length > 4 }) return null
        val n = partes.map { it.toLongOrNull() ?: return null }
        val (a, m, d) = when {
            partes[0].length == 4 -> Triple(n[0], n[1], n[2])
            partes[2].length == 4 -> Triple(n[2], n[1], n[0])
            else -> return null
        }
        if (m !in 1..12 || d !in 1..31 || a !in 1900..9999) return null
        val s = aSerie(a, m, d)
        val vuelta = desdeSerie(s) ?: return null
        return if (vuelta[0] == a && vuelta[1] == m && vuelta[2] == d) s.toDouble() else null
    }

    /** `dd/mm/aaaa`, y la hora si la lleva. */
    fun textoDeFecha(serial: Double): String {
        if (!serial.isFinite() || serial < -657434 || serial > 2958465) return ERROR_NUM
        val p = partes(serial)
        val base = dos(p[2]) + "/" + dos(p[1]) + "/" + p[0]
        val minutos = Math.round((serial - Math.floor(serial)) * 1440).toInt()
        if (minutos <= 0 || minutos >= 1440) return base
        return base + " " + dos(minutos / 60) + ":" + dos(minutos % 60)
    }

    private fun dos(n: Int) = if (n < 10) "0$n" else "$n"

    const val ERROR_NUM = "#¡NUM!"

    /** Un número como lo enseña la vista «General». Ver la cabecera. */
    fun general(d: Double): String {
        if (!d.isFinite()) return ERROR_NUM
        if (d == 0.0) return "0"
        val a = Math.abs(d)
        val signo = if (d < 0) "-" else ""
        if (a < 1e15 && a == Math.floor(a)) return signo + a.toLong().toString()
        if (a >= 1e15 || a < 1e-9) {
            val bd = BigDecimal(a).round(MathContext(6, RoundingMode.HALF_EVEN))
            val exp = bd.precision() - bd.scale() - 1
            var mant = bd.movePointLeft(exp).toPlainString()
            if (mant.contains('.')) mant = mant.trimEnd('0').trimEnd('.')
            val e = Math.abs(exp)
            return signo + mant + "E" + (if (exp < 0) "-" else "+") + (if (e < 10) "0$e" else "$e")
        }
        // Mitad al par sobre el valor binario exacto, como `format!("{a:.9e}")` del PC.
        val bd = BigDecimal(a).round(MathContext(10, RoundingMode.HALF_EVEN))
        var s = bd.toPlainString()
        if (s.contains('.')) s = s.trimEnd('0').trimEnd('.')
        return signo + s
    }

    /** `f64::round` de Rust para un número no negativo: la mitad, hacia arriba. */
    private fun redondo(a: Double): Double = if (a >= 4.5e15) a else Math.round(a).toDouble()

    /**
     * Redondea como `REDONDEAR` (modo 0), `REDONDEAR.MAS` (1) y `REDONDEAR.MENOS` (-1), con el
     * algoritmo del PC (`mates::redondear_numero`): lo que está a una milmillonésima de un entero
     * es ese entero, y en `REDONDEAR` lo que está a una milmillonésima de una mitad es esa mitad;
     * así 2,675 da 2,68 aunque en binario sea 2,67499999…
     */
    fun redondear(x: Double, decimales: Int, modo: Int = 0): Double {
        if (x == 0.0 || !x.isFinite()) return x
        val d = decimales.coerceIn(-400, 400)
        val f = Math.pow(10.0, minOf(Math.abs(d), 300).toDouble())
        val esc = if (d >= 0) x * f else x / f
        if (!esc.isFinite()) return x
        var a = Math.abs(esc)
        // Si ya está a un suspiro de un entero, es ese entero.
        val entero = redondo(a)
        if (Math.abs(a - entero) <= 1e-9 * maxOf(a, 1.0)) a = entero
        val r = when (modo) {
            1 -> Math.ceil(a)
            -1 -> Math.floor(a)
            else -> {
                // Y a un suspiro de una mitad, es esa mitad.
                val doble = a * 2
                if (Math.abs(doble - redondo(doble)) <= 1e-9 * maxOf(doble, 1.0)) a = redondo(doble) / 2
                redondo(a)
            }
        }
        val conSigno = Math.copySign(r, x)
        return if (d >= 0) conSigno / f else conSigno * f
    }

    // ---- TEXTO(valor; formato), como el PC (`formula/formato.rs`) ----

    /**
     * **`TEXTO`** de un número ya leído: la primera sección del formato, `General`, formatos de
     * número (`#,##0.00`, `0%`, `000`, texto entre comillas, `\x`, `_x`) y de fecha y hora
     * (`dd/mm/aaaa hh:mm`, `mmmm`, `dddd`, `AM/PM`), con los nombres en español. Devuelve el
     * texto o el error (`#¡VALOR!` para notación científica o condiciones, `#¡NUM!` para una
     * fecha fuera de rango).
     */
    fun darFormato(n: Double, formatoEntero: String): Pair<String?, String?> {
        val formato = formatoEntero.substringBefore(';')
        if (!n.isFinite()) return null to ERROR_NUM
        if ((formato.contains('[') || formato.contains('E') || formato.contains('e')) && !formato.contains('"') && tieneCientifica(formato)) {
            return null to Calculadora.ERROR_VALOR
        }
        if (formato.equals("general", ignoreCase = true) || formato.isEmpty()) return general(n) to null
        val marcadores = formato.any { it == '0' || it == '#' }
        if (!marcadores && esFormatoDeFecha(formato)) {
            return fechaConFormato(n, formato)?.let { it to null } ?: (null to ERROR_NUM)
        }
        return numeroConFormato(n, formato) to null
    }

    private fun tieneCientifica(f: String) =
        f.contains("E+") || f.contains("E-") || f.contains("e+") || f.contains("e-") || f.contains('[')

    private fun esFormatoDeFecha(f: String): Boolean {
        var comillas = false
        for (c in f) {
            if (c == '"') comillas = !comillas
            else if (!comillas && c.lowercaseChar() in "dmyahs") return true
        }
        return false
    }

    private fun numeroConFormato(n: Double, formato: String): String {
        val ch = formato.toCharArray()
        val prefijo = StringBuilder()
        val region = StringBuilder()
        val sufijo = StringBuilder()
        var porciento = false
        var i = 0
        var estado = 0
        while (i < ch.size) {
            val c = ch[i]
            val literal: String? = when (c) {
                '"' -> {
                    var fin = -1
                    for (k in i + 1 until ch.size) if (ch[k] == '"') { fin = k; break }
                    if (fin >= 0) { val t = String(ch, i + 1, fin - i - 1); i = fin + 1; t }
                    else { val t = String(ch, i + 1, ch.size - i - 1); i = ch.size; t }
                }
                '\\', '_' -> {
                    val t = if (i + 1 < ch.size) (if (c == '_') " " else ch[i + 1].toString()) else ""
                    i += 2
                    t
                }
                '%' -> { porciento = true; i++; "%" }
                '0', '#', '?', '.', ',' -> null
                else -> { i++; c.toString() }
            }
            if (literal != null) {
                if (estado == 0) prefijo.append(literal) else { estado = 2; sufijo.append(literal) }
            } else {
                if (estado == 2) sufijo.append(c) else { estado = 1; region.append(c) }
                i++
            }
        }
        val reg = region.toString()
        val punto = reg.indexOf('.')
        val ent = if (punto >= 0) reg.substring(0, punto) else reg
        val dec = if (punto >= 0) reg.substring(punto + 1) else ""
        val miles = ent.contains(',')
        val minEnteros = ent.count { it == '0' }
        val decimales = dec.count { it == '0' || it == '#' || it == '?' }
        val minDecimales = dec.count { it == '0' }
        val valor = if (porciento) n * 100 else n
        val redondeado = redondear(valor, decimales, 0)
        val negativo = redondeado < 0
        val cuerpo = BigDecimal(Math.abs(redondeado)).setScale(decimales, RoundingMode.HALF_EVEN).toPlainString()
        val p = cuerpo.indexOf('.')
        var parteEnt = if (p >= 0) cuerpo.substring(0, p) else cuerpo
        var parteDec = if (p >= 0) cuerpo.substring(p + 1) else ""
        while (parteDec.length > minDecimales && parteDec.endsWith('0')) parteDec = parteDec.dropLast(1)
        if (minEnteros == 0 && parteEnt == "0" && (decimales > 0 || reg.contains('#'))) parteEnt = ""
        while (parteEnt.length < minEnteros) parteEnt = "0$parteEnt"
        if (miles) {
            val sb = StringBuilder()
            for ((k, c) in parteEnt.withIndex()) {
                if (k > 0 && (parteEnt.length - k) % 3 == 0) sb.append(',')
                sb.append(c)
            }
            parteEnt = sb.toString()
        }
        val s = StringBuilder()
        if (negativo) s.append('-')
        s.append(prefijo).append(parteEnt)
        if (parteDec.isNotEmpty()) s.append('.').append(parteDec)
        s.append(sufijo)
        return s.toString()
    }

    private val MESES = listOf("enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto",
        "septiembre", "octubre", "noviembre", "diciembre")
    private val DIAS_DE_LA_SEMANA = listOf("domingo", "lunes", "martes", "miércoles", "jueves", "viernes", "sábado")

    /** Un trozo de un formato de fecha: [tipo] `a m d h s n`(minutos) `p`(AM/PM) `l`(literal). */
    private class Trozo(var tipo: Char, val n: Int, val texto: String = "")

    private fun fechaConFormato(n: Double, formato: String): String? {
        if (n < 0.0 || n > 2_958_465.999_999) return null
        val ch = formato.toCharArray()
        val trozos = ArrayList<Trozo>()
        var i = 0
        while (i < ch.size) {
            val c = ch[i]
            val bajo = c.lowercaseChar().takeIf { c.code < 128 } ?: c
            if (String(ch, i, ch.size - i).uppercase().startsWith("AM/PM")) { trozos += Trozo('p', 0); i += 5; continue }
            var corrida = 0
            while (i + corrida < ch.size && (ch[i + corrida].let { if (it.code < 128) it.lowercaseChar() else it }) == bajo) corrida++
            when (bajo) {
                'y', 'a' -> trozos += Trozo('a', corrida)
                'm' -> trozos += Trozo('m', corrida)
                'd' -> trozos += Trozo('d', corrida)
                'h' -> trozos += Trozo('h', corrida)
                's' -> trozos += Trozo('s', corrida)
                else -> {
                    when (c) {
                        '"' -> {
                            var fin = -1
                            for (k in i + 1 until ch.size) if (ch[k] == '"') { fin = k; break }
                            if (fin >= 0) { trozos += Trozo('l', 0, String(ch, i + 1, fin - i - 1)); i = fin + 1 }
                            else { trozos += Trozo('l', 0, String(ch, i + 1, ch.size - i - 1)); i = ch.size }
                        }
                        '\\' -> { if (i + 1 < ch.size) trozos += Trozo('l', 0, ch[i + 1].toString()); i += 2 }
                        else -> { trozos += Trozo('l', 0, c.toString()); i++ }
                    }
                    continue
                }
            }
            i += corrida
        }
        // `m` o `mm` son minutos justo detrás de una hora o delante de unos segundos (sin contar literales).
        for (k in trozos.indices) {
            if (trozos[k].tipo != 'm') continue
            val antes = (k - 1 downTo 0).map { trozos[it] }.firstOrNull { it.tipo != 'l' }
            val despues = (k + 1 until trozos.size).map { trozos[it] }.firstOrNull { it.tipo != 'l' }
            if (antes?.tipo == 'h' || despues?.tipo == 's') trozos[k].tipo = 'n'
        }
        var dia = Math.floor(n).toLong()
        var segundos = Math.round((n - Math.floor(n)) * 86_400.0)
        if (segundos >= 86_400) { segundos -= 86_400; dia++ }
        val f = desdeSerie(dia) ?: return null
        val (a, m, d) = Triple(f[0], f[1].toInt(), f[2])
        val semana = Math.floorMod(dia - 1, 7L).toInt()
        val hh = segundos / 3600
        val mm = segundos / 60 % 60
        val ss = segundos % 60
        val conAmPm = trozos.any { it.tipo == 'p' }
        fun dos(x: Long) = if (x < 10) "0$x" else "$x"
        val s = StringBuilder()
        for (t in trozos) when (t.tipo) {
            'a' -> s.append(if (t.n <= 2) dos(a % 100) else a.toString().padStart(4, '0'))
            'm' -> s.append(when (t.n) { 1 -> m.toString(); 2 -> dos(m.toLong()); 3 -> MESES[m - 1].take(3); else -> MESES[m - 1] })
            'd' -> s.append(when (t.n) { 1 -> d.toString(); 2 -> dos(d); 3 -> DIAS_DE_LA_SEMANA[semana].take(3); else -> DIAS_DE_LA_SEMANA[semana] })
            'h' -> { val h = if (conAmPm) (hh + 11) % 12 + 1 else hh; s.append(if (t.n >= 2) dos(h) else h.toString()) }
            'n' -> s.append(if (t.n >= 2) dos(mm) else mm.toString())
            's' -> s.append(if (t.n >= 2) dos(ss) else ss.toString())
            'p' -> s.append(if (hh < 12) "AM" else "PM")
            else -> s.append(t.texto)
        }
        return s.toString()
    }

    // ---- El formato de número de una celda (el `nf` de su estilo) ----

    /**
     * **Un número con el formato de su celda**, con el mismo texto que el PC. Allí el pin de hoja
     * pasa la tabla al visor de Excel con los formatos integrados (`tabla_xlsx.rs`): `numero` es el
     * 4 (`#,##0.00`), `moneda` el propio `"S/" #,##0.00;-"S/" #,##0.00`, `porcentaje` el 10
     * (`0.00%`) y `fecha` el 14, la fecha corta, `dd/mm/yyyy` en el equipo del autor. Separadores
     * del español del Perú (`Regional::es_pe` de `pixpin-hojas`): punto decimal y coma de miles.
     * [moneda] es el símbolo del aparato (allí, el de Windows).
     */
    fun conFormatoDeCelda(v: Double, nf: FormatoDeNumero, moneda: String = MONEDA_POR_DEFECTO): String {
        if (!v.isFinite()) return ERROR_NUM
        return when (nf) {
            // Un negativo que se queda en cero al redondear va sin signo (una sección); la moneda
            // lleva su sección de negativos, que pone el «-» siempre.
            FormatoDeNumero.NUMERO -> conMiles(v, 2).let { (if (v < 0 && it.any { c -> c in '1'..'9' }) "-" else "") + it }
            FormatoDeNumero.MONEDA -> (if (v < 0) "-" else "") + moneda + " " + conMiles(v, 2)
            FormatoDeNumero.PORCENTAJE -> {
                val (e, f) = digitos(v * 100, 2)
                (if (v < 0 && (e != "0" || f.any { it != '0' })) "-" else "") + e + "." + f + "%"
            }
            FormatoDeNumero.FECHA -> fechaCorta(v)
        }
    }

    /** El símbolo cuando el aparato no dice ninguno: el del Perú, el del autor. */
    const val MONEDA_POR_DEFECTO = "S/"

    /** `1,234.50`: [dec] decimales con el redondeo de Excel ([digitos]) y coma cada tres cifras. */
    private fun conMiles(v: Double, dec: Int): String {
        val (e, f) = digitos(v, dec)
        val sb = StringBuilder()
        for ((i, ch) in e.withIndex()) {
            if (i > 0 && (e.length - i) % 3 == 0) sb.append(',')
            sb.append(ch)
        }
        return if (dec > 0) "$sb.$f" else sb.toString()
    }

    /**
     * **Las cifras de |v| con [dec] decimales, como las redondea Excel**: primero a quince cifras
     * significativas y luego la mitad hacia arriba (2,675 con dos decimales da 2,68). Es
     * `digitos` de `crates/pixpin-hojas/src/formato.rs`, cifra a cifra. Devuelve la parte entera
     * y la decimal.
     */
    fun digitos(v: Double, dec: Int): Pair<String, String> {
        val a = Math.abs(v)
        if (a == 0.0) return "0" to "0".repeat(dec)
        // Lo mismo que `format!("{v:.14e}")` en Rust: quince cifras significativas, mitad al par
        // sobre el valor binario exacto (BigDecimal de un double es exacto).
        val bd = BigDecimal(a).round(MathContext(15, RoundingMode.HALF_EVEN))
        val cifras = bd.unscaledValue().toString().padEnd(15, '0').take(15).map { it - '0' }
        val punto = bd.precision() - bd.scale()
        val corte = punto + dec
        val d = ArrayList<Int>()
        if (corte > 0) for (k in 0 until corte) d += cifras.getOrElse(k) { 0 }
        val siguiente = if (corte < 0) 0 else cifras.getOrElse(corte) { 0 }
        if (siguiente >= 5) {
            var k = d.size
            while (true) {
                if (k == 0) { d.add(0, 1); break }
                k--
                if (d[k] == 9) d[k] = 0 else { d[k]++; break }
            }
        }
        while (d.size < dec + 1) d.add(0, 0)
        val ent = d.subList(0, d.size - dec).joinToString("").trimStart('0').ifEmpty { "0" }
        val frac = d.subList(d.size - dec, d.size).joinToString("")
        return ent to frac
    }

    /**
     * `dd/mm/yyyy` del número de serie, como el formato 14 del visor del PC: redondeado al segundo,
     * con el 29-feb-1900 que no existió (el serial 60) y almohadillas fuera de las fechas de Excel.
     */
    fun fechaCorta(v: Double): String {
        if (!(v >= 0.0 && v <= 2_958_466.0)) return "#".repeat(11)
        val total = Math.round(v * 86400.0).toDouble()
        val dias = Math.floor(total / 86400.0).toLong()
        val p = fechaDeSerie(dias)
        return dos(p[2]) + "/" + dos(p[1]) + "/" + p[0]
    }

    /**
     * Año, mes y día del serial **entero** [dias] de Excel (sistema 1900): el 1 es el 1-ene-1900,
     * el 60 el 29-feb-1900 (que no existió, pero Excel lo cree) y del 61 en adelante, días desde
     * el 30-dic-1899. El 0 es el `0/1/1900`.
     */
    fun fechaDeSerie(dias: Long): IntArray {
        val p = desdeSerie(maxOf(0L, dias))!!
        return intArrayOf(p[0].toInt(), p[1].toInt(), p[2].toInt())
    }
}
