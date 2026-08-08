package com.forge.pixpin.motor

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixNormal
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.HighlightAlt
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.JoinFull
import androidx.compose.material.icons.filled.SpaceBar
import androidx.compose.material.icons.filled.SquareFoot
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.outlined.Diamond
import androidx.compose.material.icons.outlined.NearMe
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forge.pixpin.R

/**
 * La barra de dibujo, **con el reparto de Excalidraw**.
 *
 * El intento anterior repartía las quince herramientas en cinco grupos y
 * obligaba a abrir uno para llegar a cualquier cosa: dos toques para coger el
 * rectángulo. Se veía lleno porque lo estaba.
 *
 * El original lo resuelve de otra forma, y es la que se copia aquí: **una sola
 * fila con lo que se usa siempre**, en su orden —mano y selección primero, luego
 * las formas de más a menos usadas, después texto e imagen, y el borrador al
 * final— y **un único desplegable «⋯» con lo raro**. Lo frecuente queda a un
 * toque; lo que se usa una vez al mes no estorba.
 *
 * En ese desplegable es donde viven las herramientas propias de PixPin, que es
 * exactamente el sitio que el original reserva para las suyas menos corrientes.
 *
 * La elegida **se queda puesta** hasta que la cambies.
 */
@Composable
fun DrawToolbar(
    tool: Tool,
    onTool: (Tool) -> Unit,
    style: ItemStyle,
    onStyle: (ItemStyle) -> Unit,
    canUndo: Boolean,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier,
    palette: List<Int> = DRAW_PALETTE,
    /** Selector de imagen, si el sitio donde vive la barra sabe abrirlo. */
    onImage: (() -> Unit)? = null,
    /** Modo noche: null si este sitio no lo ofrece. */
    dark: Boolean? = null,
    onToggleDark: (() -> Unit)? = null,
    /** Botón de cerrar, si el sitio donde vive la barra tiene salida. */
    onDone: (() -> Unit)? = null,
    /** Con qué se está midiendo, para avisar cuando no hay con qué. */
    escala: Escala? = null,
    onQuitarEscala: (() -> Unit)? = null,
    /**
     * Si la cota pide su medida al trazarla. A null este sitio no lo ofrece.
     *
     * Ver [DrawController.pedirLaMedida]: son dos formas de acotar, no una
     * mejor que la otra.
     */
    pedirLaMedida: Boolean? = null,
    onPedirLaMedida: (() -> Unit)? = null,
    /**
     * Qué herramientas se enseñan aquí. null = todas.
     *
     * Es lo que separa la edición simple de la avanzada: el pin lleva las que
     * el usuario haya marcado en los ajustes y el editor a pantalla completa no
     * filtra nada. La barra no decide cuáles son ni las recuerda —eso es de la
     * aplicación—; aquí solo se respeta la lista.
     */
    permitidas: Set<Tool>? = null,
    /**
     * Si se ofrece elegir la letra aquí.
     *
     * El pin dice que no: allí la letra se elige una vez en los ajustes, y el
     * botón ocupaba sitio en una barra flotante para algo que casi nadie cambia
     * dos veces. En el editor y en la captura sigue estando a mano.
     */
    mostrarFuente: Boolean = true,
    /**
     * El reparto en grupos, si este sitio lo usa.
     *
     * Con grupos la barra enseña **un botón por grupo** —la herramienta que
     * tengas puesta de ese grupo— y el resto se despliega al volver a tocarlo.
     * Es lo que permite tener diecinueve herramientas en una barra de seis
     * botones sin esconder nada a dos toques de distancia.
     *
     * A null se usa el reparto de siempre: fila principal más «⋯». El editor a
     * pantalla completa se queda así, que allí sitio hay.
     */
    grupos: List<List<Tool>>? = null,
    /**
     * El modo referencia y qué hacer con lo ya trazado en él.
     *
     * A null este sitio no lo ofrece. Va como un interruptor sobre las mismas
     * herramientas y no como una segunda lista: las de referencia tienen que ser
     * exactamente las mismas, y duplicarlas obligaría a mantener dos listas que
     * se separarían a la primera herramienta nueva.
     */
    modoReferencia: Boolean? = null,
    onModoReferencia: (() -> Unit)? = null,
    referenciasVisibles: Boolean = true,
    onAlternarReferencias: (() -> Unit)? = null,
    hayReferencias: Boolean = false
) {
    var extrasAbiertas by remember { mutableStateOf(false) }
    var grupoAbierto by remember { mutableStateOf(-1) }
    val principales = MAIN_TOOLS.filter { permitidas == null || it in permitidas }
    val extras = EXTRA_TOOLS.filter { permitidas == null || it in permitidas }

    Surface(shape = RoundedCornerShape(14.dp), shadowElevation = 6.dp, modifier = modifier) {
        Column(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 3.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Con qué vara se está midiendo, **solo mientras se mide**. Una cota
            // en píxeles que parezca metros es la peor forma de equivocarse que
            // tiene esta herramienta, así que cuando no hay escala se dice.
            if (tool == Tool.MEASURE || tool == Tool.SCALE) {
                AvisoEscala(
                    escala = escala,
                    onQuitar = onQuitarEscala,
                    tamanoTexto = style.fontSize,
                    onTamanoTexto = { onStyle(style.copy(fontSize = it)) }
                )
                // El interruptor de las dos formas de acotar, junto al aviso de
                // con qué se está midiendo: es donde uno está mirando cuando
                // decide cómo quiere acotar.
                if (tool == Tool.MEASURE && pedirLaMedida != null && onPedirLaMedida != null) {
                    ToolButton(
                        if (pedirLaMedida) Icons.Filled.Dialpad else Icons.Filled.Straighten,
                        stringResource(
                            if (pedirLaMedida) R.string.cota_dictar else R.string.cota_medir
                        ),
                        pedirLaMedida,
                        onPedirLaMedida
                    )
                }
            }

            // Lo que hay dentro del grupo abierto, ENCIMA de la fila: así la
            // fila no se mueve de sitio al desplegarlo y el botón que acabas de
            // tocar sigue debajo del dedo.
            val abierto = grupos?.getOrNull(grupoAbierto)
            if (abierto != null) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    abierto.forEach { t ->
                        ToolButton(iconFor(t), stringResource(labelFor(t)), t == tool) {
                            onTool(t)
                            grupoAbierto = -1
                        }
                    }
                }
                Separador(horizontal = true)
            }

            // Las extras se despliegan ENCIMA de la fila principal para que la
            // fila no se mueva de sitio al abrirlas.
            if (grupos == null && extrasAbiertas) {
                // También se desplaza: el desplegable ya no son cuatro botones
                // desde que viven aquí las de medir, y en un móvil estrecho las
                // últimas se quedaban fuera de la pantalla sin forma de llegar.
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    extras.forEach { t ->
                        ToolButton(iconFor(t), stringResource(labelFor(t)), t == tool) {
                            onTool(t)
                            extrasAbiertas = false
                        }
                    }
                }
                Separador(horizontal = true)
            }

            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (grupos != null) {
                    // **Un botón por grupo.** Enseña la herramienta que tengas
                    // puesta de ese grupo, y al volver a tocarlo despliega las
                    // hermanas: el primer toque coge, el segundo elige. Así la
                    // que usas está siempre a un toque y ninguna a más de dos.
                    grupos.forEachIndexed { i, grupo ->
                        val cara = caraDelGrupo(grupo, tool) ?: return@forEachIndexed
                        val activo = tool in grupo
                        ToolButton(iconFor(cara), stringResource(labelFor(cara)), activo) {
                            if (activo && grupo.size > 1) {
                                grupoAbierto = if (grupoAbierto == i) -1 else i
                            } else {
                                onTool(cara)
                                grupoAbierto = -1
                            }
                        }
                    }
                } else {
                    principales.forEach { t ->
                        ToolButton(iconFor(t), stringResource(labelFor(t)), t == tool) { onTool(t) }
                    }
                }
                onImage?.let {
                    ToolButton(Icons.Filled.Image, stringResource(R.string.tool_image), false, it)
                }
                // El «⋯» se enciende si la herramienta puesta vive dentro, y no
                // sale si no queda nada dentro: con la lista recortada podía
                // quedar un botón que abría una fila vacía.
                if (grupos == null && extras.isNotEmpty()) {
                    ToolButton(
                        Icons.Filled.MoreHoriz,
                        stringResource(R.string.cd_tools),
                        tool in extras
                    ) { extrasAbiertas = !extrasAbiertas }
                }

                // **El modo guía va al final de esta fila, no en una propia.**
                //
                // Tenía una fila para él solo con un botón y medio, y esta barra
                // flota encima de lo que estás dibujando: una fila entera es una
                // franja de dibujo que deja de verse. Aquí ocupa lo que ocupa
                // una herramienta más, que es lo que es.
                if (modoReferencia != null && onModoReferencia != null) {
                    Separador()
                    ToolButton(
                        Icons.Filled.Layers,
                        stringResource(R.string.ref_modo),
                        modoReferencia,
                        onModoReferencia
                    )
                    // Y el ojo solo cuando hay guías que esconder: un botón que
                    // no puede hacer nada no se enseña.
                    if (hayReferencias) {
                        onAlternarReferencias?.let {
                            ToolButton(
                                if (referenciasVisibles) Icons.Filled.Visibility
                                else Icons.Filled.VisibilityOff,
                                stringResource(R.string.ref_ver),
                                !referenciasVisibles,
                                it
                            )
                        }
                    }
                }
            }

            Separador(horizontal = true)

            Row(verticalAlignment = Alignment.CenterVertically) {
                ColorDots(palette, style.strokeColor) { onStyle(style.copy(strokeColor = it)) }
                Separador()
                StrokeWidthButton(style.strokeWidth) { onStyle(style.copy(strokeWidth = it)) }
                if (mostrarFuente) {
                    FontButton(style.fontFamily) { onStyle(style.copy(fontFamily = it)) }
                }
                Separador()
                IconButton(onClick = onUndo, enabled = canUndo) {
                    Icon(Icons.Filled.Undo, stringResource(R.string.cd_undo))
                }
                if (dark != null && onToggleDark != null) {
                    IconButton(onClick = onToggleDark) {
                        Icon(
                            if (dark) Icons.Filled.LightMode else Icons.Filled.DarkMode,
                            stringResource(if (dark) R.string.cd_modo_dia else R.string.cd_modo_noche)
                        )
                    }
                }
                onDone?.let {
                    IconButton(onClick = it) {
                        Icon(
                            Icons.Filled.Check,
                            stringResource(R.string.cd_done),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

/**
 * Con qué vara se mide ahora mismo, cómo dejar de medir con ella y **de qué
 * tamaño sale el número**.
 *
 * El tamaño va aquí y no en el panel de ajustes porque es lo primero que hay
 * que tocar: la cota se rotula en píxeles de la escena, y sobre una captura de
 * cuatro mil de ancho la letra por defecto sale ilegible. Estando al lado del
 * aviso se ve el problema y el remedio en el mismo sitio.
 */
@Composable
private fun AvisoEscala(
    escala: Escala?,
    onQuitar: (() -> Unit)?,
    tamanoTexto: Double,
    onTamanoTexto: (Double) -> Unit
) {
    Row(
        Modifier.padding(horizontal = 6.dp, vertical = 2.dp).horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (escala != null && escala.valida) {
                stringResource(R.string.escala_puesta, textoDeMedida(100.0, escala))
            } else {
                stringResource(R.string.escala_sin)
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (escala != null && escala.valida && onQuitar != null) {
            IconButton(onClick = onQuitar, modifier = Modifier.size(26.dp)) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.escala_quitar),
                    modifier = Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Separador()
        LetraBoton("A−", stringResource(R.string.medida_texto_menor)) {
            onTamanoTexto(tamanoDeMedidaMenor(tamanoTexto))
        }
        Text(
            text = "${tamanoTexto.toInt()}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        LetraBoton("A+", stringResource(R.string.medida_texto_mayor)) {
            onTamanoTexto(tamanoDeMedidaMayor(tamanoTexto))
        }
    }
}

@Composable
private fun LetraBoton(glifo: String, descripcion: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .padding(horizontal = 3.dp)
            .semantics { contentDescription = descripcion }
            .clickable { onClick() }
    ) {
        Text(
            text = glifo,
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
        )
    }
}

/**
 * El tamaño de letra siguiente, hacia arriba y hacia abajo.
 *
 * En pasos proporcionales y no de dos en dos: la cota se mide en píxeles de la
 * escena, así que el mismo salto que va bien en un lienzo a tamaño de pantalla
 * no se nota en una captura de cuatro mil píxeles de ancho. Multiplicando, subir
 * de 20 a 200 son ocho toques en vez de noventa.
 *
 * Fuera de Compose para poder comprobar los topes sin dispositivo.
 */
fun tamanoDeMedidaMayor(actual: Double): Double =
    (actual * MEDIDA_PASO).coerceIn(MEDIDA_MIN, MEDIDA_MAX)

fun tamanoDeMedidaMenor(actual: Double): Double =
    (actual / MEDIDA_PASO).coerceIn(MEDIDA_MIN, MEDIDA_MAX)

/** Cuánto crece o mengua la letra en cada toque. */
const val MEDIDA_PASO = 1.25

/** Por debajo no se lee ni de cerca; por encima ya tapa lo que se mide. */
const val MEDIDA_MIN = 8.0
const val MEDIDA_MAX = 400.0

/**
 * «¿Cuánto mide de verdad?»: lo que se pregunta al escalar.
 *
 * **Trae su propio teclado numérico** en vez de un campo de texto, y no es un
 * capricho de diseño. La barra de dibujo del pin vive en una ventana flotante
 * que no puede recibir el foco —si lo recibiera, robaría el teclado a la
 * aplicación de debajo—, así que un `TextField` ahí no llegaría a escribir
 * nada. Con las teclas puestas, el mismo diálogo sirve en el editor a pantalla
 * completa y en el pin, que es lo que evita tener dos formas de calibrar.
 *
 * Doce teclas y ya: aquí solo se teclean números, y de paso son teclas grandes,
 * que es lo que se agradece con el móvil en una mano en mitad de una obra.
 */
@Composable
fun DialogoEscala(
    largoPx: Double,
    onCalibrar: (medida: Double, unidad: String) -> Unit,
    onCancelar: () -> Unit,
    modifier: Modifier = Modifier
) {
    var texto by remember { mutableStateOf("") }
    var unidad by remember { mutableStateOf(Escala.METRO) }
    val medida = Escala.leerNumero(texto)
    val valida = medida != null && medida > 0.0 && largoPx > 0.0

    Surface(shape = RoundedCornerShape(16.dp), shadowElevation = 8.dp, modifier = modifier) {
        Column(
            Modifier.widthIn(max = 300.dp).padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                stringResource(R.string.escala_titulo),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                stringResource(R.string.escala_ayuda),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 10.dp)
            )

            // Lo tecleado, con la unidad al lado: es la frase que se está
            // formando —«4,20 m»— y no dos controles sueltos.
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (texto.isEmpty()) "0 $unidad" else "$texto $unidad",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (texto.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }

            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Escala.UNIDADES.forEach { u ->
                    val puesta = u == unidad
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (puesta) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.clickable { unidad = u }
                    ) {
                        Text(
                            u,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            color = if (puesta) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            for (fila in TECLAS) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    fila.forEach { tecla ->
                        Tecla(tecla) { texto = teclear(texto, tecla) }
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onCancelar) {
                    Text(stringResource(R.string.action_cancel))
                }
                TextButton(
                    onClick = { medida?.let { onCalibrar(it, unidad) } },
                    enabled = valida
                ) { Text(stringResource(R.string.escala_aceptar)) }
            }
        }
    }
}

/**
 * El mismo teclado, para **dictarle a una cota cuánto mide y hacia dónde va**.
 *
 * Se abre en cuanto se traza, igual que el de calibrar: es el momento en que uno
 * sabe la medida, y obligarle a ir a buscar un panel después es perderla. Se
 * teclea la distancia, se pasa al ángulo con la misma pestaña y ya.
 *
 * Y **el principio de la raya no se mueve**: se acierta con el dedo dónde empieza
 * una medida —en una esquina, en un cruce— y no se acierta nunca ni el largo ni
 * el ángulo. Ver [conLargoYAngulo].
 */
@Composable
fun DialogoDeCota(
    largoActual: Double,
    anguloActual: Double,
    unidad: String,
    onAceptar: (largo: Double, grados: Double) -> Unit,
    onCancelar: () -> Unit,
    modifier: Modifier = Modifier
) {
    var enElAngulo by remember { mutableStateOf(false) }
    var largo by remember { mutableStateOf(formatearMedida(largoActual)) }
    var angulo by remember { mutableStateOf(formatearMedida(anguloActual)) }
    val valorLargo = Escala.leerNumero(largo)
    val valorAngulo = Escala.leerNumero(angulo)
    val valida = valorLargo != null && valorLargo > 0.0 && valorAngulo != null

    Surface(shape = RoundedCornerShape(16.dp), shadowElevation = 8.dp, modifier = modifier) {
        Column(
            Modifier.widthIn(max = 300.dp).padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                stringResource(R.string.cota_titulo),
                style = MaterialTheme.typography.titleMedium
            )

            // Las dos casillas, y se teclea en la que esté encendida. Dos
            // teclados no caben en una barra flotante, y con uno solo hay que
            // decir a cuál de las dos va lo que se pulsa.
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                CasillaDeCota(
                    valor = if (largo.isEmpty()) "0" else largo,
                    sufijo = unidad,
                    etiqueta = stringResource(R.string.medida_largo),
                    activa = !enElAngulo,
                    modifier = Modifier.weight(1f)
                ) { enElAngulo = false }
                CasillaDeCota(
                    valor = if (angulo.isEmpty()) "0" else angulo,
                    sufijo = "°",
                    etiqueta = stringResource(R.string.medida_angulo),
                    activa = enElAngulo,
                    modifier = Modifier.weight(1f)
                ) { enElAngulo = true }
            }

            for (fila in TECLAS) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    fila.forEach { tecla ->
                        Tecla(tecla) {
                            if (enElAngulo) angulo = teclear(angulo, tecla)
                            else largo = teclear(largo, tecla)
                        }
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // El menos, para los ángulos que van hacia abajo. Solo cuando se
                // está tecleando el ángulo: una distancia negativa no existe.
                if (enElAngulo) {
                    TextButton(onClick = {
                        angulo = if (angulo.startsWith("-")) angulo.drop(1) else "-" + angulo
                    }) { Text("±") }
                }
                TextButton(onClick = onCancelar) {
                    Text(stringResource(R.string.action_cancel))
                }
                TextButton(
                    onClick = { onAceptar(valorLargo ?: 0.0, valorAngulo ?: 0.0) },
                    enabled = valida
                ) { Text(stringResource(R.string.escala_aceptar)) }
            }
        }
    }
}

@Composable
private fun CasillaDeCota(
    valor: String,
    sufijo: String,
    etiqueta: String,
    activa: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (activa) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.clickable { onClick() }
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(
                etiqueta,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "$valor $sufijo",
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/** Las teclas, en el orden del teclado de un teléfono. */
private val TECLAS: List<List<String>> = listOf(
    listOf("1", "2", "3"),
    listOf("4", "5", "6"),
    listOf("7", "8", "9"),
    listOf(",", "0", "⌫")
)

@Composable
private fun Tecla(etiqueta: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.padding(vertical = 3.dp).size(width = 74.dp, height = 44.dp)
            .clickable { onClick() }
    ) {
        Box(Modifier.padding(4.dp), contentAlignment = Alignment.Center) {
            Text(etiqueta, fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/**
 * Qué deja cada tecla en lo tecleado.
 *
 * Fuera de Compose porque es una regla, no una pintura: que no se puedan meter
 * dos comas ni empezar por coma se comprueba sin dispositivo.
 */
fun teclear(actual: String, tecla: String): String = when {
    tecla == "⌫" -> actual.dropLast(1)
    // Una sola coma, y nunca la primera: «,5» no es un número que nadie escriba.
    tecla == "," -> if (actual.contains(',') || actual.isEmpty()) actual else actual + tecla
    // Sin ceros a la izquierda: «007» se teclea sin querer y se lee mal.
    actual == "0" -> tecla
    actual.length >= MAX_CIFRAS -> actual
    else -> actual + tecla
}

/** Suficiente para cualquier medida real y corto para que quepa en la línea. */
private const val MAX_CIFRAS = 9

/**
 * Un botón de la barra.
 *
 * Lo activo lleva **pastilla sólida detrás**, no solo un tinte distinto: con el
 * tema oscuro el color de acento y el gris del resto quedan a un paso de
 * contraste y no se distinguía cuál estaba puesta.
 */
@Composable
private fun ToolButton(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        modifier = Modifier.padding(horizontal = 1.dp)
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(38.dp)) {
            Icon(
                icon,
                contentDescription = label,
                modifier = Modifier.size(20.dp),
                tint = if (selected) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun Separador(horizontal: Boolean = false) {
    Box(
        Modifier
            .padding(if (horizontal) 2.dp else 4.dp)
            .size(
                width = if (horizontal) 120.dp else 1.dp,
                height = if (horizontal) 1.dp else 20.dp
            )
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}

@Composable
private fun ColorDots(palette: List<Int>, current: String, onPick: (String) -> Unit) {
    palette.forEach { argb ->
        val hex = argb.toHexColor()
        val selected = hex.equals(current, ignoreCase = true)
        Box(
            Modifier
                .padding(2.dp)
                .size(if (selected) 22.dp else 18.dp)
                .background(Color(argb), CircleShape)
                .border(
                    width = if (selected) 3.dp else 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary else Color.Gray,
                    shape = CircleShape
                )
                .clickable { onPick(hex) }
        )
    }
}

/**
 * Los cuatro grosores del original, en rotación.
 *
 * Un botón que va pasando de uno a otro y no cuatro botones ni un deslizador:
 * son cuatro valores y el propio botón enseña cuál está puesto por su tamaño.
 */
@Composable
private fun StrokeWidthButton(current: Double, onPick: (Double) -> Unit) {
    IconButton(onClick = {
        val i = ItemStyle.STROKE_WIDTHS.indexOfFirst { it >= current }
        onPick(ItemStyle.STROKE_WIDTHS[(if (i < 0) 0 else i + 1) % ItemStyle.STROKE_WIDTHS.size])
    }) {
        Box(
            Modifier
                .size((current * 1.6 + 5).dp)
                .background(MaterialTheme.colorScheme.onSurface, CircleShape)
        )
    }
}

/** Las tres letras, en rotación y **escritas con su propia letra**. */
@Composable
private fun FontButton(current: Int, onPick: (Int) -> Unit) {
    val i = ItemStyle.FONT_FAMILIES.indexOf(ItemStyle.fontFamilyResuelta(current))
    IconButton(onClick = {
        onPick(ItemStyle.FONT_FAMILIES[(i + 1).mod(ItemStyle.FONT_FAMILIES.size)])
    }) {
        androidx.compose.material3.Text(
            text = "Aa",
            fontFamily = composeFontFamily(current),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** La familia de Compose que corresponde a una familia de Excalidraw. */
@Composable
fun composeFontFamily(familia: Int?): androidx.compose.ui.text.font.FontFamily =
    when (ItemStyle.fontFamilyResuelta(familia)) {
        ItemStyle.FONT_NUNITO ->
            androidx.compose.ui.text.font.FontFamily(androidx.compose.ui.text.font.Font(R.font.nunito))
        ItemStyle.FONT_COMIC_SHANNS ->
            androidx.compose.ui.text.font.FontFamily(androidx.compose.ui.text.font.Font(R.font.comic_shanns))
        else ->
            androidx.compose.ui.text.font.FontFamily(androidx.compose.ui.text.font.Font(R.font.excalifont))
    }

// -------------------------------------------------------------------------
// El reparto
//
// Fuera del composable y sin nada de Compose: qué herramienta va a la vista y
// cuál al desplegable es una decisión que se puede comprobar sin dispositivo
// —que ninguna se quede fuera, que ninguna esté en los dos sitios—, y es justo
// lo que se escapa al tocar la barra.
// -------------------------------------------------------------------------

/**
 * La fila principal, **en el orden de `Toolbar.tsx`**: mano y selección, las
 * formas de más a menos usada, el lápiz, el texto y el borrador al final. La
 * imagen va detrás, como en el original, pero la pone quien tenga selector de
 * archivos.
 */
val MAIN_TOOLS: List<Tool> = listOf(
    Tool.HAND,
    Tool.SELECTION,
    Tool.RECTANGLE,
    Tool.DIAMOND,
    Tool.ELLIPSE,
    Tool.ARROW,
    Tool.LINE,
    Tool.FREEDRAW,
    Tool.TEXT,
    Tool.ERASER
)

/**
 * Las del desplegable «⋯».
 *
 * El original guarda ahí el lazo, el láser y el marco. Aquí van el lazo y las
 * de PixPin: el mismo criterio —lo que no se usa a diario— aplicado a nuestras
 * herramientas.
 *
 * Las dos de medir van juntas y al final, en su orden de uso: primero se
 * escala y luego se acota. Antes vivían en el croquis, que era una aplicación
 * entera para esto; aquí son dos botones más del mismo motor y se pueden usar
 * encima de cualquier cosa que se esté dibujando.
 */
val EXTRA_TOOLS: List<Tool> = listOf(
    Tool.LASSO,
    Tool.HIGHLIGHTER,
    Tool.RELLENO,
    Tool.RECORTAR,
    Tool.EXTENDER,
    Tool.MOSAIC,
    Tool.SPOTLIGHT,
    Tool.SERIAL,
    Tool.FRAME,
    Tool.SCALE,
    Tool.MEASURE,
    Tool.ESCALA_GRAFICA,
    Tool.NUDO
)

/**
 * Todas, en el orden en que se enseñan. Es lo que recorre la pantalla de
 * ajustes para preguntar cuáles se quedan en el pin.
 */
val ALL_TOOLS: List<Tool> = MAIN_TOOLS + EXTRA_TOOLS

/**
 * Con qué se anota **dentro del pin** mientras nadie diga otra cosa.
 *
 * La edición simple no es una edición recortada por capricho: el pin es una
 * ventana de dos dedos de ancho con una barra flotante encima, y ahí no caben
 * diecisiete herramientas sin que la barra tape justo la foto que estás
 * anotando. Se dejan las de señalar y tapar sobre una captura —que es a lo que
 * se viene— y el resto vive en la edición avanzada, que tiene pantalla entera.
 *
 * La mano y el lazo se quedan fuera: en el pin la vista no se encuadra, la
 * imagen está siempre entera, y seleccionar a mano alzada pide una precisión
 * que ahí no se tiene.
 */
val PIN_TOOLS_POR_DEFECTO: Set<Tool> = setOf(
    Tool.SELECTION,
    Tool.ARROW,
    Tool.RECTANGLE,
    Tool.ELLIPSE,
    Tool.FREEDRAW,
    Tool.TEXT,
    Tool.ERASER,
    Tool.MOSAIC,
    Tool.SERIAL
)

/**
 * Con qué se dibuja **encima de la pantalla** mientras nadie diga otra cosa.
 *
 * Más que en el pin porque hay más sitio: la capa ocupa la pantalla entera y su
 * barra no tapa lo que estás anotando más de lo imprescindible.
 *
 * Fuera se quedan las que ahí no significan nada: la mano y el lazo —la vista
 * no se encuadra, la pantalla es la pantalla—, la imagen y la hoja, que son de
 * un documento y no de un borrador encima de otra aplicación, y las de medir,
 * que sin calibrar sobre algo conocido darían píxeles.
 */
val CAPA_TOOLS_POR_DEFECTO: Set<Tool> = setOf(
    Tool.SELECTION,
    Tool.ARROW,
    Tool.RECTANGLE,
    Tool.ELLIPSE,
    Tool.LINE,
    Tool.FREEDRAW,
    Tool.HIGHLIGHTER,
    Tool.RELLENO,
    Tool.TEXT,
    Tool.ERASER,
    Tool.MOSAIC,
    Tool.SPOTLIGHT,
    Tool.SERIAL
)

fun iconFor(tool: Tool): ImageVector = when (tool) {
    Tool.SELECTION -> Icons.Outlined.NearMe
    Tool.LASSO -> Icons.Filled.HighlightAlt
    Tool.HAND -> Icons.Filled.PanTool
    Tool.FREEDRAW -> Icons.Filled.Gesture
    Tool.HIGHLIGHTER -> Icons.Filled.Highlight
    Tool.ERASER -> Icons.Filled.AutoFixNormal
    Tool.RECTANGLE -> Icons.Filled.CropSquare
    Tool.ELLIPSE -> Icons.Filled.RadioButtonUnchecked
    Tool.DIAMOND -> Icons.Outlined.Diamond
    Tool.ARROW -> Icons.Filled.NorthEast
    Tool.LINE -> Icons.Filled.Remove
    Tool.TEXT -> Icons.Filled.TextFields
    Tool.SERIAL -> Icons.Filled.FormatListNumbered
    Tool.MOSAIC -> Icons.Filled.BlurOn
    Tool.SPOTLIGHT -> Icons.Filled.CenterFocusStrong
    Tool.IMAGE -> Icons.Filled.Image
    Tool.FRAME -> Icons.Filled.CropFree
    Tool.MEASURE -> Icons.Filled.SquareFoot
    Tool.SCALE -> Icons.Filled.Straighten
    Tool.RELLENO -> Icons.Filled.FormatColorFill
    Tool.ESCALA_GRAFICA -> Icons.Filled.SpaceBar
    Tool.NUDO -> Icons.Filled.JoinFull
    Tool.RECORTAR -> Icons.Filled.ContentCut
    Tool.EXTENDER -> Icons.Filled.OpenInFull
}

@StringRes
fun labelFor(tool: Tool): Int = when (tool) {
    Tool.SELECTION -> R.string.tool_selection
    Tool.LASSO -> R.string.tool_lasso
    Tool.HAND -> R.string.tool_hand
    Tool.FREEDRAW -> R.string.tool_freedraw
    Tool.HIGHLIGHTER -> R.string.tool_highlighter
    Tool.ERASER -> R.string.tool_eraser
    Tool.RECTANGLE -> R.string.tool_rectangle
    Tool.ELLIPSE -> R.string.tool_ellipse
    Tool.DIAMOND -> R.string.tool_diamond
    Tool.ARROW -> R.string.tool_arrow
    Tool.LINE -> R.string.tool_line
    Tool.TEXT -> R.string.tool_text
    Tool.SERIAL -> R.string.tool_serial
    Tool.MOSAIC -> R.string.tool_mosaic
    Tool.SPOTLIGHT -> R.string.tool_spotlight
    Tool.IMAGE -> R.string.tool_image
    Tool.FRAME -> R.string.tool_frame
    Tool.MEASURE -> R.string.tool_measure
    Tool.SCALE -> R.string.tool_scale
    Tool.RELLENO -> R.string.tool_relleno
    Tool.ESCALA_GRAFICA -> R.string.tool_escala_grafica
    Tool.NUDO -> R.string.tool_nudo
    Tool.RECORTAR -> R.string.tool_recortar
    Tool.EXTENDER -> R.string.tool_extender
}

/**
 * Paleta de la barra: los colores de trazo del original (`COLOR_PALETTE`).
 *
 * Negro, rojo, verde, azul y naranja. Son los cinco que ofrece Excalidraw y se
 * dejan en su orden; el blanco se añade porque aquí se dibuja sobre capturas de
 * pantalla y sobre un fondo oscuro ninguno de los otros cinco resalta.
 */
val DRAW_PALETTE: List<Int> = listOf(
    0xFF1E1E1E.toInt(), 0xFFE03131.toInt(), 0xFF2F9E44.toInt(),
    0xFF1971C2.toInt(), 0xFFF08C00.toInt(), 0xFFFFFFFF.toInt()
)

/** ARGB de Android al `#rrggbb` que guarda el `.excalidraw`. */
fun Int.toHexColor(): String = String.format("#%06x", this and 0xFFFFFF)
