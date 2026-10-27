package com.forge.pixpin.guardados

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * **«Guardar» de un archivo abierto de paso** (27-oct-2026). Ver [ModoDePaso].
 *
 * Una pantalla aparte, y **opaca**, a propósito: al taparlo, el visor pasa por `onPause` y `onStop`
 * y deja en disco lo anotado hasta ese momento —cada visor lo guarda ahí—; solo entonces se lleva
 * al chat ([AbrirDePaso.guardar]). Después abre el mismo archivo **ya guardado** en un visor nuevo
 * (con su mensaje, como desde el chat, y el botón en «Guardado ✓») y cierra la tarea del de paso.
 *
 * Sin preguntas al salir (el usuario: «que no me pregunte cada vez al salir, sino que el botón me
 * permita guardarlo rápido o no»): lo que no se guarda con el botón se va al cerrar.
 */
class GuardarDePasoActivity : LanzadorDePaso() {

    private var trabajando by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val e = AbrirDePaso.de(intent)
        if (e == null || e.guardado != null) { finish(); return }
        val elegir = intent.getBooleanExtra(EXTRA_ELEGIR_CHAT, false)
        val chats = if (elegir) AbrirDePaso.chats(this) else emptyList()
        var eligiendo by mutableStateOf(elegir && chats.isNotEmpty())
        setContent {
            com.forge.pixpin.ui.theme.PixPinTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (!eligiendo) Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(16.dp))
                            Text("Guardando «${e.nombre}»…", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 24.dp))
                        }
                    }
                    if (eligiendo) AlertDialog(
                        onDismissRequest = { finish() },
                        title = { Text("Guardar en…") },
                        text = {
                            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                                item { Destino(getString(com.forge.pixpin.R.string.guardados_titulo)) { eligiendo = false; hacer(e, null) } }
                                items(chats, key = { it.first }) { (id, nombre) -> Destino(nombre) { eligiendo = false; hacer(e, id) } }
                            }
                        },
                        confirmButton = { TextButton(onClick = { finish() }) { Text(getString(com.forge.pixpin.R.string.cancel)) } }
                    )
                }
            }
        }
        if (!eligiendo) hacer(e, null)
    }

    @androidx.compose.runtime.Composable
    private fun Destino(nombre: String, alElegir: () -> Unit) {
        Text(
            nombre, style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.fillMaxWidth().clickable(onClick = alElegir).padding(vertical = 14.dp)
        )
    }

    private fun hacer(e: AbrirDePaso.Estado, proyecto: String?) {
        if (trabajando) return
        trabajando = true
        lifecycleScope.launch {
            // Lo que el visor escribe al pararse va por su hilo de disco: un respiro para que acabe.
            delay(400)
            val hecho = withContext(Dispatchers.IO) { runCatching { AbrirDePaso.guardar(applicationContext, e, proyecto) }.getOrNull() }
            if (hecho == null) {
                Toast.makeText(this@GuardarDePasoActivity, "No se pudo guardar", Toast.LENGTH_SHORT).show()
                trabajando = false
                finish()
                return@launch
            }
            val donde = proyecto?.let { p -> AbrirDePaso.chats(this@GuardarDePasoActivity).firstOrNull { it.first == p }?.second }
                ?: getString(com.forge.pixpin.R.string.guardados_titulo)
            Toast.makeText(this@GuardarDePasoActivity, "Guardado en «$donde»", Toast.LENGTH_SHORT).show()
            // El mismo archivo, ya del chat, en un visor nuevo; y fuera la tarea del de paso (esta incluida).
            runCatching { AbrirDePaso.abrirEnSuVisor(this@GuardarDePasoActivity, hecho) }
            finishAndRemoveTask()
        }
    }

    companion object {
        private const val EXTRA_ELEGIR_CHAT = "paso_elegir_chat"

        /** Guarda lo de [e] en Mensajes guardados o, con [elegirChat], en el chat que se elija. Desde el visor. */
        fun abrir(c: Context, e: AbrirDePaso.Estado, elegirChat: Boolean) {
            runCatching {
                c.startActivity(AbrirDePaso.alGuardar(Intent(c, GuardarDePasoActivity::class.java), e).putExtra(EXTRA_ELEGIR_CHAT, elegirChat))
            }
        }
    }
}
