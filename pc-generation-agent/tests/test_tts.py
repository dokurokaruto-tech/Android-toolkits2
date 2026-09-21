from __future__ import annotations

import base64
import json
import subprocess
import sys
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from dataclasses import replace
from http import HTTPStatus
from pathlib import Path
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from generation_agent.config import AgentConfig
from generation_agent.server import AgentServer
from generation_agent.tts import (
    MAX_SAMPLE_BYTES, MAX_TEXT_LENGTH, TtsBusyError, TtsService,
    TtsUnavailableError, validate_request,
)
from generation_agent.tts_worker import CHUNK_CHARACTERS, split_text

AUDIO = b"RIFF" + bytes(80)


def request_body() -> dict:
    return {"text": "こんにちは。", "voices": [{
        "audio_base64": base64.b64encode(AUDIO).decode(), "ref_text": "サンプルです。",
    }]}


class TtsValidationTest(unittest.TestCase):
    def test_one_voice(self):
        self.assertEqual(validate_request(request_body()), ("こんにちは。", "サンプルです。", AUDIO))

    def test_transcript_optional(self):
        body = request_body()
        del body["voices"][0]["ref_text"]
        self.assertEqual(validate_request(body)[1], "")

    def test_multiple_even_identical_voices_rejected(self):
        body = request_body()
        body["voices"] *= 2
        with self.assertRaisesRegex(ValueError, "1つだけ"):
            validate_request(body)

    def test_missing_empty_or_malformed_voices(self):
        for voices in (None, [], {}, "voice", [None]):
            with self.subTest(voices=voices), self.assertRaises(ValueError):
                validate_request({"text": "こんにちは", "voices": voices})

    def test_text_limits_and_types(self):
        for text in (None, 1, "", "  ", "あ" * (MAX_TEXT_LENGTH + 1)):
            with self.subTest(text=str(text)[:30]), self.assertRaises(ValueError):
                validate_request(dict(request_body(), text=text))

    def test_invalid_and_oversized_sample(self):
        for encoded in (None, "", "not base64!", "あ", "A" * (MAX_SAMPLE_BYTES * 2)):
            with self.subTest(encoded=str(encoded)[:30]), self.assertRaises(ValueError):
                validate_request({"text": "Hello", "voices": [{"audio_base64": encoded}]})

    def test_chunking_preserves_text_and_bounds(self):
        text = "こんばんは。" + "長" * 700 + "！続きです。"
        chunks = split_text(text)
        self.assertEqual("".join(chunks), text)
        self.assertTrue(all(0 < len(part) <= CHUNK_CHARACTERS for part in chunks))


class TtsServiceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        model = self.root / "model"
        model.mkdir()
        (model / "config.json").write_text("{}")
        self.config = replace(AgentConfig.load(self.root / "config.json"), tts_model_dir=model)
        self.lock = threading.Lock()
        self.sd = Mock()
        self.sd.health.return_value = True
        self.service = TtsService(self.config, self.lock, self.sd)

    def test_not_configured(self):
        service = TtsService(replace(self.config, tts_model_dir=None), self.lock, self.sd)
        with self.assertRaises(TtsUnavailableError):
            service.synthesize(request_body())

    def test_missing_model(self):
        service = TtsService(replace(self.config, tts_model_dir=self.root / "missing"), self.lock, self.sd)
        with self.assertRaises(TtsUnavailableError):
            service.synthesize(request_body())

    @patch("generation_agent.tts.subprocess.run")
    def test_busy_never_starts_worker(self, run):
        with self.lock, self.assertRaises(TtsBusyError):
            self.service.synthesize(request_body())
        run.assert_not_called()
        self.sd.unload_checkpoint.assert_not_called()

    @patch("generation_agent.tts.subprocess.run")
    def test_conflict_validated_before_gpu(self, run):
        body = request_body()
        body["voices"] *= 2
        with self.assertRaises(ValueError):
            self.service.synthesize(body)
        run.assert_not_called()
        self.sd.health.assert_not_called()

    @patch("generation_agent.tts.subprocess.run")
    def test_success_and_cleanup(self, run):
        paths = []
        def worker(command, **kwargs):
            root = Path(command[-1]).parent
            paths.append(root)
            self.assertEqual((root / "reference.audio").read_bytes(), AUDIO)
            data = json.loads((root / "request.json").read_text(encoding="utf-8"))
            self.assertEqual(data["ref_text"], "サンプルです。")
            self.assertEqual(data["attention"], "auto")
            self.assertTrue(self.lock.locked())
            self.sd.unload_checkpoint.assert_called_once()
            (root / "speech.wav").write_bytes(AUDIO)
            return subprocess.CompletedProcess(command, 0, "", "")
        run.side_effect = worker
        self.assertEqual(self.service.synthesize(request_body()), AUDIO)
        self.sd.reload_checkpoint.assert_called_once()
        self.assertFalse(paths[0].exists())
        self.assertFalse(self.lock.locked())

    @patch("generation_agent.tts.subprocess.run")
    def test_timeout_releases_gpu_and_reloads_sd(self, run):
        paths = []
        def timeout(command, **kwargs):
            paths.append(Path(command[-1]).parent)
            raise subprocess.TimeoutExpired(command, 1)
        run.side_effect = timeout
        with self.assertRaisesRegex(RuntimeError, "制限時間"):
            self.service.synthesize(request_body())
        self.sd.reload_checkpoint.assert_called_once()
        self.assertFalse(self.lock.locked())
        self.assertFalse(paths[0].exists())

    @patch("generation_agent.tts.subprocess.run")
    def test_worker_error_propagated(self, run):
        def fail(command, **kwargs):
            (Path(command[-1]).parent / "error.txt").write_text("GPUメモリ不足", encoding="utf-8")
            return subprocess.CompletedProcess(command, 1, "", "failure")
        run.side_effect = fail
        with self.assertLogs(level="ERROR"), self.assertRaisesRegex(RuntimeError, "GPUメモリ不足"):
            self.service.synthesize(request_body())
        self.assertFalse(self.lock.locked())

    @patch("generation_agent.tts.subprocess.run")
    def test_sd_offline_still_runs_tts(self, run):
        self.sd.health.return_value = False
        def worker(command, **kwargs):
            (Path(command[-1]).parent / "speech.wav").write_bytes(AUDIO)
            return subprocess.CompletedProcess(command, 0, "", "")
        run.side_effect = worker
        self.assertEqual(self.service.synthesize(request_body()), AUDIO)
        self.sd.unload_checkpoint.assert_not_called()
        self.sd.reload_checkpoint.assert_not_called()

    def test_config_paths_are_relative_to_config(self):
        path = self.root / "config.json"
        path.write_text(json.dumps({"tts_model_dir": "model", "tts_python": ".venv-tts/python"}))
        loaded = AgentConfig.load(path)
        self.assertEqual(loaded.tts_model_dir, self.root / "model")
        self.assertEqual(loaded.tts_python, str(self.root / ".venv-tts/python"))


class TtsHttpTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        config = replace(AgentConfig.load(Path(self.temp.name) / "config.json"),
                         listen_host="127.0.0.1", listen_port=0, api_key="test-key")
        self.service = Mock()
        self.server = AgentServer(config, self.service)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base = f"http://127.0.0.1:{self.server.server_port}"

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()

    def post(self, body=None, key="test-key"):
        request = urllib.request.Request(self.base + "/api/v1/tts",
            data=json.dumps(body or request_body()).encode(),
            headers={"Content-Type": "application/json", "Authorization": "Bearer " + key})
        return urllib.request.urlopen(request, timeout=5)

    def test_authorization_before_synthesis(self):
        with self.assertRaises(urllib.error.HTTPError) as caught:
            self.post(key="wrong")
        self.assertEqual(caught.exception.code, HTTPStatus.UNAUTHORIZED)
        self.service.synthesize.assert_not_called()

    def test_wav_response_not_cached(self):
        self.service.synthesize.return_value = AUDIO
        with self.post() as response:
            self.assertEqual(response.status, HTTPStatus.OK)
            self.assertEqual(response.headers["Content-Type"], "audio/wav")
            self.assertEqual(response.headers["Cache-Control"], "no-store")
            self.assertEqual(response.read(), AUDIO)

    def test_error_mapping(self):
        for error, status in [(ValueError("bad"), HTTPStatus.BAD_REQUEST),
                              (TtsBusyError("busy"), HTTPStatus.CONFLICT),
                              (TtsUnavailableError("disabled"), HTTPStatus.SERVICE_UNAVAILABLE),
                              (RuntimeError("failed"), HTTPStatus.INTERNAL_SERVER_ERROR)]:
            self.service.synthesize.side_effect = error
            with self.subTest(error=error), self.assertRaises(urllib.error.HTTPError) as caught:
                self.post()
            self.assertEqual(caught.exception.code, status)
            self.assertEqual(json.load(caught.exception)["error"], str(error))


if __name__ == "__main__":
    unittest.main()
