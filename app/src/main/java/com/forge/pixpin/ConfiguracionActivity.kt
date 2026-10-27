package com.forge.pixpin

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.forge.pixpin.ui.theme.PixPinTheme

/**
 * **La configuración y los ajustes, en su pantalla** (26-oct-2026). Eran la primera tarjeta del
 * montón de proyectos ([TarjetaDeConfiguracion]); sin esa pantalla, viven aquí y se llega desde el
 * engranaje de la lista de chats y desde «Ajustes» en los ⋮ del chat.
 *
 * Desde el 27-oct-2026 se abre directa en los ajustes, con la bienvenida arriba (ver
 * [TarjetaDeConfiguracion]).
 *
 * Es otra actividad y no un estado de la principal porque la principal es `singleTask`: abrirla
 * desde un chat **borraría el chat que hay encima** y atrás ya no volvería a él.
 */
class ConfiguracionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PixPinTheme {
                // **Directo a los ajustes** (27-oct-2026, pedido del usuario): el engranaje abría
                // antes la bienvenida y había que pulsar «Ajustes» otra vez. La bienvenida, con
                // «Comenzar» y los permisos que falten, va ahora encima de las secciones.
                PantallaDeAjustes(
                    onVolver = { finish() },
                    cabecera = {
                        TarjetaDeConfiguracion(Modifier.fillMaxWidth().padding(bottom = 16.dp))
                    }
                )
            }
        }
    }

    override fun finish() {
        // Abierta de raíz (por ejemplo, la principal la abrió y luego se cerró), atrás va a la
        // lista de chats y no al escritorio. Ver [Volver].
        Volver.alCerrar(this, desdeDentro = true)
        super.finish()
    }

    companion object {
        fun abrir(context: android.content.Context) {
            runCatching {
                context.startActivity(
                    Intent(context, ConfiguracionActivity::class.java)
                        .also { if (context !is android.app.Activity) it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                )
            }
        }
    }
}
