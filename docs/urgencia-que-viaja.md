# La revisión de urgencia que viaja (4-nov-2026)

Manual para que el PC haga lo mismo que el teléfono (Android desde la v0.161.0).

> El usuario: «Si una tarea ya fue revisada (normal, alta, poco urgente…), que no se vuelva a hacer. Lo
> revisado en el PC se sincroniza al celular y ya no se hace allí; si la tarea se creó en el celular, se
> revisa al momento, se pone su etiqueta y eso viaja al PC. Sin gastar tokens.»

## 1. El fichero

Uno por lista de tareas (el mensaje del chat), al lado de lo demás anotado de ese mensaje:

`pins/draw/anot-<uid del mensaje>.urgencia.json`

Viaja con el chat de la lista como `.comentarios.json` o `.marcos.json`: en el PC hay que añadir
`.urgencia.json` a `TERMINACIONES` de `pixpin-sincro/src/anotado.rs` (en Android,
`AnotacionesDelAdjunto.TERMINACIONES`).

```json
{
  "version": 1,
  "tareas": [
    {
      "texto": "Mandar la cotización (al cliente)",
      "consecuencia": [0.05, 0.15, 0.5, 0.3],
      "confianza": 0.6,
      "bloquea": 0.8,
      "plazo_ajeno": 0.9,
      "piden_ya": 0.2,
      "cuando": 1793582613265,
      "aparato": "pc1"
    }
  ]
}
```

- `texto`: exactamente lo que se le manda a Jev (`texto_para_jev`: el título sin marcas y, si tiene,
  su nota entre paréntesis). Es la clave. Si el texto cambia es otra tarea y se pregunta otra vez.
- Se guardan **las señales**, no la prioridad: `consecuencia` es la probabilidad de cada nivel del Score
  (0 a 3), `bloquea`, `plazo_ajeno` y `piden_ya` el Noul (0 a 1). `piden_ya` falta si se preguntó sin
  mensajes del chat. La prioridad (urgente, alta, normal, baja) se sigue calculando gratis en cada
  aparato con la tabla de `urgencia.rs` y los días que faltan, que cambian cada día.
- `cuando`: ms UTC de la respuesta; `aparato`: el código del aparato que preguntó.
- Números redondeados a diezmilésimas. JSON con sangría de dos espacios y `\n` al final; las tareas
  ordenadas por `texto`. Lo que no se conoce (otras claves de arriba) se conserva.

## 2. Leer y escribir

1. Antes de preguntar a Jev por una tarea, mirar el fichero de su lista. Si su `texto` está, usar esas
   señales y **no preguntar**, venga de este aparato o de otro.
2. Al recibir respuesta, añadirla al fichero de su lista, juntando con lo que haya en disco.
3. Juntar: por `texto`; la misma tarea en los dos, gana la de `cuando` mayor y, a igualdad, la de
   `aparato` mayor (comparación de texto). Conmutativo e idempotente.
4. Un JSON roto no se escribe encima. Si nada cambia, no se reescribe.
5. Los temas de una reunión (`temasLista`) no se preguntan ni se guardan.

## 3. Cuándo se pregunta

- **Las listas nacidas en este aparato** (`aparato` del mensaje igual al de aquí, o sin aparato): al
  cambiar los mensajes, pasados 5 s, sin esperar a abrir Tareas.
- **Las de otro aparato**: solo al abrir Tareas y si su revisión aún no ha llegado. Quien la creó ya
  la hizo, y suele llegar en la misma vuelta de la sincronización.
- Tras un fallo, esa tarea no se vuelve a preguntar en 10 min (como antes).
