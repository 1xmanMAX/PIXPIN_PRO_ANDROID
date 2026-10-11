# Lo del PC del 8 y 9-oct-2026 en Android (v0.116.0)

Commits del PC (`PIXPIN_PRO_WINDOWS`): `62712f0`, `c493abb`, `76fc59a`, `1e5be50`.

| Del PC | En Android |
|---|---|
| Sincronización automática con la app abierta (`sincronizar/automatica.rs`) | `sincro/SincronizacionAutomatica.kt`: mismo reloj (`RelojDeSincro`, 30 s quieto, cada 10 min, al aparecer), mismo «sin ruido», no borra proyectos. Interruptor «Sincronizar sola» en Sincronizar. Pruebas: `RelojDeSincroTest`. |
| Recordatorios con su archivo como pin | `pin/RecordatorioReceiver.kt` (`ArchivoDelRecordatorio`): la foto o el documento sale como pin, con una copia. |
| Galería de capturas que viaja (`c493abb`, el PC la adopta de Android v0.107) | Ya estaba; se prueba contra el Rust del PC con `pc-simulado`. |
| Visor de planos: regla, SHX de Hershey, TEXT al revés, láminas, anotar (`c493abb`) | Es lo de Android v0.110–0.114 que el PC adoptó: nada que traer. Su `PXCAD` pasa a v6 solo en la caché del PC. |
| Lectores como un pin con la barra fuera (`76fc59a`) | Cosa de ventanas de Windows: en el teléfono ya van a pantalla completa. |
| Visor 3D: caja de sección con tapas macizas, aislar con otro toque, giro alrededor de lo tocado (`1e5be50`) | `planos/Visor3DActivity.kt`, `Pintor3D.kt` (los mismos sombreadores en GLSL ES), `ElegirEn3D` (la tapa da el punto del corte). Pruebas: `CajaDeSeccionTest`. |

Además, pedido del usuario el 10-oct: **los IFC y los Revit se abren en el visor 3D** (como en
Windows), desde el chat, «Abrir con» y compartir; un OBJ sigue yendo al croquis. El visor suma
piezas por tipo, ocultar/aislar, medir y «Al croquis 3D». El croquis, al importar un IFC o un
Revit, usa ya la misma lectura del PC (`ModeloAMalla`).

Diagnóstico (`ModeloAMallaTest`): el lector propio del croquis coloca las piezas casi igual que
ifc-lite (en el edificio del usuario, una losa a 0,5 m); lo que se veía «descolocado» en el croquis
viene sobre todo de pintar sin búfer de profundidad. En el visor 3D eso no pasa.

Nada de esto se ha visto en un teléfono.

## Lo del PC del 10-oct (`9625d0d`) en Android (v0.117.0)

| Del PC | En Android |
|---|---|
| Panel de **niveles y categorías** del visor 3D (`ventana3d/arbol.rs`): cuántos elementos y su volumen; clic muestra u oculta, Ctrl + clic deja solo ese, «Mostrar todo» | `planos/ArbolDelModelo.kt` (las cuentas) y el panel de `Visor3DActivity` (botón de capas, a la derecha): **un toque** muestra u oculta, **mantener** deja solo ese. Lo ocultado a mano se suma a lo del panel. Pruebas: `ArbolDelModeloTest` (las tres del PC y el lector). |
| Niveles del IFC (`pixpin-bim/src/niveles.rs`: `IfcBuildingStorey`, `IfcRelContainedInSpatialStructure`, `IfcRelAggregates`), y así de Revit | Traído tal cual al `.so` (`traer-del-pc.sh` copia `niveles.rs`). |
| Volumen, área en planta y en alzado de cada elemento (`medir` en `modelo3d.rs`); la pastilla dice nivel, m³ y m² | La pastilla de lo tocado usa `ArbolDelModelo.describir` (mismo texto que el PC; «≈» si la malla no es cerrada). |
| Caché PX3D **v3** | `Modelo3D` lee v3; la caché pasa a `<clave>.v3.px3d` (las viejas se rehacen). |
| (`c493abb`) SHX de Hershey dentro del programa y volteo de los TEXT: lo de Android adoptado | El guion ya no parchea eso: `include_bytes!` apunta a `app/src/main/assets/fuentes-shx`. PXCAD **v6** (igual que v5): `ModeloCad` lee las dos. |

## Lo del PC del 10-oct por la tarde en Android (v0.118.0)

| Del PC | En Android |
|---|---|
| Visor de planos: encuadrar lo que importa (`4f33ab3`, `Modelo::caja_util`) | `ModeloCad.cajaUtil` (mismas cuentas) en «ver todo» y al abrir. La unidad de lo anotado sigue con la caja entera. Prueba: `PlanosTest` con `suelto-lejos.dxf` leído por el .so. |
| Tareas: cada grupo con su color, sin repetir (12 tonos), y su marca junto al nombre (`11bd1f6`) | `TodasLasTareas.coloresDeListas` (mismo reparto: FNV-1a de la clave, orden de clave por bytes, las vacías no gastan); marca junto al nombre y raya a la izquierda de cada tarjeta. Prueba con los números que da la función del PC compilada aparte. |
| N invierte los colores de pines y lectores (`11bd1f6`) | Lector de PDF: «Invertir colores» en el engranaje (hoja, márgenes y tinta; el tono se queda). Android 12 o más. El Word/EPUB ya sigue el claro/oscuro del aparato. |
| Planos: grosor y letra del panel a la escala del plano (`11bd1f6`) | No hace falta: aquí la capa del plano va en unidades fijas del plano (lado/1000), no de la pantalla, y lo del panel no sale enorme. |
| Captura, cursor del chat, barras de ventanas, vista rápida del Explorador, imprimir (`11bd1f6`, `8636b12`) | Cosas de Windows. |
| Lienzo: fuera el cronograma; los dibujos que lo tienen lo conservan para el móvil | Android lo sigue teniendo; nada que cambiar. |

## Lo de la tableta Huawei y las letras (v0.119.0, 11-oct)

Lo vio el usuario en su Huawei MatePad Mini (en el Galaxy S24 Ultra no pasaba):

| Problema | Arreglo |
|---|---|
| Letras a trozos al alejarse (de «3832.0» solo el «8», recuadros vacíos) | `PintorDePlano`: cada letra lleva su glifo (desde y cuántos vértices) en sus datos, sin la textura de enteros `uGlifos`; y las instancias van en tandas de 4096 como mucho. **Sin confirmar en la tableta**: es lo que diferenciaba a esa tarjeta, no un fallo que se haya podido reproducir aquí. |
| Lento al acercarse a los detalles | Los arcos grandes en pantalla (más de 160 px) van como una **banda** de 48 trozos que sigue la curva, no como un cuadrado que tapaba la pantalla entera y se pintaba píxel a píxel por cada arco. |
| Textos que se montan o se salen de sus recuadros | El PC escribe con las letras de Windows; aquí iban a Roboto: un estilo con **Arial Narrow** salía un 11 % más ancho y Arial un 8 % más estrecho. Ahora van en la app **Liberation Sans / Sans Narrow / Serif / Mono** (mismas medidas que Arial, Arial Narrow, Times New Roman y Courier New), **Carlito** (Calibri) y **Caladea** (Cambria) en `assets/fuentes-ttf` con sus licencias; el `.so` las recibe en el renglón `ttf:` de las carpetas de letras (`carpeta_ttf_android`, `fuente_parecida`, `ruta_de_fuente`, `familias_android` en `lib.rs`). Caché de planos rehecha (`REVISION_DE_LETRAS` = 3). |
| Grosor y letra del panel enormes al anotar un plano (PC `11bd1f6`) | `DrawController.escalaDeLoNuevo` (1 en toda la app; en el plano, uno entre el zoom): lo nuevo nace a la escala de lo que se ve, cambiar el tamaño de lo marcado también, y el panel lo enseña en su medida. Prueba: `EscalaDeLoNuevoTest`. |

## Letras del usuario y la tableta Huawei, segunda vuelta (v0.120.0, 11-oct)

- **Las letras de AutoCAD no pueden ir dentro de PixPin** (son de Autodesk; las de Windows, de
  Microsoft). El visor de planos tiene ahora el botón **Aa**: dice qué letras pide el plano y no
  están (el .so las apunta en `sinDibujar` como `fuente que falta: X`, desde `falta`, `falta_shx`,
  `revisar_ttf` y `clave_de_familia`), dónde están en el PC (`C:\Program Files\Autodesk\AutoCAD
  20xx\Fonts`, `C:\Windows\Fonts`) y deja **añadirlas** (SHX, TTF, OTF, TTC) a `files/fuentes-shx`.
  El lector las usa antes que las de PixPin (`del_usuario`, y las TrueType por su nombre de verdad
  de la tabla `name` en `familias_del_usuario`); la caché del plano lleva la huella de esas letras
  (`LectorDePlanos.huellaDeLetras`), así que al añadir una el plano se relee. Prueba Rust:
  `pruebas_letras_del_usuario` (cargo test --release --lib); probado también con `convertir-plano`.
- **Tableta Huawei** (seguía a trozos y lenta): las letras van **ya puestas en el plano**, en un
  búfer de vértices sueltos como las rayas (sin instancias, sin texturas ni `gl_VertexID`), si caben
  (≤ 6 millones de vértices; si no, el camino de antes). Y **moviéndose, a menos píxeles** (≤ 1,5
  millones, en un búfer aparte que se estira a la pantalla); a los 140 ms de quieto se pinta nítido.
