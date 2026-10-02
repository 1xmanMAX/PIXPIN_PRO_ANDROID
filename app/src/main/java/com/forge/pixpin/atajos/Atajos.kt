package com.forge.pixpin.atajos

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.forge.pixpin.R
import com.forge.pixpin.motor.Proyecto

/**
 * **PixPin en el buscador del teléfono** (2-oct-2026).
 *
 * Samsung Finder, la búsqueda de Huawei y Honor, la del Pixel y casi todos los lanzadores leen
 * lo mismo: **los atajos de la aplicación**. Los fijos están en `xml/shortcuts.xml`; aquí van los
 * que cambian, que son **los últimos proyectos tocados**. Así, escribir «Tesis» en el buscador
 * ofrece abrir el proyecto «Tesis» sin pasar por la lista, y mantener pulsado el icono también.
 * **Y los últimos archivos del chat** —un PDF, una foto, una nota de voz, un lienzo—, que se
 * abren directamente con su visor, como al tocarlos en Mensajes guardados.
 *
 * Todos entran por [AtajoActivity], con una seña `pixpin://atajo/...`: el atajo guarda solo
 * esa seña, y lo que abre se decide al tocarlo, con la aplicación ya al día.
 */
object Atajos {
    const val ESQUEMA = "pixpin"
    const val ANFITRION = "atajo"
    const val CAPTURAR = "capturar"
    const val MENSAJES = "mensajes"
    const val PROYECTOS = "proyectos"
    const val SINCRONIZAR = "sincronizar"
    /** `pixpin://atajo/proyecto/<id>` */
    const val PROYECTO = "proyecto"

    /** `pixpin://atajo/archivo/<id del mensaje>`: un archivo del chat, abierto con su visor. */
    const val ARCHIVO = "archivo"

    private const val PREFIJO_PROYECTO = "proyecto-"
    private const val PREFIJO_ARCHIVO = "archivo-"

    /**
     * Cuántos proyectos. Los lanzadores enseñan cuatro o cinco atajos al mantener pulsado el
     * icono, y los cuatro fijos ya ocupan su sitio: con tres, los proyectos asoman sin echar
     * a nadie, y el buscador los encuentra todos igual.
     */
    private const val CUANTOS_PROYECTOS = 3

    /**
     * Cuántos archivos del chat, como mucho. **El sistema pone un tope a los atajos de una
     * aplicación** (15 en casi todos, 10 en algunos; ver [huecos]): no caben todos los
     * archivos, así que van los más recientes, que son los que uno busca.
     */
    private const val CUANTOS_ARCHIVOS = 8

    /** Los atajos fijos de `xml/shortcuts.xml`: cuentan para el tope. */
    private const val FIJOS = 4

    private val CON_ARCHIVO = setOf(
        com.forge.pixpin.guardados.Clase.IMAGEN, com.forge.pixpin.guardados.Clase.ARCHIVO,
        com.forge.pixpin.guardados.Clase.VOZ, com.forge.pixpin.guardados.Clase.DIBUJO,
        com.forge.pixpin.guardados.Clase.PAGINA, com.forge.pixpin.guardados.Clase.TABLA,
        com.forge.pixpin.guardados.Clase.CROQUIS
    )

    fun sena(accion: String, id: String? = null): Uri =
        Uri.parse("$ESQUEMA://$ANFITRION/$accion" + if (id != null) "/" + Uri.encode(id) else "")

    private fun huecos(context: Context): Int =
        (runCatching { ShortcutManagerCompat.getMaxShortcutCountPerActivity(context) }.getOrDefault(10)
            .takeIf { it > 0 } ?: 10) - FIJOS

    /**
     * Los atajos dinámicos, al día con [proyectos] y con los archivos del chat ([mensajes]).
     * Barato: solo toca el sistema si algo cambió. Trabajo de disco no hace; el llamador ya leyó.
     */
    fun ponerAlDia(context: Context, proyectos: List<Proyecto>, mensajes: List<com.forge.pixpin.guardados.Mensaje>) {
        val sitio = huecos(context)
        val losProyectos = proyectos.asSequence()
            .filter { !it.archivado && it.nombre.isNotBlank() }
            .sortedByDescending { it.tocado }
            .take(minOf(CUANTOS_PROYECTOS, sitio))
            .toList()
        val losArchivos = mensajes.asSequence()
            .filter { esArchivo(it) }
            .mapNotNull { m -> nombreDe(m)?.let { m to it } }
            .sortedByDescending { it.first.cuando }
            .take(minOf(CUANTOS_ARCHIVOS, sitio - losProyectos.size).coerceAtLeast(0))
            .toList()
        val firma = losProyectos.joinToString("\u0000") { it.id + "\u0001" + it.nombre } + "\u0002" +
            losArchivos.joinToString("\u0000") { it.first.id + "\u0001" + it.second }
        if (firma == ultimaFirma) return
        ultimaFirma = firma
        val atajos = losProyectos.map { p ->
            atajo(context, PREFIJO_PROYECTO + p.id, p.nombre,
                context.getString(R.string.atajo_proyecto_largo, p.nombre),
                R.drawable.atajo_proyecto, sena(PROYECTO, p.id))
        } + losArchivos.map { (m, nombre) ->
            atajo(context, PREFIJO_ARCHIVO + m.id, nombre, nombre, iconoDe(m), sena(ARCHIVO, m.id))
        }
        runCatching {
            // **Lo que sale de la lista sale del buscador**: un archivo borrado del chat, o un
            // proyecto borrado en otro aparato y sincronizado, no puede quedarse ahí —tocarlo
            // no abriría nada—. Los de larga vida sobreviven a `setDynamicShortcuts`, de ahí
            // que se quiten aparte.
            val quedan = atajos.mapTo(HashSet()) { it.id }
            val sobran = ShortcutManagerCompat.getDynamicShortcuts(context).map { it.id }.filter { it !in quedan }
            if (sobran.isNotEmpty()) ShortcutManagerCompat.removeLongLivedShortcuts(context, sobran)
            ShortcutManagerCompat.setDynamicShortcuts(context, atajos.mapIndexed { i, a ->
                ShortcutInfoCompat.Builder(a).setRank(i).build()
            })
        }.onFailure { ultimaFirma = null }
    }

    private fun atajo(context: Context, id: String, corta: String, larga: String, icono: Int, sena: Uri) =
        ShortcutInfoCompat.Builder(context, id)
            .setShortLabel(corta.take(24))
            .setLongLabel(larga.take(48))
            .setIcon(IconCompat.createWithResource(context, icono))
            .setIntent(Intent(Intent.ACTION_VIEW, sena).setClass(context, AtajoActivity::class.java))
            // **Que dure**: el buscador y las sugerencias del sistema solo guardan los atajos
            // marcados así; los demás se olvidan en cuanto la aplicación los quita.
            .setLongLived(true)
            .build()

    /** Si un mensaje del chat es un archivo que se puede abrir, y no texto suelto. */
    fun esArchivo(m: com.forge.pixpin.guardados.Mensaje): Boolean =
        m.clase in CON_ARCHIVO && !m.enBuzon && (m.ruta != null || m.referencia != null)

    /** Cómo se llama en el buscador: su nombre, o el del archivo si no tiene. */
    fun nombreDe(m: com.forge.pixpin.guardados.Mensaje): String? =
        m.nombre.trim().ifBlank { m.ruta?.substringAfterLast('/')?.trim().orEmpty() }.takeIf { it.isNotBlank() }

    private fun iconoDe(m: com.forge.pixpin.guardados.Mensaje): Int = when (m.clase) {
        com.forge.pixpin.guardados.Clase.IMAGEN -> R.drawable.atajo_imagen
        com.forge.pixpin.guardados.Clase.VOZ -> R.drawable.atajo_voz
        com.forge.pixpin.guardados.Clase.DIBUJO, com.forge.pixpin.guardados.Clase.PAGINA,
        com.forge.pixpin.guardados.Clase.CROQUIS -> R.drawable.atajo_dibujo
        else -> R.drawable.atajo_archivo
    }

    /**
     * Avisa de que se ha usado un atajo, para que el sistema aprenda qué sugerir. Lo piden
     * los lanzadores que ordenan por uso (Pixel, One UI); a los demás no les estorba.
     */
    fun usado(context: Context, accion: String, id: String?) {
        val atajo = when {
            accion == PROYECTO && id != null -> PREFIJO_PROYECTO + id
            accion == ARCHIVO && id != null -> PREFIJO_ARCHIVO + id
            else -> accion
        }
        runCatching { ShortcutManagerCompat.reportShortcutUsed(context, atajo) }
    }

    @Volatile private var ultimaFirma: String? = null
}
