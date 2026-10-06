"""Headless Blender host that exposes the official blender-mcp (mcp-for-blender)
addon socket protocol on port 9876.

The upstream addon refuses to start in background mode because it drains its
command queue through ``bpy.app.timers``, which needs a GUI event loop.  Since
this project drives Blender through the ``bpy`` python module (no GUI, no X
server available in this sandbox), we reuse the *exact* same
``BlenderMCPServer`` class shipped inside the ``mcp-for-blender`` wheel, but we
pump ``_drain_command_queue()`` ourselves from the main thread.  All bpy access
therefore still happens on the main thread (thread safe), while client sockets
are accepted/queued by the addon's own server thread.

Usage:  python blender_mcp_host.py
Env:    BLENDERMCP_HOST (default 0.0.0.0)  BLENDERMCP_PORT (default 9876)
        HH_SAVE_PATH    path written on SIGTERM / 'save' file marker
"""
import importlib.util
import os
import signal
import socket
import sys
import threading
import time
import traceback

import bpy  # noqa: F401  (the bpy module *is* Blender 4.5 LTS here)

ADDON_PATH = os.environ.get(
    "BLENDERMCP_ADDON_PATH",
    os.path.join(
        os.path.dirname(sys.executable),
        "..", "lib", "python3.11", "site-packages",
        "blender_mcp", "bundled", "addon.py",
    ),
)
HOST = os.environ.get("BLENDERMCP_HOST", "0.0.0.0")
PORT = int(os.environ.get("BLENDERMCP_PORT", "9876"))
SAVE_PATH = os.environ.get("HH_SAVE_PATH", "")


def _load_addon_module():
    spec = importlib.util.spec_from_file_location("blendermcp_addon", ADDON_PATH)
    mod = importlib.util.module_from_spec(spec)
    sys.modules["blendermcp_addon"] = mod
    spec.loader.exec_module(mod)
    return mod


def main():
    addon = _load_addon_module()
    # register() adds the scene properties / operators the MCP server expects
    # during its handshake (get_addon_info).  UI classes are harmless in
    # background mode; anything that needs a window manager is guarded below.
    try:
        addon.register()
        print("[hh-host] addon.register() ok", flush=True)
    except Exception:
        traceback.print_exc()
    srv = addon.BlenderMCPServer(host=HOST, port=PORT)

    # --- manual start(): socket thread only, no bpy.app.timers -------------
    srv.running = True
    srv.socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.socket.bind((srv.host, srv.port))
    srv.socket.listen(5)
    srv.socket.settimeout(1.0)
    srv.server_thread = threading.Thread(target=srv._server_loop, daemon=True)
    srv.server_thread.start()
    print(f"[hh-host] Blender {bpy.app.version_string} MCP bridge listening on "
          f"{HOST}:{PORT}", flush=True)
    print("[hh-host] HOST_READY", flush=True)

    def _save_and_exit(signum, _frame):
        if SAVE_PATH:
            try:
                bpy.ops.wm.save_as_mainfile(filepath=SAVE_PATH)
                print(f"[hh-host] saved {SAVE_PATH}", flush=True)
            except Exception:
                traceback.print_exc()
        print("[hh-host] shutting down", flush=True)
        srv.running = False
        os._exit(0)

    signal.signal(signal.SIGTERM, _save_and_exit)
    signal.signal(signal.SIGINT, _save_and_exit)

    # --- main-thread pump: executes queued MCP commands --------------------
    while True:
        try:
            srv._drain_command_queue()
        except Exception:
            traceback.print_exc()
        time.sleep(0.02)


if __name__ == "__main__":
    main()
