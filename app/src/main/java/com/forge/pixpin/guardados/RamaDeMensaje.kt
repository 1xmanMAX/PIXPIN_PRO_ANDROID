package com.forge.pixpin.guardados

import android.content.Context
import com.forge.pixpin.motor.ExcalidrawStore
import com.forge.pixpin.motor.TablasEnDisco
import java.io.File

/**
 * **Reenviar es abrir otra rama**: lo que se reenvía a otro chat se edita por su cuenta, y lo
 * que se le haga allí no toca el mensaje de origen (lo pidió el usuario el 11-sep-2026).
 *
 * El archivo adjunto ya se copiaba (ver `MensajesActivity.reenviarA`), pero lo **editable** no:
 * la copia de una foto anotada, de un dibujo, de una tabla o de un croquis seguía apuntando al
 * mismo dibujo, a la misma tabla y al mismo croquis, así que editar uno editaba el otro. Aquí se
 * duplica lo que el mensaje señala, **tal como está ahora**, con un identificador sacado del
 * mensaje nuevo. Trabajo de disco: fuera del hilo de la pantalla.
 */
object RamaDeMensaje {

    fun separar(context: Context, original: Mensaje, copia: Mensaje): Mensaje {
        val ref = original.referencia
        return when (original.clase) {
            Clase.DIBUJO, Clase.PAGINA ->
                ref?.let { copiarDibujo(context, it, "dib-${copia.id}") }?.let { copia.copy(referencia = it) } ?: copia
            // La foto: su apunte, sea el que se le puso al abrirla o el que se le creó al unirla.
            Clase.IMAGEN -> {
                val suyo = original.dibujoDeLaFoto
                if (!File(ExcalidrawStore.rutaDe(context, suyo)).exists()) copia.copy(referencia = null)
                else copiarDibujo(context, suyo, "foto-${copia.id}")?.let { copia.copy(referencia = it) } ?: copia.copy(referencia = null)
            }
            Clase.TABLA -> ref?.let { id ->
                val almacen = TablasEnDisco.de(context.filesDir)
                val t = almacen.cargar(id) ?: return@let null
                "t-${copia.id}".takeIf { almacen.guardar(it, t.copy(tocado = System.currentTimeMillis())) }
            }?.let { copia.copy(referencia = it) } ?: copia
            Clase.CROQUIS -> ref?.let { id ->
                val json = com.forge.pixpin.croquis3d.Croquis3DAlmacen.jsonDe(context, id) ?: return@let null
                "c3d-${copia.id}".takeIf { com.forge.pixpin.croquis3d.Croquis3DAlmacen.guardarJson(context, it, json) }
            }?.let { copia.copy(referencia = it) } ?: copia
            // Un Excel ya abierto: sus tablas, con lo editado, pasan a ser las del mensaje nuevo.
            Clase.ARCHIVO -> {
                val almacen = TablasEnDisco.de(context.filesDir)
                var i = 0
                while (true) {
                    val t = almacen.cargar(LibroDelChat.idDeHoja(original, i)) ?: break
                    almacen.guardar(LibroDelChat.idDeHoja(copia, i), t)
                    i++
                }
                copia
            }
            else -> copia
        }
    }

    private fun copiarDibujo(context: Context, id: String, nuevo: String): String? {
        val escena = ExcalidrawStore.cargar(ExcalidrawStore.rutaDe(context, id)) ?: return null
        return nuevo.takeIf { ExcalidrawStore.guardar(context, it, escena) != null }
    }
}
