package com.forge.pixpin.guardados

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forge.pixpin.jev.Cliente
import com.forge.pixpin.jev.DictadoAlChat
import com.forge.pixpin.jev.ErrorJev
import com.forge.pixpin.jev.Jev
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * **Dictar al chat** (29-oct-2026; `apps/pixpin/src/ventana_chat/dictado_a_chat.rs` del PC, `15811c76`):
 * dictando en la caja del chat (el botón 🗣 de la caja, el reconocedor del teléfono frase a frase),
 * «anota en el chat de la obra que faltan tornillos» o «anota esto en el chat de la obra» **propone**
 * anotarlo allí.
 *
 * La lógica es la del PC, sin tocar ([DictadoAlChat]): por cada frase dictada (tres palabras o más), una
 * petición con la parte `chats` (los nombres de los 30 chats más recientes, sin el abierto ni el del
 * asistente) y la tabla `dictado::decidir`. El chat lo elige Jev; el texto **no se genera**: el código
 * saca los candidatos (lo que sigue a «:» o a «que», o la frase anterior para «esto»; la anterior es la
 * dictada antes o, si es la primera, lo que ya había escrito en la caja) y Jev elige cuál.
 *
 * Lo decidido es **una propuesta, nunca hecho solo**: encima de la caja sale la chapa «Anotar en «Obra
 * Lima»: faltan tornillos». Tocarla lo anota (una nota en ese chat, como escrita allí; el chat sube en
 * la lista) y quita de la caja lo dictado para pedirlo; la ✕ la quita. Sin chat dicho («anótalo, que
 * la grúa se va el viernes») la chapa pregunta en qué chat, y tocarla abre la lista de chats.
 *
 * **Sin clave o con el interruptor apagado no sale ni el botón**: el chat es el de siempre. Si Jev falla
 * (sin red, 401…), lo dictado se queda en la caja y se dice por qué, abajo y un momento; nunca es un
 * «no». Una petición en vuelo como mucho; lo que se dicta mientras tanto espera (las tres últimas), 4 s
 * por petición. **Nada nuevo viaja**: la nota anotada es un mensaje normal y la propuesta sin aceptar no
 * sale del teléfono. Lo prueba `DictadoYRepasoTest`.
 */
object DictarAlChat {

    /** Cuántos chats van de opciones: los más recientes. */
    const val CHATS = 30

    /** Lo que se espera a Jev por frase (la app usa 2-4 s). */
    const val TIEMPO_MS = 4_000L

    /** Las frases que esperan mientras una está en vuelo. */
    const val EN_COLA = 3

    /** Quien dicta (solo va en el estado: «frase.quien»). */
    const val QUIEN = "Yo"

    /** **Lo que se propone anotar.** */
    data class Propuesta(
        /** El chat elegido: (id, nombre). null: no se dijo, se pregunta. */
        val chat: Pair<String, String>?,
        val texto: String,
        /**
         * Lo que se quita de la caja al anotarlo: la frase que lo pidió y, si el texto era la frase
         * anterior, también ella (ya va al otro chat).
         */
        val quitar: List<String>
    )

    /**
     * **Pega una frase dictada detrás de lo escrito** (`juntar_dictado` del PC), con un espacio si hace
     * falta y la primera letra en mayúscula si empieza frase (en minúscula si no).
     */
    fun juntar(escrito: String, frase0: String): String {
        val frase = frase0.trim()
        if (frase.isEmpty()) return escrito
        val ultima = escrito.trimEnd().lastOrNull()
        val empieza = ultima == null || ultima in ".?!\n"
        val primera = frase.substring(0, frase.offsetByCodePoints(0, 1))
        val resto = frase.substring(primera.length)
        val f = (if (empieza) primera.uppercase() else primera.lowercase()) + resto
        return if (escrito.isEmpty() || escrito.endsWith(' ') || escrito.endsWith('\n')) escrito + f else "$escrito $f"
    }

    /**
     * **Quita de lo escrito un trozo dictado** (`quitar_dicho` del PC): la última vez que sale, con el
     * espacio que lo separaba. Al dictarlo, su primera letra pudo cambiar de mayúscula a minúscula
     * ([juntar]): esa no cuenta. Lo que no está (lo borró a mano) deja la caja como estaba.
     */
    fun quitarDicho(escrito: String, trozo0: String): String {
        val trozo = trozo0.trim()
        if (trozo.isEmpty()) return escrito
        val primera = trozo.substring(0, trozo.offsetByCodePoints(0, 1))
        val resto = trozo.substring(primera.length)
        val sitios = mutableListOf<Int>()
        if (resto.isEmpty()) {
            for (i in 0..escrito.length) sitios += i
        } else {
            var i = escrito.indexOf(resto)
            while (i >= 0) { sitios += i; i = escrito.indexOf(resto, i + 1) }
        }
        for (i in sitios.asReversed()) {
            if (i == 0) continue
            val c = escrito.codePointBefore(i)
            val largo = Character.charCount(c)
            if (String(Character.toChars(c)).lowercase() != primera.lowercase()) continue
            val fuera = escrito.substring(0, i - largo).trimEnd(' ')
            val dentro0 = escrito.substring(i + resto.length)
            val dentro = dentro0.removePrefix(" ")
            return if (fuera.isEmpty() || dentro.isEmpty() || fuera.endsWith('\n')) fuera + dentro else "$fuera $dentro"
        }
        return escrito
    }

    /**
     * **Los chats que se ofrecen**: (id, nombre), los [CHATS] más recientes (por su último mensaje o por
     * cuándo se tocaron), sin [este] ni el del [asistente] ni los archivados o sin nombre.
     */
    fun chatsPara(
        proyectos: List<com.forge.pixpin.motor.Proyecto>,
        este: String?,
        asistente: String?,
        ultimoMensaje: Map<String, Long> = emptyMap()
    ): List<Pair<String, String>> =
        proyectos.asSequence()
            .filter { it.id != este && it.id != asistente && !it.archivado && it.nombre.isNotBlank() }
            .sortedByDescending { maxOf(it.tocado, ultimoMensaje[it.id] ?: 0L) }
            .take(CHATS)
            .map { it.id to it.nombre.trim() }
            .toList()

    /**
     * **De lo que dijo Jev a la propuesta** (lo de después de `vivo::preguntar` en `dictado_a_chat.rs`):
     * la tabla y, si anota, el chat por su índice (dos chats pueden llamarse igual) y lo que se quita de
     * la caja. null: nada que proponer (dudoso, sin texto o no).
     */
    fun propuestaDe(
        frase: DictadoAlChat.Frase,
        antes: List<DictadoAlChat.Frase>,
        chats: List<Pair<String, String>>,
        senal: DictadoAlChat.Senal?
    ): Propuesta? {
        val r = DictadoAlChat.decidir(frase, senal, chats.map { it.second })
        if (r !is DictadoAlChat.Resultado.Anotar) return null
        val n = r.nota
        val chat = if (n.chat != null && senal != null) chats.getOrNull(senal.chat) else null
        val quitar = mutableListOf(frase.dice)
        // Lo que no sale en la frase es la anterior («anota esto»).
        val anterior = antes.lastOrNull()
        if (anterior != null && !frase.dice.contains(n.texto.trimEnd('…'))) quitar += anterior.dice
        return Propuesta(chat, n.texto, quitar)
    }

    /** **El rótulo de la chapa** (`chat-anotar-en` y `chat-anotar-donde` del PC). */
    fun rotulo(p: Propuesta): String {
        val n = p.texto.codePointCount(0, p.texto.length)
        val corto = if (n > 90) p.texto.substring(0, p.texto.offsetByCodePoints(0, 90)) + "…" else p.texto
        return p.chat?.let { "Anotar en «${it.second}»: $corto" } ?: "¿En qué chat anoto «$corto»? Elegir chat"
    }

    /** El cliente de Jev para dictar: con clave y el interruptor encendido. Lee el disco: fuera del hilo principal. */
    fun cliente(context: Context): Cliente? {
        if (!Jev.encendida(context, Jev.Funcion.DICTADO)) return null
        val clave = Jev.clave(context) ?: return null
        return Cliente(clave, tiempoMs = TIEMPO_MS)
    }

    /** Si sale el botón de dictar: con la clave, el interruptor y algún reconocedor. Lee el disco. */
    fun disponible(context: Context): Boolean =
        Jev.hayClave(context) && Jev.encendida(context, Jev.Funcion.DICTADO) && EscuchaFraseAFrase.disponible(context)

    /**
     * **Anota la propuesta** en su chat: una nota como escrita allí (y el chat sube en la lista).
     * Trabajo de disco.
     */
    fun anotar(context: Context, chatId: String, texto: String) {
        AlChat.meterTexto(context, juntar("", texto), chatId)
    }

    /** El aviso de un fallo de Jev: lo dictado sigue en la caja y se dice por qué (nunca es un «no»). */
    fun avisoDeFallo(e: Throwable): String = when (e) {
        is ErrorJev.Red -> "Sin conexión: Jev no pudo mirar si había que anotarlo en otro chat. Lo dictado sigue en la caja."
        is ErrorJev -> "Jev no contestó (${e.message}). Lo dictado sigue en la caja."
        else -> "Jev no contestó. Lo dictado sigue en la caja."
    }

    /** Una frase por preguntar: ella, las de antes y los chats de entonces. */
    private class PorPreguntar(val frase: DictadoAlChat.Frase, val antes: List<DictadoAlChat.Frase>, val chats: List<Pair<String, String>>)

    /**
     * **El dictado al chat de la pantalla abierta**: lo dictado antes (para «esto»), la cola y lo
     * propuesto. Todo en el hilo principal; la petición, en el de fondo de [alcance].
     */
    class Anotar(private val context: Context, private val alcance: CoroutineScope) {
        /** Las frases dictadas antes (las dos últimas), para «esto». */
        private val antes = mutableListOf<DictadoAlChat.Frase>()
        private val cola = ArrayDeque<PorPreguntar>()
        private var enVuelo = false

        /** Lo que se propone anotar (la chapa). */
        var propuesta by mutableStateOf<Propuesta?>(null)

        /** Por qué no se pudo mirar (un fallo de Jev), un momento encima de la caja. */
        var aviso by mutableStateOf<String?>(null)

        /** Empieza un dictado nuevo: lo dictado antes ya no es «esto». */
        fun empezar() {
            antes.clear()
            cola.clear()
        }

        /**
         * **Una frase dictada**: [escritoAntes] es lo que había en la caja antes de ella (la anterior, si
         * es la primera del dictado); [chats], los de [chatsPara].
         */
        fun oir(dicho: String, escritoAntes: String, chats: List<Pair<String, String>>) {
            val frase = DictadoAlChat.Frase(System.currentTimeMillis(), QUIEN, dicho.trim())
            val previas = antes.toMutableList()
            if (previas.isEmpty() && escritoAntes.isNotBlank()) previas += DictadoAlChat.Frase(frase.ms, QUIEN, escritoAntes.trim())
            antes += frase
            while (antes.size > DictadoAlChat.ANTES) antes.removeAt(0)
            if (!DictadoAlChat.valeLaPena(frase) || chats.isEmpty()) return
            cola.addLast(PorPreguntar(frase, previas, chats))
            while (cola.size > EN_COLA) cola.removeFirst()
            lanzar()
        }

        /** La siguiente de la cola, si no hay ninguna en vuelo. */
        private fun lanzar() {
            if (enVuelo) return
            val p = cola.removeFirstOrNull() ?: return
            enVuelo = true
            alcance.launch {
                val r: Result<Propuesta?>? = withContext(Dispatchers.IO) {
                    val c = cliente(context) ?: return@withContext null
                    runCatching {
                        val senal = DictadoAlChat.preguntar(c, p.antes, p.frase, p.chats.map { it.second })
                        propuestaDe(p.frase, p.antes, p.chats, senal)
                    }
                }
                enVuelo = false
                when {
                    // Sin clave o apagado a medias: nada más que preguntar.
                    r == null -> cola.clear()
                    r.isSuccess -> r.getOrNull()?.let { propuesta = it }
                    else -> {
                        val e = r.exceptionOrNull()
                        runCatching { android.util.Log.i("PixPinJev", "el dictado al chat no se preguntó: ${e?.message}") }
                        aviso = avisoDeFallo(e ?: Exception())
                    }
                }
                lanzar()
            }
        }
    }
}

/**
 * **Lo de dictar encima de la caja**: lo que se va oyendo, la chapa de la propuesta y el aviso de un
 * fallo. Tocar la chapa llama a [alAnotar]; la ✕ la quita.
 */
@Composable
fun EncimaDeLaCaja(
    anotar: DictarAlChat.Anotar,
    parcial: String,
    alAnotar: (DictarAlChat.Propuesta) -> Unit,
    modifier: Modifier = Modifier
) {
    val propuesta = anotar.propuesta
    val aviso = anotar.aviso
    LaunchedEffect(aviso) {
        if (aviso != null) {
            kotlinx.coroutines.delay(6_000)
            anotar.aviso = null
        }
    }
    Column(modifier) {
        if (parcial.isNotBlank()) Text(
            "🗣 $parcial", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
        )
        if (propuesta != null) Surface(
            shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).clickable { alAnotar(propuesta) }
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 14.dp)) {
                Text(DictarAlChat.rotulo(propuesta), fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
                IconButton(onClick = { anotar.propuesta = null }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Quitar")
                }
            }
        }
        if (aviso != null) Surface(
            shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        ) {
            Text(aviso, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
        }
    }
}

/** **En qué chat se anota** (la propuesta sin chat dicho): la lista de los chats que se ofrecen. */
@Composable
fun ElegirChatParaAnotar(
    chats: List<Pair<String, String>>,
    texto: String,
    alElegir: (Pair<String, String>) -> Unit,
    alCerrar: () -> Unit
) {
    AlertDialog(
        onDismissRequest = alCerrar,
        title = { Text("¿En qué chat lo anoto?") },
        text = {
            Column {
                Text("«$texto»", fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (chats.isEmpty()) Text("No hay otros chats.", modifier = Modifier.padding(top = 8.dp))
                LazyColumn(Modifier.heightIn(max = 360.dp).padding(top = 8.dp)) {
                    items(chats) { c ->
                        Text(
                            c.second, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().clickable { alElegir(c) }.padding(vertical = 10.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = alCerrar) { Text("Cancelar") } }
    )
}
