"""Regression checks for browser URL constants and regexes (no WebView)."""
import json
import pathlib
import re
import unittest

SOURCE = (pathlib.Path(__file__).resolve().parents[1] /
          "app/src/main/java/com/example/kennys_dokidoki_wallpaper/CivitaiBrowserActivity.kt").read_text()


def literal(name):
    pattern = rf'{name}\s*=\s*(?:Pattern\.compile\(\s*)?("(?:\\.|[^"\\])*")'
    return json.loads(re.search(pattern, SOURCE).group(1))


class CivitaiBrowserTest(unittest.TestCase):
    def test_home_uses_red(self):
        self.assertEqual(literal("HOME_URL"), "https://civitai.red/")
        self.assertEqual(SOURCE.count("webView.loadUrl(HOME_URL)"), 2)

    def test_red_models(self):
        pattern = re.compile(literal("MODEL_PATTERN"), re.I)
        for url in ("https://civitai.red/models/123/name",
                    "https://www.civitai.red/models/123?modelVersionId=456",
                    "https://CIVITAI.RED/models/123"):
            with self.subTest(url=url):
                match = pattern.search(url)
                self.assertIsNotNone(match)
                self.assertEqual(match.group(1), "123")

    def test_com_links_still_work(self):
        pattern = re.compile(literal("MODEL_PATTERN"), re.I)
        self.assertEqual(pattern.search("https://civitai.com/models/123/name").group(1), "123")

    def test_version_is_preserved(self):
        pattern = re.compile(literal("VERSION_PATTERN"))
        self.assertEqual(pattern.search("https://civitai.red/models/123?modelVersionId=456").group(1), "456")

    def test_non_model_urls(self):
        pattern = re.compile(literal("MODEL_PATTERN"), re.I)
        for url in ("https://civitai.red/", "https://civitai.red/images/123",
                    "https://civitai.red.evil.test/models/123",
                    "https://example.org/?url=https://civitai.red/models/123"):
            with self.subTest(url=url):
                self.assertIsNone(pattern.search(url))


if __name__ == "__main__":
    unittest.main()
