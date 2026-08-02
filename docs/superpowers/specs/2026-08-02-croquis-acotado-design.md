# Croquis acotado: dibujar, medir y exportar

Mini-aplicación para levantar un croquis con medidas reales, y para medir sobre
la captura de un plano ajeno. Nace de una necesidad de obra: saber cuánto mide
lo que hay que construir, y poder dibujar un plano pequeño para explicar un
punto.

## Por qué no es un visor de CAD

El encargo empezó pidiendo abrir DWG y Revit. Se investigó con archivos reales
antes de diseñar nada, y los datos cambiaron el destino del proyecto. Queda
aquí lo medido, porque justifica todo lo que viene después.

**DWG no se puede leer.** Es binario, cerrado y versionado; desde 2004 sus
secciones van comprimidas. Las tres únicas vías —LibreDWG (GPLv3, incompatible
con la licencia MIT de este proyecto), el SDK de ODA (comercial) y la nube de
Autodesk (descartada por principio)— quedan fuera.

**La miniatura embebida tampoco es un atajo.** Se barrieron 394 DWG del disco
buscando el centinela de la sección de vista previa: **0 la tenían accesible**.
De los 199 archivos propios del usuario, 197 son de AutoCAD 2018 y 2 de 2013,
ambas generaciones comprimidas. Llegar a la miniatura exigiría implementar
antes la descompresión de secciones, que es justo el trabajo que la miniatura
pretendía evitar.

**El DXF es viable pero desproporcionado.** Se convirtió un plano real de
topografía con `accoreconsole`:

| Medida | Valor |
|---|---|
| DWG → DXF | 12,5 MB → 59,1 MB (×4,7) en 35 s |
| Líneas | 5.999.366 |
| Entidades en espacio modelo | 1.264 |
| Entidades dentro de bloques | 133.102, en 345 definiciones |
| Capas | 221 declaradas, 212 con contenido |
| `$INSUNITS` | 6 → metros |
| Extensión | X hasta 709.927,66 · Y hasta 8.240.708,72 |

Dos conclusiones de ahí. La primera: **sin expandir los `INSERT` se dibujaría el
1 % del plano**, y las 13.066 splines son las curvas de nivel, así que tampoco
son opcionales. Un visor honesto es un proyecto entero.

La segunda es la que se quedó: **con coordenadas de orden 10⁶, un `Float` de 32
bits pierde alrededor de un metro de precisión.** Un dibujo que se ve bien y
mide mal es el peor fallo posible en una herramienta cuyo propósito es medir.

**Lo que resolvió el problema fue otra cosa.** En obra no se lleva el DXF: se
lleva una captura del plano. Y PixPin ya es una aplicación de capturas. Calibrar
esa captura contra una medida conocida da lo que el visor iba a dar, sin parsear
nada.

## Alcance

Dentro:

- Mini-aplicación por palabra mágica, con hoja infinita en pantalla completa.
- Dibujo de línea, polilínea, rectángulo, círculo, texto y cota.
- Precisión: imantado a extremos, orto y **entrada numérica** de longitudes y
  coordenadas.
- Modo medir, con el dibujo deshabilitado, punto a punto y sin ensuciar.
- Captura de fondo calibrable, para medir sobre un plano ajeno.
- Exportación a PDF vectorial y a JPG.

Fuera, a propósito:

- Importar DXF, DWG, RVT o cualquier formato CAD.
- Arcos, splines, sombreados, bloques y capas múltiples.
- Corrección de perspectiva: la fuente es una captura de pantalla, que es
  ortogonal por definición.
- Acotación angular, radial y de área. Solo distancia entre dos puntos.

## Modelo

El mundo está en **metros**, no en píxeles. De esa decisión cuelga el resto.

```kotlin
@Serializable
data class Croquis(
    val entidades: List<Entidad> = emptyList(),
    val fondo: Fondo? = null,
    val decimales: Int = 2
)

/** Punto en coordenadas del mundo, en metros. */
@Serializable
data class P(val x: Double, val y: Double)

@Serializable
sealed interface Entidad {
    data class Linea(val a: P, val b: P) : Entidad
    data class Polilinea(val puntos: List<P>, val cerrada: Boolean) : Entidad
    data class Rect(val a: P, val b: P) : Entidad
    data class Circulo(val centro: P, val radio: Double) : Entidad
    data class Texto(val en: P, val texto: String, val alturaM: Double) : Entidad
    data class Cota(val a: P, val b: P, val desplazamiento: Double) : Entidad
}

@Serializable
data class Fondo(
    val imagenPath: String,
    val origen: P,
    val metrosPorPixel: Double
)
```

`Double` y no `Float`: es la conclusión medida más arriba, y la única razón por
la que este modelo no reutiliza `Annotation`, que guarda `Float` en coordenadas
de imagen. La conversión a `Float` ocurre **solo** al pintar, después de restar
el origen de la vista.

`Cota` guarda sus dos puntos y **calcula su cifra**, en vez de almacenarla. Un
número escrito a mano sobrevive a los cambios y miente; uno calculado, no.

`Entidad` es una interfaz **sellada**. La objeción documentada en
`PinModels.kt` iba contra el polimorfismo abierto, que obliga a registrar
subtipos; el cerrado lo resuelve kotlinx sin intervención.

## Dos puertas al mismo editor

```
palabra «croquis»  ──────────────►  hoja en blanco
                                          │
pin de imagen ──► acción «acotar» ──► misma hoja, con la captura de fondo
                                          │
                                          ▼
                                   CroquisEditorActivity
```

El pin flotante muestra una vista reducida y sirve para consultar. **Editar abre
pantalla completa**: la hoja infinita y una barra de entrada numérica no caben
en un pin.

## Modos

| Modo | Comportamiento |
|---|---|
| Dibujar | Herramientas activas. No se mide |
| Medir | **Dibujo deshabilitado.** Dos toques dan una distancia, cuantas veces se quiera |

Lo medido en modo medir es **efímero**: se muestra y desaparece. Lo que deba
quedar escrito se acota en modo dibujar, y eso es una entidad `Cota`.

## Precisión

Tres mecanismos que se suman:

1. **Imantado a extremos.** A menos de 12 dp del extremo de otra entidad, el
   punto salta ahí exacto. Es lo que hace que las líneas conecten de verdad.
2. **Orto.** A menos de 5° de 0°, 45° o 90°, la línea se endereza.
3. **Entrada numérica.** Una barra fija abajo muestra longitud y ángulo en vivo
   mientras se arrastra; al teclear un valor, **manda el número y el dedo deja
   de contar**. Acepta también coordenadas absolutas (`12.5, 8.3`) para
   replantear por cota. Una entidad ya dibujada puede reescribir su longitud: el
   extremo se recoloca conservando la dirección.

## Calibración

Trazar una línea sobre una medida conocida de la captura y teclear su valor real
produce `metrosPorPixel`. Desde ese momento la imagen **es geometría**: medir
sobre ella y medir sobre lo dibujado usan el mismo código, porque ambos están en
metros.

La calibración pertenece a **esa captura concreta**. Se guarda junto a la imagen
para que una recaptura con otro zoom no herede una escala falsa.

## Exportación

`android.graphics.pdf.PdfDocument` viene en Android y entrega un `Canvas`. Como
el croquis se dibuja con las mismas primitivas, el PDF sale con **geometría y
texto vectoriales**: se amplía sin pixelar y se imprime a escala.

Un único `CroquisRenderer` sirve a la pantalla, al PDF y al JPG. Si algo se ve
en el móvil, sale igual en el papel.

- JPG por `CompressFormat.JPEG`; `Export.saveToGallery` ya resuelve `MediaStore`
  y solo necesita parametrizar el formato, hoy fijo a PNG.
- PDF a `MediaStore.Downloads`, compartido por el `FileProvider` existente.
- El PDF imprime abajo **la escala y la fecha**. Un croquis acotado sin
  constancia de su escala es una trampa para quien lo reciba.

## Errores

| Situación | Respuesta |
|---|---|
| Calibrar con longitud cero o negativa | Se rechaza; sin escala válida no se entra en modo medir |
| La captura de fondo ya no está en disco | El croquis sobrevive sin fondo y avisa: la geometría está en metros y no depende de la imagen |
| Croquis vacío al exportar | No genera archivo; avisa |

## Persistencia

El croquis se guarda en **su propio archivo JSON**, con la ruta en el `PinState`,
igual que hacen `ImageStore` y `FileStore`. Un `PinState` se lee entero al
arrancar y no debe cargar con cientos de entidades.

`UndoStack` sirve tal cual.

## Pruebas

`CroquisGeometria` es un objeto puro, sin Android, comprobable en JVM — el mismo
patrón de `AnnotationGeometry`, `TableData` y `Ledger`:

- **Calibración**: dos puntos a 300 px con longitud real 4,20 → `0,014` m/px.
- **Imantado**: a 8 dp salta; a 40 dp, no.
- **Orto**: 3° se endereza; 20° no.
- **Longitud tecleada**: recoloca el extremo conservando la dirección.
- **Cota viva**: mover un extremo cambia la cifra.
- **Precisión**: una línea con coordenadas de orden 10⁶ conserva los milímetros.
  Es la prueba que ancla la decisión de usar `Double`, y la que habría fallado
  con `Float`.
