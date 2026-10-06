package com.forge.pixpin.ajustes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.forge.pixpin.PixPinApp
import com.forge.pixpin.ajustes.CatalogoDeAjustes.Opcion
import com.forge.pixpin.ajustes.CatalogoDeAjustes.Seccion
import com.forge.pixpin.data.Settings
import com.forge.pixpin.motor.ALL_TOOLS
import com.forge.pixpin.motor.HerramientasPorSitio.Sitio
import com.forge.pixpin.motor.iconFor
import com.forge.pixpin.motor.labelFor
import kotlinx.coroutines.launch

/** El azul del punto de «distinto de fábrica», el mismo del PC (`AZUL`, 0A84FF). */
private val AZUL = androidx.compose.ui.graphics.Color(0xFF0A84FF)

/**
 * **Los ajustes v2** (5-oct-2026, como «Ajustes v2» del PC, commit 8be72d8): arriba el buscador y
 * «Deshacer»; debajo las secciones, cada una con su resumen y un punto azul si algo suyo no está
 * como vino; dentro de una sección, «Restablecer <sección>» y sus tarjetas, cada una con su punto
 * azul y su botón de volver a fábrica cuando se ha movido.
 *
 * Las tarjetas son las de siempre —[tarjeta] las pinta por su id de [CatalogoDeAjustes]—, así que
 * no se pierde ninguna opción; lo nuevo es cómo se llega a ellas. Buscando, salen las de **todas**
 * las secciones que casen, cada tanda bajo el nombre de la suya.
 *
 * Solo se compone lo que se ve: una sección cerrada no pinta sus tarjetas (algunas leen del disco
 * al componerse), igual que con los grupos plegables de antes.
 */
@Composable
fun AjustesV2(
    onVolver: () -> Unit,
    pie: @Composable () -> Unit,
    tarjeta: @Composable (id: String) -> Unit
) {
    val app = LocalContext.current.applicationContext as PixPinApp
    val scope = rememberCoroutineScope()
    val ajustes by app.settings.settings.collectAsState(initial = Settings())
    var busqueda by rememberSaveable { mutableStateOf("") }
    var abierta by rememberSaveable { mutableStateOf<String?>(null) }
    val seccion = abierta?.let { n -> Seccion.entries.firstOrNull { it.name == n } }

    // **Deshacer**: se mira lo guardado y se apunta cómo estaba antes de cada cambio. Vive lo que
    // vive la pantalla, como en el PC (cerrar los ajustes es darlos por buenos).
    val historial = remember { HistorialDeAjustes(CatalogoDeAjustes.CLAVES) }
    var hayDeshacer by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        app.settings.crudo.collect { historial.observar(it); hayDeshacer = historial.hayAlgo }
    }

    BackHandler(enabled = busqueda.isNotEmpty() || seccion != null) {
        if (busqueda.isNotEmpty()) busqueda = "" else abierta = null
    }

    Scaffold { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { if (seccion != null && busqueda.isEmpty()) abierta = null else onVolver() },
                    modifier = Modifier.padding(end = 4.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(com.forge.pixpin.R.string.cd_close))
                }
                Text(
                    if (seccion != null && busqueda.isEmpty()) seccion.titulo else stringResource(com.forge.pixpin.R.string.ajustes_titulo),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f)
                )
                // Deshacer: siempre en el mismo sitio, apagado si no hay nada.
                TextButton(
                    enabled = hayDeshacer,
                    onClick = { historial.deshacer()?.let { antes -> scope.launch { app.settings.restaurar(antes, CatalogoDeAjustes.CLAVES) } } }
                ) {
                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Deshacer", maxLines = 1)
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = busqueda,
                onValueChange = { busqueda = it.take(120) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Buscar en los ajustes") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = {
                    if (busqueda.isNotEmpty()) IconButton(onClick = { busqueda = "" }) { Icon(Icons.Filled.Close, "Borrar") }
                },
                singleLine = true,
                shape = RoundedCornerShape(18.dp)
            )
            Spacer(Modifier.height(16.dp))

            when {
                busqueda.isNotBlank() -> Resultados(busqueda, ajustes, tarjeta)
                seccion == null -> {
                    for (s in Seccion.entries) FilaDeSeccion(s, ajustes) { abierta = s.name }
                    pie()
                }
                else -> ContenidoDeSeccion(seccion, ajustes, tarjeta)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun FilaDeSeccion(s: Seccion, ajustes: Settings, alTocar: () -> Unit) {
    val algoMovido = CatalogoDeAjustes.deSeccion(s).any { CatalogoDeAjustes.cambiada(it, ajustes) }
    Card(Modifier.fillMaxWidth().clickable(onClick = alTocar)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.titulo, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    if (algoMovido) Punto(Modifier.padding(start = 8.dp))
                }
                Text(
                    s.resumen,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun ContenidoDeSeccion(s: Seccion, ajustes: Settings, tarjeta: @Composable (String) -> Unit) {
    val app = LocalContext.current.applicationContext as PixPinApp
    val scope = rememberCoroutineScope()
    val claves = CatalogoDeAjustes.clavesARestablecer(s, ajustes)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            s.resumen,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).padding(end = 8.dp)
        )
        // «Restablecer <sección>»: solo lo de esta sección, y solo si hay algo movido.
        OutlinedButton(enabled = claves.isNotEmpty(), onClick = { scope.launch { app.settings.quitar(claves) } }) {
            Text("Restablecer ${s.titulo}", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    Spacer(Modifier.height(12.dp))
    val opciones = CatalogoDeAjustes.deSeccion(s)
    for (o in opciones) {
        if (o.id == "herramientas" || o.id.startsWith("barra-")) continue
        ConPunto(o, ajustes) { tarjeta(o.id) }
    }
    if (s == Seccion.DIBUJAR) HerramientasPorSitioCard(ajustes, tarjeta)
}

@Composable
private fun Resultados(busqueda: String, ajustes: Settings, tarjeta: @Composable (String) -> Unit) {
    val halladas = remember(busqueda) { CatalogoDeAjustes.buscar(busqueda) }
    if (halladas.isEmpty()) {
        Text(
            "Nada con «${busqueda.trim()}»",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(8.dp)
        )
        return
    }
    for ((s, opciones) in halladas) {
        Text(
            s.titulo,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )
        for (o in opciones) ConPunto(o, ajustes) { tarjeta(o.id) }
    }
}

/**
 * Una tarjeta con lo del PC por opción: si se ha movido de fábrica, debajo el **punto azul** y
 * «De fábrica» para volver. Debajo y no encima para que la tarjeta no salte al tocarla.
 */
@Composable
private fun ConPunto(o: Opcion, ajustes: Settings, contenido: @Composable () -> Unit) {
    val app = LocalContext.current.applicationContext as PixPinApp
    val scope = rememberCoroutineScope()
    contenido()
    if (CatalogoDeAjustes.cambiada(o, ajustes)) {
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            Punto()
            Text(
                "Cambiado",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp)
            )
            TextButton(onClick = { scope.launch { app.settings.quitar(o.claves) } }) { Text("De fábrica", maxLines = 1) }
        }
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun Punto(modifier: Modifier = Modifier) {
    Box(modifier.size(8.dp).clip(CircleShape).background(AZUL))
}

/**
 * **Qué herramientas salen en cada sitio** (como el PC, 55db60d): arriba, dónde —en todos a la vez
 * o en uno—; debajo, en «Todos» la lista general de encender y apagar, y en un sitio su barra de
 * siempre (arrastrando, que también dice cómo se agrupan). El sitio elegido no se guarda: es por
 * dónde se mira, no un ajuste.
 */
@Composable
private fun HerramientasPorSitioCard(ajustes: Settings, tarjeta: @Composable (String) -> Unit) {
    var donde by rememberSaveable { mutableStateOf<String?>(null) }
    val sitio = donde?.let { n -> Sitio.entries.firstOrNull { it.name == n } }
    Text(
        "Herramientas: dónde",
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(start = 4.dp)
    )
    Text(
        "En todos los sitios a la vez o en uno solo, para que no salgan siempre todas.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 2.dp)
    )
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        FilterChip(selected = sitio == null, onClick = { donde = null }, label = { Text("Todos") })
        for (s in Sitio.entries) {
            val id = "barra-${s.nombre}"
            FilterChip(
                selected = sitio == s,
                onClick = { donde = s.name },
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(s.rotulo)
                        CatalogoDeAjustes.porId(id)?.let { if (CatalogoDeAjustes.cambiada(it, ajustes)) Punto(Modifier.padding(start = 6.dp)) }
                    }
                }
            )
        }
    }
    val id = if (sitio == null) "herramientas" else "barra-${sitio.nombre}"
    CatalogoDeAjustes.porId(id)?.let { o -> ConPunto(o, ajustes) { tarjeta(id) } }
}

/**
 * La lista general: una apagada aquí no sale en ninguna barra. Encender en un sitio una apagada aquí
 * la deja solo en ese sitio (ver [com.forge.pixpin.motor.HerramientasPorSitio]).
 */
@Composable
fun HerramientasEnTodosCard() {
    val app = LocalContext.current.applicationContext as PixPinApp
    val scope = rememberCoroutineScope()
    val ajustes by app.settings.settings.collectAsState(initial = Settings())
    val reparto = ajustes.reparto
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Herramientas en todos los sitios", style = MaterialTheme.typography.titleSmall)
            Text(
                "Una apagada aquí no sale en ninguna barra: ni en el lienzo, ni en el pin, ni en la pantalla, ni en el lector.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
            )
            for (t in ALL_TOOLS) {
                val activa = reparto.activa(t)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { scope.launch { app.settings.setReparto(reparto, reparto.alternarEnTodos(t)) } }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(iconFor(t), null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        stringResource(labelFor(t)),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f).padding(start = 12.dp)
                    )
                    Switch(
                        checked = activa,
                        onCheckedChange = { scope.launch { app.settings.setReparto(reparto, reparto.alternarEnTodos(t)) } }
                    )
                }
            }
        }
    }
}
