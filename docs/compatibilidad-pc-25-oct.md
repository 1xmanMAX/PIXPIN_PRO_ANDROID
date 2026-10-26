# Lo del PC hasta el 25-oct, en Android (v0.131.0)

Del PC (`1xmanMAX/PIXPIN_PRO_WINDOWS`, `main` en `553f43f`; todas sus ramas ya están dentro de
`main`) y de su `docs/novedades-2026-10-25-para-los-otros-aparatos.md`. Pedido del usuario:
«revisa todo el código de Windows e implementa todas las funciones nuevas, que todo sea
sincronizable». **Nada de esto se ha probado en un teléfono**: compila y pasan 3368 pruebas JVM.

| Del PC | En Android | Viaja |
|---|---|---|
| Figuras de serie (627, 35 categorías) | `motor/FigurasDeSerie.kt`, generadas con el código del PC (`herramientas/figuras-de-serie/volcar.sh`); panel por categorías con buscador | ids `serie-…` iguales; el `figuras.json` del PC (puntos `[x,y]`) ya se lee: antes no salía ninguna figura |
| Texto rico y fórmulas `$…$` en los textos del lienzo | `motor/TextoRico.kt`, `FormulaRica.kt`, `CajasRicas.kt`, `PintorDeTextoRico.kt`, barra al editar | `textoRico` + su `text` plano, regla del PC |
| Nube de revisión dinámica | `motor/NubeDeRevision.kt`, herramienta Nube | `nubeRadio`, `nubeBase` (antes se perdían al guardar) |
| Láminas de geometría (`manual-geometria-android.md`) | herramienta Ángulo, `marcaAngulo`, `cierreArco`, `degradado`, `trazoRayaPunto` pintados | claves del PC |
| Puntero láser y copiar estilo | `PunteroLaser.kt`, `CopiarEstilo.kt`; láser también al presentar | no se guarda |
| Vista de cada marcador del lienzo | `motor/VistasDeMarcas.kt`; mantener el punto → «Actualizar vista» | `<d>.vistas.json` (antes no viajaba) |
| Grabar clase (audio, capturas, vídeo) | `guardados/GrabarClase*.kt`, barra flotante, letra con la imagen del minuto | `grabacion`, `capturas`, `video`, `enGrabacion` |
| Nota rica en el chat | `guardados/NotaRica*.kt`: Markdown y fotos en álbum en la burbuja | sin campos nuevos |
| Ficha del chat: Vídeos y Enlaces | `guardados/SeccionesDelChat.kt` | — |
| Adjuntar → Figura | `guardados/AdjuntarFigura.kt`, el panel de figuras | — |
| Carpetas de páginas | `guardados/CarpetaDePaginas.kt`, `CarpetaActivity`, página nueva con su plantilla, PDF | `MINIAPP` `carpeta`, `{"formato":1,"paginas":[…]}` |
| Asistente: conversación nueva y memoria larga | `ia/MemoriaLarga.kt`, chip «🧹 Conversación nueva» | `nuevaConversacionIa`, `auto-memoria-…` |
| Asistente: `preguntar_usuario`, `editar_texto`, lint, guardas, pasos | `ia/Preguntar.kt`, `EditarTexto.kt`, `Lint.kt`, `Guardas.kt`, `Pasos.kt` | — |
| Enviar por Wi‑Fi a mis aparatos sin código | `sincro/AlGrupo.kt` en Enviar | protocolo `suelto` de siempre |
| Tareas en Kanban | `mini/Kanban.kt` | — |
| Enviar a Anki | `anki/`: AnkiDroid o fichero de importación | — |
| Horario semanal con aviso de llamada | `horario/`, botón en Tareas | `horario.md` local, como en el PC |

## Quitado

- **Los modelos de IA locales** (llama.cpp, Qwen, Gemma): el asistente es solo de la nube; al
  arrancar se borran los modelos descargados. El dictado (Whisper) se queda.
- Del lienzo en modo visualización, el ⋮ (encuadrar, presentar, cerrar): solo el riel.
- Del ⋮ del chat, Proyectos y Comenzar (Ajustes y Chats se quedan: en el teléfono son la única
  puerta a la portada y a otros chats).

## Lo que no, y por qué

- Asistente: editar plantillas por bloques (`alargar`, `personalizar`, `reorganizar_hoja`), las
  demás herramientas de edición del lienzo, `ojos`/`repasar`/`orquesta` y las de §2.1 que el
  teléfono no ofrece.
- Exportar a SVG/PDF el texto rico, los sectores y los degradados (salen con su reserva; en
  pantalla y PNG se ven bien).
- Grabar clase: sin sonido del equipo; con vídeo, la barra sale en el vídeo y las capturas son
  de pantalla entera.
- Carpetas: sin marcadores en el PDF ni tapar lo oculto; reordenar con flechas.
- Anki: sin aviso de duplicadas. Horario: sin Google Calendar; no viaja (tampoco en el PC).

## v0.132.0: las figuras de serie, de verdad

- **No salían en el teléfono**: al empaquetar el APK, AAPT descomprime los `.gz` de `assets/` y
  les quita la extensión; se abría `figuras/serie.json.gz`, no estaba, y la lista quedaba vacía sin
  decir nada (las pruebas leían el fichero del repositorio, no el del APK). Ahora se prueban los dos
  nombres y se mira la cabecera gzip, no el nombre.
- **Sincronizables como en el PC** (`biblioteca_figuras::instalar_de_serie`): una vez por versión del
  catálogo (marca que no viaja) se instalan en `pins/draw/figuras.json` —quitar las `serie-` de
  antes, poner las de ahora al final, gana la del usuario si se llama igual—, el fichero que viaja
  por el mensaje `auto-figuras` del chat «Asistente». Antes, guardar una figura en el teléfono
  mandaba al PC una biblioteca sin las de serie, y el PC no las volvía a poner.
- `figuras.json` se lee una vez por versión del fichero (con las de serie son 10 MB).

## v0.133.0: lo del PC de 553f43f a 078c8ba

- **Ficha del chat como el panel de medios de Telegram** (`guardados/FichaDelChat.kt`, al tocar el
  título): Proyectos primero, Fotos (con las de las notas ricas), Vídeos, Dibujos, Archivos,
  Enlaces, Voz y Fijados, por meses, con buscador y previa a pantalla completa. `PROYECTOS` es solo
  de la pantalla, como en el PC: no se guarda ni viaja.
- **Nota rica revisada**: paréntesis en rutas, `<…>`, enlaces Markdown, `![]()` dentro de código,
  nunca rutas de red ni con `..`; resumen «🖼 foto» en la lista de chats.
- **Ir a una marca** con los límites de zoom del editor.
- **Grabar clase**: calidad de vídeo Ligero/Normal/Nítido como el PC, dos modos («Pantalla y
  sonido», «Audio y capturas») y **Estudiar la clase** (vídeo o captura del minuto, barra con
  capturas y marcadores, transcripción que sigue). Marcadores en `marcadores:[{ms,texto}]`, mismas
  claves que el PC.
- Del documento del PC «el motor en Android»: hilo de audio con prioridad, `VOICE_RECOGNITION` /
  `CAMCORDER`, micrófono perdido que se reabre rellenando con silencio. Los motores de escritorio
  (WASAPI, D3D11, Media Foundation) no se portan.

## v0.134.0: chats compartidos como conversación (PC 078c8ba..cad5e20)

- Quién escribió cada mensaje sale de `Mensaje.aparato` (código de 4 del aparato, como el PC) y de
  `compartidos.json` (`id`, `codigo`, `nombre`, `desde`). Míos: este aparato, los de mi grupo y los
  de la lista que son míos. Los míos a la derecha; cada persona a la izquierda con su inicial, su
  color (FNV-1a del nombre módulo 7, el mismo que el PC) y su nombre encima del primer globo de cada
  tanda. Lógica en `sincro/EntrePersonas.kt`.
- Chapa de compartido en la lista de chats y «Compartido · N personas» en la cabecera.
- «+» en la lista de chats: chat normal o compartido.
- No cambia nada de lo que se guarda ni de lo que viaja.

## Sincronizar lo nuevo sin perder nada (PC cad5e20..d56a74a)

Del `docs/manual-sincronizar-lo-nuevo.md` del PC (§7 y §8). Las tres reglas de fusión nuevas, en
`sincro/Fusion.kt` y `sincro/Protocolo.kt`, iguales byte a byte que `crates/pixpin-sincro/src/fusion.rs`
(`FusionDeLoNuevoTest`: casos con la salida que dio el PC en `d56a74a`):

| Qué | Regla | Borrar |
|---|---|---|
| `marcas` (`[ms]`) y `marcadores` (`[{ms,texto}]`) de un mensaje | por milisegundo, a tres bandas; el mismo `ms` cambiado en los dos lo gana quien gana el mensaje; sin base, todo lo de los dos; en orden de `ms`. Algo sin `ms`: la lista entera, como antes | quitado en uno sin tocar en el otro, sale; `marcadores` vacío quita la clave, `marcas` queda `[]` |
| `texto` de un `MINIAPP` «carpeta» (`{"formato":1,"paginas":[…]}`) | página a página por `id` (como las figuras de un lienzo), título a título; JSON compacto; texto vacío de base = sin páginas. Va después de la tabla; si no es una carpeta, por párrafos | quitada en uno, sale; quitada en uno y renombrada en el otro, se queda |
| `pins/draw/figuras.json` cambiado en los dos | se trae el suyo, figura a figura por `id` sin base, pieza a pieza dentro; se escribe aquí y se manda. Si alguno no es una lista de figuras con `id`, gana el tocado más tarde | sin choque (cambió un solo lado) viaja tal cual |

- `<d>.marcas`, `<d>.vistas.json` y lo anotado (`anot-<uid>.*`) siguen como en el PC: el fichero
  entero, gana el tocado más tarde (§3 del manual).
- Al llegar `figuras.json` se olvida la biblioteca leída y el panel del lienzo abierto se recarga
  (`BibliotecaStore.alLlegar`).

## OCR: Indra en el PC, «Mi vocabulario» aquí (PC d56a74a..e78c1a3)

El PC lee el texto con un motor a elegir (`[ocr] motor = "windows" | "indra"`), corrige con el
diccionario español y «Mi vocabulario», lee en otro hilo («Leyendo…» en la barra del pin, lo viejo
se tira) y precarga Indra al abrir una captura. Su `docs/ocr.md` (§ Android) da tres caminos para
el teléfono: pedírselo al escáner (una acción `OCR` que el escáner aún no tiene), copiar el
`ocr/indra/` del escáner con ONNX Runtime bajado aparte (12,9 MB de `.so` + 26 MB de pesos con
licencia OpenRAIL-M de Datalab, que **no pueden ir en el APK**) o JNI al crate de Rust (no:
Android no compila Rust). Ninguno está pedido como hecho; aquí va lo puro y lo que ya sirve:

| Del PC | En Android | Viaja |
|---|---|---|
| `pixpin-indra`: `lexico.rs`, `corrector.rs`, `dudas.rs`, `catalogo.rs` | `ocr/LexicoOcr.kt`, `CorrectorOcr.kt`, `DudasOcr.kt`, `CatalogoIndra.kt`, con las pruebas del PC (`CorrectorOcrTest`) | — |
| `[ocr] vocabulario` («Mi vocabulario») | Ajustes › Capturar › «Mi vocabulario» (`ocr_vocabulario`); corrige lo que transcribe de una foto la visión del asistente (`EjecutorDelChat.leerConVision`) | **no**: los ajustes no viajan ni en el PC ni aquí |
| `[ocr] motor`, `[ocr] corregir` | no: no hay motor que elegir ni diccionario bajado; sin confianza por letra el diccionario adivinaría | — |
| Leer en otro hilo, «Leyendo…», precargar | no hace falta: los pines y la captura de Android no leen texto (no hay OCR); el asistente ya lee fuera del hilo de la interfaz | — |

El canal de Indra (imagen, detección, LM, orden) no se porta sin el runtime que lo corra.

## v0.139.0: un solo visor de hojas, solo para ver y anotar

Pedido del usuario: «quita la visualización de Excel antigua, todo en la nueva; de momento sin
edición, solo ver y anotar con el editor avanzado; el de Windows, limpio, con la UI de los demás».

- Todo abre en `ExcelActivity`: .xlsx/.xlsm/.xls/.ods/.csv/.tsv, las tablas del chat (también las
  editadas en el PC, regla `hoja_pin::libro_para_ver`), las de los proyectos y la rueda de abiertos.
  Tablas (`TablaActivity`) ya no se abre desde ningún sitio, pero no se quita (como
  `EDITAR_EN_TABLAS = false`); sus datos y su sincronización siguen igual.
- Diseño como el visor de hojas del PC: pestañas planas con el subrayado verde, la línea de la
  celda en la pastilla de cristal, arriba solo claro/oscuro, exportar y «Abrir con»; el riel de
  siempre (marcador, lápiz con el editor avanzado, marcos; mantener el marco imprime).
- Anotaciones: `anot-<uid del mensaje>` (igual que el PC); las tablas de proyecto, por su página.
  El PC aún no guarda tinta en las tablas del chat: la del teléfono viaja pero el PC no la enseña.

## v0.140.0: la carpeta de una grabación (PC e935dba..31b45c2)

- Al grabar una clase, la nota, el vídeo y las capturas entran en una carpeta `MINIAPP`
  `carpeta` con `tipo:"grabacion"`, `voz`, `video`, `duracionMs` y el `ms` de cada página; lo
  adjuntado a la clase va con `adjunto:true`. En el chat, una sola tarjeta («● Grabación»,
  duración, capturas, «Estudiar»); lo que está dentro de una carpeta no sale suelto.
- Sincroniza con la regla §7.2 de siempre: un adjunto en cada aparato deja los dos.
- Compartir una grabación: página web, PDF, Word, Markdown, texto y Anki (CSS y JS del PC tal
  cual). Lo que en el PC son varios ficheros, aquí va en un ZIP.
- Estudiar la clase como los visores: claro/oscuro, acercar con los dedos, el riel con marcador y
  lápiz (anota sobre la captura; con el vídeo, primero guarda el fotograma como captura).

## v0.141.0: Chat · Funciones y Tareas en tarjetas, Kanban u horario (PC 31b45c2..9620ac8b)

- Arriba de la lista de chats, el interruptor **Chat · Funciones**; Funciones son los mosaicos del PC
  (mismo orden, nombres, colores). Los atajos de Tareas, Lecciones y Galería salen de la cabecera
  de Proyectos.
- **Tareas**: Tarjetas, Kanban y Horario (sin la vista Lista). El horario semanal vive dentro de
  Tareas, con las tareas con hora de la semana como chapitas en su día; el mosaico Horario abre
  Tareas en esa vista. La vista se recuerda (`tareas`/`vista`: tarjetas, kanban, horario).
- Flow Launcher y los atajos de teclado del PC no se portan (son del escritorio).

## v0.142.0: foto del chat, buscar en todos los chats y sincronizar que gira (PC 9620ac8b..455ea973)

- **Foto del chat** (imagen, emoji o color): campo `avatar` del `Proyecto`
  (`{tipo, emoji, color, color2, ruta, cuando}`, guardado tal cual para no perder tipos nuevos); la
  imagen en `files/guardados/avatar-<ms>.png`, cuadrada y ≤256 px. Se junta campo a campo como en el
  PC. Los logos viejos del teléfono pasan a ser la foto del chat al arrancar, así ya viajan.
  Probado contra el PC de verdad (`herramientas/pc-simulado`) en los dos sentidos.
- **Buscar en todos los chats**: la lupa de la lista busca también dentro de los chats (sección
  «Mensajes») y filtra sin mirar tildes.
- **El botón de sincronizar gira** mientras hay una vuelta y se marca en rojo si falló.

## v0.143.0: las grabaciones y las carpetas, de verdad (26-oct)

El usuario: «no se sincronizan las grabaciones ni hay interfaz para verlas». Probado contra el
código real del PC (`herramientas/pc-simulado`, comando `clase`): el protocolo llevaba todo bien
(voz, vídeo, capturas y carpetas, byte a byte, en los dos sentidos). Lo que fallaba:

1. **El chat lo escondía todo**: lo que estaba dentro de una carpeta no salía aunque la tarjeta de
   la carpeta no estuviera a la vista. Ahora solo se esconde si su tarjeta está en la misma lista.
2. **Los chats nuevos no se sincronizaban solos** si ya se había elegido qué chats sincronizar con
   un aparato (p. ej. el proyecto donde el PC grabó la clase). Ahora entran los creados después;
   los que se quitaron a propósito siguen fuera.
3. **Una vuelta cortada dejaba la grabación sin nada**: el vídeo grande iba primero. Ahora van los
   ficheros de menor a mayor.

Interfaz como el PC: tarjetas de carpeta y de grabación con sus iconos, botón de abrir la carpeta,
«Estudiar»; dentro, cada pieza con su icono y su visor (vídeo en la app, voz con su barra, imagen,
PDF); en la ficha del chat, las carpetas en «Archivos».

## v0.144.0: leer texto en el teléfono (Indra) y Whisper por defecto (26-oct)

- **OCR con Indra** (opción 2 de `docs/ocr.md` del PC): el motor del escáner portado a
  `ocr/indra/`, sin OpenCV (las operaciones del `pixpin-indra` del PC) y sobre el `libonnxruntime.so`
  que ya trae el APK (por JNA, como Qwen3-ASR). Los pesos (26 MB, licencia de Datalab) los baja el
  usuario en Ajustes › Capturar › «Leer texto (OCR)», con su sha256; nunca van en el APK.
  Se usa en la captura («Texto»), en el pin de imagen («Copiar texto») y en la galería (busca y
  filtra por el texto). El texto sale como `texto_de_imagen` del PC (párrafos, columnas, sin
  líneas dudosas) y no pisa el que llega del PC.
- **Motor de voz por defecto: Whisper**, como el PC. Quien eligió otro lo conserva; si Whisper no
  carga (teléfonos de 32 bits), se usa Vosk en vez de fallar.
