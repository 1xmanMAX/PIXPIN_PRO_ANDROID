package com.forge.pixpin.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forge.pixpin.R

/**
 * **La letra y el tamaño con que se lee una nota** (como el PC, 30-sep-2026): una preferencia de
 * vista, no del `.md` —la nota es la misma en los dos aparatos—. Para todas las notas, o con
 * «Solo en esta nota» para una.
 */
object VistaDeNotas {
    private const val PREFS = "vista_de_notas"
    val LETRAS = listOf("Normal", "Nunito", "Excalifont", "Comic Shanns", "Con serifa", "Monoespaciada")
    val TAMANOS = listOf("Pequeña" to 14f, "Normal" to 16f, "Grande" to 18f, "Muy grande" to 21f)

    class Vista(val letra: String, val tamano: Float, val soloEsta: Boolean)

    fun leer(context: Context, nota: String): Vista {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val suya = nota.isNotBlank() && p.contains("letra:$nota")
        val l = if (suya) p.getString("letra:$nota", null) else p.getString("letra", null)
        val t = if (suya) p.getFloat("tamano:$nota", 16f) else p.getFloat("tamano", 16f)
        return Vista(l ?: "Normal", t, suya)
    }

    fun guardar(context: Context, nota: String, v: Vista) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (v.soloEsta && nota.isNotBlank()) {
                putString("letra:$nota", v.letra); putFloat("tamano:$nota", v.tamano)
            } else {
                remove("letra:$nota"); remove("tamano:$nota")
                putString("letra", v.letra); putFloat("tamano", v.tamano)
            }
        }.apply()
    }

    fun familia(letra: String): FontFamily? = when (letra) {
        "Nunito" -> FontFamily(Font(R.font.nunito))
        "Excalifont" -> FontFamily(Font(R.font.excalifont))
        "Comic Shanns" -> FontFamily(Font(R.font.comic_shanns))
        "Con serifa" -> FontFamily.Serif
        "Monoespaciada" -> FontFamily.Monospace
        else -> null
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DialogoDeVista(actual: VistaDeNotas.Vista, onCerrar: () -> Unit, onElegir: (VistaDeNotas.Vista) -> Unit) {
    var letra by remember { mutableStateOf(actual.letra) }
    var tamano by remember { mutableFloatStateOf(actual.tamano) }
    var soloEsta by remember { mutableStateOf(actual.soloEsta) }
    AlertDialog(
        onDismissRequest = onCerrar,
        title = { Text("Letra y tamaño") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Letra", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    VistaDeNotas.LETRAS.forEach { l ->
                        FilterChip(selected = letra == l, onClick = { letra = l },
                            label = { Text(l, fontFamily = VistaDeNotas.familia(l)) })
                    }
                }
                Text("Tamaño", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    VistaDeNotas.TAMANOS.forEach { (n, t) ->
                        FilterChip(selected = tamano == t, onClick = { tamano = t }, label = { Text(n, fontSize = (t * 0.85f).sp) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = soloEsta, onCheckedChange = { soloEsta = it })
                    Text("Solo en esta nota")
                }
            }
        },
        confirmButton = { TextButton(onClick = { onElegir(VistaDeNotas.Vista(letra, tamano, soloEsta)) }) { Text("Aplicar") } },
        dismissButton = { TextButton(onClick = onCerrar) { Text("Cancelar") } }
    )
}
