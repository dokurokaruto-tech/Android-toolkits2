"""Chroma-key matting for the character drafts.

The image generator cannot emit true transparency, so every source render sits
on a flat #00FF00 backdrop.  A naive colour-distance key leaves green halos:
silhouette pixels are a blend of the key and the character, and that blend is
far from the key colour, so it survives as opaque green fringe.

Approach: find the confident interior ("core"), then only inside a few-pixel
band around it solve for coverage and un-mix the key colour:

    observed = a * fg + (1 - a) * key      ->     fg = (observed - (1-a)*key) / a

Interior pixels keep their exact colours, so naturally green artwork (eyes,
stars) is preserved, while the silhouette gets a clean, decontaminated edge.
"""
from __future__ import annotations

from dataclasses import dataclass

import cv2
import numpy as np

GREEN_KEY = np.array([4.0, 238.0, 4.0])
RIG_SIZE = (592, 750)

BAND_PX = 3
CORE_DISTANCE = 120.0
EDGE_DISTANCE = 26.0


@dataclass(frozen=True)
class Frame:
    """Where the character body sits on the rig canvas, in rig pixels."""

    head_top: float = 8.0
    feet_bottom: float = 742.0

    def scale_for(self, bbox: tuple[int, int, int, int]) -> float:
        left, top, right, bottom = bbox
        return (self.feet_bottom - self.head_top) / max(1, bottom - top)


def matte(rgb: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """Return decontaminated RGB and a soft alpha in 0..1."""
    distance = np.linalg.norm(rgb - GREEN_KEY, axis=2)
    core = (distance > CORE_DISTANCE).astype(np.uint8)
    core = cv2.morphologyEx(core, cv2.MORPH_OPEN, np.ones((2, 2), np.uint8))
    kernel = np.ones((BAND_PX * 2 + 1, BAND_PX * 2 + 1), np.uint8)
    band = (cv2.dilate(core, kernel) > 0) & (core == 0)

    alpha = core.astype(np.float32)
    ramp = np.clip((distance - EDGE_DISTANCE) / (CORE_DISTANCE - EDGE_DISTANCE), 0.0, 1.0)
    alpha[band] = ramp[band]
    alpha = np.where(band | (core > 0), alpha, 0.0).astype(np.float32)

    safe = np.maximum(alpha, 0.03)[:, :, None]
    clean = (rgb - (1.0 - alpha[:, :, None]) * GREEN_KEY) / safe
    clean = np.where((alpha > 0.0)[:, :, None], clean, rgb)
    return np.clip(clean, 0, 255).astype(np.float32), alpha


def trim(alpha: np.ndarray, threshold: float = 0.06) -> tuple[int, int, int, int]:
    rows = np.where(alpha.max(axis=1) > threshold)[0]
    cols = np.where(alpha.max(axis=0) > threshold)[0]
    if rows.size == 0 or cols.size == 0:
        return 0, 0, alpha.shape[1], alpha.shape[0]
    return int(cols[0]), int(rows[0]), int(cols[-1] + 1), int(rows[-1] + 1)


def resize_rgba(rgb: np.ndarray, alpha: np.ndarray, size: tuple[int, int]) -> tuple[np.ndarray, np.ndarray]:
    """Resize in premultiplied space so transparent pixels cannot bleed colour."""
    pre = np.dstack([rgb * alpha[:, :, None], alpha * 255.0])
    image = np.clip(pre, 0, 255).astype(np.uint8)
    image = cv2.resize(image, size, interpolation=cv2.INTER_AREA)
    arr = image.astype(np.float32)
    out_alpha = arr[:, :, 3] / 255.0
    out_rgb = np.zeros_like(arr[:, :, :3])
    safe = out_alpha > 0.004
    out_rgb[safe] = arr[:, :, :3][safe] / out_alpha[safe][:, None]
    return np.clip(out_rgb, 0, 255), out_alpha
