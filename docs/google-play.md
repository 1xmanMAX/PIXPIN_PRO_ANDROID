# PixPin en Google Play: guía paso a paso y lista de comprobación

Escrito el 30-oct-2026 para publicar PixPin **gratis**, sin anuncios ni compras. Lo que viene
después (pagos, cuentas, servidores) está en [monetizacion-y-cuentas.md](monetizacion-y-cuentas.md).

> **Cómo leer las marcas.** ✅ = comprobado en documentación oficial de Google el 30-oct-2026.
> ⚠️ **Verificar en Play Console** = lo sé por fuentes secundarias o por conocimiento previo, y las
> pantallas de Play Console cambian: antes de enviar, compruébalo donde se indica.

---

## 0. Resumen en una página

1. Cuenta de desarrollador (personal) → verificación de identidad → 25 USD.
2. Crear la **clave de subida** y `clave-de-subida.properties` (§2).
3. Publicar `docs/privacidad.html` en GitHub Pages (§1.5).
4. `./gradlew :app:bundlePlay` (o el flujo **Actions → «AAB para Google Play»**) → `app-play.aab`.
5. Crear la app en Play Console, activar **Play App Signing**, subir el AAB a **Prueba interna**.
6. Rellenar **Contenido de la app**: privacidad, anuncios (no), acceso, clasificación, público
   objetivo, seguridad de los datos, servicios en primer plano, alarmas exactas, IA, salud, finanzas,
   gobierno, noticias (§4).
7. Ficha de la tienda (§5).
8. **Prueba cerrada con ≥ 12 testers durante 14 días seguidos** (cuentas personales nuevas) (§6).
9. Solicitar acceso a producción → lanzamiento escalonado.

---

## 1. Cuenta de desarrollador

### 1.1 Personal u organización

| | Personal | Organización |
|---|---|---|
| Para quién | Una persona | Empresa, autónomo con marca, ONG |
| Requisitos | Documento de identidad, teléfono y correo verificados, dirección | **Número D-U-N-S** (gratis, hasta ~28 días en conseguirlo ✅), web de la organización, verificación de la organización |
| Prueba cerrada obligatoria antes de producción | **Sí** (cuentas creadas después del 13-nov-2023): ≥ 12 testers, 14 días seguidos ⚠️ | No |
| Nombre público | Se muestra tu nombre (o el que elijas, pero Google muestra el nombre legal verificado en ciertos países) | El de la organización |
| Dirección pública | Si en el futuro **vendes** algo (pagos), Google muestra tu dirección física en la ficha ⚠️ | La de la organización |

**Recomendación:** cuenta **personal** ahora (es lo rápido y barato). Si más adelante se monetiza y
no quieres que tu dirección de casa aparezca públicamente, crea una organización (en Perú, por
ejemplo, una EIRL o SAC con RUC; en España, sociedad o autónomo) y pide el D-U-N-S; las apps se
pueden **transferir** entre cuentas de Play Console.

### 1.2 Verificación de identidad

Play Console pide: nombre legal, dirección, documento de identidad (foto), teléfono y correo de
contacto verificados, y que el **correo de contacto** de la ficha funcione. Usar
`max.antony.8.5.1@gmail.com` o, mejor, un alias dedicado (p. ej. `pixpin.soporte@…`) para no
mezclar correo personal. Tarda de horas a varios días.

Además, Google exige que el **acceso al dispositivo Android** quede verificado en la app «Play
Console» del teléfono ⚠️ (paso que aparece al crear cuentas nuevas).

### 1.3 Verificación de desarrolladores de Android 2026 (fuera de Play) ✅

Desde 2026 Android exige que **toda app instalada en un dispositivo certificado** venga de un
desarrollador verificado, también las que se instalan desde un APK (lo que hoy hace PixPin con
GitHub). Calendario oficial (developer.android.com/developer-verification):

| Fecha | Qué pasa |
|---|---|
| Agosto 2026 | APIs para desarrolladores, cuentas de **distribución limitada** (estudiantes/aficionados, hasta 20 aparatos, sin DNI ni pago) y el «flujo avanzado» para usuarios expertos |
| **30-sep-2026** | Empieza a aplicarse en **Brasil, Indonesia, Singapur y Tailandia** (Android 7+ certificados) |
| **2027** | Se extiende a todo el mundo (incluidos Perú y España) |

Lo que importa para PixPin:

- **Play Console es el sitio para gestionarlo todo**, también lo que se reparte fuera de Play ✅. Las
  apps con **Play App Signing** se registran solas (paquete + clave de firma de Google).
- Se pueden **verificar varias claves de firma para un mismo paquete** ✅. Si un paquete ya está en
  uso con otra clave, puede pedir revisión adicional ✅.
- **Las instalaciones por ADB no necesitan verificación** ✅; un APK sin registrar solo se instala o
  actualiza con el «flujo avanzado» (espera de 24 h) o por ADB ✅.
- Si se pierde una clave de firma, ya no se pueden registrar los paquetes firmados con ella ✅.

### 1.4 El conflicto con el APK de GitHub ⚠️ (importante)

Hoy hay **dos PixPin con el mismo `applicationId` (`com.forge.pixpin`) y claves distintas**:

| | Versión GitHub (`assembleRelease`) | Versión Play (`bundlePlay`) |
|---|---|---|
| Firma | `debug.keystore` del PC (SHA-256 `05554faf…`) o `herramientas/pixpin-prueba.keystore` (SHA-256 `72a69d9e…`) | La clave de **Play App Signing** (la guarda Google); tú subes con la clave de subida |
| Permisos | Todos | Sin REQUEST_INSTALL_PACKAGES, USE_FULL_SCREEN_INTENT, USE_EXACT_ALARM |

Consecuencias:

1. **Android no instala una encima de otra** (firma distinta): quien tenga la de GitHub verá «La
   app no está instalada» o Play dirá que no puede actualizar. Para pasar a la de Play hay que
   **desinstalar**, y eso **borra los datos** (chats, grabaciones…). Antes, el usuario debe sacar
   una copia (sincronizar con el PC o «Copias», `sincro/CopiasActivity`).
2. Con la verificación de 2026-2027, el paquete `com.forge.pixpin` quedará registrado a tu nombre
   con la clave de Play. La de GitHub tendría que añadirse como **segunda clave** del mismo paquete.
3. **`herramientas/pixpin-prueba.keystore` está en el repositorio** (público si el repo lo es):
   **cualquiera** puede firmar un APK con ella. **Nunca la registres** como clave verificada de
   `com.forge.pixpin`: estarías avalando con tu identidad lo que firme cualquiera.
4. Una clave `debug.keystore` (certificado «Android Debug») tampoco es buena idea como clave
   registrada: no está pensada para custodia ⚠️ (Google podría no aceptarla; no confirmado).

**Opciones** (de más a menos recomendable):

- **A. Separar los paquetes (recomendado).** La versión de GitHub pasa a
  `applicationIdSuffix = ".github"` (o `.dev`) con nombre «PixPin (GitHub)». Conviven las dos en
  el mismo teléfono, cada una con sus datos. La de GitHub se registra aparte (en Play Console como
  app distribuida fuera de Play) con **una clave de release nueva y privada** (no la del repo). Los
  usuarios de GitHub migran sus datos con la sincronización/copias. *Cambio de código: lo haría
  otro agente; aquí solo se documenta.*
- **B. Solo Play.** Dejar de publicar APKs en GitHub (o publicarlos solo para desarrollo/ADB).
  Los testers usan la prueba interna/cerrada de Play. Lo más simple a largo plazo.
- **C. Mismo paquete, dos claves registradas.** Crear una clave de release nueva para GitHub y
  registrarla como segunda clave de `com.forge.pixpin`. Sigue sin poder instalarse una versión
  encima de la otra, así que confunde a los usuarios; no se recomienda.

### 1.5 Publicar la política de privacidad (GitHub Pages)

1. GitHub → repositorio `1xmanMAX/PIXPIN_PRO_ANDROID` → **Settings → Pages** → *Deploy from a
   branch* → rama `main`, carpeta **`/docs`** → Save.
2. En 1-2 minutos: `https://1xmanmax.github.io/PIXPIN_PRO_ANDROID/privacidad.html`.
3. Comprueba que abre **sin iniciar sesión**, en el móvil, y que no es un PDF ni un documento editable.

⚠️ Notas: GitHub Pages en un repo **privado** requiere un plan de pago (GitHub Pro); si el repo es
privado, la alternativa es un repo público aparte solo con `privacidad.html`. Y Pages **publica
todo `/docs`** (los `.md` se renderizan como páginas): revisa que nada de `docs/` sea privado
(por ejemplo `pendientes-del-usuario.md`).

---

## 2. Firma: clave de subida y Play App Signing

### 2.1 Crear la clave de subida (una vez)

En el PC (JDK 17+), **fuera** de la carpeta sincronizada con Drive si es posible:

```bash
keytool -genkeypair -v \
  -keystore pixpin-subida.jks \
  -storetype PKCS12 \
  -alias pixpin-subida \
  -keyalg RSA -keysize 4096 \
  -validity 10000 \
  -dname "CN=PixPin, O=Max, C=PE"
```

Pide una contraseña del almacén (con PKCS12 la de la clave es la misma). Guarda el `.jks` y la
contraseña en **dos sitios seguros** (gestor de contraseñas + copia cifrada). Si se pierde la clave
de subida, Google permite **restablecerla** desde Play Console (no es catastrófico, a diferencia de
la clave de firma de la app, que guarda Google).

### 2.2 `clave-de-subida.properties` (raíz del proyecto, ignorado por git)

```properties
# Ruta al .jks (absoluta, o relativa a app/)
almacen=C:/claves/pixpin-subida.jks
claveAlmacen=LA_CONTRASEÑA_DEL_ALMACEN
alias=pixpin-subida
clave=LA_CONTRASEÑA_DE_LA_CLAVE
```

> `almacen` se resuelve con `file(...)` del módulo `app`: una ruta relativa sería relativa a
> `app/`. Usa ruta absoluta. Comprueba que `*.properties` con claves no entra en git:
> `git check-ignore -v clave-de-subida.properties` debe mostrar la regla.

Alternativa sin archivo (CI): variables `PIXPIN_SUBIDA_ALMACEN`, `PIXPIN_SUBIDA_CLAVE_ALMACEN`,
`PIXPIN_SUBIDA_ALIAS`, `PIXPIN_SUBIDA_CLAVE` (ver `.github/workflows/play.yml`).

### 2.3 Compilar

```bash
./gradlew :app:bundlePlay
# → pixpin-build/app/outputs/bundle/play/app-play.aab
#   (pixpin-build está en %LOCALAPPDATA% en Windows, o en la raíz del proyecto si no existe esa variable)
```

Comprobar la firma: `keytool -printcert -jarfile app-play.aab` (debe salir el certificado de la
clave de subida, no «Android Debug»). Si no hay clave configurada, el AAB sale **sin firmar** y
Play lo rechaza.

O en GitHub: **Actions → «AAB para Google Play» → Run workflow** (necesita los secretos de §7).

### 2.4 Play App Signing

Al crear la app y subir el primer AAB, acepta **Play App Signing** (obligatorio para AAB): Google
genera y custodia la clave de firma de la app; tú solo firmas con la de subida. En *Configuración →
Integridad de la app* verás los SHA-1/SHA-256 de ambas claves (los necesitarás si algún día usas
Sign in with Google o APIs de Google).

### 2.5 `versionCode` / `versionName`

- `versionCode` (hoy **251**) debe **subir en cada AAB** que se sube a Play, en cualquier pista. Play
  rechaza un número ya usado, aunque se haya descartado esa versión.
- Máximo 2 100 000 000. No se puede bajar nunca.
- `versionName` (hoy `0.152.0`) es solo texto visible.
- Como el APK de GitHub y el AAB salen del mismo `build.gradle.kts`, comparten número: está bien,
  pero **no subas a Play un `versionCode` menor que el último subido**.

---

## 3. Requisitos técnicos

| Requisito | Estado en PixPin | Fuente |
|---|---|---|
| **AAB** (no APK) para apps nuevas | ✅ `bundlePlay` | ✅ |
| **Target API**: apps nuevas y actualizaciones deben apuntar a **Android 16 (API 36)** desde el **31-ago-2026** | ✅ `targetSdk = 36` | ✅ support.google.com/googleplay/android-developer/answer/11926878 |
| Próximo salto: **API 37 (Android 17)** previsiblemente para ago-2027 | Pendiente | ⚠️ |
| **Páginas de 16 KB**: obligatorio para apps nuevas y actualizaciones que apuntan a Android 15+ (desde 1-nov-2025; la prórroga terminó el 31-may-2026) | Vosk/JNA actualizados; **comprobar todas las `.so`** (sherpa-onnx, onnxruntime, pdfsqueeze, pixpincad, pixpinoffice): `play.yml` lo verifica | ⚠️ (fuentes secundarias, coherentes) |
| `useLegacyPackaging = true` (bibliotecas comprimidas) | Compatible con 16 KB: se extraen al instalar; basta con el alineado ELF de cada `.so`. Play Console avisa en *Explorador de app bundles* si algo no cumple | ⚠️ |
| **Edge-to-edge**: con target 35+ es obligatorio; con target 36 ya no vale `windowOptOutEdgeToEdgeEnforcement` | Las actividades llaman a `enableEdgeToEdge()`; revisar en pantallas sin inset (teclado, barra de gestos) | ✅ (documentación de Android 16) |
| **Pantallas grandes (Android 16)**: en pantallas ≥ 600 dp se ignoran `screenOrientation`, `resizeableActivity=false` y `min/maxAspectRatio`; con API 36 aún se puede excluir, con API 37 no | PixPin no fija orientación ni redimensionado ✅. Probar en tablet/plegable y en ventana libre | ✅ blog de Android Developers (ene-2025) |
| **Tipos de servicio en primer plano** declarados en el manifiesto y en Play Console | Ver §4.3 | ✅ |
| **Permiso de fotos y vídeos** (READ_MEDIA_IMAGES/VIDEO): solo si es función central | **No se usa** ✅ (selector del sistema). No hay que declarar nada | ✅ |
| `QUERY_ALL_PACKAGES`, accesibilidad, `MANAGE_EXTERNAL_STORAGE` | **No se usan** ✅ | — |
| `REQUEST_INSTALL_PACKAGES` | Quitado en `play` ✅ | — |
| `USE_FULL_SCREEN_INTENT` (desde Android 14 solo llamadas y alarmas) | Quitado en `play` ✅ | — |
| `USE_EXACT_ALARM` (solo despertadores/calendarios) → `SCHEDULE_EXACT_ALARM` | Cambiado en `play` ✅ (+ alternativa sin alarma exacta, otro agente) | — |
| Código ejecutable descargado (dex/jar/.so de fuera) **prohibido** | Los modelos descargados son **datos** (ONNX, Vosk, diccionarios), las `.so` van en el AAB ✅ | Política «Abuso de dispositivos y redes» |
| Tráfico en claro | Sin `usesCleartextTraffic` (por defecto prohibido) ✅; la sincronización local usa sockets propios cifrados | — |

---

## 4. Contenido de la app: declaraciones en Play Console

*Play Console → tu app → Política → Contenido de la app.* Textos listos para pegar en **inglés**
(los revisores trabajan en inglés) y **español** (por si la consola está en español o para tu
referencia). Donde Play limita a 500 caracteres, los textos caben.

### 4.1 Política de privacidad

URL: `https://1xmanmax.github.io/PIXPIN_PRO_ANDROID/privacidad.html`

Además, la app la enlaza dentro (Ajustes → Privacidad y datos), que también es obligatorio.

### 4.2 Anuncios / Acceso a la app / Gobierno / Noticias / Finanzas / Salud

| Declaración | Respuesta | Nota |
|---|---|---|
| **Anuncios** | **No**, la app no contiene anuncios | — |
| **Acceso a la app** | **Toda la funcionalidad está disponible sin restricciones** (no hay inicio de sesión) | Añade una nota: «Some features (AI assistant, Jev) need the user's own third-party API key; they are optional and the app works without them.» / «Algunas funciones (asistente IA, Jev) necesitan una clave de API propia del usuario; son opcionales.» Si el revisor necesita probar el asistente, puedes dar una clave de prueba con límite de gasto en *instrucciones de acceso* ⚠️ |
| **App de gobierno** | No | — |
| **App de noticias** | No | — |
| **Funciones financieras** | **No ofrece servicios financieros** («My app doesn't provide any financial features») | La mini-app de gastos es una libreta local, no un servicio financiero ⚠️ verificar opciones del formulario |
| **Apps de salud** (formulario obligatorio para todas las apps desde 2025 ⚠️) | **Sin funciones de salud** | — |
| **Contenido generado por IA** | Ver §4.8 | — |
| **ID de publicidad** | **No** usa el ID de publicidad | Con target 33+, si se declara «no», el manifiesto no debe tener `com.google.android.gms.permission.AD_ID` ✅ (no lo tiene) |

### 4.3 Servicios en primer plano (FGS) ✅

*Contenido de la app → Permisos de servicios en primer plano.* Por cada tipo: descripción, impacto
si se difiere/interrumpe, y **un vídeo** (enlace de YouTube no listado o Drive público) que muestre
al usuario iniciando la tarea y la notificación visible. Un vídeo por tipo, de 30-90 s, grabado con
la versión `play` en un teléfono real.

Servicios del manifiesto:

| Servicio | Tipo(s) |
|---|---|
| `floating.PinHostService` | `specialUse` (subtipo: «Floating overlay pins and quick capture entry points») |
| `ui.LeyendoEnVozService` | `mediaPlayback` |
| `guardados.ReunionPresencialService` | `microphone`, `camera`, `mediaProjection` |
| `guardados.PasarATextoService` | `dataSync`, `mediaProcessing` |
| `sincro.SubidaDeLaCamaraService` | `dataSync` |
| `capture.CaptureService` | `mediaProjection` |

> `mediaPlayback`, `microphone`, `camera`, `mediaProjection` y `mediaProcessing` normalmente
> **no** requieren declaración (los gobiernan permisos de ejecución) ⚠️; `dataSync` y `specialUse`
> **sí**. Play Console solo enseña los que necesita: rellena los que pida, con estos textos.

#### `specialUse` — pines flotantes
- **EN:** PixPin lets the user pin screenshots, notes and clipboard items as small floating windows over other apps (SYSTEM_ALERT_WINDOW) and start a capture from a quick-settings tile. The foreground service hosts these overlay windows while the user keeps them on screen; it stops as soon as the last pin is closed. A persistent notification lets the user close all pins. If the system deferred or stopped it, the pins the user placed would vanish while they are using them for reference.
- **ES:** PixPin permite fijar capturas, notas y elementos del portapapeles como ventanas flotantes pequeñas sobre otras apps (SYSTEM_ALERT_WINDOW) e iniciar una captura desde un mosaico de ajustes rápidos. El servicio en primer plano mantiene esas ventanas mientras el usuario las tiene en pantalla y se detiene al cerrar el último pin. Una notificación permanente permite cerrarlos todos. Si el sistema lo detuviera, los pines desaparecerían mientras el usuario los está usando como referencia.
- **Vídeo:** abrir una captura → «Fijar» → aparece el pin sobre otra app (p. ej. el navegador) → se ve la notificación → cerrar el pin → la notificación desaparece.

#### `dataSync` — subir fotos/clips al PC y descargar el modelo de voz
- **EN:** User-initiated transfers that must finish even if the user leaves the app: (1) uploading the photos and video clips of a recording to the user's own PC over the local Wi-Fi (end-to-end encrypted, no internet server); (2) downloading the on-device speech-recognition model (hundreds of MB) the first time the user turns on offline transcription. A notification shows progress and lets the user cancel. Interrupting it would leave a half-copied recording or a corrupt model.
- **ES:** Transferencias iniciadas por el usuario que deben terminar aunque salga de la app: (1) subir las fotos y clips de una grabación a su propio PC por la Wi-Fi local (cifrado de extremo a extremo, sin servidor en internet); (2) descargar el modelo de reconocimiento de voz en el dispositivo (cientos de MB) la primera vez que activa la transcripción sin conexión. Una notificación muestra el progreso y permite cancelar. Interrumpirlo dejaría una grabación a medias o un modelo corrupto.
- **Vídeo:** emparejado con el PC → grabar unos clips → «Subir al PC» → salir de la app → notificación con progreso → termina. Y: Ajustes → Transcripción → descargar modelo → notificación de descarga.
- ⚠️ Android 15+ limita `dataSync` a **6 h por día**; y Google anima a migrar a WorkManager / transferencias iniciadas por el usuario (`JobScheduler#setUserInitiated`). Riesgo bajo, pero conviene a medio plazo.

#### `mediaProcessing` — pasar una grabación a texto
- **EN:** When the user taps “Transcribe”, PixPin converts a long audio recording (a class or meeting) to text entirely on the device with a local speech model. This can take several minutes; the foreground service keeps it running with a progress notification and a cancel button so the user can switch apps. Nothing is uploaded.
- **ES:** Cuando el usuario pulsa «Pasar a texto», PixPin convierte una grabación larga (una clase o reunión) a texto completamente en el teléfono con un modelo de voz local. Puede tardar varios minutos; el servicio en primer plano lo mantiene con una notificación de progreso y un botón de cancelar para que el usuario pueda cambiar de app. No se sube nada.
- **Vídeo:** abrir una grabación → «Pasar a texto» → ir a la pantalla de inicio → notificación con progreso → volver: transcripción lista.

#### `microphone` / `camera` / `mediaProjection` — grabar clase o reunión
- **EN:** The user explicitly starts recording a class or an in-person meeting. The microphone (and optionally the rear camera for photos/clips of the board, and screen recording when the user picks “Screen”) keeps recording while the user switches to the slides, a video-call app or locks the screen. A persistent notification with Pause/Stop is always visible, and Android shows its own microphone/camera indicators and the screen-capture consent dialog. Stopping it would lose part of the class.
- **ES:** El usuario inicia explícitamente la grabación de una clase o reunión presencial. El micrófono (y, si lo elige, la cámara trasera para fotos/clips de la pizarra, y la grabación de pantalla si elige «Pantalla») sigue grabando mientras cambia a las diapositivas, a una videollamada o bloquea la pantalla. Siempre se ve una notificación permanente con Pausa/Detener, y Android muestra sus indicadores de micrófono/cámara y el diálogo de consentimiento de captura de pantalla. Detenerlo perdería parte de la clase.
- **Vídeo:** «Grabar clase» → aceptar permisos → empieza (notificación + indicador verde) → cambiar a otra app → hacer una foto desde la barra → volver → Detener. Repetir con «Pantalla» para que se vea el diálogo del sistema.

#### `mediaProjection` — captura de pantalla
- **EN:** Taking a screenshot or a short screen clip that the user starts from the floating button or the quick-settings tile. Android asks for consent each session; the service only runs while the capture is active.
- **ES:** Hacer una captura o un clip corto de pantalla que el usuario inicia desde el botón flotante o el mosaico de ajustes rápidos. Android pide consentimiento en cada sesión; el servicio solo corre mientras la captura está activa.

#### `mediaPlayback` — leer en voz alta
- **EN:** Reads a document or book aloud with the device's text-to-speech engine and keeps playing with the screen off or in another app, with media controls in the notification.
- **ES:** Lee en voz alta un documento o libro con el motor de voz del teléfono y sigue sonando con la pantalla apagada o en otra app, con controles multimedia en la notificación.

### 4.4 Mostrar sobre otras apps (SYSTEM_ALERT_WINDOW)

Play Console **no tiene** hoy un formulario específico para este permiso ⚠️, pero el revisor lo ve.
La justificación va en la de `specialUse` (arriba) y en la ficha («pines flotantes»). Requisitos:
pedirlo con una pantalla propia que explique para qué antes de mandar a Ajustes, y que la app
funcione sin él. Texto por si lo piden:

- **EN:** Used only for the core “pin” feature: small floating windows with the user's own screenshots/notes over other apps, which the user creates, moves and closes. Never used to cover or imitate other apps' UI.
- **ES:** Solo para la función principal de «pines»: ventanas flotantes pequeñas con capturas/notas del propio usuario sobre otras apps, que el usuario crea, mueve y cierra. Nunca se usa para tapar o imitar la interfaz de otras apps.

### 4.5 Alarmas exactas (SCHEDULE_EXACT_ALARM)

`SCHEDULE_EXACT_ALARM` no requiere formulario, pero desde Android 14 **viene denegado** en
instalaciones nuevas y el usuario lo concede en Ajustes ✅. Play exige que la app funcione si se
deniega (de eso se encarga la alternativa sin alarma exacta). Si Play Console pregunta ⚠️:

- **EN:** User-set reminders: the user picks a date and time for a reminder or a class alert and expects it at that exact minute. The app asks for the permission only when the user creates a timed reminder, explains why, and falls back to an inexact alarm if it is denied.
- **ES:** Recordatorios que pone el usuario: elige fecha y hora de un recordatorio o aviso de clase y espera que suene a ese minuto. La app pide el permiso solo al crear un recordatorio con hora, explica por qué y, si se deniega, usa una alarma inexacta.

### 4.6 Cámara y micrófono

Son permisos de ejecución; no hay formulario aparte. Asegúrate de que el texto de la ficha y la
política los explican (ya lo hacen) y de que **se piden en contexto**.

### 4.7 Seguridad de los datos (Data safety)

Definiciones de Google: **recopilar** = transmitir datos fuera del dispositivo (también a terceros
mediante tu código); **compartir** = transferir a un tercero. **No cuentan**: datos procesados solo
en el dispositivo, datos cifrados de extremo a extremo que nadie más puede leer, y (para «compartir»)
transferencias que el usuario inicia esperando razonablemente que ocurran ⚠️ (revisa la ayuda
«Provide information for Google Play's Data safety section» antes de enviar).

Criterio elegido: **declarar como «recopilado» lo que la app envía a internet**, aunque vaya con la
clave del propio usuario y nosotros no lo veamos; marcar **todo como opcional**; y no como
«compartido» porque cada envío es una acción explícita del usuario hacia el servicio que él eligió.
Es la lectura prudente: declarar de más no penaliza; de menos, sí.

Respuestas generales:

| Pregunta | Respuesta |
|---|---|
| ¿La app recopila o comparte alguno de los tipos de datos requeridos? | **Sí** |
| ¿Todos los datos recopilados se cifran en tránsito? | **Sí** (HTTPS) |
| ¿Ofreces una forma de solicitar que se borren los datos? | **Sí**: «Borrar todos mis datos» en la app; los datos no llegan a nuestros servidores |
| ¿Cuentas de usuario? | **No** (la app no permite crear cuentas) |
| Revisión de seguridad independiente (MASA) | No (opcional) |

Tabla de tipos de datos:

| Tipo de dato (categoría de Play) | ¿Recopilado? | ¿Compartido? | Finalidad | ¿Opcional? | Cuándo |
|---|---|---|---|---|---|
| **Mensajes → Otros mensajes en la app** | Sí | No | Funcionalidad de la app | Opcional | Chat con el asistente (Anthropic/DeepSeek) |
| **Fotos y vídeos → Fotos** | Sí | No | Funcionalidad de la app | Opcional | Fotos/capturas que el usuario adjunta al asistente o a Jev; «Compartir como enlace» |
| **Fotos y vídeos → Vídeos** | Sí | No | Funcionalidad de la app | Opcional | «Compartir como enlace» de un vídeo |
| **Archivos y documentos** | Sí | No | Funcionalidad de la app | Opcional | Texto de documentos que se adjunta al asistente; «Compartir como enlace» |
| **Actividad en apps → Historial de búsqueda en la app** | Sí | No | Funcionalidad de la app | Opcional | Búsqueda web del asistente (DuckDuckGo/Brave) |
| **Actividad en apps → Otro contenido generado por el usuario** | Sí | No | Funcionalidad de la app | Opcional | Transcripciones/dictados que van a Jev |
| Audio (grabaciones de voz, música) | **No** | No | — | — | Se transcriben en el teléfono; el audio no sale (la sincronización con tu PC es cifrada de extremo a extremo) |
| Ubicación, contactos, info personal, salud, finanzas, calendario, identificadores, info del dispositivo, diagnósticos | **No** | No | — | — | — |
| Datos de fallos / diagnóstico | **No** | No | — | — | No hay SDK de fallos |

Para cada tipo «Sí», Play pregunta: *¿Procesado de forma efímera?* → **No** (el proveedor de IA
puede conservarlo según su política). *¿Obligatorio u opcional?* → **Opcional** (el usuario decide
usar la función).

> Si otro agente oculta en `play` las voces de Edge (que envían texto a Microsoft), no hay que
> declararlas. Si algún día vuelven a la versión de Play, añade «Otro contenido generado por el
> usuario» → compartido con Microsoft.

### 4.8 Contenido generado por IA ✅

Política «Contenido generado por IA»: las apps que **generan** contenido con IA (chatbots,
generadores de imágenes) deben tener **un botón para reportar/señalar contenido ofensivo sin salir
de la app** y evitar contenido restringido. Excepción: productividad que usa IA como función
secundaria o para resumir. PixPin tiene un chat con un asistente generativo ⇒ **aplica**, y otro
agente añade «Reportar respuesta» en el menú de cada respuesta.

Si Play Console pregunta (cuestionario de clasificación o declaración de IA ⚠️):

- **EN:** PixPin includes an optional AI assistant that generates text answers using third-party models (Anthropic Claude or DeepSeek) with the user's own API key. Users can report any AI response from the message's menu (“Report response”) without leaving the app; reports reach the developer, who reviews them. The assistant uses the providers' safety systems and system instructions that refuse sexual, violent, hateful or dangerous content. The app does not generate images, audio or video of real people.
- **ES:** PixPin incluye un asistente de IA opcional que genera respuestas de texto con modelos de terceros (Anthropic Claude o DeepSeek) usando la clave de API del propio usuario. El usuario puede reportar cualquier respuesta desde el menú del mensaje («Reportar respuesta») sin salir de la app; los reportes llegan al desarrollador, que los revisa. El asistente usa los sistemas de seguridad de los proveedores e instrucciones de sistema que rechazan contenido sexual, violento, de odio o peligroso. La app no genera imágenes, audio ni vídeo de personas reales.

⚠️ Asegúrate de que la frase sobre «instrucciones de sistema que rechazan…» sea cierta en el
prompt del asistente antes de enviarla; si no lo es, quítala.

### 4.9 Clasificación de contenido (IARC)

Categoría: **Utilidades, productividad, comunicación u otros** (no juego, no referencia). Guía de
respuestas honestas:

| Pregunta | Respuesta sugerida | Por qué |
|---|---|---|
| Violencia, sexo, lenguaje, drogas, juego con dinero | No | — |
| ¿Los usuarios pueden interactuar o intercambiar contenido? | **Sí** | Chats compartidos con otras personas (por Wi-Fi) y archivos compartidos por enlace |
| ¿Comparte la ubicación del usuario con otros? | No | — |
| ¿Permite compras digitales? | No | — |
| ¿Acceso sin restricciones a internet / navegador? | **Sí** ⚠️ | El asistente lee páginas web y el visor abre HTML |
| ¿Contenido generado por IA? (pregunta añadida en 2024-25 ⚠️) | **Sí** | Asistente generativo |

Resultado esperable: PEGI 3/12 o «Todos/Teen» con descriptores «Los usuarios interactúan» y
«Acceso a internet». Está bien; no lo maquilles.

### 4.10 Público objetivo y contenido

- **Grupos de edad: solo 18+** (o como mínimo **16+** si quieres estudiantes de bachillerato ⚠️).
- **No** marcar ningún grupo menor de 13 ⇒ no entra en la **Política de Familias** (que exigiría SDK
  certificados, nada de IA abierta, revisiones extra y una política para niños).
- Razones: el asistente usa servicios de IA cuyas condiciones exigen ser mayor de edad (Anthropic
  18+); lee la web sin filtro; graba audio/cámara/pantalla; maneja claves de API.
- «¿Podría la app atraer accidentalmente a niños?» → **No** (estilo visual sobrio, sin personajes).

---

## 5. Ficha de Google Play (borradores)

> Reglas de la ficha: sin «gratis», «mejor», «n.º 1», precios ni emojis en el título; sin
> mayúsculas sostenidas; sin nombres de marcas de terceros como palabras clave (puedes decir que
> funciona con Claude/DeepSeek, en la descripción, sin logos). ⚠️ revisa la política de metadatos.

### 5.1 Español (es-ES; copia también a es-419)

**Nombre (≤ 30):** **`PixPin – capturas y apuntes`** (27)

**Descripción breve (≤ 80):**
`Fija capturas sobre otras apps, graba clases y pásalas a texto sin internet.` (76)

**Descripción completa (≤ 4000):**

```
PixPin es tu cuaderno de bolsillo para estudiar y trabajar: captura, fija, graba y ordena todo en chats, sin cuentas y con tus datos en tu teléfono.

FIJA LO QUE NECESITAS VER
• Captura la pantalla desde un botón flotante o desde los ajustes rápidos.
• Fija capturas, notas o lo que copiaste como pequeñas ventanas flotantes encima de cualquier app: copia un código, sigue una receta o compara dos cosas sin cambiar de pantalla.
• Recorta, dibuja y anota sobre la captura.

GRABA LA CLASE Y PÁSALA A TEXTO
• Graba una clase o una reunión con el micrófono, y si quieres haz fotos de la pizarra o graba la pantalla a la vez.
• Pasa la grabación a texto en el propio teléfono, sin enviar el audio a ningún servidor.
• Repasa con la transcripción, las fotos y los temas ordenados por hora.
• Dicta mensajes y escucha documentos en voz alta, también con la pantalla apagada.

TODO EN CHATS
• Cada tema es un chat con tus capturas, fotos, PDF, documentos de Word, hojas de Excel, notas de voz y enlaces.
• Lector de PDF, visor de Word y Excel y editor de notas en Markdown integrados.
• Tareas, gastos, horario de clases y recordatorios a la hora exacta.
• Widgets de tareas y de últimas capturas.
• Busca cualquier cosa desde la app o desde el buscador del teléfono.
• Envía tarjetas a AnkiDroid para estudiar.

SINCRONIZA CON TU PC POR LA WI-FI
• Conecta el teléfono con PixPin para Windows en la misma red con un QR.
• Los chats, fotos y grabaciones viajan cifrados de un aparato a otro, sin pasar por internet.
• Comparte un chat con un compañero que esté en la misma Wi-Fi.

ASISTENTE DE IA (OPCIONAL)
• Pregunta sobre tus apuntes, tus fotos o una página web a un asistente que funciona con tu propia clave de API de Claude (Anthropic) o DeepSeek.
• Sin clave, PixPin funciona entero; con ella, decides tú qué envías.
• Puedes reportar cualquier respuesta desde el propio mensaje.

PRIVACIDAD
• Sin anuncios, sin analítica, sin rastreadores y sin cuentas.
• Tus datos se guardan en tu teléfono. Solo sale algo cuando usas una función que lo necesita (el asistente, la búsqueda web o compartir como enlace), y te lo decimos.
• Borra todos tus datos cuando quieras desde Ajustes.

Permisos: cámara y micrófono solo para fotos, clips y grabaciones que tú inicias; mostrar sobre otras apps para los pines flotantes; notificaciones y alarmas para los recordatorios. Durante una grabación siempre verás una notificación.

¿Dudas o sugerencias? Escríbenos a max.antony.8.5.1@gmail.com.
```

### 5.2 English (en-US)

**App name (≤ 30):** **`PixPin – Screenshots & Notes`** (28)

**Short description (≤ 80):**
`Pin screenshots over any app, record classes and transcribe them offline.` (73)

**Full description (≤ 4000):**

```
PixPin is a pocket notebook for studying and work: capture, pin, record and organize everything in chats — no account, and your data stays on your phone.

PIN WHAT YOU NEED TO SEE
• Take screenshots from a floating button or a Quick Settings tile.
• Pin screenshots, notes or copied text as small floating windows over any app: copy a code, follow a recipe or compare two things without switching screens.
• Crop, draw and annotate on your captures.

RECORD YOUR CLASS AND TURN IT INTO TEXT
• Record a class or meeting with the microphone, and optionally take photos of the board or record the screen at the same time.
• Transcribe recordings on your phone — the audio is never uploaded.
• Review with the transcript, photos and topics ordered by time.
• Dictate messages and listen to documents read aloud, even with the screen off.

EVERYTHING IN CHATS
• Each topic is a chat with your screenshots, photos, PDFs, Word documents, Excel sheets, voice notes and links.
• Built-in PDF reader, Word and Excel viewer and Markdown notes editor.
• Tasks, expenses, class timetable and exact-time reminders.
• Home-screen widgets for tasks and recent captures.
• Search everything from the app or your phone's search.
• Send flashcards to AnkiDroid.

SYNC WITH YOUR PC OVER WI-FI
• Pair your phone with PixPin for Windows on the same network using a QR code.
• Chats, photos and recordings travel encrypted between your devices, never through the internet.
• Share a chat with a classmate on the same Wi-Fi.

AI ASSISTANT (OPTIONAL)
• Ask about your notes, photos or a web page with an assistant that uses your own Claude (Anthropic) or DeepSeek API key.
• Without a key PixPin works fully; with one, you decide what to send.
• Report any response right from the message.

PRIVACY
• No ads, no analytics, no trackers, no accounts.
• Your data is stored on your phone. Something leaves it only when you use a feature that needs it (the assistant, web search or share-as-link), and we tell you.
• Delete all your data anytime from Settings.

Permissions: camera and microphone only for photos, clips and recordings you start; display over other apps for floating pins; notifications and alarms for reminders. A notification is always shown while recording.

Questions or feedback? max.antony.8.5.1@gmail.com
```

> La descripción está escrita en español de España; para es-419 cambia «ordenador» si aparece y
> revisa tono. **Comprueba cada afirmación con la versión `play`** (p. ej. si Edge TTS está oculta,
> no hables de «voces naturales»).

### 5.3 Recursos gráficos

| Recurso | Especificación | Qué poner |
|---|---|---|
| Icono | **512×512 PNG 32 bits**, ≤ 1 MB, sin esquinas redondeadas (Play las aplica) | El icono adaptativo exportado a 512 |
| Gráfico de funciones | **1024×500** JPG/PNG 24 bits sin alfa | Fondo liso + 1 captura del teléfono con un pin flotante + lema «Fija, graba, repasa» / «Pin, record, review». Nada importante en los bordes |
| Capturas de teléfono | 2-8, 16:9 o 9:16, lado entre 320 y 3840 px ⚠️ | 1) pin flotante sobre otra app; 2) lista de chats; 3) grabando clase (notificación visible); 4) transcripción con fotos; 5) lector de PDF/Word; 6) tareas + widget; 7) emparejar con el PC por QR; 8) asistente con el botón «Reportar respuesta» visible |
| Capturas de tablet 7" y 10" | Recomendadas para aparecer en tablets | Las mismas en tablet/emulador |
| Vídeo (opcional) | YouTube | 30 s de las funciones 1, 3 y 7 |

No uses en las capturas datos reales de otras personas (nombres de compañeros, profesores…).

### 5.4 Otros datos de la ficha

- **Categoría:** Productividad (alternativa: Educación).
- **Etiquetas:** notas, captura de pantalla, estudio, grabadora, transcripción ⚠️ (lista cerrada).
- **Correo de contacto** (obligatorio, público), web opcional (`https://1xmanmax.github.io/PIXPIN_PRO_ANDROID/`).
- **Países:** todos, o empezar por Perú, España y Latinoamérica.
- **Precio:** **Gratis**. ⚠️ Una app publicada como gratuita **no puede pasar a ser de pago**
  (precio de descarga) nunca; sí puede añadir compras dentro de la app o suscripciones.

---

## 6. Pistas de lanzamiento

1. **Prueba interna** (hasta 100 testers por lista de correos, disponible en minutos, sin revisión
   completa): sube aquí cada AAB primero. Comprueba que se instala, que los permisos se piden bien y
   que el informe previo al lanzamiento (*Pre-launch report*) no da errores graves.
2. **Prueba cerrada** (revisión completa de Google): crea una lista o un Grupo de Google con
   **≥ 12 testers** ⚠️ (eran 20 hasta dic-2024). Cada tester debe **aceptar** (opt-in) por el enlace
   y tener la app instalada. El reloj cuenta **14 días seguidos** con ≥ 12 inscritos; si alguien se
   da de baja y bajas de 12, el contador se reinicia ⚠️. Durante esos días:
   - Pide a los testers que la usen de verdad y dejen comentarios (Google pregunta después por la
     participación ⚠️; hay informes de rechazos por poca actividad en 2026, no confirmados).
   - Sube al menos una actualización con correcciones: demuestra que iteras.
3. **Solicitar acceso a producción** (*Panel → Solicitar acceso*): cuestionario sobre cómo
   reclutaste testers, qué feedback recibiste y qué cambiaste, y por qué la app está lista. Respuesta
   en ~7 días ⚠️.
4. **Producción con lanzamiento escalonado**: 10 % → 25 % → 50 % → 100 % en una semana, vigilando
   *Android vitals* (ANR < 0,47 %, fallos < 1,09 % son los umbrales de «mal comportamiento» ⚠️).
   Se puede pausar el escalonado si algo va mal.

Reglas: cada revisión puede tardar de horas a 7 días (más en cuentas nuevas). Tras el primer
rechazo, **lee el correo y la *Bandeja de entrada de políticas*** y responde por el formulario de
apelación; no subas la misma versión sin cambios.

---

## 7. CI: `.github/workflows/play.yml`

Secretos (GitHub → Settings → Secrets and variables → Actions → *New repository secret*):

| Secreto | Valor |
|---|---|
| `CLAVE_DE_SUBIDA` | El `.jks` en base64: `base64 -w0 pixpin-subida.jks` (Linux) o `[Convert]::ToBase64String([IO.File]::ReadAllBytes("pixpin-subida.jks"))` (PowerShell) |
| `CLAVE_DE_SUBIDA_ALMACEN_PASS` | Contraseña del almacén |
| `CLAVE_DE_SUBIDA_ALIAS` | `pixpin-subida` |
| `CLAVE_DE_SUBIDA_PASS` | Contraseña de la clave (igual que la del almacén en PKCS12) |

El flujo compila `bundlePlay`, **falla si alguna `.so` de arm64 no está alineada a 16 KB**, y deja
el AAB como artefacto del workflow (se descarga desde la ejecución, 30 días). La subida a Play se
hace a mano en Play Console (más adelante se puede automatizar con la API de publicación y una
cuenta de servicio).

---

## 8. Riesgos encontrados en la auditoría (30-oct-2026)

| # | Riesgo | Gravedad | Recomendación |
|---|---|---|---|
| 1 | **Mismo paquete en GitHub y Play con claves distintas** + verificación de desarrolladores 2026-27 | Alta (usuarios) | §1.4: opción A (`.github`) u opción B (solo Play). No registrar nunca `pixpin-prueba.keystore` |
| 2 | **`herramientas/pixpin-prueba.keystore` en el repo** | Alta si se registrara | Tratarla como pública. Nunca usarla para Play ni registrarla |
| 3 | **Voces de Edge** (`motor/EdgeVoz.kt`): usa un token y una API no pública de Microsoft imitando al navegador | Alta (política de «Abuso de dispositivos y redes» / propiedad intelectual) | Ocultas en `play` (otro agente). Comprobar que **ni el código se ejecuta** en `play` |
| 4 | **«Llamada secreta»** (`pin/LlamadaSecretaActivity`): una pantalla de llamada entrante simulada como aviso | Media | Política de **comportamiento engañoso** (imitar la UI del sistema). En Play: que se vea claramente que es un recordatorio de PixPin (nombre/icono de la app, no un contacto real), que solo la active el usuario, y descríbela en la ficha como «aviso con aspecto de llamada». Si se rechaza, ocultarla en `play` |
| 5 | **Grabación en segundo plano con cámara/micrófono/pantalla** | Media | Notificación siempre visible, inicio solo por el usuario, vídeos de FGS claros. Recuerda al usuario en la app que pida consentimiento para grabar a otras personas (ley local) |
| 6 | **«Compartir como enlace»** sube archivos a servicios públicos anónimos (litterbox, temp.sh) | Media | Aviso claro antes de subir (ya existe en `CompartirEnlaceActivity`) + declarado en Data safety y política. Si un revisor lo ve como «compartir contenido sin moderación», ocultarlo en `play` |
| 7 | **Asistente IA generativo** | Media | «Reportar respuesta» (otro agente), público 18+, declaración IA (§4.8) |
| 8 | **SYSTEM_ALERT_WINDOW + FGS `specialUse`** | Media | Justificación §4.3/§4.4; los revisores son estrictos con `specialUse`: el vídeo debe ser muy claro |
| 9 | `dataSync` en Android 15+ (límite de 6 h) | Baja | Migrar a WorkManager/user-initiated jobs más adelante |
| 10 | `LecturaVelozReceiver` exportado sin permiso | Baja (seguridad) | Ignora lo que no reconoce, pero conviene protegerlo con un permiso de firma o comprobar el emisor |
| 11 | `allowBackup="true"` | Baja | Reglas de exclusión de claves/secretos (otro agente). Declarado en la política |
| 12 | Funciones que dependen de **la clave del usuario** (Claude, DeepSeek, TypeSafe, Brave) | Baja | Indicarlo en «Acceso a la app»; la app funciona sin ellas |
| 13 | **Diferencias entre la ficha y la versión `play`** (lo oculto en Play no debe anunciarse) | Media | Repasar la descripción con la build `play` |
| 14 | Todo `docs/` se publica con GitHub Pages | Baja | Revisar que no haya nada privado en `docs/` |
| 15 | Android 17 (target 37, previsiblemente obligatorio en ago-2027 ⚠️): permiso de **red local** y fin de la exclusión de orientación en pantallas grandes | Futuro | La sincronización por Wi-Fi necesitará el nuevo permiso de red local ⚠️ |

---

## 9. Lista de comprobación final

- [ ] Cuenta personal creada, identidad y teléfono verificados
- [ ] `privacidad.html` publicada y accesible sin sesión
- [ ] Clave de subida creada, guardada en 2 sitios, `clave-de-subida.properties` fuera de git
- [ ] Decidida la estrategia GitHub vs Play (§1.4)
- [ ] `bundlePlay` firmado con la clave de subida; `versionCode` mayor que el último subido
- [ ] `.so` alineadas a 16 KB (flujo `play.yml` en verde)
- [ ] Probado en Android 14, 15 y 16, y en una tablet/emulador ≥ 600 dp
- [ ] Probado sin conceder: cámara, micrófono, notificaciones, alarma exacta, superposición
- [ ] En `play`: sin Edge TTS, sin instalar APKs, «Privacidad y datos», «Borrar todos mis datos», «Reportar respuesta»
- [ ] App creada en Play Console (Gratis, App), Play App Signing activado
- [ ] Contenido de la app: privacidad, anuncios (no), acceso, IARC, público 18+, Data safety, FGS (+ vídeos), finanzas, salud, gobierno, noticias, IA
- [ ] Ficha ES + EN, icono 512, gráfico 1024×500, ≥ 4 capturas
- [ ] Prueba interna OK → prueba cerrada con ≥ 12 testers × 14 días
- [ ] Solicitud de producción → escalonado 10 % → 100 %

## 10. Fuentes consultadas (30-oct-2026)

- Target API: <https://support.google.com/googleplay/android-developer/answer/11926878> y <https://developer.android.com/google/play/requirements/target-sdk>
- Verificación de desarrolladores: <https://developer.android.com/developer-verification> y su FAQ <https://developer.android.com/developer-verification/guides/faq>
- Prueba cerrada (12 testers / 14 días): <https://support.google.com/googleplay/android-developer/answer/14151465> (solo pude leer fuentes secundarias que lo citan)
- Orientación y redimensionado en Android 16: <https://android-developers.googleblog.com/2025/01/orientation-and-resizability-changes-in-android-16.html>
- Contenido generado por IA: <https://support.google.com/googleplay/android-developer/answer/13985936>
- Borrado de cuentas: <https://support.google.com/googleplay/android-developer/answer/13327111>
- 16 KB: fuentes secundarias (grupo Android NDK, Expo) que citan el aviso de Play Console
