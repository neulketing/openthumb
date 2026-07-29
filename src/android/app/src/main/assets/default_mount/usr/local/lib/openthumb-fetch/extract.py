"""Pull the readable part out of a page, and throw the other 99% away.

This is the difference between a fetch tool that is usable on a phone and one
that is not. A Threads profile measured in this repo is 867KB of markup
carrying 2KB of words — feed the raw body to an on-device model and one page
consumes the whole context window; truncating instead silently drops whatever
was past the cut, which is usually the article.

Three sources, best one wins:

  json_ld   — schema.org articleBody. Server-rendered sites put the full text
              here even when the visible DOM is a JavaScript shell, so it is
              often the only complete copy on the page.
  main      — the text under <article>/<main>/[role=main], nav and footer
              dropped. What a reader mode picks.
  visible   — everything not inside script/style. The floor; always available.

Standard library only.
"""
from __future__ import annotations

import json
import re
from html.parser import HTMLParser

# Containers whose text is furniture, not content.
_CHROME_TAGS = {"nav", "header", "footer", "aside", "form", "noscript",
                "script", "style", "template", "svg", "button", "select"}
_MAIN_TAGS = {"article", "main"}
# A block must beat the whole-page text by this much before it is trusted as
# "the" content — otherwise a short <article> teaser wins over the real body.
_MAIN_MIN_SHARE = 0.25


class _TextExtractor(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self._skip = 0
        self._depth = 0
        self._main_stack = []          # depths where a main container opened
        self.chunks = []               # (depth_of_open_main_or_None, text)
        self.jsonld = []

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        self._depth += 1
        if tag == "script" and "ld+json" in (a.get("type") or ""):
            self._in_ldjson = True
        if tag in _CHROME_TAGS or (a.get("role") or "") in ("navigation", "banner"):
            self._skip += 1
            return
        if tag in _MAIN_TAGS or (a.get("role") or "") == "main":
            self._main_stack.append(self._depth)

    def handle_endtag(self, tag):
        if tag in _CHROME_TAGS:
            self._skip = max(0, self._skip - 1)
        if self._main_stack and tag in _MAIN_TAGS:
            self._main_stack.pop()
        self._depth = max(0, self._depth - 1)
        self._in_ldjson = False

    def handle_data(self, data):
        if getattr(self, "_in_ldjson", False):
            self.jsonld.append(data)
            return
        if self._skip:
            return
        text = data.strip()
        if text:
            self.chunks.append((bool(self._main_stack), text))


def _walk_json(node):
    """Every dict in a JSON-LD document, however it is nested or graphed."""
    if isinstance(node, dict):
        yield node
        for v in node.values():
            yield from _walk_json(v)
    elif isinstance(node, list):
        for v in node:
            yield from _walk_json(v)


def _from_jsonld(blobs) -> str:
    best = ""
    for blob in blobs:
        try:
            doc = json.loads(blob)
        except Exception:
            continue
        for node in _walk_json(doc):
            for key in ("articleBody", "text", "description"):
                val = node.get(key)
                if isinstance(val, str) and len(val) > len(best):
                    best = val
    return _tidy(best)


def _tidy(text: str) -> str:
    text = re.sub(r"[ \t ]+", " ", text)
    text = re.sub(r"\n\s*\n\s*\n+", "\n\n", text)
    return text.strip()


def extract(html: str) -> dict:
    """Return {"text", "source", "chars"} — the most complete readable copy."""
    p = _TextExtractor()
    try:
        p.feed(html)
    except Exception:
        pass  # a malformed document still yields whatever parsed before the fault

    visible = _tidy(" ".join(t for _, t in p.chunks))
    main = _tidy(" ".join(t for in_main, t in p.chunks if in_main))
    ld = _from_jsonld(p.jsonld)

    best, source = visible, "visible"
    # <article> only wins when it holds a real share of the page — a teaser
    # card is also an <article> and would otherwise replace the whole body.
    if main and len(main) >= max(len(visible) * _MAIN_MIN_SHARE, 200):
        best, source = main, "main"
    # JSON-LD wins outright when it is longer, which is the shell case: the
    # words never reached the DOM but the metadata carries them.
    if len(ld) > len(best):
        best, source = ld, "json_ld"

    return {"text": best, "source": source, "chars": len(best)}
