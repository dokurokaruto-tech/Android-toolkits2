from __future__ import annotations

import importlib.metadata
import io
import sys
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from tools.tts_setup import main, package_issues

READY = {
    "torch": "2.7.1+cu126", "torchaudio": "2.7.1+cu126", "qwen-tts": "0.1.1",
    "imageio-ffmpeg": "0.6.0",
    "Pillow": "11.3.0", "soundfile": "0.13.1",
}


class PackageDetectionTest(unittest.TestCase):
    def test_ready_does_not_need_install(self):
        with patch("importlib.metadata.version", side_effect=READY.__getitem__):
            self.assertEqual(package_issues(), [])

    def test_missing_packages_detected_without_imports(self):
        with patch("importlib.metadata.version", side_effect=importlib.metadata.PackageNotFoundError), \
             patch("tools.tts_setup.subprocess.run") as run:
            issues = package_issues()
        self.assertEqual(len(issues), len(READY))
        self.assertTrue(all("Missing package" in issue for issue in issues))
        run.assert_not_called()

    def test_cpu_pytorch_and_wrong_cuda_rejected(self):
        for version in ("2.7.1", "2.7.1+cpu", "2.7.1+cu118", "2.8.0+cu126"):
            with self.subTest(version=version), \
                 patch("importlib.metadata.version", side_effect=dict(READY, torch=version).__getitem__):
                self.assertTrue(any("torch:" in issue for issue in package_issues()))

    def test_ffmpeg_requires_setup(self):
        def version(name):
            if name == "imageio-ffmpeg":
                raise importlib.metadata.PackageNotFoundError(name)
            return READY[name]
        with patch("importlib.metadata.version", side_effect=version):
            self.assertEqual(package_issues(), ["Missing package: imageio-ffmpeg"])

    def test_qwen_pin_enforced(self):
        versions = dict(READY, **{"qwen-tts": "0.1.0"})
        with patch("importlib.metadata.version", side_effect=versions.__getitem__):
            self.assertTrue(any("qwen-tts:" in issue for issue in package_issues()))

    def test_range_boundaries(self):
        for name, version, valid in (("Pillow", "10.0", True), ("Pillow", "11.3.0.post1", True),
                                     ("Pillow", "12.0.0", False), ("soundfile", "0.13.0", True),
                                     ("soundfile", "0.12.1", False), ("soundfile", "0.14.0", False),
                                     ("Pillow", "11.0.0rc1", False), ("soundfile", "bad", False)):
            with self.subTest(name=name, version=version), \
                 patch("importlib.metadata.version", side_effect=dict(READY, **{name: version}).__getitem__):
                self.assertEqual(not package_issues(), valid)

    def test_cli_reports_status_without_saving(self):
        for issues, status in (([], 0), (["Missing package: torch"], 1)):
            with self.subTest(issues=issues), patch.object(sys, "argv", ["tts_setup.py", "--packages-check"]), \
                 patch("tools.tts_setup.package_issues", return_value=issues), \
                 patch("tools.tts_setup.prepare_config") as prepare, \
                 patch("tools.tts_setup.check_setup") as check, redirect_stdout(io.StringIO()):
                self.assertEqual(main(), status)
                prepare.assert_not_called()
                check.assert_not_called()


class InstallScriptContractTest(unittest.TestCase):
    """Static guardrails; these do not replace Windows CMD / installer testing."""

    def test_bootstrap_and_pip_are_behind_consent(self):
        batch = (ROOT / "setup-tts.bat").read_text()
        self.assertIn('call :confirm "Download and install Python 3.12 for this Windows user?"\n'
                      'if errorlevel 1 goto canceled\n'
                      'powershell.exe', batch)
        self.assertIn('call :confirm "Install or repair the required libraries?"\n'
                      'if errorlevel 1 goto canceled\n'
                      '"%PYTHON%" -m ensurepip', batch)
        self.assertIn('call :confirm "Create the project\'s isolated .venv-tts environment?"\n'
                      'if errorlevel 1 goto canceled\n'
                      '%BOOTSTRAP% -m venv', batch)

    def test_consent_fails_closed(self):
        batch = (ROOT / "setup-tts.bat").read_text()
        self.assertIn('choice /C YN /N /M "%~1 [Y/N]: "\n'
                      'if errorlevel 2 exit /b 1\n'
                      'if errorlevel 1 exit /b 0\n'
                      'exit /b 1', batch)

    def test_manager_cannot_download_while_probing(self):
        for name in ("setup-tts.bat", "start-agent.bat"):
            batch = (ROOT / name).read_text()
            self.assertLess(batch.index('set "PYTHON_MANAGER_AUTOMATIC_INSTALL=false"'), batch.index("py -3"))

    def test_installer_verifies_publisher_first(self):
        script = (ROOT / "tools/install-python312.ps1").read_text()
        self.assertLess(script.index("if (-not $Approved)"), script.index("Invoke-WebRequest"))
        self.assertLess(script.index("Get-AuthenticodeSignature"), script.index("Start-Process"))
        self.assertIn("https://www.python.org/ftp/python/", script)
        self.assertIn("O=Python Software Foundation", script)
        self.assertIn("'InstallAllUsers=0'", script)
        self.assertIn("'/norestart'", script)
        self.assertIn("finally {", script)

    def test_installed_packages_skip_pip_install(self):
        batch = (ROOT / "setup-tts.bat").read_text()
        check = batch.index('"%PYTHON%" tools\\tts_setup.py --packages-check')
        skip = batch.index("goto prepare", check)
        offer = batch.index(":offer_packages\n")
        install = batch.index('"%PYTHON%" -m pip install')
        self.assertLess(check, skip)
        self.assertLess(skip, offer)
        self.assertLess(offer, install)
        self.assertIn('set "TORCH_VERSION=2.7.1+cu126"', batch)

    def test_start_agent_pillow_requires_consent(self):
        batch = (ROOT / "start-agent.bat").read_text()
        self.assertIn('choice /C YN /N /M "Pillow is missing.', batch)
        self.assertIn('if errorlevel 2 goto canceled\nif not errorlevel 1 goto canceled', batch)
        self.assertIn('call setup-tts.bat\nif errorlevel 1 exit /b %ERRORLEVEL%', batch)


if __name__ == "__main__":
    unittest.main()
