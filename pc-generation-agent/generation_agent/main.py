from __future__ import annotations

import argparse
import os
import signal
import socket
import subprocess
import sys
import threading
from pathlib import Path

from .config import AgentConfig
from .server import AgentServer
from .service import GenerationService


def connection_addresses(port: int) -> list[str]:
    """Return concrete phone-facing URLs, preferring the Windows default LAN route."""
    addresses: list[str] = []
    if os.name == "nt":
        try:
            # First output is the adapter with a default gateway (normally the same Wi-Fi
            # as the phone); remaining lines include Tailscale and other active adapters.
            command = (
                "$preferred=(Get-NetIPConfiguration | Where-Object {$_.IPv4DefaultGateway -ne $null} "
                "| Select-Object -First 1).IPv4Address.IPAddress; "
                "$all=Get-NetIPAddress -AddressFamily IPv4 | Where-Object {"
                "$_.AddressState -eq 'Preferred' -and $_.IPAddress -notlike '127.*' "
                "-and $_.IPAddress -notlike '169.254.*'} | Select-Object -ExpandProperty IPAddress; "
                "@($preferred)+@($all) | Where-Object {$_} | Select-Object -Unique"
            )
            result = subprocess.run(
                ["powershell.exe", "-NoProfile", "-Command", command],
                check=False,
                capture_output=True,
                text=True,
                timeout=8,
            )
            addresses.extend(line.strip() for line in result.stdout.splitlines() if line.strip())
        except (OSError, subprocess.SubprocessError):
            pass
    try:
        # No packet is sent. The OS only tells us which adapter it would use.
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as probe:
            probe.connect(("8.8.8.8", 80))
            addresses.append(probe.getsockname()[0])
    except OSError:
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            addresses.append(info[4][0])
    except OSError:
        pass
    usable = []
    for address in addresses:
        if address.startswith("127.") or address.startswith("169.254.") or address in usable:
            continue
        usable.append(address)
    return [f"http://{address}:{port}" for address in usable]


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
    phone_urls = connection_addresses(config.listen_port)
    recommended = phone_urls[0] if phone_urls else f"http://PC-IP:{config.listen_port}"
    connection_file = config.root / "connection-info.txt"
    try:
        connection_file.write_text(
            "Android Toolkits PC Generation Agent\n"
            f"RECOMMENDED_ANDROID_URL={recommended}\n"
            f"SD_API_URL={config.sd_base_url}\n"
            + "OTHER_ANDROID_URLS=" + ",".join(phone_urls[1:]) + "\n",
            encoding="utf-8",
        )
    except OSError:
        connection_file = Path("connection-info.txt")
    print("=" * 66)
    print(" Android Toolkits - PC Generation Agent 1.0.0")
    print(f" ANDROID APP URL (use this exact value): {recommended}")
    for alternative in phone_urls[1:]:
        print(f" Alternative network URL              : {alternative}")
    print(f" SD API                                : {config.sd_base_url}")
    print(f" Images                                : {config.output_dir}")
    print(f" Connection memo                       : {connection_file}")
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
