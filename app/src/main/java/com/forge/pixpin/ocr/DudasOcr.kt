package com.forge.pixpin.ocr

/**
 * **Líneas dudosas** (25-oct-2026, puerto de `crates/pixpin-indra/src/dudas.rs` del PC =
 * `IndraDoubt.kt` del escáner): las que el reconocedor lee con una confianza entre [SUELO_DUDA] y
 * [CONFIANZA_MIN]. El original las vaciaba; el escáner las conserva, marcadas, si no son basura
 * evidente: en campos vacíos, casillas y rayas el reconocedor lee «-», «I», «1», «•» o letras
 * sueltas; una lectura con dos letras o cifras y altura de renglón se queda. Banco del escáner
 * (154 líneas bajo 0,5 en 54 imágenes): pasan 34, con el mismo % de palabras.
 *
 * Como en el PC, el texto plano que se copia **no** las lleva: solo se conservan marcadas.
 */
object DudasOcr {
    /** Desde aquí una línea se lee sin dudas (`canal::CONFIANZA_MIN`). */
    const val CONFIANZA_MIN = 0.5f
    /** Por debajo, ni dudosa: se tira (`canal::SUELO_DUDA`). */
    const val SUELO_DUDA = 0.3f

    private const val BASURA = "Il1|!-–—_=~.,:;'\"`•·*()[]{}<>/\\^°ºoO0 "

    /** Si se conserva una línea dudosa. [altoRel] = alto de la línea / la mediana de la página. */
    fun conservar(texto: String, confianza: Float, altoRel: Float, suelo: Float = SUELO_DUDA): Boolean {
        val t = texto.trim()
        if (confianza < suelo || t.isEmpty()) return false
        if (t.count { esLetraOCifra(it) } < 2) return false
        if (t.all { it in BASURA }) return false
        // Trazos (muy bajos) o varios renglones fundidos (muy altos).
        if (altoRel !in 0.45f..2.6f) return false
        val trozos = t.split(' ').filter { it.isNotEmpty() }
        val sinCifras = t.none { esCifra(it) }
        if (sinCifras && trozos.all { it.length <= 2 }) return false
        if (confianza < 0.4f && sinCifras && trozos.none { it.length >= 4 }) return false
        return true
    }
}
