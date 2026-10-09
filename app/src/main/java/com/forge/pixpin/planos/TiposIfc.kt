package com.forge.pixpin.planos

/**
 * El nombre en castellano de las clases IFC más comunes, para decir qué se tocó en el visor 3D.
 * Es la tabla de `tipo_legible` de `crates/pixpin-cad/src/modelo3d.rs` del PC, copiada tal cual
 * (47 clases); lo que no está sale sin el «Ifc» delante.
 */
object TiposIfc {
    private val NOMBRES: Map<String, String> = mapOf(
        "ifcwall" to "Muro",
        "ifccurtainwall" to "Muro cortina",
        "ifcslab" to "Losa",
        "ifcroof" to "Techo",
        "ifccolumn" to "Columna",
        "ifcbeam" to "Viga",
        "ifcmember" to "Elemento estructural",
        "ifcplate" to "Placa",
        "ifcdoor" to "Puerta",
        "ifcwindow" to "Ventana",
        "ifcstair" to "Escalera",
        "ifcstairflight" to "Tramo de escalera",
        "ifcramp" to "Rampa",
        "ifcrampflight" to "Tramo de rampa",
        "ifcrailing" to "Baranda",
        "ifccovering" to "Revestimiento",
        "ifcfooting" to "Zapata",
        "ifcpile" to "Pilote",
        "ifcfurnishingelement" to "Mobiliario",
        "ifcfurniture" to "Mobiliario",
        "ifcbuildingelementproxy" to "Elemento",
        "ifcspace" to "Espacio",
        "ifcopeningelement" to "Vano",
        "ifcreinforcingbar" to "Acero de refuerzo",
        "ifcreinforcingmesh" to "Malla de refuerzo",
        "ifcpipesegment" to "Tuberia",
        "ifcpipefitting" to "Accesorio de tuberia",
        "ifcductsegment" to "Ducto",
        "ifcductfitting" to "Accesorio de ducto",
        "ifcflowterminal" to "Aparato",
        "ifcsanitaryterminal" to "Aparato",
        "ifccablecarriersegment" to "Bandeja de cables",
        "ifclightfixture" to "Luminaria",
        "ifcsite" to "Terreno",
        "ifcgeographicelement" to "Elemento del terreno",
        "ifcearthworksfill" to "Movimiento de tierras",
        "ifcearthworkscut" to "Movimiento de tierras",
        "ifcbearing" to "Apoyo",
        "ifctendon" to "Cable de postensado",
        "ifccourse" to "Capa",
        "ifcpavement" to "Pavimento",
        "ifcbridge" to "Puente",
        "ifcbridgepart" to "Puente",
        "ifcbuildingelementpart" to "Conjunto",
        "ifcelementassembly" to "Conjunto",
        "ifcchimney" to "Chimenea",
        "ifcshadingdevice" to "Parasol"
    )

    fun legible(tipo: String): String {
        val t = tipo.removeSuffix("StandardCase").removeSuffix("ElementedCase")
        return NOMBRES[t.lowercase()] ?: tipo.removePrefix("Ifc")
    }
}
