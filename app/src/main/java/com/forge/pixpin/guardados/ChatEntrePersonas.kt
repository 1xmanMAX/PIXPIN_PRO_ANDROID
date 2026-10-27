package com.forge.pixpin.guardados

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forge.pixpin.sincro.ChatCompartido
import com.forge.pixpin.sincro.Compartidos
import com.forge.pixpin.sincro.CompartidosEnDisco
import com.forge.pixpin.sincro.EnElChat
import com.forge.pixpin.sincro.EntrePersonas
import com.forge.pixpin.sincro.IdentidadEnDisco

/**
 * # La cara de los chats compartidos (25-oct-2026, del PC `e3f1d60`)
 *
 * El usuario: «haz que los chats compartidos sean una opción especial: al darle al «+» en los
 * chats, que primero salgan dos opciones, un chat normal o crear un chat COMPARTIDO. Un chat
 * compartido lleva una insignia para que se vea que lo es; uno normal se ve normal».
 *
 * Aquí: el nombre de quien escribe encima de su tanda ([NombreDePersona]), la chapa verde
 * ([ChapaDeCompartido]), el menú del «+» ([MenuDeChatNuevo]) y el compartir del chat recién nacido
 * ([compartirChatNuevo]). Lo que se decide sin pantalla está en [EntrePersonas]. **No cambia nada de
 * lo que viaja**: compartir un chat nuevo es exactamente lo que hace «Compartir» en
 * [com.forge.pixpin.sincro.ChatCompartidoActivity], `Compartidos.crear(chat, nombre, yo, ahora)`.
 */

/**
 * **El nombre de quien escribe**, encima de la primera burbuja de su tanda: su círculo con la
 * inicial y el nombre, los dos de su color ([EntrePersonas.colorDe]), como un grupo de Telegram.
 */
@Composable
internal fun NombreDePersona(nombre: String) {
    val color = Color(EntrePersonas.colorDe(nombre))
    Row(
        Modifier.padding(start = 4.dp, top = 2.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(18.dp).background(color, CircleShape), contentAlignment = Alignment.Center) {
            Text(EntrePersonas.inicial(nombre), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
        Spacer(Modifier.width(6.dp))
        Text(nombre, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * **La chapa de chat compartido**: un circulito verde con las dos personas y un aro del color de lo
 * de detrás ([aro]), como el punto de «en línea» de Telegram. Va abajo a la derecha del avatar.
 */
@Composable
internal fun ChapaDeCompartido(aro: Color, modifier: Modifier = Modifier, lado: Dp = 20.dp) {
    Box(modifier.size(lado).background(aro, CircleShape).padding(2.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(lado - 4.dp).background(Color(EntrePersonas.VERDE_COMPARTIDO), CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Groups, contentDescription = "Compartido", tint = Color.White, modifier = Modifier.size(lado * 0.62f))
        }
    }
}

/**
 * **El «+» de los chats: normal o compartido**, cada fila con su explicación de una línea (las
 * mismas que el PC, `chat-nuevo-*`), y unirse a uno compartido con su código.
 */
@Composable
internal fun MenuDeChatNuevo(
    abierto: Boolean, alCerrar: () -> Unit, alUnirse: () -> Unit, alElegir: (compartido: Boolean) -> Unit
) {
    DropdownMenu(expanded = abierto, onDismissRequest = alCerrar) {
        FilaDeChatNuevo(Icons.Filled.ChatBubbleOutline, "Chat normal", "Solo tú y tus aparatos") { alCerrar(); alElegir(false) }
        FilaDeChatNuevo(Icons.Filled.Groups, "Chat compartido", "Con otras personas, cada una en su lado") { alCerrar(); alElegir(true) }
        // **Unirse al de otro** (27-oct-2026): se podía crear un chat compartido pero no entrar en
        // uno; la puerta estaba en el ⋮ de «Mensajes guardados» y nadie la encontraba. Aquí, junto a
        // crearlo, como el menú del «+» del PC (`cc-menu-entrar`).
        FilaDeChatNuevo(Icons.Filled.GroupAdd, com.forge.pixpin.sincro.Invitacion.UNIRSE, com.forge.pixpin.sincro.Invitacion.UNIRSE_DETALLE) {
            alCerrar(); alUnirse()
        }
    }
}

@Composable
private fun FilaDeChatNuevo(icono: androidx.compose.ui.graphics.vector.ImageVector, titulo: String, detalle: String, alTocar: () -> Unit) {
    DropdownMenuItem(
        leadingIcon = { Icon(icono, contentDescription = null) },
        text = {
            Column {
                Text(titulo, fontSize = 15.sp)
                Text(detalle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        onClick = alTocar
    )
}

/**
 * **Nace un chat compartido** (el «+», «Chat compartido»): el chat [proyecto] con una sola
 * persona, yo, que lo creo. A los demás se les invita después desde sus personas, como siempre
 * ([com.forge.pixpin.sincro.ChatCompartidoActivity]). Desde ahora hay que escuchar en la Wi-Fi
 * aunque no haya grupo. Fuera del hilo de la pantalla: lee y escribe disco.
 */
internal fun compartirChatNuevo(contexto: Context, proyecto: String, nombre: String) {
    val filesDir = contexto.applicationContext.filesDir
    val id = IdentidadEnDisco(filesDir).leer(android.os.Build.MODEL.orEmpty().ifBlank { "Este aparato" })
    val cd = CompartidosEnDisco(filesDir)
    val yo = cd.yo(id)
    cd.cambiar { l -> if (l.any { it.chat == proyecto }) l else l + Compartidos.crear(proyecto, nombre, yo, System.currentTimeMillis()) }
    com.forge.pixpin.sincro.Presencia.reiniciar()
}

/**
 * **Los chats compartidos en los que estoy dentro**, por el id de su chat (el del proyecto), para
 * la chapa de la lista. Sin ninguno en la lista no se lee la identidad (del PC `cad5e20`).
 */
internal fun chatsCompartidosDentro(contexto: Context, lista: List<ChatCompartido>? = null): Map<String, EnElChat> {
    val filesDir = contexto.applicationContext.filesDir
    return EntrePersonas.porChat(lista ?: CompartidosEnDisco(filesDir).leer()) {
        IdentidadEnDisco(filesDir).leer(android.os.Build.MODEL.orEmpty().ifBlank { "Este aparato" })
    }
}
