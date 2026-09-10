package com.forge.pixpin.guardados

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * **La cabecera del chat, que no es una barra: son tres pastillas flotando.**
 *
 * Es lo que Telegram llama **modo vidrio**, y no se parece en nada a una `TopAppBar`. No
 * hay ninguna barra: la `ActionBar` se queda **sin fondo** (`ActionBar.java:214-251`,
 * `setBackground(null)` y `setClipChildren(false)`) y en su `dispatchDraw` se pintan tres
 * pastillas sueltas —volver, el título, el menú— con la lista pasando **por detrás**,
 * a pantalla completa. Lo enciende `ChatActivity.java:4576-4579`.
 *
 * Lo pidió el usuario el 8-sep-2026 con una captura: «el header es como una píldora
 * flotante que no está como una barra… cada punto son puntos flotantes… el chat va por
 * detrás».
 *
 * ## Las medidas, sacadas de su fuente
 *
 * | Qué | Cuánto | Dónde |
 * |---|---|---|
 * | Alto de cada pastilla | 46 dp | `ActionBar.java:2223` (`s = dp(46)`) |
 * | Radio | 23, o sea cápsula entera | `ActionBar.java:2263` |
 * | Margen y separación | 6 dp | `ActionBar.java:2224` (`p = dp(6)`), `:2232` |
 * | La central se mide a su contenido y se **centra** entre las otras dos | | `ActionBar.java:2244-2246` |
 * | Filete que la despega del fondo | 0,55 dp | `BlurredBackgroundProviderImpl.java:171-175` |
 * | **No se encoge ni se esconde al desplazar** | | comprobado por tres vías |
 *
 * ## Y una cosa que Telegram hace y aquí **no**, a propósito
 *
 * Su pastilla lleva desenfoque de verdad de lo que pasa por debajo: capturan la pantalla
 * en un `RenderNode` **reducido ocho veces** y le aplican `RenderEffect` de 40 dp más un
 * triple de saturación (`DownscaleScrollableNoiseSuppressor.java:422-424`). La reducción
 * no es un detalle: es lo único que hace que salga barato.
 *
 * Compose sí sabe hacer esto —`rememberGraphicsLayer` con `renderEffect`— pero **a
 * resolución completa**: habría que grabar la conversación entera en una capa y volver a
 * dibujarla desenfocada en cada fotograma, justo en la pantalla que más se arrastra. Eso
 * es cambiar fluidez por adorno, y en este proyecto la fluidez manda.
 *
 * Lo interesante es que **Telegram opina igual**: solo desenfoca con API 31 y un aparato
 * que dé la talla (`ChatActivity.java:2619`, `SharedConfig.java:1748-1754`), y cuando no,
 * **no baja la calidad del desenfoque: lo quita y pone la pastilla opaca**
 * (`BlurredBackgroundProviderImpl.java:162-165`). Aquí se toma ese mismo camino siempre.
 * Para tener el suyo haría falta antes una captura reducida, no un desenfoque a pelo.
 *
 * La legibilidad de lo que pasa por detrás la resuelve lo mismo que a ellos: el velo de
 * 48 dp de [VeloDeLaLista] (`ChatActivityFadeView.java:47-51`).
 */

/** El alto de cada pastilla. `ActionBar.java:2223`. */
val ALTO_DE_LA_PILDORA = 46.dp

/** El margen contra el borde y la separación entre pastillas. `ActionBar.java:2224, 2232`. */
val AIRE_DE_LA_PILDORA = 6.dp

/** El filete que la separa del fondo. `BlurredBackgroundProviderImpl.java:171-175`. */
val FILETE_DE_LA_PILDORA = 0.55.dp

/** Lo que mide el velo que hace legible lo que pasa por debajo. `ChatActivityFadeView.java:47-51`. */
val VELO_DE_LA_LISTA = 48.dp

/**
 * La fila de tres pastillas.
 *
 * [atras] es opcional: cuando no hay a dónde volver, la pastilla de la izquierda no se
 * dibuja **pero su sitio se reserva**. Así el título no da un salto lateral al entrar en un
 * proyecto y salir de él, que es de las cosas que más delatan que algo está mal montado.
 */
@Composable
fun CabeceraFlotante(
    modifier: Modifier = Modifier,
    atras: (@Composable () -> Unit)? = null,
    menu: (@Composable RowScope.() -> Unit)? = null,
    centro: @Composable () -> Unit
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = AIRE_DE_LA_PILDORA, vertical = AIRE_DE_LA_PILDORA),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // El sitio de la izquierda se ocupa siempre, con pastilla o sin ella: ver arriba.
        if (atras != null) PastillaRedonda(atras) else Spacer(Modifier.size(ALTO_DE_LA_PILDORA))
        Spacer(Modifier.width(AIRE_DE_LA_PILDORA))
        // La central se lleva lo que sobra y se centra dentro, que es lo mismo que
        // «centrada entre las otras dos» cuando las dos laterales miden igual.
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .heightIn(min = ALTO_DE_LA_PILDORA)
                    .widthIn(max = 420.dp)
                    .clip(RoundedCornerShape(ALTO_DE_LA_PILDORA / 2))
                    .background(fondoDeLaPildora())
                    .border(
                        FILETE_DE_LA_PILDORA,
                        ColoresDelChat.filete(),
                        RoundedCornerShape(ALTO_DE_LA_PILDORA / 2)
                    )
                    // Aire arriba y abajo: sin él, lo que se meta dentro —un disco, dos
                    // renglones— llega al borde redondeado y se ve tocarlo.
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) { centro() }
        }
        if (menu != null) {
            Spacer(Modifier.width(AIRE_DE_LA_PILDORA))
            Row(
                Modifier
                    .height(ALTO_DE_LA_PILDORA)
                    .clip(RoundedCornerShape(ALTO_DE_LA_PILDORA / 2))
                    .background(fondoDeLaPildora())
                    .border(
                        FILETE_DE_LA_PILDORA,
                        ColoresDelChat.filete(),
                        RoundedCornerShape(ALTO_DE_LA_PILDORA / 2)
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(0.dp),
                content = menu
            )
        }
    }
}

/** Una pastilla de las laterales: un círculo de 46 dp con lo que se le meta dentro. */
@Composable
private fun PastillaRedonda(contenido: @Composable () -> Unit) {
    Box(
        Modifier
            .size(ALTO_DE_LA_PILDORA)
            .clip(CircleShape)
            .background(fondoDeLaPildora())
            .border(FILETE_DE_LA_PILDORA, ColoresDelChat.filete(), CircleShape),
        contentAlignment = Alignment.Center
    ) { contenido() }
}

/**
 * El fondo de la pastilla: **opaco**, que es lo que hace Telegram cuando no desenfoca.
 *
 * Ver el porqué arriba. Se usa `surfaceContainerHigh` y no `surface` para que la pastilla
 * se despegue del papel del chat sin necesitar sombra: el filete y un tono más marcan el
 * escalón, y una sombra bajo una cápsula que no se mueve solo añade trabajo al pintar.
 */
@Composable
private fun fondoDeLaPildora(): Color = MaterialTheme.colorScheme.surfaceContainerHigh

/**
 * **El velo que hace legible lo que pasa por debajo de las pastillas.**
 *
 * Un degradado de 48 dp del color del papel a transparente, arriba y abajo, por encima de
 * la lista y por debajo de las pastillas. Es literalmente lo que hace Telegram
 * (`ChatActivityFadeView.java:47-51`, montado en `ChatActivity.java:6979-6980`), y es lo
 * que permite que la lista vaya a pantalla completa sin que un mensaje se lea a medias
 * detrás de la cabecera: no se recorta la lista, se apaga lo que asoma.
 *
 * [colorDelPapel] es el extremo del degradado del chat que toque a ese lado, para que el
 * velo se funda con el fondo en vez de meter una franja gris.
 */
@Composable
fun VeloDeLaLista(colorDelPapel: Color, arriba: Boolean, modifier: Modifier = Modifier) {
    val colores =
        if (arriba) listOf(colorDelPapel, Color.Transparent)
        else listOf(Color.Transparent, colorDelPapel)
    Box(
        modifier
            .fillMaxWidth()
            .height(VELO_DE_LA_LISTA)
            .background(Brush.verticalGradient(colores))
    )
}
