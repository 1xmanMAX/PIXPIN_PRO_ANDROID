package com.forge.pixpin.motor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * La ventana de configuración: **lo que se toca una vez, junto**.
 *
 * La barra de arriba tiene que quedarse con lo que se usa dibujando. Todo lo
 * demás —cómo se ve el lienzo, cómo engancha el dedo, sacar el dibujo de aquí—
 * se toca al empezar o al terminar, y cada cosa suelta en la barra le robaba
 * sitio a las que sí se usan a cada rato.
 *
 * Van en pestañas y no en una lista larga porque son grupos que no se mezclan:
 * quien viene a exportar no está mirando el imán. Y **exportar es una pestaña
 * más**, no un menú aparte: era la única de estas que tenía botón propio, y
 * tenerlo en dos sitios es justo lo que hay que evitar.
 *
 * Flota y se arrastra, como la paleta, pero **se va sola en cuanto se toca el
 * lienzo**: es una ventana para ajustar y volver, no para dejarla puesta. Quien
 * toca el dibujo ya no la está mirando. Ver `DrawCanvas.onTocar`.
 */

/** Cada pestaña de la ventana. */
enum class PestanaDeAjustes(val titulo: String) {
    LIENZO("Lienzo"),
    DIBUJO("Dibujo"),
    EXPORTAR("Exportar"),
    DETALLE("Detalle")
}

/** Una opción de exportar, para no repetir la fila cuatro veces. */
data class FormatoDeSalida(val icono: ImageVector, val nombre: String, val al: () -> Unit)

@Composable
fun VentanaDeAjustes(
    cuadricula: Cuadricula,
    onCuadricula: (Cuadricula) -> Unit,
    zoomBloqueado: Boolean,
    onZoomBloqueado: (Boolean) -> Unit,
    /** Enseñar u ocultar las líneas de referencia; null si no hay ninguna. */
    referenciasVisibles: Boolean?,
    onReferencias: () -> Unit,
    /** Solo el lápiz dibuja; el dedo mueve el papel. */
    modoLapiz: Boolean,
    onModoLapiz: (Boolean) -> Unit,
    /** Ensancha todas las zonas de toque. Ver [DrawController.modoDedo]. */
    modoDedo: Boolean,
    onModoDedo: (Boolean) -> Unit,
    /**
     * **Cómo se trae el plano del PDF: como líneas o como imagen.** Null cuando no se está
     * anotando ningún PDF, y entonces el interruptor no sale. Ver [PlanoEnPantalla].
     */
    planoEnLineas: Boolean? = null,
    onPlanoEnLineas: (Boolean) -> Unit = {},
    /** El trazo a mano sale firme. Ver [Element.presionFirme]. */
    presionFirme: Boolean,
    onPresionFirme: (Boolean) -> Unit,
    /** La llave de paso de las luces del dibujo. Ver [LucesDelDibujo]. */
    luces: LucesDelDibujo,
    onLuces: (LucesDelDibujo) -> Unit,
    /**
     * El estilo que hay puesto ahora mismo: es el valor que guarda el botón de «+».
     *
     * Ver [MarcasGuardadas]: una marca se guarda **con lo que hay puesto**, que es lo mismo
     * que hacía tocar el mango del deslizador viejo.
     */
    estilo: ItemStyle,
    /** Las marcas guardadas de cada escala. Ver [MarcasDelDeslizador]. */
    marcas: Map<Deslizador, List<Float>>,
    onMarcas: (Deslizador, List<Float>) -> Unit,
    /** El color del papel. De él sale el modo noche. Ver [DrawTheme.esDeNoche]. */
    papel: String,
    onPapel2: (String) -> Unit,
    /** Traer una imagen para copiar de ella. Ver [VentanaDeReferencia]. */
    onImagenDeReferencia: () -> Unit,
    /** Poner el zoom a la escala de una hoja. Ver [Papel]. */
    onPapel: (Papel) -> Unit,
    /** Guardar lo que hay en el lienzo como una hoja del proyecto en curso. */
    onAProyecto: () -> Unit,
    /** Soltar lo que esté clavado, o null si no hay nada clavado. */
    onSoltarClavados: (() -> Unit)?,
    formatos: List<FormatoDeSalida>,
    exportando: Boolean,
    /** Qué archivo es y cuánto pesa, y lo mismo de su proyecto. Ver [Detalle]. */
    detalleDelArchivo: List<Pair<String, String>> = emptyList(),
    detalleDelProyecto: List<Pair<String, String>> = emptyList(),

    onCerrar: () -> Unit,
    modifier: Modifier = Modifier
) {
    var pestana by remember { mutableStateOf(PestanaDeAjustes.LIENZO) }
    var dx by remember { mutableFloatStateOf(0f) }
    var dy by remember { mutableFloatStateOf(0f) }

    Surface(
        shape = RoundedCornerShape(22.dp),
        shadowElevation = 12.dp,
        tonalElevation = 2.dp,
        modifier = modifier
            .offset { IntOffset(dx.roundToInt(), dy.roundToInt()) }
            .widthIn(max = 340.dp)
    ) {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 12.dp)) {
            BarraDeVentana(
                onArrastrar = { ax, ay -> dx += ax; dy += ay },
                onAmpliar = null,
                onCerrar = onCerrar
            )

            // **Las pestañas, como un mando de tres posiciones**: una sola pieza con la
            // puesta resaltada, que se lee de un vistazo como «estoy en Dibujo».
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        RoundedCornerShape(14.dp)
                    )
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                PestanaDeAjustes.entries.forEach { cual ->
                    Pestana(cual.titulo, cual == pestana, Modifier.weight(1f)) { pestana = cual }
                }
            }

            Column(
                Modifier
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                when (pestana) {
                    PestanaDeAjustes.LIENZO -> {
                        // **El papel, y con él el modo noche.** No son dos decisiones:
                        // elegir papel oscuro **es** ponerse en modo noche, y de ahí salen el
                        // filtro de la tinta y el color de la cuadrícula. Ver [DrawTheme.esDeNoche].
                        Seccion("Papel", "Con papel oscuro, la tinta y la cuadrícula se pintan para leerse encima: es el modo noche.") {
                            Segmentos(
                                DrawTheme.PAPELES.map { it.first },
                                DrawTheme.PAPELES.indexOfFirst { it.second.equals(papel, ignoreCase = true) }
                            ) { onPapel2(DrawTheme.PAPELES[it].second) }
                        }
                        Seccion("Fondo") {
                            Segmentos(
                                listOf("Liso", "Cuadros", "Puntos"),
                                when (cuadricula) {
                                    Cuadricula.NINGUNA -> 0
                                    Cuadricula.LINEAS -> 1
                                    else -> 2
                                }
                            ) {
                                onCuadricula(
                                    when (it) {
                                        0 -> Cuadricula.NINGUNA
                                        1 -> Cuadricula.LINEAS
                                        else -> Cuadricula.PUNTOS
                                    }
                                )
                            }
                        }
                        // **A la escala de una hoja.** El lienzo es infinito y eso está bien
                        // para trabajar, pero lo que se entrega es una hoja: ponerse «a A3» es
                        // ver el dibujo del tamaño al que va a salir impreso.
                        Seccion("Ver como hoja", "Pone el zoom al tamaño real de la hoja.") {
                            Segmentos(Papel.entries.map { it.etiqueta }, -1) { onPapel(Papel.entries[it]) }
                        }
                        Seccion("Vista") {
                            Interruptor("Clavar el zoom", "El pellizco solo mueve, no amplía.", zoomBloqueado, onZoomBloqueado)
                            if (referenciasVisibles != null) {
                                Fila(
                                    "Líneas de referencia",
                                    if (referenciasVisibles) "A la vista" else "Escondidas",
                                    onReferencias
                                )
                            }
                        }
                    }

                    PestanaDeAjustes.DIBUJO -> {
                        Seccion("Con qué se dibuja") {
                            // **El interruptor del lápiz.** Se enciende solo al usar el stylus;
                            // está aquí para poder **apagarlo**: sin lápiz a mano, el dedo
                            // tiene que volver a dibujar.
                            Interruptor(
                                "Solo el lápiz dibuja",
                                "El dedo mueve el lienzo. Se enciende solo al detectar un lápiz.",
                                modoLapiz, onModoLapiz
                            )
                            Interruptor("Modo dedo", "Zonas de toque más anchas.", modoDedo, onModoDedo)
                            // **Las dos maneras de traer un plano.** Con líneas se ve nítido a
                            // cualquier aumento y no hay nada que cargar al acercarse; con
                            // imagen se rasteriza por cuadros, que es lo que vale para un PDF
                            // escaneado. Ver [PlanoEnPantalla] y [ElMosaicoDelPapel].
                            if (planoEnLineas != null) {
                                Interruptor(
                                    "Plano en líneas",
                                    "Lee el PDF como geometría: nítido a cualquier aumento y sin cargar nada al acercarse. Un escaneo sigue yendo como imagen.",
                                    planoEnLineas,
                                    onPlanoEnLineas
                                )
                            }
                            // Escribir y dibujar piden cosas contrarias, y por eso es un
                            // interruptor y no una decisión de la aplicación.
                            Interruptor("Trazo firme", "Sin presión: el grosor no cambia al apretar.", presionFirme, onPresionFirme)
                        }
                        // **Los gestos, escritos donde se buscan.** No se descubren solos, y
                        // la ventana de ajustes es el sitio al que uno viene a mirar.
                        Seccion("Gestos") {
                            Gesto("2 dedos, toque", "Deshacer")
                            Gesto("3 dedos, toque", "Rehacer")
                            Gesto("4 dedos, toque", "Esconder o enseñar los mandos")
                            Gesto("1 dedo apoyado + lápiz", "Elegir color arrastrando")
                            Gesto("2 dedos apoyados + lápiz", "Borrar mientras se mantienen")
                            Gesto("Pantalla completa: botón flotante mantenido + lápiz", "Abanico: lápiz, borrador, color o figuras; llevar el lápiz y soltar")
                            Gesto("En el abanico: lápiz o figuras", "Tres lápices de fino a grueso; elipse, línea, flecha, cuadrado, rombo")
                            Gesto("En el abanico: color", "Sale la rueda del color: tocarla y soltar sobre el color")
                            Gesto("Botón flotante", "Un toque lo deja fijo hasta el siguiente toque; el asa de arriba lo mueve")
                            Gesto("2 dedos quietos mientras trazas", "Figura perfecta")
                        }
                        Seccion("Referencias") {
                            Fila("Imagen de referencia", "Abrir", onImagenDeReferencia)
                            // Solo cuando hay algo clavado: es un botón de rescate, y
                            // ofrecerlo siempre haría pensar que hace falta.
                            if (onSoltarClavados != null) Fila("Soltar lo clavado", "Soltar", onSoltarClavados)
                        }
                        // **La llave de paso de las luces.** Una para todo el dibujo: lo que
                        // uno hace con las luces de un plano es subirlas todas o apagarlas
                        // todas. Ver [LucesDelDibujo].
                        Seccion(
                            "Luces",
                            "Apagadas, la tinta de luz se pinta como tinta normal. Pasado el cien por cien se sale del blanco y el papel se apaga un poco."
                        ) {
                            Interruptor(
                                if (luces.encendidas) "Luces encendidas" else "Luces apagadas",
                                null,
                                luces.encendidas
                            ) { puestas ->
                                // Encendiéndolas con la fuerza a cero volverían apagadas y el
                                // interruptor parecería roto: se les devuelve la de fábrica.
                                onLuces(
                                    luces.copy(
                                        encendidas = puestas,
                                        fuerza = if (luces.fuerza <= 0.02) 1.0 else luces.fuerza
                                    )
                                )
                            }
                            if (luces.encendidas) LaFuerzaDeLasLuces(luces.fuerza) {
                                onLuces(luces.copy(fuerza = it))
                            }
                        }
                        // **Las marcas del grosor y de la opacidad se ponen aquí.** Un mando no
                        // tiene mango que tocar, así que guardar es esto: lo que hay puesto,
                        // con un botón. Ver [MarcasGuardadas] y [MarcasDelDeslizador].
                        Seccion("Marcas", "Los valores a los que se vuelve siempre: el mando se pega a ellos al pasar cerca.") {
                            MarcasGuardadas(
                                nombre = "Grosor",
                                ahora = fraccionDelGrosor(estilo.strokeWidth),
                                escrito = { grosorEscrito(grosorDeLaFraccion(it)) },
                                marcas = marcas[Deslizador.GROSOR].orEmpty(),
                                onMarcas = { onMarcas(Deslizador.GROSOR, it) }
                            )
                            MarcasGuardadas(
                                nombre = "Opacidad",
                                ahora = fraccionDeLaOpacidad(estilo.opacity),
                                escrito = { "${opacidadDeLaFraccion(it)} %" },
                                marcas = marcas[Deslizador.OPACIDAD].orEmpty(),
                                onMarcas = { onMarcas(Deslizador.OPACIDAD, it) }
                            )
                        }
                    }

                    PestanaDeAjustes.DETALLE -> {
                        Seccion("Este archivo") { Filas(detalleDelArchivo) }
                        Seccion("Su proyecto", if (detalleDelProyecto.isEmpty()) "No está en ningún proyecto." else null) {
                            Filas(detalleDelProyecto)
                        }
                    }

                    PestanaDeAjustes.EXPORTAR -> {
                        Seccion("Sacar el dibujo", if (exportando) "Escribiendo el archivo…" else null) {
                            // **Cuadrados grandes, a dos columnas**, con el icono arriba y
                            // el nombre debajo: lo pidió el usuario (2-sep-2026) en vez de
                            // la lista de filas, para que el icono se vea grande y el
                            // formato se reconozca de un vistazo.
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                formatos.chunked(2).forEach { tanda ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        tanda.forEach { formato ->
                                            Cuadro(
                                                formato.icono, formato.nombre,
                                                if (exportando) null else formato.al,
                                                Modifier.weight(1f)
                                            )
                                        }
                                        repeat(2 - tanda.size) { Box(Modifier.weight(1f)) }
                                    }
                                }
                            }
                        }
                        // **Llevarlo al proyecto**, que es donde acaba lo que se dibuja: en un
                        // PDF con las demás hojas.
                        Seccion("Proyecto") {
                            Fila("Guardar en un proyecto", "Añadir", onAProyecto)
                        }
                    }
                }
            }
        }
    }
}

/**
 * **Una sección: título, una línea de ayuda si hace falta, y lo suyo dentro de una
 * tarjeta.** Es lo que ordena la ventana: cada cosa con las de su clase, y el ojo va de
 * título en título sin leer nada.
 */
/** Pares nombre → valor, uno por línea. Para la pestaña de detalle. */
@Composable
private fun Filas(filas: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        filas.forEach { (que, cuanto) ->
            Row(Modifier.fillMaxWidth()) {
                Text(que, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(cuanto, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun Seccion(titulo: String, ayuda: String? = null, contenido: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f),
                RoundedCornerShape(14.dp)
            )
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Titulo(titulo)
        contenido()
        if (ayuda != null) {
            Text(
                ayuda,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

/** Un gesto y lo que hace, en una fila: la mano a la izquierda, el resultado a la derecha. */
@Composable
private fun Gesto(gesto: String, hace: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            gesto,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1.3f)
        )
        Text(
            hace,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f)
        )
    }
}

/** Varias opciones de las que solo una está puesta, como un mando de posiciones. */
@Composable
private fun Segmentos(opciones: List<String>, elegida: Int, onElegir: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        opciones.forEachIndexed { i, texto ->
            Opcion(texto, i == elegida, Modifier.weight(1f)) { onElegir(i) }
        }
    }
}

/**
 * **Las marcas de una escala: las que hay, y un botón para guardar la de ahora.**
 *
 * Una fila por escala. Cada marca es una pastilla con su valor escrito; se toca y se quita,
 * que es lo mismo que hacía volver a tocar el mango del deslizador viejo. El «+» guarda la
 * que haya puesta en ese momento, y se apaga cuando ya está guardada o cuando no caben más
 * —ver [MARCAS_POR_DESLIZADOR]: una escala llena de marcas es una escala con topes por
 * todas partes y deja de poder afinarse entre ellas—.
 *
 * Las cuentas son de [MarcasDelDeslizador] y están probadas sin dispositivo: aquí solo se
 * pintan. Lo que se puede romper de esto no es que se vea mal, es que una marca guardada
 * mande a un valor que no es el suyo.
 */
@Composable
private fun MarcasGuardadas(
    nombre: String,
    /** Dónde cae ahora mismo el valor de esta escala, de 0 a 1. */
    ahora: Float,
    /** Cómo se escribe una fracción del recorrido, para leer la pastilla. */
    escrito: (Float) -> String,
    marcas: List<Float>,
    onMarcas: (List<Float>) -> Unit
) {
    // «Ya está guardada» no es «hay una idéntica»: dos marcas a medio píxel una de otra son
    // una marca que no se puede quitar. Se pregunta con el mismo margen con el que tira el
    // imán. Ver [marcaEn].
    val yaEsta = marcaEn(marcas, ahora) != null
    val caben = marcas.size < MARCAS_POR_DESLIZADOR
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
    ) {
        Text(nombre, fontSize = 12.sp, modifier = Modifier.width(70.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.weight(1f)
        ) {
            if (marcas.isEmpty()) {
                Text(
                    "sin marcas",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            for (m in marcas) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.clickable { onMarcas(sinMarca(marcas, m)) }
                ) {
                    Text(
                        escrito(m),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
        val puede = !yaEsta && caben
        Text(
            "+ " + escrito(ahora),
            fontSize = 12.sp,
            color = if (puede) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outline,
            modifier = Modifier
                .then(
                    if (puede) Modifier.clickable { onMarcas(conMarca(marcas, ahora)) }
                    else Modifier
                )
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun Titulo(texto: String) {
    Text(
        texto.uppercase(),
        fontSize = 10.5.sp,
        letterSpacing = 1.1.sp,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 6.dp)
    )
}

@Composable
private fun Pestana(
    texto: String,
    puesta: Boolean,
    modifier: Modifier = Modifier,
    onTocar: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(11.dp),
        color = if (puesta) MaterialTheme.colorScheme.surface else Color.Transparent,
        shadowElevation = if (puesta) 2.dp else 0.dp,
        modifier = modifier.clickable { onTocar() }
    ) {
        Text(
            texto,
            fontSize = 12.5.sp,
            textAlign = TextAlign.Center,
            color = if (puesta) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        )
    }
}

@Composable
private fun Opcion(
    texto: String,
    puesta: Boolean,
    modifier: Modifier = Modifier,
    onTocar: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(9.dp),
        color = if (puesta) MaterialTheme.colorScheme.primary else Color.Transparent,
        modifier = modifier.clickable { onTocar() }
    ) {
        Text(
            texto,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            color = if (puesta) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        )
    }
}

@Composable
private fun Interruptor(texto: String, detalle: String?, puesto: Boolean, onCambio: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onCambio(!puesto) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(texto, fontSize = 13.sp)
            if (detalle != null) {
                Text(
                    detalle,
                    fontSize = 11.sp,
                    lineHeight = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(checked = puesto, onCheckedChange = onCambio)
    }
}

/**
 * Un cuadrado que se toca: el icono grande arriba y el nombre debajo, dentro.
 * Con [al] a null sale apagado.
 */
@Composable
private fun Cuadro(icono: ImageVector, texto: String, al: (() -> Unit)?, modifier: Modifier = Modifier) {
    val tinta = if (al == null) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface
    Column(
        modifier
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f), RoundedCornerShape(14.dp))
            .let { if (al == null) it else it.clickable { al() } }
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            icono,
            contentDescription = null,
            tint = if (al == null) tinta else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp)
        )
        Spacer(Modifier.height(10.dp))
        Text(
            texto,
            fontSize = 13.sp,
            color = tinta,
            textAlign = TextAlign.Center,
            maxLines = 2
        )
    }
}

/** Una fila que se toca, con lo que hace a la derecha. Con [al] a null sale apagada. */
@Composable
private fun Fila(
    texto: String,
    accion: String?,
    al: (() -> Unit)?,
    derecha: @Composable () -> Unit = {}
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
            .let { if (al == null) it else it.clickable { al() } }
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            texto,
            fontSize = 13.sp,
            color = if (al == null) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (accion != null) {
            Text(
                accion,
                fontSize = 12.sp,
                color = if (al == null) MaterialTheme.colorScheme.outline
                else MaterialTheme.colorScheme.primary
            )
        }
        derecha()
    }
}

/**
 * **Cuánto alumbran**, de apagado al doble.
 *
 * Una tira y no un mando de los del lateral: esto se toca de tarde en tarde —se sube al
 * enseñar el dibujo, se baja al trabajar— y está dentro de un panel que ya se ha abierto a
 * propósito, así que aquí lo que hace falta es verla entera de un vistazo y no un gesto corto.
 *
 * El color de la tira dice lo que va a pasar: de apagado, al color de una luz, al blanco. Y el
 * cien por cien va marcado, porque es la frontera: hasta ahí la luz **se enciende**, y de ahí
 * para arriba lo que sube es cuánto se sale del blanco de la pantalla.
 */
@Composable
private fun LaFuerzaDeLasLuces(fuerza: Double, onFuerza: (Double) -> Unit) {
    val tinta = MaterialTheme.colorScheme.onSurfaceVariant
    val cambiar by rememberUpdatedState(onFuerza)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(
            Modifier
                .weight(1f)
                .height(28.dp)
                .pointerInput(Unit) {
                    fun senalar(x: Float) {
                        cambiar(
                            (x / size.width.toFloat()).coerceIn(0f, 1f).toDouble() *
                                LucesDelDibujo.LO_MAS_QUE_ALUMBRAN
                        )
                    }
                    awaitEachGesture {
                        val abajo = awaitFirstDown()
                        senalar(abajo.position.x)
                        abajo.consume()
                        while (true) {
                            val evento = awaitPointerEvent()
                            val dedo = evento.changes.firstOrNull { it.pressed } ?: break
                            senalar(dedo.position.x)
                            dedo.consume()
                        }
                    }
                }
        ) {
            val alto = size.height * 0.42f
            val arriba = (size.height - alto) / 2
            drawRoundRect(
                Brush.horizontalGradient(
                    listOf(tinta.copy(alpha = 0.25f), Color(0xFFFFC857), Color.White)
                ),
                topLeft = Offset(0f, arriba),
                size = Size(size.width, alto),
                cornerRadius = CornerRadius(alto / 2)
            )
            // La marca del cien por cien: hasta ahí se enciende, de ahí para arriba se sale.
            val medio = size.width / 2
            drawLine(
                tinta.copy(alpha = 0.55f),
                Offset(medio, arriba - 3f), Offset(medio, arriba + alto + 3f),
                strokeWidth = 1.5f
            )
            val donde = (fuerza / LucesDelDibujo.LO_MAS_QUE_ALUMBRAN).toFloat()
                .coerceIn(0f, 1f) * size.width
            val x = donde.coerceIn(alto / 2, size.width - alto / 2)
            drawCircle(Color.White, radius = alto * 0.62f, center = Offset(x, size.height / 2))
            drawCircle(
                Color.Black.copy(alpha = 0.35f),
                radius = alto * 0.62f, center = Offset(x, size.height / 2),
                style = Stroke(1f)
            )
        }
        Text(
            "${Math.round(fuerza * 100)} %",
            fontSize = 12.sp,
            color = tinta,
            modifier = Modifier.padding(start = 8.dp).widthIn(min = 44.dp),
            textAlign = TextAlign.End
        )
    }
}

/**
 * **Qué lleva la página web, justo antes de compartirla.**
 *
 * Sale al tocar «Página web» en la pestaña de exportar: las funciones marcadas son las que
 * viajan, y lo que no se marca **no está** en el archivo —ni el botón, ni el atajo, ni el
 * lápiz aunque se conecte uno—. Con todo lo de dibujar apagado, lo que se manda es una lámina
 * de solo mirar, como compartir un documento sin permiso de edición. Se recuerda para la
 * próxima vez. Lo pidió el usuario (5-sep-2026) en vez de tenerlo como ajuste aparte.
 */
@Composable
fun DialogoDeFuncionesWeb(
    marcadas: Set<String>,
    onCambio: (String, Boolean) -> Unit,
    onCompartir: () -> Unit,
    onCerrar: () -> Unit
) {
    val nombres = listOf(
        "lapiz" to "Lápiz", "resaltador" to "Resaltador", "borrador" to "Borrador",
        "deshacer" to "Deshacer y rehacer", "medir" to "Medir", "capas" to "Capas del plano",
        "paginas" to "Pasar de página", "guardar" to "Guardar", "compartir" to "Compartir"
    )
    val soloMirar = marcadas.none { it == "lapiz" || it == "resaltador" || it == "borrador" }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onCerrar,
        title = { Text("Página web") },
        text = {
            Column {
                Text(
                    if (soloMirar) "Solo para mirar: quien la abra no podrá dibujar encima."
                    else "Quien la abra podrá usar lo que marques.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                for ((clave, texto) in nombres) {
                    Interruptor(texto, null, clave in marcadas) { onCambio(clave, it) }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onCompartir) { Text("Compartir") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onCerrar) { Text("Cancelar") }
        }
    )
}
