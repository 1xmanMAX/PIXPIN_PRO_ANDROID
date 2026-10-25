package com.forge.pixpin.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forge.pixpin.motor.DrawController
import com.forge.pixpin.motor.Tool
import kotlinx.coroutines.launch

/** El cristal de las barras de los visores (la del visor de Revit, la que gustó: 25-oct-2026). */
val CRISTAL_DE_VISOR = Color(0x8C14182B)

/**
 * **El editor avanzado encima de cualquier visor** (25-oct-2026, el usuario: «cuando le doy al
 * lápiz en cualquier formato me aparece el editor viejo, simple; quiero siempre el avanzado, pero
 * que no estorbe»). Era el `EditorAvanzado` del plano, ahora de todos:
 *
 * - **Arriba**, la pastilla de cristal con «Listo» (y lo que el visor añada, [arriba]).
 * - **Abajo**, la barra de herramientas agrupada del lienzo, sin estilo ni deshacer (van aparte), y
 *   encima, a un lado, deshacer y rehacer.
 * - **El estilo, plegado**: un botón redondo con el color de ahora, al lado contrario de la mano.
 *   Tocarlo despliega el panel del lienzo —color, grosor, opacidad, trazo, letra y su tamaño— y
 *   tocarlo otra vez lo recoge, así el documento se ve entero mientras se anota.
 *
 * Mismo motor y mismas piezas que el lienzo a pantalla completa ([com.forge.pixpin.motor.PanelLateralDeEstilo],
 * [com.forge.pixpin.motor.CajaDeDeshacer], [com.forge.pixpin.motor.DrawToolbar]). [cambiado] con true:
 * cambió lo dibujado (hay que guardar).
 */
@Composable
fun BoxScope.EditorAvanzadoDeVisor(
    capa: DrawController,
    ajustes: com.forge.pixpin.data.Settings,
    permitidas: Set<Tool>,
    grupos: List<List<Tool>>,
    noche: Boolean,
    /** Sube con cada cambio del motor: sin él, esto no se volvería a componer. */
    tick: Int,
    alListo: () -> Unit,
    cambiado: (Boolean) -> Unit,
    /**
     * De quién son deshacer y rehacer, si no de [capa]: en el PDF cada hoja tiene su lienzo y [capa]
     * es el que solo lleva la herramienta y el estilo de la barra.
     */
    historia: () -> DrawController? = { capa },
    /** Además del estilo de [capa], a quién más se le pone (la hoja que se está anotando). */
    alCambiarEstilo: (com.forge.pixpin.motor.ItemStyle) -> Unit = {},
    /** Lo que el visor pone junto a «Listo» (exportar, «Dedo: mover»…). */
    arriba: @Composable () -> Unit = {}
) {
    @Suppress("UNUSED_EXPRESSION") tick
    val contexto = LocalContext.current
    val alcance = rememberCoroutineScope()
    val zurdo = ajustes.zurdo
    val seleccionado = capa.selectedElements()
    val aplican = com.forge.pixpin.motor.propiedadesPara(capa.tool, seleccionado)
    var conEstilo by remember { mutableStateOf(false) }
    fun aplicarEstilo(nuevo: com.forge.pixpin.motor.ItemStyle) {
        // Como en el lienzo: un texto al que se le cambia la letra se vuelve a medir.
        capa.cambiarEstilo(nuevo) { e ->
            if (e.type != com.forge.pixpin.motor.ElementType.TEXT) return@cambiarEstilo e
            val (w, h) = com.forge.pixpin.motor.DrawFonts.medirTexto(contexto, e.text.orEmpty(), e.fontFamily ?: nuevo.fontFamily, e.fontSize ?: nuevo.fontSize)
            e.copy(width = w, height = h)
        }
        alCambiarEstilo(nuevo)
    }
    val marcasDeColor = remember(ajustes.coloresMarcados) {
        ajustes.coloresMarcados.split(',').map { it.trim() }.filter { it.startsWith("#") && it.length == 7 }
    }

    // Arriba: «Listo» y lo del visor, en la pastilla de cristal.
    Row(
        Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp)
            .clip(RoundedCornerShape(50)).background(CRISTAL_DE_VISOR),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            Modifier.clip(RoundedCornerShape(50)).clickable(onClick = alListo).padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            Text("Listo", color = Color.White, modifier = Modifier.padding(start = 6.dp))
        }
        arriba()
    }

    // El estilo, plegado en un botón con el color de ahora; desplegado, el panel del lienzo.
    if (aplican.isNotEmpty()) Column(
        Modifier.align(if (zurdo) Alignment.CenterEnd else Alignment.CenterStart).padding(horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val estilo = capa.estiloActivo()
        val color = runCatching { Color(android.graphics.Color.parseColor(estilo.strokeColor)) }.getOrDefault(Color.White)
        Box(
            Modifier.size(42.dp).clip(CircleShape).background(CRISTAL_DE_VISOR)
                .semantics { contentDescription = if (conEstilo) "Recoger el estilo" else "Color, grosor, opacidad y letra" }
                .clickable { conEstilo = !conEstilo },
            contentAlignment = Alignment.Center
        ) {
            Box(Modifier.size(24.dp).clip(CircleShape).background(color).border(2.dp, Color.White.copy(alpha = 0.85f), CircleShape))
            if (conEstilo) Text("×", color = if (color.luminanceAprox() > 0.6f) Color.Black else Color.White, fontSize = 16.sp)
        }
        AnimatedVisibility(conEstilo, enter = fadeIn(), exit = fadeOut()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.size(6.dp))
                com.forge.pixpin.motor.PanelLateralDeEstilo(
                    aplican = aplican,
                    estilo = estilo,
                    rellenoObligatorio = capa.tool == Tool.RELLENO || seleccionado.any { it.type == com.forge.pixpin.motor.ElementType.REGION },
                    zurdo = zurdo,
                    marcas = remember(ajustes.marcasDeDeslizadores) { com.forge.pixpin.motor.marcasDeTexto(ajustes.marcasDeDeslizadores) },
                    onMarcas = { cual, valores ->
                        val todas = com.forge.pixpin.motor.marcasDeTexto(ajustes.marcasDeDeslizadores) + (cual to valores)
                        alcance.launch {
                            (contexto.applicationContext as com.forge.pixpin.PixPinApp).settings.setMarcas(com.forge.pixpin.motor.marcasATexto(todas))
                        }
                    },
                    onEstilo = { nuevo -> aplicarEstilo(nuevo); cambiado(seleccionado.isNotEmpty()) },
                    // El panel habla en pantalla: el grosor elegido ya va a la escala de lo que se ve.
                    zoom = { (capa.scene.viewport.zoom * capa.escalaDeLoNuevo).toFloat() },
                    marcasDeColor = marcasDeColor,
                    noche = noche
                )
            }
        }
    }

    // Abajo: deshacer y rehacer a un lado, y la barra agrupada de herramientas.
    Column(
        Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 8.dp, start = 6.dp, end = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val h = historia()
        com.forge.pixpin.motor.CajaDeDeshacer(
            puedeDeshacer = h?.canUndo == true,
            puedeRehacer = h?.canRedo == true,
            onDeshacer = { h?.undo(); cambiado(true) },
            onRehacer = { h?.redo(); cambiado(true) },
            enFila = true,
            modifier = Modifier.align(if (zurdo) Alignment.Start else Alignment.End)
        )
        Spacer(Modifier.size(6.dp))
        com.forge.pixpin.ui.theme.SuperficieDeCristal(Modifier, RoundedCornerShape(22.dp)) {
            com.forge.pixpin.motor.DrawToolbar(
                tool = capa.tool,
                onTool = { capa.selectTool(it); cambiado(false) },
                style = capa.scene.style,
                onStyle = { nuevo -> aplicarEstilo(nuevo); cambiado(false) },
                canUndo = h?.canUndo == true,
                onUndo = { h?.undo(); cambiado(true) },
                // El estilo y el deshacer van aparte: aquí serían lo mismo dos veces.
                mostrarEstilo = false,
                mostrarDeshacer = false,
                dark = noche,
                permitidas = permitidas,
                grupos = grupos
            )
        }
    }
}

private fun Color.luminanceAprox(): Float = 0.299f * red + 0.587f * green + 0.114f * blue
