# El formato `.pixpin`

Un `.pixpin` es **un ZIP normal** con un proyecto entero dentro: lienzos, croquis del espacio,
notas, fotos y PDF. Se puede abrir con cualquier biblioteca de ZIP y no lleva nada cifrado ni
binario propio. Es el formato con el que un proyecto se pasa de un aparato a otro —o a la
versión de escritorio— y se sigue editando allí.

## Entradas

| Entrada                      | Qué es |
|------------------------------|--------|
| `manifest.json`              | `{"formato":"pixpin","version":1,"aplicacion":"pixpin-android","escrito":<ms>,"proyecto":"<nombre>"}` |
| `proyecto.json`              | El proyecto (ver abajo). Sin rutas del aparato: `pdfOrigen` y `pdfLimpio` van a `null`. |
| `lienzos/<id>.excalidraw`    | Un lienzo por hoja con dibujo, en JSON de Excalidraw (`type: "excalidraw"`). En `files[<id>].path` va `imagenes/<id>`, no una ruta. |
| `imagenes/<id>`              | Cada foto de los lienzos tal cual (JPEG, PNG, WEBP…); el `mimeType` está en `files` del lienzo. |
| `croquis/<id>.json`          | Cada croquis del espacio, en el JSON de `Croquis` (kotlinx.serialization). |
| `notas/<id-de-hoja>.md`      | Cada nota, en Markdown. También va inline en `proyecto.json` (`hojas[].nota`). |
| `tablas/<id>.json`           | Cada tabla con fórmulas (`hojas[].tabla` es su id). `{"nombre","celdas":{"B7":"=SUMA(B1:B6)"},"anchos":{"B":140},"estilos":{"A1":{"n":true,"a":"c","f":"#fff3bf"}},"tocado"}`. Se guarda lo tecleado, nunca el resultado. |
| `documento.pdf`              | El PDF del proyecto, si lo tiene: el limpio (sin anotar). |
| `chat/mensajes.jsonl`        | Opcional: los mensajes de su chat, uno por línea, con sus archivos en `chat/adjuntos/<clave>` (rutas `adjunto:<clave>`; el PDF del proyecto, `documento:`). |
| `guardados.jsonl`            | Opcional (28-oct-2026, el `.pixpin` de un cuaderno): el mismo chat como lo lee el PC, con las rutas relativas a la carpeta del chat (`chat/adjuntos/<clave>`, `documento.pdf`, `lienzos/<dibujo>.excalidraw`) y sin `proyecto`. El PC copia el paquete a la carpeta del chat y, si trae este fichero, es su chat (`almacen::importar_paquete`): así la carpeta del cuaderno llega con sus páginas. El teléfono lo ignora y lee `chat/mensajes.jsonl`. |

## `proyecto.json`

```json
{
  "id": "pr-1725500000000",
  "nombre": "Casa Rosales",
  "hojas": [
    {"id": "h1", "nombre": "Planta", "dibujo": "dib-1725500001", "pagina": 0},
    {"id": "h2", "nombre": "Notas", "nota": "# Obra\n…"},
    {"id": "h3", "nombre": "Maqueta", "croquis": "cr-1725500002"}
  ],
  "archivado": false,
  "tocado": 1725500000000,
  "pdfOrigen": null,
  "pdfLimpio": null,
  "croquis": ["cr-1725500002"]
}
```

- `hojas[].dibujo` es el id del lienzo → `lienzos/<dibujo>.excalidraw`.
- `hojas[].pagina` es la página (desde 0) de `documento.pdf` sobre la que se dibujó ese lienzo.
- `hojas[].croquis` es el id de un croquis → `croquis/<croquis>.json`; `hojas[].vista`, el nombre
  de una vista guardada de ese croquis (una lámina del croquis).
- `croquis` es la lista de croquis del proyecto (también los que no tienen hoja).

## Al importar

Los ids se renombran con un sufijo (`<id>-<ms>`) para no pisar nada que ya haya en el aparato;
las fotos se copian y se les pone su ruta nueva en `files[].path`; el PDF se copia y pasa a ser
`pdfOrigen` y `pdfLimpio`. Lo hace `PaquetePixpin.importar`.

## Versión

`version` sube solo cuando cambia algo que un lector viejo no pueda ignorar. Las claves nuevas se
añaden sin subirla; los lectores tienen que ignorar claves desconocidas.
