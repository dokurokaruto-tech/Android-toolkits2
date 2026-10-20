"""Rebuild expression patches for Kita/Nijika/Ryo from their checked-in bodies.

Requires Pillow. Coordinates are measured on the 592x750 sprites.
Every edit stays inside the face box so patch edges stay bit-identical
to the body (no rectangular seam when the mesh deforms).
"""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parents[1] / 'app/src/main/assets/live2d'
S = 4  # supersample; downscaling gives 1px anti-aliasing

# eye: bbox (incl. white), iris centre/radii. brow: (x0, y, x1). mouth: bbox, centre.
CHARACTERS = {
    'kita': {
        'box': (162, 92, 312, 222),
        'eyes': [((172, 133, 233, 164), (203, 149), (17, 14)),
                 ((247, 133, 302, 163), (273, 149), (16, 14))],
        'brows': [(178, 106, 228), (255, 106, 300)],
        'mouth': ((214, 180, 265, 200), (239, 189)),
        'skin_rect': (232, 203, 247, 212),
        'lash': '#4a2c20', 'iris': (169, 196, 54),
        'sweat': (306, 118),
    },
    'nijika': {
        'box': (186, 122, 332, 250),
        'eyes': [((195, 161, 251, 192), (226, 178), (16, 15)),
                 ((270, 160, 323, 191), (295, 177), (16, 15))],
        'brows': [(200, 140, 250), (278, 138, 325)],
        'mouth': ((243, 209, 293, 226), (267, 217)),
        'skin_rect': (260, 230, 275, 239),
        'lash': '#54301e', 'iris': (168, 84, 44),
        'sweat': (328, 150),
    },
    'ryo': {
        'box': (162, 92, 312, 215),
        'eyes': [((172, 136, 233, 157), (204, 147), (15, 9)),
                 ((246, 135, 300, 156), (272, 147), (15, 9))],
        'brows': [(185, 110, 228), (252, 110, 296)],
        'mouth': ((222, 179, 254, 188), (238, 184)),
        'skin_rect': (232, 192, 247, 200),
        'lash': '#23222a', 'iris': (196, 178, 60),
        'sweat': (304, 120),
    },
}

# eyes: per-eye mode list. mouth/brow: mode. extra: blush level, sweat flag.
RECIPES = {
    'kita': {
        'half_blink': dict(eyes=['half', 'half'], mouth='keep', brow='keep'),
        'blink': dict(eyes=['closed', 'closed'], mouth='keep', brow='keep'),
        'smile': dict(eyes=['open', 'open'], mouth='smile', brow='keep', blush=1),
        'awakened': dict(eyes=['star', 'star'], mouth='open_big', brow='raised', blush=1),
        'cheer': dict(eyes=['cheer', 'cheer'], mouth='grin', brow='raised', blush=1),
        'wink': dict(eyes=['open', 'wink'], mouth='cat', brow='keep', blush=1),
        'surprised': dict(eyes=['small', 'small'], mouth='o', brow='raised', sweat=True),
        'dreamy': dict(eyes=['heart', 'heart'], mouth='open_small', brow='keep', blush=2),
    },
    'nijika': {
        'half_blink': dict(eyes=['half', 'half'], mouth='keep', brow='keep'),
        'blink': dict(eyes=['closed', 'closed'], mouth='keep', brow='keep'),
        'smile': dict(eyes=['open', 'open'], mouth='smile', brow='keep', blush=1),
        'awakened': dict(eyes=['star', 'star'], mouth='open_big', brow='angled', blush=1),
        'cheer': dict(eyes=['cheer', 'cheer'], mouth='grin', brow='raised', blush=1),
        'wink': dict(eyes=['open', 'wink'], mouth='open_small', brow='keep', blush=1),
        'surprised': dict(eyes=['small', 'small'], mouth='o', brow='worried', sweat=True),
        'dreamy': dict(eyes=['half', 'half'], mouth='cat', brow='keep', blush=2),
    },
    'ryo': {
        'half_blink': dict(eyes=['half', 'half'], mouth='keep', brow='keep'),
        'blink': dict(eyes=['closed', 'closed'], mouth='keep', brow='keep'),
        'smile': dict(eyes=['open', 'open'], mouth='smug', brow='keep'),
        'awakened': dict(eyes=['bliss', 'bliss'], mouth='open_small', brow='keep', blush=1),
        'cheer': dict(eyes=['cheer', 'cheer'], mouth='cat', brow='raised', blush=1),
        'wink': dict(eyes=['open', 'wink'], mouth='smug', brow='keep'),
        'surprised': dict(eyes=['small', 'small'], mouth='o', brow='raised', sweat=True),
        'dreamy': dict(eyes=['star', 'star'], mouth='open_small', brow='keep', blush=1),
    },
}

WHITE_EYE = '#fff7f2'
MOUTH_DARK = '#9c4a4c'
MOUTH_LINE = '#8a4048'
TONGUE = '#ef9097'


def sc(points):
    return [(round(x * S), round(y * S)) for x, y in points]


def curve(draw, p0, p1, p2, fill, width):
    pts = []
    for i in range(41):
        t = i / 40
        pts.append(tuple(round(((1 - t) ** 2 * p0[j] + 2 * (1 - t) * t * p1[j] + t * t * p2[j]) * S)
                         for j in range(2)))
    draw.line(pts, fill=fill, width=round(width * S), joint='curve')


def eye_poly(bbox):
    x0, y0, x1, y1 = bbox
    w, cx, cy = x1 - x0, (x0 + x1) / 2, (y0 + y1) / 2
    return [(x0 + 1, cy - 1), (x0 + w * 0.16, y0 + 1), (cx, y0 - 1), (x1 - w * 0.16, y0 + 1),
            (x1 - 1, cy - 2), (x1 - w * 0.2, y1 - 1), (cx, y1 + 1), (x0 + w * 0.2, y1 - 1)]


def fill_skin(image, char, polygon, skin, cutoff=None):
    mask = Image.new('L', image.size)
    md = ImageDraw.Draw(mask)
    md.polygon(sc(polygon), fill=255)
    if cutoff is not None:  # keep the part below cutoff (visible lower iris)
        md.rectangle((0, cutoff * S, image.width, image.height), fill=0)
    image.paste(skin, (0, 0), mask)


def star(draw, cx, cy, r, fill):
    pts = []
    for i in range(8):
        rad = r if i % 2 == 0 else r * 0.3
        ang = 3.14159265 * 0.25 * i - 3.14159265 / 2
        import math
        pts.append((cx + math.cos(ang) * rad, cy + math.sin(ang) * rad))
    draw.polygon(sc(pts), fill=fill)


def heart(draw, cx, cy, r, fill):
    draw.ellipse([(cx - r) * S, (cy - r * 0.72) * S, cx * S, (cy + r * 0.28) * S], fill=fill)
    draw.ellipse([cx * S, (cy - r * 0.72) * S, (cx + r) * S, (cy + r * 0.28) * S], fill=fill)
    draw.polygon(sc([(cx - r * 0.86, cy - r * 0.1), (cx + r * 0.86, cy - r * 0.1), (cx, cy + r)]), fill=fill)


def draw_eye(image, char, index, mode, skin):
    bbox, (cx, cy), (rx, ry) = char['eyes'][index]
    x0, y0, x1, y1 = bbox
    draw = ImageDraw.Draw(image)
    lash = char['lash']
    if mode == 'open':
        return
    if mode == 'half':
        cutoff = y0 + (y1 - y0) * 0.55
        fill_skin(image, char, eye_poly(bbox), skin, cutoff=round(cutoff))
        curve(draw, (x0 + 2, cy - 4), (cx, cutoff + 2), (x1 - 2, cy - 4), lash, 2.2)
        return
    if mode == 'small':
        fill_skin(image, char, eye_poly(bbox), WHITE_EYE)
        draw.ellipse([(cx - rx * 0.55) * S, (cy - ry * 0.62) * S,
                      (cx + rx * 0.55) * S, (cy + ry * 0.62) * S], fill=char['iris'])
        draw.ellipse([(cx - 2.8) * S, (cy - 3.2) * S, (cx + 2.8) * S, (cy + 3.2) * S], fill='#2a1c14')
        draw.ellipse([(cx - 1.4) * S, (cy - 2.2) * S, (cx + 0.6) * S, (cy - 0.2) * S], fill='white')
        curve(draw, (x0 + 1, cy - 2), (cx, y0 - 3), (x1 - 1, cy - 3), lash, 2.6)
        return
    if mode in ('star', 'heart'):
        if mode == 'star':
            star(draw, cx - 2, cy - 2, rx * 0.62, 'white')
        else:
            heart(draw, cx, cy, rx * 0.58, '#ff6b9d')
        draw.ellipse([(cx + rx * 0.25) * S, (cy + ry * 0.25) * S,
                      (cx + rx * 0.25 + 3) * S, (cy + ry * 0.25 + 3) * S], fill='white')
        return
    # fully closed variants differ only in the arc shape
    fill_skin(image, char, eye_poly(bbox), skin)
    if mode == 'closed':
        curve(draw, (x0 + 3, cy - 3), (cx, cy + 7), (x1 - 3, cy - 3), lash, 2.6)
    elif mode == 'cheer':
        curve(draw, (x0 + 3, cy + 5), (cx, cy - 12), (x1 - 3, cy + 5), lash, 3.0)
    elif mode == 'wink':
        curve(draw, (x0 + 3, cy + 2), (cx, cy + 6), (x1 - 3, cy - 3), lash, 2.8)
    elif mode == 'bliss':
        curve(draw, (x0 + 3, cy - 1), (cx, cy + 6), (x1 - 3, cy - 1), lash, 2.6)


def draw_brow(image, char, index, mode, skin):
    if mode == 'keep':
        return
    x0, y, x1 = char['brows'][index]
    draw = ImageDraw.Draw(image)
    # Erase the original brow column by column, cloning the pixel 8px above:
    # brows are drawn through the bangs, and the same vertical hair strand
    # continues above the brow, so no flat band appears.
    px = image.load()
    for x in range(x0 - 2, x1 + 3):
        color = px[x * S + S // 2, (y - 8) * S]
        draw.rectangle([x * S, (y - 2) * S, (x + 1) * S, (y + 3) * S], fill=color)
    inner = x1 if index == 0 else x0
    outer = x0 if index == 0 else x1
    if mode == 'raised':
        curve(draw, (outer, y - 4), ((x0 + x1) / 2, y - 9), (inner, y - 5), char['lash'], 1.8)
    elif mode == 'angled':  # inner end down: determined
        draw.line(sc([(outer, y - 4), (inner, y + 3)]), fill=char['lash'], width=round(2.0 * S))
    elif mode == 'worried':  # inner end up: troubled
        draw.line(sc([(outer, y + 2), (inner, y - 5)]), fill=char['lash'], width=round(2.0 * S))


def mouth_cover(image, bbox, skin):
    x0, y0, x1, y1 = bbox
    mask = Image.new('L', image.size)
    ImageDraw.Draw(mask).ellipse(
        [(x0 - 4) * S, (y0 - 4) * S, (x1 + 4) * S, (y1 + 4) * S], fill=255)
    mask = mask.filter(ImageFilter.GaussianBlur(S * 0.9))
    image.paste(skin, (0, 0), mask)


def open_mouth(draw, cx, cy, w, h, teeth=False):
    draw.pieslice([(cx - w) * S, (cy - h) * S, (cx + w) * S, (cy + h) * S],
                  0, 180, fill=MOUTH_DARK, outline=MOUTH_LINE, width=S)
    draw.line(sc([(cx - w, cy), (cx + w, cy)]), fill=MOUTH_LINE, width=S)
    if teeth:
        draw.rectangle([(cx - w * 0.82) * S, cy * S, (cx + w * 0.82) * S, (cy + h * 0.34) * S], fill='white')
    else:
        draw.pieslice([(cx - w * 0.62) * S, (cy + h * 0.08) * S,
                       (cx + w * 0.62) * S, (cy + h * 1.05) * S], 0, 180, fill=TONGUE)


def draw_mouth(image, char, mode, skin):
    if mode == 'keep':
        return
    bbox, (cx, cy) = char['mouth']
    mouth_cover(image, bbox, skin)
    draw = ImageDraw.Draw(image)
    w = (bbox[2] - bbox[0]) / 2
    if mode == 'smile':
        curve(draw, (cx - w * 0.72, cy - 2), (cx, cy + 6), (cx + w * 0.72, cy - 2), MOUTH_LINE, 2.0)
    elif mode == 'cat':
        curve(draw, (cx - w * 0.8, cy - 2), (cx - w * 0.4, cy + 5), (cx, cy - 1), MOUTH_LINE, 2.0)
        curve(draw, (cx, cy - 1), (cx + w * 0.4, cy + 5), (cx + w * 0.8, cy - 2), MOUTH_LINE, 2.0)
    elif mode == 'smug':
        curve(draw, (cx - w * 0.55, cy + 1), (cx, cy + 4), (cx + w * 0.75, cy - 3), MOUTH_LINE, 2.0)
    elif mode == 'o':
        draw.ellipse([(cx - 5) * S, (cy - 4) * S, (cx + 5) * S, (cy + 6) * S],
                     fill=MOUTH_DARK, outline=MOUTH_LINE, width=S)
    elif mode == 'open_small':
        open_mouth(draw, cx, cy - 1, w * 0.52, 7.5)
    elif mode == 'open_big':
        open_mouth(draw, cx, cy - 2, w * 0.66, 10.5)
    elif mode == 'grin':
        open_mouth(draw, cx, cy - 1, w * 0.85, 8.5, teeth=True)


def draw_extras(body, char, recipe):
    level = recipe.get('blush', 0)
    if level:
        layer = Image.new('RGBA', body.size, (0, 0, 0, 0))
        d = ImageDraw.Draw(layer)
        (_, (mcx, mcy)) = char['mouth']
        for (bbox, (cx, cy), _) in char['eyes']:
            d.ellipse([cx - 24, mcy - 26, cx + 24, mcy - 8], fill=(255, 130, 150, 42 * level))
        body.alpha_composite(layer.filter(ImageFilter.GaussianBlur(7)))
    if recipe.get('sweat'):
        d = ImageDraw.Draw(body)
        x, y = char['sweat']
        d.polygon([(x, y - 9), (x - 5, y + 3), (x + 5, y + 3)], fill='#9fd8ff', outline='#5aa7d6')
        d.ellipse([x - 5, y - 1, x + 5, y + 7], fill='#9fd8ff', outline='#5aa7d6')


def skin_color(body, rect):
    crop = body.crop(rect).convert('RGB')
    px = crop.load()
    pixels = sorted(px[x, y] for x in range(crop.width) for y in range(crop.height))
    return pixels[len(pixels) // 2]


def build(name, char):
    folder = ROOT / name
    body = Image.open(folder / f'{name}_body_base.webp').convert('RGBA')
    skin = skin_color(body, char['skin_rect'])
    edits = [char['eyes'][0][0], char['eyes'][1][0], char['mouth'][0],
             (char['brows'][0][0], char['brows'][0][1] - 10, char['brows'][1][2], char['brows'][1][1] + 6)]
    pad = 8
    box = char['box']
    # 編集領域は顔枠の内側2pxまでに制限し、枠の端ピクセルをボディと同一に保つ
    region = (max(box[0] + 2, min(b[0] for b in edits) - pad),
              max(box[1] + 2, min(b[1] for b in edits) - pad),
              min(box[2] - 2, max(b[2] for b in edits) + pad + 14),
              min(box[3] - 2, max(b[3] for b in edits) + pad))
    for suffix, recipe in RECIPES[name].items():
        big = body.resize((body.width * S, body.height * S), Image.Resampling.LANCZOS)
        for i, mode in enumerate(recipe['eyes']):
            draw_eye(big, char, i, mode, skin)
        for i in range(2):
            draw_brow(big, char, i, recipe['brow'], skin)
        draw_mouth(big, char, recipe['mouth'], skin)
        edited = big.resize(body.size, Image.Resampling.LANCZOS)
        draw_extras(edited, char, recipe)
        face = body.copy()
        face.paste(edited.crop(region), region)
        patch = face.crop(char['box'])
        # 完全透明ピクセルのRGBを0にし、メッシュ補間での色滲みを防ぐ
        clear = patch.getchannel('A').point(lambda a: 255 if a == 0 else 0)
        patch.paste((0, 0, 0, 0), (0, 0), clear)
        patch.save(folder / f'{name}_face_{suffix}.webp', lossless=True, exact=True)
        print(name, suffix)


def main():
    for name, char in CHARACTERS.items():
        build(name, char)


if __name__ == '__main__':
    main()
