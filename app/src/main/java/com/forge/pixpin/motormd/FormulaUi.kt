package com.forge.pixpin.motormd

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Compone una fórmula ya entendida.
 *
 * Cada construcción es una disposición: una fracción es una columna con una raya
 * en medio, un exponente es una fila con el índice más pequeño y subido, una raíz
 * es un signo dibujado con una línea que tapa lo de dentro. Se baja por el árbol
 * de [Formulas] y cada rama se compone con la de dentro más pequeña, que es lo
 * que hace que un exponente dentro de un exponente salga bien sin ningún caso
 * especial.
 *
 * No es LaTeX de verdad y no lo pretende: no hay ajuste fino de espacios ni
 * cursivas matemáticas propias. Es lo que hace falta para que una fórmula de una
 * nota **se lea como una fórmula** y no como una línea de símbolos.
 */
@Composable
fun FormulaUi(
    latex: String,
    tamanoSp: Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface
) {
    val arbol = remember(latex) { Formulas.leer(latex) }
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        PiezaUi(arbol, tamanoSp, color)
    }
}

/** Cuánto encoge lo que va en un exponente o en un índice de raíz. */
private const val ENCOGE = 0.72f

/** Lo menos que se encoge, o a la tercera planta ya no se lee nada. */
private const val MINIMO_SP = 9f

@Composable
private fun PiezaUi(pieza: Pieza, tamanoSp: Float, color: Color) {
    when (pieza) {
        is Pieza.Texto -> Text(
            text = pieza.texto,
            fontSize = tamanoSp.sp,
            fontStyle = if (pieza.cursiva) FontStyle.Italic else FontStyle.Normal,
            fontFamily = FontFamily.Serif,
            color = color
        )

        is Pieza.Fila -> Row(verticalAlignment = Alignment.CenterVertically) {
            pieza.partes.forEach { PiezaUi(it, tamanoSp, color) }
        }

        // La fracción: uno encima de otro y una raya que llega de lado a lado.
        is Pieza.Fraccion -> Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 2.dp)
        ) {
            PiezaUi(pieza.arriba, menor(tamanoSp), color)
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(((tamanoSp * 0.09f).coerceAtLeast(1f)).dp)
                    .padding(vertical = 1.dp)
            ) {
                drawRect(color)
            }
            PiezaUi(pieza.abajo, menor(tamanoSp), color)
        }

        // Los índices van pegados a la base: el de arriba subido, el de abajo
        // bajado, los dos más pequeños. En columna para que uno no empuje al
        // otro cuando están los dos.
        is Pieza.ConIndices -> Row(verticalAlignment = Alignment.CenterVertically) {
            PiezaUi(pieza.base, tamanoSp, color)
            Column(horizontalAlignment = Alignment.Start) {
                pieza.arriba?.let {
                    Box(Modifier.padding(bottom = (tamanoSp * 0.28f).dp)) {
                        PiezaUi(it, menor(tamanoSp), color)
                    }
                }
                pieza.abajo?.let {
                    Box(Modifier.padding(top = (tamanoSp * 0.28f).dp)) {
                        PiezaUi(it, menor(tamanoSp), color)
                    }
                }
            }
        }

        // La raíz: su índice arriba a la izquierda, el signo, y una raya por
        // encima de lo de dentro que llega hasta donde llegue.
        is Pieza.Raiz -> Row(verticalAlignment = Alignment.CenterVertically) {
            pieza.indice?.let {
                Box(Modifier.padding(bottom = (tamanoSp * 0.4f).dp)) {
                    PiezaUi(it, (tamanoSp * 0.55f).coerceAtLeast(MINIMO_SP), color)
                }
            }
            Text(
                text = "√",
                fontSize = (tamanoSp * 1.15f).sp,
                fontFamily = FontFamily.Serif,
                color = color
            )
            Column {
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                ) { drawRect(color) }
                Spacer(Modifier.height(1.dp))
                Box(Modifier.padding(horizontal = 1.dp)) {
                    PiezaUi(pieza.dentro, tamanoSp, color)
                }
            }
        }

        is Pieza.Agrupado -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = pieza.abre,
                fontSize = (tamanoSp * 1.1f).sp,
                fontFamily = FontFamily.Serif,
                color = color
            )
            PiezaUi(pieza.dentro, tamanoSp, color)
            Text(
                text = pieza.cierra,
                fontSize = (tamanoSp * 1.1f).sp,
                fontFamily = FontFamily.Serif,
                color = color
            )
        }
    }
}

private fun menor(tamanoSp: Float): Float = (tamanoSp * ENCOGE).coerceAtLeast(MINIMO_SP)
