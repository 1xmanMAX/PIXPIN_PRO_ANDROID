package com.forge.pixpin.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.forge.pixpin.PixPinApp
import com.forge.pixpin.R
import com.forge.pixpin.motor.DrawEditorActivity
import com.forge.pixpin.motor.ExcalidrawStore
import com.forge.pixpin.motor.Hoja
import com.forge.pixpin.motor.HojasDelProyecto
import com.forge.pixpin.motor.Proyecto
import com.forge.pixpin.motor.Proyectos
import com.forge.pixpin.motormd.Paginado
import com.forge.pixpin.pin.ImageStore

/*
 * **Lo que queda de la pantalla de Proyectos** (`ui/Proyectos.kt`, quitada el 26-oct-2026 como el
 * PC `c687285f`: «quita la visualización de proyectos»). Un proyecto es su chat y sus hojas son
 * mensajes de ese chat ([com.forge.pixpin.guardados.ChatDeLosProyectos]); aquí se queda lo que
 * otras pantallas siguen usando —abrir una hoja desde un enlace o una nota, crear una hoja nueva
 * desde el chat, el diálogo del nombre y la imagen incrustada de las páginas web— y nada de la
 * pantalla en sí.
 */

/**
 * Abre una hoja para trabajarla.
 *
 * Fuera de la composición porque la abren varios sitios —un enlace `pixpin:` y lo incrustado en
 * una nota— y tienen que abrir exactamente lo mismo que el mensaje de esa hoja en el chat.
 */
internal fun abrirHoja(
    contexto: android.content.Context,
    app: PixPinApp,
    p: Proyecto,
    pagina: HojasDelProyecto.Pagina
) {
    val h = pagina.hoja
    when {
        // **Una tabla del proyecto se ve en el visor de hojas, sin editar** (25-oct-2026, el usuario:
        // «quita la edición de Excel y solo ponlo en visualización normal»); se anota con el riel,
        // con el código de su hoja. Ver [com.forge.pixpin.excel.EntradaDeHojas].
        h.tabla != null -> com.forge.pixpin.excel.ExcelActivity.abrirDestino(contexto, com.forge.pixpin.excel.EntradaDeHojas.destino(h.tabla))

        // **La lámina de un croquis en el espacio abre el croquis, no la lámina.**
        //
        // Lo que se entrega es la lámina —vectores, se exporta y se amplía sin límite— pero
        // donde se trabaja es en el espacio. Tocarla lleva al croquis **puesto en la vista
        // que la creó**, que es de donde salió y donde se sigue.
        h.croquis != null && h.vista != null ->
            com.forge.pixpin.croquis3d.Croquis3DActivity.abrir(
                contexto, p.id, h.croquis!!, desdeProyectos = true, vista = h.vista!!
            )

        // El croquis entero, el que sale como una hoja más de la rejilla.
        h.croquis != null ->
            com.forge.pixpin.croquis3d.Croquis3DActivity.abrir(
                contexto, p.id, h.croquis!!, desdeProyectos = true
            )

        // La nota abre **por la hoja que se ha tocado**: el editor no tiene
        // páginas —el corte cambiaría con cada letra— pero sí sabe empezar por
        // donde estabas. Ver [MarkdownEditorActivity.abrir].
        h.nota != null -> MarkdownEditorActivity.abrir(
            contexto,
            h.id,
            h.nota!!,
            if (pagina.nota >= 0) {
                Paginado.deTexto(h.nota!!).getOrNull(pagina.nota)?.desde ?: -1
            } else {
                -1
            },
            desdeProyecto = p.id
        )
        else -> {
            val dibujo = h.dibujo ?: "dib-${System.currentTimeMillis()}"
            if (h.dibujo == null) {
                app.proyectos.guardar(
                    Proyectos.conDibujo(p, h.id, dibujo, System.currentTimeMillis())
                )
            }
            val ruta = ExcalidrawStore.rutaDe(contexto, dibujo)
            if (p.pdfOrigen != null && h.pagina != null) {
                DrawEditorActivity.abrirPaginaDePdf(
                    contexto, dibujo, ruta, p.pdfOrigen!!, h.pagina!!, desdeProyecto = p.id
                )
            } else {
                DrawEditorActivity.abrir(contexto, dibujo, ruta, null, desdeProyecto = p.id)
            }
        }
    }
}

/**
 * **Una hoja nueva en el chat de un proyecto** (26-oct-2026): lienzo, nota o croquis en el espacio,
 * desde el «+» del chat (el `adjuntar-lienzo` y la `chat-adj-nota-md` del PC). Eran los botones de
 * la barra de la tarjeta de Proyectos; sin esa pantalla, se hacen desde el chat, que es donde va a
 * salir la hoja: se guarda en el proyecto y [com.forge.pixpin.guardados.ChatDeLosProyectos] deja
 * su mensaje. Se abre en el acto, y al cerrar se vuelve al chat ([com.forge.pixpin.Volver]).
 */
internal object HojaNueva {

    /**
     * **Un lienzo en blanco suelto en el chat [chat]** (null, «Guardados»), sin proyecto detrás: el
     * de la pestaña Funciones y el del «+» de «Mensajes guardados». Su id, o null si no se pudo
     * escribir. Es disco: fuera del hilo de la pantalla.
     */
    fun lienzoSuelto(contexto: android.content.Context, chat: String?): String? = runCatching {
        val ahora = System.currentTimeMillis()
        val id = "dib-$ahora"
        ExcalidrawStore.guardar(contexto, id, com.forge.pixpin.motor.Scene()) ?: return@runCatching null
        com.forge.pixpin.guardados.MensajesStore(contexto).anadir(
            com.forge.pixpin.guardados.Mensaje(
                id = java.util.UUID.randomUUID().toString(), cuando = ahora, clase = com.forge.pixpin.guardados.Clase.DIBUJO,
                nombre = "Lienzo", referencia = id, proyecto = chat
            )
        )
        id
    }.getOrNull()

    /** Un lienzo en blanco en el proyecto [p], abierto. */
    fun lienzo(contexto: android.content.Context, app: PixPinApp, p: Proyecto) {
        val ahora = System.currentTimeMillis()
        val dibujo = "dib-$ahora"
        app.proyectos.guardar(Proyectos.conHoja(p, Hoja(id = "h-$ahora", dibujo = dibujo), ahora))
        DrawEditorActivity.abrir(contexto, dibujo, ExcalidrawStore.rutaDe(contexto, dibujo), null, desdeProyecto = p.id)
    }

    /** Una nota vacía en el proyecto [p], abierta en el editor de notas. */
    fun nota(contexto: android.content.Context, app: PixPinApp, p: Proyecto) {
        val ahora = System.currentTimeMillis()
        val id = "n-$ahora"
        app.proyectos.guardar(Proyectos.conHoja(p, Hoja(id = id, nota = ""), ahora))
        MarkdownEditorActivity.abrir(contexto, id, "", desdeProyecto = p.id)
    }

    /**
     * Un croquis en el espacio en el proyecto [p], abierto.
     *
     * El identificador lleva la hora dentro: es único sin tener que mirar los que ya hay, y
     * ordena solo por antigüedad, que es el orden en que se enseñan.
     */
    fun croquis(contexto: android.content.Context, app: PixPinApp, p: Proyecto) {
        val ahora = System.currentTimeMillis()
        val id = "c3d-$ahora"
        app.proyectos.guardar(Proyectos.conCroquis(p, id, ahora))
        com.forge.pixpin.croquis3d.Croquis3DActivity.abrir(contexto, p.id, id, desdeProyectos = true)
    }
}


/**
 * Pregunta el nombre del proyecto.
 *
 * Es el mismo que llevará el PDF al exportar, así que cambiarlo aquí cambia lo
 * que le llega al que lo reciba. Ver [com.forge.pixpin.motor.ExportarProyecto.nombreDeArchivo].
 */
@Composable
internal fun DialogoDeNombre(
    actual: String,
    onCerrar: () -> Unit,
    titulo: String? = null,
    onAceptar: (String) -> Unit
) {
    var texto by remember { mutableStateOf(actual) }
    AlertDialog(
        onDismissRequest = onCerrar,
        title = { Text(titulo ?: stringResource(R.string.proyecto_renombrar)) },
        text = {
            OutlinedTextField(
                value = texto,
                onValueChange = { texto = it.take(80) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { onAceptar(texto.trim().ifBlank { actual }) }) {
                Text(stringResource(R.string.cd_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onCerrar) { Text(stringResource(R.string.cancel)) }
        }
    )
}

/**
 * Una imagen del croquis metida en la página, como `data:`. Se recomprime: una foto de doce
 * megapíxeles dentro de un archivo que se manda por mensajería no la abre nadie.
 */
internal fun imagenIncrustada(ruta: String): String? = runCatching {
    val bmp = ImageStore.load(ruta, 1024) ?: return null
    val salida = java.io.ByteArrayOutputStream()
    val conAlfa = bmp.hasAlpha()
    bmp.compress(
        if (conAlfa) android.graphics.Bitmap.CompressFormat.PNG
        else android.graphics.Bitmap.CompressFormat.JPEG,
        82, salida
    )
    "data:" + (if (conAlfa) "image/png" else "image/jpeg") + ";base64," +
        android.util.Base64.encodeToString(salida.toByteArray(), android.util.Base64.NO_WRAP)
}.getOrNull()
