#!/bin/sh
# **El APK listo para instalar a mano.**
#
# `assembleRelease` deja el APK firmado **solo con v2**. Android 10 en adelante la da por
# buena, pero el instalador que abre un archivo descargado —el de «instalar desde
# almacenamiento», que es como se reparte esto— quiere además la firma **v1 (JAR)**, y sin
# ella el teléfono dice «hay un problema con el archivo de la app» sin más explicación. Pasó
# el 9-sep-2026 y costó un rato encontrarlo, porque el APK estaba perfectamente bien: lo que
# le faltaba era una firma que el propio Gradle decide omitir.
#
# Ponerlo en `build.gradle.kts` **no sirve**: con `minSdk 29` el plugin omite la v1 aunque se
# pida (`enableV1Signing` no surte efecto). La única forma de incluirla de verdad es volver a
# firmar con `apksigner` diciéndole `--min-sdk-version 21`, que es lo que hace este guion.
#
# Uso:  herramientas/apk-para-repartir.sh [salida.apk]
set -e
cd "$(dirname "$0")/.."

SDK=${ANDROID_HOME:-/root/android-sdk}
TOOLS=$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)
[ -n "$TOOLS" ] || { echo "no encuentro build-tools en $SDK"; exit 1; }

ORIGEN=pixpin-build/app/outputs/apk/release/app-release.apk
[ -f "$ORIGEN" ] || { echo "no está $ORIGEN; compila antes con assembleRelease"; exit 1; }

VERSION=$(grep -m1 'versionName' app/build.gradle.kts | sed 's/.*"\(.*\)".*/\1/')
SALIDA=${1:-PixPin-$VERSION.apk}

# `--out` y no firmar en el sitio: firmando encima del propio archivo, apksigner se queda
# con el esquema que ya traía y la v1 no llega a escribirse (comprobado).
"$TOOLS/apksigner" sign \
  --ks "${HOME:-/root}/.android/debug.keystore" \
  --ks-pass pass:android --key-pass pass:android --ks-key-alias androiddebugkey \
  --min-sdk-version 21 --max-sdk-version 36 \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out "$SALIDA" "$ORIGEN"

# Y se comprueba, que es la mitad del valor de esto: un APK mal firmado no falla al firmarlo,
# falla en el teléfono de otro.
"$TOOLS/apksigner" verify --verbose --min-sdk-version 21 "$SALIDA" | head -4

echo
echo "$SALIDA"
echo "  $(stat -c%s "$SALIDA") bytes"
echo "  sha256 $(sha256sum "$SALIDA" | cut -d' ' -f1)"
echo
echo "Al subirlo, **comprobar el archivo servido**: la red de este entorno corta las bajadas"
echo "largas, así que no vale con bajarlo. Se compara el tamaño de la cabecera y unos trozos"
echo "pedidos por rango (curl -r), que sí llegan enteros."
