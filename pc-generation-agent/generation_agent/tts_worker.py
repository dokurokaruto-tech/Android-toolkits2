"""Executed with the optional Qwen virtualenv; process exit releases all VRAM."""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

# This file is also launched directly with the separate TTS interpreter.
if not __package__:
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from generation_agent.sox_runtime import configure_sox
from generation_agent.reference_audio import decode_reference

CHUNK_CHARACTERS = 200
MAX_NEW_TOKENS = 2048
MAX_REFERENCE_SECONDS = 30
MAX_SAMPLE_RATE = 192000
CHUNK_PAUSE_SECONDS = 0.15


def split_text(text: str) -> list[str]:
    chunks: list[str] = []
    current = ""
    for sentence in re.split(r"(?<=[。！？.!?\n])", text):
        if len(current) + len(sentence) > CHUNK_CHARACTERS:
            if current.strip():
                chunks.append(current.strip())
            current = ""
        while len(sentence) > CHUNK_CHARACTERS:
            chunks.append(sentence[:CHUNK_CHARACTERS])
            sentence = sentence[CHUNK_CHARACTERS:]
        current += sentence
    if current.strip():
        chunks.append(current.strip())
    return chunks


def generate(request: Path) -> None:
    configure_sox()
    decode_reference(request.parent / "reference.audio", request.parent / "reference.wav")
    import numpy as np
    import soundfile as sf
    import torch
    from qwen_tts import Qwen3TTSModel

    body = json.loads(request.read_text(encoding="utf-8"))
    root = request.parent
    try:
        with sf.SoundFile(root / "reference.wav") as source:
            if (not 1 <= source.frames / source.samplerate <= MAX_REFERENCE_SECONDS
                    or source.channels > 2 or source.samplerate > MAX_SAMPLE_RATE):
                raise ValueError("サンプルは1〜30秒、192kHz以下のモノラル／ステレオ音声にしてください。")
            sample_rate = source.samplerate
            reference = source.read(dtype="float32", always_2d=True).mean(axis=1)
    except sf.LibsndfileError as error:
        raise ValueError("音声を読み込めません。WAV・FLAC・MP3・M4Aを使用してください。") from error
    if not np.isfinite(reference).all() or np.max(np.abs(reference)) < 1e-5:
        raise ValueError("サンプル音声が無音か不正です。")
    if not torch.cuda.is_available():
        raise RuntimeError("CUDA版PyTorchとNVIDIAドライバーを確認してください。")
    # Turing (RTX 2070): no BF16 or FlashAttention 2 requirement.
    model = Qwen3TTSModel.from_pretrained(
        body["model_dir"], device_map="cuda:0", dtype=torch.float16,
        attn_implementation="sdpa", local_files_only=True,
    )
    if getattr(model.model, "tts_model_type", None) != "base":
        raise ValueError("Qwen3-TTSのBaseモデルを指定してください。")
    transcript = body["ref_text"]
    with torch.inference_mode():
        prompt = model.create_voice_clone_prompt(
            ref_audio=(reference, sample_rate), ref_text=transcript or None,
            x_vector_only_mode=not bool(transcript),
        )
        audio = []
        for chunk in split_text(body["text"]):
            waves, rate = model.generate_voice_clone(
                text=chunk, language="Auto", voice_clone_prompt=prompt,
                max_new_tokens=MAX_NEW_TOKENS,
            )
            wave = np.asarray(waves[0], dtype=np.float32)
            if wave.size == 0 or not np.isfinite(wave).all():
                raise RuntimeError("音声生成に失敗しました。サンプルを変更して再試行してください。")
            audio.extend([wave, np.zeros(int(rate * CHUNK_PAUSE_SECONDS), dtype=np.float32)])
    sf.write(root / "speech.wav", np.concatenate(audio), rate, subtype="PCM_16")


if __name__ == "__main__":
    request_path = Path(sys.argv[1])
    try:
        generate(request_path)
    except Exception as error:
        message = str(error)
        if "out of memory" in message.lower():
            message = "GPUメモリ不足です。Forgeなど他のGPUアプリを停止し、本文・サンプルを短くしてください。"
        (request_path.parent / "error.txt").write_text(message[:1000], encoding="utf-8")
        raise
