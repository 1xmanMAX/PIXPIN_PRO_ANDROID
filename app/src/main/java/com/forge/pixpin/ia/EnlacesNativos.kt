package com.forge.pixpin.ia

/**
 * **Los enlaces `pixpin://` del PC** (`pixpin-shell/orden.rs`, 15-oct-2026), compilados en
 * `libpixpinia.so`: `pixpin://<verbo>/<trozos>?campo=valor` (o `pixpinmax://`, o la seña de los
 * atajos `pixpin://atajo/…`) a los pedidos del protocolo, con su misma tabla de verbos y su misma
 * seguridad. Así un enlace hecho en el PC hace lo mismo en el teléfono.
 */
object EnlacesNativos {
    val disponible: Boolean get() = HojaNativa.disponible

    /** `{"pedidos": [{"pedido", "seguridad", "dice"}], "firmado"}`, `{"ayuda"}` o `{"error", "codigo"}`. */
    @JvmStatic external fun leer(url: String, clave: String): String

    /** Un enlace de la casa sin firmar (`pixpin://verbo?…`); [campos] en JSON. */
    @JvmStatic external fun armar(verbo: String, campos: String): String
}
