package com.forge.pixpin.cuenta

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * **Lo que manda el servidor, comprobado** (7-nov-2026). El catálogo de funciones, la licencia y la versión vienen
 * firmados con la clave privada del servidor (ECDSA P-256, SHA-256), que solo está allí; aquí va la pública
 * (`BuildConfig.FIRMA_PUBLICA`). Si alguien cambia una coma por el camino o se inventa una licencia, la firma no
 * cuadra y se descarta: es lo que hace que no se puedan «activar todas las funciones» desde fuera.
 *
 * Un documento firmado: `{"datos": "<JSON en base64url>", "firma": "<DER en base64url>", "clave": "<id>"}`; se firman
 * los bytes del texto de `datos` (ver `servidor/pindesk/src/firma.mjs`).
 */
object Firmado {
    /** El objeto de dentro si la firma es buena con [publicaB64] (SPKI en base64); si no, null. */
    fun comprobar(doc: JsonObject?, publicaB64: String): JsonObject? = runCatching {
        if (doc == null || publicaB64.isBlank()) return null
        val datos = doc["datos"]!!.jsonPrimitive.content
        val firma = Base64.getUrlDecoder().decode(doc["firma"]!!.jsonPrimitive.content)
        val clave = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicaB64.trim())))
        val v = Signature.getInstance("SHA256withECDSA").apply { initVerify(clave); update(datos.toByteArray(Charsets.US_ASCII)) }
        if (!v.verify(firma)) return null
        Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(datos), Charsets.UTF_8)) as JsonObject
    }.getOrNull()

    fun comprobar(texto: String?, publicaB64: String): JsonObject? =
        comprobar(texto?.let { runCatching { Json.parseToJsonElement(it) as JsonObject }.getOrNull() }, publicaB64)
}
