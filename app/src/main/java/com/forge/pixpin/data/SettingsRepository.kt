package com.forge.pixpin.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "pixpin_settings")

/**
 * Cómo se gestiona el permiso de captura de Android 14+:
 * - [FAST]: la sesión se mantiene viva → capturas instantáneas, pero el sistema
 *   muestra el icono de "grabando pantalla" mientras dura.
 * - [DISCREET]: la sesión se cierra tras cada captura → sin icono permanente,
 *   pero Android pide permiso cada vez.
 */
enum class CaptureMode { FAST, DISCREET }

/**
 * Con qué se escribe la imagen al copiar, compartir o guardar.
 *
 * - [PNG]: tamaño completo, sin comprimir con pérdida. Es lo que entiende todo
 *   el mundo y lo que hay que mandar si al otro lado lo van a volver a editar.
 * - [WEBP]: **la misma imagen, exactamente**, ocupando en torno a la mitad.
 *   Es compresión **sin pérdida**: no se toca ni un píxel, solo se guarda mejor.
 *   Lo que se pierde no es detalle, es compatibilidad — hay sitios viejos que
 *   no abren WebP.
 *
 * No hay opción con pérdida a propósito. Una captura es texto y bordes duros,
 * que es justo lo que peor lleva el JPEG: para ahorrar de verdad habría que
 * bajar la calidad hasta que se vieran halos alrededor de las letras.
 */
enum class CopyFormat {
    PNG, WEBP;

    /**
     * El compresor de Android que corresponde.
     *
     * `WEBP_LOSSLESS` es de Android 11; por debajo se usa el `WEBP` de siempre,
     * que **con calidad 100 también es sin pérdida**. Es el mismo resultado por
     * dos caminos, no una degradación en los móviles viejos.
     */
    val compresor: android.graphics.Bitmap.CompressFormat
        get() = when (this) {
            PNG -> android.graphics.Bitmap.CompressFormat.PNG
            WEBP ->
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    android.graphics.Bitmap.CompressFormat.WEBP_LOSSLESS
                } else {
                    @Suppress("DEPRECATION")
                    android.graphics.Bitmap.CompressFormat.WEBP
                }
        }

    val extension: String get() = if (this == WEBP) "webp" else "png"

    val mime: String get() = if (this == WEBP) "image/webp" else "image/png"
}

/** Ajustes de la app. Se amplía con nuevas claves a medida que crecen las fases. */
data class Settings(
    val defaultPinAlpha: Float = 1f,
    val historySize: Int = 10,
    val ballX: Int = -1,
    val ballY: Int = -1,
    val captureMode: CaptureMode = CaptureMode.FAST,
    val ballVisible: Boolean = true,
    /**
     * Qué herramientas del motor se quedan en la barra del pin, por su nombre.
     *
     * **null es «no lo he tocado»**, y entonces manda la lista de fábrica; un
     * conjunto vacío es «no quiero ninguna», que es una respuesta distinta y hay
     * que respetarla. Por eso no se guarda la lista por defecto al arrancar: si
     * mañana el motor gana una herramienta que debería salir en el pin, a quien
     * nunca tocó esto le aparece sola, y a quien eligió no le cambia nada.
     */
    val pinTools: Set<String>? = null,
    /**
     * Con qué letra se escribe en la edición simple.
     *
     * Está aquí y no en la barra del pin porque es una decisión que se toma una
     * vez: en una barra flotante, el botón de la letra ocupaba sitio para algo
     * que casi nadie cambia dos veces. En la avanzada sigue estando a mano.
     */
    val pinFont: Int = com.forge.pixpin.motor.ItemStyle.FONT_EXCALIFONT,
    val copyFormat: CopyFormat = CopyFormat.PNG,
    /** Lo mismo que [pinTools], pero para la capa que se dibuja sobre la pantalla. */
    val capaTools: Set<String>? = null,
    /**
     * La mano con la que se dibuja.
     *
     * Cambia **de qué lado se ponen las cosas**: los paneles y la lupa se van al
     * lado contrario a la mano, porque el brazo entra por el lado de la mano y
     * tapa justo lo que hay debajo de él. Con la izquierda, todo lo que estaba
     * bien colocado para una diestra pasa a estorbar.
     */
    val zurdo: Boolean = false,
    /**
     * Cómo se agrupan las herramientas en cada barra, escrito.
     *
     * Es cosa aparte de **cuáles** salen: se puede tener la misma lista repartida
     * de dos maneras, y quitar una herramienta no debería descolocar el resto.
     * El formato lo pone y lo lee `com.forge.pixpin.motor.Barra`.
     */
    val pinGroups: String? = null,
    val capaGroups: String? = null,
    /**
     * Y la del editor a pantalla completa.
     *
     * Ahí sitio hay, así que de fábrica salen **todas**; pero «todas» no es lo
     * mismo que «todas a la vista», y quien no use la mitad tiene derecho a
     * quitarlas de en medio y a repartir el resto como le sirva.
     */
    val editorTools: Set<String>? = null,
    val editorGroups: String? = null,
    /**
     * Negro de verdad en modo noche, para pantallas OLED.
     *
     * Ver [com.forge.pixpin.motor.DrawTheme.FONDO_OLED]: en un OLED el negro
     * puro apaga el píxel, y eso se ve y se nota en la batería. Apagado por
     * defecto porque en una pantalla LCD el negro puro se ve peor.
     */
    val oledNegro: Boolean = false
) {
    /** Los grupos de la barra del pin, ya resueltos contra lo permitido. */
    val pinGroupList: List<List<com.forge.pixpin.motor.Tool>>
        get() = com.forge.pixpin.motor.gruposDe(pinGroups, pinToolSet)

    val capaGroupList: List<List<com.forge.pixpin.motor.Tool>>
        get() = com.forge.pixpin.motor.gruposDe(capaGroups, capaToolSet)

    val editorGroupList: List<List<com.forge.pixpin.motor.Tool>>
        get() = com.forge.pixpin.motor.gruposDe(editorGroups, editorToolSet)

    /**
     * Lo mismo, ya traducido a herramientas del motor.
     *
     * Los nombres que no existan se ignoran en silencio: son de una versión que
     * las tenía y se quitaron, y tirar por eso los ajustes enteros sería peor.
     */
    val pinToolSet: Set<com.forge.pixpin.motor.Tool>
        get() = herramientas(pinTools, com.forge.pixpin.motor.PIN_TOOLS_POR_DEFECTO)

    /** Las de la capa sobre la pantalla. */
    val capaToolSet: Set<com.forge.pixpin.motor.Tool>
        get() = herramientas(capaTools, com.forge.pixpin.motor.CAPA_TOOLS_POR_DEFECTO)

    /** Las del editor a pantalla completa: de fábrica, todas. */
    val editorToolSet: Set<com.forge.pixpin.motor.Tool>
        get() = herramientas(editorTools, com.forge.pixpin.motor.ALL_TOOLS.toSet())

    private fun herramientas(
        guardadas: Set<String>?,
        porDefecto: Set<com.forge.pixpin.motor.Tool>
    ): Set<com.forge.pixpin.motor.Tool> {
        if (guardadas == null) return porDefecto
        return guardadas.mapNotNull {
            runCatching { com.forge.pixpin.motor.Tool.valueOf(it) }.getOrNull()
        }.toSet()
    }
}

class SettingsRepository(private val context: Context) {

    private object Keys {
        val DEFAULT_PIN_ALPHA = floatPreferencesKey("default_pin_alpha")
        val HISTORY_SIZE = intPreferencesKey("history_size")
        val BALL_X = intPreferencesKey("ball_x")
        val BALL_Y = intPreferencesKey("ball_y")
        val CAPTURE_MODE = stringPreferencesKey("capture_mode")
        val BALL_VISIBLE = booleanPreferencesKey("ball_visible")
        val PIN_TOOLS = stringSetPreferencesKey("pin_tools")
        val PIN_FONT = intPreferencesKey("pin_font")
        val CAPA_TOOLS = stringSetPreferencesKey("capa_tools")
        val PIN_GROUPS = stringPreferencesKey("pin_groups")
        val CAPA_GROUPS = stringPreferencesKey("capa_groups")
        val EDITOR_TOOLS = stringSetPreferencesKey("editor_tools")
        val EDITOR_GROUPS = stringPreferencesKey("editor_groups")
        val OLED_NEGRO = booleanPreferencesKey("oled_negro")
        val ZURDO = booleanPreferencesKey("zurdo")
        val COPY_FORMAT = stringPreferencesKey("copy_format")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { prefs ->
        Settings(
            defaultPinAlpha = prefs[Keys.DEFAULT_PIN_ALPHA] ?: 1f,
            historySize = prefs[Keys.HISTORY_SIZE] ?: 10,
            ballX = prefs[Keys.BALL_X] ?: -1,
            ballY = prefs[Keys.BALL_Y] ?: -1,
            captureMode = runCatching {
                CaptureMode.valueOf(prefs[Keys.CAPTURE_MODE] ?: CaptureMode.FAST.name)
            }.getOrDefault(CaptureMode.FAST),
            ballVisible = prefs[Keys.BALL_VISIBLE] ?: true,
            pinTools = prefs[Keys.PIN_TOOLS],
            capaTools = prefs[Keys.CAPA_TOOLS],
            pinGroups = prefs[Keys.PIN_GROUPS],
            capaGroups = prefs[Keys.CAPA_GROUPS],
            editorTools = prefs[Keys.EDITOR_TOOLS],
            editorGroups = prefs[Keys.EDITOR_GROUPS],
            oledNegro = prefs[Keys.OLED_NEGRO] ?: false,
            zurdo = prefs[Keys.ZURDO] ?: false,
            pinFont = com.forge.pixpin.motor.ItemStyle.fontFamilyResuelta(prefs[Keys.PIN_FONT]),
            copyFormat = runCatching {
                CopyFormat.valueOf(prefs[Keys.COPY_FORMAT] ?: CopyFormat.PNG.name)
            }.getOrDefault(CopyFormat.PNG)
        )
    }

    suspend fun setDefaultPinAlpha(value: Float) {
        context.dataStore.edit { it[Keys.DEFAULT_PIN_ALPHA] = value.coerceIn(0.2f, 1f) }
    }

    suspend fun setHistorySize(value: Int) {
        context.dataStore.edit { it[Keys.HISTORY_SIZE] = value.coerceIn(0, 50) }
    }

    suspend fun setBallPosition(x: Int, y: Int) {
        context.dataStore.edit {
            it[Keys.BALL_X] = x
            it[Keys.BALL_Y] = y
        }
    }

    suspend fun setCaptureMode(mode: CaptureMode) {
        context.dataStore.edit { it[Keys.CAPTURE_MODE] = mode.name }
    }

    suspend fun setBallVisible(visible: Boolean) {
        context.dataStore.edit { it[Keys.BALL_VISIBLE] = visible }
    }

    /** Fija qué herramientas se quedan en el pin. */
    suspend fun setPinTools(tools: Set<com.forge.pixpin.motor.Tool>) {
        context.dataStore.edit { it[Keys.PIN_TOOLS] = tools.map { t -> t.name }.toSet() }
    }

    /** Lo mismo, para la capa sobre la pantalla. */
    suspend fun setCapaTools(tools: Set<com.forge.pixpin.motor.Tool>) {
        context.dataStore.edit { it[Keys.CAPA_TOOLS] = tools.map { t -> t.name }.toSet() }
    }

    suspend fun resetCapaTools() {
        context.dataStore.edit { it.remove(Keys.CAPA_TOOLS) }
    }

    /**
     * Guarda de una vez el reparto de una barra: qué sale y cómo se agrupa.
     *
     * Las dos cosas juntas porque el editor las cambia con el mismo gesto —
     * arrastrar una herramienta fuera la quita y recoloca el resto—, y
     * guardarlas por separado dejaría un instante con la lista de una y los
     * grupos de la otra.
     */
    suspend fun setBarraDelPin(grupos: List<List<com.forge.pixpin.motor.Tool>>) {
        context.dataStore.edit {
            it[Keys.PIN_TOOLS] = grupos.flatten().map { t -> t.name }.toSet()
            it[Keys.PIN_GROUPS] = com.forge.pixpin.motor.escribirGrupos(grupos)
        }
    }

    suspend fun setBarraDeLaCapa(grupos: List<List<com.forge.pixpin.motor.Tool>>) {
        context.dataStore.edit {
            it[Keys.CAPA_TOOLS] = grupos.flatten().map { t -> t.name }.toSet()
            it[Keys.CAPA_GROUPS] = com.forge.pixpin.motor.escribirGrupos(grupos)
        }
    }

    suspend fun setOledNegro(valor: Boolean) {
        context.dataStore.edit { it[Keys.OLED_NEGRO] = valor }
    }

    suspend fun setBarraDelEditor(grupos: List<List<com.forge.pixpin.motor.Tool>>) {
        context.dataStore.edit {
            it[Keys.EDITOR_TOOLS] = grupos.flatten().map { t -> t.name }.toSet()
            it[Keys.EDITOR_GROUPS] = com.forge.pixpin.motor.escribirGrupos(grupos)
        }
    }

    suspend fun resetBarraDelEditor() {
        context.dataStore.edit { it.remove(Keys.EDITOR_TOOLS); it.remove(Keys.EDITOR_GROUPS) }
    }

    suspend fun resetBarraDelPin() {
        context.dataStore.edit { it.remove(Keys.PIN_TOOLS); it.remove(Keys.PIN_GROUPS) }
    }

    suspend fun resetBarraDeLaCapa() {
        context.dataStore.edit { it.remove(Keys.CAPA_TOOLS); it.remove(Keys.CAPA_GROUPS) }
    }

    /**
     * Vuelve a «no lo he tocado», que no es lo mismo que guardar la lista de
     * fábrica: a partir de aquí, las herramientas que traiga el motor en el
     * futuro vuelven a aparecer solas si les toca.
     */
    suspend fun resetPinTools() {
        context.dataStore.edit { it.remove(Keys.PIN_TOOLS) }
    }

    /** Con qué letra se escribe en la edición simple. */
    suspend fun setPinFont(familia: Int) {
        context.dataStore.edit {
            it[Keys.PIN_FONT] = com.forge.pixpin.motor.ItemStyle.fontFamilyResuelta(familia)
        }
    }

    suspend fun setZurdo(valor: Boolean) {
        context.dataStore.edit { it[Keys.ZURDO] = valor }
    }

    suspend fun setCopyFormat(format: CopyFormat) {
        context.dataStore.edit { it[Keys.COPY_FORMAT] = format.name }
    }
}
