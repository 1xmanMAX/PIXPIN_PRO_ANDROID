#!/bin/sh
# Compila libpixpincad.so (arm64) y lo deja en app/src/main/jniLibs/arm64-v8a/.
# En esta máquina (un teléfono con PRoot) el Rust que funciona es el de Termux, que ya es
# aarch64-linux-android: ver la memoria «pixpin-pc-contra-android». En otra, con el NDK:
#   cargo build --release --target aarch64-linux-android (y su enlazador en .cargo/config.toml).
set -e
AQUI=$(cd "$(dirname "$0")" && pwd)
R=${RUST_TERMUX:-/data/data/com.termux/files/home/.cache/pixpin-rust-temporal}
export LD_LIBRARY_PATH="$R/lib:/data/data/com.termux/files/usr/lib"
# Páginas de 16 KB (Android 15): igual que libpdfsqueeze.so.
export RUSTC="$R/bin/rustc" PATH="$R/bin:$PATH"
export RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wl,-soname,libpixpincad.so"
cd "$AQUI"
"$R/bin/cargo" build --release --lib --bins
cp target/release/libpixpincad.so "$AQUI/../../app/src/main/jniLibs/arm64-v8a/libpixpincad.so"
ls -l "$AQUI/../../app/src/main/jniLibs/arm64-v8a/libpixpincad.so"
