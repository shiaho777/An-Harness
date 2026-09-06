# An-Harness

Android-first, on-device AI agent harness. GPLv3.

An-Harness puts a capable AI agent on your Android phone, running against a
real Linux sandbox on-device: shell, files, browser, skills, persistent
memory, and deep system integration — with the user's own model keys
(Claude, GPT, Gemini and OpenAI-compatible gateways).

## What it does

* **Real Linux shell on-device** — PRoot + Alpine rootfs: the agent
  installs packages, runs scripts, and works with real files in isolation.
* **Agent loop with guard rails** — formal event stream
  (`agent_start/turn/message/tool_execution/turn_end/agent_end`),
  parallel/sequential tool batches, output-limit truncation guard,
  loop detection (warn then circuit-break), and permission gating on
  every native offload.
* **Unified model layer** — one provider/model catalog with per-turn auth
  resolution and SSE streaming (Anthropic Messages + OpenAI-compatible).
* **Tools** — `shell_execute`, file read/write/edit, browser automation,
  `read_image`, persistent memory (`memory_write/get`).
* **Skills & sessions** — folder + `SKILL.md` skills loaded lazily
  (metadata in context, body on use); JSONL session history with
  fork/clone and sliding-window compaction.

## Layout

```
app/          Android app (Compose)
core/llm/     Model catalog, auth resolution, SSE streaming
core/agent/   AgentLoop, session store, compaction, skills
app/.../harness/  Adapters: platform tools into loop hooks
deps/         Sandbox dependency sources (proot, talloc, rclone-mobile)
scripts/      Sandbox asset preparation
shared/       Bash rules (single source of truth, copied to assets at build)
skills/       Bundled skills
docs/         Specs and CI records
evals/        Regression evals
```

## Pilot: `/harness` (default off)

`/harness` routes `file_read` through the `core:agent` adapters with
legacy fallback on error. Detector precheck, preflight, recording and
overlay are untouched — only the executor swaps.

## Build

Requirements: JDK 17, Android SDK (compileSdk 36), NDK r28+ (sandbox only),
Go 1.25+ (rclone backup artifact only).

```sh
./gradlew :core:llm:testDebugUnitTest :core:agent:testDebugUnitTest  # JVM unit tests, no device
./gradlew :app:assembleDebug                                        # APK (needs SDK + sandbox assets)
./deps/build_proot.sh && ./scripts/prepare_android_sandbox.sh       # sandbox binaries (once)
```

Copy `app/provider-customization.properties.example` to
`app/provider-customization.properties` for local builds (never committed).

See `docs/specs/an-harness-overview.md` for the full architecture and
`docs/known-failures.md` for environment-dependent CI records.

## License

GPLv3 — see `LICENSE`. The sandbox links PRoot (GPLv2) so the combined work
distributes under GPLv3. Bundled third-party terms:
`THIRD_PARTY_LICENSES.md`.

## Acknowledgements

An-Harness builds on open-source work including PRoot, Alpine Linux,
and the AndroidX / Kotlin / OkHttp ecosystems; the full inventory is in
`THIRD_PARTY_LICENSES.md`.
