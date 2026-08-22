from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any


@dataclass(frozen=True)
class AgentConfig:
    root: Path
    listen_host: str
    listen_port: int
    sd_base_url: str
    output_dir: Path
    database_path: Path
    api_key: str
    request_timeout_seconds: int
    retry_count: int
    legacy_api_url: str

    @classmethod
    def load(cls, path: Path) -> "AgentConfig":
        root = path.resolve().parent
        raw: dict[str, Any] = {}
        if path.exists():
            with path.open("r", encoding="utf-8") as stream:
                raw = json.load(stream)

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
            database_path=resolve(str(raw.get("database_path", "data/agent.sqlite3"))),
            api_key=str(raw.get("api_key", "")).strip(),
            request_timeout_seconds=max(30, timeout),
            retry_count=max(0, min(retry_count, 10)),
            legacy_api_url=str(raw.get("legacy_api_url", "")).rstrip("/"),
        )
