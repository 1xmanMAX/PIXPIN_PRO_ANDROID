package com.forge.pixpin.audio

/**
 * **Por dónde sale el sonido: la cuenta, sin Android** (27-oct-2026, lo pidió el usuario: «que la
 * música, los audios, todo se pueda reproducir por el altavoz del oído, el que se usa para
 * llamadas, además del altavoz normal y los audífonos»).
 *
 * Aquí solo se decide; quien lo aplica es [SalidaDeAudio]. Va aparte para poder comprobarlo en
 * el ordenador: una regla mal puesta es la que deja la música sonando por el altavoz en el metro
 * o, peor, muda en el auricular con los audífonos puestos.
 *
 * Las tres reglas, en este orden:
 * 1. **Los audífonos mandan.** Con unos puestos (de cable, Bluetooth o USB) el sonido va por
 *    ellos, se haya elegido lo que se haya elegido: es lo que espera cualquiera al enchufarlos.
 * 2. **El teléfono en la oreja** (el sensor de cercanía, como en Telegram y WhatsApp) pasa al
 *    auricular aunque se hubiera elegido el altavoz.
 * 3. Si no, **lo elegido** con el botón: altavoz o auricular.
 */
object DecisionDeSalida {

    /** Lo que elige el usuario con el botón. Se guarda en el aparato; no viaja. */
    enum class Preferida { ALTAVOZ, OIDO }

    /** Por dónde va a sonar de verdad. */
    enum class Ruta { ALTAVOZ, OIDO, AURICULARES }

    /** La ruta según las tres reglas de arriba. */
    fun decidir(preferida: Preferida, auriculares: Boolean, cerca: Boolean): Ruta = when {
        auriculares -> Ruta.AURICULARES
        preferida == Preferida.OIDO || cerca -> Ruta.OIDO
        else -> Ruta.ALTAVOZ
    }

    /**
     * Si un reproductor preparado [alOido] (o no) tiene que rehacerse para [ruta]. Solo cuenta el
     * auricular: altavoz y audífonos son el mismo uso de «medios» y el sistema ya los cambia solo.
     */
    fun hayQueRehacer(alOido: Boolean, ruta: Ruta): Boolean = alOido != (ruta == Ruta.OIDO)

    /**
     * **Al bajar el teléfono de la oreja se pausa**, como en Telegram: si fue la cercanía la que
     * lo pasó al auricular y el usuario tenía elegido el altavoz, seguir sonando a todo volumen
     * por el altavoz al apartar la cara sería un susto. Con el auricular elegido no cambia nada.
     */
    fun pausarAlAlejar(preferida: Preferida, cercaAntes: Boolean, cercaAhora: Boolean, auriculares: Boolean): Boolean =
        cercaAntes && !cercaAhora && !auriculares && preferida == Preferida.ALTAVOZ

    /**
     * Si merece la pena escuchar el sensor de cercanía: hay algo sonando que sabe cambiar de
     * salida a media canción y no hay audífonos (con ellos la oreja no cambia nada).
     */
    fun escucharLaCercania(hayQuienCambie: Boolean, auriculares: Boolean): Boolean = hayQuienCambie && !auriculares

    /**
     * Si el sensor dice «cerca». Los sensores dan centímetros, y muchos solo dos valores: 0 o su
     * máximo. Cerca es por debajo de su máximo y de 5 cm, como la documentación de Android.
     */
    fun estaCerca(distancia: Float, maximo: Float): Boolean = distancia < minOf(maximo, 5f)

    /** La preferida al revés: lo que hace el botón. */
    fun otra(p: Preferida): Preferida = if (p == Preferida.OIDO) Preferida.ALTAVOZ else Preferida.OIDO
}
