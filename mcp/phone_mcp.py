#!/usr/bin/env python3
"""
Phone MCP - stdio server for Claude to control Android phone
Communicates with Phone Bridge at http://127.0.0.1:7070
"""
import asyncio
import json
import time
import urllib.request
import urllib.error
from mcp.server.fastmcp import FastMCP

# ── Config ──────────────────────────────────────────────────────────────
BRIDGE_URL      = "http://127.0.0.1:7070"
TOKEN_PATH      = "/path/to/phone_bridge_token.txt"
POLL_INTERVAL   = 1.0    # seconds between result polls
MAX_WAIT_SECS   = 20     # max wait for phone to respond
CLAIM_TIMEOUT   = 8      # seconds: if phone hasn't claimed job, it's offline

with open(TOKEN_PATH) as f:
    _TOKEN = f.read().strip()

_HEADERS = {
    "Authorization": f"Bearer {_TOKEN}",
    "Content-Type": "application/json",
}

# ── HTTP helpers (sync, for simplicity in stdio context) ────────────────
def _request(method: str, path: str, data: dict | None = None) -> dict | None:
    url = BRIDGE_URL + path
    body = json.dumps(data).encode() if data is not None else None
    req = urllib.request.Request(url, data=body, headers=_HEADERS, method=method)
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            raw = resp.read().decode()
            if not raw.strip():
                return None
            return json.loads(raw)
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        raise RuntimeError(f"Bridge {method} {path} → HTTP {e.code}: {raw}")
    except Exception as e:
        raise RuntimeError(f"Bridge {method} {path} error: {e}")

def _create_job(action: str, args: dict | None = None) -> str:
    resp = _request("POST", "/jobs", {"action": action, "args": args or {}})
    return resp["job_id"]

def _wait_for_result(job_id: str) -> dict:
    """Poll until job is completed, error, or timeout."""
    deadline = time.time() + MAX_WAIT_SECS
    claim_deadline = time.time() + CLAIM_TIMEOUT

    while time.time() < deadline:
        resp = _request("GET", f"/result/{job_id}")
        status = resp.get("status")

        if status == "completed":
            return {"ok": True, "result": resp.get("result")}
        elif status == "error":
            return {"ok": False, "error": resp.get("error", "unknown error")}
        elif status == "timeout":
            return {"ok": False, "error": "phone bridge timed out waiting for phone"}
        elif status == "pending" and time.time() > claim_deadline:
            return {"ok": False, "error": "phone is offline or not polling (job never claimed)"}
        # pending or claimed → keep waiting
        time.sleep(POLL_INTERVAL)

    return {"ok": False, "error": f"MCP timed out waiting for phone (>{MAX_WAIT_SECS}s)"}

def _run_action(action: str, args: dict | None = None) -> str:
    """Create job, wait for result, return formatted string for Claude."""
    try:
        job_id = _create_job(action, args)
    except RuntimeError as e:
        return f"[Phone Bridge Error] Could not create job: {e}"

    result = _wait_for_result(job_id)
    if result["ok"]:
        r = result["result"]
        if isinstance(r, dict):
            return json.dumps(r, ensure_ascii=False, indent=2)
        return str(r)
    else:
        return f"[Phone Error] {result['error']}"

# ── MCP Server ───────────────────────────────────────────────────────────
mcp = FastMCP("phone")

@mcp.tool()
def phone_get_foreground_app() -> str:
    """Get the package name of the app currently in the foreground on the Android phone.
    Returns the package name (e.g. com.ss.android.ugc.aweme for TikTok/Douyin,
    com.xingin.xhs for Xiaohongshu/Little Red Book)."""
    return _run_action("get_foreground_app")

@mcp.tool()
def phone_press_home() -> str:
    """Press the HOME button on the Android phone, returning to the launcher/desktop.
    Use this when the user wants to go back to the home screen."""
    return _run_action("press_home")

@mcp.tool()
def phone_launch_app(package_name: str) -> str:
    """Launch an app on the Android phone by its package name.
    Common packages:
    - com.xingin.xhs (Xiaohongshu / Little Red Book)
    - com.ss.android.ugc.aweme (Douyin / TikTok)
    - com.tencent.mm (WeChat)
    - com.android.settings (Settings)
    Always use the exact package name."""
    # Validate package name
    import re
    if not re.match(r'^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$', package_name):
        return f"[Error] Invalid package name: {package_name!r}"
    if len(package_name) > 200:
        return "[Error] Package name too long"
    return _run_action("launch_app", {"package_name": package_name})

@mcp.tool()
def phone_get_device_status() -> str:
    """Get basic device status from the Android phone: battery level, charging status,
    screen state, foreground app, and current timestamp."""
    return _run_action("get_device_status")

if __name__ == "__main__":
    mcp.run()
