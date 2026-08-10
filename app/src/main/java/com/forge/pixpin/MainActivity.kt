package com.forge.pixpin

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.getSystemService
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.forge.pixpin.data.CaptureMode
import com.forge.pixpin.data.ClaseDeIman
import com.forge.pixpin.data.CrashLog
import com.forge.pixpin.data.Settings
import com.forge.pixpin.ui.theme.PixPinTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PixPinTheme {
                OnboardingScreen()
            }
        }
    }
}

private data class PermissionItem(
    val icon: ImageVector,
    val titleRes: Int,
    val descRes: Int,
    val granted: Boolean,
    val onGrant: () -> Unit
)

@Composable
fun OnboardingScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var overlayGranted by remember { mutableStateOf(AndroidSettings.canDrawOverlays(context)) }
    var batteryIgnored by remember { mutableStateOf(isBatteryIgnored(context)) }
    var notifGranted by remember { mutableStateOf(isNotifGranted(context)) }

    // Recomprobar al volver de los ajustes del sistema
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlayGranted = AndroidSettings.canDrawOverlays(context)
                batteryIgnored = isBatteryIgnored(context)
                notifGranted = isNotifGranted(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { notifGranted = it }

    val items = buildList {
        add(
            PermissionItem(
                icon = Icons.Filled.Layers,
                titleRes = R.string.perm_overlay_title,
                descRes = R.string.perm_overlay_desc,
                granted = overlayGranted,
                onGrant = {
                    context.startActivity(
                        Intent(
                            AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                    )
                }
            )
        )
        if (Build.VERSION.SDK_INT >= 33) {
            add(
                PermissionItem(
                    icon = Icons.Filled.Notifications,
                    titleRes = R.string.perm_notif_title,
                    descRes = R.string.perm_notif_desc,
                    granted = notifGranted,
                    onGrant = { notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
                )
            )
        }
        add(
            PermissionItem(
                icon = Icons.Filled.BatterySaver,
                titleRes = R.string.perm_battery_title,
                descRes = R.string.perm_battery_desc,
                granted = batteryIgnored,
                onGrant = {
                    runCatching {
                        context.startActivity(
                            Intent(
                                AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:${context.packageName}")
                            )
                        )
                    }
                }
            )
        )
    }

    // **La portada enseña lo que hace falta para empezar, y nada más.**
    //
    // Antes eran trece bloques uno detrás de otro: los tres permisos, el modo de
    // captura, el formato de copia, tres barras configurables, el negro OLED, la
    // mano, la letra del pin y el informe de fallo. Todo abierto a la vez y todo
    // con el mismo peso, así que abrir la app era enfrentarse a un formulario en
    // vez de a un botón.
    //
    // Lo que uno viene a hacer aquí es **pulsar «Comenzar»**. Lo demás se toca
    // una vez y no se vuelve a mirar, así que se va detrás de una puerta.
    var enAjustes by remember { mutableStateOf(false) }
    var enProyectos by remember { mutableStateOf(false) }
    val listo = overlayGranted && notifGranted && batteryIgnored

    if (enAjustes) {
        PantallaDeAjustes(onVolver = { enAjustes = false })
        return
    }
    if (enProyectos) {
        com.forge.pixpin.ui.PantallaDeProyectos(
            app = context.applicationContext as PixPinApp,
            onVolver = { enProyectos = false }
        )
        return
    }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
        ) {
            Text(
                text = stringResource(R.string.onboarding_title),
                style = MaterialTheme.typography.headlineMedium
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(
                    if (listo) R.string.onboarding_listo else R.string.onboarding_subtitle
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))

            // **Un permiso concedido deja de ocupar sitio.** Su tarjeta ya no
            // dice nada que haga falta: si está dado, está dado. Lo que queda a
            // la vista es lo que todavía hay que tocar, que es justo lo que uno
            // necesita ver.
            items.filter { !it.granted }.forEach { item ->
                PermissionCard(item)
                Spacer(Modifier.height(12.dp))
            }
            if (listo) {
                TodoConcedido()
                Spacer(Modifier.height(12.dp))
            }

            // El informe de fallo sí manda: si lo hay, es lo primero que hay que
            // ver, porque hasta descartarlo los pines no vuelven.
            CrashReportCard()

            Spacer(Modifier.height(20.dp))

            Button(
                onClick = {
                    com.forge.pixpin.floating.PinHostService.start(context)
                    android.widget.Toast.makeText(
                        context, R.string.app_started, android.widget.Toast.LENGTH_LONG
                    ).show()
                },
                enabled = overlayGranted,
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                Text(stringResource(R.string.start_app))
            }
            if (!overlayGranted) {
                Text(
                    text = stringResource(R.string.need_overlay),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth()) {
                // **Proyectos junto a Ajustes, y no en la bola.** Es donde se
                // vuelve a lo empezado, así que va en la pantalla que se abre
                // para empezar — y no roba un sitio en la bola, que es para lo
                // que se hace cada dos minutos.
                TextButton(onClick = { enProyectos = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Folder, contentDescription = null, Modifier.size(18.dp))
                    Text(
                        stringResource(R.string.proyectos_titulo),
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
                TextButton(onClick = { enAjustes = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Tune, contentDescription = null, Modifier.size(18.dp))
                    Text(
                        stringResource(R.string.ajustes_titulo),
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        }
    }
}

/** Una línea en vez de tres tarjetas cuando ya no hay nada que conceder. */
@Composable
private fun TodoConcedido() {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Text(
            stringResource(R.string.permisos_listos),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 10.dp)
        )
    }
}

/**
 * Los ajustes, detrás de una puerta y **en tres grupos**.
 *
 * Sueltos y todos seguidos daban nueve tarjetas del mismo tamaño y con el mismo
 * peso, sin decir cuál importa ni cuál va con cuál. Agrupados por lo que tocan
 * —capturar, dibujar, cómo se ve— se leen de un vistazo y se encuentra lo que se
 * busca sin recorrerlos todos.
 */
@Composable
private fun PantallaDeAjustes(onVolver: () -> Unit) {
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onVolver, modifier = Modifier.padding(end = 4.dp)) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.cd_close)
                    )
                }
                Text(
                    stringResource(R.string.ajustes_titulo),
                    style = MaterialTheme.typography.headlineSmall
                )
            }
            Spacer(Modifier.height(16.dp))

            GrupoDeAjustes(stringResource(R.string.ajustes_capturar)) {
                CaptureModeCard()
                Spacer(Modifier.height(12.dp))
                FormatoDeCopiaCard()
            }

            GrupoDeAjustes(stringResource(R.string.ajustes_dibujar)) {
                ImanCard()
                Spacer(Modifier.height(12.dp))
                ManoCard()
                Spacer(Modifier.height(12.dp))
                BarraDelEditorCard()
                Spacer(Modifier.height(12.dp))
                BarraDelPinCard()
                Spacer(Modifier.height(12.dp))
                BarraDeLaCapaCard()
            }

            GrupoDeAjustes(stringResource(R.string.ajustes_aspecto)) {
                OledCard()
                Spacer(Modifier.height(12.dp))
                LetraDelPinCard()
            }

            // **La versión, aquí abajo.** Si el número solo vive en el archivo
            // de compilación, desde el móvil no hay forma de saber qué build
            // tienes puesto — y con los APK repartidos por enlace, esa era la
            // pregunta que no se podía responder.
            Text(
                stringResource(R.string.version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun GrupoDeAjustes(titulo: String, contenido: @Composable () -> Unit) {
    Text(
        titulo,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 8.dp)
    )
    contenido()
    Spacer(Modifier.height(28.dp))
}

/**
 * A qué se pega el dedo.
 *
 * Una fila por clase, y la lista sale del propio enum: añadir una clase al imán
 * la hace aparecer aquí sin tocar esta pantalla. Es la misma idea que hace que
 * una herramienta nueva aparezca sola en la barra.
 *
 * El interruptor de arriba apaga el imán entero, y entonces los demás dejan de
 * poder tocarse: encender «intersecciones» con el imán apagado no haría nada, y
 * un interruptor que no hace nada es peor que no estar.
 */
@Composable
private fun ImanCard() {
    val context = LocalContext.current
    val app = context.applicationContext as PixPinApp
    val scope = rememberCoroutineScope()
    val ajustes by app.settings.settings.collectAsState(initial = Settings())

    fun puesto(cual: ClaseDeIman): Boolean = when (cual) {
        ClaseDeIman.ACTIVO -> ajustes.imanActivo
        ClaseDeIman.ESQUINAS -> ajustes.imanEsquinas
        ClaseDeIman.MEDIOS -> ajustes.imanMedios
        ClaseDeIman.CENTROS -> ajustes.imanCentros
        ClaseDeIman.INTERSECCIONES -> ajustes.imanIntersecciones
        ClaseDeIman.EJE -> ajustes.imanEje
        ClaseDeIman.BORDE_DE_GUIA -> ajustes.imanBordeDeGuia
        ClaseDeIman.BORDE_DE_FIGURA -> ajustes.imanBordeDeFigura
    }

    fun nombre(cual: ClaseDeIman): Int = when (cual) {
        ClaseDeIman.ACTIVO -> R.string.iman_activo
        ClaseDeIman.ESQUINAS -> R.string.iman_esquinas
        ClaseDeIman.MEDIOS -> R.string.iman_medios
        ClaseDeIman.CENTROS -> R.string.iman_centros
        ClaseDeIman.INTERSECCIONES -> R.string.iman_intersecciones
        ClaseDeIman.EJE -> R.string.iman_eje
        ClaseDeIman.BORDE_DE_GUIA -> R.string.iman_borde_guia
        ClaseDeIman.BORDE_DE_FIGURA -> R.string.iman_borde_figura
    }

    Card {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.iman_titulo),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                stringResource(R.string.iman_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
            )
            for (cual in ClaseDeIman.entries) {
                val esElGeneral = cual == ClaseDeIman.ACTIVO
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(nombre(cual)),
                        style =
                            if (esElGeneral) MaterialTheme.typography.bodyLarge
                            else MaterialTheme.typography.bodyMedium,
                        color =
                            if (esElGeneral || ajustes.imanActivo) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f).padding(start = if (esElGeneral) 0.dp else 12.dp)
                    )
                    Switch(
                        checked = puesto(cual),
                        enabled = esElGeneral || ajustes.imanActivo,
                        onCheckedChange = { scope.launch { app.settings.setIman(cual, it) } }
                    )
                }
            }
        }
    }
}

/**
 * Modo de captura. En Android 14+ un permiso de grabación solo vale para una
 * sesión, así que hay que elegir: mantenerla viva (rápido, con icono de
 * grabación) o cerrarla tras cada captura (discreto, pide permiso cada vez).
 */
@Composable
private fun CaptureModeCard() {
    val context = LocalContext.current
    val app = context.applicationContext as PixPinApp
    val scope = rememberCoroutineScope()
    val settings by app.settings.settings.collectAsState(initial = com.forge.pixpin.data.Settings())

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.capture_mode_title),
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(Modifier.height(8.dp))
            CaptureMode.entries.forEach { mode ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { scope.launch { app.settings.setCaptureMode(mode) } }
                        .padding(vertical = 6.dp)
                ) {
                    RadioButton(
                        selected = settings.captureMode == mode,
                        onClick = { scope.launch { app.settings.setCaptureMode(mode) } }
                    )
                    Column(Modifier.padding(start = 4.dp)) {
                        Text(
                            stringResource(
                                if (mode == CaptureMode.FAST) R.string.capture_mode_fast
                                else R.string.capture_mode_discreet
                            ),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            stringResource(
                                if (mode == CaptureMode.FAST) R.string.capture_mode_fast_desc
                                else R.string.capture_mode_discreet_desc
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/**
 * Qué herramientas del motor se quedan en el pin.
 *
 * El pin y el editor a pantalla completa usan **el mismo motor**, y esa era
 * justo la papeleta: todo lo que se le añade al motor aparece también en una
 * barra flotante de dos dedos de ancho, encima de la foto que estás anotando.
 * En vez de decidir por el usuario qué es «lo básico», se pregunta: lo marcado
 * va al pin y lo demás sigue existiendo, pero en la edición avanzada.
 *
 * Se listan con su icono y su nombre, los mismos de la barra, para que lo que
 * se marca aquí se reconozca allí sin traducir nada mentalmente.
 */
/**
 * La barra del pin: qué herramientas salen y cómo se agrupan.
 *
 * Arrastrando, no con casillas. La diferencia importa: agrupar es una decisión
 * sobre **qué va con qué**, y una lista de casillas no puede expresarla — lo
 * más que sabe decir es sí o no.
 */
@Composable
private fun BarraDelPinCard() {
    val context = LocalContext.current
    val app = context.applicationContext as PixPinApp
    val scope = rememberCoroutineScope()
    val settings by app.settings.settings.collectAsState(initial = com.forge.pixpin.data.Settings())
    BarraCard(
        titulo = R.string.pin_tools_title,
        descripcion = R.string.pin_tools_desc,
        grupos = settings.pinGroupList,
        puestas = settings.pinToolSet,
        onCambio = { scope.launch { app.settings.setBarraDelPin(it) } },
        onReset = { scope.launch { app.settings.resetBarraDelPin() } }
    )
}

/**
 * Lo mismo, para el editor a pantalla completa.
 *
 * Allí caben todas, y por eso de fábrica están todas; pero tener sitio no
 * obliga a enseñarlo todo. Quien dibuje siempre lo mismo puede dejar a la vista
 * lo suyo y plegar el resto, con el mismo gesto que en las otras dos barras.
 */
@Composable
private fun BarraDelEditorCard() {
    val context = LocalContext.current
    val app = context.applicationContext as PixPinApp
    val scope = rememberCoroutineScope()
    val settings by app.settings.settings.collectAsState(initial = com.forge.pixpin.data.Settings())
    BarraCard(
        titulo = R.string.editor_tools_title,
        descripcion = R.string.editor_tools_desc,
        grupos = settings.editorGroupList,
        puestas = settings.editorToolSet,
        onCambio = { scope.launch { app.settings.setBarraDelEditor(it) } },
        onReset = { scope.launch { app.settings.resetBarraDelEditor() } }
    )
}

/** Lo mismo, para la capa que se dibuja sobre la pantalla. */
@Composable
private fun BarraDeLaCapaCard() {
    val context = LocalContext.current
    val app = context.applicationContext as PixPinApp
    val scope = rememberCoroutineScope()
    val settings by app.settings.settings.collectAsState(initial = com.forge.pixpin.data.Settings())
    BarraCard(
        titulo = R.string.capa_tools_title,
        descripcion = R.string.capa_tools_desc,
        grupos = settings.capaGroupList,
        puestas = settings.capaToolSet,
        onCambio = { scope.launch { app.settings.setBarraDeLaCapa(it) } },
        onReset = { scope.launch { app.settings.resetBarraDeLaCapa() } }
    )
}

/**
 * El editor de una barra, con su título.
 *
 * Uno solo para las dos barras —la del pin y la de la capa— porque son la misma
 * pregunta hecha en dos sitios: con dos copias, la segunda se queda atrás en
 * cuanto el motor gana una herramienta.
 */
@Composable
private fun BarraCard(
    @androidx.annotation.StringRes titulo: Int,
    @androidx.annotation.StringRes descripcion: Int,
    grupos: List<List<com.forge.pixpin.motor.Tool>>,
    puestas: Set<com.forge.pixpin.motor.Tool>,
    onCambio: (List<List<com.forge.pixpin.motor.Tool>>) -> Unit,
    onReset: () -> Unit
) {
    val fuera = com.forge.pixpin.motor.ALL_TOOLS.filter { it !in puestas }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(titulo), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(descripcion),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            Text(
                stringResource(R.string.barra_ayuda),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 6.dp, bottom = 6.dp)
            )

            com.forge.pixpin.ui.EditorDeBarra(
                grupos = grupos,
                fuera = fuera,
                onCambio = { nuevos, _ -> onCambio(nuevos) }
            )

            TextButton(onClick = onReset) {
                Text(stringResource(R.string.pin_tools_reset))
            }
        }
    }
}

/**
 * Con qué mano se dibuja.
 *
 * No es una preferencia estética: el brazo entra por el lado de la mano y tapa
 * lo que hay debajo. Todo lo que está bien colocado para una diestra —los
 * paneles, la lupa que enseña el punto bajo el dedo— estorba con la izquierda.
 */
/**
 * Negro de verdad en modo noche.
 *
 * En un OLED el negro puro no enciende el píxel: el lienzo desaparece contra el
 * marco del móvil y gasta menos. En un LCD se ve gris lavado, así que va
 * apagado de fábrica y lo enciende quien lo quiera.
 */
@Composable
private fun OledCard() {
    val context = LocalContext.current
    val app = context.applicationContext as PixPinApp
    val scope = rememberCoroutineScope()
    val settings by app.settings.settings.collectAsState(initial = com.forge.pixpin.data.Settings())

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.oled_title),
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    stringResource(R.string.oled_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Switch(
                checked = settings.oledNegro,
                onCheckedChange = { scope.launch { app.settings.setOledNegro(it) } }
            )
        }
    }
}

@Composable
private fun ManoCard() {
    val context = LocalContext.current
    val app = context.applicationContext as PixPinApp
    val scope = rememberCoroutineScope()
    val settings by app.settings.settings.collectAsState(initial = com.forge.pixpin.data.Settings())

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.mano_title), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.mano_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
            )
            listOf(false to R.string.mano_diestro, true to R.string.mano_zurdo).forEach { (z, texto) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { scope.launch { app.settings.setZurdo(z) } }
                        .padding(vertical = 2.dp)
                ) {
                    RadioButton(
                        selected = settings.zurdo == z,
                        onClick = { scope.launch { app.settings.setZurdo(z) } }
                    )
                    Text(stringResource(texto), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/**
 * Con qué letra se escribe en la edición simple.
 *
 * Vive aquí y no en la barra del pin porque es una decisión que se toma una vez
 * y no se vuelve a mirar: en una barra flotante ese botón ocupaba el sitio de
 * algo que sí se cambia a menudo. En la edición avanzada sigue estando a mano.
 */
@Composable
private fun LetraDelPinCard() {
    val context = LocalContext.current
    val app = context.applicationContext as PixPinApp
    val scope = rememberCoroutineScope()
    val settings by app.settings.settings.collectAsState(initial = com.forge.pixpin.data.Settings())

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.pin_font_title),
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                stringResource(R.string.pin_font_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
            )
            com.forge.pixpin.motor.ItemStyle.FONT_FAMILIES.forEach { familia ->
                val puesta = settings.pinFont == familia
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { scope.launch { app.settings.setPinFont(familia) } }
                        .padding(vertical = 2.dp)
                ) {
                    RadioButton(
                        selected = puesta,
                        onClick = { scope.launch { app.settings.setPinFont(familia) } }
                    )
                    // Cada opción **escrita con su propia letra**: es lo único
                    // que dice de verdad en qué se diferencian.
                    Text(
                        com.forge.pixpin.motor.DrawFonts.nombreDe(familia),
                        fontFamily = com.forge.pixpin.motor.composeFontFamily(familia),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }
    }
}

/**
 * Cómo se escribe la imagen al copiar, compartir y guardar.
 *
 * Las dos opciones son **sin pérdida**: lo que cambia es cuánto ocupa el
 * archivo y quién sabe abrirlo, no lo que se ve. Una captura es texto y bordes
 * duros, que es justo lo que peor lleva la compresión con pérdida, así que
 * ofrecer JPEG aquí sería ofrecer halos alrededor de las letras.
 */
@Composable
private fun FormatoDeCopiaCard() {
    val context = LocalContext.current
    val app = context.applicationContext as PixPinApp
    val scope = rememberCoroutineScope()
    val settings by app.settings.settings.collectAsState(initial = com.forge.pixpin.data.Settings())

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.copy_format_title),
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(Modifier.height(8.dp))
            com.forge.pixpin.data.CopyFormat.entries.forEach { formato ->
                val png = formato == com.forge.pixpin.data.CopyFormat.PNG
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { scope.launch { app.settings.setCopyFormat(formato) } }
                        .padding(vertical = 6.dp)
                ) {
                    RadioButton(
                        selected = settings.copyFormat == formato,
                        onClick = { scope.launch { app.settings.setCopyFormat(formato) } }
                    )
                    Column(Modifier.padding(start = 4.dp)) {
                        Text(
                            stringResource(
                                if (png) R.string.copy_format_png else R.string.copy_format_webp
                            ),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            stringResource(
                                if (png) R.string.copy_format_png_desc
                                else R.string.copy_format_webp_desc
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/** Aparece solo si la app se cerró de golpe: permite enviarme la traza. */
@Composable
private fun CrashReportCard() {
    val context = LocalContext.current
    var hasReport by remember { mutableStateOf(CrashLog.hasReport(context)) }
    if (!hasReport) return

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.crash_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.error
            )
            Text(
                stringResource(R.string.crash_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row {
                TextButton(onClick = {
                    CrashLog.shareIntent(context)?.let {
                        context.startActivity(Intent.createChooser(it, null))
                    }
                }) { Text(stringResource(R.string.crash_share)) }
                TextButton(onClick = {
                    CrashLog.clear(context)
                    hasReport = false
                }) { Text(stringResource(R.string.crash_dismiss)) }
            }
        }
    }
}

@Composable
private fun PermissionCard(item: PermissionItem) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(item.icon, contentDescription = null)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(item.titleRes), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(item.descRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (item.granted) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = stringResource(R.string.granted),
                    tint = MaterialTheme.colorScheme.primary
                )
            } else {
                TextButton(onClick = item.onGrant) {
                    Text(stringResource(R.string.grant))
                }
            }
        }
    }
}

private fun isBatteryIgnored(context: android.content.Context): Boolean {
    val pm = context.getSystemService<PowerManager>() ?: return true
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

private fun isNotifGranted(context: android.content.Context): Boolean {
    return Build.VERSION.SDK_INT < 33 ||
        context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
}
