"""Generates the app icon set from code, so there's no binary blob to maintain.

    python build/make_icon.py

Writes build/icon.ico (Windows), build/icon.png (Linux/generic, 512px) and
build/icon.icns is left to electron-builder, which derives it from icon.png.
"""

from __future__ import annotations

import os

from PIL import Image, ImageDraw

OUT = os.path.dirname(os.path.abspath(__file__))

# Matches the .mark gradient in renderer/styles.css
C1 = (109, 92, 255)    # --accent
C2 = (217, 79, 255)


def rounded_mask(size: int, radius_ratio: float = 0.22) -> Image.Image:
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        (0, 0, size - 1, size - 1), radius=int(size * radius_ratio), fill=255
    )
    return mask


def gradient(size: int) -> Image.Image:
    """Diagonal C1 -> C2, matching the 135deg CSS gradient."""
    grad = Image.new("RGB", (size, size))
    px = grad.load()
    for y in range(size):
        for x in range(size):
            t = (x + y) / (2 * (size - 1))
            px[x, y] = (
                round(C1[0] + (C2[0] - C1[0]) * t),
                round(C1[1] + (C2[1] - C1[1]) * t),
                round(C1[2] + (C2[2] - C1[2]) * t),
            )
    return grad


def render(size: int) -> Image.Image:
    ss = size * 4  # supersample, then downscale for clean edges
    img = Image.new("RGBA", (ss, ss), (0, 0, 0, 0))
    img.paste(gradient(ss), (0, 0), rounded_mask(ss))

    d = ImageDraw.Draw(img)
    w = ss

    # Download arrow: shaft + head, sitting low and centred.
    shaft_w = w * 0.085
    cx = w / 2
    top = w * 0.26
    bot = w * 0.56
    d.rounded_rectangle(
        (cx - shaft_w / 2, top, cx + shaft_w / 2, bot),
        radius=shaft_w / 2,
        fill=(255, 255, 255, 235),
    )
    head = w * 0.155
    d.polygon(
        [(cx - head, bot - w * 0.03), (cx + head, bot - w * 0.03), (cx, bot + w * 0.16)],
        fill=(255, 255, 255, 235),
    )

    # Tray line underneath, the universal "save" cue.
    tray_y = w * 0.79
    tray_w = w * 0.30
    d.rounded_rectangle(
        (cx - tray_w, tray_y, cx + tray_w, tray_y + w * 0.055),
        radius=w * 0.028,
        fill=(255, 255, 255, 235),
    )

    return img.resize((size, size), Image.LANCZOS)


def main() -> None:
    sizes = [16, 24, 32, 48, 64, 128, 256]
    frames = [render(s) for s in sizes]

    ico_path = os.path.join(OUT, "icon.ico")
    frames[-1].save(ico_path, format="ICO", sizes=[(s, s) for s in sizes])

    png_path = os.path.join(OUT, "icon.png")
    render(512).save(png_path, format="PNG")

    print(f"wrote {ico_path}")
    print(f"wrote {png_path}")


if __name__ == "__main__":
    main()
