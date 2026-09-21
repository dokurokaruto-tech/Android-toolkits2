"""Fetch a pinned SoX archive, never a SourceForge download landing page."""
from __future__ import annotations

import argparse
import hashlib
import http.client
import logging
import os
import sys
import tempfile
import urllib.request
from pathlib import Path
from typing import BinaryIO
from urllib.parse import urlsplit

SOX_VERSION = "14.4.2"
# https://github.com/microsoft/winget-pkgs/blob/master/manifests/c/ChrisBagwell/SoX/14.4.2/ChrisBagwell.SoX.installer.yaml
SOX_SHA256 = "8072CC147CF1A3B3713B8B97D6844BB9389E211AB9E1101E432193FAD6AE6662"
_ARCHIVE_PATH = f"/project/sox/sox/{SOX_VERSION}/sox-{SOX_VERSION}-win32.zip"
SOX_URLS = tuple(host + _ARCHIVE_PATH for host in (
    "https://downloads.sourceforge.net",
    "https://master.dl.sourceforge.net",
    "https://netix.dl.sourceforge.net",
))
MAX_ARCHIVE_BYTES = 32 * 1024 * 1024
_DOWNLOAD_TIMEOUT_SECONDS = 45
_CHUNK_BYTES = 64 * 1024
_ZIP_SIGNATURE = b"PK\x03\x04"
_LOG = logging.getLogger("sox-download")


def verify_archive(path: Path) -> None:
    size = path.stat().st_size
    if size > MAX_ARCHIVE_BYTES:
        raise ValueError(f"Archive exceeds {MAX_ARCHIVE_BYTES} bytes (received {size}).")
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        signature = stream.read(len(_ZIP_SIGNATURE))
        digest.update(signature)
        for chunk in iter(lambda: stream.read(_CHUNK_BYTES), b""):
            digest.update(chunk)
    actual = digest.hexdigest()
    detail = f"bytes={size}; actual SHA256={actual}; expected SHA256={SOX_SHA256}"
    if signature != _ZIP_SIGNATURE:
        raise ValueError(f"Response is not a ZIP archive (possibly an HTML/block page); {detail}")
    if actual.lower() != SOX_SHA256.lower():
        raise ValueError(f"SoX ZIP SHA256 mismatch; {detail}")


def _store_verified(stream: BinaryIO, output: Path) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    descriptor, name = tempfile.mkstemp(prefix="sox-", suffix=".part", dir=output.parent)
    temporary = Path(name)
    try:
        with os.fdopen(descriptor, "wb") as target:
            total = 0
            while chunk := stream.read(_CHUNK_BYTES):
                total += len(chunk)
                if total > MAX_ARCHIVE_BYTES:
                    raise ValueError(f"Download exceeds {MAX_ARCHIVE_BYTES} bytes.")
                target.write(chunk)
        verify_archive(temporary)
        temporary.replace(output)
    finally:
        temporary.unlink(missing_ok=True)


def copy_verified(source: Path, output: Path) -> None:
    """Browser-downloaded archives are subject to exactly the same hash check."""
    if source.resolve() == output.resolve():
        verify_archive(source)
        return
    with source.open("rb") as stream:
        _store_verified(stream, output)
    _LOG.info("Browser archive verified: %s", source.name)


def download_sox(output: Path) -> None:
    for index, url in enumerate(SOX_URLS, 1):
        _LOG.info("Download attempt %s/%s: %s", index, len(SOX_URLS), url)
        request = urllib.request.Request(url, headers={
            "User-Agent": "AndroidToolkits-SoX-Setup/1.0",
            "Accept": "application/zip, application/octet-stream;q=0.9, */*;q=0.1",
        })
        try:
            with urllib.request.urlopen(request, timeout=_DOWNLOAD_TIMEOUT_SECONDS) as response:
                if urlsplit(response.geturl()).scheme != "https":
                    raise ValueError("Refusing a redirect to a non-HTTPS mirror.")
                _LOG.info("Response Content-Type: %s", response.headers.get("Content-Type", "unknown"))
                _store_verified(response, output)
        except (OSError, ValueError, http.client.HTTPException) as error:
            _LOG.warning("Attempt rejected: %s", error)
            continue
        _LOG.info("SoX archive SHA256 verified.")
        return
    raise RuntimeError(
        "No verified SoX archive was obtained. Nothing will be extracted or executed. "
        "See sox-download.log, or use the browser-download method in README.md."
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--archive", type=Path, help="Verify a ZIP already downloaded with a browser")
    parser.add_argument("--log", type=Path)
    args = parser.parse_args()
    handlers: list[logging.Handler] = [logging.StreamHandler(sys.stdout)]
    if args.log:
        args.log.parent.mkdir(parents=True, exist_ok=True)
        handlers.append(logging.FileHandler(args.log, mode="w", encoding="utf-8"))
    logging.basicConfig(level=logging.INFO, format="[%(levelname)s] %(message)s", handlers=handlers)
    try:
        if args.archive:
            copy_verified(args.archive, args.output)
        else:
            download_sox(args.output)
        return 0
    except (OSError, ValueError, RuntimeError) as error:
        _LOG.error("%s", error)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
