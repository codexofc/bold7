#!/usr/bin/env bash
# Compile-only stubs from the Q25's own binaries, then proof that the quickstep AIDL of
# core/platform match the device. Read-only on the phone: it only pulls files.
#   tools/stubs.sh               pull framework.jar, MtkSystemUI and Launcher3 from the phone
#   tools/stubs.sh --from <dir>  use local copies laid out like stubs/device/
# Produces stubs/framework.jar (for core/platform) and stubs/systemui.jar (for system/sysui),
# outside git; exits non-zero if tools/aidl_check.py finds a gap.
set -euo pipefail
cd "$(dirname "$0")/.."

D2J_VERSION=2.4
# Upstream publishes no checksum; this is the one pinned by Homebrew's dex2jar formula.
D2J_SHA256=ee7c45eb3c1d2474a6145d8d447e651a736a22d9664b6d3d3be5a5a817dda23a
D2J_ZIP=build/dex2jar/dex-tools-v$D2J_VERSION.zip
D2J=build/dex2jar/dex-tools-v$D2J_VERSION/d2j-dex2jar.sh

src=stubs/device
if [ "${1:-}" = --from ]; then
  src=${2:?usage: tools/stubs.sh [--from <dir>]}
else
  adb get-state >/dev/null
  mkdir -p "$src/framework"
  adb pull /system/framework/framework.jar "$src/framework/framework.jar"
  adb pull /system_ext/priv-app/MtkSystemUI/MtkSystemUI.apk "$src/MtkSystemUI.apk"
  adb pull /system_ext/priv-app/SearchLauncherQuickStep/SearchLauncherQuickStep.apk "$src/SearchLauncherQuickStep.apk"
fi
for f in framework/framework.jar MtkSystemUI.apk SearchLauncherQuickStep.apk; do
  [ -f "$src/$f" ] || { echo "missing $src/$f" >&2; exit 1; }
done

if [ ! -x "$D2J" ]; then
  mkdir -p build/dex2jar
  curl -fsSL -o "$D2J_ZIP" "https://github.com/pxb1988/dex2jar/releases/download/v$D2J_VERSION/dex-tools-v$D2J_VERSION.zip"
  echo "$D2J_SHA256  $D2J_ZIP" | shasum -a 256 -c - >/dev/null || { rm -f "$D2J_ZIP"; echo "dex2jar checksum mismatch" >&2; exit 1; }
  unzip -q -o "$D2J_ZIP" -d build/dex2jar
  chmod +x build/dex2jar/dex-tools-v$D2J_VERSION/*.sh
fi

# dex2jar reads every classesN.dex of the container itself. Never merge by extracting to
# disk: macOS is case-insensitive and AudioAttributes/audioAttributes would collide.
to_jar() { # <apk or jar> <output jar>
  local err=build/dex2jar/${2##*/}-errors.zip
  rm -f "$err"
  "$D2J" --force --no-code -e "$err" -o "$2" "$1" >/dev/null
  [ ! -f "$err" ] || echo "warning: dex2jar could not translate some classes, see $err" >&2
  # dex2jar writes class files version 50 (Java 6) even for interfaces with default methods,
  # which javac rejects. Signatures only (--no-code), so version 52 (Java 8) is truthful.
  python3 - "$2" <<'EOF'
import sys, zipfile
path = sys.argv[1]
with zipfile.ZipFile(path) as z:
    entries = [(i, z.read(i)) for i in z.infolist()]
with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
    for info, data in entries:
        if info.filename.endswith(".class") and int.from_bytes(data[6:8], "big") < 52:
            data = data[:6] + (52).to_bytes(2, "big") + data[8:]
        z.writestr(info, data)
EOF
  echo "$2: $(unzip -Z1 "$2" | grep -c '\.class$') classes"
}
mkdir -p stubs
to_jar "$src/framework/framework.jar" stubs/framework.jar
to_jar "$src/MtkSystemUI.apk" stubs/systemui.jar
shasum -a 256 "$src/framework/framework.jar" "$src/MtkSystemUI.apk" "$src/SearchLauncherQuickStep.apk"

python3 tools/aidl_check.py "$src/SearchLauncherQuickStep.apk" core/platform/src/main/aidl
