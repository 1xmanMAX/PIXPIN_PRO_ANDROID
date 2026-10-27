package com.forge.pixpin.guardados

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forge.pixpin.sincro.Codigos
import com.forge.pixpin.sincro.VersionesVinculadas

/**
 * **Las versiones vinculadas, en el chat** (27-oct-2026): lo de Android y lo de pantalla de
 * [VersionesVinculadas], que es donde está la regla. Aquí: hacer la versión con el almacén de
 * verdad, cerrar los borradores al volver de un editor, y los dos rótulos de la burbuja —«Versión
 * de Ana» encima de una versión, que lleva al original, y «2 versiones» en el original, que las
 * lista—.
 */
object VersionesEnElChat {

    /** Un rótulo que lleva a un mensaje: [id] es adónde va (vacío: a ninguno, el original no está aquí). */
    data class Rotulo(val id: String, val texto: String)

    /** Los rótulos de un chat: el de cada versión ([cabecera], por su id) y la lista de cada original ([versiones]). */
    data class DelChat(val cabecera: Map<String, Rotulo>, val versiones: Map<String, List<Rotulo>>) {
        companion object { val NADA = DelChat(emptyMap(), emptyMap()) }
    }

    /**
     * **Qué rótulo lleva cada burbuja.** [autor] da el nombre de la persona de un mensaje, o null si
     * es mío. [compartido]: si no lo es (un chat del que ya se salió), no se nombra a nadie.
     */
    fun delChat(mensajes: List<Mensaje>, compartido: Boolean, autor: (Mensaje) -> String?): DelChat {
        if (mensajes.none { it.versionDe != null }) return DelChat.NADA
        val porUid = HashMap<String, Mensaje>()
        for (m in mensajes) porUid.putIfAbsent(Codigos.unico(m), m)
        val cabecera = HashMap<String, Rotulo>()
        val versiones = HashMap<String, MutableList<Pair<Mensaje, Rotulo>>>()
        for (v in mensajes) {
            val uid = v.versionDe ?: continue
            val original = porUid[uid]?.takeIf { it.id != v.id }
            val n = v.versionN?.takeIf { it > 1 }?.let { " $it" }.orEmpty()
            val texto = when {
                !compartido -> "Versión$n"
                original == null -> "Versión$n de otro mensaje"
                else -> autor(original)?.let { "Versión$n de $it" } ?: "Versión$n de lo tuyo"
            }
            cabecera[v.id] = Rotulo(original?.id.orEmpty(), texto)
            if (original != null) {
                val quien = if (compartido) autor(v) ?: "tuya" else null
                versiones.getOrPut(original.id) { ArrayList() } +=
                    v to Rotulo(v.id, "Versión ${v.versionN ?: 1}" + (quien?.let { " · $it" }.orEmpty()))
            }
        }
        return DelChat(cabecera, versiones.mapValues { (_, l) -> l.sortedBy { it.first.cuando }.map { it.second } })
    }

    /** «1 versión», «3 versiones». */
    fun cuantas(n: Int): String = if (n == 1) "1 versión" else "$n versiones"

    private fun aparato(context: Context) = Codigos.deEsteAparato(context.filesDir)

    /**
     * **Empieza mi versión de [original]** (de otra persona) y la devuelve para abrir el editor sobre
     * ella. Antes se cierran los borradores que hubiera ([cerrar]). Una provisional entra ya en el
     * chat. Disco: fuera del hilo de la pantalla.
     */
    fun empezar(context: Context, almacen: MensajesStore, original: Mensaje): VersionesVinculadas.EnCurso? = runCatching {
        cerrar(context, almacen)
        val e = VersionesVinculadas.empezar(
            context.filesDir, original, almacen.leer(), aparato(context), System.currentTimeMillis()
        )
        if (e.provisional) almacen.cambiar { lista ->
            if (lista.any { it.id == e.version.id }) lista
            else VersionesVinculadas.conVersiones(lista, VersionesVinculadas.Cierre(listOf(e.version), emptySet()))
        }
        e
    }.getOrNull()

    /**
     * **Al volver al chat**: los borradores cambiados pasan a ser versiones y las provisionales sin
     * tocar se quitan. Devuelve cuántas versiones nuevas hay. Sin nada apuntado, ni se lee el chat.
     */
    fun cerrar(context: Context, almacen: MensajesStore): Int {
        if (VersionesVinculadas.enCurso(context.filesDir).isEmpty()) return 0
        var hechas = 0
        runCatching {
            almacen.cambiar { lista ->
                val c = VersionesVinculadas.cerrar(context.filesDir, lista, System.currentTimeMillis())
                hechas = c.anadir.size
                if (c.nada) lista else VersionesVinculadas.conVersiones(lista, c)
            }
        }
        return hechas
    }

    /** **Una versión de un toque** de [original], con [cambio] hecho. Devuelve si se guardó. */
    fun alMomento(context: Context, almacen: MensajesStore, original: Mensaje, cambio: (Mensaje) -> Mensaje): Boolean = runCatching {
        almacen.cambiar { lista ->
            val v = VersionesVinculadas.alMomento(
                context.filesDir, original, lista, aparato(context), System.currentTimeMillis(), cambio
            )
            VersionesVinculadas.conVersiones(lista, VersionesVinculadas.Cierre(listOf(v), emptySet()))
        }
        true
    }.getOrDefault(false)
}

/**
 * **«Versión de Ana»**, encima de una versión: tocarlo lleva al original (si está en este chat).
 * Con el color de acento, pequeño, como la línea de «Reenviado» de Telegram.
 */
@Composable
fun CabeceraDeVersion(r: VersionesEnElChat.Rotulo, onIrA: (String) -> Unit) {
    val acento = MaterialTheme.colorScheme.primary
    Row(
        Modifier
            .padding(bottom = 4.dp)
            .clip(RoundedCornerShape(6.dp))
            .then(if (r.id.isNotEmpty()) Modifier.clickable { onIrA(r.id) } else Modifier)
            .padding(end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Edit, contentDescription = null, tint = acento, modifier = Modifier.size(13.dp))
        Text(
            r.texto, fontSize = 12.sp, color = acento, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 4.dp), maxLines = 1
        )
    }
}

/** **«2 versiones»**, en el original: abre la lista de sus versiones, y cada una lleva a la suya. */
@Composable
fun ChipDeVersiones(lista: List<VersionesEnElChat.Rotulo>, onIrA: (String) -> Unit) {
    var abierto by remember { mutableStateOf(false) }
    val acento = MaterialTheme.colorScheme.primary
    Box(Modifier.padding(bottom = 4.dp)) {
        Row(
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable { abierto = true }
                .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Edit, contentDescription = null, tint = acento, modifier = Modifier.size(13.dp))
            Text(
                VersionesEnElChat.cuantas(lista.size) + " ▾", fontSize = 12.sp, color = acento,
                fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp)
            )
        }
        DropdownMenu(expanded = abierto, onDismissRequest = { abierto = false }) {
            for (r in lista) {
                DropdownMenuItem(text = { Text(r.texto) }, onClick = { abierto = false; onIrA(r.id) })
            }
        }
    }
}
