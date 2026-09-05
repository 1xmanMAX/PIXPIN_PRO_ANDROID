package com.forge.pixpin.croquis3d

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forge.pixpin.R
import com.forge.pixpin.motor.parseColor

/**
 * **La galería de vistas congeladas.**
 *
 * Croquizando en el espacio uno encuentra el sitio bueno —el ángulo desde el que la pieza se
 * entiende, el alzado donde las medidas cuadran, el escorzo que enseña el hueco— y lo pierde
 * en cuanto gira para dibujar otra cosa. Volver a él a pulso no se puede: son dos ángulos,
 * un aumento, un punto al que se mira y una lente, todos a la vez. Aquí se congela con un
 * toque y se vuelve con otro.
 *
 * ## Y las miniaturas son el croquis de verdad
 *
 * Cada una **repinta la escena entera desde su cámara**, en pequeño ([pintarLaEscena]). No
 * es un adorno: una fila de cuadraditos de color, o de números, no dice a qué vista se
 * vuelve —y saberlo es lo único que se le pide a una galería—. Al dibujar más, cambian
 * solas, porque son el dibujo.
 *
 * En fila y perezosa: las vistas se guardan de a pocas, se miran de reojo y crecen sin
 * límite; una fila que compone las veinte aunque se vean tres se nota con el dedo encima del
 * croquis.
 */
@Composable
fun Croquis3DVistas(
    controlador: Croquis3DControlador,
    imagenes: Map<String, android.graphics.Bitmap>,
    modifier: Modifier = Modifier,
    /**
     * Qué hace «congelar esta».
     *
     * Lo pone la pantalla porque no siempre es lo mismo: en un croquis suelto es guardar la
     * vista, y en el croquis de un proyecto es además **dejarla de hoja** en él. Ver
     * [HojaDeLaVista].
     */
    alCongelar: () -> Unit = { controlador.congelarLaVista() },
    /** Y qué hace «una zona»: marcar el rectángulo antes de congelar. */
    alRecortar: () -> Unit = { controlador.recortando = true }
) {
    val vistas = controlador.croquis.vistas
    Surface(
        modifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 3.dp
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.croquis_vistas),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    " · ${stringResource(R.string.croquis_congelar)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = alCongelar)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
                // **Y congelar una zona.** Lo que uno se lleva a la lámina casi nunca es la
                // pantalla entera: es el detalle, o la pieza sin el aire de al lado. Se
                // cierra el panel y se marca el rectángulo sobre el dibujo, que es donde hay
                // que mirar para encuadrar.
                Text(
                    stringResource(R.string.croquis_congelar_zona),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = alRecortar)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            if (vistas.isEmpty()) {
                Text(
                    stringResource(R.string.croquis_sin_vistas),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    modifier = Modifier.padding(top = 6.dp)
                )
                return@Column
            }

            LazyRow(
                Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(vistas, key = { _, v -> v.id }) { i, vista ->
                    // El número por su sitio en la lista y no por el nombre guardado: una
                    // vista borrada dejaba huecos en la cuenta, y un croquis viejo puede
                    // traer nombres de cuando esto se llamaba de otra manera.
                    Miniatura(controlador, vista, i + 1, imagenes)
                }
            }
        }
    }
}

/**
 * Una vista de la galería: el croquis visto desde ella, su número y la ✕ de tirarla.
 *
 * La miniatura entera es el botón de volver —un blanco grande, que es lo que se hace nueve
 * de cada diez veces— y la ✕ va en una esquina, pequeña y pegada al canto, donde no se toca
 * por error al querer volver.
 */
@Composable
private fun Miniatura(
    controlador: Croquis3DControlador,
    vista: Vista3D,
    cual: Int,
    imagenes: Map<String, android.graphics.Bitmap>
) {
    val fondo = controlador.croquis.colorDelFondo
        ?.let { Color(parseColor(it, 255)) }
        ?: MaterialTheme.colorScheme.surface
    Box {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(
                Modifier
                    .size(width = ANCHO.dp, height = ALTO.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(fondo)
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(10.dp)
                    )
                    .clickable { controlador.irALaVista(vista.id) }
            ) {
                // **La misma cámara, al aumento de la miniatura.**
                //
                // El aumento guardado es el de la pantalla entera; en un recuadro de cuatro
                // dedos, lo dibujado se saldría por los cuatro lados. Encogiéndolo en la
                // misma proporción que el recuadro, la miniatura enseña **lo mismo** que se
                // vería al volver, que es justo lo que se le pide.
                val cuanto = size.width / controlador.anchoDeLaVista.toFloat()
                val encuadrada = camaraEncuadrada(
                    vista, controlador.anchoDeLaVista, controlador.altoDeLaVista
                )
                pintarLaEscena(
                    controlador.croquis,
                    encuadrada.copy(zoom = encuadrada.zoom * cuanto),
                    size.width.toDouble(),
                    size.height.toDouble(),
                    emptySet(),
                    imagenes,
                    espejoPuesto = false
                )
            }
            Text(
                stringResource(R.string.croquis_vista_n, cual),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .size(22.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f))
                .clickable { controlador.borrarLaVista(vista.id) },
            contentAlignment = Alignment.Center
        ) {
            Text("✕", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Lo que mide una miniatura, en dp. */
private const val ANCHO = 104
private const val ALTO = 74
