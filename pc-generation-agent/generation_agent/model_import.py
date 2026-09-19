from __future__ import annotations

import base64
import binascii
import json
import os
import re
import shutil
import threading
import urllib.error
import urllib.request
import uuid
from datetime import datetime
from pathlib import Path
from typing import Any

from .config import AgentConfig
from .sd_client import StableDiffusionClient

_MODEL_SUFFIXES = {".safetensors", ".ckpt", ".pt", ".pth", ".bin"}
_WINDOWS_RESERVED = {
    "CON", "PRN", "AUX", "NUL",
    "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
    "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
}
_THUMB_MAX_SIDE = 768
_CHUNK = 1024 * 1024
_USER_AGENT = "AndroidToolkits-PC-Agent/1.0"


def safe_model_filename(raw: Any) -> str:
    name = Path(str(raw or "")).name.strip().rstrip(".")
    if not name or len(name) > 200:
        raise ValueError("filename is required and must be at most 200 chars")
    if Path(name).suffix.lower() not in _MODEL_SUFFIXES:
        raise ValueError("filename must end with .safetensors/.ckpt/.pt/.pth/.bin")
    if any(ord(char) < 32 for char in name):
        raise ValueError("filename contains control characters")
    if Path(name).stem.upper() in _WINDOWS_RESERVED:
        name = f"_{name}"
    return name


class ModelImportService:
    """Downloads Civitai models on the PC into Forge folders.

    Android browses Civitai, resolves metadata via the public API, then hands
    this service a direct download URL. The phone never touches multi-GB files.
    """

    def __init__(self, config: AgentConfig, sd: StableDiffusionClient):
        self._config = config
        self._sd = sd
        self._lock = threading.Lock()
        self._jobs: dict[str, dict[str, Any]] = {}

    def submit(self, body: dict[str, Any]) -> dict[str, Any]:
        download_url = str(body.get("download_url", "")).strip()
        if not download_url.lower().startswith(("http://", "https://")):
            raise ValueError("download_url must be an http(s) URL")
        filename = safe_model_filename(body.get("filename", ""))
        kind = str(body.get("kind", "")).strip().lower()
        if kind not in {"checkpoint", "lora"}:
            raise ValueError("kind must be checkpoint or lora")
        thumbnail_b64 = str(body.get("thumbnail_base64", "") or "").strip()
        if thumbnail_b64:
            try:
                base64.b64decode(thumbnail_b64, validate=True)
            except (binascii.Error, ValueError) as error:
                raise ValueError("thumbnail_base64 is not valid base64") from error
        thumbnail_url = str(body.get("thumbnail_url", "") or "").strip()
        if thumbnail_url and not thumbnail_url.lower().startswith(("http://", "https://")):
            raise ValueError("thumbnail_url must be an http(s) URL")
        raw_words = body.get("trigger_words", [])
        trigger_words = [
            str(word).strip()[:120] for word in raw_words
            if isinstance(raw_words, list) and str(word).strip()
        ][:20]
        job_id = uuid.uuid4().hex
        now = datetime.now().astimezone().isoformat(timespec="seconds")
        job: dict[str, Any] = {
            "id": job_id,
            "status": "queued",
            "kind": kind,
            "filename": filename,
            "model_name": str(body.get("model_name", "") or "").strip()[:200],
            "version_name": str(body.get("version_name", "") or "").strip()[:200],
            "bytes_downloaded": 0,
            "bytes_total": 0,
            "progress": 0.0,
            "target_path": None,
            "error": None,
            "created_at": now,
            "updated_at": now,
            "_request": {
                "download_url": download_url,
                "token": str(body.get("civitai_token", "") or "").strip(),
                "thumbnail_base64": thumbnail_b64,
                "thumbnail_url": thumbnail_url,
                "trigger_words": trigger_words,
                "civitai_model_id": body.get("civitai_model_id"),
                "civitai_version_id": body.get("civitai_version_id"),
            },
        }
        with self._lock:
            self._jobs[job_id] = job
        worker = threading.Thread(
            target=self._run, args=(job_id,), name=f"model-import-{job_id[:8]}", daemon=True
        )
        worker.start()
        return self.public(job_id) or {}

    def get(self, job_id: str) -> dict[str, Any] | None:
        with self._lock:
            job = self._jobs.get(job_id)
            return self._public_locked(job) if job else None

    def public(self, job_id: str) -> dict[str, Any] | None:
        return self.get(job_id)

    def list_jobs(self) -> list[dict[str, Any]]:
        with self._lock:
            ordered = sorted(self._jobs.values(), key=lambda job: job["created_at"], reverse=True)
            return [self._public_locked(job) for job in ordered[:30]]

    @staticmethod
    def _public_locked(job: dict[str, Any]) -> dict[str, Any]:
        return {key: value for key, value in job.items() if not key.startswith("_")}

    def list_checkpoints(self) -> dict[str, Any]:
        models: list[dict[str, Any]] = []
        titles = self._sd_checkpoint_titles()
        for item in self._local_checkpoint_files():
            stat = item.stat()
            models.append({
                "name": item.name,
                "size": stat.st_size,
                "modified": datetime.fromtimestamp(stat.st_mtime).astimezone().isoformat(timespec="seconds"),
                "sd_title": titles.get(item.name),
            })
        return {"checkpoints": models, "active": self._active_checkpoint_name(titles)}

    def _local_checkpoint_files(self) -> list[Path]:
        root = self._config.checkpoint_dir
        if not root.is_dir():
            return []
        return [
            item for item in sorted(root.iterdir(), key=lambda entry: entry.name.lower())
            if item.is_file() and item.suffix.lower() in _MODEL_SUFFIXES
        ]

    def _sd_checkpoint_titles(self) -> dict[str, str]:
        """Map local filename -> exact SD title.

        SD only accepts its own title (e.g. "sd/foo.safetensors [abc123]")
        for sd_model_checkpoint; a bare filename makes it answer 500.
        """
        try:
            entries = self._sd.get_sd_models()
        except Exception:
            return {}
        by_path: dict[str, str] = {}
        by_base: dict[str, str] = {}
        for entry in entries:
            title = str(entry.get("title") or "").strip()
            filename = str(entry.get("filename") or "").strip()
            if not title or not filename:
                continue
            by_path.setdefault(os.path.normcase(os.path.normpath(filename)), title)
            by_base.setdefault(os.path.normcase(os.path.basename(filename.replace("\\", "/"))), title)
        titles: dict[str, str] = {}
        for item in self._local_checkpoint_files():
            candidates = [
                os.path.normcase(os.path.normpath(str(item))),
                os.path.normcase(item.name),
            ]
            try:
                candidates.insert(0, os.path.normcase(os.path.normpath(str(item.resolve()))))
            except OSError:
                pass
            title = next((by_path[key] for key in candidates if key in by_path), None)
            if title is None:
                title = by_base.get(os.path.normcase(item.name))
            if title:
                titles[item.name] = title
        return titles

    def _active_checkpoint_name(self, titles: dict[str, str]) -> str | None:
        try:
            raw = self._sd.get_options().get("sd_model_checkpoint")
        except Exception:
            return None
        value = str(raw or "").strip()
        if not value:
            return None
        for name, title in titles.items():
            if title == value:
                return name
        # Fallback when the SD model list is unavailable: the options value
        # looks like "sd/foo.safetensors [hash]", so strip and match by file.
        stem = re.sub(r"\s*\[[^\[\]]*\]\s*$", "", value).strip()
        base = os.path.basename(stem.replace("\\", "/"))
        if not base:
            return None
        names = list(titles) or [item.name for item in self._local_checkpoint_files()]
        wanted = os.path.normcase(base)
        return next((name for name in names if os.path.normcase(name) == wanted), None)

    def resolve_checkpoint_preview(self, raw_name: Any) -> Path | None:
        target = self._checkpoint_file(raw_name)
        if target is None:
            return None
        stem = target.with_name(target.stem)
        for candidate in (
            stem.with_suffix(".png"),
            target.with_name(target.stem + ".preview.png"),
            stem.with_suffix(".jpg"),
            stem.with_suffix(".jpeg"),
            stem.with_suffix(".webp"),
        ):
            if candidate.is_file():
                return candidate
        return None

    def set_active_checkpoint(self, raw_name: Any) -> str:
        target = self._checkpoint_file(raw_name)
        if target is None:
            raise ValueError("checkpoint not found")
        # SD matches by its own title; a bare filename is rejected with 500.
        desired = self._sd_checkpoint_titles().get(target.name, target.name)
        try:
            self._sd.set_options({"sd_model_checkpoint": desired})
        except Exception as error:
            raise RuntimeError(f"SD rejected the model switch: {error}") from error
        print(f"Active checkpoint: {target.name}")
        return target.name

    def _checkpoint_file(self, raw_name: Any) -> Path | None:
        name = str(raw_name or "").strip()
        if not name or Path(name).name != name:
            return None
        if Path(name).suffix.lower() not in _MODEL_SUFFIXES:
            return None
        root = self._config.checkpoint_dir.resolve()
        target = (self._config.checkpoint_dir / name).resolve()
        try:
            target.relative_to(root)
        except ValueError:
            return None
        return target if target.is_file() else None

    def _run(self, job_id: str) -> None:
        with self._lock:
            job = self._jobs.get(job_id)
        if job is None:
            return
        request = job["_request"]
        target_dir = self._config.checkpoint_dir if job["kind"] == "checkpoint" else self._config.lora_dir
        try:
            target_dir.mkdir(parents=True, exist_ok=True)
            destination = target_dir / str(job["filename"])
            token = request["token"] or self._config.civitai_api_key
            if destination.is_file() and destination.stat().st_size > 0:
                # Re-import of an existing file still refreshes sidecars, then skips bytes.
                self._save_sidecars(job, destination, request, token)
                self._finish(job_id, destination)
                self._refresh_sd(str(job["kind"]))
                return
            self._update(job_id, status="downloading")
            self._download(request["download_url"], destination, token, job_id)
            self._save_sidecars(job, destination, request, token)
            self._finish(job_id, destination)
            self._refresh_sd(str(job["kind"]))
        except Exception as error:
            self._fail(job_id, str(error) or type(error).__name__)

    def _download(self, url: str, destination: Path, token: str, job_id: str) -> None:
        part = destination.with_name(destination.name + ".part")
        start = part.stat().st_size if part.is_file() else 0
        headers = {"User-Agent": _USER_AGENT}
        if start > 0:
            headers["Range"] = f"bytes={start}-"
        if token:
            headers["Authorization"] = f"Bearer {token}"
        request = urllib.request.Request(url, headers=headers)
        try:
            response = urllib.request.urlopen(request, timeout=self._config.request_timeout_seconds)
        except urllib.error.HTTPError as error:
            if error.code == 416:  # Range beyond EOF: stale part file, restart clean.
                part.unlink(missing_ok=True)
                return self._download(url, destination, token, job_id)
            raise
        with response:
            if response.status == 206 and start > 0:
                mode, downloaded = "ab", start
                total = start + int(response.headers.get("Content-Length", 0) or 0)
            else:
                mode, downloaded = "wb", 0
                total = int(response.headers.get("Content-Length", 0) or 0)
            self._update(job_id, bytes_downloaded=downloaded, bytes_total=total)
            with part.open(mode) as stream:
                while chunk := response.read(_CHUNK):
                    stream.write(chunk)
                    downloaded += len(chunk)
                    self._update(job_id, bytes_downloaded=downloaded, bytes_total=total)
        os.replace(part, destination)

    def _save_sidecars(
        self, job: dict[str, Any], destination: Path, request: dict[str, Any], token: str
    ) -> None:
        raw: bytes | None = None
        if request["thumbnail_base64"]:
            raw = base64.b64decode(request["thumbnail_base64"], validate=True)
        elif request["thumbnail_url"]:
            raw = self._fetch_bytes(request["thumbnail_url"], token)
        if raw:
            self._write_preview(destination, raw)
        info = {
            "model_name": job["model_name"],
            "version_name": job["version_name"],
            "trigger_words": request["trigger_words"],
            "civitai_model_id": request["civitai_model_id"],
            "civitai_version_id": request["civitai_version_id"],
            "filename": job["filename"],
            "downloaded_at": datetime.now().astimezone().isoformat(timespec="seconds"),
        }
        destination.with_name(destination.name + ".civitai.info").write_text(
            json.dumps(info, ensure_ascii=False, indent=2), encoding="utf-8"
        )

    @staticmethod
    def _write_preview(destination: Path, raw: bytes) -> None:
        # A1111 shows <stem>.png, Forge prefers <stem>.preview.png; write both.
        plain = destination.with_name(destination.stem + ".png")
        flagged = destination.with_name(destination.stem + ".preview.png")
        try:
            from PIL import Image, ImageOps
            import io
            with Image.open(io.BytesIO(raw)) as opened:
                image = ImageOps.exif_transpose(opened)
                image.thumbnail((_THUMB_MAX_SIDE, _THUMB_MAX_SIDE))
                if image.mode != "RGB":
                    image = image.convert("RGB")
                image.save(plain, "PNG", optimize=True)
            shutil.copyfile(plain, flagged)
        except Exception:
            # Unreadable bytes still land next to the model; viewers sniff magic.
            flagged.write_bytes(raw)
            if not plain.is_file():
                shutil.copyfile(flagged, plain)

    def _fetch_bytes(self, url: str, token: str) -> bytes:
        headers = {"User-Agent": _USER_AGENT}
        if token:
            headers["Authorization"] = f"Bearer {token}"
        request = urllib.request.Request(url, headers=headers)
        with urllib.request.urlopen(request, timeout=120) as response:
            return response.read(20 * 1024 * 1024)

    def _refresh_sd(self, kind: str) -> None:
        # Forge picks up new files only after a refresh; failure is non-fatal.
        endpoint = "refresh-checkpoints" if kind == "checkpoint" else "refresh-loras"
        try:
            request = urllib.request.Request(
                f"{self._config.sd_base_url}/sdapi/v1/{endpoint}",
                data=b"{}",
                method="POST",
                headers={"Content-Type": "application/json"},
            )
            with urllib.request.urlopen(request, timeout=60):
                pass
        except Exception as error:
            print(f"SD {endpoint} failed: {error}")

    def _update(self, job_id: str, **fields: Any) -> None:
        with self._lock:
            job = self._jobs.get(job_id)
            if job is None:
                return
            job.update(fields)
            total = int(job.get("bytes_total", 0) or 0)
            done = int(job.get("bytes_downloaded", 0) or 0)
            job["progress"] = min(1.0, done / total) if total > 0 else 0.0
            job["updated_at"] = datetime.now().astimezone().isoformat(timespec="seconds")

    def _finish(self, job_id: str, destination: Path) -> None:
        with self._lock:
            job = self._jobs.get(job_id)
            if job is None:
                return
            job["status"] = "completed"
            job["progress"] = 1.0
            job["target_path"] = str(destination)
            job["updated_at"] = datetime.now().astimezone().isoformat(timespec="seconds")
            print(f"Model import completed: {destination}")

    def _fail(self, job_id: str, message: str) -> None:
        with self._lock:
            job = self._jobs.get(job_id)
            if job is None:
                return
            job["status"] = "failed"
            job["error"] = message[:500]
            job["updated_at"] = datetime.now().astimezone().isoformat(timespec="seconds")
            print(f"Model import failed: {message[:500]}")
