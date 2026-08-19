package com.forge.pixpin.guardados

/**
 * Cómo se llama un día en los separadores de la conversación.
 *
 * ## Por qué no vale con la fecha
 *
 * Los separadores decían siempre la fecha completa, con año incluido: «16 ago 2026»
 * para lo de esta mañana. Y la fecha de hoy es justo el dato que nadie necesita leer,
 * porque hoy ya se sabe qué día es. Lo que uno busca al desplazar es **desde cuándo**
 * viene lo que está viendo, y para eso «Hoy» y «Ayer» dicen en una palabra lo que una
 * fecha obliga a calcular.
 *
 * El año es lo mismo un escalón más arriba: dentro del año en curso no aporta nada y
 * ensucia todos los separadores. Telegram usa `d MMMM` y solo añade el año cuando la
 * cosa tiene más de uno (`LocaleController.formatDateChat`), y esa es la regla que se
 * copia aquí. Lo de «Hoy» y «Ayer» sí es añadido nuestro: Telegram no lo hace en el
 * separador, pero en un cajón de documentos que se abre varias veces al día se agradece.
 *
 * ## Por qué es un fichero aparte
 *
 * Porque la cuenta tiene esquinas: «ayer» no es «hace 24 horas» —a las dos de la
 * madrugada, lo de hace tres horas es de ayer— y «este año» no es «hace menos de 365
 * días». Las dos se calculan por días de calendario en la zona de quien mira, y las dos
 * se pueden equivocar en silencio. Aquí se comprueban sin dispositivo.
 */
enum class NombreDelDia {
    /** Hoy mismo. */
    HOY,

    /** El día de antes. */
    AYER,

    /** Del año en curso: se dice el día y el mes, sin año. */
    ESTE_ANO,

    /** De otro año: entonces sí hace falta el año. */
    CON_ANO
}

/**
 * Cómo hay que llamar al día de [cuando], mirándolo desde [ahora].
 *
 * Las dos marcas van en milisegundos y la cuenta se hace en la zona horaria de quien
 * mira, no en UTC: si no, el separador cambiaría de nombre según en qué país se abra la
 * aplicación, y cerca de la medianoche diría «ayer» a lo de hace un rato.
 */
fun nombreDelDia(
    cuando: Long,
    ahora: Long,
    zona: java.util.TimeZone = java.util.TimeZone.getDefault()
): NombreDelDia {
    val suyo = java.util.Calendar.getInstance(zona).apply { timeInMillis = cuando }
    val hoy = java.util.Calendar.getInstance(zona).apply { timeInMillis = ahora }

    val anoSuyo = suyo.get(java.util.Calendar.YEAR)
    val anoHoy = hoy.get(java.util.Calendar.YEAR)
    val diaSuyo = suyo.get(java.util.Calendar.DAY_OF_YEAR)
    val diaHoy = hoy.get(java.util.Calendar.DAY_OF_YEAR)

    if (anoSuyo == anoHoy && diaSuyo == diaHoy) return NombreDelDia.HOY

    // Ayer se calcula retrocediendo un día **de calendario** y comparando, en vez de
    // restar veinticuatro horas: así sigue siendo correcto el día que cambia la hora y
    // el 1 de enero, que son justo los dos días en que una resta de milisegundos falla.
    val ayer = java.util.Calendar.getInstance(zona).apply {
        timeInMillis = ahora
        add(java.util.Calendar.DAY_OF_YEAR, -1)
    }
    if (anoSuyo == ayer.get(java.util.Calendar.YEAR) &&
        diaSuyo == ayer.get(java.util.Calendar.DAY_OF_YEAR)
    ) {
        return NombreDelDia.AYER
    }

    return if (anoSuyo == anoHoy) NombreDelDia.ESTE_ANO else NombreDelDia.CON_ANO
}
