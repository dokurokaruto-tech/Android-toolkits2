"""Run with: python -m unittest discover -s tools -p 'test_kita_assets.py'. Requires Pillow."""
from pathlib import Path
import unittest
from PIL import Image, ImageChops

ASSETS = Path(__file__).resolve().parents[1] / 'app/src/main/assets/live2d/kita'
FACE_BOX = (160, 90, 316, 216)


class KitaAssetsTest(unittest.TestCase):
    def test_body_geometry_and_alpha(self):
        with Image.open(ASSETS / 'kita_body_base.webp') as image:
            self.assertEqual(image.size, (592, 750))
            self.assertEqual(image.mode, 'RGBA')
            self.assertEqual(image.getpixel((0, 0))[3], 0)
            self.assertEqual(image.getpixel((240, 300))[3], 255)
            self.assertTrue(all(image.getpixel((x, 749))[3] == 0 for x in range(592)))
            self.assertFalse(any(g > max(r, b) + 60 and a > 200 for r, g, b, a in image.getdata()))

    def test_face_patches_match_rig(self):
        body = Image.open(ASSETS / 'kita_body_base.webp').convert('RGBA').crop(FACE_BOX)
        for name in ('half_blink', 'blink', 'smile', 'awakened', 'cheer'):
            with self.subTest(name=name), Image.open(ASSETS / f'kita_face_{name}.webp') as image:
                self.assertEqual(image.size, (156, 126))
                diff = ImageChops.difference(body, image.convert('RGBA')).convert('RGB')
                for edge in ((0, 0, 156, 1), (0, 125, 156, 126), (0, 0, 1, 126), (155, 0, 156, 126)):
                    self.assertIsNone(diff.crop(edge).getbbox())
                if name in ('blink', 'half_blink', 'awakened', 'cheer'):
                    self.assertIsNotNone(diff.getbbox())


if __name__ == '__main__':
    unittest.main()
