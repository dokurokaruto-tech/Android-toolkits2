"""Locate SoX without changing the user's persistent Windows PATH."""
from __future__ import annotations

import os
import shutil
import subprocess
from pathlib import Path

BUNDLED_SOX = Path(__file__).resolve().parents[1] / ".tools" / "sox" / "sox.exe"
_PROBE_TIMEOUT_SECONDS = 5


def find_sox() -> Path | None:
    installed = shutil.which("sox")
    candidates = ([Path(installed)] if installed else []) + [BUNDLED_SOX]
    for executable in dict.fromkeys(candidates):
        if not executable.is_file():
            continue
        try:
            result = subprocess.run(
                [str(executable), "--version"], capture_output=True, text=True,
                encoding="utf-8", errors="replace", timeout=_PROBE_TIMEOUT_SECONDS, check=False,
            )
        except (OSError, subprocess.TimeoutExpired):
            continue
        if result.returncode == 0 and "sox" in (result.stdout + result.stderr).lower():
            return executable.resolve()
    return None


def configure_sox() -> Path:
    executable = find_sox()
    if executable is None:
        raise RuntimeError("SoX was not found or could not run. Run setup-tts.bat and choose Y for SoX.")
    directory = str(executable.parent)
    previous = os.environ.get("PATH", "")
    entries = [entry for entry in previous.split(os.pathsep) if entry]
    entries = [entry for entry in entries if os.path.normcase(entry) != os.path.normcase(directory)]
    os.environ["PATH"] = os.pathsep.join([directory, *entries])
    return executable
