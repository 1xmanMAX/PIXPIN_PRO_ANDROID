# PixPin de un vistazo

Catálogo visual. Cada lámina es una función.

| | |
|---|---|
| [Dónde se dibuja](#dónde-se-dibuja) · [Capturar](#capturar) · [Pines](#pines) | [Dibujar](#dibujar) · [Tapar](#tapar-y-señalar) · [Medir](#medir) · [Construir](#construir) |
| [Gestos](#gestos) · [Lápiz](#lápiz) · [Imán](#imán) · [Alfiler](#alfiler) · [Bote](#bote) | [Recortar](#recortar) · [El elemento](#el-elemento) · [Salidas](#salidas) · [PDF](#pdf) |
| [Ajustes](#ajustes) · [Dentro](#dentro) | |

---

## Dónde se dibuja

![Las cuatro pantallas donde vive el mismo motor](img/superficies.svg)

---

## Capturar

![Bola flotante, region libre, ventana y captura larga](img/captura.svg)

---

## Pines

![Tipos de pin flotante](img/pines.svg)

---

## Dibujar

![Rectangulo, elipse, rombo, linea, lapiz, marcador, texto y esquinas](img/herramientas-dibujar.svg)

---

## Tapar y señalar

![Mosaico, foco, numeros de serie, imagen, hoja y borrador](img/herramientas-tapar.svg)

---

## Medir

![Cota, escalar, escala grafica y angulos internos](img/herramientas-medir.svg)

---

## Construir

![Bote, recortar, extender, alfiler, guias y transportador](img/herramientas-arreglar.svg)

---

## Gestos

![Un dedo dibuja, dos encuadran, el segundo hace la figura perfecta](img/gestos.svg)

```mermaid
stateDiagram-v2
    direction LR
    [*] --> Quieto
    Quieto --> Tocando: baja 1 dedo
    Tocando --> Dibujando: se mueve más de 14 px
    Tocando --> Herramienta: suelta sin moverse
    Dibujando --> Perfecta: baja el 2.º dedo
    Perfecta --> Dibujando: lo levanta
    Quieto --> Encuadre: bajan 2 dedos
    Encuadre --> Quieto: los levanta
    Dibujando --> Quieto: levanta
    Herramienta --> Quieto: bote · texto · número
```

---

## Lápiz

![Arranca al tocar, aprovecha todas las muestras, sigue la presion y rechaza la palma](img/lapiz.svg)

---

## Imán

![Vertices, medios, cruces y borde: a que se pega el dedo](img/anclajes.svg)

Prioridad: vértice → punto medio → cruce → centro → **borde** (solo si no hay nada más).

---

## Alfiler

![Los grados de libertad segun cuantos alfileres](img/alfiler.svg)

| clavos | qué hace |
|:--:|---|
| 0 | se mueve libre |
| 1 | gira alrededor de él |
| 2+ | fija: solo se estira desde otro punto |

---

## Bote

![Como se encuentra el espacio cerrado y se rellena](img/relleno.svg)

---

## Recortar

![Recorta el tramo entre dos cruces, extiende hasta el primero](img/recorte.svg)

---

## El elemento

![Anatomia de un elemento](img/elemento.svg)

---

## Salidas

![Imagen, SVG para documentos, PDF de varias hojas y archivo editable](img/salidas.svg)

---

## PDF

![Como se le pega una anotacion a un PDF sin romperlo](img/pdf-incremental.svg)

```mermaid
flowchart LR
    A[PDF ajeno] --> B[se abre una hoja]
    B --> C[la dibujas encima]
    C --> D{qué haces}
    D -->|devolver| E[se añade al final<br/>el texto original sigue vivo]
    D -->|exportar| F[PDF nuevo solo con esa hoja]
    D -->|esperar| G[queda en espera<br/>para juntarla luego]
```

---

## Segundo dedo

![El segundo dedo convierte la figura en exacta](img/segundo-dedo.svg)

---

## Ajustes

![Modo de captura, barra a tu gusto, guardado automatico y modo seguro](img/ajustes.svg)

---

## Dentro

```mermaid
flowchart TB
    subgraph nucleo["motor · sin Android"]
        E[Element] --> S[Scene]
        S --> R[Rough · Freehand]
        S --> G[Perímetros · Regiones · Recorte]
        S --> N[Nudos · Ángulos · Medida]
        S --> P[PdfLectura]
    end
    nucleo --> UI[Renderer · DrawCanvas · Toolbar]
    UI --> C1[captura]
    UI --> C2[pin]
    UI --> C3[capa]
    UI --> C4[editor]
```

El núcleo no importa `android.*`. Se prueba en la JVM, sin emulador.

```
925 pruebas · todas en verde
```
