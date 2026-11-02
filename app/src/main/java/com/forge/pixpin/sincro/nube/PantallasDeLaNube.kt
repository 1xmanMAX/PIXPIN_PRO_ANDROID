package com.forge.pixpin.sincro.nube

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.forge.pixpin.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * **Sincronizar → Nube** (2-nov-2026, `sincronizar/nube_ui.rs` del PC): la dirección de la web, la
 * llave del aparato (se guarda cifrada y se enseña enmascarada), Guardar, Probar y Olvidar.
 */
@Composable
fun AjustesDeLaNube(alCerrar: () -> Unit) {
    val c = LocalContext.current
    val alcance = rememberCoroutineScope()
    var direccion by remember { mutableStateOf(NubeEnMarcha.direccion(c)) }
    var llave by remember { mutableStateOf("") }
    var guardada by remember { mutableStateOf(NubeEnMarcha.llaveALaVista(c)) }
    var aviso by remember { mutableStateOf<String?>(null) }
    var probando by remember { mutableStateOf(false) }
    val malaDireccion = stringResource(R.string.nb_nube_direccion_mala)
    val malaLlave = stringResource(R.string.nb_nube_llave_mala)
    val guardadoTxt = stringResource(R.string.nb_nube_guardado)
    val noContesta = stringResource(R.string.nb_nube_no_contesta)
    AlertDialog(
        onDismissRequest = alCerrar,
        title = { Text(stringResource(R.string.nb_nube_titulo)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.nb_nube_explica), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(direccion, { direccion = it.trim() }, label = { Text(stringResource(R.string.nb_nube_direccion)) }, singleLine = true, placeholder = { Text("https://…") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(llave, { llave = it.trim() }, label = { Text(stringResource(R.string.nb_nube_llave)) }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                guardada?.let { g ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.nb_nube_llave_guardada) + " " + g, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { NubeEnMarcha.olvidarLlave(c); guardada = null }) { Text(stringResource(R.string.nb_nube_olvidar)) }
                    }
                }
                Text(stringResource(R.string.nb_nube_que_va), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                aviso?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                aviso = try {
                    NubeEnMarcha.guardar(c, direccion, llave.ifBlank { null })
                    if (llave.isNotBlank()) { guardada = NubeEnMarcha.llaveALaVista(c); llave = "" }
                    guardadoTxt
                } catch (e: IllegalArgumentException) {
                    if (e.message == "llave") malaLlave else malaDireccion
                }
            }) { Text(stringResource(R.string.nb_nube_guardar)) }
        },
        dismissButton = {
            TextButton(enabled = !probando, onClick = {
                probando = true
                alcance.launch {
                    val r = withContext(Dispatchers.IO) { NubeEnMarcha.probar(c) }
                    probando = false
                    aviso = r.fold({ c.getString(R.string.nb_nube_probado, it.first) }, { noContesta + " " + (it.message ?: "") })
                }
            }) { Text(stringResource(R.string.nb_nube_probar)) }
        }
    )
}

/**
 * **«Por dónde va»** un chat compartido (2-nov-2026, como el PC): Red local o Nube. Sin la nube
 * configurada, «Nube» lo dice.
 */
@Composable
fun PorDondeVa(chat: String, modifier: Modifier = Modifier) {
    val c = LocalContext.current
    @Suppress("UNUSED_VARIABLE") val v by NubeEnMarcha.version.collectAsState()
    val modo = NubeEnMarcha.modo(c, chat)
    Column(modifier) {
        Text(stringResource(R.string.nb_nube_modo_titulo), style = MaterialTheme.typography.titleSmall)
        for ((m, titulo, detalle) in listOf(
            Triple(Modo.LOCAL, R.string.nb_nube_modo_local, R.string.nb_nube_modo_local_detalle),
            Triple(Modo.NUBE, R.string.nb_nube_modo_nube, R.string.nb_nube_modo_nube_detalle)
        )) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { NubeEnMarcha.ponerModo(c, chat, m) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = modo == m, onClick = { NubeEnMarcha.ponerModo(c, chat, m) })
                Column(Modifier.weight(1f)) {
                    Text(stringResource(titulo), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(detalle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (modo == Modo.NUBE && !NubeEnMarcha.configurada(c)) Text(stringResource(R.string.nb_nube_falta_configurar), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

/**
 * **La nubecita** arriba a la derecha del avatar de un chat de la nube: azul subiendo, verde al día,
 * gris sin red, roja si la llave no vale. Nada si el chat no va por la nube.
 */
@Composable
fun NubecitaDelChat(chat: String, modifier: Modifier = Modifier) {
    val c = LocalContext.current
    val estados by NubeEnMarcha.estados.collectAsState()
    @Suppress("UNUSED_VARIABLE") val v by NubeEnMarcha.version.collectAsState()
    @Suppress("UNUSED_VARIABLE") val leer = estados
    val e = NubeEnMarcha.estadoDe(c, chat) ?: return
    val (icono, color) = when (e) {
        NubeEnMarcha.EstadoNube.SUBIENDO -> Icons.Filled.CloudUpload to Color(0xFF3D8BFF)
        NubeEnMarcha.EstadoNube.AL_DIA -> Icons.Filled.Cloud to Color(0xFF2FA35A)
        NubeEnMarcha.EstadoNube.SIN_RED -> Icons.Filled.CloudQueue to Color(0xFF8A8F98)
        NubeEnMarcha.EstadoNube.LLAVE -> Icons.Filled.CloudOff to Color(0xFFE5484D)
    }
    Box(modifier.size(18.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
        Icon(icono, null, tint = color, modifier = Modifier.size(13.dp))
    }
}

/**
 * **«En línea / Sin conexión»** de los visores (2-nov-2026, `en_linea.rs` del PC): solo en un
 * documento de un chat de la nube. Sin conexión, lo anotado se queda aquí ([SinConexion]); al salir
 * se pregunta ([PreguntaAlSalir]).
 */
object EnLinea {
    /** El chat y la clave de lo anotado de un adjunto (por su código), si va por la nube. */
    fun deAdjunto(c: Context, ruta: String?): Pair<String, String>? {
        val uid = com.forge.pixpin.sincro.AnotacionesDelAdjunto.uidDe(c.filesDir, ruta) ?: return null
        return deCodigo(c, uid)
    }

    /** El chat de un mensaje por su código único, y el código como clave. */
    fun deCodigo(c: Context, uid: String): Pair<String, String>? {
        val m = runCatching { com.forge.pixpin.guardados.MensajesStore(c).leer().firstOrNull { com.forge.pixpin.sincro.Codigos.unico(it) == uid } }.getOrNull() ?: return null
        val chat = m.proyecto ?: return null
        return (chat to uid).takeIf { NubeEnMarcha.modo(c, chat) == Modo.NUBE }
    }

    /** El chat de un lienzo (por su referencia) si va por la nube. */
    fun deLienzo(c: Context, referencia: String): Pair<String, String>? {
        val m = runCatching { com.forge.pixpin.guardados.MensajesStore(c).leer().firstOrNull { it.referencia == referencia } }.getOrNull()
        val chat = m?.proyecto ?: (c.applicationContext as? com.forge.pixpin.PixPinApp)?.proyectos?.proyectos?.value?.firstOrNull { p -> p.hojas.any { it.dibujo == referencia } }?.id ?: return null
        return (chat to referencia).takeIf { NubeEnMarcha.modo(c, chat) == Modo.NUBE }
    }

    /** De la base `anot-<uid>…` de lo anotado. */
    fun deBase(c: Context, base: String?): Pair<String, String>? =
        base?.removePrefix("anot-")?.takeWhile { it != '-' && it != '.' }?.takeIf { it.isNotEmpty() }?.let { deCodigo(c, it) }

    fun sinConexion(c: Context, chat: String, clave: String) = SinConexion(c.filesDir).esta(chat, clave)
}

/** El botón: nube verde «En línea» o nube tachada dorada «Sin conexión». */
@Composable
fun BotonEnLinea(sin: Boolean, alCambiar: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.clip(RoundedCornerShape(50)).clickable(onClick = alCambiar), color = Color(0xCC1C1C1E), shape = RoundedCornerShape(50)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (sin) Icons.Filled.CloudOff else Icons.Filled.Cloud, null, tint = if (sin) Color(0xFFE0B341) else Color(0xFF39C26B), modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(if (sin) R.string.nb_nube_sin_conexion else R.string.nb_nube_en_linea), color = Color.White, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/**
 * **El botón de un visor**: solo si el documento ([par] = chat y clave) es de un chat de la nube.
 * Con «atrás» sin conexión, se pregunta qué hacer antes de [alSalir].
 */
@Composable
fun EnLineaDelVisor(par: Pair<String, String>?, alSalir: () -> Unit, modifier: Modifier = Modifier) {
    if (par == null) return
    val c = LocalContext.current
    val (chat, clave) = par
    var sin by remember(chat, clave) { mutableStateOf(EnLinea.sinConexion(c, chat, clave)) }
    var preguntando by remember { mutableStateOf(false) }
    BotonEnLinea(sin, {
        val sc = SinConexion(c.filesDir)
        if (sin) { sc.volver(chat, clave); NubeEnMarcha.despertar() } else sc.retener(chat, clave)
        sin = !sin
    }, modifier)
    androidx.activity.compose.BackHandler(enabled = sin) { preguntando = true }
    if (preguntando) PreguntaAlSalir(chat, clave, alAcabar = { preguntando = false; sin = false; alSalir() }, alCancelar = { preguntando = false })
}

/**
 * **Al salir de un documento sin conexión**, se pregunta: descartar (por omisión), subir, o dejarlo
 * sin conexión. [alAcabar] se llama al elegir (y entonces se puede cerrar).
 */
@Composable
fun PreguntaAlSalir(chat: String, clave: String, alAcabar: () -> Unit, alCancelar: () -> Unit) {
    val c = LocalContext.current
    AlertDialog(
        onDismissRequest = alCancelar,
        title = { Text(stringResource(R.string.nb_nube_cerrar_titulo)) },
        text = { Text(stringResource(R.string.nb_nube_cerrar_pregunta)) },
        confirmButton = {
            TextButton(onClick = { SinConexion(c.filesDir).descartar(chat, clave); alAcabar() }) { Text(stringResource(R.string.nb_descartar)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = alAcabar) { Text(stringResource(R.string.nb_dejar)) }
                TextButton(onClick = { SinConexion(c.filesDir).volver(chat, clave); NubeEnMarcha.despertar(); alAcabar() }) { Text(stringResource(R.string.nb_subir)) }
            }
        }
    )
}

@Suppress("unused")
private val PARA_EL_ICONO_DE_RED = Icons.Filled.Wifi
