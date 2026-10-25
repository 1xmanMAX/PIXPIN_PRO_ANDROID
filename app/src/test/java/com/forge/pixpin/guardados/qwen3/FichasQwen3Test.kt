package com.forge.pixpin.guardados.qwen3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Los tokens de Qwen: los mismos casos que `qwen3/fichas.rs` del PC. */
class FichasQwen3Test {

    @Test
    fun `la tabla de gpt2 deja lo imprimible y mueve lo demas`() {
        assertEquals('a', FichasQwen3.letraDeByte('a'.code))
        assertEquals('Ġ', FichasQwen3.letraDeByte(' '.code))
        assertEquals('Ċ', FichasQwen3.letraDeByte('\n'.code))
        assertEquals('Ã', FichasQwen3.letraDeByte(0xC3))
        assertEquals("ninguna letra repetida", 256, FichasQwen3.BYTE_DE_LETRA.size)
    }

    @Test
    fun `el pretroceado parte como la expresion de qwen2`() {
        // Salidas de `pre_tokenizers.Split(PAT)` de `tokenizers` 0.23.
        val p = FichasQwen3::pretrocear
        assertEquals(listOf("language", " Spanish"), p("language Spanish"))
        assertEquals(listOf("system", "\n"), p("system\n"))
        assertEquals(listOf("¿Qué", " tal", "?", " Año", " ", "2", "0", "2", "6", ",", " ñandú", "."), p("¿Qué tal? Año 2026, ñandú."))
        assertEquals(listOf("hola", " ", " mundo"), p("hola  mundo"))
        assertEquals(listOf("a", "\n\n", "b", "  "), p("a\n\nb  "))
        assertEquals(listOf("it", "'s", " OK"), p("it's OK"))
        assertEquals(listOf("\n", " ", " x"), p("\n  x"))
        assertEquals(listOf("x", " \n \n", "y"), p("x \n \ny"))
        assertEquals(listOf("a", ",", " b", "!!\n\n", "c"), p("a, b!!\n\nc"))
        assertEquals(emptyList<String>(), p(""))
        // Un emoji es un punto de código, no dos `Char`.
        assertEquals(listOf("hola", " 😀"), p("hola 😀"))
    }

    private fun mini(): Pair<FichasQwen3.Fichas, FichasQwen3.Fusiones> {
        // Un vocabulario de juguete: los bytes sueltos que hacen falta y dos fusiones.
        val vocab = """{"h":0,"o":1,"l":2,"a":3,"ho":4,"hola":5,"Ġ":6,"la":7,"Ã":8,"±":9}"""
        val config = """{"added_tokens_decoder":{"100":{"content":"<|fin|>","special":true}}}"""
        return FichasQwen3.Fichas.leer(vocab, config)!! to FichasQwen3.Fusiones.leer("#version: 0.2\nh o\nl a\nho la\n")
    }

    @Test
    fun `las fusiones van por orden y los especiales enteros`() {
        val (f, fus) = mini()
        assertEquals(listOf(5L), f.codificar("hola", fus))
        assertEquals(listOf(5L, 100L, 7L), f.codificar("hola<|fin|>la", fus))
        assertEquals(listOf(100L), f.codificar("<|fin|>", fus))
        assertEquals(100L, f.especial("<|fin|>"))
        assertNull(f.especial("<|otro|>"))
    }

    @Test
    fun `los bytes de varios tokens se juntan antes de pasar a utf8`() {
        val (f, _) = mini()
        // «ñ» es C3 B1: «Ã» y «±» por separado.
        assertEquals("hola ñ", f.texto(listOf(5, 6, 8, 9)))
        // Un especial o un número fuera de la tabla no se escribe.
        assertEquals("hola", f.texto(listOf(5, 100, -1)))
        // Medio carácter al final se tira.
        assertEquals("hola", f.texto(listOf(5, 8)))
    }

    @Test
    fun `lo que no es json no se lee`() {
        assertNull(FichasQwen3.Fichas.leer("no", "{}"))
        assertNull(FichasQwen3.Fichas.leer("{\"a\":0}", "{}"))
    }

    /** Con el tokenizador de verdad (`PIXPIN_QWEN3`, la carpeta bajada): las frases del guion dan los números de `tokenizers`. */
    @Test
    fun `el guion da los numeros de tokenizers`() {
        val d = System.getenv("PIXPIN_QWEN3")?.let(::File)
        assumeTrue("pide los ficheros de Qwen3-ASR bajados (PIXPIN_QWEN3)", d != null && File(d, "vocab.json").isFile)
        fun leer(n: String) = File(d, n).readText()
        val f = FichasQwen3.Fichas.leer(leer("vocab.json"), leer("tokenizer_config.json"))!!
        val fus = FichasQwen3.Fusiones.leer(leer("merges.txt"))
        assertEquals(
            listOf(151644L, 8948, 198, 151645, 198, 151644, 872, 198, 151669),
            f.codificar("<|im_start|>system\n<|im_end|>\n<|im_start|>user\n<|audio_start|>", fus)
        )
        assertEquals(
            listOf(151670L, 151645, 198, 151644, 77091, 198, 11528, 15154, 151704),
            f.codificar("<|audio_end|><|im_end|>\n<|im_start|>assistant\nlanguage Spanish<asr_text>", fus)
        )
        val frase = "¿Qué tal? El año 2026 fue muy boscoso, ñandú."
        assertEquals(frase, f.texto(f.codificar(frase, fus)))
    }
}
