#!/usr/bin/env bash
# Usage: tools/build_assets.sh /path/to/OZMA-0_9_5-ports.zip
# Output: app/src/main/assets/runtime.zip + runtime.id, app/src/main/res/drawable-nodpi/ic_launcher.png
#
# aarch64 pieces (our libSDL2 shim + glibc loader/libs) come from either:
#   PREBUILT_LIBS=<dir>   a directory already containing them (used by the GitHub workflow, built in Debian bookworm), or
#   a local cross toolchain:  sudo apt install gcc-aarch64-linux-gnu zip unzip   (SYSROOT defaults to /usr/aarch64-linux-gnu)
set -euo pipefail
ZIP="${1:?usage: $0 OZMA-0_9_5-ports.zip}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
command -v unzip >/dev/null && command -v zip >/dev/null || { echo "need unzip and zip"; exit 1; }

unzip -q "$ZIP" -d "$W/src"
APP="$(find "$W/src" -name bricktracker -type f -printf '%h\n' | head -1)"
[ -n "$APP" ] || { echo "bricktracker not found in zip"; exit 1; }
DATA="$(find "$W/src" -maxdepth 2 -type d -name ozma-data | head -1 || true)"
[ -n "$DATA" ] || DATA="$(find "$W/src" -maxdepth 3 -type d -name Samples -printf '%h\n' | head -1 || true)"
echo "app: $APP"; echo "data: ${DATA:-<none>}"

R="$W/rt"; mkdir -p "$R/bin" "$R/lib" "$R/data"
cp "$APP/bricktracker" "$R/bin/"

if [ -n "${PREBUILT_LIBS:-}" ]; then
  echo "== using prebuilt aarch64 libs from $PREBUILT_LIBS"
  cp -L "$PREBUILT_LIBS"/* "$R/lib/"
else
  CC="${CC:-aarch64-linux-gnu-gcc}"; SYSROOT="${SYSROOT:-/usr/aarch64-linux-gnu}"
  command -v "$CC" >/dev/null || { echo "missing $CC  ->  sudo apt install gcc-aarch64-linux-gnu"; exit 1; }
  [ -f "$SYSROOT/lib/ld-linux-aarch64.so.1" ] || { echo "no glibc at $SYSROOT/lib (set SYSROOT=...)"; exit 1; }
  echo "== building shim (glibc aarch64)"
  "$CC" -shared -fPIC -O2 -fvisibility=hidden -Wl,-soname,libSDL2-2.0.so.0 -o "$R/lib/libSDL2-2.0.so.0" "$ROOT/shim/sdl_shim.c" -lpthread
  for f in ld-linux-aarch64.so.1 libc.so.6 libm.so.6 libpthread.so.0 libdl.so.2; do cp -L "$SYSROOT/lib/$f" "$R/lib/$f"; done
fi
for f in libSDL2-2.0.so.0 ld-linux-aarch64.so.1 libc.so.6 libm.so.6 libpthread.so.0 libdl.so.2; do
  [ -s "$R/lib/$f" ] || { echo "missing $f in runtime lib dir"; exit 1; }
done
[ -n "${DATA:-}" ] && cp -r "$DATA"/. "$R/data/"

# Optional prebuilt ARM64 probes used to isolate loader/libpthread/SDL startup.
if [ -n "${PREBUILT_PROBES:-}" ]; then
  mkdir -p "$R/diagnostics"
  for f in glibc-basic glibc-pthread-link-only glibc-pthread glibc-sdl-dlopen; do
    [ -s "$PREBUILT_PROBES/$f" ] || { echo "missing diagnostic probe: $PREBUILT_PROBES/$f"; exit 1; }
    cp "$PREBUILT_PROBES/$f" "$R/diagnostics/$f"
    chmod 755 "$R/diagnostics/$f"
  done
fi

mkdir -p "$ROOT/app/src/main/assets" "$ROOT/app/src/main/res/drawable-nodpi"
rm -f "$ROOT/app/src/main/assets/runtime.zip"
( cd "$R" && zip -qr -9 "$ROOT/app/src/main/assets/runtime.zip" . )
[ -f "$APP/icon.png" ] && cp "$APP/icon.png" "$ROOT/app/src/main/res/drawable-nodpi/ic_launcher.png"
sha256sum "$APP/bricktracker" | cut -c1-16 > "$ROOT/app/src/main/assets/runtime.id"
ls -lh "$ROOT/app/src/main/assets/runtime.zip"
echo "OK."
