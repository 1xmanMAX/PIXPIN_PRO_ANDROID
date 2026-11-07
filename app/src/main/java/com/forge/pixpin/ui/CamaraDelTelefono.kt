package com.forge.pixpin.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

/**
 * **Hacer una foto con la cámara del teléfono** (5-nov-2026, el usuario: «la cámara de los comentarios no
 * funciona»). Devuelve la función que la abre; [alHacer] recibe el `content://` de la foto hecha.
 *
 * - **Con el permiso de la cámara pedido antes**: PixPin declara `CAMERA` (el escáner, la clase), y entonces
 *   Android **no deja** abrir la cámara del sistema (`ACTION_IMAGE_CAPTURE`) sin ese permiso concedido: lanza
 *   un `SecurityException` y no se abre nada. Por eso no funcionaba.
 * - La foto va a `cache/share` (lo sirve el FileProvider) y su `Uri` se guarda con `rememberSaveable`: si
 *   Android cierra la pantalla mientras la cámara está delante, al volver aún se sabe dónde quedó.
 */
@Composable
fun rememberCamaraDelTelefono(alHacer: (Uri) -> Unit): () -> Unit {
    val contexto = LocalContext.current
    val alHacerAhora by rememberUpdatedState(alHacer)
    var pendiente by rememberSaveable { mutableStateOf<Uri?>(null) }
    val hacer = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { hecha ->
        val u = pendiente
        pendiente = null
        if (hecha && u != null) alHacerAhora(u)
    }
    fun abrir() {
        runCatching {
            val f = File(File(contexto.cacheDir, "share").apply { mkdirs() }, "camara-${System.currentTimeMillis()}.jpg")
            val u = FileProvider.getUriForFile(contexto, "${contexto.packageName}.fileprovider", f)
            pendiente = u
            hacer.launch(u)
        }.onFailure {
            pendiente = null
            Toast.makeText(contexto, "No se pudo abrir la cámara", Toast.LENGTH_SHORT).show()
        }
    }
    val permiso = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { si ->
        if (si) abrir() else Toast.makeText(contexto, "Sin permiso de la cámara no se puede hacer la foto", Toast.LENGTH_LONG).show()
    }
    return {
        if (ContextCompat.checkSelfPermission(contexto, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) abrir()
        else permiso.launch(Manifest.permission.CAMERA)
    }
}
