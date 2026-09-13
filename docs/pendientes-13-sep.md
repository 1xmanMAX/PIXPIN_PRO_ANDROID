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
- [ ] Visor de PDF: los sublienzos de cada página se ven al alejar esa página (pellizco hacia fuera), a los
      lados y a la altura de su página, con una línea que va de cada uno a su recuadro en el PDF; al
      acercar se van. Por página: solo la que se pellizca cambia. Ligero y rápido.
- [ ] PDF vectorial en el lienzo: tras acercar y alejar desaparecen las líneas (solo quedan letras) fuera
      de lo que estaba en foco; en PDF muy cargados, rellenos que se ponen negros o se tapan tras mover.
- [ ] Opción en el lienzo para pasar una página de vectorial a imagen.
- [ ] Fusionar páginas (p. ej. 4, 5 y 6) en un mismo lienzo, separadas a una distancia razonable.
- [ ] Opciones de la página web agrupadas con nombres claros: Herramientas de edición (lápiz, resaltador,
      borrador, deshacer), Herramientas avanzadas (medir, capas), Predeterminado (pasar de página),
      Herramientas especiales (guardar, compartir).
- [ ] El chat manda: un lienzo recibido y editado se veía nuevo en proyectos y viejo en el chat.
- [ ] Sincronizar con «aparato maestro»: al empezar, elegir qué gana en lo cambiado en los dos (preguntar,
      este aparato, el otro, el más reciente). Los lienzos nuevos de cada lado ya se suman (Mezcla).
- [ ] Visor de PDF: integrar `SublienzosDelPdf.kt` (escrito en el scratchpad, pendiente de meter en pdf/).
