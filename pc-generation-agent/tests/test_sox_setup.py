from __future__ import annotations

import importlib
import io
import os
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))


class SoxRuntimeTest(unittest.TestCase):
    def setUp(self):
        self.runtime = importlib.import_module("generation_agent.sox_runtime")
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.executable = self.root / "SoX with spaces" / "sox.exe"
        self.executable.parent.mkdir()
        self.executable.write_bytes(b"test executable placeholder")
        self.probe_ok = subprocess.CompletedProcess([], 0, "sox: SoX v14.4.2\n", "")

    def test_existing_sox_is_reused(self):
        with patch.object(self.runtime.shutil, "which", return_value=str(self.executable)), \
             patch.object(self.runtime.subprocess, "run", return_value=self.probe_ok) as run:
            self.assertEqual(self.runtime.find_sox(), self.executable)
            self.assertEqual(run.call_args.args[0], [str(self.executable), "--version"])
            self.assertNotIn("shell", run.call_args.kwargs)

    def test_bundled_sox_works_without_global_path(self):
        with patch.object(self.runtime.shutil, "which", return_value=None), \
             patch.object(self.runtime, "BUNDLED_SOX", self.executable), \
             patch.object(self.runtime.subprocess, "run", return_value=self.probe_ok):
            self.assertEqual(self.runtime.find_sox(), self.executable)

    def test_missing_sox_has_actionable_error(self):
        with patch.object(self.runtime, "find_sox", return_value=None):
            with self.assertRaisesRegex(RuntimeError, "setup-tts.bat"):
                self.runtime.configure_sox()

    def test_path_is_process_local_and_idempotent(self):
        old_path = str(self.root / "existing-bin")
        with patch.object(self.runtime, "find_sox", return_value=self.executable), \
             patch.dict(os.environ, {"PATH": old_path}):
            self.runtime.configure_sox()
            self.runtime.configure_sox()
            self.assertEqual(os.environ["PATH"].split(os.pathsep), [str(self.executable.parent), old_path])

    def test_bad_executable_is_not_accepted(self):
        for result in (subprocess.CompletedProcess([], 1, "SoX", "broken"),
                       subprocess.CompletedProcess([], 0, "not the expected tool", "")):
            with self.subTest(result=result), \
                 patch.object(self.runtime.shutil, "which", return_value=str(self.executable)), \
                 patch.object(self.runtime, "BUNDLED_SOX", self.executable), \
                 patch.object(self.runtime.subprocess, "run", return_value=result):
                self.assertIsNone(self.runtime.find_sox())

    def test_probe_failure_is_reported_as_missing(self):
        for error in (OSError("blocked"), subprocess.TimeoutExpired("sox", 5)):
            with self.subTest(error=error), \
                 patch.object(self.runtime.shutil, "which", return_value=str(self.executable)), \
                 patch.object(self.runtime, "BUNDLED_SOX", self.executable), \
                 patch.object(self.runtime.subprocess, "run", side_effect=error):
                self.assertIsNone(self.runtime.find_sox())


class SoxInstallContractTest(unittest.TestCase):
    """Static Windows script contracts; no actual installer execution here."""

    def test_installation_is_consent_gated(self):
        batch = (ROOT / "setup-tts.bat").read_text()
        self.assertIn('call :confirm "Download and install portable SoX for this project?"\n'
                      'if errorlevel 1 goto canceled\n'
                      'powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools\\install-sox.ps1 -Approved', batch)
        self.assertIn('--sox-check\nif not errorlevel 1 goto save_config', batch)

    def test_archive_verified_before_execution(self):
        script = (ROOT / "tools/install-sox.ps1").read_text()
        self.assertLess(script.index("if (-not $Approved)"), script.index("& $python @arguments"))
        self.assertLess(script.index("& $python @arguments"), script.index("Get-FileHash"))
        self.assertLess(script.index("Get-FileHash"), script.index("Expand-Archive"))
        self.assertLess(script.index("Expand-Archive"), script.index("& $executable --version"))
        self.assertIn("8072CC147CF1A3B3713B8B97D6844BB9389E211AB9E1101E432193FAD6AE6662", script)
        self.assertNotIn("SetEnvironmentVariable", script)
        self.assertIn("finally {", script)

    def test_qwen_path_order(self):
        setup = (ROOT / "tools/tts_setup.py").read_text()
        section = setup[setup.index("def runtime_check("):]
        self.assertLess(section.index("configure_sox()"), section.index("from qwen_tts import"))
        worker = (ROOT / "generation_agent/tts_worker.py").read_text()
        for function in ("def generate(", "def serve("):
            section = worker[worker.index(function):]
            self.assertLess(section.index("configure_sox()"), section.index("TtsEngine()"))

    def test_cli_probe_does_not_change_config(self):
        setup = importlib.import_module("tools.tts_setup")
        for executable, status in ((None, 1), (Path("sox.exe"), 0)):
            with self.subTest(executable=executable), \
                 patch.object(setup, "find_sox", return_value=executable), \
                 patch.object(setup, "prepare_config") as prepare, \
                 patch.object(sys, "argv", ["tts_setup.py", "--sox-check"]), redirect_stdout(io.StringIO()):
                self.assertEqual(setup.main(), status)
                prepare.assert_not_called()


if __name__ == "__main__":
    unittest.main()
