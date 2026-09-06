# An-Harness overview (P1)

## Problem

OpenMinis-Android runs tools through a 12k-line `ChatViewModel` with inline
loop checks; each LLM provider is a bespoke client. pi proved a cleaner shape
— unified `Provider/Model` catalog, formal `AgentEvent` loop, JSONL sessions,
lazy skills — but ships TypeScript with no permission model and no mobile
sandbox. Port the shape, keep the Android runtime.

## Shape (pi → An-Harness)

```
pi-ai types/models/providers/api → core:llm (LlmApi/LlmModel/LlmAuth/LlmStreaming/LlmStreamer)
agent-core agent-loop            → core:agent AgentLoop (inner steering + outer follow-up)
agent-core session               → core:agent AgentSessionStore (messages.jsonl + fork/clone)
agent-core compaction            → core:agent Compaction (sliding window now, LLM summarizer hook)
skills.ts + SKILL.md             → core:agent SkillStore + skills/
ToolLoopDetector (kept)          → HarnessBridge.LoopGuard as before/afterToolCall
AgentTools.makeAgentTools        → HarnessBridge.toLlmTool + file/delegating adapters
```

Example turn: `prompt()` → `turn_start` → `streamAssistant` (transformContext
compacts past 80 msgs → SSE) → `executeBatch` (parallel unless a tool opts
`sequential`; `length` stop fails all) → `turn_end` → `shouldStopAfterTurn` →
drain steering, else follow-ups, else `agent_end`.

## Android specifics (no pi equivalent)

* Sandbox: `deps/build_proot.sh` static proot + `scripts/prepare_android_sandbox.sh`
  rootfs → `assets/` (`noCompress tar.gz, proot-aarch64`); loaders stay
  `*.so` in `nativeLibraryDir` (Android 10+ W^X).
* Permissions: `OffloadPermissionManager` + offload IPC gate stay authoritative.
  `beforeToolCall` may deny, never allow-list.
* Auth: keys in Keystore/EncryptedSharedPreferences via `AuthResolver(store)`;
  OAuth refresh resolves per-turn (`StreamOptions.apiKey` re-read each turn).
* Perf: SSE read timeout disabled, 30s connect; image payloads capped
  (file_read T-FILEREAD-CAP); compaction threshold 80 keeps Compose + context
  bounded on low-RAM devices.

## P2 roadmap

ChatViewModel migration to AgentLoop (shell/browser/memory bodies move into
HarnessBridge, drop delegating lambdas); branch-summarization `Summarizer`;
Vision Group routing through `LlmModel.supportsVision`; evals runner in
`evals/` on the faux StreamFn; model catalog refresh from gateway `/models`.
