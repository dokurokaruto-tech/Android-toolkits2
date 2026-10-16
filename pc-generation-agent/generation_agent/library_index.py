"""Persistent keyset index. Directory scans never run on a page request."""
from __future__ import annotations

import base64
import json
import os
import re
import sqlite3
import threading
import time
from pathlib import Path

_DATE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
_SUFFIXES = {".png", ".jpg", ".jpeg", ".webp"}
_INDEX_BATCH = 128
_REFRESH_SECONDS = 30


class LibraryIndex:
    def __init__(self, database: Path, root: Path):
        self.database = database
        self.root = root
        self._lock = threading.RLock()
        self._running: dict[str, threading.Thread] = {}
        self._last: dict[str, float] = {}
        self._closed = False
        self._errors: dict[str, str] = {}
        with self._connect() as db:
            db.execute("PRAGMA journal_mode=WAL")
            db.execute("CREATE TABLE IF NOT EXISTS library_files (date TEXT, name TEXT, mtime INTEGER, seen TEXT, PRIMARY KEY(date,name))")
            db.execute("CREATE INDEX IF NOT EXISTS library_order ON library_files(date,mtime DESC,name DESC)")
            db.execute("CREATE TABLE IF NOT EXISTS library_ready (date TEXT PRIMARY KEY)")

    def _connect(self):
        return sqlite3.connect(self.database, timeout=30)

    def refresh(self, date: str):
        if not _DATE.fullmatch(date):
            raise ValueError("date must use YYYY-MM-DD")
        with self._lock:
            if self._closed or date in self._running:
                return
            if date in self._last and time.monotonic() - self._last[date] < _REFRESH_SECONDS:
                return
            self._errors.pop(date, None)
            thread = threading.Thread(target=self._scan, args=(date,), daemon=True, name="library-index")
            self._running[date] = thread
            thread.start()

    def _scan(self, date: str):
        # Filesystem discovery is background work. Existing indexed pages remain usable.
        stamp = str(time.time_ns())
        try:
            folder = self.root / date
            if folder.is_dir():
                batch = []
                with os.scandir(folder) as entries:
                    for entry in entries:
                        if self._closed:
                            return
                        if Path(entry.name).suffix.lower() not in _SUFFIXES or not entry.is_file():
                            continue
                        try:
                            batch.append((date, entry.name, entry.stat().st_mtime_ns, stamp))
                        except FileNotFoundError:
                            continue
                        if len(batch) >= _INDEX_BATCH:
                            self._write_batch(batch)
                            batch.clear()
                self._write_batch(batch)
            # Check stale entries in bounded batches without holding the refresh lock.
            last_name = ""
            while not self._closed:
                with self._connect() as db:
                    stale = db.execute(
                        "SELECT name FROM library_files WHERE date=? AND seen<>? AND name>? ORDER BY name LIMIT ?",
                        (date, stamp, last_name, _INDEX_BATCH),
                    ).fetchall()
                if not stale:
                    break
                missing = [(date, name) for (name,) in stale if not (folder / name).is_file()]
                with self._lock, self._connect() as db:
                    db.executemany("DELETE FROM library_files WHERE date=? AND name=?", missing)
                last_name = stale[-1][0]
            if not self._closed:
                with self._connect() as db:
                    db.execute("INSERT OR IGNORE INTO library_ready VALUES (?)", (date,))
        except (OSError, sqlite3.Error) as error:
            with self._lock:
                self._errors[date] = str(error)
        finally:
            with self._lock:
                self._running.pop(date, None)
                self._last[date] = time.monotonic()

    def _write_batch(self, batch):
        with self._lock, self._connect() as db:
            db.executemany("INSERT OR REPLACE INTO library_files VALUES (?,?,?,?)", batch)

    def summary(self, date: str):
        # Do not start one scan per date merely to draw the folder picker.
        with self._connect() as db:
            count = db.execute("SELECT COUNT(*) FROM library_files WHERE date=?", (date,)).fetchone()[0]
            if count == 0 and not db.execute("SELECT 1 FROM library_ready WHERE date=?", (date,)).fetchone():
                count = -1
            first = db.execute("SELECT name FROM library_files WHERE date=? ORDER BY mtime DESC,name DESC LIMIT 1", (date,)).fetchone()
        return count, first[0] if first else None

    def upsert(self, date: str, file: Path):
        with self._lock, self._connect() as db:
            db.execute("INSERT OR REPLACE INTO library_files VALUES (?,?,?,?)", (date, file.name, file.stat().st_mtime_ns, "live"))

    def remove(self, date: str, name: str):
        with self._lock, self._connect() as db:
            db.execute("DELETE FROM library_files WHERE date=? AND name=?", (date, name))

    def page(self, date: str, limit: int, cursor: str = "") -> dict:
        if not _DATE.fullmatch(date) or not 1 <= limit <= 200:
            raise ValueError("invalid date or page size (1..200)")
        after = None
        if cursor:
            try:
                if len(cursor) > 2048:
                    raise ValueError()
                value = json.loads(base64.urlsafe_b64decode(cursor))
                if value[0] != date or not isinstance(value[1], int) or not isinstance(value[2], str):
                    raise ValueError()
                if not 0 <= value[1] <= 2**63 - 1:
                    raise ValueError()
                after = value[1:3]
            except Exception as error:
                raise ValueError("invalid library cursor") from error
        self.refresh(date)
        with self._connect() as db:
            ready = db.execute("SELECT 1 FROM library_ready WHERE date=?", (date,)).fetchone()
            if not ready:
                if date in self._errors:
                    raise ValueError("library indexing failed: " + self._errors[date])
                return {"names": [], "next_cursor": None, "indexing": True}
            condition = "date=?"
            args: list = [date]
            if after:
                condition += " AND (mtime,name)<(?,?)"
                args.extend(after)
            rows = db.execute(f"SELECT name,mtime FROM library_files WHERE {condition} ORDER BY mtime DESC,name DESC LIMIT ?", (*args, limit + 1)).fetchall()
        next_cursor = None
        if len(rows) > limit:
            name, mtime = rows[limit - 1]
            next_cursor = base64.urlsafe_b64encode(json.dumps([date, mtime, name]).encode()).decode()
        return {"names": [row[0] for row in rows[:limit]], "next_cursor": next_cursor, "indexing": False}

    def close(self):
        with self._lock:
            self._closed = True
            threads = list(self._running.values())
        for thread in threads:
            thread.join(timeout=5)
