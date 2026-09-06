# An-Harness architecture

## Modules

* **`core:llm`** — the model layer. `ModelCatalog` (builtin models plus
  dynamic gateway listings), `AuthResolver` (explicit key > stored
  credential > environment), and SSE streamers. Failures surface as stream
  events plus a terminal `ERROR` stop — never thrown.
* **`core:agent`** — the loop. `AgentLoop` runs an outer follow-up loop
  (messages arriving after stop) around an inner steering loop (tool calls
  plus messages injected before the next request). `AgentSessionStore`
  persists JSONL history with fork/clone. `Compaction` trims context past
  a threshold (sliding window today, summarizer hook reserved).
  `SkillStore` matches skills on metadata; bodies load only on use.
* **`app/.../harness`** — adapters between the platform tools and the
  loop: definition conversion, file-tool executors, delegation hooks for
  shell/browser/memory, and the loop guard (`before/afterToolCall`).

## A turn, end to end

`prompt()` → `turn_start` → stream assistant (context transform compacts
past 80 messages → SSE) → `executeBatch` (parallel unless a tool opts
into `sequential`; a `length` stop fails the whole batch because truncated
arguments are unsafe) → `turn_end` → `shouldStopAfterTurn` → drain
steering messages, else follow-ups, else `agent_end`.

Tool results: unknown tools and blocked calls become error results; early
stop requires *every* result in the batch to set `terminate`.

## Device runtime

* **Sandbox** — `deps/build_proot.sh` cross-compiles static proot;
  `scripts/prepare_android_sandbox.sh` fetches the Alpine rootfs. Both
  land in `assets/` (`noCompress tar.gz, proot-aarch64`); native loaders
  stay `*.so` in `nativeLibraryDir` (Android 10+ W^X). arm64-v8a only.
* **Permissions** — `OffloadPermissionManager` plus the offload IPC gate
  are authoritative. `beforeToolCall` may deny, never allow-list.
* **Auth** — keys live in Keystore/EncryptedSharedPreferences behind the
  credential store; OAuth tokens re-resolve per turn.
* **Budgets** — SSE read timeout disabled, 30s connect; image payloads
  capped at the tool layer; compaction keeps Compose and context bounded
  on low-RAM devices.

## Roadmap

Migrate shell/browser/memory executors fully behind the loop adapters;
LLM-backed branch summarization; vision-capable routing via
`supportsVision`; gateway `/models` catalog refresh; scenario evals in
`evals/` on the faux stream function.
