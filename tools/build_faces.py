"""Build Live2D stage sprites and seamless expression patches from AI drafts.

Each character owns a folder under tools/gen/<name>/ holding:
  body.png  full-body draft on a flat green screen
  <key>.png the same draft redrawn with a different facial expression

Drafts are keyed, fitted to the rig canvas (592x750) and turned into
  app/src/main/assets/live2d/<name>/<name>_body_base.webp
  app/src/main/assets/live2d/<name>/<name>_face_<key>.webp

An expression patch keeps the outer ring of the base body untouched and only
swaps the brow/eye/mouth band, so the rig never shows a rectangular seam.
The band is aligned with the base by masked normalised correlation, which also
absorbs any drift between drafts.

  python3 tools/build_faces.py kita half_blink blink smile
"""
import sys
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
GEN = ROOT / 'tools/gen'
ASSETS = ROOT / 'app/src/main/assets/live2d'

SIZE = (592, 750)
FACE_BOX = (160, 90, 316, 216)
# 足元をフェードさせてステージ床へ溶け込ませる高さ
FOOT_FADE_ROWS = 44
# Brow/eye/mouth band inside the face box. Everything outside it (fringe,
# cheeks, jaw outline) is copied from the base body.
BAND = (168, 106, 308, 213)
FEATHER = 8
MAX_SHIFT = 8
KEY_DIST_0 = 90.0
KEY_DIST_1 = 240.0
# 緑チャンネルに残す許容量。ネオンの緑みを消しつつ黄色は傷つけない
KEY_SPILL_HEADROOM = 24


def key_green(rgb: np.ndarray) -> np.ndarray:
    """Turn a flat green screen into alpha and strip the green spill."""
    r = rgb[..., 0].astype(int)
    g = rgb[..., 1].astype(int)
    b = rgb[..., 2].astype(int)
    dist = np.abs(g - 255) + r + b
    alpha = np.clip((dist - KEY_DIST_0) / (KEY_DIST_1 - KEY_DIST_0), 0.0, 1.0)

    # グリーンバックの残像を取り除く。緑が最大チャンネルを超える分を削り、
    # 髪や衣装の縁が緑に光らなくする。どのキャラも緑要素を持たないので全体に効く。
    out = rgb.astype(float)
    out[..., 1] = np.minimum(g, np.maximum(r, b) + KEY_SPILL_HEADROOM)
    return np.dstack((np.clip(out, 0, 255).astype(np.uint8), (alpha * 255).astype(np.uint8)))


def flatten_transparent(image: np.ndarray) -> np.ndarray:
    """透過ピクセルのRGBを0へ揃える。エンコーダ差で値が残るのを防ぐ"""
    image = image.copy()
    image[image[..., 3] == 0, :3] = 0
    return image


def load_draft(path: Path) -> np.ndarray:
    """Key a draft and fit it onto the rig canvas without cropping the subject."""
    src = Image.open(path).convert('RGB')
    scale = min(SIZE[0] / src.width, SIZE[1] / src.height)
    fitted = src.resize((round(src.width * scale), round(src.height * scale)), Image.Resampling.LANCZOS)
    canvas = Image.new('RGB', SIZE, (0, 255, 0))
    canvas.paste(fitted, ((SIZE[0] - fitted.width) // 2, (SIZE[1] - fitted.height) // 2))
    return flatten_transparent(key_green(np.array(canvas)))


def box_mask(box, size):
    x0, y0, x1, y1 = box
    xs = np.arange(size[0], dtype=float)[None, :]
    ys = np.arange(size[1], dtype=float)[:, None]
    return ((xs >= x0) & (xs < x1) & (ys >= y0) & (ys < y1)).astype(float)


def band_mask(size):
    """Feathered mask over the expression band, clipped to the face box."""
    x0, y0, x1, y1 = BAND
    xs = np.arange(size[0], dtype=float)[None, :]
    ys = np.arange(size[1], dtype=float)[:, None]
    edge = np.minimum(np.minimum(xs - (x0 + FEATHER), (x1 - FEATHER) - xs),
                      np.minimum(ys - (y0 + FEATHER), (y1 - FEATHER) - ys))
    inner = np.clip(edge / FEATHER, 0.0, 1.0)
    return inner * box_mask(FACE_BOX, size)


def load_recycled(body: np.ndarray, name: str, key: str) -> np.ndarray:
    """既存パッチを新しい継ぎ目なし形式へ作り直す。

    旧パッチは表情だけを重ねる差分画像なので、ボディに合成してから
    バンド合成にかける。外輪はボディそのままになり、見た目は変わらない。
    """
    with Image.open(ASSETS / name / f'{name}_face_{key}.webp') as patch:
        canvas = Image.fromarray(body).convert('RGBA')
        canvas.alpha_composite(patch.convert('RGBA'), (FACE_BOX[0], FACE_BOX[1]))
    return np.array(canvas)


def best_shift(base: np.ndarray, variant: np.ndarray, anchor: np.ndarray) -> tuple:
    """Offset that lines the variant's fringe/cheeks/jaw up with the base body."""
    keep = anchor[..., None]
    target = base[..., :3] * keep
    tnorm = np.linalg.norm(target)
    best, best_score = (0, 0), -1e18
    for dy in range(-MAX_SHIFT, MAX_SHIFT + 1):
        for dx in range(-MAX_SHIFT, MAX_SHIFT + 1):
            moved = np.roll(variant, (dy, dx), axis=(0, 1))
            window = moved[..., :3] * keep
            score = float((target * window).sum() / (tnorm * np.linalg.norm(window) + 1e-6))
            if score > best_score:
                best, best_score = (dx, dy), score
    return best


def fade_feet(image: np.ndarray) -> np.ndarray:
    """下端をアルファ0へフェードさせ、床への継ぎ目を消す"""
    rows = image.shape[0]
    ramp = np.ones(rows, dtype=float)
    start = rows - FOOT_FADE_ROWS
    ramp[start:] = np.linspace(1.0, 0.0, FOOT_FADE_ROWS)
    image[..., 3] = np.round(image[..., 3] * ramp[:, None]).astype(np.uint8)
    return image


def load_body(name: str) -> np.ndarray:
    """新規ドラフトが無い場合は、既存のボディ素材をそのまま引き継ぐ"""
    draft = GEN / name / 'body.png'
    if not draft.is_file():
        with Image.open(ASSETS / name / f'{name}_body_base.webp') as image:
            return np.array(image.convert('RGBA')).astype(np.uint8)
    return fade_feet(load_draft(draft))


def build(name: str, keys: list, recycled: list = ()) -> None:
    body = load_body(name)
    out = ASSETS / name
    out.mkdir(parents=True, exist_ok=True)
    # ボディは新規ドラフトがあるときだけ書き出す。再利用時はそのまま尊重する
    if (GEN / name / 'body.png').is_file():
        Image.fromarray(body).save(out / f'{name}_body_base.webp', lossless=True)

    band = band_mask(SIZE)
    anchor = box_mask(FACE_BOX, SIZE) - band
    stack = np.repeat(band[:, :, None], 4, axis=2)
    for key in keys:
        variant = (load_recycled(body, name, key) if key in recycled
                   else load_draft(GEN / name / f'{key}.png'))
        dx, dy = best_shift(body, variant, anchor)
        moved = np.roll(variant, (dy, dx), axis=(0, 1))
        blended = body * (1.0 - stack) + moved * stack
        # drawBitmapMesh は顔パッチを顔ボックス全面へ引き延ばすので、実寸で切る
        patch = Image.fromarray(np.round(blended).astype(np.uint8)).crop(FACE_BOX)
        patch.save(out / f'{name}_face_{key}.webp', lossless=True)
        print(f'{name}_face_{key}.webp  shift=({dx},{dy})')


if __name__ == '__main__':
    # 使い方: build_faces.py <名前> [--recycle キー ...] [-- 新規キー ...]
    name = sys.argv[1]
    rest = sys.argv[2:]
    recycled = rest[rest.index('--recycle') + 1:rest.index('--')] if '--recycle' in rest else []
    fresh = [a for a in rest if a not in ('--recycle', '--') and a not in recycled]
    build(name, fresh + recycled, recycled)
