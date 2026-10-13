package com.forge.pixpin.guardados

import android.content.Context
import com.forge.pixpin.motor.TablaDeCalculo
import com.forge.pixpin.motor.TablasEnDisco
import com.forge.pixpin.motor.TablasEnMensajes
import java.io.File
import java.util.UUID

/**
 * **Las tablas que son mensajes del chat**, como en el PC (13-oct-2026; el usuario: «toma la
 * forma de la PC… toda edición, todo cambio es sincronizable»).
 *
 * Dos clases de tabla viven en el `texto` de un mensaje `MINIAPP` con `miniapp = "tabla"`, el
 * JSON exacto de [TablaDeCalculo]:
 *
 * - **La mini-app `tabla`** que se crea en el PC. Aquí se abre con el id `mini-<mensaje>`.
 * - **Cada hoja editada de un Excel del chat**, con `referencia = "libro-<mensaje>-<hoja>"` (la
 *   clave de [LibroDelChat.idDeHoja] y de `hoja_pin/libro.rs`). Hasta hoy el móvil las guardaba
 *   en `tablas/libro-….json` y el PC en mensajes, y ninguno veía lo del otro.
 *
 * Al ser mensajes, viajan con la fusión de mensajes de siempre y, como las tareas, **salen al
 * instante** ([MensajesStore.chatsConTareasCambiadas]).
 *
 * Se engancha en [TablasEnDisco.enMensajes]: cualquiera que cargue o guarde una tabla por su id
 * (el editor, la miniatura, el PDF del proyecto, el `.pixpin`) pasa por aquí sin saberlo.
 */
class TablasDelChat(private val context: Context) : TablasEnMensajes {

    private val carpetaPropia: File = TablasEnDisco.de(context.filesDir).archivo("x").parentFile!!

    private fun esMia(carpeta: File) = carpeta.absoluteFile == carpetaPropia.absoluteFile

    override fun leer(carpeta: File, id: String): String? {
        if (!esMia(carpeta)) return null
        return mensajeDe(MensajesStore(context).leer(), id)?.texto
    }

    override fun escribir(carpeta: File, id: String, tabla: TablaDeCalculo): Boolean {
        if (!esMia(carpeta)) return false
        val almacen = MensajesStore(context)
        val ahora = System.currentTimeMillis()
        var hecho = false
        almacen.cambiar { todos ->
            val m = mensajeDe(todos, id) ?: return@cambiar todos
            hecho = true
            todos.map { if (it.id == m.id) alGuardar(it, tabla, ahora) else it }
        }
        if (hecho) return true
        // Una mini-app que se borró del chat mientras se editaba no se resucita en un archivo suelto
        // (el PC hace igual: «se borró del chat… no se guarda»).
        if (id.startsWith(TablasEnMensajes.MINI)) return true
        // Una hoja de un libro que aún no tenía mensajes: nacen ahora, **todas** las del libro, como
        // en el PC (`Editable::guardar`): así el otro aparato las ve en el mismo orden.
        if (!id.startsWith(TablasEnMensajes.LIBRO)) return false
        val (libro, hoja) = partirLibro(id) ?: return false
        val excel = almacen.leer().firstOrNull { it.id == libro } ?: return false
        return crearLasDelLibro(almacen, excel, hoja to tabla, ahora)
    }

    /**
     * **Migración**: los mensajes de las hojas de [excel] a partir de los `tablas/libro-….json` que
     * ya hubiera de antes. Lo viejo no se borra. No hace nada si ya hay mensajes o no hay archivos.
     */
    fun migrar(excel: Mensaje): Boolean {
        val almacen = MensajesStore(context)
        if (hechas(almacen.leer(), excel.id).isNotEmpty()) return false
        return crearLasDelLibro(almacen, excel, null, System.currentTimeMillis())
    }

    private fun crearLasDelLibro(almacen: MensajesStore, excel: Mensaje, editada: Pair<Int, TablaDeCalculo>?, ahora: Long): Boolean {
        val disco = TablasEnDisco(carpetaPropia)
        val hojas = ArrayList<Pair<TablaDeCalculo, String?>>()
        while (true) {
            val i = hojas.size
            val archivo = disco.archivo(LibroDelChat.idDeHoja(excel, i))
            val t = if (editada?.first == i) editada.second
            else if (archivo.exists()) TablaDeCalculo.deJson(archivo.readText()) else null
            if (t == null) break
            hojas += t to (if (archivo.exists()) runCatching { archivo.readText() }.getOrNull() else null)
        }
        if (hojas.isEmpty() || (editada != null && editada.first >= hojas.size)) return false
        // Con la llave del chat puesta y mirando otra vez: dos guardados seguidos no las crean dos
        // veces, y la hoja que ya tenga mensaje no se repite (solo se pone al día la editada).
        synchronized(com.forge.pixpin.sincro.Cerrojos.chat) {
            val ya = hechas(almacen.leer(), excel.id).associate { it.first to it.second }
            for ((i, par) in hojas.withIndex()) {
                val (t, original) = par
                val suyo = ya[i]
                if (suyo != null) {
                    if (editada?.first == i) almacen.cambiar { todos -> todos.map { if (it.id == suyo.id) alGuardar(it, t, ahora) else it } }
                    continue
                }
                // Cada hoja un milisegundo detrás de la anterior, como en el PC (`mensajes_de_hojas`).
                val cuando = ahora + i
                val tocado = if (editada?.first == i) editadaA(ahora, cuando) else t.tocado
                almacen.anadir(
                    Mensaje(
                        id = UUID.randomUUID().toString(),
                        cuando = cuando,
                        clase = Clase.MINIAPP,
                        miniapp = MINIAPP,
                        nombre = t.nombre,
                        texto = TablaDeCalculo.aJsonSobre(t.copy(tocado = tocado), original),
                        referencia = LibroDelChat.idDeHoja(excel, i),
                        proyecto = excel.proyecto
                    )
                )
            }
        }
        return true
    }

    companion object {
        /** La palabra de `Mensaje.miniapp` de una tabla: la del PC (`tabla::MINIAPP`). No se cambia nunca. */
        const val MINIAPP = "tabla"

        fun esTabla(m: Mensaje) = m.clase == Clase.MINIAPP && m.miniapp == MINIAPP

        /** El id con que [com.forge.pixpin.tabla.TablaActivity] abre la mini-app tabla [m]. */
        fun idDe(m: Mensaje) = TablasEnMensajes.MINI + m.id

        /** El mensaje que guarda la tabla [id], si lo hay. */
        fun mensajeDe(todos: List<Mensaje>, id: String): Mensaje? = when {
            id.startsWith(TablasEnMensajes.MINI) -> id.removePrefix(TablasEnMensajes.MINI).let { m -> todos.firstOrNull { it.id == m && esTabla(it) } }
            id.startsWith(TablasEnMensajes.LIBRO) -> todos.firstOrNull { esTabla(it) && it.referencia == id }
            else -> null
        }

        /** Las tablas ya hechas del libro [libro] (id del mensaje del Excel), en el orden de sus hojas. */
        fun hechas(todos: List<Mensaje>, libro: String): List<Pair<Int, Mensaje>> {
            val prefijo = "${TablasEnMensajes.LIBRO}$libro-"
            return todos.filter { esTabla(it) }
                .mapNotNull { m ->
                    val r = m.referencia ?: return@mapNotNull null
                    if (!r.startsWith(prefijo)) null else r.removePrefix(prefijo).toIntOrNull()?.let { it to m }
                }
                .sortedBy { it.first }
        }

        /** `libro-<mensaje>-<hoja>` → mensaje y hoja. El id del mensaje puede llevar guiones: la hoja es lo último. */
        fun partirLibro(id: String): Pair<String, Int>? {
            val resto = id.removePrefix(TablasEnMensajes.LIBRO)
            val i = resto.lastIndexOf('-')
            if (i <= 0) return null
            return resto.substring(0, i) to (resto.substring(i + 1).toIntOrNull() ?: return null)
        }

        /**
         * El mensaje [m] con [tabla] guardada en su texto: **lo que no se conoce del JSON de antes se
         * queda** ([TablaDeCalculo.aJsonSobre]) y `tocado` va siempre claramente después de la hora
         * del mensaje, para que el PC sepa que se editó (`libro::editada`, `editada_a`).
         */
        fun alGuardar(m: Mensaje, tabla: TablaDeCalculo, ahora: Long): Mensaje {
            val t = tabla.copy(tocado = editadaA(maxOf(ahora, tabla.tocado), m.cuando))
            return m.copy(
                texto = TablaDeCalculo.aJsonSobre(t, m.texto),
                nombre = t.nombre.ifBlank { m.nombre }
            )
        }

        /** Como `editada_a` del PC: ahora, pero al menos 2001 ms después de que naciera el mensaje. */
        fun editadaA(ahora: Long, cuando: Long) = maxOf(ahora, cuando + 2_001)
    }
}
