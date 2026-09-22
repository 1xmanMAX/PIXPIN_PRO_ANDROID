package com.forge.pixpin.motor

/**
 * **Leer con la página que sube sola** (22-sep-2026, pedido por el usuario: «un scroll que
 * determine yo la velocidad, y que me muestre en cuánto terminaré de leer con esa velocidad»).
 *
 * La velocidad no va en píxeles sino **en palabras por minuto**, que es como se mide leer: así no
 * cambia al agrandar la letra (el documento se alarga, pero las palabras son las mismas) y el
 * «terminas en» sale directo. Los píxeles por segundo se sacan de cuántas palabras tiene el
 * documento y cuánto mide. Aquí van las cuentas, sin Android; el bucle está en el visor.
 */
object AutoDesplazar {

    /** De leer despacio a hojear; el paso de los botones − y +. */
    const val MINIMO = 80
    const val MAXIMO = 700
    const val PASO = 20
    /** Lo que lee de media un adulto en silencio. */
    const val POR_DEFECTO = 230

    fun valida(ppm: Int): Int = ppm.coerceIn(MINIMO, MAXIMO)

    /** Un paso más rápido (+1) o más lento (−1), redondeando al paso para no quedar en 237. */
    fun otra(ppm: Int, sentido: Int): Int {
        val abajo = ppm / PASO * PASO
        return valida(if (sentido < 0 && abajo != ppm) abajo else abajo + sentido * PASO)
    }

    /**
     * **Cuántos píxeles por segundo** hay que subir para leer [ppm] palabras por minuto en un
     * documento de [palabras] palabras que mide [alto] píxeles. Cero si no hay nada que leer.
     */
    fun pixelesPorSegundo(ppm: Int, palabras: Int, alto: Float): Float =
        if (palabras <= 0 || alto <= 0f) 0f else alto / palabras * ppm / 60f

    /**
     * **Los minutos que quedan** a [ppm], con [progreso] de 0 a 1 (ver [Lectura.progreso]). Lo que
     * ya se ve en pantalla al final cuenta como aún por leer, así que no se da por acabado antes.
     */
    fun minutosQueQuedan(palabras: Int, progreso: Float, ppm: Int): Float =
        if (palabras <= 0 || ppm <= 0) 0f else palabras * (1f - progreso.coerceIn(0f, 1f)) / ppm

    /** «menos de 1 min», «12 min», «1 h 05 min». */
    fun rotulo(minutos: Float): String {
        val m = kotlin.math.ceil(minutos.toDouble()).toInt()
        return when {
            minutos <= 0f -> "terminado"
            m <= 1 && minutos < 1f -> "menos de 1 min"
            m < 60 -> "$m min"
            else -> "${m / 60} h " + (m % 60).toString().padStart(2, '0') + " min"
        }
    }

    /**
     * **La hora a la que se acaba**, «18:40», contando desde [ahoraEnMinutos] (minutos desde las
     * 00:00 de hoy). Si pasa de medianoche se da la vuelta.
     */
    fun horaDeTerminar(ahoraEnMinutos: Int, minutos: Float): String {
        val fin = (ahoraEnMinutos + kotlin.math.ceil(minutos.toDouble()).toInt()).mod(24 * 60)
        return "${fin / 60}:" + (fin % 60).toString().padStart(2, '0')
    }

    /** Cuenta las palabras del documento (el `innerText` del cuerpo). En ES5. */
    const val CONTAR = """(function(){
  var t=(document.body&&document.body.innerText)||'';
  var m=t.match(/[^\s]+/g);
  return m?m.length:0;
})()"""
}
