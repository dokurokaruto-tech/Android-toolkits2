from __future__ import annotations

import hashlib
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
        self.config.thumbnail_dir.mkdir(parents=True, exist_ok=True)
        self.config.mobile_thumbnail_dir.mkdir(parents=True, exist_ok=True)
        self.config.progressive_tile_dir.mkdir(parents=True, exist_ok=True)
        self.database = JobDatabase(config.database_path)
        self.sd = StableDiffusionClient(config.sd_base_url, config.request_timeout_seconds)
        self._wake = threading.Event()
        self._stop = threading.Event()
        self._worker = threading.Thread(target=self._worker_loop, name="generation-worker", daemon=True)
        self._active_job_id: str | None = None
        self._active_lock = threading.Lock()
        self._mobile_thumbnail_lock = threading.Lock()
        self._progressive_tile_lock = threading.Lock()

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
            prefix = "THUMB" if task["_agent_collection"] == "thumbnail" else "GEN"
            task["_agent_output_base"] = f"{prefix}_{stamp}_{job_id[:8]}_{index + 1:04d}"
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
                    "thumbnail_url": self.mobile_thumbnail_url(folder.name, images[0].name),
                })
        return sorted(folders, key=lambda item: item["date"], reverse=True)

    def library_images(self, date: str) -> list[dict[str, Any]]:
        if not _DATE.fullmatch(date):
            raise ValueError("date must use YYYY-MM-DD")
        folder = self.config.output_dir / date
        if not folder.is_dir():
            return []
        return [
            self._public_library_image(date, image)
            for image in self._folder_images(folder)
        ]

    def delete_library_image(self, date: str, name: str) -> bool:
        source = self.resolve_file(date, name)
        if source is None:
            return False
        source.unlink(missing_ok=True)
        metadata = self.config.database_path.parent / "metadata" / date / f"{name}.json"
        metadata.unlink(missing_ok=True)
        folder = self.config.output_dir / date
        if folder.is_dir() and not any(folder.iterdir()):
            folder.rmdir()
        return True

    def resolve_file(self, date: str, name: str) -> Path | None:
        return self._resolve_collection_file(self.config.output_dir, date, name)

    def resolve_thumbnail_file(self, date: str, name: str) -> Path | None:
        return self._resolve_collection_file(self.config.thumbnail_dir, date, name)

    @staticmethod
    def _resolve_collection_file(root: Path, date: str, name: str) -> Path | None:
        if not _DATE.fullmatch(date) or Path(name).name != name or Path(name).suffix.lower() not in _IMAGE_SUFFIXES:
            return None
        candidate = (root / date / name).resolve()
        try:
            candidate.relative_to(root.resolve())
        except ValueError:
            return None
        return candidate if candidate.is_file() else None

    @staticmethod
    def file_url(date: str, name: str) -> str:
        from urllib.parse import quote
        return f"/api/v1/files/{quote(date)}/{quote(name)}"

    @staticmethod
    def mobile_thumbnail_url(date: str, name: str) -> str:
        from urllib.parse import quote
        return f"/api/v1/mobile-thumbnails/{quote(date)}/{quote(name)}"

    def mobile_thumbnail_file(self, date: str, name: str) -> Path | None:
        source = self.resolve_file(date, name)
        if source is None:
            return None
        stat = source.stat()
        cache_key = hashlib.sha256(
            f"{source.resolve()}:{stat.st_mtime_ns}:{stat.st_size}:480x854:q72".encode("utf-8")
        ).hexdigest()[:24]
        cache_dir = self.config.mobile_thumbnail_dir / date
        destination = cache_dir / f"{cache_key}.jpg"
        if destination.is_file():
            return destination

        with self._mobile_thumbnail_lock:
            if destination.is_file():
                return destination
            try:
                from PIL import Image, ImageOps
            except ImportError as error:
                raise RuntimeError(
                    "Pillow is required for mobile thumbnails. Run start-agent.bat again."
                ) from error
            cache_dir.mkdir(parents=True, exist_ok=True)
            temporary = destination.with_suffix(".jpg.part")
            try:
                with Image.open(source) as image:
                    image = ImageOps.exif_transpose(image)
                    image.thumbnail((480, 854), Image.Resampling.LANCZOS)
                    if image.mode != "RGB":
                        if "A" in image.getbands():
                            background = Image.new("RGB", image.size, "black")
                            background.paste(image, mask=image.getchannel("A"))
                            image = background
                        else:
                            image = image.convert("RGB")
                    image.save(temporary, "JPEG", quality=72, optimize=True, progressive=True)
                os.replace(temporary, destination)
            finally:
                if temporary.exists():
                    temporary.unlink(missing_ok=True)
        return destination

    def progressive_manifest(self, date: str, name: str) -> dict[str, Any] | None:
        package = self._ensure_progressive_tiles(date, name)
        if package is None:
            return None
        manifest_path, manifest = package
        del manifest_path
        from urllib.parse import quote
        encoded_date = quote(date)
        encoded_name = quote(name)
        return {
            "width": manifest["width"],
            "height": manifest["height"],
            "tile_height": manifest["tile_height"],
            "tiles": [
                {
                    "index": tile["index"],
                    "top": tile["top"],
                    "height": tile["height"],
                    "url": f"/api/v1/progressive/{encoded_date}/{encoded_name}/{tile['index']}",
                }
                for tile in manifest["tiles"]
            ],
        }

    def progressive_tile_file(self, date: str, name: str, index: int) -> Path | None:
        package = self._ensure_progressive_tiles(date, name)
        if package is None:
            return None
        manifest_path, manifest = package
        tile = next((item for item in manifest["tiles"] if item["index"] == index), None)
        if tile is None:
            return None
        path = manifest_path.parent / tile["file"]
        return path if path.is_file() else None

    def _ensure_progressive_tiles(self, date: str, name: str) -> tuple[Path, dict[str, Any]] | None:
        source = self.resolve_file(date, name)
        if source is None:
            return None
        stat = source.stat()
        tile_height = 128
        cache_key = hashlib.sha256(
            f"{source.resolve()}:{stat.st_mtime_ns}:{stat.st_size}:tiles:{tile_height}:png".encode("utf-8")
        ).hexdigest()[:24]
        package_dir = self.config.progressive_tile_dir / date / cache_key
        manifest_path = package_dir / "manifest.json"

        def read_manifest() -> dict[str, Any] | None:
            if not manifest_path.is_file():
                return None
            try:
                return json.loads(manifest_path.read_text(encoding="utf-8"))
            except Exception:
                return None

        cached = read_manifest()
        if cached is not None:
            return manifest_path, cached

        with self._progressive_tile_lock:
            cached = read_manifest()
            if cached is not None:
                return manifest_path, cached
            try:
                from PIL import Image, ImageOps
            except ImportError as error:
                raise RuntimeError(
                    "Pillow is required for progressive image tiles. Run start-agent.bat again."
                ) from error

            temporary_dir = package_dir.with_name(package_dir.name + ".part")
            if temporary_dir.exists():
                import shutil
                shutil.rmtree(temporary_dir, ignore_errors=True)
            temporary_dir.mkdir(parents=True, exist_ok=True)
            try:
                with Image.open(source) as opened:
                    image = ImageOps.exif_transpose(opened)
                    width, height = image.size
                    tiles: list[dict[str, Any]] = []
                    for index, top in enumerate(range(0, height, tile_height)):
                        bottom = min(top + tile_height, height)
                        tile_name = f"{index:05d}.png"
                        # Lossless strips preserve original pixels and can be decoded independently.
                        image.crop((0, top, width, bottom)).save(
                            temporary_dir / tile_name, "PNG", optimize=False
                        )
                        tiles.append({
                            "index": index,
                            "top": top,
                            "height": bottom - top,
                            "file": tile_name,
                        })
                manifest = {
                    "width": width,
                    "height": height,
                    "tile_height": tile_height,
                    "tiles": tiles,
                }
                (temporary_dir / "manifest.json").write_text(
                    json.dumps(manifest, ensure_ascii=False), encoding="utf-8"
                )
                package_dir.parent.mkdir(parents=True, exist_ok=True)
                os.replace(temporary_dir, package_dir)
            finally:
                if temporary_dir.exists():
                    import shutil
                    shutil.rmtree(temporary_dir, ignore_errors=True)
            return manifest_path, manifest

    @staticmethod
    def thumbnail_file_url(date: str, name: str) -> str:
        from urllib.parse import quote
        return f"/api/v1/thumbnail-files/{quote(date)}/{quote(name)}"

    def output_url(self, output_path: str) -> str | None:
        parts = output_path.replace("\\", "/").split("/")
        if len(parts) == 2:  # Compatibility with jobs created by agent 1.0.
            return self.file_url(parts[0], parts[1])
        if len(parts) != 3:
            return None
        collection, date, name = parts
        if collection == "thumbnail":
            return self.thumbnail_file_url(date, name)
        if collection == "image":
            return self.file_url(date, name)
        return None

    def public_job(self, job: dict[str, Any]) -> dict[str, Any]:
        total = max(1, int(job["total"]))
        terminal = job["status"] in {"completed", "partial_failed", "failed", "canceled"}
        progress = (int(job["completed"]) + int(job["failed"])) / total if terminal else int(job["completed"]) / total
        images = []
        for output_path in self.database.completed_outputs(str(job["id"])):
            url = self.output_url(output_path)
            if url:
                images.append({"url": url, "output_path": output_path})
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
            "images": images,
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
                    self._write_metadata(job_id, index, existing, task["payload"], None)
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

    def _collection_root(self, payload: dict[str, Any]) -> tuple[str, Path]:
        collection = str(payload.get("_agent_collection", "image"))
        if collection == "thumbnail":
            return collection, self.config.thumbnail_dir
        return "image", self.config.output_dir

    def _existing_output(self, payload: dict[str, Any]) -> str | None:
        date = str(payload.get("_agent_output_date", ""))
        base = str(payload.get("_agent_output_base", ""))
        if not _DATE.fullmatch(date) or not base:
            return None
        collection, root = self._collection_root(payload)
        folder = root / date
        for suffix in _IMAGE_SUFFIXES:
            candidate = folder / f"{base}{suffix}"
            if candidate.is_file():
                return f"{collection}/{date}/{candidate.name}"
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
        collection, root = self._collection_root(stored_payload)
        folder = root / date
        folder.mkdir(parents=True, exist_ok=True)
        filename = f"{stored_payload['_agent_output_base']}{suffix}"
        destination = folder / filename
        temporary = destination.with_suffix(destination.suffix + ".part")
        with temporary.open("wb") as stream:
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, destination)
        self._write_metadata(job_id, index, f"{collection}/{date}/{filename}", stored_payload, sd_payload)
        if collection == "image":
            # Prepare lossless top-down strips while the PC is already processing the result,
            # so mobile viewing can start with the first real rows immediately.
            try:
                self._ensure_progressive_tiles(date, filename)
            except Exception as error:
                print(f"Progressive tile preparation failed for {filename}: {error}")
        return f"{collection}/{date}/{filename}"

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
        purpose = str(task.get("purpose", "image")).strip().lower()
        if purpose not in {"image", "thumbnail"}:
            raise ValueError("purpose must be image or thumbnail")
        result = dict(task)
        result.pop("purpose", None)
        result["_agent_collection"] = purpose
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
        result.pop("tags", None)
        result.pop("card_states", None)
        result.pop("random_picked_ids", None)
        result.pop("random_categories", None)
        result["_agent_tags"] = self._normalize_tags(task.get("tags"))
        result["_agent_card_states"] = self._normalize_card_states(task.get("card_states"))
        result["_agent_random_picked_ids"] = self._normalize_id_list(task.get("random_picked_ids"))
        result["_agent_random_categories"] = self._normalize_id_list(task.get("random_categories"))
        return result

    @staticmethod
    def _normalize_tags(raw: Any) -> list[str]:
        if raw is None:
            return []
        if not isinstance(raw, list):
            raise ValueError("tags must be an array")
        tags: list[str] = []
        for item in raw:
            text = str(item).strip()
            if text and text not in tags:
                tags.append(text[:80])
            if len(tags) > 64:
                raise ValueError("a task may contain at most 64 tags")
        return tags

    @staticmethod
    def _normalize_card_states(raw: Any) -> dict[str, int]:
        if raw is None:
            return {}
        if not isinstance(raw, dict):
            raise ValueError("card_states must be an object")
        states: dict[str, int] = {}
        for key, value in raw.items():
            card_id = str(key).strip()[:80]
            try:
                level = int(value)
            except (TypeError, ValueError) as error:
                raise ValueError("card_states values must be integers") from error
            if card_id and 1 <= level <= 3:
                states[card_id] = level
            if len(states) > 128:
                raise ValueError("a task may contain at most 128 card_states")
        return states

    @staticmethod
    def _normalize_id_list(raw: Any) -> list[str]:
        if raw is None:
            return []
        if not isinstance(raw, list):
            raise ValueError("id list must be an array")
        values: list[str] = []
        for item in raw:
            text = str(item).strip()[:80]
            if text and text not in values:
                values.append(text)
            if len(values) > 128:
                raise ValueError("a task may contain at most 128 ids")
        return values

    def _metadata_path(self, date: str, name: str) -> Path:
        return self.config.database_path.parent / "metadata" / date / f"{name}.json"

    def _read_image_metadata(self, date: str, name: str) -> dict[str, Any]:
        path = self._metadata_path(date, name)
        if not path.is_file():
            return {}
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except Exception:
            return {}
        return data if isinstance(data, dict) else {}

    def _read_image_tags(self, date: str, name: str) -> list[str]:
        tags = self._read_image_metadata(date, name).get("tags")
        if not isinstance(tags, list):
            return []
        return [str(tag).strip() for tag in tags if str(tag).strip()]

    def _public_library_image(self, date: str, image: Path) -> dict[str, Any]:
        metadata = self._read_image_metadata(date, image.name)
        tags = metadata.get("tags")
        card_states = metadata.get("card_states")
        parameters = metadata.get("parameters")
        return {
            "name": image.name,
            "date": date,
            "size": image.stat().st_size,
            "created_at": datetime.fromtimestamp(image.stat().st_mtime).astimezone().isoformat(timespec="seconds"),
            "url": self.file_url(date, image.name),
            "thumbnail_url": self.mobile_thumbnail_url(date, image.name),
            "tags": [str(tag).strip() for tag in tags if str(tag).strip()] if isinstance(tags, list) else [],
            "card_states": card_states if isinstance(card_states, dict) else {},
            "parameters": parameters if isinstance(parameters, dict) else {},
        }

    def _write_metadata(
        self,
        job_id: str,
        index: int,
        relative_path: str,
        stored_payload: dict[str, Any],
        sd_payload: dict[str, Any] | None,
    ) -> None:
        parts = relative_path.replace("\\", "/").split("/")
        if len(parts) == 2:
            date, name = parts
        elif len(parts) == 3:
            _, date, name = parts
        else:
            return
        metadata_dir = self.config.database_path.parent / "metadata" / date
        metadata_dir.mkdir(parents=True, exist_ok=True)
        path = metadata_dir / f"{name}.json"
        existing: dict[str, Any] = {}
        if path.is_file():
            try:
                loaded = json.loads(path.read_text(encoding="utf-8"))
                if isinstance(loaded, dict):
                    existing = loaded
            except Exception:
                existing = {}
        tags = stored_payload.get("_agent_tags") or existing.get("tags") or []
        card_states = stored_payload.get("_agent_card_states") or existing.get("card_states") or {}
        random_picked = stored_payload.get("_agent_random_picked_ids") or existing.get("random_picked_ids") or []
        random_categories = stored_payload.get("_agent_random_categories") or existing.get("random_categories") or []
        metadata = {
            "job_id": job_id,
            "task_index": index,
            "created_at": existing.get("created_at") or datetime.now().astimezone().isoformat(timespec="milliseconds"),
            "file": relative_path if relative_path.count("/") == 2 else f"image/{relative_path}",
            "parameters": sd_payload if sd_payload is not None else existing.get("parameters", {}),
            "tags": tags if isinstance(tags, list) else [],
            "card_states": card_states if isinstance(card_states, dict) else {},
            "random_picked_ids": random_picked if isinstance(random_picked, list) else [],
            "random_categories": random_categories if isinstance(random_categories, list) else [],
        }
        path.write_text(json.dumps(metadata, ensure_ascii=False, indent=2), encoding="utf-8")
