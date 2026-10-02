from __future__ import annotations

import io
import sys
import tempfile
import threading
import unittest
import json
import urllib.request
import urllib.error
from dataclasses import replace
from pathlib import Path
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from generation_agent.config import AgentConfig
from generation_agent.database import JobDatabase
from generation_agent.service import GenerationService
from generation_agent.server import AgentServer


class InsertNextTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.config = AgentConfig.load(Path(self.temp.name) / "config.json")
        self.service = GenerationService(self.config)

    def tearDown(self):
        self.service.database.close()
        self.temp.cleanup()

    def insert(self, job, prompt="urgent", request="insert-1", **extra):
        return self.service.insert_next(job["id"], {
            "client_request_id": request,
            "tasks": [{"prompt": prompt, **extra}],
        })

    def test_priority_and_retry(self):
        job = self.service.submit({"prompt": "normal", "count": 100})
        current = self.service.database.claim_next_task(job["id"])
        result = self.insert(job, seed=42, steps=35)
        self.assertEqual(101, result["total"])
        self.assertEqual("running", self.service.database.get_task(job["id"], 0)["status"])
        next_task = self.service.database.next_task(job["id"])
        self.assertEqual(100, next_task["task_index"])
        self.assertEqual(42, next_task["payload"]["seed"])
        self.assertEqual(35, next_task["payload"]["steps"])
        self.assertEqual(101, self.insert(job, seed=42, steps=35)["total"])
        with self.assertRaises(ValueError):
            self.insert(job, seed=43)
        self.assertEqual(0, current["task_index"])

    def test_refresh_excludes_insertions(self):
        job = self.service.submit({"tasks": [{"prompt": "base"}, {"prompt": "thumb", "purpose": "thumbnail"}]})
        self.insert(job, seed=8)
        result = self.service.refresh_pending(job["id"], {"tasks": [{"prompt": "changed"}] * 3})
        self.assertEqual([0], result["refreshed_task_indices"])
        self.assertEqual("changed", self.service.database.get_task(job["id"], 0)["payload"]["prompt"])
        self.assertEqual("thumb", self.service.database.get_task(job["id"], 1)["payload"]["prompt"])
        self.assertEqual("urgent", self.service.database.get_task(job["id"], 2)["payload"]["prompt"])

    def test_mixed_execution_and_results(self):
        started = threading.Event()
        release = threading.Event()
        prompts = []
        png = io.BytesIO()
        Image.new("RGB", (64, 64)).save(png, "PNG")
        def generate(payload):
            prompts.append(payload["prompt"])
            self.assertNotIn("binding", payload)
            if len(prompts) == 1:
                started.set()
                self.assertTrue(release.wait(5))
            return png.getvalue(), ".png", payload.get("seed", 123)
        self.service.sd.generate = generate
        job = self.service.submit({"prompt": "normal", "count": 2})
        self.service.database.start_job(job["id"])
        worker = threading.Thread(target=self.service._run_job, args=(job["id"],))
        worker.start()
        self.assertTrue(started.wait(5))
        try:
            self.insert(job, purpose="thumbnail", binding={"kind": "card", "id": "card-1"})
            self.insert(job, "replay", "insert-2", seed=987, steps=40)
        finally:
            release.set()
            worker.join(10)
        self.assertFalse(worker.is_alive())
        self.assertEqual(["normal", "urgent", "replay", "normal"], prompts)
        result = self.service.get_job(job["id"], with_progress=False)
        self.assertEqual("completed", result["status"])
        self.assertEqual((4, 4), (result["total"], result["completed"]))
        by_index = {image["task_index"]: image for image in result["images"]}
        self.assertEqual({"kind": "card", "id": "card-1"}, by_index[2]["binding"])
        self.assertIn("thumbnail-files", by_index[2]["url"])
        self.assertEqual(987, by_index[3]["metadata"]["parameters"]["seed"])
        self.assertEqual(40, by_index[3]["metadata"]["parameters"]["steps"])
        with self.assertRaises(ValueError):
            self.insert(job, request="after-finish")
        # A lost HTTP response may be retried even after the batch completed.
        self.assertEqual(4, self.insert(job, "replay", "insert-2", seed=987, steps=40)["total"])

    def test_recovery_keeps_priority(self):
        job = self.service.submit({"prompt": "normal", "count": 2})
        self.insert(job)
        self.service.database.close()
        self.service.database = JobDatabase(self.config.database_path)
        self.assertEqual(2, self.service.database.next_task(job["id"])["task_index"])
        self.assertEqual(3, self.insert(job)["total"])

    def test_cancel_and_invalid_are_atomic(self):
        job = self.service.submit({"prompt": "normal"})
        with self.assertRaises(ValueError):
            self.insert(job, purpose="image", binding={"kind": "card", "id": "a"})
        self.assertEqual(1, self.service.database.get_job(job["id"])["total"])
        self.service.database.request_cancel(job["id"])
        with self.assertRaises(ValueError):
            self.insert(job)

    def test_http_auth_and_retry(self):
        config = replace(self.config, listen_port=0, api_key="test-secret")
        server = AgentServer(config, self.service)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        job = self.service.submit({"prompt": "base"})
        url = f"http://127.0.0.1:{server.server_port}/api/v1/jobs/{job['id']}/insert-next"
        body = json.dumps({"client_request_id": "http-1", "tasks": [{"prompt": "next"}]}).encode()
        try:
            request = urllib.request.Request(url, data=body, headers={"Content-Type": "application/json"})
            with self.assertRaises(urllib.error.HTTPError) as caught:
                urllib.request.urlopen(request)
            self.assertEqual(401, caught.exception.code)
            request.add_header("Authorization", "Bearer test-secret")
            for _ in range(2):
                with urllib.request.urlopen(request) as response:
                    self.assertEqual(202, response.status)
                    self.assertEqual(2, json.load(response)["total"])
        finally:
            server.shutdown()
            server.server_close()
            thread.join()

    def test_last_task_boundary(self):
        job = self.service.submit({"prompt": "last"})
        current = self.service.database.claim_next_task(job["id"])
        self.service.database.finish_task(job["id"], current["task_index"], "image/2026-10-02/a.png")
        # Accepted before the atomic empty-queue check must execute, not be stranded.
        self.insert(job)
        self.assertEqual(1, self.service.database.claim_next_task(job["id"])["task_index"])
        self.service.database.fail_task(job["id"], 1, "test failure")
        self.assertIsNone(self.service.database.claim_next_task(job["id"]))
        with self.assertRaises(ValueError):
            self.insert(job, request="too-late")

    def test_failed_thumb_mapping(self):
        job = self.service.submit({"tasks": [
            {"prompt": "fail", "purpose": "thumbnail", "binding": {"kind": "card", "id": "a"}},
            {"prompt": "ok", "purpose": "thumbnail", "binding": {"kind": "preset", "id": "b"}},
        ]})
        self.service.database.fail_task(job["id"], 0, "test failure")
        self.service.database.finish_task(job["id"], 1, "thumbnail/2026-10-02/b.png")
        self.insert(job, seed=7)
        results = self.service.get_job(job["id"], with_progress=False)["images"]
        self.assertEqual(1, len(results))
        self.assertEqual(1, results[0]["task_index"])
        self.assertEqual({"kind": "preset", "id": "b"}, results[0]["binding"])

    def test_concurrent_duplicates(self):
        job = self.service.submit({"prompt": "normal"})
        errors = []
        def submit():
            try:
                self.insert(job)
            except Exception as error:
                errors.append(error)
        workers = [threading.Thread(target=submit) for _ in range(8)]
        for worker in workers:
            worker.start()
        for worker in workers:
            worker.join()
        self.assertFalse(errors)
        self.assertEqual(2, self.service.database.get_job(job["id"])["total"])


if __name__ == "__main__":
    unittest.main()
