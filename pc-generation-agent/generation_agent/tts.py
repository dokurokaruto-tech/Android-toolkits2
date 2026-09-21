"""Bounded TTS with optional warm-worker reuse under the image GPU lock."""
from __future__ import annotations

import json
import logging
import os
import time
import subprocess
import sys
import tempfile
import threading
from pathlib import Path
from typing import Any

from .config import AgentConfig
from .sd_client import StableDiffusionClient
from .tts_process import TtsProcess
from .tts_options import MAX_AUDIO_BYTES
from .voice_store import VoiceStore, check_ids, decode_sample, MAX_SAMPLE_BYTES

MAX_TEXT_LENGTH = 2000


class TtsBusyError(RuntimeError):
    pass


class TtsUnavailableError(RuntimeError):
    pass


def validate_request(body: dict[str, Any]) -> tuple[str, str, bytes | tuple[str, str]]:
    voices = body.get("voices")
    if not isinstance(voices, list) or len(voices) != 1:
        raise ValueError("音声付きタグは1つだけ必要です。複数のタグの声は混合できません。")
    voice = voices[0]
    if not isinstance(voice, dict):
        raise ValueError("invalid voice")
    text = body.get("text")
    if not isinstance(text, str) or not 1 <= len(text.strip()) <= MAX_TEXT_LENGTH:
        raise ValueError(f"読み上げ本文は1〜{MAX_TEXT_LENGTH}文字にしてください。")
    transcript = voice.get("ref_text", "")
    if not isinstance(transcript, str) or len(transcript) > MAX_TEXT_LENGTH:
        raise ValueError("サンプルの文字起こしが長すぎます。")
    if "tag_id" in voice or "sample_id" in voice:
        check_ids(voice.get("tag_id"), voice.get("sample_id") or "")
        if "audio_base64" in voice:
            raise ValueError("音声データと保存音声IDは同時に指定できません。")
        return text.strip(), transcript.strip(), (voice["tag_id"], voice["sample_id"])
    return text.strip(), transcript.strip(), decode_sample(voice.get("audio_base64"))



class TtsService:
    def __init__(self, config: AgentConfig, gpu_lock: threading.Lock, sd: StableDiffusionClient):
        self._config = config
        self._gpu_lock = gpu_lock
        self._sd = sd
        self._voices = VoiceStore(config.database_path.parent / "tts-voices.sqlite3")
        self._process = TtsProcess(config)
        self._timer: threading.Timer | None = None
        self._sd_unloaded = False
        self._invalidated = threading.Event()
        self._closed = False
        self._idle_token = None

    def list_voices(self, tag_id: str) -> dict[str, Any]:
        return self._voices.list_samples(tag_id)

    def store_voice(self, tag_id: str, body: dict[str, Any]) -> dict[str, Any]:
        return self._voices.put_sample(tag_id, body)

    def delete_voices(self, tag_id: str, epoch: str, sample_id: str | None) -> dict[str, Any]:
        result = self._voices.delete_samples(tag_id, epoch, sample_id)
        # Forget derived prompts too; a running request is allowed to finish.
        self._invalidated.set()
        if self._gpu_lock.acquire(blocking=False):
            try:
                self.release_gpu()
            finally:
                self._gpu_lock.release()
        return result

    def synthesize(self, body: dict[str, Any]) -> bytes:
        text, transcript, audio = validate_request(body)
        if isinstance(audio, tuple):
            audio = self._voices.read_sample(*audio)
        model = self._config.tts_model_dir
        if model is None:
            raise TtsUnavailableError("PCのconfig.jsonにtts_model_dirを設定してください。")
        if not (model / "config.json").is_file():
            raise TtsUnavailableError("tts_model_dirにモデルのconfig.jsonがありません。")
        if not self._gpu_lock.acquire(blocking=False):
            raise TtsBusyError("PCで画像または音声を生成中です。完了後に再試行してください。")
        started = time.monotonic()
        success = False
        self._cancel_timer()
        try:
            if self._closed:
                raise RuntimeError("TTSエージェントを終了中です。")
            if self._config.tts_unload_sd and not self._sd_unloaded and self._sd.health():
                unload_start = time.monotonic()
                self._sd.unload_checkpoint()
                self._sd_unloaded = True
                logging.warning("[TTS timing] sd_unload=%.2fs", time.monotonic() - unload_start)
            result = self._run_worker(model, text, transcript, audio)
            success = True
            return result
        finally:
            try:
                if not success or not self._config.tts_keep_alive_seconds or self._invalidated.is_set() or self._closed:
                    self.release_gpu()
                else:
                    self._idle_token = object()
                    self._timer = threading.Timer(self._config.tts_keep_alive_seconds, self._expire, (self._idle_token,))
                    self._timer.daemon = True
                    self._timer.start()
            finally:
                logging.warning("[TTS timing] request_total=%.2fs", time.monotonic() - started)
                self._gpu_lock.release()
                self._clear_invalidated()

    def _clear_invalidated(self) -> None:
        if not self._invalidated.is_set() or not self._gpu_lock.acquire(blocking=False):
            return
        try:
            if self._invalidated.is_set():
                self.release_gpu()
        finally:
            self._gpu_lock.release()

    def _cancel_timer(self) -> None:
        self._idle_token = None
        if self._timer is not None:
            self._timer.cancel()
            self._timer = None

    def _expire(self, token=None) -> None:
        if not self._gpu_lock.acquire(blocking=False):
            return
        try:
            if token is not None and token is not self._idle_token:
                return
            self.release_gpu()
        finally:
            self._gpu_lock.release()

    def release_gpu(self) -> None:
        """Caller must own the shared GPU lock before any SD GPU operation."""
        self._cancel_timer()
        self._process.stop()
        self._invalidated.clear()
        if not self._sd_unloaded:
            return
        self._sd_unloaded = False
        started = time.monotonic()
        try:
            self._sd.reload_checkpoint()
        except Exception:
            logging.exception("Could not reload SD checkpoint after TTS")
        finally:
            logging.warning("[TTS timing] sd_restore=%.2fs", time.monotonic() - started)

    def close(self) -> None:
        self._closed = True
        self._cancel_timer()
        self._process.close()

    def _run_worker(self, model: Path, text: str, transcript: str, audio: bytes) -> bytes:
        body = {"model_dir": str(model), "text": text, "ref_text": transcript,
                "backend": self._config.tts_backend.value, "attention": self._config.tts_attention.value}
        if self._config.tts_keep_alive_seconds:
            return self._process.synthesize(body, audio)
        # Single-shot mode remains available for externally managed VRAM.
        with tempfile.TemporaryDirectory(prefix="toolkits-tts-") as directory:
            root = Path(directory)
            (root / "reference.audio").write_bytes(audio)
            request = root / "request.json"
            request.write_text(json.dumps(body, ensure_ascii=False), encoding="utf-8")
            command = [
                self._config.tts_python or sys.executable,
                str(Path(__file__).with_name("tts_worker.py")), str(request),
            ]
            try:
                result = subprocess.run(
                    command, capture_output=True, text=True, encoding="utf-8", errors="replace",
                    timeout=self._config.tts_timeout_seconds, check=False,
                    env=dict(os.environ, HF_HUB_OFFLINE="1", TRANSFORMERS_OFFLINE="1"),
                )
            except subprocess.TimeoutExpired as error:
                raise RuntimeError("TTSが制限時間を超えました。本文を短くしてください。") from error
            error_file = root / "error.txt"
            if result.returncode != 0:
                detail = error_file.read_text(encoding="utf-8") if error_file.exists() else (
                    "TTS用Pythonと依存パッケージを確認してください。"
                )
                logging.error("TTS worker failed: %s", result.stderr[-4000:])
                raise RuntimeError(detail)
            metrics = root / "metrics.json"
            if metrics.exists():
                logging.warning("[TTS timing] %s", metrics.read_text(encoding="utf-8")[:4000])
            output = root / "speech.wav"
            if not output.is_file() or not 44 < output.stat().st_size <= MAX_AUDIO_BYTES:
                raise RuntimeError("TTSから有効な音声が返りませんでした。")
            return output.read_bytes()
