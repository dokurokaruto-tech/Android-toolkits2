"""Bounded, opt-in TTS. Each request owns a short-lived GPU process."""
from __future__ import annotations

import base64
import binascii
import json
import logging
import subprocess
import sys
import tempfile
import threading
from pathlib import Path
from typing import Any

from .config import AgentConfig
from .sd_client import StableDiffusionClient

MAX_SAMPLE_BYTES = 6 * 1024 * 1024
MAX_TEXT_LENGTH = 2000
MAX_AUDIO_BYTES = 64 * 1024 * 1024


class TtsBusyError(RuntimeError):
    pass


class TtsUnavailableError(RuntimeError):
    pass


def validate_request(body: dict[str, Any]) -> tuple[str, str, bytes]:
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
    encoded = voice.get("audio_base64")
    if not isinstance(encoded, str) or len(encoded) > ((MAX_SAMPLE_BYTES + 2) // 3) * 4:
        raise ValueError("サンプル音声は6 MiB以下にしてください。")
    try:
        audio = base64.b64decode(encoded, validate=True)
    except (ValueError, binascii.Error) as error:
        raise ValueError("サンプル音声のデータが不正です。") from error
    if not audio or len(audio) > MAX_SAMPLE_BYTES:
        raise ValueError("サンプル音声が空か、6 MiBを超えています。")
    return text.strip(), transcript.strip(), audio


class TtsService:
    def __init__(self, config: AgentConfig, gpu_lock: threading.Lock, sd: StableDiffusionClient):
        self._config = config
        self._gpu_lock = gpu_lock
        self._sd = sd

    def synthesize(self, body: dict[str, Any]) -> bytes:
        text, transcript, audio = validate_request(body)
        model = self._config.tts_model_dir
        if model is None:
            raise TtsUnavailableError("PCのconfig.jsonにtts_model_dirを設定してください。")
        if not (model / "config.json").is_file():
            raise TtsUnavailableError("tts_model_dirにモデルのconfig.jsonがありません。")
        if not self._gpu_lock.acquire(blocking=False):
            raise TtsBusyError("PCで画像または音声を生成中です。完了後に再試行してください。")
        unloaded = False
        try:
            if self._config.tts_unload_sd and self._sd.health():
                self._sd.unload_checkpoint()
                unloaded = True
            return self._run_worker(model, text, transcript, audio)
        finally:
            try:
                if unloaded:
                    self._sd.reload_checkpoint()
            except Exception:
                logging.exception("Could not reload SD checkpoint after TTS")
            finally:
                self._gpu_lock.release()

    def _run_worker(self, model: Path, text: str, transcript: str, audio: bytes) -> bytes:
        # Neither reference audio nor generated speech is retained on the PC.
        with tempfile.TemporaryDirectory(prefix="toolkits-tts-") as directory:
            root = Path(directory)
            (root / "reference.audio").write_bytes(audio)
            request = root / "request.json"
            request.write_text(json.dumps({
                "model_dir": str(model), "text": text, "ref_text": transcript,
            }, ensure_ascii=False), encoding="utf-8")
            command = [
                self._config.tts_python or sys.executable,
                str(Path(__file__).with_name("tts_worker.py")), str(request),
            ]
            try:
                result = subprocess.run(
                    command, capture_output=True, text=True, encoding="utf-8", errors="replace",
                    timeout=self._config.tts_timeout_seconds, check=False,
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
            output = root / "speech.wav"
            if not output.is_file() or not 44 < output.stat().st_size <= MAX_AUDIO_BYTES:
                raise RuntimeError("TTSから有効な音声が返りませんでした。")
            return output.read_bytes()
