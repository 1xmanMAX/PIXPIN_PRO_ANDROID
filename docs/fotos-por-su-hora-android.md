# Fotos por su hora, de la PC al móvil y colaboradores (Android)

28-oct-2026. Es la parte de Android de `docs/movil-como-camara.md` §12, §13 y §14 del PC
(`origin/main` 995fd94b) y de «Fotos por su hora, de la PC al movil y colaboradores» de su
`docs/manual-sincronizar-lo-nuevo.md`. Las reglas son las de
`crates/pixpin-proyecto/src/por_su_hora.rs`, copiadas una a una con sus pruebas.

El usuario, en el PC: «que se puedan tomar fotos normalmente aunque NO se esté conectado; cuando
logre conectarse, como la foto lleva su hora y la grabación también guarda su hora, si la hora de
la foto está fuera de la grabación, que la ignore; si está dentro, que calcule dónde va y la ponga
ahí». Y: «las fotos tienen que ser BIDIRECCIONALES con la PC». Y: «agregar COLABORADORES».

## Lo que ve el usuario

- **La cámara para la reunión del PC dispara siempre.** Sin un PC grabando a la vista (sin red en
  la reunión), la foto se guarda en la Conversación general con su hora y la pantalla dice «Fotos
  por su hora». Cuando el teléfono vuelve a oír a un PC del grupo, se la sube solo con su hora: si
  cae en una de sus grabaciones (la de ahora, una recién acabada o cualquier clase guardada), entra
  en su minuto (✓ en la miniatura); si no, se queda como la foto normal que ya era (· gris, no es
  un fallo).
- **Al sincronizar, las fotos con hora entran solas en su grabación.** Si el teléfono grabó la clase
  (reunión presencial o «Grabar clase»), las fotos del PC y de las personas invitadas que caigan
  dentro aparecen en su minuto en «Estudiar la clase» y en la carpeta de la grabación; si grabó el
  PC, lo hace el PC con las del teléfono.
- **El PC aporta capturas a la grabación del teléfono.** Mientras el teléfono graba, cada PC del
  grupo lo sabe (sale su botón «Captura para la grabación del móvil»); cada captura llega al teléfono
  con su minuto y, al guardar la clase, entra como una captura más («🖥 ⏱ 12:34»). La pantalla de la
  reunión cuenta «N capturas del PC».
- **Colaboradores.** En la pantalla de la reunión presencial, «Que todos los del chat aporten fotos»;
  en la nota de una clase ya guardada, en «Más» del menú, «Que todos los del chat aporten fotos» /
  «Solo mis fotos en esta grabación». Las fotos de las personas del chat compartido entran en su
  minuto; en «Estudiar la clase» cada una lleva **el círculo de su autor** (su inicial en el color de
  sus burbujas) y, si su reloj nunca se midió, un 🕒 pequeño: «hora sin comprobar».
- **«Adjuntar a la grabación»** (en «Más» de una foto que aún no es de ninguna): la pone en la
  grabación del chat donde cae su hora o, si no cae en ninguna, al final de la última.

## Los campos (todos se conservan al reescribir un mensaje aquí)

| Mensaje | Campo | Quién lo escribe | Android |
|---|---|---|---|
| Nota de voz de una clase | `reloj: {aparato, inicio, fin, pausas, vistoEn, huecos, grabacion}` | quien grabó, al guardarla | `Mensaje.reloj` (`JsonObject`, tal cual); modelo `guardados/RelojDePared.kt`, la misma forma que el PC |
| La misma nota | `colaboradores: ["K7Q2", …]` o `["*"]` | quien grabó, al invitar | `Mensaje.colaboradores` (`JsonArray`) |
| Cada entrada de `capturas` | `aparato`, `uid`, `horaSinComprobar` (además de `id`, `ms`) | quien grabó, al colocar | `Mensaje.capturas` es un `JsonArray`: lo de cada entrada va tal cual; las nuevas, con las claves en el orden alfabético del PC |
| Cada foto | `tomadaEn` | el aparato que la hizo | `Mensaje.tomadaEn` (ya estaba); lo ponen la cámara del PC sin grabación y las fotos de la reunión presencial |
| Cada foto sin `tomadaEn` | `tomadaEnExif` + `desfaseExif` | el aparato que la hizo | `Mensaje.tomadaEnExif/desfaseExif`; los pone `MensajesStore.anadir` desde el EXIF (`HoraDeLaFoto`) |
| Cada foto | `paraGrabacion` | el aparato que la hizo | `Mensaje.paraGrabacion` (se conserva y se respeta al decidir) |
| Foto de mis aparatos ya colocada | `enGrabacion: {de, ms}`, `fuente: "movil"` | quien grabó | ya estaban |
| Copia de la cámara en directo | `fotoUid` | quien grabó | `Mensaje.fotoUid`; también en las capturas del PC que entran en la grabación del teléfono |
| Captura del PC en la grabación del teléfono | `fuente: "pantalla"`, `deAparato`, `tomadaEn`, `fotoUid` | el teléfono | `GrabacionesDelMovil.adjuntar` |

Fuera del chat, sin viajar:

- `filesDir/sincro/desfases.json`: `{"aparatos":{"<código>":[{"cuando","desfase"}]}}`, 32 medidas por
  aparato. Se apunta en cada saludo de sincronizar (`Sesion`, con el grupo y con personas), en cada
  pregunta y aviso de la cámara del PC (`CamaraDelPc`) y en cada anuncio de la grabación del
  teléfono (`GrabacionDelMovil`).
- `filesDir/sincro/camara-movil/de-otros/<grabación>/`: capturas del PC que esperan a que se guarde
  la clase del teléfono, cada una con su ficha `.json` (minuto, de quién, hora).

**Choques**: los campos de la nota los escribe solo quien grabó (`reloj.aparato`); repasar es
idempotente; la foto de otra persona no se toca nunca (la colocación vive en la nota).

## El código

| Qué | Dónde |
|---|---|
| Las reglas (reloj, margen, pausas y huecos, desfases, EXIF, decidir, colocar, repasar, a mano, buscar por id y por hora) | `guardados/PorSuHora.kt` — `PorSuHoraTest` (las 16 del PC y alguna más) |
| El reloj de pared de la nota | `guardados/RelojDePared.kt` |
| El repaso y los desfases en disco | `sincro/FotosPorSuHora.kt`; `Disco.cambiarMensajes` (escribe y apunta en el diario: viaja) |
| Dónde se repasa | `Sesion.adios/soltar` (sincronizar dirigiendo, con el grupo o personas, normal o por diario), `Respondedor.atender/atenderPersona` (respondiendo), `RecibirActivity` («Enviar por Wi‑Fi»), `GrabacionDelMovil.guardada` (al guardar la clase del teléfono), al invitar a todos |
| La hora del EXIF | `guardados/HoraDeLaFoto.kt`, desde `MensajesStore.anadir` |
| §12.4 la cámara sin grabación | `CamaraDelPcActivity.apuntarPorSuHora`, `ColaDeLaCamara.Toma.porSuHora` (`grabacion`/`pc` vacíos), `ConexionDeCamara.mandar` (sin `grabacion` ni `ms`), `SubidaALaGrabacion.pasar` (mide el reloj con `camara_remota` si la toma no lo sabía), `CamaraRemota.NINGUNA` («no cae en ninguna»: negada para siempre, ya es una foto normal) |
| §13 el teléfono que graba | `sincro/GrabacionesDelMovil.kt` (el `Camaras` del PC, puro) y `sincro/GrabacionDelMovil.kt` (las cuatro llamadas de la grabadora, el anuncio a cada PC cada 30 s, la recepción); `CamaraRemota.Pregunta` (`suya`), `ConexionDeCamara.anunciarLaMia`, `QuienGraba` y `Respondedor.recibirParaLaGrabacion`; `Presencia` sigue escuchando mientras se graba |
| Las grabadoras | `ReunionPresencialService` y `GrabarClaseService`: `GrabacionDelMovil.empezar/pausa/acabar`; `GuardarLaGrabacion`: `guardada`/`tirar` |
| §14 invitar y el autor | `ReunionEnCursoActivity` (el interruptor), `OpcionesDelMensaje.Opcion.APORTAN_TODOS` / `ADJUNTAR_A_LA_GRABACION` y `FotosALaGrabacion`; `AutoresDeLaClase.kt` y la miniatura de `EstudiarClaseActivity` |

## El reloj de pared mientras se graba

El reloj de pared de la grabación y su diario son de «grabar sin perder nada»
(`guardados/RelojDeLaGrabacion.kt`): al terminar (o al recuperar tras un corte) la nota entra ya con su
`reloj`, huecos incluidos y con las claves en orden alfabético, byte a byte el del PC. Aquí
`GrabacionesDelMovil` lleva además el suyo (hora de pared, al día con el cronómetro de la grabadora
con 250 ms de tolerancia) solo para anunciar la grabación al PC y pasar sus capturas a su minuto; al
guardarse, **escribe `reloj` solo si la nota aún no lo lleva** (el del diario manda). Las grabadoras
llaman a `GrabacionDelMovil.empezar/pausa/acabar`; el guardado, en `GuardarLaGrabacion.clase/reunion`
(el mismo camino de «Terminar» y de recuperar), llama a `guardada` o `tirar`.

## Probado contra el PC de verdad

`herramientas/pc-simulado` (con los crates de `origin/main` 995fd94b) tiene órdenes nuevas:
`clase-reloj` (una clase del PC con `reloj`), `foto-hora` (una foto del PC con `tomadaEn`),
`repasar`; `responder` y `dirigir` repasan como el PC (y `dirigir` mide el reloj del teléfono); en
`camara`, el PC sabe su código y su almacén (por su hora, §12.4) y atiende `ajena` y `al-movil`
(«Captura para la grabación del móvil»). `FotosPorSuHoraConElPcTest`:

1. El PC graba y la foto del teléfono hecha sin conexión entra por su hora; la de fuera, no; lo
   escrito por el PC vuelve al teléfono, se reescribe y vuelve al PC sin perder nada.
2. El teléfono graba; la foto del PC (comprobada) y la de una colaboradora (sin comprobar) entran en
   la clase del teléfono; el PC se lo queda tal cual y no lo repasa.
3. La cámara sin grabación: dentro entra en su minuto con `fotoUid`; fuera, «no cae en ninguna»;
   repetida, una sola.
4. El teléfono anuncia su grabación (`suya`), el PC mide su reloj y le manda una captura que entra en
   el minuto 35; caducada la grabación ajena (90 s), no hay a quién mandar; la clase se guarda y
   viaja al PC con `fuente: "pantalla"`.

## Lo que necesita aparatos de verdad

- La cámara sin red de verdad (sin Wi‑Fi en la reunión) y la subida al volver.
- El botón del PC con la grabación del teléfono en otra Wi‑Fi, con el teléfono en segundo plano o con
  la pantalla apagada (Android puede dormir la red; `Presencia` sigue escuchando mientras se graba).
- Relojes de verdad desfasados (un teléfono con la hora a mano) y el EXIF de fotos de la galería de
  varias marcas (`OffsetTimeOriginal` no lo escriben todas).
- Un chat compartido con una persona de verdad que haga fotos durante la reunión.
