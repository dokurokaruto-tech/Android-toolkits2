"""Optional Qwen runtimes. Imports and tensors stay inside the TTS process."""
from __future__ import annotations

import hashlib
import importlib.metadata
import time
from collections import OrderedDict
from pathlib import Path

from .reference_audio import decode_reference
from .qwen_runtime import import_qwen
from .tts_attention import prepare_attention
from .tts_options import (TtsBackend, TtsAttention, FAST_PACKAGE_VERSION, MAX_NEW_TOKENS,
                          GRAPH_SEQUENCE_LENGTH, GRAPH_PROMPT_RESERVE)

_MAX_PROMPTS = 2
_CHUNK_PAUSE_SECONDS = 0.15


def check_graph_budget(text: str, transcript: str) -> None:
    # UTF-8 bytes bound byte-level BPE tokens conservatively. Reserve codec/control tokens.
    upper_bound = len((text + transcript).encode("utf-8")) + GRAPH_PROMPT_RESERVE + MAX_NEW_TOKENS
    if upper_bound >= GRAPH_SEQUENCE_LENGTH:
        raise ValueError("CUDA Graphの入力上限です。文字起こしを短くするか、標準モードを使用してください。")


class TtsEngine:
    def __init__(self):
        self._model = None
        self._base = None
        self._key = None
        self._prompts = OrderedDict()
        self._attention = {}

    def _load(self, directory: str, backend: TtsBackend, attention: TtsAttention) -> None:
        import torch
        if not torch.cuda.is_available():
            raise RuntimeError("CUDA版PyTorchとNVIDIAドライバーを確認してください。")
        Qwen3TTSModel = import_qwen()
        self._attention = prepare_attention(Path(directory), attention)
        if backend == TtsBackend.CUDA_GRAPH:
            try:
                version = importlib.metadata.version("faster-qwen3-tts")
            except importlib.metadata.PackageNotFoundError:
                version = ""
            if version != FAST_PACKAGE_VERSION:
                raise RuntimeError("setup-tts-fast.batで指定版の高速化ライブラリを導入してください。")
            from faster_qwen3_tts import FasterQwen3TTS
            self._model = FasterQwen3TTS.from_pretrained(
                directory, device="cuda:0", dtype=torch.float16, attn_implementation="sdpa",
                max_seq_len=GRAPH_SEQUENCE_LENGTH, local_files_only=True,
            )
            self._base = self._model.model
        else:
            self._model = Qwen3TTSModel.from_pretrained(
                directory, device_map="cuda:0", dtype=torch.float16,
                attn_implementation="sdpa", local_files_only=True,
            )
            self._base = self._model
        if getattr(self._base.model, "tts_model_type", None) != "base":
            raise ValueError("Qwen3-TTSのBaseモデルを指定してください。")
        talker = self._base.model.talker
        for name, module in (("talker", talker), ("predictor", talker.code_predictor)):
            actual = getattr(module.config, "_attn_implementation", None)
            self._attention[name] = actual if isinstance(actual, str) else "unknown"
        self._key = (directory, backend, attention)

    def _prompt(self, root: Path, transcript: str):
        import numpy as np
        import soundfile as sf
        key = (hashlib.sha256((root / "reference.audio").read_bytes()).digest(), transcript)
        if key in self._prompts:
            self._prompts.move_to_end(key)
            return self._prompts[key], "hit"
        decode_reference(root / "reference.audio", root / "reference.wav")
        reference, rate = sf.read(root / "reference.wav", dtype="float32")
        if not np.isfinite(reference).all() or np.max(np.abs(reference)) < 1e-5:
            raise ValueError("サンプル音声が無音か不正です。")
        prompt = self._base.create_voice_clone_prompt(
            ref_audio=(reference, rate), ref_text=transcript or None,
            x_vector_only_mode=not bool(transcript),
        )
        self._prompts[key] = prompt
        while len(self._prompts) > _MAX_PROMPTS:
            self._prompts.popitem(last=False)
        return prompt, "miss"

    def synthesize(self, body: dict, root: Path, chunks: list[str]) -> dict:
        started = time.monotonic()
        import numpy as np
        import soundfile as sf
        import torch
        imports_done = time.monotonic()
        backend = TtsBackend(body.get("backend", TtsBackend.STANDARD.value))
        attention = TtsAttention(body.get("attention", TtsAttention.AUTO.value))
        if backend == TtsBackend.CUDA_GRAPH:
            for chunk in chunks:
                check_graph_budget(chunk, body["ref_text"])
        reused = self._model is not None
        if self._model is None:
            self._load(body["model_dir"], backend, attention)
        elif self._key != (body["model_dir"], backend, attention):
            raise RuntimeError("モデル設定を変更したらエージェントを再起動してください。")
        torch.cuda.synchronize()
        model_done = time.monotonic()
        with torch.inference_mode():
            prompt, prompt_cache = self._prompt(root, body["ref_text"])
            torch.cuda.synchronize()
            prompt_done = time.monotonic()
            audio = []
            duration = 0.0
            for chunk in chunks:
                options = {"text": chunk, "language": "Auto", "voice_clone_prompt": prompt,
                           "max_new_tokens": MAX_NEW_TOKENS}
                if backend == TtsBackend.CUDA_GRAPH:
                    # The fast wrapper needs transcript IDs for a precomputed ICL prompt.
                    options["ref_text"] = body["ref_text"]
                waves, rate = self._model.generate_voice_clone(**options)
                wave = np.asarray(waves[0], dtype=np.float32)
                if wave.size == 0 or not np.isfinite(wave).all() or np.max(np.abs(wave)) < 1e-5:
                    raise RuntimeError("音声生成に失敗しました。サンプルを変更して再試行してください。")
                duration += wave.size / rate
                audio.extend([wave, np.zeros(int(rate * _CHUNK_PAUSE_SECONDS), dtype=np.float32)])
            torch.cuda.synchronize()
            generation_done = time.monotonic()
        sf.write(root / "speech.wav", np.concatenate(audio), rate, subtype="PCM_16")
        total = time.monotonic() - started
        return {"backend": backend.value, "attention": self._attention, "model_reused": reused, "prompt_cache": prompt_cache,
                "imports_s": round(imports_done - started, 2), "model_s": round(model_done - imports_done, 2),
                "reference_s": round(prompt_done - model_done, 2),
                "generation_s": round(generation_done - prompt_done, 2),
                "worker_total_s": round(total, 2), "audio_s": round(duration, 2),
                "seconds_per_audio_second": round(total / max(duration, 0.001), 2)}
