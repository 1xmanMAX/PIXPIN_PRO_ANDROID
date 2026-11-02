# Lo del PC hasta el 2-nov, en Android (v0.157.0)

Del PC (`1xmanMAX/PIXPIN_PRO_WINDOWS`, `origin/main` en `9e3e9071`; la única rama con algo fuera de
`main` es `microsoft-store`, que es el empaquetado de la tienda de Windows y no aplica). Pedido del
usuario: «revisa el código de Windows y actualiza la aplicación con todas las nuevas funciones y
arreglos… que sea sincronizable». Hecho en orden, por un solo agente. **Compila y pasan las pruebas
JVM; falta verlo en un teléfono.**

| Del PC | En Android | Viaja / formato |
|---|---|---|
| Tareas quietas (`tareas_quietas.rs`): lo tachado se queda en su sitio y el orden congelado | `mini/TareasQuietas.kt`, Todas las tareas y la mini‑app | — |
| Exportar listas de tareas (`tareas_exportar.rs`): página, PDF, imagen, Word, Markdown, texto | `mini/ExportarTareas.kt`, `ExportarTareasEnLaApp.kt`, `assets/tareas-exportar.css`; 14 idiomas | — |
| Cada asignatura con su lista (`lista:<id>`), renombrar, en Tareas, el asistente | `horario/Asignaturas.kt`, `Horario.kt`, `Semana.kt`; destino y «＋» en Tareas | `horario.md` con `lista:<id>` (igual que el PC) |
| **Marcas de texto** de notas y páginas web (§3.10) | `motormd/MarcasDeTexto.kt` (fichero y fusión), `AnclaDeTexto.kt` (puerto de `marcas-texto.mjs`), `MarcasDeLaNota.kt` (pintarlas), `ui/MarcasDeTextoEnLaWeb.kt` + `assets/marcas-pagina.js` (el guion del PC con puente táctil); en la nota, «Marcar texto» sin teclado | `anot-<uid>.marcas-texto.json` (añadido a `TERMINACIONES`: viaja y se borra con su mensaje) |
| **Marcas de texto** de PDF, Word/PowerPoint, Word/libros leídos y hojas (§3.9) | `motor/MarcasDeTextoEnTinta.kt` (formas de `marcas_texto.rs`), `pdf/ElegirTextoDeLaHoja.kt` (texto del PDF con `PdfRenderer.selectContent`, Android 15+; trozos de Office), visor de documentos (selección → tinta), hojas (rango de celdas) | trazos con `groupIds` `marca-texto-<ms>` en la capa de siempre |
| Color avanzado del lienzo (`selector_color.rs`): HSV, código de 3/6/8 cifras, contraste, recientes, cuentagotas | `motor/SelectorDeColor.kt`, `ColorAvanzado.kt` en el taller del color; recientes y el último «trazo a mano» en ajustes | `strokeColor`/`opacity` de siempre |
| Gestos del trackpad en lo exportado (`GestosVista`) | dibujo (`ExportarHtml`), tabla (`VisorTabla`) y plano (`PaginaDelPlanoGuion`) | — |
| La hoja «Adjuntar» con «Pegar lo copiado» | `OpcionesDeAdjuntar.PEGAR`, abajo y aparte | — |
| **La nube de los chats compartidos** (protocolo 1 + `deltas1`, `docs/nube.md`) | `sincro/nube/`: `Nube.kt` (cliente y cuerpos), `TrazosNube.kt` (deltas, regla, lápidas, compactar), `EstadoNube.kt`, `VueltaNube.kt`, `SinConexion.kt`, `NubeEnMarcha.kt` (llave `pxn_` cifrada con el Keystore, bucle y novedades), `PantallasDeLaNube.kt` | `sincro/nube/chats.json` (no `compartidos.json`), `sincro/nube/chats/<chat>.json`; `/nube/api/v1`, `PUT /yo` firmado P‑256 |
| «En línea / Sin conexión» en los visores | botón arriba a la izquierda en PDF/Office, web y Word, notas, hojas y lienzos de un chat de la nube; al salir con atrás se pregunta | copia aparte en `sincro/nube/sin-conexion/` |

## Lo que el PC hizo a imitación del teléfono (ya estaba)

- El botón redondo día/noche de las cabeceras y los visores, y las figuras del chat como el iPhone:
  Android ya los tenía (el PC copió «el botón redondo del móvil»).
- El panel del lienzo con tema claro y oscuro: en Android ya sigue al tema (colores del tema, sin
  fijos).

## Lo que no aplica

- Las frases clave del dictado del **timeline** por idioma: Android no tiene el timeline del PC.
- `microsoft-store`: el alias de la tienda de Windows.

## Notas

- Elegir texto en un **PDF** necesita Android 15 (la API de texto de `PdfRenderer`); en uno anterior
  se avisa y se puede abrir «como texto» (ahí sí se marca). Lo marcado en el PC se ve siempre.
- La nube necesita su dirección y la llave del aparato (Sincronizar → Nube); sin ellas, los chats
  marcados «Nube» siguen por la red local y la nubecita sale gris.
