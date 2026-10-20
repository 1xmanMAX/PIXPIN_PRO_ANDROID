# Plan: lo del PC del 18 al 20-oct en Android (y lo que quedó del 18-oct)

Repo del PC revisado hasta `d891ca4` (20-oct-2026), desde `cf46430` (ya planificado en
`plan-pc-18-oct.md`, pasos 1-5 hechos). Rama: `pc-20-oct` (sale de `pc-18-oct`). Sin agentes: un
paso detrás de otro, cada uno con sus pruebas y su commit. Manual del PC que manda:
`docs/sincronizar-marcos-vistas-y-tinta.md` de ese repo.

Lo que pidió el usuario (20-oct): «los frames» (marcos numerados), barra de arriba y barra lateral
en los visores como en los pines del PC (en el lateral: anotar, marcadores y marcos), todo
sincronizable, y mejorar el audio.

## Pasos

1. **Lo que viaja** (sin Android): `.marcos.json` y `.vistas.json` en `TERMINACIONES`;
   `motor/MarcosDoc.kt` = `marcos_doc.rs` (lista, modo, arrastrar > 8 px, quitar el último, borrar,
   fichas, `px_para_llenar`, vistas `zoom x y`), guardar por temporal, `[]`/`{}` en vez de borrar,
   no reescribir si no cambia. Pruebas con los JSON del PC y por la sincronización de verdad.
2. **La interfaz común**: `ui/BarraDeVisor.kt` (barra de arriba: tipo, nombre, botones propios,
   luna/sol, pantalla completa, menú ⋮ sin repetidos, cerrar) y `ui/RielDeVisor.kt` (riel a la
   derecha arriba: marcador, lápiz, marco; pulsado = azul `#0060DF` con icono blanco; toque largo =
   nombre), `ui/CapaDeMarcos.kt` (raya azul `#0078D4`, número en su cuadrito, fichas abajo a la
   izquierda con la pista; arrastrar pone uno) e imprimir en grupo (una hoja por marco).
3. **PDF y Office maquetado** (`LectorPdfActivity`): riel + marcos en unidades del lector
   (1400 por hoja, hueco 18), imprimir los marcos, marcadores con vista (zoom y sitio).
4. **Word/EPUB/HTML** (`VisorHtmlActivity`): lo mismo en píxeles CSS; tinta de html/md sin la regla
   de columnas del Word.
5. **Excel** (`ExcelActivity`): riel, tinta por hoja `anot-<uid>-h<i>` (px al 100 %), marcadores
   con vista y marcos por hoja.
6. **Lienzo 2D**: abre en solo mirar si tiene algo, el lápiz de la barra pasa a editar; riel con
   marcador, lápiz y marco; marcos y fichas.
7. **Pines de imagen** (`PinWindowController`): riel con lápiz y marco; marcos en px de la imagen.
8. **Planos y modelos**: `.vistas.json` de planos (2D: centro y tamaño) y modelos (`Estado3d`),
   marcos 3D, tinta de cada marcador 3D (`-m<id>`), riel y fichas.
9. **Audio**: realce al reproducir (`volumen.rs`): pico del 99,9 %, hasta ×16, limitador tanh, copia
   WAV en caché; en todos los reproductores.
10. **Tareas** (paso 6 del 18-oct, WIP `98cd1ffa`) + lo nuevo: urgencia en tres niveles, etiquetas,
    fecha intuitiva, Kanban, horario semanal con asignaturas (cada una un grupo de tareas).
11. **Notas y visores** (paso 7 del 18-oct): fotos en el sitio del cursor, tablas del Markdown
    editables en la celda, enlaces pulsables en la caja del chat.
12. **Asistente** (paso 8 del 18-oct) + geometría exacta del asistente (`0dd8a45`).
13. **Calcar** (paso 9 del 18-oct + calcar agéntico del 18/19-oct), lo que encaje.
14. **Google Drive y Calendar** (paso 10 + `0de0eef`), si cabe.

15. **Lo del PC del 20-oct (noche), según vaya llegando** (revisión cada 30 min): `6cddabe` el
    `.pixpin` lleva todo lo anotado (un archivo suelto con código propio y su tinta, marcos, vistas y
    marcadores de planos/3D; «crear como nuevo» renombra lo anotado), `1984938` lo editable es el
    `.pixpin` (Android ya no ofrecía el `.excalidraw` suelto). Pendiente de que lo suba: la extensión
    `.excalidraw` → `.pixpin` en todo y los enlaces `pixpin://` para que otras apps llamen a PixPin.
    Llegó también `01ef092` (claves estables GlobalId de los marcadores BIM) y `7048d8e` (.doc/.ppt
    antiguos leídos en nombre del original: en Android el .so ya los abre con el uid del mensaje).

Al final: suite completa, relectura del diff, `SincronizarDeVerdadTest` y `SincronizarConElPcTest`
varias veces, versión nueva, push a main.

16. **Lo del PC del 20-oct (mañana)**, `4a87067`: (a) la página de notas nueva (buscador, tablas con su ancho)
    y **la maqueta de la tinta** (la medida de la columna con que se anotó, en las vistas, que viajan; con
    otra medida la tinta se esconde hasta el marcador ✏️ o el lápiz); (b) **Claude con búsqueda y lectura
    web de servidor** (`web_search`, `web_fetch`; si la cuenta no las tiene, se sigue sin ellas); (c) los
    **enlaces `pixpin://<verbo>?…` y `pixpinmax://`** del PC (`pixpin-shell/orden.rs`, desde el 15-oct) en el
    teléfono: el mismo lector de enlaces (compilado) y su seguridad, y sus pedidos con lo del teléfono.
    Solo de Windows: el globo que tiraba la app al cerrar un pin, `--sin-chat`, el encargo de fuera en el pin.
    **Hecho**: (a) `assets/lector-md.html` de `4a87067` y la maqueta en `VisorDeNotaActivity` (el guion nuevo del PC,
    `Maqueta`, ✏️, la clave `maqueta` no se poda); (b) `Nube.Claude.herramientasWeb`, sus bloques intactos y
    `Nube.webApagada`; (c) `orden.rs` del PC compilado (`EnlacesNativos`), `atajos/EnlaceActivity` (pixpin:// y
    pixpinmax://, pregunta lo que no es inofensivo) y `EnlacesDelTelefono` (cada pedido a su fila de la tabla).
    Pruebas: `NubeTest`, `VisorDeNotaTest`, `EnlacesTest`, y las de `orden.rs` del PC en Rust (13/17: las 4 que no
    pasan son de la consola de Windows con rutas `C:\`).

## Fuera (solo Windows)

WebView2 (carpetas, precalentado, cierres), teclas (I, Supr, Ctrl+P, G, F11) —en Android los
botones—, «siempre encima», Flow, Microsoft Print to PDF.

## Estado

(se rellena al acabar cada paso)

- **1 hecho**: `motor/MarcosDoc.kt` (MarcosDoc, VistasDoc, GuardadoDoc), terminaciones `.marcos.json` y
  `.vistas.json`, `-h<i>` y `-m<id>`. Pruebas: `MarcosDocTest`, `SincronizarDeVerdadTest` (marcos, vistas y
  tinta de Excel viajan; vaciados quedan `[]`).
- **2 hecho**: `ui/BarraDeVisor.kt` (barra, riel, `botonesDelRiel`, pantalla completa), `ui/CapaDeMarcos.kt`
  (marcos, fichas, dos dedos mueven), `Imprimir.fotos`, `ui/TintaSobreVisor.kt`.
- **3 hecho** PDF/Office: unidades del PC (`motor/HojasDoc.kt`), marcos, fotos por hoja con su tinta,
  marcadores con vista, ir a página para el asistente.
- **4 hecho** Word/EPUB/HTML: marcos en px CSS, marcadores (también en HTML) con vista, barra con ⋮ y
  «Cambiar el nombre» (ya no al tocar). Imprimir un marco más alto que la pantalla sale cortado.
- **5 hecho** Excel: riel, marcadores (x = columna, y = hoja·10⁷ + fila) con vista, marcos por hoja, tinta
  por hoja (`anot-<uid>-h<i>`), imprimir en claro.
- **6 hecho** lienzo: riel (marcador, lápiz = mirar/editar, marco = herramienta Marco), fichas de las hojas.
- **7 hecho**: pines de imagen con marcos numerados (en píxeles de la foto, `<imagen>.pixpin-marcos` como el PC),
  botón en la barra del pin, fichas, quitar el último, borrar, imprimir en grupo con lo anotado
  (`ImprimirFotosActivity`, el diálogo de impresión necesita una actividad).
- **8 hecho** planos y 3D: riel, marcadores 2D/3D con vista (`MarcadoresCad.kt`, comparten fichero),
  marcos 3D e imprimirlos, tinta por marcador 3D; claves estables GlobalId (PX3D v4, `libpixpincad.so`
  recompilado). Pruebas: `MarcadoresCadTest`.
- **9 hecho** audio: `audio/Volumen.kt` (`VolumenTest`, casos del PC) + `AudioAlto` (LoudnessEnhancer).
- **10 hecho** tareas: `mini/Frase.kt` = `pixpin-frase/tarea.rs` (`FraseTest`), fechas nuevas (`FechasTest`),
  horario semanal (`Semana.kt`, `HorarioSemanal.kt`, `SemanaTest`), asignaturas = listas del chat
  «Asignaturas» (`Asignaturas.kt`), reloj y libro en la caja de Tareas, chapitas con +N que buscan.
- **11 hecho**: foto en el cursor de la nota, enlaces pulsables en el chat y **el riel en las notas**
  (`ui/VisorDeNotaActivity`): la página de notas del PC (`assets/lector-md.html`, `herramientas/traer-lector-md.sh`)
  en modo lectura con marcador (fracción + vista exacta), tinta y marcos **dentro de la página** con el guion del
  riel del PC (`capa_web::INSTALAR`), en `anot-<código>` de la nota (viaja). Se entra desde el editor: «Leer y anotar».
- **12 en parte**: complejidad, conversación nueva, pastilla del modelo, contexto 40×4000, hora al minuto,
  sin lectura automática; 18 herramientas nuevas (`EnElChat.kt`, `AccionesDelTelefono`), geometría exacta
  (`GeometriaPrecisa.kt`, `GeometriaPrecisaTest`); catálogo y selección del PC de hoy por el oráculo.
  Después (20-oct): ver_lienzo y editar el lienzo (`Edicion.kt`, `Ojos.kt`), funciones, ecuación, cronograma,
  física, problema en limpio (iguales que el PC por el oráculo), calcar con rayas finas y texto (ML Kit),
  documentos (anotar, buscar dentro, exportar), **anotar_inteligente** (`ia/Anotacion.kt` = `pixpin_ia::anotacion`,
  `asistente/AnotarInteligente.kt`: PDF, Word, PowerPoint, foto, plano y Excel en sus capas), `revision_tabla`,
  tabla_de_imagen con OCR, acciones del PC (capturas, sincronizar, enviar al grupo, automatizaciones, soltar).
  **hoja_a_lienzo**: el código del PC compilado (`herramientas/pixpin-ia-android` → `libpixpinia.so`: `pixpin_ia::hoja`
  y `hoja_a_lienzo.rs` con su planificador y supervisor, traídos sin tocar por `traer-del-pc.py`); Kotlin pone el puente
  (`asistente/HojaAlLienzo.kt`: leer, OCR de ML Kit, nube, cada zona al lienzo y pintar para comprobar). Calcar una
  lámina con la nube la pasa como hoja, como el PC.
- **12, tabla de una foto como el PC (16-oct, `tabla_aspecto`)**: `HojaNativa.tabla` (la rejilla medida corrige las
  celdas; fondos y anchos si cuadra) y `Xlsx.escribir(…, fondos, anchos)` como `escribir_excel_con` (ancho px/7).
  Con destino lienzo va la tabla del chat sin aspecto, igual que el PC (`tabla_nueva` solo lleva filas). `XlsxConAspectoTest`.
- **13 hecho por la biblioteca nativa**: el calcar agéntico del PC (`plan.rs`, `supervisor.rs`, `hoja_a_lienzo.rs`
  hasta `49eb7ac`) corre tal cual en `libpixpinia.so`; calcar una lámina se desvía a ella (`HojaNativa.lamina`).
  Lo que es solo trazado a mano (calcar sin nube) sigue el de Kotlin. Y `al_lienzo` (fotos y texto a un lienzo
  por su nombre, apilados: `motor/PilaDelLienzo.kt` = PC `pila_lienzo.rs`), que faltaba en los enlaces.
- **14 hecho**: Google Drive y Calendar con `pixpin_ia::conexiones` compilado en `libpixpinia.so` (OAuth con PKCE y
  vuelta a 127.0.0.1, guardado cifrado con la Keystore en `files/claves`, red por `HttpURLConnection`): las cinco
  herramientas, Ajustes → Asistente → Conexiones, el caché de Calendar cada 15 min (`calendario-google.json`, la forma del
  PC), sus eventos en la rejilla del horario y sus avisos con la llamada. `ConexionesTest`.
- **15 en parte**: `.pixpin` de un archivo suelto con todo lo anotado (`ChatQueViaja.conLoSuelto`,
  `LoSueltoTest`), claves estables BIM. **`8d0cc90` (PC, 20-oct)**: la etiqueta de un `.doc`/`.ppt` convertido;
  en el teléfono la barra ya dice «Word»/«PowerPoint» por el tipo, no por la copia: nada que cambiar.
  **La extensión `.pixpin` en vez del `.excalidraw`** (lo que pidió el usuario): en el PC es `1984938`+`841c32f`
  (compartir solo ofrece el `.pixpin`, el de un lienzo con el lienzo como su hoja, copiado de `EnviarActivity.deLienzo`
  del móvil). El teléfono ya lo hacía así; quitada la última cadena «Compartir como .excalidraw» (sin usar). Los
  `.excalidraw` siguen abriéndose y el almacén interno sigue igual (el PC tampoco lo cambió).
