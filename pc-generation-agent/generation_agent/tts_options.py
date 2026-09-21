"""Shared worker settings; no optional ML imports in the agent."""
from enum import Enum


class TtsBackend(str, Enum):
    STANDARD = "standard"
    CUDA_GRAPH = "cuda_graph"


FAST_PACKAGE_VERSION = "0.3.2"
MAX_AUDIO_BYTES = 64 * 1024 * 1024
MAX_NEW_TOKENS = 2048
GRAPH_SEQUENCE_LENGTH = 4096
GRAPH_PROMPT_RESERVE = 512
DEFAULT_KEEP_ALIVE_SECONDS = 120
MAX_KEEP_ALIVE_SECONDS = 600
