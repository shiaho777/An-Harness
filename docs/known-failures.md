# Known environment failures (CI tracks, never silently skips)

18 tests fail identically on pristine upstream OpenMinis-Android and on
An-Harness in container/JDK CI runners. Verified by running the same two
classes against an unmodified upstream tree (`/tmp` pristine check,
2026-09-06): same 18 failures, same messages. Migration introduces zero
regressions.

## `com.anharness.app.provider.OpenAIProviderTest` (14)

`LLMError$TransientError: Server returned an empty response (connection
dropped or upstream error)` on every MockWebServer-backed case.
Upstream provider code, untouched by the package rename. Suspected runner
cause: MockWebServer 4.12.0 read behavior under the CI JDK. Repro locally
with `./gradlew :app:testDebugUnitTest --tests
"com.anharness.app.provider.OpenAIProviderTest"`.

## `com.anharness.app.sandbox.TerminalSanitizerTest` (4)

CR-folding whitespace expectations (`expected:<complete[ ]> but
was:<complete[]>`, etc.). Upstream sanitizer code, untouched. Suspected
runner cause: line-ending/whitespace handling under the CI JDK.

## CI policy

* `unit` job passes `-PanharnessSkipEnvTests` (excludes exactly these two
  classes) and must stay green.
* `known-failing` job runs exactly these two classes with
  `continue-on-error: true` so regressions-within-failures stay visible.
* Removing an entry here requires the test passing in CI, not locally.
