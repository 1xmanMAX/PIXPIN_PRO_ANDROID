package com.forge.pixpin.planos

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import java.nio.ByteBuffer
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.opengles.GL10

/**
 * **El modelo 3D en la tarjeta** (OpenGL ES 3.0): `gpu3d.rs` del PC con los mismos sombreadores
 * pasados a GLSL. Se sube una vez; cada fotograma solo cambia la cámara. Cinco pasadas, como allí:
 * lo opaco (con profundidad y luz), las aristas vivas encima (finas, oscuras), las rayas sueltas
 * (curvas de nivel, ejes, tuberías), los puntos (de tamaño fijo en pantalla) y lo transparente
 * (vidrios) al final, sin escribir profundidad. La **caja de sección** esconde lo que queda fuera
 * de ella (y pinta macizo lo cortado), y lo **elegido** se tiñe de azul.
 */
class Pintor3D(private val m: Modelo3D, private val grosor: Float, private val densidad: Float) : GLSurfaceView.Renderer {
    @Volatile var orbita: Orbita? = null
    @Volatile var claro = false
    @Volatile var conAristas = true
    /**
     * **La caja de sección** (10-oct-2026, la del PC `1e5be50`, como la de Revit): mínimo y máximo
     * (x, y, z) relativos al origen, o null sin caja. Lo de fuera no se ve, y lo cortado se ve
     * **macizo**: la cara de dentro que asoma por el hueco se pinta como tapa.
     */
    @Volatile var seccion: FloatArray? = null
    /** El elemento elegido, o -1. */
    @Volatile var elegido = -1
    @Volatile var fallo: String? = null
    /**
     * **Lo escondido** (10-oct-2026, como el «ocultar» y «aislar» de Revit): un byte por elemento,
     * 1 = no se pinta. Va a la tarjeta como textura y el sombreador de vértices tira lo escondido
     * fuera de la pantalla; cambiarlo no vuelve a subir el modelo.
     */
    @Volatile var ocultos: ByteArray? = null
        set(v) { field = v; ocultosNuevos = true }
    @Volatile private var ocultosNuevos = true
    var alCambiarTamano: ((Int, Int) -> Unit)? = null

    private var ancho = 1
    private var alto = 1
    private var listo = false
    private var prog3d = 0; private var progVidrio = 0; private var progTapa = 0; private var progArista = 0; private var progLinea = 0; private var progPunto = 0
    private val vao = IntArray(1)
    private var vb = 0
    private val ib = IntArray(5)
    private val texOcultos = IntArray(1)
    /** Lo que mide el modelo de punta a punta (para los planos de recorte de la cámara). */
    val radio: Double = run {
        val c = m.caja
        val dx = (c[3] - c[0]).toDouble(); val dy = (c[4] - c[1]).toDouble(); val dz = (c[5] - c[2]).toDouble()
        kotlin.math.sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-6)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        listo = false
        try { subir(); listo = true } catch (e: Throwable) { fallo = e.message ?: e.javaClass.simpleName }
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        ancho = w.coerceAtLeast(1); alto = h.coerceAtLeast(1)
        GLES30.glViewport(0, 0, ancho, alto)
        alCambiarTamano?.invoke(ancho, alto)
    }

    override fun onDrawFrame(gl: GL10?) {
        val fondo = if (claro) floatArrayOf(0.955f, 0.958f, 0.965f) else floatArrayOf(0.13f, 0.15f, 0.19f)
        GLES30.glClearColor(fondo[0], fondo[1], fondo[2], 1f)
        GLES30.glDepthMask(true)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        val o = orbita ?: return
        if (!listo) return
        val mvp = o.matriz(ancho, alto, radio)
        val (r, u, f) = o.ejes()
        val luz = Orbita.unit(Orbita.suma(Orbita.suma(Orbita.por(f, -0.5), Orbita.por(u, 0.75)), Orbita.por(r, 0.35)))
        val ojo = o.ojo()
        val c = seccion
        if (ocultosNuevos) { ocultosNuevos = false; subirOcultos() }
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texOcultos[0])
        fun vista(p: Int) {
            GLES30.glUseProgram(p)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(p, "uOcultos"), 0)
            GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(p, "uMvp"), 1, false, mvp, 0)
            GLES30.glUniform3f(GLES30.glGetUniformLocation(p, "uLuz"), luz[0].toFloat(), luz[1].toFloat(), luz[2].toFloat())
            GLES30.glUniform3f(GLES30.glGetUniformLocation(p, "uOjo"), ojo[0].toFloat(), ojo[1].toFloat(), ojo[2].toFloat())
            GLES30.glUniform4f(GLES30.glGetUniformLocation(p, "uCorte"), if (c != null) 1f else 0f, 0f, 4.5f * densidad, 0f)
            GLES30.glUniform3f(GLES30.glGetUniformLocation(p, "uSecMin"), c?.get(0) ?: 0f, c?.get(1) ?: 0f, c?.get(2) ?: 0f)
            GLES30.glUniform3f(GLES30.glGetUniformLocation(p, "uSecMax"), c?.get(3) ?: 0f, c?.get(4) ?: 0f, c?.get(5) ?: 0f)
            GLES30.glUniform1ui(GLES30.glGetUniformLocation(p, "uElegido"), (elegido + 1).toUInt().toInt())
            GLES30.glUniform1f(GLES30.glGetUniformLocation(p, "uClaro"), if (claro) 1f else 0f)
            GLES30.glUniform2f(GLES30.glGetUniformLocation(p, "uPantalla"), ancho.toFloat(), alto.toFloat())
        }
        GLES30.glBindVertexArray(vao[0])
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        // 1. Lo opaco, un poco hacia atrás para que las aristas se vean encima.
        if (m.nOpacos > 0) {
            vista(prog3d)
            GLES30.glDisable(GLES30.GL_BLEND); GLES30.glDepthMask(true)
            GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL); GLES30.glPolygonOffset(1f, 1f)
            dibujar(GLES30.GL_TRIANGLES, 0, m.nOpacos)
            GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL)
        }
        // 2. Las aristas vivas.
        if (conAristas && m.nAristas > 0) {
            vista(progArista)
            GLES30.glEnable(GLES30.GL_BLEND); GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
            GLES30.glDepthMask(false); GLES30.glLineWidth(1f)
            dibujar(GLES30.GL_LINES, 2, m.nAristas)
        }
        // 2a. Las tapas de la caja: después de las aristas, para que las del fondo queden tapadas.
        if (c != null && m.nOpacos > 0) {
            vista(progTapa)
            GLES30.glDisable(GLES30.GL_BLEND); GLES30.glDepthMask(true)
            dibujar(GLES30.GL_TRIANGLES, 0, m.nOpacos)
        }
        // 3. Las rayas sueltas.
        if (m.nLineas > 0) {
            vista(progLinea)
            GLES30.glEnable(GLES30.GL_BLEND); GLES30.glDepthMask(true); GLES30.glLineWidth(grosor)
            dibujar(GLES30.GL_LINES, 3, m.nLineas)
        }
        // 4. Los puntos.
        if (m.nPuntos > 0) {
            vista(progPunto)
            GLES30.glDisable(GLES30.GL_BLEND); GLES30.glDepthMask(true)
            dibujar(GLES30.GL_TRIANGLES, 4, m.nPuntos)
        }
        // 5. Lo transparente, al final y sin escribir profundidad (las dos caras, sin tapas).
        if (m.nTransparentes > 0) {
            vista(progVidrio)
            GLES30.glEnable(GLES30.GL_BLEND); GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
            GLES30.glDepthMask(false)
            dibujar(GLES30.GL_TRIANGLES, 1, m.nTransparentes)
        }
        GLES30.glDepthMask(true)
        GLES30.glBindVertexArray(0)
    }

    private fun dibujar(modo: Int, lista: Int, n: Int) {
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ib[lista])
        GLES30.glDrawElements(modo, n, GLES30.GL_UNSIGNED_INT, 0)
    }

    private fun subir() {
        prog3d = programa(VS_3D, FS_3D)
        progVidrio = programa(VS_3D, FS_VIDRIO)
        progTapa = programa(VS_3D, FS_TAPA)
        progArista = programa(VS_3D, FS_ARISTA)
        progLinea = programa(VS_3D, FS_LINEA)
        progPunto = programa(VS_PUNTO, FS_PUNTO)
        GLES30.glGenVertexArrays(1, vao, 0)
        GLES30.glBindVertexArray(vao[0])
        vb = buffer(GLES30.GL_ARRAY_BUFFER, m.vertices, m.nVertices * 24)
        GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 24, 0)
        GLES30.glEnableVertexAttribArray(1); GLES30.glVertexAttribIPointer(1, 1, GLES30.GL_UNSIGNED_INT, 24, 12)
        GLES30.glEnableVertexAttribArray(2); GLES30.glVertexAttribIPointer(2, 1, GLES30.GL_UNSIGNED_INT, 24, 16)
        GLES30.glEnableVertexAttribArray(3); GLES30.glVertexAttribIPointer(3, 1, GLES30.GL_UNSIGNED_INT, 24, 20)
        GLES30.glBindVertexArray(0)
        val listas = arrayOf(m.opacos to m.nOpacos, m.transparentes to m.nTransparentes, m.aristas to m.nAristas, m.lineas to m.nLineas, m.puntos to m.nPuntos)
        for ((k, l) in listas.withIndex()) ib[k] = buffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, l.first, l.second * 4)
        GLES30.glGenTextures(1, texOcultos, 0)
        ocultosNuevos = true
        if (GLES30.glGetError() == GLES30.GL_OUT_OF_MEMORY) throw IllegalStateException("El modelo no cabe en la tarjeta gráfica")
    }

    /** Los bytes de [ocultos] en una textura de [ANCHO_OCULTOS] de ancho (un elemento por texel). */
    private fun subirOcultos() {
        val n = m.elementos.size.coerceAtLeast(1)
        val filas = (n + ANCHO_OCULTOS - 1) / ANCHO_OCULTOS
        val datos = ByteBuffer.allocateDirect(ANCHO_OCULTOS * filas)
        ocultos?.let { o -> datos.put(o, 0, minOf(o.size, datos.capacity())) }
        datos.position(0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texOcultos[0])
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8UI, ANCHO_OCULTOS, filas, 0, GLES30.GL_RED_INTEGER, GLES30.GL_UNSIGNED_BYTE, datos)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
    }

    private fun buffer(tipo: Int, datos: ByteBuffer, bytes: Int): Int {
        val id = IntArray(1).also { GLES30.glGenBuffers(1, it, 0) }[0]
        GLES30.glBindBuffer(tipo, id)
        if (bytes > 0) GLES30.glBufferData(tipo, bytes, datos.duplicate().apply { position(0); limit(bytes) }, GLES30.GL_STATIC_DRAW)
        else GLES30.glBufferData(tipo, 4, ByteBuffer.allocateDirect(4), GLES30.GL_STATIC_DRAW)
        return id
    }

    private fun programa(vs: String, fs: String): Int {
        fun sombreador(tipo: Int, fuente: String): Int {
            val s = GLES30.glCreateShader(tipo)
            GLES30.glShaderSource(s, fuente); GLES30.glCompileShader(s)
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

    /** Antialias 4× y profundidad de 24 bits si se puede; si no, lo que haya con profundidad. */
    class Muestreo : GLSurfaceView.EGLConfigChooser {
        override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig? {
            val es3 = 0x40
            for ((muestras, prof) in listOf(4 to 24, 0 to 24, 0 to 16)) {
                val atributos = intArrayOf(
                    EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_DEPTH_SIZE, prof,
                    EGL10.EGL_RENDERABLE_TYPE, es3,
                    EGL10.EGL_SAMPLE_BUFFERS, if (muestras > 0) 1 else 0, EGL10.EGL_SAMPLES, muestras, EGL10.EGL_NONE
                )
                val n = IntArray(1); val configs = arrayOfNulls<EGLConfig>(1)
                if (egl.eglChooseConfig(display, atributos, configs, 1, n) && n[0] > 0) return configs[0]
            }
            val n = IntArray(1); val configs = arrayOfNulls<EGLConfig>(1)
            egl.eglChooseConfig(display, intArrayOf(EGL10.EGL_RENDERABLE_TYPE, es3, EGL10.EGL_DEPTH_SIZE, 16, EGL10.EGL_NONE), configs, 1, n)
            return configs[0] ?: throw IllegalArgumentException("Este teléfono no tiene OpenGL ES 3")
        }
    }

    companion object {
        private const val ANCHO_OCULTOS = 1024

        private const val COMUN = """#version 300 es
precision highp float;
precision highp int;
precision highp usampler2D;
uniform mat4 uMvp;
uniform vec3 uLuz;
uniform vec3 uOjo;
uniform vec4 uCorte;     // x: 1 si hay caja de sección, z: el radio de los puntos en píxeles
uniform vec3 uSecMin;    // la caja de sección, relativa al origen
uniform vec3 uSecMax;
uniform uint uElegido;   // elemento resaltado + 1 (0: ninguno)
uniform float uClaro;
uniform vec2 uPantalla;
"""

        private const val VS_COMUN = COMUN + """
layout(location = 0) in vec3 aPos;
layout(location = 1) in uint aNormal;
layout(location = 2) in uint aColor;
layout(location = 3) in uint aElem;
out vec3 vN;
out vec4 vC;
out vec3 vW;
flat out uint vE;
uniform usampler2D uOcultos;
// Lo escondido sale fuera del recorte (todos sus vértices: el triángulo entero se descarta).
bool oculto(uint e) { return texelFetch(uOcultos, ivec2(int(e % 1024u), int(e / 1024u)), 0).r != 0u; }
vec3 normalDe(uint n) {
    ivec3 v = ivec3(int(n << 24u) >> 24, int(n << 16u) >> 24, int(n << 8u) >> 24);
    return vec3(v) / 127.0;
}
vec4 colorDe(uint c) {
    return vec4(float(c & 255u), float((c >> 8u) & 255u), float((c >> 16u) & 255u), float((c >> 24u) & 255u)) / 255.0;
}
"""

        private const val VS_3D = VS_COMUN + """
void main() {
    gl_Position = oculto(aElem) ? vec4(2.0, 2.0, 2.0, 1.0) : uMvp * vec4(aPos, 1.0);
    vN = normalDe(aNormal); vC = colorDe(aColor); vW = aPos; vE = aElem;
}
"""

        /** Un punto: 4 vértices en el mismo sitio; la esquina (byte alto de la normal) lo abre en pantalla. */
        private const val VS_PUNTO = VS_COMUN + """
void main() {
    gl_Position = uMvp * vec4(aPos, 1.0);
    uint k = (aNormal >> 24u) & 3u;
    vec2 d = vec2((k & 1u) != 0u ? 1.0 : -1.0, (k & 2u) != 0u ? 1.0 : -1.0);
    gl_Position.xy += d * uCorte.z * 2.0 / uPantalla * gl_Position.w;
    if (oculto(aElem)) gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
    vN = vec3(d, 0.0); vC = colorDe(aColor); vW = aPos; vE = aElem;
}
"""

        private const val FS_COMUN = COMUN + """
in vec3 vN;
in vec4 vC;
in vec3 vW;
flat in uint vE;
out vec4 oColor;
// La caja de sección (la de Revit): lo de fuera no se ve.
void cortar() { if (uCorte.x > 0.5 && (any(lessThan(vW, uSecMin)) || any(greaterThan(vW, uSecMax)))) discard; }
// Dónde entra en la caja el rayo del ojo a w (fracción del camino; <= 0 si el ojo ya está dentro)
// y por qué cara (su normal, hacia fuera).
float entrada(vec3 w, out vec3 cara) {
    vec3 d = w - uOjo;
    d = mix(d, vec3(1e-9), lessThan(abs(d), vec3(1e-9)));
    vec3 t1 = (uSecMin - uOjo) / d;
    vec3 t2 = (uSecMax - uOjo) / d;
    vec3 tn = min(t1, t2);
    float t = max(tn.x, max(tn.y, tn.z));
    vec3 eje = (tn.x >= t) ? vec3(1.0, 0.0, 0.0) : ((tn.y >= t) ? vec3(0.0, 1.0, 0.0) : vec3(0.0, 0.0, 1.0));
    cara = -eje * sign(d);
    return t;
}
// **La tapa del corte**: con la caja puesta, lo que se ve de un sólido por el hueco que abre una
// cara es su cara de dentro. Se mira con la normal geométrica (vuelta hacia el ojo) contra la
// guardada: así las normales suaves no engañan en los bordes.
bool esTapa() {
    if (uCorte.x < 0.5) return false;
    vec3 g = cross(dFdx(vW), dFdy(vW));
    if (dot(g, uOjo - vW) < 0.0) g = -g;
    vec3 cara;
    return dot(vN, g) < 0.0 && entrada(vW, cara) > 0.0;
}
vec4 sombrear() {
    vec3 n = normalize(vN);
    vec3 v = normalize(uOjo - vW);
    if (dot(n, v) < 0.0) n = -n;   // las dos caras
    float dif = clamp(dot(n, uLuz), 0.0, 1.0);
    float cielo = 0.5 + 0.5 * n.z;
    vec3 h = normalize(uLuz + v);
    float brillo = pow(clamp(dot(n, h), 0.0, 1.0), 40.0) * 0.12;
    vec3 col = vC.rgb * (0.30 + 0.22 * cielo + 0.55 * dif) + brillo;
    if (vE + 1u == uElegido) col = mix(col, vec3(0.10, 0.55, 1.0), 0.55);
    return vec4(col, vC.a);
}
"""

        /** Lo opaco, sin las tapas (van después, en [FS_TAPA]). */
        private const val FS_3D = FS_COMUN + """
void main() {
    cortar();
    if (esTapa()) discard;
    oColor = sombrear();
}
"""

        /** Los vidrios: las dos caras, como siempre. */
        private const val FS_VIDRIO = FS_COMUN + """
void main() {
    cortar();
    oColor = sombrear();
}
"""

        /** La tapa: plana y algo más oscura que el elemento, para que se lea como corte. */
        private const val FS_TAPA = FS_COMUN + """
void main() {
    cortar();
    if (!esTapa()) discard;
    vec3 cara;
    entrada(vW, cara);
    float dif = clamp(dot(cara, uLuz), 0.0, 1.0);
    vec3 col = vC.rgb * (0.40 + 0.12 * (0.5 + 0.5 * cara.z) + 0.55 * dif) * 0.8;
    if (vE + 1u == uElegido) col = mix(col, vec3(0.10, 0.55, 1.0), 0.55);
    oColor = vec4(col, 1.0);
}
"""

        private const val FS_PUNTO = FS_COMUN + """
void main() {
    cortar();
    float r = length(vN.xy);
    if (r > 1.0) discard;
    vec3 col = vC.rgb;
    if (vE + 1u == uElegido) col = vec3(0.10, 0.55, 1.0);
    if (r > 0.62) col *= 0.35;
    oColor = vec4(col, 1.0);
}
"""

        private const val FS_LINEA = FS_COMUN + """
void main() {
    cortar();
    if (vE + 1u == uElegido) { oColor = vec4(0.10, 0.55, 1.0, 1.0); return; }
    oColor = vC;
}
"""

        private const val FS_ARISTA = FS_COMUN + """
void main() {
    cortar();
    vec3 base = vC.rgb * 0.28;
    if (vE + 1u == uElegido) base = vec3(0.0, 0.30, 0.75);
    oColor = vec4(base, uClaro > 0.5 ? 0.75 : 0.85);
}
"""
    }
}
