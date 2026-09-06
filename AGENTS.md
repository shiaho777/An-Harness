# An-Harness development rules

## Style

* Concise, technical, direct. No emojis in code/commits.
* Explain non-trivial designs as: problem → concrete trace → solution.

## Module boundaries

* `core:llm` is platform-agnostic (no `android.*` imports). Auth storage,
  Keystore, network policy live in `app/`.
* `core:agent` depends only on `core:llm` + coroutines/serialization.
  No OpenMinis imports — `app/.../harness/HarnessBridge.kt` owns adaptation.
* `app/` keeps the OpenMinis runtime (sandbox, offloads, permissions).
  Never weaken `OffloadPermissionManager` — pi has no permission system;
  do not copy that weakness.

## Correctness

* `AgentLoop` semantics mirror pi `agent-loop.ts`: every-result-`terminate`
  required to stop a batch; `length` stop fails the whole tool batch;
  unknown tools become error results, never throws.
* `ToolLoopDetector` thresholds stay `warning < critical < circuitBreaker`;
  `record()` never returns CRITICAL (check-only by spec).
* New agent behavior needs a unit test in `core/*/src/test` (faux
  StreamFn + fake tools, no network/keys). Run
  `./gradlew :core:llm:testDebugUnitTest :core:agent:testDebugUnitTest`.

## Deps

* Pin exact versions for direct external deps (mirrors pi supply-chain rule).
* `minSdk 26`, `arm64-v8a` only, Kotlin 2.1.0, coroutines 1.9.0,
  serialization-json 1.7.3, okhttp 4.12.0 — bump together across modules.
