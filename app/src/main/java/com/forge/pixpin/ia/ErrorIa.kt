package com.forge.pixpin.ia

/**
 * **Lo que puede salir mal** (el `ErrorIa` del PC). Los textos van en español porque acaban en
 * la burbuja; [paraElUsuario] los dice sin códigos ni JSON.
 */
sealed class ErrorIa(mensaje: String) : Exception(mensaje) {
    class Protocolo(val detalle: String) : ErrorIa("el modelo contesto algo que no se entiende: $detalle")
    /** Un fallo de la nube (clave, saldo, red, servidor), ya dicho para el usuario. */
    class Nube(val aviso: String) : ErrorIa(aviso)

    /** El fallo en palabras del usuario (`agente::para_el_usuario`). Lo técnico va al registro. */
    fun paraElUsuario(): String = when (this) {
        is Protocolo -> "Esta vez no me salió la respuesta. Prueba otra vez o pídelo más corto."
        is Nube -> aviso
    }
}
