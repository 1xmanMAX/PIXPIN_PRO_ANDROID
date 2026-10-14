package com.forge.pixpin.ia

/**
 * **Lo que puede salir mal** (el `ErrorIa` del PC). Los textos van en español porque acaban en
 * la burbuja; [paraElUsuario] los dice sin códigos ni JSON.
 */
sealed class ErrorIa(mensaje: String) : Exception(mensaje) {
    class Descarga(val que: String, val detalle: String) : ErrorIa("no se pudo bajar $que: $detalle")
    class HuellaDistinta(val que: String) :
        ErrorIa("el fichero $que esta danado (su huella no coincide); se borra y hay que bajarlo otra vez")
    class Falta(val que: String) : ErrorIa("falta $que: hay que bajar el asistente primero")
    class Arranque(detalle: String) : ErrorIa("no se pudo arrancar el modelo: $detalle")
    class Conexion(detalle: String) : ErrorIa("el modelo dejo de responder: $detalle")
    class Protocolo(val detalle: String) : ErrorIa("el modelo contesto algo que no se entiende: $detalle")
    object Cancelado : ErrorIa("cancelado") {
        private fun readResolve(): Any = Cancelado
    }

    /** El fallo en palabras del usuario (`agente::para_el_usuario`). Lo técnico va al registro. */
    fun paraElUsuario(): String = when (this) {
        is Falta -> "Falta bajar el asistente: abre el chat «Asistente» para bajarlo."
        is Arranque -> "No pude arrancar el asistente. Prueba otra vez en un momento."
        is Conexion -> "El asistente dejó de responder. Prueba otra vez."
        is Protocolo -> "Esta vez no me salió la respuesta. Prueba otra vez o pídelo más corto."
        Cancelado -> "Cancelado."
        is Descarga, is HuellaDistinta -> message.orEmpty()
    }
}
