#!/bin/sh
# **Regenera `app/src/main/assets/figuras/serie.json.gz` con el catálogo del PC** (figuras_de_serie).
# Uso: herramientas/figuras-de-serie/volcar.sh /ruta/a/PIXPIN_PRO_WINDOWS
# Después, si cambió la `VERSION` del PC, súbela en motor/FigurasDeSerie.kt y la prueba.
set -e
PC=${1:?ruta del repositorio del PC}
AQUI=$(cd "$(dirname "$0")/../.." && pwd)
TMP=$(mktemp -d)
mkdir -p "$TMP/src"
cp "$(dirname "$0")/main.rs" "$TMP/src/main.rs"
cat > "$TMP/Cargo.toml" <<FIN
[package]
name = "volcar"
version = "0.1.0"
edition = "2021"
[dependencies]
pixpin-motor2d = { path = "$PC/crates/pixpin-motor2d" }
[workspace]
FIN
(cd "$TMP" && RUSTUP_TOOLCHAIN=stable cargo run --release -q) | gzip -9 > "$AQUI/app/src/main/assets/figuras/serie.json.gz"
rm -rf "$TMP"
