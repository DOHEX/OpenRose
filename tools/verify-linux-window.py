#!/usr/bin/env python3
"""Manual niri regression: verify a native Wayland window with isolated preferences."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time

from PIL import Image


def windows():
    return json.loads(subprocess.check_output(["niri", "msg", "--json", "windows"]))


def action(name, window_id, *args):
    subprocess.run(["niri", "msg", "action", name, "--id", str(window_id), *args],
                   check=True, capture_output=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    launch = parser.add_mutually_exclusive_group()
    launch.add_argument("--dev", action="store_true", help="Run :desktopApp:run instead of the packaged client")
    launch.add_argument("--hot-reload", action="store_true", help="Run :desktopApp:hotRunJvm with automatic reload")
    parser.add_argument("--style", choices=("miuix", "material3"), default="miuix")
    parser.add_argument("--appearance", choices=("light", "dark"), default="light")
    parser.add_argument("--session-defaults", action="store_true", help="Keep the session's display selection instead of forcing Wayland without DISPLAY")
    parser.add_argument("--resize", action="store_true", help="Resize only the fixture window to 1012x1102 logical pixels")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    artifact = root / "desktopApp/build/compose/binaries/main/app/OpenRose/bin/OpenRose"
    if not (args.dev or args.hot_reload) and not artifact.is_file():
        parser.error("Build the Linux app image with ./gradlew :desktopApp:createDistributable first")
    if not os.environ.get("WAYLAND_DISPLAY"):
        parser.error("Run this probe inside a niri Wayland session")
    scratch = root / ".scratch/linux-window-probe"
    scratch.mkdir(parents=True, exist_ok=True)
    prefix = "tao-hot-" if args.hot_reload else "tao-dev-" if args.dev else "tao-"
    run = Path(tempfile.mkdtemp(prefix=prefix, dir=scratch))
    prefs = run / "prefs/.java/.userPrefs/io/github/dohex/openrose/prefs.xml"
    prefs.parent.mkdir(parents=True)
    prefs.write_text('<?xml version="1.0" encoding="UTF-8"?>\n'
                     '<!DOCTYPE map SYSTEM "http://java.sun.com/dtd/preferences.dtd">\n'
                     f'<map MAP_XML_VERSION="1.0"><entry key="appearance" value="{args.appearance}"/>'
                     f'<entry key="interface_style" value="{args.style}"/></map>')
    env = os.environ.copy()
    # With no X11 display, a visible window and xdg_surface/buffer traffic prove native Wayland.
    if not args.session_defaults:
        env.pop("DISPLAY", None)
        env.pop("NUCLEUS_TAO_LINUX_RENDERER", None)
        env["GDK_BACKEND"] = "wayland"
    env.pop("_JAVA_AWT_WM_NONREPARENTING", None)
    env["WAYLAND_DEBUG"] = "client"
    # The fixture must not touch the user's settings or print their JVM option values.
    env.pop("_JAVA_OPTIONS", None)
    env.pop("JDK_JAVA_OPTIONS", None)
    env["JAVA_TOOL_OPTIONS"] = (
        f'-Djava.util.prefs.userRoot="{run / "prefs"}" '
        '-Dnucleus.tao.fatalErrorDialog=false'
    )
    before = {window["id"] for window in windows()}
    command = [str(root / "gradlew"), ":desktopApp:run", "--console=plain"] if args.dev else [str(artifact)]
    if args.hot_reload:
        command = [str(root / "gradlew"), ":desktopApp:hotRunJvm", "--console=plain", "--auto",
                   "--mainClass", "io.github.dohex.openrose.desktop.MainKt"]
    target = None
    with (run / "app.log").open("w") as log:
        process = subprocess.Popen(command, cwd=root, env=env, stdout=log, stderr=subprocess.STDOUT)
        try:
            deadline = time.monotonic() + (120 if args.hot_reload else 60 if args.dev else 20)
            while time.monotonic() < deadline:
                candidates = [w for w in windows() if w["id"] not in before and w.get("title") == "OpenRose"]
                if len(candidates) > 1:
                    raise RuntimeError("Multiple new OpenRose windows: refusing to choose a window")
                if candidates:
                    target = candidates[0]
                    break
                if process.poll() is not None:
                    raise RuntimeError(f"Launcher exited with {process.returncode}; see {run / 'app.log'}")
                time.sleep(.1)
            else:
                raise RuntimeError(f"No new OpenRose window; see {run / 'app.log'}")
            time.sleep(4)
            if args.resize:
                action("set-window-width", target["id"], "1012")
                action("set-window-height", target["id"], "1102")
                time.sleep(1)
            target = next(w for w in windows() if w["id"] == target["id"])
            path = run / "window.png"
            action("screenshot-window", target["id"], "--path", str(path))
            for _ in range(50):
                try:
                    with Image.open(path) as source:
                        rgba = source.convert("RGBA")
                        content_bounds = rgba.getchannel("A").getbbox()
                        if content_bounds is None:
                            raise RuntimeError("Window screenshot is fully transparent")
                        # Tao's native GTK shadow is transparent outside the actual window.
                        image = rgba.crop(content_bounds).convert("RGB")
                    break
                except (FileNotFoundError, OSError):
                    time.sleep(.1)
            else:
                raise RuntimeError("Window screenshot was not written")
            width, height = image.size
            points = [(width - 12, height // 3), (width // 3, height - 12), (width - 12, height - 12)]
            colors = [image.getpixel(point) for point in points]
            minimum = 170 if args.appearance == "light" else 8
            unpainted = sum(min(color) < minimum for color in colors)
            trace = (run / "app.log").read_text(errors="replace")
            native_wayland = "get_xdg_surface" in trace and "wl_surface" in trace and ".attach(" in trace
            result = {"verdict": "FAIL" if unpainted or not native_wayland else "PASS", "style": args.style,
                      "appearance": args.appearance, "dev": args.dev, "hot_reload": args.hot_reload,
                      "resize": args.resize, "native_wayland": native_wayland,
                      "display_unset": "DISPLAY" not in env, "session_defaults": args.session_defaults,
                      "window_size": target["layout"]["window_size"], "screenshot_pixels": rgba.size,
                      "content_bounds": content_bounds,
                      "edge_colors": colors, "unpainted_edges": unpainted}
        finally:
            # Close only the new fixture window, including when Gradle owns the child JVM.
            if target is not None and any(w["id"] == target["id"] for w in windows()):
                action("close-window", target["id"])
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.terminate()
                try:
                    process.wait(timeout=3)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
    result["exit_code"] = process.returncode
    if process.returncode != 0:
        result["verdict"] = "FAIL"
    (run / "result.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result), flush=True)
    print(f"Evidence: {run}", flush=True)
    return 0 if result["verdict"] == "PASS" else 2


if __name__ == "__main__":
    raise SystemExit(main())
