# Idiomas en Android: los catorce del PC

**1-nov-2026.** PinDesk para Android habla los catorce idiomas del PC (`crates/pixpin-store/src/idioma.rs`
y sus catálogos `i18n/<locale>/main.ftl`); empezó con español e inglés. Este documento dice cómo funciona,
qué está hecho, qué queda en español y cómo se añade un texto.

## Cómo se elige el idioma

**Desde el 1-nov-2026, catorce idiomas, como el PC** (commit f87a6712 «catorce idiomas con red al inglés
y al español» y el selector de 7c7458ac): es, en, zh-CN, hi, ar, pt-BR, fr, de, ja, ru, ko, it, id, tr.

Ajustes, abajo del todo, tarjeta **Idioma**: un desplegable con «El del teléfono» y los catorce, cada uno
**con su nombre en su propia lengua** (`Idioma.nombrePropio`, los `nombre_propio` del PC: «中文 (简体)»,
«العربية», «Türkçe»…), en el orden del PC (`Idioma::TODOS`).

| Elección | Clave guardada (y alias que también se leen) | Resultado |
|---|---|---|
| El del teléfono (de fábrica) | `sistema` | El idioma del teléfono si es uno de los catorce; **si no, inglés** |
| Español, English, 中文… | `es` (`es-ES`), `en` (`en-US`), `zh-CN` (`zh`, `zh-Hans`), `hi`, `ar`, `pt-BR` (`pt`), `fr`, `de`, `ja`, `ru`, `ko`, `it`, `id`, `tr` (y `xx-XX`) | Ese idioma |

- La regla es `resolverIdioma` (`ajustes/Idioma.kt`), porte de `resolver_idioma`/`idioma_del_locale` del
  PC, con sus mismas pruebas (`IdiomaTest`): se mira la parte del idioma (`fr-CA`, `ar-EG`, `es_419`,
  `fr_FR.UTF-8`); el chino tradicional (`zh-TW`, `zh-Hant-HK`) va al simplificado, `pt-PT` al de Brasil,
  `in` es indonesio. Un teléfono en neerlandés ve **inglés**, no el español de `values/` en el que caería
  Android por su cuenta: por eso con «El del teléfono» se fija siempre un idioma explícito.
- **La red** (`Idioma.cadena`, la del PC: propio → inglés → español). A Android se le da una lista
  (`etiquetasPara`): el idioma y, si no es español ni inglés, `en` detrás. Si un idioma no tuviera
  carpeta, Android escoge el inglés de la lista. Ojo: Android elige **una** carpeta por idioma, y una
  clave suelta que falte en ella sale en español (`values/`), no en inglés; por eso `TraduccionesTest`
  exige todas las claves en todas las carpetas.
- **Se guarda en el teléfono** (preferencias `idioma`, clave `preferencia`), no en los ajustes que viajan:
  cada aparato tiene el suyo, como en el PC.
- **Cómo se aplica** (`ajustes/IdiomaDeLaApp.kt`):
  - Android 13+: idiomas por aplicación (`LocaleManager.applicationLocales`). Salen también en Ajustes del
    sistema › Idioma de las apps gracias a `res/xml/locales_config.xml` (`android:localeConfig` en el
    manifiesto), con los catorce. El sistema rehace las pantallas abiertas.
  - Android 10–12: se cambia la configuración de los recursos de la aplicación y de cada pantalla al nacer
    (`onActivityPreCreated`). La pantalla de Ajustes se rehace al elegir; las de detrás cambian al reabrirse.
  - Sin `appcompat`: no hace falta y no se tocan los temas.
  - Se vuelve a mirar al arrancar y cuando el teléfono cambia de idioma (`PixPinApp.onConfigurationChanged`).
  - Si el teléfono habla el idioma elegido se conserva su región (`es-MX`, `en-GB`, `ar-EG`) para fechas y
    números. El chino y el portugués van siempre como `zh-CN` y `pt-BR`: su carpeta lleva región y Android
    no se la daría a un `zh-TW` o a un `pt-PT`.
  - El árabe se escribe de derecha a izquierda: el manifiesto ya lleva `android:supportsRtl="true"`, así
    que Compose voltea solo la disposición.
  - En Robolectric no se toca nada: las pruebas ven el español de fábrica.

### Las carpetas

| Idioma | Carpeta | Plurales (CLDR) |
|---|---|---|
| Español | `values/` (el de fábrica) | `one`, `other` |
| Inglés | `values-en/` | `one`, `other` |
| Chino simplificado | `values-zh-rCN/` | `other` |
| Japonés, coreano | `values-ja/`, `values-ko/` | `other` |
| Hindi, turco | `values-hi/`, `values-tr/` | `one`, `other` |
| Árabe | `values-ar/` | `zero`, `one`, `two`, `few`, `many`, `other` |
| Portugués (Brasil), francés, italiano, alemán, ruso, indonesio | `values-pt-rBR/`, `values-fr/`, `values-it/`, `values-de/`, `values-ru/`, `values-in/` | los suyos (`TraduccionesTest` los comprueba) |

El vocabulario de cada idioma es el de su catálogo del PC (`crates/pixpin-store/i18n/<locale>/main.ftl`):
para un concepto (Tareas, Bandeja, Capturar, Pinear, Sincronizar, Grabar, Lienzo…) se busca primero allí.
El nombre visible de la aplicación es **PinDesk** en todas. Rutas y extensiones que son datos
(`Pictures/PixPin`, `.pixpin`) no se traducen.

## Los textos

- `values/` sigue siendo **el español** (el de fábrica); `values-en/` es el inglés, **completo**: todas las
  claves de todos los `strings*.xml`, con los mismos `%1$s`/`%d` y plurales.
- `TraduccionesTest` falla si una clave de `values/` no está en `values-en/` (o al revés) o si sus huecos
  de formato no coinciden. Es la red del test de paridad de catálogos del PC.
- El vocabulario es el del catálogo inglés del PC: Tasks, Inbox, Capture, Pin, Sync, Record, Canvas,
  Transcribe, Saved messages, Settings… Para un concepto nuevo, buscar primero en `en-US/main.ftl`.
- **No se traduce** nada que sea dato o viaje con el PC: campos del JSON de los mensajes, claves, nombres de
  archivo, la identidad de la lista «Inbox» (`TodasLasTareas.INBOX`), los marcadores de las tareas, etc. Solo
  texto de interfaz. Lo que se busca por palabras en español (el buscador de Ajustes, el del clip) sigue
  buscando en español; el del clip lleva además sinónimos en inglés.

### Textos que estaban en el código

Están en `values/strings_idiomas.xml` y `values-en/strings_idiomas.xml` (archivo propio para no pisar los
cambios que otros añaden a `strings.xml`). Hechos:

- **Lista de chats** (`ui/ListaDeChats.kt`): botones, vacíos, el vistazo de la última línea, «ayer»; el
  menú del «+» (`guardados/ChatEntrePersonas.kt`, con los `chat-nuevo-*` del PC).
- **Chat** (`guardados/MensajesActivity.kt`): avisos, diálogos, nombres, recordatorios, la hoja del clip
  (`guardados/OpcionesDeAdjuntar.kt`: cada opción lleva su `@StringRes texto`).
- **Ajustes** (`MainActivity.kt`, `ajustes/AjustesV2.kt`): secciones, buscar, deshacer, restablecer,
  «De fábrica», barras por sitio, días hasta borrar, compresión de PDF (`pdf/ComprimirPdf.ROTULOS`), los
  nombres de los idiomas de voz (los da Java en el idioma puesto) y la tarjeta Idioma.
- **Tareas** (`mini/TodasLasTareasActivity.kt`): todo lo de la pantalla.
- **Grabación** (`MandosDeLaGrabacion.kt`, `ReunionEnCursoActivity.kt`, `GrabarClaseActivity.kt` ya usaban
  recursos) y `guardados/TextosDeLaReunion.kt` (los temas y el acta con Jev), que pasó de `const val` a
  textos leídos cada vez.

Para el código **sin `Context`** (funciones que también se prueban en la JVM) está `ui/Textos.kt`:
`Textos.t(R.string.x, "español de respaldo", args…)` da el texto del idioma puesto y, sin aplicación (en
una prueba), el español de respaldo, así que las pruebas que miran textos en español siguen pasando.

### Lo que queda en español

Archivos con textos de interfaz escritos en el código (recuento aproximado de líneas con un literal en
`Text(…)`, avisos, `contentDescription`, títulos…; 696 en 95 archivos). Con 5 o más:

| Archivo | Líneas |
|---|---|
| `horario/PanelDelHorario.kt` | 55 |
| `sincro/SincronizarActivity.kt` | 49 |
| `lecciones/LeccionesActivity.kt` | 37 |
| `tabla/TablaActivity.kt` | 35 |
| `ui/VisorHtmlActivity.kt` | 30 |
| `motor/DrawEditorActivity.kt` | 25 |
| `ui/MarkdownEditorActivity.kt` | 21 |
| `sincro/RecibirActivity.kt` | 21 |
| `mini/MiniActivity.kt` | 21 |
| `asistente/TarjetasDelAsistente.kt` | 19 |
| `sincro/ChatCompartidoActivity.kt` | 18 |
| `sincro/EnviarActivity.kt` | 16 |
| `lecciones/LeccionActivity.kt` | 15 |
| `guardados/CarpetaActivity.kt` | 15 |
| `pdf/LectorPdfActivity.kt` | 14 |
| `ui/Multilienzo.kt` | 13 |
| `ui/GaleriaDeCapturasActivity.kt` | 13 |
| `ui/ComentariosDeNota.kt` | 13 |
| `sincro/CopiasActivity.kt` | 13 |
| `pin/GrabadoraActivity.kt` | 12 |
| `privacidad/PrivacidadYDatos.kt` | 11 |
| `privacidad/ReportarIa.kt` | 9 |
| `lecciones/AdjuntosDeLaLeccion.kt` | 9 |
| `guardados/LetraEnPantalla.kt` | 9 |
| `ui/HojaDeCompartir.kt` | 8 |
| `atajos/BuscarActivity.kt` | 8 |
| `ui/MandosDeLector.kt` | 7 |
| `ocr/MotorOcrCard.kt` | 7 |
| `motormd/BarraDeFormatoUi.kt` | 7 |
| `jev/VistasDeJev.kt` | 7 |
| `guardados/CancionNuevaActivity.kt` | 7 |
| `asistente/web/LaWebDelAsistente.kt` | 7 |
| `ui/VistaDeNotas.kt` | 6 |
| `pin/PinWindowController.kt` | 6 |
| `ocr/MiVocabularioCard.kt` | 6 |
| `sincro/CamaraDelPcActivity.kt` | 5 |
| `motor/VentanaDeAjustes.kt` | 5 |
| `motor/PonerseAlDia.kt` | 5 |
| `mini/TareaRapidaActivity.kt` | 5 |
| `guardados/RepasarActivity.kt` | 5 |
| `guardados/EstudiarClaseActivity.kt` | 5 |
| `guardados/DictarAlChat.kt` | 5 |
| `excel/ExcelActivity.kt` | 5 |

Con menos: `ui/ElegirParaLaNota.kt` (4), `guardados/GuardarDePasoActivity.kt` (4), `ui/EscribirEnElLienzo.kt` (3), `ui/AbrirCon.kt` (3), `pin/LlamadaSecretaActivity.kt` (3), `motor/NotaAdhesiva.kt` (3), `guardados/BarraDelReproductor.kt` (3), `guardados/AbrirConPixPinActivity.kt` (3), `croquis3d/PanelDelModelo.kt` (3), `ui/RielDelVisor.kt` (2), `ui/RielDeMarcas.kt` (2), `ui/PestanaDeFunciones.kt` (2), `ui/CapaDeMarcos.kt` (2), `ui/BarraDePaso.kt` (2), `sincro/Diario.kt` (2), `planos/Visor3DActivity.kt` (2), `ocr/CopiarTexto.kt` (2), `motormd/RejillaDeTabla.kt` (2), `motor/PaletaDeColores.kt` (2), `motor/DrawFiguras.kt` (2), `motor/BotonFlotante.kt` (2), `guardados/VistaDeImportantes.kt` (2), `guardados/ConversacionActivity.kt` (2), `floating/FloatingBallController.kt` (2), `ui/EditorAvanzadoDeVisor.kt` (1), `ui/CompartirEnlaceActivity.kt` (1), `planos/PlanoActivity.kt` (1), `pin/AlarmaExacta.kt` (1), `pdf/SublienzosDelPdf.kt` (1), `office/AbrirOffice.kt` (1), `ocr/indra/CanalIndra.kt` (1), `motormd/ExportarDocx.kt` (1), `motormd/EditorVivo.kt` (1), `motor/VistaPreviaDeFiguraUi.kt` (1), `motor/VentanaDeReferencia.kt` (1), `mini/ImagenesDeTareas.kt` (1), `ia/Lienzo.kt` (1), `guardados/VisorDeVideoActivity.kt` (1), `guardados/TarjetaDeMusica.kt` (1), `guardados/SoltarActivity.kt` (1), `guardados/PrepararTemas.kt` (1), `guardados/PasarATextoService.kt` (1), `guardados/NuevoProyectoCompartidoActivity.kt` (1), `guardados/Imprimir.kt` (1), `guardados/GuardarCompartidoActivity.kt` (1), `guardados/FichaDelChat.kt` (1), `guardados/CompartirCarpeta.kt` (1), `guardados/ChatEntrePersonas.kt` (1), `croquis3d/Croquis3DActivity.kt` (1), `capture/Export.kt` (1), `capture/CaptureActivity.kt` (1), `atajos/AtajoActivity.kt` (1).

Además quedan en español: los títulos y explicaciones de `ajustes/CatalogoDeAjustes.kt` (solo los usa el
buscador de Ajustes), `sincro/Invitacion.kt`, los textos del motor de dibujo y del asistente que se
construyen fuera de Compose, `Tareas.textoDeHora` y las fechas escritas a mano, y el botón «Sincronizar»
de `MainActivity` (otro cambio lo quita). Para encontrar más:

```
grep -rnE '(Text|makeText\([^,]*,|contentDescription =)\(?\s*"[^"]*[a-záéíóúñ]{3,}' --include=*.kt app/src/main/java
```

## Cómo se añade un texto

1. En un composable, nunca `Text("…")`: `Text(stringResource(R.string.mi_texto))`. Fuera de Compose,
   `getString(R.string.mi_texto)` o, sin `Context`, `Textos.t(R.string.mi_texto, "Mi texto")`.
2. Añadir la clave **a la vez** en `values/…xml` (español), en `values-en/` y en cada `values-XX/` (el
   mismo archivo), con los mismos huecos (`%1$s`, `%2$d`) y, en plurales, las formas de cada idioma
   (tabla de arriba).
3. Escapar `'` como `\'`, `&` como `&amp;` y `<` como `&lt;`. Comillas: «…» en español, “…” en inglés, las de cada idioma en el suyo (「…」 en japonés, “…” en chino…).
4. Pasar `testDebugUnitTest`: `TraduccionesTest` dice qué falta.
