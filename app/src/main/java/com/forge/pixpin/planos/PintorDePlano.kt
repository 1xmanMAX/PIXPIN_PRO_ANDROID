package com.forge.pixpin.planos

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.opengles.GL10

/**
 * **El plano en la tarjeta gráfica** (OpenGL ES 3.0): lo de `crates/pixpin-cad/src/gpu.rs` del PC,
 * que lo hace con Direct3D 11, con los mismos sombreadores pasados a GLSL. El plano se sube
 * **una vez**; cada fotograma solo cambia la vista (cuatro números) y se dibujan los tramos que
 * caen en la pantalla y que de tamaño se verían. Así va fluido aunque el plano sea grande.
 *
 * Cinco pasadas, como en el PC: rellenos, sombreados con patrón (sus rayas se calculan píxel a
 * píxel, no se guardan), rayas, círculos y arcos (exactos a cualquier zoom: un cuadrado por arco y
 * la distancia a la circunferencia en cada píxel) y letras (cada letra distinta está una vez; las
 * demás son instancias).
 *
 * Diferencias con el PC, por ser un teléfono: OpenGL ES 3.0 no tiene `baseInstance` (se corre el
 * puntero de las instancias en cada tramo) ni búferes estructurados (las tramas, familias y
 * letras van en texturas enteras de 1024 de ancho); y la raya tiene [grosor] píxeles —con 1, en
 * una pantalla de 450 ppp casi no se ve—.
 */
class PintorDePlano(private val modelo: ModeloCad, private val grosor: Float) : GLSurfaceView.Renderer {
    /** La vista que se pinta; la cambia el hilo de la interfaz y la lee el de la tarjeta. */
    @Volatile var camara: CamaraPlano? = null
    @Volatile var claro: Boolean = false
    /** Si la tarjeta no pudo con algo (sombreadores, memoria): se dice en vez de quedarse en negro. */
    @Volatile var fallo: String? = null
    var alCambiarTamano: ((Int, Int) -> Unit)? = null

    private var ancho = 1
    private var alto = 1
    private var listo = false

    private var progSimple = 0; private var progRelleno = 0; private var progTrama = 0; private var progLetra = 0; private var progArco = 0
    private val vaos = IntArray(4)
    private var vbVertices = 0; private var ibLineas = 0; private var ibTriangulos = 0
    private var vbTrama = 0; private var ibTrama = 0; private var vbLetras = 0; private var vbArcos = 0
    private var texTramas = 0; private var texFamilias = 0; private var texMalla = 0; private var texGlifos = 0
    private var conTramas = false; private var conLetras = false

    private val salida = IntArray(2 * maxOf(
        modelo.tramosLineas.n, modelo.tramosTriangulos.n, modelo.tramosTrama.n, modelo.tramosArcos.n, 1
    ) + 2)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // Un contexto nuevo (la primera vez, o tras perderlo): se sube todo otra vez.
        listo = false
        try {
            subir()
            listo = true
        } catch (e: Throwable) {
            fallo = e.message ?: e.javaClass.simpleName
        }
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        ancho = w.coerceAtLeast(1); alto = h.coerceAtLeast(1)
        GLES30.glViewport(0, 0, ancho, alto)
        alCambiarTamano?.invoke(ancho, alto)
    }

    override fun onDrawFrame(gl: GL10?) {
        val fondo = if (claro) FONDO_CLARO else FONDO_OSCURO
        GLES30.glClearColor(fondo[0], fondo[1], fondo[2], 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        val c = camara ?: return
        if (!listo) return
        val px = c.px.toFloat()
        val cx = c.centroX.toFloat(); val cy = c.centroY.toFloat()
        val mx = (ancho / 2.0 * c.px).toFloat(); val my = (alto / 2.0 * c.px).toFloat()
        val vx0 = cx - mx; val vy0 = cy - my; val vx1 = cx + mx; val vy1 = cy + my
        val ex = (2.0 / (ancho * c.px)).toFloat(); val ey = (2.0 / (alto * c.px)).toFloat()
        val c7 = if (claro) 0f else 1f
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        fun vista(p: Int) {
            GLES30.glUseProgram(p)
            GLES30.glUniform2f(GLES30.glGetUniformLocation(p, "uEscala"), ex, ey)
            GLES30.glUniform2f(GLES30.glGetUniformLocation(p, "uCentro"), cx, cy)
            GLES30.glUniform4f(GLES30.glGetUniformLocation(p, "uColor7"), c7, c7, c7, 1f)
            GLES30.glUniform1f(GLES30.glGetUniformLocation(p, "uPx"), px)
            GLES30.glUniform1f(GLES30.glGetUniformLocation(p, "uGrosor"), grosor)
            GLES30.glUniform1f(GLES30.glGetUniformLocation(p, "uModo"), if (claro) 2f else 1f)
        }

        // 1. Rellenos.
        if (modelo.triangulos.cuantos > 0) {
            vista(progRelleno)
            GLES30.glBindVertexArray(vaos[0])
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibTriangulos)
            val n = modelo.tramosTriangulos.visibles(vx0, vy0, vx1, vy1, px, salida)
            for (i in 0 until n) GLES30.glDrawElements(GLES30.GL_TRIANGLES, salida[2 * i + 1], GLES30.GL_UNSIGNED_INT, salida[2 * i] * 4)
        }
        // 2. Sombreados con patrón.
        if (conTramas) {
            vista(progTrama)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texTramas)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texFamilias)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(progTrama, "uTramas"), 0)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(progTrama, "uFamilias"), 1)
            GLES30.glBindVertexArray(vaos[1])
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibTrama)
            val n = modelo.tramosTrama.visibles(vx0, vy0, vx1, vy1, px * 2f, salida)
            for (i in 0 until n) GLES30.glDrawElements(GLES30.GL_TRIANGLES, salida[2 * i + 1], GLES30.GL_UNSIGNED_INT, salida[2 * i] * 4)
        }
        // 3. Rayas: tiras cortadas por el índice 0xFFFFFFFF. **El corte hay que encenderlo**
        // (9-oct-2026): sin él, ese índice se lee como un vértice más —basura, casi siempre el
        // centro del plano— y cada tira acababa en un abanico de rayas hacia el medio.
        if (modelo.lineas.cuantos > 0) {
            GLES30.glEnable(GLES30.GL_PRIMITIVE_RESTART_FIXED_INDEX)
            vista(progSimple)
            GLES30.glLineWidth(grosor)
            GLES30.glBindVertexArray(vaos[0])
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibLineas)
            val n = modelo.tramosLineas.visibles(vx0, vy0, vx1, vy1, px * 0.75f, salida)
            for (i in 0 until n) GLES30.glDrawElements(GLES30.GL_LINE_STRIP, salida[2 * i + 1], GLES30.GL_UNSIGNED_INT, salida[2 * i] * 4)
        }
        // 3b. Círculos y arcos: seis vértices (un cuadrado) por instancia.
        if (modelo.arcos.cuantos > 0) {
            vista(progArco)
            GLES30.glBindVertexArray(vaos[3])
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbArcos)
            val n = modelo.tramosArcos.visibles(vx0, vy0, vx1, vy1, px * 0.75f, salida)
            for (i in 0 until n) {
                punterosDeArcos(salida[2 * i] * 32)
                GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLES, 0, 6, salida[2 * i + 1])
            }
        }
        // 4. Letras: un texto de menos de 3 píxeles no se lee.
        if (conLetras) {
            vista(progLetra)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texMalla)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texGlifos)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(progLetra, "uMalla"), 0)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(progLetra, "uGlifos"), 1)
            GLES30.glBindVertexArray(vaos[2])
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbLetras)
            val t = modelo.tramosLetras
            for (i in 0 until t.n) {
                if (t.tamano[i] < px * 3f) break
                if (!t.corta(i, vx0, vy0, vx1, vy1)) continue
                val rayas = t.clase[i] and ModeloCad.GLIFO_DE_RAYAS != 0
                punterosDeLetras(t.desde[i] * 32)
                GLES30.glDrawArraysInstanced(
                    if (rayas) GLES30.GL_LINES else GLES30.GL_TRIANGLES, 0,
                    t.clase[i] and ModeloCad.GLIFO_DE_RAYAS.inv(), t.cuantos[i]
                )
            }
        }
        GLES30.glBindVertexArray(0)
    }

    // ------------------------------------------------------------------ subir

    private fun subir() {
        progSimple = programa(VS_SIMPLE, FS_COLOR)
        progRelleno = programa(VS_SIMPLE, FS_RELLENO)
        progTrama = programa(VS_TRAMA, FS_TRAMA)
        progLetra = programa(VS_LETRA, FS_COLOR)
        progArco = programa(VS_ARCO, FS_ARCO)
        GLES30.glGenVertexArrays(4, vaos, 0)
        val m = modelo

        // Rayas y rellenos: un vértice = x, y (f32) y color (u32).
        vbVertices = buffer(GLES30.GL_ARRAY_BUFFER, m.vertices)
        ibLineas = buffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, m.lineas)
        ibTriangulos = buffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, m.triangulos)
        GLES30.glBindVertexArray(vaos[0])
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbVertices)
        GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 12, 0)
        GLES30.glEnableVertexAttribArray(1); GLES30.glVertexAttribIPointer(1, 1, GLES30.GL_UNSIGNED_INT, 12, 8)

        // Sombreados: x, y, color, trama.
        val maxTex = IntArray(1).also { GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, it, 0) }[0]
        if (m.triangulosTrama.cuantos > 0 && m.tramas.cuantos > 0 &&
            filas(m.tramas.cuantos * 3) <= maxTex && filas(m.familias.cuantos * 4) <= maxTex
        ) {
            vbTrama = buffer(GLES30.GL_ARRAY_BUFFER, m.verticesTrama)
            ibTrama = buffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, m.triangulosTrama)
            GLES30.glBindVertexArray(vaos[1])
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbTrama)
            GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, 0)
            GLES30.glEnableVertexAttribArray(1); GLES30.glVertexAttribIPointer(1, 1, GLES30.GL_UNSIGNED_INT, 16, 8)
            GLES30.glEnableVertexAttribArray(2); GLES30.glVertexAttribIPointer(2, 1, GLES30.GL_UNSIGNED_INT, 16, 12)
            // Una trama son 9 palabras: van en 3 texeles (12, con relleno); una familia, en 4.
            texTramas = texturaEntera(m.tramas, 12)
            texFamilias = texturaEntera(m.familias, 16)
            conTramas = true
        }

        // Letras: instancias de 32 bytes (posición, matriz, color, glifo).
        if (m.letras.cuantos > 0 && m.mallaLetras.cuantos > 0 && filas(m.mallaLetras.cuantos) <= maxTex && filas(m.glifos.cuantos) <= maxTex) {
            vbLetras = buffer(GLES30.GL_ARRAY_BUFFER, m.letras)
            texMalla = textura(m.mallaLetras, GLES30.GL_RG32F, GLES30.GL_RG, GLES30.GL_FLOAT)
            texGlifos = textura(m.glifos, GLES30.GL_RG32UI, GLES30.GL_RG_INTEGER, GLES30.GL_UNSIGNED_INT)
            GLES30.glBindVertexArray(vaos[2])
            for (k in 0..3) { GLES30.glEnableVertexAttribArray(k); GLES30.glVertexAttribDivisor(k, 1) }
            conLetras = true
        }

        // Arcos: instancias de 32 bytes (centro, radio, inicio, barrido, color).
        if (m.arcos.cuantos > 0) {
            vbArcos = buffer(GLES30.GL_ARRAY_BUFFER, m.arcos)
            GLES30.glBindVertexArray(vaos[3])
            for (k in 0..4) { GLES30.glEnableVertexAttribArray(k); GLES30.glVertexAttribDivisor(k, 1) }
        }
        GLES30.glBindVertexArray(0)
        val error = GLES30.glGetError()
        if (error == GLES30.GL_OUT_OF_MEMORY) throw IllegalStateException("El plano no cabe en la tarjeta gráfica")
    }

    private fun punterosDeLetras(base: Int) {
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbLetras)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 32, base)
        GLES30.glVertexAttribPointer(1, 4, GLES30.GL_FLOAT, false, 32, base + 8)
        GLES30.glVertexAttribIPointer(2, 1, GLES30.GL_UNSIGNED_INT, 32, base + 24)
        GLES30.glVertexAttribIPointer(3, 1, GLES30.GL_UNSIGNED_INT, 32, base + 28)
    }

    private fun punterosDeArcos(base: Int) {
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbArcos)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 32, base)
        GLES30.glVertexAttribPointer(1, 1, GLES30.GL_FLOAT, false, 32, base + 8)
        GLES30.glVertexAttribPointer(2, 1, GLES30.GL_FLOAT, false, 32, base + 12)
        GLES30.glVertexAttribPointer(3, 1, GLES30.GL_FLOAT, false, 32, base + 16)
        GLES30.glVertexAttribIPointer(4, 1, GLES30.GL_UNSIGNED_INT, 32, base + 20)
    }

    private fun buffer(tipo: Int, l: ModeloCad.Lista): Int {
        val id = IntArray(1).also { GLES30.glGenBuffers(1, it, 0) }[0]
        GLES30.glBindBuffer(tipo, id)
        val datos = l.datos.duplicate().apply { position(0); limit(l.bytes) }
        // Una lista vacía también se crea (con un byte): un búfer sin datos no se puede atar.
        if (l.bytes > 0) GLES30.glBufferData(tipo, l.bytes, datos, GLES30.GL_STATIC_DRAW)
        else GLES30.glBufferData(tipo, 4, ByteBuffer.allocateDirect(4), GLES30.GL_STATIC_DRAW)
        return id
    }

    private fun filas(texeles: Int): Int = (texeles + ANCHO_TEX - 1) / ANCHO_TEX

    /** Una lista de N palabras por elemento en una textura RGBA32UI, con cada elemento en [hueco] palabras. */
    private fun texturaEntera(l: ModeloCad.Lista, hueco: Int): Int {
        val texeles = l.cuantos * hueco / 4
        val h = filas(texeles).coerceAtLeast(1)
        val b = ByteBuffer.allocateDirect(ANCHO_TEX * h * 16).order(ByteOrder.nativeOrder())
        for (e in 0 until l.cuantos) for (k in 0 until l.palabras) b.putInt((e * hueco + k) * 4, l.palabra(e, k))
        return subirTextura(b, h, GLES30.GL_RGBA32UI, GLES30.GL_RGBA_INTEGER, GLES30.GL_UNSIGNED_INT)
    }

    /** Una lista de dos palabras por elemento, un elemento por texel. */
    private fun textura(l: ModeloCad.Lista, interno: Int, formato: Int, tipo: Int): Int {
        val h = filas(l.cuantos).coerceAtLeast(1)
        val b = ByteBuffer.allocateDirect(ANCHO_TEX * h * 8).order(ByteOrder.nativeOrder())
        for (e in 0 until l.cuantos) { b.putInt(e * 8, l.palabra(e, 0)); b.putInt(e * 8 + 4, l.palabra(e, 1)) }
        return subirTextura(b, h, interno, formato, tipo)
    }

    private fun subirTextura(b: ByteBuffer, h: Int, interno: Int, formato: Int, tipo: Int): Int {
        val id = IntArray(1).also { GLES30.glGenTextures(1, it, 0) }[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
        b.position(0)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, interno, ANCHO_TEX, h, 0, formato, tipo, b)
        return id
    }

    private fun programa(vs: String, fs: String): Int {
        fun sombreador(tipo: Int, fuente: String): Int {
            val s = GLES30.glCreateShader(tipo)
            GLES30.glShaderSource(s, fuente)
            GLES30.glCompileShader(s)
            val ok = IntArray(1).also { GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, it, 0) }[0]
            if (ok == 0) throw IllegalStateException("Sombreador: " + GLES30.glGetShaderInfoLog(s))
            return s
        }
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, sombreador(GLES30.GL_VERTEX_SHADER, vs))
        GLES30.glAttachShader(p, sombreador(GLES30.GL_FRAGMENT_SHADER, fs))
        GLES30.glLinkProgram(p)
        val ok = IntArray(1).also { GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, it, 0) }[0]
        if (ok == 0) throw IllegalStateException("Programa: " + GLES30.glGetProgramInfoLog(p))
        return p
    }

    /** Antialias 4× si la tarjeta puede; si no, sin él. */
    class Muestreo : GLSurfaceView.EGLConfigChooser {
        override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig? {
            val es3 = 0x40 // EGL_OPENGL_ES3_BIT_KHR
            for (muestras in intArrayOf(4, 0)) {
                val atributos = intArrayOf(
                    EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
                    EGL10.EGL_RENDERABLE_TYPE, es3,
                    EGL10.EGL_SAMPLE_BUFFERS, if (muestras > 0) 1 else 0, EGL10.EGL_SAMPLES, muestras,
                    EGL10.EGL_NONE
                )
                val n = IntArray(1)
                val configs = arrayOfNulls<EGLConfig>(1)
                if (egl.eglChooseConfig(display, atributos, configs, 1, n) && n[0] > 0) return configs[0]
            }
            // Lo mínimo: cualquiera que sirva para OpenGL ES 3 (sin config, GLSurfaceView se cae).
            val n = IntArray(1)
            val configs = arrayOfNulls<EGLConfig>(1)
            egl.eglChooseConfig(display, intArrayOf(EGL10.EGL_RENDERABLE_TYPE, es3, EGL10.EGL_NONE), configs, 1, n)
            return configs[0] ?: throw IllegalArgumentException("Este teléfono no tiene OpenGL ES 3")
        }
    }

    companion object {
        private const val ANCHO_TEX = 1024
        /** Los fondos del PC: oscuro azulado y papel. */
        val FONDO_OSCURO = floatArrayOf(0.13f, 0.15f, 0.19f)
        val FONDO_CLARO = floatArrayOf(0.99f, 0.988f, 0.976f)

        private const val COMUN = """#version 300 es
precision highp float;
precision highp int;
uniform vec2 uEscala;
uniform vec2 uCentro;
uniform vec4 uColor7;
uniform float uPx;
uniform float uGrosor;
vec4 colorDe(uint c) {
    vec4 r = vec4(float(c & 255u), float((c >> 8u) & 255u), float((c >> 16u) & 255u), float((c >> 24u) & 255u)) / 255.0;
    if (r.a == 0.0) r = uColor7;
    return r;
}
vec4 aPantalla(vec2 p) { return vec4((p - uCentro) * uEscala, 0.0, 1.0); }
"""

        /**
         * **Los colores en el otro fondo** (`tinta` y `apagado` de `gpu.rs` del PC, 8-oct-2026):
         * los colores del plano se pensaron para un fondo; en el otro, los que no se leen se
         * aclaran (u oscurecen) sin perder su tono, los grises oscuros pasan a claros como el
         * color 7, y en oscuro los rellenos grandes se apagan (un blanco o un amarillo de tabla
         * deslumbraba y las letras de encima no se leían). `uModo`: 1 fondo oscuro, 2 claro.
         */
        private const val COLORES = """
uniform float uModo;
float luz(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }
vec4 tinta(vec4 c) {
    if (uModo < 0.5) return c;
    float l = luz(c.rgb);
    float sat = max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b));
    if (uModo < 1.5) {
        if (sat < 0.15 && l < 0.5) c.rgb = 1.0 - c.rgb * 0.8;
        else if (l < 0.55) c.rgb = mix(c.rgb, vec3(1.0), (0.55 - l) / (1.0 - l));
    } else if (l > 0.62) {
        c.rgb *= 0.5 / l;
    }
    return c;
}
vec4 apagado(vec4 c) {
    if (uModo < 0.5 || uModo > 1.5) return c;
    float sat = max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b));
    if (sat < 0.15) c.rgb = 0.17 + (1.0 - c.rgb) * 0.22;
    else c.rgb *= 0.42;
    return c;
}
"""

        private const val VS_SIMPLE = COMUN + """
layout(location = 0) in vec2 aPos;
layout(location = 1) in uint aColor;
out vec4 vColor;
void main() { gl_Position = aPantalla(aPos); vColor = colorDe(aColor); }
"""

        private const val FS_COLOR = """#version 300 es
precision highp float;
""" + COLORES + """
in vec4 vColor;
out vec4 oColor;
void main() { oColor = tinta(vColor); }
"""

        private const val FS_RELLENO = """#version 300 es
precision highp float;
""" + COLORES + """
in vec4 vColor;
out vec4 oColor;
void main() { oColor = apagado(vColor); }
"""

        private const val VS_TRAMA = COMUN + """
layout(location = 0) in vec2 aPos;
layout(location = 1) in uint aColor;
layout(location = 2) in uint aTrama;
out vec4 vColor;
out vec2 vMundo;
flat out uint vTrama;
void main() { gl_Position = aPantalla(aPos); vColor = colorDe(aColor); vMundo = aPos; vTrama = aTrama; }
"""

        private const val FS_TRAMA = COMUN + COLORES + """
uniform highp usampler2D uTramas;
uniform highp usampler2D uFamilias;
in vec4 vColor;
in vec2 vMundo;
flat in uint vTrama;
out vec4 oColor;
uvec4 leer(highp usampler2D t, int i) { return texelFetch(t, ivec2(i % 1024, i / 1024), 0); }
vec4 reales(uvec4 u) { return uintBitsToFloat(u); }
void main() {
    int t = int(vTrama) * 3;
    vec4 t0 = reales(leer(uTramas, t));
    uvec4 t1u = leer(uTramas, t + 1);
    vec4 t1 = reales(t1u);
    uint cuantas = leer(uTramas, t + 2).x;
    uint desde = t1u.w;
    vec2 d = vMundo - t0.xy;
    vec2 q = vec2(t0.z * d.x + t0.w * d.y, t1.x * d.x + t1.y * d.y);
    float pxl = uPx / max(t1.z, 1e-30);
    float cob = 0.0;
    for (uint k = 0u; k < cuantas && k < 32u; k++) {
        int f = int(desde + k) * 4;
        vec4 f0 = reales(leer(uFamilias, f));
        uvec4 f1u = leer(uFamilias, f + 1);
        vec4 f1 = reales(f1u);
        vec4 tz0 = reales(leer(uFamilias, f + 2));
        vec4 tz1 = reales(leer(uFamilias, f + 3));
        float trazos[8] = float[8](tz0.x, tz0.y, tz0.z, tz0.w, tz1.x, tz1.y, tz1.z, tz1.w);
        vec2 v = q - f0.xy;
        vec2 n = vec2(-f0.w, f0.z);
        float s = dot(v, n);
        float pasoF = f1.x;
        float paso = abs(pasoF);
        // Rayas a menos de 3 píxeles: un tono, no un muaré.
        if (paso < pxl * 3.0) { cob = max(cob, 0.3); continue; }
        float kk = floor(s / pasoF + 0.5);
        float dist = abs(s - kk * pasoF) / pxl;
        float a = clamp(uGrosor * 0.5 + 0.75 - dist, 0.0, 1.0);
        if (a <= 0.0) continue;
        uint nt = f1u.w;
        float largoCiclo = f1.z;
        if (nt > 0u && largoCiclo > pxl) {
            float tt = dot(v, f0.zw) - kk * f1.y;
            float ph = tt - floor(tt / largoCiclo) * largoCiclo;
            float acc = 0.0; float dentro = 0.0;
            for (uint j = 0u; j < nt && j < 8u; j++) {
                float L = trazos[j];
                float largo = max(abs(L), pxl);
                if (ph >= acc && ph < acc + largo) { dentro = L >= 0.0 ? 1.0 : 0.0; break; }
                acc += abs(L);
            }
            a *= dentro;
        }
        cob = max(cob, a);
    }
    if (cob <= 0.004) discard;
    vec4 tc = tinta(vColor);
    oColor = vec4(tc.rgb, tc.a * cob);
}
"""

        private const val VS_LETRA = COMUN + """
uniform highp sampler2D uMalla;
uniform highp usampler2D uGlifos;
layout(location = 0) in vec2 aPos;
layout(location = 1) in vec4 aM;
layout(location = 2) in uint aColor;
layout(location = 3) in uint aGlifo;
out vec4 vColor;
void main() {
    int g = int(aGlifo);
    uvec2 gl = texelFetch(uGlifos, ivec2(g % 1024, g / 1024), 0).xy;
    int n = int(gl.y & 0x7fffffffu);
    vColor = colorDe(aColor);
    if (gl_VertexID >= n) { gl_Position = vec4(0.0, 0.0, -2.0, 1.0); return; }
    int i = int(gl.x) + gl_VertexID;
    vec2 e = texelFetch(uMalla, ivec2(i % 1024, i / 1024), 0).xy;
    vec2 w = aPos + vec2(aM.x * e.x + aM.y * e.y, aM.z * e.x + aM.w * e.y);
    gl_Position = aPantalla(w);
}
"""

        private const val VS_ARCO = COMUN + """
layout(location = 0) in vec2 aCentro;
layout(location = 1) in float aRadio;
layout(location = 2) in float aInicio;
layout(location = 3) in float aBarrido;
layout(location = 4) in uint aColor;
out vec4 vColor;
out vec2 vMundo;
flat out vec4 vArco;
flat out float vBarrido;
const vec2 ESQ[6] = vec2[6](vec2(-1.0, -1.0), vec2(1.0, -1.0), vec2(-1.0, 1.0), vec2(1.0, -1.0), vec2(1.0, 1.0), vec2(-1.0, 1.0));
void main() {
    vec2 w = aCentro + ESQ[gl_VertexID] * (aRadio + (uGrosor + 2.0) * uPx);
    gl_Position = aPantalla(w);
    vColor = colorDe(aColor);
    vMundo = w;
    vArco = vec4(aCentro, aRadio, aInicio);
    vBarrido = aBarrido;
}
"""

        private const val FS_ARCO = COMUN + COLORES + """
in vec4 vColor;
in vec2 vMundo;
flat in vec4 vArco;
flat in float vBarrido;
out vec4 oColor;
void main() {
    vec2 d = vMundo - vArco.xy;
    float dist = abs(length(d) - vArco.z) / uPx;
    float a = clamp(uGrosor * 0.5 + 0.6 - dist, 0.0, 1.0);
    if (a <= 0.003) discard;
    if (vBarrido < 6.2831) {
        float ang = atan(d.y, d.x) - vArco.w;
        ang = ang - floor(ang / 6.2831853) * 6.2831853;
        if (ang > vBarrido) discard;
    }
    vec4 t = tinta(vColor);
    oColor = vec4(t.rgb, t.a * a);
}
"""
    }
}
