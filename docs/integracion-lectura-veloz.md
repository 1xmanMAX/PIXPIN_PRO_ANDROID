# PixPin ↔ Lectura Veloz en Android: lo que PixPin necesita

Propuesta del 27-oct-2026 para el repositorio `1xmanMAX/Speed-Reading-Tool`. El manual que ya
existe (`docs/PIXPIN.md`) cubre solo el atajo de Windows con el portapapeles. Lo que pidió el
usuario para el teléfono es otra cosa, y funciona como **Escáner Pro Max**
(`guardados/EscanerProMax.kt`): un botón en PixPin abre la otra app directamente en el punto
justo, sin exportar nada.

Cuando Lectura Veloz publique su manual de Android, ese manual manda. Este documento dice qué
necesita PixPin para que la integración quede completa.

## Lo que verá el usuario

1. **«Lectura veloz».** En el visor de PDF, Word, EPUB, HTML, Markdown y notas, y en el menú del
   mensaje, aparece la opción «Lectura veloz». Al tocarla, Lectura Veloz se abre con ese
   documento ya cargado y arranca **en el marcador de lectura en voz alta de PixPin**.
2. **El marcador va y vuelve.** Mientras se lee en Lectura Veloz, el punto se manda de vuelta a
   PixPin de forma continua (cada frase, o cada pocos segundos). Al volver a PixPin, el marcador
   de voz ya está donde se quedó. Al revés pasa lo mismo: si después se lee en voz alta en
   PixPin y se abre otra vez Lectura Veloz, arranca en el punto nuevo.
3. **Discreto.** El documento muestra una chapa pequeña «Leyendo en Lectura veloz · 42 %», como
   la de «Escaneado», y la ficha del chat puede tener una lista «Leyendo».

## El marcador de PixPin

- PixPin guarda el punto de lectura en voz alta como `"<párrafo>:<fracción>"`, por ejemplo
  `"37:0.42"`: el párrafo 37 del texto de lectura, al 42 % de ese párrafo. Ver
  `motor/Lectura.kt` (`vozATexto`/`vozDeTexto`).
- Se guarda junto al archivo (`anotaciones/<base>.voz`) y se sincroniza con el PC y los demás
  aparatos.
- Para no depender de cómo cada app parte el texto, PixPin manda **el texto ya partido en
  párrafos** (los mismos que usa su lector en voz alta) y el punto como índice de párrafo más
  desplazamiento en caracteres. Lectura Veloz solo tiene que convertir ese punto a su índice de
  palabra.

## Contrato propuesto (Android)

### PixPin → Lectura Veloz: abrir y leer

```
Intent("com.lecturaveloz.action.READ")
  .setPackage("com.lecturaveloz.app")
  .setDataAndType(content://…/documento.txt, "text/plain")   // FileProvider de PixPin, FLAG_GRANT_READ_URI_PERMISSION
  .putExtra("com.lecturaveloz.extra.TITLE", "Capítulo 3.pdf")
  .putExtra("com.lecturaveloz.extra.DOCUMENT_ID", "<uid del mensaje en PixPin>")   // estable entre aparatos
  .putExtra("com.lecturaveloz.extra.PARAGRAPH", 37)
  .putExtra("com.lecturaveloz.extra.CHAR_OFFSET", 512)       // dentro del párrafo
  .putExtra("com.lecturaveloz.extra.CALLER_LABEL", "PixPin")
  .putExtra("com.lecturaveloz.extra.CALLBACK", "com.forge.pixpin.action.POSICION_DE_LECTURA")
```

- **El archivo** es texto plano en UTF‑8, con los párrafos separados por una línea en blanco.
  PixPin ya sabe sacar el texto de PDF, Word, EPUB, HTML y Markdown, así que Lectura Veloz no
  tiene que leer esos formatos.
- **Equivalente por enlace** (Tauri deep-link, sin Kotlin propio):
  `lecturaveloz://open?uri=<content-uri>&id=<uid>&p=37&c=512&title=…&caller=pixpin`. Para eso
  hay que añadir el host `open` en `plugins.deep-link.mobile`.

### Lectura Veloz → PixPin: el punto, continuamente

```
Intent("com.forge.pixpin.action.POSICION_DE_LECTURA")
  .setPackage("com.forge.pixpin")
  .putExtra("com.lecturaveloz.extra.DOCUMENT_ID", "<el mismo uid>")
  .putExtra("com.lecturaveloz.extra.PARAGRAPH", 41)
  .putExtra("com.lecturaveloz.extra.CHAR_OFFSET", 0)
  .putExtra("com.lecturaveloz.extra.PERCENT", 47)            // para la chapa
  .putExtra("com.lecturaveloz.extra.STATE", "reading")       // reading | paused | finished | closed
```

- Se manda como **broadcast explícito** (`sendBroadcast`), a lo sumo una vez por frase o cada 2 s,
  y siempre al pausar o cerrar.
- **Equivalente por enlace:**
  `pixpin://lectura?id=<uid>&p=41&c=0&pct=47&estado=reading`. PixPin registrará el esquema
  `pixpin` si Lectura Veloz prefiere enlaces.
- PixPin guarda ese punto como su marcador de voz. Se sincroniza como cualquier anotación, así
  que también llega al PC.

## Lo que hace PixPin (pendiente de confirmar con el manual de Lectura Veloz)

- Botón «Lectura veloz» en los visores y en el menú del mensaje. Si la app no está instalada, un
  aviso con su enlace, como con el escáner.
- Recibir el punto de vuelta (receptor y esquema `pixpin://lectura`), validar que el `uid`
  exista, y guardar `párrafo:fracción`.
- La chapa «Leyendo en Lectura veloz · N %» en la burbuja y en el visor, que se va al terminar.
- Pruebas JVM de la petición, de la conversión punto ↔ `párrafo:fracción`, y de la respuesta.
