package com.forge.pixpin.asistente

import android.content.Context
import com.forge.pixpin.guardados.Clase
import com.forge.pixpin.guardados.Mensaje
import com.forge.pixpin.guardados.MensajesStore
import com.forge.pixpin.ia.Acciones
import com.forge.pixpin.ia.EnlacesNativos
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.io.File
import java.util.UUID

/**
 * **Lo que pide un enlace, hecho en el teléfono** (PC `apps/pixpin/src/orden.rs`): el lector y la
 * seguridad son los del PC ([EnlacesNativos]); los pedidos salen del protocolo de siempre y los hace
 * lo mismo que ya los hace para el asistente ([AccionesDelTelefono]): cada pedido busca su fila en la
 * tabla por su `accion` y sus campos fijos. Lo que el teléfono no sabe hacer se dice.
 */
class EnlacesDelTelefono(private val contexto: Context) {

    /** Un pedido de un enlace, con lo que dice su seguridad. */
    class Pedido(val pedido: JsonObject, val seguridad: String, val dice: String) {
        val directa get() = seguridad == "directa"
        val prohibida get() = seguridad.startsWith("prohibida")
    }

    /** Lo que trae un enlace, o el motivo de que no valga. */
    class Leido(val pedidos: List<Pedido>, val ayuda: String? = null, val error: String? = null)

    private fun claveDeLosEnlaces() = File(ConexionesDelTelefono.carpetaDeClaves(contexto), "enlaces.clave").path

    fun leer(url: String): Leido {
        if (!EnlacesNativos.disponible) return Leido(emptyList(), error = "esta versión no trae los enlaces (falta su biblioteca)")
        val o = runCatching { Json.parseToJsonElement(EnlacesNativos.leer(url, claveDeLosEnlaces())) as JsonObject }.getOrNull()
            ?: return Leido(emptyList(), error = "el enlace no se pudo leer")
        (o["error"] as? JsonPrimitive)?.let { return Leido(emptyList(), error = it.content) }
        (o["ayuda"] as? JsonPrimitive)?.let { return Leido(emptyList(), ayuda = it.content) }
        val ps = (o["pedidos"] as? JsonArray).orEmpty().mapNotNull { e ->
            val p = e as? JsonObject ?: return@mapNotNull null
            Pedido(p["pedido"] as? JsonObject ?: return@mapNotNull null, (p["seguridad"] as? JsonPrimitive)?.content ?: "confirmar:cambia",
                (p["dice"] as? JsonPrimitive)?.content.orEmpty())
        }
        return Leido(ps)
    }

    /** Lo que se le enseña al usuario antes de hacer algo que no es inofensivo (PC `orden-motivo-…`). */
    fun motivo(seguridad: String): String = when (seguridad.substringAfter(':')) {
        "ficheros" -> "abre o manda ficheros de este teléfono"
        "asistente" -> "le pide algo al asistente"
        "microfono" -> "graba con el micrófono"
        "otros-aparatos" -> "manda o sincroniza con tus otros aparatos"
        "automatizacion" -> "toca una automatización"
        "borra" -> "borra algo"
        "ruta-no-local" -> "abre una ruta que no es de este teléfono"
        "crear-automatizacion" -> "crea una automatización"
        else -> "cambia algo en PixPin"
    }

    /** ¿Encaja [fijos] (los campos fijos de una fila) con [pedido]? Lo que falta no estorba; lo distinto, sí. */
    private fun encaja(fijos: JsonElement, pedido: JsonElement?): Int? {
        if (fijos is JsonObject) {
            var n = 0
            for ((k, v) in fijos) {
                val suyo = (pedido as? JsonObject)?.get(k) ?: continue
                n += (encaja(v, suyo) ?: return null) + 1
            }
            return n
        }
        if (pedido == null) return 0
        return if (fijos == pedido) 1 else null
    }

    /** La fila de la tabla que hace este pedido (su `accion` y lo más de sus campos fijos). */
    fun filaDe(pedido: JsonObject): Acciones.Accion? {
        val accion = (pedido["accion"] as? JsonPrimitive)?.content ?: return null
        return Acciones.TODAS.filter { it.accion == accion }
            .mapNotNull { a -> runCatching { Json.parseToJsonElement(a.fijos) }.getOrNull()?.let { f -> encaja(f, pedido)?.let { it to a } } }
            .maxByOrNull { it.first }?.second
    }

    /**
     * **Hace un pedido.** Lo que no es de la tabla del asistente (el asistente mismo, leer en voz,
     * al lienzo) se hace aquí; lo demás, por [AccionesDelTelefono] con su fila. Devuelve lo hecho.
     */
    fun hacer(p: Pedido, confirmado: Boolean): Result<String> = runCatching {
        if (p.prohibida) error("por enlace eso no se hace nunca: ${motivo(p.seguridad)}")
        if (!p.directa && !confirmado) error("falta que digas que sí: ${motivo(p.seguridad)}")
        val pedido = p.pedido
        val app = contexto.applicationContext as com.forge.pixpin.PixPinApp
        val almacen = MensajesStore(app)
        fun texto(k: String) = (pedido[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
        when ((pedido["accion"] as? JsonPrimitive)?.content) {
            // Le pide algo al asistente: va a su chat y contesta allí (en el PC, en un pin).
            "asistente" -> {
                val t = texto("texto") ?: error("falta lo que se le pide al asistente")
                val chat = Asistente.asegurarChat(app).id
                val nota = Mensaje(id = UUID.randomUUID().toString(), cuando = System.currentTimeMillis(), clase = Clase.NOTA, texto = t, proyecto = chat)
                almacen.anadir(nota)
                if (!Asistente.preguntar(app, nota, chat)) error("el asistente está contestando otra cosa")
                com.forge.pixpin.guardados.MensajesActivity.irAlMensaje(app, chat, Asistente.asegurarChat(app).nombre, nota.id)
                "Preguntado al asistente."
            }
            "leer_en_voz" -> {
                val t = texto("texto") ?: error("falta el texto")
                android.os.Handler(android.os.Looper.getMainLooper()).post { VozDelAsistente.leer(app, t) }
                "Leyendo en voz alta."
            }
            else -> {
                val fila = filaDe(pedido) ?: error("el teléfono aún no sabe hacer «${(pedido["accion"] as? JsonPrimitive)?.content}»")
                val a = Acciones.AccionApp(fila.herramienta, pedido, null, JsonObject(emptyMap()))
                AccionesDelTelefono(app, almacen, (pedido["proyecto"] as? JsonPrimitive)?.takeIf { it.isString }?.content, { true }).hacer(a).getOrThrow()
            }
        }
    }
}
