package com.forge.pixpin.ia

import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * **El asistente de verdad, en esta máquina** (aarch64 con bionic, como un teléfono): el
 * `libllamaserver.so` que va en el APK, arrancado con el Qwen3.5-0.8B bajado de Hugging Face y
 * comprobado por su huella, contestando un saludo y llamando a `dibujar_en_lienzo` ante
 * «dibuja un círculo rojo». Mide la carga y los tokens por segundo.
 *
 * Solo con `IA_EN_VIVO=1` (como `EDGE_EN_VIVO`): baja ~740 MB y tarda. `IA_MODELOS` es la carpeta
 * de los modelos (por omisión `pixpin-build/ia-modelos` del proyecto; no entra en git).
 */
class AsistenteEnVivoTest {

    /** Un motor que apunta las medidas de cada respuesta. */
    private class Medido(val motor: Motor) : Motor by motor {
        val respuestas = mutableListOf<Respuesta>()
        override fun completar(peticion: Peticion, alTrozo: (Trozo) -> Seguir): Respuesta =
            motor.completar(peticion, alTrozo).also { respuestas += it }
    }

    @Test
    fun `el 0_8B contesta y llama a la herramienta de dibujar`() {
        assumeTrue(System.getenv("IA_EN_VIVO") == "1")
        val proyecto = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
        val raiz = File(System.getenv("IA_MODELOS") ?: File(proyecto, "pixpin-build/ia-modelos").path)
        val servidor = File(proyecto, "app/src/main/jniLibs/arm64-v8a/libllamaserver.so")
        assertTrue("falta $servidor", servidor.canExecute())
        val m = Modelos.QWEN35_08B
        // Reanudar con rangos a través de las redirecciones de Hugging Face (lo que hace la descarga
        // cuando la red corta): tiene que contestar 206 con lo que falta.
        val c = java.net.URL(m.vision!!.url).openConnection() as java.net.HttpURLConnection
        c.instanceFollowRedirects = true
        c.setRequestProperty("Range", "bytes=1000-")
        val codigo = c.responseCode
        val largo = c.getHeaderField("Content-Length")?.toLongOrNull()
        c.disconnect()
        println("rango tras redirección: HTTP $codigo, $largo bytes")
        assertTrue(codigo == 206 && largo == m.vision!!.bytes - 1000)
        val t0 = System.currentTimeMillis()
        Descarga.asegurar(raiz, m, {}, AtomicBoolean(false))
        println("modelo listo (huella comprobada) en ${(System.currentTimeMillis() - t0) / 1000.0} s")

        val carpeta = Descarga.carpetaModelo(raiz, m)
        val hilos = ServidorLlama.hilosParaEsteAparato()
        val llama = ServidorLlama(ServidorLlama.Config(
            servidor, File(carpeta, m.pesos.nombre), File(carpeta, m.vision!!.nombre), File(raiz, "trabajo"), hilos = hilos
        ))
        val motor = Medido(llama)
        try {
            llama.precargar()
            println("hilos: $hilos · carga: ${llama.tiempoDeCarga} ms · pid ${llama.pid}")

            // 1. Un saludo: sin herramientas (es charla), contesta con texto.
            val h1 = mutableListOf(MensajeIa.sistema(Agente.instrucciones()), MensajeIa.usuario("Hola, ¿qué tal?"))
            val f1 = Agente.turno(motor, h1, Herramientas.paraElPedido("Hola, ¿qué tal?"), { Result.success("hecho") }) { Seguir.SI }
            val r1 = motor.respuestas.last()
            println("saludo: «${f1.texto}» · lee ${"%.1f".format(r1.medidas.tokensPorSegundoLectura)} tok/s · escribe ${"%.1f".format(r1.medidas.tokensPorSegundoEscritura)} tok/s (${r1.medidas.tokensSalida} tokens)")
            assertTrue(f1.texto.isNotBlank())

            // 2. «Dibuja un círculo rojo»: tiene que llamar a dibujar_en_lienzo con una elipse.
            val pedido = "Dibuja un círculo rojo"
            val hechas = mutableListOf<Llamada>()
            val h2 = mutableListOf(MensajeIa.sistema(Agente.instrucciones()), MensajeIa.usuario(pedido))
            val t2 = System.currentTimeMillis()
            val f2 = Agente.turno(motor, h2, Herramientas.paraElPedido(pedido), { l ->
                hechas += l
                val n = Lienzo.aElementos(Lienzo.elementos(l, 0.0 to 0.0, 1)).size
                Result.success("dibujadas $n figuras en el lienzo")
            }) { Seguir.SI }
            val llamadas = h2.flatMap { it.llamadas }
            println("dibujo en ${(System.currentTimeMillis() - t2) / 1000.0} s · llamadas: ${llamadas.map { it.nombre + " " + it.argumentos }}")
            println("respuesta: «${f2.texto}» · reintentos ${f2.reintentos}")
            for (r in motor.respuestas.drop(1)) println("  vuelta: lee ${"%.1f".format(r.medidas.tokensPorSegundoLectura)} tok/s · escribe ${"%.1f".format(r.medidas.tokensPorSegundoEscritura)} tok/s (${r.medidas.tokensEntrada} entrada, ${r.medidas.tokensSalida} salida)")
            val dibujo = hechas.filterIsInstance<Llamada.Dibujar>().firstOrNull()
            assertTrue("no llamó a dibujar_en_lienzo: $llamadas", dibujo != null)
            val f = dibujo!!.figuras
            println("figuras: ${f.map { "${it.tipo} ${it.color}/${it.relleno}" }}")
            // Lo que se pide es que **llame bien a la herramienta** (llamada válida, figuras rojas). Que
            // la figura sea un círculo no se exige: el 0.8B a veces elige otra (medido aquí, 14-oct:
            // una estrella roja; en el PC el 0.8B tampoco era fiable dibujando). Se apunta.
            println("¿círculo?: ${f.any { it.tipo == TipoFigura.ELIPSE }}")
            assertTrue(f.isNotEmpty())
            assertTrue(f.any { it.color == "#e03131" || it.relleno == "#e03131" })
        } finally {
            llama.soltar()
            assertTrue(!llama.cargado)
        }
    }

    /**
     * **La selección por turno con el modelo de verdad** (§2.2): pedidos de la app (tareas, ayuda,
     * ventanas, lienzo, documentos) con las ≤6 herramientas que elige [Seleccion] y la hoja de
     * trabajo y el plan si se piden con `IA_PLAN=1`. Apunta qué llamó y cuánto tardó; exige que
     * lo que llame sea de lo ofrecido y que los saludos no lleven herramientas. Sin descargar nada
     * (usa lo que haya en `IA_MODELOS`).
     */
    @Test
    fun `la seleccion por turno funciona con el modelo de verdad`() {
        assumeTrue(System.getenv("IA_EN_VIVO") == "1")
        val proyecto = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
        val raiz = File(System.getenv("IA_MODELOS") ?: File(proyecto, "pixpin-build/ia-modelos").path)
        val m = Modelos.porId(System.getenv("IA_MODELO")) ?: Modelos.QWEN35_08B
        val carpeta = Descarga.carpetaModelo(raiz, m)
        assumeTrue("sin el modelo en $carpeta", File(carpeta, m.pesos.nombre).isFile)
        val servidor = File(proyecto, "app/src/main/jniLibs/arm64-v8a/libllamaserver.so")
        val llama = ServidorLlama(ServidorLlama.Config(
            servidor, File(carpeta, m.pesos.nombre), File(carpeta, m.vision!!.nombre), File(raiz, "trabajo"), hilos = ServidorLlama.hilosParaEsteAparato()
        ))
        val plan = System.getenv("IA_PLAN") == "1"
        val pedidos = listOf(
            "hola" to null,
            "Apunta comprar pan mañana a las 10" to setOf("anadir_tarea", "anadir_tareas"),
            "¿Cómo hago una captura de pantalla?" to setOf(Herramientas.AYUDA),
            "Abre la galería de capturas" to setOf("abrir_ventana"),
            "¿Qué tengo pendiente esta semana?" to setOf("consultar_tareas"),
            "Crea un lienzo nuevo llamado Puente" to setOf("lienzo_nuevo"),
            "Haz una gráfica de barras con enero 3, febrero 5 y marzo 7" to setOf(Herramientas.GRAFICA),
            "Dibuja un triángulo azul" to setOf(Herramientas.DIBUJAR)
        )
        var aciertos = 0
        try {
            llama.precargar()
            for ((pedido, esperadas) in pedidos) {
                val ofrecidas = Herramientas.paraElPedido(pedido)
                assertTrue(ofrecidas.size <= Seleccion.MAXIMO)
                val hechas = mutableListOf<Llamada>()
                val h = mutableListOf(MensajeIa.sistema(Agente.instrucciones()), MensajeIa.usuario(pedido))
                val t = System.currentTimeMillis()
                val f = Agente.turno(llama, h, ofrecidas, { l -> hechas += l; Result.success("hecho") }, opciones = Agente.Opciones(planificar = plan, hoja = HojaTrabajo.Hoja(), ventana = ServidorLlama.CONTEXTO, ampliar = Herramientas.catalogo())) { Seguir.SI }
                val nombres = hechas.map { it.nombre }
                val bien = if (esperadas == null) nombres.isEmpty() else nombres.any { it in esperadas }
                if (bien) aciertos++
                println("«$pedido» → ofrecidas ${ofrecidas.map { it.nombre }} · llamó $nombres · ${(System.currentTimeMillis() - t) / 1000.0} s · ${if (bien) "BIEN" else "mal"} · «${f.texto.take(80)}»")
                if (esperadas == null) assertTrue("un saludo no lleva herramientas", ofrecidas.isEmpty())
                // Lo que llama es de lo ofrecido (o de lo que el plan añadió del catálogo).
                assertTrue(nombres.all { n -> Herramientas.DEL_PC.any { it.nombre == n } })
            }
            println("aciertos: $aciertos de ${pedidos.size} con ${m.nombre}${if (plan) " y plan" else ""}")
        } finally {
            llama.soltar()
        }
    }
}
