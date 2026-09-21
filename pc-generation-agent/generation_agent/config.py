from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

DEFAULT_TTS_TIMEOUT_SECONDS = 1200

DEFAULT_CHECKPOINT_DIR = (
    r"C:\AI\StabilityMatrix\Data\Packages\stable-diffusion-webui-forge"
    r"\models\Stable-diffusion\sd"
)
DEFAULT_LORA_DIR = (
    r"C:\AI\StabilityMatrix\Data\Packages\stable-diffusion-webui-forge"
    r"\models\Lora"
)


def load_config_values(path: Path) -> dict[str, Any]:
    """Keep machine paths and secrets outside the tracked defaults."""
    raw: dict[str, Any] = {}
    for source in (path, path.with_suffix(".local.json")):
        if not source.exists():
            continue
        with source.open("r", encoding="utf-8-sig") as stream:
            value = json.load(stream)
        if not isinstance(value, dict):
            raise ValueError(f"{source.name} must contain a JSON object")
        raw.update(value)
    return raw


@dataclass(frozen=True)
class AgentConfig:
    root: Path
    listen_host: str
    listen_port: int
    sd_base_url: str
    output_dir: Path
    thumbnail_dir: Path
    mobile_thumbnail_dir: Path
    progressive_tile_dir: Path
    database_path: Path
    api_key: str
    request_timeout_seconds: int
    retry_count: int
    legacy_api_url: str
    checkpoint_dir: Path = field(default_factory=lambda: Path(DEFAULT_CHECKPOINT_DIR))
    lora_dir: Path = field(default_factory=lambda: Path(DEFAULT_LORA_DIR))
    civitai_api_key: str = ""
    tts_model_dir: Path | None = None
    tts_python: str = ""
    tts_timeout_seconds: int = DEFAULT_TTS_TIMEOUT_SECONDS
    tts_unload_sd: bool = True

    @classmethod
    def load(cls, path: Path) -> "AgentConfig":
        root = path.resolve().parent
        raw = load_config_values(path)

        def resolve(value: str) -> Path:
            candidate = Path(value)
            return candidate if candidate.is_absolute() else (root / candidate).resolve()

        port = int(raw.get("listen_port", 3001))
        if not 1 <= port <= 65535:
            raise ValueError("listen_port must be between 1 and 65535")
        timeout = int(raw.get("request_timeout_seconds", 600))
        retry_count = int(raw.get("retry_count", 1))
        return cls(
            root=root,
            listen_host=str(raw.get("listen_host", "0.0.0.0")),
            listen_port=port,
            sd_base_url=str(raw.get("sd_base_url", "http://127.0.0.1:7860")).rstrip("/"),
            output_dir=resolve(str(raw.get("output_dir", "generated"))),
            thumbnail_dir=resolve(str(raw.get("thumbnail_dir", "thumbnails"))),
            mobile_thumbnail_dir=resolve(str(raw.get("mobile_thumbnail_dir", "data/mobile-thumbnails"))),
            progressive_tile_dir=resolve(str(raw.get("progressive_tile_dir", "data/progressive-tiles"))),
            database_path=resolve(str(raw.get("database_path", "data/agent.sqlite3"))),
            api_key=str(raw.get("api_key", "")).strip(),
            request_timeout_seconds=max(30, timeout),
            retry_count=max(0, min(retry_count, 10)),
            legacy_api_url=str(raw.get("legacy_api_url", "")).rstrip("/"),
            checkpoint_dir=resolve(str(raw.get("checkpoint_dir", DEFAULT_CHECKPOINT_DIR))),
            lora_dir=resolve(str(raw.get("lora_dir", DEFAULT_LORA_DIR))),
            civitai_api_key=str(raw.get("civitai_api_key", "")).strip(),
            tts_model_dir=resolve(raw["tts_model_dir"]) if raw.get("tts_model_dir") else None,
            tts_python=str(resolve(raw["tts_python"])) if raw.get("tts_python") else "",
            tts_timeout_seconds=max(30, min(int(raw.get("tts_timeout_seconds", DEFAULT_TTS_TIMEOUT_SECONDS)), DEFAULT_TTS_TIMEOUT_SECONDS)),
            tts_unload_sd=raw.get("tts_unload_sd", True) is True,
        )
