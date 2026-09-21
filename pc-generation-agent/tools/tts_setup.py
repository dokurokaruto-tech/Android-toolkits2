"""Windows setup support; no model downloads or automatic firewall changes."""
from __future__ import annotations

import argparse
import importlib.metadata
import json
import re
import secrets
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from generation_agent.config import AgentConfig, load_config_values
from generation_agent.sox_runtime import configure_sox, find_sox

DEFAULT_MODEL_DIR = "models/Qwen3-TTS-12Hz-1.7B-Base"
DEFAULT_TTS_PYTHON = ".venv-tts/Scripts/python.exe"
RUNTIME_TIMEOUT_SECONDS = 120
_REQUIRED_EXACT = {
    "torch": "2.7.1+cu126",
    "torchaudio": "2.7.1+cu126",
    "qwen-tts": "0.1.1",
}
_REQUIRED_RANGES = {
    "Pillow": ((10, 0, 0), (12, 0, 0)),
    "soundfile": ((0, 13, 0), (0, 14, 0)),
}


def package_issues() -> list[str]:
    """No GPU imports or downloads: this runs before the install consent prompt."""
    issues = []
    for name in (*_REQUIRED_EXACT, *_REQUIRED_RANGES):
        try:
            version = importlib.metadata.version(name)
        except importlib.metadata.PackageNotFoundError:
            issues.append(f"Missing package: {name}")
            continue
        expected = _REQUIRED_EXACT.get(name)
        if expected is not None:
            if version != expected:
                issues.append(f"{name}: installed {version}; required {expected}")
            continue
        match = re.fullmatch(r"(\d+)\.(\d+)(?:\.(\d+))?(?:\.post\d+)?", version)
        release = tuple(int(part or 0) for part in match.groups()) if match else None
        minimum, maximum = _REQUIRED_RANGES[name]
        if release is None or not minimum <= release < maximum:
            issues.append(f"Unsupported {name} version: {version}")
    return issues


def prepare_config(path: Path, model_dir: str | None = None) -> Path:
    values = load_config_values(path)
    if model_dir is not None:
        if not model_dir.strip():
            raise ValueError("Model directory must not be empty")
        values["tts_model_dir"] = model_dir
    if not values.get("tts_model_dir"):
        values["tts_model_dir"] = DEFAULT_MODEL_DIR
    if not values.get("tts_python"):
        values["tts_python"] = DEFAULT_TTS_PYTHON
    if not str(values.get("api_key", "")).strip():
        values["api_key"] = secrets.token_urlsafe(32)
    local = path.with_suffix(".local.json")
    # Snapshot existing settings, including old config.json customizations.
    temporary = local.with_suffix(".tmp")
    try:
        temporary.write_text(json.dumps(values, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        temporary.replace(local)
    finally:
        temporary.unlink(missing_ok=True)
    return local


def model_issues(model: Path | None) -> list[str]:
    if model is None or not model.is_dir():
        return ["Set tts_model_dir in config.local.json to the existing model folder."]
    issues = []
    for relative in ("config.json", "generation_config.json", "tokenizer_config.json",
                     "speech_tokenizer/config.json", "speech_tokenizer/preprocessor_config.json"):
        if not (model / relative).is_file():
            issues.append(f"Missing model file: {relative}")
    config_file = model / "config.json"
    if config_file.is_file():
        try:
            data = json.loads(config_file.read_text(encoding="utf-8"))
            if not isinstance(data, dict) or data.get("tts_model_type") != "base":
                issues.append("Use Qwen3-TTS-12Hz-1.7B-Base, not CustomVoice or VoiceDesign.")
        except (OSError, ValueError):
            issues.append("Model config.json could not be read.")
    if not (model / "tokenizer.json").is_file() and not all(
        (model / name).is_file() for name in ("vocab.json", "merges.txt")
    ):
        issues.append("Missing text tokenizer: tokenizer.json or vocab.json + merges.txt.")
    for directory in (model, model / "speech_tokenizer"):
        weights = list(directory.glob("*.safetensors")) + list(directory.glob("*.bin"))
        if not weights:
            issues.append(f"Missing model weights: {directory.name}")
        for index in directory.glob("*.index.json"):
            try:
                mapping = json.loads(index.read_text(encoding="utf-8"))["weight_map"]
                for name in set(mapping.values()):
                    if not (directory / name).is_file():
                        issues.append(f"Missing weight shard: {directory.name}/{name}")
            except (OSError, ValueError, KeyError, AttributeError, TypeError):
                issues.append(f"Invalid weight index: {index.name}")
    return issues


def runtime_check() -> int:
    print(f"[OK] SoX: {configure_sox()}")
    import torch
    import torchaudio
    import soundfile
    from qwen_tts import Qwen3TTSModel

    print(f"[OK] qwen-tts {importlib.metadata.version('qwen-tts')}, torch {torch.__version__}")
    if not torch.cuda.is_available():
        raise RuntimeError("CUDA unavailable. Check CUDA PyTorch and the NVIDIA driver.")
    properties = torch.cuda.get_device_properties(0)
    # Check that the installed wheel can actually execute on this GPU.
    value = torch.ones((16, 16), device="cuda:0", dtype=torch.float16)
    if not torch.isfinite(value @ value).all().item():
        raise RuntimeError("CUDA FP16 check failed")
    torch.cuda.synchronize()
    print(f"[OK] GPU: {properties.name}, VRAM: {properties.total_memory / 1024**3:.1f} GiB")
    print("[INFO] Model inference / VRAM fit is NOT tested by this check.")
    return 0


def check_setup(path: Path) -> int:
    config = AgentConfig.load(path)
    issues = model_issues(config.tts_model_dir)
    if not config.api_key:
        issues.append("No API key. Run setup-tts.bat to create a private key.")
    try:
        import PIL
    except ImportError:
        issues.append("Pillow missing from agent Python. Run setup-tts.bat.")
    executable = Path(config.tts_python or sys.executable)
    if not executable.is_file():
        issues.append("TTS Python not found. Run setup-tts.bat or set tts_python in config.local.json.")
    else:
        result = subprocess.run([str(executable), str(Path(__file__).resolve()), "--runtime-check"],
                                timeout=RUNTIME_TIMEOUT_SECONDS, check=False)
        if result.returncode:
            issues.append("TTS runtime check failed. See the output above.")
    for issue in issues:
        print(f"[ERROR] {issue}")
    if issues:
        return 1
    print("[OK] Setup checks passed. Start start-agent.bat, then test one short reply.")
    print(f"[INFO] Android URL: http://<PC Tailscale IPv4>:{config.listen_port}")
    print("[INFO] Copy api_key from config.local.json to Android's existing agent API-key setting.")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, default=ROOT / "config.json")
    modes = parser.add_mutually_exclusive_group(required=True)
    modes.add_argument("--prepare", action="store_true")
    modes.add_argument("--check", action="store_true")
    modes.add_argument("--runtime-check", action="store_true")
    modes.add_argument("--packages-check", action="store_true")
    modes.add_argument("--sox-check", action="store_true")
    parser.add_argument("--model-dir", help="Existing model directory; only used with --prepare")
    args = parser.parse_args()
    try:
        if args.sox_check:
            executable = find_sox()
            if executable is None:
                print("[INFO] SoX is missing or could not run. Run setup-tts.bat to install it.")
                return 1
            print(f"[OK] SoX: {executable}")
            return 0
        if args.packages_check:
            issues = package_issues()
            for issue in issues:
                print(f"[INFO] {issue}")
            return 1 if issues else 0
        if args.runtime_check:
            return runtime_check()
        if args.prepare:
            local = prepare_config(args.config, args.model_dir)
            print(f"[OK] Private settings saved to {local.name}; tracked config unchanged.")
            print("[INFO] Existing keys are preserved. A new key is created only if none was set.")
            return 0
        return check_setup(args.config)
    except Exception as error:
        print(f"[ERROR] {error}")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
