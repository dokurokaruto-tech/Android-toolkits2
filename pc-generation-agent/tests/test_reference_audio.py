from __future__ import annotations

import math
import struct
import subprocess
import sys
import tempfile
import unittest
import wave
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from generation_agent.reference_audio import decode_reference, ffmpeg_executable, REFERENCE_RATE


class ReferenceAudioTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.source = self.root / "original.wav"
        self.output = self.root / "decoded.wav"
        self._write_wave(self.source, 2)

    def _write_wave(self, path, seconds):
        rate = 16000
        frames = b"".join(struct.pack("<h", int(16000 * math.sin(2 * math.pi * 440 * index / rate)))
                          for index in range(int(seconds * rate)))
        with wave.open(str(path), "wb") as audio:
            audio.setnchannels(1)
            audio.setsampwidth(2)
            audio.setframerate(rate)
            audio.writeframes(frames)

    def _require_ffmpeg(self):
        try:
            return ffmpeg_executable()
        except (ImportError, RuntimeError):
            self.skipTest("Install imageio-ffmpeg to run codec integration tests")

    def _assert_decoded(self):
        with wave.open(str(self.output), "rb") as audio:
            self.assertEqual(audio.getnchannels(), 1)
            self.assertEqual(audio.getsampwidth(), 2)
            self.assertEqual(audio.getframerate(), REFERENCE_RATE)
            self.assertGreaterEqual(audio.getnframes(), REFERENCE_RATE)

    def test_wav_decode(self):
        self._require_ffmpeg()
        decode_reference(self.source, self.output)
        self._assert_decoded()

    def test_m4a_aac_and_alac(self):
        executable = self._require_ffmpeg()
        for codec in ("aac", "alac"):
            with self.subTest(codec=codec):
                m4a = self.root / (codec + ".m4a")
                subprocess.run([executable, "-y", "-loglevel", "error", "-i", str(self.source), "-c:a", codec, str(m4a)],
                               check=True, capture_output=True, timeout=20)
                # Uploaded blobs intentionally have no extension on the PC.
                raw = self.root / "reference.audio"
                raw.write_bytes(m4a.read_bytes())
                decode_reference(raw, self.output)
                self._assert_decoded()

    def test_flac_and_mp3(self):
        executable = self._require_ffmpeg()
        for extension in ("flac", "mp3"):
            with self.subTest(extension=extension):
                encoded = self.root / ("sample." + extension)
                subprocess.run([executable, "-y", "-loglevel", "error", "-i", str(self.source), str(encoded)],
                               check=True, capture_output=True, timeout=20)
                raw = self.root / "reference.audio"
                raw.write_bytes(encoded.read_bytes())
                decode_reference(raw, self.output)
                self._assert_decoded()

    def test_duration_not_truncated(self):
        self._require_ffmpeg()
        self._write_wave(self.source, 32)
        with self.assertRaisesRegex(ValueError, "1〜30秒"):
            decode_reference(self.source, self.output)
        self.assertFalse(self.output.exists())

    def test_short_sample_rejected(self):
        self._require_ffmpeg()
        self._write_wave(self.source, 0.2)
        with self.assertRaisesRegex(ValueError, "1〜30秒"):
            decode_reference(self.source, self.output)

    def test_invalid_audio_rejected(self):
        self._require_ffmpeg()
        self.source.write_text("not audio")
        with self.assertRaisesRegex(ValueError, "読み込めません"):
            decode_reference(self.source, self.output)

    def test_network_formats_blocked(self):
        self._require_ffmpeg()
        self.source.write_text("#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXTINF:10,\nhttp://127.0.0.1/audio.aac\n")
        with self.assertRaises(ValueError):
            decode_reference(self.source, self.output)

    def test_timeout(self):
        with patch("generation_agent.reference_audio.ffmpeg_executable", return_value="ffmpeg"), \
             patch("generation_agent.reference_audio.subprocess.run", side_effect=subprocess.TimeoutExpired("ffmpeg", 60)):
            with self.assertRaisesRegex(ValueError, "タイムアウト"):
                decode_reference(self.source, self.output)


if __name__ == "__main__":
    unittest.main()
