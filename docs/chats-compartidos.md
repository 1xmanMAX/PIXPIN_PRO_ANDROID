# Chats compartidos con otras personas (Android, 13-oct-2026) — y qué haría falta en el PC

El usuario: «añade una función nueva que sea los chats compartidos, a los que se puedan añadir
personas y hacer sincronización con ellos también solo en esos chats».

Hasta ahora sincronizar era **entre los aparatos de uno mismo**: el grupo comparte un código y
quien lo sabe lo ve todo. Un chat compartido es otra cosa: el chat de un proyecto que se lleva con
**otra persona** (un compañero de la carrera) y del que esa persona ve **ese chat y nada más** —ni
los otros proyectos, ni la conversación general, ni la galería—, y sin entrar en el grupo.

Código: `sincro/Compartidos.kt` (lo que decide, sin Android), `sincro/Invitacion.kt` (entrar con un
código y la vuelta con una persona), `Canal.abrirEntrePersonas`, y en `Protocolo.kt`
`Respondedor.atenderPersona/atenderInvitacion` y `Sesion.conectarConPersona/juntarMiembros/
compartidosDelGrupo`. Pantalla: `sincro/ChatCompartidoActivity.kt`. Pruebas:
`ChatsCompartidosTest` (las listas y las firmas) y `ChatsCompartidosDeVerdadTest` (dos carpetas
por un socket).

## Por qué así

- **Claves por aparato, no un código por chat.** Con un código compartido, a quien se quita le
  seguiría valiendo (lo sabe) y habría que repartir otro sin poder decírselo a nadie por un canal
  seguro. Con claves, cada aparato tiene su par P-256 (`files/sincro/persona.json`, la privada no
  sale nunca) y la lista de cada chat lleva la **pública** de cada miembro: quitar a alguien es
  quitarlo de la lista, y deja de entrar.
- **La lista viaja firmada.** No hay servidor que diga quién está: cada aparato junta las listas
  que le llegan. Para que un miembro no pueda echar a otro ni cambiarle la clave, **cada entrada la
  firma quien invita** y **cada salida la firma la propia persona (se fue) o quien creó el chat (la
  quitó)**. Lo que no lleva una firma buena no se cree. Juntar es conmutativo e idempotente: da lo
  mismo el orden y por qué aparato llegue cada cosa.
- **La valla de archivos.** El que responde solo deja tocar los chats donde estáis **los dos**
  dentro, y además nunca da, escribe ni deja borrar un archivo que sea de **otro** chat suyo, aunque
  un mensaje del chat compartido lo nombre (un mensaje trampa que señale `pins/draw/privado…`).
  Lo mismo al revés: lo que llega de una persona y nombra algo de otro chat se tira.
- **Mis aparatos son yo.** Mi tableta (del grupo) entra sola en los chats que comparto: al
  sincronizar el grupo, cada aparato mete al otro en sus chats compartidos, firmándolo. A la otra
  persona se lo cuenta el primero de mis aparatos que hable con ella.
- **Quién escribió cada mensaje**: por `Mensaje.aparato` (el código fijo del aparato, `K7Q2`), que
  ya viajaba. La lista dice de cada aparato el nombre de su persona. No hace falta campo nuevo.

## Lo que se guarda (local; viaja por el cable, no como archivo)

`<datos>/sincro/compartidos.json`: una lista de chats compartidos.

```json
[{"chat":"<id del proyecto>","nombre":"Tesis","creador":"<id aparato>","creado":1791000000000,
  "personas":[{"id":"<id aparato>","nombre":"Ana","codigo":"K7Q2","clave":"<X.509 base64>",
    "desde":1791000000000,"invito":"<id aparato>","firma":"<base64>",
    "fuera":0,"quito":null,"firmaFuera":null}]}]
```

| Campo | Qué es |
|---|---|
| `id` | El `id` del aparato (el de la identidad). Una fila por aparato. |
| `nombre` | El nombre de la persona («Ana»). |
| `codigo` | `Aparato.codigo` (cuatro signos del SHA-256 del id): el que llevan sus mensajes en `aparato`. |
| `clave` | Su clave pública EC P-256, X.509 DER en base64. |
| `desde` / `invito` / `firma` | Cuándo entró la última vez, quién lo metió (él mismo si es el creador) y la firma de ese. |
| `fuera` / `quito` / `firmaFuera` | Cuándo salió, quién lo sacó (él mismo o el creador) y su firma. 0 = nunca. |

Está **dentro** quien tiene `fuera < desde`.

Firmas: ECDSA `SHA256withECDSA` (DER, base64) sobre estos textos UTF-8:

```text
entrada: pixpin-chat|entra|<chat>|<id>|<clave>|<desde>|<invito>|<nombre>|<codigo>
salida:  pixpin-chat|sale|<chat>|<id>|<fuera>|<quito>
```

**Juntar** (`Compartidos.juntar`): por `id`. Si llega un `id` con otra `clave`, se ignora. De cada
uno se queda la entrada bien firmada con `desde` mayor y la salida bien firmada con `fuera` mayor.
Una entrada vale si la firma `invito` (que tiene que estar ya en la lista) o, si `invito == id`, si
es el creador del chat firmándose a sí mismo. Una salida vale si la firma el propio aparato o el
creador. `creador` y `nombre` del chat son los que ya había (los del primero que llegó).

`<datos>/sincro/persona.json`: `{"publica","privada" (PKCS#8 base64),"nombre"}`.

## El cable (sin subir el protocolo: sigue en `VERSION = 4`)

### Con un aparato del grupo (opcional)

Después de los chats, en la misma conexión, quien dirige manda dos veces:

```text
→ {"t":"compartidos","compartidos":"[<mis listas>]","persona":"{<mi ficha: id,nombre,codigo,clave>}"}
← {"compartidos":"[<las suyas, ya juntadas>]","persona":"{<su ficha>}"}   (o {"error":"No sé qué es «compartidos»"}: se deja)
```

Cada lado junta todo (también chats que no tenía: son míos) y mete al otro aparato, firmando, en
los chats donde él está dentro y el otro no consta. Un PixPin de antes (o el PC de hoy) contesta
«No sé» y la vuelta sigue.

### Con una persona

Es otra conversación, en el **mismo puerto** que la sincronización: el que escucha mira los cuatro
primeros bytes. `PXS1` es el grupo de siempre; `PXC1`, una persona; `PXI1`, una invitación.

Apertura (`Canal.abrirEntrePersonas`), los dos lados a la vez:

```text
"PXC1" · u16 largo · clave pública X.509 · 32 bytes al azar
```

Si la clave del otro no está en la lista de ningún chat donde también consto yo, se corta. Si sí:
`secreto = ECDH(mi privada, su pública)`;
`sesion = HMAC-SHA256(secreto, "personas" ‖ azarDelQueLlama ‖ azarDelQueResponde ‖ claveDelQueLlama ‖ claveDelQueResponde)`;
`ida`/`vuelta` = `HMAC(sesion,"ida"/"vuelta")[0..32]`, y los tramos van con AES-GCM igual que con
el grupo. Quien no tiene la privada no descifra el primer tramo.

Luego, como siempre, `hola` (el `id` del saludo tiene que ser el de la ficha de esa clave) y:

```text
→ {"t":"miembros","compartidos":"[<listas de los chats donde constamos los dos>]"}
← {"compartidos":"[<las suyas de esos chats, juntadas>]"}
```

y por cada chat donde estamos **dentro los dos**: `inventario`, `mensajes`, `aplicar`, `archivos`,
`dame`, `damecambios`, `pon`, `parche`, `base` como con el grupo. El que responde:

- `catalogo` solo da esos chats; `lapidas`, ninguna.
- `borrarchat`, `suelto`, la galería y `compartidos`: «No sé qué es».
- Cualquier petición con un chat que no sea de esos: error.
- `dame`/`damecambios`: solo archivos del alcance de ese chat **que no estén en el de ningún otro
  chat suyo**. `pon`/`parche`: lo mismo, y si el archivo ya existe tiene que ser de ese chat. A un
  `pon` rechazado se le leen los trozos y se contesta error (la conversación sigue sana).
- `aplicar`: tira los mensajes que nombran archivos de otros chats o que llevan el código único de
  un mensaje de otro chat, y el proyecto si no es el de ese chat; al borrar, no tira archivos de
  otros chats.

El que dirige hace lo mismo con lo que trae, y un error del otro en un archivo lo salta (en su
aparato puede ser de otro chat suyo) en vez de cortar la vuelta.

### La invitación

Quien invita abre **otro** puerto mientras tiene la pantalla delante, anunciado como
`_pixpinchat._tcp.` con `g = etiqueta(clave("chat-"+código))`, y enseña el código (8 signos de
`Grupo.SIGNOS`, `K7Q2-M9XP`) y el QR `pixpin-chat:1:<código>:<ip>:<puerto>`.

```text
canal: Canal.abrir con magia "PXI1" y clave = Grupo.clave("chat-" + código)   (PBKDF2, como el grupo)
→ {"t":"unirme","hola":{…},"persona":"{<mi ficha>}"}
← {"hola":{…},"compartidos":"[<la lista del chat, conmigo dentro firmado por él>]"}
→ {"t":"adios"}  ← {}
```

Quien entra comprueba que de verdad está dentro con una firma buena y abre una conversación de
persona (`PXC1`) al mismo puerto para traerse el chat. Desde ahí, se reconocen por las claves.
Cinco códigos equivocados cierran la invitación.

## Lo que haría falta en el PC

1. Su par de claves P-256 y `compartidos.json`, con el mismo `juntar` (las pruebas de
   `ChatsCompartidosTest` son la referencia: orden indiferente, firmas falsas fuera, clave ajena fuera).
2. Contestar `compartidos` en la vuelta del grupo (si no, sigue funcionando: los chats compartidos
   viajan como cualquier chat entre mis aparatos, pero el PC no entra en la lista).
3. Atender `PXC1` y `PXI1` en su puerto, con la valla de archivos de arriba; y llamar él a las
   personas.
4. En la conversación, el nombre encima de los mensajes de otros (`aparato` → `nombre`).

## Sin probar todavía

- Nada de esto en teléfonos de verdad: solo en la JVM, dos y tres carpetas por un socket.
- El descubrimiento por la red de una persona sin grupo (se anuncia sin `g`, con su `id`).
- La sincronización automática con personas (mismo reloj que el grupo, por su última dirección).
