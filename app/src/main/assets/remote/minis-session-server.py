#!/usr/bin/env python3
"""minis-session-server — remote agent-session engine (P4 slice 5).

Runs the agent LOOP on the remote host; the phone is a thin client.
Speaks RemoteSessionProtocol v1 (newline-delimited JSON) over
stdin/stdout, matching the Kotlin client byte-for-byte:

  frames out: {"type":"snapshot","v":1,"seq":N,"running":B,"messages":[...]}
              {"type":"event","v":1,"seq":N,"kind":K,"text":T,...}
  requests in: {"action":"send","text":...} | {"action":"stop"}
               {"action":"snapshot"} | {"action":"resync","sinceSeq":N}

Event kinds: text_delta, tool_start, tool_result, turn_end, error.
The fold semantics mirror RemoteSessionProtocol.applyEvent exactly so
snapshots and event replay converge to the same state.

Stdlib only — deployable to any Linux host with python3. The LLM API is
OpenAI-compatible chat completions (non-streaming per step; each step's
content is emitted as text_delta). Config via env or ~/.minis-session.json:
  MINIS_API_BASE (default https://api.openai.com/v1)
  MINIS_API_KEY  (required)
  MINIS_MODEL    (default gpt-4o-mini)

Tools: shell(command) executed ON THE HOST with a timeout and the same
50k head+tail output cap as the phone's local shell. The loop is bounded
(MAX_TURNS) and stoppable between steps.
"""

import json
import os
import subprocess
import sys
import threading
import urllib.request

PROTOCOL_VERSION = 1
MAX_TURNS = 32
DEFAULT_TIMEOUT_S = 60
MAX_TIMEOUT_S = 900
MAX_OUTPUT_CHARS = 50_000
EVENT_LOG_LIMIT = 4096

SHELL_TOOL = {
    "type": "function",
    "function": {
        "name": "shell",
        "description": "Run a shell command on this host and return its output.",
        "parameters": {
            "type": "object",
            "properties": {
                "command": {"type": "string", "description": "The command to run."},
                "timeout": {"type": "integer", "description": "Seconds (1-900, default 60)."},
            },
            "required": ["command"],
        },
    },
}


def truncate_output(text, limit=MAX_OUTPUT_CHARS):
    """Head+tail cap, mirroring RemoteHostConfig.truncateOutput."""
    if len(text) <= limit:
        return text
    keep = limit // 2
    omitted = len(text) - 2 * keep
    return text[:keep] + "\n…[%d chars omitted]…\n" % omitted + text[-keep:]


def apply_event(state, kind, text="", tool_name=None, is_error=False):
    """Fold one event into state — mirrors the Kotlin client exactly.

    state: {"messages": [ {role,text,toolName?,isError?} ], "running": bool,
            "lastError": str|None}
    """
    msgs = state["messages"]
    if kind == "text_delta":
        if msgs and msgs[-1]["role"] == "assistant" and not msgs[-1].get("toolName"):
            msgs[-1]["text"] += text
        else:
            msgs.append({"role": "assistant", "text": text})
        state["running"] = True
    elif kind == "tool_start":
        m = {"role": "tool", "text": text}
        if tool_name:
            m["toolName"] = tool_name
        msgs.append(m)
        state["running"] = True
    elif kind == "tool_result":
        m = {"role": "tool", "text": text}
        if tool_name:
            m["toolName"] = tool_name
        if is_error:
            m["isError"] = True
        msgs.append(m)
        state["running"] = True
    elif kind == "turn_end":
        state["running"] = False
    elif kind == "error":
        state["running"] = False
        state["lastError"] = text
    # unknown kinds ignored (forward-compatible)
    return state


class Session:
    """Sequenced state + event log + wire emission. Thread-safe."""

    def __init__(self, emit):
        self._emit = emit  # callable(line:str)
        self._lock = threading.Lock()
        self.seq = 0
        self.state = {"messages": [], "running": False, "lastError": None}
        self._log = []  # list of (seq, frame dict)

    def snapshot_frame(self):
        return {
            "type": "snapshot", "v": PROTOCOL_VERSION, "seq": self.seq,
            "running": self.state["running"],
            "messages": self.state["messages"],
        }

    def send_snapshot(self):
        with self._lock:
            self._emit(json.dumps(self.snapshot_frame()))

    def add_user(self, text):
        with self._lock:
            self.state["messages"].append({"role": "user", "text": text})

    def event(self, kind, text="", tool_name=None, is_error=False):
        with self._lock:
            self.seq += 1
            frame = {"type": "event", "v": PROTOCOL_VERSION, "seq": self.seq,
                     "kind": kind, "text": text}
            if tool_name:
                frame["toolName"] = tool_name
            if is_error:
                frame["isError"] = True
            apply_event(self.state, kind, text, tool_name, is_error)
            self._log.append((self.seq, frame))
            if len(self._log) > EVENT_LOG_LIMIT:
                del self._log[: len(self._log) - EVENT_LOG_LIMIT]
            self._emit(json.dumps(frame))

    def resync(self, since_seq):
        with self._lock:
            if self._log and self._log[0][0] > since_seq + 1:
                # Requested range predates the retained log — heal with a
                # full snapshot (client adopts it as the new base).
                self._emit(json.dumps(self.snapshot_frame()))
                return
            if since_seq >= self.seq:
                return
            for seq, frame in self._log:
                if seq > since_seq:
                    self._emit(json.dumps(frame))


def load_config():
    cfg = {}
    path = os.path.expanduser("~/.minis-session.json")
    if os.path.exists(path):
        try:
            with open(path) as f:
                cfg.update(json.load(f))
        except Exception:
            pass
    return {
        "api_base": os.environ.get("MINIS_API_BASE", cfg.get("api_base", "https://api.openai.com/v1")),
        "api_key": os.environ.get("MINIS_API_KEY", cfg.get("api_key", "")),
        "model": os.environ.get("MINIS_MODEL", cfg.get("model", "gpt-4o-mini")),
        "system": cfg.get("system", "You are a helpful agent running on a remote Linux host. Use the shell tool to accomplish tasks."),
    }


def run_shell(command, timeout_s=DEFAULT_TIMEOUT_S, runner=None):
    """Execute on the host; returns (output, exit_code). Timeout → 124."""
    timeout_s = max(1, min(MAX_TIMEOUT_S, int(timeout_s)))
    runner = runner or subprocess.run
    try:
        r = runner(["/bin/sh", "-c", command], capture_output=True, text=True, timeout=timeout_s)
        out = (r.stdout or "") + (("\n" + r.stderr) if r.stderr else "")
        return truncate_output(out), r.returncode
    except subprocess.TimeoutExpired:
        return "[timed out after %ds]" % timeout_s, 124


def chat_completion(cfg, messages, http=None):
    """One non-streaming chat-completions step. Returns the message dict."""
    body = json.dumps({
        "model": cfg["model"],
        "messages": messages,
        "tools": [SHELL_TOOL],
        "stream": False,
    }).encode()
    req = urllib.request.Request(
        cfg["api_base"].rstrip("/") + "/chat/completions",
        data=body,
        headers={"Content-Type": "application/json",
                 "Authorization": "Bearer " + cfg["api_key"]},
    )
    opener = http or urllib.request.urlopen
    with opener(req, timeout=120) as resp:
        data = json.loads(resp.read().decode())
    return data["choices"][0]["message"]


def agent_loop(session, cfg, stop_flag, http=None, shell_runner=None):
    """Run one turn to completion (bounded, stoppable). Emits protocol events."""
    history = [{"role": "system", "content": cfg["system"]}]
    for m in session.state["messages"]:
        if m["role"] in ("user", "assistant") and not m.get("toolName"):
            history.append({"role": m["role"], "content": m["text"]})
    try:
        for _ in range(MAX_TURNS):
            if stop_flag.is_set():
                session.event("turn_end")
                return
            msg = chat_completion(cfg, history, http=http)
            history.append(msg)
            content = msg.get("content") or ""
            calls = msg.get("tool_calls") or []
            if content:
                session.event("text_delta", content)
            if not calls:
                session.event("turn_end")
                return
            for call in calls:
                if stop_flag.is_set():
                    session.event("turn_end")
                    return
                fn = call.get("function", {})
                try:
                    args = json.loads(fn.get("arguments") or "{}")
                except Exception:
                    args = {}
                command = args.get("command", "")
                session.event("tool_start", command, tool_name="shell")
                out, code = run_shell(command, args.get("timeout", DEFAULT_TIMEOUT_S), runner=shell_runner)
                session.event("tool_result", out, tool_name="shell", is_error=(code != 0))
                history.append({"role": "tool", "tool_call_id": call.get("id", ""), "content": out})
        session.event("error", "turn limit reached (%d)" % MAX_TURNS)
    except Exception as e:  # noqa: BLE001 — surface as protocol error, never crash
        session.event("error", "%s: %s" % (type(e).__name__, e))


def serve(stdin=None, stdout=None, cfg=None, http=None, shell_runner=None):
    """Main request loop. Injectable IO for tests."""
    stdin = stdin or sys.stdin
    stdout = stdout or sys.stdout
    cfg = cfg or load_config()
    out_lock = threading.Lock()

    def emit(line):
        with out_lock:
            stdout.write(line + "\n")
            stdout.flush()

    session = Session(emit)
    stop_flag = threading.Event()
    worker = [None]
    session.send_snapshot()

    for line in stdin:
        line = line.strip()
        if not line:
            continue
        try:
            req = json.loads(line)
        except Exception:
            continue
        action = req.get("action")
        if action == "send":
            text = (req.get("text") or "").strip()
            if not text:
                continue
            if worker[0] and worker[0].is_alive():
                session.event("error", "a turn is already running; stop it first")
                continue
            if not cfg.get("api_key"):
                session.add_user(text)
                session.event("error", "MINIS_API_KEY not configured on host")
                continue
            session.add_user(text)
            stop_flag.clear()
            t = threading.Thread(target=agent_loop,
                                 args=(session, cfg, stop_flag),
                                 kwargs={"http": http, "shell_runner": shell_runner},
                                 daemon=True)
            worker[0] = t
            t.start()
        elif action == "stop":
            stop_flag.set()
        elif action == "snapshot":
            session.send_snapshot()
        elif action == "resync":
            session.resync(int(req.get("sinceSeq", 0)))
    stop_flag.set()
    if worker[0]:
        worker[0].join(timeout=5)


if __name__ == "__main__":
    serve()