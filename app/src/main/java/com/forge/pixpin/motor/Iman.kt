package com.forge.pixpin.motor

/**
 * El motor del imán: **un solo sitio que decide a qué se pega el dedo**.
 *
 * Es la misma idea que el motor de dibujo. Antes cada herramienta traía su
 * lápiz, su borrador y su forma de pintar, y arreglar algo había que arreglarlo
 * tres veces; se juntó en un motor y desde entonces lo que se mejora se mejora
 * para todas. Con el imán pasaba exactamente lo mismo, un piso más abajo.
 *
 * ## Lo que había
 *
 * Seis sitios distintos llamaban al buscador de anclajes, y cada uno decidía por
 * su cuenta **tres cosas**: si engancha o no, con qué radio, y qué anclajes
 * cuentan. Escritas a mano, con condiciones como
 * `tool.isShape || tool.isLinear || tool.isFreehand || tool == Tool.PUNTO`.
 *
 * El resultado era el previsible: cada herramienta nueva había que acordarse de
 * añadirla a esa lista, y a alguna se le olvidaba. El punto nació sin imán al
 * arrastrarlo. El clavo nunca enganchó a los puntos tecleados. Y no había forma
 * de saber qué enganchaba con qué sin leerse los seis sitios — así que la única
 * manera de encontrar un olvido era tropezarse con él usando la app.
 *
 * ## Lo que hay ahora
 *
 * Una tabla. Se dice **qué se está haciendo** ([Faena]) y el motor responde. Una
 * herramienta nueva no tiene que tocar nada del imán: encaja en una de las
 * faenas y hereda su comportamiento entero.
 *
 * | Faena | Qué engancha |
 * |---|---|
 * | [Faena.TRAZANDO] | Todo lo configurado: es dibujar una figura nueva |
 * | [Faena.A_MANO] | Solo el canto, y solo de las guías |
 * | [Faena.AFINANDO] | Todo: se está corrigiendo una punta, no trazando |
 * | [Faena.MOVIENDO] | Todo, con más radio: se busca un sitio, no se traza |
 * | [Faena.SITIO_NOTABLE] | Solo lo que puede llevar nombre, y obliga |
 *
 * ## Y todo se puede apagar
 *
 * Cada clase de punto por separado, desde los ajustes. No es una preferencia de
 * adorno: un imán que tira cuando no quieres es peor que no tenerlo, y cuál
 * estorba depende de lo que estés dibujando. Ver [AjustesEnganche].
 */
object Iman {

    /**
     * Qué se está haciendo cuando se pide sitio.
     *
     * No es «qué herramienta hay puesta» a propósito. Lo que decide a qué debe
     * pegarse el dedo no es el botón que esté pulsado sino **qué está pasando**:
     * afinar la punta de una flecha ya trazada y afinar la de una recta son la
     * misma faena aunque sean dos herramientas, y trazar a mano alzada es otra
     * aunque el lápiz y el marcador sean dos botones.
     */
    enum class Faena {
        /** Naciendo una figura: se arrastra y va creciendo. */
        TRAZANDO,

        /**
         * A mano alzada.
         *
         * Solo el canto de las guías, y por un motivo concreto: un trazo que
         * salta a un vértice en mitad del recorrido no se corrige, **se rompe**
         * —el garabato pega un tirón y sigue—. El canto no da ese problema
         * porque no es un punto al que ir, es una superficie sobre la que
         * resbalar.
         */
        A_MANO,

        /** Recolocando un punto de algo ya dibujado, o un clavo. */
        AFINANDO,

        /** Arrastrando algo entero de un sitio a otro. */
        MOVIENDO,

        /**
         * Poniendo algo que **solo tiene sentido en un sitio notable**.
         *
         * Es la del punto etiquetado: o cae en una intersección, un extremo, un
         * medio o el centro de una circunferencia, o no cae. Ver
         * [sitioValidoParaPunto].
         */
        SITIO_NOTABLE
    }

    /**
     * A qué se pega el dedo, o null si nada tira de él.
     *
     * Es **la única puerta**. Quien quiera enganchar algo pregunta aquí y no
     * llama al buscador por su cuenta: es lo que hace que añadir una herramienta
     * no obligue a acordarse del imán.
     */
    fun sitio(
        scene: Scene,
        p: Pt,
        zoom: Double,
        faena: Faena,
        config: AjustesEnganche,
        excluir: String? = null,
        /** Elementos a mirar. Por defecto, lo visible con sus guías. */
        elementos: List<Element> = scene.visibleConReferencias
    ): Anclaje? {
        val ajustes = ajustesPara(faena, config)
        if (!ajustes.activo) return null

        val candidato = buscarAnclaje(
            elementos, p, zoom, ajustes, excluir,
            extra = extras(scene, p, zoom, faena, ajustes)
        ) ?: return null

        // La única faena que además filtra: un punto etiquetado no va donde
        // caiga, va donde hay algo que nombrar.
        if (faena == Faena.SITIO_NOTABLE && !sitioValidoParaPunto(candidato, elementos)) {
            return null
        }
        return candidato
    }

    /**
     * Los ajustes que tocan para esta faena, partiendo de los del usuario.
     *
     * **Lo que el usuario apaga se queda apagado**, pase lo que pase: la faena
     * puede quitar cosas pero nunca encender lo que se ha desactivado a mano.
     * Al revés sería un ajuste que no se obedece, que es peor que no tenerlo.
     */
    fun ajustesPara(faena: Faena, config: AjustesEnganche): AjustesEnganche = when (faena) {
        Faena.TRAZANDO, Faena.AFINANDO -> config

        Faena.A_MANO -> AjustesEnganche.SOLO_GUIAS.copy(
            activo = config.activo,
            bordeDeGuia = config.bordeDeGuia,
            radio = config.radio
        )

        // Moviendo se busca un sitio, no se traza: se puede ser más generoso con
        // el radio sin que estorbe, porque no hay un trazo en curso al que dar
        // un tirón.
        Faena.MOVIENDO -> config.copy(radio = config.radio * 1.5)

        // Aquí el dedo **tiene** que acertar en un sitio notable para que pase
        // algo, así que apretar el radio convertiría la herramienta en un juego
        // de puntería. Al no haber puntos libres, un radio grande no puede
        // colocar nada donde no debía.
        Faena.SITIO_NOTABLE -> config.copy(radio = RADIO_PARA_PUNTOS)
    }

    /**
     * Los anclajes que no salen de ninguna figura.
     *
     * Los puntos tecleados en una tabla y el eje de coordenadas. Alguien los
     * puso ahí a propósito, así que enganchan igual que una esquina — y estaban
     * en dos de los seis sitios y en los otros cuatro no, que es justo la clase
     * de olvido que este motor viene a hacer imposible.
     */
    private fun extras(
        scene: Scene, p: Pt, zoom: Double, faena: Faena, ajustes: AjustesEnganche
    ): List<Anclaje> {
        // A mano alzada, ninguno: por lo mismo que no engancha a los vértices.
        if (faena == Faena.A_MANO) return emptyList()
        return anclajesDeTablas(scene.tablas, scene.origenCoordenadas, scene.escala) +
            anclajesDelEje(
                scene.origenCoordenadas, p,
                radio = ajustes.radio / zoom.coerceAtLeast(0.0001),
                activo = ajustes.eje
            )
    }
}
