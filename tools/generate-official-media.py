#!/usr/bin/env python3
"""Regenerate OpenRose's original neutral earbud clips (requires ffmpeg with SVG support)."""
from pathlib import Path
import subprocess
import tempfile

output = Path(__file__).resolve().parents[1] / "androidApp/src/main/res/raw"
output.mkdir(parents=True, exist_ok=True)
with tempfile.TemporaryDirectory(prefix="openrose-media-") as temporary:
    for side in ("left", "right"):
        transform = "" if side == "left" else 'transform="translate(192 0) scale(-1 1)"'
        source = Path(temporary) / f"{side}.svg"
        source.write_text(
            '<svg xmlns="http://www.w3.org/2000/svg" width="192" height="192" viewBox="0 0 192 192">'
            '<rect width="192" height="192" fill="#202020"/>'
            f'<g {transform}>'
            '<rect x="67" y="35" width="57" height="72" rx="27" fill="#f4f4f5"/>'
            '<rect x="99" y="88" width="28" height="66" rx="13" fill="#f4f4f5"/>'
            '<ellipse cx="93" cy="69" rx="12" ry="9" fill="#4b4b50"/>'
            '</g></svg>'
        )
        subprocess.run([
            "ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-loop", "1", "-i", str(source),
            "-t", "1", "-r", "24", "-an", "-c:v", "libx264", "-profile:v", "baseline", "-level", "3.0",
            "-pix_fmt", "yuv420p", "-movflags", "+faststart", str(output / f"openrose_earbud_{side}.mp4"),
        ], check=True)
