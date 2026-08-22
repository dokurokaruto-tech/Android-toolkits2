from __future__ import annotations

import json
import os
import re
import threading
import time
import uuid
from datetime import datetime
from pathlib import Path
from typing import Any

from .config import AgentConfig
from .database import JobDatabase
from .sd_client import StableDiffusionClient

_DATE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
_IMAGE_SUFFIXES = {".png", ".jpg", ".jpeg", ".webp"}


class GenerationService:
    def __init__(self, config: AgentConfig):
        self.config = config
        self.config.output_dir.mkdir(parents=True, exist_ok=True)
        self.database = JobDatabase(config.database_path)
        self.sd = StableDiffusionClient(config.sd_base_url, config.request_timeout_seconds)
        self._wake = threading.Event()
        self._stop = threading.Event()
        self._worker = threading.Thread(target=self._worker_loop, name="generation-worker", daemon=True)
        self._active_job_id: str | None = None
        self._active_lock = threading.Lock()

    def start(self) -> None:
        self._worker.start()

    def close(self) -> None:
        self._stop.set()
        self._wake.set()
        self._worker.join(timeout=5)
        self.database.close()

    def submit(self, body: dict[str, Any]) -> dict[str, Any]:
        raw_tasks = body.get("tasks")
        if raw_tasks is None:
            count = self._integer(body.get("count", 1), "count", 1, 1000)
            template = {key: value for key, value in body.items() if key not in {"count", "client_request_id", "tasks"}}
            raw_tasks = [template for _ in range(count)]
        if not isinstance(raw_tasks, list) or not raw_tasks:
            raise ValueError("tasks must be a non-empty array")
        if len(raw_tasks) > 1000:
            raise ValueError("a job may contain at most 1000 images")
        tasks = [self._validate_task(task) for task in raw_tasks]
        job_id = uuid.uuid4().hex
        submitted_at = datetime.now().astimezone()
        date = submitted_at.strftime("%Y-%m-%d")
        stamp = submitted_at.strftime("%Y%m%d_%H%M%S_%f")[:-3]
        for index, task in enumerate(tasks):
            # A deterministic destination makes crash recovery exactly-once at file level:
            # if the PNG was renamed into place before SQLite updated, restart reuses it.
            task["_agent_output_date"] = date
            task["_agent_output_base"] = f"GEN_{stamp}_{job_id[:8]}_{index + 1:04d}"
        client_request_id = str(body.get("client_request_id", "")).strip()[:200]
        job, created = self.database.create_job(job_id, client_request_id, tasks)
        if created:
            self._wake.set()
        return self.public_job(job)

    def get_job(self, job_id: str, with_progress: bool = True) -> dict[str, Any] | None:
        job = self.database.get_job(job_id)
        if not job:
            return None
        result = self.public_job(job)
        if with_progress and job["status"] == "running":
            try:
                sd_progress = max(0.0, min(float(self.sd.progress().get("progress", 0.0)), 1.0))
            except Exception:
                sd_progress = 0.0
            total = max(1, int(job["total"]))
            result["current_image_progress"] = sd_progress
            result["progress"] = min(1.0, (int(job["completed"]) + sd_progress) / total)
            result["preview_url"] = f"/api/v1/jobs/{job_id}/preview"
        return result

    def list_jobs(self, limit: int) -> list[dict[str, Any]]:
        return [self.public_job(job) for job in self.database.list_jobs(limit)]

    def cancel(self, job_id: str) -> bool:
        changed = self.database.request_cancel(job_id)
        with self._active_lock:
            is_active = self._active_job_id == job_id
        if changed and is_active:
            try:
                self.sd.interrupt()
            except Exception:
                pass
        self._wake.set()
        return changed

    def stop_after_current(self, job_id: str) -> bool:
        # Mark cancellation without interrupting SD. The worker observes it after the current image is saved.
        changed = self.database.request_cancel(job_id)
        self._wake.set()
        return changed

    def skip(self, job_id: str) -> bool:
        with self._active_lock:
            is_active = self._active_job_id == job_id
        if is_active:
            self.sd.skip()
        return is_active

    def preview(self, job_id: str) -> tuple[bytes, str] | None:
        with self._active_lock:
            if self._active_job_id != job_id:
                return None
        result = self.sd.progress(include_image=True)
        encoded = str(result.get("current_image", ""))
        if not encoded:
            return None
        if "," in encoded and encoded.lstrip().startswith("data:"):
            encoded = encoded.split(",", 1)[1]
        import base64
        raw = base64.b64decode(encoded, validate=False)
        if raw.startswith(b"\x89PNG"):
            return raw, "image/png"
        if raw.startswith(b"\xff\xd8\xff"):
            return raw, "image/jpeg"
        if raw.startswith(b"RIFF"):
            return raw, "image/webp"
        return None

    def library_dates(self) -> list[dict[str, Any]]:
        folders: list[dict[str, Any]] = []
        for folder in self.config.output_dir.iterdir():
            if not folder.is_dir() or not _DATE.fullmatch(folder.name):
                continue
            images = self._folder_images(folder)
            if images:
                folders.append({
                    "date": folder.name,
                    "count": len(images),
                    "thumbnail_url": self.file_url(folder.name, images[0].name),
                })
        return sorted(folders, key=lambda item: item["date"], reverse=True)

    def library_images(self, date: str) -> list[dict[str, Any]]:
        if not _DATE.fullmatch(date):
            raise ValueError("date must use YYYY-MM-DD")
        folder = self.config.output_dir / date
        if not folder.is_dir():
            return []
        return [
            {
                "name": image.name,
                "date": date,
                "size": image.stat().st_size,
                "created_at": datetime.fromtimestamp(image.stat().st_mtime).astimezone().isoformat(timespec="seconds"),
                "url": self.file_url(date, image.name),
            }
            for image in self._folder_images(folder)
        ]

    def resolve_file(self, date: str, name: str) -> Path | None:
        if not _DATE.fullmatch(date) or Path(name).name != name or Path(name).suffix.lower() not in _IMAGE_SUFFIXES:
            return None
        candidate = (self.config.output_dir / date / name).resolve()
        try:
            candidate.relative_to(self.config.output_dir.resolve())
        except ValueError:
            return None
        return candidate if candidate.is_file() else None

    @staticmethod
    def file_url(date: str, name: str) -> str:
        from urllib.parse import quote
        return f"/api/v1/files/{quote(date)}/{quote(name)}"

    @staticmethod
    def public_job(job: dict[str, Any]) -> dict[str, Any]:
        total = max(1, int(job["total"]))
        terminal = job["status"] in {"completed", "partial_failed", "failed", "canceled"}
        progress = (int(job["completed"]) + int(job["failed"])) / total if terminal else int(job["completed"]) / total
        return {
            "id": job["id"],
            "status": job["status"],
            "created_at": job["created_at"],
            "updated_at": job["updated_at"],
            "total": int(job["total"]),
            "completed": int(job["completed"]),
            "failed": int(job["failed"]),
            "progress": min(1.0, progress),
            "cancel_requested": bool(job["cancel_requested"]),
            "error": job["error"],
        }

    def _worker_loop(self) -> None:
        while not self._stop.is_set():
            job = self.database.next_job()
            if not job:
                self._wake.clear()
                self._wake.wait(timeout=2)
                continue
            job_id = str(job["id"])
            if job["cancel_requested"]:
                self.database.finish_job(job_id)
                continue
            self.database.start_job(job_id)
            with self._active_lock:
                self._active_job_id = job_id
            try:
                self._run_job(job_id)
            finally:
                with self._active_lock:
                    self._active_job_id = None

    def _run_job(self, job_id: str) -> None:
        while not self._stop.is_set():
            job = self.database.get_job(job_id)
            if not job or job["cancel_requested"]:
                self.database.finish_job(job_id)
                return
            task = self.database.next_task(job_id)
            if not task:
                self.database.finish_job(job_id)
                return
            index = int(task["task_index"])
            self.database.start_task(job_id, index)
            try:
                existing = self._existing_output(task["payload"])
                if existing is not None:
                    self.database.finish_task(job_id, index, existing)
                    continue
                sd_payload = {key: value for key, value in task["payload"].items() if not key.startswith("_agent_")}
                image, suffix = self.sd.generate(sd_payload)
                relative_path = self._save_image(job_id, index, suffix, image, task["payload"], sd_payload)
                self.database.finish_task(job_id, index, relative_path)
            except Exception as error:
                # start_task increments attempts; retry_count means retries after the first attempt.
                attempts = self.database.task_attempts(job_id, index)
                if attempts <= self.config.retry_count:
                    self.database.retry_task(job_id, index, str(error))
                    time.sleep(min(2**attempts, 10))
                else:
                    self.database.fail_task(job_id, index, str(error))

    def _existing_output(self, payload: dict[str, Any]) -> str | None:
        date = str(payload.get("_agent_output_date", ""))
        base = str(payload.get("_agent_output_base", ""))
        if not _DATE.fullmatch(date) or not base:
            return None
        folder = self.config.output_dir / date
        for suffix in _IMAGE_SUFFIXES:
            candidate = folder / f"{base}{suffix}"
            if candidate.is_file():
                return f"{date}/{candidate.name}"
        return None

    def _save_image(
        self,
        job_id: str,
        index: int,
        suffix: str,
        content: bytes,
        stored_payload: dict[str, Any],
        sd_payload: dict[str, Any],
    ) -> str:
        now = datetime.now().astimezone()
        date = str(stored_payload["_agent_output_date"])
        folder = self.config.output_dir / date
        folder.mkdir(parents=True, exist_ok=True)
        filename = f"{stored_payload['_agent_output_base']}{suffix}"
        destination = folder / filename
        temporary = destination.with_suffix(destination.suffix + ".part")
        with temporary.open("wb") as stream:
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, destination)
        metadata_dir = self.config.database_path.parent / "metadata" / date
        metadata_dir.mkdir(parents=True, exist_ok=True)
        metadata = {
            "job_id": job_id,
            "task_index": index,
            "created_at": now.isoformat(timespec="milliseconds"),
            "file": f"{date}/{filename}",
            "parameters": sd_payload,
        }
        metadata_path = metadata_dir / f"{filename}.json"
        metadata_path.write_text(json.dumps(metadata, ensure_ascii=False, indent=2), encoding="utf-8")
        return f"{date}/{filename}"

    @staticmethod
    def _folder_images(folder: Path) -> list[Path]:
        return sorted(
            [item for item in folder.iterdir() if item.is_file() and item.suffix.lower() in _IMAGE_SUFFIXES],
            key=lambda item: (item.stat().st_mtime_ns, item.name),
            reverse=True,
        )

    @staticmethod
    def _integer(value: Any, name: str, minimum: int, maximum: int) -> int:
        try:
            result = int(value)
        except (TypeError, ValueError) as error:
            raise ValueError(f"{name} must be an integer") from error
        if not minimum <= result <= maximum:
            raise ValueError(f"{name} must be between {minimum} and {maximum}")
        return result

    def _validate_task(self, task: Any) -> dict[str, Any]:
        if not isinstance(task, dict):
            raise ValueError("each task must be an object")
        prompt = str(task.get("prompt", "")).strip()
        if not prompt:
            raise ValueError("prompt is required")
        width = self._integer(task.get("width", 720), "width", 64, 4096)
        height = self._integer(task.get("height", 1280), "height", 64, 4096)
        steps = self._integer(task.get("steps", 20), "steps", 1, 300)
        result = dict(task)
        result.update({
            "prompt": prompt,
            "negative_prompt": str(task.get("negative_prompt", "")),
            "width": width,
            "height": height,
            "steps": steps,
            "cfg_scale": float(task.get("cfg_scale", 7)),
            "sampler_name": str(task.get("sampler_name", "Euler a")),
        })
        result.pop("batch_size", None)
        result.pop("n_iter", None)
        return result
