"""Rebuild expression patches from the checked-in, keyed Kita body. Requires Pillow."""
from pathlib import Path
from PIL import Image, ImageDraw

ASSETS = Path(__file__).resolve().parents[1] / 'app/src/main/assets/live2d/kita'
FACE_BOX = (160, 90, 316, 216)
SCALE = 4
# Coordinates measured on the 592 x 750 sprite; retain hair outside the eyelids.
EYES = [((190, 140), (200, 129), (215, 130), (226, 140), (223, 153), (216, 161), (202, 160), (195, 153)),
        ((254, 143), (262, 135), (277, 133), (287, 141), (282, 153), (274, 163), (262, 163), (255, 157))]
SKIN = '#fbe2d6'
LASH = '#433026'


def curve(draw, points, width=2):
    p0, p1, p2 = points
    samples = []
    for i in range(41):
        t = i / 40
        samples.append(tuple(round(((1-t)**2*p0[j] + 2*(1-t)*t*p1[j] + t*t*p2[j])*SCALE) for j in range(2)))
    draw.line(samples, fill=LASH, width=round(width*SCALE), joint='curve')


def lid(image, eye, mode):
    draw = ImageDraw.Draw(image)
    polygon = EYES[eye]
    mask = Image.new('L', image.size)
    ImageDraw.Draw(mask).polygon([(x*SCALE, y*SCALE) for x,y in polygon], fill=255)
    if mode == 'half':
        cutoff = (146 if eye == 0 else 149)*SCALE
        ImageDraw.Draw(mask).rectangle((0, cutoff, image.width, image.height), fill=0)
    image.paste(SKIN, (0, 0), mask)
    if mode == 'half':
        points = [(191, 141), (208, 149), (226, 145)] if eye == 0 else [(254, 148), (270, 151), (287, 143)]
    elif mode == 'cheer':
        points = [(191, 149), (208, 133), (226, 150)] if eye == 0 else [(254, 152), (270, 136), (286, 149)]
    else:
        points = [(191, 146), (208, 159), (226, 147)] if eye == 0 else [(254, 149), (270, 162), (286, 146)]
    curve(draw, points, 2.5)


def main():
    body = Image.open(ASSETS / 'kita_body_base.webp').convert('RGBA')
    for name in ('half_blink', 'blink', 'smile', 'awakened', 'cheer'):
        face = body.resize((body.width*SCALE, body.height*SCALE), Image.Resampling.LANCZOS)
        if name in ('half_blink', 'blink', 'cheer'):
            mode = {'half_blink': 'half', 'blink': 'closed', 'cheer': 'cheer'}[name]
            for eye in range(2):
                lid(face, eye, mode)
        if name == 'awakened':
            lid(face, 1, 'cheer')
        edited = face.resize(body.size, Image.Resampling.LANCZOS)
        face = body.copy()
        # Keep patch edges bit-identical to the body to avoid a rectangular seam.
        eye_box = (189, 128, 289, 166)
        face.paste(edited.crop(eye_box), eye_box)
        face.crop(FACE_BOX).save(ASSETS / f'kita_face_{name}.webp', lossless=True)


if __name__ == '__main__':
    main()
