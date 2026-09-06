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
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.forge.pixpin.clipboard.MagicWord
import com.forge.pixpin.clipboard.MiniApp
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.forge.pixpin.data.DondeSeDibuja
import com.forge.pixpin.data.CrashLog
import com.forge.pixpin.data.Settings
import com.forge.pixpin.ui.theme.PixPinTheme
import kotlinx.coroutines.launch

/** Pide abrir directamente la pantalla de proyectos. */
const val EXTRA_PROYECTOS = "abrir_proyectos"

/**
 * Qué proyecto abrir, si es que se viene a por uno concreto.
 *
 * Un acceso directo a un proyecto tiene que llevar **a ese proyecto**, no a la lista
 * donde está entre otros veinte. Con la lista entera, quien lo toca acaba buscando a mano
 * lo que acababa de pedir por su nombre.
 */
const val EXTRA_PROYECTO = "abrir_proyecto"

/**
 * **Volver a un proyecto dentro del montón**, no a él solo.
 *
 * Al cerrar un lienzo, un croquis o una nota, lo que se espera es la pantalla de siempre
 * —el paginador de proyectos— puesta en el proyecto del que se venía, y no la vista de un
 * proyecto suelto que es para los accesos directos. Lo pidió el usuario (5-sep-2026).
 */
const val EXTRA_IR_A = "ir_a_proyecto"

/**
 * **De dónde se vino, para saber a dónde volver.**
 *
 * Los editores —el lienzo, el croquis en el espacio y el de notas— viven **en su propia
 * tarea de Android**. Tiene que ser así: si compartieran tarea con la pantalla principal,
 * abrirlos desde la bola flotante traería toda la aplicación al frente y uno acabaría
 * capturando PixPin en vez de lo suyo. Pero eso tiene un precio: al cerrarlos, atrás no es
 * de donde se vino, es lo que hubiera debajo de su tarea —o sea la pantalla principal, o el
 * escritorio—. Abriendo una hoja desde un proyecto y cerrándola, uno acababa en la portada
 * de la aplicación en vez de en el proyecto que estaba mirando.
 *
 * Con esto puesto, el editor sabe que se vino de un proyecto y al cerrarse vuelve a él. El
 * valor es el id del proyecto, o vacío para la lista. Ver [volverALosProyectos].
 */
const val EXTRA_DESDE_PROYECTO = "desde_proyecto"

/**
 * Vuelve a la zona de proyectos. [proyecto] vacío es la lista; con id, ese proyecto.
 *
 * La pantalla principal es `singleTask`, así que esto no crea otra: trae al frente la que
 * ya hay y le llega por `onNewIntent`, que es justo lo que se quiere.
 */
fun volverALosProyectos(context: android.content.Context, proyecto: String?) {
    runCatching {
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_PROYECTOS, true)
                .also { if (!proyecto.isNullOrBlank()) it.putExtra(EXTRA_IR_A, proyecto) }
        )
    }
}

class MainActivity : ComponentActivity() {

    /**
     * Con qué se ha entrado esta vez.
     *
     * Esta pantalla es `singleTask`, así que **volver a abrirla no vuelve a crearla**: el
     * intento nuevo llega por [onNewIntent] y el `intent` que se leyó al componer se
     * queda con lo de la vez anterior. Sin esto, pedir un proyecto después de haber
     * pedido otro abría el primero. Guardarlo en estado es lo que hace que la pantalla se
     * entere.
     */
    private val loQuePiden = mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        loQuePiden.value = intent
        setContent {
            PixPinTheme {
                OnboardingScreen(loQuePiden.value)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        loQuePiden.value = intent
    }
}

private data class PermissionItem(
    val icon: ImageVector,
    val titleRes: Int,
    val descRes: Int,
    val granted: Boolean,
    val onGrant: () -> Unit
)

/**
 * Por dónde se entra: **directamente a los proyectos**.
 *
 * Antes había una portada —los permisos, «Comenzar», y dos botones para irse a otro
 * sitio— que se miraba una vez en la vida y luego estorbaba: en cuanto los tres permisos
 * estaban dados, la pantalla se saltaba sola y lo que ponía en ella no había forma de
 * volver a verlo sin retroceder a propósito.
 *
 * Eso mismo es ahora [TarjetaDeConfiguracion], la **primera tarjeta** del montón de
 * proyectos. Ahí no es una puerta que hay que cruzar, es una tarjeta más: se llega
 * deslizando, se lee de un vistazo si la bola está en marcha y se sigue bajando a lo que
 * uno venía a hacer. Y quien acaba de instalar la aplicación se la encuentra delante, que
 * es lo mismo que hacía la portada.
 */
@Composable
fun OnboardingScreen(loQuePiden: Intent? = null) {
    val context = LocalContext.current
    val actividad = context as? android.app.Activity
    // Se puede entrar **directamente en un proyecto** desde fuera: es lo que hace que un
    // acceso directo guardado abra lo que promete. Ver [Clase.PROYECTO].
    val proyectoPedido = loQuePiden?.getStringExtra(EXTRA_PROYECTO)
    // Y a cuál ir dentro del montón, al volver de un lienzo, un croquis o una nota.
    val irA = loQuePiden?.getStringExtra(EXTRA_IR_A)
    var enAjustes by remember { mutableStateOf(false) }

    if (enAjustes) {
        PantallaDeAjustes(onVolver = { enAjustes = false })
        return
    }
    com.forge.pixpin.ui.PantallaDeProyectos(
        app = context.applicationContext as PixPinApp,
        soloEste = proyectoPedido,
        irA = irA,
        onAjustes = { enAjustes = true },
        // Atrás sale de la aplicación: ya no hay ninguna pantalla detrás de esta a la
        // que volver, la portada **es** la primera tarjeta de aquí dentro.
        onVolver = { actividad?.finish() }
    )
}

/**
 * La configuración inicial, hecha una tarjeta más del montón de proyectos.
 *
 * Lleva lo que llevaba la portada —los permisos que falten, arrancar la bola, el informe
 * de fallo si lo hay y la puerta a los ajustes— y va **siempre la primera**, encima del
 * primer proyecto. Que esté en el mismo sitio y con la misma forma que lo demás es lo que
 * la hace útil pasado el primer día: se comprueba de un vistazo que la bola sigue en
 * marcha sin salir de donde uno estaba, y se vuelve a bajar.
 *
 * @param sinProyectos si no hay ningún proyecto todavía, para decirlo aquí: es la única
 *   tarjeta que hay, y una pantalla que solo enseña permisos no cuenta qué falta.
 */
@Composable
fun TarjetaDeConfiguracion(
    onAjustes: () -> Unit,
    sinProyectos: Boolean,
    modifier: Modifier = Modifier
) {
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

    val listo = overlayGranted && notifGranted && batteryIgnored
    // **Si la bola ya está puesta, el botón lo dice.** Un «Comenzar» que se puede pulsar
    // veinte veces no cuenta nada; lo que uno viene a mirar aquí pasado el primer día es
    // justo si el servicio sigue vivo, y eso antes había que adivinarlo asomándose a la
    // pantalla a ver si estaba la bola.
    val enMarcha = com.forge.pixpin.floating.PinHostService.enMarcha

    Card(modifier) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
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
            Spacer(Modifier.height(20.dp))

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

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = {
                    com.forge.pixpin.floating.PinHostService.start(context)
                    android.widget.Toast.makeText(
                        context, R.string.app_started, android.widget.Toast.LENGTH_LONG
                    ).show()
                },
                enabled = overlayGranted && !enMarcha,
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                if (enMarcha) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, Modifier.size(18.dp))
                    Text(
                        stringResource(R.string.app_en_marcha),
                        modifier = Modifier.padding(start = 8.dp)
                    )
                } else {
                    Text(stringResource(R.string.start_app))
                }
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
            // **La puerta a Mensajes guardados**, grande y aquí: estaba como un icono en la
            // cabecera de los proyectos y ese hueco lo ocupa ahora la caja de exportar. Esta
            // tarjeta es la portada, y es donde uno la busca. Lo pidió el usuario (6-sep-2026).
            androidx.compose.material3.OutlinedButton(
                onClick = {
                    context.startActivity(Intent(context, com.forge.pixpin.guardados.MensajesActivity::class.java))
                },
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                Icon(Icons.Filled.BookmarkBorder, contentDescription = null, Modifier.size(20.dp))
                Text(stringResource(R.string.guardados_titulo), modifier = Modifier.padding(start = 8.dp))
            }

            Spacer(Modifier.height(8.dp))
            // Ya no hace falta un botón de «Proyectos»: se está en ellos, a una
            // deslizada de aquí. Queda la puerta a los ajustes, que sigue siendo otra
            // pantalla porque son cuarenta interruptores y no una tarjeta.
            Row(Modifier.fillMaxWidth()) {
                // **El croquis en el espacio, desde aquí.** Es una aplicación aparte —otra
                // forma de dibujar, no una herramienta más del lienzo— así que necesita su
                // propia puerta, y esta tarjeta es la portada.
                TextButton(
                    onClick = {
                        com.forge.pixpin.croquis3d.Croquis3DActivity.abrir(context)
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.ViewInAr, contentDescription = null, Modifier.size(18.dp))
                    Text(
                        stringResource(R.string.croquis_titulo),
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
                TextButton(onClick = onAjustes, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Tune, contentDescription = null, Modifier.size(18.dp))
                    Text(
                        stringResource(R.string.ajustes_titulo),
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }

            if (sinProyectos) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.proyectos_vacio),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
 * Los ajustes, detrás de una puerta y **en grupos que se pliegan**.
 *
 * Sueltos y todos seguidos daban trece tarjetas del mismo tamaño y con el mismo peso, sin
 * decir cuál importa ni cuál va con cuál, y con la del motor de voz ocupando media pantalla
 * ella sola: recorrerlos era un viaje. Ahora cada grupo es una fila con su resumen, cerrada,
 * y la pantalla entera cabe de una vez; se abre solo lo que se viene a tocar.
 */
@Composable
fun PantallaDeAjustes(onVolver: () -> Unit) {
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

            GrupoDeAjustes(
                stringResource(R.string.ajustes_capturar),
                stringResource(R.string.ajustes_capturar_resumen)
            ) {
                CaptureModeCard()
                Spacer(Modifier.height(12.dp))
                FormatoDeCopiaCard()
            }

            GrupoDeAjustes(
                stringResource(R.string.ajustes_dibujar),
                stringResource(R.string.ajustes_dibujar_resumen)
            ) {
                ModoGuiaCard()
                Spacer(Modifier.height(12.dp))
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

            GrupoDeAjustes(
                stringResource(R.string.ajustes_pinear),
                stringResource(R.string.ajustes_pinear_resumen)
            ) {
                PalabrasMagicasCard()
            }

            // La voz tiene su grupo: estaba en «Aspecto» de cuando era una tarjeta pequeña,
            // y ahora es la más larga de todas y nadie la busca ahí.
            GrupoDeAjustes(
                stringResource(R.string.ajustes_voz),
                stringResource(R.string.ajustes_voz_resumen)
            ) {
                MotorDeVozCard()
            }

            GrupoDeAjustes(
                stringResource(R.string.ajustes_aspecto),
                stringResource(R.string.ajustes_aspecto_resumen)
            ) {
                ModoNocheCard()
                Spacer(Modifier.height(12.dp))
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
private fun GrupoDeAjustes(titulo: String, resumen: String, contenido: @Composable () -> Unit) {
    var abierto by rememberSaveable(titulo) { mutableStateOf(false) }
    Card(
        Modifier
            .fillMaxWidth()
            .clickable { abierto = !abierto }
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(titulo, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                // **El resumen es lo que evita abrirlo para ver qué había.** Cerrado, la
                // pantalla entera cabe de un vistazo y aun así se sabe dónde mirar.
                Text(
                    resumen,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Icon(
                if (abierto) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    // **Cerrado no se compone.** Trece tarjetas abiertas a la vez eran una pantalla que no
    // se acababa de recorrer (lo reportó el usuario el 6-sep-2026), y algunas leen del disco
    // al componerse: así solo trabaja la que se abre.
    if (abierto) {
        Spacer(Modifier.height(12.dp))
        contenido()
    }
    Spacer(Modifier.height(12.dp))
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
/**
 * Dónde sale el **modo guía**.
 *
 * Es una opción y no una herramienta —no dibuja nada, decide de qué clase sale
 * lo que se trace— así que su sitio son los ajustes y no la barra. Y va uno por
 * cada edición porque la misma función estorba en unas y hace falta en otras:
 * anotando una captura de paso, un andamio que luego hay que esconder es un
 * botón de más; levantando un plano en el editor, es la mitad del trabajo.
 */
@Composable
private fun ModoGuiaCard() {
    val context = LocalContext.current
    val app = context.applicationContext as PixPinApp
    val scope = rememberCoroutineScope()
    val ajustes by app.settings.settings.collectAsState(initial = Settings())

    fun puesto(donde: DondeSeDibuja): Boolean = when (donde) {
        DondeSeDibuja.EDITOR -> ajustes.guiaEnEditor
        DondeSeDibuja.PIN -> ajustes.guiaEnPin
        DondeSeDibuja.CAPA -> ajustes.guiaEnCapa
        DondeSeDibuja.CAPTURA -> ajustes.guiaEnCaptura
    }

    fun nombre(donde: DondeSeDibuja): Int = when (donde) {
        DondeSeDibuja.EDITOR -> R.string.ajustes_dibujar
        DondeSeDibuja.PIN -> R.string.ajustes_pinear
        DondeSeDibuja.CAPA -> R.string.ajustes_capa_nombre
        DondeSeDibuja.CAPTURA -> R.string.ajustes_capturar
    }

    Card {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.ajustes_modo_guia),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                stringResource(R.string.ajustes_modo_guia_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
            )
            for (donde in DondeSeDibuja.entries) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(nombre(donde)),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = puesto(donde),
                        onCheckedChange = { scope.launch { app.settings.setGuia(donde, it) } }
                    )
                }
            }
        }
    }
}

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
/**
 * Cuándo se pone el modo noche. Ver [com.forge.pixpin.data.ModoNoche]: la opción que
 * importa es la automática, que mira la hora **y** el sensor de luz del aparato.
 */
@Composable
private fun ModoNocheCard() {
    val context = LocalContext.current
    val app = context.applicationContext as PixPinApp
    val scope = rememberCoroutineScope()
    val settings by app.settings.settings.collectAsState(initial = com.forge.pixpin.data.Settings())
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(stringResource(R.string.modo_noche_title), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.modo_noche_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val opciones = listOf(
                    com.forge.pixpin.data.ModoNoche.SISTEMA to R.string.modo_noche_sistema,
                    com.forge.pixpin.data.ModoNoche.CLARO to R.string.modo_noche_claro,
                    com.forge.pixpin.data.ModoNoche.OSCURO to R.string.modo_noche_oscuro,
                    com.forge.pixpin.data.ModoNoche.AUTO to R.string.modo_noche_auto
                )
                for ((modo, texto) in opciones) {
                    androidx.compose.material3.FilterChip(
                        selected = settings.modoNoche == modo,
                        onClick = { scope.launch { app.settings.setModoNoche(modo) } },
                        label = { Text(stringResource(texto)) }
                    )
                }
            }
        }
    }
}

/**
 * Con qué se pasan los audios a texto: Vosk o Whisper, cada uno con su modelo, que se
 * puede bajar desde aquí sin esperar al primer audio. Ver [com.forge.pixpin.guardados.MotorVosk]
 * y [com.forge.pixpin.guardados.MotorWhisper].
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun MotorDeVozCard() {
    val context = LocalContext.current
    val app = context.applicationContext as PixPinApp
    val scope = rememberCoroutineScope()
    val settings by app.settings.settings.collectAsState(initial = com.forge.pixpin.data.Settings())
    var descargando by remember { mutableStateOf<Pair<String, Float>?>(null) }
    var version by remember { mutableStateOf(0) }
    val delTelefono = java.util.Locale.getDefault().toLanguageTag()
    val idioma = settings.idiomaDeVoz.ifBlank { delTelefono }
    val modeloWhisper = settings.modeloWhisper.takeIf { it in com.forge.pixpin.guardados.MotorWhisper.MODELOS } ?: com.forge.pixpin.guardados.MotorWhisper.MODELO_POR_DEFECTO
    val voskListo = remember(version, idioma) { com.forge.pixpin.guardados.MotorVosk.modeloListo(context, idioma) }
    val whisperListo = remember(version, modeloWhisper) { com.forge.pixpin.guardados.MotorWhisper.modeloListo(context, modeloWhisper) }
    val googleDisponible = remember { com.forge.pixpin.guardados.MotorGoogle.disponible(context) }
    fun bajar(motor: String) {
        descargando = motor to 0f
        Thread {
            val ok = if (motor == com.forge.pixpin.data.MOTOR_WHISPER)
                com.forge.pixpin.guardados.MotorWhisper.asegurarModelo(context, modeloWhisper) { descargando = motor to it } != null
            else com.forge.pixpin.guardados.MotorVosk.asegurarModelo(context, idioma) { descargando = motor to it } != null
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                descargando = null; version++
                if (!ok) android.widget.Toast.makeText(context, R.string.motor_voz_no_se_pudo, android.widget.Toast.LENGTH_LONG).show()
            }
        }.start()
    }
    fun abrir(url: String) {
        runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
    }
    // **Importar un modelo bajado a mano**: el zip de Vosk o los tres archivos de Whisper,
    // elegidos con el selector del sistema. Para cuando la descarga desde la aplicación no
    // va (red capada, otro aparato) — lo pidió el usuario (6-sep-2026).
    val importar = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        Thread {
            var hechos = 0
            for (uri in uris) {
                val nombre = runCatching {
                    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                        val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (i >= 0 && c.moveToFirst()) c.getString(i) else null
                    }
                }.getOrNull() ?: uri.lastPathSegment.orEmpty()
                val ok = context.contentResolver.openInputStream(uri)?.use { entrada ->
                    when {
                        nombre.endsWith(".zip", true) -> com.forge.pixpin.guardados.MotorVosk.instalarZip(context, entrada) != null
                        else -> com.forge.pixpin.guardados.MotorWhisper.instalarArchivo(context, nombre, entrada)
                    }
                } ?: false
                if (ok) hechos++
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                version++
                android.widget.Toast.makeText(
                    context, if (hechos > 0) R.string.motor_voz_importado else R.string.motor_voz_importar_no, android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }.start()
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(stringResource(R.string.motor_voz_title), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.motor_voz_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            // Google: el del teléfono. Ni se baja ni pesa; solo hace falta que esté.
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.FilterChip(
                    selected = settings.motorDeVoz == com.forge.pixpin.data.MOTOR_GOOGLE,
                    onClick = { scope.launch { app.settings.setMotorDeVoz(com.forge.pixpin.data.MOTOR_GOOGLE) } },
                    label = { Text(stringResource(R.string.motor_voz_google)) }
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(if (googleDisponible) R.string.motor_voz_google_ok else R.string.motor_voz_google_no),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = {
                    runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS)) }
                }) { Text("Ajustes") }
            }
            Text(
                stringResource(R.string.motor_voz_google_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            for ((motor, texto, listo) in listOf(
                Triple(com.forge.pixpin.data.MOTOR_VOSK, stringResource(R.string.motor_voz_vosk), voskListo),
                Triple(com.forge.pixpin.data.MOTOR_WHISPER, stringResource(R.string.motor_voz_whisper, modeloWhisper, com.forge.pixpin.guardados.MotorWhisper.MODELOS[modeloWhisper] ?: 0), whisperListo)
            )) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.FilterChip(
                        selected = settings.motorDeVoz == motor,
                        onClick = { scope.launch { app.settings.setMotorDeVoz(motor) } },
                        label = { Text(texto) }
                    )
                    Spacer(Modifier.width(10.dp))
                    val enCurso = descargando?.takeIf { it.first == motor }
                    Text(
                        when {
                            enCurso != null -> stringResource(R.string.motor_voz_descargando, (enCurso.second * 100).toInt())
                            listo -> stringResource(R.string.motor_voz_descargado)
                            else -> stringResource(R.string.motor_voz_sin_descargar)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    if (!listo && enCurso == null) {
                        TextButton(onClick = { bajar(motor) }, enabled = descargando == null) { Text(stringResource(R.string.motor_voz_descargar)) }
                    }
                }
            }
            // **El tamaño de Whisper**: tiny, base o small. Cada uno se baja aparte.
            Text(stringResource(R.string.motor_voz_tamano_whisper), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((nombre, peso) in com.forge.pixpin.guardados.MotorWhisper.MODELOS) {
                    val bajado = remember(version) { com.forge.pixpin.guardados.MotorWhisper.modeloListo(context, nombre) }
                    androidx.compose.material3.FilterChip(
                        selected = modeloWhisper == nombre,
                        onClick = { scope.launch { app.settings.setModeloWhisper(nombre) } },
                        label = { Text(if (bajado) "$nombre ✓" else "$nombre ($peso MB)") }
                    )
                }
            }
            // **En qué idioma se habla**, y un segundo para Whisper. Ver Settings.idiomaDeVoz.
            Text(stringResource(R.string.motor_voz_idioma), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                androidx.compose.material3.FilterChip(
                    selected = settings.idiomaDeVoz.isBlank(),
                    onClick = { scope.launch { app.settings.setIdiomaDeVoz("") } },
                    label = { Text(stringResource(R.string.motor_voz_idioma_telefono, delTelefono)) }
                )
                for ((codigo, nombre) in IDIOMAS_DE_VOZ) {
                    androidx.compose.material3.FilterChip(
                        selected = settings.idiomaDeVoz == codigo,
                        onClick = { scope.launch { app.settings.setIdiomaDeVoz(codigo) } },
                        label = { Text(nombre) }
                    )
                }
            }
            Text(stringResource(R.string.motor_voz_segundo_idioma), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                androidx.compose.material3.FilterChip(
                    selected = settings.segundoIdiomaDeVoz.isBlank(),
                    onClick = { scope.launch { app.settings.setSegundoIdiomaDeVoz("") } },
                    label = { Text(stringResource(R.string.motor_voz_segundo_ninguno)) }
                )
                for ((codigo, nombre) in IDIOMAS_DE_VOZ) {
                    if (codigo == idioma.substringBefore('-').lowercase()) continue
                    androidx.compose.material3.FilterChip(
                        selected = settings.segundoIdiomaDeVoz == codigo,
                        onClick = { scope.launch { app.settings.setSegundoIdiomaDeVoz(codigo) } },
                        label = { Text(nombre) }
                    )
                }
            }
            // **Con dos idiomas, qué hace Whisper**: cada trozo en el suyo, o todo en el primero (traducido).
            if (settings.segundoIdiomaDeVoz.isNotBlank()) {
                Text(stringResource(R.string.motor_voz_modo_idiomas), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((modo, texto) in listOf(
                        com.forge.pixpin.data.MODO_CADA_IDIOMA to R.string.motor_voz_modo_cada_uno,
                        com.forge.pixpin.data.MODO_TODO_EN_UNO to R.string.motor_voz_modo_todo_en_uno
                    )) {
                        androidx.compose.material3.FilterChip(
                            selected = settings.modoDeIdiomas == modo,
                            onClick = { scope.launch { app.settings.setModoDeIdiomas(modo) } },
                            label = { Text(stringResource(texto, IDIOMAS_DE_VOZ.firstOrNull { it.first == idioma.substringBefore('-').lowercase() }?.second ?: idioma)) }
                        )
                    }
                }
            }
            // Los enlaces de los modelos, para bajarlos con el navegador e importarlos.
            TextButton(onClick = { abrir(com.forge.pixpin.guardados.MotorVosk.enlaceDelModelo(idioma)) }) { Text(stringResource(R.string.motor_voz_enlace_vosk)) }
            TextButton(onClick = { com.forge.pixpin.guardados.MotorWhisper.enlaces(modeloWhisper).forEach { abrir(it) } }) { Text(stringResource(R.string.motor_voz_enlace_whisper, modeloWhisper)) }
            TextButton(onClick = { importar.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.motor_voz_importar)) }
            TextButton(onClick = { abrir("https://alphacephei.com/vosk/models") }) { Text(stringResource(R.string.motor_voz_enlace)) }
        }
    }
}

/** Los idiomas que se ofrecen para los audios: los que tienen modelo pequeño de Vosk (y Whisper los entiende todos). */
private val IDIOMAS_DE_VOZ = listOf(
    "es" to "Español", "en" to "Inglés", "pt" to "Portugués", "fr" to "Francés",
    "de" to "Alemán", "it" to "Italiano", "ca" to "Catalán"
)

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

/**
 * Qué palabra abre qué herramienta.
 *
 * ## Por qué se edita por herramienta y en un diálogo
 *
 * Lo que uno quiere cambiar es «qué palabras abren la ruleta», no «a qué apunta
 * la palabra *sorteo*»: se piensa desde la herramienta. Por eso hay una fila por
 * mini-aplicación con sus palabras, y no una lista de palabras sueltas.
 *
 * Y va en un diálogo en vez de un campo escribible en la propia fila porque el
 * campo tendría que guardar en cada letra, y guardar significa releer y volver a
 * pintar la lista: el cursor saltaría al final a mitad de palabra. Escribiendo
 * en el diálogo, lo tecleado es del diálogo hasta que se acepta.
 *
 * La lista de filas sale del propio enum: una mini-aplicación nueva aparece aquí
 * sola, como pasa con las clases del imán y con las herramientas de la barra.
 */
@Composable
private fun PalabrasMagicasCard() {
    val context = LocalContext.current
    val app = context.applicationContext as PixPinApp
    val scope = rememberCoroutineScope()
    val ajustes by app.settings.settings.collectAsState(initial = com.forge.pixpin.data.Settings())
    val palabras = ajustes.palabras
    var editando by remember { mutableStateOf<MiniApp?>(null) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.palabras_title), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.palabras_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
            )

            MiniApp.entries.forEach { cual ->
                val suyas = MagicWord.deLaApp(palabras, cual)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { editando = cual }
                        .padding(vertical = 8.dp)
                ) {
                    Text(
                        stringResource(nombreDe(cual)),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.width(120.dp)
                    )
                    Text(
                        text = suyas.joinToString(", ")
                            .ifEmpty { stringResource(R.string.palabras_ninguna) },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (suyas.isEmpty()) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            }

            TextButton(
                onClick = { scope.launch { app.settings.resetPalabras() } },
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Text(stringResource(R.string.palabras_reset))
            }
        }
    }

    editando?.let { cual ->
        DialogoDePalabras(
            titulo = stringResource(nombreDe(cual)),
            inicial = MagicWord.deLaApp(palabras, cual).joinToString(", "),
            onCerrar = { editando = null },
            onAceptar = { escrito ->
                val nuevas = escrito.split(',', '\n')
                scope.launch { app.settings.setPalabras(MagicWord.conLasDe(palabras, cual, nuevas)) }
                editando = null
            }
        )
    }
}

/** Cómo se llama cada mini-aplicación en los ajustes. */
private fun nombreDe(app: MiniApp): Int = when (app) {
    MiniApp.TIMER -> R.string.palabras_timer
    MiniApp.STOPWATCH -> R.string.palabras_stopwatch
    MiniApp.CHECKLIST -> R.string.palabras_checklist
    MiniApp.COUNTER -> R.string.palabras_counter
    MiniApp.LEDGER -> R.string.palabras_ledger
    MiniApp.BOARD -> R.string.palabras_board
    MiniApp.RULETA -> R.string.palabras_ruleta
    MiniApp.DRAW -> R.string.palabras_draw
    MiniApp.SHEET -> R.string.palabras_sheet
}

@Composable
private fun DialogoDePalabras(
    titulo: String,
    inicial: String,
    onCerrar: () -> Unit,
    onAceptar: (String) -> Unit
) {
    var texto by remember { mutableStateOf(inicial) }
    AlertDialog(
        onDismissRequest = onCerrar,
        title = { Text(titulo) },
        text = {
            Column {
                OutlinedTextField(
                    value = texto,
                    onValueChange = { texto = it },
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    stringResource(R.string.palabras_ayuda),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        },
        confirmButton = { TextButton(onClick = { onAceptar(texto) }) { Text("Aceptar") } },
        dismissButton = { TextButton(onClick = onCerrar) { Text("Cancelar") } }
    )
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
