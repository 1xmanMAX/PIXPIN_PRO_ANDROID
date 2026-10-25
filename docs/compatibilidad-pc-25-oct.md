# Lo del PC hasta el 25-oct, en Android (v0.131.0)

Del PC (`1xmanMAX/PIXPIN_PRO_WINDOWS`, `main` en `553f43f`; todas sus ramas ya están dentro de
`main`) y de su `docs/novedades-2026-10-25-para-los-otros-aparatos.md`. Pedido del usuario:
«revisa todo el código de Windows e implementa todas las funciones nuevas, que todo sea
sincronizable». **Nada de esto se ha probado en un teléfono**: compila y pasan 3368 pruebas JVM.

| Del PC | En Android | Viaja |
|---|---|---|
| Figuras de serie (627, 35 categorías) | `motor/FigurasDeSerie.kt`, generadas con el código del PC (`herramientas/figuras-de-serie/volcar.sh`); panel por categorías con buscador | ids `serie-…` iguales; el `figuras.json` del PC (puntos `[x,y]`) ya se lee: antes no salía ninguna figura |
| Texto rico y fórmulas `$…$` en los textos del lienzo | `motor/TextoRico.kt`, `FormulaRica.kt`, `CajasRicas.kt`, `PintorDeTextoRico.kt`, barra al editar | `textoRico` + su `text` plano, regla del PC |
| Nube de revisión dinámica | `motor/NubeDeRevision.kt`, herramienta Nube | `nubeRadio`, `nubeBase` (antes se perdían al guardar) |
| Láminas de geometría (`manual-geometria-android.md`) | herramienta Ángulo, `marcaAngulo`, `cierreArco`, `degradado`, `trazoRayaPunto` pintados | claves del PC |
| Puntero láser y copiar estilo | `PunteroLaser.kt`, `CopiarEstilo.kt`; láser también al presentar | no se guarda |
| Vista de cada marcador del lienzo | `motor/VistasDeMarcas.kt`; mantener el punto → «Actualizar vista» | `<d>.vistas.json` (antes no viajaba) |
| Grabar clase (audio, capturas, vídeo) | `guardados/GrabarClase*.kt`, barra flotante, letra con la imagen del minuto | `grabacion`, `capturas`, `video`, `enGrabacion` |
| Nota rica en el chat | `guardados/NotaRica*.kt`: Markdown y fotos en álbum en la burbuja | sin campos nuevos |
| Ficha del chat: Vídeos y Enlaces | `guardados/SeccionesDelChat.kt` | — |
| Adjuntar → Figura | `guardados/AdjuntarFigura.kt`, el panel de figuras | — |
| Carpetas de páginas | `guardados/CarpetaDePaginas.kt`, `CarpetaActivity`, página nueva con su plantilla, PDF | `MINIAPP` `carpeta`, `{"formato":1,"paginas":[…]}` |
| Asistente: conversación nueva y memoria larga | `ia/MemoriaLarga.kt`, chip «🧹 Conversación nueva» | `nuevaConversacionIa`, `auto-memoria-…` |
| Asistente: `preguntar_usuario`, `editar_texto`, lint, guardas, pasos | `ia/Preguntar.kt`, `EditarTexto.kt`, `Lint.kt`, `Guardas.kt`, `Pasos.kt` | — |
| Enviar por Wi‑Fi a mis aparatos sin código | `sincro/AlGrupo.kt` en Enviar | protocolo `suelto` de siempre |
| Tareas en Kanban | `mini/Kanban.kt` | — |
| Enviar a Anki | `anki/`: AnkiDroid o fichero de importación | — |
| Horario semanal con aviso de llamada | `horario/`, botón en Tareas | `horario.md` local, como en el PC |

## Quitado

- **Los modelos de IA locales** (llama.cpp, Qwen, Gemma): el asistente es solo de la nube; al
  arrancar se borran los modelos descargados. El dictado (Whisper) se queda.
- Del lienzo en modo visualización, el ⋮ (encuadrar, presentar, cerrar): solo el riel.
- Del ⋮ del chat, Proyectos y Comenzar (Ajustes y Chats se quedan: en el teléfono son la única
  puerta a la portada y a otros chats).

## Lo que no, y por qué

- Asistente: editar plantillas por bloques (`alargar`, `personalizar`, `reorganizar_hoja`), las
  demás herramientas de edición del lienzo, `ojos`/`repasar`/`orquesta` y las de §2.1 que el
  teléfono no ofrece.
- Exportar a SVG/PDF el texto rico, los sectores y los degradados (salen con su reserva; en
  pantalla y PNG se ven bien).
- Grabar clase: sin sonido del equipo; con vídeo, la barra sale en el vídeo y las capturas son
  de pantalla entera.
- Carpetas: sin marcadores en el PDF ni tapar lo oculto; reordenar con flechas.
- Anki: sin aviso de duplicadas. Horario: sin Google Calendar; no viaja (tampoco en el PC).
