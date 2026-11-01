package com.forge.pixpin.ajustes

import android.app.Activity
import android.app.Application
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.forge.pixpin.R
import java.util.Locale

/**
 * **Pone a la aplicación en su idioma** (1-nov-2026). La regla está en [resolverIdioma]; aquí,
 * cómo se aplica en Android y dónde se guarda.
 *
 * - **Se guarda en el teléfono**, en sus preferencias y no en los ajustes que viajan: como en el
 *   PC, cada aparato tiene su idioma.
 * - **Android 13 o más**: con los idiomas por aplicación ([LocaleManager]), que además lo enseñan
 *   en Ajustes del sistema › Idioma de las apps (`res/xml/locales_config.xml`). El sistema rehace
 *   las pantallas abiertas al cambiarlo.
 * - **Android 10 a 12**: no hay idiomas por aplicación, así que se cambia la configuración de los
 *   recursos de la aplicación y de **cada pantalla al nacer** (`onActivityPreCreated`, como
 *   [com.forge.pixpin.motor.PantallaFluida]: son muchas y sin clase base común). La pantalla desde
 *   la que se cambia se rehace; las de detrás cambian al volver a abrirse.
 *
 * Con «el del teléfono» **se fija siempre un idioma explícito** (`es…` o `en…`), nunca «el del
 * sistema» de Android: con el teléfono en francés Android usaría `values/`, que es el español, y la
 * regla del PC dice inglés. Por eso también se vuelve a mirar al arrancar y cuando el teléfono
 * cambia de idioma.
 *
 * En Robolectric no se toca nada: las pruebas leen los textos de fábrica (español).
 */
object IdiomaDeLaApp {

    private const val ARCHIVO = "idioma"
    private const val CLAVE = "preferencia"

    @Volatile
    private var instalado = false

    fun preferencia(contexto: Context): PreferenciaIdioma =
        PreferenciaIdioma.deClave(
            runCatching { contexto.getSharedPreferences(ARCHIVO, Context.MODE_PRIVATE).getString(CLAVE, null) }.getOrNull()
        )

    /** El locale del **teléfono**, no el de la aplicación (que ya puede llevar el nuestro puesto). */
    fun localeDelSistema(contexto: Context): String = runCatching {
        if (Build.VERSION.SDK_INT >= 33) {
            contexto.getSystemService(LocaleManager::class.java)?.systemLocales?.takeIf { !it.isEmpty }?.get(0)?.toLanguageTag()
        } else null
    }.getOrNull() ?: Resources.getSystem().configuration.locales.takeIf { !it.isEmpty }?.get(0)?.toLanguageTag().orEmpty()

    fun resuelto(contexto: Context): Idioma = resolverIdioma(localeDelSistema(contexto), preferencia(contexto))

    private fun enRobolectric(): Boolean = Build.FINGERPRINT == "robolectric"

    /** Al arrancar la aplicación: fija el idioma y, antes de Android 13, vigila las pantallas nuevas. */
    fun alArrancar(app: Application) {
        if (enRobolectric()) return
        aplicar(app)
        if (Build.VERSION.SDK_INT < 33 && !instalado) {
            instalado = true
            app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
                    runCatching { ponerEn(activity.resources, etiqueta(activity)) }
                }
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
                override fun onActivityStarted(activity: Activity) {}
                override fun onActivityResumed(activity: Activity) {}
                override fun onActivityPaused(activity: Activity) {}
                override fun onActivityStopped(activity: Activity) {}
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
                override fun onActivityDestroyed(activity: Activity) {}
            })
        }
    }

    private fun etiqueta(contexto: Context): String {
        val sistema = localeDelSistema(contexto)
        return etiquetaPara(sistema, resolverIdioma(sistema, preferencia(contexto)))
    }

    /** Pone el idioma que toca. Si ya estaba, no hace nada (no rehace ninguna pantalla). */
    fun aplicar(contexto: Context) {
        if (enRobolectric()) return
        val tag = etiqueta(contexto)
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                val lm = contexto.getSystemService(LocaleManager::class.java) ?: return@runCatching
                val quiero = LocaleList.forLanguageTags(tag)
                if (lm.applicationLocales.toLanguageTags() != quiero.toLanguageTags()) lm.applicationLocales = quiero
            } else {
                Locale.setDefault(Locale.forLanguageTag(tag))
                ponerEn(contexto.applicationContext.resources, tag)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun ponerEn(recursos: Resources, tag: String) {
        val locales = LocaleList.forLanguageTags(tag)
        if (recursos.configuration.locales.toLanguageTags() == locales.toLanguageTags()) return
        val c = Configuration(recursos.configuration)
        c.setLocales(locales)
        recursos.updateConfiguration(c, recursos.displayMetrics)
    }

    /**
     * **Lo elige el usuario** en Ajustes. En Android 13 o más el sistema rehace las pantallas; antes,
     * se rehace [activity] a mano.
     */
    fun elegir(activity: Activity, p: PreferenciaIdioma) {
        runCatching {
            activity.applicationContext.getSharedPreferences(ARCHIVO, Context.MODE_PRIVATE).edit().putString(CLAVE, p.clave).commit()
        }
        val antes = activity.resources.configuration.locales.toLanguageTags()
        aplicar(activity.applicationContext)
        if (Build.VERSION.SDK_INT < 33 && etiqueta(activity) != antes) activity.recreate()
    }
}

/**
 * **La tarjeta del idioma** en Ajustes (1-nov-2026): las tres elecciones del PC («Idioma» y «El de
 * Windows» allí; aquí «El del teléfono»). Los nombres de los idiomas van en su propio idioma, como
 * en todas partes, para que lo encuentre quien no entiende el que está puesto.
 */
@Composable
fun IdiomaCard() {
    val contexto = LocalContext.current
    var elegida by remember { mutableStateOf(IdiomaDeLaApp.preferencia(contexto)) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.ajustes_idioma), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.ajustes_idioma_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (p in PreferenciaIdioma.entries) {
                    FilterChip(
                        selected = elegida == p,
                        onClick = {
                            if (elegida != p) {
                                elegida = p
                                (contexto as? Activity)?.let { IdiomaDeLaApp.elegir(it, p) }
                            }
                        },
                        label = {
                            Text(
                                when (p) {
                                    PreferenciaIdioma.SISTEMA -> stringResource(R.string.ajustes_idioma_sistema)
                                    PreferenciaIdioma.ESPANOL -> "Español"
                                    PreferenciaIdioma.INGLES -> "English"
                                },
                                maxLines = 1
                            )
                        }
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
}
