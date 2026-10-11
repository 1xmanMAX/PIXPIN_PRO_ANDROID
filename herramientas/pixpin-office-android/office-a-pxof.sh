#!/bin/sh
# Pasa un Word o un PowerPoint a PXOF con el binario office-a-pxof (de ./compilar.sh), con las
# letras de PixPin y las del sistema, y dice cuánto tarda cada página. Para medir y para hacer los
# fixtures de las pruebas:
#   ./office-a-pxof.sh <documento> <carpeta de salida> [máx. páginas]
# En esta máquina el binario es del Rust de Termux: necesita sus bibliotecas (ver compilar.sh).
set -e
AQUI=$(cd "$(dirname "$0")" && pwd)
R=${RUST_TERMUX:-/data/data/com.termux/files/home/.cache/pixpin-rust-temporal}
export LD_LIBRARY_PATH="$R/lib:/data/data/com.termux/files/usr/lib"
exec "$AQUI/target/release/office-a-pxof" "$1" "$2" "$AQUI/../../app/src/main/assets/fuentes-ttf" ${3:-}
