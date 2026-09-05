package com.forge.pixpin.croquis3d

import android.content.Context
import androidx.compose.ui.unit.Density
import com.forge.pixpin.data.ProyectosRepository
import com.forge.pixpin.motor.Element
import com.forge.pixpin.motor.ElementType
import com.forge.pixpin.motor.ExcalidrawStore
import com.forge.pixpin.motor.anchoPintadoDelLapiz
import com.forge.pixpin.motor.Hoja
import com.forge.pixpin.motor.Pt
import com.forge.pixpin.motor.Pt3
import com.forge.pixpin.motor.Scene
import com.forge.pixpin.motor.randomId
import java.io.File
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * **Una vista congelada, convertida en lámina del proyecto. En vectores.**
 *
 * ## Se proyecta, no se fotografía
 *
 * Lo primero que se hizo aquí fue pintar la vista en un mapa de bits y meter la foto en el
 * proyecto. Funciona y está mal: una lámina de un proyecto acaba en un PDF, y una foto en un
 * PDF es una foto —se pixela al ampliar, pesa lo que pesa una imagen y no se puede tocar
 * nada de lo que hay dentro—. Lo que se entrega de un croquis no puede ser un pantallazo.
 *
 * Así que lo que se guarda es **el dibujo proyectado**: cada trazo del espacio pasa por la
 * cámara de la vista y sale como un trazo del plano, con sus puntos, su color, su grosor y
 * su transparencia. Es exactamente lo que hace un dibujante al pasar a limpio un croquis
 * desde un punto de vista, y el resultado es una lámina de verdad: se amplía sin límite, se
 * exporta como vectores, pesa cuatro veces menos y **se puede seguir dibujando encima**.
 *
 * ## Y la lámina sabe volver
 *
 * La hoja guarda de qué croquis y de qué vista salió ([Hoja.croquis], [Hoja.vista]), así que
 * tocarla no abre una imagen: abre **el croquis en el espacio, puesto en esa vista**. La
 * lámina es lo que se entrega; el croquis es donde se trabaja, y desde la lámina se vuelve.
 */
object HojaDeLaVista {

    /**
     * Proyecta la vista, la guarda como lámina y la mete en el proyecto.
     *
     * Devuelve false sin ruido si algo no estaba: es lo que pasa cuando el proyecto se ha
     * borrado desde otra pantalla mientras se croquizaba, y no es un fallo del que avisar.
     */
    fun aniadir(
        context: Context,
        proyectos: ProyectosRepository,
        proyectoId: String,
        /** De qué croquis en el espacio es esta vista: es lo que le pone el color. */
        idDelCroquis: String,
        croquis: Croquis,
        vista: Vista3D,
        anchoDeLaVista: Double,
        altoDeLaVista: Double,
        densidad: Density,
        ahora: Long = System.currentTimeMillis()
    ): Boolean = runCatching {
        val proyecto = proyectos.porId(proyectoId) ?: return false
        if (anchoDeLaVista < 1 || altoDeLaVista < 1) return false

        // **La lámina tiene la proporción de lo que se congeló**, no la de la pantalla: con
        // una zona marcada, lo que se entrega es esa zona y a esa escala.
        val recorte = vista.recorte?.takeIf { it.sirve }
        val proporcion = (altoDeLaVista * (recorte?.alto ?: 1.0)) /
            (anchoDeLaVista * (recorte?.ancho ?: 1.0))
        val alto = ANCHO_DE_LA_LAMINA * proporcion
        val escala = ANCHO_DE_LA_LAMINA / (anchoDeLaVista * (recorte?.ancho ?: 1.0))
        // **Las imágenes se llevan su archivo.** Una imagen del plano es un elemento que
        // señala a un archivo de la escena; sin registrarlo, la lámina traía el hueco donde
        // iba la foto y no la foto. Se copia al almacén de la escena, que es donde la
        // buscarán después la miniatura y el PDF.
        val archivos = mutableMapOf<String, com.forge.pixpin.motor.SceneFile>()
        val dibujado = proyectada(
            croquis, vista, escala, alto, densidad.density.toDouble(),
            anchoDeLaVista, altoDeLaVista
        ) { ruta ->
            val origen = File(ruta)
            if (!origen.exists()) null
            else ExcalidrawStore.guardarImagen(context, origen, mimeDe(ruta))?.also {
                archivos[it.id] = it
            }?.id
        }
        if (dibujado.isEmpty()) return false

        // El marco es **la vista**, no lo que ocupe el dibujo: lo que se congeló fue un
        // encuadre, y la lámina tiene que enseñar ese encuadre aunque el trazo se salga o
        // aunque quede aire de sobra a un lado.
        val marco = Element(
            id = randomId(),
            type = ElementType.FRAME,
            x = 0.0, y = 0.0,
            width = ANCHO_DE_LA_LAMINA, height = alto,
            seed = 1
        )
        val dibujo = "croquis3d-${vista.id}"
        ExcalidrawStore.guardar(
            context,
            dibujo,
            Scene(elements = dibujado + marco, files = archivos)
        ) ?: return false

        proyectos.conHoja(
            proyecto,
            Hoja(
                id = "v-${vista.id}",
                nombre = "Vista ${vista.nombre}",
                dibujo = dibujo,
                marco = marco.id,
                croquis = idDelCroquis,
                vista = vista.id
            ),
            ahora
        )
        true
    }.getOrDefault(false)

    /**
     * **El croquis pasado a limpio desde una vista**: sus trazos, ya en el plano.
     *
     * Cada trazo del espacio se proyecta punto a punto con la cámara de la vista y se guarda
     * como un trazo a mano alzada del motor plano. Lo que se conserva es lo que un plano
     * puede decir: por dónde pasa, de qué color, de qué grueso, lo que tapa y el pulso de la
     * mano. Lo que no —que si era un listón o una luz, el resplandor, la arista— se queda en
     * el croquis, que es donde significa algo: en una lámina eso es tinta y punto.
     *
     * **Lo escondido no sale, y la hoja de dibujo tampoco**: una es la mesa y lo otro es lo
     * que uno ha decidido no ver.
     */
    fun proyectada(
        croquis: Croquis,
        vista: Vista3D,
        escala: Double,
        alto: Double,
        densidad: Double,
        anchoDeLaVista: Double,
        altoDeLaVista: Double,
        /** Copia una imagen al almacén de la escena y devuelve con qué nombre quedó. */
        registrar: (String) -> String? = { null }
    ): List<Element> {
        // La cámara ya puesta en la zona que se congeló, y luego a la escala de la lámina:
        // un recorte no se aplica cortando después, se aplica mirando ahí. Ver
        // [camaraEncuadrada].
        // Del encuadre se toma **a dónde mira**; el aumento se calcula aparte, porque la
        // escala ya lleva dentro lo estrecha que sea la zona: el ancho de la lámina entre lo
        // que mide esa zona en el mundo.
        val encuadrada = camaraEncuadrada(vista, anchoDeLaVista, altoDeLaVista)
        val camara = encuadrada.copy(zoom = vista.camara.zoom * escala)

        // **Lo de debajo va antes**, porque en una lámina el orden es el que manda: no hay
        // hondura que consultar, hay una lista y se pinta en fila. El reparto es el mismo
        // que en el lienzo: el espacio, lo que tira al suelo, lo que se subraya y la tinta.
        val elFondo = mutableListOf<Element>()
        val lasSombras = mutableListOf<Element>()
        val loSubrayado = mutableListOf<Element>()
        val laTinta = mutableListOf<Element>()

        /** Si algo cae del todo fuera de la lámina: entonces no se guarda. */
        fun seVe(c: Caja): Boolean =
            c.x + c.ancho >= 0 && c.y + c.alto >= 0 && c.x <= ANCHO_DE_LA_LAMINA && c.y <= alto

        // **El fondo del croquis, primero de todo.**
        //
        // Faltaba, y es lo que dejaba las láminas ilegibles: un croquis sobre pizarra se
        // dibuja con tinta clara, y la lámina salía con esa tinta clara sobre el blanco del
        // papel — o sea, en blanco. Y con algo subrayado encima era peor todavía: la banda
        // del resaltador sin nada debajo es una mancha sobre nada.
        //
        // Es un rectángulo del tamaño del marco y no un color de hoja porque una lámina se
        // exporta, se imprime y se dibuja encima: un rectángulo se ve en todas partes y se
        // puede quitar de un toque si alguien lo quiere en blanco.
        croquis.colorDelFondo?.let { color ->
            elFondo += Element(
                id = randomId(),
                type = ElementType.RECTANGLE,
                x = 0.0, y = 0.0,
                width = ANCHO_DE_LA_LAMINA, height = alto,
                strokeColor = color,
                backgroundColor = color,
                fillStyle = com.forge.pixpin.motor.FillStyle.SOLID,
                strokeWidth = 0.5,
                roughness = 0,
                seed = 1
            )
        }

        // **Las sombras, entre el suelo y el dibujo.**
        //
        // Van porque **se ven**: son la mitad de lo que hace que un croquis en el espacio se
        // lea como un volumen y no como un alambre, y una lámina que no las trae enseña otra
        // cosa que la que se congeló. Se proyectan igual que en el lienzo —cada punto,
        // aplastado contra el suelo por el rayo, y de ahí a la cámara—, así que salen con la
        // misma geometría: no son un borrón debajo de la figura, son la figura tumbada.
        //
        // La luna primero: su sombra es la floja, y si las dos alumbran manda la del sol.
        for ((astro, tinta, cuanto) in listOfNotNull(
            croquis.luna?.let { Triple(it, COLOR_DE_LA_SOMBRA_DE_LA_LUNA, ALFA_DE_LA_LUNA) },
            croquis.sol?.let { Triple(it, COLOR_DE_LA_SOMBRA_DEL_SOL, ALFA_DEL_SOL) }
        )) {
            for (trazo in croquis.trazos) {
                if (trazo.oculto || trazo.puntos.size < 2) continue
                val tumbados = trazo.puntos.mapNotNull { astro.sombraDe(it) }
                if (tumbados.size < 2) continue
                val en = tumbados.map { camara.aPantalla(it, ANCHO_DE_LA_LAMINA, alto) }
                val caja = caja(en) ?: continue
                if (!seVe(caja)) continue
                lasSombras += Element(
                    id = randomId(),
                    type = ElementType.FREEDRAW,
                    x = caja.x, y = caja.y,
                    width = caja.ancho, height = caja.alto,
                    points = en.map { Pt(it.x - caja.x, it.y - caja.y) },
                    strokeColor = tinta,
                    strokeWidth = grosorEnLaLamina(trazo, camara.zoom, densidad),
                    opacity = cuanto,
                    seed = 1
                )
            }
        }

        for (trazo in croquis.trazos) {
            if (trazo.oculto || trazo.puntos.size < 2) continue
            val en = trazo.puntos.map { camara.aPantalla(it, ANCHO_DE_LA_LAMINA, alto) }
            val caja = caja(en) ?: continue
            // **Lo que cae del todo fuera del encuadre no se guarda.** El marco enseña la
            // zona que se congeló, pero los trazos de fuera seguían estando ahí: una lámina
            // de un rincón del croquis se llevaba el croquis entero escondido detrás del
            // marco, y eso pesa, tarda en abrirse y aparece en cuanto alguien mueve el marco.
            if (!seVe(caja)) continue
            // **El rodillo se pinta con lo que tape su tinta**, que es lo que le pone la
            // punta al pintar y no lo que trae el trazo. Proyectándolo sin eso, una lámina
            // con una mano de color dada salía con otra transparencia que el croquis.
            val esResaltador = trazo.pincel.vigente == Pincel.RESALTADOR
            val tapa = if (esResaltador) trazo.opacidad * ALFA_DEL_RESALTADOR else trazo.opacidad
            val elemento = Element(
                id = randomId(),
                type = ElementType.FREEDRAW,
                x = caja.x, y = caja.y,
                width = caja.ancho, height = caja.alto,
                // Los puntos van **relativos a la esquina de su caja**, que es como los
                // guarda el motor plano: así mover el trazo es mover un número, no mil.
                points = en.map { Pt(it.x - caja.x, it.y - caja.y) },
                // Un subrayado no lleva pulso: sale del ancho que sale, como el de verdad.
                // Y firme, o el generador se lo inventaría a partir de la velocidad y la
                // banda saldría afilada por las puntas.
                pressures = if (esResaltador) null else trazo.presiones,
                presionFirme = esResaltador,
                strokeColor = trazo.color,
                strokeWidth = grosorEnLaLamina(trazo, camara.zoom, densidad),
                opacity = (tapa * 100).toInt().coerceIn(5, 100),
                seed = 1
            )
            // Y **debajo de la tinta**, que es donde va un resaltador: se subraya lo que ya
            // está escrito. Ordenados entre ellos por el orden en que se pasaron.
            if (esResaltador) loSubrayado += elemento else laTinta += elemento
        }

        for (imagen in croquis.imagenes) {
            if (imagen.oculto || imagen.esquinas.size < 4) continue
            val en = imagen.esquinas.map { camara.aPantalla(it, ANCHO_DE_LA_LAMINA, alto) }
            val caja = caja(en) ?: continue
            if (!seVe(caja)) continue
            // Una imagen del plano es un rectángulo con su giro, así que de la proyección se
            // toma eso: dónde cae, lo que ocupa y cuánto se ha ladeado su lado de arriba. El
            // escorzo se pierde —un rectángulo no puede tener cuatro esquinas cualesquiera—
            // y es la única cosa de todo esto que no pasa entera al plano.
            val archivo = registrar(imagen.ruta) ?: continue
            laTinta += Element(
                id = randomId(),
                type = ElementType.IMAGE,
                x = caja.x, y = caja.y,
                width = caja.ancho, height = caja.alto,
                angle = atan2(en[1].y - en[0].y, en[1].x - en[0].x),
                fileId = archivo,
                opacity = (imagen.opacidad * 100).toInt().coerceIn(5, 100),
                seed = 1
            )
        }
        // Un fondo solo no es una lámina: si no se ha proyectado nada, no hay nada que
        // guardar y quien llama se entera por la lista vacía.
        if (lasSombras.isEmpty() && loSubrayado.isEmpty() && laTinta.isEmpty()) return emptyList()
        return elFondo + lasSombras + loSubrayado + laTinta
    }

    /** Lo que tapa un resaltador, en tanto por uno. Ver [Croquis3DLienzo]. */
    /** Lo que tapa un rodillo: todo. Ver [Croquis3DLienzo]. */
    private const val ALFA_DEL_RESALTADOR = 1.0

    /** De qué color y cuánto tapan las dos sombras. Los mismos que en el lienzo. */
    private const val COLOR_DE_LA_SOMBRA_DEL_SOL = "#000000"
    private const val ALFA_DEL_SOL = 33
    private const val COLOR_DE_LA_SOMBRA_DE_LA_LUNA = "#2B3A67"
    private const val ALFA_DE_LA_LUNA = 20

    /**
     * Lo gordo que sale un trazo en la lámina.
     *
     * En la pantalla, un trazo mide lo que mide en el mundo por el aumento, y eso en puntos
     * de pantalla. La lámina es lo mismo a otra escala: se proyecta con el aumento de la
     * lámina y el grueso va con él, así que un trazo que en pantalla se veía el doble de
     * gordo que otro **sigue viéndose el doble** de gordo en el PDF.
     */
    private fun grosorEnLaLamina(trazo: Trazo3D, zoom: Double, densidad: Double): Double {
        // Lo que mide en el mundo, al aumento de la lámina: un trazo que en el croquis se
        // veía el doble de gordo que otro sigue viéndose el doble aquí, y uno que estaba
        // lejos sigue saliendo fino.
        val enPantalla =
            (trazo.calibre?.let { it * zoom } ?: trazo.grosor) * densidad * trazo.punta.ancho
        // **Y dividido por lo que el motor plano engorda un trazo a mano alzada, que no es
        // el factor que parece.**
        //
        // Un trazo a mano del plano no se pinta del grueso que dice: se pinta con una punta
        // de cuatro veces y cuarto ese número. Pero eso es **el tamaño de la punta, no lo
        // ancho que sale**: encima va la presión, que ensancha el trazo por su cuenta —a la
        // presión típica lo deja en algo más de seis veces el número, y con la presión que
        // manda un dedo, que en muchos aparatos es siempre uno, en el doble—. Dividiendo por
        // cuatro y cuarto, la lámina salía **con las líneas la mitad más gordas** de lo que
        // se veía en el croquis, y eso es justo lo que se notaba al mirar la hoja en el
        // proyecto.
        //
        // Se le pregunta al propio generador con la presión que lleva este trazo, que es lo
        // mismo que hace la muestra del panel: si algún día se toca [FreedrawTuning], esto
        // va detrás solo. Ver [anchoPintadoDelLapiz].
        val firme = trazo.pincel.vigente == Pincel.RESALTADOR
        val presion = trazo.presiones?.takeIf { it.isNotEmpty() }?.average() ?: 0.5
        val loQueEngorda = anchoPintadoDelLapiz(1.0, presion, firme)
        if (loQueEngorda <= 1e-6) return 0.1
        return (enPantalla / loQueEngorda).coerceIn(0.1, 100.0)
    }

    /** De qué tipo es una imagen, por cómo acaba su nombre. */
    private fun mimeDe(ruta: String): String =
        if (ruta.endsWith(".jpg", true) || ruta.endsWith(".jpeg", true)) "image/jpeg"
        else "image/png"

    /** La caja que ocupa una ristra de puntos, o nada si no ocupa nada. */
    private fun caja(en: List<Pt>): Caja? {
        if (en.isEmpty()) return null
        val x = en.minOf { it.x }
        val y = en.minOf { it.y }
        val ancho = en.maxOf { it.x } - x
        val alto = en.maxOf { it.y } - y
        if (hypot(ancho, alto) < 1e-6) return null
        return Caja(x, y, ancho, alto)
    }

    private class Caja(val x: Double, val y: Double, val ancho: Double, val alto: Double)

    /**
     * Lo ancha que sale una lámina, en unidades del plano.
     *
     * Es una escala, no una resolución: lo que se guarda son vectores, así que esto solo
     * decide con qué números se escriben. Mil seiscientos deja el trazo más fino por encima
     * de la unidad y el más gordo por debajo del ancho de la hoja, que es donde las cuentas
     * se leen bien si algún día hay que mirarlas.
     */
    private const val ANCHO_DE_LA_LAMINA = 1600.0
}
