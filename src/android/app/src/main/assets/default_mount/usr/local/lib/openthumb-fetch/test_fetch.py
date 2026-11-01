#!/usr/bin/env python3
"""Offline checks for the fetch verdict, URL variants and escalation ladder.

No network and no dependencies — run it anywhere, including inside the phone's
Alpine sandbox:

    python3 /usr/local/lib/openthumb-fetch/test_fetch.py

The cases that matter are the ones where naive code gets it wrong: a 200 that
is a challenge, a 403 that carries the challenge in its body, a 429 that must
not be reported as a wall, and a 600-byte page that is genuinely a page.
"""
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import routes  # noqa: E402
import urls  # noqa: E402
from verdict import Response, Verdict, validate, _selector_hits  # noqa: E402

FAILED = []


def ck(name, got, want):
    if got == want:
        print("  ok   %s" % name)
    else:
        print("  FAIL %s: want %r, got %r" % (name, want, got))
        FAILED.append(name)


def html(body, head=""):
    return "<html><head>%s</head><body>%s</body></html>" % (head, body)


def r(status=200, text="", **kw):
    return Response(status=status, text=text, url="https://x.test/", **kw)


# --- the whole reason this exists: 200 is not success ----------------------
ck("200 + Cloudflare interstitial is a challenge",
   validate(r(200, html("<div>Just a moment...</div>" + "x" * 5000))).verdict,
   Verdict.CHALLENGE)
ck("200 + Akamai container is a challenge",
   validate(r(200, html('<div id="sec-if-cpt-container"></div>' + "x" * 5000))).verdict,
   Verdict.CHALLENGE)
ck("403 body is still read, not short-circuited",
   validate(r(403, html("<p>%s</p>" % ("word " * 900)))).verdict,
   Verdict.WEAK_OK)

# --- status semantics ------------------------------------------------------
ck("429 is rate limited", validate(r(429, "slow down")).verdict, Verdict.RATE_LIMITED)
ck("401 is auth required", validate(r(401, "nope")).verdict, Verdict.AUTH_REQUIRED)
ck("404 is not found", validate(r(404, "gone")).verdict, Verdict.NOT_FOUND)
ck("503 is blocked", validate(r(503, "oops")).verdict, Verdict.BLOCKED)
ck("no response at all is unknown", validate(r(0, "")).verdict, Verdict.UNKNOWN)

# --- small bodies ----------------------------------------------------------
ck("a short but complete page is a page",
   validate(r(200, html("<h1>Example Domain</h1><p>" + "meaningful text " * 8 + "</p>"))).verdict,
   Verdict.WEAK_OK)
ck("a script-only stub is a challenge",
   validate(r(200, "<html><body><script>go()</script></body>")).verdict,
   Verdict.CHALLENGE)

# --- JSON ------------------------------------------------------------------
ck("tiny non-empty JSON is an API hit",
   validate(r(200, '{"id":1}', headers={"Content-Type": "application/json"})).verdict,
   Verdict.WEAK_OK)
ck("empty JSON is not trusted",
   validate(r(200, "[]", headers={"Content-Type": "application/json"})).verdict,
   Verdict.SUSPECT_OK)

# --- selectors as positive proof ------------------------------------------
page = html('<article class="post"><p>%s</p></article>' % ("body " * 900))
ck("matched selector is strong proof",
   validate(r(200, page), success_selectors=["article.post"]).verdict, Verdict.STRONG_OK)
ck("requested selector missing is a challenge",
   validate(r(200, page), success_selectors=["#paywalled"]).verdict, Verdict.CHALLENGE)
ck("selector beats a soft marker in a script",
   validate(r(200, html('<article class="post">%s</article>' % ("t " * 900),
                        head="<script>captcha()</script>")),
            success_selectors=["article.post"]).verdict,
   Verdict.STRONG_OK)

# --- the Akamai sensor cookie ---------------------------------------------
ck("unresolved _abck demotes success to non-terminal",
   validate(r(200, page, cookies={"_abck": "AAA~-1~BBB"}),
            success_selectors=["article.post"]).verdict,
   Verdict.SUSPECT_OK)
ck("resolved _abck leaves success alone",
   validate(r(200, page, cookies={"_abck": "AAA~0~BBB"}),
            success_selectors=["article.post"]).verdict,
   Verdict.STRONG_OK)
ck("suspect_ok is not ok()",
   validate(r(200, "[]", headers={"Content-Type": "application/json"})).ok, False)

# --- the JavaScript shell --------------------------------------------------
# Measured on a real Threads profile: 867KB of markup, 62 readable characters.
shell = html('<div id="root"></div>', head="<script>%s</script>" % ("var a=1;" * 4000))
ck("a big body with no words is a shell, not a page",
   validate(r(200, shell)).verdict, Verdict.SUSPECT_OK)
ck("a shell is not a terminal success", validate(r(200, shell)).ok, False)
ck("a long real page is untouched by the density rule",
   validate(r(200, html("<p>%s</p>" % ("readable words here " * 2000)))).verdict,
   Verdict.WEAK_OK)
ck("a selector match still wins over low density",
   validate(r(200, html('<article class="post">%s</article>' % ("t" * 40000))),
            success_selectors=["article.post"]).verdict,
   Verdict.STRONG_OK)

# --- size fingerprint ------------------------------------------------------
stub = html("<p>%s</p>" % ("z" * 9000))
ck("a known stub size is a challenge",
   validate(r(200, stub), known_bad_sizes=[len(stub.encode())]).verdict,
   Verdict.CHALLENGE)

# --- marker matching should not fire on lookalikes -------------------------
ck("'octocaptcha' is not 'captcha'",
   validate(r(200, html("<p>octocaptcha %s</p>" % ("w " * 900)))).verdict,
   Verdict.WEAK_OK)

# --- the stdlib selector matcher ------------------------------------------
doc = '<div id="main"><ul class="list x"><li><a href="#">t</a></li></ul></div>'
ck("id selector", _selector_hits(doc, ["#main"]), ["#main"])
ck("tag.class selector", _selector_hits(doc, ["ul.list"]), ["ul.list"])
ck("multi-class selector", _selector_hits(doc, [".list.x"]), [".list.x"])
ck("descendant chain", _selector_hits(doc, ["#main li a"]), ["#main li a"])
ck("wrong chain does not match", _selector_hits(doc, ["a li"]), [])
ck("void tags do not swallow siblings",
   _selector_hits('<div><br><span class="s">t</span></div>', ["br span"]), [])

# --- URL variants ----------------------------------------------------------
ck("www becomes m", urls.apply_transform("mobile_subdomain", "https://www.a.com/p"),
   "https://m.a.com/p")
ck("apex gains m", urls.apply_transform("am_prefix", "https://a.com/p"), "https://m.a.com/p")
ck("deep subdomain is left alone",
   urls.apply_transform("am_prefix", "https://news.a.co.uk/p"), None)
ck("port survives the rewrite",
   urls.apply_transform("mobile_subdomain", "https://www.a.com:8443/p"),
   "https://m.a.com:8443/p")
ck("variants are deduplicated",
   [u for _, u in urls.iter_transformed("https://a.com/p")],
   ["https://a.com/p", "https://m.a.com/p"])

# --- the escalation ladder -------------------------------------------------
r404, browser404 = routes.untried_routes(Verdict.NOT_FOUND.value, True)
ck("404 leaves nothing to try", r404, [])
ck("404 does not demand a browser", browser404, False)
r401, _ = routes.untried_routes(Verdict.AUTH_REQUIRED.value, True)
ck("401 leaves nothing to try", r401, [])

r429, browser429 = routes.untried_routes(Verdict.RATE_LIMITED.value, True)
ck("429 is never a dead end", len(r429) >= 2, True)
ck("429 still offers the browser", browser429, True)
ck("429 says wait, not give up", "transient" in r429[0], True)

rch, browserch = routes.untried_routes(Verdict.CHALLENGE.value, True)
ck("a challenge escalates to the WebView", browserch, True)
ck("the browser is the first route offered", rch[0].startswith("browser_use"), True)
ck("the cookie bridge is offered too",
   any("cookie bridge" in x for x in rch), True)

rpart, _ = routes.untried_routes(Verdict.CHALLENGE.value, False)
ck("unfinished variants are named before the browser",
   "not exhausted" in rpart[0], True)

# --- was it a defence or a wall? ------------------------------------------
ck("a WAF signal means bot detection",
   routes.classify_block([Verdict.CHALLENGE.value, Verdict.CHALLENGE.value]),
   "bot_detection")
ck("uniform 404 means a real wall",
   routes.classify_block([Verdict.NOT_FOUND.value, Verdict.NOT_FOUND.value]),
   "infra_or_auth")
ck("routes disagreeing means bot detection",
   routes.classify_block([Verdict.NOT_FOUND.value, Verdict.WEAK_OK.value]),
   "bot_detection")
ck("no signal classifies as nothing", routes.classify_block([]), "")

# --- readable-text extraction ---------------------------------------------
import extract  # noqa: E402

page = html(
    '<nav>Home Search Login</nav>'
    '<article><p>The actual article body goes here and is long enough to win.</p></article>'
    '<footer>Copyright someone</footer>',
    head="<script>var junk=1;</script><style>body{}</style>")
got = extract.extract(page)
ck("nav and footer are dropped", "Home Search Login" in got["text"], False)
ck("script bodies are dropped", "var junk" in got["text"], False)
ck("the article survives", "actual article body" in got["text"], True)

# The shell case: the words never reached the DOM, only the metadata has them.
shell_ld = html(
    '<div id="root"></div>',
    head='<script type="application/ld+json">%s</script>' % json.dumps(
        {"@type": "Article", "articleBody": "Recovered from metadata. " * 20}))
got = extract.extract(shell_ld)
ck("json-ld rescues a shell", got["source"], "json_ld")
ck("json-ld text is the article body", got["text"].startswith("Recovered from metadata."), True)

# A teaser card is also an <article>; it must not replace the whole page.
teaser = html('<article><p>Read more</p></article>'
              '<div><p>%s</p></div>' % ("the real page text " * 200))
ck("a teaser article does not hijack the page",
   extract.extract(teaser)["source"], "visible")

ck("nested json-ld graphs are searched",
   extract.extract(html("", head='<script type="application/ld+json">%s</script>'
                        % json.dumps({"@graph": [{"articleBody": "deep " * 60}]})))["source"],
   "json_ld")
ck("an empty document does not crash", extract.extract("")["text"], "")
ck("broken markup still yields text",
   "survives" in extract.extract("<div><p>text survives<div")["text"], True)

# --- cookies stay on their own host, and the journal resumes ---------------
import shutil  # noqa: E402
import tempfile  # noqa: E402

_tmp = tempfile.mkdtemp(prefix="openthumb-fetch-test-")
os.environ["OPENTHUMB_FETCH_STATE"] = _tmp
import state  # noqa: E402
state.STATE_DIR = _tmp

state.save_cookies("https://a.test/page", {"sid": "secret"})
ck("cookies come back for their own host",
   state.load_cookies("https://a.test/other"), {"sid": "secret"})
ck("cookies never cross to another host",
   state.load_cookies("https://b.test/page"), {})
ck("a host that looks similar is still another host",
   state.load_cookies("https://a.test.evil.com/page"), {})
ck("cookies are not world-readable",
   oct(os.stat(os.path.join(_tmp, "cookies",
       state._safe("a.test") + ".json")).st_mode)[-3:], "600")
state.forget_cookies("https://a.test/page")
ck("forget drops them", state.load_cookies("https://a.test/page"), {})

j = state.Journal("https://a.test/p", "fp")
ck("a fresh journal knows nothing", j.seen("https://a.test/p"), None)
j.record({"url": "https://a.test/p", "verdict": "challenge"})
ck("a reopened journal remembers",
   state.Journal("https://a.test/p", "fp").seen("https://a.test/p")["verdict"],
   "challenge")
ck("different rules start a different journal",
   state.Journal("https://a.test/p", "other-fp").seen("https://a.test/p"), None)
with open(j.path, "a", encoding="utf-8") as fh:
    fh.write('{"url": "https://a.test/q", "verdi')  # killed mid-write
ck("a run killed mid-write does not break resume",
   state.Journal("https://a.test/p", "fp").seen("https://a.test/p")["verdict"],
   "challenge")
j.clear()
ck("clearing the journal forgets everything",
   state.Journal("https://a.test/p", "fp").seen("https://a.test/p"), None)

# A stale journal must not make the fetcher skip a route that now works.
j2 = state.Journal("https://a.test/stale", "fp")
j2.record({"url": "https://a.test/stale", "verdict": "challenge"})
os.utime(j2.path, (0, time.time() - state.JOURNAL_TTL - 60))
ck("a journal past its TTL is discarded",
   state.Journal("https://a.test/stale", "fp").seen("https://a.test/stale"), None)

shutil.rmtree(_tmp, ignore_errors=True)

print("all checks passed" if not FAILED else "%d FAILED: %s" % (len(FAILED), FAILED))
sys.exit(1 if FAILED else 0)
