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
    val historySize: Int = 10,
    val ballX: Int = -1,
    val ballY: Int = -1,
    val captureMode: CaptureMode = CaptureMode.FAST,
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
     * Las marcas guardadas de cada deslizador, en un texto.
     *
     * Se guarda como `grosor:0.2,0.55|opacidad:0.8` y no como estructura porque son
     * cuatro números por mando y esto va a un almacén de preferencias, no a una base de
     * datos. Ver [com.forge.pixpin.motor.marcasDeTexto].
     */
    val marcasDeDeslizadores: String = "",
    /**
     * Los colores marcados en la rueda, separados por comas.
     *
     * Son de quien dibuja y no del dibujo —el tono con el que uno rotula es el mismo abra la
     * lámina que abra—, así que viven aquí y no en el `.excalidraw`. Ver [marcaImantada].
     */
    val coloresMarcados: String = "",
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
    val oledNegro: Boolean = false,

    /**
     * **Cómo se trae un plano en PDF: como líneas o como imagen.**
     *
     * Con esto puesto, al abrir un PDF vectorial se lee su geometría y se pinta como rayas
     * (ver `PlanoEnPantalla`): se ve nítido a cualquier aumento y **no hay nada que cargar al
     * acercarse**, que era lo lento del plano por cuadros. A cambio, abrirlo cuesta unos
     * segundos la primera vez y no vale para un PDF escaneado —eso es una foto y no hay
     * líneas que leer—, en cuyo caso se vuelve solo al mosaico de siempre.
     */
    val planoEnLineas: Boolean = true,

    /**
     * **Qué funciones lleva la página web exportada.** null es «todas»; un conjunto es lo
     * marcado en Ajustes → Exportar → Página web. Los nombres son los de
     * `ExportarHtml.Opciones.NOMBRES`.
     */
    val funcionesWeb: Set<String>? = null,

    /**
     * A qué se pega el dedo, punto por punto.
     *
     * Cada clase por separado y no un solo interruptor: **cuál estorba depende
     * de lo que estés dibujando**. Levantando un plano, las intersecciones son
     * lo que más falta hace; garabateando un esquema, tirar de cada cruce es un
     * incordio. Un imán que tira cuando no quieres es peor que no tenerlo. Ver
     * [com.forge.pixpin.motor.Iman].
     */
    /**
     * Si el **modo guía** sale en la barra de cada sitio donde se dibuja.
     *
     * Es una opción y no una herramienta: no dibuja nada por sí misma, decide de
     * qué clase sale lo que se trace. Y hace falta en unos sitios y en otros no
     * —anotando una captura de paso, un andamio que luego hay que esconder es
     * un botón de más— así que se enciende por separado en cada edición.
     *
     * En el editor a pantalla completa viene puesto y en las barras flotantes
     * no: allí cada botón le quita sitio a los que se usan a cada trazo.
     */
    val guiaEnEditor: Boolean = true,
    val guiaEnPin: Boolean = false,
    val guiaEnCapa: Boolean = false,
    val guiaEnCaptura: Boolean = false,
    val imanActivo: Boolean = true,
    val imanEsquinas: Boolean = true,
    val imanMedios: Boolean = true,
    val imanCentros: Boolean = true,
    val imanIntersecciones: Boolean = true,
    val imanEje: Boolean = true,
    val imanBordeDeGuia: Boolean = true,
    val imanBordeDeFigura: Boolean = true,
    /**
     * Qué palabra abre qué mini-aplicación, escrito.
     *
     * Mismo trato que [pinTools]: **null es «no lo he tocado»** y mandan las de
     * fábrica, mientras que una cadena vacía es «no quiero ninguna». El formato
     * lo pone y lo lee [com.forge.pixpin.clipboard.MagicWord].
     */
    val palabrasMagicas: String? = null
) {

    /** Las palabras mágicas ya resueltas contra las de fábrica. */
    val palabras: Map<String, com.forge.pixpin.clipboard.MiniApp>
        get() = com.forge.pixpin.clipboard.MagicWord.leer(palabrasMagicas)

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
        val IMAN_ACTIVO = booleanPreferencesKey("iman_activo")
        val MARCAS_DESLIZADORES = stringPreferencesKey("marcas_deslizadores")
        val COLORES_MARCADOS = stringPreferencesKey("colores_marcados")
        val GUIA_EDITOR = booleanPreferencesKey("guia_editor")
        val GUIA_PIN = booleanPreferencesKey("guia_pin")
        val GUIA_CAPA = booleanPreferencesKey("guia_capa")
        val GUIA_CAPTURA = booleanPreferencesKey("guia_captura")
        val IMAN_ESQUINAS = booleanPreferencesKey("iman_esquinas")
        val IMAN_MEDIOS = booleanPreferencesKey("iman_medios")
        val IMAN_CENTROS = booleanPreferencesKey("iman_centros")
        val IMAN_INTERSECCIONES = booleanPreferencesKey("iman_intersecciones")
        val IMAN_EJE = booleanPreferencesKey("iman_eje")
        val IMAN_BORDE_GUIA = booleanPreferencesKey("iman_borde_guia")
        val IMAN_BORDE_FIGURA = booleanPreferencesKey("iman_borde_figura")
        val CAPA_TOOLS = stringSetPreferencesKey("capa_tools")
        val PIN_GROUPS = stringPreferencesKey("pin_groups")
        val CAPA_GROUPS = stringPreferencesKey("capa_groups")
        val EDITOR_TOOLS = stringSetPreferencesKey("editor_tools")
        val EDITOR_GROUPS = stringPreferencesKey("editor_groups")
        val OLED_NEGRO = booleanPreferencesKey("oled_negro")
        val PLANO_EN_LINEAS = booleanPreferencesKey("plano_en_lineas")
        val FUNCIONES_WEB = stringSetPreferencesKey("funciones_web")
        val ZURDO = booleanPreferencesKey("zurdo")
        val COPY_FORMAT = stringPreferencesKey("copy_format")
        val PALABRAS = stringPreferencesKey("palabras_magicas")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { prefs ->
        Settings(
            historySize = prefs[Keys.HISTORY_SIZE] ?: 10,
            ballX = prefs[Keys.BALL_X] ?: -1,
            ballY = prefs[Keys.BALL_Y] ?: -1,
            captureMode = runCatching {
                CaptureMode.valueOf(prefs[Keys.CAPTURE_MODE] ?: CaptureMode.FAST.name)
            }.getOrDefault(CaptureMode.FAST),
            pinTools = prefs[Keys.PIN_TOOLS],
            capaTools = prefs[Keys.CAPA_TOOLS],
            pinGroups = prefs[Keys.PIN_GROUPS],
            capaGroups = prefs[Keys.CAPA_GROUPS],
            editorTools = prefs[Keys.EDITOR_TOOLS],
            editorGroups = prefs[Keys.EDITOR_GROUPS],
            oledNegro = prefs[Keys.OLED_NEGRO] ?: false,
            planoEnLineas = prefs[Keys.PLANO_EN_LINEAS] ?: true,
            funcionesWeb = prefs[Keys.FUNCIONES_WEB],
            guiaEnEditor = prefs[Keys.GUIA_EDITOR] ?: true,
            guiaEnPin = prefs[Keys.GUIA_PIN] ?: false,
            guiaEnCapa = prefs[Keys.GUIA_CAPA] ?: false,
            guiaEnCaptura = prefs[Keys.GUIA_CAPTURA] ?: false,
            imanActivo = prefs[Keys.IMAN_ACTIVO] ?: true,
            marcasDeDeslizadores = prefs[Keys.MARCAS_DESLIZADORES] ?: "",
            coloresMarcados = prefs[Keys.COLORES_MARCADOS] ?: "",
            imanEsquinas = prefs[Keys.IMAN_ESQUINAS] ?: true,
            imanMedios = prefs[Keys.IMAN_MEDIOS] ?: true,
            imanCentros = prefs[Keys.IMAN_CENTROS] ?: true,
            imanIntersecciones = prefs[Keys.IMAN_INTERSECCIONES] ?: true,
            imanEje = prefs[Keys.IMAN_EJE] ?: true,
            imanBordeDeGuia = prefs[Keys.IMAN_BORDE_GUIA] ?: true,
            imanBordeDeFigura = prefs[Keys.IMAN_BORDE_FIGURA] ?: true,
            zurdo = prefs[Keys.ZURDO] ?: false,
            pinFont = com.forge.pixpin.motor.ItemStyle.fontFamilyResuelta(prefs[Keys.PIN_FONT]),
            copyFormat = runCatching {
                CopyFormat.valueOf(prefs[Keys.COPY_FORMAT] ?: CopyFormat.PNG.name)
            }.getOrDefault(CopyFormat.PNG),
            palabrasMagicas = prefs[Keys.PALABRAS]
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

    /** Qué funciones lleva la página web exportada. Ver [Settings.funcionesWeb]. */
    suspend fun setFuncionesWeb(valor: Set<String>) {
        context.dataStore.edit { it[Keys.FUNCIONES_WEB] = valor }
    }

    /** Cómo se trae un plano en PDF. Ver [Settings.planoEnLineas]. */
    suspend fun setPlanoEnLineas(valor: Boolean) {
        context.dataStore.edit { it[Keys.PLANO_EN_LINEAS] = valor }
    }

    /** Enciende o apaga una clase de enganche. Ver [com.forge.pixpin.motor.Iman]. */
    /**
     * Enciende o apaga el modo guía en un sitio.
     *
     * Uno por sitio y no uno global: la misma función estorba en la barra del
     * pin y hace falta en el editor. Ver [Settings.guiaEnEditor].
     */
    suspend fun setGuia(donde: DondeSeDibuja, valor: Boolean) {
        context.dataStore.edit {
            it[
                when (donde) {
                    DondeSeDibuja.EDITOR -> Keys.GUIA_EDITOR
                    DondeSeDibuja.PIN -> Keys.GUIA_PIN
                    DondeSeDibuja.CAPA -> Keys.GUIA_CAPA
                    DondeSeDibuja.CAPTURA -> Keys.GUIA_CAPTURA
                }
            ] = valor
        }
    }

    /** Guarda las marcas de los deslizadores. Ver [Settings.marcasDeDeslizadores]. */
    suspend fun setMarcas(texto: String) {
        context.dataStore.edit { it[Keys.MARCAS_DESLIZADORES] = texto }
    }

    /** Guarda los colores marcados en la rueda. Ver [Settings.coloresMarcados]. */
    suspend fun setColoresMarcados(texto: String) {
        context.dataStore.edit { it[Keys.COLORES_MARCADOS] = texto }
    }

    suspend fun setIman(cual: ClaseDeIman, valor: Boolean) {
        context.dataStore.edit {
            it[
                when (cual) {
                    ClaseDeIman.ACTIVO -> Keys.IMAN_ACTIVO
                    ClaseDeIman.ESQUINAS -> Keys.IMAN_ESQUINAS
                    ClaseDeIman.MEDIOS -> Keys.IMAN_MEDIOS
                    ClaseDeIman.CENTROS -> Keys.IMAN_CENTROS
                    ClaseDeIman.INTERSECCIONES -> Keys.IMAN_INTERSECCIONES
                    ClaseDeIman.EJE -> Keys.IMAN_EJE
                    ClaseDeIman.BORDE_DE_GUIA -> Keys.IMAN_BORDE_GUIA
                    ClaseDeIman.BORDE_DE_FIGURA -> Keys.IMAN_BORDE_FIGURA
                }
            ] = valor
        }
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

    /** Fija las palabras mágicas. Ver [Settings.palabrasMagicas]. */
    suspend fun setPalabras(palabras: Map<String, com.forge.pixpin.clipboard.MiniApp>) {
        context.dataStore.edit {
            it[Keys.PALABRAS] = com.forge.pixpin.clipboard.MagicWord.escribir(palabras)
        }
    }

    /** Vuelve a las de fábrica, que no es lo mismo que guardarlas: ver [resetPinTools]. */
    suspend fun resetPalabras() {
        context.dataStore.edit { it.remove(Keys.PALABRAS) }
    }

    suspend fun setCopyFormat(format: CopyFormat) {
        context.dataStore.edit { it[Keys.COPY_FORMAT] = format.name }
    }
}

/**
 * Las clases de enganche que se pueden apagar, para no repetir la lista.
 *
 * Van en un enum y no como ocho funciones porque **la pantalla de ajustes las
 * recorre**: añadir una clase nueva al imán tiene que hacerla aparecer en los
 * ajustes sin tocar la pantalla, que es la misma idea que hace que una
 * herramienta nueva aparezca sola en la barra.
 */
enum class ClaseDeIman {
    ACTIVO, ESQUINAS, MEDIOS, CENTROS, INTERSECCIONES, EJE, BORDE_DE_GUIA, BORDE_DE_FIGURA
}


/** Los cuatro sitios donde se dibuja, cada uno con su barra. */
enum class DondeSeDibuja { EDITOR, PIN, CAPA, CAPTURA }
