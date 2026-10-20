#!/bin/sh
# Las pruebas del PC que van dentro del crate (las de `orden.rs`, los enlaces), aquí mismo: el Rust de
# Termux compila para este aparato. Sobre la copia que dejó compilar.sh.
set -e
AQUI=$(cd "$(dirname "$0")" && pwd)
R=${RUST_TERMUX:-/data/data/com.termux/files/home/.cache/pixpin-rust-temporal}
C="$AQUI/copia-del-pc"
rm -rf "$C/crates/pixpin-ia-android" && cp -r "$AQUI/crate" "$C/crates/pixpin-ia-android"
export LD_LIBRARY_PATH="$R/lib:/data/data/com.termux/files/usr/lib"
export RUSTC="$R/bin/rustc" PATH="$R/bin:$PATH"
(cd "$C" && CARGO_TARGET_DIR="$AQUI/target" "$R/bin/cargo" test -p pixpin-ia-android "$@")
