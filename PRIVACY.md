# Privacy Policy — OpenThumb

*Last updated: 2026-07-28*

OpenThumb is an on-device AI agent. This policy covers the OpenThumb Android
app (`com.fug.openthumb`) distributed from this repository.

## What we collect

**Nothing.** OpenThumb has no analytics, no telemetry, no accounts, and no
server of ours. Crash reports are written to files on your device
(`filesDir/logs/`) and go nowhere unless you choose to share them.

## What leaves your device

Only what you configure it to send:

- **Model providers** — your chats, and any files or screen content you let the
  agent read, are sent to the AI provider you set up (Anthropic, OpenAI, Google,
  or another). Their privacy policies apply. Your API keys are stored on the
  device and sent only to the matching provider.
- **Sync (optional, off by default)** — only if you enter the URL and token of
  a sync server you operate yourself (see `tools/sync-worker/`), the app
  uploads your trigger rules, trigger run history and scheduled tasks to that
  server. Chats are not synced in v1. The token is stored on the device and
  sent only to your server. You can turn sync off or wipe the settings at any
  time.
- **The web** — when you ask the agent to browse or call APIs, those requests
  go to those sites, like a browser.

## Permissions

The app requests permissions (accessibility, notifications, storage, and
others) solely to give the agent the capabilities you invoke. The accessibility
service is used to let the agent see and act on the screen at your direction —
never for data harvesting; nothing it reads is stored off-device or sent
anywhere except to your configured model provider as part of a conversation.

## Your data, your device

Conversations, memory, skills and the Linux sandbox all live in app-private
storage. Uninstalling the app deletes them. There is nothing to request a copy
of and nothing for us to delete, because we never had it.

The app also keeps a small local file of *structured* run statistics (e.g.
"an accessibility-category notification rule fired at 2pm, duration bucket
5–30s") — never notification or chat content — capped at 1 MB and deleted
with the app. It is written for an optional, not-yet-existing sync feature
and is sent nowhere. The exact fields are fixed in
`docs/specs/stats-schema.md`.

## Contact

neulketing@gmail.com — or open an issue at
[github.com/neulketing/openthumb](https://github.com/neulketing/openthumb).
