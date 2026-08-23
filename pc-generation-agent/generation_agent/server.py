from __future__ import annotations

import json
import mimetypes
import re
import urllib.error
import urllib.request
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import parse_qs, unquote, urlsplit

from .config import AgentConfig
from .service import GenerationService

_JOB = re.compile(r"^/api/v1/jobs/([0-9a-f]{32})$")
_CANCEL = re.compile(r"^/api/v1/jobs/([0-9a-f]{32})/cancel$")
_SKIP = re.compile(r"^/api/v1/jobs/([0-9a-f]{32})/skip$")
_STOP_AFTER_CURRENT = re.compile(r"^/api/v1/jobs/([0-9a-f]{32})/stop-after-current$")
_REFRESH_PENDING = re.compile(r"^/api/v1/jobs/([0-9a-f]{32})/refresh-pending$")
_PREVIEW = re.compile(r"^/api/v1/jobs/([0-9a-f]{32})/preview$")
_FILE = re.compile(r"^/api/v1/files/([^/]+)/([^/]+)$")
_MOBILE_THUMBNAIL = re.compile(r"^/api/v1/mobile-thumbnails/([^/]+)/([^/]+)$")
_PROGRESSIVE_MANIFEST = re.compile(r"^/api/v1/progressive/([^/]+)/([^/]+)/manifest$")
_PROGRESSIVE_TILE = re.compile(r"^/api/v1/progressive/([^/]+)/([^/]+)/(\d+)$")
_THUMBNAIL_FILE = re.compile(r"^/api/v1/thumbnail-files/([^/]+)/([^/]+)$")


class AgentServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, config: AgentConfig, service: GenerationService):
        super().__init__((config.listen_host, config.listen_port), AgentRequestHandler)
        self.config = config
        self.service = service


class AgentRequestHandler(BaseHTTPRequestHandler):
    server: AgentServer
    protocol_version = "HTTP/1.1"

    def do_OPTIONS(self) -> None:
        self.send_response(HTTPStatus.NO_CONTENT)
        self._common_headers()
        self.send_header("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Authorization, Content-Type")
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_GET(self) -> None:
        parsed = urlsplit(self.path)
        path, query = parsed.path, parse_qs(parsed.query)
        if path == "/api/v1/health":
            if not self._authorized(query):
                return
            self._json(HTTPStatus.OK, {
                "service": "android-toolkits-generation-agent",
                "version": "1.0.0",
                "sd_reachable": self.server.service.sd.health(),
                "output_dir": str(self.server.config.output_dir),
                "thumbnail_dir": str(self.server.config.thumbnail_dir),
                "mobile_thumbnails": True,
                "progressive_tiles": True,
            })
            return
        if not self._authorized(query):
            return
        if path.startswith("/sdapi/v1/"):
            self._proxy_sd("GET", path + (("?" + parsed.query) if parsed.query else ""))
            return
        if path == "/api/v1/jobs":
            limit = self._query_int(query, "limit", 30)
            self._json(HTTPStatus.OK, {"jobs": self.server.service.list_jobs(limit)})
            return
        match = _JOB.fullmatch(path)
        if match:
            job = self.server.service.get_job(match.group(1))
            self._json(HTTPStatus.OK, job) if job else self._error(HTTPStatus.NOT_FOUND, "job not found")
            return
        match = _PREVIEW.fullmatch(path)
        if match:
            try:
                preview = self.server.service.preview(match.group(1))
                if not preview:
                    self._error(HTTPStatus.NOT_FOUND, "preview is not available")
                else:
                    self._bytes(HTTPStatus.OK, preview[0], preview[1], cache="no-store")
            except Exception as error:
                self._error(HTTPStatus.BAD_GATEWAY, str(error))
            return
        if path == "/api/v1/library/dates":
            self._json(HTTPStatus.OK, {"dates": self.server.service.library_dates()})
            return
        if path == "/api/v1/library/images":
            date = query.get("date", [""])[0]
            try:
                self._json(HTTPStatus.OK, {"images": self.server.service.library_images(date)})
            except ValueError as error:
                self._error(HTTPStatus.BAD_REQUEST, str(error))
            return
        match = _FILE.fullmatch(path)
        if match:
            file = self.server.service.resolve_file(unquote(match.group(1)), unquote(match.group(2)))
            if not file:
                self._error(HTTPStatus.NOT_FOUND, "image not found")
            else:
                content_type = mimetypes.guess_type(file.name)[0] or "application/octet-stream"
                self._send_file(file, content_type)
            return
        match = _MOBILE_THUMBNAIL.fullmatch(path)
        if match:
            try:
                file = self.server.service.mobile_thumbnail_file(
                    unquote(match.group(1)), unquote(match.group(2))
                )
                if not file:
                    self._error(HTTPStatus.NOT_FOUND, "image not found")
                else:
                    self._send_file(file, "image/jpeg")
            except Exception as error:
                self._error(HTTPStatus.INTERNAL_SERVER_ERROR, str(error))
            return
        match = _PROGRESSIVE_MANIFEST.fullmatch(path)
        if match:
            try:
                manifest = self.server.service.progressive_manifest(
                    unquote(match.group(1)), unquote(match.group(2))
                )
                if manifest is None:
                    self._error(HTTPStatus.NOT_FOUND, "image not found")
                else:
                    self._json(HTTPStatus.OK, manifest)
            except Exception as error:
                self._error(HTTPStatus.INTERNAL_SERVER_ERROR, str(error))
            return
        match = _PROGRESSIVE_TILE.fullmatch(path)
        if match:
            try:
                file = self.server.service.progressive_tile_file(
                    unquote(match.group(1)), unquote(match.group(2)), int(match.group(3))
                )
                if file is None:
                    self._error(HTTPStatus.NOT_FOUND, "tile not found")
                else:
                    self._send_file(file, "image/png")
            except Exception as error:
                self._error(HTTPStatus.INTERNAL_SERVER_ERROR, str(error))
            return
        match = _THUMBNAIL_FILE.fullmatch(path)
        if match:
            file = self.server.service.resolve_thumbnail_file(unquote(match.group(1)), unquote(match.group(2)))
            if not file:
                self._error(HTTPStatus.NOT_FOUND, "thumbnail not found")
            else:
                content_type = mimetypes.guess_type(file.name)[0] or "application/octet-stream"
                self._send_file(file, content_type)
            return
        self._error(HTTPStatus.NOT_FOUND, "route not found")

    def do_POST(self) -> None:
        parsed = urlsplit(self.path)
        path, query = parsed.path, parse_qs(parsed.query)
        if not self._authorized(query):
            return
        if path.startswith("/sdapi/v1/"):
            self._proxy_sd("POST", path + (("?" + parsed.query) if parsed.query else ""))
            return
        if path == "/api/v1/jobs":
            try:
                body = self._read_json()
                self._json(HTTPStatus.ACCEPTED, self.server.service.submit(body))
            except ValueError as error:
                self._error(HTTPStatus.BAD_REQUEST, str(error))
            except Exception as error:
                self._error(HTTPStatus.INTERNAL_SERVER_ERROR, str(error))
            return
        match = _CANCEL.fullmatch(path)
        if match:
            changed = self.server.service.cancel(match.group(1))
            if not self.server.service.database.get_job(match.group(1)):
                self._error(HTTPStatus.NOT_FOUND, "job not found")
            else:
                self._json(HTTPStatus.ACCEPTED, {"accepted": changed})
            return
        match = _STOP_AFTER_CURRENT.fullmatch(path)
        if match:
            changed = self.server.service.stop_after_current(match.group(1))
            if not self.server.service.database.get_job(match.group(1)):
                self._error(HTTPStatus.NOT_FOUND, "job not found")
            else:
                self._json(HTTPStatus.ACCEPTED, {"accepted": changed})
            return
        match = _SKIP.fullmatch(path)
        if match:
            try:
                self._json(HTTPStatus.ACCEPTED, {"accepted": self.server.service.skip(match.group(1))})
            except Exception as error:
                self._error(HTTPStatus.BAD_GATEWAY, str(error))
            return
        match = _REFRESH_PENDING.fullmatch(path)
        if match:
            try:
                job = self.server.service.refresh_pending(match.group(1), self._read_json())
                if not job:
                    self._error(HTTPStatus.NOT_FOUND, "job not found")
                else:
                    self._json(HTTPStatus.ACCEPTED, job)
            except ValueError as error:
                self._error(HTTPStatus.BAD_REQUEST, str(error))
            except Exception as error:
                self._error(HTTPStatus.INTERNAL_SERVER_ERROR, str(error))
            return
        if path == "/api/generate-prompt" and self.server.config.legacy_api_url:
            self._proxy_legacy(path)
            return
        self._error(HTTPStatus.NOT_FOUND, "route not found")

    def do_DELETE(self) -> None:
        parsed = urlsplit(self.path)
        path, query = parsed.path, parse_qs(parsed.query)
        if not self._authorized(query):
            return
        if path == "/api/v1/library/images":
            date = query.get("date", [""])[0]
            name = query.get("name", [""])[0]
            try:
                if not date or not name:
                    raise ValueError("date and name are required")
                deleted = self.server.service.delete_library_image(date, name)
            except ValueError as error:
                self._error(HTTPStatus.BAD_REQUEST, str(error))
                return
            if deleted:
                self._json(HTTPStatus.OK, {"deleted": True, "date": date, "name": name})
            else:
                self._error(HTTPStatus.NOT_FOUND, "image not found")
            return
        self._error(HTTPStatus.NOT_FOUND, "route not found")

    def _authorized(self, query: dict[str, list[str]]) -> bool:
        expected = self.server.config.api_key
        if not expected:
            return True
        supplied = self.headers.get("Authorization", "")
        bearer = supplied[7:] if supplied.startswith("Bearer ") else ""
        query_token = query.get("token", [""])[0]
        import hmac
        if hmac.compare_digest(expected, bearer) or hmac.compare_digest(expected, query_token):
            return True
        self._error(HTTPStatus.UNAUTHORIZED, "authentication required")
        return False

    def _read_json(self) -> dict[str, Any]:
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError as error:
            raise ValueError("invalid Content-Length") from error
        if length <= 0 or length > 10 * 1024 * 1024:
            raise ValueError("JSON body is required and must be at most 10 MB")
        try:
            value = json.loads(self.rfile.read(length).decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise ValueError("invalid JSON body") from error
        if not isinstance(value, dict):
            raise ValueError("JSON body must be an object")
        return value

    def _proxy_sd(self, method: str, path: str) -> None:
        try:
            length = int(self.headers.get("Content-Length", "0"))
            body = self.rfile.read(length) if length > 0 else None
            request = urllib.request.Request(
                self.server.config.sd_base_url + path,
                data=body,
                method=method,
                headers={
                    "Content-Type": self.headers.get("Content-Type", "application/json"),
                    "Accept": self.headers.get("Accept", "application/json"),
                },
            )
            with urllib.request.urlopen(request, timeout=self.server.config.request_timeout_seconds) as response:
                self._bytes(
                    response.status, response.read(),
                    response.headers.get("Content-Type", "application/json"), cache="no-store"
                )
        except urllib.error.HTTPError as error:
            self._bytes(
                error.code, error.read(), error.headers.get("Content-Type", "application/json"), cache="no-store"
            )
        except Exception as error:
            self._error(HTTPStatus.BAD_GATEWAY, str(error))

    def _proxy_legacy(self, path: str) -> None:
        try:
            length = int(self.headers.get("Content-Length", "0"))
            body = self.rfile.read(length)
            request = urllib.request.Request(
                self.server.config.legacy_api_url + path,
                data=body,
                method="POST",
                headers={"Content-Type": self.headers.get("Content-Type", "application/json")},
            )
            with urllib.request.urlopen(request, timeout=self.server.config.request_timeout_seconds) as response:
                self._bytes(response.status, response.read(), response.headers.get_content_type(), cache="no-store")
        except urllib.error.HTTPError as error:
            self._bytes(error.code, error.read(), "application/json", cache="no-store")
        except Exception as error:
            self._error(HTTPStatus.BAD_GATEWAY, str(error))

    def _send_file(self, file: Path, content_type: str) -> None:
        size = file.stat().st_size
        self.send_response(HTTPStatus.OK)
        self._common_headers()
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(size))
        # Android browsing is intentionally stream-only until explicit import.
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        with file.open("rb") as stream:
            while chunk := stream.read(128 * 1024):
                self.wfile.write(chunk)

    def _json(self, status: int, value: Any) -> None:
        self._bytes(status, json.dumps(value, ensure_ascii=False).encode("utf-8"), "application/json; charset=utf-8", cache="no-store")

    def _error(self, status: int, message: str) -> None:
        self._json(status, {"error": message})

    def _bytes(self, status: int, content: bytes, content_type: str, cache: str) -> None:
        self.send_response(status)
        self._common_headers()
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(content)))
        self.send_header("Cache-Control", cache)
        self.end_headers()
        self.wfile.write(content)

    def _common_headers(self) -> None:
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("X-Content-Type-Options", "nosniff")

    @staticmethod
    def _query_int(query: dict[str, list[str]], key: str, default: int) -> int:
        try:
            return int(query.get(key, [str(default)])[0])
        except ValueError:
            return default

    def log_message(self, format: str, *args: Any) -> None:
        print(f"[{self.log_date_time_string()}] {self.address_string()} {format % args}")
