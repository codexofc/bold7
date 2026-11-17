#!/usr/bin/env bash
# Compile-only stubs from the Q25's own binaries (LineageOS 24), then proof that the quickstep
# AIDL of core/platform match the device. Read-only on the phone: it only pulls files.
#   tools/stubs.sh               pull the framework jars, SystemUI and Launcher3 from the phone, and
#                                framework-res, Settings and PermissionController for the RROs
#   tools/stubs.sh --from <dir>  use local copies laid out like stubs/device/
#   STUBS_OUT=<dir>              write the stubs there instead of stubs/ (another ROM's, side by side)
# Produces stubs/framework.jar (for core/platform) and stubs/systemui.jar (for system/sysui),
# outside git; exits non-zero if tools/aidl_check.py finds a gap. The RROs of system/overlays link
# against $src/framework/framework-res.apk and are checked against their targets there.
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
  for j in framework framework-graphics framework-location; do
    adb pull /system/framework/$j.jar "$src/framework/$j.jar"
  done
  adb pull /apex/com.android.nfcservices/javalib/framework-nfc.jar "$src/framework/framework-nfc.jar"
  adb pull /system_ext/priv-app/SystemUI/SystemUI.apk "$src/SystemUI.apk"
  adb pull /system_ext/priv-app/Launcher3QuickStep/Launcher3QuickStep.apk "$src/Launcher3QuickStep.apk"
  adb pull /system/framework/framework-res.apk "$src/framework/framework-res.apk"
  adb pull /system_ext/priv-app/Settings/Settings.apk "$src/Settings.apk"
  # In the com.android.permission APEX, its folder named after the build.
  adb pull "$(adb shell pm path com.android.permissioncontroller | sed -n 's/^package://p' | head -n 1)" "$src/PermissionController.apk"
fi
# Android 17 moved classes out of framework.jar into jars of their own on the boot class path:
# NfcAdapter into the nfcservices APEX, location and part of graphics into /system/framework.
JARS="framework framework-graphics framework-location framework-nfc"
RES="framework/framework-res.apk Settings.apk PermissionController.apk"
for f in $(printf 'framework/%s.jar ' $JARS) SystemUI.apk Launcher3QuickStep.apk $RES; do
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
out=${STUBS_OUT:-stubs}
mkdir -p "$out"
# One framework.jar for the compilers: each boot jar converted, then merged zip to zip (never
# through the case-insensitive disk), framework.jar's class winning a duplicate.
for j in $JARS; do to_jar "$src/framework/$j.jar" "build/dex2jar/$j.jar"; done
python3 - "$out/framework.jar" $(printf 'build/dex2jar/%s.jar ' $JARS) <<'EOF'
import sys, zipfile
seen = set()
with zipfile.ZipFile(sys.argv[1], "w", zipfile.ZIP_DEFLATED) as out:
    for path in sys.argv[2:]:
        with zipfile.ZipFile(path) as z:
            for info in z.infolist():
                if info.filename not in seen:
                    seen.add(info.filename)
                    out.writestr(info, z.read(info))
print(f"{sys.argv[1]}: {sum(n.endswith('.class') for n in seen)} classes")
EOF
to_jar "$src/SystemUI.apk" "$out/systemui.jar"
shasum -a 256 $(printf "$src/framework/%s.jar " $JARS) "$src/SystemUI.apk" "$src/Launcher3QuickStep.apk" $(printf "$src/%s " $RES)

# Launcher3 implements ILauncherProxy and calls IShellTransitions; SystemUI is read too, in case
# its R8 keeps what Launcher3's drops. registerRemoteForTakeover is in neither copy of the
# interface: SystemUI's merged IShellTransitionsImpl answers it at 7, checked by hand (docs/STUBS.md).
python3 tools/aidl_check.py --absent com.android.wm.shell.shared.IShellTransitions.registerRemoteForTakeover \
  "$src/SystemUI.apk" "$src/Launcher3QuickStep.apk" core/platform/src/main/aidl
