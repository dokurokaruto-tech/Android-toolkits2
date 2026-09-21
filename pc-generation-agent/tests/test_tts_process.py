from __future__ import annotations

import json
import sys
import tempfile
import threading
import unittest
from dataclasses import replace
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from generation_agent.config import AgentConfig
from generation_agent.tts_process import TtsProcess

# Exercise real IPC, reuse and termination without importing CUDA or Qwen.
FAKE_WORKER = r'''
import json, os, sys, time
from pathlib import Path
root = Path(sys.argv[2])
while root.exists():
    request = root / "request.json"
    if not request.exists():
        time.sleep(0.005)
        continue
    body = json.loads(request.read_text())
    request.unlink()
    if body.get("mode") == "hang":
        time.sleep(60)
    if body.get("mode") == "crash":
        raise SystemExit(7)
    if body.get("mode") == "fail":
        result = {"ok":False, "error":"fake model error"}
    else:
        value = str(os.getpid()).encode() + b":" + (root / "reference.audio").read_bytes()
        (root / "speech.wav").write_bytes(value)
        result = {"ok":True, "metrics":{"model_reused":True}}
    (root / "done.tmp").write_text(json.dumps(result))
    (root / "done.tmp").replace(root / "done.json")
    if body.get("mode") == "fail":
        break
'''


class ProcessTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        root = Path(temp.name)
        self.script = root / "fake.py"
        self.script.write_text(FAKE_WORKER)
        self.config = replace(AgentConfig.load(root / "config.json"), tts_python=sys.executable,
                              tts_timeout_seconds=2)
        self.driver = TtsProcess(self.config)
        self.addCleanup(self.driver.close)
        patched = patch("generation_agent.tts_process._WORKER_PATH", self.script)
        patched.start()
        self.addCleanup(patched.stop)
        self.audio = b"reference" * 20

    def test_pid_and_fresh_audio(self):
        first = self.driver.synthesize({}, self.audio)
        second = self.driver.synthesize({}, b"changed" * 20)
        self.assertEqual(first.split(b":")[0], second.split(b":")[0])
        self.assertTrue(second.endswith(b"changed" * 20))
        root = Path(self.driver._directory.name)
        for name in ("reference.audio", "speech.wav", "done.json"):
            self.assertFalse((root / name).exists())

    def test_stop_and_restart(self):
        first = self.driver.synthesize({}, self.audio)
        root = Path(self.driver._directory.name)
        process = self.driver._process
        self.driver.stop()
        self.assertIsNotNone(process.poll())
        self.assertFalse(root.exists())
        second = self.driver.synthesize({}, self.audio)
        self.assertNotEqual(first.split(b":")[0], second.split(b":")[0])

    def test_timeout_kills_worker(self):
        self.driver = TtsProcess(replace(self.config, tts_timeout_seconds=0.15))
        self.addCleanup(self.driver.close)
        with self.assertRaisesRegex(RuntimeError, "制限時間"):
            self.driver.synthesize({"mode":"hang"}, self.audio)
        self.assertIsNone(self.driver._process)
        self.assertIsNone(self.driver._directory)

    def test_error_not_reused(self):
        with self.assertRaisesRegex(RuntimeError, "fake model error"):
            self.driver.synthesize({"mode":"fail"}, self.audio)
        self.assertIsNone(self.driver._process)
        self.assertTrue(self.driver.synthesize({}, self.audio).endswith(self.audio))

    def test_crash_detected(self):
        with self.assertRaisesRegex(RuntimeError, "終了"):
            self.driver.synthesize({"mode":"crash"}, self.audio)
        self.assertIsNone(self.driver._process)

    def test_worker_has_idle_exit(self):
        from generation_agent.tts_worker import serve
        with tempfile.TemporaryDirectory() as directory, \
             patch("generation_agent.tts_worker.configure_sox"), \
             patch("generation_agent.tts_worker.TtsEngine") as engine:
            serve(Path(directory), 0.01, 1)
        engine.return_value.synthesize.assert_not_called()

    def test_close_prevents_restart(self):
        self.driver.close()
        with self.assertRaises(RuntimeError):
            self.driver.synthesize({}, self.audio)

    def test_close_aborts_active(self):
        entered = threading.Event()
        actual_start = self.driver._start
        def start():
            root = actual_start()
            entered.set()
            return root
        errors = []
        def synthesize():
            try:
                self.driver.synthesize({"mode":"hang"}, self.audio)
            except Exception as error:
                errors.append(error)
        with patch.object(self.driver, "_start", side_effect=start):
            thread = threading.Thread(target=synthesize)
            thread.start()
            self.assertTrue(entered.wait(2))
            self.driver.close()
            thread.join(3)
        self.assertFalse(thread.is_alive())
        self.assertTrue(errors)


if __name__ == "__main__":
    unittest.main()
