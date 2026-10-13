package com.forge.pixpin.office

import android.content.Context
import android.content.res.Configuration

/**
 * **El modo oscuro de los visores** (PC 74801b4/ce82d27, 12-oct-2026: «todos los visores con modo
 * oscuro, con un botón luna/sol»). La misma cuenta que `pixpin_render::oscuro` del PC, que a su vez
 * copió la de Android ([com.forge.pixpin.pdf.MATRIZ_INVERTIDA]): **invertir la luz y girar el matiz
 * media vuelta** (el `invert(1) hue-rotate(180deg)` de CSS). Lo blanco se oscurece, lo negro se
 * aclara y **el tono se queda**: una raya roja sigue roja. Lo blanco no va a negro puro sino a
 * [PAPEL] y lo negro no va a blanco puro sino a [TINTA]: de noche no deslumbra.
 *
 * Por color, como el PC en sus pintores en vectores (Word, PowerPoint y Excel): solo cambia lo del
 * papel o «automático» (el fondo, la rejilla, el texto oscuro sin relleno debajo); los rellenos, las
 * imágenes y los gráficos se quedan con sus colores, como el modo oscuro de Word. Un PDF, que llega
 * rasterizado, va con la matriz por píxel.
 *
 * Sin nada de Android en las cuentas (se prueban en la JVM). La preferencia es **de este aparato**
 * (no viaja): la clave `invertido` de los ajustes `lectura`, la misma del interruptor «Invertir
 * colores» del lector; si nunca se tocó, manda el tema del teléfono (como el PC con el de Windows).
 */
object Oscuro {
    /** Lo blanco del documento, en oscuro: el gris de la barra de los pines del PC. */
    const val PAPEL = 0xFF1F1F22.toInt()
    /** Lo negro del documento, en oscuro. */
    const val TINTA = 0xFFE6E6E8.toInt()

    /** El giro de matiz de 180° (fila = canal de salida); cada fila suma 1: los grises no cambian. */
    private val GIRO = arrayOf(floatArrayOf(-0.574f, 1.430f, 0.144f), floatArrayOf(0.426f, 0.430f, 0.144f), floatArrayOf(0.426f, 1.430f, -0.856f))

    private fun r(c: Int) = (c shr 16) and 0xff
    private fun g(c: Int) = (c shr 8) and 0xff
    private fun b(c: Int) = c and 0xff

    /** La luz (luma) de un color, de 0 a 1. */
    fun luz(c: Int): Float = (0.2126f * r(c) + 0.7152f * g(c) + 0.0722f * b(c)) / 255f

    /** **Un color en oscuro**: la luz invertida, el tono conservado, entre [PAPEL] y [TINTA]. El alfa se queda. */
    fun invertirLuz(c: Int): Int {
        val inv = floatArrayOf(1f - r(c) / 255f, 1f - g(c) / 255f, 1f - b(c) / 255f)
        val lo = intArrayOf(r(PAPEL), g(PAPEL), b(PAPEL))
        val hi = intArrayOf(r(TINTA), g(TINTA), b(TINTA))
        var salida = c and 0xFF000000.toInt()
        for (k in 0..2) {
            val canal = GIRO[k][0] * inv[0] + GIRO[k][1] * inv[1] + GIRO[k][2] * inv[2]
            val v = ((lo[k] + (hi[k] - lo[k]) * canal) / 255f).coerceIn(0f, 1f)
            salida = salida or (Math.round(v * 255f).coerceIn(0, 255) shl (16 - 8 * k))
        }
        return salida
    }

    /** **Un texto sobre el papel oscuro**: si es oscuro se aclara conservando el tono; si ya se lee, igual. */
    fun legible(c: Int): Int = if (luz(c) < 0.45f) invertirLuz(c) else c

    /** **Una raya** (borde, eje): solo se aclara la casi negra; las de color son del gráfico y se quedan. */
    fun rayaLegible(c: Int): Int = if (luz(c) < 0.2f) invertirLuz(c) else c

    /** Un relleno liso casi blanco: en oscuro es papel (muchos libros rellenan de blanco para esconder la rejilla). */
    fun casiBlanco(c: Int): Boolean = (c ushr 24) > 200 && minOf(r(c), g(c), b(c)) >= 0xF0

    /** Un color del marco del visor (fondo, rejilla, encabezados): tal cual en claro, invertido en oscuro. */
    fun si(oscuro: Boolean, c: Int): Int = if (oscuro) invertirLuz(c) else c

    // ------------------------------------------------------------------------- la preferencia

    private const val AJUSTES = "lectura"
    private const val CLAVE = "invertido"

    /** Si los visores van en oscuro: lo elegido con luna/sol o, si nunca se tocó, el tema del teléfono. */
    fun activo(c: Context): Boolean {
        val p = c.getSharedPreferences(AJUSTES, Context.MODE_PRIVATE)
        return if (p.contains(CLAVE)) p.getBoolean(CLAVE, false) else delTelefono(c)
    }

    fun poner(c: Context, si: Boolean) {
        c.getSharedPreferences(AJUSTES, Context.MODE_PRIVATE).edit().putBoolean(CLAVE, si).apply()
    }

    fun delTelefono(c: Context): Boolean =
        (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
}
