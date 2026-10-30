# Monetización, cuentas y servidores: el plan para después de publicar gratis

Escrito el 30-oct-2026. PixPin sale **gratis** en Google Play (ver [google-play.md](google-play.md)).
Esto es lo que habrá que hacer cuando se añadan pagos, cuentas de usuario y servidores, ajustado a
las políticas vigentes hoy. Las marcas: ✅ comprobado en fuente oficial el 30-oct-2026; ⚠️ verificar
antes de implementar (cambia a menudo o solo encontré fuentes secundarias).

> **Regla de oro.** Publicar gratis no cierra puertas a suscripciones ni compras dentro de la app.
> Lo único irreversible: una app publicada como **gratuita** no puede pasar a tener **precio de
> descarga**. Por eso el modelo será *gratis + premium dentro de la app*.

---

## 1. Qué se puede vender y cómo cobrarlo

### 1.1 Cuándo es obligatorio Google Play Billing

| Lo que se vende | ¿Play Billing obligatorio? |
|---|---|
| Funciones o contenido **digital** dentro de la app: suscripción premium, más almacenamiento en la nube, más uso de IA, desbloquear funciones, temas | **Sí** (salvo los programas de facturación alternativa de §3) |
| Bienes o servicios **físicos** (imprimir y enviar apuntes, una clase presencial) | **No** (y no se puede usar Play Billing para eso) |
| Donaciones a un desarrollador individual | ⚠️ Play solo permite donaciones sin Play Billing a **organizaciones benéficas verificadas**; una «propina» al desarrollador es contenido digital ⇒ Play Billing (como compra consumible) |
| Pagos entre personas, publicidad | No aplica |
| Software/servicio que también se usa fuera de la app (p. ej. PixPin para Windows) | Si se **compra dentro de la app Android** ⇒ Play Billing. Si se compra en la web, la app puede **reconocerlo** («lector»/acceso multiplataforma) pero, fuera de los programas de §3, **no puede enlazar ni invitar** a pagar fuera ✅/⚠️ |

### 1.2 Play Billing Library: versiones ✅

Tabla oficial de retirada (developer.android.com/google/play/billing/deprecation-faq):

| Versión | Fecha límite apps nuevas/actualizaciones | Con prórroga |
|---|---|---|
| 7 | 31-ago-2026 | **1-nov-2026** |
| **8** | **31-ago-2027** | 1-nov-2027 |
| 9 | 31-ago-2028 | 1-nov-2028 ⚠️ |

**Hoy: empezar directamente con la versión 8 o la más reciente estable** (`com.android.billingclient:billing-ktx`).
La versión 7 deja de aceptarse en dos días. El manifiesto debe acabar llevando la entrada
`com.google.android.play.billingclient.version` (la pone la biblioteca) para que Play Console sepa
la versión.

### 1.3 Diseño propuesto: gratis + PixPin Premium

| Plan | Qué incluye | Coste para nosotros |
|---|---|---|
| **Gratis** (como hoy) | Todo lo que funciona en el teléfono: capturas, pines, grabación, transcripción local, OCR, lector de documentos, sincronización por Wi-Fi con el PC, asistente **con la clave propia** del usuario | 0 |
| **Premium mensual / anual** (suscripción) | Sincronización y copia en la **nube** (cifrada), espacio en servidor (p. ej. 20 GB), asistente **sin clave propia** con una cuota mensual de IA, Jev sin clave, enlaces para compartir alojados por nosotros (con caducidad y borrado) | Servidor + almacenamiento + IA |
| **Paquete de IA** (compra consumible, opcional) | Créditos extra de IA cuando se agota la cuota | IA |

Detalles de Play Billing a tener en cuenta:

- Suscripción = **un producto** con **planes base** (mensual, anual) y **ofertas** (prueba gratuita
  de 7 días, precio de introducción). Un usuario solo puede tener una prueba gratuita por producto.
- Mantener lo gratuito **sin cambios**: quitar funciones gratuitas para meterlas en premium genera
  malas reseñas y puede rozar la política de «cambios de funcionalidad» ⚠️.
- Validar **cada compra en el servidor** (Google Play Developer API: `purchases.subscriptionsv2.get`)
  y **reconocer** (`acknowledge`) en 3 días o Google la reembolsa automáticamente ✅ (regla conocida).
- Escuchar las **Real-time developer notifications** (Pub/Sub) para renovaciones, cancelaciones,
  periodo de gracia, retención de cuenta y reembolsos.
- Gestionar el estado en el servidor, no solo en el teléfono (el teléfono se puede manipular).

### 1.4 Reglas de presentación del precio y de la suscripción ⚠️

Política de suscripciones de Play:

- Mostrar claramente **precio, periodicidad, qué incluye, y que se renueva automáticamente** antes
  de comprar; si hay prueba gratuita, **cuándo y cuánto se cobrará** al terminar.
- Explicar **cómo cancelar** (Play Store → Pagos y suscripciones) y enlazar a
  `https://play.google.com/store/account/subscriptions?sku=…&package=com.forge.pixpin`.
- No engañar con el diseño (botón de cerrar visible, nada de «gratis» si luego se cobra, sin
  presión de cuenta atrás falsa).
- Precios: usar el **precio localizado** que devuelve `ProductDetails` (no escribirlo a mano).
- Ficha: la etiqueta «Contiene compras en la app» aparece sola al crear productos.

### 1.5 Reembolsos

- En las primeras 48 h el usuario puede pedirlo a Google directamente; después, al desarrollador ⚠️.
- Desde Play Console (*Gestión de pedidos*) se puede reembolsar y revocar.
- Las **notificaciones de compras anuladas** (Voided Purchases API) deben revocar el acceso premium.
- UE: el derecho de desistimiento de 14 días para contenido digital se renuncia si el usuario
  acepta expresamente empezar a usarlo; Play gestiona ese aviso en su flujo de pago ⚠️.

---

## 2. Comisiones (orientativo) ⚠️

- Suscripciones: **15 %** desde el primer día ✅ (conocido, verificar si cambió en 2026).
- Compras puntuales: 15 % del primer millón de USD al año por desarrollador (inscribiéndose en el
  nivel del 15 %), 30 % por encima ⚠️.
- En **junio de 2026** varias fuentes hablan de un anuncio de Google de «más opciones de facturación
  y comisiones más bajas» desde el 30-jun-2026 (EE. UU., EEE y Reino Unido primero) ⚠️ **no pude
  confirmarlo en una página oficial**: revisar *Play Console → Políticas → Tarifas de servicio*.

---

## 3. Facturación alternativa y enlaces externos

### 3.1 Espacio Económico Europeo (EEE) — Ley de Mercados Digitales (DMA)

Google ofrece en el EEE (opt-in, por app) ⚠️:

- **Facturación alternativa** (y **facturación con elección del usuario**, *user choice billing*:
  Play Billing junto a otro sistema y el usuario elige): comisión reducida (históricamente −3 puntos
  respecto a la de Play) ✅/⚠️.
- **Programa de ofertas externas** (*External offers*, desde mar-2024): enlazar a una oferta fuera
  de la app con una pantalla de aviso de Google; comisiones de «adquisición inicial» y «servicios
  continuos» sobre las compras hechas fuera ✅ (estructura; las cifras cambiaron con la presión de
  la Comisión Europea en 2025-2026 ⚠️).
- Varias fuentes de 2026 dicen que un modelo nuevo («own billing») sustituirá a *user choice
  billing* y *external offers* ⚠️ sin confirmar.

España está en el EEE: **aplica a usuarios de España**. Requisitos típicos: inscribirse en Play
Console, mostrar las pantallas informativas de Google, **reportar las transacciones** por API cada
mes y pagar la comisión. Exige un sistema de pago propio (Stripe, Adyen…) con PCI-DSS y
facturación con IVA de cada país (OSS).

### 3.2 Estados Unidos — Epic v. Google ✅ (con lo que no está cerrado ⚠️)

Según la página oficial de Play Console (answer/15582165, leída el 30-oct-2026):

| Fecha | Hecho |
|---|---|
| 12-sep-2025 | El Noveno Circuito confirma la orden judicial (caso Epic) |
| 29-oct-2025 | Fecha de cumplimiento de los cambios |
| 9-dic-2025 | Entran las políticas y programas actuales para EE. UU. |
| 4-mar-2026 | Nuevo acuerdo con Epic; se pide al tribunal una orden modificada |
| 22-jul-2026 | Programa de acceso al catálogo de Play para tiendas de terceros (opt-out hasta esa fecha) y aviso de comisiones |
| **1-oct-2026** | Los inscritos en **enlaces a contenido externo** y **facturación alternativa** deben **reportar transacciones y pagar comisiones** |
| 21-oct-2026 | Los de enlaces externos tienen hasta el **1-feb-2027** para reportar descargas y pagar |

- Para usuarios de **EE. UU.** Google **no prohíbe** enlazar a descargas, compras o contenido fuera
  de la app ni obliga a usar Play Billing, **pero** hay que inscribirse en los programas y pagar
  sus comisiones ✅. Las cifras no están en esa página; fuentes secundarias hablan de ~20 % en enlaces
  externos estándar ⚠️.
- El Form 10-Q de Alphabet (T3-2026) dice que en julio de 2026 Epic y Google **retiraron** la
  petición de modificar la orden y que Google cumple la sentencia de oct-2024 ⚠️. **El marco puede
  volver a cambiar**: comprobarlo antes de implementar nada.

### 3.3 Resto del mundo (Perú, Latinoamérica)

Sin programas especiales confirmados ⚠️: **Play Billing obligatorio** para lo digital y **prohibido
dirigir** al usuario a pagar fuera desde la app. (Corea del Sur, Japón, India, Indonesia y otros
tienen facturación alternativa con elección del usuario ⚠️.)

### 3.4 Recomendación

Empezar **solo con Play Billing** en todo el mundo: una sola integración, sin reportes mensuales,
y la comisión de suscripciones (15 %) es asumible al principio. Valorar los programas del EEE/EE. UU.
solo cuando los ingresos de esas regiones justifiquen el trabajo y el cumplimiento.

---

## 4. Cuentas de usuario

### 4.1 Iniciar sesión con Google: Credential Manager ✅/⚠️

- Usar **Credential Manager** (`androidx.credentials`) con **Sign in with Google**
  (`com.google.android.libraries.identity.googleid`): `GetGoogleIdOption` / `GetSignInWithGoogleOption`.
  La antigua *Google Sign-In for Android* (`GoogleSignInClient`) está **obsoleta** ⚠️.
- El teléfono obtiene un **ID token**; el servidor lo **verifica** (firma, `aud` = tu client ID web,
  `iss`, caducidad) y crea su propia sesión. **Nunca** confiar en datos del teléfono sin verificar.
- Hace falta un proyecto en Google Cloud con pantalla de consentimiento OAuth, un client ID **web**
  (para el servidor) y uno **Android** con el **SHA-1 de la clave de Play App Signing** y el de la
  clave de subida (Play Console → Integridad de la app).
- Ofrecer también **passkeys** (el mismo Credential Manager) a medio plazo.
- La cuenta debe ser **opcional**: lo gratuito y local sigue sin cuenta.

### 4.2 Borrado de cuenta: dentro de la app **y** en la web ✅

Política de Play (answer/13327111) para apps que permiten crear cuentas:

1. **Ruta dentro de la app** para borrar la cuenta y sus datos (fácil de encontrar: Ajustes →
   Cuenta → Borrar cuenta).
2. **Enlace web** donde pedir el borrado **sin tener la app instalada** (se pone en el formulario de
   Data safety). Ej.: `https://1xmanmax.github.io/PIXPIN_PRO_ANDROID/borrar-cuenta.html` con un
   formulario o instrucciones + correo, o una página del propio servidor con inicio de sesión.
3. **Desactivar/congelar no cuenta** como borrado ✅. Se puede ofrecer además borrar solo algunos
   datos ✅.
4. Indicar qué datos se conservan, por qué (p. ej. registros de facturación por ley) y cuánto.
5. Borrar la cuenta **no cancela** una suscripción de Play: avisarlo y enlazar a la gestión de
   suscripciones.

Si el formulario de borrado de Data safety está mal, **Play rechaza actualizaciones** ✅.

### 4.3 Actualizar Data safety y la política al añadir cuentas y servidores

Antes de publicar la versión con cuentas:

| Nuevo dato | Categoría de Play | Recopilado | Compartido | Finalidad |
|---|---|---|---|---|
| Correo, nombre | Información personal → Correo / Nombre | Sí | No | Gestión de la cuenta |
| ID de usuario | Identificadores → ID de usuario | Sí | No | Gestión de la cuenta, funcionalidad |
| Historial de compras | Información financiera → Historial de compras | Sí | No (Google procesa el pago) | Funcionalidad, gestión de cuenta |
| Chats, fotos, archivos, audio en la nube | Mensajes / Fotos y vídeos / Archivos / Audio | Sí | No (los encargados del tratamiento, p. ej. el proveedor del servidor, **no** cuentan como «compartir» ✅/⚠️) | Funcionalidad (copia/sincronización) |
| Uso de IA del servidor | Actividad en apps / Otro contenido | Sí | Sí, si el proveedor de IA no actúa como encargado ⚠️ | Funcionalidad |
| Diagnósticos (si se añade un SDK de fallos) | Info de la app y rendimiento | Sí | Según SDK | Analítica |

Y en la política de privacidad (`privacidad.html`, sección 11): base jurídica, servidores y país,
encargados (hosting, IA, correo), plazos de conservación, transferencias internacionales, cómo
borrar la cuenta (app + web), y la fecha nueva. **Avisar dentro de la app** del cambio.

---

## 5. Servidores: seguridad y ley

### 5.1 Seguridad mínima

- **TLS 1.2+** en todo (HTTPS, HSTS); certificados automáticos (Let's Encrypt o el del proveedor).
- **Cifrado en reposo** (disco/BD del proveedor) y, para chats y archivos, preferible **cifrado de
  extremo a extremo** con claves derivadas en el teléfono: así el servidor no puede leerlos, el
  riesgo legal baja y Data safety puede declararlos como cifrados de extremo a extremo (no
  «recopilados») ⚠️.
- Autenticación con tokens de corta duración; secretos en un gestor (no en el repo); principio de
  mínimo privilegio; copias de seguridad cifradas y probadas; registros sin datos personales.
- **Play Integrity API** para que solo la app auténtica use las APIs de pago/IA (evita abuso de la
  cuota de IA).
- Límites de uso por usuario (rate limiting) y alertas de gasto.

### 5.2 Protección de datos

- **UE/España — RGPD + LOPDGDD**: base jurídica (ejecución del contrato para la cuenta y la nube;
  consentimiento para lo opcional), registro de actividades, contratos de encargo con proveedores
  (DPA), transferencias fuera del EEE con cláusulas tipo, notificación de brechas en 72 h a la
  AEPD, derechos ARSOPL.
- **Perú — Ley 29733** y su reglamento (D.S. 016-2024-JUS, vigente desde 2025 ⚠️): consentimiento
  informado, **inscripción de bancos de datos** ante la Autoridad Nacional de Protección de Datos
  Personales (ANPD), comunicación de flujo transfronterizo si los servidores están fuera de Perú,
  medidas de seguridad, derechos ARCO, y aviso de incidentes ⚠️.
- Otros países donde se publique: revisar al menos LGPD (Brasil) y las leyes de privacidad de
  EE. UU. por estado si se crece allí.
- Menores: mantener el público 18+ (o 16+) para no entrar en reglas de menores (COPPA, art. 8 RGPD).

### 5.3 IA en el servidor: costes y claves

Hoy el asistente usa **la clave del usuario** (guardada cifrada en su teléfono): el coste es suyo y
nosotros no vemos nada. Al ofrecer IA a usuarios de pago:

- **La clave del proveedor de IA vive solo en el servidor.** Nunca en el APK/AAB (se extrae en
  minutos) ni en la configuración remota. El teléfono habla con **nuestro** servidor
  (`/v1/asistente`), que comprueba la suscripción y la cuota y reenvía al proveedor.
- Medir tokens por usuario y mes; cuota en la suscripción y créditos extra (compra consumible).
- Calcular el precio con margen: coste medio de tokens por usuario activo × 1,5-2 + almacenamiento
  + comisión de Play (15 %) + impuestos.
- Usar el modo «sin retención de datos/sin entrenamiento» que ofrezcan los proveedores y
  firmar su DPA; el proveedor pasa a ser **encargado** del tratamiento.
- Mantener la opción **«usar mi propia clave»** para usuarios gratuitos.
- Ojo con la política de IA generativa de Play (reportar respuestas, contenido restringido): ya
  cubierto con «Reportar respuesta»; con servidor propio, los reportes pueden llegar al servidor.

---

## 6. Cómo encaja con `planes/` (abstracción en el código)

Otro agente añade un paquete `planes/` con, al menos:

- `Planes.tiene(FuncionDePago): Boolean` — la única pregunta que hace la UI («¿puede usar esto?»).
- `interface Cobro` — quién sabe si se pagó (hoy una implementación que dice siempre «gratis»).

Plan de implementación sobre esa abstracción:

| Paso | En `planes/` | Fuera de `planes/` |
|---|---|---|
| 0. Hoy (gratis) | `Cobro` = `CobroGratis` (no hay nada que comprar); `Planes.tiene(...)` devuelve `true` para lo que hoy es gratis y `false` para lo que aún no existe (nube, IA sin clave) | Nada |
| 1. Productos en Play | Enum `FuncionDePago` ↔ ID de producto de Play (`premium_mensual`, `premium_anual`, `creditos_ia_100`) | Crear productos en Play Console; precios por país |
| 2. `CobroPlay : Cobro` | Envuelve `BillingClient` (versión ≥ 8): `queryProductDetailsAsync`, `launchBillingFlow`, `queryPurchasesAsync`; expone un `Flow<EstadoDePlan>` | Solo en la variante `play` (en la de GitHub, `CobroGratis` o un `CobroWeb`) |
| 3. Verificación en servidor | `CobroPlay` manda el `purchaseToken` al servidor y **solo** activa premium con su respuesta; caché local firmada con caducidad para usar sin red | Servidor: Play Developer API + RTDN (Pub/Sub) + `acknowledge` |
| 4. Cuenta | `Planes` asocia el plan al **ID de usuario** del servidor (así se restaura en otro teléfono) | Credential Manager; borrar cuenta en app + web |
| 5. Nube e IA | `Planes.tiene(FuncionDePago.NUBE)` / `.IA_SIN_CLAVE` deciden si se muestra la opción | Cliente HTTP al servidor propio; la IA sin clave va por el servidor |
| 6. Regiones | `Cobro` puede tener implementaciones por programa (facturación alternativa EEE/EE. UU.) sin tocar la UI | Inscripción, pantallas de Google, reportes mensuales |

Reglas para el código (para quien lo implemente):

- La UI **nunca** pregunta «¿es premium?», solo `Planes.tiene(funcion)`. Así cambiar qué incluye
  cada plan es tocar una tabla.
- Nada de lógica de cobro en la versión de GitHub que esquive Play: si la versión de GitHub
  vendiera algo, debe ser por la web y no anunciarse en la de Play.
- Probar con **cuentas de prueba de licencias** (Play Console → Configuración → Pruebas de licencias)
  y con la prueba interna; las compras de prueba no cobran.

---

## 7. Lista de comprobación cuando llegue el momento

- [ ] Perfil de pagos en Play Console (cuenta bancaria, datos fiscales; Google mostrará la dirección
      del vendedor en la ficha ⚠️ — valorar cuenta de organización)
- [ ] Productos y suscripciones creados; precios por país revisados
- [ ] Play Billing Library ≥ 8; compras verificadas y reconocidas en servidor; RTDN activo
- [ ] Pantalla de suscripción con precio, periodo, renovación automática, prueba y cómo cancelar
- [ ] Cuenta opcional con Credential Manager; borrado en app **y** web
- [ ] Servidor con TLS, cifrado en reposo, copias, Play Integrity, límites de uso
- [ ] Claves de IA solo en servidor; cuotas por usuario
- [ ] Política de privacidad y Data safety actualizadas **antes** de publicar; aviso en la app
- [ ] RGPD/LOPDGDD (DPA con proveedores) y Ley 29733 (inscripción del banco de datos en Perú)
- [ ] Decidir sobre programas EEE / EE. UU. (§3) con las cifras vigentes ese día

## 8. Fuentes (30-oct-2026)

- Play Billing Library, retirada de versiones: <https://developer.android.com/google/play/billing/deprecation-faq>
- EE. UU. (Epic v. Google): <https://support.google.com/googleplay/android-developer/answer/15582165>
- Alphabet 10-Q T3-2026: <https://www.sec.gov/Archives/edgar/data/0001652044/000165204426000084/goog-20260930.htm>
- Facturación alternativa EEE: <https://support.google.com/googleplay/android-developer/answer/12348241>
- Borrado de cuentas: <https://support.google.com/googleplay/android-developer/answer/13327111>
- Credential Manager: <https://developer.android.com/identity/sign-in/credential-manager-siwg>
