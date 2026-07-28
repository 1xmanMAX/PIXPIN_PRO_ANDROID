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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
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
import com.forge.pixpin.data.CrashLog
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
                text = stringResource(R.string.onboarding_subtitle),
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(24.dp))

            items.forEach { item ->
                PermissionCard(item)
                Spacer(Modifier.height(12.dp))
            }

            Spacer(Modifier.height(8.dp))
            CaptureModeCard()

            Spacer(Modifier.height(12.dp))
            CrashReportCard()

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = {
                    com.forge.pixpin.floating.PinHostService.start(context)
                    android.widget.Toast.makeText(
                        context, R.string.app_started, android.widget.Toast.LENGTH_LONG
                    ).show()
                },
                enabled = overlayGranted,
                modifier = Modifier.fillMaxWidth()
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
