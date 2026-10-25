package com.forge.pixpin.guardados

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.forge.pixpin.motor.BibliotecaStore
import com.forge.pixpin.motor.DrawFonts
import com.forge.pixpin.motor.ExcalidrawStore
import com.forge.pixpin.motor.FiguraGuardada
import com.forge.pixpin.motor.FigurasDeSerie
import com.forge.pixpin.motor.ItemStyle
import com.forge.pixpin.motor.PanelDeFiguras
import com.forge.pixpin.motor.Pt
import com.forge.pixpin.motor.Scene
import com.forge.pixpin.motor.estampar
import com.forge.pixpin.motor.figurasDeFabrica
import com.forge.pixpin.motor.getCommonBounds
import java.util.UUID

/**
 * **Adjuntar una figura al chat** (25-oct-2026, como el PC `adjuntar_figura`): se elige **viéndola**
 * en el mismo panel de figuras del lienzo ([PanelDeFiguras] con su botón «Adjuntar»), con las 627
 * de serie del PC, las básicas y las mías. Lo que entra en el chat es **un lienzo nuevo con la
 * figura dentro**, con su nombre (sin la categoría): un `DIBUJO` de siempre, que viaja como todos.
 */
object AdjuntarFigura {
    /** «Diario · Gratitud» → «Gratitud», como `categoria_de` del PC. */
    fun nombreDe(f: FiguraGuardada): String = FigurasDeSerie.nombreCorto(f.nombre).ifBlank { "Figura" }

    /** La escena con la figura dentro, desde el origen (centrada en la mitad de su caja). */
    fun escenaCon(f: FiguraGuardada): Scene {
        if (f.elementos.isEmpty()) return Scene()
        val caja = getCommonBounds(f.elementos)
        return Scene(elements = estampar(f, Pt(caja.width / 2, caja.height / 2)))
    }

    /** Escribe el lienzo y su mensaje en [chat]. Devuelve el mensaje, o null si no se pudo escribir. */
    fun adjuntar(context: Context, almacen: MensajesStore, chat: String?, f: FiguraGuardada): Mensaje? {
        val ahora = System.currentTimeMillis()
        val id = "dib-$ahora"
        ExcalidrawStore.guardar(context, id, escenaCon(f)) ?: return null
        val m = Mensaje(id = UUID.randomUUID().toString(), cuando = ahora, clase = Clase.DIBUJO, nombre = nombreDe(f), referencia = id, proyecto = chat)
        almacen.anadir(m)
        return m
    }
}

/** El panel de figuras sobre un velo, para elegir la que se adjunta. */
@Composable
fun HojaDeAdjuntarFigura(onCerrar: () -> Unit, onAdjuntar: (FiguraGuardada) -> Unit) {
    val contexto = LocalContext.current
    val listas by produceState<Pair<List<FiguraGuardada>, List<FiguraGuardada>>?>(null) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val estilo = ItemStyle()
            val fabrica = figurasDeFabrica(estilo) { texto, tamano -> DrawFonts.medirTexto(contexto, texto, estilo.fontFamily, tamano) }
            (fabrica + FigurasDeSerie.sinLasDeSerie(BibliotecaStore.cargar(contexto))) to FigurasDeSerie.todas(contexto)
        }
    }
    // En un diálogo: así tapa la pantalla entera esté donde esté puesto en la del chat.
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onCerrar,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) { Box(
        Modifier.fillMaxSize()
            .background(Color(0x33000000))
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onCerrar() },
        contentAlignment = Alignment.Center
    ) {
        val l = listas ?: return@Box
        PanelDeFiguras(
            figuras = l.first,
            deSerie = l.second,
            onInsertar = {},
            onQuitar = {},
            onGuardarSeleccion = {},
            puedeGuardar = false,
            onPegarTabla = {},
            onCerrar = onCerrar,
            onAdjuntar = onAdjuntar,
            // Que un toque en el panel no llegue al velo de detrás y lo cierre.
            modifier = Modifier.padding(12.dp)
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {}
        )
    } }
}
