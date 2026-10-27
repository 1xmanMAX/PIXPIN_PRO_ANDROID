package com.forge.pixpin.planos

import com.forge.pixpin.motor.DrawTheme
import com.forge.pixpin.motor.PapelDeLaHoja

/**
 * **La tinta de lo anotado sobre un plano, como la pinta el PC** (27-oct-2026, el usuario: «cuando
 * anoto un plano de AutoCAD en la computadora en modo noche, la tinta es blanca; al sincronizar
 * llega negra al teléfono, aunque el teléfono también está en modo noche»).
 *
 * **El color guardado es el que se eligió, en los dos aparatos**: ni el PC ni el móvil guardan un
 * color «de día» para luego invertirlo. Lo que cambia es cómo se pinta:
 *
 * - **El PC** (`plano_cad::anotar::papel`, `lector_tinta::pintar_capa_parte` y
 *   `dibujo::tema::con_papel`/`tinta`) pinta la capa contra **el papel del plano**: el claro
 *   (0,99; 0,988; 0,976) o el oscuro azulado (0,13; 0,15; 0,19), los mismos de
 *   [PintorDePlano.FONDO_CLARO] y [PintorDePlano.FONDO_OSCURO]. **Solo adapta si ese papel es de
 *   noche**; sobre el claro, cada tinta va tal cual.
 * - **El móvil** adaptaba contra el papel de la escena de la capa, y la capa del plano no trae
 *   papel (el PC no escribe `appState`): salía el blanco de serie, `#ffffff`. El blanco es un gris,
 *   y un gris sobre papel blanco se da la vuelta ([DrawTheme.adaptar]): **negro, de día y de
 *   noche**, encima del plano oscuro.
 *
 * Ahora el pintor de la capa recibe el papel del plano ([papel]) y adapta con la regla del PC
 * ([PapelDeLaHoja.tintaSoloDeNoche]). De noche, el blanco se queda blanco y la tinta oscura se
 * aclara igual que en el PC (la misma cuenta, contra el mismo papel); de día, todo tal cual.
 */
object TintaDelPlano {

    /** De 0..1 a 0..255 como el PC (`a8`: redondeando). */
    private fun a8(v: Float): Int = Math.round(v.coerceIn(0f, 1f) * 255f)

    private fun deFondo(f: FloatArray): Int = (0xFF shl 24) or (a8(f[0]) shl 16) or (a8(f[1]) shl 8) or a8(f[2])

    /** El papel del plano en el tema claro (`#fcfcf9`). */
    val PAPEL_CLARO: Int = deFondo(PintorDePlano.FONDO_CLARO)

    /** El del tema oscuro (`#212630`). */
    val PAPEL_OSCURO: Int = deFondo(PintorDePlano.FONDO_OSCURO)

    /** El papel que hay debajo de lo anotado con el tema [claro] del visor. */
    fun papel(claro: Boolean): Int = if (claro) PAPEL_CLARO else PAPEL_OSCURO

    /** La tinta [argb] tal como se ve sobre el plano con el tema [claro] (fuera de hojas). */
    fun comoSeVe(argb: Int, claro: Boolean): Int =
        PapelDeLaHoja.tintaSoloDeNoche(PapelDeLaHoja.Mapa.VACIO, null, argb, papel(claro))

    /** Si el papel del plano con este tema es de noche (de él depende que se adapte). */
    fun deNoche(claro: Boolean): Boolean = DrawTheme.esDeNoche(papel(claro))
}
