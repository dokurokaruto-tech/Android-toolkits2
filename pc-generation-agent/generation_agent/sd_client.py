from __future__ import annotations

import base64
import json
import urllib.error
import urllib.parse
import urllib.request
from typing import Any


class StableDiffusionClient:
    def __init__(self, base_url: str, timeout: int):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout

    def _request(self, path: str, method: str = "GET", body: dict[str, Any] | None = None, timeout: int | None = None) -> Any:
        data = json.dumps(body).encode("utf-8") if body is not None else None
        request = urllib.request.Request(
            self.base_url + path,
            data=data,
            method=method,
            headers={"Content-Type": "application/json", "Accept": "application/json"},
        )
        try:
            with urllib.request.urlopen(request, timeout=timeout or self.timeout) as response:
                content = response.read()
                return json.loads(content.decode("utf-8")) if content else {}
        except urllib.error.HTTPError as error:
            detail = error.read().decode("utf-8", errors="replace")[:2000]
            raise RuntimeError(f"SD HTTP {error.code}: {detail}") from error
        except (urllib.error.URLError, TimeoutError) as error:
            raise RuntimeError(f"SD connection failed: {error}") from error

    def health(self) -> bool:
        try:
            self._request("/sdapi/v1/options", timeout=3)
            return True
        except Exception:
            return False

    def generate(self, payload: dict[str, Any]) -> tuple[bytes, str]:
        request_payload = dict(payload)
        request_payload["batch_size"] = 1
        request_payload["n_iter"] = 1
        result = self._request("/sdapi/v1/txt2img", "POST", request_payload)
        images = result.get("images") if isinstance(result, dict) else None
        if not images:
            raise RuntimeError("SD response did not contain an image")
        encoded = str(images[0])
        if "," in encoded and encoded.lstrip().startswith("data:"):
            encoded = encoded.split(",", 1)[1]
        try:
            raw = base64.b64decode(encoded, validate=False)
        except Exception as error:
            raise RuntimeError("SD returned invalid base64 image data") from error
        seed = self._seed_from_result(result)
        if raw.startswith(b"\x89PNG\r\n\x1a\n"):
            return raw, ".png", seed
        if raw.startswith(b"\xff\xd8\xff"):
            return raw, ".jpg", seed
        if raw.startswith(b"RIFF") and raw[8:12] == b"WEBP":
            return raw, ".webp", seed
        raise RuntimeError("SD returned an unsupported or corrupt image")

    @staticmethod
    def _seed_from_result(result: dict[str, Any]) -> int | None:
        info = result.get("info")
        parsed: dict[str, Any] = {}
        if isinstance(info, str) and info.strip():
            try:
                loaded = json.loads(info)
                if isinstance(loaded, dict):
                    parsed = loaded
            except Exception:
                parsed = {}
        elif isinstance(info, dict):
            parsed = info
        for key in ("seed",):
            value = parsed.get(key)
            if isinstance(value, bool):
                continue
            if isinstance(value, int) and value >= 0:
                return value
            if isinstance(value, float) and value >= 0:
                return int(value)
        seeds = parsed.get("all_seeds")
        if isinstance(seeds, list) and seeds:
            first = seeds[0]
            if isinstance(first, (int, float)) and not isinstance(first, bool) and first >= 0:
                return int(first)
        return None

    def progress(self, include_image: bool = False) -> dict[str, Any]:
        suffix = "" if include_image else "?skip_current_image=true"
        result = self._request("/sdapi/v1/progress" + suffix, timeout=5)
        return result if isinstance(result, dict) else {}

    def interrupt(self) -> None:
        self._request("/sdapi/v1/interrupt", "POST", {}, timeout=5)

    def skip(self) -> None:
        self._request("/sdapi/v1/skip", "POST", {}, timeout=5)
