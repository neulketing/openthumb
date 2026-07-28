# Security

## Reporting

Email **neulketing@gmail.com**. Please do not open a public issue for a
vulnerability. If the issue is in upstream OpenMinis rather than fork-specific
code, report it to [OpenMinis](https://github.com/OpenMinis/OpenMinis) too — this
fork cannot fix it for their users.

This is a small project with no dedicated security team and no bounty. Expect a
best-effort reply, not an SLA.

## What this app actually is

OpenThumb runs an LLM agent on your phone with a real Linux shell, accessibility
control of the device, and integrations with your apps and data. That combination
is the whole point of the app, and it is also the risk:

- **Private data access** — files, messages, screen contents, whatever the
  accessibility service can see.
- **Untrusted input** — web pages, documents and messages the agent reads can
  contain instructions aimed at the model rather than at you.
- **Outbound capability** — a shell, network access, and the ability to tap
  things on your behalf.

Prompt injection is a structural property of that design, not a bug we can close.
An attacker who controls text the agent reads can try to steer what the agent
does next. Approval prompts and sandboxing raise the cost of an attack; they do
not eliminate it, and we do not claim they do.

## Advice

**Do not run this on a phone that holds accounts you cannot afford to lose.** A
spare device is the right setup. Beyond that:

- Read what the agent is about to do before approving it, especially shell
  commands and anything that sends data somewhere.
- Be deliberate about pointing the agent at untrusted content.
- Grant accessibility and other broad permissions only while you need them.
- Your API keys are only as protected as the device; treat a lost or rooted
  phone as a key compromise and rotate.

## Scope

We make no security guarantees. The Alpine/proot sandbox provides isolation of
the filesystem the agent works in — it is not a security boundary against a
determined attacker, and it does not contain anything the agent does through the
Android APIs the app itself holds permissions for.
