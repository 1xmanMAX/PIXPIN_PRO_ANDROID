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

    /**
     * **`al_lienzo`** (PC 17-oct): el lienzo que se llame así (sin tildes ni mayúsculas; del `proyecto`
     * dicho o, sin él, de cualquier chat; si no hay ninguno, uno nuevo en `proyecto` o en Mensajes
     * guardados), y en él, apiladas, las fotos y debajo el texto (sin los `[img NN]`). Abierto, en vivo.
     */
    internal fun alLienzo(pedido: JsonObject, almacen: MensajesStore): String {
        fun texto(k: String) = (pedido[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
        val nombre = texto("lienzo") ?: error("falta el nombre del lienzo")
        val proyecto = texto("proyecto")
        // Sin tildes ni mayúsculas, como el PC.
        fun llano(t: String) = java.text.Normalizer.normalize(t.trim().lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        val buscado = llano(nombre)
        val lienzos = almacen.leer().filter { it.clase == Clase.DIBUJO && it.referencia != null && llano(it.nombre) == buscado }
        val ahora = System.currentTimeMillis()
        val id = ((if (proyecto != null) lienzos.lastOrNull { it.proyecto == proyecto } else null) ?: lienzos.lastOrNull())?.referencia
            ?: ("dib-$ahora").also { nuevo ->
                com.forge.pixpin.motor.ExcalidrawStore.guardar(contexto, nuevo, com.forge.pixpin.motor.Scene()) ?: error("no se pudo crear el lienzo")
                almacen.anadir(Mensaje(id = UUID.randomUUID().toString(), cuando = ahora, clase = Clase.DIBUJO, nombre = nombre, referencia = nuevo, proyecto = proyecto))
            }
        val fotos = (pedido["imagenes"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }.mapNotNull { r ->
            val f = File(r).takeIf { it.isFile } ?: return@mapNotNull null
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(f.path, o)
            val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "image/png"
            com.forge.pixpin.motor.ExcalidrawStore.guardarImagen(contexto, f, mime)?.let { Triple(it, o.outWidth, o.outHeight) }
        }
        val dicho = texto("texto")?.replace(Regex("\\[img \\d+\\]"), "")?.trim()?.takeIf { it.isNotEmpty() }
        // El texto nace como al pegarlo en el lienzo (la letra y el tamaño de siempre), y luego va a la pila.
        val elTexto = dicho?.let { t ->
            com.forge.pixpin.motor.DrawController(com.forge.pixpin.motor.Scene()).pegarTexto(t, com.forge.pixpin.motor.Pt(0.0, 0.0), com.forge.pixpin.motor.PilaDelLienzo.ANCHO_COLUMNA,
                { r, fam, tam -> com.forge.pixpin.motor.DrawFonts.medirTexto(contexto, r, fam, tam).first },
                { r, fam, tam -> com.forge.pixpin.motor.DrawFonts.medirTexto(contexto, r, fam, tam) })
        }
        if (fotos.isEmpty() && elTexto == null) error("falta el texto o alguna imagen")
        val abierto = com.forge.pixpin.motor.LienzoAlFrente.actual() as? com.forge.pixpin.motor.EditaElLienzo
        // En el hilo de la interfaz, sin `runBlocking(Main)` (se cuelga si ya se está en él).
        val enVivo = abierto != null && enLaInterfaz {
            val a = abierto.paraEditar()?.takeIf { it.id == id } ?: return@enLaInterfaz false
            val apilada = com.forge.pixpin.motor.PilaDelLienzo.apilar(a.escena, fotos, elTexto, ahora)
            abierto.ponerEscena(apilada.elements, apilada.files)
        }
        if (!enVivo) {
            val ruta = com.forge.pixpin.motor.ExcalidrawStore.rutaDe(contexto, id)
            val escena = com.forge.pixpin.motor.ExcalidrawStore.cargar(ruta) ?: com.forge.pixpin.motor.Scene()
            com.forge.pixpin.motor.ExcalidrawStore.guardar(contexto, id, com.forge.pixpin.motor.PilaDelLienzo.apilar(escena, fotos, elTexto, ahora))
                ?: error("no se pudo guardar el lienzo")
        }
        return "Apilado en el lienzo «$nombre»."
    }

    private fun enLaInterfaz(bloque: () -> Boolean): Boolean {
        val principal = android.os.Looper.getMainLooper()
        if (android.os.Looper.myLooper() == principal) return bloque()
        val hecho = java.util.concurrent.CountDownLatch(1)
        var r = false
        android.os.Handler(principal).post { try { r = bloque() } catch (_: Throwable) { } finally { hecho.countDown() } }
        hecho.await(10, java.util.concurrent.TimeUnit.SECONDS)
        return r
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
            // Fotos y texto a un lienzo por su nombre, apilados (PC `pila_lienzo.rs`).
            "al_lienzo" -> alLienzo(pedido, almacen)
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
