package com.forge.pixpin.capture

import java.util.TimeZone

/**
 * **Lo que la galería de capturas decide sin pintar nada** (v2, como la del PC:
 * `apps/pixpin/src/galeria_capturas/logica.rs`, 4-oct-2026): los filtros con su número, el color
 * de la caducidad, la búsqueda, cómo caen los grupos por día y lo que se elige.
 *
 * Aparte de la pantalla para poder probarlo todo sin aparato (`GaleriaLogicaTest`). Las fechas
 * van en ms UTC y el día es el LOCAL, con la [TimeZone] que se pase (en las pruebas, UTC).
 */
object GaleriaLogica {

    const val DIA_MS = CaducidadDeCapturas.DIA_MS

    /** El día LOCAL de un instante en ms UTC, contado desde 1970. */
    fun diaLocal(utcMs: Long, zona: TimeZone = TimeZone.getDefault()): Long =
        Math.floorDiv(utcMs + zona.getOffset(utcMs), DIA_MS)

    /** Día de la semana de un día desde 1970, con el lunes en 0. El 1-1-1970 fue jueves (3). */
    fun diaDeLaSemana(dia: Long): Int = Math.floorMod(dia + 3, 7L).toInt()

    /** El lunes de la semana de [dia]. */
    fun lunesDe(dia: Long): Long = dia - diaDeLaSemana(dia)

    // ------------------------------------------------------------- caducidad

    /** El color de la pastilla de caducidad. */
    enum class Tono {
        /** Gris: queda más de tres días. */
        LEJOS,
        /** Naranja: dos o tres días. */
        PRONTO,
        /** Rojo: hoy o mañana. */
        URGENTE,
        /** Verde: no se va. */
        CONSERVADA
    }

    /** Qué pone la pastilla. */
    sealed interface Plazo {
        data object Conservada : Plazo
        data object Hoy : Plazo
        data object Manana : Plazo
        /** «Se borra en N días» (2 o 3). */
        data class EnDias(val dias: Long) : Plazo
        /** «Se borra el 10 oct». */
        data class ElDia(val cuando: Long) : Plazo
    }

    /** Días de calendario (locales) que faltan de [ahora] a [seVa]. */
    fun diasQueFaltan(seVa: Long, ahora: Long, zona: TimeZone = TimeZone.getDefault()): Long =
        diaLocal(seVa, zona) - diaLocal(ahora, zona)

    fun plazo(seVa: Long?, ahora: Long, zona: TimeZone = TimeZone.getDefault()): Plazo {
        if (seVa == null) return Plazo.Conservada
        val f = diasQueFaltan(seVa, ahora, zona)
        return when {
            f <= 0 -> Plazo.Hoy
            f == 1L -> Plazo.Manana
            f in 2..3 -> Plazo.EnDias(f)
            else -> Plazo.ElDia(seVa)
        }
    }

    fun tono(p: Plazo): Tono = when (p) {
        Plazo.Conservada -> Tono.CONSERVADA
        Plazo.Hoy, Plazo.Manana -> Tono.URGENTE
        is Plazo.EnDias -> Tono.PRONTO
        is Plazo.ElDia -> Tono.LEJOS
    }

    /** El texto de la pastilla, con los mismos textos que el PC. */
    fun textoDelPlazo(p: Plazo, zona: TimeZone = TimeZone.getDefault()): String = when (p) {
        Plazo.Conservada -> "Conservada"
        Plazo.Hoy -> "Se borra hoy"
        Plazo.Manana -> "Se borra mañana"
        is Plazo.EnDias -> "Se borra en ${p.dias} días"
        is Plazo.ElDia -> "Se borra el ${fechaCorta(p.cuando, zona)}"
    }

    /** «hoy», «mañana», «en 5 días»: lo que va tras la pastilla en el detalle. */
    fun enDias(n: Long): String = when {
        n <= 0 -> "hoy"
        n == 1L -> "mañana"
        else -> "en $n días"
    }

    // --------------------------------------------------------------- filtros

    enum class Filtro(val rotulo: String) {
        TODAS("Todas"),
        HOY("Hoy"),
        SEMANA("Esta semana"),
        CONSERVADAS("Conservadas"),
        PRONTO("Se borran pronto"),
        GIF("GIF"),
        VIDEOS("Vídeos"),
        CON_TEXTO("Con texto")
    }

    /** Lo que hace falta saber de una captura para filtrarla y buscarla. */
    data class Ficha(
        val nombre: String,
        val extension: String,
        /** Su fecha, en ms UTC. */
        val cuando: Long,
        val seVa: Long?,
        /** El texto reconocido, si ya se leyó (`""`: leída, sin texto). null: sin leer o sin OCR. */
        val texto: String? = null
    )

    fun pasa(f: Filtro, c: Ficha, ahora: Long, zona: TimeZone = TimeZone.getDefault()): Boolean = when (f) {
        Filtro.TODAS -> true
        Filtro.HOY -> diaLocal(c.cuando, zona) == diaLocal(ahora, zona)
        Filtro.SEMANA -> lunesDe(diaLocal(c.cuando, zona)) == lunesDe(diaLocal(ahora, zona))
        Filtro.CONSERVADAS -> c.seVa == null
        Filtro.PRONTO -> tono(plazo(c.seVa, ahora, zona)).let { it == Tono.PRONTO || it == Tono.URGENTE }
        Filtro.GIF -> c.extension.equals("gif", ignoreCase = true)
        Filtro.VIDEOS -> c.extension.equals("mp4", ignoreCase = true)
        Filtro.CON_TEXTO -> !c.texto.isNullOrBlank()
    }

    /**
     * Los filtros del carril con su número, en su orden. «Con texto» solo si hay OCR; en el
     * móvil, «GIF» y «Vídeos» solo si hay alguno (aquí no se graban: sería un chip siempre a 0
     * ocupando el ancho del teléfono).
     */
    fun carril(fichas: List<Ficha>, ahora: Long, hayOcr: Boolean, zona: TimeZone = TimeZone.getDefault()): List<Pair<Filtro, Int>> =
        Filtro.entries
            .filter { it != Filtro.CON_TEXTO || hayOcr }
            .map { f -> f to fichas.count { pasa(f, it, ahora, zona) } }
            .filter { (f, n) -> (f != Filtro.GIF && f != Filtro.VIDEOS) || n > 0 }

    // -------------------------------------------------------------- búsqueda

    /** En minúsculas y sin tildes: «Árbol» encuentra «arbol» y al revés. */
    fun plegar(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s.lowercase()) sb.append(
            when (c) {
                'á', 'à', 'ä', 'â' -> 'a'
                'é', 'è', 'ë', 'ê' -> 'e'
                'í', 'ì', 'ï', 'î' -> 'i'
                'ó', 'ò', 'ö', 'ô' -> 'o'
                'ú', 'ù', 'ü', 'û' -> 'u'
                else -> c
            }
        )
        return sb.toString()
    }

    /** Si TODAS las palabras de la consulta (ya plegada) están en alguno de los campos (ya plegados). Vacía encaja con todo. */
    fun encaja(consultaPlegada: String, campos: List<String>): Boolean =
        consultaPlegada.split(' ', '\t', '\n').filter { it.isNotEmpty() }.all { p -> campos.any { it.contains(p) } }

    /** Lo que se busca de una captura: su nombre, su fecha («3 oct», «viernes») y el texto de dentro si lo hay. */
    fun coincide(c: Ficha, consulta: String, zona: TimeZone = TimeZone.getDefault()): Boolean {
        val q = plegar(consulta.trim())
        if (q.isEmpty()) return true
        val fecha = plegar("${fechaCorta(c.cuando, zona)} ${DIAS_DE_LA_SEMANA[diaDeLaSemana(diaLocal(c.cuando, zona))]}")
        return encaja(q, listOf(plegar(c.nombre), fecha, plegar(c.texto ?: "")))
    }

    // ----------------------------------------------------------------- fechas

    val DIAS_DE_LA_SEMANA = listOf("lunes", "martes", "miércoles", "jueves", "viernes", "sábado", "domingo")
    private val MESES = listOf("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic")

    /** «3 oct». */
    fun fechaCorta(utcMs: Long, zona: TimeZone = TimeZone.getDefault()): String {
        val c = java.util.Calendar.getInstance(zona).apply { timeInMillis = utcMs }
        return "${c.get(java.util.Calendar.DAY_OF_MONTH)} ${MESES[c.get(java.util.Calendar.MONTH)]}"
    }

    /** El título de un grupo y su detalle: («Hoy», «lunes 6 oct»), («Ayer», …) o («Viernes», «3 oct»). */
    fun tituloDelDia(dia: Long, hoy: Long, zona: TimeZone = TimeZone.getDefault()): Pair<String, String> {
        val semana = DIAS_DE_LA_SEMANA[diaDeLaSemana(dia)]
        // Mediodía del día local, de vuelta a UTC: así la fecha no cae en el día de al lado.
        val medio = dia * DIA_MS + DIA_MS / 2
        val fecha = fechaCorta(medio - zona.getOffset(medio), zona)
        return when (hoy - dia) {
            0L -> "Hoy" to "$semana $fecha"
            1L -> "Ayer" to "$semana $fecha"
            else -> semana.replaceFirstChar { it.uppercase() } to fecha
        }
    }

    /** «Hoy, 10:42», «Ayer, 10:42», «3 oct, 10:42». */
    fun fechaYHora(ms: Long, ahora: Long, zona: TimeZone = TimeZone.getDefault()): String {
        val local = ms + zona.getOffset(ms)
        val minutos = Math.floorMod(local, DIA_MS) / 60_000
        val hora = "${minutos / 60}:${(minutos % 60).toString().padStart(2, '0')}"
        val dia = when (diaLocal(ahora, zona) - diaLocal(ms, zona)) {
            0L -> "Hoy"
            1L -> "Ayer"
            else -> fechaCorta(ms, zona)
        }
        return "$dia, $hora"
    }

    // ------------------------------------------------------------- agrupar

    /** Un grupo de un día: las posiciones `desde until hasta` de la vista. */
    data class Grupo(val dia: Long, val desde: Int, val hasta: Int)

    /** Corta la vista (ya en orden, la más nueva primero) en grupos por día. `dias[i]` es el día local de la `i`. */
    fun agrupar(dias: List<Long>): List<Grupo> {
        val grupos = ArrayList<Grupo>()
        var i = 0
        while (i < dias.size) {
            var j = i
            while (j < dias.size && dias[j] == dias[i]) j++
            grupos += Grupo(dias[i], i, j)
            i = j
        }
        return grupos
    }

    // --------------------------------------------------------------- elegir

    /** «Elegir todo el día» (y «Todas»): si ya estaban todas, las suelta; si no, las añade. */
    fun <K> alternarVarias(elegidas: Set<K>, estas: Collection<K>): Set<K> =
        if (estas.isNotEmpty() && estas.all { it in elegidas }) elegidas - estas.toSet() else elegidas + estas

    /** Elegir o soltar una. */
    fun <K> alternarUna(elegidas: Set<K>, esta: K): Set<K> = if (esta in elegidas) elegidas - esta else elegidas + esta

    /** «1 elegida», «3 elegidas». */
    fun elegidas(n: Int): String = if (n == 1) "1 elegida" else "$n elegidas"

    // ---------------------------------------------------------------- medidas

    /** «1,2 GB», «412 KB»: lo que ocupan, para el carril y el detalle. */
    fun tamanoLegible(bytes: Long, decimal: Char = ','): String {
        val k = 1024.0
        val b = bytes.toDouble()
        val (v, u) = when {
            b >= k * k * k -> b / (k * k * k) to "GB"
            b >= k * k -> b / (k * k) to "MB"
            else -> maxOf(b / k, if (bytes > 0) 1.0 else 0.0) to "KB"
        }
        val texto = if (u == "KB" || v >= 100.0) String.format(java.util.Locale.ROOT, "%.0f", v)
        else String.format(java.util.Locale.ROOT, "%.1f", v).replace('.', decimal)
        return "$texto $u"
    }

    /** El subtítulo de la cabecera: «12 · duran 7 días», «12 · no caducan». */
    fun subtitulo(cuantas: Int, dias: Int): String =
        if (dias <= 0) "$cuantas · no caducan" else "$cuantas · duran $dias ${if (dias == 1) "día" else "días"}"
}
