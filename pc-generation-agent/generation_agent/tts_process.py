"""One private, serialized worker. No network listener and no pickle IPC."""
from __future__ import annotations

import json
import logging
import os
import signal
import subprocess
import sys
import tempfile
import threading
import time
from pathlib import Path

from .config import AgentConfig
from .tts_options import MAX_AUDIO_BYTES

_WORKER_PATH = Path(__file__).with_name("tts_worker.py")
_POLL_SECONDS = 0.05
_STOP_SECONDS = 5
_ORPHAN_IDLE_MARGIN = 30


class TtsProcess:
    def __init__(self, config: AgentConfig):
        self._config = config
        self._lock = threading.RLock()
        self._process = None
        self._directory = None
        self._log = None
        self._closed = False

    def _start(self) -> Path:
        with self._lock:
            if self._closed:
                raise RuntimeError("TTSエージェントを終了中です。")
            if self._process is not None and self._process.poll() is None:
                return Path(self._directory.name)
            self.stop()
            self._directory = tempfile.TemporaryDirectory(prefix="toolkits-tts-warm-")
            root = Path(self._directory.name)
            self._log = (root / "worker.log").open("w+b")
            environment = dict(os.environ, HF_HUB_OFFLINE="1", TRANSFORMERS_OFFLINE="1",
                               HF_HUB_DISABLE_PROGRESS_BARS="1", TQDM_DISABLE="1", PYTHONUNBUFFERED="1")
            try:
                self._process = subprocess.Popen(
                    [self._config.tts_python or sys.executable, str(_WORKER_PATH), "--serve", str(root),
                     str(self._config.tts_keep_alive_seconds + _ORPHAN_IDLE_MARGIN),
                     str(self._config.tts_timeout_seconds)],
                    stdin=subprocess.DEVNULL, stdout=self._log, stderr=self._log, env=environment,
                    start_new_session=os.name != "nt",
                )
            except Exception:
                self.stop()
                raise
            return root

    def synthesize(self, body: dict, audio: bytes) -> bytes:
        # Caller owns the GPU lock. close() can still abort a blocked request.
        root = self._start()
        for name in ("done.json", "error.txt", "speech.wav", "metrics.json"):
            (root / name).unlink(missing_ok=True)
        (root / "reference.audio").write_bytes(audio)
        temporary = root / "request.tmp"
        temporary.write_text(json.dumps(body, ensure_ascii=False), encoding="utf-8")
        temporary.replace(root / "request.json")
        deadline = time.monotonic() + self._config.tts_timeout_seconds
        try:
            while not (root / "done.json").exists():
                with self._lock:
                    if self._process is None or self._process.poll() is not None:
                        if (root / "done.json").exists():
                            break
                        raise RuntimeError("TTSワーカーが終了しました。PCログを確認してください。")
                if time.monotonic() >= deadline:
                    raise RuntimeError("TTSが制限時間を超えました。本文を短くしてください。")
                time.sleep(_POLL_SECONDS)
            result = json.loads((root / "done.json").read_text(encoding="utf-8"))
            if not result.get("ok"):
                raise RuntimeError(str(result.get("error", "TTS生成に失敗しました。"))[:1000])
            output = root / "speech.wav"
            if not output.is_file() or not 44 < output.stat().st_size <= MAX_AUDIO_BYTES:
                raise RuntimeError("TTSから有効な音声が返りませんでした。")
            metrics = result.get("metrics", {})
            logging.warning("[TTS timing] %s", json.dumps(metrics, ensure_ascii=False))
            return output.read_bytes()
        except Exception:
            self._log_tail()
            self.stop()
            raise
        finally:
            # Idle model/prompt cache needs no original audio or generated WAV on disk.
            for name in ("reference.audio", "reference.wav", "speech.wav", "done.json", "metrics.json"):
                (root / name).unlink(missing_ok=True)
            with self._lock:
                if self._log is not None:
                    self._log.seek(0)
                    self._log.truncate()

    def _log_tail(self) -> None:
        with self._lock:
            if self._log is None:
                return
            self._log.seek(0, os.SEEK_END)
            self._log.seek(max(0, self._log.tell() - 4000))
            logging.error("TTS worker failed: %s", self._log.read().decode("utf-8", errors="replace"))

    def stop(self) -> None:
        with self._lock:
            process = self._process
            if process is not None:
                if process.poll() is None:
                    try:
                        if os.name == "nt":
                            # Include a decoder child if cancellation occurs during FFmpeg conversion.
                            subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"],
                                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                           timeout=_STOP_SECONDS, check=False)
                        else:
                            os.killpg(process.pid, signal.SIGTERM)
                    except (OSError, subprocess.TimeoutExpired):
                        if process.poll() is None:
                            process.terminate()
                    try:
                        process.wait(timeout=_STOP_SECONDS)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=_STOP_SECONDS)
                self._process = None
            if self._log is not None:
                self._log.close()
                self._log = None
            if self._directory is not None:
                self._directory.cleanup()
                self._directory = None

    def close(self) -> None:
        with self._lock:
            self._closed = True
            self.stop()
