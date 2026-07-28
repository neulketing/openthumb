# Stats schema — what OpenThumb may one day share, defined before it shares anything

Status: **design + local instrumentation only.** Events defined here are
written to an on-device JSONL file and go nowhere. There is no network
path, no upload flag, no server. Turning that on is a separate, future
decision that requires an in-app opt-in and a PRIVACY.md revision first.

## Why this exists

Products like Cursor run a data flywheel: usage signals make the product
better, which attracts usage. OpenThumb's equivalent fuel is not keystrokes
but *agent trajectories on a phone* — which rules fire, how runs end, where
the agent gets stuck. This document fixes the shape of that data now, so
"start collecting" later is a config change, not an architecture change.

## Principles (non-negotiable, any future implementation)

1. **Structure, never content.** Events carry types, counts, durations and
   outcomes. Never notification text, chat text, prompts, file names,
   contact names, or screenshots. If a field could contain a human's words,
   it does not ship.
2. **Off by default.** The sink writes locally; nothing leaves the device
   without an explicit, revocable opt-in that does not exist yet.
3. **Schema is the contract.** Only fields listed here may ever be
   collected. Adding a field = editing this document in the same commit.
4. **Bounded retention.** 30 days on device, 30 days on any future server.
5. **Bucketing over precision.** Timestamps are truncated to the hour,
   durations to fixed buckets, app packages to a category + a whitelist.

## Event: `trigger_run_stats`

One row per trigger-rule dispatch (fired, dropped, or failed).

| Field | Type | Notes |
|---|---|---|
| `ts` | int | epoch seconds, truncated to the hour |
| `rule_kind` | string | `"package"` \| `"package+text"` \| `"any"` — shape of the matcher, not its values |
| `app_category` | string | store category of the source app (`"social"`, `"productivity"`, …) or `"other"`; whitelist of exact package ids may be added here later, per principle 3 |
| `outcome` | string | `"fired"` \| `"cooldown"` \| `"quiet_hours"` \| `"outside_window"` \| `"in_flight_cap"` \| `"launch_failed"` |
| `latency_bucket` | string | `"<5s"` \| `"5-30s"` \| `"30-120s"` \| `">120s"` — match-to-dispatch |

## Event: `rule_pattern`

Emitted on rule create/edit/delete. Describes the *shape* of user demand
for the rules gallery / template recommendations.

| Field | Type | Notes |
|---|---|---|
| `ts` | int | epoch seconds, hour-truncated |
| `action` | string | `"created"` \| `"edited"` \| `"deleted"` \| `"toggled"` |
| `has_package` | bool | |
| `has_text_match` | bool | |
| `cooldown_bucket` | string | `"0"` \| `"<5m"` \| `"5-60m"` \| `">60m"` |
| `has_active_window` | bool | |
| `prompt_len_bucket` | string | `"<50"` \| `"50-200"` \| `">200"` — characters; the prompt itself never |

## Event: `trajectory_meta`

One row per headless agent run (trigger or scheduled task). The aggregate
fuel for tuning defaults and, eventually, evaluating mobile-agent runs.

| Field | Type | Notes |
|---|---|---|
| `ts` | int | epoch seconds, hour-truncated |
| `origin` | string | `"trigger"` \| `"scheduled"` \| `"manual"` |
| `tool_calls_bucket` | string | `"0"` \| `"1-5"` \| `"6-20"` \| `">20"` |
| `duration_bucket` | string | `"<30s"` \| `"30-120s"` \| `"2-10m"` \| `">10m"` |
| `outcome` | string | `"completed"` \| `"failed"` \| `"cancelled"` \| `"timeout"` |
| `model_tier` | string | `"small"` \| `"mid"` \| `"large"` — capability tier, not model name |

## On-device storage

`filesDir/stats/events.jsonl` — one JSON object per line, keys exactly as
above plus `v: 1` (schema version). Rotated at 1 MB, oldest lines dropped.
Deletable by the user at any time (app data clear); a future in-app
"Delete my stats" button maps to the same file.

## Explicitly out of scope (forever, not just now)

- Notification/chat/prompt content or embeddings of it
- Device identifiers, advertising id, precise location, contact graph
- Screenshots or accessibility-tree dumps
- Anything correlatable to a person without the user's own devices doing
  the correlating
