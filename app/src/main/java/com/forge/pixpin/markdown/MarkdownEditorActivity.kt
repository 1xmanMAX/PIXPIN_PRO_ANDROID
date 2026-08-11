package com.forge.pixpin.markdown

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forge.pixpin.ui.theme.PixPinTheme

/**
 * El editor avanzado de notas, hermano del editor avanzado de dibujo.
 *
 * ## Qué se copia de Telegram y qué no
 *
 * Se copia **toda la organización**: los mismos formatos, en el orden de su
 * `FloatingToolbar.STYLE_BUTTONS`, repartidos entre panel principal y
 * desbordamiento como su `FloatingToolbarPopup`, con los botones encendidos
 * según lo que cubra la selección y alternando por cobertura como su
 * `toggleStyleForSelection`. Y su diálogo de enlace, con el botón de pegar que
 * solo asoma cuando hay algo que pegar. Todo eso vive en [BarraDeFormatoUi] y en
 * [MarkdownEdit], compartido con la nota flotante.
 *
 * No se copia una cosa: que el suyo sea **wysiwyg**. En Telegram nunca ves un
 * asterisco; el formato va en spans, aparte del texto. Aquí el texto que se
 * escribe **es** lo que se guarda, y de él viven la exportación, el PDF y el
 * SVG. Convertir spans a Markdown y de vuelta en cada pulsación metería un
 * traductor entre el dedo y el archivo, y ese traductor es justo donde se pierde
 * lo que el usuario escribió. Así que aquí se escribe Markdown y se ve el
 * resultado al lado, con el botón de arriba.
 */
class MarkdownEditorActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val id = intent.getStringExtra(EXTRA_ID).orEmpty()
        val inicial = intent.getStringExtra(EXTRA_TEXTO).orEmpty()

        setContent {
            PixPinTheme {
                Pantalla(
                    inicial = inicial,
                    onGuardar = { texto ->
                        if (id.isNotEmpty()) TextoStore.guardar(id, texto)
                        finish()
                    },
                    onDescartar = { finish() }
                )
            }
        }
    }

    companion object {
        private const val EXTRA_ID = "md_id"
        private const val EXTRA_TEXTO = "md_texto"

        /**
         * Abre la nota [id] con [texto].
         *
         * El texto va en el intent y no se lee de disco porque el pin puede
         * tener cambios sin guardar en el momento de abrir, y empezar a editar
         * perdiendo lo último escrito es el peor estreno posible. Al cerrar
         * vuelve por [TextoStore].
         */
        fun abrir(context: Context, id: String, texto: String) {
            val i = Intent(context, MarkdownEditorActivity::class.java)
                .putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_TEXTO, texto)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(i) }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun Pantalla(
    inicial: String,
    onGuardar: (String) -> Unit,
    onDescartar: () -> Unit
) {
    var valor by remember {
        mutableStateOf(TextFieldValue(inicial, androidx.compose.ui.text.TextRange(inicial.length)))
    }
    var viendo by remember { mutableStateOf(false) }
    var pidiendoUrl by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (viendo) "Vista previa" else "Nota") },
                navigationIcon = {
                    IconButton(onClick = onDescartar) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Descartar"
                        )
                    }
                },
                actions = {
                    // Un solo botón que alterna, no dos pestañas: el editor y la
                    // vista enseñan lo mismo, y dos controles para una cosa es
                    // uno de más.
                    IconButton(onClick = { viendo = !viendo }) {
                        Icon(
                            if (viendo) Icons.Filled.Edit else Icons.Filled.Visibility,
                            contentDescription = if (viendo) "Escribir" else "Ver el resultado"
                        )
                    }
                    IconButton(onClick = { onGuardar(valor.text) }) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = "Guardar",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            )
        }
    ) { hueco ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(hueco)
                .imePadding()
        ) {
            Box(Modifier.weight(1f)) {
                if (viendo) {
                    val bloques = remember(valor.text) { Markdown.parse(valor.text) }
                    Box(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp)
                    ) {
                        MarkdownText(blocks = bloques, baseSizeSp = 16f)
                    }
                } else {
                    BasicTextField(
                        value = valor,
                        onValueChange = { valor = it },
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        textStyle = TextStyle(
                            fontSize = 16.sp,
                            lineHeight = 24.sp,
                            // Monoespaciada al escribir: las marcas se cuentan
                            // con la vista y en proporcional los asteriscos se
                            // esconden entre las letras.
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(
                            MaterialTheme.colorScheme.primary
                        )
                    )
                }
            }

            // La barra solo mientras se escribe: en la vista no hay selección a
            // la que aplicarle nada.
            if (!viendo) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    BarraDeFormatoUi(
                        valor = valor,
                        onValor = { valor = it },
                        onPedirUrl = { pidiendoUrl = true }
                    )
                }
            }
        }
    }

    if (pidiendoUrl) {
        DialogoDeEnlace(
            onCerrar = { pidiendoUrl = false },
            onAceptar = { url ->
                valor = conEnlace(valor, url)
                pidiendoUrl = false
            }
        )
    }
}

/**
 * El diálogo de enlace de Telegram (`EditTextCaption.makeSelectedUrl`).
 *
 * Dos detalles suyos que valen lo que cuestan: el campo empieza con `https://`
 * escrito, y hay un **botón de pegar que solo asoma cuando el campo está intacto
 * y el portapapeles tiene algo**. El camino real es copiar la dirección en el
 * navegador, volver, seleccionar la palabra y darle a enlace; con el botón eso
 * son dos toques y sin él es abrir el teclado y buscar el menú de pegar.
 */
@Composable
private fun DialogoDeEnlace(onCerrar: () -> Unit, onAceptar: (String) -> Unit) {
    val contexto = LocalContext.current
    val porDefecto = "https://"
    var url by remember { mutableStateOf(porDefecto) }

    val hayQuePegar = remember(url) {
        if (url.isNotEmpty() && url != porDefecto) {
            false
        } else {
            val cb = contexto.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cb?.hasPrimaryClip() == true
        }
    }

    AlertDialog(
        onDismissRequest = onCerrar,
        title = { Text("Enlace") },
        text = {
            Column {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    singleLine = true,
                    label = { Text("Dirección") },
                    modifier = Modifier.fillMaxWidth()
                )
                if (hayQuePegar) {
                    TextButton(onClick = {
                        val cb = contexto.getSystemService(Context.CLIPBOARD_SERVICE)
                            as? ClipboardManager
                        val texto = runCatching {
                            cb?.primaryClip?.getItemAt(0)?.coerceToText(contexto)?.toString()
                        }.getOrNull()
                        if (!texto.isNullOrEmpty()) url = texto
                    }) {
                        Text("Pegar")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // Solo el prefijo no es una dirección: se trata como vacío y el
                // enlace queda con los paréntesis listos para rellenar.
                onAceptar(if (url == porDefecto) "" else url.trim())
            }) { Text("Aceptar") }
        },
        dismissButton = { TextButton(onClick = onCerrar) { Text("Cancelar") } }
    )
}
