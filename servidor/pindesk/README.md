# El servidor de PinDesk

Cuentas con Google, **qué funciones ve cada persona y si las puede usar** (el catálogo y su licencia, **firmados**), la
versión de la app para que se actualice sola, y el asistente (Claude) con un cupo por persona y día. Sin dependencias:
solo Node 22. Los datos, en archivos JSON en `/var/lib/pindesk`.

## Cómo es de seguro

- **Todo lo que decide va firmado** (ECDSA P-256, como los certificados de los bancos). La clave privada (`firma.pem`)
  solo está en la VPS; la app lleva la pública. Si alguien cambia algo por el camino (el «hombre en el medio») o se
  inventa una licencia, la firma no cuadra y la app no la acepta. Las pruebas lo comprueban (`prueba/`).
- **HTTPS siempre** (Caddy pone y renueva el certificado solo). El servidor de Node solo escucha en `127.0.0.1`: desde
  fuera solo se ve Caddy, y el cortafuegos deja pasar solo SSH y la web.
- **Lo que cuesta se decide aquí**: cada pregunta al asistente comprueba la sesión, el plan, que el asistente esté
  abierto en el catálogo y el cupo del día. La clave de Claude nunca sale de la VPS; el modelo y el tope de respuesta
  los pone el servidor.
- **Las sesiones** van firmadas (HMAC); bloquear una cuenta en el panel las corta al momento.
- **El panel** pide la clave del panel y un código de 6 cifras de Google Authenticator (cambia cada 30 s). Se puede
  limitar además a tu IP (ver `despliegue/Caddyfile`).
- Frena a quien prueba muchas veces (entrar y el panel).

## Lo que necesita tu VPS

- **Ubuntu 22.04/24.04 o Debian 12**, con acceso por SSH como root.
- **1 vCPU y 1 GB de RAM sobran** para empezar (miles de usuarios). Disco: unos cientos de MB.
- **Un dominio** (o subdominio) apuntando a la IP de la VPS: un registro `A`, por ejemplo `api.tudominio.com`. Sin
  dominio no hay certificado HTTPS, y sin HTTPS la app no se conecta.
- Los puertos **80 y 443** abiertos (también en el panel de tu proveedor, si tiene cortafuegos propio).

## Instalarlo

```sh
# En tu ordenador: copia la carpeta a la VPS
scp -r servidor/pindesk root@TU_IP:/root/pindesk
# En la VPS
ssh root@TU_IP
cd /root/pindesk && sh despliegue/instalar.sh api.tudominio.com
```

**¿Tu VPS ya tiene nginx con otra web?** Usa `sudo sh despliegue/instalar-nginx.sh tu.dominio` en vez de `instalar.sh`:
añade solo un sitio nuevo de nginx (con certificado de certbot) y no toca lo que ya hay, ni el Node ni el cortafuegos.

Al acabar enseña **la clave del panel**, **la clave para Google Authenticator** (añádela en la app del móvil) y **la
clave pública para la app**: guárdalas. Luego edita `/etc/pindesk/config.env`:

- `GOOGLE_CLIENT_IDS`: el ID de cliente OAuth **web** de Google (el mismo que se pone en la app al compilar). Se crea en
  [console.cloud.google.com](https://console.cloud.google.com) → *APIs y servicios → Credenciales*: uno de tipo **Web**
  y otro de tipo **Android** con el paquete `com.forge.pixpin` y la huella SHA-1 de la firma de la app.
- `CLAUDE_API_KEY`: tu clave de [console.anthropic.com](https://console.anthropic.com).

y `systemctl restart pindesk`. Comprueba: `curl https://api.tudominio.com/salud` → `{"ok":true}`.

## En la app

Al compilar (las variables del repositorio en GitHub → *Settings → Secrets and variables → Actions → Variables*):

- `SERVIDOR_PINDESK` = `https://api.tudominio.com`
- `FIRMA_PUBLICA` = la clave pública que enseñó la instalación
- `GOOGLE_CLIENTE_WEB` = el ID de cliente web de Google

Sin `SERVIDOR_PINDESK`, la app funciona sola con el catálogo de fábrica (los visores, el asistente con la clave propia
del usuario, las tareas): nada se rompe.

## El panel: `https://api.tudominio.com/admin`

- **Usuarios**: quién se registró, su plan (prueba, gratis, pro) y hasta cuándo. *+1 mes Pro* / *+1 año Pro* cuando te
  pagan por Yape o transferencia (y una *Nota*: «pagó por Yape el 7-nov»), regalar una función a alguien, bloquear.
- **Funciones**: cada una *oculta · gratis · pro*. Cambiarla llega a las apps la próxima vez que se abren. *Programar
  un cambio*: «desde el 1 de diciembre, Pro», con un aviso que la app enseña antes.
- **Versión**: publicar una versión nueva (la URL del APK en https, su SHA-256 y las novedades). La app avisa, la baja,
  comprueba la huella y la instala. Con *versión mínima*, quien tenga una más vieja tiene que actualizar.

## Las rutas

| Ruta | Para |
|---|---|
| `GET /v1/catalogo` | el catálogo firmado (igual para todos) |
| `GET /v1/version` | la última versión, firmada |
| `POST /v1/entrar {"idToken"}` | entrar con Google → sesión, cuenta, licencia y catálogo |
| `GET /v1/estado` (Bearer) | licencia, catálogo y cupo al día (y la sesión renovada) |
| `POST /v1/messages` (Bearer) | el asistente, igual que `api.anthropic.com/v1/messages` |
| `/admin`, `/admin/api/*` | el panel |

## Copias de seguridad

Todo está en `/var/lib/pindesk/*.json` (y su `.anterior`). Basta con copiar esa carpeta y `/etc/pindesk` (las claves:
**si se pierde `firma.pem`, las apps instaladas dejan de reconocer las licencias**; guárdala en un sitio seguro).

## Pruebas

`node --test --test-force-exit prueba/*.test.mjs`: firmas que no se pueden tocar, Google de mentira (token falso,
de otra app o caducado), prueba de 30 días, Pro desde el panel, funciones programadas, cupo del asistente, versión
solo con https y el freno a los intentos.
