"""Optional TTS interpreter, in single-shot or private warm-worker mode."""
from __future__ import annotations

import json
import os
import time
import threading
import traceback
import re
import sys
from pathlib import Path

# This file is also launched directly with the separate TTS interpreter.
if not __package__:
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from generation_agent.sox_runtime import configure_sox
from generation_agent.tts_engine import TtsEngine

CHUNK_CHARACTERS = 200
_POLL_SECONDS = 0.05
_WATCHDOG_MARGIN = 5


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
    body = json.loads(request.read_text(encoding="utf-8"))
    metrics = TtsEngine().synthesize(body, request.parent, split_text(body["text"]))
    (request.parent / "metrics.json").write_text(json.dumps(metrics), encoding="utf-8")


def error_message(error: Exception) -> str:
    message = str(error)
    if "out of memory" in message.lower():
        return "GPUメモリ不足です。他のGPUアプリを停止するか、setup-tts-standard.batで標準モードへ戻してください。"
    return message[:1000]


def serve(root: Path, idle_seconds: float, timeout_seconds: float) -> None:
    configure_sox()
    engine = TtsEngine()
    request = root / "request.json"
    last_request = time.monotonic()
    while root.is_dir():
        if not request.exists():
            if time.monotonic() - last_request >= idle_seconds:
                return
            time.sleep(_POLL_SECONDS)
            continue
        watchdog = threading.Timer(timeout_seconds + _WATCHDOG_MARGIN, lambda: os._exit(1))
        watchdog.daemon = True
        watchdog.start()
        try:
            body = json.loads(request.read_text(encoding="utf-8"))
            request.unlink()
            metrics = engine.synthesize(body, root, split_text(body["text"]))
            result = {"ok": True, "metrics": metrics}
        except Exception as error:
            traceback.print_exc()
            result = {"ok": False, "error": error_message(error)}
        finally:
            watchdog.cancel()
        last_request = time.monotonic()
        sys.stdout.flush()
        sys.stderr.flush()
        temporary = root / "done.tmp"
        temporary.write_text(json.dumps(result, ensure_ascii=False), encoding="utf-8")
        temporary.replace(root / "done.json")
        if not result["ok"]:
            return  # Never reuse a potentially damaged CUDA graph/context.


if __name__ == "__main__":
    # The wrapper's torch loader does not forward local_files_only; enforce offline globally.
    os.environ["HF_HUB_OFFLINE"] = "1"
    os.environ["TRANSFORMERS_OFFLINE"] = "1"
    if sys.argv[1] == "--serve":
        serve(Path(sys.argv[2]), float(sys.argv[3]), float(sys.argv[4]))
    else:
        request_path = Path(sys.argv[1])
        try:
            generate(request_path)
        except Exception as error:
            (request_path.parent / "error.txt").write_text(error_message(error), encoding="utf-8")
            raise
