"""Run with: python -m unittest discover -s tools -p 'test_live2d_assets.py'. Requires Pillow."""
from pathlib import Path
import unittest
from PIL import Image, ImageChops

ASSETS = Path(__file__).resolve().parents[1] / 'app/src/main/assets/live2d'
# folder -> (face box, key-colour check: channel that must not dominate)
GENERATED = {
    'kita': ((162, 92, 312, 222), 'green'),
    'nijika': ((186, 122, 332, 250), 'blue'),
    'ryo': ((162, 92, 312, 215), 'green'),
}
FACES = ('half_blink', 'blink', 'smile', 'awakened', 'cheer', 'wink', 'surprised', 'dreamy')


class Live2dAssetsTest(unittest.TestCase):
    def test_bodies(self):
        for name, (_, key) in GENERATED.items():
            with self.subTest(name=name), Image.open(ASSETS / name / f'{name}_body_base.webp') as image:
                self.assertEqual(image.size, (592, 750))
                self.assertEqual(image.mode, 'RGBA')
                self.assertEqual(image.getpixel((0, 0))[3], 0)
                self.assertTrue(all(image.getpixel((x, 749))[3] == 0 for x in range(592)))
                if key == 'green':
                    self.assertFalse(any(g > 200 and g > max(r, b) + 80 and a > 200
                                         for r, g, b, a in image.getdata()))
                else:  # pure chroma blue (1,71,255); cyan rim light has g > 180
                    self.assertFalse(any(b > 230 and r < 40 and 60 < g < 130 and a > 200
                                         for r, g, b, a in image.getdata()))

    def test_face_patches_match_rig(self):
        for name, (box, _) in GENERATED.items():
            body = Image.open(ASSETS / name / f'{name}_body_base.webp').convert('RGBA').crop(box)
            w, h = box[2] - box[0], box[3] - box[1]
            for face in FACES:
                path = ASSETS / name / f'{name}_face_{face}.webp'
                with self.subTest(name=name, face=face), Image.open(path) as image:
                    self.assertEqual(image.size, (w, h))
                    diff = ImageChops.difference(body, image.convert('RGBA')).convert('RGB')
                    for edge in ((0, 0, w, 1), (0, h - 1, w, h), (0, 0, 1, h), (w - 1, 0, w, h)):
                        self.assertIsNone(diff.crop(edge).getbbox())
                    self.assertIsNotNone(diff.getbbox())

    def test_bocchi_assets_unchanged_geometry(self):
        with Image.open(ASSETS / 'bocchi/bocchi_body_base.webp') as image:
            self.assertEqual(image.size, (592, 750))
        for face in ('half_blink', 'blink', 'smile', 'awakened', 'panic'):
            with self.subTest(face=face), Image.open(ASSETS / f'bocchi/bocchi_face_{face}.webp') as image:
                self.assertEqual(image.size, (156, 126))


if __name__ == '__main__':
    unittest.main()
