# Descubrir rápido y prioridad al grabar, en Android (28-oct-2026)

Puerto del §11 de `docs/movil-como-camara.md` del PC (`pixpin_pro_windows`,
`origin/main` 995fd94b; código de referencia `crates/pixpin-sincro/src/baliza.rs`
y `apps/pixpin/src/sincronizar/baliza.rs`).

El usuario: «con las clases grabadas, el MÓVIL tarda demasiado en saber si el PC
está grabando, y no puedo hacer fotos rápido… que localice cualquier aparato de
la red rápido y simple. Perder algo sería perder una reunión».

## Qué cambia para quien lo usa

- Con PixPin abierto y el PC empezando a grabar, la tira «Cámara para la reunión
  del PC» sale **en un segundo** (antes, hasta 20 s o lo que tardara el mDNS),
  con el minuto de la grabación mientras llega el anuncio completo.
- Si el DHCP le dio otra dirección al PC (o al teléfono), se arregla sola al
  oír la siguiente baliza: no hace falta volver a buscarlo.
- Las fotos para la grabación del PC empiezan a subir **en menos de un segundo**:
  van por una conexión que ya está abierta, sin saludo ni PBKDF2. Si la Wi-Fi se
  corta, esperan en el teléfono y salen solas al volver.
- Cuando el teléfono graba («Grabar clase» o la reunión presencial), el PC lo
  sabe al momento y cada segundo.
- Mientras alguien del grupo graba, la sincronización automática **no arranca
  nada** (ni vueltas, ni tareas, ni chats compartidos): solo la cámara. Al
  acabar, la grabación sale primero y lo demás ya, sin esperar los 10 minutos.
- Grabando 20 s sin que aparezca nadie del grupo, en «Actividad» de
  Sincronizar sale una vez por qué puede ser (otra red, o una Wi-Fi que aísla a
  los aparatos).

## El cable (idéntico al PC)

`sincro/Baliza.kt`. UDP **47475**, multidifusión **239.255.74.74** (TTL 1),
difusión `255.255.255.255` y la de cada red, y directa. Binario big-endian:

| Bytes | Campo |
| --- | --- |
| 0–3 | `PXB1` |
| 4 | versión 1 |
| 5 | banderas: bit0 grabando, bit1 en pausa, bit2 pregunta, bit3 cámara |
| 6–13 | etiqueta del grupo en crudo (`HMAC(clave, "etiqueta")[..8]`, la `g` del mDNS) |
| 14–15 | puerto TCP de la sincronización |
| 16–23 | reloj (ms desde 1970) |
| 24–27 | secuencia (`u32`) |
| 28–35 | `ms_actual` |
| 36… | largo + id del aparato (1–64), largo + id de la grabación (0–64) |
| últimos 16 | `HMAC-SHA256(HMAC(clave, "baliza"), todo lo anterior)[..16]` |

Al leer, en el orden del PC: magia/versión/largos (55–182) → etiqueta (otro
grupo: ni se calcula la firma) → firma en tiempo constante
(`MessageDigest.isEqual`) → textos UTF-8 estrictos → no es mía → no repetida
(`Oidas`: `(reloj, secuencia)` mayor que la última de ese id y
`|reloj − ahora| ≤ 10 min`).

**Compatibilidad comprobada**:

- `BalizaTest`: el vector del PC (`K7Q2M9XMPA`, `pc-portatil`…), byte a byte
  en los dos sentidos, y las mismas pruebas que `mod pruebas` de `baliza.rs`
  (firma, recortes UTF-8, repetidas, redes, barrido, ritmo, prioridad,
  diagnóstico), más los sockets de verdad en loopback.
- `BalizaContraElPcTest` (con `PIXPIN_PC_SIMULADO`): `pc-simulado baliza …`
  usa el `pixpin_sincro::baliza` de verdad. Lo que escribe Android lo lee el PC
  y al revés (cuatro balizas: la del vector, el teléfono grabando en pausa con
  letras de dos bytes, una pregunta sin puerto y la más larga, 182 bytes); lo
  mal firmado lo tira el PC; por UDP de verdad en los dos sentidos (`Emisor` y
  `Oyente` del PC contra `EmisorDeBalizas`/`OyenteDeBalizas`); y el barrido de
  seis redes sale igual.

## Los tiempos

| Cuándo | Qué |
| --- | --- |
| Al abrir PixPin, al volver la red | una con **pregunta**, al momento |
| PixPin a la vista o un servicio vivo, sin grabar | cada 15 s |
| El teléfono graba | cada 1 s, y al momento al empezar, pausar, seguir o acabar |
| Alguien pregunta | contestar directa al momento (una por segundo por IP) |
| «Sigue grabando» | hasta 4 s tras la última baliza con grabando |
| Grabando sin oír a nadie 5 s | barrido de la red (§11.4), cada 10 s |

## Las piezas

- `Baliza.kt`: formato, firma, `Oidas`, `Interfaz`, `barrido`, `Ritmo`,
  `Trabajo`/`queToca`, `diagnosticar`. Puro.
- `SocketsDeLaBaliza.kt`: `EmisorDeBalizas` (un socket por red, para que la
  difusión salga por cada una), `OyenteDeBalizas` (`0.0.0.0:47475` con
  `reuseAddress`, `joinGroup` en cada red) y `RedesDelAparato` (las IPv4 con su
  prefijo, la Wi-Fi primero, sin datos móviles: lo de `pixpin-shell::red`).
- `BalizaDelMovil.kt`: los dos hilos (`pixpin-baliza`, `pixpin-baliza-oye`).
  Viven mientras haya un motivo: PixPin a la vista (`Presencia`), una grabación
  (`GrabacionParaLaBaliza`) o el servicio de subida de fotos. El
  `WifiManager.MulticastLock` (`setReferenceCounted(false)`) se coge al oír y
  se suelta al parar. Al oír una del grupo (§11.5): apunta la IP **del
  datagrama** y el puerto, avisa a la automática de que está, contesta si
  pregunta y, si el PC graba con cámara, pinta la tira
  (`CamaraDelPc.alOirBaliza`) y aviva la conexión caliente.
- `GrabacionParaLaBaliza.kt`: el enganche de `GrabarClaseService` y
  `ReunionPresencialService` (empezó / pausa o sigue / acabó / guardada). El id
  de la grabación del teléfono es `m-<hora de empezar>`. La bandera «cámara» va
  apagada: el teléfono aún no recibe fotos en su grabación por este camino.
- `ConexionCaliente.kt` (§11.7): `PlanCaliente` (puro) y la conexión con el PC
  que graba: `hola` con `camara1`, `camara_remota` cada 3 s (keepalive y reloj,
  `CamaraDelPc.alResponder`), cada foto un `suelto` más por la misma conexión,
  reabrir con 0,5, 1, 2… s hasta 5 s (o ya, con la siguiente baliza), `adios`
  al acabar con la cola vacía. `SubidaDeLaCamaraService` sube por ella si está
  abierta; las dos van de una en una (`ConexionCaliente.SUBIDA`), así que una
  foto nunca sale dos veces a la par. La cola (`ColaDeLaCamara`) sigue en
  disco: nada se pierde si se corta.
- `Prioridad.kt` → `ModoPrioritario` y `RelojDeSincro.acaboDeGrabar`: el
  §11.6 en `SincronizacionAutomatica`.

## Lo que no hace (aún)

- Darse por **ocupado** mientras el teléfono graba (el PC sí lo hace): quien
  llame para sincronizar con el teléfono que graba sigue siendo atendido.
- §11.5 punto 5 (mandar el anuncio de la cámara al aparecer alguien mientras el
  teléfono graba): es de «De la PC a la grabación del móvil» (§13).

## Probar con aparatos de verdad

PC y teléfono en la misma Wi-Fi, el PC con 995fd94b o posterior:

1. Abrir PixPin en el teléfono y empezar a grabar en el PC: la tira debe salir
   en ~1 s.
2. Con la pantalla del teléfono **apagada** y la cámara del PC abierta antes,
   hacer fotos y ver que llegan en < 1 s (logcat `PixPinCaliente`).
3. Cambiar la IP del teléfono (olvidar y volver a la Wi-Fi) grabando: la
   dirección se corrige sola (logcat `PixPinBaliza`, «dirección nueva»).
4. Grabar en el teléfono y mirar en el PC «Grabando en otro aparato del grupo».
5. Una Wi-Fi de invitados (que aísla): a los 20 s el aviso en «Actividad».
6. Con la Wi-Fi marcada como «Pública» en Windows, la baliza no entra en el PC
   (§11.9): `herramientas\cortafuegos-sincro.ps1` en el PC.
