package com.forge.pixpin.asistente

import android.content.Context
import android.util.Log
import com.forge.pixpin.PixPinApp
import com.forge.pixpin.guardados.Clase
import com.forge.pixpin.guardados.Mensaje
import com.forge.pixpin.guardados.MensajesStore
import com.forge.pixpin.ia.MemoriaLarga
import com.forge.pixpin.ia.Nube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.TimeZone

/**
 * **La memoria de largo plazo en el chat «Asistente»** (`apps/pixpin/src/asistente/memoria.rs`
 * del PC, 22-oct-2026; aquí desde el 25-oct-2026): el almacén de [MemoriaLarga] en la carpeta que
 * viaja a los aparatos propios, y lo que la app hace con ella (meterla en cada turno, guardar lo
 * duradero al empezar una conversación nueva).
 *
 * **Dónde vive**: `files/guardados/asistente/memoria/` (`pixpin:files/guardados/asistente/memoria/`,
 * la misma ruta portátil que el PC). **Cómo viaja**: un mensaje `ARCHIVO` por fichero con id fijo
 * `auto-memoria-<ruta limpia>` ([idDe], el `id_de` del PC): la sincronización manda solo lo que
 * cambió, dos aparatos que escriben temas distintos no se pisan, y borrar un tema quita su mensaje
 * con marca y con él el fichero en el otro aparato. Son mensajes de sistema (`auto-`): van en la
 * franja, no son burbujas.
 */
object MemoriaQueViaja {
    private const val TAG = "PixPinMemoria"

    /** Dentro de `files/`. */
    const val CARPETA = "guardados/asistente/memoria"
    /** El principio del id del mensaje de cada fichero. */
    const val PREFIJO_ID = "auto-memoria-"
    /** El texto del mensaje (el del PC). */
    const val TEXTO = "🧠 Memoria del asistente (lo que recuerda entre conversaciones; solo va a tus aparatos)"

    /** El id fijo del mensaje del fichero [rel] (`PERFIL.md`, `temas/x.md`): `disco::limpio` del PC. */
    fun idDe(rel: String): String = PREFIJO_ID + rel.lowercase().map { c ->
        if ((c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') || c == '.' || c == '_' || c == '-') c else '_'
    }.joinToString("")

    fun carpeta(c: Context) = File(c.filesDir, CARPETA)

    /**
     * **El almacén que viaja**: la carpeta de siempre, y por cada fichero que se escribe o se borra,
     * su mensaje `ARCHIVO` en el chat «Asistente».
     */
    class Almacen(private val contexto: Context) : MemoriaLarga.EnCarpeta(carpeta(contexto)) {
        override fun escribir(rel: String, texto: String) {
            super.escribir(rel, texto)
            runCatching { senalar(rel) }.onFailure { Log.w(TAG, "no se pudo señalar $rel para la sincronización", it) }
        }

        override fun borrar(rel: String) {
            super.borrar(rel)
            // Con su marca de borrado (la pone `MensajesStore.cambiar`): así el otro aparato lo quita también.
            runCatching { val id = idDe(rel); MensajesStore(contexto).cambiar { l -> l.filterNot { it.id == id } } }
                .onFailure { Log.w(TAG, "no se pudo quitar el mensaje de $rel", it) }
        }

        /** Pone el mensaje del fichero si no está (el fichero cambia, el mensaje es el mismo). */
        private fun senalar(rel: String) {
            val almacen = MensajesStore(contexto)
            val id = idDe(rel)
            if (almacen.leer().any { it.id == id }) return
            val f = ruta(rel)
            val chat = Asistente.asegurarChat(contexto)
            almacen.anadir(
                Mensaje(
                    id = id, cuando = System.currentTimeMillis(), clase = Clase.ARCHIVO,
                    ruta = f.absolutePath, nombre = f.name, bytes = f.length(), texto = TEXTO, proyecto = chat.id
                )
            )
        }
    }

    /** El día de hoy en la hora del teléfono: la fecha de lo que se guarda. */
    fun hoy(): String {
        val ahora = System.currentTimeMillis()
        return MemoriaLarga.fechaDeMs(ahora + TimeZone.getDefault().getOffset(ahora))
    }

    /** **La memoria del teléfono**, con [quien] como procedencia de lo que se escriba. */
    fun de(c: Context, quien: String): MemoriaLarga.Memoria = MemoriaLarga.Memoria(Almacen(c.applicationContext), quien, hoy())

    /**
     * **Al cerrar una conversación** («Conversación nueva»): lo duradero de la que se cierra, en
     * segundo plano, con la nube y su clave (Claude, lo más barato, como el PC; si solo hay la de
     * DeepSeek, con ella). Sin clave no se hace nada: la memoria sigue como estaba. No espera: el
     * botón no añade latencia.
     */
    fun alCerrar(c: Context, conversacion: List<Pair<Boolean, String>>) {
        if (conversacion.none { (u, t) -> u && t.isNotBlank() }) return
        val app = c.applicationContext as? PixPinApp ?: return
        app.scope.launch(Dispatchers.IO) {
            val proveedor = listOf(Nube.Proveedor.CLAUDE, Nube.Proveedor.DEEPSEEK).firstOrNull { ClavesDeLaNube.leer(app, it) != null } ?: return@launch
            val clave = ClavesDeLaNube.leer(app, proveedor) ?: return@launch
            runCatching {
                val n = MemoriaLarga.guardarLoDuradero(Nube.Motor(proveedor, clave, ligero = true), de(app, proveedor.nombre), MemoriaLarga.transcripcion(conversacion))
                Log.i(TAG, "guardado lo duradero: $n escrituras")
            }.onFailure { Log.w(TAG, "no se pudo guardar lo duradero", it) }
        }
    }
}
