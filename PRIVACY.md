# Privacy Policy — OpenThumb

*Last updated: 2026-07-28*

OpenThumb is an on-device AI agent. This policy covers the OpenThumb Android
app (`com.neulketing.openthumb`) distributed from this repository.

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

## Contact

neulketing@gmail.com — or open an issue at
[github.com/neulketing/openthumb](https://github.com/neulketing/openthumb).
