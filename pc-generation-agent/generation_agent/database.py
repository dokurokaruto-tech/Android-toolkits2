from __future__ import annotations

import json
import sqlite3
import threading
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds")


class JobDatabase:
    """Small SQLite queue. Every mutation commits before an HTTP response is returned."""

    def __init__(self, path: Path):
        path.parent.mkdir(parents=True, exist_ok=True)
        self._connection = sqlite3.connect(path, check_same_thread=False)
        self._connection.row_factory = sqlite3.Row
        self._lock = threading.RLock()
        with self._lock, self._connection:
            self._connection.executescript(
                """
                PRAGMA journal_mode=WAL;
                PRAGMA synchronous=FULL;
                CREATE TABLE IF NOT EXISTS jobs (
                    id TEXT PRIMARY KEY,
                    client_request_id TEXT UNIQUE,
                    status TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    total INTEGER NOT NULL,
                    completed INTEGER NOT NULL DEFAULT 0,
                    failed INTEGER NOT NULL DEFAULT 0,
                    cancel_requested INTEGER NOT NULL DEFAULT 0,
                    error TEXT
                );
                CREATE TABLE IF NOT EXISTS tasks (
                    job_id TEXT NOT NULL,
                    task_index INTEGER NOT NULL,
                    status TEXT NOT NULL,
                    payload TEXT NOT NULL,
                    output_path TEXT,
                    error TEXT,
                    attempts INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY(job_id, task_index),
                    FOREIGN KEY(job_id) REFERENCES jobs(id) ON DELETE CASCADE
                );
                CREATE INDEX IF NOT EXISTS idx_jobs_queue ON jobs(status, created_at);
                """
            )
            # A process may have stopped while SD was generating. Requeue that unit.
            self._connection.execute(
                "UPDATE tasks SET status='pending' WHERE status='running'"
            )
            self._connection.execute(
                "UPDATE jobs SET status='queued', updated_at=? WHERE status='running'",
                (utc_now(),),
            )

    def close(self) -> None:
        with self._lock:
            self._connection.close()

    def create_job(self, job_id: str, client_request_id: str, tasks: list[dict[str, Any]]) -> tuple[dict[str, Any], bool]:
        now = utc_now()
        with self._lock, self._connection:
            if client_request_id:
                existing = self._connection.execute(
                    "SELECT id FROM jobs WHERE client_request_id=?", (client_request_id,)
                ).fetchone()
                if existing:
                    return self.get_job(existing["id"]), False
            self._connection.execute(
                "INSERT INTO jobs(id, client_request_id, status, created_at, updated_at, total) VALUES(?,?,?,?,?,?)",
                (job_id, client_request_id or None, "queued", now, now, len(tasks)),
            )
            self._connection.executemany(
                "INSERT INTO tasks(job_id, task_index, status, payload) VALUES(?,?,?,?)",
                [(job_id, index, "pending", json.dumps(task, ensure_ascii=False)) for index, task in enumerate(tasks)],
            )
        return self.get_job(job_id), True

    def next_job(self) -> dict[str, Any] | None:
        with self._lock:
            row = self._connection.execute(
                "SELECT id FROM jobs WHERE status='queued' ORDER BY created_at LIMIT 1"
            ).fetchone()
            return self.get_job(row["id"]) if row else None

    def next_task(self, job_id: str) -> dict[str, Any] | None:
        with self._lock:
            row = self._connection.execute(
                "SELECT * FROM tasks WHERE job_id=? AND status='pending' ORDER BY task_index LIMIT 1",
                (job_id,),
            ).fetchone()
            return self._task_from_row(row)

    def pending_tasks(self, job_id: str) -> list[dict[str, Any]]:
        with self._lock:
            rows = self._connection.execute(
                "SELECT * FROM tasks WHERE job_id=? AND status='pending' ORDER BY task_index",
                (job_id,),
            ).fetchall()
            return [self._task_from_row(row) for row in rows if row is not None]

    def pending_count(self, job_id: str) -> int:
        with self._lock:
            row = self._connection.execute(
                "SELECT COUNT(*) AS n FROM tasks WHERE job_id=? AND status='pending'",
                (job_id,),
            ).fetchone()
            return int(row["n"]) if row else 0

    def get_task(self, job_id: str, index: int) -> dict[str, Any] | None:
        with self._lock:
            row = self._connection.execute(
                "SELECT * FROM tasks WHERE job_id=? AND task_index=?",
                (job_id, index),
            ).fetchone()
            return self._task_from_row(row)

    def update_task_payload(self, job_id: str, index: int, payload: dict[str, Any]) -> bool:
        with self._lock, self._connection:
            cursor = self._connection.execute(
                "UPDATE tasks SET payload=? WHERE job_id=? AND task_index=? AND status='pending'",
                (json.dumps(payload, ensure_ascii=False), job_id, index),
            )
            if cursor.rowcount > 0:
                self._connection.execute(
                    "UPDATE jobs SET updated_at=? WHERE id=?",
                    (utc_now(), job_id),
                )
            return cursor.rowcount > 0

    @staticmethod
    def _task_from_row(row: sqlite3.Row | None) -> dict[str, Any] | None:
        if row is None:
            return None
        result = dict(row)
        result["payload"] = json.loads(result["payload"])
        return result

    def get_job(self, job_id: str) -> dict[str, Any] | None:
        with self._lock:
            row = self._connection.execute("SELECT * FROM jobs WHERE id=?", (job_id,)).fetchone()
            return dict(row) if row else None

    def list_jobs(self, limit: int = 30) -> list[dict[str, Any]]:
        with self._lock:
            rows = self._connection.execute(
                "SELECT * FROM jobs ORDER BY created_at DESC LIMIT ?", (max(1, min(limit, 100)),)
            ).fetchall()
            return [dict(row) for row in rows]

    def completed_outputs(self, job_id: str) -> list[str]:
        with self._lock:
            rows = self._connection.execute(
                "SELECT output_path FROM tasks WHERE job_id=? AND status='completed' "
                "AND output_path IS NOT NULL ORDER BY task_index",
                (job_id,),
            ).fetchall()
            return [str(row["output_path"]) for row in rows]

    def start_job(self, job_id: str) -> None:
        self._update_job(job_id, status="running", updated_at=utc_now())

    def start_task(self, job_id: str, index: int) -> None:
        with self._lock, self._connection:
            self._connection.execute(
                "UPDATE tasks SET status='running', attempts=attempts+1 WHERE job_id=? AND task_index=?",
                (job_id, index),
            )
            self._connection.execute("UPDATE jobs SET updated_at=? WHERE id=?", (utc_now(), job_id))

    def task_attempts(self, job_id: str, index: int) -> int:
        with self._lock:
            row = self._connection.execute(
                "SELECT attempts FROM tasks WHERE job_id=? AND task_index=?", (job_id, index)
            ).fetchone()
            return int(row["attempts"]) if row else 0

    def retry_task(self, job_id: str, index: int, error: str) -> None:
        with self._lock, self._connection:
            self._connection.execute(
                "UPDATE tasks SET status='pending', error=? WHERE job_id=? AND task_index=?",
                (error[:4000], job_id, index),
            )

    def finish_task(self, job_id: str, index: int, output_path: str) -> None:
        with self._lock, self._connection:
            self._connection.execute(
                "UPDATE tasks SET status='completed', output_path=?, error=NULL WHERE job_id=? AND task_index=?",
                (output_path, job_id, index),
            )
            self._connection.execute(
                "UPDATE jobs SET completed=completed+1, updated_at=? WHERE id=?",
                (utc_now(), job_id),
            )

    def fail_task(self, job_id: str, index: int, error: str) -> None:
        with self._lock, self._connection:
            self._connection.execute(
                "UPDATE tasks SET status='failed', error=? WHERE job_id=? AND task_index=?",
                (error[:4000], job_id, index),
            )
            self._connection.execute(
                "UPDATE jobs SET failed=failed+1, error=?, updated_at=? WHERE id=?",
                (error[:4000], utc_now(), job_id),
            )

    def finish_job(self, job_id: str) -> None:
        job = self.get_job(job_id)
        if not job:
            return
        if job["cancel_requested"]:
            status = "canceled"
        elif job["failed"]:
            status = "partial_failed" if job["completed"] else "failed"
        else:
            status = "completed"
        self._update_job(job_id, status=status, updated_at=utc_now())

    def request_cancel(self, job_id: str) -> bool:
        with self._lock, self._connection:
            cursor = self._connection.execute(
                "UPDATE jobs SET cancel_requested=1, updated_at=? WHERE id=? AND status IN ('queued','running')",
                (utc_now(), job_id),
            )
            return cursor.rowcount > 0

    def _update_job(self, job_id: str, **values: Any) -> None:
        if not values:
            return
        columns = ", ".join(f"{column}=?" for column in values)
        with self._lock, self._connection:
            self._connection.execute(
                f"UPDATE jobs SET {columns} WHERE id=?", (*values.values(), job_id)
            )
