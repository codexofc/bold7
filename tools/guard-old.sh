# Sourced by tools/install.sh and tools/uninstall.sh, after they define sh() (adb shell, CR stripped).
#
# refuse_if_old_build <last line of the message>: returns 1, saying why, while the BB10 build
# (formerly Cascade) is still on the phone: its KernelSU module `cascade`, installed or waiting
# in /data/adb/modules_update for the next boot, or any package dev.cascade.* (-u also lists the
# ones uninstalled with their data kept). A priv-app renamed is another app, and nothing here
# knows how to undo what that build changed (quick settings list, sound settings, HOME): only the
# tools/uninstall.sh of the bb10-final tag does. Read-only on the phone.
refuse_if_old_build() {
  local active pending apps essai
  active=$(sh "su -c 'ls /data/adb/modules 2>/dev/null; true'" | grep -x cascade || true)
  pending=$(sh "su -c 'ls /data/adb/modules_update 2>/dev/null; true'" | grep -x cascade || true)
  apps=$(sh "pm list packages -u dev.cascade" | sort || true)
  [ -n "$active$pending$apps" ] || return 0
  essai=$(echo "$apps" | grep -x 'package:dev.cascade.essai' || true)
  {
    echo "The previous build (cascade) is still on the phone:"
    [ -z "$active" ] || echo "  KernelSU module: /data/adb/modules/$active"
    [ -z "$pending" ] || echo "  KernelSU module waiting for the next boot: /data/adb/modules_update/$pending"
    [ -z "$apps" ] || echo "$apps" | sed 's/^/  /'
    if [ -n "$active$pending$(echo "$apps" | grep -vx 'package:dev.cascade.essai' || true)" ]; then
      echo "Remove it first, with its own scripts, in two runs around a reboot:"
      echo "  git worktree add \"\$(mktemp -d)/bb10-final\" bb10-final"
      echo "  (cd <that directory> && tools/uninstall.sh)   # then reboot, then run it again"
      echo "Check with ksud module list that it is marked remove=true and that no update waits in /data/adb/modules_update."
    fi
    # The test app is no part of the module: the old uninstall.sh does not remove it.
    [ -z "$essai" ] || echo "dev.cascade.essai is left by hand: adb uninstall dev.cascade.essai"
    echo "$1"
  } >&2
  return 1
}
