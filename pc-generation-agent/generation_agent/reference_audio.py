"""Decode uploaded audio locally; FFmpeg is supplied by imageio-ffmpeg wheels."""
from __future__ import annotations

import subprocess
import wave
from pathlib import Path

REFERENCE_RATE = 24000
MAX_REFERENCE_SECONDS = 30
_DECODE_TIMEOUT_SECONDS = 60


def ffmpeg_executable() -> str:
    import imageio_ffmpeg
    return imageio_ffmpeg.get_ffmpeg_exe()


def decode_reference(source: Path, destination: Path) -> None:
    command = [
        ffmpeg_executable(), "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
        "-protocol_whitelist", "file,pipe", "-format_whitelist", "wav,flac,mp3,mov",
        "-i", str(source), "-map", "0:a:0", "-vn", "-ac", "1", "-ar", str(REFERENCE_RATE),
        "-t", str(MAX_REFERENCE_SECONDS + 1), "-c:a", "pcm_s16le", "-f", "wav", str(destination),
    ]
    try:
        result = subprocess.run(command, capture_output=True, timeout=_DECODE_TIMEOUT_SECONDS, check=False)
    except subprocess.TimeoutExpired as error:
        raise ValueError("サンプル音声の変換がタイムアウトしました。") from error
    if result.returncode != 0:
        destination.unlink(missing_ok=True)
        raise ValueError("音声を読み込めません。WAV・FLAC・MP3・M4A（AAC/ALAC）を使用してください。")
    with wave.open(str(destination), "rb") as audio:
        duration = audio.getnframes() / audio.getframerate()
    if not 1 <= duration <= MAX_REFERENCE_SECONDS:
        destination.unlink(missing_ok=True)
        raise ValueError("サンプル音声は1〜30秒にしてください。")
