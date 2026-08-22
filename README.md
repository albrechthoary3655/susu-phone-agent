# Susu Phone Agent

**Give Claude Code a real Android device.**

Control a real Android phone from Claude Code through MCP + Shizuku — no root, no Claude credentials on the phone, no model polling.

[![Android 9+](https://img.shields.io/badge/Android-9%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com)
[![Shizuku](https://img.shields.io/badge/Shizuku-wireless%20ADB-blue)](https://shizuku.rikkaapp.dev)
[![MCP](https://img.shields.io/badge/MCP-stdio-blueviolet)](https://modelcontextprotocol.io)
[![Claude Code](https://img.shields.io/badge/Claude_Code-native-orange)](https://claude.ai/code)
[![No Root](https://img.shields.io/badge/root-not%20required-brightgreen)](#)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

---

## What it does

```
You
 ↓
Claude Code
 ↓  MCP tool call
Susu Phone MCP
 ↓  HTTPS job queue
Susu Phone Bridge
 ↓  polling (phone-initiated)
Android Agent APK
 ↓  Shizuku UserService (uid=2000)
📱 Real Android phone
```

| You say | What happens |
|---|---|
| *"What app am I using?"* | `→ com.xingin.xhs` |
| *"Go home."* | Phone returns to launcher |
| *"Open Xiaohongshu."* | App opens on the real device |

---

## Demo

> TODO: add demo GIF — Claude types "Open Xiaohongshu" → app opens on real phone

---

## How it works

Three components, all yours to host:

**1. Phone MCP** (`mcp/phone_mcp.py`) — a FastMCP stdio server. Claude Code calls it like any other MCP tool. It forwards requests to the Phone Bridge.

**2. Phone Bridge** (`bridge/bridge.py`) — a FastAPI job queue on your server. The MCP pushes jobs in; the Android APK pulls them out. Nothing is pushed to the phone.

**3. Android Agent APK** (`android/`) — a foreground service. Built with [Shizuku](https://shizuku.rikkaapp.dev/) in wireless ADB mode. No root required. The phone polls your Bridge over ordinary HTTPS. No AI inference happens on the device.

### Why not ADB from the server?

ADB over the internet is fragile, requires open ports, and breaks on network change. Inverting the connection — phone polls your server — works over mobile data, behind NAT, anywhere.

### Why Shizuku instead of accessibility services?

Accessibility services are heavily restricted on Android 13+. Shizuku provides direct shell-level access (`uid=2000`) via the wireless debugging channel already built into Android. Commands like `input keyevent`, `dumpsys window`, and `monkey` work reliably without root.

### Why MCP?

The Android device never receives Claude or Anthropic credentials. Claude Code invokes the MCP server locally on the host machine. Phone polling is ordinary HTTPS traffic — it does not invoke a model.

---

## Quick Start

### Prerequisites

- Android phone with [Shizuku](https://shizuku.rikkaapp.dev/) installed (wireless ADB mode)
- A server with Python 3.11+, nginx (or another reverse proxy)
- [Claude Code](https://claude.ai/code) with a Claude subscription
- JDK 17+ and Android SDK API 34 (for building the APK)

### 1. Generate a token

```bash
openssl rand -hex 32 > /etc/susu-phone-agent/phone_bridge_token.txt
chmod 600 /etc/susu-phone-agent/phone_bridge_token.txt
```

### 2. Run the Phone Bridge

```bash
pip install fastapi uvicorn pydantic

# Edit bridge/bridge.py: set TOKEN_PATH to your token file path
uvicorn bridge.bridge:app --host 127.0.0.1 --port 7070
```

Expose it via nginx at `/phone-bridge/` over HTTPS. See [`bridge/bridge.py`](bridge/bridge.py) for the full API.

### 3. Configure the Phone MCP

```bash
pip install "mcp[cli]" fastmcp

# Edit mcp/phone_mcp.py: set TOKEN_PATH and BRIDGE_URL
```

Add to your Claude Code MCP config (`~/.claude/mcp_config.json` or similar):

```json
{
  "mcpServers": {
    "phone": {
      "type": "stdio",
      "command": "python3",
      "args": ["/path/to/mcp/phone_mcp.py"]
    }
  }
}
```

### 4. Build the APK

```bash
# Download Shizuku dependencies
mkdir -p android/libs
for artifact in api provider aidl shared; do
  curl -o android/libs/shizuku-${artifact}.aar \
    "https://repo1.maven.org/maven2/dev/rikka/shizuku/${artifact}/13.1.5/${artifact}-13.1.5.aar"
  unzip -p android/libs/shizuku-${artifact}.aar classes.jar \
    > android/libs/shizuku-${artifact}.jar
done

# Create a signing keystore (once)
keytool -genkeypair -keystore android/my.jks -alias susu \
  -keyalg RSA -keysize 2048 -validity 10000

# Build (set KS, KS_PASS via environment)
export KS=android/my.jks
export KS_PASS=your_keystore_password
cd android && bash build.sh
```

### 5. Install and configure the APK

Sideload `android/output/susu-phone-agent.apk`. On first launch:

1. Enter your **Bridge URL** and paste your **Bridge token** → **Save**
2. Tap **Test Bridge** — confirm `✓ Token OK`
3. Tap **Request Shizuku Permission** — approve in the Shizuku dialog
4. Tap **Start Agent**

The notification bar shows `● Connected · Idle` when everything is linked.

> **Honor / MIUI / ColorOS users:** Disable battery optimization for Phone Agent in system settings to prevent background killing. See the in-app prompts for your manufacturer's specific path.

---

## Security model

| Component | Claude credentials | Bridge token | Runs as |
|---|---|---|---|
| Phone MCP | No | Yes (reads from file) | Host process |
| Phone Bridge | No | Yes (validates requests) | Host process |
| Android APK | **No** | Yes (stored in app private storage) | Android app |
| Shizuku UserService | No | No | `uid=2000` (shell) |

The MCP exposes a small, allow-listed tool surface. There is no arbitrary `executeShell` tool. Package names passed to `launch_app` are validated against a strict regex before execution.

The Bridge token lives in Android's private app storage (`getSharedPreferences`). It is never written to logcat.

> **Note on Shizuku in wireless ADB mode:** After a device reboot, Shizuku typically needs to be restarted from the Shizuku app. The Android Agent includes a boot receiver that re-registers the Shizuku listener automatically, but Shizuku itself must be re-initialized by the user (or by an ADB automation if you have a connected host). Full zero-touch reboot recovery requires rooted Shizuku.

---

## Sleep Guard

An optional local rule engine. When enabled, the Agent polls a `/sleep-guard/state` endpoint every 4 seconds and presses HOME if the foreground app is on a blocked list — no model call, no AI inference.

**Dual safety:** Sleep Guard only activates when both are true:

1. `guard_enabled = true` in the app (opt-in, **off by default**)
2. `active: true` in the server response

Either being false → no action. A server misconfiguration alone cannot lock the device.

```json
{
  "active": true,
  "blocked_apps": ["com.example.app"],
  "allow_pkg": "com.example.app",
  "allow_until": 1756000000
}
```

---

## APK capabilities (versionCode 7)

| | |
|---|---|
| Foreground service (`specialUse` type) | ✅ |
| Shizuku UserService (`uid=2000`) | ✅ |
| Self-healing poller (`ScheduledExecutorService` + `catch(Throwable)` + `finally` reschedule) | ✅ |
| Bridge and Sleep Guard pollers fully independent | ✅ |
| `PARTIAL_WAKE_LOCK` for background survival | ✅ |
| Boot receiver | ✅ |
| No hardcoded credentials | ✅ |

---

## Roadmap

- [ ] UI tap / swipe via `input tap` and `input swipe`
- [ ] Screenshot capture → return to Claude
- [ ] Notification reading (opt-in)
- [ ] `input text` for keyboard input
- [ ] Multiple-device support (one Bridge, multiple pollers)
- [ ] Sleep Guard management UI

---

## Contributing

Issues and pull requests welcome. Please do not include real tokens, private domains, or personal data in contributions.

---

## Related work

- [PhoneAgent](https://github.com/tencent-ailab/phone_agent) — screenshot-based agent using vision models
- [Mobile-Agent](https://github.com/X-PLUG/MobileAgent) — visual grounding agent for mobile UIs
- [AppAgent](https://github.com/mnotgod96/AppAgent) — LLM agent for smartphone task automation

**What's different here:** shell-level control via Shizuku instead of screenshot + vision, MCP-native instead of prompt-based, phone pulls jobs from your server instead of a model pushing commands, no AI inference on the device.

---

## License

MIT — see [LICENSE](LICENSE).
