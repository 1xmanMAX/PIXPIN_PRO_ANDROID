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
