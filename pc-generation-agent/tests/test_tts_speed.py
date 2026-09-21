from __future__ import annotations

import io
import sys
import tempfile
import threading
import unittest
import urllib.error
from dataclasses import replace
from pathlib import Path
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from generation_agent.config import AgentConfig
from generation_agent.sd_client import StableDiffusionClient
from generation_agent.tts import TtsService, TtsBusyError
from generation_agent.service import GenerationService
from test_tts import request_body, AUDIO


class ReloadTest(unittest.TestCase):
    def test_reload_not_found(self):
        sd = StableDiffusionClient("http://localhost:7860", 30)
        error = urllib.error.HTTPError("http://localhost", 404, "Not Found", {}, io.BytesIO(b'{"detail":"Not Found"}'))
        with patch("urllib.request.urlopen", side_effect=error) as send, self.assertLogs(level="WARNING"):
            sd.reload_checkpoint()
            sd.reload_checkpoint()
        self.assertEqual(send.call_count, 1)

    def test_reload_server_error(self):
        sd = StableDiffusionClient("http://localhost:7860", 30)
        error = urllib.error.HTTPError("http://localhost", 500, "Failed", {}, io.BytesIO(b"failure"))
        with patch("urllib.request.urlopen", side_effect=error), self.assertRaises(RuntimeError):
            sd.reload_checkpoint()


class WarmServiceTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        root = Path(temp.name)
        (root / "config.json").write_text('{"tts_model_dir":".", "tts_keep_alive_seconds":120}')
        self.config = AgentConfig.load(root / "config.json")
        self.lock = threading.Lock()
        self.sd = Mock()
        self.sd.health.return_value = True
        self.service = TtsService(self.config, self.lock, self.sd)
        self.addCleanup(lambda: getattr(self.service, "close", lambda: None)())

    def test_reuse_defers_sd_reload(self):
        with patch.object(self.service, "_run_worker", return_value=AUDIO):
            self.service.synthesize(request_body())
            self.service.synthesize(request_body())
        self.sd.unload_checkpoint.assert_called_once()
        self.sd.reload_checkpoint.assert_not_called()
        with self.lock:
            self.service.release_gpu()
        self.sd.reload_checkpoint.assert_called_once()

    def test_error_releases_warm_gpu(self):
        with patch.object(self.service, "_run_worker", side_effect=RuntimeError("failed")):
            with self.assertRaises(RuntimeError):
                self.service.synthesize(request_body())
        self.sd.reload_checkpoint.assert_called_once()
        self.assertFalse(self.lock.locked())

    def test_idle_releases_gpu(self):
        with patch.object(self.service, "_run_worker", return_value=AUDIO):
            self.service.synthesize(request_body())
        self.service._expire()
        self.sd.reload_checkpoint.assert_called_once()

    def test_old_timer_cannot_evict(self):
        with patch.object(self.service, "_run_worker", return_value=AUDIO):
            self.service.synthesize(request_body())
            token = self.service._idle_token
            self.service.synthesize(request_body())
        self.service._expire(token)
        self.sd.reload_checkpoint.assert_not_called()

    def test_delete_clears_prompt(self):
        with patch.object(self.service, "_run_worker", return_value=AUDIO):
            self.service.synthesize(request_body())
        tag = "a" * 32
        epoch = self.service.list_voices(tag)["epoch"]
        self.service.delete_voices(tag, epoch, None)
        self.sd.reload_checkpoint.assert_called_once()

    def test_delete_during_generation(self):
        tag = "a" * 32
        epoch = self.service.list_voices(tag)["epoch"]
        def generate(*args):
            self.service.delete_voices(tag, epoch, None)
            return AUDIO
        with patch.object(self.service, "_run_worker", side_effect=generate):
            self.service.synthesize(request_body())
        self.sd.reload_checkpoint.assert_called_once()
        self.assertIsNone(self.service._timer)

    def test_delete_on_idle(self):
        tag = "a" * 32
        epoch = self.service.list_voices(tag)["epoch"]
        timer = Mock()
        timer.start.side_effect = lambda: self.service.delete_voices(tag, epoch, None)
        with patch.object(self.service, "_run_worker", return_value=AUDIO), \
             patch("generation_agent.tts.threading.Timer", return_value=timer):
            self.service.synthesize(request_body())
        self.sd.reload_checkpoint.assert_called_once()
        self.assertIsNone(self.service._timer)

    def test_invalid_config_rejected(self):
        for body in ('{"tts_backend":"unknown"}', '{"tts_keep_alive_seconds":-1}'):
            with tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / "config.json"
                path.write_text(body)
                with self.assertRaises(ValueError):
                    AgentConfig.load(path)


class ImageHandoffTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.service = GenerationService(AgentConfig.load(Path(temp.name) / "config.json"))
        self.service.start()
        self.addCleanup(self.service.close)

    def test_proxy_evicts_before_work(self):
        with patch.object(self.service._tts, "release_gpu") as release:
            with self.service.sd_operation("POST", "/sdapi/v1/txt2img"):
                release.assert_called_once()
                self.assertTrue(self.service._gpu_lock.locked())
        self.assertFalse(self.service._gpu_lock.locked())

    def test_busy_allows_cancel(self):
        with self.service._gpu_lock:
            with self.assertRaises(TtsBusyError):
                with self.service.sd_operation("POST", "/sdapi/v1/options"):
                    self.fail("must not reach SD")
            for method, path in (("GET", "/sdapi/v1/progress"), ("POST", "/sdapi/v1/interrupt")):
                with self.service.sd_operation(method, path):
                    pass

    def test_checkpoint_evicts(self):
        with patch.object(self.service._tts, "release_gpu") as release, \
             patch.object(self.service.model_imports, "set_active_checkpoint", return_value="model") as switch:
            self.assertEqual(self.service.set_checkpoint("model"), "model")
            release.assert_called_once()
            switch.assert_called_once_with("model")


if __name__ == "__main__":
    unittest.main()
