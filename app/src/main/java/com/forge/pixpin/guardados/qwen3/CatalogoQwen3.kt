package com.forge.pixpin.guardados.qwen3

/**
 * **Qué se baja para Qwen3-ASR-0.6B**, de dónde, cuánto pesa y su sha256 (25-oct-2026).
 *
 * Puerto de `crates/pixpin-voz/src/qwen3/catalogo.rs` del PC, con **los mismos URL fijados al
 * commit y los mismos sha256** (`docs/voz-qwen3.md` § Android): la exportación a ONNX int8 que
 * publica sherpa-onnx (k2-fsa) en `csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25`.
 * Un commit no cambia, así que el PC y el teléfono bajan exactamente los mismos bytes y el texto
 * sale igual en los dos (el int8 de ARM y x86 puede diferir en algún token).
 *
 * Tres modelos —el frente convolucional, el encoder de audio y el decoder (el modelo de lenguaje,
 * con su caché de claves y valores)— y el tokenizador de Qwen. **Nunca van dentro del APK**: son
 * 988 MB y solo se bajan si el usuario elige Qwen3-ASR en Ajustes › Voz, a `filesDir/qwen3-asr/`.
 *
 * Licencia: los pesos de Qwen3-ASR-0.6B son **Apache-2.0** (Qwen/Alibaba); la exportación es una
 * conversión de esos mismos pesos.
 */
object CatalogoQwen3 {

    /** Commit del repositorio de Hugging Face con la exportación. */
    const val REVISION = "68818b2313fe77bd06f6a7c5068ff3ef59d02b8a"

    /** La licencia de los pesos, para enseñarla antes de bajar. */
    const val LICENCIA = "Apache-2.0"

    /** Dónde se lee la licencia (la ficha del modelo original). */
    const val ENLACE_LICENCIA = "https://huggingface.co/Qwen/Qwen3-ASR-0.6B"

    private const val BASE = "https://huggingface.co/csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25/resolve/$REVISION/"

    /** Un fichero que se baja: cómo se guarda en `qwen3-asr/`, de dónde, cuánto pesa y su sha256. */
    data class Fichero(val nombre: String, val url: String, val bytes: Long, val sha256: String)

    /** Los seis, por orden de bajada: primero lo pequeño, para que un fallo de red se note en el acto. */
    val FICHEROS: List<Fichero> = listOf(
        Fichero("tokenizer_config.json", BASE + "tokenizer/tokenizer_config.json", 12_487, "4942d005604266809309cabc9f4e9cb89ce855d59b14681fdc0e1cc62ea26c4c"),
        Fichero("merges.txt", BASE + "tokenizer/merges.txt", 1_671_853, "8831e4f1a044471340f7c0a83d7bd71306a5b867e95fd870f74d0c5308a904d5"),
        Fichero("vocab.json", BASE + "tokenizer/vocab.json", 2_776_833, "ca10d7e9fb3ed18575dd1e277a2579c16d108e32f27439684afa0e10b1440910"),
        Fichero("conv_frontend.onnx", BASE + "conv_frontend.onnx", 44_148_281, "d22dc4423e0940e49884e903d2ea2f7e5567c14fc1aed97e4e26d6b8f208ef9e"),
        Fichero("encoder.int8.onnx", BASE + "encoder.int8.onnx", 182_491_662, "60748d3e6744a57c9c91e1b17424a6c2990567e8adceb0783940c03ed98fa9d9"),
        Fichero("decoder.int8.onnx", BASE + "decoder.int8.onnx", 755_914_231, "4f6885be5959ae26af3089d38ee7972c5fafbeeb1cf8d5e76eab6d8b61ca5771")
    )

    /** Índices en [FICHEROS], para no escribir los nombres dos veces. */
    const val CONFIG = 0
    const val MERGES = 1
    const val VOCAB = 2
    const val CONV = 3
    const val ENCODER = 4
    const val DECODER = 5

    /** Lo que se enseña al usuario: «unos 988 MB». */
    fun megas(): Long = (FICHEROS.sumOf { it.bytes } + 999_999) / 1_000_000

    /**
     * **Memoria que pide mientras transcribe**, en MB: la medida del PC (pico del proceso con los
     * pesos preempaquetados, y sin ellos). En el teléfono no se ha medido; el runtime y los pesos
     * son los mismos, así que se espera lo mismo más lo que ocupe la propia app.
     */
    const val MEGAS_DE_RAM = 1_650L
    const val MEGAS_DE_RAM_CUIDADOSO = 1_450L

    /**
     * **Por debajo de esto no se ofrece** (`docs/voz-qwen3.md` § Android: «solo en móviles con
     * 6 GB o más»). Un móvil «de 6 GB» declara algo menos en `MemoryInfo.totalMem` (el núcleo y la
     * radio se quedan su parte), igual que en el PC «4 GB» son unos 3,9: de ahí 5 500 y no 6 144.
     */
    const val RAM_MINIMA_MB = 5_500L

    /** Desde aquí se preempaquetan los pesos (más rápido, ~200 MB más); por debajo, modo cuidadoso. */
    const val RAM_HOLGADA_MB = 7_500L
}
