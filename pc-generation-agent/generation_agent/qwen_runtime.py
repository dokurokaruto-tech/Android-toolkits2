"""Explain one misleading upstream import notice, without hiding other diagnostics."""
from __future__ import annotations

import importlib
import io
import sys
from contextlib import redirect_stdout

_BANNER = ("\n********\nWarning: flash-attn is not installed. Will only run the manual PyTorch version. "
           "Please install flash-attn for faster inference.\n********\n ")
_NOTICE = ("[INFO] Optional Qwen 25Hz encoder: flash-attn import is unavailable. "
           "This import notice does not determine the 12Hz model's attention backend. "
           "See [TTS attention] for the selected runtime.\n")


def _rewrite_notice(text: str) -> str:
    return text.replace(_BANNER, _NOTICE)


def import_qwen():
    output = io.StringIO()
    destination = sys.stdout
    try:
        with redirect_stdout(output):
            return importlib.import_module("qwen_tts").Qwen3TTSModel
    finally:
        destination.write(_rewrite_notice(output.getvalue()))
        destination.flush()
