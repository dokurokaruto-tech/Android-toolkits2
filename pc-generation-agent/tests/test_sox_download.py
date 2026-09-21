from __future__ import annotations

import hashlib
import http.client
import importlib
import io
import sys
import tempfile
import unittest
import urllib.error
import zipfile
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))


class Response(io.BytesIO):
    def __init__(self, body: bytes, content_type: str = "application/zip"):
        super().__init__(body)
        self.headers = {"Content-Type": content_type}

    def geturl(self):
        return "https://mirror.example/sox.zip"


class SoxDownloadTest(unittest.TestCase):
    def setUp(self):
        self.module = importlib.import_module("tools.sox_download")
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.output = Path(temporary.name) / "sox.zip"
        archive = io.BytesIO()
        with zipfile.ZipFile(archive, "w") as zip_file:
            zip_file.writestr("sox-14.4.2/sox.exe", b"test executable; never run")
        self.valid = archive.getvalue()
        self.sha = hashlib.sha256(self.valid).hexdigest()
        patcher = patch.object(self.module, "SOX_SHA256", self.sha)
        patcher.start()
        self.addCleanup(patcher.stop)

    def test_html_retries(self):
        with patch.object(self.module.urllib.request, "urlopen", side_effect=[
            Response(b"<!doctype html><html>Please wait</html>", "text/html"), Response(self.valid)
        ]) as open_url:
            with self.assertLogs("sox-download", level="INFO") as logs:
                self.module.download_sox(self.output)
        self.assertEqual(self.output.read_bytes(), self.valid)
        self.assertEqual(open_url.call_count, 2)
        self.assertTrue(any("not a ZIP" in line for line in logs.output))
        self.assertTrue(any("text/html" in line for line in logs.output))

    def test_wrong_hash_retries(self):
        altered = self.valid.replace(b"test executable", b"evil executable")
        with patch.object(self.module.urllib.request, "urlopen", side_effect=[Response(altered), Response(self.valid)]):
            with self.assertLogs("sox-download", level="INFO") as logs:
                self.module.download_sox(self.output)
        self.assertEqual(self.output.read_bytes(), self.valid)
        self.assertTrue(any("SHA256 mismatch" in line for line in logs.output))
        self.assertTrue(any(self.sha in line for line in logs.output))

    def test_network_retry(self):
        with patch.object(self.module.urllib.request, "urlopen", side_effect=[
            urllib.error.URLError("connection closed"), Response(self.valid)
        ]):
            self.module.download_sox(self.output)
        self.assertEqual(self.output.read_bytes(), self.valid)

    def test_truncated_retry(self):
        class Truncated(Response):
            def read(self, size=-1):
                raise http.client.IncompleteRead(b"PK\x03\x04partial")

        with patch.object(self.module.urllib.request, "urlopen", side_effect=[Truncated(b""), Response(self.valid)]):
            self.module.download_sox(self.output)
        self.assertEqual(self.output.read_bytes(), self.valid)
        self.assertEqual(list(self.output.parent.iterdir()), [self.output])

    def test_failure_keeps_original(self):
        self.output.write_bytes(b"original")
        with patch.object(self.module.urllib.request, "urlopen", side_effect=urllib.error.URLError("offline")):
            with self.assertRaisesRegex(RuntimeError, "No verified SoX archive"):
                self.module.download_sox(self.output)
        self.assertEqual(self.output.read_bytes(), b"original")
        self.assertEqual(list(self.output.parent.iterdir()), [self.output])

    def test_invalid_zip_is_not_saved(self):
        with patch.object(self.module.urllib.request, "urlopen", side_effect=lambda *a, **k: Response(b"<html>blocked</html>")):
            with self.assertRaises(RuntimeError):
                self.module.download_sox(self.output)
        self.assertEqual(list(self.output.parent.iterdir()), [])

    def test_oversized_download_is_bounded(self):
        with patch.object(self.module, "MAX_ARCHIVE_BYTES", 8), \
             patch.object(self.module.urllib.request, "urlopen", side_effect=lambda *a, **k: Response(self.valid)):
            with self.assertRaises(RuntimeError):
                self.module.download_sox(self.output)
        self.assertFalse(self.output.exists())

    def test_manual_hash_check(self):
        source = self.output.parent / "browser-download.zip"
        source.write_bytes(self.valid)
        self.module.copy_verified(source, self.output)
        self.assertEqual(self.output.read_bytes(), self.valid)
        source.write_bytes(self.valid + b"altered")
        with self.assertRaisesRegex(ValueError, "SHA256 mismatch"):
            self.module.copy_verified(source, self.output)
        self.assertEqual(self.output.read_bytes(), self.valid)

    def test_hash_diagnostics(self):
        self.output.write_bytes(self.valid + b"altered")
        actual = hashlib.sha256(self.output.read_bytes()).hexdigest()
        with self.assertRaises(ValueError) as caught:
            self.module.verify_archive(self.output)
        self.assertIn(actual, str(caught.exception))
        self.assertIn(self.sha, str(caught.exception))
        self.assertIn(str(self.output.stat().st_size), str(caught.exception))

    def test_direct_https_urls(self):
        urls = self.module.SOX_URLS
        self.assertGreaterEqual(len(urls), 2)
        self.assertTrue(all(url.startswith("https://") and url.endswith(".zip") for url in urls))
        self.assertFalse(any("/download" == url[-9:] for url in urls))


if __name__ == "__main__":
    unittest.main()
