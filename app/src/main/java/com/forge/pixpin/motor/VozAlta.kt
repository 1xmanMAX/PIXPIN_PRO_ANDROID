package com.forge.pixpin.motor

/**
 * **Leer un Word o un libro en voz alta, con la voz de Google que trae el teléfono** (22-sep-2026,
 * pedido por el usuario: «el lector de Google en los dispositivos, la versión local»).
 *
 * Como el reconocedor de voz ([com.forge.pixpin.guardados.MotorGoogle]): **el del teléfono**, que ni
 * se baja con PixPin ni pesa, y **solo sus voces sin conexión** —las que el motor de Google tiene
 * instaladas en el aparato—. Las de la red suenan algo mejor, pero mandan el texto a un servidor, y
 * lo que se lee aquí no sale del teléfono. Aquí van las cuentas, sin Android, para poder
 * comprobarlas; la parte del sistema es [com.forge.pixpin.ui.LectorEnVoz].
 */
object VozAlta {

    /** El paquete del motor de voz de Google («Servicios de voz de Google»). */
    const val MOTOR_DE_GOOGLE = "com.google.android.tts"

    /** Las velocidades que se ofrecen; 1 es la normal. */
    val VELOCIDADES = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

    /**
     * Lo más largo que se le da al motor de una vez. Android admite 4000 caracteres
     * (`TextToSpeech.getMaxSpeechInputLength`); con trozos más cortos empieza a hablar antes y,
     * al pausar, se vuelve a un sitio más cercano.
     */
    const val TOPE = 600

    /** Una voz del motor, con lo que hace falta para elegir. [idioma] como BCP 47 («es-ES»). */
    data class Voz(
        val nombre: String,
        val idioma: String,
        /** No necesita la red para hablar. */
        val local: Boolean,
        /** Sus datos están en el teléfono (el motor avisa de las que aún hay que bajar). */
        val instalada: Boolean,
        /** La calidad que declara el motor: de 100 (muy baja) a 500 (muy alta). */
        val calidad: Int = 300
    )

    /**
     * **La voz que más se parece a [idioma]**: primero la del mismo idioma y país, luego la del
     * mismo idioma con otro acento; entre iguales, la de más calidad. Nunca una que no esté bajada.
     *
     * Sin [enLinea], **solo las sin conexión**. Con [enLinea] (el usuario lo pidió el 22-sep-2026:
     * las voces en línea de Google, que suenan mejor y son gratis dentro del mismo motor), **se
     * prefieren las de la red**, y si no hay de ese idioma, la sin conexión de siempre. Null si no
     * hay ninguna.
     */
    fun mejorVoz(voces: List<Voz>, idioma: String, enLinea: Boolean = false): Voz? {
        val (lengua, pais) = partes(idioma)
        return voces
            .filter { (it.local || enLinea) && it.instalada && partes(it.idioma).first == lengua }
            .sortedWith(
                compareByDescending<Voz> { partes(it.idioma).second == pais && pais.isNotEmpty() }
                    .thenByDescending { enLinea && !it.local }
                    .thenByDescending { it.calidad }
                    .thenBy { it.nombre }
            )
            .firstOrNull()
    }

    /** Si el motor tiene una voz sin conexión de ese idioma que **aún no está bajada**. */
    fun hayQueBajarla(voces: List<Voz>, idioma: String): Boolean {
        val lengua = partes(idioma).first
        return voces.any { it.local && !it.instalada && partes(it.idioma).first == lengua }
    }

    /** «es_ES», «es-es», «ES» → («es», «ES»). */
    fun partes(idioma: String): Pair<String, String> {
        val p = idioma.trim().replace('_', '-').split('-').filter { it.isNotBlank() }
        return p.getOrElse(0) { "" }.lowercase() to p.getOrElse(1) { "" }.uppercase().takeIf { it.length == 2 || it.length == 3 }.orEmpty()
    }

    /**
     * **De qué idioma es el texto**, por sus palabras más corrientes. Un Word convertido dice
     * siempre `lang="es"` —[DocxAHtml] no tiene de dónde sacarlo— y un libro en inglés leído con la
     * voz española es ininteligible. Si la muestra no deja claro nada, [porDefecto].
     */
    fun idiomaDelTexto(muestra: String, porDefecto: String): String {
        val palabras = muestra.lowercase().split(Regex("[^\\p{L}']+")).filter { it.isNotEmpty() }.take(600)
        if (palabras.size < 8) return porDefecto
        val cuentas = PALABRAS.mapValues { (_, lista) -> palabras.count { it in lista } }
        val (mejor, n) = cuentas.maxByOrNull { it.value } ?: return porDefecto
        val segundo = cuentas.filterKeys { it != mejor }.values.maxOrNull() ?: 0
        // Claro: al menos un 6 % de las palabras y bastante por delante del siguiente.
        return if (n >= palabras.size * 0.06 && n > segundo * 1.3) mejor else porDefecto
    }

    /**
     * **Con qué idioma se lee**: el adivinado por el texto, afinado con el acento más concreto que
     * se tenga de esa misma lengua —primero el de la página si trae país (un libro «es-MX»), luego
     * el del aparato («es-PE»)—. Sin nada que adivinar, el de la página, y si no, el del aparato.
     */
    fun idiomaParaLeer(muestra: String, lang: String, delAparato: String): String {
        val adivinado = idiomaDelTexto(muestra, "")
        val (suyo, suPais) = partes(lang)
        return when {
            adivinado.isEmpty() -> lang.takeIf { suyo.isNotEmpty() } ?: delAparato
            suyo == adivinado && suPais.isNotEmpty() -> lang
            partes(delAparato).first == adivinado -> delAparato
            else -> adivinado
        }
    }

    /**
     * **Un párrafo, en trozos que el motor admita**: se corta por el final de las frases; una frase
     * más larga que [tope], por las comas; y lo que aún sobre, por los espacios. Sin trozos vacíos.
     */
    fun trozos(texto: String, tope: Int = TOPE): List<String> {
        val limpio = texto.replace(Regex("\\s+"), " ").trim()
        if (limpio.isEmpty()) return emptyList()
        if (limpio.length <= tope) return listOf(limpio)
        val sale = ArrayList<String>()
        val actual = StringBuilder()
        fun soltar() { actual.toString().trim().takeIf { it.isNotEmpty() }?.let(sale::add); actual.clear() }
        for (frase in cortarDetras(limpio, Regex("[.!?…;:]+[\"'»”)]*\\s"))) {
            if (actual.length + frase.length > tope) soltar()
            if (frase.length <= tope) { actual.append(frase); continue }
            for (parte in cortarDetras(frase, Regex(",\\s"))) {
                if (actual.length + parte.length > tope) soltar()
                if (parte.length <= tope) { actual.append(parte); continue }
                for (palabra in parte.split(' ')) {
                    if (actual.length + palabra.length + 1 > tope) soltar()
                    // Una «palabra» más larga que el tope (una dirección, un número eterno): a tajos.
                    palabra.chunked(tope).forEach { pedazo ->
                        if (actual.length + pedazo.length + 1 > tope) soltar()
                        actual.append(pedazo).append(' ')
                    }
                }
            }
        }
        soltar()
        return sale
    }

    /** Parte [texto] justo **detrás** de cada coincidencia de [marca], sin perder nada. */
    private fun cortarDetras(texto: String, marca: Regex): List<String> {
        val sale = ArrayList<String>()
        var desde = 0
        for (m in marca.findAll(texto)) {
            sale += texto.substring(desde, m.range.last + 1)
            desde = m.range.last + 1
        }
        if (desde < texto.length) sale += texto.substring(desde)
        return sale
    }

    /** El nombre de un trozo para el motor: de qué lectura, qué párrafo y qué trozo. */
    fun idDe(lectura: Int, parrafo: Int, trozo: Int) = "$lectura:$parrafo:$trozo"

    /** Al revés que [idDe]; null si no es uno nuestro. */
    fun deId(id: String?): Triple<Int, Int, Int>? {
        val p = id?.split(':') ?: return null
        if (p.size != 3) return null
        return Triple(p[0].toIntOrNull() ?: return null, p[1].toIntOrNull() ?: return null, p[2].toIntOrNull() ?: return null)
    }

    /** La velocidad siguiente al tocar el botón, dando la vuelta al final. */
    fun siguienteVelocidad(ahora: Float): Float {
        val i = VELOCIDADES.indexOfFirst { kotlin.math.abs(it - ahora) < 0.01f }
        return VELOCIDADES[(i + 1).mod(VELOCIDADES.size)]
    }

    /** «1×», «1,25×»: como se lee en el botón. */
    fun rotulo(velocidad: Float): String {
        val centesimas = kotlin.math.round(velocidad * 100).toInt()
        val entero = centesimas / 100
        val resto = centesimas % 100
        return when {
            resto == 0 -> "$entero×"
            resto % 10 == 0 -> "$entero,${resto / 10}×"
            else -> "$entero," + resto.toString().padStart(2, '0') + "×"
        }
    }

    /**
     * El guion que prepara la página para leerla: numera cada bloque de texto que no tenga otro
     * dentro (un párrafo, un título, un punto de una lista, una celda) y devuelve, en JSON, sus
     * textos, dónde empieza cada uno (de 0 a 1, para el marcador verde), el primero que asoma arriba
     * de la pantalla y el `lang` de la página. En ES5.
     */
    const val PREPARAR = """(function(){
  var sel='p,li,h1,h2,h3,h4,h5,h6,blockquote,pre,td,th,dt,dd,figcaption,caption,div';
  if(!document.getElementById('pixpin-voz-estilo')){
    var s=document.createElement('style'); s.id='pixpin-voz-estilo';
    s.textContent='.pixpin-leyendo{background:rgba(255,196,64,.30)!important;box-shadow:0 0 0 3px rgba(255,196,64,.30)!important;border-radius:3px!important}';
    (document.head||document.body).appendChild(s);
  }
  // El texto suelto de un bloque que tiene otros dentro (un punto de lista con su sublista):
  // se envuelve en un trozo propio para que se lea en su orden, y no se pierda.
  var con=sel+',.pixpin-trozo';
  function soltar(b,tanda){
    var hay=false;
    for(var k=0;k<tanda.length;k++){ if((tanda[k].textContent||'').replace(/\s+/g,'')){ hay=true; break; } }
    if(!hay) return;
    var sp=document.createElement('span'); sp.className='pixpin-trozo';
    b.insertBefore(sp,tanda[0]);
    for(var k2=0;k2<tanda.length;k2++) sp.appendChild(tanda[k2]);
  }
  var bloques=document.body.querySelectorAll(sel);
  for(var q=0;q<bloques.length;q++){
    var bl=bloques[q];
    if(!bl.querySelector(con)) continue;
    if(bl.closest&&bl.closest('#pixpin-tinta')) continue;
    var hijos=[].slice.call(bl.childNodes), tanda=[];
    for(var h=0;h<hijos.length;h++){
      var n=hijos[h];
      if(n.nodeType==1&&(n.matches(con)||n.querySelector(con))){ soltar(bl,tanda); tanda=[]; }
      else tanda.push(n);
    }
    soltar(bl,tanda);
  }
  var todos=document.body.querySelectorAll(con), out=[], fr=[], arriba=-1;
  var alto=Math.max(1,document.documentElement.scrollHeight), y0=window.pageYOffset;
  for(var i=0;i<todos.length;i++){
    var el=todos[i];
    el.removeAttribute('data-pixpin-voz');
    if(el.closest&&el.closest('#pixpin-tinta')) continue;
    if(el.querySelector(con)) continue;
    var t=(el.innerText||el.textContent||'').replace(/\s+/g,' ').replace(/^\s+|\s+$/g,'');
    if(!t) continue;
    el.setAttribute('data-pixpin-voz',out.length);
    var r=el.getBoundingClientRect();
    if(arriba<0&&r.bottom>4) arriba=out.length;
    out.push(t); fr.push(Math.max(0,Math.min(1,(r.top+y0)/alto)));
  }
  window.__pixpinVozAntes=-1;
  return JSON.stringify({t:out,f:fr,desde:arriba<0?0:arriba,lang:document.documentElement.lang||''});
})()"""

    /**
     * El guion que resalta el párrafo [parrafo] y, con [seguir], lo trae a la vista si se ha salido
     * —pero solo si el anterior se estaba viendo: si el lector se ha ido a otra parte del
     * documento, no se le arrastra de vuelta—. Con −1 solo quita el resaltado.
     *
     * Devuelve **en qué fracción del documento empieza ese párrafo** (de 0 a 1), para la flecha
     * del riel de lectura; −1 si no está.
     */
    fun resaltar(parrafo: Int, seguir: Boolean): String = """(function(){
  var antes=document.querySelector('.pixpin-leyendo'), seVeia=true;
  if(antes){ var ra=antes.getBoundingClientRect(); seVeia=ra.bottom>0&&ra.top<window.innerHeight; antes.classList.remove('pixpin-leyendo'); }
  var el=document.querySelector('[data-pixpin-voz="$parrafo"]'); if(!el) return -1;
  el.classList.add('pixpin-leyendo');
  var r=el.getBoundingClientRect(), h=window.innerHeight;
  var f=(r.top+window.pageYOffset)/Math.max(1,document.documentElement.scrollHeight);
  if($seguir&&seVeia&&(r.top<h*0.08||r.bottom>h*0.85)) window.scrollTo(window.pageXOffset, window.pageYOffset+r.top-h*0.25);
  return Math.max(0,Math.min(1,f));
})()"""

    /** Las palabras más corrientes de cada lengua, para [idiomaDelTexto]. */
    private val PALABRAS: Map<String, Set<String>> = mapOf(
        "es" to setOf("el", "la", "los", "las", "de", "del", "que", "y", "en", "un", "una", "por", "con", "para", "es", "se", "no", "lo", "al", "su", "como", "más", "pero", "sus", "le", "ya", "o", "este", "sí", "porque", "esta", "entre", "cuando", "muy", "sin", "sobre", "también", "hay", "donde", "está"),
        "en" to setOf("the", "of", "and", "to", "in", "is", "that", "it", "was", "for", "on", "are", "as", "with", "his", "they", "at", "be", "this", "have", "from", "or", "by", "but", "not", "what", "all", "were", "when", "we", "there", "can", "which", "their", "if", "would", "been", "has", "an", "she"),
        "pt" to setOf("o", "os", "as", "de", "do", "da", "dos", "das", "que", "e", "em", "um", "uma", "para", "com", "não", "no", "na", "por", "mais", "se", "ao", "como", "mas", "foi", "ele", "ela", "seu", "sua", "ou", "ser", "quando", "muito", "há", "nos", "já", "está", "também", "só", "pelo"),
        "fr" to setOf("le", "la", "les", "de", "des", "du", "et", "un", "une", "est", "en", "que", "qui", "dans", "pour", "pas", "sur", "au", "aux", "ne", "il", "elle", "se", "ce", "avec", "plus", "par", "son", "sa", "ses", "mais", "nous", "vous", "ont", "été", "cette", "sont", "comme", "leur", "tout"),
        "it" to setOf("il", "lo", "la", "gli", "le", "di", "del", "della", "che", "e", "è", "un", "una", "per", "con", "non", "in", "si", "da", "al", "sono", "come", "più", "ma", "anche", "questo", "nel", "nella", "dei", "delle", "suo", "sua", "ha", "molto", "quando", "già", "tra", "cosa", "loro", "essere"),
        "de" to setOf("der", "die", "das", "und", "ist", "nicht", "ein", "eine", "zu", "den", "dem", "mit", "sich", "des", "auf", "für", "im", "von", "auch", "es", "an", "werden", "aus", "er", "hat", "dass", "sie", "nach", "wird", "bei", "einer", "um", "noch", "wie", "einem", "über", "so", "zum", "war", "haben")
    )
}
