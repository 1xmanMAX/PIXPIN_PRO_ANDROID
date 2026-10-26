package com.forge.pixpin.ocr.indra

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * **El vocabulario del reconocedor CTC de Indra** (26-oct-2026, `IndraVocab.kt` del escáner, de
 * `indra_ocr/vocab.py`). Clase 0 = blanco; clase i + 1 = [letras][i] (un carácter o un comando de
 * LaTeX como `\alpha`).
 */
class VocabIndra(val letras: List<String>) {
    /** Ficha → clase (i + 1). */
    val indice: Map<String, Int> = HashMap<String, Int>(letras.size * 2).apply { letras.forEachIndexed { i, c -> put(c, i + 1) } }
    private val conComandos = letras.any { it.length > 1 && it.codePointCount(0, it.length) > 1 }
    private val jamo = "ᄀ" in indice

    /** Clase del espacio (para cortar palabras y celdas), o null. */
    val espacio: Int? = indice[" "]

    fun decodificar(clases: IntArray): String {
        val fichas = ArrayList<String>(clases.size)
        for (i in clases) if (i in 1..letras.size) fichas += letras[i - 1]
        if (jamo) return java.text.Normalizer.normalize(fichas.joinToString(""), java.text.Normalizer.Form.NFC)
        if (!conComandos) return fichas.joinToString("")
        val sb = StringBuilder()
        for (k in fichas.indices) {
            val t = fichas[k]
            sb.append(t)
            // «\alpha» + «x» no debe leerse «\alphax».
            val sig = fichas.getOrNull(k + 1) ?: ""
            if (t.length > 2 && t[0] == '\\' && t.substring(1).all { it.isLetter() } && sig.isNotEmpty() && sig[0].isLetter()) sb.append(' ')
        }
        return sb.toString()
    }

    companion object {
        /** Desde la cadena JSON `{"blank": 0, "chars": [...]}` de `meta.json`. */
        fun deJson(s: String): VocabIndra {
            val o = Json.parseToJsonElement(s) as JsonObject
            val blanco = o["blank"]?.jsonPrimitive?.int ?: 0
            require(blanco == 0) { "solo vocabularios con blank=0" }
            return VocabIndra(o.getValue("chars").jsonArray.map { it.jsonPrimitive.content })
        }
    }
}

/** **Lo que dice `meta.json` y hace falta** (`Meta` de `canal.rs` del PC). */
class MetaIndra(
    val vocab: VocabIndra,
    val ladoLargo: Int,
    val altoRec: Int,
    val ventana: Int,
    val alfa: Double,
    val beta: Double,
    val puerta: Double
) {
    companion object {
        fun deJson(texto: String): MetaIndra {
            val v = Json.parseToJsonElement(texto) as JsonObject
            val base = (v["lm_params"] as? JsonObject)?.get("base")?.jsonObject
            fun real(k: String, d: Double) = base?.get(k)?.jsonPrimitive?.double ?: d
            return MetaIndra(
                vocab = VocabIndra.deJson(v.getValue("vocab").jsonPrimitive.content),
                ladoLargo = v.getValue("det_long_side").jsonPrimitive.int,
                altoRec = v.getValue("rec_height").jsonPrimitive.int,
                ventana = (v["rec_window"]?.jsonPrimitive?.int ?: 1).coerceAtLeast(1),
                alfa = real("alpha", CanalIndra.ALFA_LM),
                beta = real("beta", CanalIndra.BETA_LM),
                puerta = real("gate", CanalIndra.PUERTA_LM)
            )
        }
    }
}
