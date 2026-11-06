# El servidor de la IA de la fase de prueba

La clave de Claude vive aquí y no en las apps. Quien prueba entra con su cuenta de Google; si su correo está en la
lista, el asistente de PinDesk (Android y Windows) contesta a través de este servidor. Para cerrar la prueba se vacía
la lista o se borra el Worker: nadie más entra, sin sacar otra versión.

## Ponerlo en marcha (una vez, unos 15 minutos)

### 1. Cloudflare

1. Crea una cuenta gratis en https://dash.cloudflare.com.
2. En tu ordenador, con Node instalado, desde esta carpeta (`servidor/ia-de-prueba`):

   ```sh
   npx wrangler login
   npx wrangler kv namespace create CUPOS     # copia el id que sale y pégalo en wrangler.toml (quita los #)
   npx wrangler secret put CLAUDE_API_KEY      # la clave de Claude (sk-ant-…). Mejor una solo para esto, con tope de gasto
   npx wrangler secret put PERMITIDOS          # los correos, separados por comas: ana@gmail.com,luis@gmail.com
   npx wrangler secret put SECRETO_DE_SESION   # una frase larga al azar (no hace falta recordarla)
   npx wrangler deploy
   ```

   `deploy` dice la dirección, del estilo `https://pindesk-ia-de-prueba.<tu-usuario>.workers.dev`.

### 2. Google (los ID de cliente para «Entrar con Google»)

En https://console.cloud.google.com → un proyecto nuevo → «APIs y servicios» → «Pantalla de consentimiento de OAuth»
(tipo Externo, en modo de prueba; añade como «usuarios de prueba» los mismos correos de la lista). Luego «Credenciales» →
«Crear credenciales» → «ID de cliente de OAuth», tres veces:

1. **Aplicación web** (nombre: PinDesk servidor). Su ID es el que va en `GOOGLE_CLIENTE_WEB` de Android.
2. **Android**: paquete `com.forge.pixpin` y la huella **SHA-1** de la clave con que se firma el APK. Sale en el
   registro de la acción «APK de prueba» de GitHub (paso «Clave de depuración»). Con la clave de prueba del repositorio
   es `54:4D:B7:3C:4B:EC:33:97:E3:BE:D0:62:CC:9B:64:B5:0F:15:EC:89`.
3. **App de escritorio** (para Windows). Su ID y su «secreto» los usa el PC (ver `docs/acceso-de-prueba-ia.md`).

Y en Cloudflare, los que valen:

```sh
npx wrangler secret put GOOGLE_CLIENT_IDS   # el ID web y el de escritorio, separados por comas
```

### 3. GitHub (para que el APK lo traiga)

En el repositorio → Settings → Secrets and variables → Actions → **Variables** (no secretos):

- `SERVIDOR_IA_DE_PRUEBA`: la dirección del Worker (`https://pindesk-ia-de-prueba.<tu-usuario>.workers.dev`).
- `GOOGLE_CLIENTE_WEB`: el ID de cliente **web** del paso 2.1.

Y se lanza «APK de prueba». En el chat «Asistente» → su ficha → «Entrar con Google».

## Durante la prueba

- **Añadir o quitar a alguien**: `npx wrangler secret put PERMITIDOS` con la lista nueva. Quitado, no entra más.
- **Cupo**: `CUPO_DIARIO` en `wrangler.toml` (200 preguntas por persona y día) y `npx wrangler deploy`.
- **Terminar la prueba**: `npx wrangler delete` (o vaciar `PERMITIDOS`), y revocar la clave de Claude en su consola.

## Probarlo sin Cloudflare

`node prueba/prueba.mjs`: la sesión, la lista, el cupo y el paso a Claude (con un Claude de mentira).
