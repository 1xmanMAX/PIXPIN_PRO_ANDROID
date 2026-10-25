package com.forge.pixpin.ocr

/**
 * **Qué se baja para leer con Indra**, de dónde, cuánto pesa y su sha256 (25-oct-2026, puerto de
 * `crates/pixpin-indra/src/catalogo.rs` del PC = `ModelCatalog.FAST_READER` del escáner).
 *
 * Fijados al commit de la etiqueta `v1.1.0` de `datalab-to/indra-ocr` (los de una etiqueta no
 * cambian): **los mismos ficheros en el móvil, el PC y el escáner**, para que lean igual. Solo lo
 * de la escritura latina.
 *
 * **Por qué aquí no se baja nada todavía** (`docs/ocr.md` del PC, § Android): leer con Indra en el
 * teléfono pide además ONNX Runtime para Android (12,9 MB de `.so` en arm64, que el escáner baja
 * aparte para no engordar el APK) y el puerto del canal (`ocr/indra/` del escáner, ~2 800 líneas).
 * Los pesos tienen licencia **OpenRAIL-M modificada de Datalab**: no pueden ir en el APK; los baja
 * el usuario, si quiere. Esta lista es la que usará esa descarga, y la que dice el tamaño.
 */
object CatalogoIndra {
    /** Commit de la etiqueta v1.1.0 de `datalab-to/indra-ocr`. */
    const val REVISION = "bd02167fdf2005a19962cc15188f8496a2c87d3b"

    data class Fichero(val nombre: String, val url: String, val bytes: Long, val sha256: String)

    private fun indra(nombre: String, bytes: Long, sha256: String) =
        Fichero(nombre, "https://huggingface.co/datalab-to/indra-ocr/resolve/$REVISION/$nombre", bytes, sha256)

    /**
     * Los pesos (OpenRAIL-M modificada de Datalab) y el diccionario español (RLA-ES por LibreOffice,
     * MPL), por orden de bajada: primero lo pequeño, para que un fallo de red se note en el acto.
     */
    val FICHEROS: List<Fichero> = listOf(
        indra("meta.json", 211_869, "4ce4c94b46f5d644033c02e718322014ff4081270f385038c571912b56696339"),
        indra("det.onnx", 389_948, "f2bd4cba23e9c328cf7ec824634e423e191a2c6aacbbf35c62bd79dd70f62f59"),
        indra("orient.onnx", 371_802, "20eaf670f55a7cf9facfe1bdd6c7b8e40be75bbf4d48a3df43f66aa3bbd874fd"),
        indra("order.onnx", 2_973_138, "a42db2e8b536ad3d84045a4b7f9b0a72667cf8be4aa02d63f7b38004719fb180"),
        indra("rec.onnx", 5_042_161, "475972a2710be4a3cbfeb2bc7f3624ffd563de07de42476fc7a290d4e1ece484"),
        indra("lm.npz", 15_695_957, "6c0fb618a363a0acdd05d4f5b0edd9e8146d2d990541b87f6130bbfe17cea58d"),
        Fichero(
            "es_ES.aff",
            "https://raw.githubusercontent.com/LibreOffice/dictionaries/libreoffice-25.8.2.2/es/es_ES.aff",
            169_202, "e73a9bf8e1383f4986a5dc9e2fbed49371c0c61f511c626d15586bd433c1cad9"
        ),
        Fichero(
            "es_ES.dic",
            "https://raw.githubusercontent.com/LibreOffice/dictionaries/libreoffice-25.8.2.2/es/es_ES.dic",
            715_989, "6975dddec3d5d2c676069537bc67b4b5f786c65c5d4cf6703a82acf779ac9ec1"
        )
    )

    /** Lo que se enseña al usuario: «unos 26 MB». */
    fun megas(): Long = (FICHEROS.sumOf { it.bytes } + 999_999) / 1_000_000
}
