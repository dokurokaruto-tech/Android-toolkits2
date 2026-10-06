#!/usr/bin/env python3
"""Tiny MCP *client* used to drive Blender through the mcp-for-blender server.

    python mcp_run.py tools                 # list MCP tools
    python mcp_run.py scene                 # get_scene_info
    python mcp_run.py exec <file.py>        # run a python file inside Blender
    python mcp_run.py code "<one liner>"    # run inline code

Everything goes through the real MCP server (stdio) which in turn talks to the
Blender host socket on :9876 - i.e. Blender is only ever controlled via MCP.
"""
import asyncio
import json
import os
import sys

from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client

HERE = os.path.dirname(os.path.abspath(__file__))
PY = sys.executable


def _env():
    e = dict(os.environ)
    e["BLENDER_MCP_DISABLE_TELEMETRY"] = "1"
    e["DISABLE_TELEMETRY"] = "1"
    return e


async def run(action, payload):
    params = StdioServerParameters(
        command=PY,
        args=["-m", "blender_mcp.server"],
        env=_env(),
    )
    async with stdio_client(params) as (read, write):
        async with ClientSession(read, write) as session:
            await session.initialize()
            if action == "tools":
                tools = await session.list_tools()
                for t in tools.tools:
                    print(f"- {t.name}: {t.description.splitlines()[0] if t.description else ''}")
                return
            if action == "scene":
                res = await session.call_tool("get_scene_info", {})
            elif action == "exec":
                with open(payload, "r", encoding="utf-8") as fh:
                    code = fh.read()
                res = await session.call_tool("execute_blender_code", {"code": code})
            elif action == "code":
                res = await session.call_tool("execute_blender_code", {"code": payload})
            else:
                raise SystemExit(f"unknown action {action}")
            for c in res.content:
                txt = getattr(c, "text", None)
                if txt:
                    print(txt)
            if res.isError:
                raise SystemExit(1)


if __name__ == "__main__":
    action = sys.argv[1] if len(sys.argv) > 1 else "scene"
    payload = sys.argv[2] if len(sys.argv) > 2 else ""
    asyncio.run(run(action, payload))
