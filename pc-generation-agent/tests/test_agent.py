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

_thumb_buffer = io.BytesIO()
Image.new("RGB", (100, 160), (10, 200, 90)).save(_thumb_buffer, "JPEG")
THUMB_JPG = _thumb_buffer.getvalue()
MODEL_BYTES = b"fake-safetensors-content" * 4096


class FakeSdHandler(BaseHTTPRequestHandler):
    generated = 0
    sd_model = "sd/base.safetensors [abc123]"
    sd_models: list = []

    def do_GET(self) -> None:
        if self.path.startswith("/sdapi/v1/options"):
            self.send_json({"sd_model_checkpoint": type(self).sd_model})
        elif self.path.startswith("/sdapi/v1/sd-models"):
            self.send_json(list(type(self).sd_models))
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
            seed = body.get("seed", 4242)
            self.send_json({
                "images": [base64.b64encode(PNG).decode()],
                "info": json.dumps({"seed": seed, "all_seeds": [seed]}),
            })
        elif self.path in {"/sdapi/v1/interrupt", "/sdapi/v1/skip"}:
            self.send_json({})
        elif self.path == "/sdapi/v1/options":
            # Like real SD: only an exact known title switches the model.
            picked = body.get("sd_model_checkpoint")
            known = {
                str(entry.get("title", ""))
                for entry in type(self).sd_models
                if isinstance(entry, dict)
            }
            if isinstance(picked, str) and picked in known:
                type(self).sd_model = picked
                self.send_json({})
            else:
                self.send_error(500, "Could not find checkpoint")
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


class FileDownloadHandler(BaseHTTPRequestHandler):
    hits = 0

    def do_GET(self) -> None:
        type(self).hits += 1
        if self.path == "/model.safetensors":
            self.send_bytes(MODEL_BYTES, "application/octet-stream")
        elif self.path == "/thumb.jpg":
            self.send_bytes(THUMB_JPG, "image/jpeg")
        else:
            self.send_error(404)

    def send_bytes(self, content: bytes, content_type: str) -> None:
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(content)))
        self.end_headers()
        self.wfile.write(content)

    def log_message(self, _format: str, *_args: object) -> None:
        pass


class AgentIntegrationTest(unittest.TestCase):
    def setUp(self) -> None:
        FakeSdHandler.generated = 0
        FakeSdHandler.sd_model = "sd/base.safetensors [abc123]"
        FakeSdHandler.sd_models = []
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
            checkpoint_dir=root / "checkpoints",
            lora_dir=root / "loras",
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
        self.assertIn("sd_model_checkpoint", options)

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
        self.assertEqual(4242, images["images"][0]["parameters"].get("seed"))

    def test_replay_task_keeps_requested_seed(self) -> None:
        _, job = self.request(
            "/api/v1/jobs",
            "POST",
            {"tasks": [{"prompt": "replay me", "steps": 40, "seed": 777}]},
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
        self.assertEqual(777, images["images"][0]["parameters"]["seed"])
        self.assertEqual(40, images["images"][0]["parameters"]["steps"])

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

    def test_refresh_pending_rewrites_unstarted_payloads_only(self) -> None:
        root = Path(self.temp.name) / "refresh-pending"
        config = AgentConfig(
            root=root,
            listen_host="127.0.0.1",
            listen_port=0,
            sd_base_url=self.config.sd_base_url,
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
        service = GenerationService(config)
        server = AgentServer(config, service)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            job = service.submit({
                "tasks": [
                    {"prompt": "first", "width": 512, "height": 512, "steps": 10, "tags": ["old-a"]},
                    {"prompt": "second", "width": 512, "height": 512, "steps": 10, "tags": ["old-b"]},
                    {"prompt": "third", "width": 512, "height": 512, "steps": 10, "tags": ["old-c"]},
                ]
            })
            self.assertEqual(3, job["pending"])
            first_base = service.database.get_task(job["id"], 0)["payload"]["_agent_output_base"]
            second_base = service.database.get_task(job["id"], 1)["payload"]["_agent_output_base"]
            third_base = service.database.get_task(job["id"], 2)["payload"]["_agent_output_base"]
            service.database.start_job(job["id"])
            service.database.start_task(job["id"], 0)

            request = urllib.request.Request(
                f"http://127.0.0.1:{server.server_port}/api/v1/jobs/{job['id']}/refresh-pending",
                data=json.dumps({
                    "tasks": [
                        {"prompt": "new-second", "width": 640, "height": 960, "steps": 15, "tags": ["new-b"]},
                        {"prompt": "new-third", "width": 640, "height": 960, "steps": 15, "tags": ["new-c"]},
                    ]
                }).encode(),
                method="POST",
                headers={"Authorization": "Bearer secret", "Content-Type": "application/json"},
            )
            with urllib.request.urlopen(request, timeout=5) as response:
                self.assertEqual(202, response.status)
                refreshed = json.loads(response.read())
            self.assertEqual(2, refreshed["pending"])
            self.assertEqual("first", service.database.get_task(job["id"], 0)["payload"]["prompt"])
            self.assertEqual(first_base, service.database.get_task(job["id"], 0)["payload"]["_agent_output_base"])
            second = service.database.get_task(job["id"], 1)["payload"]
            third = service.database.get_task(job["id"], 2)["payload"]
            self.assertEqual("new-second", second["prompt"])
            self.assertEqual("new-third", third["prompt"])
            self.assertEqual(640, second["width"])
            self.assertEqual(15, third["steps"])
            self.assertEqual(second_base, second["_agent_output_base"])
            self.assertEqual(third_base, third["_agent_output_base"])
            self.assertEqual(["new-b"], second["_agent_tags"])
            self.assertEqual(["new-c"], third["_agent_tags"])
        finally:
            server.shutdown()
            server.server_close()
            service.database.close()

    def test_model_import_saves_file_preview_and_info(self) -> None:
        FileDownloadHandler.hits = 0
        server = ThreadingHTTPServer(("127.0.0.1", 0), FileDownloadHandler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            base = f"http://127.0.0.1:{server.server_port}"
            status, job = self.request("/api/v1/model-imports", "POST", {
                "download_url": f"{base}/model.safetensors",
                "filename": "test-lora.safetensors",
                "kind": "lora",
                "model_name": "Test LoRA",
                "version_name": "v1",
                "trigger_words": ["kw-one", "kw-two"],
                "thumbnail_url": f"{base}/thumb.jpg",
            })
            self.assertEqual(202, status)
            state = self.wait_import(job["id"])
            self.assertEqual("completed", state["status"])
            target = self.config.lora_dir / "test-lora.safetensors"
            self.assertEqual(str(target), state["target_path"])
            self.assertEqual(MODEL_BYTES, target.read_bytes())
            preview = self.config.lora_dir / "test-lora.png"
            self.assertTrue(preview.is_file())
            self.assertTrue((self.config.lora_dir / "test-lora.preview.png").is_file())
            with Image.open(preview) as image:
                self.assertLessEqual(max(image.size), 768)
            info = json.loads(
                (self.config.lora_dir / "test-lora.safetensors.civitai.info").read_text(encoding="utf-8")
            )
            self.assertEqual(["kw-one", "kw-two"], info["trigger_words"])

            _, ckpt = self.request("/api/v1/model-imports", "POST", {
                "download_url": f"{base}/model.safetensors",
                "filename": "test-checkpoint.safetensors",
                "kind": "checkpoint",
                "thumbnail_base64": base64.b64encode(THUMB_JPG).decode(),
            })
            self.assertEqual("completed", self.wait_import(ckpt["id"])["status"])
            self.assertTrue((self.config.checkpoint_dir / "test-checkpoint.safetensors").is_file())
            self.assertTrue((self.config.checkpoint_dir / "test-checkpoint.preview.png").is_file())

            # Re-import skips bytes but still completes with sidecars refreshed.
            hits = FileDownloadHandler.hits
            _, again = self.request("/api/v1/model-imports", "POST", {
                "download_url": f"{base}/model.safetensors",
                "filename": "test-lora.safetensors",
                "kind": "lora",
            })
            self.assertEqual("completed", self.wait_import(again["id"])["status"])
            self.assertEqual(hits, FileDownloadHandler.hits)

            with self.assertRaises(Exception):
                self.request("/api/v1/model-imports", "POST", {
                    "download_url": f"{base}/model.safetensors",
                    "filename": "evil.exe",
                    "kind": "lora",
                })
            with self.assertRaises(Exception):
                self.request("/api/v1/model-imports", "POST", {
                    "download_url": f"{base}/model.safetensors",
                    "filename": "x.safetensors",
                    "kind": "embedding",
                })
            with self.assertRaises(Exception):
                self.request("/api/v1/model-imports/" + "0" * 32)
        finally:
            server.shutdown()
            server.server_close()

    def wait_import(self, job_id: str) -> dict:
        deadline = time.time() + 10
        state: dict = {}
        while time.time() < deadline:
            _, state = self.request(f"/api/v1/model-imports/{job_id}")
            if state["status"] in {"completed", "failed"}:
                break
            time.sleep(0.05)
        return state

    def test_post_body_does_not_desync_keep_alive(self) -> None:
        import http.client
        conn = http.client.HTTPConnection("127.0.0.1", self.agent_server.server_port, timeout=5)
        try:
            auth = {"Authorization": "Bearer secret", "Content-Type": "application/json"}
            # Unknown route with a body: 404 must still consume the bytes,
            # or the next request on this socket parses garbage (HTTP 414).
            conn.request("POST", "/api/v1/nope", body=json.dumps({"pad": "x" * 4096}), headers=auth)
            response = conn.getresponse()
            self.assertEqual(404, response.status)
            response.read()
            conn.request("GET", "/api/v1/health", headers=auth)
            response = conn.getresponse()
            self.assertEqual(200, response.status)
            self.assertIn("service", json.loads(response.read()))

            # Bodiless handlers (cancel) receive {} from Android; same rule.
            _, job = self.request("/api/v1/jobs", "POST", {"tasks": [{"prompt": "x"}]})
            conn.request("POST", f"/api/v1/jobs/{job['id']}/cancel", body="{}", headers=auth)
            response = conn.getresponse()
            self.assertEqual(202, response.status)
            response.read()
            conn.request("GET", f"/api/v1/jobs/{job['id']}", headers=auth)
            response = conn.getresponse()
            self.assertEqual(200, response.status)
            response.read()
        finally:
            conn.close()

    def test_checkpoint_list_preview_and_switch(self) -> None:
        root = self.config.checkpoint_dir
        root.mkdir(parents=True, exist_ok=True)
        base_file = root / "base.safetensors"
        base_file.write_bytes(b"fake-weights")
        (root / "base.preview.png").write_bytes(PNG)
        (root / "notes.txt").write_text("ignored")
        second_file = root / "second.safetensors"
        second_file.write_bytes(b"fake-weights-2")
        FakeSdHandler.sd_models = [
            {
                "title": "sd/base.safetensors [abc123]",
                "model_name": "base",
                "filename": str(base_file),
            },
            {
                "title": "sd/second.safetensors [def456]",
                "model_name": "second",
                # Path SD reports need not match ours; basename still resolves.
                "filename": "D:\\other\\second.safetensors",
            },
        ]
        FakeSdHandler.sd_model = "sd/base.safetensors [abc123]"

        _, listed = self.request("/api/v1/models/checkpoints")
        self.assertEqual(
            ["base.safetensors", "second.safetensors"],
            [item["name"] for item in listed["checkpoints"]],
        )
        self.assertEqual("sd/base.safetensors [abc123]", listed["checkpoints"][0]["sd_title"])
        self.assertEqual("base.safetensors", listed["active"])

        with urllib.request.urlopen(
            self.base + "/api/v1/models/checkpoints/preview?name=base.safetensors&token=secret",
            timeout=5,
        ) as response:
            self.assertEqual(PNG, response.read())

        _, switched = self.request(
            "/api/v1/models/checkpoints/active", "POST", {"name": "second.safetensors"}
        )
        self.assertEqual("second.safetensors", switched["active"])
        # The exact SD title must be sent; a bare filename gets SD 500.
        self.assertEqual("sd/second.safetensors [def456]", FakeSdHandler.sd_model)

        with self.assertRaises(Exception):
            self.request("/api/v1/models/checkpoints/active", "POST", {"name": "../evil.safetensors"})
        with self.assertRaises(Exception):
            self.request("/api/v1/models/checkpoints/active", "POST", {"name": "missing.safetensors"})

        # Without the SD model list, the "[hash]" suffix is stripped to find active.
        FakeSdHandler.sd_models = []
        _, listed = self.request("/api/v1/models/checkpoints")
        self.assertEqual("second.safetensors", listed["active"])

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
