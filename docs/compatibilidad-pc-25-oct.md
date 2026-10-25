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
