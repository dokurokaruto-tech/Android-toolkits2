"""Overlay the Live2D rig guide on a sprite candidate for alignment checks."""
import sys
from PIL import Image, ImageDraw

SIZE = (592, 750)
FACE = (160, 90, 316, 216)
EYES = [(0.352, 0.203), (0.453, 0.205)]
GTR = [(0.26, 0.65), (0.96, 0.35)]


def main():
    src = Image.open(sys.argv[1]).convert('RGB')
    dst = Image.new('RGB', SIZE, (16, 16, 24))
    scale = min(SIZE[0] / src.width, SIZE[1] / src.height)
    resized = src.resize((round(src.width * scale), round(src.height * scale)), Image.Resampling.LANCZOS)
    dst.paste(resized, ((SIZE[0] - resized.width) // 2, (SIZE[1] - resized.height) // 2))
    d = ImageDraw.Draw(dst)
    d.rectangle(FACE, outline=(0, 255, 0), width=2)
    for u, v in EYES:
        d.ellipse([u * SIZE[0] - 5, v * SIZE[1] - 5, u * SIZE[0] + 5, v * SIZE[1] + 5], outline=(255, 255, 0), width=2)
    for u, v in GTR:
        d.line([u * SIZE[0] - 6, v * SIZE[1] - 6, u * SIZE[0] + 6, v * SIZE[1] + 6], fill=(255, 0, 255), width=2)
        d.line([u * SIZE[0] - 6, v * SIZE[1] + 6, u * SIZE[0] + 6, v * SIZE[1] - 6], fill=(255, 0, 255), width=2)
    out = sys.argv[2] if len(sys.argv) > 2 else sys.argv[1].rsplit('.', 1)[0] + '_guide.png'
    dst.save(out)
    print(out)


if __name__ == '__main__':
    main()
