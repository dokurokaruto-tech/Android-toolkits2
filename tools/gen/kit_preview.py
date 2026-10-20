"""Render the drum kit layout over a body sprite so the UV constants can be
tuned before the character art exists. Mirrors BocchiLive2dStageView.

  python3 tools/gen/kit_preview.py kita
  python3 tools/gen/kit_preview.py kita --pose drum
"""
import sys
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[2]
SIZE = (592, 750)
CHAR_TOP_RATIO = 0.045
CHAR_HEIGHT_RATIO = 0.76
MAX_CHAR_WIDTH_RATIO = 0.96
CHAR_X_BIAS = 0.02

KIT = {
    'hat': (0.22, 0.58, 0.085, 0.024),
    'snare': (0.52, 0.68, 0.088, 0.058),
    'tom': (0.66, 0.58, 0.072, 0.048),
    'floor': (0.82, 0.66, 0.098, 0.064),
    'bass': (0.56, 0.84, 0.185, 0.120),
    'crash': (0.86, 0.46, 0.105, 0.028),
}
STAND_V = 0.14
STEEL = (154, 167, 180, 235)
BRASS = (201, 150, 47, 235)
BRASS_LIT = (242, 206, 114, 210)
HEAD = (243, 236, 221, 255)
STICK = (232, 217, 184, 255)
SHELL = (255, 201, 74, 210)
SHELL_SNARE = (35, 38, 46, 210)
ACCENT = (255, 201, 74, 200)


def frame(name, hits, hands):
    body = Image.open(ROOT / f'app/src/main/assets/live2d/{name}/{name}_body_base.webp').convert('RGBA')
    body = body.resize(SIZE, Image.Resampling.LANCZOS)
    stage = Image.new('RGBA', SIZE, (18, 10, 40, 255))
    stage.alpha_composite(body)

    char_height = SIZE[1] * CHAR_HEIGHT_RATIO
    char_width = char_height * (SIZE[0] / SIZE[1])
    if char_width > SIZE[0] * MAX_CHAR_WIDTH_RATIO:
        char_width = SIZE[0] * MAX_CHAR_WIDTH_RATIO
        char_height = char_width / (SIZE[0] / SIZE[1])
    char_left = (SIZE[0] - char_width) * 0.5 + SIZE[0] * CHAR_X_BIAS
    char_top = SIZE[1] * CHAR_TOP_RATIO

    def point(u, v):
        return char_left + u * char_width, char_top + v * char_height

    overlay = Image.new('RGBA', SIZE, (0, 0, 0, 0))
    d = ImageDraw.Draw(overlay)

    def stand(u, v):
        x, y = point(u, v)
        d.line([x, y, x, y + char_height * STAND_V], fill=STEEL, width=max(2, int(char_width * 0.006)))

    def cymbal(key, hit):
        u, v, ru, rv = KIT[key]
        x, y = point(u, v)
        rx, ry = char_width * ru * (1 + hit * 0.10), char_height * rv * (1 + hit * 0.10)
        d.ellipse([x - rx, y - ry, x + rx, y + ry], fill=BRASS, outline=BRASS_LIT, width=3)
        d.ellipse([x - rx * 0.62, y - ry * 0.62, x + rx * 0.62, y + ry * 0.62], fill=BRASS_LIT)

    def shell(key, hit, color):
        u, v, ru, rv = KIT[key]
        x, y = point(u, v)
        rx, ry = char_width * ru, char_height * rv
        swell = 1 + hit * 0.13
        d.ellipse([x - rx, y - ry * 1.22, x + rx, y + ry * 1.22], fill=color)
        d.ellipse([x - rx * 0.7, y - ry * 1.16, x + rx * 0.7, y - ry * 0.42], fill=(255, 255, 255, 26))
        d.ellipse([x - rx * swell, y - ry * swell, x + rx * swell, y + ry * swell], fill=HEAD,
                  outline=SHELL, width=max(2, int(char_width * 0.007)))

    def stick(hand, head, hit):
        hx, hy = point(*hand)
        tx, ty = point(*head)
        mid = ((hx + tx) / 2, (hy + ty) / 2 - char_width * (0.05 + hit * 0.055))
        d.line([hx, hy, mid, tx, ty], fill=ACCENT, width=max(3, int(char_width * 0.008)))
        d.line([hx, hy, mid, tx, ty], fill=STICK, width=max(2, int(char_width * 0.0055)))
        r = char_width * (0.010 + hit * 0.012)
        d.ellipse([tx - r, ty - r, tx + r, ty + r], fill=ACCENT)

    stand(KIT['crash'][0], KIT['crash'][1])
    stand(KIT['hat'][0], KIT['hat'][1])
    cymbal('crash', hits['crash'])
    cymbal('hat', hits['hat'])
    shell('tom', hits['snare'] * 0.5, SHELL)
    shell('floor', hits['snare'] * 0.4, SHELL)
    shell('snare', hits['snare'], SHELL_SNARE)
    shell('bass', hits['kick'], SHELL)
    stick(hands[0], (KIT['hat'][0], KIT['hat'][1]), hits['hat'])
    stick(hands[1], (KIT['snare'][0], KIT['snare'][1]), hits['snare'])

    stage.alpha_composite(overlay)
    out = ROOT / f'tools/gen/kit_{name}.png'
    stage.save(out)
    print(out)


if __name__ == '__main__':
    name = sys.argv[1]
    hits = {'kick': 1.0, 'snare': 0.0, 'hat': 0.9, 'crash': 0.5}
    hands = ((0.28, 0.46), (0.58, 0.44))
    frame(name, hits, hands)
