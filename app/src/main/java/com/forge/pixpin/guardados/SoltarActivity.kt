package com.forge.pixpin.guardados

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.DragEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.forge.pixpin.PixPinApp
import com.forge.pixpin.ui.theme.PixPinTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * **El recuadro para soltar** (como el del PC, 3-oct-2026): lo que se arrastra hasta él —archivos,
 * fotos, texto— entra en el chat elegido, y se queda abierto para soltar más.
 *
 * En Android solo se puede arrastrar de una aplicación a otra **con las dos a la vista**: pantalla
 * partida o ventana emergente. Por eso esto es una ventanita que se pone al lado de la otra app
 * (Archivos, la galería, el navegador), y no una ventana flotante del servicio: una ventana del
 * servicio recibiría lo soltado pero no podría pedir permiso para leerlo.
 */
class SoltarActivity : ComponentActivity() {

    private var estado by mutableStateOf("")
    private var proyecto by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        proyecto = intent.getStringExtra(EXTRA_PROYECTO)
        window.decorView.setOnDragListener { _, e ->
            when (e.action) {
                DragEvent.ACTION_DRAG_STARTED -> true
                DragEvent.ACTION_DRAG_ENTERED -> { estado = "Suéltalo aquí"; true }
                DragEvent.ACTION_DRAG_EXITED -> { estado = ""; true }
                DragEvent.ACTION_DROP -> { soltar(e); true }
                else -> true
            }
        }
        setContent { PixPinTheme { Pantalla() } }
    }

    /** Lo soltado: el permiso se pide ya (dura lo que dure esta pantalla) y se copia fuera del hilo. */
    private fun soltar(e: DragEvent) {
        val permisos = runCatching { requestDragAndDropPermissions(e) }.getOrNull()
        val clip = e.clipData ?: run { estado = "No se pudo añadir nada"; return }
        val elegido = proyecto
        estado = "Añadiendo…"
        lifecycleScope.launch {
            val n = withContext(Dispatchers.IO) {
                var n = 0
                for (i in 0 until clip.itemCount) {
                    val it = clip.getItemAt(i)
                    when {
                        it.uri != null -> if (AlChat.meter(this@SoltarActivity, it.uri, elegido)) n++
                        !it.text.isNullOrBlank() -> { AlChat.meterTexto(this@SoltarActivity, it.text.toString(), elegido); n++ }
                    }
                }
                n
            }
            permisos?.release()
            estado = when (n) { 0 -> "No se pudo añadir nada"; 1 -> "1 añadido al chat"; else -> "$n añadidos al chat" }
            delay(2200)
            estado = ""
        }
    }

    @Composable
    private fun Pantalla() {
        val app = application as PixPinApp
        val proyectos by app.proyectos.proyectos.collectAsState()
        val nombre = proyectos.firstOrNull { it.id == proyecto }?.nombre ?: "Mensajes guardados"
        var eligiendo by remember { mutableStateOf(false) }
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        AssistChip(onClick = { eligiendo = true }, label = { Text(nombre) })
                        DropdownMenu(expanded = eligiendo, onDismissRequest = { eligiendo = false }) {
                            DropdownMenuItem(text = { Text("Mensajes guardados") }, onClick = { proyecto = null; eligiendo = false })
                            proyectos.filter { !it.archivado }.sortedByDescending { it.tocado }.forEach { p ->
                                DropdownMenuItem(text = { Text(p.nombre) }, onClick = { proyecto = p.id; eligiendo = false })
                            }
                        }
                    }
                    IconButton(onClick = { finish() }) { Icon(Icons.Filled.Close, "Cerrar") }
                }
                Box(
                    Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp)
                        .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f), RoundedCornerShape(20.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(20.dp)) {
                        Icon(Icons.Filled.FileDownload, null, Modifier.size(42.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            estado.ifEmpty { "Suelta aquí para añadir a «$nombre»" },
                            textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium
                        )
                        if (estado.isEmpty()) Text(
                            "Pon PixPin al lado de la otra app (pantalla partida o ventana emergente) y arrastra lo que quieras.",
                            textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }
        }
    }

    companion object {
        private const val EXTRA_PROYECTO = "soltar_proyecto"
        fun abrir(context: Context, proyecto: String? = null) = context.startActivity(
            Intent(context, SoltarActivity::class.java).putExtra(EXTRA_PROYECTO, proyecto)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
        )
    }
}
