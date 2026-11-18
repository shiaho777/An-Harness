#!/usr/bin/env python3
"""Unit tests for minis-session-server (P4 slice 5). Stdlib unittest only.

Run: python3 scripts/test_minis_session_server.py

Pins the wire protocol + fold semantics against the Kotlin client
(RemoteSessionProtocol.applyEvent) so both ends converge to identical
state from the same frames.
"""

import importlib.util
import io
import json
import os
import subprocess
import sys
import threading
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SERVER_PATH = os.path.join(HERE, "..", "app", "src", "main", "assets", "remote", "minis-session-server.py")

spec = importlib.util.spec_from_file_location("minis_session_server", SERVER_PATH)
srv = importlib.util.module_from_spec(spec)
spec.loader.exec_module(srv)


def fresh_state():
    return {"messages": [], "running": False, "lastError": None}


class FoldSemanticsTest(unittest.TestCase):
    """Mirror of RemoteSessionTest folding cases (Kotlin side)."""

    def test_text_deltas_accumulate(self):
        s = fresh_state()
        srv.apply_event(s, "text_delta", "Hel")
        srv.apply_event(s, "text_delta", "lo")
        self.assertEqual(len(s["messages"]), 1)
        self.assertEqual(s["messages"][0]["text"], "Hello")
        self.assertTrue(s["running"])

    def test_tool_events_append(self):
        s = fresh_state()
        s["messages"].append({"role": "assistant", "text": "thinking"})
        srv.apply_event(s, "tool_start", "running", tool_name="shell")
        srv.apply_event(s, "tool_result", "ok", tool_name="shell")
        self.assertEqual(len(s["messages"]), 3)
        self.assertEqual(s["messages"][1]["role"], "tool")
        self.assertEqual(s["messages"][2]["toolName"], "shell")

    def test_text_after_tool_starts_new_assistant(self):
        s = fresh_state()
        s["messages"].append({"role": "tool", "text": "result", "toolName": "shell"})
        srv.apply_event(s, "text_delta", "answer")
        self.assertEqual(len(s["messages"]), 2)
        self.assertEqual(s["messages"][1]["role"], "assistant")

    def test_turn_end_and_error(self):
        s = fresh_state()
        s["running"] = True
        srv.apply_event(s, "turn_end")
        self.assertFalse(s["running"])
        srv.apply_event(s, "error", "boom")
        self.assertEqual(s["lastError"], "boom")
        self.assertFalse(s["running"])

    def test_unknown_kind_ignored(self):
        s = fresh_state()
        s["messages"].append({"role": "user", "text": "x"})
        before = json.dumps(s)
        srv.apply_event(s, "future_kind", "y")
        self.assertEqual(json.dumps(s), before)

    def test_error_result_flag(self):
        s = fresh_state()
        srv.apply_event(s, "tool_result", "failed", tool_name="shell", is_error=True)
        self.assertTrue(s["messages"][0]["isError"])


class TruncateTest(unittest.TestCase):
    def test_short_unchanged(self):
        self.assertEqual(srv.truncate_output("hello"), "hello")

    def test_long_keeps_head_tail(self):
        long = "A" * 30000 + "B" * 30000
        out = srv.truncate_output(long)
        self.assertTrue(out.startswith("A"))
        self.assertTrue(out.endswith("B"))
        self.assertIn("omitted", out)
        self.assertLessEqual(len(out), srv.MAX_OUTPUT_CHARS + 64)


class SessionFramingTest(unittest.TestCase):
    def setUp(self):
        self.lines = []
        self.session = srv.Session(self.lines.append)

    def frames(self):
        return [json.loads(l) for l in self.lines]

    def test_events_sequenced_and_folded(self):
        self.session.event("text_delta", "a")
        self.session.event("text_delta", "b")
        self.session.event("turn_end")
        frames = self.frames()
        self.assertEqual([f["seq"] for f in frames], [1, 2, 3])
        self.assertTrue(all(f["v"] == 1 and f["type"] == "event" for f in frames))
        self.assertEqual(self.session.state["messages"][0]["text"], "ab")
        self.assertFalse(self.session.state["running"])

    def test_snapshot_reflects_state(self):
        self.session.add_user("hi")
        self.session.event("text_delta", "hello")
        self.session.send_snapshot()
        snap = self.frames()[-1]
        self.assertEqual(snap["type"], "snapshot")
        self.assertEqual(snap["seq"], 1)
        self.assertEqual(snap["messages"][0], {"role": "user", "text": "hi"})
        self.assertEqual(snap["messages"][1]["text"], "hello")

    def test_resync_replays_only_newer(self):
        self.session.event("text_delta", "a")
        self.session.event("text_delta", "b")
        self.session.event("text_delta", "c")
        self.lines.clear()
        self.session.resync(1)
        seqs = [f["seq"] for f in self.frames()]
        self.assertEqual(seqs, [2, 3])

    def test_resync_up_to_date_silent(self):
        self.session.event("text_delta", "a")
        self.lines.clear()
        self.session.resync(1)
        self.assertEqual(self.lines, [])

    def test_resync_before_log_falls_back_to_snapshot(self):
        # Force log eviction by shrinking the limit.
        old = srv.EVENT_LOG_LIMIT
        srv.EVENT_LOG_LIMIT = 2
        try:
            for i in range(5):
                self.session.event("text_delta", str(i))
            self.lines.clear()
            self.session.resync(0)  # log starts at seq 4 > 0+1 → snapshot
            frames = self.frames()
            self.assertEqual(len(frames), 1)
            self.assertEqual(frames[0]["type"], "snapshot")
            self.assertEqual(frames[0]["seq"], 5)
        finally:
            srv.EVENT_LOG_LIMIT = old


class RunShellTest(unittest.TestCase):
    def test_runs_and_captures(self):
        out, code = srv.run_shell("echo hello")
        self.assertEqual(code, 0)
        self.assertIn("hello", out)

    def test_nonzero_exit(self):
        out, code = srv.run_shell("exit 3")
        self.assertEqual(code, 3)

    def test_timeout_returns_124(self):
        def fake_runner(cmd, **kw):
            raise subprocess.TimeoutExpired(cmd, kw.get("timeout", 1))
        out, code = srv.run_shell("sleep 999", timeout_s=1, runner=fake_runner)
        self.assertEqual(code, 124)
        self.assertIn("timed out", out)

    def test_timeout_clamped(self):
        captured = {}
        def fake_runner(cmd, **kw):
            captured["timeout"] = kw.get("timeout")
            class R: stdout, stderr, returncode = "", "", 0
            return R()
        srv.run_shell("x", timeout_s=99999, runner=fake_runner)
        self.assertEqual(captured["timeout"], srv.MAX_TIMEOUT_S)


class AgentLoopTest(unittest.TestCase):
    """Loop with a fake LLM + fake shell — no network, no real commands."""

    def setUp(self):
        self.lines = []
        self.session = srv.Session(self.lines.append)
        self.cfg = {"api_base": "http://fake", "api_key": "k", "model": "m", "system": "sys"}
        self.stop = threading.Event()

    def frames(self):
        return [json.loads(l) for l in self.lines]

    def test_plain_answer_emits_delta_and_turn_end(self):
        def http(req, timeout=None):
            class Resp:
                def read(self):
                    return json.dumps({"choices": [{"message": {"content": "hi there"}}]}).encode()
                def __enter__(self): return self
                def __exit__(self, *a): return False
            return Resp()
        self.session.add_user("hello")
        srv.agent_loop(self.session, self.cfg, self.stop, http=http)
        kinds = [f["kind"] for f in self.frames()]
        self.assertEqual(kinds, ["text_delta", "turn_end"])
        self.assertEqual(self.session.state["messages"][-1]["text"], "hi there")

    def test_tool_call_round_trip(self):
        responses = [
            {"content": "", "tool_calls": [{"id": "c1", "function": {"name": "shell", "arguments": json.dumps({"command": "echo x"})}}]},
            {"content": "done"},
        ]
        idx = [0]
        def http(req, timeout=None):
            msg = responses[idx[0]]; idx[0] += 1
            class Resp:
                def read(self):
                    return json.dumps({"choices": [{"message": msg}]}).encode()
                def __enter__(self): return self
                def __exit__(self, *a): return False
            return Resp()
        def shell_runner(cmd, **kw):
            class R: stdout, stderr, returncode = "x\n", "", 0
            return R()
        self.session.add_user("run echo")
        srv.agent_loop(self.session, self.cfg, self.stop, http=http, shell_runner=shell_runner)
        kinds = [f["kind"] for f in self.frames()]
        self.assertEqual(kinds, ["tool_start", "tool_result", "text_delta", "turn_end"])
        tool_result = [f for f in self.frames() if f["kind"] == "tool_result"][0]
        self.assertIn("x", tool_result["text"])
        self.assertNotIn("isError", tool_result)

    def test_llm_failure_becomes_error_event(self):
        def http(req, timeout=None):
            raise RuntimeError("upstream down")
        self.session.add_user("x")
        srv.agent_loop(self.session, self.cfg, self.stop, http=http)
        kinds = [f["kind"] for f in self.frames()]
        self.assertEqual(kinds, ["error"])
        self.assertIn("upstream down", self.session.state["lastError"])

    def test_stop_flag_ends_turn(self):
        self.stop.set()
        self.session.add_user("x")
        srv.agent_loop(self.session, self.cfg, self.stop, http=lambda *a, **k: self.fail("must not call LLM"))
        kinds = [f["kind"] for f in self.frames()]
        self.assertEqual(kinds, ["turn_end"])


class ServeTest(unittest.TestCase):
    """End-to-end request handling over fake stdin/stdout."""

    def test_snapshot_request_and_missing_key_error(self):
        stdin = io.StringIO('{"action":"snapshot"}\n{"action":"send","text":"hi"}\n')
        stdout = io.StringIO()
        cfg = {"api_base": "http://fake", "api_key": "", "model": "m", "system": "s"}
        srv.serve(stdin=stdin, stdout=stdout, cfg=cfg)
        frames = [json.loads(l) for l in stdout.getvalue().splitlines()]
        types = [f["type"] for f in frames]
        self.assertEqual(types[0], "snapshot")  # initial bootstrap
        self.assertIn("snapshot", types[1:])    # explicit request
        errors = [f for f in frames if f.get("kind") == "error"]
        self.assertTrue(any("MINIS_API_KEY" in e["text"] for e in errors))

    def test_malformed_requests_ignored(self):
        stdin = io.StringIO('not json\n{"action":"unknown"}\n\n')
        stdout = io.StringIO()
        srv.serve(stdin=stdin, stdout=stdout, cfg={"api_key": "", "api_base": "", "model": "", "system": ""})
        frames = [json.loads(l) for l in stdout.getvalue().splitlines()]
        self.assertEqual(len(frames), 1)  # only the initial snapshot
        self.assertEqual(frames[0]["type"], "snapshot")


if __name__ == "__main__":
    unittest.main(verbosity=2)