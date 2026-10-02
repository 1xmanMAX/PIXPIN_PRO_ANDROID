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

    private const val PREFIJO_PROYECTO = "proyecto-"

    /**
     * Cuántos proyectos. Los lanzadores enseñan cuatro o cinco atajos al mantener pulsado el
     * icono, y los cuatro fijos ya ocupan su sitio: con tres, los proyectos asoman sin echar
     * a nadie, y el buscador los encuentra todos igual.
     */
    private const val CUANTOS = 3

    fun sena(accion: String, id: String? = null): Uri =
        Uri.parse("$ESQUEMA://$ANFITRION/$accion" + if (id != null) "/" + Uri.encode(id) else "")

    /** Los atajos dinámicos, al día con [proyectos]. Barato: solo reescribe si algo cambió. */
    fun ponerAlDia(context: Context, proyectos: List<Proyecto>) {
        val elegidos = proyectos.asSequence()
            .filter { !it.archivado && it.nombre.isNotBlank() }
            .sortedByDescending { it.tocado }
            .take(CUANTOS)
            .toList()
        val firma = elegidos.joinToString("\u0000") { it.id + "\u0001" + it.nombre }
        if (firma == ultimaFirma) return
        ultimaFirma = firma
        val atajos = elegidos.mapIndexed { i, p ->
            ShortcutInfoCompat.Builder(context, PREFIJO_PROYECTO + p.id)
                .setShortLabel(p.nombre.take(24))
                .setLongLabel(context.getString(R.string.atajo_proyecto_largo, p.nombre).take(48))
                .setIcon(IconCompat.createWithResource(context, R.drawable.atajo_proyecto))
                .setIntent(
                    Intent(Intent.ACTION_VIEW, sena(PROYECTO, p.id))
                        .setClass(context, AtajoActivity::class.java)
                )
                // **Que dure**: el buscador y las sugerencias del sistema solo guardan los atajos
                // marcados así; los demás se olvidan en cuanto la aplicación los quita.
                .setLongLived(true)
                .setRank(i)
                .build()
        }
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, atajos) }
            .onFailure { ultimaFirma = null }
    }

    /**
     * Avisa de que se ha usado un atajo, para que el sistema aprenda qué sugerir. Lo piden
     * los lanzadores que ordenan por uso (Pixel, One UI); a los demás no les estorba.
     */
    fun usado(context: Context, accion: String, id: String?) {
        val atajo = if (accion == PROYECTO && id != null) PREFIJO_PROYECTO + id else accion
        runCatching { ShortcutManagerCompat.reportShortcutUsed(context, atajo) }
    }

    /** Un proyecto borrado no puede seguir en el buscador: tocarlo no abriría nada. */
    fun olvidar(context: Context, idsQueYaNoEstan: List<String>) {
        if (idsQueYaNoEstan.isEmpty()) return
        runCatching {
            ShortcutManagerCompat.removeLongLivedShortcuts(context, idsQueYaNoEstan.map { PREFIJO_PROYECTO + it })
        }
    }

    @Volatile private var ultimaFirma: String? = null
}
