"""Run with: python3 -m unittest discover -s tools -p 'test_live2d_assets.py'. Requires Pillow, numpy."""
from pathlib import Path
import unittest

import numpy as np
from PIL import Image

ASSETS = Path(__file__).resolve().parents[1] / 'app/src/main/assets/live2d'
SIZE = (592, 750)
FACE_BOX = (160, 90, 316, 216)
# 顔ボックス内の眉・目・口バンド。この外側は継ぎ目を出してはいけない。
BAND = (8, 16, 148, 123)
KEYS = ('half_blink', 'blink', 'smile', 'awakened', 'panic', 'laugh', 'wink', 'surprise')


def load(path: Path) -> np.ndarray:
    with Image.open(path) as image:
        return np.array(image.convert('RGBA')).astype(int)


class Live2dAssetsTest(unittest.TestCase):
    def test_body_geometry_and_alpha(self):
        for folder in sorted(ASSETS.iterdir()):
            body = load(folder / f'{folder.name}_body_base.webp')
            with self.subTest(character=folder.name):
                self.assertEqual(body.shape[:2], (750, 592))
                # 背景は抜け、足元はフェードで床へ溶ける
                self.assertEqual(body[0, 0, 3], 0)
                self.assertTrue((body[749, :, 3] == 0).all())
                self.assertEqual(body[300, 240, 3], 255)
                # グリーンバックの残留がない
                opaque = body[:, :, 3] > 200
                green_dominant = body[:, :, 1] > np.maximum(body[:, :, 0], body[:, :, 2]) + 60
                self.assertEqual(int((opaque & green_dominant).sum()), 0)

    def test_face_patches_match_rig(self):
        for folder in sorted(ASSETS.iterdir()):
            body = load(folder / f'{folder.name}_body_base.webp')
            crop = body[FACE_BOX[1]:FACE_BOX[3], FACE_BOX[0]:FACE_BOX[2]]
            with self.subTest(character=folder.name):
                for key in KEYS:
                    patch = load(folder / f'{folder.name}_face_{key}.webp')
                    with self.subTest(patch=key):
                        self.assertEqual(patch.shape[:2], (126, 156))

                        # 1. シルエット外へのはみ出しがない。長方形の輪郭が出ない
                        self.assertTrue((patch[:, :, 3] <= crop[:, :, 3]).all())

                        # 2. バンド外は差分なし。端がボディと同一である
                        #    （透過ピクセルは下のボディがそのまま見えるので対象外）
                        diff = np.abs(patch[:, :, :3] - crop[:, :, :3]).sum(axis=2)
                        diff[patch[:, :, 3] == 0] = 0
                        outside = diff.copy()
                        outside[BAND[1]:BAND[3], BAND[0]:BAND[2]] = 0
                        self.assertEqual(int(outside.max()), 0)

                        # 3. バンド内に表情差分が実在する
                        self.assertGreater(int(diff[BAND[1]:BAND[3], BAND[0]:BAND[2]].max()), 0)


if __name__ == '__main__':
    unittest.main()
