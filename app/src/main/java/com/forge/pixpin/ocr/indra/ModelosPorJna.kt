package com.forge.pixpin.ocr.indra

import com.forge.pixpin.guardados.qwen3.OrtPorJna
import java.io.File

/**
 * **Los cuatro modelos de Indra con el ONNX Runtime que ya trae el APK** (26-oct-2026).
 *
 * El escáner usa la API de Java de ONNX Runtime (`ai.onnxruntime.OrtSession`, del AAR
 * `onnxruntime-android`). Aquí no puede ser: ese AAR trae su propio `libonnxruntime.so`, y el APK
 * ya lleva el 1.27.1 de sherpa-onnx (Whisper), con los símbolos versionados que pide
 * `libsherpa-onnx-jni.so`; dos con el mismo nombre no caben (ver [OrtPorJna]). Así que se hace lo
 * del PC (`pixpin-ocr/src/indra.rs`, sobre `pixpin_voz::ort`): las mismas sesiones por la API en C,
 * con JNA, el puente que ya usa Qwen3-ASR. Indra es ONNX IR 8 / opset 17: el 1.27.1 sobra.
 *
 * Sesiones sin arena (con arena crecía sin parar, `pipeline.py`) y sin preempaquetar (como el PC).
 */
class ModelosPorJna private constructor(
    private val ort: OrtPorJna,
    private val det: OrtPorJna.Sesion,
    private val rec: OrtPorJna.Sesion,
    private val orden: OrtPorJna.Sesion?,
    private val orient: OrtPorJna.Sesion?
) : ModelosIndra, AutoCloseable {

    /** Si `rec.onnx` da las salidas de la búsqueda en haz (un `rec.onnx` viejo no): sin ellas no hay LM. */
    val conSalidasDeLm: Boolean get() = SALIDAS_LM.all { it in rec.salidas }

    val versionDelRuntime: String get() = ort.version

    /** Corre [s] y devuelve sus salidas por nombre; las suelta al acabar [usa]. */
    private inline fun <T> correr(s: OrtPorJna.Sesion, entradas: List<OrtPorJna.Valor>, paso: String, usa: (Map<String, OrtPorJna.Valor>) -> T): T {
        val salidas = try {
            s.correr(entradas, paso)
        } finally {
            entradas.forEach { it.close() }
        }
        try {
            return usa(s.salidas.zip(salidas).toMap())
        } finally {
            salidas.forEach { it.close() }
        }
    }

    /** Las entradas en el orden que pide la sesión, por su nombre (como `mapOf("x" to …)` del escáner). */
    private fun porNombre(s: OrtPorJna.Sesion, valores: Map<String, OrtPorJna.Valor>): List<OrtPorJna.Valor> {
        if (s.entradas.toSet() != valores.keys) {
            valores.values.forEach { it.close() }
            throw OrtPorJna.ErrorOrt("entradas", "el modelo pide ${s.entradas}")
        }
        return s.entradas.map { valores.getValue(it) }
    }

    override fun detectar(x: FloatArray, alto: Int, ancho: Int): Triple<FloatArray, Int, Int> {
        val t = ort.tensorF32(x, longArrayOf(1, 1, alto.toLong(), ancho.toLong()))
        return correr(det, listOf(t), "det.onnx") { o ->
            val s = o[det.salidas[0]] ?: throw OrtPorJna.ErrorOrt("det.onnx", "sin salida")
            val f = s.forma()
            if (f.size != 4) throw OrtPorJna.ErrorOrt("det.onnx", "forma ${f.toList()}")
            Triple(s.comoF32(), f[2].toInt(), f[3].toInt())
        }
    }

    override fun reconocer(x: FloatArray, lote: Int, alto: Int, ancho: Int, anchos: LongArray, conLm: Boolean): SalidaRec {
        val tx = ort.tensorF32(x, longArrayOf(lote.toLong(), 1, alto.toLong(), ancho.toLong()))
        val tw = try { ort.tensorI64(anchos, longArrayOf(lote.toLong())) } catch (e: Throwable) { tx.close(); throw e }
        val entradas = porNombre(rec, mapOf("x" to tx, "widths" to tw))
        return correr(rec, entradas, "rec.onnx") { o ->
            fun toma(n: String) = o[n] ?: throw OrtPorJna.ErrorOrt("rec.onnx", "sin «$n»")
            val ids = toma("ids")
            val pasos = ids.forma().getOrNull(1)?.toInt() ?: 0
            val lm = conLm && SALIDAS_LM.all { it in o }
            val k = if (lm) o.getValue("topv").forma().getOrNull(2)?.toInt() ?: 0 else 0
            SalidaRec(
                ids = ids.comoI64(), conf = toma("conf").comoF32(), longitudes = toma("lengths").comoI64(), pasos = pasos,
                blanco = if (k > 0) o.getValue("blank").comoF32() else null,
                topv = if (k > 0) o.getValue("topv").comoF32() else null,
                topi = if (k > 0) o.getValue("topi").comoI64() else null,
                k = k
            )
        }
    }

    override fun orientar(x: FloatArray, lado: Int): FloatArray? {
        val s = orient ?: return null
        val t = ort.tensorF32(x, longArrayOf(1, 1, lado.toLong(), lado.toLong()))
        return correr(s, listOf(t), "orient.onnx") { o -> o[s.salidas[0]]?.comoF32() }
    }

    override fun ordenar(rasgos: FloatArray, n: Int): SalidaOrden? {
        val s = orden ?: return null
        val tf = ort.tensorF32(rasgos, longArrayOf(1, n.toLong(), OrdenIndra.N_RASGOS.toLong()))
        val tm = try { ort.tensorBool(BooleanArray(n) { true }, longArrayOf(1, n.toLong())) } catch (e: Throwable) { tf.close(); throw e }
        val entradas = porNombre(s, mapOf("feats" to tf, "mask" to tm))
        return correr(s, entradas, "order.onnx") { o ->
            val a = o["a"] ?: throw OrtPorJna.ErrorOrt("order.onnx", "sin «a»")
            val n2 = a.forma().getOrNull(2)?.toInt() ?: n
            val cls = o["cls"]
            val mismo = o["same"]
            SalidaOrden(a.comoF32(), n2, if (cls != null && mismo != null) cls.comoF32() else null, if (cls != null && mismo != null) mismo.comoF32() else null)
        }
    }

    override fun close() {
        listOfNotNull(det, rec, orden, orient).forEach { runCatching { it.close() } }
    }

    companion object {
        val SALIDAS_LM = listOf("blank", "topv", "topi")

        /** **Abre los modelos de [dir]** con [hilos] de CPU. `order.onnx` y `orient.onnx` son opcionales. */
        fun abrir(ort: OrtPorJna, dir: File, hilos: Int): ModelosPorJna {
            val abiertas = ArrayList<OrtPorJna.Sesion>()
            fun abre(n: String) = ort.abrir(File(dir, n).path, hilos, preempaquetar = false).also { abiertas += it }
            fun opcional(n: String) = if (File(dir, n).isFile) abre(n) else null
            try {
                return ModelosPorJna(ort, abre("det.onnx"), abre("rec.onnx"), opcional("order.onnx"), opcional("orient.onnx"))
            } catch (e: Throwable) {
                abiertas.forEach { runCatching { it.close() } }
                throw e
            }
        }
    }
}
