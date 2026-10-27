package com.forge.pixpin.mini

import android.content.Context
import com.forge.pixpin.sincro.CompartidosEnDisco
import com.forge.pixpin.sincro.EnElChat
import com.forge.pixpin.sincro.EntrePersonas
import com.forge.pixpin.sincro.Quien

/**
 * **Las tareas de un chat compartido** (27-oct-2026). El usuario: «cuando creas tareas en un chat
 * compartido, que en el widget de tareas aparezca que esa tarea es compartida, un símbolo que
 * muestre que es de un chat compartido».
 *
 * - **Qué lista es compartida** lo dice el mismo mapa que pone la chapa verde en la lista de chats
 *   ([com.forge.pixpin.guardados.chatsCompartidosDentro]): los chats de `compartidos.json` en los
 *   que sigo dentro, por su proyecto. «Mensajes guardados» no se comparte nunca. Así el símbolo
 *   sale y se va a la vez en la lista de chats, el widget y la pantalla de Tareas.
 * - **Quién la apuntó**: el aparato del mensaje de la lista ([TodasLasTareas.Lista.aparato]),
 *   leído como las burbujas del chat ([com.forge.pixpin.sincro.LadosDelChat.quien]). Es de la
 *   lista entera, no de cada tarea: el documento no guarda quién escribió cada línea.
 *
 * Sin Android lo que se decide ([de], [autor], [inicial], [conSimbolo]), probado en
 * `TareasCompartidasTest`; leerlo del disco, con su caché, en [EnCache]. No cambia nada de lo que
 * se guarda ni de lo que viaja.
 */
object TareasCompartidas {

    /** El símbolo donde solo cabe texto (las chapitas del horario): las dos personas. */
    const val SIMBOLO = "👥"

    /** El chat compartido de la lista [l], o null si no es de ninguno. */
    fun de(l: TodasLasTareas.Lista, compartidos: Map<String, EnElChat>): EnElChat? =
        if (compartidos.isEmpty()) null else l.proyecto?.let { compartidos[it] }

    /** Si la lista [l] es de un chat compartido. */
    fun esCompartida(l: TodasLasTareas.Lista, compartidos: Map<String, EnElChat>): Boolean = de(l, compartidos) != null

    /**
     * El nombre de quien apuntó la lista [l] si fue **otra persona**; null si fui yo (o uno de mis
     * aparatos), si no se sabe o si la lista no es de un chat compartido.
     */
    fun autor(l: TodasLasTareas.Lista, compartidos: Map<String, EnElChat>): String? {
        val c = de(l, compartidos) ?: return null
        return (c.lados.quien(EntrePersonas.aparatoCorto(l.aparato)) as? Quien.Otro)?.nombre
    }

    /** La inicial de un nombre, en mayúscula y entera aunque sea un emoji o una letra compuesta. */
    fun inicial(nombre: String): String {
        val t = nombre.trim()
        if (t.isEmpty()) return ""
        return t.substring(0, t.offsetByCodePoints(0, 1)).uppercase()
    }

    /** [texto] con el [SIMBOLO] delante si es [compartida]. */
    fun conSimbolo(texto: String, compartida: Boolean): String = if (compartida) "$SIMBOLO $texto" else texto

    /**
     * **Los chats compartidos, guardados un rato** (27-oct-2026). El widget se repinta con cada
     * cambio del chat y cada media hora; releer `compartidos.json` (y la identidad) cada vez sería
     * tirar disco. Se vuelve a leer cuando sube [CompartidosEnDisco.version] (todo lo que guarda
     * esa lista la sube) o pasados [VIGENCIA_MS], por si cambiaron mis aparatos, que no la suben.
     * Sin chats compartidos no se mira la identidad: lo hace ya [EntrePersonas.porChat].
     */
    object EnCache {
        const val VIGENCIA_MS = 5 * 60_000L

        private class Leido(val version: Int, val cuando: Long, val mapa: Map<String, EnElChat>)

        @Volatile private var leido: Leido? = null

        fun leer(contexto: Context, ahora: Long = System.currentTimeMillis()): Map<String, EnElChat> {
            val version = CompartidosEnDisco.version.value
            leido?.takeIf { it.version == version && ahora - it.cuando in 0 until VIGENCIA_MS }?.let { return it.mapa }
            val mapa = runCatching { com.forge.pixpin.guardados.chatsCompartidosDentro(contexto) }.getOrDefault(emptyMap())
            leido = Leido(version, ahora, mapa)
            return mapa
        }
    }
}
