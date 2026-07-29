# Google Play submission pack

Everything Play asks for, pre-written. Copy into the console at submission
time. Owner action still required: the $25 developer account, and taking the
store screenshots on a real device.

## Account type — decide this first (it sets the timeline)

Play gates production access on account type, not app category:

| | Personal | Organization (개인사업자 / 법인) |
|---|---|---|
| Cost | $25 one-time | $25 one-time |
| Closed testing before production | **12 testers, opted in 14 consecutive days** — for every new app | **Exempt** |
| Extra requirement | none | D-U-N-S number (free, up to ~30 days; ~8 business days paid) |
| Start time | immediately | after D-U-N-S + verification |

A Korean 개인사업자 qualifies as an organization: D&B issues D-U-N-S to sole
proprietors, and 사업자등록증 is the business document verification asks for.
**The business name must match exactly across three places** — Play account,
사업자등록증, and the D-U-N-S registration — or verification fails.

Play separates the **legal business name** (used for verification) from the
**public developer name** shown on the store listing, so the listing can read
`neulketing` while the account is verified against the business entity.

Recommendation: **개인사업자 → organization account.** The tester gate applies
to every new app on a personal account, not just the first, and a farm-run
tester pool risks the account itself. D-U-N-S is the long pole, so apply
before anything else.

## Store listing

**App name**: OpenThumb

**Short description** (80 max)
> On-device AI agent with a real Linux shell — works even when you are not looking.

**Full description**
> OpenThumb turns your phone into an AI agent that acts on its own.
>
> Bring your own model — Claude, GPT, Gemini and others, via your own API keys.
> The agent gets a real computer to work with: a sandboxed Alpine Linux shell
> running on the device, browser automation, extensible skills and persistent
> memory.
>
> What makes it autonomous:
> • Notification triggers — when a message matching your rule arrives, the
>   agent wakes up and handles it. No tap required.
> • Scheduled runs — prompts that fire on a clock and survive reboots.
> • Fleet orchestration — drive many devices from one host (developer tool).
>
> Privacy: no analytics, no telemetry, no accounts, no server of ours. Your
> keys and conversations go only to the model provider you configure.
>
> OpenThumb is free and open source (GPL-3.0), a fork of OpenMinis — most of
> the app is their work. Source: github.com/neulketing/openthumb

**Category**: Tools · **Content rating**: Everyone (questionnaire: no UGC
sharing, no ads, no purchases) · **Privacy policy URL**:
https://neulketing.github.io/openthumb/ → PRIVACY.md

## Accessibility API declaration

Play requires a declaration for any app using AccessibilityService. Answers:

**What is the core functionality of your app?**
> An AI agent that carries out tasks on the user's device on their behalf.

**How does your app use the Accessibility API?**
> The agent reads the on-screen UI tree and injects taps, text and scrolls in
> order to operate other apps as instructed by the user — the same actions the
> user would perform manually. Screen content is read only while a task the
> user initiated (or an automation they configured) is running.

**Is the use disclosed to the user?**
> Yes. A prominent in-app disclosure appears before the user is sent to the
> system accessibility screen, stating what is accessed, what it is used for,
> and that the data is not shared. Implemented in
> `ui/settings/SystemPermissionsScreen.kt`
> (`AccessibilityDisclosureDialog`), strings `a11y_disclosure_*`.

**Is any of this data collected or shared?**
> Not by us. Screen content is transmitted only to the AI model provider the
> user configured, as part of their own conversation, under their own API key.
> The app has no backend, no analytics and no third-party SDKs receiving it.

**Functionality if the permission is denied**: The app remains fully usable for
chat, the Linux shell and scheduled runs; only device automation is
unavailable.

## Data safety form

| Question | Answer |
|---|---|
| Does your app collect or share user data? | **No** |
| Data encrypted in transit? | Yes (HTTPS to the user's chosen provider) |
| Can users request deletion? | N/A — nothing is held; uninstalling removes local data |
| Third-party SDKs collecting data? | None. ACRA writes crash files locally only. |

Rationale for "No": Play defines collection as transmission off the device
**to the developer or a third party acting for them**. Traffic to a provider
the user configured with their own key is user-directed, not developer
collection — the same treatment as a browser.

## Sensitive permissions justification

- **AccessibilityService** — see the declaration above.
- **Notification listener** — powers notification triggers: the user writes a
  rule ("when a message from X arrives, do Y"), and the app reads notification
  title/text to match it. Never uploaded.
- **Foreground service (`dataSync|specialUse`)** — keeps a running agent alive
  with the screen off.
- **SYSTEM_ALERT_WINDOW** — the tool-status overlay while the agent acts.

## Pre-submission checklist

- [x] Signed release APK/AAB (keystore outside the repo)
- [x] Privacy policy live at a public URL
- [x] Prominent accessibility disclosure in-app
- [x] `foregroundServiceType` matches actual behaviour (was `mediaPlayback`)
- [x] Signed **AAB** built (`bundleRelease`, 20 MB, jarsigner verified)
- [x] Screenshots — 6 phone shots at 1080x2400, `fastlane/.../phoneScreenshots/`
      (captured on an arm64 API-35 emulator; retake on a real device before launch if you want photographic status bars)
- [x] Feature graphic 1024×500 + icon 512×512 — `fastlane/metadata/android/en-US/images/`
- [~] D-U-N-S number — **requested 2026-07-29** via Apple's lookup form as
      "REDACTED" (HQ: Gangseo-gu, Busan). No existing D&B record, so a new
      registration was submitted; D&B responds within 5 business days (watch
      neulketing@gmail.com). Free via Apple's lookup form (works for non-US
      businesses; the number is shared with Google). Needs an Apple ID sign-in.
      **Owner action** — cannot be automated.
- [ ] Developer account ($25, owner) — organization type, after D-U-N-S.
      **Owner action** — payment + identity verification.
      **In progress (2026-07-29)**: org signup started as neulketing@gmail.com,
      steps 완료: account type(company/business) → Android dev ID → developer
      name "neulketing". Stopped at the payments-profile step: a NEW payments
      profile must be created for REDACTED with the D-U-N-S number (필수),
      NOT the existing "퍼그" profile (different entity). The $25 payment
      comes after. Resume: play.google.com/console/signup continues where it
      left off once the D-U-N-S arrives.
