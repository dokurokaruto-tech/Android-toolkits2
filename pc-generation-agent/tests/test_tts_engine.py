from __future__ import annotations

import contextlib
import sys
import tempfile
import types
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from generation_agent.tts_engine import TtsEngine

try:
    import numpy as np
    import soundfile as sf
except ImportError:
    np = sf = None


@unittest.skipIf(np is None or sf is None, "Install numpy and soundfile for engine tests")
class EngineTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        (self.root / "reference.audio").write_bytes(b"original audio")
        self.base = Mock()
        self.base.model.tts_model_type = "base"
        self.base.model.talker.config._attn_implementation = "sdpa"
        self.base.model.talker.code_predictor.config._attn_implementation = "sdpa"
        self.base.create_voice_clone_prompt.return_value = [object()]
        self.base.generate_voice_clone.return_value = ([np.ones(24000, dtype=np.float32)], 24000)
        self.factory = Mock()
        self.factory.from_pretrained.return_value = self.base
        self.fast = Mock()
        self.fast.model = self.base
        self.fast.generate_voice_clone.return_value = self.base.generate_voice_clone.return_value
        self.fast_factory = Mock()
        self.fast_factory.from_pretrained.return_value = self.fast
        torch = types.SimpleNamespace(cuda=types.SimpleNamespace(is_available=lambda: True, synchronize=lambda: None),
                                      float16="fp16", inference_mode=contextlib.nullcontext)
        modules = patch.dict(sys.modules, {"torch":torch,
            "qwen_tts":types.SimpleNamespace(Qwen3TTSModel=self.factory),
            "faster_qwen3_tts":types.SimpleNamespace(FasterQwen3TTS=self.fast_factory)})
        modules.start()
        self.addCleanup(modules.stop)
        def decode(source, destination):
            sf.write(destination, np.ones(24000), 24000)
        self.decode = patch("generation_agent.tts_engine.decode_reference", side_effect=decode)
        self.decoder = self.decode.start()
        self.addCleanup(self.decode.stop)
        attention = patch("generation_agent.tts_engine.prepare_attention",
                          return_value={"implementation":"sdpa", "gqa_policy":"stock"})
        attention.start()
        self.addCleanup(attention.stop)
        self.body = {"model_dir":"existing/model", "text":"こんにちは", "ref_text":"サンプル", "backend":"standard"}
        self.engine = TtsEngine()

    def test_model_and_prompt_reused(self):
        first = self.engine.synthesize(self.body, self.root, ["こんにちは"])
        second = self.engine.synthesize(self.body, self.root, ["こんばんは"])
        self.assertFalse(first["model_reused"])
        self.assertTrue(second["model_reused"])
        self.assertEqual(second["attention"]["implementation"], "sdpa")
        self.assertEqual(second["attention"]["talker"], "sdpa")
        self.assertEqual(second["attention"]["predictor"], "sdpa")
        self.assertEqual(second["prompt_cache"], "hit")
        self.factory.from_pretrained.assert_called_once()
        self.base.create_voice_clone_prompt.assert_called_once()
        self.decoder.assert_called_once()
        self.assertTrue((self.root / "speech.wav").exists())

    def test_sample_change(self):
        self.engine.synthesize(self.body, self.root, ["こんにちは"])
        (self.root / "reference.audio").write_bytes(b"different audio")
        result = self.engine.synthesize(self.body, self.root, ["こんにちは"])
        self.assertEqual(result["prompt_cache"], "miss")
        self.assertEqual(self.base.create_voice_clone_prompt.call_count, 2)

    def test_transcript_and_lru_key(self):
        for transcript in ("one", "two", "three", "one"):
            self.engine.synthesize(dict(self.body, ref_text=transcript), self.root, ["hello"])
        self.assertEqual(self.base.create_voice_clone_prompt.call_count, 4)
        self.assertEqual(len(self.engine._prompts), 2)

    def test_fast_uses_fp16_and_icl(self):
        with patch("importlib.metadata.version", return_value="0.3.2"):
            self.engine.synthesize(dict(self.body, backend="cuda_graph"), self.root, ["こんにちは"])
        kwargs = self.fast_factory.from_pretrained.call_args.kwargs
        self.assertEqual(kwargs["dtype"], "fp16")
        self.assertEqual(kwargs["attn_implementation"], "sdpa")
        self.assertTrue(kwargs["local_files_only"])
        self.assertEqual(self.fast.generate_voice_clone.call_args.kwargs["ref_text"], "サンプル")
        self.base.generate_voice_clone.assert_not_called()

    def test_model_change(self):
        self.engine.synthesize(self.body, self.root, ["こんにちは"])
        with self.assertRaisesRegex(RuntimeError, "再起動"):
            self.engine.synthesize(dict(self.body, model_dir="other"), self.root, ["こんにちは"])

    def test_silence_is_not_success(self):
        self.base.generate_voice_clone.return_value = ([np.zeros(24000)], 24000)
        with self.assertRaisesRegex(RuntimeError, "音声生成に失敗"):
            self.engine.synthesize(self.body, self.root, ["こんにちは"])


if __name__ == "__main__":
    unittest.main()
