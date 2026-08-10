package com.forge.pixpin.data

import android.net.Uri
import androidx.core.content.FileProvider

/**
 * El proveedor de archivos, **diciendo la verdad sobre lo que entrega**.
 *
 * ## Por qué un SVG llegaba convertido en JPG
 *
 * El `FileProvider` de serie deduce el tipo de un archivo **por su extensión**,
 * preguntándole a `MimeTypeMap` del sistema. Y ese mapa está lleno de agujeros:
 * `svg` no está en muchas versiones de Android, y `excalidraw` no está en
 * ninguna. Cuando no lo encuentra devuelve `application/octet-stream`.
 *
 * El detalle que lo convierte en un fallo real es que **muchas apps no miran el
 * tipo que pone el intent**, miran el que responde el proveedor. Así que daba
 * igual que el intent dijera `image/svg+xml`: quien lo recibía preguntaba aquí,
 * oía «un archivo cualquiera», y hacía lo que le parecía — normalmente tratarlo
 * como imagen y guardarlo en JPG. El SVG salía bien de aquí y llegaba
 * rasterizado al otro lado, que es la peor forma de fallar: el archivo está
 * bien y el resultado no.
 *
 * ## Y por qué no basta con arreglar el SVG
 *
 * Lo mismo le pasa a `.excalidraw`, que tampoco está en el mapa del sistema.
 * Ahí el efecto es más discreto —el archivo llega entero— pero la app que lo
 * recibe no sabe qué es, así que no se ofrece a abrirlo con nada.
 *
 * Los tipos que sí conoce el sistema —PNG, JPEG, PDF— se dejan como estaban: no
 * hay nada que arreglar y una lista propia solo podría quedarse vieja.
 *
 * ## Y las rutas siguen en el manifiesto
 *
 * Podría configurarse por constructor, y así se hizo al principio: **fue un
 * fallo**. `getUriForFile` es estático, no ve esta instancia y busca las rutas
 * en el `meta-data` del manifiesto pase lo que pase. Al quitarlo lanzaba
 * excepción, y como quien la llama se la traga para no reventar, dejó de
 * abrirse y de compartirse **todo** sin un solo aviso: tocar un pin de archivo
 * no hacía nada y no había forma de saber por qué.
 */
class ProveedorDeArchivos : FileProvider() {

    override fun getType(uri: Uri): String? {
        val nombre = uri.lastPathSegment.orEmpty().lowercase()
        for ((extension, tipo) in LOS_QUE_EL_SISTEMA_NO_SABE) {
            if (nombre.endsWith(extension)) return tipo
        }
        return super.getType(uri)
    }

    private companion object {
        val LOS_QUE_EL_SISTEMA_NO_SABE = listOf(
            ".svg" to "image/svg+xml",
            ".excalidraw" to "application/vnd.excalidraw+json"
        )
    }
}
