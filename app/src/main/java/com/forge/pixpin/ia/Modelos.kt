package com.forge.pixpin.ia

/**
 * **El asistente local, sin internet** (porte de `crates/pixpin-ia` del PC, 14-oct-2026).
 *
 * Pedido del usuario: el asistente del PC —un modelo de lenguaje pequeño que vive en el chat y
 * usa herramientas de PixPin (dibujar en el lienzo, gráficas, leer un documento)— también en el
 * teléfono, **con los mismos ficheros, las mismas huellas y el mismo protocolo**, para que lo
 * que hace uno lo entienda el otro al sincronizar.
 *
 * Este archivo dice **qué se baja**: los mismos GGUF que `modelos.rs` del PC, con su URL, su
 * tamaño y su SHA-256 fijos. Si alguien cambia un fichero en el servidor, la descarga falla en
 * vez de cargar algo que no se probó. Sin Android: se prueba en la JVM.
 */

/** Un fichero que se baja tal cual. */
data class Fichero(
    /** Nombre en disco, dentro de la carpeta del modelo. */
    val nombre: String,
    val url: String,
    val bytes: Long,
    val sha256: String
)

/** Un modelo: los pesos y, si ve imágenes, su proyector de visión. */
data class Modelo(
    /** Identificador estable (carpeta en disco y ajuste guardado): el mismo que en el PC. */
    val id: String,
    /** Cómo se le enseña al usuario. */
    val nombre: String,
    val licencia: String,
    val pesos: Fichero,
    val vision: Fichero?,
    /** RAM del proceso con el modelo cargado y una imagen vista (medida en el PC). */
    val ramMb: Int
) {
    val ficheros: List<Fichero> get() = listOfNotNull(pesos, vision)
    val bytes: Long get() = ficheros.sumOf { it.bytes }
}

object Modelos {
    /** **El de siempre**: Qwen3.5-2B, 4 bits, con visión. ~1,9 GB. Llama bien a las herramientas. */
    val QWEN35_2B = Modelo(
        id = "qwen3.5-2b",
        nombre = "Qwen3.5 2B",
        licencia = "Apache-2.0",
        pesos = Fichero(
            "Qwen3.5-2B-Q4_K_M.gguf",
            "https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/resolve/main/Qwen3.5-2B-Q4_K_M.gguf",
            1_280_835_840,
            "aaf42c8b7c3cab2bf3d69c355048d4a0ee9973d48f16c731c0520ee914699223"
        ),
        vision = Fichero(
            "mmproj-F16.gguf",
            "https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/resolve/main/mmproj-F16.gguf",
            668_227_264,
            "7035e9cb8d7c6a9681d07eef9a364783e86ea4cd73faab2eabb4f43a101830c7"
        ),
        ramMb = 2900
    )

    /**
     * **El ligero**: Qwen3.5-0.8B, 4 bits, con visión. ~740 MB. Para teléfonos con poca memoria;
     * dibuja peor y ve mucho peor (medido en el PC: a veces no llama a la herramienta).
     */
    val QWEN35_08B = Modelo(
        id = "qwen3.5-0.8b",
        nombre = "Qwen3.5 0.8B",
        licencia = "Apache-2.0",
        pesos = Fichero(
            "Qwen3.5-0.8B-Q4_K_M.gguf",
            "https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/resolve/main/Qwen3.5-0.8B-Q4_K_M.gguf",
            532_517_120,
            "bd258782e35f7f458f8aced1adc053e6e92e89bc735ba3be89d38a06121dc517"
        ),
        vision = Fichero(
            "mmproj-F16.gguf",
            "https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/resolve/main/mmproj-F16.gguf",
            204_987_232,
            "56e4c6cfe73b0c82e3e82bc518d7591997e61d81f723fc41a586f4fa69ea2453"
        ),
        ramMb = 1400
    )

    val TODOS: List<Modelo> = listOf(QWEN35_2B, QWEN35_08B)

    /**
     * Por debajo de esto el 2B no se elige solo (`RAM_PARA_EL_2B_MB` del PC). Un teléfono de
     * 8 GB anuncia algo menos (~7,4 GB: lo que se queda el sistema), así que se compara con un
     * 85 %: «tiene 8 GB» es lo que diría su caja.
     */
    const val RAM_PARA_EL_2B_MB = 8192L

    fun porId(id: String?): Modelo? = TODOS.firstOrNull { it.id == id }

    /**
     * **El que toca en este aparato**: el elegido en Ajustes si lo hay; si no, el 0.8B con menos
     * de 8 GB de RAM y el 2B con más. [ramTotal] es `ActivityManager.MemoryInfo.totalMem`.
     */
    fun elegido(ajuste: String?, ramTotal: Long): Modelo =
        porId(ajuste) ?: if (ramTotal >= RAM_PARA_EL_2B_MB * 1024 * 1024 * 85 / 100) QWEN35_2B else QWEN35_08B

    /** «1,9 GB», «737 MB»: como se escribe el tamaño en la tarjeta de la primera vez. */
    fun tamanoLegible(bytes: Long): String {
        val mb = bytes / 1_000_000.0
        return if (mb >= 1000) String.format(java.util.Locale.ROOT, "%.1f GB", mb / 1000).replace('.', ',')
        else "${mb.toLong()} MB"
    }
}
