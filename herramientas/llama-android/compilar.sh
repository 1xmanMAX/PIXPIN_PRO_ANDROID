#!/bin/sh
# Compila llama-server de llama.cpp (la MISMA versión que el PC: b11580) para Android arm64
# y lo deja como app/src/main/jniLibs/arm64-v8a/libllamaserver.so.
#
# Es un EJECUTABLE con nombre de biblioteca: Android solo extrae a nativeLibraryDir lo que
# se llama lib*.so, y desde ahí (y solo desde ahí, por W^X desde Android 10) se puede
# lanzar con ProcessBuilder. Ver ia/ServidorLlama.kt.
#
# En esta máquina (un teléfono con PRoot) se compila con el clang de Termux, que ya es
# aarch64-linux-android (bionic). cmake/ninja no están en Termux por apt (capado): se bajan
# como .deb de packages.termux.dev (ver la memoria «pixpin-build-aapt2») y se ponen con
# ENTORNO=…/env.sh. La libc++ se enlaza ESTÁTICA (libc++_static.a + libc++abi.a del paquete
# ndk-multilib de Termux, carpeta NDK_ESTATICO) para que el binario solo pida libc, libm,
# libdl y liblog del sistema.
#
# CPU: armv8.2-a + dotprod + fp16 (lo tienen el S24 y cualquier Cortex-A76 o posterior,
# también los Kirin de las Huawei). No se pide i8mm: un Kirin sin él moriría con SIGILL.
#
# Uso: compilar.sh [carpeta-del-código]   (si no existe, se baja el tar.gz de la versión)
set -e
VERSION=b11580
AQUI=$(cd "$(dirname "$0")" && pwd)
RAIZ=$(cd "$AQUI/../.." && pwd)
TRABAJO=${TRABAJO:-$RAIZ/pixpin-build/llama-android}
FUENTE=${1:-$TRABAJO/llama.cpp-$VERSION}
ENTORNO=${ENTORNO:-/root/android-sdk/termux-extra/env.sh}
NDK_ESTATICO=${NDK_ESTATICO:-/root/android-sdk/termux-extra/ndk-static}
HILOS=${HILOS:-3}

[ -f "$ENTORNO" ] && . "$ENTORNO"
mkdir -p "$TRABAJO"
if [ ! -d "$FUENTE" ]; then
    curl -sL -o "$TRABAJO/$VERSION.tar.gz" \
        "https://github.com/ggml-org/llama.cpp/archive/refs/tags/$VERSION.tar.gz"
    tar xzf "$TRABAJO/$VERSION.tar.gz" -C "$TRABAJO"
fi

ENLACE="-nostdlib++ -L$NDK_ESTATICO -Wl,-Bstatic -lc++_static -lc++abi -Wl,-Bdynamic"
ENLACE="$ENLACE -Wl,-z,max-page-size=16384 -Wl,--gc-sections -Wl,--as-needed -s"

cmake -G Ninja -S "$FUENTE" -B "$TRABAJO/build" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_C_COMPILER=clang -DCMAKE_CXX_COMPILER=clang++ \
    -DCMAKE_C_FLAGS="-ffunction-sections -fdata-sections" \
    -DCMAKE_CXX_FLAGS="-ffunction-sections -fdata-sections" \
    -DCMAKE_EXE_LINKER_FLAGS="$ENLACE" \
    -DCMAKE_SKIP_RPATH=ON \
    -DBUILD_SHARED_LIBS=OFF \
    -DGGML_NATIVE=OFF \
    -DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16 \
    -DGGML_OPENMP=OFF \
    -DGGML_LLAMAFILE=OFF \
    -DLLAMA_OPENSSL=OFF \
    -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF \
    -DLLAMA_BUILD_TOOLS=ON -DLLAMA_BUILD_SERVER=ON -DLLAMA_BUILD_APP=OFF \
    -DLLAMA_BUILD_UI=OFF -DLLAMA_USE_PREBUILT_UI=OFF \
    -DLLAMA_BUILD_NUMBER=11580
cmake --build "$TRABAJO/build" --target llama-server -j "$HILOS"

SALIDA="$RAIZ/app/src/main/jniLibs/arm64-v8a/libllamaserver.so"
cp "$TRABAJO/build/bin/llama-server" "$SALIDA"
# El clang de Termux mete siempre -rpath=$PREFIX/lib: se deja vacío (ver sin-runpath.py).
python3 -I "$AQUI/sin-runpath.py" "$SALIDA"
ls -l "$SALIDA"
# Lo que pide al sistema: tiene que ser solo libc, libm, libdl y liblog.
readelf -d "$SALIDA" | grep -E "NEEDED|RUNPATH|RPATH" || true
