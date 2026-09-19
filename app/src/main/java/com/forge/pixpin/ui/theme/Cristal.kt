package com.forge.pixpin.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * **El cristal**: las piezas de las barras flotantes, con el aspecto de la barra de modos del
 * sistema solar (17-sep-2026). Lo pidió el usuario: «donde haya menús flotantes en barra, ese
 * estilo: iconos grandes y bien ordenados, botones redondeados y semitransparentes».
 *
 * - [BarraDeCristal]: la pastilla que los contiene, azul translúcido con un filo claro.
 * - [BotonDeBarra]: icono grande con su nombre debajo; el puesto, en dorado.
 * - [BotonRedondo]: el círculo translúcido de las cabeceras (volver, buscar, verlo todo).
 *
 * Con otro aspecto que no sea Cosmos toman los colores del tema, con la misma forma: así una
 * barra no tiene que saber qué aspecto hay puesto. Sin desenfoque, por rendimiento
 * ([pixpin-cabecera-telegram]).
 */
object Cristal {
    /** El fondo de una barra. */
    val barra: Color
        @Composable @ReadOnlyComposable get() =
            if (LocalCosmos.current) Color(0xDD161A33)
            else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f)

    /** El filo de una barra. */
    val filo: Color
        @Composable @ReadOnlyComposable get() =
            if (LocalCosmos.current) Color(0x33FFFFFF) else MaterialTheme.colorScheme.outlineVariant

    /** El fondo de un botón redondo suelto. */
    val boton: Color
        @Composable @ReadOnlyComposable get() =
            if (LocalCosmos.current) Color(0x33FFFFFF)
            else MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.9f)

    /** La tinta de lo que no está puesto. */
    val tinta: Color
        @Composable @ReadOnlyComposable get() =
            if (LocalCosmos.current) Color.White.copy(alpha = 0.88f) else MaterialTheme.colorScheme.onSurface

    /** El fondo de lo puesto: el dorado del cosmos, o el primario. */
    val puesto: Color
        @Composable @ReadOnlyComposable get() =
            if (LocalCosmos.current) CosmosDorado else MaterialTheme.colorScheme.primary

    val tintaPuesta: Color
        @Composable @ReadOnlyComposable get() =
            if (LocalCosmos.current) Color(0xFF231A00) else MaterialTheme.colorScheme.onPrimary

    val formaDeBarra = RoundedCornerShape(28.dp)
    val formaDeBoton = RoundedCornerShape(22.dp)
}

/**
 * **Los nombres de botón, cortos y en una línea** (lo pidió el usuario el 17-sep-2026): si un
 * nombre no cabe, no se corta con puntos suspensivos, se cambia por otro más breve. Esta tabla
 * es el sitio de esos cambios; [nombreDeBoton] la aplica y, si aun así se pasa, se queda con la
 * primera palabra. [LARGO_MAXIMO] letras como mucho.
 */
object NombresCortos {
    const val LARGO_MAXIMO = 9

    val tabla: Map<String, String> = mapOf(
        "Seleccionar" to "Elegir",
        "Selección" to "Elegir",
        "Rectángulo" to "Rect.",
        "Rectangulo" to "Rect.",
        "Circunferencia" to "Círculo",
        "Herramientas" to "Útiles",
        "Propiedades" to "Estilo",
        "Configuración" to "Ajustes",
        "Compartir" to "Enviar",
        "Exportar" to "Sacar",
        "Presentar" to "Mostrar",
        "Deshacer" to "Atrás",
        "Rehacer" to "Rehacer",
        "Borrador" to "Goma",
        "Fotografía" to "Foto",
        "Imágenes" to "Fotos",
        "Imagen" to "Foto",
        "Documento" to "Doc",
        "Documentos" to "Docs",
        "Sincronizar" to "Sincro",
        "Transcribir" to "A texto",
        "Transcripción" to "Texto",
        "Grabar audio" to "Grabar",
        "Nota de voz" to "Voz",
        "Pantalla completa" to "Pantalla",
        "Cuadrícula" to "Rejilla",
        "Cuadricula" to "Rejilla",
        "Plantillas" to "Moldes",
        "Biblioteca" to "Figuras",
        "Conectar" to "Unir",
        "Desmarcar" to "Soltar",
        "Renombrar" to "Nombre",
        "Cambiar nombre" to "Nombre",
        "Cambiar el nombre" to "Nombre",
        "Fusionar" to "Juntar",
        "Imprimir" to "Imprimir",
        "Archivar" to "Archivar",
        "Buscar" to "Buscar",
        "Proyectos" to "Proyect.",
        "Mensajes" to "Chat"
    )

    fun de(nombre: String): String {
        val limpio = nombre.trim()
        if (limpio.length <= LARGO_MAXIMO) return limpio
        tabla[limpio]?.let { return it }
        val primera = limpio.substringBefore(' ')
        tabla[primera]?.let { return it }
        return if (primera.length <= LARGO_MAXIMO) primera else primera.take(LARGO_MAXIMO)
    }
}

/** El nombre que se enseña en un botón de barra. Ver [NombresCortos]. */
fun nombreDeBoton(nombre: String): String = NombresCortos.de(nombre)

/** Una pastilla de cristal: el contenedor de cualquier barra o isla flotante. */
@Composable
fun Modifier.cristal(forma: Shape = Cristal.formaDeBarra): Modifier =
    this.clip(forma).background(Cristal.barra).border(1.dp, Cristal.filo, forma)

/**
 * El fondo y el filo del cristal **sin recortar a los hijos**: para los paneles de los que
 * tiene que poder salirse algo, como la rueda del color que cuelga de su mando.
 */
@Composable
fun Modifier.fondoDeCristal(forma: Shape = Cristal.formaDeBarra): Modifier =
    this.background(Cristal.barra, forma).border(1.dp, Cristal.filo, forma)

/** Una barra flotante de cristal, con sus botones en fila. */
@Composable
fun BarraDeCristal(
    modifier: Modifier = Modifier,
    separacion: Dp = 4.dp,
    contenido: @Composable RowScope.() -> Unit
) {
    Row(
        modifier.cristal().padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(separacion),
        verticalAlignment = Alignment.CenterVertically,
        content = contenido
    )
}

/**
 * Un botón de barra: **icono grande y su nombre debajo**. Sin nombre, solo el icono, igual de
 * grande. [puesto] lo pinta en dorado, que es como se sabe qué está elegido.
 */
@Composable
fun BotonDeBarra(
    icono: ImageVector,
    texto: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    puesto: Boolean = false,
    activo: Boolean = true,
    /** Lo que lee el lector de pantalla: el nombre entero, aunque se enseñe el corto. */
    descripcion: String? = texto,
    onLargo: (() -> Unit)? = null,
    tintaPropia: Color? = null
) {
    val tinta = when {
        puesto -> Cristal.tintaPuesta
        tintaPropia != null -> tintaPropia
        else -> Cristal.tinta
    }.let { if (activo) it else it.copy(alpha = 0.35f) }
    Column(
        modifier
            .clip(Cristal.formaDeBoton)
            .background(if (puesto) Cristal.puesto else Color.Transparent)
            .combinedClickable(enabled = activo, onClick = onClick, onLongClick = onLargo)
            .padding(horizontal = if (texto == null) 10.dp else 12.dp, vertical = if (texto == null) 10.dp else 6.dp)
            .widthIn(min = if (texto == null) 26.dp else 44.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icono, contentDescription = descripcion, tint = tinta, modifier = Modifier.size(if (texto == null) 26.dp else 22.dp))
        if (texto != null) {
            // Una línea y corto: si no cabe, se cambia por otro nombre. Ver [NombresCortos].
            Text(
                nombreDeBoton(texto),
                color = tinta,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                textAlign = TextAlign.Center
            )
        }
    }
}

/** El botón redondo translúcido de las cabeceras. */
@Composable
fun BotonRedondo(
    icono: ImageVector,
    descripcion: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tamano: Dp = 48.dp,
    puesto: Boolean = false,
    activo: Boolean = true,
    onLargo: (() -> Unit)? = null
) {
    Box(
        modifier
            .size(tamano)
            .clip(CircleShape)
            .background(if (puesto) Cristal.puesto else Cristal.boton)
            .combinedClickable(enabled = activo, onClick = onClick, onLongClick = onLargo),
        contentAlignment = Alignment.Center
    ) {
        val tinta = if (puesto) Cristal.tintaPuesta else Cristal.tinta
        Icon(
            icono, contentDescription = descripcion,
            tint = if (activo) tinta else tinta.copy(alpha = 0.35f),
            modifier = Modifier.size(tamano * 0.5f)
        )
    }
}

/**
 * Una superficie de cristal: lo mismo que una `Surface` de isla, con el fondo, el filo y la
 * tinta del cristal y **sin sombra** (una sombra bajo un fondo translúcido se ve como una
 * mancha). Para cambiar una isla de estilo sin tocar lo que lleva dentro.
 */
@Composable
fun SuperficieDeCristal(
    modifier: Modifier = Modifier,
    forma: Shape = Cristal.formaDeBarra,
    contenido: @Composable () -> Unit
) {
    androidx.compose.material3.Surface(
        modifier = modifier,
        shape = forma,
        color = Cristal.barra,
        contentColor = Cristal.tinta,
        border = androidx.compose.foundation.BorderStroke(1.dp, Cristal.filo),
        content = contenido
    )
}
