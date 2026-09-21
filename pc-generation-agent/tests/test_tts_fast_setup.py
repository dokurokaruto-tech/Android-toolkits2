from __future__ import annotations

import importlib.metadata
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from tools.tts_fast_setup import package_issues, set_backend
from generation_agent.tts_options import TtsBackend, FAST_PACKAGE_VERSION
from generation_agent.tts_engine import check_graph_budget


class FastSetupTest(unittest.TestCase):
    def test_version_pin(self):
        with patch("importlib.metadata.version", side_effect={"faster-qwen3-tts":FAST_PACKAGE_VERSION,
                                                             "transformers":"4.57.3"}.__getitem__):
            self.assertEqual(package_issues(), [])
        with patch("importlib.metadata.version", return_value="0.4.0"):
            self.assertTrue(package_issues())
        with patch("importlib.metadata.version", side_effect=importlib.metadata.PackageNotFoundError):
            self.assertTrue(package_issues())

    def test_config_preserved(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "config.json"
            path.write_text('{"tts_model_dir":"existing/model", "api_key":"keep", "tts_unload_sd":false}')
            original = path.read_bytes()
            set_backend(path, TtsBackend.CUDA_GRAPH)
            values = json.loads(path.with_suffix(".local.json").read_text())
            self.assertEqual(values["tts_model_dir"], "existing/model")
            self.assertEqual(values["api_key"], "keep")
            self.assertFalse(values["tts_unload_sd"])
            self.assertEqual(values["tts_backend"], "cuda_graph")
            self.assertEqual(values["tts_attention"], "auto")
            self.assertEqual(values["tts_keep_alive_seconds"], 120)
            set_backend(path, TtsBackend.STANDARD)
            values = json.loads(path.with_suffix(".local.json").read_text())
            self.assertEqual(values["tts_backend"], "standard")
            self.assertEqual(values["tts_attention"], "sdpa")
            self.assertEqual(values["tts_keep_alive_seconds"], 0)
            self.assertEqual(path.read_bytes(), original)

    def test_install_requires_consent(self):
        batch = (ROOT / "setup-tts-fast.bat").read_text()
        confirm = 'call :confirm "Install/check the fast backend and enable it?"\nif errorlevel 1 goto canceled'
        self.assertIn(confirm, batch)
        self.assertLess(batch.index(confirm), batch.index(' -m pip install'))
        self.assertLess(batch.index('--runtime-check --backend cuda_graph'), batch.index('tts_fast_setup.py --enable'))
        self.assertIn('if errorlevel 2 exit /b 1\nif errorlevel 1 exit /b 0\nexit /b 1', batch)
        self.assertIn('"torch==2.7.1+cu126"', batch)

    def test_offline_model_load(self):
        for name in ('generation_agent/tts_worker.py', 'generation_agent/tts_process.py'):
            source = (ROOT / name).read_text()
            self.assertIn('HF_HUB_OFFLINE', source)
            self.assertIn('TRANSFORMERS_OFFLINE', source)

    def test_graph_budget(self):
        check_graph_budget("こんにちは。", "サンプルの声です。")
        with self.assertRaisesRegex(ValueError, "入力上限"):
            check_graph_budget("あ" * 200, "長" * 2000)


if __name__ == "__main__":
    unittest.main()
