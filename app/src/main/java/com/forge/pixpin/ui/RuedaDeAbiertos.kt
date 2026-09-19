package com.forge.pixpin.ui

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.forge.pixpin.PixPinApp
import com.forge.pixpin.data.Abiertos
import com.forge.pixpin.data.LienzoAbierto
import com.forge.pixpin.motor.HaciaDonde
import com.forge.pixpin.motor.elArrastreDeTresDedos
import com.forge.pixpin.motor.elToqueDeCuatroDedos

/**
 * **La multitarea, para lo que no es un lienzo** (19-sep-2026): tablas, croquis 3D y PDF enteros
 * entran en la misma lista de abiertos que los lienzos.
 *
 * Cada una de esas cosas tiene su propia pantalla —su actividad, con su estado dentro—, así que
 * no pueden vivir **a la vez** al lado de un lienzo como viven los lienzos entre sí: eso pediría
 * sacar el estado de tres pantallas enteras. Lo que sí comparten es **la rueda**: la misma lista,
 * la misma baraja con cuatro dedos hacia arriba, los mismos tres dedos al lado para pasar al
 * vecino, y los grupos guardados. En la tira de un lienzo salen como una tarjeta con su vista
 * previa; tocarla lleva a su pantalla, sin animación, como quien se corre por la tira.
 */

/** Abre [l] en la pantalla que le toca. Quien llama cierra la suya si quiere que sea un relevo. */
fun abrirLoAbierto(context: Context, l: LienzoAbierto, desdeProyecto: String? = l.proyecto) {
    when (l.clase) {
        Abiertos.TABLA -> com.forge.pixpin.tabla.TablaActivity.abrir(context, l.ruta, desdeProyecto = desdeProyecto)
        Abiertos.CROQUIS -> com.forge.pixpin.croquis3d.Croquis3DActivity.abrir(
            context, l.proyecto, l.ruta, desdeProyectos = desdeProyecto != null
        )
        Abiertos.PDF -> l.pdf?.let { com.forge.pixpin.pdf.LectorPdfActivity.abrir(context, it, l.nombre) }
        else -> if (l.pdf != null && l.pagina >= 0) {
            com.forge.pixpin.motor.DrawEditorActivity.abrirPaginaDePdf(context, l.id, l.ruta, l.pdf, l.pagina, desdeProyecto)
        } else {
            com.forge.pixpin.motor.DrawEditorActivity.abrir(
                context, l.id, l.ruta ?: com.forge.pixpin.motor.ExcalidrawStore.rutaDe(context, l.id), null, desdeProyecto
            )
        }
    }
}

/** El relevo: abre [l] y cierra esta pantalla, **sin animación**: es correrse por la tira. */
@Suppress("DEPRECATION")
fun relevarPor(actividad: Activity, l: LienzoAbierto) {
    abrirLoAbierto(actividad, l)
    runCatching { actividad.overridePendingTransition(0, 0) }
    actividad.finish()
    runCatching { actividad.overridePendingTransition(0, 0) }
}

/**
 * **Lo que se ve de una tabla, un croquis 3D o un PDF** en la tira y en la baraja: su vista
 * previa si la hay, su icono, su nombre y a qué invita.
 */
@Composable
fun TarjetaDeOtraCosa(l: LienzoAbierto, modifier: Modifier = Modifier, conTexto: Boolean = true) {
    val contexto = LocalContext.current
    var portada by remember(l.id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(l.id) {
        val pdf = l.pdf
        if (l.clase == Abiertos.PDF && pdf != null) {
            portada = runCatching {
                com.forge.pixpin.motor.PdfMiniaturas.enMemoria(pdf, 0) ?: com.forge.pixpin.motor.PdfMiniaturas.de(contexto, pdf, 0)
            }.getOrNull()
        }
    }
    Box(modifier.background(Color(0xFF10163A)), contentAlignment = Alignment.Center) {
        val tabla = l.ruta
        val foto = portada
        when {
            l.clase == Abiertos.TABLA && tabla != null ->
                com.forge.pixpin.tabla.MiniaturaDeTabla(tabla, grande = true, modifier = Modifier.fillMaxSize().padding(6.dp))
            foto != null -> Image(
                foto.asImageBitmap(), contentDescription = null,
                contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(6.dp)
            )
        }
        Column(
            Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Sobre la vista previa el icono va pequeño, de sello; sin ella, grande: es lo que hay.
            val hayVista = (l.clase == Abiertos.TABLA && tabla != null) || foto != null
            Icon(
                when (l.clase) {
                    Abiertos.TABLA -> Icons.Filled.TableChart
                    Abiertos.CROQUIS -> Icons.Filled.ViewInAr
                    else -> Icons.Filled.PictureAsPdf
                },
                contentDescription = null,
                tint = Color.White.copy(alpha = if (hayVista) 0.0f else 0.8f),
                modifier = Modifier.size(44.dp)
            )
            if (conTexto && !hayVista) {
                Text(
                    l.nombre.ifBlank { "Sin nombre" }, color = Color.White, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp)
                )
                Text(
                    "Toca para abrir", color = Color.White.copy(alpha = 0.55f), maxLines = 1,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

/**
 * **La rueda puesta encima de una pantalla que no es el editor de lienzos.** Apunta [actual] en
 * la lista de abiertos y le da a [contenido] los mismos gestos: tres dedos al lado pasan al
 * vecino, cuatro hacia arriba abren la baraja, y en la baraja están los grupos guardados.
 */
@Composable
fun ConLaRueda(
    actual: LienzoAbierto,
    /**
     * El croquis 3D **ya usa los tres dedos** para ladear la cámara: ahí la rueda se queda solo
     * con los cuatro hacia arriba, y al vecino se pasa desde la baraja.
     */
    conTresDedos: Boolean = true,
    contenido: @Composable () -> Unit
) {
    val contexto = LocalContext.current
    val actividad = contexto as? Activity
    val abiertos = (contexto.applicationContext as? PixPinApp)?.lienzosAbiertos
    if (abiertos == null || actividad == null) {
        contenido()
        return
    }
    LaunchedEffect(actual.id, actual.nombre) { abiertos.abrir(actual) }
    val lista by abiertos.lista.collectAsState()
    val grupos by abiertos.grupos.collectAsState()
    var viendoTodos by remember { mutableStateOf(false) }
    val umbral = 40f * LocalDensity.current.density
    val corrido = remember { floatArrayOf(0f) }
    val alAcabar = rememberUpdatedState {
        val desde = lista.indexOfFirst { it.id == actual.id }
        val ido = corrido[0]
        corrido[0] = 0f
        if (desde >= 0) {
            val i = Multilienzo.alSoltar(desde, ido, umbral, lista.size)
            lista.getOrNull(i)?.takeIf { it.id != actual.id }?.let { relevarPor(actividad, it) }
        }
    }
    BackHandler(viendoTodos) { viendoTodos = false }
    Box(
        Modifier
            .fillMaxSize()
            .then(
                if (conTresDedos) Modifier.elArrastreDeTresDedos(
                    enColumna = false, alCorrer = { corrido[0] += it }, alAcabar = { alAcabar.value() }
                ) else Modifier
            )
            .elToqueDeCuatroDedos(
                alJuntarse = {},
                alTocar = {},
                alDeslizar = { if (it == HaciaDonde.ARRIBA) viendoTodos = true }
            )
    ) {
        contenido()
        if (viendoTodos) {
            VistaDeTodos(
                lienzos = lista,
                actual = actual.id,
                onAbrir = { viendoTodos = false; if (it.id != actual.id) relevarPor(actividad, it) },
                onCerrar = { abiertos.cerrar(it.id) },
                onOrdenar = { de, a -> abiertos.mover(de, a) },
                onSalir = { viendoTodos = false },
                grupos = grupos,
                onGuardarGrupo = { abiertos.guardarGrupo(it) },
                onAbrirGrupo = { g ->
                    viendoTodos = false
                    abiertos.abrirGrupo(g.id)?.takeIf { it.id != actual.id }?.let { relevarPor(actividad, it) }
                },
                onBorrarGrupo = { abiertos.borrarGrupo(it.id) },
                conElBotonDeOrden = false,
                conAbrirOtro = false
            )
        }
    }
}
