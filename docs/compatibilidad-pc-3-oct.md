# Lo del PC del 30-sep al 3-oct, en Android

Fuente: `1xmanMAX/PIXPIN_PRO_WINDOWS` (commits `3ab18a2`…`f3189db`) y sus notas para Android en
`docs/investigacion/` (`2026-09-29-marco-de-la-tinta-android.md`, `2026-09-30-comentarios-de-notas-android.md`,
`2026-09-30-paginas-vivas-android.md`, `2026-10-01-ficheros-de-proyectos-del-pc-android.md`,
`2026-10-01-incrustados-de-notas-android.md`, `2026-10-02-tareas-con-fecha.md`).

## Sincronización (lo que se perdía o no viajaba)

| Qué | Dónde |
|---|---|
| La ruta entera de cada adjunto y el PDF del proyecto en `alcance` («informe (1).pdf» no viajaba) | `sincro/Disco.kt` |
| `anot-<uid>.comentarios.json` viaja con su nota (mensaje NOTA u hoja nota) y se borra con ella | `sincro/AnotacionesDelAdjunto.kt`, `Disco.kt`, `MensajesStore.borrarAdjunto`, `ProyectosRepository.guardar` |
| Marcos de tinta `.hoja` de tres líneas (huella): se leen y se reescriben en dos | `AnotacionesDelAdjunto.Marco` |
| Tareas con fecha `➕ AAAA-MM-DD` dentro del texto | `mini/Tareas.kt` |
| Colores de celda `style="background:#…;color:#…"` en las tablas HTML (antes se perdían al editar) | `motormd/Tablas.kt`, `Markdown.Celda` |

## Notas

- **Comentarios** (`motormd/Comentarios.kt`, puerto de `md_comentarios.rs` con sus pruebas):
  «Comentar» sobre lo elegido o la palabra, párrafos comentados en ámbar con su globo, panel para
  responder, editar, borrar y resolver; al guardar se juntan con lo que llegó del PC.
- **Exportar a Word** (`motormd/ExportarDocx.kt`): partes fijas iguales que el PC; tablas con
  combinadas y colores, fotos, comentarios con hilos.
- **Páginas vivas** (`ui/PaginasVivas.kt`): `vivo-<código>.png` se repinta al abrir la nota si su
  lienzo o su página de PDF cambió. Tablas, notas y croquis se quedan con la copia del PC.
- **Lo enlazado**: `pixpin:hoja=` y `pixpin:mensaje=` se pintan como burbuja y se abren dentro
  (`ui/EnlacesPixpin.kt`, `ui/IncrustadosDeLaNota.kt`); archivos con su icono de color, peso y
  lector propio; fotos con su ancho `|320` (mantener pulsada para cambiarlo).
- **Meterlo desde el móvil**: «Página de un proyecto», «Enlace a una hoja» y «Del chat» en `/` y
  en Adjuntar; «Insertar en una nota» en el menú de cualquier mensaje.
- **Audio**: mantener pulsado un audio de la nota → «Pasar a texto» (escribe `[m:ss] …` debajo).
- **Letra y tamaño** por nota o para todas (preferencia de vista, no va en el `.md`).

## Voz

- **Realce al grabar** (`audio/Realce.kt`, mismas constantes y pruebas que `realce.rs`): paso
  alto 80 Hz, ganancia automática a −3 dBFS (hasta +24 dB), puerta a −45 dBFS, limitador suave.
  Grabadora propia con AudioRecord + AAC (`audio/Grabador.kt`); si falla, la del sistema.
- **Micrófono flotante** (`pin/GrabadoraActivity.kt`): graba nada más abrirse, guarda en el chat y
  ofrece «Convertir en llamada» (En 15 min, En 1 h, Mañana 9:00 o flechas). Atajo «Grabar».

## Tareas, capturas y pedidos

- Tareas: «hace N días», barra de avance, ocultar hechas, corregir conservando la fecha, subir y
  bajar; la lista sacada a la pantalla queda **ligada a su mensaje**.
- **Galería de capturas** (`Pictures/PixPin`): abrir, sacar a la pantalla, copiar, compartir, al
  chat y papelera.
- **Recuadro para soltar** (`guardados/SoltarActivity.kt`): en Android solo se arrastra entre apps
  con las dos a la vista, así que es una ventana para pantalla partida o ventana emergente.
- **Nueva tarea** rápida desde el icono y el buscador.

### Pedidos de fuera (equivalente a `docs/protocolo-pedidos.md` del PC)

Cualquier app (Tasker, un atajo, el buscador) puede abrir `pixpin://atajo/<acción>`:

| Seña | Hace |
|---|---|
| `pixpin://atajo/tarea?texto=…` | Añade la tarea (con fecha) a la última lista del chat general; sin texto, abre el campo |
| `pixpin://atajo/chat?texto=…&proyecto=<id>` | Escribe una nota en el chat; sin texto, abre el chat |
| `pixpin://atajo/nota?texto=…` | Abre una nota nueva |
| `pixpin://atajo/lista?titulo=…&proyecto=<id>` | Crea una lista de tareas |
| `pixpin://atajo/grabar` | Micrófono flotante |
| `pixpin://atajo/capturar` · `capturas` · `soltar` | Capturar · galería · recuadro para soltar |
| `pixpin://atajo/leccion` · `lecciones` · `mensajes` · `proyectos` · `sincronizar` | Lo de siempre |

## No se trae (es del PC)

- El plugin de Flow Launcher: en el teléfono su papel lo hacen los atajos y el buscador del sistema.
- El agrupador de capturas con halo azul y el duplicador: son de las capturas de escritorio.
- La voz de Windows (SAPI): el móvil ya lee en voz alta con la suya.
