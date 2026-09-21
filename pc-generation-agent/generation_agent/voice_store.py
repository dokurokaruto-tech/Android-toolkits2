"""Persistent, tag-scoped reference audio. No audio bytes in the job database."""
from __future__ import annotations

import base64
import binascii
import hashlib
import re
import sqlite3
import threading
import uuid
from contextlib import closing
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

MAX_SAMPLE_BYTES = 6 * 1024 * 1024
_MAX_STORE_BYTES = 512 * 1024 * 1024
_MAX_TAG_SAMPLES = 256
_TAG_ID = re.compile(r"[0-9a-f]{32}")
_SAMPLE_ID = re.compile(r"[0-9a-f]{64}")


class VoiceMissingError(RuntimeError):
    pass


class VoiceChangedError(RuntimeError):
    pass


def check_ids(tag_id: str, sample_id: str | None = None) -> None:
    if not isinstance(tag_id, str) or not _TAG_ID.fullmatch(tag_id):
        raise ValueError("invalid voice tag ID")
    if sample_id is not None and (not isinstance(sample_id, str) or not _SAMPLE_ID.fullmatch(sample_id)):
        raise ValueError("invalid voice sample ID")


def decode_sample(encoded: Any) -> bytes:
    if not isinstance(encoded, str) or len(encoded) > ((MAX_SAMPLE_BYTES + 2) // 3) * 4:
        raise ValueError("サンプル音声は6 MiB以下にしてください。")
    try:
        audio = base64.b64decode(encoded, validate=True)
    except (ValueError, binascii.Error) as error:
        raise ValueError("サンプル音声のデータが不正です。") from error
    if not audio or len(audio) > MAX_SAMPLE_BYTES:
        raise ValueError("サンプル音声が空か、6 MiBを超えています。")
    return audio


class VoiceStore:
    def __init__(self, path: Path):
        self._path = path
        self._lock = threading.Lock()
        path.parent.mkdir(parents=True, exist_ok=True)
        with closing(self._connect()) as db, db:
            db.execute("CREATE TABLE IF NOT EXISTS voice_tags (tag_id TEXT PRIMARY KEY, epoch TEXT NOT NULL)")
            db.execute("""CREATE TABLE IF NOT EXISTS voices (
                tag_id TEXT NOT NULL, sample_id TEXT NOT NULL, tag_name TEXT NOT NULL,
                name TEXT NOT NULL, created_at TEXT NOT NULL, audio BLOB NOT NULL,
                PRIMARY KEY (tag_id, sample_id))""")

    def _connect(self) -> sqlite3.Connection:
        db = sqlite3.connect(self._path, timeout=30)
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA secure_delete=ON")
        return db

    def _epoch(self, db: sqlite3.Connection, tag_id: str) -> str:
        db.execute("INSERT OR IGNORE INTO voice_tags VALUES (?, ?)", (tag_id, uuid.uuid4().hex))
        return db.execute("SELECT epoch FROM voice_tags WHERE tag_id=?", (tag_id,)).fetchone()[0]

    def list_samples(self, tag_id: str) -> dict[str, Any]:
        check_ids(tag_id)
        with self._lock, closing(self._connect()) as db, db:
            epoch = self._epoch(db, tag_id)
            rows = db.execute("""SELECT sample_id, tag_name, name, created_at, length(audio) AS size_bytes
                FROM voices WHERE tag_id=? ORDER BY created_at DESC, sample_id""", (tag_id,)).fetchall()
            return {"tag_id": tag_id, "epoch": epoch, "samples": [dict(row) for row in rows]}

    def put_sample(self, tag_id: str, body: dict[str, Any]) -> dict[str, Any]:
        sample_id = body.get("sample_id")
        check_ids(tag_id, sample_id if sample_id is not None else "")
        epoch = body.get("epoch")
        name, tag_name = body.get("name"), body.get("tag_name")
        if not all(isinstance(value, str) and 0 < len(value) <= 1000 for value in (name, tag_name)):
            raise ValueError("音声ファイル名とタグ名が必要です（各1000文字以下）。")
        audio = decode_sample(body.get("audio_base64"))
        if hashlib.sha256(audio).hexdigest() != sample_id:
            raise ValueError("音声のSHA256が一致しません。")
        with self._lock, closing(self._connect()) as db, db:
            # A delete invalidates in-flight uploads so they cannot resurrect removed audio.
            db.execute("BEGIN IMMEDIATE")
            if self._epoch(db, tag_id) != epoch:
                raise VoiceChangedError("PCの音声保存状態が変わりました。もう一度操作してください。")
            existing = db.execute("SELECT 1 FROM voices WHERE tag_id=? AND sample_id=?", (tag_id, sample_id)).fetchone()
            if not existing:
                count = db.execute("SELECT count(*) FROM voices WHERE tag_id=?", (tag_id,)).fetchone()[0]
                if count >= _MAX_TAG_SAMPLES:
                    raise ValueError("このタグの保存音声が256件に達しました。不要な音声を削除してください。")
                size = db.execute("SELECT coalesce(sum(length(audio)),0) FROM voices").fetchone()[0]
                if size + len(audio) > _MAX_STORE_BYTES:
                    raise ValueError("PCのサンプル音声保存上限（512 MiB）です。不要な音声を削除してください。")
                db.execute("INSERT INTO voices VALUES (?,?,?,?,?,?)", (
                    tag_id, sample_id, tag_name, name, datetime.now(timezone.utc).isoformat(), audio,
                ))
        return {"tag_id": tag_id, "sample_id": sample_id, "stored": True}

    def read_sample(self, tag_id: str, sample_id: str) -> bytes:
        check_ids(tag_id, sample_id)
        with self._lock, closing(self._connect()) as db:
            row = db.execute("SELECT audio FROM voices WHERE tag_id=? AND sample_id=?", (tag_id, sample_id)).fetchone()
        if row is None:
            raise VoiceMissingError("PC上のサンプル音声がありません。TTSボタンをもう一度押して再送してください。")
        audio = bytes(row[0])
        if hashlib.sha256(audio).hexdigest() != sample_id:
            raise RuntimeError("PC上の音声データが破損しています。タグ編集から削除して再送してください。")
        return audio

    def delete_samples(self, tag_id: str, epoch: str, sample_id: str | None = None) -> dict[str, Any]:
        check_ids(tag_id, sample_id)
        with self._lock, closing(self._connect()) as db, db:
            db.execute("BEGIN IMMEDIATE")
            if self._epoch(db, tag_id) != epoch:
                raise VoiceChangedError("PCの音声保存状態が変わりました。一覧を開き直してください。")
            if sample_id is None:
                cursor = db.execute("DELETE FROM voices WHERE tag_id=?", (tag_id,))
            else:
                cursor = db.execute("DELETE FROM voices WHERE tag_id=? AND sample_id=?", (tag_id, sample_id))
            count = cursor.rowcount
            new_epoch = uuid.uuid4().hex
            db.execute("UPDATE voice_tags SET epoch=? WHERE tag_id=?", (new_epoch, tag_id))
        return {"deleted": count, "epoch": new_epoch}
