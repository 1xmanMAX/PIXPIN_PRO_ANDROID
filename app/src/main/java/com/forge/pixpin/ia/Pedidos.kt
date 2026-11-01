package com.forge.pixpin.ia

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * **La versión del protocolo de pedidos** (`{"pindesk": 1, "accion": …}`), como el PC
 * (`docs/protocolo-pedidos.md` de Windows; 1-nov-2026, `docs/formato-pindesk-android.md`).
 *
 * Antes de PinDesk la clave era `"pixpin"`. Los pedidos que arma Android ([Acciones], [Tareas],
 * [Documentos]) llevan ya `"pindesk": 1` —el PC lo entiende desde la misma versión en que lo
 * escribe— y los que llegan se aceptan con cualquiera de las dos; si vienen las dos, manda
 * `pindesk`.
 */
object Pedidos {
    const val VERSION = "pindesk"
    /** La clave de antes de PinDesk: se acepta siempre, ya no se escribe. */
    const val VERSION_DE_ANTES = "pixpin"

    /** Si [clave] es la de la versión (la nueva o la de antes) y no un campo del pedido. */
    fun esCampoDeVersion(clave: String): Boolean = clave == VERSION || clave == VERSION_DE_ANTES

    /** La versión de [pedido]: la de `pindesk` si la trae, si no la de `pixpin`; null sin ninguna. */
    fun version(pedido: JsonObject): Int? =
        (pedido[VERSION] as? JsonPrimitive)?.intOrNull ?: (pedido[VERSION_DE_ANTES] as? JsonPrimitive)?.intOrNull
}
