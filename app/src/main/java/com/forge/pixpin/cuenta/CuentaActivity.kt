package com.forge.pixpin.cuenta

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * **La cuenta** (7-nov-2026): entrar con Google, el plan (gratis, la prueba de Pro o Pro, y hasta cuándo), el cupo
 * del asistente de hoy, qué incluye Pro y cerrar la sesión. Si se abre para una función de Pro ([abrirParaPro]),
 * enseña esa función arriba.
 */
class CuentaActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val paraFuncion = intent.getStringExtra(PARA)
        setContent { com.forge.pixpin.ui.theme.PixPinTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Pantalla(paraFuncion) } } }
    }

    @Composable
    private fun Pantalla(paraFuncion: String?) {
        val datos by Cuenta.datos.collectAsState()
        val foto by Disponible.ahora.collectAsState()
        val cupo by Cuenta.cupo.collectAsState()
        val alcance = rememberCoroutineScope()
        var entrando by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { runCatching { Cuenta.refrescar(this@CuentaActivity) } }
        val lic = Disponible.licencia()
        val fecha = DateFormat.getDateInstance(DateFormat.LONG)

        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") }
                Text("Cuenta", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))

            // Lo que se vino a buscar: una función de Pro.
            paraFuncion?.let { id ->
                Tarjeta {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.WorkspacePremium, null, tint = Color(0xFFE8890C))
                        Text("  ${foto.nombres[id] ?: "Esta función"} es parte de Pro", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    }
                    Text(
                        if (datos == null) "Entra con tu cuenta de Google: las cuentas nuevas tienen 30 días de Pro para probarlo todo."
                        else "Tu prueba ya terminó. Lo que hiciste con ella sigue siendo tuyo: puedes verlo y exportarlo.",
                        fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp)
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            val d = datos
            if (d == null) {
                Tarjeta {
                    Text("Entra con tu cuenta de Google", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                    Text(
                        "Así tus funciones te siguen en cualquier teléfono, el asistente funciona sin poner ninguna clave y, " +
                            "si eres nuevo, tienes 30 días de Pro gratis.",
                        fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                    )
                    if (!Cuenta.configurada) Text("Esta versión aún no trae cuentas.", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                    else Button(
                        onClick = {
                            entrando = true
                            alcance.launch {
                                when (val e = Cuenta.entrar(this@CuentaActivity)) {
                                    is Cuenta.Entrada.Dentro -> Toast.makeText(this@CuentaActivity, if (e.nueva) "¡Bienvenido! Tienes 30 días de Pro." else "Hola de nuevo", Toast.LENGTH_LONG).show()
                                    Cuenta.Entrada.Bloqueada -> Toast.makeText(this@CuentaActivity, "Esta cuenta está bloqueada", Toast.LENGTH_LONG).show()
                                    is Cuenta.Entrada.Fallo -> if (e.motivo != "Cancelado") Toast.makeText(this@CuentaActivity, e.motivo, Toast.LENGTH_LONG).show()
                                }
                                entrando = false
                            }
                        },
                        enabled = !entrando, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(14.dp)
                    ) {
                        if (entrando) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Entrar con Google", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            } else {
                Tarjeta {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(52.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                            Text((d.nombre ?: d.email).take(1).uppercase(), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        Column(Modifier.padding(start = 14.dp)) {
                            d.nombre?.let { Text(it, fontWeight = FontWeight.SemiBold, fontSize = 17.sp) }
                            Text(d.email, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Tarjeta {
                    val (plan, color) = when (lic?.plan) {
                        "pro" -> "Pro" to Color(0xFFE8890C)
                        "prueba" -> "Prueba de Pro" to Color(0xFF0A84FF)
                        else -> "Gratis" to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Text("Tu plan", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(plan, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = color)
                    lic?.hasta?.let { h ->
                        val dias = ((h - System.currentTimeMillis()) / 86_400_000L).coerceAtLeast(0)
                        Text(
                            if (lic.plan == "prueba") "Te quedan $dias días · hasta el ${fecha.format(Date(h))}" else "Hasta el ${fecha.format(Date(h))}",
                            fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    cupo?.let { (quedan, total) ->
                        Text("Asistente: te quedan $quedan de $total preguntas hoy", fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }

            // Qué incluye Pro: lo que el catálogo tiene como Pro ahora (y lo que pasará a Pro pronto).
            val dePro = foto.estados.filter { it.value == Disponible.Estado.PRO }.keys
            if (dePro.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Tarjeta {
                    Text("Qué incluye Pro", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.padding(bottom = 6.dp))
                    dePro.forEach { id ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                            Icon(Icons.Filled.CheckCircle, null, tint = Color(0xFF30A14E), modifier = Modifier.size(18.dp))
                            Text("  ${foto.nombres[id] ?: id}", fontSize = 14.sp)
                        }
                    }
                }
            }

            if (d != null) {
                Spacer(Modifier.height(20.dp))
                OutlinedButton(onClick = { Cuenta.salir(this@CuentaActivity) }, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(14.dp)) {
                    Text("Cerrar sesión en este teléfono")
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    @Composable
    private fun Tarjeta(contenido: @Composable () -> Unit) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) { contenido() }
        }
    }

    companion object {
        private const val PARA = "cuenta_para_funcion"
        fun abrir(c: Context) = c.startActivity(Intent(c, CuentaActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        /** Al tocar una función de Pro sin tener Pro: qué es y cómo tenerla (una vez; nunca candados por todas partes). */
        fun abrirParaPro(c: Context, funcion: String) =
            c.startActivity(Intent(c, CuentaActivity::class.java).putExtra(PARA, funcion).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
