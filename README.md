# PixPin Android

Captura, anota y **fija notas flotantes** sobre cualquier app de tu móvil.

Adaptación a Android de la idea central de [PixPin](https://pixpin.com/) para Windows —
*capturar cualquier cosa y fijarla en pantalla*— repensada para una pantalla táctil: sin
atajos de teclado, sin menús anidados y sin diálogos intermedios. Todo se hace con gestos.

> **Proyecto personal, no oficial.** No está afiliado a PixPin ni a DepthPixel; no es un
> port de su código, sino una implementación propia en Kotlin de las funciones que me
> resultan útiles en el móvil. Se instala por APK, no está en Google Play.

**Estado:** funcional en Android 10, 12 y 15/16 · Kotlin + Jetpack Compose · 732 pruebas

[**⬇ Descargar el APK**](https://github.com/1xmanMAX/PIXPIN_PRO_ANDROID/releases/latest)

---

## Índice

- [Qué hace](#qué-hace)
- [Motor de edición](#motor-de-edición)
- [Mini-aplicaciones](#mini-aplicaciones)
- [Visor de PDF](#visor-de-pdf)
- [Gestos](#gestos)
- [Permisos y por qué](#permisos-y-por-qué)
- [Instalación](#instalación)
- [Compilar desde el código](#compilar-desde-el-código)
- [Arquitectura](#arquitectura)
- [Restricciones de Android que moldean el diseño](#restricciones-de-android-que-moldean-el-diseño)
- [Limitaciones conocidas](#limitaciones-conocidas)
- [Roadmap](#roadmap)
- [Licencia](#licencia)

---

## Qué hace

### Tres formas de disparar una captura

| Disparador | Cómo |
|---|---|
| **Bola flotante** | Un orbe arrastrable siempre visible que se imanta al borde de la pantalla. Un toque abre su menú; **doble toque captura directamente**; una pulsación larga oculta o muestra todos los pines. |
| **Tile de ajustes rápidos** | Se añade al panel desplegable del sistema y captura de un toque. |
| **Notificación persistente** | Botones de *Capturar* y *Pin*, siempre a mano. |

### Captura y recorte

Al disparar, la pantalla se congela y aparece la pantalla de recorte. **Lo que ves es
exactamente lo que se recorta**: se trabaja sobre el fotograma congelado, no sobre la
pantalla en vivo, así que nada se mueve bajo el dedo.

- **Arrastra para recortar** o **toca para la pantalla entera**.
- Las esquinas se ajustan con tiradores; el recorte se puede mover entero.
- **Lupa con selector de color**: mientras ajustas, una lupa muestra el píxel exacto bajo
  el dedo y su valor HEX, que se copia con un toque.
- Los overlays de PixPin (bola y pines) se ocultan durante el fotograma: no salen dentro
  de tu propia captura.
- Una sola barra de acciones, con **Pin** destacado: anotar · scroll · copiar · compartir · cerrar.

### Captura con scroll

Para páginas más largas que la pantalla. Eliges la zona, pulsas **Scroll** y aparece una
barrita abajo: desplazas con el dedo a ritmo normal y PixPin va cosiendo, enseñando cuántas
pantallas lleva. Al pulsar *Listo* la imagen larga entra en la pantalla de siempre, lista
para anotar, fijar, guardar o compartir.

Android no ofrece ninguna API para esto —la captura larga del sistema solo funciona dentro
de apps que la implementan—, así que el desplazamiento se deduce comparando fotogramas.
Las consecuencias, dichas de frente:

- **Ante la duda no cose.** Un tramo mal encajado estropea la imagen entera y no se ve
  hasta el final; descartarlo solo cuesta unos milisegundos. Se rechazan los fotogramas
  ambiguos, los de zonas sin textura y los saltos demasiado grandes; si vas muy rápido, la
  barrita te lo dice.
- **Las cabeceras fijas salen repetidas** si quedan dentro de la zona elegida. La solución
  es elegir la zona sin ellas.
- Tope de **5 pantallas** de alto, por memoria.
- Necesita el **modo Rápido** (sesión de captura abierta). En Discreto te lo pide y retoma
  la captura tras concederlo.

### Anotación

Un solo motor de edición para todo: la pantalla de captura, el pin flotante, la capa sobre
la pantalla y el editor a pantalla completa dibujan con **el mismo código**. Es un port de
Excalidraw —su trazo a mano alzada, su modelo y su formato— más las herramientas que aquí
hacían falta.

Veintitantas herramientas repartidas en grupos: formas, rayas, lápiz y marcador, texto,
números de serie, mosaico, foco, marco, imagen… y las que no vienen del original:

- **Bote de relleno** — pinta el hueco que dejan varias figuras, que no es de ninguna de
  ellas. Si el espacio no está cerrado, no pinta y lo dice.
- **Recortar y extender** — se traza de largo y se quita lo que sobra, hasta donde lo cruzan
  las demás. Vale para rayas, rectángulos, rombos, óvalos y arcos.
- **Alfiler** — un clavo que une dos figuras: con uno giran alrededor de él, con dos quedan
  fijas.
- **Guías** — el lápiz azul de los planos: se traza el andamio, se dibuja encima apoyándose
  en él y al final se borra.
- **Cota, escalar y escala gráfica** — medir de verdad sobre lo que sea. Ver
  [Medir y levantar planos](#medir-y-levantar-planos).

Con **imán** a esquinas, puntos medios, centros e **intersecciones de cualquier figura con
cualquier figura**; deshacer y rehacer ilimitados; y todo vectorial y serializable, que no
se «quema» hasta exportar.

La barra enseña **un botón por grupo** y despliega sus hermanas al volver a tocarlo, y **qué
herramientas salen y cómo se agrupan se elige en los ajustes**, por separado para el pin, la
capa y el editor.

![Un solo motor para los cuatro sitios donde se dibuja](docs/img/anclajes.svg)

Detalle completo del motor, **con dibujos de cada mecanismo**:
[`docs/motor.md`](docs/motor.md).

### Escribir a mano y con lápiz óptico

El dibujo a mano alzada está pensado para el lápiz, no solo para el dedo:

- El trazo empieza **en el primer contacto**, sin margen muerto: un punto, una tilde o la
  barra de una «t» se dibujan igual que un trazo largo.
- Se aprovechan **todas las muestras** del digitalizador, no solo una por fotograma —un
  lápiz muestrea a cientos de hercios y el resto viajan como puntos históricos.
- Trazo **suavizado por curvas**, no por segmentos rectos.
- **Presión**: el grosor sigue la fuerza del lápiz. Con el dedo se mantiene constante,
  porque ahí la presión es ruido.
- **Rechazo de palma**: mientras el lápiz está en uso, la mano apoyada no pinta.

### Pines: notas flotantes siempre visibles

Cada pin es una **ventana independiente** del sistema, así que mover uno no afecta a los
demás y todos siguen visibles sobre cualquier app.

| Tipo | Origen | Al tocarlo |
|---|---|---|
| **Imagen** | Una captura, o una imagen del portapapeles | Se copia al portapapeles, con lo que hayas dibujado encima |
| **Texto** | Texto copiado en cualquier app | Se copia al portapapeles |
| **Color** | Un color copiado en formato CSS: `#29B8DB`, `rgb(41,184,219)`, `41, 184, 219`, `orange`… | Se copia el HEX |
| **Archivo** | Cualquier documento compartido a PixPin desde otra app | Se abre con la app que corresponda |
| **Tabla** | Un rango copiado de una hoja de cálculo, de una web o de un PDF | Se copia el texto |
| **Mini-app** | Una [palabra mágica](#mini-aplicaciones) pineada a solas | Depende de cuál |

Además de eso:

- **Dibujar sobre el pin sin abrir nada**: el lápiz de la barra del pin activa el modo
  anotación, el pin se queda quieto y se dibuja encima. La barrita de una sola fila
  —herramienta · color · grosor · deshacer · listo— se pega al borde de la pantalla que
  menos tape del pin, y la herramienta activa hace de botón que despliega las demás solo
  mientras eliges. Como las anotaciones se guardan en coordenadas de la imagen, da igual
  anotar con el pin diminuto o a pantalla completa: se ven bien en cualquier tamaño,
  escalan con él, siguen siendo re-editables y se hornean al guardar o copiar.
- **Zoom más allá de la pantalla**: un pin de imagen crece con el pellizco hasta el triple
  de lo que cabe en pantalla, para acercarse a leer letra pequeña. Nunca se pierde: al
  soltarlo siempre queda un trozo agarrable, y arrastrarlo lo recorre.
- **Opacidad por pin**, ajustable con dos dedos en vivo.
- **Toques a través** (*click-through*): los toques atraviesan el pin hasta la app de
  abajo; un borde de color indica que está activo. Se desactiva desde la lista de pines.
- **Minimizar en burbuja**: doble toque y el pin se reduce a una burbuja que se queda
  donde estaba; otro doble toque lo devuelve. Arrastrarlo sobre la bola flotante lo aparca
  en una columna ordenada junto a ella.
- **Grupos**: se marcan varios pines en la lista y se agrupan. A partir de ahí se mueven
  juntos —arrastrar uno arrastra a los demás conservando las distancias—, se minimizan en
  **una sola burbuja** y se cierran juntos; una sombra del color del grupo los identifica. La
  escala y la opacidad siguen siendo de cada pin: agrupar sirve para colocar, no para
  uniformar.
- **Cuadro de texto redimensionable**: los pines de texto se estiran por su esquina inferior
  derecha, a lo ancho y a lo alto. Fijado el alto, el texto que sobra se desplaza dentro en
  vez de recortarse. Al pellizcar crece **todo en proporción** —cuadro y letra a la vez—,
  igual que una imagen.
- **Markdown en los pines de texto**: títulos, negrita, cursiva, tachado, código en línea y en
  bloque, listas con viñeta y numeradas, citas, reglas y enlaces. Siempre activo, porque el
  texto plano no lleva marcas y se ve igual. Lo que se copia al tocar el pin sigue siendo el
  original con sus marcas, no lo formateado. Un toque sobre un enlace lo abre en el
  navegador; en cualquier otro sitio del pin, copia.
- **Editar en el propio pin**: el lápiz de la barra abre el texto para escribir, con una
  barrita abajo —título · negrita · cursiva · tachado · código · viñeta— que pone y quita
  las marcas sobre lo seleccionado. Se escribe el Markdown en crudo, que es lo que se
  guarda y lo que se copia. También editan así las listas, las cuentas y las tablas: sus
  ítems son las líneas.
- **Pastilla de desplazamiento** a caballo sobre el borde derecho, que solo aparece cuando
  hay algo que desplazar.
- **Tablas del portapapeles**: un rango copiado de una hoja de cálculo se reconoce y se
  pinta alineado, con la cabecera separada. Se detectan columnas por tabulador, por barra
  vertical y por espacios, porque cada origen usa el suyo.
- **Prioridad**: cada pin nace sin prioridad y se alterna con una pulsación larga sobre su
  nombre en la lista. Solo hay dos estados a propósito: cinco etiquetas eran cuatro de más.
- **Pegatinas**: un emoji pegado en la esquina del pin, torcido y desbordando el recuadro,
  para distinguir de un vistazo cuál es cuál cuando hay media docena en pantalla.
- **Ocultar todo / mostrar todo** sin cerrar nada.
- **Historial**: los pines cerrados se pueden recuperar; los eliminados, no.
- **Sobreviven al reinicio** del teléfono, con su posición, tamaño y opacidad.
- Los pines pueden salirse de los bordes de la pantalla, pero nunca del todo: siempre
  queda un trozo agarrable.

### Mini-aplicaciones

Herramientas que se abren **copiando una palabra y pineándola**. Nada de menús: la palabra
es el comando.

| Palabras | Qué abre | Cómo se usa |
|---|---|---|
| `time` · `timer` · `pomodoro` | Reloj y cuenta atrás | La pegatina de emoji hace de mando: **5 · 15 · 30 · 60** minutos |
| `crono` · `cronómetro` · `stopwatch` | Cronómetro con décimas | Toque: marcha y pausa · doble toque: a cero |
| `todo` · `compras` · `tareas` | Lista con casillas | Toque en una fila la marca · el lápiz añade ítems |
| `count` · `contador` | Contador | Toque: +1 · doble toque: −1 |
| `gastos` · `cuentas` · `money` | Libro de cuentas | `-250 Compra` en rojo, `+1000 Sueldo` en verde, con total |
| `board` · `pizarra` | Pizarra para dibujar | Cuatro fondos y pauta de cuadrícula, rayas, columnas o puntos |
| `croquis` · `cad` · `sketch` | Croquis acotado | Hoja con proporción de A4; el pin abre el editor a pantalla completa |

Funcionan en mayúsculas, minúsculas o mezcla. **La palabra tiene que ir sola**: «el time es
oro» sigue dando un pin de texto normal. Cada palabra mágica es una palabra que dejas de
poder pinear como texto, y eso hay que gastarlo con cuidado.

La pizarra no es un tipo de pin aparte: es un pin de imagen con el lienzo generado, así que
hereda el dibujo a mano, el zoom, las anotaciones re-editables y la exportación sin nada
propio.

### Visor de PDF

Un pin de PDF ofrece, en su pulsación larga, una rejilla con las miniaturas de todas sus
páginas. Tocar una la extrae como **pin de imagen normal**, con su zoom, sus anotaciones y
su pegatina; «Todas» saca las primeras veinte de una tacada.

Las miniaturas se dibujan bajo demanda y pequeñas: un PDF de doscientas páginas renderizado
entero de golpe se lleva la memoria por delante. Y el tope de veinte no es pereza —doscientas
páginas serían doscientas ventanas overlay—.

Usa `PdfRenderer`, que viene en el propio Android: **cero dependencias**. Word, Excel y CAD
no tienen equivalente en el sistema y por eso no están.

### Medir y levantar planos

Medir sobre el plano de otro, y dibujar uno pequeño con medidas reales. Fue una aplicación
aparte —el croquis— y hoy son herramientas del mismo motor, así que **se puede acotar encima
de un dibujo y dibujar encima de un plano acotado**.

**Medir sobre una captura.** En obra no se lleva el DXF: se lleva la captura del plano que
te mandaron. Se traza una raya sobre una medida conocida, se teclea cuánto mide, y a partir
de ahí la imagen **es geometría**. Una captura de pantalla es ortogonal por definición, así
que no hay perspectiva que corregir y la calibración es exacta.

| Herramienta | Qué hace |
|---|---|
| **Cota** | Guarda sus dos puntos y **calcula** su cifra: al mover un extremo o al recalibrar, el número cambia solo. Un número escrito a mano se quedaría mintiendo |
| **Escalar** | Se traza sobre algo de medida conocida y se dicta cuánto mide. Es lo que le da unidades a todas las cotas |
| **Escala gráfica** | La reglita a cuadros de los planos. Un «1:50» escrito miente en cuanto alguien fotocopia; la barra encoge con el dibujo |
| **Recortar** y **extender** | Se traza de largo y se ajusta después, como a escuadra y cartabón |
| **Guías** y **alfileres** | El andamio, y los clavos que hacen que una figura hecha a trozos no se abra al moverla |

![Los grados de libertad segun cuantos alfileres](docs/img/alfiler.svg)

**Dos formas de acotar**, con interruptor: *dictada* —se teclea la longitud y el ángulo con
teclado numérico, y la raya obedece anclada por su principio— o *medida*, que dice lo que
hay. Los **ángulos internos** aparecen mientras mueves y desaparecen al soltar.

**Sale en PDF vectorial**, y con varias hojas: si pones tres marcos salen tres páginas, cada
una encuadrada y orientada por su cuenta.

### Guardar, copiar y compartir

**No hay botón de guardar: se guarda solo.** Toda captura con la que hagas algo —fijarla,
copiarla o compartirla— queda en `Pictures/PixPin`, visible en la galería. Si la cierras
con la ✕ es que no la querías, y entonces no se guarda nada. Al terminar de dibujar sobre
un pin, la versión con lo dibujado también se guarda sola.

### Si algo falla

- **Informe de fallo**: si la app se cierra de forma inesperada, guarda la traza y la
  pantalla principal ofrece compartirla. No hace falta cable ni `adb`.
- **Modo seguro**: mientras haya un informe sin revisar, PixPin no se activa sola al
  reiniciar el móvil **ni restaura los pines guardados**. Un pin que revienta al dibujarse
  dejaba la app inservible para siempre: cascaba, quedaba guardado tal cual, y al abrir se
  volvía a crear y a cascar, sin forma de entrar ni siquiera para mandar el informe. No se
  borra nada: los pines vuelven al descartar el informe desde la pantalla principal.
- **La bola siempre vuelve**: pulsar «Comenzar» la devuelve aunque se haya quedado oculta
  por una captura que no llegó a terminar.

---

## Gestos

Toda la interacción con un pin, sin menús:

| Gesto | Efecto |
|---|---|
| Arrastrar | Mover el pin |
| Arrastrar sobre la bola | Minimizar en burbuja aparcada junto a ella |
| Arrastrar la esquina inferior derecha (texto, lista, cuentas, tabla) | **Redimensionar el cuadro**: ancho y alto, con scroll si no cabe |
| Arrastrar la pastilla del borde derecho | Desplazar el contenido |
| Pellizcar | Escalar, con el punto entre los dedos clavado en su sitio |
| Dos dedos arriba/abajo | Opacidad en vivo |
| Toque | Copiar (imagen, texto, color), abrir (archivo, enlace) o actuar según la mini-app |
| Doble toque | Minimizar en burbuja / restaurar. En contador y cronómetro, su propia acción |
| Pulsación larga | Barra de acciones: toques a través · dibujar · **editar** · **PDF** · **pizarra** · pegatina · guardar · cerrar |

Los botones de la barra aparecen **según el tipo de pin**: el lápiz de dibujo solo en
imágenes, el de editar solo en los que se escriben, el de PDF solo en PDFs y la paleta solo
en pizarras.

En la lista de pines:

| Gesto | Efecto |
|---|---|
| Pulsación larga sobre el nombre | **Alternar prioridad**. En texto, archivo y color sale como etiqueta delante del nombre; en las imágenes el nombre entero pasa de «PixPin» a «Prioridad» |

Y con la bola flotante:

| Gesto | Efecto |
|---|---|
| Toque | Menú: capturar · pin del portapapeles · ocultar todo · lista de pines |
| Doble toque | Capturar directamente |
| Pulsación larga | Ocultar / mostrar todos los pines |
| Arrastrar | Moverla; se imanta al borde más cercano |

---

## Permisos y por qué

| Permiso | Para qué | ¿Imprescindible? |
|---|---|---|
| **Mostrar sobre otras apps** | Los pines y la bola son ventanas del sistema sobre las demás apps | Sí |
| **Grabación de pantalla** (`MediaProjection`) | Tomar el fotograma que se recorta. Se pide con el diálogo estándar de Android | Sí, para capturar |
| **Notificaciones** | Barra persistente con los accesos rápidos | Recomendado |
| **Excluir del ahorro de batería** | Evita que el sistema cierre el servicio y desaparezcan los pines | Recomendado |
| **Arranque al iniciar** | Restaurar los pines tras reiniciar el móvil | Opcional |

Nada sale del teléfono: no hay red, ni analítica, ni cuentas. Las imágenes de los pines
viven en el almacenamiento privado de la app.

### Modo de captura

Android 14+ ya no permite «un permiso, capturas para siempre», así que el modo se elige en
los ajustes:

- **Rápido** — la sesión de captura se mantiene abierta: las capturas son instantáneas y
  fiables. Mientras dura, consume como una grabación de pantalla y el sistema muestra su
  icono; se cierra desde la notificación.
- **Discreto** — la sesión se cierra tras cada captura: sin icono permanente ni gasto,
  pero Android pide permiso cada vez.

---

## Instalación

1. Descarga el APK desde la [última versión](https://github.com/1xmanMAX/PIXPIN_PRO_ANDROID/releases/latest)
   (o compílalo tú, más abajo).
2. Permite la instalación de orígenes desconocidos en tu navegador o gestor de archivos.
3. Abre PixPin y concede los permisos que pide el onboarding.
4. Pulsa **Comenzar**: aparece la bola flotante.

Requiere **Android 10 (API 29) o superior**. Probado en Android 10, 12 y 15/16.

---

## Compilar desde el código

```bash
# JDK 17+ (el JBR de Android Studio sirve)
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"

./gradlew assembleDebug        # APK de depuración
./gradlew testDebugUnitTest    # 109 tests unitarios
./gradlew lintDebug            # análisis estático
```

Necesitas el SDK de Android con la plataforma **android-36** y `build-tools 36`. La ruta
del SDK va en `local.properties` (no se sube al repositorio):

```properties
sdk.dir=C\:\\Users\\TU_USUARIO\\AppData\\Local\\Android\\Sdk
```

> El `build.gradle.kts` raíz redirige la carpeta de compilación fuera del proyecto
> (`%LOCALAPPDATA%/pixpin-build`). Es para que el proyecto pueda vivir en una carpeta
> sincronizada en la nube sin que el sincronizador pelee con los archivos temporales; si
> no lo necesitas, borra ese bloque.

---

## Arquitectura

Kotlin + Jetpack Compose, un solo módulo, sin inyección de dependencias ni base de datos:
el estado es pequeño y se guarda en JSON.

```
com.forge.pixpin/
├── capture/    Sesión de MediaProjection, pantalla de recorte, exportación
├── motor/      Motor de edición: modelo, geometría, trazo, gestos, render y salidas
├── capa/       Capa para dibujar encima de la pantalla, sobre otras apps
├── annotate/   Lector de trazo del lápiz (presión y rechazo de palma) e histórico
├── pin/        Ventanas overlay: gestos, tipos de pin, almacenes, gestor global
├── clipboard/  Lectura y clasificación del portapapeles, receptor de "compartir"
├── floating/   Bola flotante, tile de ajustes rápidos, servicio ambiental
├── data/       Ajustes (DataStore), persistencia de pines (JSON), informe de fallos
└── ui/theme/   Tema Material 3
```

### Piezas principales

| Componente | Responsabilidad |
|---|---|
| `ProjectionSession` | Dueña de la sesión de captura: un único `VirtualDisplay` que vive mientras dura la sesión y conserva el último fotograma recibido |
| `CaptureFlow` | Punto de entrada único de «quiero capturar», venga de donde venga |
| `CaptureService` | Servicio en primer plano que sostiene la sesión (tipo `mediaProjection`) |
| `CaptureActivity` | Recorte, lupa, color, anotación y acciones sobre el fotograma congelado |
| `OverlayManager` | Crea y gobierna todos los pines: visibilidad, historial, persistencia |
| `PinWindowController` | Un pin = una ventana. Gestos, contenido y estado serializable |
| `OverlayTouchHandler` | Reconocedor de gestos en coordenadas absolutas de pantalla |
| `StrokeTouchReader` | Motor de trazo: lee el `MotionEvent` crudo para no perder ni el arranque ni las muestras del lápiz. Lo comparten la pantalla de captura y los pines |
| `StrokeSmoothing` | Suavizado por puntos medios y grosor según presión; alimenta tanto el dibujado en vivo como el horneado |
| `PinZoom` | Matemática pura del pellizco: foco entre los dedos clavado y tope deducido del tamaño natural y la pantalla |
| `PinGroups` | Matemática pura de los grupos: pertenencia, arrastre solidario y disposición al plegar y desplegar la burbuja |
| `OverlayComposeWindow` | Andamiaje para meter Compose en una ventana sin Activity detrás |
| `SelectionGeometry` | Matemática pura del recorte (anclas, esquinas, límites) |
| `ScrollMatcher` | Matemática pura del cosido: resume cada fila en una firma y busca el desplazamiento entre fotogramas, rechazando lo ambiguo |
| `ScrollStitcher` | Guarda solo las tiras nuevas de cada fotograma y las junta al terminar |
| `Markdown` | Intérprete propio de un subconjunto de Markdown, en dos niveles: bloques e inline. Devuelve el texto ya sin marcas más los tramos que lo decoran |
| `MarkdownEdit` | Aritmética de índices al poner y quitar marcas sobre la selección |
| `MagicWord` | Qué palabra abre qué mini-aplicación, y la regla de que tenga que ir sola |
| `TableData` | Detección de tablas: prueba varios separadores y se queda con la rejilla más coherente |
| `Ledger` | Interpretación y suma del libro de cuentas |
| `TextBoxSize` | Límites del cuadro de texto al estirarlo por la esquina |
| `PinChrome` | Hueco que la ventana le deja a lo que se dibuja fuera del recuadro: sombra y pegatina |
| `BallState` | Los tres estados de la bola. El tercero —puesta pero oculta— era el que la dejaba desaparecida sin vuelta |
| `PdfDoc` | Lectura de PDFs con `PdfRenderer`, sin dependencias |
| `DrawController` | La máquina de estados del dedo del motor: qué pasa entre que se toca y se levanta. Sin una línea de Android |
| `Renderer` | Pintado de la escena, con caché de geometría por elemento y el modo noche como filtro |
| `Perimetros` | El perímetro de **cualquier** figura en tramos rectos: de ahí salen las intersecciones y el relleno |
| `Regiones` | El bote: rejilla, derrame y contorno con agujeros |
| `Nudos` | Los alfileres y su ley de grados de libertad |
| `Recorte` | Recortar y extender sobre rayas, arcos, óvalos, rectángulos y rombos |
| `Medida` · `EscalaGrafica` | La escala, la cota que calcula su cifra y la reglita a cuadros |
| `PdfLectura` · `PdfLector` | Lectura del formato PDF por dentro —índice comprimido incluido— para poder escribir encima sin romperlo |

La lógica delicada está extraída en objetos puros (`PinZoom`, `PinGroups`,
`SelectionGeometry`, `ContentClassifier`, `AnnotationGeometry`, `UndoStack`,
`StrokeSmoothing`, `ScrollMatcher`, `Markdown`, `MarkdownEdit`, `MagicWord`, `TableData`,
`Ledger`, `TextBoxSize`, `PinChrome`, `BallState`) precisamente para poder probarla sin
dispositivo: son **732 pruebas** que corren en la JVM en menos de un minuto. El resto se
cubre con Robolectric, que en los tests del cosido corre en modo gráfico nativo para
trabajar con píxeles de verdad.

Lo que queda fuera de las pruebas es todo lo que se ve y todo lo que se toca: composición,
sombras, ventanas overlay y reparto de gestos solo se validan en un dispositivo real.

### Flujo de una captura

```
Bola / Tile / Notificación
        └─ CaptureFlow ─┬─ ¿sesión viva? ── ProjectionSession.grab()
                        └─ si no: ConsentActivity → CaptureService → sesión → grab()
                                        │
                          (overlays ocultos durante el fotograma)
                                        ↓
                                 CaptureActivity
                       recorte + anotación → Pin | Copiar | Compartir | Scroll
                                    (y guardado automático)
```

---

## Restricciones de Android que moldean el diseño

Buena parte del diseño es consecuencia directa de reglas de la plataforma que no son
evidentes. Quedan aquí por si le ahorran tiempo a alguien:

1. **El consentimiento de captura va ANTES del servicio.** Desde Android 14, arrancar un
   servicio en primer plano de tipo `mediaProjection` sin haber obtenido antes el permiso
   hace que `startForeground()` lance `SecurityException` y el sistema mate la app.
2. **Un token de captura sirve para una sola `createVirtualDisplay()`.** No se puede crear
   un display por captura: hay que crear uno y mantenerlo mientras dure la sesión.
3. **Un espejo de pantalla solo emite fotogramas cuando la pantalla cambia.** Enganchar la
   superficie justo al capturar no garantiza recibir nada; por eso se conserva siempre el
   último fotograma (si no ha llegado uno nuevo es porque nada ha cambiado).
4. **El portapapeles solo se lee con la ventana enfocada**, no basta con que la actividad
   exista: hay que leerlo en `onWindowFocusChanged`.
5. **El permiso sobre una URI compartida muere con la actividad**: el archivo hay que
   copiarlo antes de llamar a `finish()`.
6. **Las actividades lanzadas desde un overlay necesitan `taskAffinity` propio.** Si
   comparten tarea con la pantalla principal, Android trae toda la tarea al frente y el
   usuario acaba viendo —y capturando— la propia app en lugar de la suya.
7. **Compose dentro de una ventana overlay** busca los `ViewTree*Owner` subiendo hasta la
   vista raíz: si solo los tiene un hijo, revienta al adjuntarse.
8. **Atenuar la ventana entera la vuelve intocable** a partir de cierto punto (protección
   contra overlays engañosos). La transparencia se aplica al contenido, no a la ventana.
9. **Arrastrar con los gestos de Compose no funciona si mueves la propia ventana**: los
   deltas son relativos a una ventana que se mueve bajo el dedo y se anulan entre sí. Hay
   que trabajar con `MotionEvent.getRawX/getRawY`.
10. **Una ventana `WRAP_CONTENT` no puede medir más que la pantalla.** Es el techo con el
    que los pines dejaban de crecer mientras la escala seguía subiendo. La salida es darle
    a la ventana un **tamaño explícito en píxeles**, que sí puede pasarse de la pantalla.
11. **Cambiar de tamaño una ventana no recompone nada.** Hay nueva medida y nueva
    disposición, pero Compose solo recompone si cambia un estado que se haya leído. Un
    tamaño guardado en un campo suelto se queda con el valor de la primera vez — y las
    anotaciones dejan de seguir a la imagen al escalarla.
12. **Los gestos de Compose se comen el arranque del trazo** (esperan al *touch slop*) y
    entregan una muestra por fotograma. Un lápiz óptico muestrea a cientos de hercios y el
    resto viajan dentro del evento como puntos históricos: sin leerlos, se tira la mayor
    parte del trazo.
13. **No hay API de captura con scroll.** La del sistema solo funciona dentro de apps que
    la implementan; desde fuera solo queda coser fotogramas y deducir el desplazamiento.

---

## Limitaciones conocidas

- **Imposibles en Android** (fuera de alcance permanente): proyectar una ventana viva de
  otra app, atajos de teclado globales, gestos globales de ratón y arrastrar el contenido
  de un pin hacia otra app.
- **Contenido protegido**: las apps que marcan su ventana como segura (banca, vídeo con
  DRM) salen en negro en la captura. Es una protección del sistema.
- **Fabricantes agresivos con la batería** (Xiaomi, Huawei, Oppo…): si el sistema mata el
  servicio, los pines desaparecen hasta volver a abrir la app. Excluir PixPin del ahorro
  de batería lo evita.
- Aún **sin OCR, sin QR y sin grabación** de GIF/vídeo.
- Lo más reciente del motor —los ángulos en vivo, el pixelado del propio dibujo, las
  cabezas de los alfileres— está **sin verificar en dispositivo**: su geometría tiene
  pruebas, su aspecto no.
- **No se leen DWG, RVT ni DXF.** Para medir sobre un plano ajeno se calibra su captura,
  que es el camino que la fase 6 tomó a propósito.
- La **captura con scroll** funciona cosiendo fotogramas, no con una API del sistema: en
  pantallas sin textura o con cabeceras fijas el resultado puede no ser perfecto.
- El APK de depuración pesa ~65 MB porque incluye el paquete completo de iconos de
  Material y no está minificado.

---

## Roadmap

| Fase | Contenido |
|---|---|
| ✅ **1 — MVP** | Disparadores, captura y recorte, 8 herramientas de anotación, pines de imagen/texto/color/archivo, gestos, burbujas, historial, restauración al reinicio |
| ✅ **1.5** | Motor de trazo para lápiz óptico (presión, rechazo de palma, suavizado), anotar sobre un pin flotante, zoom del pin hasta el borde de la pantalla, nº de serie, polilínea y foco |
| ✅ **2** | Grupos de pines |
| ✅ **3** | Captura con scroll |
| ✅ **4** | Cuadro de texto redimensionable, Markdown con enlaces, edición en el pin, prioridad, pegatinas de emoji y sombra con color de grupo |
| ✅ **5** | Mini-aplicaciones por palabra mágica, tablas del portapapeles y visor de PDF |
| ✅ **6 — Croquis acotado** | Mundo en metros con `Double`, imantado y entrada numérica, recortar y extender, cotas que calculan su cifra, medición sobre una captura calibrada y PDF vectorial |
| ✅ **6.5 — Motor único** | Un solo motor para captura, pin, capa y editor: port de Excalidraw, bote de relleno, alfileres, guías con escuadra, escala gráfica, PDF de varias hojas y capa sobre la pantalla. Retira `croquis` y jubila `annotate` |
| **6.9 — Editar PDFs** | Anotar una página y **devolverla al PDF original** con una actualización incremental, conservando su texto seleccionable y buscable. El lector del formato ya está |
| **7** | OCR local con ML Kit, reconocimiento de QR y traducción |
| **8 — Vídeo** | Pin de vídeo que se reproduce en su sitio, rejilla de fotogramas, recorte por tiempo y grabación de pantalla |
| **9 — Documentos de oficina** | Contenido de DOCX, XLSX y PPTX como pin de texto o de tabla |

### Lo que le queda al motor

- **Verificación en dispositivo** de lo más reciente. La geometría tiene pruebas; el aspecto
  y el tacto solo se han comprobado compilando. Es la deuda mayor.
- **Escribir dentro de un PDF**: el apéndice incremental. Leerlo ya funciona.
- **Pegar una tabla de coordenadas** desde Excel o Google Sheets, y poder ponerle una letra
  a cada punto.
- **La fuente Excalifont** del texto y una auditoría de las puntas de flecha contra el
  original — lo que queda para igualar a Excalidraw.
- **Exportar a DXF** para devolver el dibujo a un CAD de escritorio. Leer DXF sigue
  descartado; escribirlo es mucho más fácil.

### Fase 8 — Vídeo

El vídeo entra por donde ya entró el PDF: **lo que Android trae de fábrica**.

- **Pin de vídeo.** `MediaPlayer` sobre un `TextureView` dentro de la ventana del pin. Toque
  para marcha y pausa, doble toque al principio — los mismos gestos que el cronómetro.
  Arrastrar, pellizcar y agrupar siguen funcionando igual que en cualquier otro pin.
- **Rejilla de fotogramas.** Pulsación larga → miniaturas cada pocos segundos con
  `MediaMetadataRetriever`. Tocar una la extrae como **pin de imagen normal**, con su zoom,
  sus anotaciones y su pegatina. Es el visor de PDF aplicado al eje del tiempo.
- **Recorte por tiempo.** Dos marcas sobre la barra de reproducción y el trozo sale a un
  archivo con `MediaMuxer`, sin recodificar y sin perder calidad.
- **Grabación de pantalla en MP4.** `MediaRecorder` colgado de la **misma `MediaProjection`
  que ya está viva**: la restricción nº 2 obliga a conservar un único display durante toda
  la sesión, así que grabar es engancharse a él, no pedir otro permiso.
- **GIF.** Aquí sí hay que escribir el codificador: Android sabe leer GIF, no escribirlo.
  Queda al final de la fase por ser lo único que no regala la plataforma.

### Fase 9 — Documentos de oficina

**DOCX, XLSX y PPTX.** Son ZIP con XML dentro, y tanto `ZipInputStream` como
`XmlPullParser` vienen en Android. Se extrae el contenido y se pinta con el renderizador
de **Markdown y tablas que ya existe desde v0.3.0**: el documento se convierte en un pin
de texto y la hoja de cálculo en un pin de tabla. Es contenido, **no maquetado** — sin
saltos de página, sin estilos ni fórmulas. Un pin no es un visor de Office.

Descartados a propósito: pin de fórmulas LaTeX, motor de scripts y sincronización en la nube.

Estudiados y **descartados por lo que costarían frente a lo que dan**:

- **Leer DWG.** Binario, propietario, cerrado y comprimido desde 2004. Las tres únicas vías
  chocan con el proyecto: LibreDWG es GPLv3 —incompatible con la licencia MIT—, el SDK de
  ODA es comercial, y la nube de Autodesk está descartada por principio. Se barrieron **394
  DWG del disco buscando la miniatura embebida y 0 la tenían accesible**, así que tampoco
  hay atajo por ahí.
- **Visor de DXF.** Viable, pero desproporcionado. Se convirtió un plano de topografía real
  y salieron **59 MB, 133.102 entidades dentro de 345 bloques y 13.066 splines**: sin
  expandir los `INSERT` se dibujaría el 1 % del plano, y las splines son las curvas de
  nivel. Es un proyecto entero, y la **fase 6 resolvió la necesidad por otro lado** —medir
  sobre la captura del plano, que es lo que uno lleva encima en obra—.
- **Office con su maquetado.** Renderizarlo tal cual se ve pide librerías grandes. La fase 9
  se queda a propósito en el contenido.
- **SVG.** Es donde el zoom más luciría, pero necesita una dependencia externa; sería la
  primera del proyecto. El lienzo vectorial del croquis ya existe, así que sería ese mismo
  `Canvas` con un parser delante.
- **Historial del portapapeles.** Android 10+ prohíbe leerlo en segundo plano, así que solo
  podría registrar lo que pase por PixPin — que es el historial que ya hay.
- **Buscador en la lista de pines.** Queda a medias: el texto está en los recursos y el
  campo no se llegó a construir.

---

## Documentación de diseño

- [`docs/motor.md`](docs/motor.md) — **el motor de edición por dentro**: el modelo, el trazo,
  el imán, los alfileres, el bote, la máquina de estados del dedo y el PDF
- [`docs/superpowers/specs/2026-07-26-pixpin-android-design.md`](docs/superpowers/specs/2026-07-26-pixpin-android-design.md) — diseño original y decisiones de producto
- [`docs/superpowers/specs/2026-07-27-correcciones-estabilidad-fluidez.md`](docs/superpowers/specs/2026-07-27-correcciones-estabilidad-fluidez.md) — diagnóstico de estabilidad y fluidez
- [`docs/superpowers/specs/2026-07-28-anotacion-grupos-scroll-design.md`](docs/superpowers/specs/2026-07-28-anotacion-grupos-scroll-design.md) — motor de trazo, anotación sobre el pin, grupos y captura con scroll
- [`docs/superpowers/specs/2026-07-30-texto-prioridad-sticker-design.md`](docs/superpowers/specs/2026-07-30-texto-prioridad-sticker-design.md) — cuadro de texto, prioridad binaria y pegatinas
- [`docs/superpowers/specs/2026-08-01-markdown-pellizco-sombra-design.md`](docs/superpowers/specs/2026-08-01-markdown-pellizco-sombra-design.md) — Markdown, pellizco proporcional y sombra en vez de marco
- [`docs/superpowers/specs/2026-08-02-croquis-acotado-design.md`](docs/superpowers/specs/2026-08-02-croquis-acotado-design.md) — croquis acotado, con lo medido sobre DWG y DXF reales que descartó el visor de CAD
- [`docs/superpowers/plans/2026-07-30-texto-prioridad-sticker.md`](docs/superpowers/plans/2026-07-30-texto-prioridad-sticker.md) — plan de implementación de esa tanda

---

## Licencia

MIT. Ver [LICENSE](LICENSE).

El nombre y la aplicación **PixPin** pertenecen a DepthPixel. Este proyecto es una
implementación personal e independiente, sin relación con ellos ni con su código.
