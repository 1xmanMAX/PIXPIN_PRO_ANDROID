package com.forge.pixpin.mini

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.forge.pixpin.R
import com.forge.pixpin.guardados.MensajesStore
import com.forge.pixpin.ui.theme.PixPinTheme
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * La pantalla de una mini-aplicación: la lista de tareas, los gastos.
 *
 * ## Por qué es una pantalla y no una burbuja que se edita en el sitio
 *
 * Porque se escribe. Editar dentro de la conversación obliga a convivir con el teclado, la
 * lista desplazándose y el campo de escribir de abajo, que es otro sitio donde escribir a
 * dos centímetros: dos campos de texto a la vez en la misma pantalla es la receta de
 * escribir la compra en el chat y el mensaje en la compra.
 *
 * ## Se guarda solo, y a cada cambio
 *
 * No hay botón de guardar. Marcar una tarea es un gesto de medio segundo y nadie va a
 * confirmarlo después; si hubiera que hacerlo, la mitad de las listas se quedarían sin
 * guardar. Escribir el documento entero cuesta poco —es texto— y va al hilo de disco.
 */
class MiniActivity : ComponentActivity() {

    private lateinit var almacen: MensajesStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        almacen = MensajesStore(this)
        val id = intent.getStringExtra(EXTRA_MENSAJE)
        if (id == null) {
            finish()
            return
        }
        setContent { PixPinTheme { Pantalla(id) } }
    }

    @Composable
    private fun Pantalla(id: String) {
        // El documento vive aquí mientras se edita, y el archivo se actualiza detrás. Al
        // revés —releer el archivo tras cada cambio— la lista parpadearía a cada letra.
        var documento by remember { mutableStateOf<String?>(null) }
        var cual by remember { mutableStateOf<MiniApp?>(null) }

        androidx.compose.runtime.LaunchedEffect(id) {
            val m = withContext(Dispatchers.IO) { almacen.leer() }.firstOrNull { it.id == id }
            if (m == null) {
                finish()
                return@LaunchedEffect
            }
            cual = MiniApp.de(m.miniapp)
            documento = m.texto
        }

        fun guardar(nuevo: String) {
            documento = nuevo
            lifecycleScope.launch(Dispatchers.IO) {
                val todos = almacen.leer()
                almacen.reescribir(
                    todos.map { if (it.id == id) it.copy(texto = nuevo) else it }
                )
            }
        }

        val doc = documento
        val app = cual
        Scaffold(
            topBar = {
                // **Con el hueco de la barra de estado.**
                //
                // Desde Android 15 la ventana va de borde a borde por defecto, así que
                // sin esto el título quedaba **debajo del reloj y la batería**: se veían
                // encima las letras del sistema. `TopAppBar` lo hace solo, pero esta
                // barra es a mano, y lo hecho a mano tiene que pedir su hueco.
                Surface(shadowElevation = 2.dp) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { finish() }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = getString(R.string.cd_close)
                            )
                        }
                        // El título se edita aquí mismo: es una línea, y mandarla a un
                        // diálogo aparte para cambiar una palabra sería más trabajo que
                        // el que uno viene a hacer.
                        TextField(
                            value = doc?.let { Cabecera.titulo(it) }.orEmpty(),
                            onValueChange = { nuevo ->
                                val actual = doc ?: return@TextField
                                guardar(Cabecera.linea(nuevo) + Cabecera.cuerpo(actual))
                            },
                            singleLine = true,
                            placeholder = { Text(getString(R.string.miniapp_titulo_nuevo)) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        ) { hueco ->
            if (doc == null || app == null) return@Scaffold
            Column(Modifier.fillMaxSize().padding(hueco)) {
                when (app) {
                    MiniApp.TAREAS -> DeTareas(doc, ::guardar)
                    MiniApp.GASTOS -> DeGastos(doc, ::guardar)
                    MiniApp.CRONOMETRO -> DeCronometro(doc, ::guardar)
                    MiniApp.TEMPORIZADOR -> DeTemporizador(id, doc, ::guardar)
                    MiniApp.ALARMA -> DeAlarma(id, doc, ::guardar)
                    MiniApp.CONTADOR -> DeContador(doc, ::guardar)
                    MiniApp.RULETA -> DeRuleta(doc, ::guardar)
                }
            }
        }
    }

    /** La lista de tareas: casillas que se marcan y una línea para añadir. */
    @Composable
    private fun DeTareas(documento: String, onGuardar: (String) -> Unit) {
        val titulo = Cabecera.titulo(documento)
        val tareas = remember(documento) { Tareas.leer(documento) }
        var escrito by remember { mutableStateOf("") }

        fun conLasTareas(nuevas: List<Tarea>) = onGuardar(Tareas.escribir(titulo, nuevas))

        Column(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.weight(1f)) {
                if (tareas.isEmpty()) {
                    item { Nada() }
                }
                itemsIndexed(tareas, key = { i, _ -> i }) { i, t ->
                    Row(
                        Modifier.fillMaxWidth().padding(end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = t.hecha,
                            onCheckedChange = { conLasTareas(Tareas.alternar(tareas, i)) }
                        )
                        // Lo hecho se tacha en vez de irse: ver lo tachado es la mitad de
                        // la satisfacción de una lista, y además dice lo que ya no hace
                        // falta volver a pensar.
                        Text(
                            t.texto,
                            fontSize = 16.sp,
                            textDecoration = if (t.hecha) TextDecoration.LineThrough else null,
                            color = if (t.hecha) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { conLasTareas(Tareas.borrar(tareas, i)) }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = getString(R.string.cd_delete),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
            LineaParaAnadir(
                valor = escrito,
                onValor = { escrito = it },
                onAnadir = {
                    if (escrito.isNotBlank()) {
                        conLasTareas(Tareas.anadir(tareas, escrito))
                        escrito = ""
                    }
                }
            )
        }
    }

    /** Los gastos: concepto, importe y **el total**, que es a lo que se viene. */
    @Composable
    private fun DeGastos(documento: String, onGuardar: (String) -> Unit) {
        val idioma = resources.configuration.locales[0] ?: Locale.getDefault()
        val libro = remember(documento) { Gastos.leer(documento, idioma) }
        var concepto by remember { mutableStateOf("") }
        var importe by remember { mutableStateOf("") }

        Column(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.weight(1f)) {
                if (libro.gastos.isEmpty()) {
                    item { Nada() }
                }
                itemsIndexed(libro.gastos, key = { i, _ -> i }) { i, g ->
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp,
                                                        top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            g.concepto,
                            fontSize = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            Gastos.textoDeImporte(g.centimos, libro.moneda, idioma),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        )
                        IconButton(onClick = {
                            onGuardar(Gastos.escribir(Gastos.borrar(libro, i), idioma))
                        }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = getString(R.string.cd_delete),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
            // El total, separado y en grande: es el número por el que se abre esto.
            HorizontalDivider()
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(getString(R.string.miniapp_total), fontSize = 16.sp)
                Text(
                    Gastos.textoDeImporte(libro.total, libro.moneda, idioma),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Surface(shadowElevation = 8.dp) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .imePadding()
                        .navigationBarsPadding()
                        .padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextField(
                        value = concepto,
                        onValueChange = { concepto = it },
                        singleLine = true,
                        placeholder = { Text(getString(R.string.miniapp_concepto)) },
                        modifier = Modifier.weight(1.6f)
                    )
                    // **El menos, en un botón.**
                    //
                    // El teclado decimal de Android **no trae signo menos**, así que una
                    // devolución no se podía escribir: había que teclear el importe y
                    // buscarse la vida. El botón lo pone y lo quita, y así además se ve
                    // de un vistazo si lo que se va a añadir suma o resta.
                    val enNegativo = importe.trimStart().startsWith("-")
                    androidx.compose.material3.TextButton(
                        onClick = {
                            importe = if (enNegativo) importe.trimStart().removePrefix("-")
                            else "-" + importe.trimStart()
                        }
                    ) {
                        Text(
                            if (enNegativo) "−" else "+",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (enNegativo) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary
                        )
                    }
                    TextField(
                        value = importe,
                        onValueChange = { importe = it },
                        singleLine = true,
                        // Teclado de números con coma: escribir «12,50» con el teclado de
                        // letras es cambiar de plano dos veces por cada gasto.
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal
                        ),
                        placeholder = { Text(getString(R.string.miniapp_importe)) },
                        modifier = Modifier.weight(1f).padding(start = 4.dp)
                    )
                    IconButton(onClick = {
                        val centimos = Gastos.centimosDe(importe, libro.decimales)
                        // Sin importe legible no se añade nada: una línea de gasto sin
                        // número no suma, y una cuenta con líneas que no suman engaña.
                        if (concepto.isNotBlank() && centimos != null) {
                            onGuardar(
                                Gastos.escribir(
                                    Gastos.anadir(libro, concepto, centimos), idioma
                                )
                            )
                            concepto = ""
                            importe = ""
                        }
                    }) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = getString(R.string.miniapp_anadir),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }

    /**
     * El cronómetro.
     *
     * El número se repinta **diez veces por segundo mientras corre**, y ni una sola vez
     * mientras está parado: un reloj parado que se recompone es batería tirada. Y el
     * número no se guarda en cada latido —se guarda el instante de arranque, ver
     * [Tiempos]—, así que esos latidos no tocan el disco.
     */
    @Composable
    private fun DeCronometro(documento: String, onGuardar: (String) -> Unit) {
        val titulo = Cabecera.titulo(documento)
        val c = remember(documento) { Tiempos.leerCronometro(documento) }
        var ahora by remember { mutableStateOf(System.currentTimeMillis()) }
        androidx.compose.runtime.LaunchedEffect(c.corriendo) {
            while (c.corriendo) {
                ahora = System.currentTimeMillis()
                kotlinx.coroutines.delay(100)
            }
        }
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                Tiempos.comoSeLee(c.transcurrido(ahora)),
                fontSize = 56.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 24.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                androidx.compose.material3.Button(onClick = {
                    val t = System.currentTimeMillis()
                    onGuardar(
                        Tiempos.escribirCronometro(
                            titulo,
                            if (c.corriendo) Tiempos.parar(c, t) else Tiempos.arrancar(c, t)
                        )
                    )
                }) {
                    Text(
                        getString(
                            if (c.corriendo) R.string.miniapp_parar
                            else R.string.miniapp_arrancar
                        )
                    )
                }
                androidx.compose.material3.OutlinedButton(
                    onClick = {
                        onGuardar(
                            Tiempos.escribirCronometro(
                                titulo, Tiempos.vuelta(c, System.currentTimeMillis())
                            )
                        )
                    },
                    enabled = c.corriendo
                ) { Text(getString(R.string.miniapp_vuelta)) }
                androidx.compose.material3.OutlinedButton(onClick = {
                    onGuardar(Tiempos.escribirCronometro(titulo, Tiempos.reiniciar(c)))
                }) { Text(getString(R.string.miniapp_reiniciar)) }
            }
            LazyColumn(Modifier.padding(top = 20.dp)) {
                itemsIndexed(c.vueltas.reversed()) { i, v ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("${c.vueltas.size - i}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(Tiempos.comoSeLee(v), fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }

    /** El temporizador: lo que falta, en grande, y la alarma puesta de verdad. */
    @Composable
    private fun DeTemporizador(id: String, documento: String, onGuardar: (String) -> Unit) {
        val titulo = Cabecera.titulo(documento)
        val t = remember(documento) { Tiempos.leerTemporizador(documento) }
        var ahora by remember { mutableStateOf(System.currentTimeMillis()) }
        androidx.compose.runtime.LaunchedEffect(t.corriendo) {
            while (t.corriendo) {
                ahora = System.currentTimeMillis()
                kotlinx.coroutines.delay(200)
            }
        }
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                Tiempos.comoSeLeeCorto(t.restante(ahora)),
                fontSize = 56.sp,
                fontWeight = FontWeight.Bold,
                color = if (t.vencido(ahora)) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(vertical = 24.dp)
            )
            // Sumar y restar minutos, que es como se pone un temporizador de cocina.
            // Un selector de hora para «cinco minutos» son cuatro toques y una pantalla.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(-5, -1, 1, 5).forEach { minutos ->
                    androidx.compose.material3.OutlinedButton(onClick = {
                        onGuardar(
                            Tiempos.escribirTemporizador(
                                titulo,
                                Tiempos.conDuracion(
                                    t, t.duracion + minutos * 60_000L,
                                    System.currentTimeMillis()
                                )
                            )
                        )
                    }) { Text(if (minutos > 0) "+$minutos" else "$minutos") }
                }
            }
            androidx.compose.material3.Button(
                onClick = {
                    val cuando = System.currentTimeMillis()
                    val nuevo = if (t.corriendo) {
                        com.forge.pixpin.pin.Recordatorios.quitar(this@MiniActivity, aviso(id))
                        Tiempos.detener(t)
                    } else {
                        Tiempos.lanzar(t, cuando).also { lanzado ->
                            lanzado.finEn?.let {
                                com.forge.pixpin.pin.Recordatorios.poner(
                                    this@MiniActivity, aviso(id), it
                                )
                            }
                        }
                    }
                    onGuardar(Tiempos.escribirTemporizador(titulo, nuevo))
                },
                modifier = Modifier.padding(top = 20.dp)
            ) {
                Text(
                    getString(
                        if (t.corriendo) R.string.miniapp_parar else R.string.miniapp_arrancar
                    )
                )
            }
        }
    }

    /** La alarma: una hora y un interruptor. */
    @Composable
    private fun DeAlarma(id: String, documento: String, onGuardar: (String) -> Unit) {
        val titulo = Cabecera.titulo(documento)
        val a = remember(documento) { Tiempos.leerAlarma(documento) }

        fun conLaAlarma(nueva: Alarma) {
            // La alarma del sistema se rehace entera en cada cambio: quitarla y volver a
            // ponerla es una llamada, y así no hay forma de que quede una vieja sonando a
            // una hora que ya nadie pidió.
            com.forge.pixpin.pin.Recordatorios.quitar(this, aviso(id))
            if (nueva.activa) {
                com.forge.pixpin.pin.Recordatorios.poner(
                    this, aviso(id),
                    com.forge.pixpin.pin.proximaVezQueSean(
                        System.currentTimeMillis(), nueva.hora, nueva.minuto,
                        java.util.TimeZone.getDefault()
                    )
                )
            }
            onGuardar(Tiempos.escribirAlarma(titulo, nueva))
        }

        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "${a.hora}:${a.minuto.toString().padStart(2, '0')}",
                fontSize = 64.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 24.dp)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(getString(R.string.miniapp_activa), fontSize = 16.sp)
                androidx.compose.material3.Switch(
                    checked = a.activa,
                    onCheckedChange = { conLaAlarma(a.copy(activa = it)) },
                    modifier = Modifier.padding(start = 12.dp)
                )
            }
            Row(
                Modifier.padding(top = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                androidx.compose.material3.OutlinedButton(onClick = {
                    conLaAlarma(a.copy(hora = (a.hora + 23) % 24))
                }) { Text("-1 h") }
                androidx.compose.material3.OutlinedButton(onClick = {
                    conLaAlarma(a.copy(hora = (a.hora + 1) % 24))
                }) { Text("+1 h") }
                androidx.compose.material3.OutlinedButton(onClick = {
                    conLaAlarma(a.copy(minuto = (a.minuto + 55) % 60))
                }) { Text("-5 min") }
                androidx.compose.material3.OutlinedButton(onClick = {
                    conLaAlarma(a.copy(minuto = (a.minuto + 5) % 60))
                }) { Text("+5 min") }
            }
        }
    }

    /** El identificador con el que esta mini-app pide hora al sistema. */
    private fun aviso(id: String): String =
        com.forge.pixpin.pin.RecordatorioReceiver.DE_UNA_MINIAPP + id

    /**
     * El contador: un número grande y dos botones grandes.
     *
     * Grandes de verdad, porque se usa sin mirar: contando cajas o vueltas, el dedo va al
     * botón mientras la vista está en otro sitio. Un botón de icono de 24 puntos ahí es
     * un fallo de cuenta.
     */
    @Composable
    private fun DeContador(documento: String, onGuardar: (String) -> Unit) {
        val titulo = Cabecera.titulo(documento)
        val c = remember(documento) { Contador.leer(documento) }
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                c.valor.toString(),
                fontSize = 72.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 20.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                androidx.compose.material3.OutlinedButton(
                    onClick = { onGuardar(Contador.escribir(titulo, Contador.menos(c))) },
                    modifier = Modifier.size(96.dp)
                ) { Text("−", fontSize = 34.sp) }
                androidx.compose.material3.Button(
                    onClick = { onGuardar(Contador.escribir(titulo, Contador.mas(c))) },
                    modifier = Modifier.size(96.dp)
                ) { Text("+", fontSize = 34.sp) }
            }
            Row(
                Modifier.padding(top = 28.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(getString(R.string.miniapp_paso), fontSize = 14.sp)
                listOf(1L, 5L, 10L, 12L).forEach { paso ->
                    androidx.compose.material3.TextButton(onClick = {
                        onGuardar(Contador.escribir(titulo, Contador.conPaso(c, paso)))
                    }) {
                        Text(
                            "$paso",
                            fontWeight = if (c.paso == paso) FontWeight.Bold else FontWeight.Normal,
                            color = if (c.paso == paso) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            androidx.compose.material3.TextButton(
                onClick = { onGuardar(Contador.escribir(titulo, Contador.reiniciar(c))) }
            ) { Text(getString(R.string.miniapp_reiniciar)) }
        }
    }

    /**
     * La ruleta: los nombres y el sorteo.
     *
     * El sorteo usa el mismo [Ruleta] que el pin, así que reparte igual desde los dos
     * sitios. Al que sale se le puede quitar de la lista, que es lo que uno hace cuando
     * está repartiendo turnos y no quiere que repita.
     */
    @Composable
    private fun DeRuleta(documento: String, onGuardar: (String) -> Unit) {
        val titulo = Cabecera.titulo(documento)
        val nombres = remember(documento) { RuletaDoc.leer(documento) }
        var escrito by remember { mutableStateOf("") }
        var tocado by remember { mutableStateOf<Int?>(null) }

        Column(Modifier.fillMaxSize()) {
            tocado?.let { i ->
                nombres.getOrNull(i)?.let { quien ->
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                quien,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            androidx.compose.material3.TextButton(onClick = {
                                onGuardar(
                                    RuletaDoc.escribir(titulo, RuletaDoc.quitar(nombres, i))
                                )
                                tocado = null
                            }) { Text(getString(R.string.cd_delete)) }
                        }
                    }
                }
            }
            LazyColumn(Modifier.weight(1f)) {
                if (nombres.isEmpty()) {
                    item { Nada() }
                }
                itemsIndexed(nombres, key = { i, _ -> i }) { i, nombre ->
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            nombre,
                            fontSize = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (tocado == i) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = {
                            onGuardar(RuletaDoc.escribir(titulo, RuletaDoc.quitar(nombres, i)))
                            tocado = null
                        }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = getString(R.string.cd_delete),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
            androidx.compose.material3.Button(
                onClick = {
                    // El azar entra por fuera, como en el pin: así el sorteo se puede
                    // comprobar de verdad en vez de girar mil veces y confiar.
                    tocado = Ruleta.elegir(nombres) { Math.random() }
                },
                enabled = nombres.size >= 2,
                modifier = Modifier.fillMaxWidth().padding(16.dp)
            ) { Text(getString(R.string.miniapp_sortear)) }
            LineaParaAnadir(
                valor = escrito,
                onValor = { escrito = it },
                onAnadir = {
                    if (escrito.isNotBlank()) {
                        onGuardar(RuletaDoc.escribir(titulo, RuletaDoc.anadir(nombres, escrito)))
                        escrito = ""
                    }
                }
            )
        }
    }

    /** Recién creada y sin nada dentro. */
    @Composable
    private fun Nada() {
        Text(
            getString(R.string.miniapp_sin_nada),
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(32.dp)
        )
    }

    /** La línea de abajo para añadir una cosa más. */
    @Composable
    private fun LineaParaAnadir(
        valor: String,
        onValor: (String) -> Unit,
        onAnadir: () -> Unit
    ) {
        Surface(shadowElevation = 8.dp) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextField(
                    value = valor,
                    onValueChange = onValor,
                    singleLine = true,
                    placeholder = { Text(getString(R.string.miniapp_anadir)) },
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onAnadir) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = getString(R.string.miniapp_anadir),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }

    companion object {
        private const val EXTRA_MENSAJE = "mini_mensaje"

        /** Abre la mini-app de ese mensaje. */
        fun abrir(context: Context, idDelMensaje: String) {
            context.startActivity(
                Intent(context, MiniActivity::class.java)
                    .putExtra(EXTRA_MENSAJE, idDelMensaje)
            )
        }
    }
}
