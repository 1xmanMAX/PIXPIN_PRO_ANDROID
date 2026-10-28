package com.forge.pixpin.guardados

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * **La letra en pantalla** (28-oct-2026): lo que era del karaoke ([MusicaActivity], 27-oct-2026) y ahora
 * usa «Estudiar la clase» con una canción ([EstudiarClaseActivity]): escribir o pegar la letra
 * ([EditorDeLetra]), sincronizarla a toques ([ListaParaSincronizar], [PanelDeSincronizar]) y copiarla en
 * LRC ([copiarComoLrc]). Movido aquí sin cambios al retirar la pantalla propia de la música.
 */

/** **Copia la letra en LRC** (`[mm:ss.xx]línea`) para otra aplicación de karaoke. */
fun copiarComoLrc(context: Context, texto: String) {
    runCatching {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("LRC", texto))
        Toast.makeText(context, com.forge.pixpin.R.string.letra_copiada_lrc, Toast.LENGTH_SHORT).show()
    }
}

/** Las líneas mientras se sincroniza: la que toca marcar, resaltada; las marcadas, con su minuto. */
@Composable
internal fun ListaParaSincronizar(s: SincronizadorDeLetra, toques: Int, tamano: Int) {
    val lista = rememberLazyListState()
    LaunchedEffect(toques) {
        runCatching { lista.animateScrollToItem(s.siguiente.coerceAtMost((s.textos.size - 1).coerceAtLeast(0)), -lista.layoutInfo.viewportSize.height / 3) }
    }
    LazyColumn(state = lista, modifier = Modifier.fillMaxSize()) {
        itemsIndexed(s.textos) { i, t ->
            val toca = i == s.siguiente
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(if (toca) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    s.tiempo(i)?.let { LetraDeCancion.marca(it) } ?: "",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 10.dp).size(width = 44.dp, height = 18.dp)
                )
                Text(
                    t, fontSize = (tamano * 0.8f).sp,
                    fontWeight = if (toca) FontWeight.Bold else FontWeight.Normal,
                    color = if (s.tiempo(i) != null) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f) else MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
internal fun PanelDeSincronizar(
    s: SincronizadorDeLetra, toques: Int,
    alMarcar: () -> Unit, alDeshacer: () -> Unit, alGuardar: () -> Unit, alCancelar: () -> Unit
) {
    // [toques] solo está para que esto se vuelva a pintar con cada toque.
    val siguiente = if (toques >= 0) s.textos.getOrNull(s.siguiente) else null
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                if (siguiente != null) "Siguiente: $siguiente" else "Todas marcadas. Guarda la letra.",
                fontSize = 14.sp, maxLines = 2, modifier = Modifier.padding(bottom = 8.dp)
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(enabled = s.siguiente > 0, onClick = alDeshacer) {
                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Deshacer la última marca")
                }
                Button(enabled = !s.terminado, onClick = alMarcar, modifier = Modifier.weight(1f).height(56.dp)) {
                    Text("Marcar  ${s.siguiente + (if (s.terminado) 0 else 1)}/${s.textos.size}", fontSize = 18.sp)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = alCancelar) { Text("Cancelar") }
                OutlinedButton(enabled = s.siguiente > 0, onClick = alGuardar) { Text("Guardar") }
            }
        }
    }
}


/**
 * **Escribir o pegar la letra** (27-oct-2026): la caja de texto y, encima, «Pegar del
 * portapapeles» y «Abrir .lrc o .txt». Vale texto llano o LRC; al guardar se pasa al formato del
 * chat con [LetraDeCancion.aGuardar]. Lo usan «Estudiar la clase» con una canción ([EstudiarClaseActivity]) y la canción nueva
 * ([CancionNuevaActivity]).
 */
@Composable
fun ColumnScope.EditorDeLetra(texto: String, alCambiar: (String) -> Unit) {
    val contexto = LocalContext.current
    val abrir = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val leido = runCatching { leerTexto(contexto, uri) }.getOrNull()
        if (leido.isNullOrBlank()) Toast.makeText(contexto, "No se pudo leer ese archivo", Toast.LENGTH_SHORT).show()
        else alCambiar(leido)
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AssistChip(
            onClick = {
                val copiado = portapapeles(contexto)
                if (copiado.isNullOrBlank()) Toast.makeText(contexto, "No hay texto copiado", Toast.LENGTH_SHORT).show()
                else alCambiar(copiado)
            },
            label = { Text("Pegar del portapapeles") },
            leadingIcon = { Icon(Icons.Filled.ContentPaste, null, Modifier.size(18.dp)) }
        )
        AssistChip(
            onClick = { runCatching { abrir.launch(arrayOf("text/*", "application/octet-stream", "application/x-subrip")) } },
            label = { Text("Abrir .lrc o .txt") },
            leadingIcon = { Icon(Icons.Filled.FileOpen, null, Modifier.size(18.dp)) }
        )
    }
    OutlinedTextField(
        value = texto,
        onValueChange = alCambiar,
        modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp),
        placeholder = { Text("Pega aquí la letra.\n\nVale texto normal o LRC:\n[00:12.30] Primera línea\n[00:15.80] Segunda línea") }
    )
    val leida = remember(texto) { LetraDeCancion.leer(texto) }
    Text(
        if (leida.conTiempos) "Con tiempos: se resaltará la línea que suena."
        else "Sin tiempos. Para cantarla al ritmo, guarda y toca el reloj («Sincronizar»).",
        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)
    )
}

/** El texto del portapapeles, o null. */
fun portapapeles(context: Context): String? = runCatching {
    val cm = context.getSystemService(ClipboardManager::class.java)
    cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
}.getOrNull()

/** Un `.lrc` o `.txt` elegido, en UTF-8 y sin la marca de orden de bytes. */
private fun leerTexto(context: Context, uri: Uri): String? {
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    if (bytes.size > 2_000_000) return null
    return String(bytes, Charsets.UTF_8).removePrefix("﻿")
}
