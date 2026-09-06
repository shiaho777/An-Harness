# An-Harness development rules

## Style

* Concise, technical, direct. No emojis in code/commits.
* Explain non-trivial designs as: problem → concrete trace → solution.

## Module boundaries

* `core:llm` is platform-agnostic (no `android.*` imports). Auth storage,
  Keystore, network policy live in `app/`.
* `core:agent` depends only on `core:llm` + coroutines/serialization.
  No app-runtime imports — `app/.../harness/HarnessBridge.kt` owns adaptation.
* `app/` owns the device runtime (sandbox, offloads, permissions).
  Never weaken `OffloadPermissionManager` — the loop has no permission
  model of its own; the offload gate is the authority.

## Correctness

* `AgentLoop` contract: every-result-`terminate`
  required to stop a batch; `length` stop fails the whole tool batch;
  unknown tools become error results, never throws.
* `ToolLoopDetector` thresholds stay `warning < critical < circuitBreaker`;
  `record()` never returns CRITICAL (check-only by spec).
* New agent behavior needs a unit test in `core/*/src/test` (faux
  StreamFn + fake tools, no network/keys). Run
  `./gradlew :core:llm:testDebugUnitTest :core:agent:testDebugUnitTest`.

## Deps

* Pin exact versions for direct external deps.
* `minSdk 26`, `arm64-v8a` only, Kotlin 2.1.0, coroutines 1.9.0,
  serialization-json 1.7.3, okhttp 4.12.0 — bump together across modules.
