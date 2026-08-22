"""
Phone Bridge - FastAPI queue between MCP server and Android poller
Port: 7070 (localhost only, exposed via nginx at /phone-bridge/)
"""
import asyncio
import re
import uuid
from datetime import datetime, timezone
from typing import Any, Optional

from fastapi import FastAPI, HTTPException, Request, Depends
from fastapi.responses import JSONResponse
from pydantic import BaseModel, field_validator

# ── Config ──────────────────────────────────────────────────────────────
TOKEN_PATH = "/path/to/phone_bridge_token.txt"
JOB_TIMEOUT_SECS = 30      # job expires if phone doesn't collect in 30s
RESULT_TTL_SECS  = 120     # completed jobs removed after 2 min

ALLOWED_ACTIONS = {"get_foreground_app", "press_home", "launch_app", "get_device_status"}
PKG_RE = re.compile(r'^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$')
PKG_MAX_LEN = 200

# ── Token ────────────────────────────────────────────────────────────────
with open(TOKEN_PATH) as f:
    BRIDGE_TOKEN = f.read().strip()

def require_token(request: Request):
    auth = request.headers.get("Authorization", "")
    if not auth.startswith("Bearer ") or auth[7:] != BRIDGE_TOKEN:
        raise HTTPException(status_code=401, detail="Unauthorized")

# ── Job storage (in-memory, asyncio.Lock for atomicity) ─────────────────
jobs: dict[str, dict] = {}
jobs_lock = asyncio.Lock()

def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()

def now_ts() -> float:
    return datetime.now(timezone.utc).timestamp()

# ── Models ───────────────────────────────────────────────────────────────
class CreateJobRequest(BaseModel):
    action: str
    args: dict = {}

    @field_validator("action")
    @classmethod
    def validate_action(cls, v):
        if v not in ALLOWED_ACTIONS:
            raise ValueError(f"action must be one of {ALLOWED_ACTIONS}")
        return v

    @field_validator("args")
    @classmethod
    def validate_args(cls, v, info):
        # package_name validation for launch_app
        action = info.data.get("action") if hasattr(info, "data") else None
        if action == "launch_app":
            pkg = v.get("package_name", "")
            if not pkg:
                raise ValueError("launch_app requires args.package_name")
            if len(pkg) > PKG_MAX_LEN or not PKG_RE.match(pkg):
                raise ValueError(f"invalid package_name: {pkg!r}")
        return v

class ResultSubmit(BaseModel):
    success: bool
    result: Optional[Any] = None
    error: Optional[str] = None

# ── App ──────────────────────────────────────────────────────────────────
app = FastAPI(title="Phone Bridge", docs_url=None, redoc_url=None)

@app.post("/jobs", dependencies=[Depends(require_token)])
async def create_job(body: CreateJobRequest):
    job_id = str(uuid.uuid4())
    job = {
        "id": job_id,
        "action": body.action,
        "args": body.args,
        "status": "pending",
        "created_at": now_iso(),
        "created_ts": now_ts(),
        "claimed_at": None,
        "completed_at": None,
        "result": None,
        "error": None,
    }
    async with jobs_lock:
        jobs[job_id] = job
    return {"job_id": job_id, "status": "pending"}

@app.get("/next", dependencies=[Depends(require_token)])
async def get_next_job():
    """Android poller: claim next pending job (atomic)."""
    async with jobs_lock:
        now = now_ts()
        # First expire old pending/claimed jobs
        for j in list(jobs.values()):
            if j["status"] in ("pending", "claimed"):
                age = now - j["created_ts"]
                if age > JOB_TIMEOUT_SECS:
                    j["status"] = "timeout"
                    j["error"] = f"timed out after {age:.0f}s"
        # Also remove old completed/timeout/error jobs
        to_del = [jid for jid, j in jobs.items()
                  if j["status"] in ("completed", "timeout", "error")
                  and (now - j["created_ts"]) > RESULT_TTL_SECS]
        for jid in to_del:
            del jobs[jid]
        # Find a pending job
        pending = [j for j in jobs.values() if j["status"] == "pending"]
        if not pending:
            return JSONResponse(status_code=204, content=None)
        # Sort by created_ts, take oldest
        job = min(pending, key=lambda j: j["created_ts"])
        job["status"] = "claimed"
        job["claimed_at"] = now_iso()
        # Return a copy (only fields phone needs)
        return {
            "id": job["id"],
            "action": job["action"],
            "args": job["args"],
        }

@app.post("/result/{job_id}", dependencies=[Depends(require_token)])
async def submit_result(job_id: str, body: ResultSubmit):
    """Android poller: submit execution result."""
    async with jobs_lock:
        job = jobs.get(job_id)
        if not job:
            raise HTTPException(status_code=404, detail="job not found")
        if job["status"] != "claimed":
            raise HTTPException(status_code=409, detail=f"job status is {job['status']!r}, expected claimed")
        job["status"] = "completed" if body.success else "error"
        job["result"] = body.result
        job["error"] = body.error
        job["completed_at"] = now_iso()
    return {"ok": True}

@app.get("/result/{job_id}", dependencies=[Depends(require_token)])
async def query_result(job_id: str):
    """MCP server: poll for completed result."""
    job = jobs.get(job_id)
    if not job:
        raise HTTPException(status_code=404, detail="job not found")
    return {
        "id": job["id"],
        "status": job["status"],
        "result": job["result"],
        "error": job["error"],
        "created_at": job["created_at"],
        "claimed_at": job["claimed_at"],
        "completed_at": job["completed_at"],
    }

@app.get("/health")
async def health():
    return {"ok": True, "jobs": len(jobs)}

