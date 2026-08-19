package com.forge.pixpin.motor

import android.content.Context
import java.io.File

/**
 * Qué hojas llevan **algo dibujado de verdad** encima.
 *
 * ## Por qué no vale «tiene dibujo»
 *
 * A una hoja se le asigna su dibujo **al tocarla**, antes de trazar nada, y el editor
 * guarda al salir aunque uno no haya pintado una raya. O sea que el archivo existe en
 * cuanto abres una página y la cierras. Con ese criterio —el que había— el puntito de
 * «esta hoja lleva algo encima» aparecía por el simple hecho de haberla mirado, y el
 * contador de la cabecera decía que un documento estaba anotado entero cuando no había
 * nada dentro. Un indicador que se enciende solo no informa: estorba.
 *
 * El criterio bueno **ya existía en otro sitio**: es el mismo que decide si esa hoja entra
 * en el PDF al exportar —contenido visible, ver [PdfDelProyecto]—. Usándolo aquí, lo que
 * enseña la lista es exactamente lo que va a salir en el archivo.
 *
 * ## El coste, y por qué se paga una vez
 *
 * Saberlo obliga a descomprimir la escena y mirarla. Es barato —unos kilobytes— pero no
 * gratis, y la lista de proyectos se recompone a menudo. Así que la respuesta se guarda
 * por **archivo y fecha**: mirar la lista veinte veces cuesta una, y tocar una hoja
 * invalida la suya sola sin tirar la de las demás.
 */
object CapaDeAnotacion {

    /** Lo que ya se ha mirado: ruta y fecha del archivo → si tenía algo. */
    private val visto = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /**
     * De los dibujos que se le pasen, **los que llevan algo dibujado**.
     *
     * Se pregunta de una vez por proyecto y no hoja a hoja: la respuesta hace falta antes
     * de colocar nada, y pedirla suelta por miniatura daría un salto de maquetación por
     * cada una que contestara tarde.
     *
     * Hay que llamarla fuera del hilo de la interfaz: abre archivos.
     */
    fun conContenido(context: Context, dibujos: List<String>): Set<String> {
        val salida = mutableSetOf<String>()
        for (dibujo in dibujos) {
            val ruta = ExcalidrawStore.rutaDe(context, dibujo)
            val clave = "$ruta-${File(ruta).lastModified()}"
            val tiene = visto.getOrPut(clave) {
                val escena = runCatching { ExcalidrawStore.cargar(ruta) }.getOrNull()
                escena != null && escena.contenidoVisible.isNotEmpty()
            }
            if (tiene) salida.add(dibujo)
        }
        return salida
    }
}
