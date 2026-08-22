from __future__ import annotations

import argparse
import signal
import sys
import threading
from pathlib import Path

from .config import AgentConfig
from .server import AgentServer
from .service import GenerationService


def run(config_path: Path) -> int:
    config = AgentConfig.load(config_path)
    service = GenerationService(config)
    server = AgentServer(config, service)
    stopping = threading.Event()

    def stop(_signum: int, _frame: object) -> None:
        if not stopping.is_set():
            stopping.set()
            threading.Thread(target=server.shutdown, daemon=True).start()

    signal.signal(signal.SIGINT, stop)
    if hasattr(signal, "SIGTERM"):
        signal.signal(signal.SIGTERM, stop)

    service.start()
    print("=" * 66)
    print(" Android Toolkits - PC Generation Agent 1.0.0")
    print(f" Listen : http://{config.listen_host}:{config.listen_port}")
    print(f" SD API : {config.sd_base_url}")
    print(f" Images : {config.output_dir}")
    print(" Close with Ctrl+C. Jobs already accepted stay in SQLite for restart.")
    print("=" * 66)
    try:
        server.serve_forever(poll_interval=0.5)
    finally:
        server.server_close()
        service.close()
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="Persistent Stable Diffusion generation agent")
    parser.add_argument("--config", type=Path, default=Path(__file__).resolve().parents[1] / "config.json")
    args = parser.parse_args()
    try:
        return run(args.config)
    except (OSError, ValueError) as error:
        print(f"Agent could not start: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
