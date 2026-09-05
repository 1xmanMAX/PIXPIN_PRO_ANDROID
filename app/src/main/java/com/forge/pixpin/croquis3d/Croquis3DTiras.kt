package com.forge.pixpin.croquis3d

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forge.pixpin.R
import com.forge.pixpin.motor.LaRuedaDelColor
import com.forge.pixpin.motor.Pt
import com.forge.pixpin.motor.Pt3
import com.forge.pixpin.motor.deHsv
import com.forge.pixpin.motor.enHsv
import com.forge.pixpin.motor.enTexto
import com.forge.pixpin.motor.parseColor

/**
 * **El taller de la tinta**: se dibuja a la izquierda y se prueba a la derecha.
 *
 * ## Dos mitades y nada más
 *
 * Tenía seis mandos y cinco cortes de fábrica, y eso no es un taller: es un formulario. Lo
 * que hace falta para inventarse una tinta son dos cosas, y son las dos que hay:
 *
 * - **A la izquierda se dibuja la punta.** Lo que se trace ahí —una curva, una cuña, una
 *   gota— **es** la tinta: es la figura que se va a barrer a lo largo del trazo. Con su
 *   borrador para volver a empezar y su color, que es el de la tinta.
 * - **A la derecha se prueba.** No es un icono ni una imitación: es el croquis pintando una
 *   ese con esa tinta, con el mismo código que pinta el dibujo, así que lo que se ve ahí es
 *   exactamente lo que va a salir.
 *
 * Debajo, lo único que se ajusta de una tinta después de tenerla: **lo gorda y lo que
 * tapa**. Y el botón de guardarla, que la manda a la galería con su color y su grueso — una
 * tinta no es una forma suelta, es esa forma con ese color y ese grueso.
 */
@Composable
fun Croquis3DPunta(controlador: Croquis3DControlador, modifier: Modifier = Modifier) {
    var eligiendoColor by remember { mutableStateOf(false) }
    val tinta = Color(parseColor(controlador.color, 255))
    val apagado = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
    // **Los mandos no se tiñen con la tinta.** El grueso y lo sólido son cuánto hay de algo,
    // no de qué color se pinta, y con una tinta clara sus tiras se borraban sobre el panel.
    // El color se enseña donde toca: en la pastilla del color y en las muestras del material.
    // Lo pidió el usuario (3-sep-2026), y lo mismo se hizo en el lateral del lienzo plano.
    val mando = MaterialTheme.colorScheme.onSurface

    Surface(
        modifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 3.dp
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row {
                Box(Modifier.weight(1f)) {
                    LaPuntaQueSeDibuja(controlador, tinta)
                    Row(Modifier.padding(4.dp)) {
                        // Deshacer el último rasgo antes que vaciarlo todo: es lo que se pide
                        // nueve de cada diez veces, y sin ello arreglar el tercer rasgo
                        // costaba volver a dibujar los tres.
                        Chapa("↶") { controlador.deshacerEnLaPunta() }
                        Chapa("⌫") { controlador.borrarLaMarca() }
                        // **Maciza o a rasgos.** Lo que se traza aquí se guarda tal cual, sin
                        // cerrar: eso es lo que deja hacer una tinta de dos rayas cruzadas.
                        // Pero quien dibuja un círculo lo quiere relleno, y sin esto le salía
                        // el contorno hueco sin forma de rellenarlo. Ver
                        // [Croquis3DControlador.rellenarLaPunta].
                        if (controlador.hayPuntaDibujada) {
                            Chapa(if (controlador.punta.tienePerfil) "●" else "○") {
                                controlador.rellenarLaPunta(!controlador.punta.tienePerfil)
                            }
                        }
                        Box(
                            Modifier
                                .padding(start = 4.dp)
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(tinta)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                                .clickable { eligiendoColor = !eligiendoColor }
                        )
                    }
                }
                LaMuestra(controlador, Modifier.weight(1f).padding(start = 8.dp))
            }

            if (eligiendoColor) LaRuedaYSusDosColores(controlador, Modifier.padding(top = 8.dp))

            // **El material va con el color, y no con la punta.**
            //
            // Es la otra mitad de la misma decisión: uno no elige «una punta redonda» y
            // luego «que alumbre», elige **una tinta de luz**. Y aquí abajo, pegado a la
            // rueda, se ve lo que hace sin abrir nada: cada chapa enseña un trazo con ese
            // material y con el color que hay puesto. Ver [MaterialDeLaTinta].
            ElMaterial(controlador)

            Tira(
                nombre = stringResource(R.string.croquis_punta_ancho),
                colores = listOf(apagado, mando),
                valor = ((controlador.grosor - GROSOR_MINIMO) / (GROSOR_MAXIMO - GROSOR_MINIMO))
                    .toFloat(),
                alEmpezar = controlador::empezarARetocar
            ) { cuanto ->
                val gordo = GROSOR_MINIMO + cuanto * (GROSOR_MAXIMO - GROSOR_MINIMO)
                controlador.grosor = gordo
                controlador.engrosarLaSeleccion(gordo)
            }
            Tira(
                nombre = stringResource(R.string.croquis_punta_solidez),
                // Lo sólido sí se enseña con la propia tira, pero en el color del mando: lo
                // que dice es cuánto tapa, y eso se lee igual sea la tinta la que sea.
                colores = listOf(mando.copy(alpha = 0f), mando),
                valor = controlador.opacidad.toFloat(),
                alEmpezar = controlador::empezarARetocar
            ) { cuanto ->
                val cuanta = cuanto.toDouble().coerceAtLeast(0.06)
                controlador.opacidad = cuanta
                controlador.transparentarLaSeleccion(cuanta)
            }


            LaGaleriaDeTintas(controlador)
        }
    }
}

/**
 * **Una sola rueda de color, y dos pastillas cuando la tinta tiene dos colores.**
 *
 * Una tinta de luz tiene dos: el del gas encendido y el del cristal apagado. Eso se resolvía
 * poniendo **una segunda rueda** debajo, y estaba mal por lo evidente: en cuanto se elegía el
 * material de luz aparecían dos paletas iguales, una encima de otra, y no había forma de
 * saber cuál era cuál sin leer la letra pequeña. Dos ruedas para elegir un color es una
 * rueda de más.
 *
 * Ahora la rueda es una y las dos pastillas dicen **a cuál de los dos colores está
 * apuntando**. Con cualquier otro material no salen: no hay dos colores que elegir.
 */
@Composable
private fun LaRuedaYSusDosColores(
    controlador: Croquis3DControlador,
    modifier: Modifier = Modifier
) {
    val dosColores = controlador.pincel == Pincel.LUZ || controlador.punta.alumbra
    var laApagada by remember { mutableStateOf(false) }
    // Al dejar de ser una luz, la rueda vuelve al color de siempre: quedarse apuntando al
    // del tubo apagado dejaría la rueda tocando algo que ya no se pinta.
    if (!dosColores && laApagada) laApagada = false

    Column(modifier) {
        if (dosColores) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                UnaPastillaDeColor(
                    color = controlador.color,
                    nombre = stringResource(R.string.croquis_material_luz),
                    puesta = !laApagada
                ) { laApagada = false }
                UnaPastillaDeColor(
                    color = controlador.colorApagada ?: controlador.color,
                    nombre = stringResource(R.string.croquis_punta_apagada),
                    puesta = laApagada
                ) { laApagada = true }
            }
        }
        RuedaDeColor(
            if (laApagada) controlador.colorApagada ?: controlador.color else controlador.color,
            Modifier.padding(top = 6.dp),
            controlador.croquis.favoritos,
            alGuardar = controlador::recordarElColor
        ) {
            // **Elegir un color no lo guarda: lo guarda el más.**
            //
            // Se apuntaba solo cada tono por el que pasaba el dedo por el aro, así que la
            // fila de al lado se llenaba en dos segundos con los diez tonos de un barrido
            // —ninguno de los cuales era el que uno buscaba— y encima echaba de ahí a los
            // que sí. Con el más, lo que hay guardado es lo que alguien ha decidido guardar.
            if (laApagada) {
                controlador.colorApagada = it
                controlador.apagarLaSeleccionDe(it)
            } else {
                controlador.color = it
                controlador.pintarLaSeleccion(it)
            }
        }
    }
}

/** Una de las dos pastillas: su color, su nombre y si es la que manda la rueda. */
@Composable
private fun UnaPastillaDeColor(
    color: String,
    nombre: String,
    puesta: Boolean,
    alTocar: () -> Unit
) {
    Row(
        Modifier
            .padding(end = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (puesta) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                else Color.Transparent
            )
            .clickable { alTocar() }
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(Color(parseColor(color, 255)))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
        )
        Text(
            nombre,
            Modifier.padding(start = 6.dp),
            style = MaterialTheme.typography.labelSmall,
            color =
                if (puesta) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * **El taller del color, a secas**: la rueda, sus guardados y el material.
 *
 * Lo mismo que enseña el taller de la punta, pero suelto y sin nada más. Abrir la
 * configuración entera para cambiar de color era pedir cuatro toques —abrir, buscar la
 * pestaña, abrir la rueda, elegir— para lo que se hace treinta veces mientras se dibuja.
 * Ver [Croquis3DActivity].
 */
@Composable
fun Croquis3DColor(controlador: Croquis3DControlador, modifier: Modifier = Modifier) {
    Surface(
        modifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 3.dp
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            LaRuedaYSusDosColores(controlador)
            ElMaterial(controlador)
        }
    }
}

/**
 * **El material de la tinta**: lisa, encendida o con grano.
 *
 * Cinco chapas y cada una **enseña lo que hace**, con el color que hay puesto: nadie
 * distingue «rayada» de «cruzada» leyendo las dos palabras, y las distingue en cuanto ve los
 * dos trazos uno al lado del otro. Es la misma razón por la que la punta se elige viendo su
 * muestra y no su nombre.
 *
 * Puestas aquí, debajo del color, porque es la misma decisión: de qué va a ser el trazo. Ver
 * [MaterialDeLaTinta].
 */
@Composable
private fun ElMaterial(controlador: Croquis3DControlador) {
    val tinta = Color(parseColor(controlador.color, 255))
    val puesto = MaterialDeLaTinta.de(controlador.punta)
    Column(Modifier.padding(top = 10.dp)) {
        Text(
            stringResource(R.string.croquis_material),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            Modifier
                .padding(top = 4.dp)
                .horizontalScroll(rememberScrollState())
        ) {
            for (cual in MaterialDeLaTinta.entries) {
                val esEste = cual == puesto
                Column(
                    Modifier
                        .padding(end = 6.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (esEste) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                            else Color.Transparent
                        )
                        .clickable {
                            controlador.empezarARetocar()
                            val nueva = cual.puestoEn(controlador.punta)
                            controlador.punta = nueva
                            controlador.empuntarLaSeleccion(nueva)
                        }
                        .padding(horizontal = 6.dp, vertical = 5.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Canvas(Modifier.size(width = 44.dp, height = 20.dp)) {
                        unMaterial(cual, tinta)
                    }
                    Text(
                        stringResource(comoSeLlamaElMaterial(cual)),
                        style = MaterialTheme.typography.labelSmall,
                        color =
                            if (esEste) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // **El brillo no vive aquí.**
        //
        // Estuvo pegado a la chapa del material, con la idea de que el mando estuviera al lado
        // de donde se enciende la luz. Pero elegir de qué está hecha una tinta y graduar
        // cuánto alumbra el dibujo son dos preguntas distintas, y juntas convertían el taller
        // del color en un sitio donde además se regula la luz: cada vez que uno venía a
        // cambiar de tinta se encontraba un mando de brillo que no había venido a tocar.
        //
        // Queda en un solo sitio: la llave de paso de las luces, que es del dibujo entero
        // —como las de una habitación— y vive con los demás ajustes. Ver [LucesDelDibujo].
    }
}

/** Cómo se llama cada material en la pantalla. */
private fun comoSeLlamaElMaterial(cual: MaterialDeLaTinta): Int = when (cual) {
    MaterialDeLaTinta.LISO -> R.string.croquis_material_liso
    MaterialDeLaTinta.LAPIZ -> R.string.croquis_material_lapiz
    MaterialDeLaTinta.LUZ -> R.string.croquis_material_luz
    MaterialDeLaTinta.RAYADO -> R.string.croquis_material_rayado
    MaterialDeLaTinta.CRUZADO -> R.string.croquis_material_cruzado
    MaterialDeLaTinta.PUNTOS -> R.string.croquis_material_puntos
}

/**
 * Un trazo de muestra hecho de ese material.
 *
 * No pasa por el motor de pintado —una muestra de veinte píxeles no necesita el barrido del
 * corte— pero sí dice lo mismo que él: la luz se apaga hacia fuera y tira a blanco por el
 * medio, y el grano va **de través a por donde va la raya** y recortado a la banda. Ver
 * [pintarLaTrama].
 */
private fun DrawScope.unMaterial(cual: MaterialDeLaTinta, tinta: Color) {
    val medio = size.height / 2
    val gordo = size.height * 0.62f
    val izquierda = Offset(size.height * 0.32f, medio)
    val derecha = Offset(size.width - size.height * 0.32f, medio)
    if (cual == MaterialDeLaTinta.LUZ) {
        // De fuera adentro: el resplandor, el cuerpo y el filamento casi blanco.
        drawLine(tinta.copy(alpha = 0.22f), izquierda, derecha, gordo * 1.9f, StrokeCap.Round)
        drawLine(tinta.copy(alpha = 0.5f), izquierda, derecha, gordo, StrokeCap.Round)
        drawLine(Color.White, izquierda, derecha, gordo * 0.3f, StrokeCap.Round)
        return
    }
    // **El lápiz se enseña como lo que es: una raya fina y sin bulto.** Con el mismo grueso
    // que el liso no había forma de distinguirlos en la fila, y dos chapas iguales que hacen
    // cosas distintas es peor que no tener la segunda.
    if (cual == MaterialDeLaTinta.LAPIZ) {
        drawLine(tinta, izquierda, derecha, gordo * 0.34f, StrokeCap.Round)
        return
    }
    drawLine(tinta, izquierda, derecha, gordo, StrokeCap.Round)
    if (cual == MaterialDeLaTinta.LISO) return

    val grano = Color(
        android.graphics.Color.argb(
            255,
            (tinta.red * 255 * (1 - 0.42f)).toInt(),
            (tinta.green * 255 * (1 - 0.42f)).toInt(),
            (tinta.blue * 255 * (1 - 0.42f)).toInt()
        )
    )
    val paso = gordo * 0.75f
    val pelo = (gordo * 0.13f).coerceAtLeast(1f)
    clipPath(Path().apply {
        addRoundRect(
            androidx.compose.ui.geometry.RoundRect(
                left = izquierda.x - gordo / 2,
                top = medio - gordo / 2,
                right = derecha.x + gordo / 2,
                bottom = medio + gordo / 2,
                radiusX = gordo / 2,
                radiusY = gordo / 2
            )
        )
    }) {
        var x = izquierda.x
        var cual2 = 0
        while (x <= derecha.x) {
            if (cual == MaterialDeLaTinta.PUNTOS) {
                val ladeo = if (cual2 % 2 == 0) gordo * 0.22f else -gordo * 0.22f
                drawCircle(grano, radius = pelo * 1.5f, center = Offset(x, medio + ladeo))
            } else {
                drawLine(
                    grano,
                    Offset(x, medio - gordo),
                    Offset(x, medio + gordo),
                    strokeWidth = pelo
                )
            }
            x += paso
            cual2++
        }
        if (cual == MaterialDeLaTinta.CRUZADO) {
            for (lado in intArrayOf(-1, 1)) {
                val y = medio + gordo * 0.27f * lado
                drawLine(grano, Offset(izquierda.x, y), Offset(derecha.x, y), strokeWidth = pelo)
            }
        }
    }
}

/**
 * **El lienzo donde se dibuja la tinta**: con lápiz, y lo que salga es la tinta.
 *
 * Se traza con el lápiz de siempre y **se guarda tal cual**: no se cierra la figura ni se
 * rellena. Cerrarla convertía cualquier rasgo interesante —una curva con un pelo al lado,
 * dos rayas cruzadas— en una mancha, que es justo lo contrario de lo que uno estaba
 * dibujando.
 *
 * Se puede trazar **varias veces y con varios colores**: cada rasgo se queda con el color
 * que hubiera puesto, así que una tinta puede tener tres. Y no se recoloca nada: dos rasgos
 * dibujados uno al lado del otro siguen estando uno al lado del otro.
 */
@Composable
private fun LaPuntaQueSeDibuja(controlador: Croquis3DControlador, tinta: Color) {
    var trazando by remember { mutableStateOf<List<Offset>>(emptyList()) }
    val marca = controlador.punta.dibujo.orEmpty()
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(ALTO_DEL_TALLER.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .pointerInput(controlador.color) {
                awaitEachGesture {
                    val abajo = awaitFirstDown()
                    val puntos = ArrayList<Offset>()
                    puntos += abajo.position
                    trazando = puntos.toList()
                    abajo.consume()
                    while (true) {
                        val evento = awaitPointerEvent()
                        val dedo = evento.changes.firstOrNull { it.pressed } ?: break
                        // **Con las muestras que pasaron entre fotograma y fotograma.**
                        //
                        // Aquí se nota más que en ningún otro sitio: el taller es un
                        // recuadro de cuatro dedos y lo que se traza en él va a ser la punta
                        // de todos los trazos que vengan. Quedándose con una muestra por
                        // fotograma, un círculo trazado con cuidado llegaba con ocho o diez
                        // puntos —o sea, un polígono—, y por eso «lo dibujo redondo y me lo
                        // deja cuadrado». Leyendo también las que Android guarda sin
                        // enseñar, llega lo que se hizo con la mano.
                        for (antes in dedo.historical) puntos += antes.position
                        puntos += dedo.position
                        trazando = puntos.toList()
                        dedo.consume()
                    }
                    trazando = emptyList()
                    // **Un toque suelto es un punto, y un punto vale.** Se tiraba por
                    // «corto», así que en el taller no había forma de poner un punto —que es
                    // la marca más sencilla que se le puede pedir a una punta—.
                    if (puntos.isNotEmpty()) {
                        controlador.dibujarEnLaPunta(
                            enElCuadro(puntos, size.width.toFloat(), size.height.toFloat()),
                            controlador.color
                        )
                    }
                }
            }
    ) {
        val medio = Offset(size.width / 2, size.height / 2)
        val radio = minOf(size.width, size.height) / 2

        // **Dónde se está dibujando de verdad.**
        //
        // La punta se guarda dentro de un cuadrado, pero el recuadro del taller es ancho y
        // bajo: lo que caía a los lados **se salía del cuadrado** y aparecía en la tinta más
        // lejos de lo que uno lo había puesto, o no aparecía. Sin nada dibujado no había forma
        // de saberlo. Con el cuadrado marcado se ve el sitio que hay, y el círculo de dentro
        // dice lo que deja una punta redonda de ese tamaño, que es contra lo que uno compara
        // sin pensarlo.
        val guia = tinta.copy(alpha = 0.10f)
        drawRect(
            guia,
            topLeft = Offset(medio.x - radio, medio.y - radio),
            size = androidx.compose.ui.geometry.Size(radio * 2, radio * 2),
            style = Stroke(width = 1f)
        )
        drawCircle(guia, radius = radio, center = medio, style = Stroke(width = 1f))
        drawLine(guia, Offset(medio.x, medio.y - radio), Offset(medio.x, medio.y + radio), 1f)
        drawLine(guia, Offset(medio.x - radio, medio.y), Offset(medio.x + radio, medio.y), 1f)

        fun aLaPantalla(p: Pt) = Offset(
            medio.x + (p.x * radio).toFloat(),
            medio.y + (p.y * radio).toFloat()
        )
        for (rasgo in marca) {
            val gordo = (rasgo.grosor * radio).toFloat().coerceAtLeast(1f)
            val tono = Color(parseColor(rasgo.color, 255))
            if (rasgo.puntos.size < 2) {
                val c = rasgo.puntos.firstOrNull() ?: continue
                drawCircle(tono, radius = gordo / 2, center = aLaPantalla(c))
                continue
            }
            drawPath(
                caminoSuave(rasgo.puntos.map { aLaPantalla(it) }), tono,
                style = Stroke(width = gordo, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }
        if (trazando.size >= 2) {
            drawPath(
                caminoSuave(trazando), tinta.copy(alpha = 0.7f),
                style = Stroke(
                    width = (GORDO_DEL_RASGO * radio).toFloat(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
        } else if (trazando.size == 1) {
            drawCircle(
                tinta.copy(alpha = 0.7f),
                radius = (GORDO_DEL_RASGO * radio).toFloat() / 2,
                center = trazando.first()
            )
        }
    }
}

/**
 * Una ristra de puntos, dibujada **como una curva y no como una escalera**.
 *
 * Cuadráticas por los puntos medios, que es el trazado de toda la vida para que unos puntos
 * sueltos salgan suaves. Uniéndolos con rectas se ve cada esquinita, y en un recuadro de
 * cuatro dedos —donde lo que se dibuja es la punta de todos los trazos que vengan— eso es la
 * diferencia entre un círculo y un polígono.
 */
private fun caminoSuave(en: List<Offset>): Path {
    val camino = Path()
    if (en.isEmpty()) return camino
    camino.moveTo(en[0].x, en[0].y)
    if (en.size == 1) return camino
    for (i in 1 until en.size - 1) {
        val a = en[i]
        val b = en[i + 1]
        camino.quadraticTo(a.x, a.y, (a.x + b.x) / 2, (a.y + b.y) / 2)
    }
    camino.lineTo(en[en.size - 1].x, en[en.size - 1].y)
    return camino
}

/**
 * Lo trazado, llevado al cuadrado de lado dos **sin recolocarlo**.
 *
 * Se mide contra el propio recuadro y no contra lo dibujado: así dos rasgos trazados uno al
 * lado del otro siguen estando uno al lado del otro, que es lo que uno acaba de dibujar. El
 * tamaño no se guarda —lo pone el grueso del trazo—, solo dónde cae cada cosa dentro del
 * cuadro.
 */
private fun enElCuadro(puntos: List<Offset>, ancho: Float, alto: Float): List<Pt> {
    val radio = minOf(ancho, alto) / 2
    // **Se reparten por distancia recorrida, no de tantos en tantos.**
    //
    // Cogiendo uno de cada N se pierden justo los puntos donde la mano iba despacio —o sea,
    // las curvas—: un círculo trazado con cuidado salía convertido en un cuadrado en cuanto
    // se levantaba el lápiz. Repartiendo por recorrido, las curvas se llevan tantos puntos
    // como necesitan y las rectas ninguno de más.
    val pocos = repartidos(puntos, PUNTOS_DEL_RASGO)
    return pocos.map {
        Pt(((it.x - ancho / 2) / radio).toDouble(), ((it.y - alto / 2) / radio).toDouble())
    }
}

/** El mismo recorrido con [cuantos] puntos como mucho, repartidos por distancia. */
private fun repartidos(puntos: List<Offset>, cuantos: Int): List<Offset> {
    if (puntos.size <= 2 || puntos.size <= cuantos) return puntos
    val tramos = puntos.zipWithNext().map { (a, b) -> (b - a).getDistance().toDouble() }
    val total = tramos.sum()
    if (total < 1e-9) return listOf(puntos.first())
    val salida = ArrayList<Offset>(cuantos)
    var tramo = 0
    var llevado = 0.0
    for (k in 0 until cuantos) {
        val meta = total * k / (cuantos - 1)
        while (tramo < tramos.size - 1 && llevado + tramos[tramo] < meta) {
            llevado += tramos[tramo]; tramo++
        }
        val dentro = if (tramos[tramo] < 1e-12) 0f else ((meta - llevado) / tramos[tramo]).toFloat()
        salida += puntos[tramo] + (puntos[tramo + 1] - puntos[tramo]) * dentro
    }
    return salida
}

/**
 * **La galería de tintas**: las guardadas, en una fila.
 *
 * Cada una se pinta con su propia tinta, así que se reconocen sin nombre. Tocar una la pone;
 * el botón de la izquierda guarda la que hay ahora mismo.
 */
@Composable
private fun LaGaleriaDeTintas(controlador: Croquis3DControlador) {
    Row(
        Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Chapa("+") { controlador.guardarLaPunta() }
        for (guardada in controlador.lasTintas) {
            Box(
                Modifier
                    .padding(start = 6.dp)
                    .size(width = 54.dp, height = 34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable { controlador.usarLaPunta(guardada.id) }
            ) {
                Canvas(Modifier.fillMaxWidth().height(34.dp)) {
                    elOriginador(guardada.punta, guardada.pincel, guardada.color, guardada.opacidad)
                }
                // Las de fábrica no llevan aspa: no se tiran.
                if (!guardada.deFabrica) Text(
                    "✕",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .clickable { controlador.tirarLaPunta(guardada.id) }
                        .padding(horizontal = 4.dp)
                )
            }
        }
    }
}

/**
 * **El originador de una tinta: su corte, de cara.**
 *
 * La galería enseñaba un trazo de muestra de cada tinta, y así **todas se veían iguales**:
 * un trazo es una raya, y una raya redonda y una cuadrada del mismo color son dos rayas.
 * Lo que distingue de verdad a una tinta de otra es **por dónde toca el papel** —el círculo,
 * el cuadrado, el rectángulo del rodillo, o la figura que uno se haya dibujado con sus
 * colores—, y eso se ve de una vez y sin leer nada.
 *
 * Se pinta lo mismo que se barre al trazar: la sección de la punta, con su alargamiento y
 * su giro puestos. No es una ilustración de la tinta, es la tinta mirada de frente.
 */
private fun DrawScope.elOriginador(
    punta: PuntaDelPincel,
    pincel: Pincel,
    color: String,
    opacidad: Double
) {
    val medio = Offset(size.width / 2, size.height / 2)
    val radio = minOf(size.width, size.height) * RADIO_DEL_ORIGINADOR
    val tapa = (opacidad.coerceIn(0.0, 1.0) * 255).toInt()
    val tono = Color(parseColor(color, tapa))
    val marca = punta.dibujo

    // La figura dibujada a mano manda, y va **con los colores que se le pusieron**: es
    // justamente lo que la hace reconocible entre las demás.
    if (!marca.isNullOrEmpty()) {
        for (rasgo in marca) {
            val gordo = (rasgo.grosor * radio).toFloat().coerceAtLeast(2f)
            val tono = Color(parseColor(rasgo.color, tapa))
            val en = rasgo.puntos.map {
                Offset(medio.x + (it.x * radio).toFloat(), medio.y + (it.y * radio).toFloat())
            }
            if (en.size < 2) {
                drawCircle(tono, radius = gordo / 2, center = en.firstOrNull() ?: medio)
                continue
            }
            drawPath(
                caminoSuave(en), tono,
                style = Stroke(width = gordo, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }
        return
    }

    // Y si no, el corte: el suyo si lo tiene, y si no el que le toca a su tinta.
    val corte = punta.perfil ?: SeccionDePunta.deLaTinta(pincel).puntos()
    if (corte.size < 3) return
    val cos = kotlin.math.cos(punta.angulo)
    val sen = kotlin.math.sin(punta.angulo)
    val camino = Path()
    corte.forEachIndexed { i, pt ->
        // El alargamiento va en su eje y luego se gira: es el mismo orden en que se barre.
        val ax = pt.x * punta.ancho
        val ay = pt.y * punta.ancho * punta.largo
        val x = medio.x + ((ax * cos - ay * sen) * radio).toFloat()
        val y = medio.y + ((ax * sen + ay * cos) * radio).toFloat()
        if (i == 0) camino.moveTo(x, y) else camino.lineTo(x, y)
    }
    camino.close()

    // La de luz se enseña encendida: alrededor tiene su resplandor, que es lo suyo.
    if (pincel.vigente == Pincel.LUZ || punta.alumbra) {
        drawCircle(tono.copy(alpha = 0.22f), radio * 1.7f, medio)
        drawCircle(tono.copy(alpha = 0.35f), radio * 1.2f, medio)
    }
    drawPath(camino, tono)
}

/** Cuántos colores de uno se enseñan al lado del aro, y en cuántas columnas. */
private const val COLORES_A_MANO = 12
private const val COLUMNAS_DE_COLORES = 3

/** Lo que ocupa el corte en su recuadro. Ni pegado a los bordes ni perdido en el medio. */
private const val RADIO_DEL_ORIGINADOR = 0.30f

/** Un botón pequeño de los del taller, con su glifo. */
@Composable
private fun Chapa(glifo: String, alTocar: () -> Unit) {
    Box(
        Modifier
            .size(26.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f))
            .clickable(onClick = alTocar),
        contentAlignment = Alignment.Center
    ) {
        Text(glifo, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * **La prueba, en tres dimensiones y con la tinta de verdad.**
 *
 * Una ese tumbada en el suelo, mirada desde arriba y de lado: enseña de una vez el tramo
 * recto, el curvo, las dos puntas y **cómo se escorza** —que es la mitad de lo que hay que
 * saber de una tinta en el espacio—. Y se pinta con [pintarTrazo], el mismo que pinta el
 * croquis: no es una imitación del pincel, es el pincel.
 */
@Composable
private fun LaMuestra(controlador: Croquis3DControlador, modifier: Modifier = Modifier) {
    val fondo = controlador.croquis.colorDelFondo
        ?.let { Color(parseColor(it, 255)) }
        ?: MaterialTheme.colorScheme.surface
    Canvas(
        modifier
            .fillMaxWidth()
            .height(ALTO_DEL_TALLER.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(fondo)
    ) {
        unaMuestra(
            controlador.color,
            controlador.grosorDeVerdad,
            controlador.pincel,
            controlador.opacidad,
            controlador.punta,
            // **Y con la luz que lleve puesta.** Sin esto la muestra se pintaba siempre a
            // tope —es lo que trae de fábrica el pintado— así que el mando del brillo se
            // movía de cero a cien y aquí no cambiaba nada: el único sitio donde se puede
            // ver lo que hace el mando **antes** de trazar, y era el que no le hacía caso.
            controlador.luz,
            size.width.toDouble(),
            size.height.toDouble()
        )
    }
}

/**
 * La ese de muestra, pintada con una tinta cualquiera.
 *
 * Aparte para que la use también la galería: cada tinta guardada se pinta con la suya, así
 * que se reconocen sin nombres ni etiquetas.
 */
private fun DrawScope.unaMuestra(
    color: String,
    grosor: Double,
    pincel: Pincel,
    opacidad: Double,
    punta: PuntaDelPincel,
    /** Cuánto alumbra, si el material es de luz. Ver [Trazo3D.luz]. */
    luz: Double,
    ancho: Double,
    alto: Double
) {
    val cuantos = 40
    val largo = ancho * 0.72
    val hondo = alto * 0.9
    val puntos = (0..cuantos).map { i ->
        val t = i.toDouble() / cuantos
        Pt3(-largo / 2 + largo * t, kotlin.math.sin(t * Math.PI * 2) * hondo, 0.0)
    }
    val presiones = (0..cuantos).map { 0.35 + 0.65 * kotlin.math.sin(it.toDouble() / cuantos * Math.PI) }
    pintarTrazo(
        puntos, color, grosor, pincel,
        // Una cámara de mentira, mirando desde arriba y de lado: es lo que hace que la
        // muestra se vea en el espacio y no como una raya en un papel.
        Camara3D(giro = -0.55, inclinacion = 0.75, zoom = 1.0),
        ancho, alto,
        calibre = grosor,
        presiones = presiones,
        opacidad = opacidad,
        luz = luz,
        punta = punta
    )
}

/**
 * **La rueda de color**: el tono en el anillo y lo claro que va, en la tira.
 *
 * Un anillo y cuatro tintas de siempre, y ya. Había tres deslizadores —tono, viveza y luz—
 * que es la forma de elegir un color de quien ya sabe qué es cada uno; en un anillo se
 * señala el color, que es como lo elige cualquiera. Y las cuatro de al lado son las que se
 * usan de verdad: la de dibujar, su contraria y dos para marcar.
 */
@Composable
internal fun RuedaDeColor(
    color: String,
    modifier: Modifier = Modifier,
    /** Los colores guardados. Vacío, las cuatro de siempre. */
    favoritos: List<String> = emptyList(),
    /** Qué hacer al tocar el más. Sin esto, no sale el botón. */
    alGuardar: ((String) -> Unit)? = null,
    alElegir: (String) -> Unit
) {
    val hsv = enHsv(parseColor(color, 255))
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // **La misma rueda que sale al arrastrar el mando de la barra**, y aquí además
            // se toca. Antes era otra —un aro de tonos con el color en el medio— y eso son
            // dos ruedas que aprender por separado para la misma decisión: la que uno se
            // sabe del gesto rápido no le servía al abrir el taller. Ver [LaRuedaDelColor].
            LaRuedaDelColor(
                tono = { hsv[0] },
                viveza = { hsv[1] },
                marcas = favoritos,
                claridad = hsv[2],
                lado = ANILLO.dp,
                alElegir = { tono, viveza ->
                    // Lo claro que va no lo toca la rueda: lo pone la tira de abajo, y es lo
                    // que hace que una tinta sea *esa* tinta.
                    alElegir(
                        enTexto(deHsv(floatArrayOf(tono, viveza, hsv[2].coerceAtLeast(0.35f))))
                    )
                }
            )

            // **Los colores de uno, en columnas, y un más para ir guardándolos.**
            //
            // Se guardan a mano y no solos: lo que uno toca al buscar un color no son sus
            // colores —son los diez tonos por los que ha pasado el dedo—, y una lista que se
            // llena sola de eso no sirve para volver a nada. Con el más, ahí está lo que uno
            // ha decidido que le vale.
            Column(Modifier.padding(start = 10.dp)) {
                // Los de uno primero y las cuatro de siempre detrás, sin repetir. Con la
                // lista de uno **en vez de** las de siempre, guardar el primer color hacía
                // desaparecer el negro y el blanco: se ganaba un color y se perdían cuatro.
                val guardados =
                    favoritos + LAS_DE_SIEMPRE.filterNot { c -> favoritos.any { it.equals(c, true) } }
                for (fila in guardados.take(COLORES_A_MANO).chunked(COLUMNAS_DE_COLORES)) {
                    Row {
                        for (c in fila) {
                            Box(
                                Modifier
                                    .padding(3.dp)
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(Color(parseColor(c, 255)))
                                    .border(
                                        if (c.equals(color, true)) 2.dp else 1.dp,
                                        if (c.equals(color, true)) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outlineVariant,
                                        CircleShape
                                    )
                                    .clickable { alElegir(c) }
                            )
                        }
                    }
                }
                alGuardar?.let { guardar ->
                    Box(
                        Modifier
                            .padding(3.dp)
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                            .clickable { guardar(color) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "+", fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // **De negro a blanco pasando por el color**, y leyéndose igual en los dos
        // sentidos. Estaba escrito con dos reglas distintas —una para ir y otra para volver—
        // y por eso el pulgar saltaba: subiendo hacia la luz, el color dejaba de estar
        // «apagado» y pasaba a estar «lavado», y la tira lo leía como si estuviera al otro
        // lado. Ahora es una sola cuenta: por debajo de la mitad se apaga y por encima se
        // lava, y leerla es esa misma cuenta al revés.
        Tira(
            nombre = stringResource(R.string.croquis_luz),
            colores = listOf(
                Color.Black,
                Color(deHsv(floatArrayOf(hsv[0], 1f, 1f))),
                Color.White
            ),
            valor = if (hsv[2] < 0.995f) hsv[2] / 2 else 1f - hsv[1] / 2,
            alEmpezar = {}
        ) { cuanto ->
            val nuevo =
                if (cuanto <= 0.5f) {
                    deHsv(floatArrayOf(hsv[0], 1f, (cuanto * 2f).coerceAtLeast(0.06f)))
                } else {
                    deHsv(floatArrayOf(hsv[0], ((1f - cuanto) * 2f).coerceIn(0f, 1f), 1f))
                }
            alElegir(enTexto(nuevo))
        }
    }
}

/** Las cuatro de siempre, al lado del anillo: la de dibujar, su contraria y dos de marcar. */
private val LAS_DE_SIEMPRE = listOf("#1e1e1e", "#f1f3f5", "#e03131", "#1971c2")

/** Lo alto que son los dos recuadros del taller, y con cuántos puntos se guarda un rasgo. */
/**
 * Lo alto que va el recuadro del taller, en `dp`.
 *
 * Ciento veinte y no noventa y seis: aquí se dibuja con el dedo la figura que va a dejar cada
 * trazo del croquis, y en cuatro dedos de alto no cabe trazar una curva con cuidado. Es de lo
 * poco de esta pantalla que se gana ensanchándolo.
 */
private const val ALTO_DEL_TALLER = 120
private const val PUNTOS_DEL_RASGO = 48

/** Lo gordo que va un rasgo de la tinta, en partes del tamaño de la marca. */
private const val GORDO_DEL_RASGO = 0.12
private const val ANILLO = 96

/** Hasta dónde llega el grueso que se puede pedir, en dp. */
private const val GROSOR_MINIMO = 1.5

/** Lo más gordo que se puede elegir. El mismo que el del selector de la barra. */
private const val GROSOR_MAXIMO = 64.0

/** Un interruptor de los del taller: el nombre y una pastilla que se enciende. */
@Composable
private fun Interruptor(nombre: String, puesto: Boolean, alTocar: () -> Unit) {
    Row(
        Modifier
            .padding(top = 10.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = alTocar)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(width = 34.dp, height = 20.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(
                    if (puesto) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
                )
        )
        Text(
            nombre,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

/** Por debajo de esto la tinta no se vería y parecería que no pinta. */
private const val MINIMO_QUE_SE_VE = 0.06

/**
 * Una tira de color: **se toca donde se quiere y ya está ahí**.
 *
 * Sin umbral de arrastre y sin agarrar el pulgar: tocar la tira lleva el valor a donde se
 * ha tocado, y de ahí se sigue arrastrando. Obligando a coger el pulgar, cambiar de un
 * extremo al otro son dos gestos y hay que apuntar a un círculo de medio centímetro.
 *
 * El aviso al empezar es para el historial: todo el arrastre entra en una sola anotación,
 * o deshacer tendría que tocarse cincuenta veces para volver al fondo de antes.
 */
@Composable
internal fun Tira(
    nombre: String,
    colores: List<Color>,
    valor: Float,
    alEmpezar: () -> Unit,
    alValor: (Float) -> Unit
) {
    // **Al día, y no como estaban al aparecer.**
    //
    // El atendedor se arma una sola vez —`pointerInput(Unit)`, que es lo que evita rearmarlo
    // en cada fotograma del arrastre— y se queda con lo que hubiera entonces. Con las
    // acciones capturadas tal cual, una tira sigue mandando lo que quisiera decir la primera
    // vez que se pintó, y lo que se toca ahora ya no es eso.
    val empezar by rememberUpdatedState(alEmpezar)
    val darValor by rememberUpdatedState(alValor)
    Row(
        Modifier.padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            nombre,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp).width(46.dp)
        )
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(ALTO_DE_LA_TIRA.dp)
                .clip(RoundedCornerShape(ALTO_DE_LA_TIRA.dp / 2))
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val abajo = awaitFirstDown()
                        empezar()
                        darValor((abajo.position.x / size.width).coerceIn(0f, 1f))
                        abajo.consume()
                        while (true) {
                            val evento = awaitPointerEvent()
                            val dedo = evento.changes.firstOrNull { it.pressed } ?: break
                            darValor((dedo.position.x / size.width).coerceIn(0f, 1f))
                            dedo.consume()
                        }
                    }
                }
        ) {
            drawRect(Brush.horizontalGradient(colores))
            // El pulgar: un aro blanco con un filo oscuro por fuera, que es lo único que se
            // ve tanto sobre un amarillo como sobre un azul marino.
            val radio = size.height * 0.36f
            val donde = Offset(
                (valor * size.width).coerceIn(radio + 2f, size.width - radio - 2f),
                size.height / 2
            )
            drawCircle(Color.White, radius = radio, center = donde, style = Stroke(width = 3f))
            drawCircle(
                Color.Black.copy(alpha = 0.45f), radius = radio + 2f, center = donde,
                style = Stroke(width = 1.5f)
            )
        }
    }
}

/** Lo alta que es una tira, en dp: la de un dedo, que es lo que se arrastra por ella. */
private const val ALTO_DE_LA_TIRA = 26
