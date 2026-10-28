package com.forge.pixpin.sincro

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * **La tira discreta de «Cámara para la reunión del PC»** (28-oct-2026, §7.1 de
 * `docs/movil-como-camara.md` del PC): con PixPin a la vista y un PC del grupo grabando (lo que
 * sabe [CamaraDelPc]), una línea arriba con el título de la grabación y «Abrir cámara». Igual en
 * todos los modos de grabación del PC: al teléfono solo le importa el anuncio. Sin grabación, no
 * ocupa nada.
 */
@Composable
fun AvisoDeCamaraDelPc(modifier: Modifier = Modifier) {
    val contexto = LocalContext.current
    val visto by CamaraDelPc.visto.collectAsState()
    val v = visto
    if (v != null && v.grabando) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(12.dp),
            modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp)) {
                Icon(Icons.Filled.PhotoCamera, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                Text(
                    "Cámara para la reunión del PC: «${v.titulo}»",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                )
                TextButton(onClick = { CamaraDelPcActivity.abrir(contexto) }) { Text("Abrir cámara") }
            }
        }
    }
}
