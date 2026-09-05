package com.forge.pixpin.croquis3d

import com.forge.pixpin.motor.Pt
import com.forge.pixpin.motor.Pt3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * La cámara del croquis en el espacio: **desde dónde se mira**.
 *
 * ## Por qué una cámara nueva y no la del lienzo
 *
 * El lienzo tiene su isometría de cuatro vistas ([com.forge.pixpin.motor.Vista]) y está
 * bien para lo que hace: poner una caja sobre un plano y poder comprobarla desde otro
 * lado. Aquí no vale. Dibujar en el espacio consiste en **girar la vista y seguir
 * dibujando**, y con cuatro posiciones fijas eso no existe: el plano que acabas de trazar
 * o lo ves de frente o lo ves de canto, y no hay nada en medio donde apoyarse.
 *
 * Así que esta es una cámara de órbita de verdad: dos ángulos y una distancia.
 *
 * ## La lente
 *
 * De fábrica es **ortográfica**, por lo mismo que el lienzo elige isometría: sin punto de
 * fuga se puede medir sobre el dibujo, dos trazos iguales se dibujan iguales estén donde
 * estén, y el dedo cae siempre en el mismo sitio del plano que se esté usando. Es lo que
 * hace que trazar sobre una lámina inclinada no resbale.
 *
 * Pero se le puede montar una lente: [lente] abre el campo de visión desde cero —la
 * ortográfica— hasta un ojo de pez. Y no es un adorno. Un croquis en el espacio se lee
 * regular sin punto de fuga: **la profundidad no se ve**, un pasillo largo y un pasillo
 * corto se dibujan igual, y hasta que uno no gira no sabe cuál es cuál. Con la lente
 * abierta, la misma escena de repente tiene fondo. Se cambia sobre la marcha porque cada
 * cosa pide una: medir pide ortográfica, mirar pide gran angular, y meterse dentro de lo
 * dibujado pide ojo de pez.
 *
 * El mapeo es **equidistante** —el de un ojo de pez de verdad, `r = f·θ`— y no el de un
 * agujero de alfiler, `r = f·tan θ`. Con la tangente, un campo de 150° manda los bordes al
 * infinito y lo que queda detrás de la cámara se da la vuelta y aparece invertido en la
 * pantalla; con el equidistante no hay ninguna singularidad, se puede abrir hasta ver casi
 * la esfera entera, y a campos pequeños las dos fórmulas son la misma.
 *
 * ## El sistema
 *
 * `x` a la derecha, `y` hacia dentro, `z` hacia arriba: el mismo del lienzo en volumen, y
 * el mismo que espera cualquiera que haya dibujado una planta y un alzado. La pantalla
 * tiene la `y` creciendo hacia abajo, así que subir en el mundo resta en la pantalla — la
 * única línea que se lee al revés de lo que uno espera.
 */
@kotlinx.serialization.Serializable
data class Camara3D(
    /** Giro alrededor del eje vertical, en radianes. Cero mira desde `-y`. */
    val giro: Double = -Math.PI / 5,
    /**
     * Cuánto se mira desde arriba, en radianes.
     *
     * Acotado a un pelo por debajo del cenit: justo encima, el «arriba» de la cámara deja
     * de estar definido y la escena pega un tirón al cruzar. Ver [conInclinacion].
     */
    val inclinacion: Double = Math.PI / 7,
    /** Cuántos píxeles de pantalla mide una unidad del mundo, en el plano del centro. */
    val zoom: Double = 1.0,
    /** A qué punto del mundo se mira. Es lo que se mueve al desplazar con dos dedos. */
    val centro: Pt3 = Pt3(0.0, 0.0, 0.0),
    /**
     * Cuánto se ladea la cámara, en radianes. Cero es con el horizonte horizontal.
     *
     * Es el eje de la propia cámara y no el de la escena: ladearla no mueve nada de sitio,
     * cambia desde qué postura se mira. Sirve para lo mismo que girar el papel sobre la
     * mesa —un trazo largo sale mejor cómodo que recto— y para encajar algo alargado en
     * una pantalla que es más alta que ancha.
     */
    val balanceo: Double = 0.0,
    /**
     * La lente: **cero es ortográfica y uno es ojo de pez**.
     *
     * Es el campo de visión en tanto por uno de [CAMPO_MAXIMO]. Ver la nota de la clase.
     */
    val lente: Double = 0.0,
    /**
     * Si la lente proyecta **como una cámara de verdad** (`r = f·tan θ`) en vez de como
     * un ojo de pez (`r = f·θ`).
     *
     * Existe por el croquis puesto en el sitio: ahí lo dibujado se pinta encima de lo que
     * ve la cámara del teléfono, y la cámara del teléfono es un agujero de alfiler. Con el
     * mapeo equidistante, un punto a treinta grados del centro cae un doce por ciento más
     * cerca del centro de lo que cae en la foto, así que al girar el teléfono el croquis
     * **resbalaba** sobre la habitación por los bordes de la pantalla — la sensación exacta
     * de que no está puesto en ningún sitio. Con la tangente cae donde cae en la foto.
     *
     * Fuera de ese modo se queda apagado: la tangente manda al infinito lo que se acerca a
     * los noventa grados, y la lente de dibujar llega a los ciento sesenta. Aquí lo de más
     * allá de [TOPE_RECTILINEO] se planta en el borde, que es lo que hace que lo de la
     * espalda no se dé la vuelta.
     */
    val rectilinea: Boolean = false
) {
    /** El campo de visión, en radianes. Cero con la ortográfica. */
    val campo: Double get() = lente.coerceIn(0.0, 1.0) * CAMPO_MAXIMO

    /** Cómo se llama la lente que hay puesta, para decírselo a quien la está girando. */
    val nombreDeLaLente: String
        get() = when {
            lente < 0.02 -> "ortográfica"
            lente < 0.30 -> "normal"
            lente < 0.62 -> "gran angular"
            else -> "ojo de pez"
        }

    /**
     * La distancia focal en píxeles: media pantalla entre medio campo.
     *
     * Sale de que el borde de la pantalla es justo el borde del campo de visión. Con el
     * campo a cero se va al infinito, que es exactamente lo que es una ortográfica.
     */
    private fun focal(alto: Double): Double =
        if (rectilinea) (alto / 2) / kotlin.math.tan(campo / 2) else alto / campo

    /**
     * A qué distancia del centro está el ojo.
     *
     * Sale de exigir que **en el plano del centro la escala siga siendo [zoom]**: sin eso,
     * abrir la lente cambiaría el tamaño de lo dibujado y parecería que la vista se aleja
     * sola. Acercar, con lente puesta, acerca el ojo de verdad — que es lo que hace una
     * cámara y lo que da esa sensación de meterse dentro.
     */
    private fun distanciaDelOjo(alto: Double): Double = focal(alto) / zoom

    /**
     * **Dónde está el ojo**, o nada con la cámara ortográfica.
     *
     * Sin lente no hay ojo: todas las visuales son paralelas y lo que hay es una dirección,
     * así que nada queda «detrás de la cámara» y no hay nada que recortar. Con lente sí, y
     * entonces hace falta saberlo: lo que cae por detrás del ojo no se proyecta, **se
     * envuelve** —sale por los bordes, que es lo que hace un ojo de fez— y una figura grande
     * que lo cruce se pinta retorcida sobre sí misma. Ver [laFranjaQueSeVe].
     *
     * Y se acerca al acercarse: el ojo está a la focal partida por el aumento, así que hacer
     * zoom **mete el ojo dentro de la escena** —que es lo que hace una cámara de verdad— y
     * por eso una hoja que llegaba hasta el fondo acababa cruzándolo.
     */
    fun ojo(alto: Double): Pt3? =
        if (campo <= 0.0) null else menos(centro, por(adelante, distanciaDelOjo(alto)))

    /** Hacia dónde mira la cámara: del ojo al centro. */
    val adelante: Pt3
        get() {
            val ci = cos(inclinacion)
            return normalizado(Pt3(sin(giro) * ci, cos(giro) * ci, -sin(inclinacion)))
        }

    /** La derecha de la pantalla, en el mundo, ya ladeada lo que diga [balanceo]. */
    val derecha: Pt3
        get() {
            val horizontal = normalizado(Pt3(cos(giro), -sin(giro), 0.0))
            if (balanceo == 0.0) return horizontal
            val vertical = normalizado(producto(horizontal, adelante))
            return normalizado(
                mas(por(horizontal, cos(balanceo)), por(vertical, sin(balanceo)))
            )
        }

    /** El arriba de la pantalla, en el mundo. */
    val arriba: Pt3 get() = normalizado(producto(derecha, adelante))

    /**
     * **La misma cámara mirando hacia otro lado, pero desde el mismo sitio.**
     *
     * Es la diferencia entre girar la vista y mirar alrededor, y es lo que hace falta para
     * enseñar el croquis puesto en el mundo de verdad. Esta cámara es de órbita: cambiarle
     * los ángulos la lleva **alrededor** de lo que mira, así que lo mirado se queda siempre
     * en el centro de la pantalla. Con el teléfono en la mano eso está al revés de lo que
     * pasa: uno se gira y lo que hay delante **se sale** de la vista, porque uno no orbita
     * nada, está de pie mirando a otro lado.
     *
     * Así que se calcula dónde está el ojo, se le cambian los ángulos y se recoloca el
     * punto mirado delante de él a la misma distancia. El ojo no se ha movido y la vista
     * sí: eso es mirar alrededor. Ver [Croquis3DControlador.mirarComoElTelefono].
     */
    fun mirandoDesdeElMismoSitio(
        giro: Double,
        inclinacion: Double,
        balanceo: Double,
        alto: Double
    ): Camara3D {
        val d = distanciaDelOjo(alto)
        if (!d.isFinite() || d <= 0.0) {
            // Sin lente no hay ojo: todas las visuales son paralelas y no hay «desde dónde»
            // que conservar. Se giran los ángulos y ya.
            return copy(
                giro = giro, inclinacion = conInclinacion(inclinacion), balanceo = balanceo
            )
        }
        val a = adelante
        val ojo = Pt3(centro.x - a.x * d, centro.y - a.y * d, centro.z - a.z * d)
        val nueva = copy(
            giro = giro, inclinacion = conInclinacion(inclinacion), balanceo = balanceo
        )
        val b = nueva.adelante
        return nueva.copy(centro = Pt3(ojo.x + b.x * d, ojo.y + b.y * d, ojo.z + b.z * d))
    }

    /**
     * **Otra lente, sin mover el ojo.**
     *
     * Cambiar la lente cambia a qué distancia del centro está el ojo, y en el croquis
     * puesto en el sitio eso es justo lo que no puede pasar: se aprende la lente de verdad
     * del aparato un instante después de encender la cámara, y si el ojo se moviera el
     * croquis pegaría un salto. Aquí se deja el ojo donde estaba y se recoloca el centro
     * delante de él a la distancia que pida la lente nueva.
     */
    fun conLenteDesdeElMismoSitio(lente: Double, alto: Double): Camara3D {
        val ojo = ojo(alto) ?: return copy(lente = lente)
        val nueva = copy(lente = lente)
        val d = nueva.distanciaDelOjo(alto)
        if (!d.isFinite() || d <= 0.0) return nueva
        val a = nueva.adelante
        return nueva.copy(centro = Pt3(ojo.x + a.x * d, ojo.y + a.y * d, ojo.z + a.z * d))
    }

    /**
     * De un punto del mundo a la pantalla.
     *
     * [ancho] y [alto] son los de la vista: el centro de la cámara cae en el centro de la
     * pantalla, que es lo que hace que girar no desplace el dibujo.
     */
    fun aPantalla(p: Pt3, ancho: Double, alto: Double): Pt {
        // **Sin fabricar el vector de en medio.** Se proyecta cada punto de cada trazo en
        // cada fotograma: al alejarse la vista no se descarta nada —se ve todo—, así que
        // esto llega a correr cien mil veces por fotograma. Un `Pt3` de usar y tirar por
        // vuelta son cien mil objetos por fotograma para el basurero, y eso no se ve como
        // memoria gastada: se ve como el tirón cada pocos segundos que hace que alejar la
        // vista sea insoportable. Las tres restas caben en tres cuentas sueltas.
        val dx = p.x - centro.x
        val dy = p.y - centro.y
        val dz = p.z - centro.z
        val u = dx * derecha.x + dy * derecha.y + dz * derecha.z
        val v = dx * arriba.x + dy * arriba.y + dz * arriba.z
        if (campo <= 0.0) return Pt(ancho / 2 + u * zoom, alto / 2 - v * zoom)

        // Con lente: el ángulo entre la mirada y el punto, y a la pantalla por el mapeo
        // equidistante. `atan2` con la profundidad medida **desde el ojo** deja que el
        // ángulo pase de noventa grados sin romperse: lo que queda a la espalda sale por
        // los bordes, que es lo que hace un ojo de pez y no una división por cero.
        val radio = hypot(u, v)
        val hondo = (dx * adelante.x + dy * adelante.y + dz * adelante.z) +
            distanciaDelOjo(alto)
        if (radio < 1e-9) {
            // **En el eje de la mirada: delante es el centro y detrás no está en ninguna
            // parte.**
            //
            // Devolvía el centro en los dos casos, y eso es un punto que está justo a tu
            // espalda pintado en mitad de la pantalla. Con la vista de siempre no se notaba
            // —uno no se pone de espaldas a lo que está dibujando—, pero mirando el croquis
            // puesto en el sitio es lo primero que pasa: te das la vuelta y lo que dejaste
            // detrás aparece flotando delante. En el mapeo equidistante, media vuelta es
            // radio `focal·π`, que se sale de cualquier pantalla; la dirección da igual
            // porque en el eje no hay ninguna.
            if (hondo >= 0.0) return Pt(ancho / 2, alto / 2)
            return Pt(ancho / 2, alto / 2 + focal(alto) * Math.PI)
        }
        val enPantalla = focal(alto) * anguloProyectado(atan2(radio, hondo), rectilinea)
        return Pt(
            ancho / 2 + enPantalla * u / radio,
            alto / 2 - enPantalla * v / radio
        )
    }

    /**
     * **La base de la cámara, pagada una vez por fotograma.**
     *
     * [derecha], [arriba] y [adelante] se calculan al pedirse —senos, cosenos y un par de
     * normalizados con sus objetos— y [aPantalla] los pide **por punto**: proyectar cien mil
     * puntos por fotograma era rehacer cien mil veces la misma trigonometría para obtener
     * cien mil veces el mismo vector. Congelada aquí, la proyección por punto queda en nueve
     * multiplicaciones y ninguna asignación.
     *
     * Da EXACTAMENTE lo mismo que [aPantalla] — la misma cuenta sobre los mismos dobles, y
     * hay una prueba que exige igualdad exacta. Vale para un fotograma: la cámara es
     * inmutable, así que congelarla es copiar sus números.
     */
    fun base(ancho: Double, alto: Double): BaseDeCamara {
        val d = derecha
        val a = arriba
        val f = adelante
        return BaseDeCamara(
            dx = d.x, dy = d.y, dz = d.z,
            ax = a.x, ay = a.y, az = a.z,
            fx = f.x, fy = f.y, fz = f.z,
            cx = centro.x, cy = centro.y, cz = centro.z,
            zoom = zoom, campo = campo,
            medioAncho = ancho / 2, medioAlto = alto / 2,
            focal = if (campo > 0.0) focal(alto) else 0.0,
            ojo = if (campo > 0.0) distanciaDelOjo(alto) else 0.0,
            rectilinea = rectilinea
        )
    }

    /**
     * El rayo que sale de un punto de la pantalla, **paralelo a la mirada**.
     *
     * Con la cámara ortográfica todos los rayos son paralelos: lo que cambia de un punto
     * de la pantalla a otro es de dónde parten, no hacia dónde van. Es justo lo que hace
     * que dibujar sobre un plano inclinado no resbale.
     */
    fun rayo(p: Pt, ancho: Double, alto: Double): Rayo {
        if (campo > 0.0) return rayoConLente(p, ancho, alto)
        val dx = (p.x - ancho / 2) / zoom
        val dy = (alto / 2 - p.y) / zoom
        val origen = Pt3(
            centro.x + derecha.x * dx + arriba.x * dy,
            centro.y + derecha.y * dx + arriba.y * dy,
            centro.z + derecha.z * dx + arriba.z * dy
        )
        // Se retira el origen bien atrás para que todo lo dibujado quede por delante:
        // así la distancia a lo largo del rayo sirve para ordenar de cerca a lejos.
        val a = adelante
        return Rayo(
            Pt3(a.x * -RETIRADA + origen.x, a.y * -RETIRADA + origen.y, a.z * -RETIRADA + origen.z),
            a
        )
    }

    /**
     * El rayo con lente puesta: **todos salen del ojo**, cada uno hacia su ángulo.
     *
     * Es el inverso exacto de [aPantalla], que es lo único que importa aquí: el punto de
     * la hoja bajo la punta del lápiz tiene que ser el que se ve bajo la punta del lápiz.
     * Con una fórmula aproximada, dibujar con la lente abierta iría corrido hacia los
     * bordes, y justo en los bordes es donde más se nota una lente.
     */
    private fun rayoConLente(p: Pt, ancho: Double, alto: Double): Rayo {
        val f = focal(alto)
        val dx = p.x - ancho / 2
        val dy = alto / 2 - p.y
        val radio = hypot(dx, dy)
        val ojo = ojo(alto) ?: centro
        if (radio < 1e-9) return Rayo(ojo, adelante)
        // El inverso exacto del mapeo que haya puesto. Ver [rectilinea].
        val angulo = if (rectilinea) kotlin.math.atan(radio / f) else radio / f
        val direccion = normalizado(
            mas(
                por(adelante, cos(angulo)),
                por(
                    mas(por(derecha, dx / radio), por(arriba, dy / radio)),
                    sin(angulo)
                )
            )
        )
        return Rayo(ojo, direccion)
    }

    /** La misma cámara girada. */
    fun girada(dGiro: Double, dInclinacion: Double): Camara3D = copy(
        giro = giro + dGiro,
        inclinacion = conInclinacion(inclinacion + dInclinacion)
    )

    /** La misma cámara con otro aumento, dentro de lo que se puede leer. */
    fun conZoom(factor: Double): Camara3D =
        copy(zoom = (zoom * factor).coerceIn(ZOOM_MINIMO, ZOOM_MAXIMO))

    /** La misma cámara ladeada. Ver [balanceo]. */
    fun balanceada(dBalanceo: Double): Camara3D = copy(balanceo = balanceo + dBalanceo)

    /** Con la lente más abierta o más cerrada, entre la ortográfica y el ojo de pez. */
    fun conLente(dLente: Double): Camara3D = copy(lente = (lente + dLente).coerceIn(0.0, 1.0))

    /** Desplazada por la pantalla: el centro se mueve en el plano de la vista. */
    fun desplazada(dx: Double, dy: Double): Camara3D {
        val r = derecha
        val u = arriba
        return copy(
            centro = Pt3(
                centro.x - (r.x * dx - u.x * dy) / zoom,
                centro.y - (r.y * dx - u.y * dy) / zoom,
                centro.z - (r.z * dx - u.z * dy) / zoom
            )
        )
    }

    companion object {
        /**
         * Hasta dónde se deja subir o bajar la vista: un pelo antes del cenit.
         *
         * En el cenit exacto, el «arriba» de la cámara sale del producto vectorial de dos
         * vectores paralelos —o sea, nada— y la escena pega un tirón. Un grado antes no se
         * nota y no puede pasar.
         */
        val TOPE_DE_INCLINACION = Math.PI / 2 - 0.02

        /**
         * Lo más que se abre la lente: ciento sesenta grados.
         *
         * Más que eso y la mitad de la pantalla es lo que uno tiene a la espalda, que se
         * ve curioso una vez y estorba siempre. Ciento sesenta ya es un ojo de pez de los
         * de verdad.
         */
        val CAMPO_MAXIMO = Math.toRadians(160.0)

        const val ZOOM_MINIMO = 0.05
        const val ZOOM_MAXIMO = 40.0

        /**
         * Hasta qué ángulo proyecta la lente rectilínea: ochenta grados.
         *
         * La tangente de noventa es infinito, y más allá cambia de signo — lo de la
         * espalda saldría dado la vuelta en mitad de la pantalla. A ochenta grados el
         * punto ya cae a cuatro pantallas del centro, o sea, fuera; de ahí en adelante se
         * queda clavado en ese radio, por el lado que le toca. Ver [rectilinea].
         */
        val TOPE_RECTILINEO = Math.toRadians(80.0)

        /**
         * El ángulo con la mirada, pasado al radio de pantalla en unidades de focal.
         *
         * Una sola función para las cuatro proyecciones que hay —la de la cámara, la de
         * su base congelada y las dos de la pluma—, que es lo que garantiza que den lo
         * mismo. Con el ojo de pez es el propio ángulo, y por eso no cambia nada de lo
         * que había.
         */
        fun anguloProyectado(angulo: Double, rectilinea: Boolean): Double =
            if (rectilinea) kotlin.math.tan(minOf(angulo, TOPE_RECTILINEO)) else angulo

        /** Cuánto se retira el origen del rayo. Ver [rayo]. */
        private const val RETIRADA = 100000.0

        fun conInclinacion(v: Double): Double = v.coerceIn(-TOPE_DE_INCLINACION, TOPE_DE_INCLINACION)
    }
}

/** Un rayo del espacio: de dónde sale y hacia dónde va. */
data class Rayo(val origen: Pt3, val direccion: Pt3)

/** Un plano del espacio, por un punto suyo y su normal. */
data class Plano3D(val punto: Pt3, val normal: Pt3) {
    /**
     * Dónde corta [rayo] a este plano, y a qué distancia del origen del rayo.
     *
     * Null si son paralelos: no hay corte, y devolver un punto inventado sería peor que
     * no devolver ninguno — el trazo aparecería en el infinito.
     */
    fun corte(rayo: Rayo): Pair<Pt3, Double>? {
        val denominador = escalar(rayo.direccion, normal)
        if (abs(denominador) < 1e-9) return null
        val d = Pt3(
            punto.x - rayo.origen.x, punto.y - rayo.origen.y, punto.z - rayo.origen.z
        )
        val t = escalar(d, normal) / denominador
        if (t < 0) return null
        return Pt3(
            rayo.origen.x + rayo.direccion.x * t,
            rayo.origen.y + rayo.direccion.y * t,
            rayo.origen.z + rayo.direccion.z * t
        ) to t
    }
}

/**
 * Una postura de la vista: **desde qué dos ángulos se mira**.
 *
 * Es lo único que hace falta para encajar en una de las vistas de dibujo técnico. El
 * centro y el aumento no entran: encajar es girarse alrededor de lo que se está mirando,
 * no irse a otro sitio ni acercarse.
 */
/**
 * La cámara de un fotograma, congelada en números sueltos. Ver [Camara3D.base].
 *
 * Todo en campos primitivos a propósito: esto se lee en el bucle más caliente de la
 * aplicación y cada indirection o asignación se paga cien mil veces.
 */
class BaseDeCamara internal constructor(
    @JvmField val dx: Double, @JvmField val dy: Double, @JvmField val dz: Double,
    @JvmField val ax: Double, @JvmField val ay: Double, @JvmField val az: Double,
    @JvmField val fx: Double, @JvmField val fy: Double, @JvmField val fz: Double,
    @JvmField val cx: Double, @JvmField val cy: Double, @JvmField val cz: Double,
    @JvmField val zoom: Double, @JvmField val campo: Double,
    @JvmField val medioAncho: Double, @JvmField val medioAlto: Double,
    @JvmField val focal: Double, @JvmField val ojo: Double,
    /** Ver [Camara3D.rectilinea]. */
    @JvmField val rectilinea: Boolean = false
) {

    /** El radio de pantalla de un punto a [radio] del eje y a [hondo] del ojo. */
    fun radioEnPantalla(radio: Double, hondo: Double): Double =
        focal * Camara3D.anguloProyectado(kotlin.math.atan2(radio, hondo), rectilinea)

    /** Idéntico a [Camara3D.aPantalla], con la base ya pagada. */
    fun aPantalla(p: Pt3): Pt {
        val vx = p.x - cx
        val vy = p.y - cy
        val vz = p.z - cz
        val u = vx * dx + vy * dy + vz * dz
        val v = vx * ax + vy * ay + vz * az
        if (campo <= 0.0) return Pt(medioAncho + u * zoom, medioAlto - v * zoom)
        val radio = kotlin.math.hypot(u, v)
        val hondo = (vx * fx + vy * fy + vz * fz) + ojo
        if (radio < 1e-9) {
            if (hondo >= 0.0) return Pt(medioAncho, medioAlto)
            return Pt(medioAncho, medioAlto + focal * Math.PI)
        }
        val enPantalla = radioEnPantalla(radio, hondo)
        return Pt(
            medioAncho + enPantalla * u / radio,
            medioAlto - enPantalla * v / radio
        )
    }

    /**
     * Un lote entero de puntos empaquetados, sin una sola asignación.
     *
     * [xyz] trae 3n dobles y [salida] recibe 2n (x, y de pantalla). Es el camino del
     * esqueleto del trazo: los cien mil puntos van por aquí y no por [aPantalla].
     */
    fun proyecta(xyz: DoubleArray, salida: DoubleArray) {
        val n = xyz.size / 3
        require(salida.size >= 2 * n) { "salida corta: ${salida.size} < ${2 * n}" }
        var i = 0
        var o = 0
        while (i < xyz.size) {
            val vx = xyz[i] - cx
            val vy = xyz[i + 1] - cy
            val vz = xyz[i + 2] - cz
            val u = vx * dx + vy * dy + vz * dz
            val v = vx * ax + vy * ay + vz * az
            if (campo <= 0.0) {
                salida[o] = medioAncho + u * zoom
                salida[o + 1] = medioAlto - v * zoom
            } else {
                val radio = kotlin.math.hypot(u, v)
                val hondo = (vx * fx + vy * fy + vz * fz) + ojo
                if (radio < 1e-9) {
                    salida[o] = medioAncho
                    salida[o + 1] =
                        if (hondo >= 0.0) medioAlto else medioAlto + focal * Math.PI
                } else {
                    val enPantalla = radioEnPantalla(radio, hondo)
                    salida[o] = medioAncho + enPantalla * u / radio
                    salida[o + 1] = medioAlto - enPantalla * v / radio
                }
            }
            i += 3
            o += 2
        }
    }

    /** La hondura de un punto a lo largo de la mirada — la moneda del orden pintor. */
    fun hondo(x: Double, y: Double, z: Double): Double =
        (x - cx) * fx + (y - cy) * fy + (z - cz) * fz
}

data class Postura(val giro: Double, val inclinacion: Double)

/**
 * Las seis vistas de siempre, una por cara del cubo.
 *
 * Viven con la cámara y no con el cubo que las pinta, porque las usan dos cosas: el cubo,
 * al tocarle una cara, y **el doble toque**, que encaja en la más cercana sin obligar a
 * apuntar a un cuadradito de setenta píxeles. Son las posturas en las que se mide y en las
 * que se dibuja en serio.
 *
 * Los ángulos salen de [Camara3D.adelante]: mirar una cara de frente es que la mirada sea
 * justo su normal cambiada de signo. La planta y su contraria se quedan con el giro en cero
 * porque desde el cenit el giro solo decide hacia dónde cae el norte, y cero es el norte.
 */
enum class CaraDelCubo(
    val normal: Pt3,
    val nombre: String,
    val giro: Double,
    val inclinacion: Double
) {
    FRENTE(Pt3(0.0, -1.0, 0.0), "fre", 0.0, 0.0),
    DETRAS(Pt3(0.0, 1.0, 0.0), "pos", Math.PI, 0.0),
    DERECHA(Pt3(1.0, 0.0, 0.0), "der", -Math.PI / 2, 0.0),
    IZQUIERDA(Pt3(-1.0, 0.0, 0.0), "izq", Math.PI / 2, 0.0),
    ARRIBA(Pt3(0.0, 0.0, 1.0), "sup", 0.0, Camara3D.TOPE_DE_INCLINACION),
    ABAJO(Pt3(0.0, 0.0, -1.0), "inf", 0.0, -Camara3D.TOPE_DE_INCLINACION);

    /** La postura de cámara desde la que esta cara se ve de frente. */
    val postura: Postura get() = Postura(giro, inclinacion)

    /** Las cuatro esquinas de la cara, en el cubo de lado dos centrado en el origen. */
    fun esquinas(): List<Pt3> {
        // Dos direcciones perpendiculares a la normal: cualquier par vale para dibujarla.
        val otra = if (abs(normal.z) > 0.9) Pt3(1.0, 0.0, 0.0) else Pt3(0.0, 0.0, 1.0)
        val u = normalizado(producto(normal, otra))
        val v = normalizado(producto(normal, u))
        return listOf(
            mas(mas(normal, u), v),
            mas(menos(normal, u), v),
            menos(menos(normal, u), v),
            menos(mas(normal, u), v)
        )
    }

    companion object {
        /**
         * A qué vista se parece más una mirada: **la cara que se tiene casi de frente**.
         *
         * La que más se opone a la mirada, que es la que uno está viendo. Sale de una sola
         * cuenta y sin casos raros: mirando desde una esquina del cubo, las tres caras que
         * se ven empatan casi, y cualquiera de ellas es una respuesta buena —el que hace el
         * gesto está pidiendo «ponme recto», no una cara concreta—.
         */
        fun laMasCercanaA(mirada: Pt3): CaraDelCubo =
            entries.minByOrNull { escalar(mirada, it.normal) } ?: FRENTE
    }
}

// -------------------------------------------------------------------------
// Vectores. Cuatro cuentas que no merecen una biblioteca.
// -------------------------------------------------------------------------

fun escalar(a: Pt3, b: Pt3): Double = a.x * b.x + a.y * b.y + a.z * b.z

fun producto(a: Pt3, b: Pt3): Pt3 = Pt3(
    a.y * b.z - a.z * b.y,
    a.z * b.x - a.x * b.z,
    a.x * b.y - a.y * b.x
)

fun largo(a: Pt3): Double = kotlin.math.sqrt(escalar(a, a))

fun normalizado(a: Pt3): Pt3 {
    val l = largo(a)
    return if (l <= 1e-12) Pt3(0.0, 0.0, 0.0) else Pt3(a.x / l, a.y / l, a.z / l)
}

fun mas(a: Pt3, b: Pt3): Pt3 = Pt3(a.x + b.x, a.y + b.y, a.z + b.z)

fun menos(a: Pt3, b: Pt3): Pt3 = Pt3(a.x - b.x, a.y - b.y, a.z - b.z)

fun por(a: Pt3, k: Double): Pt3 = Pt3(a.x * k, a.y * k, a.z * k)
