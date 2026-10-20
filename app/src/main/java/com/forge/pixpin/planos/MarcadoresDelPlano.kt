package com.forge.pixpin.planos

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forge.pixpin.motor.MarcadoresCad
import com.forge.pixpin.sincro.AnotacionesDelAdjunto
import java.io.File

/**
 * **Dónde van los marcadores de un plano o un modelo, y su lista** (20-oct-2026, PC `marcadores.rs`).
 * El plano 2D y el visor 3D del mismo archivo comparten fichero (`plano` y `modelo`): cada uno
 * escribe **solo su lista** sobre lo que haya en el disco, para no pisar la del otro.
 */
class MarcadoresDelPlano(c: Context, original: File) {
    private val uid = AnotacionesDelAdjunto.uidDe(c.filesDir, original.absolutePath)
    private val huella = original.absolutePath.hashCode().toUInt().toString(16)
    val vistas: File = uid?.let { AnotacionesDelAdjunto.vistas(c.filesDir, AnotacionesDelAdjunto.delPdf(it)) } ?: File(c.filesDir, "planos-marcadores/$huella.vistas.json")
    val marcos: File = uid?.let { AnotacionesDelAdjunto.marcos(c.filesDir, AnotacionesDelAdjunto.delPdf(it)) } ?: File(c.filesDir, "planos-marcadores/$huella.marcos.json")

    /** La tinta de un marcador 3D: `anot-<uid>-m<id>` (o una del teléfono si no es del chat). */
    fun tintaDe(id: String) = uid?.let { AnotacionesDelAdjunto.deMarcador3d(it, id) } ?: ("modelo-$huella-m" + id.filter { it.isLetterOrDigit() })

    fun leer(): MarcadoresCad = MarcadoresCad.deTextos(AnotacionesDelAdjunto.leer(vistas), AnotacionesDelAdjunto.leer(marcos))

    /** Guarda lo de [nuevo] que es de quien llama ([delPlano]: la lista 2D; si no, la 3D y los marcos). */
    fun guardar(nuevo: MarcadoresCad, delPlano: Boolean): MarcadoresCad {
        val disco = leer()
        val final = if (delPlano) disco.copy(plano = nuevo.plano) else disco.copy(modelo = nuevo.modelo, marcos = nuevo.marcos)
        val tv = final.textoDeVistas()
        if (tv != disco.textoDeVistas() || !vistas.exists()) AnotacionesDelAdjunto.escribir(vistas, tv)
        val tm = final.textoDeMarcos()
        if (!delPlano && (tm != disco.textoDeMarcos() || (!marcos.exists() && final.marcos.isNotEmpty()))) AnotacionesDelAdjunto.escribir(marcos, tm)
        return final
    }
}

/** **La lista de los marcadores** a un lado: un toque va, mantener abre la lista para quitarlos. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ListaDeMarcadoresCad(nombres: List<String>, actual: Int, modifier: Modifier, alIr: (Int) -> Unit, alQuitar: () -> Unit) {
    if (nombres.isEmpty()) return
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(Color(0x8C14182B)).padding(4.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        nombres.forEachIndexed { i, _ ->
            val es = i == actual
            Text(
                "📍", fontSize = if (es) 22.sp else 16.sp,
                modifier = Modifier.clip(RoundedCornerShape(10.dp))
                    .background(if (es) Color(com.forge.pixpin.motor.MarcosDoc.AZUL_PULSADO) else Color.Transparent)
                    .combinedClickable(onClickLabel = nombres[i], onClick = { alIr(i) }, onLongClick = alQuitar).padding(6.dp)
            )
        }
    }
}

@Composable
fun QuitarMarcadoresCad(nombres: List<String>, alQuitar: (Int) -> Unit, alCerrar: () -> Unit) {
    AlertDialog(
        onDismissRequest = alCerrar,
        title = { Text("Quitar marcadores") },
        text = {
            Column {
                nombres.forEachIndexed { i, n ->
                    Row(Modifier.fillMaxWidth().clickable { alQuitar(i) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("📍  " + n.ifBlank { "Vista" }, modifier = Modifier.weight(1f)); Icon(Icons.Filled.Close, null)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = alCerrar) { Text("Hecho") } }
    )
}
