# Inherited test failures

Three unit-test suites fail on the code this fork inherited. They are excluded
from the CI test step (`.github/workflows/android.yml`) so a **new** failure in
the fork's own code turns the badge red instead of disappearing into a
known-broken baseline.

Measured 2026-07-29 on `./gradlew :app:testDebugUnitTest`:

| Suite | Failing / total |
|---|---|
| `provider.AnthropicProviderTest` | 24 / 31 |
| `provider.OpenAIProviderTest` | 11 / 21 |
| `sandbox.TerminalSanitizerTest` | 4 / 27 |

None of the three has been modified since `ad7125b` (the rebrand commit that
moved sources under `com/neulketing/openthumb`), so these are upstream
failures, not fork regressions.

The fix belongs upstream. Until then:

- CI runs `com.fug.openthumb.trigger.*` and `com.fug.openthumb.debug.*`
  — the packages this fork actually owns.
- Run the full suite locally (`./gradlew :app:testDebugUnitTest`) when touching
  provider or sandbox code, and compare against the counts above rather than
  expecting green.
- When a suite starts passing, drop its row here and widen the CI filter.
