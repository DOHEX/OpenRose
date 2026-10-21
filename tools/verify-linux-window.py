#!/usr/bin/env python3
"""Manual niri regression: capture only our packaged client window, with isolated preferences."""
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
    parser.add_argument("--raw-launcher", action="store_true", help="Compare the original launcher with the AWT compatibility variable absent")
    parser.add_argument("--style", choices=("miuix", "material3"), default="miuix")
    parser.add_argument("--resize", action="store_true", help="Resize only the fixture window to 1012x1102 logical pixels")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    artifact = root / "desktopApp/build/compose/binaries/main/app/OpenRose/bin/OpenRose"
    if not artifact.is_file():
        parser.error("Build the Linux app image with ./gradlew :desktopApp:createDistributable first")
    scratch = root / ".scratch/linux-window-probe"
    scratch.mkdir(parents=True, exist_ok=True)
    run = Path(tempfile.mkdtemp(prefix="raw-" if args.raw_launcher else "fixed-", dir=scratch))
    prefs = run / "prefs/.java/.userPrefs/io/github/dohex/openrose/prefs.xml"
    prefs.parent.mkdir(parents=True)
    prefs.write_text('<?xml version="1.0" encoding="UTF-8"?>\n'
                     '<!DOCTYPE map SYSTEM "http://java.sun.com/dtd/preferences.dtd">\n'
                     '<map MAP_XML_VERSION="1.0"><entry key="appearance" value="light"/>'
                     f'<entry key="interface_style" value="{args.style}"/></map>')
    env = os.environ.copy()
    env.pop("_JAVA_AWT_WM_NONREPARENTING", None)
    # The fixture must not touch the user's settings or print their JVM option values.
    env.pop("_JAVA_OPTIONS", None)
    env.pop("JDK_JAVA_OPTIONS", None)
    env["JAVA_TOOL_OPTIONS"] = f'-Djava.util.prefs.userRoot="{run / "prefs"}"'
    before = {window["id"] for window in windows()}
    command = [str(artifact if args.raw_launcher else root / "run-linux.sh")]
    with (run / "app.log").open("w") as log:
        process = subprocess.Popen(command, cwd=root, env=env, stdout=log, stderr=subprocess.STDOUT)
        try:
            deadline = time.monotonic() + 20
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
                raise RuntimeError("No new OpenRose window within 20 seconds")
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
                        image = source.convert("RGB")
                    break
                except (FileNotFoundError, OSError):
                    time.sleep(.1)
            else:
                raise RuntimeError("Window screenshot was not written")
            width, height = image.size
            points = [(width - 12, height // 3), (width // 3, height - 12), (width - 12, height - 12)]
            colors = [image.getpixel(point) for point in points]
            unpainted = sum(min(color) < 170 for color in colors)
            result = {"verdict": "FAIL" if unpainted else "PASS", "style": args.style,
                      "raw_launcher": args.raw_launcher, "resize": args.resize,
                      "window_size": target["layout"]["window_size"], "screenshot_pixels": image.size,
                      "edge_colors": colors, "unpainted_edges": unpainted}
            (run / "result.json").write_text(json.dumps(result, indent=2) + "\n")
            print(json.dumps(result), flush=True)
            print(f"Evidence: {run}", flush=True)
            return 2 if unpainted else 0
        finally:
            # Terminate only the process this fixture launched, never the user's existing client.
            process.terminate()
            try:
                process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()


if __name__ == "__main__":
    raise SystemExit(main())
