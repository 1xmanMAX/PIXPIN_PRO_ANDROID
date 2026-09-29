package com.forge.pixpin.data

/**
 * **Los tipos de archivo que Android no conoce**, con todos los nombres con que se anuncian.
 *
 * ## Por qué un DWG no ofrecía ninguna aplicación de planos
 *
 * `MimeTypeMap` no sabe qué es `.dwg` (ni `.dxf`, `.skp`, `.ifc`…). Sin tipo, «Abrir con» mandaba
 * el archivo como `*`/`*`, y un intent con ese tipo casa con **cualquier** aplicación que declare
 * algún tipo: salían la galería, el reproductor, el lector de PDF… y no las de planos, que solo
 * declaran los suyos. Y esos suyos no son uno: cada visor de DWG eligió un nombre distinto
 * (`image/vnd.dwg`, `application/acad`, `application/x-dwg`…), porque el registrado en IANA llegó
 * tarde. Por eso cada extensión lleva **una lista**: el primero es el que se anuncia como tipo del
 * archivo (y el que responde el proveedor), y con todos se buscan aplicaciones.
 *
 * Los tipos de la lista tienen que estar también en `<queries>` del manifiesto: desde Android 11
 * una aplicación que no se declara buscar no aparece al preguntar. Lo vigila `TiposDeArchivoTest`.
 *
 * Sin nada de Android, para poder probarlo en la JVM.
 */
object TiposDeArchivo {

    val DESCONOCIDOS: Map<String, List<String>> = mapOf(
        "dwg" to listOf(
            "image/vnd.dwg", "application/acad", "application/x-acad", "application/autocad_dwg",
            "application/dwg", "application/x-dwg", "image/x-dwg", "drawing/dwg"
        ),
        "dxf" to listOf("image/vnd.dxf", "application/dxf", "application/x-dxf", "image/x-dxf", "application/x-autocad"),
        "dwf" to listOf("model/vnd.dwf", "drawing/x-dwf", "application/x-dwf"),
        "skp" to listOf("application/vnd.sketchup.skp", "application/x-koan"),
        "ifc" to listOf("application/x-step", "model/ifc", "application/ifc"),
        "step" to listOf("model/step", "application/step", "application/x-step"),
        "stp" to listOf("model/step", "application/step", "application/x-step"),
        "stl" to listOf("model/stl", "application/sla", "application/vnd.ms-pki.stl", "application/x-stl"),
        "obj" to listOf("model/obj", "application/x-tgif", "text/plain"),
        "3dm" to listOf("model/vnd.3dm", "application/x-3dm"),
        "kmz" to listOf("application/vnd.google-earth.kmz"),
        "kml" to listOf("application/vnd.google-earth.kml+xml"),
        "svg" to listOf("image/svg+xml"),
        "excalidraw" to listOf("application/vnd.excalidraw+json", "application/json")
    )

    /** Los tipos con que buscar quién abre [nombre], el primero el canónico; vacío si no es de los nuestros. */
    fun candidatos(nombre: String): List<String> =
        DESCONOCIDOS[nombre.substringAfterLast('.', "").lowercase()].orEmpty()

    /** El tipo que se anuncia para [nombre], o null si se deja al sistema. */
    fun principal(nombre: String): String? = candidatos(nombre).firstOrNull()

    /** Todos los tipos que hay que declarar en `<queries>`. */
    val TODOS: Set<String> get() = DESCONOCIDOS.values.flatten().toSet()
}
