# La galería de capturas que viaja (Android v0.107.0) — y qué haría falta en el PC

8-oct-2026. El usuario: «quiero que se sincronice la galería con sus tiempos de desaparición y
todo completo».

Desde v0.107.0, **dos teléfonos** del mismo grupo tienen la misma galería: cada captura pasa al
otro con su fecha de verdad y **se va el mismo día en los dos**; conservar o «7 días más» en uno
se ve en el otro; quitar una a mano antes de tiempo la quita de los demás (a su papelera).

**El PC entra desde su commit c493abb (9-oct)** (`crates/pixpin-sincro/src/galeria.rs`, puerto de
estas reglas). Probado el 10-oct con el PC de verdad en `SincronizarConElPcTest` (cinco pruebas
`galeria…`, llamando cada uno de los dos): pasa en los dos sentidos con su hora, se va el mismo
día aunque cada uno tenga otros días, «7 días más» y conservar se ven en el otro, lo quitado a
mano va a la papelera del otro y lo caducado no deja marca. Un aparato de antes sigue contestando
«No sé qué es «galeria»» (`GaleriaQueViajaTest`). Lo de abajo es la guía que se escribió para el PC.

## Por qué así

- **Fecha exacta, no «a los N días».** Una captura llega a cada aparato otro día y cada aparato
  puede tener otros «días hasta borrar»: con la regla, se iría en días distintos. Viaja `seVa`
  (ms UTC) y se apunta en el registro de caducidad como **`fijadas`** (campo nuevo, opcional, solo
  de Android: el registro no viaja), que manda sobre la regla y las prórrogas.
- **Caducar no es borrar.** Cada aparato barre lo suyo con esa fecha; no hace falta avisar. Solo
  lo quitado a mano **antes de tiempo** deja una marca (`borrada`) que viaja.
- **Solo se marca lo que este aparato tuvo.** `tenia` (local) guarda lo que había la última vez:
  lo que no está porque nunca llegó, o porque Android no deja listarlo, no cuenta. Si listar
  falla, no se toca nada.
- **Lo más reciente gana** (`cambiado`); a la misma hora, borrada > conservada > fecha más lejana.
  Igual en los dos sentidos.

## Lo que se guarda (`<datos>/galeria-compartida.json`, local)

```json
{"entradas":[{"nombre":"PixPin_20261008_101500.png","cuando":1791000000000,"bytes":48213,
  "mime":"image/png","seVa":1791604800000,"cambiado":1791000000000,"de":"id-del-aparato"}],
 "tenia":["PixPin_20261008_101500.png"]}
```

| Campo | Qué es |
|---|---|
| `nombre` | El nombre del fichero; es la clave. Sin rutas. |
| `cuando` | Cuándo se hizo (ms UTC), en el aparato donde se hizo. Quien la recibe la guarda con esa hora (`DATE_TAKEN` en Android). |
| `seVa` | Cuándo se va (ms UTC). Ausente y no conservada: no se va. |
| `conservada` | `true`: no se va nunca (en Android, además, está en el chat). |
| `borrada` | `true`: quitada a mano antes de tiempo; se quita en todos. |
| `cambiado` | Cuándo cambió lo de arriba por última vez. |
| `de` | El `id` del aparato donde se hizo (solo informativo). |

Los campos con su valor por omisión no se escriben (`encodeDefaults = false`).

## El cable (sin subir el protocolo: sigue en `VERSION = 4`)

Detrás de los chats, en la misma conexión, quien dirige:

```text
→ {"t":"galeria"}
← {"galeria":"[<entradas en JSON>]","tengo":["a.png","b.png"]}      (o {"error":"No sé qué es «galeria»"}: se deja)
→ {"t":"galeriajunta","parche":"[<entradas juntadas>]"}
← {}                                       el otro guarda las entradas, apunta fechas y tira las borradas
→ {"t":"damecaptura","ruta":"a.png"}        por cada una que me falta y él tiene
← {"bytes":N} + TROZO… + {"resumen":…}       (o {"error":…}: se salta)
→ {"t":"poncaptura","ruta":"b.png","bytes":N} + TROZO… + {"resumen":…}   por cada una que le falta
← {"saltado":false}
```

Solo pasan las **vivas** (ni borradas ni caducadas). `ruta` es el nombre, saneado: se rechaza
cualquier cosa con `/`, `\`, `..` o vacía.

## Qué haría falta en el PC

1. Su registro (`caducidad_capturas.rs`) con `fijadas: BTreeMap<String, i64>` opcional
   (`#[serde(default, skip_serializing_if = "BTreeMap::is_empty")]`) y `se_va_el` mirándolo
   después de `conservadas` y antes de la regla; «7 días más» mueve la fijada si la hay.
2. El estado compartido y sus reglas: es un puerto directo de `capture/GaleriaCompartida.kt`
   (`al_dia`, `juntar`, `aplicar`, `a_tirar`, `que_traer`, `que_mandar`), con las mismas pruebas
   que `GaleriaCompartidaTest`.
3. En el `Respondedor` las cuatro peticiones y en la vuelta el paso de quien dirige
   (`Sesion.galeria` en `sincro/Protocolo.kt`).
4. Al guardar una captura que llega, ponerle de fecha `cuando` (la galería del PC agrupa por día).
5. Una precaución propia del PC: allí los nombres `captura-NNNN` **se reutilizan**. Antes de
   juntar habría que darles un nombre único (p. ej. con la hora) o la entrada de una captura
   vieja se confundiría con la nueva.

## Dónde está en Android

- `capture/GaleriaCompartida.kt` — las reglas, sin Android.
- `capture/CaducidadDeCapturas.kt` — `fijadas`.
- `sincro/GaleriaQueViaja.kt` — `CapturasDelAparato` (la interfaz) y los pasos al día / aplicar.
- `capture/CapturasEnElTelefono.kt` — MediaStore (`Pictures/PixPin`, `DATE_TAKEN`).
- `sincro/Protocolo.kt` — `Respondedor.responderGaleria` y `Sesion.galeria`.
- Pruebas: `GaleriaCompartidaTest` (10), `GaleriaQueViajaTest` (6, dos aparatos por socket),
  `SincronizarConElPcTest` (contra el PC de verdad: `pc-simulado` tiene galería en una carpeta,
  `<datos>-galeria`, con `atender_con_galeria` y `con_galeria`; `pc-simulado galeria` la enseña).

**Sin probar en teléfono**: guardar en MediaStore con `DATE_TAKEN`, la papelera del sistema al
quitarla en otro aparato, y vídeos.
