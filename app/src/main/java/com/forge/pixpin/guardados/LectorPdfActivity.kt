package com.forge.pixpin.guardados

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forge.pixpin.R
import com.forge.pixpin.motor.PdfDoc
import com.forge.pixpin.ui.theme.PixPinTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * **Un PDF guardado, leído aquí dentro y sin salir de la aplicación.**
 *
 * Ligero a propósito: una página a la vez, a pantalla, con «anterior» y «siguiente» —para
 * ojear un plano o un documento sin abrir otra aplicación ni montar un visor de tres pisos.
 * La página se dibuja con [PdfDoc.render] fuera del hilo de la interfaz. Para anotar una
 * página está el editor de siempre, desde el mensaje que la tenga.
 *
 * Antes, tocar un PDF guardado como archivo lo abría en la aplicación de fuera
 * ([MensajesActivity]); ahora se ve aquí. Lo pidió el usuario.
 */
class LectorPdfActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ruta = intent.getStringExtra(LA_RUTA).orEmpty()
        val titulo = intent.getStringExtra(EL_TITULO).orEmpty()
        setContent {
            PixPinTheme {
                Lector(
                    ruta = ruta,
                    titulo = titulo,
                    alCerrar = { finish() },
                    avisoCancelar = getString(R.string.cancel),
                    nombreDeLaApp = getString(R.string.app_name)
                )
            }
        }
    }

    companion object {
        private const val LA_RUTA = "ruta"
        private const val EL_TITULO = "titulo"

        fun abrir(contexto: Context, ruta: String, titulo: String) {
            contexto.startActivity(
                Intent(contexto, LectorPdfActivity::class.java)
                    .putExtra(LA_RUTA, ruta)
                    .putExtra(EL_TITULO, titulo)
            )
        }
    }
}

@Composable
private fun Lector(
    ruta: String,
    titulo: String,
    alCerrar: () -> Unit,
    avisoCancelar: String,
    nombreDeLaApp: String
) {
    var paginas by remember(ruta) { mutableIntStateOf(-1) }
    var actual by remember(ruta) { mutableIntStateOf(0) }

    // Cuántas páginas tiene: una sola apertura del documento, fuera de la pantalla.
    LaunchedEffect(ruta) {
        paginas = withContext(Dispatchers.IO) { PdfDoc.pageCount(ruta) }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = alCerrar) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = avisoCancelar
                    )
                }
                Text(
                    titulo.ifBlank { nombreDeLaApp },
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            when {
                paginas < 0 -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                paginas == 0 -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No se pudo leer el PDF", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        Pagina(ruta, actual)
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.TextButton(
                            onClick = { if (actual > 0) actual-- },
                            enabled = actual > 0
                        ) { Text("Anterior") }
                        Text(
                            "${actual + 1} / $paginas",
                            fontSize = 14.sp,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                        androidx.compose.material3.TextButton(
                            onClick = { if (actual + 1 < paginas) actual++ },
                            enabled = actual + 1 < paginas
                        ) { Text("Siguiente") }
                    }
                }
            }
        }
    }
}

/** La página [pagina] dibujada a pantalla, fuera del hilo de la interfaz. */
@Composable
private fun Pagina(ruta: String, pagina: Int) {
    val bmp by produceState<Bitmap?>(null, ruta, pagina) {
        value = withContext(Dispatchers.IO) { PdfDoc.render(ruta, pagina, PdfDoc.ZOOM_WIDTH) }
    }
    val actual = bmp
    if (actual == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else {
        Image(
            bitmap = actual.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )
    }
}
