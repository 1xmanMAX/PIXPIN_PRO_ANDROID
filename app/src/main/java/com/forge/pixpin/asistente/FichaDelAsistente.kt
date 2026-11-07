package com.forge.pixpin.asistente

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.forge.pixpin.PixPinApp
import com.forge.pixpin.ia.Nube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * **La ficha del chat «Asistente»** (5-nov-2026, el usuario: «la configuración del asistente tiene que ser como en
 * Windows: la actual parece la de cualquier chat, y el asistente es un chat especial»). Lo de los tres puntos del
 * chat «Asistente» del PC (`menu_de_asistente`): **el modelo** que contesta (con el elegido marcado), **conversación
 * nueva**, la clave de la nube y sus ajustes, y **borrar el chat** en rojo; lo de cualquier chat (fotos, archivos y
 * enlaces) queda debajo, apartado.
 */
@Composable
fun FichaDelAsistente(
    /** Cuántos mensajes tiene el chat. */
    cuantos: Int,
    onCerrar: () -> Unit,
    onNuevaConversacion: () -> Unit,
    onVerArchivos: () -> Unit,
    onBorrarChat: () -> Unit
) {
    val contexto = LocalContext.current
    val app = contexto.applicationContext as? PixPinApp ?: return
    val ajustes by app.settings.settings.collectAsState(initial = app.ajustes)
    val alcance = rememberCoroutineScope()
    val proveedor = Nube.Proveedor.elegido(ajustes.asistenteMotor)
    var conClave by remember(proveedor) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(proveedor) { conClave = withContext(Dispatchers.IO) { ClavesDeLaNube.leer(contexto, proveedor) != null } }
    var confirmandoBorrar by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onCerrar, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onCerrar) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") }
                }
                // La cabecera: el asistente, quién contesta y cuánto hay en su chat.
                Column(Modifier.fillMaxWidth().padding(bottom = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.size(84.dp).clip(CircleShape)
                            .background(Brush.linearGradient(listOf(Color(0xFF7C4DFF), Color(0xFF0A9AE0)))),
                        contentAlignment = Alignment.Center
                    ) { Text("✨", fontSize = 38.sp) }
                    Spacer(Modifier.height(10.dp))
                    Text(ChatDelAsistente.NOMBRE, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Contesta ${proveedor.nombre} · $cuantos mensajes",
                        fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // El modelo, como la pastilla y el submenú del PC.
                Titulo("Modelo")
                Grupo {
                    Nube.Proveedor.entries.forEachIndexed { i, p ->
                        if (i > 0) Raya()
                        Fila(
                            Icons.Filled.Psychology, p.nombre,
                            detalle = if (p == Nube.Proveedor.CLAUDE) "Anthropic" else "DeepSeek",
                            color = if (p == Nube.Proveedor.CLAUDE) Color(0xFFD97757) else Color(0xFF4D6BFE),
                            marcada = p == proveedor
                        ) { alcance.launch { app.settings.setAsistenteMotor(p.id) } }
                    }
                }
                if (conClave == false) Text(
                    "Sin la clave de ${proveedor.nombre} solo funcionan las órdenes simples («apunta pan»). Pégala en «Clave y ajustes».",
                    fontSize = 13.sp, color = Color(0xFFE53935), modifier = Modifier.padding(horizontal = 28.dp, vertical = 6.dp)
                )

                // **El acceso de la fase de prueba** (6-nov-2026): entrar con Google; si el correo está en la lista, el
                // asistente contesta sin clave propia. Ver [AccesoDePrueba].
                if (AccesoDePrueba.configurado) {
                    var correo by remember { mutableStateOf(AccesoDePrueba.correo(contexto)) }
                    var entrando by remember { mutableStateOf(false) }
                    Titulo("Fase de prueba")
                    Grupo {
                        if (correo == null) Fila(
                            Icons.Filled.Key, if (entrando) "Entrando…" else "Entrar con Google",
                            detalle = "Para los que están en la lista de la prueba: el asistente sin clave propia", color = Color(0xFF4285F4)
                        ) {
                            val actividad = contexto as? android.app.Activity
                            if (actividad != null && !entrando) {
                                entrando = true
                                alcance.launch {
                                    val r = AccesoDePrueba.entrar(actividad)
                                    entrando = false
                                    when (r) {
                                        is AccesoDePrueba.Entrada.Dentro -> { correo = r.email; conClave = true }
                                        is AccesoDePrueba.Entrada.SinAcceso -> android.widget.Toast.makeText(contexto, "${r.email ?: "Ese correo"} no está en la lista de la prueba", android.widget.Toast.LENGTH_LONG).show()
                                        is AccesoDePrueba.Entrada.Fallo -> android.widget.Toast.makeText(contexto, r.motivo, android.widget.Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        } else Fila(Icons.Filled.Key, correo!!, detalle = "Dentro de la prueba · toca para salir", color = Color(0xFF34C759)) {
                            AccesoDePrueba.salir(contexto); correo = null
                            alcance.launch { conClave = withContext(Dispatchers.IO) { ClavesDeLaNube.leer(contexto, proveedor) != null } }
                        }
                    }
                }

                Titulo("Conversación")
                Grupo {
                    Fila(Icons.Filled.AddComment, "Conversación nueva", detalle = "Lo de arriba ya no se le manda", color = Color(0xFF34C759)) {
                        onNuevaConversacion(); onCerrar()
                    }
                    Raya()
                    Fila(Icons.Filled.Key, "Clave y ajustes del asistente", detalle = when {
                        ClavesDeLaNube.esDePrueba(contexto, proveedor) -> "Con el acceso de la fase de prueba"
                        conClave == true -> "Clave guardada en este teléfono"
                        else -> "Sin clave"
                    }, color = Color(0xFFFF9500)) {
                        com.forge.pixpin.ConfiguracionActivity.abrir(contexto, "ASISTENTE")
                    }
                    Raya()
                    Fila(Icons.Filled.Settings, "Pensar antes de contestar", detalle = if (ajustes.asistentePensar == "no") "No" else "Sí", color = Color(0xFF8E8E93)) {
                        com.forge.pixpin.ConfiguracionActivity.abrir(contexto, "ASISTENTE")
                    }
                }

                // Lo de cualquier chat, apartado.
                Titulo("Del chat")
                Grupo {
                    Fila(Icons.Filled.PhotoLibrary, "Fotos, archivos y enlaces", color = Color(0xFF0A84FF), onClick = onVerArchivos)
                }
                Spacer(Modifier.height(18.dp))
                Grupo {
                    Fila(Icons.Filled.Delete, "Borrar chat", color = Color(0xFFE53935), peligro = true) { confirmandoBorrar = true }
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
    if (confirmandoBorrar) AlertDialog(
        onDismissRequest = { confirmandoBorrar = false },
        title = { Text("Borrar chat") },
        text = { Text("Se borran todos los mensajes del chat «Asistente» (los adjuntos con ellos). ¿Seguir?") },
        confirmButton = { TextButton(onClick = { confirmandoBorrar = false; onBorrarChat(); onCerrar() }) { Text("Borrar", color = Color(0xFFE53935)) } },
        dismissButton = { TextButton(onClick = { confirmandoBorrar = false }) { Text("Cancelar") } }
    )
}

@Composable
private fun Titulo(t: String) = Text(
    t.uppercase(), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(start = 30.dp, top = 14.dp, bottom = 6.dp)
)

@Composable
private fun Grupo(contenido: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
    ) { Column { contenido() } }
}

@Composable
private fun Raya() = HorizontalDivider(Modifier.padding(start = 58.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

@Composable
private fun Fila(
    icono: ImageVector, texto: String, detalle: String? = null, color: Color, marcada: Boolean = false,
    peligro: Boolean = false, onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(color), contentAlignment = Alignment.Center) {
            Icon(icono, null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(texto, fontSize = 16.sp, color = if (peligro) Color(0xFFE53935) else MaterialTheme.colorScheme.onSurface)
            if (detalle != null) Text(detalle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (marcada) Icon(Icons.Filled.Check, "Elegido", tint = Color(0xFF0A84FF))
    }
}
