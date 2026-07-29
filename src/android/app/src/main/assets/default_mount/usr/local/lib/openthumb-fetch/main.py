"""CLI: fetch a URL and print a JSON verdict.

    openthumb-fetch https://example.com
    openthumb-fetch URL --selector "article" --selector "#content"
    openthumb-fetch URL --cookies 'cf_clearance=...; _abck=...' --ua "$(...)"
    openthumb-fetch URL --text          # body only, and exit 1 if it is not the page

Exit codes exist so a shell caller gets the answer without parsing JSON:
0 the page, 1 not the page (read untried_routes), 2 bad usage.
"""
from __future__ import annotations

import argparse
import json
import sys

import extract
import fetcher
import state


def _parse_cookies(raw: str) -> dict:
    out = {}
    for part in (raw or "").split(";"):
        if "=" in part:
            k, _, v = part.partition("=")
            out[k.strip()] = v.strip()
    return out


def main(argv=None) -> int:
    p = argparse.ArgumentParser(prog="openthumb-fetch", description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("url")
    p.add_argument("--selector", action="append", default=[], metavar="CSS",
                   help="proof the page loaded; repeatable. Without one, the "
                        "verdict falls back to heuristics and tops out at weak_ok.")
    p.add_argument("--bad-size", action="append", type=int, default=[], metavar="N",
                   help="byte size known to be a stub for this host; repeatable")
    p.add_argument("--cookies", default="", metavar="STR",
                   help="Cookie header, e.g. from browser_use get_cookies")
    p.add_argument("--ua", default="", metavar="STR",
                   help="User-Agent; pass the device's own so this fetcher and "
                        "the app do not look like two different clients")
    p.add_argument("--referer", default="", metavar="URL")
    p.add_argument("--timeout", type=float, default=fetcher.DEFAULT_TIMEOUT)
    p.add_argument("--first-only", action="store_true",
                   help="try only the URL as given, no variants")
    p.add_argument("--pause", type=float, default=0.0, metavar="SEC",
                   help="wait between variants; use when a host rate-limits")
    p.add_argument("--no-resume", action="store_true",
                   help="do not skip variants a previous run already tried. "
                        "Resuming is on because Android suspends background "
                        "work, and a fetch restarted from scratch every time "
                        "gets killed at the same place forever.")
    p.add_argument("--no-cookie-store", action="store_true",
                   help="do not reuse or keep cookies for this host. Cookies "
                        "are stored per host and never sent to another.")
    p.add_argument("--forget", action="store_true",
                   help="drop this host's stored cookies and resume journal, "
                        "then fetch clean")
    p.add_argument("--text", action="store_true", help="print the body, not JSON")
    p.add_argument("--raw", action="store_true",
                   help="return the HTML instead of the readable text. Costs "
                        "roughly 400x the tokens on a modern page — only use it "
                        "when you need the markup itself.")
    p.add_argument("--max-content", type=int, default=200000, metavar="N",
                   help="truncate the returned body (0 = omit it)")
    args = p.parse_args(argv)

    headers = {}
    if args.ua:
        headers["User-Agent"] = args.ua
    if args.referer:
        headers["Referer"] = args.referer

    if args.forget:
        state.forget_cookies(args.url)

    result = fetcher.fetch(
        args.url,
        success_selectors=args.selector or None,
        known_bad_sizes=args.bad_size or None,
        headers=headers or None,
        cookies=_parse_cookies(args.cookies) or None,
        timeout=args.timeout,
        first_only=args.first_only,
        pause=args.pause,
        resume=not (args.no_resume or args.forget),
        cookie_store=not args.no_cookie_store,
    )

    # Readable text is the default because the raw body is mostly script: a
    # measured Threads profile is 867KB of markup around 2KB of words, and an
    # on-device model cannot afford the difference.
    raw_html = result.pop("content", "")
    if args.raw:
        result["content"], result["extraction"] = raw_html, "raw"
    else:
        got = extract.extract(raw_html)
        result["content"] = got["text"]
        result["extraction"] = got["source"]
        result["html_bytes"] = len(raw_html.encode("utf-8", "ignore"))

    if args.text:
        sys.stdout.write(result["content"])
        if not result["ok"]:
            # The body is printed either way — a challenge page is evidence —
            # but the routes go to stderr so a pipe stays clean.
            sys.stderr.write("\nnot the page: %s\n" % result["verdict"])
            for r in result["untried_routes"]:
                sys.stderr.write("  untried: %s\n" % r)
        return 0 if result["ok"] else 1

    body = result["content"]
    if args.max_content:
        result["content"] = body[: args.max_content]
        result["content_truncated"] = len(body) > args.max_content
    else:
        result.pop("content")
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())
