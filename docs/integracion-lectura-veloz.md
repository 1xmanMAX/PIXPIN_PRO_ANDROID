# PixPin ↔ Lectura Veloz

Integración hecha el 27-oct-2026 en los dos lados. El contrato lo define el código de Lectura
Veloz (repo `1xmanMAX/Speed-Reading-Tool`, commit `cf7474f`):

- `web/js/app.js`, «Integración con PixPin Pro»: `pixpinParams`, `pixpinBytes`, `wordForPixpin`,
  `pixpinPosition`, `syncPixpin`, `openFromPixpin`.
- `src-tauri/src/lib.rs`: `deliver_pixpin` (el enlace de ida) y `pixpin_sync` (la vuelta en escritorio).
- `src-tauri/plugins/tauri-plugin-lv-tts/android/.../TtsPlugin.kt`: `copyUri` (copia el
  `content://` de PixPin) y `notifyPixpin` (la vuelta en Android).
- `src-tauri/tauri.conf.json`: enlace `lecturaveloz://` con host `pixpin`.

En PixPin: `guardados/LecturaVeloz.kt` (lo que no necesita Android: enlace, conversiones,
validación, quién gana) y `guardados/LecturaVelozEnElTelefono.kt` (abrir, el receptor, dónde se
guarda). Pruebas: `LecturaVelozTest` y `LecturaVelozEnElTelefonoTest`.

Funciona como **Escáner Pro Max** (`guardados/EscanerProMax.kt`): un botón en PixPin abre la otra
app directamente, sin exportar nada a mano.

## Lo que ve el usuario

1. **«Lectura veloz»**, una opción discreta:
   - en el menú del mensaje (mantener pulsado) de un archivo que Lectura Veloz lee (PDF, Word,
     EPUB, HTML, Markdown, TXT, ODT, RTF, FB2, MOBI, PowerPoint, Excel, CSV);
   - en el engranaje del visor de PDF y Office (apartado «Lectura»);
   - en el engranaje del lector de Word, libros y PDF como texto (apartado «Lectura»);
   - en el ⋮ de la nota en Markdown.

   Al tocarla, Lectura Veloz se abre **con el documento ya cargado** (el archivo original; ella lo
   lee) y **en el marcador verde de la voz** de PixPin. Si la app no está instalada, el chat
   enseña un cuadro como el del escáner («Falta «Lectura Veloz»»); los visores, un aviso corto. Si
   está pero es de antes de esta integración, se pide ponerla al día.
2. **El marcador va y vuelve, solo.** Mientras se lee en Lectura Veloz, ella manda su punto cada
   15 s como mucho (y siempre al pausar, cerrar o abrir); PixPin mueve su marcador verde. Al
   volver a un visor abierto, el verde ya está ahí (se vuelve a leer al volver al frente). Gana
   siempre **la posición más reciente**: si después se escucha en PixPin y se abre otra vez
   Lectura Veloz, ella salta a ese punto nuevo.
3. **Una chapa discreta**: «Lectura veloz · 47 %» debajo del nombre del archivo en la burbuja
   (gris, más pequeña que «Escaneado»; tocarla sigue leyendo allí), y el porcentaje a la derecha
   de la opción en el engranaje o en el ⋮. El porcentaje es lo último que mandó Lectura Veloz.

Solo para **adjuntos del chat y notas**: su código único es el que reconoce PixPin cuando vuelve
el punto. Un PDF de un proyecto o un archivo abierto de paso no la llevan.

## Ida: PixPin abre Lectura Veloz

```
Intent(ACTION_VIEW, Uri.parse(
  "lecturaveloz://pixpin/abrir?doc=<código>&nombre=<nombre.ext>&uri=<content://…>&tipo=pdf|texto" +
  "&pagina=<0..>&frac=<0..1>&fraccion=<0..1>&paginas=<n>&t=<ms>"))
  .setPackage("com.lecturaveloz.app")
  .addFlags(FLAG_GRANT_READ_URI_PERMISSION)
  .setClipData(ClipData.newRawUri(nombre, uri))
// y además: grantUriPermission("com.lecturaveloz.app", uri, FLAG_GRANT_READ_URI_PERMISSION)
```

| Parámetro | Qué es |
|---|---|
| `doc` | `Codigos.unico(mensaje)` (10 signos, el mismo en todos los aparatos); en una nota, el de su hoja o su mensaje. Ella deduplica por él: un documento, un libro. |
| `nombre` | El nombre del mensaje **con la extensión del archivo de verdad** (`LecturaVeloz.nombreConExtension`): su importador elige el formato por ella. |
| `uri` | `content://com.forge.pixpin.fileprovider/…` del archivo original (`AbrirCon.uriDe`; `files/guardados/` está en `file_paths.xml`). Una nota se escribe antes como `.md` en `cache/share/lectura-veloz/<código>/`. Ella lo copia con `openInputStream` (`copyUri`): va dentro del enlace y no en los datos del intent, así que el permiso se da con `grantUriPermission` además del `ClipData`. |
| `tipo` | `pdf` para un PDF, `texto` para todo lo demás. |
| `pagina`, `frac` | Hoja (desde 0) y por dónde va en ella (0..1). Solo cuentan en un PDF. |
| `fraccion` | Fracción del documento (0..1). Ella la usa si no sabe de hojas. |
| `paginas` | Hojas del PDF (0 si no lo es). |
| `t` | Hora (ms) del marcador de PixPin que se manda; 0 si no hay. |

Todos los números se encajan (hojas ≥ 0, fracciones de 0 a 1, sin NaN) y se escriben con punto
decimal. Los textos van con `%20`, nunca con `+`.

Ella importa el archivo la primera vez y, las siguientes, reabre el mismo libro. Solo salta al
punto de PixPin si es nuevo o si `t` es más reciente que su propio progreso; si no, sigue donde iba
y devuelve su punto enseguida.

**PixPin detecta la app** con `<queries>`: `<package android:name="com.lecturaveloz.app"/>` y un
`<intent>` `VIEW` con `scheme="lecturaveloz"`. «Sabe abrir» = el enlace
`lecturaveloz://pixpin/abrir` resuelve en ese paquete; «instalada» = eso o que tenga icono.

## Vuelta: Lectura Veloz manda su punto (Android)

```
Intent("com.forge.pixpin.LECTURA_VELOZ").setPackage("com.forge.pixpin")
  extras: doc (String), tipo ("pdf"|"texto"), pagina (Int), frac (Float), fraccion (Float),
          t (Long, ms), palabra (Int), total (Int)
```

Lo recibe `LecturaVelozReceiver` (exportado, con esa acción en el manifiesto). Comprueba todo:

- `doc` de 1 a 80 letras/cifras ASCII, `-` o `_`; sin `t` > 0, nada. Un `doc` que PixPin no le
  dio (ni está en el chat) se ignora.
- Las fracciones se encajan de 0 a 1 (NaN → 0), la hoja ≥ 0, y una hora del futuro se queda en «ahora».
- **Gana lo más reciente**: un aviso con `t` igual o más viejo que el último que mandó ella se
  ignora; uno más viejo que el marcador de PixPin (la voz lo movió después) solo actualiza la
  chapa; si no, se escribe el marcador.

## El marcador de PixPin, sin formato nuevo

Se escribe donde PixPin ya guarda el marcador verde de la voz, así que viaja a los demás aparatos y
al PC tal cual:

- `pins/draw/anot-<uid>.voz` (Word, libro, HTML, Markdown, texto): `párrafo:fracción`.
- Un PDF: `anot-<uid>-texto.voz` (el de escucharlo como texto) **y** el 🟢 de
  `anot-<uid>.marcas` (el del lector de hojas, `y` = hoja + fracción de la hoja), que es desde
  donde empieza «Escuchar este PDF».

**Conversión** (`LecturaVeloz.vozALv` / `lvAVoz`, `marcaALv` / `lvAMarca`). El visor guarda en el
teléfono, cada vez que numera los párrafos para la voz, cuántas palabras tiene cada uno, dónde
empieza (la fracción del alto con que se apunta el verde) y de qué hoja es
(`files/lectura-veloz/<base>.mapa`, no viaja). Con ese mapa:

- PixPin → LV: `fraccion` = palabras antes del párrafo / palabras del documento (como cuenta ella);
  en un PDF, `pagina` = la hoja del párrafo y `frac` = su parte dentro de esa hoja.
- LV → PixPin: el párrafo en que cae su palabra (en un PDF, por hoja y parte de la hoja) y la
  fracción donde empieza, igual que lo apunta la voz.

Sin mapa (el documento nunca se abrió en el visor de este teléfono) se pasa por la fracción:
PixPin escribe `0:<fracción>`, y el visor —como ya hacía el PC, `parrafo_de_la_marca`— busca el
párrafo que empieza más cerca de esa fracción cuando el número no casa. Ahora el visor de Android
también lo hace (`Lectura.parrafoDeLaMarca`). Una versión vieja de PixPin en otro aparato empezaría
ese caso por el primer párrafo, pero el verde se dibuja en su sitio.

**La hora del marcador**: el `.voz` no lleva hora y no se le añade (el PC lo lee). PixPin guarda en
el teléfono (preferencias `lectura_veloz`, por código) lo último que mandó Lectura Veloz: su punto
exacto, su hora y lo que escribió con él. Si el verde sigue siendo ese, su hora es la de Lectura
Veloz y al reabrirla se le manda su propio punto (sin la ida y vuelta por párrafos); si no, la hora
es la de modificación del `.voz` (lo movió la voz o llegó sincronizando) o, para el 🟢 de un PDF, su
id (la hora en que se puso). No hay campos nuevos que se sincronicen.

## Lo que haría falta en el PC (Windows)

Lectura Veloz de escritorio ya manda su punto con un enlace (`pixpin_sync` en `lib.rs`), cada 60 s
como mucho y al pausar o cerrar:

```
pixpinmax://lectura-veloz?v=1&doc=<código>&tipo=pdf|texto&pagina=<n>&frac=<0..1>&fraccion=<0..1>&t=<ms>&ruta=<ruta del archivo>
```

Y espera que PixPin la abra con `lecturaveloz://pixpin/abrir?…&ruta=<ruta absoluta>` (en vez de
`uri`): ella autoriza leer solo ese archivo (`deliver_pixpin`). Para el PC queda:

1. Registrar el esquema `pixpinmax://` (si no lo está) y atender el host `lectura-veloz` sin
   pasar al frente: mismas comprobaciones y la misma regla de «gana lo más reciente» que aquí.
2. La opción «Lectura veloz» en sus visores y en el menú del mensaje, con `ruta=` y el mismo `doc`
   (`Codigos.unico`), así que un libro abierto desde el PC y desde el teléfono es el mismo código.
3. Escribir el mismo `anot-<uid>.voz` (y el 🟢 de un PDF), con su propio mapa de párrafos o por la
   fracción (`parrafo_de_la_marca` ya lo resuelve al leer).

## Lo que no se pudo comprobar sin aparato

Con las dos apps instaladas en un teléfono: que el permiso del `content://` le llega a Lectura
Veloz y lo copia; que el enlace abre su actividad (Tauri) también si ya estaba abierta; que el
aviso de vuelta llega con PixPin cerrado (receptor del manifiesto con `setPackage`); y que el
porcentaje y el verde se ven al volver.
