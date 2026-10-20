package com.forge.pixpin.guardados

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle

/**
 * **Imprimir desde lo que no es una pantalla** (los pines flotantes): el diálogo de impresión de
 * Android necesita una actividad viva, así que esta, transparente, recibe las fotos ya recortadas
 * (una hoja por foto, [Imprimir.fotos]) y se cierra al volver del diálogo.
 */
class ImprimirFotosActivity : Activity() {
    private var lanzado = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val rutas = intent.getStringArrayExtra(EXTRA_RUTAS).orEmpty()
        val fotos = rutas.mapNotNull { BitmapFactory.decodeFile(it) }
        if (fotos.isEmpty()) { finish(); return }
        Imprimir.fotos(this, fotos, intent.getStringExtra(EXTRA_NOMBRE) ?: "PixPin")
        lanzado = true
    }

    override fun onResume() {
        super.onResume()
        // La primera vez es al abrir; la segunda, al volver del diálogo de impresión.
        if (lanzado && vuelta++ > 0) finish()
    }

    private var vuelta = 0

    companion object {
        private const val EXTRA_RUTAS = "rutas"
        private const val EXTRA_NOMBRE = "nombre"

        fun abrir(c: Context, rutas: List<String>, nombre: String) {
            if (rutas.isEmpty()) return
            c.startActivity(Intent(c, ImprimirFotosActivity::class.java).putExtra(EXTRA_RUTAS, rutas.toTypedArray()).putExtra(EXTRA_NOMBRE, nombre)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
