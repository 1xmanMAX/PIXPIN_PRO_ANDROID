# Pendientes pedidos el 13-sep-2026 (en orden de llegada)

## A. Sincronización (en curso)
- [x] Lista de elegir en dos pestañas (una por aparato), punto verde = en los dos, rojo = solo en uno,
      «Más reciente» / «Anterior» sin horas.
- [x] Un cambio recién hecho no se sincronizaba hasta 1-2 min: presencia mientras PixPin está abierto,
      direcciones recordadas y sondeo directo; lo acordado solo si quedó igual en los dos.
- [x] PDF de proyectos que no llegaba: estaba en la caché (fuera de files). Adoptar y arreglar en origen.
- [x] UI de Sincronizar más clara: cajas que se noten, «Este aparato» simplificado.

## B. Envío de una sola vez por Wi-Fi
- [x] Desde «Compartir» de cualquier app y desde proyectos; código de 6 cifras + QR; aviso de misma Wi-Fi.
- [x] El código se borra al terminar; no queda vinculado ni sale en Sincronizar.
- [x] «Quien envía manda»: identidad estable por proyecto/archivo; si ya lo tenía, se sustituye (y se avisa).

## C. Compartir
- [x] Quitar «Añadir a proyecto» de las opciones al compartir hacia PixPin.
- [x] Compartir lienzo como PDF: lista «Lienzo completo, Página 1, Página 2…» (varias); como imagen: la misma
      lista pero solo una, y enseñar el peso que tendrá (también en PDF).
- [x] Unificar el símbolo de compartir estilo iOS en toda la app.
- [x] En proyectos, las 5 opciones según contexto: una página → todas (incluidas imagen y SVG); más de una →
      sin imagen ni SVG.
- [x] Unificar el menú de compartir en toda la app: barra superior de proyectos + hoja de abajo del chat.

## D. Lienzo 2D
- [x] Figuras de la herramienta de figuras: el borrador no las borra; no se mueven fácilmente; candado para fijarlas.
- [x] Herramienta «seleccionar zona y arrastrar copia» (como Paint): copia de la zona (captura) que se arrastra.
- [x] Con un interruptor: la zona se manda como imagen al chat del proyecto, con vínculo «PDF X → página N»
      debajo; la imagen abre su propio lienzo para resolver encima; y en el lienzo de origen queda una marca que
      lleva al lienzo nuevo (doble vínculo).

## E. HTML exportado
- [x] Índice con enlaces (clicable).

## F. Pedido de la tarde del 13-sep
- [x] Sublienzos plegados bajo su página en proyectos (contador ▸ N / ▾ N).
- [x] Zona sin interruptor: herramienta persistente, la copia lleva un marco fino negro o blanco según el
      fondo, se puede mover sin cambiar de herramienta y hacer varias seguidas.
- [x] Proyecto creado desde un PDF: el PDF es el primer mensaje de su chat.
- [x] El vínculo de los sublienzos en el chat, más visible e intuitivo.
- [x] Modo noche: textos y mandos que se pierden contra el fondo (p. ej. la pastilla de la Zona).
- [x] Colores al pasar de noche a día: un rojo vivo elegido de noche sale casi negro de día. Buscar
      estudios y hacer la correspondencia bien; la rueda de noche enseña los colores oscurecidos.
- [x] Fondos del lienzo: colores de papel predeterminados basados en estudios (sin rueda).
- [x] Al acercarse mucho a un PDF aparece una cuadrícula de recuadros a ciertos aumentos.
- [x] Abrir con otra aplicación desde el chat y desde el pin (un PDF en un editor de PDF, un APK para
      instalarlo). Ahora un APK recibido no se abre.
- [x] Visor de PDF: los sublienzos de cada página se ven al alejar esa página (pellizco hacia fuera), a los
      lados y a la altura de su página, con una línea que va de cada uno a su recuadro en el PDF; al
      acercar se van. Por página: solo la que se pellizca cambia. Ligero y rápido.
- [x] PDF vectorial en el lienzo: tras acercar y alejar desaparecen las líneas (solo quedan letras) fuera
      de lo que estaba en foco; en PDF muy cargados, rellenos que se ponen negros o se tapan tras mover.
- [x] Opción en el lienzo para pasar una página de vectorial a imagen.
- [x] Fusionar páginas (p. ej. 4, 5 y 6) en un mismo lienzo, separadas a una distancia razonable.
- [x] Opciones de la página web agrupadas con nombres claros: Herramientas de edición (lápiz, resaltador,
      borrador, deshacer), Herramientas avanzadas (medir, capas), Predeterminado (pasar de página),
      Herramientas especiales (guardar, compartir).
- [x] El chat manda: un lienzo recibido y editado se veía nuevo en proyectos y viejo en el chat.
- [x] Sincronizar con «aparato maestro»: al empezar, elegir qué gana en lo cambiado en los dos (preguntar,
      este aparato, el otro, el más reciente). Los lienzos nuevos de cada lado ya se suman (Mezcla).
- [x] Visor de PDF: integrar `SublienzosDelPdf.kt` (escrito en el scratchpad, pendiente de meter en pdf/).

## G. Pedido el 14-sep-2026
- [x] El chat viaja con lo que se manda: un proyecto enviado llegaba con su galería y el chat vacío.
      `sincro/ChatQueViaja.kt` mete los mensajes (con su hora, número, letra y adjuntos) en `chat/`
      dentro del `.pixpin`; los ids no cambian, así que reenviar pone al día en vez de repetir.
- [x] Un PDF escaneado de 10 MB pesaba lo mismo en todo lo que salía de él: `pdf/ComprimirPdf.kt`
      baja las fotos de dentro a 300 ppp y JPEG 82 % (lo que hace `/printer` de Ghostscript) al
      entrar en un proyecto, y nunca a peor (si no gana un 15 % o no se vuelve a leer, se queda el
      original). La página web usa además el **ancho nativo** del escaneo (`anchoNativo`): pasar de
      ahí solo inventa píxeles. Guardas probados en `ComprimirPdfTest`.
- [x] Un PDF de cuatro páginas escaneadas pesaba 10 MB y todo lo que salía de él arrastraba el peso.

## Cómo quedó lo último (14-sep-2026, v0.42.0)

- **Las líneas que desaparecían**: el sistema tira la lista de órdenes de los nodos que lleva rato
  sin reproducir, o sea las tandas que quedaron fuera de la vista mientras se estaba acercado. Se
  comprobaba el conjunto y, si faltaba una, se volvía a grabar **todo** en un obrero. Ahora cada
  nodo lleva consigo lo que pinta y se regraba **él solo, en el fotograma en que hace falta**
  (`PlanoEnPantalla.Nodo.listo`): una tanda es un `drawLines`. No se puede probar aquí — el
  `RenderNode` de Robolectric siempre dice que tiene su lista.
- **Los rellenos negros**: `sc`/`scn` se adivinaban por cuántos números traían, y en una paleta
  (`/Indexed`) el número es un **índice**: un `scn 2` salía como «gris 1−2», o sea negro. Ahora el
  espacio de color se resuelve de verdad (`PlanoDePdf.Espacio`): paletas con su tabla, `/ICCBased`
  por sus canales, tintas planas de blanco a negro, y lo desconocido se sigue adivinando.
- **Una página como imagen**: interruptor en Ajustes del lienzo, debajo de «Plano en líneas»,
  guardado por página (`Settings.paginasComoImagen`, `claveDePagina`).
- **Fusionar páginas**: se marcan en proyectos y sale «Fusionar» (`guardados/FusionarPaginas.kt`,
  cuentas en `motor/FusionDePaginas.kt`): una hoja nueva con cada página en su marco, en fila si
  son verticales y en columna si son apaisadas, separadas un 4 % del lado mayor, y los píxeles
  repartidos entre todas (24 Mpx) sin que una salga con menos detalle que su vecina.
- **Aparato maestro**: ya decidía los archivos y los mensajes; ahora decide también el índice del
  proyecto —nombre, orden de las hojas— en vez de que lo haga el reloj (`Mezcla.Ganador`).

## H. Pedido el 14-sep-2026 (tarde) — hecho en v0.43.0

- [x] **Los sublienzos, pegados a su origen.** Iban alternando izquierda y derecha por orden de
      llegada, así que una zona del borde izquierdo salía enseñada a la derecha cruzando la página.
      Ahora `motor/SitioDeSublienzos.kt` reparte por **los cuatro lados** —el más cercano a la
      zona—, a su altura o a su anchura, corriéndose lo mínimo para no montarse y pasando al
      siguiente lado si el suyo se llena. Lo usan igual el lector de PDF (`pdf/SublienzosDelPdf.kt`,
      con bandas arriba y abajo) y la página web (`motor/SublienzosWeb.kt`, que abre el `viewBox`
      por donde haga falta). Nueve pruebas.
- [x] **El código del mensaje, también en las fotos.** Una foto sola no tiene burbuja ni renglón de
      hora, así que se quedaba sin su `#47a`: ahora va en la píldora oscura, delante de la hora.
- [x] **Un PDF se aligera en cuanto entra**, por donde entre: adjuntos del chat
      (`MensajesStore.copiarAdjunto`, que es por donde pasan todos), archivos compartidos a PixPin,
      un PDF unido a un proyecto (`UnirAlProyecto`) y un archivo pineado (`FileStore`). **No** lo
      que llega sincronizando (`aligerar = false`): los dos aparatos comparan los archivos por su
      resumen y reescribir lo recibido los dejaría distintos para siempre. El peso que se apunta en
      el mensaje es el de después, y se avisa con «PDF aligerado: 5,2 MB → 1,1 MB».
- [x] **Y se comprime de verdad**: antes solo se tocaban las fotos que ya eran JPEG, y un escáner
      **no guarda JPEG** —guarda los píxeles comprimidos con Deflate—, así que una página de más de
      un mega se quedaba igual. Ahora: (1) foto JPEG → a 300 ppp y JPEG 82; (2) **foto en crudo →
      JPEG**, solo si parece fotografía (`pareceFotografia`: se mira cuánto apretó el Deflate, que
      en un dibujo de líneas baja a una décima y a ese JPEG le sentaría fatal); (3) cualquier otro
      flujo, **re-apretado sin pérdida** con el Deflate más lento, y comprimido si venía en claro
      —lo que hacen `qpdf` y el optimizador de OCRmyPDF antes de tocar una imagen—. Sigue sin
      tocarse lo bilevel, las máscaras de recorte y el CMYK, y sigue sin aceptarse nada que no baje
      un 15 % o no se vuelva a leer.

## I. La compresión, en serio (14-sep-2026, v0.44.0)

El usuario probó 10 PDF: solo bajó 1, y iLovePDF les quitaba el 70 %. Las tres causas:

1. El documento se rehacía **por sus páginas**, así que con `/Outlines`, `/AcroForm` u
   `/OCProperties` se dejaba sin tocar — y marcadores los trae casi todo. Ahora se reescribe entero
   conservando los objetos (`PdfEscritura.completo`), tirando `/ObjStm`, índices viejos y la
   linearización, y con las referencias normalizadas a generación 0.
2. Las fotos se aceptaban solo en **JPEG RGB o gris**. Ahora también en crudo (Flate, LZW,
   RunLength, con predictor) y en CMYK, ICC y **paleta `/Indexed`**, a 1/2/4/8 bits si va por
   paleta. Se añadió el descompresor **LZW** al lector de PDF.
3. **300 ppp** no quitaba nada de un escaneo hecho a 300: ahora 200 ppp y JPEG 80, que es el orden
   en que trabajan los compresores de internet. Fuera la heurística de «parece fotografía»: decide
   el tamaño del JPEG que sale (≥ 20 % menos), que protege igual a los dibujos de líneas.

Y `Menú del mensaje → Aligerar el PDF` para los documentos que ya estaban dentro.

## J. Fusionar páginas, ajustado (14-sep-2026)

- [x] El nombre dice el rango: «Páginas 4 a 6», y cada marco «4 a 6 · pág. 5».
- [x] Más separadas: 0,14 del lado mayor en vez de 0,04.
- [x] Las páginas y sus marcos nacen con el **candado** puesto, para no moverlas por accidente.

