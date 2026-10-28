@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.forge.pixpin.guardados

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * **La ventana de una grabación en la hora de pared** (28-oct-2026, `RelojDeGrabacion` de
 * `crates/pixpin-proyecto/src/por_su_hora.rs` del PC): lo que va en `resto.reloj` de la nota de voz
 * de una clase. Todo en ms desde 1970 en el reloj de quien grabó ([aparato]).
 *
 * **Exactamente la forma del PC**, campo por campo y en su orden: `aparato` e `inicio` siempre;
 * `fin`, `pausas`, `vistoEn`, `huecos` y `grabacion` solo si no valen lo de por omisión (0 o vacío),
 * como sus `skip_serializing_if`. Cada tramo de [pausas] y [huecos] es `[desde, hasta]`, `hasta` 0
 * mientras sigue. Solo el modelo: las cuentas están en [PorSuHora].
 */
@Serializable
data class RelojDePared(
    /** El código (4 signos) del aparato que grabó: el único que coloca fotos en esta grabación. */
    @EncodeDefault val aparato: String = "",
    /** Cuándo empezó. */
    @EncodeDefault val inicio: Long = 0,
    /** Cuándo acabó. 0: sigue (o se cortó sin acabar, ver [vistoEn]). */
    val fin: Long = 0,
    /** Las pausas, `[desde, hasta]`; `hasta` 0: en pausa ahora. */
    val pausas: List<List<Long>> = emptyList(),
    /** La última vez que se apuntó grabando: si el programa se cae, la ventana acaba aquí. */
    val vistoEn: Long = 0,
    /** La hora siguió y la nota no grabó (el aparato se suspendió). Cuentan como las pausas. */
    val huecos: List<List<Long>> = emptyList(),
    /** El id con que se anunció a los demás aparatos (`camara_remota`). */
    val grabacion: String = ""
) {
    /**
     * Como va en la nota: **con las claves en orden alfabético**, como las deja el PC (su `resto` es un
     * `serde_json::Map` sin `preserve_order`, un `BTreeMap`), byte a byte lo mismo.
     */
    fun aJson(): JsonObject = JsonObject(JSON.encodeToJsonElement(serializer(), this).jsonObject.toSortedMap())

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = false }

        /** El de la nota, si lo lleva y tiene `inicio` (uno roto o sin inicio no es un reloj). */
        fun de(o: JsonObject?): RelojDePared? =
            o?.let { runCatching { JSON.decodeFromJsonElement(serializer(), it) }.getOrNull() }?.takeIf { it.inicio > 0 }

        fun de(m: Mensaje): RelojDePared? = de(m.reloj)
    }
}
