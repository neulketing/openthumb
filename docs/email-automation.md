# Email automation

OpenThumb's project mail runs through one Gmail account via an **app
password** — no server, no third-party service.

## One-time setup (owner)

1. On the Google account, turn on 2-Step Verification, then create an app
   password at <https://myaccount.google.com/apppasswords> (name it e.g.
   "openthumb-ci").
2. Add two repository secrets at
   `Settings → Secrets and variables → Actions`:
   - `GMAIL_USER` — the full Gmail address
   - `GMAIL_APP_PASSWORD` — the 16-letter app password

Nothing runs until both exist; the workflows detect missing secrets and skip
cleanly.

## What runs automatically

| Workflow | Trigger | What it does |
|---|---|---|
| `release-announce.yml` | a GitHub Release is published | emails the release notes to the maintainer inbox |
| `upstream-watch.yml` | daily 00:17 UTC (+ manual) | polls OpenMinis/OpenMinis for a new release tag; on change: opens a merge-review issue, advances `docs/upstream-last-seen.txt`, emails the maintainer inbox |

`upstream-watch` is how the fork tracks upstream without a human refreshing
the page — the issue it opens is the merge work item; closing it after
review keeps the marker honest.

## Ad-hoc: tools/openthumb-mail

```sh
export GMAIL_USER=... GMAIL_APP_PASSWORD=...
tools/openthumb-mail someone@example.com "Fleet job done" <<'EOF'
drain finished: 12 jobs, 0 failures across 3 devices.
EOF
```

Same credentials, plain curl over SMTPS. Compose the body any way you like —
fleet drains, build summaries, cron pings.

## Notes

- Gmail app-password SMTP caps at ~500 recipients/day — plenty for a project
  inbox, not a mailing list. If OpenThumb ever needs a real list, move to a
  transactional provider and change one workflow step.
- The app password grants mail only; revoking it at the same Google page
  kills every automation here at once.
