"""Debug helper: render every expression patch over its body on a dark stage."""
import sys
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[2]
BG = (20, 12, 44)


def sheet(name, keys, cols=4, cell=260):
    body = Image.open(ROOT / f'app/src/main/assets/live2d/{name}/{name}_body_base.webp').convert('RGBA')
    rows = (len(keys) + cols - 1) // cols
    out = Image.new('RGB', (cols * cell, rows * (cell + 22)), BG)
    d = ImageDraw.Draw(out)
    for i, key in enumerate(keys):
        face = Image.open(ROOT / f'app/src/main/assets/live2d/{name}/{name}_face_{key}.webp').convert('RGBA')
        frame = body.copy()
        frame.alpha_composite(face, (0, 0))
        stage = Image.new('RGBA', body.size, BG + (255,))
        stage.alpha_composite(frame)
        crop = stage.crop((140, 60, 336, 246)).resize((cell, cell), Image.Resampling.LANCZOS)
        x, y = (i % cols) * cell, (i // cols) * (cell + 22)
        out.paste(crop, (x, y))
        d.text((x + 6, y + cell + 4), key, fill=(230, 230, 240))
    path = ROOT / f'tools/gen/{name}_sheet.png'
    out.save(path)
    print(path)


if __name__ == '__main__':
    sheet(sys.argv[1], sys.argv[2:])
