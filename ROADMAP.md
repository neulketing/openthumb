# Roadmap

Where OpenThumb goes next, in quarters. Dates are targets, not promises;
everything here is public so the order can be argued with in issues.

## Now (2026 Q3)

- **Play Store listing** — the remaining steps are account-level, not code:
  D-U-N-S number, developer account, then internal track. See
  `docs/play-submission.md`.
- **Sync v2** — downsync/merge (the worker already answers `GET /sync/list`
  and `/sync/item`), `chat` and `memory` kinds, and background periodic sync
  instead of the manual button.
- **Trigger conditions v2** — day-of-week constraints on rules and a per-rule
  "fire at most N times per day" cap, both small schema extensions of the
  existing window/cooldown gates.

## Next (2026 Q4)

- **Opt-in insights tier** — flip the local stats instrumentation
  (`docs/specs/stats-schema.md`) into a revocable, documented opt-in that
  powers rule recommendations. Structure only, never content; PRIVACY.md
  becomes tiered in the same release.
- **Fleet lab mode** — scheduled fleet jobs from the queue file, a summary
  report per drain, and device tagging so a fleet can be split into groups.
- **Real-device screenshot pass** — retake the store set on hardware before
  public track.

## Later (2027 Q1)

- **Cloud runs (exploratory)** — opt-in hosted execution for heavy async
  tasks, phone as actuator. This is the "Manus-shaped" option; it only
  happens if the sync tier proves there is demand, and it will be a separate
  declared service, not a silent mode.
- **F-Droid inclusion** — metadata is already in the tree; blocked on
  reproducible-build verification.

## Not planned

- iOS. This fork ships Android only (`src/ios/` is frozen).
- Any default-on data collection. Ever.
