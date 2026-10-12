#!/bin/sh
# Pasa un libro de Excel a lo que recibe Kotlin (PXHL, PXHG y los primeros PXHT) con el binario
# hojas-a-pxhj (de ./compilar.sh), y dice cuánto tarda cada paso. Para medir y para los fixtures:
#   ./hojas-a-pxhj.sh <libro.xlsx> <carpeta de salida> [hoja] [filas por trozo] [trozos que guardar]
# En esta máquina el binario es del Rust de Termux: necesita sus bibliotecas (ver compilar.sh).
set -e
AQUI=$(cd "$(dirname "$0")" && pwd)
R=${RUST_TERMUX:-/data/data/com.termux/files/home/.cache/pixpin-rust-temporal}
export LD_LIBRARY_PATH="$R/lib:/data/data/com.termux/files/usr/lib"
exec "$AQUI/target/release/hojas-a-pxhj" "$@"
