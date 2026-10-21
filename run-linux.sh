#!/usr/bin/env bash
set -euo pipefail

project_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
desktop=":${XDG_CURRENT_DESKTOP:-}:${XDG_SESSION_DESKTOP:-}:${DESKTOP_SESSION:-}:"

# AWT must process ConfigureNotify from niri's non-reparenting XWayland manager.
# Set this only for our child process, before the JVM initializes its X11 toolkit.
if [[ -n "${WAYLAND_DISPLAY:-}" && "${desktop,,}" == *:niri:* ]]; then
    export _JAVA_AWT_WM_NONREPARENTING="${_JAVA_AWT_WM_NONREPARENTING-1}"
fi

if [[ "${1:-}" == "--dev" ]]; then
    shift
    cd -- "$project_dir"
    exec ./gradlew :desktopApp:run "$@"
fi

launcher=$project_dir/desktopApp/build/compose/binaries/main/app/OpenRose/bin/OpenRose
if [[ ! -x "$launcher" ]]; then
    printf 'Linux client not built. Run ./gradlew :desktopApp:createDistributable first.\n' >&2
    exit 1
fi
exec "$launcher" "$@"
