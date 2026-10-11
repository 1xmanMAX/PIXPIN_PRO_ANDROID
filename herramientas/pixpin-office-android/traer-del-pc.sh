#!/bin/sh
# Trae del repositorio del PC (PIXPIN_PRO_WINDOWS) el crate puro de Office —pixpin-office: Word y
# PowerPoint maquetados página a página— y, de pixpin-docs, solo su lector de XML (lo único que
# pixpin-office usa de él). No se cambia ni una línea de lo del PC: lo de Android (medir las letras
# con los TTF del teléfono, el formato que lee Kotlin y la entrada JNI) vive en src/.
#
# Uso: herramientas/pixpin-office-android/traer-del-pc.sh <repo del PC>
set -e
AQUI=$(cd "$(dirname "$0")" && pwd)
PC=${1:?Falta la ruta del repositorio PIXPIN_PRO_WINDOWS}
rm -rf "$AQUI/pc/pixpin-office/src"
mkdir -p "$AQUI/pc/pixpin-office" "$AQUI/pc/pixpin-docs/src"
cp -r "$PC/crates/pixpin-office/src" "$AQUI/pc/pixpin-office/src"
cp "$PC/crates/pixpin-docs/src/xml.rs" "$AQUI/pc/pixpin-docs/src/xml.rs"
(cd "$PC" && git rev-parse --short HEAD) > "$AQUI/pc/COMMIT_DEL_PC"
echo "Traído del PC $(cat "$AQUI/pc/COMMIT_DEL_PC"). Compila con ./compilar.sh"
