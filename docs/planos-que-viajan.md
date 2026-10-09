# Lo hecho sobre un plano DWG/DXF, en archivos que viajan al PC

*9-oct-2026 · PixPin Android v0.115 · guía para PixPin para Windows*

El usuario pidió que todo lo del visor de planos «sea sincronizable con la PC». En Android, sobre
un plano DWG o DXF del chat se puede **anotar** (texto y trazos con el motor del lienzo), **acotar**
(cadenas de medidas) y poner **marcos para imprimir**. Todo va en los archivos de siempre de lo
anotado sobre un adjunto, **sin terminaciones nuevas**: ya viajan en los dos sentidos
(`TERMINACIONES` de `crates/pixpin-sincro/src/anotado.rs`).

## Los archivos

Con `<uid>` = el código único del mensaje del plano (`Codigos.unico`, `kotlin::unico`), en `pins/draw/`:

| Archivo | Qué lleva |
|---|---|
| `anot-<uid>.excalidraw.gz` | La capa del plano: un dibujo normal del motor. Lo anotado, y **también las cotas y los marcos** (ver abajo). |
| `anot-<uid>.hoja` | **El marco de la tinta** (formato de siempre: `x0,y0,x1,y1` y `v1`): la caja del plano, **en unidades de la capa**. |

Un plano sin nada hecho no deja archivos.

## Dónde cae la capa: la `.hoja`

La `.hoja` es el rectángulo, en unidades de la capa, que ocupa **la caja del plano** (sus
coordenadas de verdad, mínimas y máximas, tal como salen del convertidor de `pixpin-cad`):

- `x0` ↔ x mínima del plano, `x1` ↔ x máxima;
- `y0` ↔ **y máxima** del plano, `y1` ↔ y mínima (la y de la capa baja; la del plano sube).

Quien lee lleva ese rectángulo a la caja del plano que ve él, y la capa cae en su sitio aunque el
centro del modelo o la escala de capa de cada aparato no coincidan. Fórmula (Android:
`CapaDelPlano.encajeDe`), con la caja del plano `[minX, minY, maxX, maxY]`:

```
u     = (maxX − minX) / (x1 − x0)          // unidades del plano por unidad de capa
xPlano = minX + (xCapa − x0) · u
yPlano = maxY − (yCapa − y0) · u
```

Android escribe la capa con `u` = la altura de letra más común del plano / 20: así el texto del
motor (20) sale del tamaño de los textos del plano y las rayas en proporción. El PC puede usar la
suya; con la `.hoja`, da igual.

## Las cotas y los marcos: figuras del motor con su grupo

Como las marcas de texto de los pines del PC («marca-texto»): dibujo normal, reconocido por su grupo.

- **Cota** (una cadena de medidas): una `line` con `groupIds = ["plano-cota"]`; sus `points` son los
  puntos de la cadena (relativos a `x`, `y`, en unidades de capa). La medida no se guarda: se
  calcula (distancia en unidades del plano, con `$INSUNITS`), y el total si hay más de dos puntos.
- **Marco para imprimir**: un `frame` con `groupIds = ["plano-marco"]`. Su caja es la parte del
  plano que va en una hoja; **el orden de las hojas es el de `updated`** (de menor a mayor).

El `id` sale de la forma (FNV-1a de las coordenadas con cuatro decimales): dos aparatos que hacen
la misma cota no la duplican. **Quitar** una cota o un marco la deja con `isDeleted = true` y versión
nueva, como cualquier figura, y así se junta al sincronizar.

Android no pinta estas figuras con el motor fuera del modo anotar: las pinta el visor con sus
medidas (naranja) y sus marcos (azul, numerados). Anotando sí salen, y se pueden borrar o mover
con las herramientas del motor; al acabar se vuelven a leer.

## Lo que no viaja

La caché de planos y modelos leídos (`cache/planos/`) y los datos del membrete de impresión
(proyecto, quién dibujó) son de cada aparato.
