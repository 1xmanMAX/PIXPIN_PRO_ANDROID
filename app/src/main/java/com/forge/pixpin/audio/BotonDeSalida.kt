package com.forge.pixpin.audio

import android.widget.Toast
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.forge.pixpin.audio.DecisionDeSalida.Preferida
import com.forge.pixpin.audio.DecisionDeSalida.Ruta

/**
 * **El botón de por dónde suena**: altavoz ↔ auricular de llamadas (27-oct-2026). El mismo en
 * todos los reproductores, como el de Telegram en su barra de audio: el icono dice por dónde
 * suena **ahora** —altavoz, auricular o audífonos— y tocarlo cambia lo elegido. Con audífonos
 * puestos se ve el de audífonos (mandan ellos), pero tocar sigue cambiando lo que se usará al
 * quitarlos. Ver [SalidaDeAudio].
 */
@Composable
fun BotonDeSalida(modifier: Modifier = Modifier, tamano: Dp = 36.dp, tinta: Color = Color.Unspecified) {
    val contexto = LocalContext.current
    LaunchedEffect(Unit) { SalidaDeAudio.iniciar(contexto) }
    val ruta by SalidaDeAudio.ruta.collectAsState()
    val preferida by SalidaDeAudio.preferida.collectAsState()
    IconButton(
        onClick = {
            val nueva = SalidaDeAudio.alternar(contexto)
            val aviso = when {
                SalidaDeAudio.ruta.value == Ruta.AURICULARES ->
                    if (nueva == Preferida.OIDO) "Con los audífonos puestos suena por ellos; al quitarlos, por el auricular"
                    else "Con los audífonos puestos suena por ellos; al quitarlos, por el altavoz"
                nueva == Preferida.OIDO -> "Por el auricular de llamadas: acércate el teléfono a la oreja"
                else -> "Por el altavoz"
            }
            Toast.makeText(contexto, aviso, Toast.LENGTH_SHORT).show()
        },
        modifier = modifier.size(tamano)
    ) {
        val (icono, texto) = when (ruta) {
            Ruta.AURICULARES -> Icons.Filled.Headphones to "Suena por los audífonos"
            Ruta.OIDO -> Icons.Filled.PhoneInTalk to "Suena por el auricular de llamadas"
            Ruta.ALTAVOZ -> Icons.AutoMirrored.Filled.VolumeUp to "Suena por el altavoz"
        }
        Icon(
            icono,
            contentDescription = texto,
            // Lo elegido a mano se ve en el color de acento: así se distingue el auricular por la
            // oreja (cercanía, gris) del auricular elegido (acento).
            tint = if (preferida == Preferida.OIDO) MaterialTheme.colorScheme.primary
            else if (tinta != Color.Unspecified) tinta else LocalContentColor.current,
            modifier = Modifier.size(tamano * 0.6f)
        )
    }
}
