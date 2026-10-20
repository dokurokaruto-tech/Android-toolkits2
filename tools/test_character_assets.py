"""Run with: python -m unittest discover -s tools -p 'test_character_assets.py'. Requires Pillow."""
from pathlib import Path
import unittest
from PIL import Image, ImageChops

LIVE2D = Path(__file__).resolve().parents[1] / 'app/src/main/assets/live2d'
FACE_BOX = (160, 90, 316, 216)
EDGES = ((0, 0, 156, 1), (0, 125, 156, 126), (0, 0, 1, 126), (155, 0, 156, 126))
CHARACTERS = ('bocchi', 'kita', 'nijika', 'ryo')
# Bocchi keeps her original panic file name; the others call the slot "cheer".
PATCHES = ('half_blink', 'blink', 'smile', 'awakened', 'singing', 'surprised', 'idle')


def patch_names(char):
    panic = 'panic' if char == 'bocchi' else 'cheer'
    return PATCHES + (panic,)


class CharacterAssetsTest(unittest.TestCase):
    def test_body_geometry_and_alpha(self):
        for char in CHARACTERS:
            with self.subTest(char=char), Image.open(LIVE2D / char / f'{char}_body_base.webp') as image:
                self.assertEqual(image.size, (592, 750))
                self.assertEqual(image.mode, 'RGBA')
                self.assertEqual(image.getpixel((0, 0))[3], 0)
                self.assertTrue(all(image.getpixel((x, 749))[3] == 0 for x in range(592)))
                # No green-screen residue anywhere opaque.
                self.assertFalse(any(g > max(r, b) + 60 and a > 200 for r, g, b, a in image.getdata()))

    def test_face_patches_match_rig(self):
        # Two patch styles exist: opaque copies (edges bit-identical to the body)
        # and sparse patches (transparent outside the edited features).
        for char in CHARACTERS:
            body = Image.open(LIVE2D / char / f'{char}_body_base.webp').convert('RGBA').crop(FACE_BOX)
            for name in patch_names(char):
                path = LIVE2D / char / f'{char}_face_{name}.webp'
                with self.subTest(char=char, name=name), Image.open(path) as image:
                    self.assertEqual(image.size, (156, 126))
                    patch = image.convert('RGBA')
                    # Only where the patch paints (alpha > 0) must it agree with the
                    # body at the edges. Where it is transparent the body shows
                    # through, and RGB under alpha=0 is invisible/encoder-defined.
                    mask = patch.getchannel('A').point([0] + [255] * 255)
                    raw = ImageChops.difference(body, patch)
                    black = Image.new('RGBA', raw.size, (0, 0, 0, 255))
                    rgb_diff = Image.composite(raw, black, mask).convert('RGB')
                    alpha_raw = ImageChops.difference(body.getchannel('A'), patch.getchannel('A'))
                    alpha_diff = Image.composite(alpha_raw, Image.new('L', raw.size, 0), mask)
                    # No seam: the composite must equal the body on every patch edge.
                    for edge in EDGES:
                        self.assertIsNone(rgb_diff.crop(edge).getbbox())
                        self.assertIsNone(alpha_diff.crop(edge).getbbox())
                    # Every expression must actually change the face.
                    self.assertIsNotNone(rgb_diff.getbbox())


if __name__ == '__main__':
    unittest.main()
