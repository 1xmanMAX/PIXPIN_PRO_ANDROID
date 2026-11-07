package com.forge.pixpin.cuenta

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ShortcutManager

/**
 * **Lo que el sistema enseña de PinDesk, según el catálogo** (7-nov-2026): los atajos de mantener el icono, el widget
 * de la galería y la baldosa de capturar de los ajustes rápidos. Si su función está oculta, no salen; al abrirla
 * desde el servidor, vuelven solos (se mira al arrancar y cada vez que llega un catálogo).
 */
object AlSistema {
    /** Cada atajo fijo (`res/xml/shortcuts.xml`) con la función de la que depende. */
    private val ATAJOS = mapOf(
        "buscar" to Disponible.CHATS, "capturar" to Disponible.CAPTURAS, "grabar" to Disponible.GRABAR,
        "nueva_tarea" to Disponible.TAREAS, "nueva_leccion" to Disponible.LECCIONES, "lecciones" to Disponible.LECCIONES,
        "mensajes" to Disponible.CHATS, "proyectos" to Disponible.CHATS, "sincronizar" to Disponible.SINCRONIZAR,
        "capturas" to Disponible.CAPTURAS,
    )
    /** Los componentes que se apagan con su función. */
    private val COMPONENTES = mapOf(
        com.forge.pixpin.widget.WidgetDeGaleria::class.java.name to Disponible.CAPTURAS,
        com.forge.pixpin.floating.CaptureTileService::class.java.name to Disponible.CAPTURAS,
    )

    fun aplicar(c: Context) {
        runCatching {
            val sm = c.getSystemService(ShortcutManager::class.java) ?: return@runCatching
            val (si, no) = ATAJOS.entries.partition { Disponible.ve(it.value) }
            if (no.isNotEmpty()) sm.disableShortcuts(no.map { it.key }, "No disponible en esta versión")
            if (si.isNotEmpty()) sm.enableShortcuts(si.map { it.key })
        }
        val pm = c.packageManager
        for ((clase, id) in COMPONENTES) runCatching {
            val nombre = ComponentName(c.packageName, clase)
            val quiero = if (Disponible.ve(id)) PackageManager.COMPONENT_ENABLED_STATE_DEFAULT else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            if (pm.getComponentEnabledSetting(nombre) != quiero) pm.setComponentEnabledSetting(nombre, quiero, PackageManager.DONT_KILL_APP)
        }
    }
}
