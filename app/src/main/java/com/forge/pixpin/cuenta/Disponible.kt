package com.forge.pixpin.cuenta

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File

/**
 * **Qué funciones se ven y cuáles se pueden usar** (7-nov-2026, el relanzamiento: «que la app parezca que solo tiene
 * los visores, el asistente, las tareas y la cuenta; las demás las vamos abriendo nosotros, semana a semana, y
 * algunas serán de pago»).
 *
 * Cada función tiene un estado que decide el servidor en su **catálogo** (firmado, ver [Firmado]):
 * - **oculta**: no se ve en absoluto, ni con candado. Tampoco arranca nada suyo de fondo (no gasta).
 * - **gratis**: para todos.
 * - **pro**: con la **licencia** Pro o en la prueba de 30 días (o regalada a esa persona).
 *
 * Sin servidor (o sin red la primera vez) manda el catálogo de fábrica ([DE_FABRICA]), que es el del relanzamiento.
 * El último catálogo y la última licencia buenos se guardan: sin red se sigue con ellos (la licencia, hasta que vence).
 * Lo de dentro de la app nunca se le quita al usuario: si algo pasa a Pro, lo suyo lo sigue viendo.
 */
object Disponible {
    enum class Estado { OCULTA, GRATIS, PRO }

    // Los ids: los mismos que en el servidor (`servidor/pindesk/src/catalogo-inicial.mjs`).
    const val VISORES = "visores"
    const val ASISTENTE = "asistente"
    const val TAREAS = "tareas"
    const val CUENTA = "cuenta"
    const val PINES = "pines"
    const val RIEL_MARCADOR = "riel.marcador"
    const val RIEL_LAPIZ = "riel.lapiz"
    const val RIEL_MARCO = "riel.marco"
    const val MARCAS_TEXTO = "marcas.texto"
    const val COMENTARIOS = "comentarios"
    const val EXPORTAR = "exportar"
    const val CHATS = "chats"
    const val CAPTURAS = "capturas"
    const val GRABAR = "grabar"
    const val LIENZO = "lienzo"
    const val HORARIO = "horario"
    const val LECCIONES = "lecciones"
    const val SINCRONIZAR = "sincronizar"
    const val ESCANER = "escaner"
    const val MUSICA = "musica"
    const val ANKI = "anki"
    const val PLANOS_MEDIR = "planos.medir"
    const val WIDGETS = "widgets"
    const val JEV = "jev"

    /** El catálogo de fábrica: el del relanzamiento (lo que no está aquí, oculto). */
    val DE_FABRICA: Map<String, Estado> = mapOf(
        VISORES to Estado.GRATIS, ASISTENTE to Estado.GRATIS, TAREAS to Estado.GRATIS, CUENTA to Estado.GRATIS, PINES to Estado.GRATIS,
    )

    data class Licencia(val cuenta: String, val plan: String, val hasta: Long?, val regaladas: Set<String>, val vence: Long, val bloqueada: Boolean)

    /** Lo de ahora: los estados del catálogo (con sus nombres y próximos cambios) y la licencia, si hay. */
    data class Foto(
        val estados: Map<String, Estado> = DE_FABRICA,
        val nombres: Map<String, String> = emptyMap(),
        val proximos: Map<String, Pair<Estado, Long>> = emptyMap(),
        val avisos: Map<String, String> = emptyMap(),
        val licencia: Licencia? = null,
    )

    private val _ahora = MutableStateFlow(Foto())
    /** Para que la pantalla se repinte cuando llega un catálogo o una licencia nuevos. */
    val ahora: StateFlow<Foto> get() = _ahora

    /**
     * En las pruebas del ordenador (JUnit, que nunca va en la app) todo abierto: las pruebas de cada pantalla prueban la pantalla, no el
     * catálogo (que tiene las suyas).
     */
    @Volatile internal var enPruebas: Boolean = runCatching { Class.forName("org.junit.Test"); true }.getOrDefault(false)
    /**
     * **La edición completa** (main y play-store, ver docs/ramas.md): todo abierto, sin catálogo ni Pro. Solo la
     * edición restringida (su rama) hace caso al catálogo.
     */
    @Volatile internal var todoAbierto: Boolean = com.forge.pixpin.BuildConfig.EDICION != "restringida"
    @Volatile private var abiertoAMano: Boolean = false
    /** Abrir todo a mano (solo pruebas). */
    fun abrirTodo(si: Boolean) { abiertoAMano = si }

    fun estado(id: String): Estado = _ahora.value.estados[id] ?: DE_FABRICA[id] ?: Estado.OCULTA

    fun licencia(ahoraMs: Long = System.currentTimeMillis()): Licencia? =
        _ahora.value.licencia?.takeIf { it.vence > ahoraMs && !it.bloqueada }

    /** Si tiene Pro (pagado o la prueba) y la licencia sigue al día. */
    fun conPro(ahoraMs: Long = System.currentTimeMillis()): Boolean =
        licencia(ahoraMs)?.let { it.plan == "pro" || it.plan == "prueba" } == true

    private fun regalada(id: String) = licencia()?.regaladas?.contains(id) == true

    /** Si se ve (en el menú, el riel, la rejilla…). Oculta: no se ve en absoluto. */
    fun ve(id: String): Boolean = enPruebas || todoAbierto || abiertoAMano || regalada(id) || estado(id) != Estado.OCULTA

    /** Si se puede usar ya. Una de Pro sin Pro se ve, pero al tocarla se ofrece Pro (ver [CuentaActivity.abrirParaPro]). */
    fun puede(id: String): Boolean = enPruebas || todoAbierto || abiertoAMano || regalada(id) || when (estado(id)) {
        Estado.GRATIS -> true
        Estado.PRO -> conPro()
        Estado.OCULTA -> false
    }

    // ---------------------------------------------------------------- lo que llega del servidor

    private fun carpeta(c: Context) = File(c.filesDir, "cuenta").apply { mkdirs() }

    /** Lo guardado (al arrancar, antes que nada): solo si sus firmas cuadran. */
    fun cargar(c: Context) {
        val publica = Cuenta.publica
        if (publica.isBlank()) return
        val cat = Firmado.comprobar(runCatching { File(carpeta(c), "catalogo.json").readText() }.getOrNull(), publica)
        val lic = Firmado.comprobar(runCatching { File(carpeta(c), "licencia.json").readText() }.getOrNull(), publica)
        aplicar(cat, lic)
    }

    /** Un catálogo y/o una licencia que llegan (en su forma firmada): se comprueban, se guardan y se aplican. */
    fun recibir(c: Context, catalogo: JsonObject?, licencia: JsonObject?, sinLicencia: Boolean = false) {
        val publica = Cuenta.publica
        val cat = Firmado.comprobar(catalogo, publica)
        val lic = Firmado.comprobar(licencia, publica)
        if (cat != null) File(carpeta(c), "catalogo.json").writeText(catalogo.toString())
        if (lic != null) File(carpeta(c), "licencia.json").writeText(licencia.toString())
        if (sinLicencia) File(carpeta(c), "licencia.json").delete()
        aplicar(cat, if (sinLicencia) null else lic, quitarLicencia = sinLicencia)
    }

    internal fun aplicar(catalogo: JsonObject?, licencia: JsonObject?, quitarLicencia: Boolean = false) {
        var f = _ahora.value
        // En la completa el catálogo no manda (ni enseña «Qué incluye Pro»).
        if (catalogo != null && !todoAbierto && catalogo["tipo"]?.jsonPrimitive?.content == "catalogo") {
            val fs = catalogo["funciones"]?.jsonObject ?: JsonObject(emptyMap())
            val estados = HashMap(DE_FABRICA); val nombres = HashMap<String, String>()
            val proximos = HashMap<String, Pair<Estado, Long>>(); val avisos = HashMap<String, String>()
            for ((id, v) in fs) {
                val o = v.jsonObject
                estadoDe(o["estado"]?.jsonPrimitive?.content)?.let { estados[id] = it }
                o["nombre"]?.jsonPrimitive?.content?.let { nombres[id] = it }
                o["aviso"]?.jsonPrimitive?.content?.let { avisos[id] = it }
                o["proximo"]?.jsonObject?.let { p ->
                    val e = estadoDe(p["estado"]?.jsonPrimitive?.content); val d = p["desde"]?.jsonPrimitive?.longOrNull
                    if (e != null && d != null) proximos[id] = e to d
                }
            }
            f = f.copy(estados = estados, nombres = nombres, proximos = proximos, avisos = avisos)
        }
        if (licencia != null && licencia["tipo"]?.jsonPrimitive?.content == "licencia") {
            f = f.copy(licencia = Licencia(
                cuenta = licencia["cuenta"]?.jsonPrimitive?.content.orEmpty(),
                plan = licencia["plan"]?.jsonPrimitive?.content ?: "gratis",
                hasta = licencia["hasta"]?.jsonPrimitive?.longOrNull,
                regaladas = licencia["regaladas"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty(),
                vence = licencia["vence"]?.jsonPrimitive?.longOrNull ?: 0L,
                bloqueada = licencia["bloqueada"]?.jsonPrimitive?.content == "true",
            ))
        }
        if (quitarLicencia) f = f.copy(licencia = null)
        val antes = _ahora.value
        _ahora.value = f
        if (antes.estados != f.estados || antes.licencia?.regaladas != f.licencia?.regaladas) alCambiar?.invoke()
    }

    /** Lo que se hace cuando cambia qué se ve (atajos y componentes del sistema: [AlSistema]). */
    @Volatile var alCambiar: (() -> Unit)? = null

    private fun estadoDe(s: String?) = when (s) { "gratis" -> Estado.GRATIS; "pro" -> Estado.PRO; "oculta" -> Estado.OCULTA; else -> null }
}
