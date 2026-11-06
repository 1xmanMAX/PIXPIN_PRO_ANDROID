# El acceso de prueba al asistente (Android y Windows)

Manual para que PinDesk de Windows haga lo mismo que el teléfono (Android desde la v0.170.0). Pedido del usuario
(6-nov-2026): «que la IA venga funcionando desde que lo instalen, para una fase de prueba; que inicien sesión con su
correo y solo tengan acceso los de la lista; luego los bloqueamos».

## 1. La idea

- **La clave de Claude no va en las apps.** Vive en un servidor (un Cloudflare Worker, `servidor/ia-de-prueba` del
  repositorio de Android). Una clave dentro de un programa que se reparte se saca en minutos.
- La persona **entra con su cuenta de Google**. El servidor comprueba el `id_token` de Google y, si el correo está en
  la lista (`PERMITIDOS`), le da una **sesión** (30 días).
- Con la sesión, la app pregunta **al servidor** lo mismo que preguntaría a Anthropic (`/v1/messages`, igual cuerpo,
  igual respuesta y el mismo `stream`); el servidor pone la clave y reenvía. Un cupo de preguntas por persona y día.
- Quitar un correo de la lista lo deja sin acceso **en la siguiente pregunta**. Borrar el Worker cierra la prueba.
- **La clave propia manda**: quien tenga su clave de Claude en Ajustes la sigue usando; la sesión solo se usa sin ella.

## 2. El servidor (lo mismo para los dos)

| Ruta | Pide | Devuelve |
|---|---|---|
| `POST /entrar` | `{"idToken": "<id_token de Google>"}` | `200 {"sesion", "email", "dias"}` · `403 {"error":"sin_acceso","email"}` · `401` token no válido |
| `GET /yo` | `Authorization: Bearer <sesion>` | `{"email", "quedan"}` (preguntas que quedan hoy, o `null` sin cupos) |
| `* /v1/...` | `Authorization: Bearer <sesion>`, `anthropic-version` | lo que conteste `https://api.anthropic.com/v1/...` |

- `401 {"error":"sin_sesion"}`: sesión caducada o mal firmada → volver a entrar.
- `403 {"error":"sin_acceso"}`: el correo ya no está en la lista → decir «la fase de prueba terminó para esta cuenta».
- `429` con `rate_limit_error`: el cupo de hoy, agotado (el mismo formato de error que Anthropic).
- El `id_token` vale si: firma RS256 con los certificados de Google, `iss` = `accounts.google.com`, `aud` es uno de
  `GOOGLE_CLIENT_IDS` (el ID **web** que usa Android y el de **escritorio** de Windows), no caducado y
  `email_verified`.

## 3. Lo que hace Android (de referencia)

- `asistente/AccesoDePrueba.kt`: «Entrar con Google» con Credential Manager (`GetSignInWithGoogleOption` con el ID de
  cliente **web**, que hace que el `aud` del token sea ese ID), `POST /entrar`, y la sesión en
  `noBackupFilesDir/acceso-de-prueba.json`.
- `ClavesDeLaNube.leer`: la clave propia; si no hay, `"pxt:" + sesion`; si no, nada.
- `ia/Nube.kt`: con una clave que empieza por `pxt:`, la petición va a `<servidor>/v1/...` (el mismo camino de
  Anthropic con el servidor delante) con `Authorization: Bearer <sesion>` y `anthropic-version`, sin `x-api-key`.
- La dirección del servidor y el ID web, en la compilación (`BuildConfig.SERVIDOR_IA_DE_PRUEBA`,
  `BuildConfig.GOOGLE_CLIENTE_WEB`, de las variables del repositorio). Vacíos: no sale nada nuevo.
- Interfaz: en la ficha del chat «Asistente», una sección «Fase de prueba» con «Entrar con Google» o, ya dentro, el
  correo y «toca para salir».

## 4. Lo que tiene que hacer Windows

1. **Entrar con Google en el escritorio** (OAuth 2.0 para apps instaladas, con PKCE y el puerto de vuelta en
   `127.0.0.1`):
   - El ID de cliente de tipo **«App de escritorio»** (va en el programa; su «secreto» de escritorio no es secreto de
     verdad para Google en este tipo y puede ir dentro).
   - Abrir en el navegador
     `https://accounts.google.com/o/oauth2/v2/auth?client_id=<ID>&redirect_uri=http://127.0.0.1:<puerto>&response_type=code&scope=openid%20email&code_challenge=<S256>&code_challenge_method=S256`,
     escuchar en ese puerto el `code` y cambiarlo en `https://oauth2.googleapis.com/token` (con `code_verifier`) por
     el `id_token`.
   - `POST <servidor>/entrar {"idToken": …}` y guardar la sesión **cifrada con DPAPI** (como las claves de la nube).
2. **Preguntar por el servidor**: donde hoy `pindesk_ia::nube` pone `x-api-key`, si no hay clave propia y hay sesión:
   la misma petición a `<servidor>/v1/messages` con `Authorization: Bearer <sesion>` y `anthropic-version`. El resto
   (el `stream`, los errores, reintentos) no cambia: el servidor devuelve lo de Anthropic tal cual.
3. **Ajustes → Asistente**: «Entrar con Google (fase de prueba)», el correo con «Salir», y los avisos de `403`
   («la prueba terminó para esta cuenta») y `429` («cupo de hoy agotado»).
4. **La dirección del servidor** y el ID de escritorio, en la compilación del PC (sin ellos no sale nada nuevo).

## 5. Al terminar la prueba

- En Cloudflare: vaciar `PERMITIDOS` o `npx wrangler delete`; revocar la clave de Claude en su consola.
- En las apps: quitar las variables de compilación (`SERVIDOR_IA_DE_PRUEBA`, `GOOGLE_CLIENTE_WEB` y el ID de
  escritorio); la sección «Fase de prueba» desaparece sola.
