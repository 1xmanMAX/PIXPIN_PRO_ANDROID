# PixPin Android

**Un cuaderno de ingeniería para Android**: escribir y dibujar con lápiz, anotar PDF y planos,
guardar todo en un chat contigo mismo, leer documentos, dibujar en 3D, hacer tablas con fórmulas —
y sacarlo en **HTML**, que abre cualquiera sin instalar nada. **Sin cuentas, sin servidores y sin
analítica.**

**Android 10+** · Kotlin + Compose · 2.559 pruebas · se instala por APK

[**⬇ Descargar el APK**](https://github.com/1xmanMAX/PIXPIN_PRO_ANDROID/releases/latest) · [**Catálogo visual del motor de dibujo**](docs/motor.md) · [**Dejar un comentario**](https://github.com/1xmanMAX/PIXPIN_PRO_ANDROID/issues)

<!-- CAPTURA 1 (portada): docs/capturas/01-portada.png — el editor con un plano o un apunte anotado -->

> Proyecto personal, no oficial. No afiliado a PixPin ni a DepthPixel.

---

## Por qué existe

Soy estudiante de ingeniería. Durante toda la carrera quise tener una buena aplicación para
escribir a mano: en el iPad existen propuestas muy populares, como GoodNotes o Notability, pero
en Android no encontré nada equivalente. Probé varias, y a casi todas les pasaba lo mismo: se
quedaban en lo básico. Servían para rayar encima de un PDF y poco más.

Yo necesitaba más que eso. Necesitaba anotar planos y apuntes, medir sobre ellos, juntar en un
mismo sitio el PDF de clase, las fotos de la pizarra, el audio de la explicación y mis notas;
pasarle todo eso a un compañero **sin obligarle a instalar nada**; y tenerlo igual en el teléfono
y en la tableta **sin entregarle mis apuntes a ninguna empresa**.

Así que terminé haciéndola yo, con la ayuda de inteligencia artificial y de otras herramientas que
me permitieron llegar mucho más lejos de lo que habría llegado solo. PixPin es el resultado: la
aplicación que me hubiera gustado encontrar el primer día de clase. La uso a diario, la sigo
mejorando, y **si la pruebas me ayudas mucho dejándome un comentario** —qué te falta, qué te
estorba, qué se rompe— en [las incidencias del repositorio](https://github.com/1xmanMAX/PIXPIN_PRO_ANDROID/issues).

## Lo que la hace distinta

| | |
|---|---|
| **Todo sale en HTML** | Un dibujo, un proyecto entero, un croquis 3D o una tabla se exportan como **una página web en un solo archivo**: se abre en cualquier teléfono u ordenador, sin conexión y sin instalar nada. Y sigue viva: quien la recibe puede anotar encima, medir, editar la tabla y **volver a guardarla** |
| **Compartir con un enlace** | Cualquier cosa —la página, un PDF, una imagen— se sube a un servicio abierto **por tiempo limitado** (de 1 hora a 3 días) y se comparte como un enlace que se toca y se abre |
| **Sincronizar sin cuenta** | Tus aparatos se ponen al día entre ellos por **tu Wi-Fi**, cifrado y sin pasar por internet. No hay registro, ni nube, ni ninguna compañía que pueda ver o rastrear lo que escribes |
| **Enviar archivos pesados, rápido** | Con un código o un QR le pasas a otra persona un proyecto entero o un archivo grande por la red local, de un aparato a otro, a la velocidad de la Wi-Fi |
| **Un chat como interfaz** | Guardar algo es mandártelo, como en WhatsApp o Telegram: ya sabemos usarlo, y ordena solo por lo que uno recuerda de verdad, que es cuándo fue |
| **Lee lo que te mandan** | PDF, Word, EPUB, PowerPoint, Excel, páginas web, Markdown e imágenes se abren dentro, y salen en «Abrir con» de Android |
| **No es solo un lienzo 2D** | Croquis en el espacio (3D, con modelos IFC y OBJ), tablas con fórmulas, notas en Markdown, voz que se pasa a texto en el teléfono |
| **Multitarea propia** | Hasta tres lienzos vivos uno al lado de otro, con gestos de tres y cuatro dedos, y grupos de pestañas guardados |
| **A tu medida** | Cada herramienta del lienzo y casi cada función se **enciende o se apaga** en los ajustes: la barra lleva solo lo que usas |

Todo funciona **con el avión puesto**. Nada sale del teléfono si tú no lo compartes.

## Índice

1. [Escribir y dibujar](#1-escribir-y-dibujar) — el lienzo, el lápiz, los gestos, la multitarea
2. [Guardar y leer](#2-guardar-y-leer) — el chat, los documentos, la voz
3. [Proyectos](#3-proyectos) — planos en PDF, universos, croquis 3D, tablas
4. [Compartir y sincronizar](#4-compartir-y-sincronizar) — HTML, enlaces, Wi-Fi, sin cuentas
5. [Capturar y fijar](#5-capturar-y-fijar) — recortes de pantalla y pines flotantes
6. [Instalar, permisos y compilar](#6-instalar-permisos-y-compilar)
7. [Por dentro](#7-por-dentro) — arquitectura, limitaciones, roadmap

---

## 1. Escribir y dibujar

### Dónde se dibuja

![Las cuatro pantallas donde vive el mismo motor](docs/img/superficies.svg)

El mismo dibujo se abre en las cuatro: los elementos son los mismos bytes.

<!-- CAPTURA 2: docs/capturas/02-lienzo.png — el lienzo con un apunte real y las barras de cristal -->

### El motor de dibujo

Figuras, lápiz de presión, texto con Markdown, mosaico y foco para tapar, cotas que calculan,
bote con agujeros, alfileres, guías, escala gráfica y transportador.

**Grafito**, el lápiz de verdad: escritura libre —no se endereza ni se corrige al pararse, y la
curva pasa por cada punto que da el lápiz, sin esquinas— y **siete durezas de mina**, de la 4H
(fina y clara) a la 8B (gorda y negra), con la HB en medio; se eligen en el panel lateral.
Los gestos rápidos siguen: un segundo quieto con un trazo grande lo vuelve recta o rectángulo, y
clavar la punta abre el compás; escribiendo, una letra no se convierte nunca.

![Rectangulo, elipse, rombo, linea, lapiz, marcador, texto y esquinas](docs/img/herramientas-dibujar.svg)

![Cota, escalar, escala grafica y angulos internos](docs/img/herramientas-medir.svg)

> Cada herramienta y cada mecanismo, en dibujos: **[docs/motor.md](docs/motor.md)**.

### Gestos y lápiz

![Un dedo dibuja, dos encuadran, el segundo hace la figura perfecta](docs/img/gestos.svg)

Un dedo dibuja, dos encuadran y **nunca dibujan**. El lápiz arranca en el primer contacto, lee
todas las muestras del sistema y la presión; con lápiz a la vista, el dedo pasa a mover el papel.

### Multitarea: varios lienzos a la vez

Android no tiene una multitarea cómoda **dentro** de una app, así que PixPin trae la suya, hecha
a imagen de la de los plegables: **hasta tres lienzos abiertos, todos vivos**, uno al lado de otro.

| Gesto | Qué hace |
|---|---|
| **Tres dedos** al lado (o arriba y abajo, en columna) | Corre la tira hasta el lienzo vecino. Con tres dedos el lienzo **se calla**: ni traza, ni marca, ni mueve el papel |
| **Cuatro dedos** hacia arriba | La **baraja**: todos los abiertos en abanico |
| Cuatro dedos que **se abren** / **se cierran** | Este lienzo a su tamaño máximo / a un hueco |
| Tocar la **pestaña** que asoma | Trae al vecino |
| Arrastrar el **asa** entre dos lienzos | Reparte el tamaño, con un imán suave en tercios y mitades |

- **Cada lienzo tiene su tamaño**, y un tope pensado para que nadie se quede encerrado: la pantalla
  menos la pestaña de cada vecino —el primero y el último solo guardan una—. Pasado el tope, el
  lienzo **se estira como una goma** y vuelve. En columna la pestaña crece con las barras de
  herramientas, para que siempre se pueda tocar por fuera de ellas.
- **La baraja** enseña cada lienzo tal como se dejó, con la forma que ocupa en la tira: las
  tarjetas van inclinadas y juntas, la del centro se gira hacia ti, se deslizan con el dedo y se
  reordenan manteniendo una pulsada. Un botón pasa la tira de **fila** a **columna**.
- **Grupos de pestañas**: «Guardar grupo» apunta lo que tienes abierto con un nombre, y un toque
  lo vuelve a abrir entero, en su orden.
- Las **páginas de un PDF** entran en la tira como un lienzo más; lo anotado vuelve al documento
  **en segundo plano**, sin tirón al cambiar de lienzo.

<!-- CAPTURA 3: docs/capturas/03-multitarea.png — dos lienzos lado a lado con el asa · CAPTURA 4: 04-baraja.png — la baraja en abanico -->

### A tu medida

![Modo de captura, barra a tu gusto, guardado automático y modo seguro](docs/img/ajustes.svg)

Cada herramienta del lienzo, cada gesto rápido y casi cada función se activa o se desactiva desde
los ajustes. Quien solo quiere lápiz, borrador y texto tiene una barra de tres botones; quien
acota planos enciende las cotas, la escala y el transportador.

## 2. Guardar y leer

### Guardar: el chat

![El chat de notas guardadas, con secciones, transcripcion y un chat por proyecto](docs/img/guardados.svg)

Un gestor con carpetas obliga a **decidir dónde va cada cosa antes de guardarla**, y esa
decisión es justo la que hace que uno no guarde nada. Una conversación no pregunta: se manda y
ya está, y ordena sola por lo único que uno recuerda de verdad, que es cuándo fue.

| | |
|---|---|
| Secciones | Todo · fotos · archivos · voz · dibujos · fijados · **buzón** |
| Buzón | Lo que llega compartido de otras apps cae aquí, no en la lista, y caduca solo |
| Emoji | Uno por mensaje, se pone después: encontrar sin recordar ni una palabra |
| Comentar | Una frase al lado de un PDF diciendo por qué se guardó |
| Por proyecto | Cada proyecto tiene su chat. Lo que mandas ahí **no entra solo** en el proyecto: sale con un punto rojo y lo añades tú desde su menú, y el punto pasa a verde |

#### Documentos que se abren dentro

Un **PDF**, una **página web** (`.html`, también las que exporta PixPin), un **Word** (`.docx`) y
un **libro** (`.epub`) se leen sin salir de la aplicación, a pantalla completa y sin barras: solo
el nombre en una pastilla semitransparente que sale al tocar y se va al mover la página; tocarla
cambia el nombre. En una página exportada funcionan sus propios botones —**guardar** reescribe el
archivo, **imprimir** y **presentar**—, y salir con cambios sin guardar avisa. Un Word o un libro
se pueden ver **como saldrían impresos** y meter en un proyecto **como PDF**, para anotarlos.

Leyendo un Word o un libro se cambia **el tamaño, el grosor y el tipo de letra**, y se dejan
**marcadores con emoticono**: salen como puntos en el lateral, se pasa el dedo por ellos —vibra al
cambiar de uno a otro— y al soltar se va a ese. Al volver, el documento se abre por donde se dejó.
En el lateral izquierdo, **una línea fina con una flechita que va bajando a medida que se lee**:
arriba es el principio y abajo el final; escuchando, la flecha va con el párrafo que suena y lo
ya leído se pinta en ámbar.

**Que la página suba sola.** El botón de las dos flechas hacia abajo desplaza el Word o el libro
a la velocidad de cada uno, **en palabras por minuto** (de 80 a 700, con − y +; se recuerda), así
que no cambia al agrandar la letra. La barra de abajo dice **en cuánto se termina a esa velocidad
y a qué hora**; se pausa, se sigue desde donde se deje el dedo y se para sola al llegar al final.

**Escuchar un Word o un libro.** El botón de la voz lo lee en alto desde lo que asoma arriba de la
pantalla, con **el motor de voz de Google que ya trae el teléfono** y **solo con sus voces sin
conexión**: el texto no sale del aparato. El idioma se adivina por el propio texto (un Word en
inglés se lee con la voz inglesa), el párrafo que suena se resalta y la página lo sigue, y una
barra abajo lleva pausa, párrafo anterior y siguiente, y la velocidad (de 0,75× a 2×). Si falta
la voz del idioma, PixPin abre la pantalla de Google para bajarla; también desde el engranaje,
«Voces sin conexión».

**Sigue sonando al salir**, como un audio: con el visor cerrado o el teléfono bloqueado, la
notificación y la pantalla de bloqueo llevan pausa, párrafo anterior y siguiente, y los mandos de
los auriculares también valen; quitarlos pausa, y una llamada también. Tocar la notificación
vuelve al documento, por el párrafo que suena. **Un marcador verde** 🟢 entre los marcadores del
lateral apunta dónde se dejó de escuchar —uno solo, que se mueve con lo que se va oyendo— y la
próxima vez se sigue desde él. En el engranaje, **«Voces en línea»** usa las voces de la red del
mismo motor de Google, gratis y más naturales (el texto sí sale entonces a Google; sin red, la de
siempre). Y el botón del teléfono de la barra lo pone **por el auricular de las llamadas**, para
oírlo con el teléfono en la oreja en un sitio con ruido.

**Zoom libre y a todas partes con un dedo.** Leyendo un Word o un libro, dos dedos acercan y
alejan sin tope —alejando se ven los espacios de los lados— y un dedo lleva el documento en
cualquier dirección. Abajo, con la pastilla, tres mandos pequeños: **espacio a la izquierda**,
**el candado del lado** (con él puesto el dedo solo sube y baja, y a lo ancho se queda donde lo
dejes) y **espacio a la derecha**. Lo anotado se corre con el texto al abrir espacio a la
izquierda. El engranaje está rediseñado en cuatro apartados cortos —Letra, Lectura, Voz,
Documento— con deslizadores, interruptores y botones pequeños.

**Los tres lectores, iguales.** Word, libro y PDF llevan la misma pastilla arriba (anotar,
marcador, escuchar, página que sube sola, compartir y engranaje), el mismo engranaje por apartados
y los mismos mandos de los lados con el candado. Un **PDF se puede ver como texto** —se saca de
dentro del PDF, con sus títulos y párrafos, y a dos columnas en su orden— y así se lee, se
escucha y se anota como un Word; y
un **Word o un libro se ven como PDF**, en hojas. La página web que se exporta de cualquiera de
ellos lleva el texto de verdad y su idioma, así que **el «Leer en voz alta» de Edge la lee**; la
de un PDF en hojas lleva el texto encima de cada hoja, invisible, que además se selecciona y se
copia.

**Voces de Microsoft.** En el engranaje, **«Voces de Microsoft»** lee con las voces neuronales de
«Leer en voz alta» de Microsoft Edge: más de 300, 45 en español, gratis y muy naturales; con
**«Elegir voz»** se escoge cuál, y se recuerda por idioma. No es un servicio oficial —se habla con
él como lo hace Edge— y el texto va a Microsoft; si falla o no hay red, sigue sola la voz de
Google. Lo ya escuchado se guarda: volver a oír un párrafo no gasta red.

**Editor rápido.** Sin salir del lector se anota con **el mismo motor del lienzo**: las mismas
herramientas y tintas, las que elijas en Ajustes arrastrando. En un **PDF**, el lápiz deja anotar
sobre **todas las hojas a la vez**, como en cualquier editor de PDF: un dedo dibuja, dos pasan las
hojas. No crea ningún proyecto; si luego lo quieres en proyectos, «Al proyecto» se lo lleva con lo
anotado, y ahí se sigue con el editor completo. En un **Word o un libro**, la capa va encima del
propio texto, que se desplaza hasta el final como siempre, con **dos tercios de margen a cada
lado** para escribir; desde que hay algo anotado la letra queda fijada, para que nada se mueva de
su sitio.

Todos estos tipos —y las imágenes, las hojas de cálculo y las notas en Markdown— salen en **«Abrir
con»** del sistema, y PixPin pregunta qué hacer: **abrirlo** (se guarda una copia en Guardados, sin
duplicar), **enviarlo por Wi-Fi** o **ponerlo como pin**.

<!-- CAPTURA 5: docs/capturas/05-chat.png — el chat con una foto, un PDF, una nota de voz transcrita y los puntos rojo/verde -->

### La llamada secreta

Un recordatorio que solo oyes tú. A una **nota de voz** del chat se le pone una hora («Llamada
secreta» en su menú, con la hora exacta que quieras) y, cuando llega, el teléfono **suena como si
te llamaran** —también bloqueado—. Contestas, te lo llevas a la oreja y lo que oyes es **tu propia
grabación, por el auricular** de las llamadas y no por el altavoz: nadie alrededor se entera del
recado, y a nadie le extraña que contestes una llamada. La pantalla se apaga junto a la cara,
cuelga sola al acabar, y si no contestas queda un aviso de llamada perdida.

### La voz

![De la voz al texto: PCM, cortes por frases, tandas de veinte segundos y tres motores](docs/img/voz.svg)

Una nota de voz se pasa a texto en cuanto se guarda, **en el teléfono y sin red**. El texto
queda dentro de la propia nota, con el minuto de cada párrafo: tocarlo salta a ese punto del
audio. En el chat de un proyecto, además, queda como hoja de notas con el audio dentro.

| Motor | Pesa | Cómo va |
|---|---|---|
| **Vosk** | 38 MB | Rápido y en cualquier móvil; el menos fino |
| **Whisper** | 104 · 161 · 375 MB | Entiende el habla natural y puntúa; lento; solo 64 bits |
| **Google** | nada | El más preciso; pide Android 13 y su idioma descargado |

Se elige el **idioma de los audios**, y un segundo para Whisper. Con dos idiomas mezclados hay
dos modos: dejar **cada trozo como se dijo**, o ponerlo **todo en el primero**, traduciendo lo
demás. Lo que suene a otro alfabeto, a coletilla de subtítulos o a palabra repetida sin parar
se descarta: eso no lo dijiste tú.

#### Y al revés

| | |
|---|---|
| **Teleprónter** | Eliges un texto —una nota, un `.md`, o lo pegas—, baja solo a la velocidad que pongas y te grabas leyéndolo. Queda como nota de voz **con cada párrafo en su minuto**, sin pasar por ningún reconocedor: se sabe cuándo cruzó cada párrafo la línea de lectura |
| **Pronunciar** | Mantienes pulsado, hablas, sueltas y **te oyes al momento**. Cada toma pisa la anterior; la que convenza se guarda y se transcribe en el idioma que practicas, para ver qué se entendió. Con una guía delante: un texto, una imagen, un PDF página a página o una nota |
| **Conversación** | Varias personas por turnos, un micrófono por cabeza. El texto sale con los nombres, y **se acuerda de quién habló cuándo**: al repetir la transcripción vuelven a salir |

## 3. Proyectos

### Proyectos y planos

Un proyecto es un montón de hojas: las páginas de un PDF, lienzos en blanco, notas en Markdown
y croquis 3D. Se marcan las que interesan y **la caja de exportar está arriba, una sola**, aunque
marques hojas de proyectos distintos.

| | |
|---|---|
| **PDF vectorial** | Un plano se lee como geometría, no como foto: nítido a cualquier aumento, sin nada que cargar al acercarse, y con las capas de AutoCAD para encender y apagar |
| **Planos enormes** | Uno mayor que un A0 se trae por trozos, como las teselas de un mapa |
| **Calibrar** | Dos toques sobre una medida conocida y las cotas salen en metros |
| **Salidas** | Página web · PDF de varias hojas · imagen · `.pixpin` editable |
| **Mirar y presentar** | Un lienzo se abre en **modo visualización**: mover, ampliar, **imprimir** y **presentar** a pantalla completa con un mando en bolita. Para dibujar se pasa a editar |
| **PowerPoint** | Un `.pptx` se convierte en PDF y entra como proyecto |
| **Sistema solar** | Todos los proyectos en un mapa: cada proyecto es un sol, sus hojas, notas y emojis lo orbitan, y las conexiones entre soles tiran como una cuerda. Tocar un sol entra en su propio sistema |
| **Copias de seguridad** | Antes de recibir o sincronizar se guarda cómo estaba el proyecto; se vuelve a cualquier versión, y los lienzos que se quedaron sin proyecto se devuelven |

### Universos: el sistema solar como organizador

Todos los proyectos se ven en un mapa: cada proyecto es un sol y las conexiones entre soles tiran
como una cuerda. **Dentro de un proyecto el universo entra vacío** y se llena a mano, como un
tablero: archivos del chat, hojas, notas, rótulos de letras, figuras, imágenes y emojis, cada cosa
donde la dejas, con **vínculos** entre ellas. Cualquier cosa puede abrir **su propio subespacio**
—un sistema solar con ese archivo de sol— y así hacia dentro, sin fondo. Los archivos no se copian
ni se borran: el universo solo los señala.

<!-- CAPTURA 6: docs/capturas/06-universo.png — un universo con archivos, notas y vínculos -->

### El croquis en el espacio

![Dibujar en el espacio: el trazo se queda en el mundo, se gira alrededor, hay cuerpos y sol](docs/img/croquis3d.svg)

Los trazos se quedan **fijos al mundo**: se afilan en las puntas, engordan donde apretaste y
cambian de tono al girar, porque cada uno es un tubo con su lomo y su flanco. Se gira alrededor
del centro de lo que ves, no de un punto cualquiera. Hay bola, cilindro, cono y anillo, licuar
para deformar a mano, sol, grupos y vistas guardadas.

**Modelos de Revit y OBJ**: un edificio exportado como IFC (o un OBJ de SketchUp, Blender o Rhino)
entra en el croquis a escala real, con los huecos de puertas y ventanas recortados. Se mueve, se
agrupa, sale en la lista, en el OBJ exportado y en la página web.

**◉ En el sitio** pone la cámara de atrás de fondo y el croquis encima a tamaño real, con la
lente de verdad del aparato: mueves el teléfono y la vista se mueve con él.

### Tablas con fórmulas

Una hoja de cálculo pequeña dentro de la app: celdas, fórmulas, pegar desde Excel o Google Sheets,
y un Excel (`.xlsx`) recibido se abre aquí con una pestaña por hoja. Una tabla se exporta como
**página web que sigue calculando**: quien la recibe cambia un número y las fórmulas se rehacen,
sin instalar nada; también sale como CSV.

### Zonas y sublienzos

La herramienta **Zona** saca una foto de lo que encuadras —el PDF de fondo nítido y lo dibujado— para
arrastrarla. Con el interruptor **al chat**, la zona va al chat del proyecto como un **sublienzo**:
un lienzo propio para resolver encima, con «Viene de: PDF «Plano» → página 3» y una marca en el
origen que lleva a él. En proyectos, los sublienzos se pliegan bajo su página, y en el lector de PDF
se ven al lado de cada página alejándola con dos dedos.

## 4. Compartir y sincronizar

### La página web: todo en un archivo que abre cualquiera

![Un archivo HTML con dibujos, croquis 3D y notas, una sola barra, y se puede anotar y volver a guardar](docs/img/web.svg)

Todo va dentro del archivo —fuentes, fotos y audios en base64—, así que **se abre sin conexión y
no caduca**. Arriba lleva un **índice** con la hoja que se mira y enlaces a todas (`#hoja-3`). Quien la reciba puede rayar encima, medir, apagar capas y **guardar**: la página se
reescribe a sí misma con lo que rayó, y sigue siendo un solo archivo.

En una página de croquis, el visor trae suelo con rejilla, sombra proyectada, niebla,
giradiscos, guía de gestos y **bajar el OBJ** desde la propia página. Sin traer ni una
biblioteca de fuera: son unos kilobytes de WebGL propio.

#### Como archivo o como enlace

Mandar un `.html` o un PDF llega como documento: hay que bajarlo y abrirlo a mano. Por eso al
compartir **cualquier cosa** —la página, el PDF, el `.pixpin`, el OBJ, una imagen— se puede
pedir un **enlace**, que se toca y se abre. Se elige cuánto dura: **1 hora**, 12 horas, un día
o tres días. Si el elegido está caído se prueban los demás.

Lo que la app dice que dura un enlace es lo que **el servicio publica**, comprobado en sus
propias páginas: litterbox («expire after 1 Hour / 12 Hours / 1 Day / 3 Days», 1 GB) y temp.sh
(«files expire after 3 days», 4 GB). Se dejaron fuera los que no lo dicen o no caducan: catbox
guarda hasta dos años sin visitas, y kappa.lol no publica plazo ninguno.

> Subir es **lo único de toda la app que manda algo fuera del teléfono**, y solo al pulsar el
> botón. El servicio no es nuestro y cualquiera con el enlace puede abrirlo mientras dure, así
> que la pantalla lo dice antes de subir nada. Compartir como archivo no sale del aparato.

### Una sola hoja de compartir en toda la app

Compartir desde el lienzo, un proyecto, el chat, el croquis o una tabla abre **la misma hoja**:
imagen, PDF, SVG, página web, editable `.pixpin` o Wi-Fi; **qué páginas** (el lienzo completo o cada
marco, varias para PDF y una para imagen) y **cuánto pesará**, calculado de verdad. Al final se abre
el **panel de compartir de tu teléfono**. Las páginas del PDF de un proyecto salen vectoriales.

### Sincronizar tus aparatos, sin servidor y sin cuenta

**Tres códigos por cada cosa.** Todo lo que pasa por el chat —un lienzo, una tabla, un PDF, una
nota— nace con un **código único** oculto, un **código de chat** (el número y el código del aparato
donde nació, `#47·K7Q2`) y su **fecha de creación**. No cambian nunca, se comparta las veces que se
comparta: por eso no hace falta ningún servidor que ponga orden.

Los aparatos que comparten un **código de grupo** se encuentran solos en la misma Wi-Fi y se
sincronizan cuando pulsas el botón. **Como git, y sin que mande ningún aparato:**

- **A+B en uno y A+C en el otro dan A+B+C.** Lo cambiado en los dos se **junta**: los lienzos figura
  por figura, las tablas celda por celda, las notas párrafo por párrafo y los croquis trazo por trazo.
- **Movida en uno y con otro color en el otro** queda movida y con el color nuevo. Si los dos tocaron
  lo mismo, gana **el último cambio** (con el reloj corregido si un aparato va desfasado), y lo otro
  queda en las copias de seguridad.
- **Borrado en uno y cambiado en el otro, se queda.** Borrado y sin tocar, se borra en los dos.
- **Una hoja solo se quita en los dos si la quitaste a mano**; si faltara por un fallo, vuelve.
- **Solo viajan los cambios**: de un lienzo ya sincronizado se manda qué figuras cambiaron, no el lienzo.
- Todo viaja **cifrado** con la clave del grupo (AES-GCM) y cada archivo lleva su resumen.

Cómo lo resuelven Excalidraw, Figma y la literatura (CRDT, OR-Set, diff3, relojes híbridos), y qué
se tomó de cada uno: [`docs/plan-sincronizacion.md`](docs/plan-sincronizacion.md).

### Enviar una sola vez por Wi-Fi

Desde **Compartir** de cualquier app, desde un proyecto, un lienzo o un mensaje: sale un **código de
seis cifras y un QR**. Quien recibe lo escanea en *Sincronizar → Recibir*, ve qué le llega y acepta.
Al terminar el código deja de existir. **El chat viaja con lo que se manda**, con su hora y su número.

**Quien recibe elige.** Si ya tiene algo con **los tres códigos iguales**, decide cosa por cosa entre
**«Actualizar el que tengo»** y **«Crear como nuevo»** (con códigos nuevos: desde ahí es otra cosa).
Si los códigos no coinciden, entra aparte sin tocar nada: ante la duda, duplicar y no pisar.

> **Con un ordenador**: sincronizar y enviar por Wi-Fi funcionan hoy **entre aparatos Android con
> PixPin**. Con Windows, Mac o Linux el puente es la página web: el HTML exportado —o su enlace— se
> abre, se anota y se vuelve a guardar en cualquier navegador. Un cliente de escritorio para la
> sincronización está en el roadmap.

## 5. Capturar y fijar

### Capturar

![Bola flotante, region libre, ventana y captura larga](docs/img/captura.svg)

### Pines

![Tipos de pin flotante](docs/img/pines.svg)

### Gestos sobre un pin

| | |
|---|---|
| Arrastrar | Mover · sobre la bola, aparcar en burbuja |
| Arrastrar la esquina | Redimensionar (texto, lista, cuentas, tabla) |
| Pellizcar · dos dedos arriba y abajo | Escalar · opacidad |
| Toque · doble toque | Copiar o abrir · minimizar |
| Pulsación larga | Barra: through · dibujar · editar · PDF · pizarra · pegatina · guardar · cerrar |

### Bola flotante

| | |
|---|---|
| Toque · doble toque | Menú · capturar |
| Pulsación larga | Ocultar o mostrar todos los pines |
| Arrastrar | Mover; se imanta al borde |

### Mini-apps: la palabra es el comando

Se copia la palabra, se pinea, y sale la herramienta. Tiene que ir sola.

| Palabras | Qué sale |
|---|---|
| `time` · `timer` · `pomodoro` | Reloj y cuenta atrás (5 · 15 · 30 · 60) |
| `crono` · `cronómetro` · `stopwatch` | Cronómetro con décimas |
| `todo` · `compras` · `tareas` | Lista con casillas |
| `count` · `contador` | Contador |
| `gastos` · `cuentas` · `money` | Libro de cuentas con total |
| `board` · `pizarra` | Pizarra con cuatro fondos y pautas |
| `croquis` · `cad` · `sketch` | Hoja A4 para dibujar acotado |

## 6. Instalar, permisos y compilar

### Instalar

1. Descarga el APK de la [última versión](https://github.com/1xmanMAX/PIXPIN_PRO_ANDROID/releases/latest).
2. Permite orígenes desconocidos.
3. Concede los permisos de la primera tarjeta y pulsa **Comenzar**.

### Permisos

| Permiso | Para qué | |
|---|---|---|
| Mostrar sobre otras apps | Pines y bola | imprescindible |
| `MediaProjection` | El fotograma que se recorta | imprescindible |
| Micrófono | Notas de voz y transcripción | para la voz |
| Internet | Bajar los modelos de voz y subir una página compartida como enlace | opcional |
| Red local (Wi-Fi, mDNS) | Sincronizar y enviar entre aparatos, sin salir a internet | para sincronizar |
| Instalar apps | Instalar un APK recibido o guardado en el chat | opcional |
| Cámara | El modo «en el sitio» del croquis | opcional |
| Notificaciones · batería | Accesos rápidos · que no maten el servicio | recomendado |

### Compilar

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"   # JDK 17+

./gradlew assembleRelease      # el APK que se instala a mano
./gradlew assembleRelease -Prapido   # sin R8: compila mucho antes, para probar
./gradlew testDebugUnitTest    # 2.559 pruebas, en la JVM
./gradlew lintDebug
```

SDK **android-36** + `build-tools 36`. La ruta va en `local.properties`:

```properties
sdk.dir=C\:\\Users\\TU_USUARIO\\AppData\\Local\\Android\\Sdk
```

> El `build.gradle.kts` raíz manda la carpeta de compilación a `pixpin-build`, para poder tener
> el proyecto en una carpeta sincronizada. Si no te hace falta, borra ese bloque.

#### Por qué el APK pesa 52 MB

Son **los motores de voz**, no el código. R8 no puede recortar un binario ya compilado:

| | |
|---|---|
| `libonnxruntime.so` (Whisper) | 21,7 MB |
| `libvosk.so` (64 y 32 bits) | 17,2 MB |
| `libsherpa-onnx-jni.so` | 4,8 MB |
| **Todo el código de la app** | **5,8 MB** |

## 7. Por dentro

### Arquitectura

```
com.forge.pixpin/
├── motor/      Modelo, geometría, trazo, render, PDF, exportación web   (55.000 líneas)
├── croquis3d/  El espacio: cámara, pluma, cuerpos, OBJ                  (21.000)
├── guardados/  El chat, la voz y sus tres motores                       (10.900)
├── pin/        Ventanas overlay, tipos de pin, almacenes                 (8.100)
├── motormd/    Markdown: bloques, edición viva, tablas, fórmulas         (7.000)
├── ui/         Proyectos, editor de notas, exportar                      (4.100)
├── capture/    MediaProjection, recorte, cosido con scroll               (2.400)
├── mini/       Mini-apps                                                 (2.000)
└── data/ floating/ clipboard/ capa/ annotate/                            (3.100)
```

| Pieza | Qué resuelve |
|---|---|
| `ProjectionSession` · `CaptureFlow` | Un solo `VirtualDisplay` por sesión; entrada única de «quiero capturar» |
| `ScrollMatcher` · `ScrollStitcher` | Deducir el desplazamiento entre fotogramas y coser solo las tiras nuevas |
| `DrawController` · `Renderer` | La máquina de estados del dedo, sin una línea de Android; pintado con caché |
| `Perimetros` · `Regiones` · `Nudos` | El perímetro de cualquier figura; el bote con agujeros; los alfileres |
| `PdfLectura` · `PlanoWeb` | El PDF por dentro; su geometría como líneas, con capas |
| `Transcriptor` · `MotorVosk/Whisper/Google` | Audio a PCM, cortes por frases, tandas, y el motor elegido |
| `Croquis3DEsqueleto` · `Croquis3DPluma` | El esqueleto del trazo, cocido una vez; el tubo con lomo y flanco |
| `Camara3D` · `BaseDeCamara` | La proyección, con lente y ojo de pez; la base congelada por fotograma |
| `ExportarHtml` · `VisorEspacio` | El documento web y su visor 3D, en JavaScript sin dependencias |
| `SubirArchivo` | Compartir como enlace: multipart a mano y servicios de reserva con caducidad publicada |

La lógica delicada vive en objetos puros para poder probarla sin dispositivo: **2.559 pruebas**
en la JVM. Lo que se ve y se toca solo se valida en un móvil real.

### Trece reglas de Android que moldearon el diseño

1. El **consentimiento de captura va antes** del servicio: al revés, `startForeground()` lanza `SecurityException`.
2. Un token de captura sirve para **una sola** `createVirtualDisplay()`.
3. Un espejo de pantalla **solo emite cuando la pantalla cambia**: hay que conservar el último fotograma.
4. El portapapeles **solo se lee con la ventana enfocada** (`onWindowFocusChanged`).
5. El permiso sobre una URI compartida **muere con la actividad**: copiar antes de `finish()`.
6. Las actividades lanzadas desde un overlay necesitan **`taskAffinity` propio**.
7. Compose en una ventana overlay busca los `ViewTree*Owner` **en la vista raíz**.
8. **Atenuar la ventana entera la vuelve intocable**: la transparencia va en el contenido.
9. Arrastrar moviendo la propia ventana **anula los deltas**: hace falta `getRawX/getRawY`.
10. Una ventana `WRAP_CONTENT` **no mide más que la pantalla**: tamaño explícito en píxeles.
11. Cambiar el tamaño de una ventana **no recompone nada** si no cambia un estado leído.
12. Los gestos de Compose **se comen el arranque** del trazo y dan una muestra por fotograma.
13. **No hay API de captura con scroll**: solo queda coser fotogramas.

### Limitaciones

- **Imposibles en Android**: proyectar la ventana viva de otra app, atajos de teclado globales, arrastrar el contenido de un pin a otra app.
- **Contenido protegido** (banca, DRM): sale en negro.
- **Fabricantes agresivos con la batería**: si matan el servicio, los pines desaparecen hasta reabrir.
- Sin **OCR** ni **grabación de vídeo** (el QR solo se usa para enviar por Wi-Fi).
- **No se leen DWG, RVT ni DXF**: un modelo de Revit entra exportado como **IFC**; para medir sobre un plano ajeno se calibra su captura.
- La **captura con scroll** cose fotogramas: con cabeceras fijas o sin textura puede fallar.
- Lo más reciente **está sin verificar en un móvil**: la geometría y los formatos tienen pruebas, el aspecto no.

### Roadmap

| | |
|---|---|
| ✅ 1 – 5 | Captura, pines, lápiz, grupos, scroll, Markdown, mini-apps, visor de PDF |
| ✅ 6 · 6.5 | Croquis acotado · **motor único**: port de Excalidraw, bote, alfileres, capa sobre la pantalla |
| ✅ 7 | Proyectos, planos vectoriales con capas, `.pixpin`, exportación web de todo |
| ✅ 8 | El chat, la voz a texto con tres motores, teleprónter y pronunciar |
| ✅ 9 | El croquis en el espacio, «en el sitio», OBJ y el visor 3D del documento web |
| ✅ 9.5 | Tablas con fórmulas, sincronizar sin servidor, enviar por Wi-Fi, compartir unificado, zonas y sublienzos |
| ✅ 9.6 | Modelos IFC/OBJ en el croquis, presentar e imprimir, `.pptx`, copias de seguridad, **tres códigos y sincronizar fusionando** |
| ✅ 9.7 | Sistema solar de proyectos y tema Cosmos, **multitarea de lienzos** con baraja en abanico y grupos de pestañas, gestos rápidos |
| ✅ 9.8 | **Universos** (el sistema solar como organizador), lector de **Word, EPUB y HTML** con letra ajustable y marcadores, **«Abrir con»**, el chat ya no une solo al proyecto |
| 10 | **Editar PDFs**: devolver la página anotada al original conservando su texto |
| 11 | **Cliente de escritorio** (Windows) para sincronizar y enviar por Wi-Fi con el ordenador |
| 12 | OCR y QR · contenido de Office: el `.docx` ya se lee dentro de la app (texto, tablas e imágenes, sin la maquetación de la página), como el `.xlsx` en Tablas y el `.pptx` en hojas; queda buscar dentro |

**Descartados a propósito**: **leer** DWG (GPLv3, SDK comercial o nube), visor de DXF (un plano
real dio 59 MB y 133.102 entidades), Office con maquetado, **leer** SVG (haría falta un parser:
sería la primera dependencia externa), historial del portapapeles (Android 10+ lo prohíbe en
segundo plano).

> Ojo con el SVG: lo descartado es **leerlo**. Escribirlo no necesita nada, porque el motor ya
> genera las figuras como `M`/`L`/`C`, que es el repertorio exacto de un camino SVG.

### Documentación

- [`docs/motor.md`](docs/motor.md) — **catálogo visual**: cada función del motor de dibujo, en dibujos
- [`docs/formato-pixpin.md`](docs/formato-pixpin.md) — el formato `.pixpin` por dentro
- [`docs/plan-sincronizacion.md`](docs/plan-sincronizacion.md) — sincronizar sin servidor: los tres códigos, la fusión a tres bandas, los parches y sus fuentes
- [`docs/plan-compartir-y-zonas.md`](docs/plan-compartir-y-zonas.md) — la hoja de compartir, lienzos por Wi-Fi, zonas y sublienzos
- [`docs/superpowers/specs/`](docs/superpowers/specs/) — diseño original y decisiones de cada fase

## Comentarios

La aplicación sigue creciendo y la mejoro con lo que me cuentan quienes la usan. Si la pruebas,
**déjame un comentario**: abre una [incidencia](https://github.com/1xmanMAX/PIXPIN_PRO_ANDROID/issues)
con lo que te falta, lo que te sobra o lo que se rompió (si puedes, con el modelo del teléfono y
una captura). Todo se lee.

## Licencia

MIT. Ver [LICENSE](LICENSE). El nombre **PixPin** pertenece a DepthPixel; este proyecto es una
implementación personal e independiente.
