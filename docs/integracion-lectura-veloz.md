# Integración con Lectura Veloz (protocolo v1)

Lectura Veloz (`com.lecturaveloz.app`) es la aplicación de lectura rápida del usuario. PixPin le
manda un documento con su posición y Lectura Veloz devuelve, en segundo plano, por dónde va la
lectura: el **marcador verde** (🟢) del documento se mueve en PixPin sin abrir nada.

Código: `app/src/main/java/com/forge/pixpin/integraciones/` (`LecturaVeloz.kt`,
`LecturaVelozReceiver.kt`, `ProtocoloLecturaVeloz.kt`).

## 1. PixPin → Lectura Veloz

Botón **«Lectura veloz»** (rayo) en la pastilla de arriba del lector de hojas (`LectorPdfActivity`:
PDF, Word y PowerPoint maquetados) y en la barra del visor de texto (`VisorHtmlActivity`: Word,
EPUB, PDF como texto).

Se lanza un `Intent.ACTION_VIEW` explícito (`setPackage("com.lecturaveloz.app")`) con
`FLAG_GRANT_READ_URI_PERMISSION | FLAG_ACTIVITY_NEW_TASK`, el archivo también en `clipData`
(`ClipData.newRawUri`) y esta dirección (cada valor codificado con `Uri.Builder.appendQueryParameter`):

```
lecturaveloz://pixpin/abrir?v=1&doc=<uid>&nombre=<nombre>&uri=<contentUri>&tipo=<tipo>&pagina=<pagina>&frac=<frac>&fraccion=<fraccion>&paginas=<numPaginas>&t=<t>
```

| Parámetro | Tipo | Significado |
|---|---|---|
| `v` | int | Versión del protocolo: `1`. |
| `doc` | texto | Código del documento: el código único del mensaje del chat (`Codigos.unico`), el del proyecto si el PDF es de un proyecto, o uno sacado de la ruta si el archivo no es del chat. Diez letras o cifras. Hay que devolverlo tal cual. |
| `nombre` | texto | Nombre del archivo, con extensión. |
| `uri` | texto | `content://com.forge.pixpin.fileprovider/…` del archivo, con permiso de lectura. |
| `tipo` | texto | `pdf` (lector de hojas) o `texto` (visor de texto). |
| `pagina` | int | Hoja (desde 0). Solo `pdf`; en `texto`, 0. |
| `frac` | float 0..1 | Altura dentro de esa hoja. Solo `pdf`; en `texto`, 0. |
| `fraccion` | float 0..1 | Posición en el documento entero. `pdf`: `(pagina + frac) / paginas`. |
| `paginas` | int | Número de hojas (`pdf`); 0 en `texto`. |
| `t` | long | Milisegundos (época) del último cambio del verde: la fecha del archivo donde vive o, si lo último que lo movió fue un aviso de Lectura Veloz y nadie lo ha tocado después, el `t` de ese aviso. 0 si no se sabe. |

La posición es la del **verde** si el documento lo tiene; si no, lo que se ve arriba de la
pantalla. Si Lectura Veloz no está instalada, PixPin avisa («Instala Lectura Veloz para usar la
lectura rápida») y abre <https://github.com/1xmanMAX/Speed-Reading-Tool/releases>.

Además, si el mensaje del chat no tenía etiqueta, se le pone **⚡** (filtrable en el chat; una
etiqueta puesta a mano no se toca).

PixPin apunta en sus preferencias (`lectura_veloz`) a dónde va el verde de cada `doc|tipo`
mandado. **Solo se aceptan avisos de documentos que PixPin mandó antes.**

## 2. Lectura Veloz → PixPin

Difusión **explícita** (`setPackage("com.forge.pixpin")`) con la acción
`com.forge.pixpin.LECTURA_VELOZ`. La recibe `LecturaVelozReceiver` (exportado), en segundo plano
(`goAsync` y un hilo para el disco).

| Extra | Tipo | Significado |
|---|---|---|
| `doc` | String | El `doc` que mandó PixPin. |
| `tipo` | String | `pdf` o `texto`, el que mandó PixPin. |
| `pagina` | Int | Hoja (desde 0), para `pdf`. |
| `frac` | Float 0..1 | Altura en la hoja, para `pdf`. |
| `fraccion` | Float 0..1 | Posición en el documento entero (la usa `texto` y los Word/PowerPoint de hojas). |
| `t` | Long | Milisegundos (época) del cambio en Lectura Veloz. |
| `palabra` | Int | Índice de la palabra (si falta `fraccion`, se usa `palabra/total`). |
| `total` | Int | Palabras del documento. |

Se aceptan también como texto o como otro número (se convierten). Lo que no valga se ignora:
`doc` que no sea de diez letras o cifras, `tipo` distinto, un documento que PixPin no mandó.
Los números se acotan (0..1, hojas ≥ 0).

Qué se escribe (siempre por un temporal y renombrando, como lo demás de lo anotado):

- **PDF de hojas** (`pdf`): en `files/pins/draw/anot-<uid>.marcas` (o las preferencias `marcas`,
  clave `pdf:<ruta>`, si el PDF no es del chat) se quita el 🟢 y se pone uno nuevo en
  `y = pagina + frac`. Los demás marcadores se quedan.
- **Word o PowerPoint en hojas** (`pdf`): en su `anot-<uid>.marcas` en fracciones
  (`id:fraccion:emoji`), el 🟢 pasa a `fraccion`.
- **Texto** (`texto`): el verde de la voz, `anot-<base>.voz` = `-1:<fraccion>` (−1: «sin párrafo,
  manda la fracción»; el visor busca el párrafo que cae ahí), y `anot-<base>.sitio` = `fraccion`.
  `<base>` es `anot-<uid>` o, en un PDF leído como texto, `anot-<uid>-texto`.

Si `t` es anterior al último cambio del verde en PixPin (el mismo valor que PixPin manda como
`t`), el aviso se ignora: lo de PixPin es más nuevo, o es un aviso atrasado. `t = 0` siempre se
aplica. Tras aplicar un aviso, PixPin apunta su `t` y la fecha con que quedó el archivo; mientras el
archivo no cambie, el `t` del verde es el de Lectura Veloz.

Los visores abiertos releen los marcadores al volver a la pantalla (`onResume`): al pasar de
Lectura Veloz a PixPin el verde ya está en su sitio.

## 3. Probar con adb

1. Con Lectura Veloz instalada, abre en PixPin un documento del chat y toca **Lectura veloz**: así
   queda apuntado (sin ella instalada no se apunta nada y el aviso se ignora).
2. Saca el `doc` de la dirección que recibe Lectura Veloz, o del registro:

   ```sh
   adb shell run-as com.forge.pixpin cat shared_prefs/lectura_veloz.xml
   ```

3. Manda el aviso:

   ```sh
   adb shell am broadcast -a com.forge.pixpin.LECTURA_VELOZ -p com.forge.pixpin \
     --es doc <uid> --es tipo pdf --ei pagina 3 --ef frac 0.5 --ef fraccion 0.3 --el t $(date +%s000)
   ```

   Para el visor de texto: `--es tipo texto --ef fraccion 0.42`.
4. `adb logcat -s LecturaVeloz` dice si se movió el verde o por qué no. Vuelve a PixPin: el 🟢
   está en la hoja 4, a media altura.

Para ver qué se manda a Lectura Veloz: `adb logcat | grep lecturaveloz://` con
`ActivityTaskManager` (línea `START u0 {act=android.intent.action.VIEW dat=lecturaveloz://…}`).
