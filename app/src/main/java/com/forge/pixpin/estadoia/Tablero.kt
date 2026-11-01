package com.forge.pixpin.estadoia

/**
 * **El estado de las IA** (1-nov-2026, `pixpin_ui::estado_ia` del PC, el usuario: «unos puntitos
 * rojo o verde, en un sitio donde no molesten, que digan si la API de Claude, de DeepSeek o de Jev
 * funciona o si falta la clave»).
 *
 * Lógica pura: qué se sabe de cada servicio (sin clave, sin comprobar, funciona, falla y por qué),
 * de qué color va su puntito y cuándo toca volver a comprobarlo. Quien llama le da lo que pasó en
 * cada petición de verdad ([Desenlace]) y la hora en ms de un reloj que solo avance; este archivo
 * no sabe de redes ni de pantallas. Las reglas son las del PC, tal cual (mismos códigos, mismos
 * tiempos), con sus pruebas.
 *
 * - **De lo que se usa de verdad**: cada petición del asistente, de Jev o de «Probar» en Ajustes
 *   apunta su desenlace ([Tablero.apuntar]).
 * - **Una comprobación que no gasta** (la lista de modelos de cada API) a los [ESPERA_INICIAL_MS]
 *   de arrancar, al cambiar una clave y, como mucho, cada [CADA_MS] desde lo último que se supo.
 *   Sin red se vuelve a mirar antes, con espera creciente ([esperaSinRed]).
 */
enum class Servicio(
    /** El nombre que se enseña (es una marca: no se traduce). */
    val nombre: String
) {
    // En el orden de los puntitos (de arriba abajo).
    CLAUDE("Claude"),
    DEEPSEEK("DeepSeek"),
    /** Jev, el modelo de decisiones de TypeSafe. */
    JEV("Jev");
}

/** **Por qué falla.** */
sealed class Motivo {
    /** 401 o 403: la clave está mal copiada, se revocó o no tiene permiso. */
    data class ClaveMala(val codigo: Int) : Motivo()
    /** 402 (o un 400 que dice que no hay saldo): la cuenta no tiene saldo. */
    data object SinSaldo : Motivo()
    /** 429: demasiadas peticiones o cuota agotada por ahora. */
    data object Limite : Motivo()
    /** No se llegó a hablar con el servidor (sin red, DNS, TLS, tiempo). */
    data object Red : Motivo()
    /** 5xx o 529: el servicio está caído o saturado. */
    data class Servidor(val codigo: Int) : Motivo()
    /** Otro código raro (404: el modelo ya no está). */
    data class Otro(val codigo: Int) : Motivo()
}

/** **El color del puntito.** */
enum class Luz {
    /** Funciona: verde lleno. */
    VERDE,
    /** Falla: rojo lleno. */
    ROJA,
    /** Sin clave: solo el aro rojo (se ve, pero no grita como un fallo). */
    HUECA,
    /** Hay clave y aún no se sabe: gris tenue. */
    GRIS
}

/** **Lo que se sabe de un servicio.** */
sealed class Estado {
    /** No hay clave guardada. */
    data object SinClave : Estado()
    /** Hay clave, pero aún no se sabe si funciona. */
    data object Desconocido : Estado()
    /** La última petición de verdad o la última comprobación salió bien. */
    data object Funciona : Estado()
    data class Falla(val motivo: Motivo) : Estado()

    val luz: Luz
        get() = when (this) {
            Funciona -> Luz.VERDE
            is Falla -> Luz.ROJA
            SinClave -> Luz.HUECA
            Desconocido -> Luz.GRIS
        }

    /** La clave de la frase del estado (la de Fluent en el PC; aquí, `ia_estado_*` en los textos). */
    val clave: String
        get() = when (this) {
            SinClave -> "ia-estado-sin-clave"
            Desconocido -> "ia-estado-desconocido"
            Funciona -> "ia-estado-funciona"
            is Falla -> when (motivo) {
                is Motivo.ClaveMala -> "ia-estado-clave-mala"
                Motivo.SinSaldo -> "ia-estado-sin-saldo"
                Motivo.Limite -> "ia-estado-limite"
                Motivo.Red -> "ia-estado-red"
                is Motivo.Servidor -> "ia-estado-servidor"
                is Motivo.Otro -> "ia-estado-otro"
            }
        }

    /** El código HTTP que se enseña entre paréntesis, si la frase lo lleva. */
    val codigo: Int?
        get() = when (val m = (this as? Falla)?.motivo) {
            is Motivo.ClaveMala -> m.codigo
            is Motivo.Servidor -> m.codigo
            is Motivo.Otro -> m.codigo
            else -> null
        }
}

/** **Lo que pasó en una petición a la API.** */
sealed class Desenlace {
    /** El servidor contestó con este código (2xx incluido). */
    data class Http(val codigo: Int) : Desenlace()
    /** No se llegó a hablar con el servidor. */
    data object Red : Desenlace()

    /**
     * El estado al que lleva.
     *
     * Un 400, 413 o 422 es de ESA petición (muy grande, mal formada): el servidor ya miró la clave
     * antes (401 va primero), así que el servicio funciona.
     */
    val estado: Estado
        get() = when (this) {
            Red -> Estado.Falla(Motivo.Red)
            is Http -> when (val c = codigo) {
                in 200..299, 400, 413, 422 -> Estado.Funciona
                401, 403 -> Estado.Falla(Motivo.ClaveMala(c))
                402 -> Estado.Falla(Motivo.SinSaldo)
                429 -> Estado.Falla(Motivo.Limite)
                in 500..599 -> Estado.Falla(Motivo.Servidor(c))
                else -> Estado.Falla(Motivo.Otro(c))
            }
        }

    companion object {
        /** El mayor código que se apunta (el `u16::MAX` del PC): uno mayor es un código raro. */
        const val CODIGO_MAXIMO = 65_535

        /** Lo que dicen las capas de red: el código si contestó, null si no se llegó. */
        fun deCodigo(codigo: Int?): Desenlace =
            if (codigo == null) Red else Http(if (codigo < 0 || codigo > CODIGO_MAXIMO) CODIGO_MAXIMO else codigo)
    }
}

/** Lo que se espera tras arrancar antes de la primera comprobación: que la app acabe de abrirse sin red de por medio. */
const val ESPERA_INICIAL_MS: Long = 10_000

/**
 * Lo más a menudo que se comprueba un servicio que funciona (o que falla por otra cosa que la
 * red): cada media hora desde lo último que se supo.
 */
const val CADA_MS: Long = 30 * 60_000L

/** La primera espera sin red; se dobla con cada fallo seguido. */
const val PRIMERA_SIN_RED_MS: Long = 60_000

/** Cuánto se espera tras [seguidos] fallos de red seguidos: 1, 2, 4, 8… minutos, y nunca más de [CADA_MS]. */
fun esperaSinRed(seguidos: Int): Long {
    val doble = (seguidos - 1).coerceIn(0, 16)
    return minOf(PRIMERA_SIN_RED_MS * (1L shl doble), CADA_MS)
}

private fun sumar(a: Long, b: Long): Long = if (a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b

/** **Lo de un servicio.** */
data class Ficha(
    val estado: Estado = Estado.SinClave,
    /** Cuándo se supo [estado] por última vez (ms del reloj de quien llama). */
    val cuando: Long? = null,
    /** Cuándo empezó la última comprobación (haya contestado o no). */
    internal val intento: Long? = null,
    /** Fallos de red seguidos. */
    internal val sinRed: Int = 0,
    /** Hay que comprobarlo ya (cambió la clave, «Comprobar ahora»). */
    internal val pendiente: Boolean = false,
    /** Hay una comprobación en marcha. */
    internal val enCurso: Boolean = false
) {
    val hayClave: Boolean get() = estado != Estado.SinClave

    /** Si hay una comprobación en marcha (el menú dice «comprobando…»). */
    val comprobando: Boolean get() = enCurso
}

/**
 * **El tablero**: lo que se sabe de los tres servicios. No es seguro entre hilos: quien lo
 * comparte lo guarda tras un cerrojo ([EstadoDeLasIa]) y enseña una [copia].
 */
class Tablero private constructor(private val fichas: Array<Ficha>) {
    constructor() : this(Array(Servicio.entries.size) { Ficha() })

    fun copia(): Tablero = Tablero(fichas.copyOf())

    fun ficha(s: Servicio): Ficha = fichas[s.ordinal]

    fun estado(s: Servicio): Estado = ficha(s).estado

    private inline fun cambiar(s: Servicio, f: (Ficha) -> Ficha) {
        fichas[s.ordinal] = f(fichas[s.ordinal])
    }

    /**
     * **Si hay clave**, mirado en el disco. Quitarla lo deja en «sin clave»; ponerla, en «sin
     * comprobar» (se comprueba a su hora). Si no cambia, no toca nada. Devuelve si cambió.
     */
    fun ponerClave(s: Servicio, hay: Boolean): Boolean {
        if (ficha(s).hayClave == hay) return false
        fichas[s.ordinal] = Ficha(estado = if (hay) Estado.Desconocido else Estado.SinClave)
        return true
    }

    /** **El usuario guardó o borró la clave**: con clave, se olvida lo que se sabía de la anterior y se comprueba ya. */
    fun claveCambiada(s: Servicio, hay: Boolean) {
        fichas[s.ordinal] = Ficha(estado = if (hay) Estado.Desconocido else Estado.SinClave, pendiente = hay)
    }

    /** «Comprobar ahora»: todos los que tienen clave, ya. */
    fun pedirAhora() {
        for (s in Servicio.entries) if (ficha(s).hayClave) cambiar(s) { it.copy(pendiente = true) }
    }

    /**
     * **Lo que pasó en una petición** (de verdad o de comprobar). Si contestó algo, había clave
     * aunque el disco aún no lo dijera.
     */
    fun apuntar(s: Servicio, d: Desenlace, ahora: Long) = cambiar(s) { f ->
        f.copy(
            estado = d.estado,
            cuando = ahora,
            sinRed = if (d == Desenlace.Red) (if (f.sinRed == Int.MAX_VALUE) f.sinRed else f.sinRed + 1) else 0,
            pendiente = false,
            enCurso = false
        )
    }

    /** Empieza una comprobación de [s]. */
    fun empezar(s: Servicio, ahora: Long) = cambiar(s) { it.copy(enCurso = true, pendiente = false, intento = ahora) }

    /** Acabó la comprobación de [s] (contestara o no por [apuntar]). */
    fun acabar(s: Servicio) = cambiar(s) { it.copy(enCurso = false) }

    /**
     * **Cuándo toca comprobar** [s], en ms del mismo reloj; null: nunca (sin clave, o ya en
     * marcha). [arranque] es cuándo arrancó la app.
     */
    fun cuandoToca(s: Servicio, arranque: Long): Long? {
        val f = ficha(s)
        if (!f.hayClave || f.enCurso) return null
        if (f.pendiente) return 0
        val a = f.cuando
        val b = f.intento
        val base = when {
            a != null && b != null -> maxOf(a, b)
            else -> a ?: b ?: return sumar(arranque, ESPERA_INICIAL_MS)
        }
        val espera = if (f.estado == Estado.Falla(Motivo.Red)) esperaSinRed(maxOf(f.sinRed, 1)) else CADA_MS
        return sumar(base, espera)
    }

    /** Los que toca comprobar ya. */
    fun porComprobar(ahora: Long, arranque: Long): List<Servicio> =
        Servicio.entries.filter { s -> cuandoToca(s, arranque)?.let { it <= ahora } == true }

    /** La próxima vez que toca comprobar alguno (para dormir hasta ahí). */
    fun proxima(arranque: Long): Long? = Servicio.entries.mapNotNull { cuandoToca(it, arranque) }.minOrNull()

    /** Si todo lo que tiene clave funciona (y hay al menos uno). */
    fun todoBien(): Boolean {
        val conClave = fichas.filter { it.hayClave }
        return conClave.isNotEmpty() && conClave.all { it.estado == Estado.Funciona }
    }

    override fun equals(other: Any?) = other is Tablero && fichas.contentEquals(other.fichas)
    override fun hashCode() = fichas.contentHashCode()
}

/**
 * **Hace cuánto**, para el menú: la clave de la frase y el número. Menos de un minuto, «ahora
 * mismo»; menos de dos horas, en minutos; y si no, en horas.
 */
fun hace(desde: Long, ahora: Long): Pair<String, Long> {
    val min = maxOf(0L, ahora - desde) / 60_000
    return when {
        min == 0L -> "ia-estado-hace-nada" to 0L
        min <= 119 -> "ia-estado-hace-min" to min
        else -> "ia-estado-hace-horas" to min / 60
    }
}
