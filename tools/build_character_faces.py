"""Build 156x126 face patches for every home-stage character from its checked-in body.

Usage: python tools/build_character_faces.py [char,...]   (default: all)
Requires Pillow + numpy. All editing happens inside EDIT_BOX at 4x supersampling and is
pasted back onto a body copy, so patch edges stay bit-identical to the body (no seam).
Geometry below is measured in 592x750 body pixels on the checked-in bodies.
"""
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

ASSETS = Path(__file__).resolve().parents[1] / 'app/src/main/assets/live2d'
FACE_BOX = (160, 90, 316, 216)
EDIT_BOX = (178, 100, 314, 214)
S = 4  # supersample
LASH = (58, 36, 28)
MOUTH_IN = (124, 58, 48)
TONGUE = (229, 138, 125)
TEETH = (255, 252, 248)
BLUSH_RGB = (244, 150, 150)

GEO = {
    'kita': dict(
        eye_l=[(201, 156), (205, 149), (214, 146), (230, 152), (231, 164), (228, 172), (218, 176), (206, 175), (203, 166)],
        eye_r=[(260, 152), (270, 146), (288, 147), (297, 154), (296, 166), (292, 173), (282, 176), (272, 174), (264, 166)],
        mouth_c=(246, 195), mouth_box=(218, 182, 274, 210),
        brow_l=None, brow_r=None,
        blush=[(205, 183), (288, 183)], mole=None, skin_pt=(240, 182),
    ),
    'nijika': dict(
        eye_l=[(216, 158), (220, 150), (228, 147), (242, 154), (241, 167), (238, 174), (228, 177), (220, 176), (217, 167)],
        eye_r=[(270, 154), (280, 148), (296, 149), (303, 155), (301, 167), (298, 174), (288, 177), (280, 176), (273, 167)],
        mouth_c=(254, 193), mouth_box=(226, 178, 282, 208),
        brow_l=(202, 128, 244, 142), brow_r=(266, 128, 308, 142),
        blush=[(216, 182), (294, 182)], mole=None, skin_pt=(248, 182),
    ),
    'ryo': dict(
        eye_l=[(210, 150), (214, 142), (224, 140), (238, 146), (237, 156), (228, 164), (214, 163), (212, 155)],
        eye_r=[(266, 147), (276, 140), (294, 140), (301, 147), (300, 156), (295, 164), (280, 164), (270, 156)],
        mouth_c=(252, 185), mouth_box=(236, 174, 270, 196),
        brow_l=None, brow_r=None,
        blush=[(214, 172), (290, 172)], mole=(298, 166), skin_pt=(245, 176),
    ),
    'bocchi': dict(
        eye_l=[(186, 146), (196, 134), (216, 132), (233, 140), (232, 154), (222, 162), (203, 162), (191, 155)],
        eye_r=[(260, 140), (270, 133), (289, 134), (301, 142), (300, 155), (291, 162), (274, 162), (264, 154)],
        mouth_c=(243, 187), mouth_box=(226, 176, 262, 198),
        brow_l=None, brow_r=None,
        blush=[(208, 172), (282, 172)], mole=None, skin_pt=(250, 176),
    ),
}

# Ops: ('lid', side, mode) ('mouth', kind, w, h) ('brow', side, mode)
#      ('blush', strength) ('sweat', (x, y)) — mouth w/h in body pixels.
RECIPES = {
    'kita': {
        'half_blink': [('lid', 'l', 'half'), ('lid', 'r', 'half')],
        'blink': [('lid', 'l', 'closed'), ('lid', 'r', 'closed')],
        'smile': [('mouth', 'curve', 30, 10), ('blush', 0.5)],
        'awakened': [('lid', 'r', 'wink'), ('mouth', 'open', 34, 16), ('blush', 0.35)],
        'cheer': [('lid', 'l', 'happy'), ('lid', 'r', 'happy'), ('mouth', 'grin', 38, 18), ('blush', 0.8)],
        'singing': [('mouth', 'aah', 26, 24), ('blush', 0.3)],
        'surprised': [('mouth', 'o', 12, 14), ('sweat', (308, 148))],
        'idle': [('lid', 'l', 'wink'), ('mouth', 'cat', 28, 12), ('blush', 0.45)],
    },
    'nijika': {
        'half_blink': [('lid', 'l', 'half'), ('lid', 'r', 'half')],
        'blink': [('lid', 'l', 'closed'), ('lid', 'r', 'closed')],
        'smile': [('lid', 'l', 'happy'), ('lid', 'r', 'happy'), ('mouth', 'grin', 36, 17), ('blush', 0.6)],
        'awakened': [('mouth', 'grin', 32, 15), ('blush', 0.25)],
        'cheer': [('lid', 'l', 'squeeze'), ('lid', 'r', 'squeeze'), ('mouth', 'open', 38, 20), ('blush', 0.7)],
        'singing': [('mouth', 'aah', 24, 22), ('blush', 0.3)],
        'surprised': [('mouth', 'o', 12, 14), ('sweat', (312, 150))],
        'idle': [('lid', 'l', 'closed'), ('lid', 'r', 'closed'), ('mouth', 'curve', 26, 9), ('blush', 0.55)],
    },
    'ryo': {
        'half_blink': [('lid', 'l', 'half'), ('lid', 'r', 'half')],
        'blink': [('lid', 'l', 'closed'), ('lid', 'r', 'closed')],
        'smile': [('lid', 'l', 'closed'), ('lid', 'r', 'closed'), ('mouth', 'curve', 20, 8)],
        'awakened': [('lid', 'l', 'half'), ('lid', 'r', 'half'), ('mouth', 'smirk', 22, 9)],
        'cheer': [('lid', 'l', 'happy'), ('lid', 'r', 'happy'), ('mouth', 'smirk', 26, 11), ('blush', 0.3)],
        'singing': [('lid', 'l', 'closed'), ('lid', 'r', 'closed'), ('mouth', 'o', 10, 12)],
        'surprised': [('mouth', 'o', 9, 11), ('sweat', (302, 128))],
        'idle': [('lid', 'l', 'half'), ('lid', 'r', 'half'), ('mouth', 'yawn', 12, 14)],
    },
    # Bocchi keeps her original five patches; only the three new slots are built here.
    'bocchi': {
        'singing': [('mouth', 'open', 20, 12)],
        'surprised': [('mouth', 'o', 11, 13), ('sweat', (190, 126))],
        'idle': [('mouth', 'wavy', 24, 8), ('blush', 0.4)],
    },
}


def inpaint(arr, mask, iters=900):
    """Diffusion-fill masked pixels of a float RGBA array from their neighbourhood."""
    if not mask.any():
        return arr
    ys, xs = np.where(mask)
    y0, y1 = max(ys.min() - 2, 0), min(ys.max() + 3, arr.shape[0])
    x0, x1 = max(xs.min() - 2, 0), min(xs.max() + 3, arr.shape[1])
    crop = arr[y0:y1, x0:x1].copy()
    m = mask[y0:y1, x0:x1]
    crop[m] = crop[~m].mean(0)
    for _ in range(iters):
        avg = (np.roll(crop, 1, 0) + np.roll(crop, -1, 0) +
               np.roll(crop, 1, 1) + np.roll(crop, -1, 1)) / 4.0
        crop[m] = avg[m]
    arr[y0:y1, x0:x1] = crop
    return arr


class Painter:
    """Edits one character face at 4x inside EDIT_BOX (body coordinates in, body out)."""

    def __init__(self, body, geo):
        self.geo = geo
        region = body.crop(EDIT_BOX).resize(
            ((EDIT_BOX[2] - EDIT_BOX[0]) * S, (EDIT_BOX[3] - EDIT_BOX[1]) * S),
            Image.Resampling.LANCZOS)
        self.arr = np.asarray(region, dtype=np.float32)
        self.src_arr = self.arr.copy()

    # -- coordinate helpers -------------------------------------------------
    def pt(self, x, y):
        return ((x - EDIT_BOX[0]) * S, (y - EDIT_BOX[1]) * S)

    def mask_polygon(self, points):
        m = Image.new('L', (self.arr.shape[1], self.arr.shape[0]), 0)
        ImageDraw.Draw(m).polygon([self.pt(x, y) for x, y in points], fill=255)
        return np.asarray(m) > 0

    def image(self):
        return Image.fromarray(np.clip(self.arr + 0.5, 0, 255).astype(np.uint8), 'RGBA')

    def draw(self):
        return ImageDraw.Draw(self._img) if hasattr(self, '_img') else None

    # -- stroke helpers -----------------------------------------------------
    def curve(self, img, p0, p1, p2, width=2.2, color=LASH):
        pts = []
        for i in range(41):
            t = i / 40
            pts.append(self.pt((1 - t) ** 2 * p0[0] + 2 * (1 - t) * t * p1[0] + t * t * p2[0],
                               (1 - t) ** 2 * p0[1] + 2 * (1 - t) * t * p1[1] + t * t * p2[1]))
        ImageDraw.Draw(img).line(pts, fill=color + (255,), width=round(width * S), joint='curve')

    def polyline(self, img, points, width=2.2, color=LASH):
        ImageDraw.Draw(img).line([self.pt(x, y) for x, y in points],
                                 fill=color + (255,), width=round(width * S), joint='curve')

    # -- features -----------------------------------------------------------
    def skin_fill(self, mask, poly):
        """Fill an eye area with cheek skin from geo['skin_pt'] (flat cel look).

        Diffusion inpainting is unusable here: it drags the black lash line
        across the closed eye as a dark smear. The fill edge is feathered so
        the polygon boundary does not show as a capsule outline.
        """
        ys = [p[1] for p in poly]
        sx, sy = self.pt(*self.geo['skin_pt'])
        win = self.src_arr[int(sy) - 6:int(sy) + 7, int(sx) - 6:int(sx) + 7, :3]
        color = np.median(win.reshape(-1, 3), axis=0)
        rows = np.arange(self.arr.shape[0])[:, None]
        top = (min(ys) - EDIT_BOX[1]) * S
        height = max((max(ys) - min(ys)) * S, 1)
        # Slightly darker toward the bangs, like the original cel shading.
        factor = 0.96 + 0.04 * np.clip((rows - top) / height, 0, 1)
        fill = color[None, None, :] * factor[:, :, None]
        soft = Image.fromarray((mask * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(1.6))
        alpha = (np.asarray(soft, dtype=np.float32) / 255.0)[:, :, None]
        self.arr[..., :3] = self.arr[..., :3] * (1 - alpha) + fill * alpha

    def erase_box(self, box):
        """Erase a mouth box with a bilinear skin patch feather-blended at its rim.

        Diffusion from a 2px ring never converges mid-box (flat grey rectangle)
        and a 1-D column lerp prints jaw outlines as vertical streaks. Averaging
        a vertical and a horizontal edge lerp keeps cheek blush on both axes.
        """
        x0, y0, x1, y1 = box
        ax, ay = self.pt(x0, y0)
        bx, by = self.pt(x1, y1)
        ax, ay, bx, by = int(ax), int(ay), int(bx), int(by)
        hh, ww = self.arr.shape[:2]

        def smooth(band):
            # band: (N, 3) edge profile. Drop non-skin samples first (cyan /
            # magenta rim light, jaw outline, transparent background) or the
            # Coons patch smears them across the whole mouth area.
            bad = ((band[:, 0] < 165) | (band[:, 1] > band[:, 0]) |
                   (band[:, 2] > band[:, 0]) | (band[:, 1] < band[:, 2] - 20))
            if bad.any() and not bad.all():
                idx = np.arange(len(band))
                for c in range(3):
                    band[bad, c] = np.interp(idx[bad], idx[~bad], band[~bad, c])
            kernel = np.ones(41) / 41
            padded = np.pad(band, [(20, 20), (0, 0)], mode='edge')
            return np.stack([np.convolve(padded[:, c], kernel, 'valid') for c in range(3)], axis=1)

        top = smooth(np.median(self.src_arr[max(ay - 3 * S, 0):ay, ax:bx, :3], axis=0))
        bot = smooth(np.median(self.src_arr[by:min(by + 3 * S, hh), ax:bx, :3], axis=0))
        left = smooth(np.median(self.src_arr[ay:by, max(ax - 3 * S, 0):ax, :3], axis=1))
        right = smooth(np.median(self.src_arr[ay:by, bx:min(bx + 3 * S, ww), :3], axis=1))
        ty = (np.arange(by - ay) / max(by - ay, 1))[:, None, None]
        tx = (np.arange(bx - ax) / max(bx - ax, 1))[None, :, None]
        vert = top[None, :, :] * (1 - ty) + bot[None, :, :] * ty
        horiz = left[:, None, :] * (1 - tx) + right[:, None, :] * tx
        # Coons patch: subtracting the corner bilinear makes all four edges
        # match the sampled skin exactly, so no rectangle ghost remains.
        c00 = (top[0] + left[0]) / 2
        c10 = (top[-1] + right[0]) / 2
        c01 = (bot[0] + left[-1]) / 2
        c11 = (bot[-1] + right[-1]) / 2
        corners = ((1 - tx) * (1 - ty) * c00 + tx * (1 - ty) * c10 +
                   (1 - tx) * ty * c01 + tx * ty * c11)
        fill = vert + horiz - corners
        # Feather the rim so no rectangle edge can show.
        mask = Image.new('L', (ww, hh), 0)
        ImageDraw.Draw(mask).rectangle([ax, ay, bx, by], fill=255)
        alpha = (np.asarray(mask.filter(ImageFilter.GaussianBlur(7)), dtype=np.float32) / 255.0)
        region = self.arr[ay:by, ax:bx, :3]
        a = alpha[ay:by, ax:bx, None]
        self.arr[ay:by, ax:bx, :3] = region * (1 - a) + fill * a

    def lid(self, side, mode):
        poly = self.geo['eye_l' if side == 'l' else 'eye_r']
        gx = sum(q[0] for q in poly) / len(poly)
        gy = sum(q[1] for q in poly) / len(poly)
        shrunk = [(gx + (q[0] - gx) * 0.96, gy + (q[1] - gy) * 0.96) for q in poly]
        mask = self.mask_polygon(shrunk)
        xs = [p[0] for p in poly]
        ys = [p[1] for p in poly]
        cx, x0, x1 = (min(xs) + max(xs)) / 2, min(xs) + 2, max(xs) - 2
        y_top, y_bot = min(ys), max(ys)
        if mode == 'half':
            cutoff = y_top + (y_bot - y_top) * 0.52
            yy = (np.arange(self.arr.shape[0])[:, None] / S + EDIT_BOX[1]) < cutoff
            self.skin_fill(mask & yy, poly)
            img = self.image()
            self.curve(img, (x0, cutoff - 2), (cx, cutoff + 3), (x1, cutoff - 2), width=2.6)
            self.arr = np.asarray(img, dtype=np.float32)
            return
        self.skin_fill(mask, poly)
        img = self.image()
        mid_y = y_top + (y_bot - y_top) * 0.55
        if mode == 'closed':
            self.curve(img, (x0, mid_y - 2), (cx, mid_y + 5), (x1, mid_y - 2), width=2.6)
        elif mode in ('happy', 'wink'):
            self.curve(img, (x0, mid_y + 3), (cx, mid_y - 7), (x1, mid_y + 3), width=2.6)
        elif mode == 'squeeze':  # > < eyes
            if side == 'l':
                self.polyline(img, [(x0, y_top + 3), (x1, mid_y), (x0, y_bot - 3)], width=2.6)
            else:
                self.polyline(img, [(x1, y_top + 3), (x0, mid_y), (x1, y_bot - 3)], width=2.6)
        self.arr = np.asarray(img, dtype=np.float32)

    def brow(self, side, mode):
        box = self.geo['brow_l' if side == 'l' else 'brow_r']
        if box is None:
            return
        x0, y0, x1, y1 = box
        self.erase_box(box)
        img = self.image()
        cy = (y0 + y1) / 2
        inner_x = x1 if side == 'l' else x0
        outer_x = x0 if side == 'l' else x1
        if mode == 'raised':
            self.curve(img, (outer_x, cy - 3), ((x0 + x1) / 2, cy - 7), (inner_x, cy - 3), width=1.8)
        elif mode == 'focus':
            self.curve(img, (outer_x, cy - 3), ((x0 + x1) / 2, cy + 1), (inner_x, cy + 5), width=2.0)
        elif mode == 'worried':
            self.curve(img, (outer_x, cy + 3), ((x0 + x1) / 2, cy - 1), (inner_x, cy - 5), width=1.8)
        self.arr = np.asarray(img, dtype=np.float32)

    def mouth(self, kind, w, h):
        cx, cy = self.geo['mouth_c']
        self.erase_box(self.geo['mouth_box'])
        img = self.image()
        d = ImageDraw.Draw(img)
        if kind in ('open', 'grin'):
            self._mouth_open(img, cx, cy, w, h, teeth=(kind == 'grin'))
        elif kind == 'aah':
            # Tall open singing mouth: full ellipse with a tongue at the bottom.
            d.ellipse([self.pt(cx - w / 2, cy - h * 0.55), self.pt(cx + w / 2, cy + h * 0.55)],
                      fill=MOUTH_IN + (255,), outline=LASH + (255,), width=round(1.7 * S))
            d.ellipse([self.pt(cx - w * 0.26, cy + h * 0.08), self.pt(cx + w * 0.26, cy + h * 0.52)],
                      fill=TONGUE + (255,))
        elif kind in ('o', 'yawn'):
            box = [self.pt(cx - w / 2, cy - h / 2), self.pt(cx + w / 2, cy + h / 2)]
            d.ellipse([box[0], box[1]], fill=MOUTH_IN + (255,), outline=LASH + (255,), width=round(1.6 * S))
            if kind == 'yawn':
                d.ellipse([self.pt(cx - w * 0.2, cy + h * 0.05), self.pt(cx + w * 0.2, cy + h * 0.42)],
                          fill=TONGUE + (255,))
        elif kind == 'curve':
            self.curve(img, (cx - w / 2, cy - 1), (cx, cy + h), (cx + w / 2, cy - 1), width=2.2)
        elif kind == 'smirk':
            self.curve(img, (cx - w / 2, cy + 1), (cx + w * 0.1, cy + h * 0.7), (cx + w / 2, cy - h * 0.5), width=2.2)
        elif kind == 'cat':
            for sgn in (-1, 1):
                ccx = cx + sgn * w * 0.23
                pts = [(ccx + w * 0.23 * np.cos(a), cy + h * 0.55 * np.sin(a))
                       for a in np.linspace(0.15 * np.pi, 0.85 * np.pi, 20)]
                self.polyline(img, pts, width=2.0)
        elif kind == 'wavy':
            pts = [(cx - w / 2 + w * i / 24, cy + 1.6 * np.sin(i / 24 * 3 * np.pi)) for i in range(25)]
            self.polyline(img, pts, width=1.9)
        self.arr = np.asarray(img, dtype=np.float32)

    def _mouth_open(self, img, cx, cy, w, h, teeth):
        # Interior: near-flat top edge + semicircular bottom, drawn on a mask for clipping.
        mask = Image.new('L', img.size, 0)
        top = [(cx - w / 2 + w * i / 20, cy - h * 0.32 + h * 0.10 * np.sin(i / 20 * np.pi)) for i in range(21)]
        bot = [(cx + w / 2 * np.cos(a), cy - h * 0.32 + h * 0.82 * np.sin(a))
               for a in np.linspace(0, np.pi, 24)]
        poly = [self.pt(x, y) for x, y in top + bot]
        ImageDraw.Draw(mask).polygon(poly, fill=255)
        layer = Image.new('RGBA', img.size, MOUTH_IN + (0,))
        layer.paste(MOUTH_IN + (255,), (0, 0), mask)
        ld = ImageDraw.Draw(layer)
        # Tongue at the bottom of the mouth.
        ld.ellipse([self.pt(cx - w * 0.24, cy + h * 0.12), self.pt(cx + w * 0.24, cy + h * 0.62)],
                   fill=TONGUE + (255,))
        if teeth:
            ld.rectangle([self.pt(cx - w / 2, cy - h * 0.32), self.pt(cx + w / 2, cy + h * 0.10)],
                         fill=TEETH + (255,))
        # Clip tongue/teeth to the mouth interior.
        clipped = np.minimum(np.asarray(layer)[..., 3], np.asarray(mask))
        layer.putalpha(Image.fromarray(clipped.astype(np.uint8)))
        merged = Image.alpha_composite(img, layer)
        img.paste(merged, (0, 0))
        ImageDraw.Draw(img).line(poly + [poly[0]], fill=LASH + (255,), width=round(1.7 * S), joint='curve')

    def blush(self, strength):
        img = self.image()
        layer = Image.new('RGBA', img.size, (0, 0, 0, 0))
        d = ImageDraw.Draw(layer)
        alpha = int(95 * strength)
        for (cx, cy) in self.geo['blush']:
            d.ellipse([self.pt(cx - 15, cy - 6), self.pt(cx + 15, cy + 6)], fill=BLUSH_RGB + (alpha,))
        layer = layer.filter(ImageFilter.GaussianBlur(3.2 * S))
        img.paste(Image.alpha_composite(img, layer), (0, 0))
        self.arr = np.asarray(img, dtype=np.float32)

    def sweat(self, pos):
        img = self.image()
        d = ImageDraw.Draw(img)
        cx, cy = pos
        pts = [(cx, cy - 8), (cx + 4.5, cy + 1), (cx + 3.4, cy + 5.5),
               (cx, cy + 7.5), (cx - 3.4, cy + 5.5), (cx - 4.5, cy + 1)]
        d.polygon([self.pt(x, y) for x, y in pts], fill=(165, 216, 245, 235),
                  outline=(70, 120, 170, 255), width=round(1.2 * S))
        self.arr = np.asarray(img, dtype=np.float32)

    def stamp_mole(self):
        if self.geo['mole'] is None:
            return
        img = self.image()
        cx, cy = self.geo['mole']
        ImageDraw.Draw(img).ellipse(
            [self.pt(cx - 1.7, cy - 1.7), self.pt(cx + 1.7, cy + 1.7)], fill=(70, 45, 35, 255))
        self.arr = np.asarray(img, dtype=np.float32)

    def finish(self):
        small = self.image().resize((EDIT_BOX[2] - EDIT_BOX[0], EDIT_BOX[3] - EDIT_BOX[1]),
                                    Image.Resampling.LANCZOS)
        return small


def build(name):
    body = Image.open(ASSETS / name / f'{name}_body_base.webp').convert('RGBA')
    geo = GEO[name]
    for patch, ops in RECIPES[name].items():
        painter = Painter(body, geo)
        for op in ops:
            if op[0] == 'lid':
                painter.lid(op[1], op[2])
            elif op[0] == 'mouth':
                painter.mouth(op[1], op[2], op[3])
            elif op[0] == 'brow':
                painter.brow(op[1], op[2])
            elif op[0] == 'blush':
                painter.blush(op[1])
            elif op[0] == 'sweat':
                painter.sweat(op[1])
        painter.stamp_mole()
        face = body.copy()
        face.paste(painter.finish(), (EDIT_BOX[0], EDIT_BOX[1]))
        patch_img = face.crop(FACE_BOX)
        if name != 'bocchi':
            arr = np.array(patch_img)
            arr[arr[:, :, 3] == 0] = 0
            patch_img = Image.fromarray(arr, 'RGBA')
        out = ASSETS / name / f'{name}_face_{patch}.webp'
        patch_img.save(out, lossless=True, exact=True)
        print(f'{name}: {out.name}')


def main():
    names = sys.argv[1].split(',') if len(sys.argv) > 1 else list(RECIPES)
    for name in names:
        build(name)


if __name__ == '__main__':
    main()
