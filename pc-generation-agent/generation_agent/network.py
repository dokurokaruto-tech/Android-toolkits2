from __future__ import annotations

# Windows LAN drops often surface as these WinSock codes:
# 10053 = connection aborted, 10054 = reset, 10057 = not connected, 10060 = timed out.
_WINDOWS_DISCONNECTS = {10053, 10054, 10057, 10060}


def is_client_disconnect(error: BaseException) -> bool:
    """True when the Android/LAN peer vanished; the job on this PC must keep running."""
    if isinstance(error, (TimeoutError, ConnectionResetError, BrokenPipeError, ConnectionAbortedError)):
        return True
    if isinstance(error, OSError):
        winerror = getattr(error, "winerror", None)
        if winerror in _WINDOWS_DISCONNECTS:
            return True
        if error.errno in {104, 110, 111, 32}:  # reset, timeout, refused, broken pipe
            return True
    text = str(error).lower()
    markers = (
        "10060",
        "10054",
        "10053",
        "timed out",
        "timeout",
        "connection reset",
        "broken pipe",
        "connection aborted",
    )
    return any(marker in text for marker in markers)


def is_idle_timeout_log(message: str) -> bool:
    lowered = message.lower()
    return "request timed out" in lowered or "10060" in lowered
