"""Guarded Turing SDPA adaptation. Never installs or pretends to enable FlashAttention."""
from __future__ import annotations

import importlib
import importlib.metadata
from pathlib import Path
from statistics import median

from .tts_options import TtsAttention

_TURING_CAPABILITY = (7, 5)
_TORCH_VERSION = "2.7.1"
_TRANSFORMERS_VERSION = "4.57.3"
_WARMUP_STEPS = 3
_TIMING_STEPS = 16
_TIMING_ROUNDS = 3
_MAX_TIME_RATIO = 0.98
_ATOL = 0.002
_RTOL = 0.01
_DEFAULT_SHAPES = [(16, 2, 128), (16, 8, 128)]


def _use_repeat_kv(capability: tuple, torch_version: str, transformers_version: str, mode: TtsAttention) -> bool:
    return (mode == TtsAttention.AUTO and capability == _TURING_CAPABILITY
            and torch_version.split("+", 1)[0] == _TORCH_VERSION
            and transformers_version == _TRANSFORMERS_VERSION)


def _head_shapes(directory: Path | None) -> list[tuple[int, int, int]]:
    if directory is None:
        return list(_DEFAULT_SHAPES)
    from qwen_tts.core.models.configuration_qwen3_tts import Qwen3TTSConfig
    # Use package defaults for fields omitted by Hugging Face's diff-serialized JSON.
    config = Qwen3TTSConfig.from_pretrained(str(directory), local_files_only=True)
    talker = config.talker_config
    shapes = []
    for part in (talker, talker.code_predictor_config):
        heads, kv_heads = part.num_attention_heads, part.num_key_value_heads
        dim = getattr(part, "head_dim", None) or part.hidden_size // heads
        if not (0 < kv_heads <= heads <= 64 and heads % kv_heads == 0 and 0 < dim <= 256):
            raise ValueError("Unrecognized attention dimensions; keeping stock SDPA")
        shape = (heads, kv_heads, dim)
        if shape not in shapes:
            shapes.append(shape)
    return shapes


def _measure(call) -> float:
    import torch
    for _ in range(_WARMUP_STEPS):
        call()
    torch.cuda.synchronize()
    times = []
    for _ in range(_TIMING_ROUNDS):
        start, end = torch.cuda.Event(enable_timing=True), torch.cuda.Event(enable_timing=True)
        start.record()
        for _ in range(_TIMING_STEPS):
            call()
        end.record()
        end.synchronize()
        times.append(start.elapsed_time(end) / _TIMING_STEPS)
    return median(times)


def _probe_efficient(directory: Path | None = None) -> dict:
    import torch
    from torch.nn.attention import SDPBackend, sdpa_kernel
    from torch.nn.functional import scaled_dot_product_attention as sdpa
    from transformers.integrations.sdpa_attention import repeat_kv

    rows = []
    generator = torch.Generator(device="cuda:0").manual_seed(1729)
    with torch.inference_mode():
        for heads, kv_heads, dim in _head_shapes(directory):
            for label, query_length, key_length in (("prefill", 64, 64), ("decode", 1, 512), ("masked", 1, 512)):
                query = torch.randn((1, heads, query_length, dim), device="cuda:0", dtype=torch.float16, generator=generator)
                key = torch.randn((1, kv_heads, key_length, dim), device="cuda:0", dtype=torch.float16, generator=generator)
                value = torch.randn(key.shape, device="cuda:0", dtype=torch.float16, generator=generator)
                mask = None
                if label == "masked":
                    mask = torch.zeros((1, 1, 1, key_length), device="cuda:0", dtype=torch.float16)
                    mask[..., key_length // 2:] = float("-inf")
                options = {"dropout_p": 0.0, "is_causal": label == "prefill", "attn_mask": mask}
                def stock():
                    return sdpa(query, key, value, enable_gqa=True, **options)
                def expanded():
                    return sdpa(query, repeat_kv(key, heads // kv_heads), repeat_kv(value, heads // kv_heads), **options)
                with sdpa_kernel(backends=[SDPBackend.MATH]):
                    reference = stock()
                    math_ms = _measure(stock)
                stock_ms = _measure(stock)
                # Math fallback is disabled in this probe, so success proves a fused kernel ran.
                with sdpa_kernel(backends=[SDPBackend.EFFICIENT_ATTENTION]):
                    result = expanded()
                    if not torch.isfinite(result).all().item() or not torch.allclose(result, reference, atol=_ATOL, rtol=_RTOL):
                        return {"passed": False, "reason": "efficient/math numerical comparison failed"}
                    efficient_ms = _measure(expanded)
                rows.append({"case": label, "heads": heads, "kv_heads": kv_heads, "dim": dim,
                             "math_ms": round(math_ms, 5), "stock_ms": round(stock_ms, 5),
                             "efficient_ms": round(efficient_ms, 5),
                             "ratio": efficient_ms / max(stock_ms, 1e-9)})
    passed = all(row["ratio"] < _MAX_TIME_RATIO for row in rows if row["case"] != "masked")
    return {"passed": passed, "reason": "verified" if passed else "no consistent speed gain",
            "cases": rows, "scope": "small attention operations, not full TTS"}


def _install_repeat_kv(integration) -> None:
    original = integration.use_gqa_in_sdpa
    if getattr(original, "_toolkits_turing", False) is True:
        return
    def choose_gqa(mask, key):
        if key.device.type == "cuda" and key.device.index == 0:
            return False
        return original(mask, key)
    choose_gqa._toolkits_turing = True
    choose_gqa._toolkits_original = original
    # Only this isolated TTS interpreter is affected; package files are never modified.
    integration.use_gqa_in_sdpa = choose_gqa


def prepare_attention(directory: Path | None = None, mode: TtsAttention = TtsAttention.AUTO) -> dict:
    import torch
    report = {"requested": mode.value, "implementation": "sdpa", "gqa_policy": "stock"}
    if not torch.cuda.is_available():
        report["probe"] = {"passed": False, "reason": "CUDA unavailable"}
        return report
    capability = torch.cuda.get_device_capability(0)
    report.update(gpu=torch.cuda.get_device_name(0), compute_capability="%d.%d" % capability,
                  flash_attention_2="unsupported GPU" if capability[0] < 8 else "not selected or tested")
    try:
        version = importlib.metadata.version("transformers")
        integration = importlib.import_module("transformers.integrations.sdpa_attention")
        current = getattr(integration, "use_gqa_in_sdpa", None)
        if getattr(current, "_toolkits_turing", False) is True:
            integration.use_gqa_in_sdpa = current._toolkits_original
        if not _use_repeat_kv(capability, torch.__version__, version, mode):
            report["probe"] = {"passed": False, "reason": "disabled or outside validated version/GPU scope"}
            return report
        report["probe"] = _probe_efficient(directory)
        if report["probe"]["passed"]:
            _install_repeat_kv(integration)
            report["gqa_policy"] = "repeat_kv"
    except Exception as error:
        report["probe"] = {"passed": False, "reason": str(error)[:500]}
    return report
