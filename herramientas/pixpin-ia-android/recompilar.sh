#!/bin/sh
# Vuelve a compilar solo este crate sobre la copia del PC que dejó compilar.sh (sin traer nada).
set -e
AQUI=$(cd "$(dirname "$0")" && pwd)
R=${RUST_TERMUX:-/data/data/com.termux/files/home/.cache/pixpin-rust-temporal}
C="$AQUI/copia-del-pc"
rm -rf "$C/crates/pixpin-ia-android" && cp -r "$AQUI/crate" "$C/crates/pixpin-ia-android"
export LD_LIBRARY_PATH="$R/lib:/data/data/com.termux/files/usr/lib"
export RUSTC="$R/bin/rustc" PATH="$R/bin:$PATH"
export RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wl,-soname,libpixpinia.so"
(cd "$C" && CARGO_TARGET_DIR="$AQUI/target" "$R/bin/cargo" build --release -p pixpin-ia-android)
cp "$AQUI/target/release/libpixpinia.so" "$AQUI/../../app/src/main/jniLibs/arm64-v8a/libpixpinia.so"
ls -l "$AQUI/../../app/src/main/jniLibs/arm64-v8a/libpixpinia.so"
