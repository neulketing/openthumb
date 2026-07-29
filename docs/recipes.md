# Trigger recipes

A rule is three things: which app's notifications to watch, an optional text
match, and the prompt to run. Placeholders `{app}`, `{title}` and `{text}` are
filled in from the notification before the prompt reaches the agent.

Rules live in **Scheduled tasks → the bell icon**. Notification access has to
be granted by hand; the rules screen links to that settings page. Use the
editor's **Test** button to fire a rule once with a synthetic notification
before you arm it.

Package names below are the common ones. To find another app's, open the rules
screen after that app has posted a notification — or run
`adb shell pm list packages | grep <name>`.

---

## Reply in the chat it came from

The rule that makes a messenger a two-way channel to the agent. Turn on
**Reply to notification** in the rule editor; the answer goes back through the
notification's own quick-reply action.

| Field | Value |
|---|---|
| App package | `com.kakao.talk` (or `com.samsung.android.messaging`, `org.telegram.messenger`, `jp.naver.line.android`) |
| Match | *(leave empty to answer everything, or a keyword so only tagged messages reach the agent)* |
| Prompt | `{title} sent: "{text}". Reply in one or two sentences, in the same language they used. If you cannot answer confidently, say you will check and get back to them.` |
| Reply to notification | **on** |
| Cooldown | 60s |

Start with a match keyword. Without one, every message in every thread wakes
the agent — including group chats.

## Away auto-reply, work hours only

Same as above with an active window, so it only answers while you are in a
meeting block.

| Field | Value |
|---|---|
| App package | `com.kakao.talk` |
| Prompt | `{title} messaged: "{text}". Tell them I am in a meeting and will reply after it ends. One short sentence, their language.` |
| Reply to notification | **on** |
| Active window | 09:00–18:00 |
| Cooldown | 600s — one auto-reply per person per ten minutes, not per message |

## File the delivery, don't answer it

A read-only rule: no reply action, so leave **Reply to notification** off.

| Field | Value |
|---|---|
| App package | *(empty — any app)* |
| Match | `배송` or `delivery` |
| Prompt | `A delivery notification arrived from {app}: "{title} — {text}". Append one line to ~/notes/deliveries.md in the sandbox: date, carrier, item, status. Create the file if missing.` |
| Cooldown | 30s |

## Bank alerts into a ledger

| Field | Value |
|---|---|
| App package | your banking app |
| Match | `출금` or `승인` |
| Prompt | `Transaction alert: "{text}". Append a row to ~/notes/ledger.csv as date,merchant,amount,category. Guess the category from the merchant name; use "unknown" if unsure.` |
| Quiet hours | on — nothing at 3am |

## Morning digest of what piled up

Not a notification rule — a scheduled task. Included here because it is the
other half of the pattern: triggers capture, the schedule summarises.

| Field | Value |
|---|---|
| Schedule | daily 08:00 |
| Prompt | `Read ~/notes/deliveries.md and ~/notes/ledger.csv for entries from the last 24 hours. Write a three-line summary to ~/notes/digest.md under today's date. If nothing new, write "nothing new".` |

---

## Rules that keep this from running away

Worth knowing before you arm anything:

- The app ignores its **own** notifications — otherwise a completion notice
  would trigger the next run forever.
- Ongoing and group-summary notifications are skipped.
- **Global quiet hours** suspend every rule overnight.
- A rule's **cooldown is claimed before dispatch**, so a burst of messages
  fires it once, not twenty times.
- At most **two** trigger runs are in flight at a time.
- A reply rule skips notifications with no reply action, so a bank push does
  not burn an agent run to produce an answer with nowhere to go.

Every firing lands in the run history (clock icon on the rules screen), so you
can see what the phone did while you were away.
