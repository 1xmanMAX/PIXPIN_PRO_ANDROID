package com.forge.pixpin.asistente

import android.content.Context
import com.forge.pixpin.guardados.Clase
import com.forge.pixpin.guardados.Mensaje
import com.forge.pixpin.guardados.MensajesStore
import java.io.File

/**
 * **La biblioteca de figuras viaja por el chat «Asistente»** (§3.7 del manual del PC): el fichero
 * `pins/draw/figuras.json` (el de [com.forge.pixpin.motor.BibliotecaStore]) lo señala UN mensaje
 * `ARCHIVO` de id fijo `auto-figuras`; la sincronización de siempre se lleva el fichero con él
 * (su ruta sale como `pixpin:files/pins/draw/figuras.json`). Gana el tocado más tarde, como un
 * fichero suelto.
 */
object FigurasQueViajan {
    const val ID = "auto-figuras"
    const val TEXTO = "🧩 Las figuras guardadas del lienzo (se usan desde el menu de figuras)"

    /** Pone el mensaje si aún no está (uno solo, en el chat del asistente). Disco: fuera de la pantalla. */
    fun asegurarMensaje(c: Context) {
        val almacen = MensajesStore(c)
        if (almacen.leer().any { it.id == ID }) return
        val fichero = File(File(c.filesDir, "pins/draw"), "figuras.json")
        if (!fichero.isFile) return
        val chat = Asistente.asegurarChat(c)
        almacen.anadir(
            Mensaje(
                id = ID, cuando = System.currentTimeMillis(), clase = Clase.ARCHIVO,
                ruta = fichero.absolutePath, nombre = "figuras.json", bytes = fichero.length(),
                texto = TEXTO, proyecto = chat.id
            )
        )
    }
}
