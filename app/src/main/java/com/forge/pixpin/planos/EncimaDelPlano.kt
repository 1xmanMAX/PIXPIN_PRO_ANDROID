package com.forge.pixpin.planos

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * **Lo de encima del plano, en el mismo fotograma que el plano** (13-oct-2026, el usuario: «cuando
 * muevo el plano los dibujos se mueven después, con retraso, no se sujetan al plano»).
 *
 * El plano lo pinta la tarjeta en su propia superficie (`GLSurfaceView`) y lo anotado y las cotas
 * los pintaba Compose en la ventana de la app. Son **dos superficies que llegan a la pantalla cada
 * una a su ritmo**: la del plano sale en cuanto la tarjeta acaba y la de la app pasa antes por el
 * hilo de la interfaz y el de pintar, así que al arrastrar el plano iba un fotograma o dos por
 * delante y lo dibujado corría detrás. No había forma de ponerlas de acuerdo desde fuera.
 *
 * Aquí lo de encima se pinta **en un mapa de bits aparte** (en su hilo, con el motor de siempre) y
 * la tarjeta lo pone encima del plano **en su mismo fotograma**, colocado con la cámara de ese
 * fotograma. Con la cámara de cada fotograma, no con la del mapa: aunque el mapa sea de hace un
 * momento, cae exactamente en su sitio del plano. Lo único que puede ir con retraso es su nitidez
 * (al acercarse se estira un instante) o un borde que acaba de entrar en la pantalla, y por eso:
 * - **moviéndose** se pide el mapa con medio aumento y el doble de campo (los mismos píxeles que la
 *   pantalla, cubriendo media pantalla más por cada lado): lo mismo que hace el plano al moverse;
 * - **quieto** se pide nítido, justo la pantalla.
 */
class EncimaDelPlano(private val pedirFotograma: () -> Unit) {

    /**
     * Qué pintar: [pintar] dibuja en un lienzo de [ancho] × [alto] visto con [camara], y le dice
     * cuántos píxeles del mapa hay por cada uno de la pantalla (1 nítido, ½ moviéndose) para que lo
     * que tiene tamaño en pantalla (letras de las cotas, grosores) no salga a la mitad.
     */
    class Pedido(
        val camara: CamaraPlano, val ancho: Int, val alto: Int, val escala: Float,
        val pintar: (android.graphics.Canvas, CamaraPlano, Int, Int, Float) -> Unit
    )

    /** Un mapa ya pintado y con qué cámara; sin mapa: no hay nada encima. */
    private class Hecho(val mapa: Bitmap?, val camara: CamaraPlano, val ancho: Int, val alto: Int)

    /** Si la tarjeta lo pone encima (anotando y quieto lo pinta el editor, no esto). */
    @Volatile var visible = true

    private val hilo = Executors.newSingleThreadExecutor { r -> Thread(r, "encima-del-plano").apply { isDaemon = true } }
    private val cerrojo = Any()
    private var siguiente: Pedido? = null
    private var enMarcha = false
    /** El último pedido: si la tarjeta pierde el contexto, se vuelve a pintar. */
    @Volatile private var ultimo: Pedido? = null
    private val porSubir = AtomicReference<Hecho?>(null)
    /** Mapas para volver a usar (como mucho dos: el que se pinta y el que espera a subir). */
    private val libres = ArrayDeque<Bitmap>()

    // Lo de la tarjeta (solo su hilo).
    private var textura = 0
    private var programa = 0
    private var vao = 0
    private var vb = 0
    private var enTextura: Hecho? = null
    private var texAncho = 0
    private var texAlto = 0
    private val esquinas = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    /** Pinta [p] en cuanto se pueda; si ya se está pintando otro, este lo sustituye en la cola. */
    fun pedir(p: Pedido) {
        ultimo = p
        synchronized(cerrojo) {
            siguiente = p
            if (enMarcha) return
            enMarcha = true
        }
        hilo.execute(::trabajar)
    }

    /** Ya no hay nada encima. */
    fun vaciar(camara: CamaraPlano) {
        ultimo = null
        synchronized(cerrojo) { siguiente = null }
        porSubir.getAndSet(Hecho(null, camara, 0, 0))?.mapa?.let(::devolver)
        pedirFotograma()
    }

    private fun trabajar() {
        while (true) {
            val p = synchronized(cerrojo) {
                val s = siguiente
                siguiente = null
                if (s == null) { enMarcha = false; return }
                s
            }
            val mapa = tomar(p.ancho, p.alto) ?: continue
            mapa.eraseColor(0)
            val ok = runCatching { p.pintar(android.graphics.Canvas(mapa), p.camara, p.ancho, p.alto, p.escala) }.isSuccess
            if (!ok) { devolver(mapa); continue }
            porSubir.getAndSet(Hecho(mapa, p.camara, p.ancho, p.alto))?.mapa?.let(::devolver)
            pedirFotograma()
        }
    }

    private fun tomar(w: Int, h: Int): Bitmap? {
        synchronized(libres) {
            while (libres.isNotEmpty()) {
                val b = libres.removeFirst()
                if (b.width == w && b.height == h && !b.isRecycled) return b
            }
        }
        return runCatching { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }.getOrNull()
    }

    private fun devolver(b: Bitmap) {
        synchronized(libres) { if (libres.size < 2) libres.addLast(b) }
    }

    /** Contexto nuevo de la tarjeta (el primero, o tras perderlo): lo de antes ya no existe. */
    fun alCrearContexto() {
        textura = 0; programa = 0; vao = 0; vb = 0; enTextura = null; texAncho = 0; texAlto = 0
        ultimo?.let(::pedir)
    }

    /** En el hilo de la tarjeta, después del plano: sube el mapa nuevo si lo hay y lo pone encima con [c]. */
    fun dibujar(c: CamaraPlano, ancho: Int, alto: Int) {
        porSubir.getAndSet(null)?.let { h ->
            if (h.mapa == null) enTextura = null
            else {
                if (subir(h.mapa)) enTextura = h
                devolver(h.mapa)
            }
        }
        val h = enTextura ?: return
        if (!visible || textura == 0) return
        if (programa == 0 && !preparar()) return
        // Las esquinas del mapa en el plano, y de ahí a la pantalla con la cámara de este fotograma.
        val x0 = h.camara.planoX(0.0, h.ancho); val y0 = h.camara.planoY(0.0, h.alto)
        val x1 = h.camara.planoX(h.ancho.toDouble(), h.ancho); val y1 = h.camara.planoY(h.alto.toDouble(), h.alto)
        fun nx(x: Double) = (c.pantallaX(x, ancho) / ancho * 2.0 - 1.0).toFloat()
        fun ny(y: Double) = (1.0 - c.pantallaY(y, alto) / alto * 2.0).toFloat()
        val l = nx(x0); val r = nx(x1); val t = ny(y0); val b = ny(y1)
        esquinas.clear()
        // Tira: arriba-izquierda, abajo-izquierda, arriba-derecha, abajo-derecha (x, y, u, v).
        esquinas.put(l).put(t).put(0f).put(0f)
        esquinas.put(l).put(b).put(0f).put(1f)
        esquinas.put(r).put(t).put(1f).put(0f)
        esquinas.put(r).put(b).put(1f).put(1f)
        esquinas.position(0)
        GLES30.glUseProgram(programa)
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vb)
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, 16 * 4, esquinas)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textura)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programa, "uImagen"), 0)
        // El mapa viene con el alfa ya multiplicado (así guarda Android los suyos).
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindVertexArray(0)
    }

    private fun subir(mapa: Bitmap): Boolean {
        if (mapa.isRecycled) return false
        if (textura == 0) {
            textura = IntArray(1).also { GLES30.glGenTextures(1, it, 0) }[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textura)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            texAncho = 0; texAlto = 0
        } else GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textura)
        if (texAncho == mapa.width && texAlto == mapa.height) GLUtils.texSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, mapa)
        else { GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, mapa, 0); texAncho = mapa.width; texAlto = mapa.height }
        return GLES30.glGetError() == GLES30.GL_NO_ERROR
    }

    private fun preparar(): Boolean = runCatching {
        fun sombreador(tipo: Int, fuente: String): Int {
            val s = GLES30.glCreateShader(tipo)
            GLES30.glShaderSource(s, fuente)
            GLES30.glCompileShader(s)
            val ok = IntArray(1).also { GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, it, 0) }[0]
            check(ok != 0) { GLES30.glGetShaderInfoLog(s) }
            return s
        }
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, sombreador(GLES30.GL_VERTEX_SHADER, VS))
        GLES30.glAttachShader(p, sombreador(GLES30.GL_FRAGMENT_SHADER, FS))
        GLES30.glLinkProgram(p)
        val ok = IntArray(1).also { GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, it, 0) }[0]
        check(ok != 0) { GLES30.glGetProgramInfoLog(p) }
        vao = IntArray(1).also { GLES30.glGenVertexArrays(1, it, 0) }[0]
        vb = IntArray(1).also { GLES30.glGenBuffers(1, it, 0) }[0]
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vb)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, 16 * 4, null, GLES30.GL_DYNAMIC_DRAW)
        GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, 0)
        GLES30.glEnableVertexAttribArray(1); GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, 8)
        GLES30.glBindVertexArray(0)
        programa = p
    }.isSuccess

    companion object {
        private const val VS = """#version 300 es
layout(location = 0) in vec2 aPos;
layout(location = 1) in vec2 aUv;
out vec2 vUv;
void main() { vUv = aUv; gl_Position = vec4(aPos, 0.0, 1.0); }
"""
        private const val FS = """#version 300 es
precision mediump float;
uniform sampler2D uImagen;
in vec2 vUv;
out vec4 oColor;
void main() { oColor = texture(uImagen, vUv); }
"""
    }
}
