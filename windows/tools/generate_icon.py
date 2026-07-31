#!/usr/bin/env python3
"""Generate the deterministic Sense aurora icon used by the TSF profile."""

from __future__ import annotations

import math
import struct
from pathlib import Path


SIZES = (16, 20, 24, 32, 40, 48, 64, 256)


def rounded_square_alpha(x: float, y: float, size: int) -> float:
    radius = size * 0.23
    left = radius
    right = size - radius
    top = radius
    bottom = size - radius
    nearest_x = min(max(x, left), right)
    nearest_y = min(max(y, top), bottom)
    distance = math.hypot(x - nearest_x, y - nearest_y)
    edge = radius - distance
    return min(1.0, max(0.0, edge + 0.6))


def pixel(x: int, y: int, size: int) -> tuple[int, int, int, int]:
    fx = x + 0.5
    fy = y + 0.5
    alpha = rounded_square_alpha(fx, fy, size)
    if alpha <= 0:
        return 0, 0, 0, 0

    nx = fx / size
    ny = fy / size
    radial = max(0.0, 1.0 - math.hypot(nx - 0.38, ny - 0.28) * 1.35)
    red = 12 + int(18 * radial + 12 * ny)
    green = 20 + int(26 * radial + 8 * ny)
    blue = 39 + int(42 * radial + 24 * nx)

    # A vertical aurora ribbon whose center line reads as a compact "S".
    center = 0.5 + 0.19 * math.sin((ny - 0.5) * math.tau)
    ribbon_distance = abs(nx - center)
    core_width = 0.048 if size >= 32 else 0.065
    glow_width = core_width * 3.4
    glow = max(0.0, 1.0 - ribbon_distance / glow_width)
    core = max(0.0, 1.0 - ribbon_distance / core_width)
    vertical_fade = min(1.0, max(0.0, (ny - 0.12) / 0.1))
    vertical_fade *= min(1.0, max(0.0, (0.88 - ny) / 0.1))
    glow *= vertical_fade
    core *= vertical_fade

    red = min(255, red + int(23 * glow + 63 * core))
    green = min(255, green + int(112 * glow + 188 * core))
    blue = min(255, blue + int(132 * glow + 199 * core))
    return blue, green, red, int(255 * alpha)


def dib(size: int) -> bytes:
    pixels = bytearray()
    for y in range(size - 1, -1, -1):
        for x in range(size):
            pixels.extend(pixel(x, y, size))
    mask_stride = ((size + 31) // 32) * 4
    mask = bytes(mask_stride * size)
    header = struct.pack(
        "<IIIHHIIIIII",
        40,
        size,
        size * 2,
        1,
        32,
        0,
        len(pixels),
        3780,
        3780,
        0,
        0,
    )
    return header + pixels + mask


def build_icon() -> bytes:
    images = [dib(size) for size in SIZES]
    directory_size = 6 + len(images) * 16
    offset = directory_size
    entries = []
    for size, image in zip(SIZES, images):
        entries.append(
            struct.pack(
                "<BBBBHHII",
                0 if size == 256 else size,
                0 if size == 256 else size,
                0,
                0,
                1,
                32,
                len(image),
                offset,
            )
        )
        offset += len(image)
    return (
        struct.pack("<HHH", 0, 1, len(images))
        + b"".join(entries)
        + b"".join(images)
    )


def main() -> None:
    output = (
        Path(__file__).resolve().parents[1]
        / "native"
        / "tsf"
        / "resources"
        / "sense.ico"
    )
    output.write_bytes(build_icon())
    print(output)


if __name__ == "__main__":
    main()
