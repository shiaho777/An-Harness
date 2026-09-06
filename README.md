# An-Harness

Android-first, on-device AI agent harness. GPLv3.

Combines two proven bases:

* **Runtime/sandbox/tools** from [OpenMinis](https://github.com/OpenMinis/OpenMinis)
  (PRoot + Alpine rootfs, native offloads, `shell_execute` / file / browser /
  memory tools, `SKILL.md` skills). Android tree only — iOS was dropped.
* **Agent architecture** from [pi](https://github.com/earendil-works/pi-mono)
  (`pi-ai` provider abstraction + `agent-core` loop), re-implemented in
  idiomatic Kotlin as `core:llm` / `core:agent` (not a TS embedding).

## Layout

```
app/          Android app (Compose, package com.anharness.app)
core/llm/     pi-ai port: Provider/Model catalog, auth layering, SSE streamers
core/agent/   agent-core port: AgentLoop, SessionStore, Compaction, Skills
app/.../harness/HarnessBridge.kt  adapter: OpenMinis tools + ToolLoopDetector → AgentLoop hooks
sandbox/      proot build + rootfs scripts (see scripts/prepare_android_sandbox.sh)
shared/       bashism rules (single source of truth, copied to assets at build)
skills/       bundled SKILL.md skills
docs/specs/   architecture specs
evals/        regression evals (faux-provider harness)
```

## What changed vs OpenMinis (P1)

* `AgentLoop` replaces the ad-hoc dispatch: formal `AgentEvent` stream
  (`agent_start/turn/message/tool_execution/turn_end/agent_end`), steering vs
  follow-up queues, parallel/sequential tool batches, output-limit truncation
  guard, `before/afterToolCall` hooks.
* `ToolLoopDetector` is preserved but rewired as `before/afterToolCall`
  (critical blocks, warnings appended to results) instead of inline checks.
* `ModelCatalog` unifies the per-provider model lists; `AuthResolver`
  implements stored > env > ambient resolution. SSE streaming covers
  Anthropic Messages + OpenAI-compatible gateways (OpenRouter, self-hosted).
* `AgentSessionStore` (JSONL + fork/clone) and `SkillStore`
  (metadata-in-context, body-on-use) land the pi session/skill model.

## Pilot: `/harness` (default off)

`/harness` toggles routing `file_read` through the `core:agent` adapters
(`AgentLoopPrefs`, persisted). Detector precheck, preflight, recording and
overlay are untouched — only the executor swaps, with legacy fallback on
error. See `docs/known-failures.md` for the CI-excluded env failures
(identical on pristine upstream).

## Build

Requirements: JDK 17, Android SDK (compileSdk 36), NDK r28+ (sandbox only).

```sh
./gradlew :core:llm:testDebugUnitTest :core:agent:testDebugUnitTest  # JVM unit tests, no device
./gradlew :app:assembleDebug                                        # APK (needs SDK + rootfs assets)
./deps/build_proot.sh && ./scripts/prepare_android_sandbox.sh       # sandbox binaries (once)
```

See `docs/specs/an-harness-overview.md` for the full architecture.

## License

GPLv3 — see `LICENSE`. The sandbox links PRoot (GPLv2) so the combined work
distributes under GPLv3, same as upstream OpenMinis. Bundled third-party
terms: `THIRD_PARTY_LICENSES.md`. Pi-ported modules (`core/`) derive from
MIT-licensed pi sources; original copyright retained in file headers.
