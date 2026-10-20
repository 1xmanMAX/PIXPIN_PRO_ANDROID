package com.forge.pixpin.atajos

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.forge.pixpin.asistente.EnlacesDelTelefono

/**
 * **Los enlaces `pixpin://` y `pixpinmax://`** (PC `orden.rs`, 15-oct-2026): que cualquier app —un
 * navegador, una nota de Obsidian, Tasker, un QR— le pida algo a PixPin con un enlace, igual que en
 * el PC. La seña de los atajos (`pixpin://atajo/…`) sigue yendo a [AtajoActivity].
 *
 * Lo inofensivo (abrir, escribir en el chat, empezar una captura…) se hace sin preguntar; lo demás
 * pregunta con lo que va a hacer; y lo que abre una ruta que no es de este teléfono o crea una
 * automatización no se hace nunca. Es la misma lista que el PC: la decide su código.
 */
class EnlaceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val datos = intent?.data
        if (datos == null) { finish(); return }
        if (datos.scheme.equals("pixpin", ignoreCase = true) && datos.host.equals("atajo", ignoreCase = true)) {
            startActivity(Intent(this, AtajoActivity::class.java).setData(datos).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish(); return
        }
        val e = EnlacesDelTelefono(applicationContext)
        Thread {
            val leido = e.leer(datos.toString())
            runOnUiThread {
                when {
                    leido.error != null -> { Toast.makeText(this, "PixPin: ${leido.error}", Toast.LENGTH_LONG).show(); finish() }
                    leido.ayuda != null -> AlertDialog.Builder(this).setTitle("Enlaces de PixPin").setMessage(leido.ayuda)
                        .setPositiveButton("Vale") { _, _ -> finish() }.setOnCancelListener { finish() }.show()
                    leido.pedidos.any { it.prohibida } -> {
                        Toast.makeText(this, "PixPin no hace eso por un enlace: ${e.motivo(leido.pedidos.first { it.prohibida }.seguridad)}", Toast.LENGTH_LONG).show()
                        finish()
                    }
                    leido.pedidos.all { it.directa } -> hacer(e, leido, false)
                    else -> AlertDialog.Builder(this)
                        .setTitle("¿Hacer lo que pide el enlace?")
                        .setMessage(leido.pedidos.filter { !it.directa }.joinToString("\n\n") { "Esto ${e.motivo(it.seguridad)}:\n${it.dice}" })
                        .setPositiveButton("Sí") { _, _ -> hacer(e, leido, true) }
                        .setNegativeButton("No") { _, _ -> finish() }
                        .setOnCancelListener { finish() }
                        .show()
                }
            }
        }.start()
    }

    private fun hacer(e: EnlacesDelTelefono, leido: EnlacesDelTelefono.Leido, confirmado: Boolean) {
        Thread {
            val fallos = leido.pedidos.mapNotNull { p -> e.hacer(p, confirmado).exceptionOrNull()?.message }
            runOnUiThread {
                if (fallos.isNotEmpty()) Toast.makeText(this, "PixPin: ${fallos.first()}", Toast.LENGTH_LONG).show()
                finish()
            }
        }.start()
    }
}
