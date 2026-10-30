package com.forge.pixpin.planes

/**
 * **Los planes de PixPin: el andamio para cobrar algún día** (30-oct-2026). Hoy la app es gratis y
 * **todo está abierto**: [Planes.tiene] dice que sí a cualquier [FuncionDePago] y ninguna función
 * existente pregunta aquí. Esto solo deja dicho dónde irá la puerta cuando lleguen las cuentas, los
 * servidores y lo de pago, para que no haya que repartir `if (premium)` por toda la app.
 *
 * Las reglas de Google Play que mandan cuando se encienda (léelas antes de tocar esto):
 *
 * - **Lo digital se cobra con Google Play Billing.** Una función, un plan o una suscripción que se
 *   usa dentro de una app distribuida por Play (sincronizar en la nube, guardar en nuestros
 *   servidores, más uso de la IA…) tiene que venderse con el sistema de facturación de Play
 *   ([Cobro], con la Play Billing Library). No vale un enlace a una web de pago ni un cobro propio,
 *   salvo **la facturación alternativa** donde Google la permite por programas regionales (p. ej.
 *   el Espacio Económico Europeo, Corea del Sur, India…), con su inscripción y sus condiciones.
 *   Lo físico o un servicio fuera de la app no entra.
 * - **Si hay cuentas, se borran desde la app y desde la web.** Una app que deja crear una cuenta
 *   tiene que ofrecer borrarla (con sus datos) **dentro de la app** y también **con un enlace web**
 *   que se declara en la ficha de Play, para quien ya desinstaló. Hoy no hay cuentas: lo que hay es
 *   «Borrar todos mis datos» del teléfono ([com.forge.pixpin.privacidad.PrivacidadYDatos]).
 * - La ficha de «Seguridad de los datos» y la política
 *   ([com.forge.pixpin.privacidad.Privacidad.URL_POLITICA]) se actualizan **antes** de publicar lo
 *   que mande datos a un servidor nuestro.
 *
 * Sin Android: se prueba en `PlanesTest`.
 */
enum class Plan {
    /** Lo de hoy: la app entera, gratis. */
    GRATIS,

    /** Un plan de pago futuro (nombre provisional). Nadie lo tiene todavía. */
    PRO
}

/**
 * **Lo que podría ser de pago** más adelante (candidatas, nada decidido). Lo que hoy hace la app en
 * el teléfono no está aquí y **no se cerrará**: esto es solo lo que costaría dinero dar (servidores,
 * almacenamiento, uso de IA pagado por nosotros).
 */
enum class FuncionDePago(val descripcion: String) {
    SINCRONIZAR_EN_LA_NUBE("Sincronizar entre aparatos por internet, no solo por la Wi-Fi de casa"),
    GUARDAR_EN_EL_SERVIDOR("Copia de tus chats, notas y grabaciones en nuestros servidores"),
    MAS_USO_DE_IA("IA sin traer tu propia clave, con un cupo mayor"),
    COMPARTIR_SIN_CADUCAR("Enlaces para compartir que no se borran a las horas"),
    TRANSCRIBIR_EN_EL_SERVIDOR("Pasar a texto grabaciones largas en el servidor")
}

/**
 * **El cobro**, para cuando exista: lo implementará Google Play Billing (la Play Billing Library,
 * en la variante `play`), o la facturación alternativa donde Google la permita. Hoy no hay ninguna
 * implementación y nada lo llama.
 */
interface Cobro {
    /** El plan que tiene el usuario ahora (de las compras que Play confirme, verificadas). */
    suspend fun planActual(): Plan

    /**
     * Abre la compra de [plan] con la interfaz de Play. Devuelve true si quedó comprado y
     * **reconocido** (`acknowledge`): Play devuelve el dinero de lo que no se reconoce en 3 días.
     */
    suspend fun comprar(plan: Plan): Boolean

    /** Recupera lo ya comprado (otro teléfono, reinstalar). */
    suspend fun restaurar(): Plan
}

/** **La puerta única**: si el usuario puede usar una [FuncionDePago]. */
object Planes {

    /** El plan de ahora. Mientras la app sea gratis, [Plan.GRATIS] y sin [Cobro]. */
    @Volatile var actual: Plan = Plan.GRATIS
        private set

    /** Lo que da cada plan. **Gratis lo da todo** mientras no se cobre nada. */
    private val INCLUYE: Map<Plan, Set<FuncionDePago>> = mapOf(
        Plan.GRATIS to FuncionDePago.entries.toSet(),
        Plan.PRO to FuncionDePago.entries.toSet()
    )

    /** Si se puede usar [funcion] con el plan [plan] (el de ahora, si no se dice). Hoy: siempre. */
    fun tiene(funcion: FuncionDePago, plan: Plan = actual): Boolean = funcion in INCLUYE[plan].orEmpty()
}
