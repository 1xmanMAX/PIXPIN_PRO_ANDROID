package com.forge.pixpin.guardados

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Screenshot
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.automirrored.filled.StopScreenShare
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.automirrored.filled.ScreenShare
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forge.pixpin.R

/**
 * **Los mandos de la grabación** (29-oct-2026, el usuario: «los iconos están muy aglomerados… el botón
 * de play y pausa es innecesario: en el tiempo, donde está el puntito rojo, le damos clic y que tenga esa
 * función, y el puntito rojo se cambia a una pausa; y si mantengo presionado, que se termine»).
 *
 * Lo mismo en la burbuja flotante ([ReunionPresencialService]), en la barra de la grabación dentro de la
 * app ([ReunionEnLaApp]) y, en grande, en la pantalla completa ([ReunionEnCursoActivity]):
 *
 * - [Reloj]: «● 12:34». **Un toque pausa o sigue** (el punto rojo pasa a «❚❚»); **mantenerlo termina**
 *   y guarda.
 * - [Acciones]: una fila aparte, cada botón con su icono y su nombre debajo, con aire entre ellos:
 *   **Foto** (la cámara), **Captura** (la pantalla al instante; mantener: un trozo), **Pantalla** (el
 *   vídeo de la pantalla, encendido en rojo; el audio sigue igual) y **Clip**.
 *
 * Todo va al servicio por sus funciones de siempre. Sin `return` antes de tiempo (R8).
 */
object MandosDeLaGrabacion {

    val ROJO = Color(0xFFE53935)
    private val ROJO_CLARO = Color(0xFFFF8A80)

    /** **El reloj**: toque, pausa o sigue; mantener, terminar. */
    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    fun Reloj(e: EstadoDeLaReunion, claro: Color = Color.White, grande: Boolean = false, modifier: Modifier = Modifier) {
        val contexto = LocalContext.current
        val vibrar = LocalHapticFeedback.current
        val desde = e.clipDesde
        val tam: TextUnit = if (grande) 30.sp else 15.sp
        Row(
            modifier.clip(RoundedCornerShape(20.dp))
                .combinedClickable(
                    onClickLabel = contexto.getString(if (e.enPausa) R.string.clase_seguir else R.string.clase_pausa),
                    onLongClickLabel = contexto.getString(R.string.clase_terminar),
                    onClick = { ReunionPresencialService.pausa() },
                    onLongClick = {
                        vibrar.performHapticFeedback(HapticFeedbackType.LongPress)
                        ReunionPresencialService.terminar()
                    }
                )
                .padding(horizontal = if (grande) 14.dp else 8.dp, vertical = if (grande) 8.dp else 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // El punto rojo grabando; en pausa, el signo de la pausa.
            if (e.enPausa) Icon(
                Icons.Filled.Pause, contentDescription = null, tint = claro,
                modifier = Modifier.size(if (grande) 30.dp else 16.dp)
            ) else Box(Modifier.size(if (grande) 18.dp else 10.dp).clip(CircleShape).background(ROJO))
            Text(
                "  " + GrabarClase.minuto(e.segundos * 1000),
                color = if (e.enPausa) claro.copy(alpha = 0.7f) else claro, fontSize = tam, fontWeight = FontWeight.SemiBold
            )
            if (desde != null) Text(
                "  ● " + contexto.getString(R.string.reunion_clip) + " " + GrabarClase.minuto((e.segundos * 1000 - desde).coerceAtLeast(0)),
                color = ROJO_CLARO, fontSize = if (grande) 18.sp else 12.sp, fontWeight = FontWeight.SemiBold
            )
        }
    }

    /**
     * **La fila de las acciones**: Foto, Captura, Pantalla y Clip, con aire. [alCapturar] hace la captura
     * (la pantalla completa la hace al irse); por defecto, al instante.
     */
    @Composable
    fun Acciones(
        e: EstadoDeLaReunion, claro: Color = Color.White, grande: Boolean = false,
        alCapturar: (Boolean) -> Unit = { zona -> ReunionPresencialService.captura(zona) }
    ) {
        val contexto = LocalContext.current
        Row(horizontalArrangement = Arrangement.spacedBy(if (grande) 10.dp else 2.dp), verticalAlignment = Alignment.Top) {
            if (e.conCamara) Accion(
                Icons.Filled.PhotoCamera, contar(contexto.getString(R.string.reunion_foto), e.fotos), claro, grande,
                al = { ReunionPresencialService.foto() }
            )
            Accion(
                Icons.Filled.Screenshot, contar(contexto.getString(R.string.clase_captura), e.capturas), claro, grande,
                descripcion = contexto.getString(R.string.grabar_captura_pista),
                al = { alCapturar(false) }, alMantener = { alCapturar(true) }
            )
            if (e.sePuedePantalla) Accion(
                if (e.pantalla) Icons.AutoMirrored.Filled.StopScreenShare else Icons.AutoMirrored.Filled.ScreenShare,
                (if (e.pantalla) "● " else "") + contexto.getString(R.string.grabar_pantalla),
                if (e.pantalla) ROJO_CLARO else claro, grande,
                al = { ReunionPresencialService.pantalla() }
            )
            if (e.conCamara) Accion(
                if (e.clipDesde != null) Icons.Filled.StopCircle else Icons.Filled.Videocam,
                if (e.clipDesde != null) contexto.getString(R.string.reunion_parar_clip) else contar(contexto.getString(R.string.reunion_clip), e.clips),
                if (e.clipDesde != null) ROJO_CLARO else claro, grande,
                al = { ReunionPresencialService.clip() }
            )
        }
    }

    private fun contar(nombre: String, n: Int) = if (n > 0) "$nombre · $n" else nombre

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun Accion(
        icono: ImageVector, nombre: String, color: Color, grande: Boolean, descripcion: String? = null,
        al: () -> Unit, alMantener: (() -> Unit)? = null
    ) {
        val vibrar = LocalHapticFeedback.current
        Column(
            Modifier.width(if (grande) 84.dp else 64.dp).clip(RoundedCornerShape(14.dp))
                .combinedClickable(
                    onClickLabel = descripcion ?: nombre,
                    onClick = al,
                    onLongClick = alMantener?.let { m -> { vibrar.performHapticFeedback(HapticFeedbackType.LongPress); m() } }
                )
                .padding(vertical = if (grande) 10.dp else 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icono, contentDescription = null, tint = color, modifier = Modifier.size(if (grande) 32.dp else 22.dp))
            Text(
                nombre, color = color, fontSize = if (grande) 14.sp else 10.sp, maxLines = 1, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}
