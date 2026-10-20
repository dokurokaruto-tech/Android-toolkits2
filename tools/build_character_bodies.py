"""Key, align and export 592x750 character bodies for the shared home-stage rig.

Usage: python tools/build_character_bodies.py <raw_dir> [out_assets_dir]
<raw_dir> holds kita_new_raw.png / nijika_raw.png / ryo_raw.png (green-screen).
Requires Pillow + numpy. Landmarks are source-pixel coordinates measured on the raws.
Rig targets (592x750): eyes (208,152)/(268,154), guitar bridge (184,469), nut (506,296).
"""
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

W, H = 592, 750
FACE_BOX = (160, 90, 316, 216)
RIG_EYE_L, RIG_EYE_R = (208.0, 152.0), (268.0, 154.0)
RIG_BRIDGE, RIG_NUT = (184.0, 469.0), (506.0, 296.0)

# src landmarks: eye_l, eye_r, bridge(or None), nut(or None); tweak applied after fit.
CHARS = {
    'kita': dict(
        raw='kita_new_raw.png', out='kita/kita_body_base.webp',
        eye_l=(292, 216), eye_r=(392, 216), bridge=(264, 724), nut=(739, 479),
        scale_mul=1.0, dx=-3.0, dy=3.0, rot_deg=0.0, fade_start=695,
        weights=(3.0, 3.0, 1.5, 1.5),
    ),
    'nijika': dict(
        raw='nijika_raw.png', out='nijika/nijika_body_base.webp',
        eye_l=(374, 260), eye_r=(474, 254), bridge=None, nut=None,
        scale_mul=0.98, dx=6.0, dy=2.0, rot_deg=0.0, fade_start=720,
        weights=(3.0, 3.0, 0.0, 0.0),
    ),
    'ryo': dict(
        raw='ryo_raw.png', out='ryo/ryo_body_base.webp',
        eye_l=(273, 220), eye_r=(369, 220), bridge=(131, 743), nut=(690, 413),
        scale_mul=1.0, dx=-8.0, dy=-5.0, rot_deg=0.0, fade_start=695,
        weights=(2.5, 2.5, 2.0, 2.0),
    ),
}


def similarity(src, dst, wts):
    """Weighted Procrustes similarity (scale, rotation, translation) src -> dst."""
    src, dst, wts = map(lambda a: np.asarray(a, dtype=np.float64), (src, dst, wts))
    w = wts / wts.sum()
    cs, cd = (w[:, None] * src).sum(0), (w[:, None] * dst).sum(0)
    xs, xd = src - cs, dst - cd
    cov = (xs * w[:, None]).T @ xd
    u, s, vt = np.linalg.svd(cov)
    rot = vt.T @ u.T
    if np.linalg.det(rot) < 0:
        vt[-1] *= -1
        rot = vt.T @ u.T
    var = (w[:, None] * xs * xs).sum()
    scale = s.sum() / var
    trans = cd - scale * rot @ cs
    return scale, rot, trans


def warp(image, scale, rot, trans):
    """Warp RGB image onto a W x H green canvas via inverse affine."""
    a = scale * rot
    inv = np.linalg.inv(a)
    # PIL AFFINE maps output -> input: x_in = inv @ (x_out - trans)
    m = (inv[0, 0], inv[0, 1], -inv[0] @ trans, inv[1, 0], inv[1, 1], -inv[1] @ trans)
    return image.transform((W, H), Image.Transform.AFFINE, m,
                           resample=Image.Resampling.BICUBIC, fillcolor=(0, 255, 0))


def chroma_key(image):
    """Green-screen key + despill + 1px alpha erosion. Returns RGBA uint8 array."""
    arr = np.asarray(image.convert('RGB'), dtype=np.float32) / 255.0
    r, g, b = arr[..., 0], arr[..., 1], arr[..., 2]
    max_rb = np.maximum(r, b)
    dom = g - max_rb
    alpha = np.clip(1.0 - (dom - 0.06) / 0.36, 0.0, 1.0)
    alpha = alpha * alpha * (3 - 2 * alpha)
    # Erode alpha by 1px to cut the green fringe line.
    eroded = alpha.copy()
    for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        eroded = np.minimum(eroded, np.roll(alpha, (dy, dx), axis=(0, 1)))
    alpha = eroded
    # Despill: pull residual green toward the stronger of r/b, strongest at edges.
    g = np.minimum(g, max_rb + 0.10 * alpha)
    out = np.stack([r, g, b, alpha], axis=-1)
    return (np.clip(out, 0, 1) * 255 + 0.5).astype(np.uint8)


def fade_feet(arr, fade_start):
    """Linear alpha fade over the bottom rows, bottom row fully transparent."""
    for y in range(fade_start, H):
        f = 1.0 - (y - fade_start + 1) / (H - fade_start)
        arr[y, :, 3] = (arr[y, :, 3] * max(f, 0.0)).astype(np.uint8)
    arr[H - 1, :, 3] = 0
    return arr


def erase_tama_logo(image):
    """Diffusion-inpaint the bass-drum logo, then stamp a star emblem over it."""
    im = image.convert('RGB').copy()
    px = np.asarray(im).astype(np.float32)
    x0, y0, x1, y1 = 312, 962, 548, 1018  # measured on nijika_raw (912x1155)
    crop = px[y0 - 2:y1 + 2, x0 - 2:x1 + 2].copy()
    mask = np.zeros(crop.shape[:2], dtype=bool)
    mask[2:-2, 2:-2] = True
    crop[mask] = crop[~mask].mean(0)  # neutral init keeps dark logo ink out
    for _ in range(3000):
        avg = (np.roll(crop, 1, 0) + np.roll(crop, -1, 0) +
               np.roll(crop, 1, 1) + np.roll(crop, -1, 1)) / 4.0
        crop[mask] = avg[mask]
    px[y0 - 2:y1 + 2, x0 - 2:x1 + 2] = crop
    im = Image.fromarray(px.astype(np.uint8))
    # Gold star emblem where the logo sat; matches the small star on the head.
    draw = ImageDraw.Draw(im)
    cx, cy, r_out, r_in = 430, 990, 34, 14
    pts = []
    for i in range(10):
        ang = -np.pi / 2 + i * np.pi / 5 - 0.2
        r = r_out if i % 2 == 0 else r_in
        pts.append((cx + r * np.cos(ang), cy + r * np.sin(ang)))
    draw.polygon(pts, fill=(245, 197, 66), outline=(138, 90, 42), width=3)
    return im


def apply_tweak(scale, rot, trans, cfg):
    """Extra rotate/scale about the eye midpoint target, then translate."""
    th = np.deg2rad(cfg['rot_deg'])
    rot2 = np.array([[np.cos(th), -np.sin(th)], [np.sin(th), np.cos(th)]])
    pivot = np.array([(RIG_EYE_L[0] + RIG_EYE_R[0]) / 2, (RIG_EYE_L[1] + RIG_EYE_R[1]) / 2])
    a = scale * rot
    a2 = (cfg['scale_mul'] * rot2) @ a
    # Re-derive scale/rot of a2 (still a similarity).
    s2 = np.hypot(a2[0, 0], a2[1, 0])
    rot_new = a2 / s2
    t2 = cfg['scale_mul'] * rot2 @ trans + (pivot - cfg['scale_mul'] * rot2 @ pivot)
    return s2, rot_new, t2 + np.array([cfg['dx'], cfg['dy']])


def build(name, raw_dir, out_dir):
    cfg = CHARS[name]
    image = Image.open(raw_dir / cfg['raw']).convert('RGB')
    if name == 'nijika':
        image = erase_tama_logo(image)
    src = [cfg['eye_l'], cfg['eye_r']]
    dst = [RIG_EYE_L, RIG_EYE_R]
    wts = [cfg['weights'][0], cfg['weights'][1]]
    if cfg['bridge'] is not None:
        src += [cfg['bridge'], cfg['nut']]
        dst += [RIG_BRIDGE, RIG_NUT]
        wts += [cfg['weights'][2], cfg['weights'][3]]
    scale, rot, trans = similarity(src, dst, wts)
    scale, rot, trans = apply_tweak(scale, rot, trans, cfg)
    # Report landmark residuals so alignment quality is visible in numbers.
    for label, s_pt, d_pt in zip(('eye_l', 'eye_r', 'bridge', 'nut'), src, dst):
        got = scale * rot @ np.asarray(s_pt, dtype=np.float64) + trans
        print(f'{name} {label}: err=({got[0]-d_pt[0]:+.1f},{got[1]-d_pt[1]:+.1f}) -> uv=({got[0]/W:.3f},{got[1]/H:.3f})')
    arr = chroma_key(warp(image, scale, rot, trans))
    arr = fade_feet(arr, cfg['fade_start'])
    # Zero invisible RGB so face patches derived later stay bit-identical at edges.
    arr[arr[:, :, 3] == 0] = 0
    out_path = out_dir / cfg['out']
    out_path.parent.mkdir(parents=True, exist_ok=True)
    Image.fromarray(arr, 'RGBA').save(out_path, lossless=True, exact=True)
    print(f'{name}: wrote {out_path}')


def main():
    raw_dir = Path(sys.argv[1])
    out_dir = Path(sys.argv[2]) if len(sys.argv) > 2 else \
        Path(__file__).resolve().parents[1] / 'app/src/main/assets/live2d'
    names = sys.argv[3].split(',') if len(sys.argv) > 3 else list(CHARS)
    for name in names:
        build(name, raw_dir, out_dir)


if __name__ == '__main__':
    main()
