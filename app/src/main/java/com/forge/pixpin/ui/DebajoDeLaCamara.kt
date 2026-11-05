package com.forge.pixpin.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed

/**
 * **Lo de arriba de un visor, debajo de la cámara** (5-nov-2026, el usuario: «la barra superior de las
 * visualizaciones: unas están debajo de la cámara y otras sobre ella; muévelas bajo la cámara»). Con la barra de
 * estado escondida (pantalla completa) su hueco es cero y `statusBarsPadding` dejaba la barra encima del agujero de
 * la cámara. Aquí cuenta el mayor de los dos: la barra de estado o el recorte de la cámara.
 */
val HuecoDeArriba: WindowInsets
    @androidx.compose.runtime.Composable get() = WindowInsets.statusBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Top)

fun Modifier.debajoDeLaCamara(): Modifier = composed { windowInsetsPadding(HuecoDeArriba) }
