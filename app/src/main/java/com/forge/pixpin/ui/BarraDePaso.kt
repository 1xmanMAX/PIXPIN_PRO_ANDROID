package com.forge.pixpin.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forge.pixpin.guardados.AbrirDePaso

private val FONDO = Color(0xCC14182B)
private val ACENTO = Color(0xFF8AB4F8)

/**
 * **Los botones del modo de paso** (27-oct-2026), solo en un visor abierto de paso (ver
 * [com.forge.pixpin.guardados.ModoDePaso]): una segunda pastilla bajo la de arriba, con texto y no
 * solo icono, porque son lo que se busca al abrir algo de fuera.
 *
 * - **Abrir con…**, la primera y en color: si el visor de PixPin se queda corto para ese formato,
 *   se le echa un vistazo y con un toque va a una aplicación especializada (el usuario).
 * - **Guardar**, con dos destinos: «En Mensajes guardados» o «En un chat…». Se guarda con lo
 *   anotado. Ya guardado dice **«Guardado ✓»** y no hace nada.
 * - **Compartir**, si el visor lo pasa ([alCompartir]): la hoja de compartir del propio visor, con
 *   todos sus formatos y además «Por Wi-Fi a mis aparatos» ([AbrirDePaso.conWifi]).
 *
 * [estado] null (no es de paso): no pinta nada, así que el visor la pone sin preguntar.
 */
@Composable
fun BarraDePaso(
    estado: AbrirDePaso.Estado?,
    visible: Boolean,
    modifier: Modifier = Modifier,
    alCompartir: (() -> Unit)? = null,
    /** Lo que el visor hace antes de guardar (dejar en disco la tinta a medias), si algo. */
    antesDeGuardar: () -> Unit = {}
) {
    if (estado == null) return
    val contexto = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    AnimatedVisibility(
        visible = visible, enter = fadeIn(), exit = fadeOut(),
        modifier = modifier.statusBarsPadding().padding(top = 64.dp, start = 16.dp, end = 16.dp)
    ) {
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(FONDO).padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Boton(Icons.Filled.OpenInNew, "Abrir con…", ACENTO, negrita = true) { AbrirDePaso.abrirCon(contexto, estado) }
            Box {
                if (estado.guardado != null) Boton(Icons.Filled.Check, "Guardado ✓", Color.White.copy(alpha = 0.7f)) {}
                else Boton(Icons.Filled.Save, "Guardar", Color.White) { menu = true }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("En Mensajes guardados") }, leadingIcon = { Icon(Icons.Filled.Bookmark, null) },
                        onClick = { menu = false; antesDeGuardar(); com.forge.pixpin.guardados.GuardarDePasoActivity.abrir(contexto, estado, elegirChat = false) }
                    )
                    DropdownMenuItem(
                        text = { Text("En un chat…") }, leadingIcon = { Icon(Icons.Filled.Forum, null) },
                        onClick = { menu = false; antesDeGuardar(); com.forge.pixpin.guardados.GuardarDePasoActivity.abrir(contexto, estado, elegirChat = true) }
                    )
                }
            }
            if (alCompartir != null) Boton(IconoDeCompartir, "Compartir", Color.White, al = alCompartir)
        }
    }
}

@Composable
private fun Boton(icono: ImageVector, texto: String, color: Color, negrita: Boolean = false, al: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).clickable(onClick = al).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icono, contentDescription = null, tint = color, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(6.dp))
        Text(texto, color = color, fontSize = 13.sp, fontWeight = if (negrita) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
    }
}
