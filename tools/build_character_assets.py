"""Build rig-ready character bodies and expression patches from chroma-key drafts.

Pipeline per character
----------------------
  drafts/<char>_base.png           flat #00FF00 backdrop, full body
        |  chroma alpha -> despill -> trim -> scale onto the shared 592x750 rig
        v
  assets/live2d/<char>/<char>_body_base.webp

  drafts/<char>_expr_<name>.png    same composition, only the face was edited
        |  ECC-register onto the base draft -> SAME crop/scale/offset
        v
  assets/live2d/<char>/<char>_face_<name>.webp   head-cropped patch

Every patch pixel that did not actually change is copied from the body, so the
overlay's border is bit-identical to the body and the drawn quad cannot seam.

Run: python tools/build_character_assets.py [--only kita]
Requires Pillow, numpy, opencv-python-headless.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
from dataclasses import dataclass, field
from pathlib import Path

import cv2
import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent))
from character_art import RIG_SIZE, Frame, matte, resize_rgba, trim

ROOT = Path(__file__).resolve().parents[1]
DRAFTS = Path(os.environ.get("CHARACTER_DRAFTS", str(ROOT.parent / "art" / "drafts")))
ASSETS = ROOT / "app" / "src" / "main" / "assets" / "live2d"
RIG_SPECS = Path(__file__).resolve().parent / "character_rigs.json"

HEAD_TARGET_X = 246.0
EDGE_MARGIN = 4
BORDER = 2
HEAD_BAND = (0.02, 0.34)      # vertical search band for face edits, in rig UV
HEAD_HALF_WIDTH = 132         # rig px around the head centre
CHANGE_THRESHOLD = 10.0       # colour units that count as an expression edit
FEATHER_SIGMA = 1.1


@dataclass
class CharacterDraft:
    head_x: float
    slug: str
    base: str
    expressions: dict[str, str] = field(default_factory=dict)


CHARACTERS = [
    CharacterDraft(HEAD_TARGET_X, "kita", "kita_base.png", {
        "half_blink": "kita_expr_halfblink.png",
        "blink": "kita_expr_blink.png",
        "smile": "kita_expr_smile.png",
        "laugh": "kita_expr_laugh.png",
        "surprised": "kita_expr_surprised.png",
        "wink": "kita_expr_wink.png",
        "awakened": "kita_expr_awakened.png",
    }),
    CharacterDraft(HEAD_TARGET_X, "nijika", "nijika_base.png", {
        "half_blink": "nijika_expr_halfblink.png",
        "blink": "nijika_expr_blink.png",
        "smile": "nijika_expr_smile.png",
        "laugh": "nijika_expr_laugh.png",
        "surprised": "nijika_expr_surprised.png",
        "wink": "nijika_expr_wink.png",
        "awakened": "nijika_expr_awakened.png",
    }),
    CharacterDraft(HEAD_TARGET_X, "ryo", "ryo_base.png", {
        "half_blink": "ryo_expr_halfblink.png",
        "blink": "ryo_expr_blink.png",
        "smile": "ryo_expr_smile.png",
        "laugh": "ryo_expr_laugh.png",
        "surprised": "ryo_expr_surprised.png",
        "wink": "ryo_expr_wink.png",
        "awakened": "ryo_expr_awakened.png",
    }),
]


@dataclass(frozen=True)
class Placement:
    box: tuple[int, int, int, int]
    scale: float
    width: int
    height: int
    x: int
    y: int


def read_rgb(path: Path) -> np.ndarray:
    return np.asarray(Image.open(path).convert("RGB"), dtype=np.float32)


def head_centre(alpha: np.ndarray, box: tuple[int, int, int, int]) -> float:
    """X of the skull, from the widest dense run in the top hair band."""
    left, top, right, bottom = box
    height = bottom - top
    band = alpha[top + int(height * 0.07):top + int(height * 0.19), left:right]
    coverage = band.mean(axis=0)
    dense = coverage > max(0.05, coverage.max() * 0.35)
    best, start = (0, 0), None
    for index, filled in enumerate(dense):
        if filled and start is None:
            start = index
        elif not filled and start is not None:
            if index - start > best[1] - best[0]:
                best = (start, index)
            start = None
    if start is not None and len(dense) - start > best[1] - best[0]:
        best = (start, len(dense))
    return left + (best[0] + best[1]) * 0.5


def placement_for(rgb: np.ndarray, frame: Frame, target_x: float) -> Placement:
    _, alpha = matte(rgb)
    box = trim(alpha)
    scale = frame.scale_for(box)
    left, top, right, bottom = box
    width = max(1, round((right - left) * scale))
    height = max(1, round((bottom - top) * scale))
    x = int(round(target_x - (head_centre(alpha, box) - left) * scale))
    x = max(EDGE_MARGIN, min(x, RIG_SIZE[0] - width - EDGE_MARGIN))
    y = max(EDGE_MARGIN, int(round(frame.head_top)))
    return Placement(box, scale, width, height, x, y)


def render(rgb: np.ndarray, place: Placement) -> np.ndarray:
    rgb, alpha = matte(rgb)
    left, top, right, bottom = place.box
    crop_rgb, crop_alpha = resize_rgba(
        rgb[top:bottom, left:right], alpha[top:bottom, left:right], (place.width, place.height)
    )
    canvas = np.zeros((RIG_SIZE[1], RIG_SIZE[0], 4), dtype=np.float32)
    x0, y0 = max(0, place.x), max(0, place.y)
    x1, y1 = min(RIG_SIZE[0], place.x + place.width), min(RIG_SIZE[1], place.y + place.height)
    sx, sy = x0 - place.x, y0 - place.y
    canvas[y0:y1, x0:x1, :3] = crop_rgb[sy:sy + (y1 - y0), sx:sx + (x1 - x0)]
    canvas[y0:y1, x0:x1, 3] = crop_alpha[sy:sy + (y1 - y0), sx:sx + (x1 - x0)] * 255.0
    return canvas


def register(base_path: Path, variant_path: Path) -> tuple[np.ndarray, float]:
    base = cv2.imread(str(base_path), cv2.IMREAD_GRAYSCALE)
    variant = cv2.imread(str(variant_path), cv2.IMREAD_GRAYSCALE)
    if variant.shape != base.shape:
        variant = cv2.resize(variant, (base.shape[1], base.shape[0]), interpolation=cv2.INTER_LANCZOS4)
    warp = np.eye(2, 3, dtype=np.float32)
    criteria = (cv2.TERM_CRITERIA_EPS | cv2.TERM_CRITERIA_COUNT, 300, 1e-8)
    score, warp = cv2.findTransformECC(
        base.astype(np.float32) / 255.0,
        variant.astype(np.float32) / 255.0,
        warp,
        cv2.MOTION_EUCLIDEAN,
        criteria,
        None,
        5,
    )
    return warp, float(score)


def load_draft(path: Path, size: tuple[int, int]) -> np.ndarray:
    image = Image.open(path).convert("RGB")
    if image.size != size:
        image = image.resize(size, Image.Resampling.LANCZOS)
    return np.asarray(image, dtype=np.float32)


def warp_draft(rgb: np.ndarray, warp: np.ndarray) -> np.ndarray:
    out = cv2.warpAffine(
        rgb,
        warp,
        (rgb.shape[1], rgb.shape[0]),
        flags=cv2.INTER_LANCZOS4 | cv2.WARP_INVERSE_MAP,
        borderMode=cv2.BORDER_REPLICATE,
    )
    return np.clip(out, 0, 255).astype(np.float32)


def difference(base: np.ndarray, variant: np.ndarray) -> np.ndarray:
    colour = np.abs(variant[:, :, :3] - base[:, :, :3]).max(axis=2)
    alpha = np.abs(variant[:, :, 3] - base[:, :, 3])
    return np.maximum(colour, alpha)


def head_window(place: Placement) -> tuple[int, int, int, int]:
    centre = place.x + (place.width * 0.5)
    return (
        int(max(0, place.x + place.width * 0.5 - HEAD_HALF_WIDTH)),
        int(RIG_SIZE[1] * HEAD_BAND[0]),
        int(min(RIG_SIZE[0], place.x + place.width * 0.5 + HEAD_HALF_WIDTH)),
        int(RIG_SIZE[1] * HEAD_BAND[1]),
    )


def face_box(base: np.ndarray, variants: dict[str, np.ndarray], window: tuple[int, int, int, int],
             margin: int) -> tuple[int, int, int, int]:
    x0, y0, x1, y1 = window
    union = np.zeros(base.shape[:2], dtype=bool)
    for variant in variants.values():
        union |= difference(base, variant) > CHANGE_THRESHOLD
    union[:y0, :] = False
    union[y1:, :] = False
    union[:, :x0] = False
    union[:, x1:] = False
    rows = np.where(union.any(axis=1))[0]
    cols = np.where(union.any(axis=0))[0]
    if rows.size == 0:
        raise SystemExit("no expression pixels changed inside the head window")
    return (
        max(0, int(cols[0]) - margin),
        max(0, int(rows[0]) - margin),
        min(RIG_SIZE[0], int(cols[-1]) + 1 + margin),
        min(RIG_SIZE[1], int(rows[-1]) + 1 + margin),
    )


def save(array: np.ndarray, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    Image.fromarray(np.clip(array, 0, 255).astype(np.uint8), "RGBA").save(path, lossless=True, quality=100)


def build(draft: CharacterDraft, margin: int) -> dict:
    base_path = DRAFTS / draft.base
    if not base_path.is_file():
        raise SystemExit(f"missing draft: {base_path}")
    base_rgb = read_rgb(base_path)
    place = placement_for(base_rgb, Frame(), draft.head_x)
    base = render(base_rgb, place)
    size = (base_rgb.shape[1], base_rgb.shape[0])

    variants: dict[str, np.ndarray] = {}
    scores: dict[str, float] = {}
    for name, filename in draft.expressions.items():
        path = DRAFTS / filename
        if not path.is_file():
            print(f"  pending {name}")
            continue
        warp, score = register(base_path, path)
        if abs(warp[0, 0] - 1.0) > 0.01:
            print(f"  WARN {name}: scale {warp[0, 0]:.4f}")
        variants[name] = render(warp_draft(load_draft(path, size), warp), place)
        scores[name] = score

    outdir = ASSETS / draft.slug
    save(base, outdir / f"{draft.slug}_body_base.webp")
    if not variants:
        print(f"{draft.slug}: body only (no expressions)")
        return {"slug": draft.slug, "faceBox": None, "ecc": {}}

    x0, y0, x1, y1 = face_box(base, variants, head_window(place), margin)
    solid = (base[:, :, 3] >= 250).astype(np.float32)
    for name, variant in variants.items():
        delta = cv2.GaussianBlur(difference(base, variant), (0, 0), FEATHER_SIGMA)
        weight = np.clip((delta - 3.0) / 6.0, 0.0, 1.0) * solid
        weight = weight[:, :, None]
        patch = base[y0:y1, x0:x1].copy()
        mixed = base[y0:y1, x0:x1] * (1.0 - weight[y0:y1, x0:x1]) + \
            variant[y0:y1, x0:x1] * weight[y0:y1, x0:x1]
        # Pupils, lash lines and blush are all drawn inside the face, so the
        # patch keeps the body's own alpha: the silhouette can never move.
        mixed[:, :, 3] = base[y0:y1, x0:x1, 3]
        patch[BORDER:-BORDER, BORDER:-BORDER] = mixed[BORDER:-BORDER, BORDER:-BORDER]
        save(patch, outdir / f"{draft.slug}_face_{name}.webp")

    print(f"{draft.slug}: body {place.width}x{place.height}@{place.x},{place.y} "
          f"face {x1 - x0}x{y1 - y0}@({x0},{y0}) ecc " +
          ", ".join(f"{k}={v:.3f}" for k, v in scores.items()))
    return {"slug": draft.slug, "faceBox": [x0, y0, x1, y1], "ecc": scores}


def write_specs(reports: list[dict]) -> None:
    spec = json.loads(RIG_SPECS.read_text()) if RIG_SPECS.is_file() else {}
    for report in reports:
        entry = spec.setdefault(report["slug"], {})
        if report.get("faceBox"):
            entry["faceBox"] = report["faceBox"]
        entry["expressions"] = sorted(report.get("ecc", {}).keys())
    RIG_SPECS.write_text(json.dumps(spec, indent=2, ensure_ascii=False) + "\n")


def contact_sheet(slugs: list[str], out: Path) -> None:
    spec = json.loads(RIG_SPECS.read_text())
    cells: list[Image.Image] = []
    for slug in slugs:
        body = Image.open(ASSETS / slug / f"{slug}_body_base.webp").convert("RGBA")
        box = spec[slug]["faceBox"]
        for path in sorted((ASSETS / slug).glob(f"{slug}_face_*.webp")):
            composed = body.copy()
            composed.alpha_composite(Image.open(path).convert("RGBA"), (box[0], box[1]))
            crop = composed.crop((box[0] - 30, box[1] - 30, box[0] + 290, box[1] + 210))
            cells.append(crop.resize((320, 240), Image.Resampling.LANCZOS))
    cols = 4
    rows = (len(cells) + cols - 1) // cols
    sheet = Image.new("RGB", (320 * cols, 240 * rows), (12, 8, 26))
    for index, image in enumerate(cells):
        sheet.paste(image.convert("RGB"), ((index % cols) * 320, (index // cols) * 240))
    out.parent.mkdir(parents=True, exist_ok=True)
    sheet.save(out)
    print(f"contact sheet -> {out}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--only", default="")
    parser.add_argument("--margin", type=int, default=14)
    args = parser.parse_args()

    reports = []
    for draft in CHARACTERS:
        if args.only and args.only != draft.slug:
            continue
        reports.append(build(draft, args.margin))
    if not reports:
        return
    write_specs(reports)
    slugs = [r["slug"] for r in reports if r.get("faceBox")]
    if slugs:
        contact_sheet(slugs, ROOT / "build" / "sheet_faces.png")


if __name__ == "__main__":
    main()
