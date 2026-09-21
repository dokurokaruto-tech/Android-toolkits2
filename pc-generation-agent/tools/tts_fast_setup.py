"""Optional fast-path checks and private configuration. No automatic installs."""
from __future__ import annotations

import argparse
import importlib.metadata
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from generation_agent.config import load_config_values
from generation_agent.tts_options import TtsBackend, FAST_PACKAGE_VERSION, DEFAULT_KEEP_ALIVE_SECONDS


def package_issues() -> list[str]:
    issues = []
    for name, expected in (("faster-qwen3-tts", FAST_PACKAGE_VERSION), ("transformers", "4.57.3")):
        try:
            if importlib.metadata.version(name) != expected:
                issues.append(f"Install {name}=={expected}")
        except importlib.metadata.PackageNotFoundError:
            issues.append(f"Install {name}=={expected}")
    return issues


def set_backend(path: Path, backend: TtsBackend) -> None:
    values = load_config_values(path)
    if not values.get("tts_model_dir"):
        raise ValueError("Run setup-tts.bat first. Existing model path is required.")
    values["tts_backend"] = backend.value
    values["tts_keep_alive_seconds"] = DEFAULT_KEEP_ALIVE_SECONDS if backend == TtsBackend.CUDA_GRAPH else 0
    local = path.with_suffix(".local.json")
    temporary = local.with_suffix(".tmp")
    try:
        temporary.write_text(json.dumps(values, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        temporary.replace(local)
    finally:
        temporary.unlink(missing_ok=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, default=ROOT / "config.json")
    modes = parser.add_mutually_exclusive_group(required=True)
    modes.add_argument("--check", action="store_true")
    modes.add_argument("--enable", action="store_true")
    modes.add_argument("--disable", action="store_true")
    args = parser.parse_args()
    try:
        if args.check or args.enable:
            issues = package_issues()
            if issues:
                for issue in issues:
                    print(f"[INFO] {issue}")
                return 1
        if not args.check:
            backend = TtsBackend.CUDA_GRAPH if args.enable else TtsBackend.STANDARD
            set_backend(args.config, backend)
            print(f"[OK] TTS backend: {backend.value}. Restart the agent.")
        return 0
    except Exception as error:
        print(f"[ERROR] {error}")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
