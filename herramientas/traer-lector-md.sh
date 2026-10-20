#!/bin/sh
# Trae del PC la página de las notas (lector-md.html: md-reader con CodeMirror), la misma que usa el
# lector de notas de Windows: con ella la tinta y los marcos del riel caen en el mismo sitio del texto
# en los dos aparatos. Ver app/src/main/java/com/forge/pixpin/ui/VisorDeNotaActivity.kt.
#
# Uso: herramientas/traer-lector-md.sh <repo del PC>
set -e
AQUI=$(cd "$(dirname "$0")" && pwd)
PC=${1:?Falta la ruta del repositorio PIXPIN_PRO_WINDOWS}
cp "$PC/apps/pixpin/recursos/lector-md.html" "$AQUI/../app/src/main/assets/lector-md.html"
echo "Traída del PC $(cd "$PC" && git log -1 --format=%h -- apps/pixpin/recursos/lector-md.html)"
