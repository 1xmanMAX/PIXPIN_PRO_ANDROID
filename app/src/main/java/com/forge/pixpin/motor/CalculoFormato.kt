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
        return (base.toEpochDay() + DIAS_HASTA_1970).toDouble()
    }

    /** Año, mes y día de un número de serie. */
    fun partes(serial: Double): IntArray {
        val d = java.time.LocalDate.ofEpochDay(Math.floor(serial).toLong() - DIAS_HASTA_1970)
        return intArrayOf(d.year, d.monthValue, d.dayOfMonth)
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
            val bd = BigDecimal(a).round(MathContext(6, RoundingMode.HALF_UP))
            val exp = bd.precision() - bd.scale() - 1
            var mant = bd.movePointLeft(exp).toPlainString()
            if (mant.contains('.')) mant = mant.trimEnd('0').trimEnd('.')
            val e = Math.abs(exp)
            return signo + mant + "E" + (if (exp < 0) "-" else "+") + (if (e < 10) "0$e" else "$e")
        }
        val bd = BigDecimal(a).round(MathContext(10, RoundingMode.HALF_UP))
        var s = bd.toPlainString()
        if (s.contains('.')) s = s.trimEnd('0').trimEnd('.')
        return signo + s
    }

    /**
     * Redondea como `REDONDEAR`: primero a quince cifras —así 2,675 es 2,675 y no
     * 2,67499999— y luego la mitad hacia fuera del cero.
     */
    fun redondear(x: Double, decimales: Int, modo: Int = 0): Double {
        if (!x.isFinite()) return x
        val n = decimales.coerceIn(-15, 15)
        val f = Math.pow(10.0, Math.abs(n).toDouble())
        val escalado = if (n >= 0) Math.abs(x) * f else Math.abs(x) / f
        val limpio = if (escalado == 0.0) 0.0
            else BigDecimal(escalado).round(MathContext(15, RoundingMode.HALF_UP)).toDouble()
        val entero = when (modo) {
            1 -> Math.ceil(limpio)
            -1 -> Math.floor(limpio)
            else -> Math.floor(limpio + 0.5)
        }
        val r = if (n >= 0) entero / f else entero * f
        return if (x < 0) -r else r
    }

    /**
     * `TEXTO(valor; formato)`, en lo que se usa de verdad: decimales (`0.00`), miles
     * (`#,##0`), porcentaje (`0%`), texto delante y detrás, y fechas (`dd/mm/aaaa`).
     */
    fun conFormato(v: Double, formato: String): String {
        // Lo que va entre comillas es texto: `0.00 "dólares"` no es una fecha por llevar «d».
        val bajo = formato.replace(Regex("\"[^\"]*\""), "").lowercase()
        if (bajo.contains("dd") || bajo.contains("yy") || bajo.contains("aa")) {
            val p = partes(v)
            var s = formato
            s = Regex("yyyy|aaaa", RegexOption.IGNORE_CASE).replace(s, p[0].toString())
            s = Regex("yy|aa", RegexOption.IGNORE_CASE).replace(s, dos(p[0] % 100))
            s = Regex("mm", RegexOption.IGNORE_CASE).replace(s, dos(p[1]))
            s = Regex("dd", RegexOption.IGNORE_CASE).replace(s, dos(p[2]))
            return s
        }
        val primero = formato.indexOfFirst { it == '0' || it == '#' }
        if (primero < 0) return formato.replace("\"", "")
        val ultimo = formato.indexOfLast { it == '0' || it == '#' || it == '%' || it == '.' || it == ',' }
        val delante = formato.substring(0, primero).replace("\"", "")
        val mascara = formato.substring(primero, ultimo + 1)
        val detras = formato.substring(ultimo + 1).replace("\"", "")
        val porCiento = mascara.contains('%')
        val punto = mascara.indexOf('.')
        val decimales = if (punto < 0) 0 else mascara.substring(punto + 1).count { it == '0' || it == '#' }
        val miles = (if (punto < 0) mascara else mascara.substring(0, punto)).contains(',')
        val x = redondear(if (porCiento) v * 100 else v, decimales)
        var cifras = BigDecimal(Math.abs(x)).setScale(decimales, RoundingMode.HALF_UP).toPlainString()
        if (miles) {
            val p = cifras.indexOf('.')
            val ent = if (p < 0) cifras else cifras.substring(0, p)
            val resto = if (p < 0) "" else cifras.substring(p)
            val sb = StringBuilder()
            for ((i, c) in ent.withIndex()) {
                if (i > 0 && (ent.length - i) % 3 == 0) sb.append(',')
                sb.append(c)
            }
            cifras = sb.toString() + resto
        }
        val signo = if (x < 0) "-" else ""
        return delante + signo + cifras + (if (porCiento) "%" else "") + detras
    }
}
