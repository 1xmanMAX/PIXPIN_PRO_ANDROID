# PixPin Android — Pellizco proporcional, Markdown en los pines de texto y sombra en vez de marco

Diseño acordado el 2026-08-01. Continúa el trabajo de
[`2026-07-30-texto-prioridad-sticker-design.md`](2026-07-30-texto-prioridad-sticker-design.md).

## 1. Qué se construye y por qué

Del uso de las mejoras del 30 de julio salen tres necesidades:

1. **Al pellizcar un pin de texto crece la letra pero no el cuadro**, así que el texto se
   re-ajusta y el pin cambia de forma. Se espera que se comporte como una imagen: todo a la vez
   y en proporción.
2. **El texto pegado desde notas o desde un chat llega lleno de marcas de Markdown** y se ve en
   crudo, con sus asteriscos y sus almohadillas.
3. **El marco gris de las imágenes ensucia**. Una sombra da la misma separación del fondo sin
   dibujar una caja.

Fuera de alcance a propósito: tablas de Markdown (en un pin estrecho hay que decidir qué hacer
al desbordar y no compensa), enlaces tocables (§4.4) e imágenes embebidas en el Markdown.

## 2. Pellizco proporcional

Hoy `TextPinBody` fija el ancho con `Modifier.width(s.textBoxWidth.dp)` y solo la fuente sigue
al zoom (`fontSize = (14f * zoom).sp`). El cambio es multiplicar **también las medidas del
cuadro** por el zoom:

| Qué | Antes | Ahora |
|---|---|---|
| Ancho | `textBoxWidth.dp` | `(textBoxWidth * zoom).dp` |
| Alto | `textBoxHeight.dp` | `(textBoxHeight * zoom).dp` |
| Fuente | `14f * zoom` | igual |

`textBoxWidth` y `textBoxHeight` pasan a significar **el tamaño base, a zoom 1**. El handle de
la esquina fija esa base; el pellizco escala el conjunto. Los dos se guardan por separado y no
se pisan.

**El detalle que hay que acertar.** `onResize` traduce hoy el arrastre de px a dp dividiendo
por la densidad. Con el pin ampliado hay que dividir **también por el zoom**: si no, un dedo
que recorre 100 px sobre un pin al triple movería la base 100 dp en vez de 33, y el cuadro se
estiraría tres veces más rápido que el dedo.

```
dxDp = dxFromDown / density / zoom
```

Lo mismo en el alto de partida de `onResizeStart`, que se deduce de la altura medida de la
ventana —que ya incluye el zoom— y por tanto hay que devolverla a base dividiéndola por él.

El handle sigue midiendo 30 dp fijos, sin escalar: es un control, no contenido. El tope de 5×
para los pines de texto se queda como está.

## 3. Markdown

### 3.1 Por qué un parser propio

Se descartan las dos alternativas:

- **Depender de `commonmark`**: correcto según la especificación, pero habría que escribir
  igualmente el renderizador de Compose, y el AST completo sobra para una nota flotante.
- **Una librería de Markdown para Compose**: no deja gobernar los tamaños, y aquí *todo* tiene
  que ser función del zoom (§2). Además ataría el proyecto a la versión de Compose de un
  tercero.

Un parser propio de un subconjunto es pequeño, y sobre todo **es puro**: se prueba en JVM sin
dispositivo, como `PinZoom`, `TextBoxSize`, `ScrollMatcher` o `PinGroups`.

### 3.2 Dos archivos nuevos, en `com.forge.pixpin.markdown`

**`Markdown.kt`** — el parser. Sin Compose y sin Android, para que sea probable en JVM.

```kotlin
enum class SpanKind { BOLD, ITALIC, STRIKE, CODE, LINK }

/** Tramo con estilo dentro de un texto ya limpio de marcas. */
data class InlineSpan(val start: Int, val end: Int, val kind: SpanKind, val url: String? = null)

/** Texto sin marcas más los tramos que lo decoran. */
data class InlineText(val text: String, val spans: List<InlineSpan> = emptyList())

sealed interface MarkdownBlock {
    data class Heading(val level: Int, val content: InlineText) : MarkdownBlock
    data class Paragraph(val content: InlineText) : MarkdownBlock
    data class Bullet(val content: InlineText) : MarkdownBlock
    data class Numbered(val number: Int, val content: InlineText) : MarkdownBlock
    data class Quote(val content: InlineText) : MarkdownBlock
    data class Code(val text: String) : MarkdownBlock
    data object Rule : MarkdownBlock
}

object Markdown {
    fun parse(source: String): List<MarkdownBlock>
    fun parseInline(line: String): InlineText
}
```

El texto de `InlineText` va **ya sin las marcas**, y los índices de los tramos apuntan a ese
texto limpio. Así el renderizador solo traduce tramos a estilos y no vuelve a mirar la sintaxis.

**Nivel de bloque**, línea a línea:

| Entrada | Bloque |
|---|---|
| `# t`, `## t`, `### t` | `Heading(1..3)` |
| `- t`, `* t`, `+ t` | `Bullet` |
| `1. t`, `2) t` | `Numbered` |
| `> t` | `Quote` |
| ` ```…``` ` | `Code`, con todo lo de dentro en crudo |
| `---`, `***`, `___` | `Rule` |
| línea en blanco | separa párrafos |
| cualquier otra | `Paragraph`, uniendo líneas seguidas |

**Nivel inline**: `**negrita**`, `*cursiva*` y `_cursiva_`, `~~tachado~~`, `` `código` `` y
`[texto](url)`. El código inline gana a todo lo demás: dentro de un `` ` `` no se interpreta
nada, que es justo lo que se espera al pegar una ruta o una expresión.

**`MarkdownText.kt`** — el renderizador Compose. Una `Column` con un `Text` por bloque y los
tramos convertidos a `AnnotatedString`:

```kotlin
@Composable
fun MarkdownText(blocks: List<MarkdownBlock>, baseSizeSp: Float, modifier: Modifier = Modifier)
```

`baseSizeSp` llega ya multiplicado por el zoom, así que el renderizador no sabe nada del
pellizco. Los títulos son múltiplos de la base: **1.6×**, **1.35×** y **1.15×**. El bloque de
código va en monoespaciada sobre `surfaceVariant`; la cita, con una barra vertical de 3 dp a la
izquierda; la regla, un `HorizontalDivider`.

### 3.3 Siempre activo

No hay interruptor. El texto plano no lleva marcas, así que se ve igual que antes; y el código
inline protege el caso que más se rompería. El riesgo asumido es que un asterisco suelto se
interprete sin querer.

Lo que se copia al tocar el pin sigue siendo `PinState.text`, **el original con sus marcas**:
el renderizado es una forma de ver, no el contenido.

### 3.4 Enlaces con estilo pero inertes

Se pintan en `primary` y subrayados, pero no responden al toque. Hacerlos tocables obligaría a
distinguir dentro de `OverlayTouchHandler` un toque sobre un enlace de un toque en el resto del
pin, y ese reconocedor lo comparten todos los pines y la bola flotante. No compensa el riesgo
en código tan delicado por una función que nadie ha pedido.

## 4. Sombra en vez de marco

### 4.1 Qué se quita y qué entra

Desaparecen el `.border(2.dp, Color.Gray)` de `ImagePinBody` y el `border` del `Surface` de
`PinRoot`. En su lugar, sobre el `Surface`:

```kotlin
Modifier.shadow(
    elevation = SHADOW_DP.dp,
    shape = <la misma forma del Surface>,
    ambientColor = color,
    spotColor = color
)
```

con `shadowElevation = 0.dp` en el `Surface`, para no sumar dos sombras. `ambientColor` y
`spotColor` existen desde API 28 y el mínimo del proyecto es 29.

El color, con la misma precedencia que tenían los marcos:

| Estado | Color de la sombra |
|---|---|
| Toques a través activados | `tertiary` |
| En un grupo | el color del grupo (`PinGroups.colorFor`) |
| Ninguno de los dos | negro (el de siempre) |

### 4.2 La sombra necesita sitio

**Una sombra se dibuja fuera del recuadro, y la ventana overlay recorta todo lo que salga de
sus límites** — el mismo problema que ya obligó a dejarle un hueco al sticker. De hecho la
sombra de 8 dp que hay hoy ya se está recortando contra el borde de la ventana; no se nota
porque no hay con qué compararla.

Así que la ventana crece un margen uniforme de **8 dp por los cuatro lados**, y la posición se
compensa en `x` e `y` para que el pin no dé un salto al aplicarlo, exactamente igual que se
hizo con el margen del sticker.

`applyContentSize()` pasa a sumar los dos márgenes:

```
ancho  = imagen × escala + SOMBRA × 2 + hueco del sticker
alto   = imagen × escala + SOMBRA × 2 + hueco del sticker
```

y compensa en posición `SOMBRA` (izquierda y arriba) más el hueco del sticker (arriba).

**`naturalW` y `naturalH` siguen siendo el tamaño de la IMAGEN, no el de la ventana.** Es la
misma advertencia del spec anterior y sigue siendo el punto más delicado: `AnnotationCanvas`
calcula su rectángulo con `naturalW × zoom`, y si los márgenes se colaran ahí, los trazos se
despegarían de la foto.

Como el margen ya no es uno sino dos, y uno de ellos aparece y desaparece con el emoji, la
cuenta se saca a una función pura —`PinChrome.insetsFor(hasEmoji)`— que devuelve los cuatro
lados. Es lo único de esto que se puede comprobar sin dispositivo, y evita repartir la
aritmética entre `applyContentSize` y `PinRoot`, que es donde se desincronizaría.

### 4.3 Los pines que se miden solos

Texto, color, archivo y la burbuja minimizada son ventanas `WRAP_CONTENT`: el margen les crece
solo al medirse, con solo poner el padding en `PinRoot`. Solo los pines de imagen, que tienen
tamaño explícito, necesitan el ajuste de `applyContentSize()`.

## 5. Pruebas

| Qué se prueba | Dónde |
|---|---|
| Cada tipo de bloque se reconoce (título, viñeta, numerada, cita, código, regla, párrafo) | `MarkdownTest` (nuevo) |
| El texto plano sin marcas sale intacto, carácter a carácter | `MarkdownTest` |
| Negrita, cursiva, tachado y enlace: texto limpio y tramos en las posiciones correctas | `MarkdownTest` |
| El código inline no interpreta lo que lleva dentro | `MarkdownTest` |
| Marcas sin cerrar (`**` suelto) no rompen ni se comen texto | `MarkdownTest` |
| `PinChrome.insetsFor` con y sin emoji | `PinChromeTest` (nuevo) |
| El redimensionado divide por el zoom | `TextBoxSizeTest` (ampliar) |

El renderizador Compose y las sombras no se prueban en JVM: dependen de `AnnotatedString`, de
`Modifier.shadow` y del compositor. Van a comprobación manual.

## 6. Orden de trabajo

1. **Parser de Markdown** con sus pruebas. Es puro y no depende de nada de lo demás.
2. **Renderizador** y enganche en `TextPinBody`.
3. **Pellizco proporcional**, que toca el mismo composable que acaba de cambiar.
4. **Sombra y márgenes**, lo último porque toca `applyContentSize()` y conviene que el resto
   esté estable antes de mover esa cuenta.
