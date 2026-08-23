from __future__ import annotations

import base64
import io
import json
import sys
import tempfile
import threading
import time
import unittest
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from generation_agent.config import AgentConfig
from generation_agent.database import JobDatabase
from generation_agent.server import AgentServer
from generation_agent.service import GenerationService

_png_buffer = io.BytesIO()
Image.new("RGB", (1200, 1800), (120, 30, 200)).save(_png_buffer, "PNG")
PNG = _png_buffer.getvalue()


class FakeSdHandler(BaseHTTPRequestHandler):
    generated = 0

    def do_GET(self) -> None:
        if self.path.startswith("/sdapi/v1/options"):
            self.send_json({})
        elif self.path.startswith("/sdapi/v1/progress"):
            self.send_json({"progress": 0.5, "current_image": base64.b64encode(PNG).decode()})
        else:
            self.send_error(404)

    def do_POST(self) -> None:
        length = int(self.headers.get("Content-Length", 0))
        body = json.loads(self.rfile.read(length) or b"{}")
        if self.path == "/sdapi/v1/txt2img":
            self.__class__.generated += 1
            assert body["batch_size"] == 1
            self.send_json({"images": [base64.b64encode(PNG).decode()]})
        elif self.path in {"/sdapi/v1/interrupt", "/sdapi/v1/skip"}:
            self.send_json({})
        else:
            self.send_error(404)

    def send_json(self, value: object) -> None:
        content = json.dumps(value).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(content)))
        self.end_headers()
        self.wfile.write(content)

    def log_message(self, _format: str, *_args: object) -> None:
        pass


class AgentIntegrationTest(unittest.TestCase):
    def setUp(self) -> None:
        FakeSdHandler.generated = 0
        self.temp = tempfile.TemporaryDirectory()
        root = Path(self.temp.name)
        self.sd_server = ThreadingHTTPServer(("127.0.0.1", 0), FakeSdHandler)
        self.sd_thread = threading.Thread(target=self.sd_server.serve_forever, daemon=True)
        self.sd_thread.start()
        self.config = AgentConfig(
            root=root,
            listen_host="127.0.0.1",
            listen_port=0,
            sd_base_url=f"http://127.0.0.1:{self.sd_server.server_port}",
            output_dir=root / "generated",
            thumbnail_dir=root / "thumbnails",
            mobile_thumbnail_dir=root / "data" / "mobile-thumbnails",
            progressive_tile_dir=root / "data" / "progressive-tiles",
            database_path=root / "data" / "agent.sqlite3",
            api_key="secret",
            request_timeout_seconds=30,
            retry_count=0,
            legacy_api_url="",
        )
        self.service = GenerationService(self.config)
        self.agent_server = AgentServer(self.config, self.service)
        self.service.start()
        self.agent_thread = threading.Thread(target=self.agent_server.serve_forever, daemon=True)
        self.agent_thread.start()
        self.base = f"http://127.0.0.1:{self.agent_server.server_port}"

    def tearDown(self) -> None:
        self.agent_server.shutdown()
        self.agent_server.server_close()
        self.service.close()
        self.sd_server.shutdown()
        self.sd_server.server_close()
        self.temp.cleanup()

    def request(self, path: str, method: str = "GET", body: dict | None = None) -> tuple[int, dict]:
        data = json.dumps(body).encode() if body is not None else None
        request = urllib.request.Request(
            self.base + path,
            data=data,
            method=method,
            headers={"Authorization": "Bearer secret", "Content-Type": "application/json"},
        )
        with urllib.request.urlopen(request, timeout=5) as response:
            return response.status, json.loads(response.read())

    def test_persistent_batch_and_library(self) -> None:
        status, job = self.request(
            "/api/v1/jobs",
            "POST",
            {
                "client_request_id": "same-click",
                "tasks": [
                    {"prompt": "one", "width": 512, "height": 512, "steps": 10},
                    {"prompt": "two", "width": 512, "height": 512, "steps": 10},
                ],
            },
        )
        self.assertEqual(202, status)
        # Idempotency prevents a retry after a lost HTTP response from duplicating work.
        _, duplicate = self.request(
            "/api/v1/jobs",
            "POST",
            {"client_request_id": "same-click", "tasks": [{"prompt": "ignored"}]},
        )
        self.assertEqual(job["id"], duplicate["id"])

        deadline = time.time() + 5
        while time.time() < deadline:
            _, state = self.request(f"/api/v1/jobs/{job['id']}")
            if state["status"] == "completed":
                break
            time.sleep(0.05)
        self.assertEqual("completed", state["status"])
        self.assertEqual(2, state["completed"])
        self.assertEqual(2, FakeSdHandler.generated)

        _, dates = self.request("/api/v1/library/dates")
        self.assertEqual(1, len(dates["dates"]))
        date = dates["dates"][0]["date"]
        self.assertEqual(2, dates["dates"][0]["count"])
        self.assertIn("/api/v1/mobile-thumbnails/", dates["dates"][0]["thumbnail_url"])
        _, images = self.request(f"/api/v1/library/images?date={date}")
        self.assertEqual(2, len(images["images"]))
        self.assertIn("/api/v1/mobile-thumbnails/", images["images"][0]["thumbnail_url"])
        thumbnail_url = images["images"][0]["thumbnail_url"] + "?token=secret"
        with urllib.request.urlopen(self.base + thumbnail_url, timeout=5) as response:
            self.assertEqual("image/jpeg", response.headers.get_content_type())
            thumbnail = Image.open(io.BytesIO(response.read()))
            self.assertLessEqual(thumbnail.width, 480)
            self.assertLessEqual(thumbnail.height, 854)

        image_url = images["images"][0]["url"] + "?token=secret"
        with urllib.request.urlopen(self.base + image_url, timeout=5) as response:
            self.assertEqual("no-store", response.headers.get("Cache-Control"))
            self.assertEqual(PNG, response.read())

        encoded_name = images["images"][0]["name"]
        _, manifest = self.request(
            f"/api/v1/progressive/{date}/{encoded_name}/manifest"
        )
        self.assertEqual(1200, manifest["width"])
        self.assertEqual(1800, manifest["height"])
        self.assertGreater(len(manifest["tiles"]), 2)
        first_tile = manifest["tiles"][0]["url"] + "?token=secret"
        with urllib.request.urlopen(self.base + first_tile, timeout=5) as response:
            tile = Image.open(io.BytesIO(response.read())).convert("RGB")
            self.assertEqual((1200, 128), tile.size)
            self.assertEqual((120, 30, 200), tile.getpixel((10, 10)))

        delete_name = images["images"][0]["name"]
        status, deleted = self.request(
            f"/api/v1/library/images?date={date}&name={delete_name}", "DELETE"
        )
        self.assertEqual(200, status)
        self.assertTrue(deleted["deleted"])
        _, remaining = self.request(f"/api/v1/library/images?date={date}")
        self.assertEqual(1, len(remaining["images"]))
        self.assertNotEqual(delete_name, remaining["images"][0]["name"])

        # Existing Android features (thumbnail generation/progress) keep using SD routes.
        _, options = self.request("/sdapi/v1/options")
        self.assertEqual({}, options)

    def test_thumbnail_is_pc_hosted_but_hidden_from_main_library(self) -> None:
        _, job = self.request(
            "/api/v1/jobs", "POST",
            {"tasks": [{"prompt": "card thumbnail", "purpose": "thumbnail"}]},
        )
        deadline = time.time() + 5
        while time.time() < deadline:
            _, state = self.request(f"/api/v1/jobs/{job['id']}")
            if state["status"] == "completed":
                break
            time.sleep(0.05)
        self.assertEqual("completed", state["status"])
        self.assertEqual(1, len(state["images"]))
        self.assertIn("/api/v1/thumbnail-files/", state["images"][0]["url"])
        self.assertIn("/api/v1/mobile-thumbnails/", state["images"][0]["thumbnail_url"])
        with urllib.request.urlopen(
            self.base + state["images"][0]["url"] + "?token=secret", timeout=5
        ) as response:
            self.assertEqual(PNG, response.read())
        with urllib.request.urlopen(
            self.base + state["images"][0]["thumbnail_url"] + "?token=secret", timeout=5
        ) as response:
            self.assertEqual("image/jpeg", response.headers.get_content_type())
            thumbnail = Image.open(io.BytesIO(response.read()))
            self.assertLessEqual(thumbnail.width, 480)
            self.assertLessEqual(thumbnail.height, 854)
        _, dates = self.request("/api/v1/library/dates")
        self.assertEqual([], dates["dates"])

    def test_library_images_return_tags_saved_with_generation(self) -> None:
        _, job = self.request(
            "/api/v1/jobs",
            "POST",
            {
                "tasks": [{
                    "prompt": "tagged character",
                    "width": 512,
                    "height": 512,
                    "steps": 10,
                    "tags": ["金髪", "幼女", "金髪"],
                }]
            },
        )
        deadline = time.time() + 5
        while time.time() < deadline:
            _, state = self.request(f"/api/v1/jobs/{job['id']}")
            if state["status"] == "completed":
                break
            time.sleep(0.05)
        self.assertEqual("completed", state["status"])
        _, dates = self.request("/api/v1/library/dates")
        date = dates["dates"][0]["date"]
        _, images = self.request(f"/api/v1/library/images?date={date}")
        self.assertEqual(["金髪", "幼女"], images["images"][0]["tags"])

    def test_delete_library_image_removes_file(self) -> None:
        date = "2026-08-22"
        folder = self.config.output_dir / date
        folder.mkdir(parents=True)
        image = folder / "GEN_manual.png"
        image.write_bytes(PNG)
        self.assertTrue(self.service.delete_library_image(date, "GEN_manual.png"))
        self.assertFalse(image.exists())
        self.assertFalse(folder.exists())
        self.assertFalse(self.service.delete_library_image(date, "GEN_manual.png"))

    def test_rejects_invalid_task_and_path_traversal(self) -> None:
        with self.assertRaises(Exception):
            self.request("/api/v1/jobs", "POST", {"tasks": [{"prompt": "", "width": 1}]})
        self.assertIsNone(self.service.resolve_file("../../etc", "passwd"))
        self.assertIsNone(self.service.resolve_file("2026-08-22", "../secret.png"))

    def test_database_requeues_interrupted_process_state(self) -> None:
        path = Path(self.temp.name) / "recovery.sqlite3"
        database = JobDatabase(path)
        database.create_job("a" * 32, "recovery", [{"prompt": "resume"}])
        database.start_job("a" * 32)
        database.start_task("a" * 32, 0)
        database.close()

        reopened = JobDatabase(path)
        try:
            self.assertEqual("queued", reopened.get_job("a" * 32)["status"])
            self.assertEqual("pending", reopened.next_task("a" * 32)["status"])
        finally:
            reopened.close()


if __name__ == "__main__":
    unittest.main()
