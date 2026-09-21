from __future__ import annotations

import base64
import hashlib
import json
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
from generation_agent.service import GenerationService
from generation_agent.tts import TtsService, validate_request
from generation_agent.voice_store import VoiceStore, VoiceChangedError, VoiceMissingError

TAG = "a" * 32
OTHER_TAG = "b" * 32
AUDIO = b"sample audio bytes"
SAMPLE = hashlib.sha256(AUDIO).hexdigest()


def upload(epoch, audio=AUDIO):
    return {"epoch": epoch, "sample_id": hashlib.sha256(audio).hexdigest(),
            "tag_name": "キャラ", "name": "sample.m4a", "audio_base64": base64.b64encode(audio).decode()}


class VoiceStoreTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.path = Path(temporary.name) / "voices.sqlite3"
        self.store = VoiceStore(self.path)
        self.epoch = self.store.list_samples(TAG)["epoch"]

    def test_save_and_restart(self):
        self.store.put_sample(TAG, upload(self.epoch))
        restored = VoiceStore(self.path)
        self.assertEqual(restored.read_sample(TAG, SAMPLE), AUDIO)
        listing = restored.list_samples(TAG)
        self.assertEqual(listing["epoch"], self.epoch)
        self.assertEqual(listing["samples"][0]["tag_name"], "キャラ")
        self.assertNotIn("audio", listing["samples"][0])

    def test_duplicate_is_idempotent(self):
        for _ in range(2):
            self.store.put_sample(TAG, upload(self.epoch))
        self.assertEqual(len(self.store.list_samples(TAG)["samples"]), 1)

    def test_tag_isolation(self):
        self.store.put_sample(TAG, upload(self.epoch))
        other_epoch = self.store.list_samples(OTHER_TAG)["epoch"]
        self.store.put_sample(OTHER_TAG, upload(other_epoch))
        self.store.delete_samples(TAG, self.epoch)
        with self.assertRaises(VoiceMissingError):
            self.store.read_sample(TAG, SAMPLE)
        self.assertEqual(self.store.read_sample(OTHER_TAG, SAMPLE), AUDIO)

    def test_exact_version_and_delete(self):
        self.store.put_sample(TAG, upload(self.epoch))
        updated = b"new voice audio"
        second = upload(self.epoch, updated)
        self.store.put_sample(TAG, second)
        self.assertEqual(self.store.read_sample(TAG, SAMPLE), AUDIO)
        result = self.store.delete_samples(TAG, self.epoch, SAMPLE)
        self.assertEqual(result["deleted"], 1)
        self.assertEqual(self.store.read_sample(TAG, second["sample_id"]), updated)
        with self.assertRaises(VoiceChangedError):
            self.store.put_sample(TAG, upload(self.epoch))
        self.assertEqual(self.store.delete_samples(TAG, result["epoch"])["deleted"], 1)

    def test_invalid_data_rejected(self):
        for body in (dict(upload(self.epoch), sample_id="../outside"),
                     dict(upload(self.epoch), sample_id="0" * 64),
                     dict(upload(self.epoch), audio_base64="invalid!!"),
                     dict(upload(self.epoch), sample_id=None)):
            with self.subTest(body=body), self.assertRaises(ValueError):
                self.store.put_sample(TAG, body)
        self.assertEqual(self.store.list_samples(TAG)["samples"], [])

    def test_ids_cannot_be_paths(self):
        with self.assertRaises(ValueError):
            self.store.list_samples("../outside")
        with self.assertRaises(ValueError):
            self.store.delete_samples(TAG, self.epoch, "../outside")

    def test_quota_and_existing(self):
        self.store.put_sample(TAG, upload(self.epoch))
        with patch("generation_agent.voice_store._MAX_STORE_BYTES", len(AUDIO)):
            self.store.put_sample(TAG, upload(self.epoch))
            with self.assertRaises(ValueError):
                self.store.put_sample(TAG, upload(self.epoch, b"additional"))

    def test_tag_version_limit(self):
        self.store.put_sample(TAG, upload(self.epoch))
        with patch("generation_agent.voice_store._MAX_TAG_SAMPLES", 1):
            self.store.put_sample(TAG, upload(self.epoch))
            with self.assertRaises(ValueError):
                self.store.put_sample(TAG, upload(self.epoch, b"additional"))

    def test_delete_reclaims_bytes(self):
        private = b"sensitive voice sample abc123" * 1000
        self.store.put_sample(TAG, upload(self.epoch, private))
        self.store.delete_samples(TAG, self.epoch)
        self.assertNotIn(private, self.path.read_bytes())

    def test_concurrent_duplicate(self):
        errors = []
        def put():
            try:
                self.store.put_sample(TAG, upload(self.epoch))
            except Exception as error:
                errors.append(error)
        threads = [threading.Thread(target=put) for _ in range(4)]
        for thread in threads: thread.start()
        for thread in threads: thread.join()
        self.assertEqual(errors, [])
        self.assertEqual(len(self.store.list_samples(TAG)["samples"]), 1)


class CachedTtsTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        model = root / "model"
        model.mkdir()
        (model / "config.json").write_text("{}")
        self.config = replace(AgentConfig.load(root / "config.json"), tts_model_dir=model, tts_unload_sd=False)
        self.service = TtsService(self.config, threading.Lock(), Mock())
        epoch = self.service.list_voices(TAG)["epoch"]
        self.service.store_voice(TAG, upload(epoch))
        self.request = {"text": "こんにちは", "voices": [{"tag_id": TAG, "sample_id": SAMPLE, "ref_text": "sample"}]}

    def test_synthesis_without_upload(self):
        with patch.object(self.service, "_run_worker", return_value=b"WAV") as worker:
            self.assertEqual(self.service.synthesize(self.request), b"WAV")
        worker.assert_called_once_with(self.config.tts_model_dir, "こんにちは", "sample", AUDIO)

    def test_conflict_before_worker(self):
        self.request["voices"] *= 2
        with patch.object(self.service, "_run_worker") as worker, self.assertRaises(ValueError):
            self.service.synthesize(self.request)
        worker.assert_not_called()

    def test_missing_does_not_fallback(self):
        self.request["voices"][0]["sample_id"] = "0" * 64
        with self.assertRaises(VoiceMissingError):
            self.service.synthesize(self.request)

    def test_null_sample_id_rejected(self):
        self.request["voices"][0]["sample_id"] = None
        with self.assertRaises(ValueError):
            validate_request(self.request)

    def test_ambiguous_input_rejected(self):
        self.request["voices"][0]["audio_base64"] = base64.b64encode(AUDIO).decode()
        with self.assertRaises(ValueError):
            validate_request(self.request)


class VoiceApiTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        config = replace(AgentConfig.load(root / "config.json"), listen_host="127.0.0.1", listen_port=0, api_key="test-key")
        self.service = GenerationService(config)
        self.service.start()
        self.server = AgentServer(config, self.service)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base = f"http://127.0.0.1:{self.server.server_port}"
        self.path = f"/api/v1/tts/voices/{TAG}"

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        self.service.close()

    def request(self, method, path, body=None, key="test-key"):
        data = json.dumps(body).encode() if body is not None else None
        request = urllib.request.Request(self.base + path, data=data, method=method,
            headers={"Authorization": "Bearer " + key, "Content-Type": "application/json"})
        with urllib.request.urlopen(request, timeout=5) as response:
            return json.load(response)

    def test_full_lifecycle(self):
        status = self.request("GET", self.path)
        self.request("POST", self.path, upload(status["epoch"]))
        saved = self.request("GET", self.path)
        self.assertEqual(saved["samples"][0]["sample_id"], SAMPLE)
        deleted = self.request("DELETE", self.path + "?epoch=" + saved["epoch"] + "&sample_id=" + SAMPLE)
        self.assertEqual(deleted["deleted"], 1)
        self.assertEqual(self.request("GET", self.path)["samples"], [])
        with self.assertRaises(urllib.error.HTTPError) as caught:
            self.request("POST", self.path, upload(status["epoch"]))
        self.assertEqual(caught.exception.code, HTTPStatus.CONFLICT)

    def test_empty_delete_id(self):
        epoch = self.request("GET", self.path)["epoch"]
        self.request("POST", self.path, upload(epoch))
        with self.assertRaises(urllib.error.HTTPError) as caught:
            self.request("DELETE", self.path + "?epoch=" + epoch + "&sample_id=")
        self.assertEqual(caught.exception.code, HTTPStatus.BAD_REQUEST)
        self.assertEqual(len(self.request("GET", self.path)["samples"]), 1)

    def test_auth_on_every_operation(self):
        for method in ("GET", "POST", "DELETE"):
            with self.subTest(method=method), self.assertRaises(urllib.error.HTTPError) as caught:
                self.request(method, self.path, {} if method == "POST" else None, key="wrong")
            self.assertEqual(caught.exception.code, HTTPStatus.UNAUTHORIZED)
        self.assertEqual(self.request("GET", self.path)["samples"], [])


if __name__ == "__main__":
    unittest.main()
