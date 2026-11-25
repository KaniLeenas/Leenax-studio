"""Exports for notes, question banks and quizzes (E4), and QA reports (E5).

The backend sends the exact stored version as JSON; this builds the file:
  notes      PDF or DOCX: the editor document turned into HTML, then LibreOffice
  questions  PDF or DOCX the same way; CSV (UTF-8 with BOM, so Excel shows Tamil); XLSX (openpyxl)
  qa         PDF or DOCX: a topic's notes, MCQ or combined QA report
  seo        PDF, DOCX or XLSX: a topic's YouTube SEO report (every number shown with its source or "estimated")

Nothing from the request is placed in HTML unescaped. Drawings of equations and diagrams
(SVG made in the browser) are rebuilt from an allowlist of elements and attributes, with
no scripts, no styles that load anything, and no links outside the drawing.
"""
from __future__ import annotations

import base64
import csv
import hashlib
import html
import io
import os
import re
import subprocess
import tempfile
import xml.etree.ElementTree as ET

from fastapi import HTTPException

CONVERT_TIMEOUT = int(os.environ.get("CONVERT_TIMEOUT_SECONDS", "180"))
LETTERS = "ABCD"
STYLE_LABEL = {"BEGINNER": "Beginner", "INTERMEDIATE": "Intermediate", "ADVANCED": "Advanced", "APPLICATION": "Application",
               "SCENARIO": "Scenario", "CODE": "Code", "CALCULATION": "Calculation", "EXAM": "Exam-focused"}
DATA_IMAGE = re.compile(r"^data:image/(png|jpeg|gif|webp);base64,[A-Za-z0-9+/=]+$")


def export(payload: dict) -> tuple[bytes, str]:
    fmt = str(payload.get("format", "")).lower()
    kind = payload.get("kind")
    if kind == "notes" and fmt in ("pdf", "docx"):
        return convert(notes_html(payload), fmt), fmt
    if kind == "questions" and fmt in ("pdf", "docx"):
        return convert(questions_html(payload), fmt), fmt
    if kind == "questions" and fmt == "csv":
        return questions_csv(payload), fmt
    if kind == "questions" and fmt == "xlsx":
        return questions_xlsx(payload), fmt
    if kind == "qa" and fmt in ("pdf", "docx"):
        return convert(qa_html(payload), fmt), fmt
    if kind == "seo" and fmt in ("pdf", "docx"):
        return convert(seo_html(payload), fmt), fmt
    if kind == "seo" and fmt == "xlsx":
        return seo_xlsx(payload), fmt
    raise HTTPException(status_code=400, detail="Unsupported export")


# ---- HTML pages ------------------------------------------------------------------------------------

STYLE = """
body, h1, h2, h3, h4, p, li, td, th { font-family: 'Noto Sans', 'Noto Sans Tamil', 'DejaVu Sans', sans-serif; }
body { font-size: 11pt; line-height: 1.45; }
h1 { font-size: 20pt; margin: 0 0 4pt 0; } h2 { font-size: 15pt; margin-top: 16pt; } h3 { font-size: 12.5pt; } h4 { font-size: 11.5pt; }
pre, code { font-family: 'DejaVu Sans Mono', 'Noto Sans Mono', monospace; font-size: 9.5pt; }
pre { background: #f3f4f6; padding: 6pt; margin-top: 8pt; }
.meta { color: #555555; font-size: 9.5pt; }
.muted { color: #666666; font-size: 9.5pt; }
table.grid { border-collapse: collapse; margin-top: 6pt; }
table.grid td, table.grid th { border: 1px solid #bbbbbb; padding: 3pt 5pt; font-size: 9.5pt; vertical-align: top; }
table.grid th { background: #eeeeee; text-align: left; } table.grid th, table.grid td { text-align: left; }
.headline { font-size: 14pt; font-weight: bold; padding: 6pt 8pt; border: 2px solid #333333; }
"""


def page(title: str, body: str) -> str:
    return (f"<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>{esc(title)}</title>"
            f"<style>{STYLE}</style></head><body>{body}</body></html>")


def header(p: dict) -> str:
    out = [f"<h1>{esc(p.get('title', ''))}</h1>"]
    if p.get("subtitle"):
        out.append(f"<p><b>{esc(p['subtitle'])}</b></p>")
    meta = [m for m in p.get("meta", []) if isinstance(m, list) and len(m) == 2]
    if meta:
        out.append("<p class=\"meta\">" + "<br>".join(f"{esc(k)}: {esc(v)}" for k, v in meta) + "</p>")
    return "".join(out)


def notes_html(p: dict) -> str:
    figures = p.get("figures") or {}
    images = p.get("images") or {}
    body = [header(p), Doc(figures, images).render(p.get("doc") or {})]
    refs = [r for r in p.get("references") or [] if isinstance(r, str) and r.strip()]
    if refs:
        body.append("<h2>Sources used</h2><ul>" + "".join(f"<li>{esc(r)}</li>" for r in refs) + "</ul>")
    return page(p.get("title", "Notes"), "".join(body))


class Doc:
    """The editor's JSON as HTML, one known block at a time; anything unknown is left out."""

    def __init__(self, figures: dict, images: dict):
        self.figures = figures
        self.images = images
        self.missing = 0

    def render(self, node: dict) -> str:
        out = self.node(node)
        if self.missing:
            out += ("<p class=\"muted\"><i>Some equations or diagrams are shown as their source because they haven't been "
                    "drawn yet. Open these notes in Leenax Studio once, then export again.</i></p>")
        return out

    def kids(self, node: dict) -> str:
        return "".join(self.node(k) for k in node.get("content") or [] if isinstance(k, dict))

    def node(self, n: dict) -> str:
        t = n.get("type")
        a = n.get("attrs") or {}
        if t == "doc":
            return self.kids(n)
        if t == "text":
            return self.text(n)
        if t == "paragraph":
            return f"<p>{self.kids(n) or '&nbsp;'}</p>"
        if t == "heading":
            level = min(4, max(1, int(a.get("level", 2) or 2)))
            level = min(4, level + 1) if level == 1 else level  # the export's own title is the only h1
            return f"<h{level}>{self.kids(n)}</h{level}>"
        if t == "bulletList":
            return f"<ul>{self.kids(n)}</ul>"
        if t == "orderedList":
            start = int(a.get("start", 1) or 1)
            return f"<ol start=\"{start}\">{self.kids(n)}</ol>"
        if t == "listItem":
            return f"<li>{self.kids(n)}</li>"
        if t == "blockquote":
            return f"<blockquote>{self.kids(n)}</blockquote>"
        if t == "codeBlock":
            code = "".join(k.get("text", "") for k in n.get("content") or [] if isinstance(k, dict))
            return f"<pre>{esc(code)}</pre>"
        if t == "horizontalRule":
            return "<hr>"
        if t == "hardBreak":
            return "<br>"
        if t == "table":
            return f"<table border=\"1\" cellpadding=\"5\" cellspacing=\"0\" width=\"100%\">{self.kids(n)}</table>"
        if t == "tableRow":
            return f"<tr>{self.kids(n)}</tr>"
        if t in ("tableHeader", "tableCell"):
            tag = "th" if t == "tableHeader" else "td"
            span = "".join(f" {k}=\"{int(a[k])}\"" for k in ("colspan", "rowspan") if isinstance(a.get(k), int) and a[k] > 1)
            inner = self.kids(n).replace("<p>", "").replace("</p>", "<br>").removesuffix("<br>")
            return f"<{tag}{span} valign=\"top\">{inner or '&nbsp;'}</{tag}>"
        if t == "mediaImage":
            src = self.images.get(str(a.get("assetId", "")))
            alt = esc(a.get("alt") or "")
            if isinstance(src, str) and DATA_IMAGE.match(src):
                return f"<p><img src=\"{src}\" alt=\"{alt}\" style=\"max-width:100%\"></p>" + (f"<p class=\"muted\">{alt}</p>" if alt else "")
            return f"<p class=\"muted\">[Image: {alt or 'not available'}]</p>"
        if t in ("mathBlock", "mathInline"):
            latex = str(a.get("latex", ""))
            img = self.figure("MATH", latex)
            if t == "mathBlock":
                return f"<p align=\"center\">{img}</p>" if img else f"<p align=\"center\"><code>{esc(latex)}</code></p>"
            return img or f"<code>{esc(latex)}</code>"
        if t == "mermaid":
            code = str(a.get("code", ""))
            img = self.figure("MERMAID", code)
            return f"<p align=\"center\">{img}</p>" if img else f"<pre>{esc(code)}</pre>"
        return ""

    def text(self, n: dict) -> str:
        s = esc(n.get("text", ""))
        for m in n.get("marks") or []:
            mt = m.get("type") if isinstance(m, dict) else None
            if mt == "bold":
                s = f"<b>{s}</b>"
            elif mt == "italic":
                s = f"<i>{s}</i>"
            elif mt == "underline":
                s = f"<u>{s}</u>"
            elif mt == "strike":
                s = f"<s>{s}</s>"
            elif mt == "code":
                s = f"<code>{s}</code>"
            elif mt == "subscript":
                s = f"<sub>{s}</sub>"
            elif mt == "superscript":
                s = f"<sup>{s}</sup>"
            elif mt == "link":
                href = str((m.get("attrs") or {}).get("href", ""))
                if re.match(r"^(https?://|mailto:)[^\s\"<>]+$", href, re.I):
                    s = f"<a href=\"{esc(href)}\">{s}</a>"
        return s

    def figure(self, kind: str, source: str) -> str | None:
        key = f"{kind}:{hashlib.sha256(source.strip().encode('utf-8')).hexdigest()}"
        svg = self.figures.get(key)
        clean = sanitize_svg(svg) if isinstance(svg, str) else None
        if not clean:
            self.missing += 1
            return None
        width, height = svg_size(clean, max_width=620 if kind == "MERMAID" else 560)
        data = base64.b64encode(clean.encode("utf-8")).decode("ascii")
        size = f" width=\"{width}\" height=\"{height}\"" if width and height else ""
        return f"<img src=\"data:image/svg+xml;base64,{data}\"{size} alt=\"{esc(source[:200])}\">"


def questions_html(p: dict) -> str:
    key = p.get("answerKey", "inline")
    qs = [q for q in p.get("questions") or [] if isinstance(q, dict)]
    out = [header(p)]
    for q in qs:
        out.append(f"<p><b>{int(q.get('number', 0))}.</b> {esc(q.get('stem', ''))}</p>")
        code = q.get("code") or {}
        if isinstance(code, dict) and code.get("text"):
            out.append(f"<pre>{esc(code['text'])}</pre>")
        opts = q.get("options") or []
        out.append("<p>" + "<br>".join(f"&nbsp;&nbsp;{LETTERS[i]}. {esc(o)}" for i, o in enumerate(opts[:4])) + "</p>")
        if key == "inline":
            out.append(answer_block(q))
    if key == "end" and qs:
        out.append("<h2 style=\"page-break-before: always\">Answer key</h2>")
        for q in qs:
            out.append(answer_block(q, with_number=True))
    return page(p.get("title", "Questions"), "".join(out))


def answer_block(q: dict, with_number: bool = False) -> str:
    ans = q.get("answer")
    opts = q.get("options") or []
    letter = LETTERS[ans] if isinstance(ans, int) and 0 <= ans < 4 else "?"
    text = esc(opts[ans]) if isinstance(ans, int) and 0 <= ans < len(opts) else ""
    lead = f"<b>{int(q.get('number', 0))}.</b> " if with_number else ""
    parts = [f"<p>{lead}<b>Answer: {letter}</b> {text}</p>"]
    if q.get("explanation"):
        parts.append(f"<p>{esc(q['explanation'])}</p>")
    why = q.get("optionExplanations") or []
    lines = [f"{LETTERS[i]}: {esc(w)}" for i, w in enumerate(why[:4]) if w and i != ans]
    if lines:
        parts.append("<p class=\"muted\">" + "<br>".join(lines) + "</p>")
    info = [STYLE_LABEL.get(q.get("style", ""), q.get("style", "")), (q.get("difficulty") or "").capitalize(),
            q.get("subtopic"), q.get("source")]
    parts.append("<p class=\"meta\">" + esc(", ".join(i for i in info if i)) + "</p>")
    return "".join(parts)


# ---- QA reports (E5) -------------------------------------------------------------------------------

HEADLINE = {"BLOCKED": "Blocked: critical issues are open", "NOT_CHECKED": "Not checked: something here has no QA check of its current version",
            "NEEDS_ATTENTION": "Needs attention: major issues are open", "PASSED": "Passed: no critical or major issues open"}
CATEGORY = {"ACCURACY": "Accuracy", "COVERAGE": "Syllabus coverage", "EXPLANATIONS": "Explanations", "REFERENCES": "References",
            "MCQ_KEYS": "MCQ keys", "DIFFICULTY": "Difficulty", "DUPLICATES": "Duplicates"}
BASIS = {"UPLOADED": "Course document page", "EXTERNAL": "Outside link given by the AI (not opened)", "AI": "The checker's own explanation",
         "ESTIMATE": "Estimate", "UNVERIFIED": "Unverified"}
STATUS = {"OPEN": "Open", "CORRECTED": "Corrected, awaiting recheck", "RESOLVED": "Resolved", "DISMISSED": "Dismissed"}
PART = {"NOTES": "NOTES", "MCQ": "MCQ", "COMBINED": None}


def words(s) -> str:
    return esc(str(s or "").replace("_", " ").lower().capitalize())


def qa_html(p: dict) -> str:
    r = p.get("report") or {}
    part = str(p.get("part") or "COMBINED").upper()
    kind = PART.get(part)
    items = [i for i in r.get("items") or [] if isinstance(i, dict) and (kind is None or i.get("kind") == kind)]
    ids = {i.get("resourceId") for i in items}
    parts = r.get("parts") or {}
    summary = parts.get(part if part in parts else "COMBINED") or {}
    out = [header(p)]
    out.append(f"<p class=\"headline\">{esc(HEADLINE.get(summary.get('headline'), summary.get('headline') or ''))}</p>")
    score = summary.get("score")
    out.append("<table class=\"grid\" border=\"1\" cellspacing=\"0\" cellpadding=\"4\" width=\"100%\"><tr><th>Checked</th><th>Passed</th><th>Failed</th><th>Corrected</th><th>Still open</th>"
               "<th>Critical open</th><th>Score</th></tr><tr>" + "".join(f"<td>{esc(summary.get(k, 0))}</td>" for k in
               ("checked", "passed", "failed", "corrected", "open", "openCritical")) + f"<td>{esc(score) + '%' if score is not None else 'n/a'}</td></tr></table>")
    cats = summary.get("categories") or {}
    if cats:
        out.append("<h2>By category</h2><table class=\"grid\" border=\"1\" cellspacing=\"0\" cellpadding=\"4\" width=\"100%\"><tr><th>Category</th><th>Checked</th><th>Passed</th><th>Failed (still open)</th></tr>")
        for c, t in cats.items():
            t = t or {}
            out.append(f"<tr><td>{esc(CATEGORY.get(c, c))}</td><td>{esc(t.get('checked', 0))}</td><td>{esc(t.get('passed', 0))}</td>"
                       f"<td>{esc(t.get('failed', 0))}</td></tr>")
        out.append("</table>")

    out.append("<h2>What was checked</h2><table class=\"grid\" border=\"1\" cellspacing=\"0\" cellpadding=\"4\" width=\"100%\"><tr><th>Item</th><th>Version</th><th>QA check</th><th>Claims by basis</th></tr>")
    for i in items:
        run = i.get("run") or None
        if run:
            who = f"{esc(run.get('model'))}" + (f" ({esc(run.get('provider'))})" if run.get("provider") else "")
            who += f", confirmed by {esc(run.get('secondModel'))}" if run.get("secondModel") else ", no second model"
            who += "" if run.get("independent") else ". Not independent: the same model wrote it"
            who += f". Checked version {esc(run.get('versionNo'))}" + ("" if run.get("current") else ", not the current one")
        else:
            who = "Not checked"
        bases = ", ".join(f"{esc(BASIS.get(k, k))}: {esc(v)}" for k, v in (i.get("claimsByBasis") or {}).items()) or "n/a"
        extra = ""
        if i.get("kind") == "MCQ":
            diff = ", ".join(f"{words(k)}: {esc(v)}" for k, v in (i.get("difficulty") or {}).items())
            extra = f"<br>{esc(i.get('questions') or 0)} questions" + (f" ({diff})" if diff else "")
        out.append(f"<tr><td>{esc(i.get('label'))}{extra}</td><td>{esc(i.get('versionNo'))}<br>{esc(str(i.get('versionStatus') or '').replace('_', ' ').lower())}</td>"
                   f"<td>{who}</td><td>{bases}</td></tr>")
    out.append("</table>")

    if kind != "MCQ" and r.get("coverage"):
        out.append("<h2>Syllabus coverage</h2><table class=\"grid\" border=\"1\" cellspacing=\"0\" cellpadding=\"4\" width=\"100%\"><tr><th>Subtopic</th><th>Covered in</th></tr>")
        for c in r.get("coverage") or []:
            where = ", ".join(esc(x) for x in c.get("levels") or []) or "<b>Not found in any level</b>"
            out.append(f"<tr><td>{esc(c.get('subtopic'))}</td><td>{where}</td></tr>")
        out.append("</table>")

    issues = [x for x in r.get("issues") or [] if isinstance(x, dict) and x.get("resourceId") in ids]
    out.append(f"<h2>Issues ({len(issues)})</h2>")
    if issues:
        out.append("<table class=\"grid\" border=\"1\" cellspacing=\"0\" cellpadding=\"4\" width=\"100%\"><tr><th>#</th><th>Severity</th><th>Where</th><th>Issue</th><th>Basis</th><th>Status</th></tr>")
        for x in issues:
            status = STATUS.get(x.get("status"), x.get("status"))
            if x.get("statusNote"):
                status += f": {x.get('statusNote')}"
            out.append(f"<tr><td>{esc(x.get('number'))}</td><td>{words(x.get('severity'))}</td>"
                       f"<td>{esc(x.get('resource'))}<br>{esc(x.get('where') or '')}</td>"
                       f"<td><b>{words(x.get('type'))}</b>: {esc(x.get('explanation'))}</td>"
                       f"<td>{esc(BASIS.get(x.get('basis'), x.get('basis')))}</td><td>{esc(status)}</td></tr>")
        out.append("</table>")
    else:
        out.append("<p>None recorded.</p>")

    corr = [c for c in r.get("corrections") or [] if isinstance(c, dict) and (kind is None or any(
        i.get("label") == c.get("resource") for i in items))]
    out.append("<h2>Correction history</h2>")
    if corr:
        out.append("<table class=\"grid\" border=\"1\" cellspacing=\"0\" cellpadding=\"4\" width=\"100%\"><tr><th>Issue</th><th>Item</th><th>Decision</th><th>By</th><th>When</th></tr>")
        for c in corr:
            out.append(f"<tr><td>{esc(c.get('issue'))}</td><td>{esc(c.get('resource'))}</td><td>{words(c.get('status'))}"
                       f"{(': ' + esc(c.get('note'))) if c.get('note') else ''}</td><td>{esc(c.get('decidedBy') or '')}</td>"
                       f"<td>{esc((c.get('at') or '')[:10])}</td></tr>")
        out.append("</table>")
    else:
        out.append("<p>No corrections proposed.</p>")

    out.append("<h2>Recommendations</h2><ul>" + "".join(f"<li>{esc(x)}</li>" for x in r.get("recommendations") or []) + "</ul>")
    out.append(f"<h2>How it's scored</h2><p>{esc(r.get('method'))}</p>")
    out.append("<h2>Limits of this check</h2><ul>" + "".join(f"<li>{esc(x)}</li>" for x in r.get("limitations") or []) + "</ul>")
    return page(p.get("title", "QA report"), "".join(out))


# ---- YouTube SEO reports (E6) ----------------------------------------------------------------------

GRID = '<table class="grid" border="1" cellspacing="0" cellpadding="4" width="100%">'
STRATEGY = {"LESSON": "Lesson video", "MCQ": "MCQ video", "EXAM": "Exam preparation video", "REVISION": "Revision video", "SHORTS": "Shorts"}
CRITERIA = ["keywordRelevance", "searchIntent", "clarity", "accuracy", "readability", "audienceRelevance", "differentiation", "clickAppeal", "length"]


def number(v) -> str:
    if isinstance(v, bool) or not isinstance(v, (int, float)):
        return ""
    if float(v).is_integer():
        return f"{int(v):,}"
    return f"{v:,.2f}".rstrip("0").rstrip(".")


def metric(m, with_label=True) -> str:
    """A number with where it came from, or why it's missing. Never a bare number."""
    if not isinstance(m, dict):
        return "n/a"
    v = m.get("value")
    if v is None:
        return esc(m.get("note") or "not available")
    unit = m.get("unit") or ""
    text = number(v) + ("" if unit in ("0-1", "1-5", "weight", "") else " " + unit)
    if unit == "1-5":
        text += " of 5"
    if not with_label:
        return esc(text)
    if m.get("estimated"):
        label = "estimated" + (f": {m.get('method')}" if m.get("method") and len(m.get("method")) < 120 else "")
    else:
        label = m.get("source") or "source missing"
        if m.get("retrievedAt"):
            label += f", {str(m.get('retrievedAt'))[:10]}"
    return f"{esc(text)} <span class=\"muted\">({esc(label)})</span>"


def metric_plain(m) -> str:
    """The same for a spreadsheet cell."""
    if not isinstance(m, dict):
        return ""
    if m.get("value") is None:
        return m.get("note") or "not available"
    lab = "estimated" if m.get("estimated") else (m.get("source") or "") + (f", {str(m.get('retrievedAt'))[:10]}" if m.get("retrievedAt") else "")
    return f"{number(m.get('value'))} ({lab})"


def ul(items) -> str:
    items = [x for x in items or [] if x]
    return "<ul>" + "".join(f"<li>{esc(x)}</li>" for x in items) + "</ul>" if items else "<p class=\"muted\">None.</p>"


def seo_html(p: dict) -> str:
    rep = p.get("report") or {}
    res = p.get("research") or {}
    kws = [k for k in res.get("keywords") or [] if isinstance(k, dict)]
    titles = [t for t in rep.get("titles") or [] if isinstance(t, dict)]
    chosen = next((t for t in titles if t.get("id") == rep.get("chosenTitleId")), None)
    recommended = next((t for t in titles if t.get("id") == rep.get("recommendedTitleId")), None)
    comps = [c for c in res.get("competitors") or [] if isinstance(c, dict)]
    out = [header(p)]
    n = [0]

    def h(title):
        n[0] += 1
        out.append(f"<h2>{n[0]}. {esc(title)}</h2>")

    h("Topic")
    out.append(f"<p><b>{esc(rep.get('topicTitle'))}</b></p>")
    if res.get("subtopics"):
        out.append("<p>Subtopics: " + esc("; ".join(res.get("subtopics"))) + "</p>")
    out.append(f"<p>Languages: {esc(', '.join({'en': 'English', 'ta': 'Tamil'}.get(l, l) for l in res.get('languages') or []))}. "
               f"Region: {esc(res.get('region') or '')}.</p>")

    h("Keyword research")
    out.append("<p>Keywords are suggestions by an AI model; YouTube's API was then searched for the top ones. Nothing here is a search volume.</p>")
    out.append(GRID + "<tr><th>Keyword</th><th>Language</th><th>Kind</th><th>Intent</th><th>Opportunity</th></tr>")
    for k in kws[:40]:
        out.append(f"<tr><td>{esc(k.get('text'))}</td><td>{esc(k.get('language'))}</td><td>{words(k.get('kind'))}</td><td>{words(k.get('intent'))}</td>"
                   f"<td>{metric(k.get('score'), False) + ' (estimated' + (', partial' if k.get('partial') else '') + ')' if k.get('score') else '<span class=muted>not searched</span>'}</td></tr>")
    out.append("</table>")

    h("Search demand estimates")
    demand = [k for k in kws if isinstance(k.get("demand"), dict)]
    if demand:
        out.append(GRID + "<tr><th>Keyword</th><th>Relative demand</th><th>Trend</th></tr>")
        for k in demand:
            out.append(f"<tr><td>{esc(k.get('text'))}</td><td>{metric(k.get('demand'))}</td><td>{esc(k.get('trend') or '')}</td></tr>")
        out.append("</table>")
    else:
        out.append("<p>No demand signal: no Google Trends file covered these keywords. YouTube's API gives no search volumes, so none are estimated here.</p>")

    h("Competition analysis")
    searched = [k for k in kws if k.get("competition")]
    if searched:
        out.append(GRID + "<tr><th>Keyword</th><th>Relevance (R)</th><th>Competition (C)</th><th>Content gap (G)</th></tr>")
        for k in searched:
            out.append(f"<tr><td>{esc(k.get('text'))}</td><td>{metric(k.get('relevance'), False)}</td><td>{metric(k.get('competition'), False)}"
                       f"</td><td>{metric(k.get('gap'), False)}</td></tr>")
        out.append("</table><p class=\"muted\">All three are estimates, 0 to 1. " + esc(SEO_METHOD) + "</p>")
    else:
        out.append("<p>No keywords were searched on YouTube in this research.</p>")

    h("Trend analysis")
    trends = [t for t in res.get("trends") or [] if isinstance(t, dict)]
    out.append(ul([f"{t.get('term')}: {t.get('direction') or 'too short to tell'} (Google Trends CSV “{t.get('filename')}”, "
                   f"{t.get('geo') or 'worldwide'}, to {t.get('lastDate')})" for t in trends]) if trends else
               "<p>No Google Trends file was imported for these keywords.</p>")

    h("Competitor comparison")
    if comps:
        out.append(GRID + "<tr><th>#</th><th>Video</th><th>Published</th><th>Length</th><th>Views</th><th>Likes</th><th>Comments</th></tr>")
        for c in comps[:20]:
            out.append(f"<tr><td>{esc(c.get('rank') or '')}</td><td>{esc(c.get('title'))}<br><span class=\"muted\">{esc(c.get('channel'))}, "
                       f"{esc(c.get('url'))}</span></td><td>{esc(str(c.get('publishedAt') or '')[:10])}</td><td>{metric(c.get('duration'), False)}</td>"
                       f"<td>{metric(c.get('views'), False)}</td><td>{metric(c.get('likes'), False)}</td><td>{metric(c.get('comments'), False)}</td></tr>")
        out.append("</table><p class=\"muted\">Public numbers from the YouTube Data API (videos.list), retrieved on the dates in the sources. "
                   "Competitors' impressions, click-through and retention aren't public and aren't shown.</p>")
    else:
        out.append("<p>No competitor videos were looked up.</p>")
    an = res.get("analysis") or {}
    if an.get("titleStrategies"):
        out.append(f"<p><b>Title strategies:</b> {esc(an.get('titleStrategies'))} <span class=\"muted\">({esc(an.get('source') or 'AI analysis')})</span></p>")

    h("Content gaps")
    cov = [c for c in res.get("coverage") or [] if isinstance(c, dict)]
    if cov:
        out.append(GRID + "<tr><th>Subtopic</th><th>Competitor videos covering it</th></tr>" + "".join(
            f"<tr><td>{esc(c.get('subtopic'))}</td><td>{metric(c.get('competitorsCovering'))}</td></tr>" for c in cov) + "</table>")
    out.append(ul(an.get("contentGaps")))
    for label, key in (("Repeated questions", "repeatedQuestions"), ("Missing explanations", "missingExplanations"),
                       ("Language opportunities", "languageOpportunities"), ("Differentiation", "differentiation")):
        if an.get(key):
            out.append(f"<p><b>{label}</b></p>" + ul(an.get(key)))

    h("Title recommendations")
    out.append(GRID + "<tr><th>Title</th><th>Style</th><th>Length</th><th>AI score</th><th>Notes</th></tr>")
    for t in titles:
        c = t.get("check") or {}
        mark = " (chosen)" if chosen is t else (" (recommended)" if recommended is t else "")
        out.append(f"<tr><td>{esc(t.get('text'))}<b>{mark}</b></td><td>{words(t.get('style'))}</td><td>{metric(c.get('length'), False)}</td>"
                   f"<td>{metric(c.get('overall'), False) + ' (estimated)' if c.get('overall') else 'not scored'}</td><td>{esc('; '.join(c.get('warnings') or []))}</td></tr>")
    out.append("</table>")

    h("Recommended title")
    out.append(f"<p><b>{esc((recommended or {}).get('text') or 'None')}</b></p><p>{esc(rep.get('recommendationReason') or '')}</p>")
    if chosen and chosen is not recommended:
        out.append(f"<p>Chosen instead: <b>{esc(chosen.get('text'))}</b></p>")

    h("Title evaluation")
    pick = chosen or recommended
    if pick and (pick.get("check") or {}).get("ai"):
        ai = pick["check"]["ai"]
        out.append(GRID + "<tr><th>Criterion</th><th>Score</th></tr>" + "".join(
            f"<tr><td>{words(k)}</td><td>{metric(ai.get(k))}</td></tr>" for k in CRITERIA if k in ai) + "</table>")
        if pick["check"].get("reasoning"):
            out.append(f"<p>{esc(pick['check']['reasoning'])}</p>")
    else:
        out.append("<p>The chosen title wasn't scored by the AI (edited by hand, or written by hand).</p>")

    h("SEO description")
    out.append("".join(f"<p>{esc(line)}</p>" for line in (rep.get("description") or "").split("\n") if line.strip()) or "<p>Not written.</p>")
    h("Primary keywords")
    out.append(ul(rep.get("primaryKeywords")))
    h("Secondary keywords")
    out.append(ul(rep.get("secondaryKeywords")))
    h("Video tags")
    out.append(f"<p>{esc(', '.join(rep.get('tags') or [])) or 'None.'}</p>")
    h("Hashtags")
    out.append(f"<p>{esc(' '.join(rep.get('hashtags') or [])) or 'None.'}</p>")
    h("Thumbnail text")
    out.append(f"<p>{esc(rep.get('thumbnailText') or 'None.')}</p>")
    h("Thumbnail design prompt")
    out.append(f"<p>{esc(rep.get('thumbnailPrompt') or 'None.')}</p>")
    h("Suggested video chapters")
    ch = [c for c in rep.get("chapters") or [] if isinstance(c, dict)]
    if ch:
        def ts(m):
            v = int((m or {}).get("value") or 0)
            return f"{v // 60}:{v % 60:02d}"
        out.append("<ul>" + "".join(f"<li>{ts(c.get('start'))} {esc(c.get('title'))}</li>" for c in ch) + "</ul>"
                   "<p class=\"muted\">Times are estimated plans; set the real times after recording.</p>")
    else:
        out.append("<p>None.</p>")
    h("Playlist recommendations")
    out.append(ul([f"{s.get('title')}: {s.get('why')}" if s.get("why") else s.get("title") for s in rep.get("playlists") or []]))
    h("Suggested next videos")
    out.append(ul([f"{s.get('title')}: {s.get('why')}" if s.get("why") else s.get("title") for s in rep.get("nextVideos") or []]))
    strategies = rep.get("strategies") or {}
    if strategies:
        out.append("<h3>Plans by video type</h3>")
        for k, label in STRATEGY.items():
            s = strategies.get(k)
            if not isinstance(s, dict):
                continue
            parts = [f"<b>{label}</b>"]
            for f, name in (("titleIdea", "Title idea"), ("angle", "Angle"), ("format", "Format"), ("notes", "Notes")):
                if s.get(f):
                    parts.append(f"{name}: {esc(s.get(f))}")
            if s.get("keywords"):
                parts.append("Keywords: " + esc(", ".join(s.get("keywords"))))
            out.append("<p>" + "<br>".join(parts) + "</p>")
    h("Research sources")
    out.append(ul([f"{s.get('name')}: {s.get('detail') or ''}" + (f" ({str(s.get('retrievedAt'))[:10]})" if s.get("retrievedAt") else "")
                   for s in res.get("sources") or [] if isinstance(s, dict)]))
    h("Research date")
    out.append(f"<p>{esc(str(res.get('startedAt') or '')[:16].replace('T', ' '))} UTC</p>")
    h("Data limitations")
    out.append(ul(res.get("limitations")))
    return page(p.get("title", "SEO report"), "".join(out))


SEO_METHOD = ("Opportunity = R × (wD·D + wC·(1 − C) + wG·G); without a Trends value D is dropped and wC = wG = 0.5 (partial). "
              "It's an estimate from relative signals, not a search volume.")


def seo_xlsx(p: dict) -> bytes:
    from openpyxl import Workbook
    from openpyxl.styles import Alignment, Font

    rep = p.get("report") or {}
    res = p.get("research") or {}
    wb = Workbook()

    def sheet(ws, head, rows, widths):
        ws.append(head)
        for c in ws[1]:
            c.font = Font(bold=True)
        for r in rows:
            ws.append([safe_cell(v) for v in r])
        for i, w in enumerate(widths):
            ws.column_dimensions[chr(65 + i)].width = w
        for row in ws.iter_rows(min_row=2):
            for c in row:
                c.alignment = Alignment(wrap_text=True, vertical="top")
        ws.freeze_panes = "A2"

    ws = wb.active
    ws.title = "Keywords"
    sheet(ws, ["Keyword", "Language", "Kind", "Intent", "Searched", "Relevance R", "Demand D", "Competition C", "Content gap G", "Opportunity", "Source"],
          [[k.get("text"), k.get("language"), k.get("kind"), k.get("intent"), "yes" if k.get("searched") else "no", metric_plain(k.get("relevance")),
            metric_plain(k.get("demand")) or "no Trends value", metric_plain(k.get("competition")), metric_plain(k.get("gap")),
            (metric_plain(k.get("score")) + (" partial" if k.get("partial") else "")) if k.get("score") else "not searched", k.get("source")]
           for k in res.get("keywords") or [] if isinstance(k, dict)],
          [34, 9, 12, 14, 9, 24, 30, 24, 24, 24, 26])
    sheet(wb.create_sheet("Competitors"), ["Rank", "Title", "Channel", "URL", "Published", "Length (s)", "Views", "Likes", "Comments", "Covers", "Found by"],
          [[c.get("rank"), c.get("title"), c.get("channel"), c.get("url"), str(c.get("publishedAt") or "")[:10], metric_plain(c.get("duration")),
            metric_plain(c.get("views")), metric_plain(c.get("likes")), metric_plain(c.get("comments")), "; ".join(c.get("covers") or []),
            "; ".join(c.get("foundBy") or [])] for c in res.get("competitors") or [] if isinstance(c, dict)],
          [6, 40, 20, 34, 11, 26, 30, 30, 30, 30, 30])
    trows = []
    for t in rep.get("titles") or []:
        if not isinstance(t, dict):
            continue
        c = t.get("check") or {}
        ai = c.get("ai") or {}
        trows.append([t.get("text"), t.get("style"), t.get("language"), metric_plain(c.get("length")), metric_plain(c.get("overall")) or "not scored"]
                     + [metric_plain(ai.get(k)) for k in CRITERIA] + ["; ".join(c.get("warnings") or []),
                     "chosen" if t.get("id") == rep.get("chosenTitleId") else ("recommended" if t.get("id") == rep.get("recommendedTitleId") else "")])
    sheet(wb.create_sheet("Titles"), ["Title", "Style", "Language", "Length", "AI mean"] + [c[0].upper() + c[1:] for c in CRITERIA] + ["Warnings", "Pick"],
          trows, [46, 12, 9, 26, 22] + [14] * len(CRITERIA) + [30, 12])
    meta = wb.create_sheet("Sources")
    sheet(meta, ["Source", "Detail", "Retrieved"], [[s.get("name"), s.get("detail"), str(s.get("retrievedAt") or "")[:10]]
                                                    for s in res.get("sources") or [] if isinstance(s, dict)] +
          [["Limitation", x, ""] for x in res.get("limitations") or []] + [["Scoring", SEO_METHOD, ""]], [30, 90, 12])
    out = io.BytesIO()
    wb.save(out)
    return out.getvalue()


# ---- spreadsheets ----------------------------------------------------------------------------------

COLUMNS = ["Number", "Topic", "Subtopic", "Style", "Difficulty", "Question", "Code", "Option A", "Option B", "Option C", "Option D",
           "Answer", "Explanation", "Why A", "Why B", "Why C", "Why D", "Source"]


def rows(p: dict):
    for q in p.get("questions") or []:
        if not isinstance(q, dict):
            continue
        opts = (list(q.get("options") or []) + ["", "", "", ""])[:4]
        why = (list(q.get("optionExplanations") or []) + ["", "", "", ""])[:4]
        ans = q.get("answer")
        code = q.get("code") or {}
        yield [q.get("number", ""), q.get("topic") or q.get("bankTitle") or "", q.get("subtopic") or "",
               STYLE_LABEL.get(q.get("style", ""), q.get("style") or ""), (q.get("difficulty") or "").capitalize(), q.get("stem") or "",
               code.get("text", "") if isinstance(code, dict) else "", *opts,
               LETTERS[ans] if isinstance(ans, int) and 0 <= ans < 4 else "", q.get("explanation") or "", *[w or "" for w in why],
               q.get("source") or ""]


def safe_cell(v):
    """Spreadsheets run cells that start with = + - @ as formulas; such text is kept as text."""
    if isinstance(v, str) and v[:1] in ("=", "+", "-", "@", "\t", "\r"):
        return "'" + v
    return v


def questions_csv(p: dict) -> bytes:
    buf = io.StringIO()
    w = csv.writer(buf)
    w.writerow(COLUMNS)
    for r in rows(p):
        w.writerow([safe_cell(v) for v in r])
    return ("﻿" + buf.getvalue()).encode("utf-8")


def questions_xlsx(p: dict) -> bytes:
    from openpyxl import Workbook
    from openpyxl.styles import Alignment, Font

    wb = Workbook()
    ws = wb.active
    ws.title = "Questions"
    ws.append(COLUMNS)
    for c in ws[1]:
        c.font = Font(bold=True)
    for r in rows(p):
        ws.append([safe_cell(v) for v in r])
    widths = {"A": 8, "B": 22, "C": 22, "D": 13, "E": 13, "F": 60, "G": 30, "H": 28, "I": 28, "J": 28, "K": 28, "L": 8, "M": 50}
    for col, w in widths.items():
        ws.column_dimensions[col].width = w
    for row in ws.iter_rows(min_row=2):
        for c in row:
            c.alignment = Alignment(wrap_text=True, vertical="top")
    ws.freeze_panes = "A2"
    meta = wb.create_sheet("About")
    meta.append(["Title", p.get("title", "")])
    for m in p.get("meta", []):
        if isinstance(m, list) and len(m) == 2:
            meta.append([safe_cell(str(m[0])), safe_cell(str(m[1]))])
    out = io.BytesIO()
    wb.save(out)
    return out.getvalue()


# ---- LibreOffice -----------------------------------------------------------------------------------

# Latin text uses Noto Sans (first in STYLE); Tamil text must then come from Noto Sans Tamil, not
# whatever other font has Tamil glyphs (some shape Tamil badly). LibreOffice picks fallback fonts
# through fontconfig, so this tells fontconfig, for the conversion only.
FONTCONFIG = """<?xml version="1.0"?>
<!DOCTYPE fontconfig SYSTEM "fonts.dtd">
<fontconfig>
  <include ignore_missing="yes">/etc/fonts/fonts.conf</include>
  <alias binding="same">
    <family>Noto Sans</family>
    <accept><family>Noto Sans Tamil</family></accept>
  </alias>
</fontconfig>
"""


def convert(page_html: str, fmt: str) -> bytes:
    with tempfile.TemporaryDirectory(prefix="lx-export-") as work:
        fonts_conf = os.path.join(work, "fonts.conf")
        with open(fonts_conf, "w", encoding="utf-8") as fh:
            fh.write(FONTCONFIG)
        src = os.path.join(work, "export.html")
        with open(src, "w", encoding="utf-8") as fh:
            fh.write(page_html)
        filt = "pdf:writer_web_pdf_Export" if fmt == "pdf" else "docx:MS Word 2007 XML"
        cmd = ["soffice", f"-env:UserInstallation=file://{work}/profile", "--headless", "--norestore", "--nolockcheck",
               "--convert-to", filt, "--outdir", work, src]
        try:
            subprocess.run(cmd, check=True, timeout=CONVERT_TIMEOUT, capture_output=True,
                           env={**os.environ, "HOME": work, "FONTCONFIG_FILE": fonts_conf})
        except (subprocess.TimeoutExpired, subprocess.CalledProcessError, FileNotFoundError):
            raise HTTPException(status_code=503, detail="The export couldn't be built")
        out = os.path.join(work, f"export.{fmt}")
        if not os.path.exists(out):
            raise HTTPException(status_code=503, detail="The export couldn't be built")
        with open(out, "rb") as fh:
            return fh.read()


# ---- SVG -------------------------------------------------------------------------------------------

SVG_NS = "http://www.w3.org/2000/svg"
XLINK_NS = "http://www.w3.org/1999/xlink"
ELEMENTS = {"svg", "g", "path", "rect", "circle", "ellipse", "line", "polyline", "polygon", "text", "tspan", "defs", "marker",
            "use", "title", "desc", "style", "clipPath", "linearGradient", "radialGradient", "stop", "symbol", "switch"}
ATTRIBUTES = {"id", "class", "d", "x", "y", "x1", "y1", "x2", "y2", "cx", "cy", "r", "rx", "ry", "width", "height", "points",
              "viewBox", "transform", "fill", "fill-opacity", "fill-rule", "stroke", "stroke-width", "stroke-opacity", "stroke-dasharray",
              "stroke-linecap", "stroke-linejoin", "stroke-miterlimit", "opacity", "font-family", "font-size", "font-weight",
              "font-style", "text-anchor", "dominant-baseline", "alignment-baseline", "dx", "dy", "style", "markerWidth",
              "markerHeight", "refX", "refY", "orient", "markerUnits", "preserveAspectRatio", "clip-path", "offset", "stop-color",
              "stop-opacity", "gradientUnits", "gradientTransform", "x-height", "role", "focusable", "aria-hidden",
              "data-c", "data-mml-node", "letter-spacing", "xml:space", "marker-end", "marker-start", "vector-effect", "version"}
LINK_ATTRS = {"href", f"{{{XLINK_NS}}}href"}
BAD_CSS = re.compile(r"@import|url\s*\(\s*['\"]?(?!#)|expression\s*\(|javascript:|behavior\s*:", re.I)


def clean_css(css: str) -> str:
    """Drops @import rules and any url() that points outside the drawing."""
    css = re.sub(r"@import[^;]*;?", "", css, flags=re.I)
    css = re.sub(r"url\(\s*(['\"]?)(?!#)[^)]*\)", "none", css, flags=re.I)
    return re.sub(r"expression\s*\(|javascript:|behavior\s*:", "", css, flags=re.I)


MAX_SVG = 600_000


def sanitize_svg(svg: str) -> str | None:
    """A clean copy of a browser-made drawing, or None if it can't be trusted or read."""
    if not svg or len(svg) > MAX_SVG or re.search(r"<!DOCTYPE|<!ENTITY", svg, re.I):
        return None
    try:
        root = ET.fromstring(svg)
    except ET.ParseError:
        return None
    if local(root.tag) != "svg":
        return None
    clean = copy_clean(root)
    if clean is None:
        return None
    ET.register_namespace("", SVG_NS)
    ET.register_namespace("xlink", XLINK_NS)
    return ET.tostring(clean, encoding="unicode")


def copy_clean(el):
    name = local(el.tag)
    if name not in ELEMENTS:
        return None
    out = ET.Element(f"{{{SVG_NS}}}{name}")
    for k, v in el.attrib.items():
        key = local(k)
        if key.lower().startswith("on"):
            continue
        if k in LINK_ATTRS or key == "href":
            if isinstance(v, str) and v.startswith("#"):
                out.set(f"{{{XLINK_NS}}}href" if k != "href" else "href", v)
            continue
        if key not in ATTRIBUTES and k != "{http://www.w3.org/XML/1998/namespace}space":
            continue
        if key == "style":
            v = clean_css(v or "")
        elif isinstance(v, str) and BAD_CSS.search(v) and key not in ("d", "points"):
            continue
        out.set(key if not k.startswith("{http://www.w3.org/XML/1998") else k, v)
    if name == "style":
        out.text = clean_css(el.text or "")
        return out
    out.text = el.text
    for child in el:
        c = copy_clean(child)
        if c is not None:
            c.tail = child.tail
            out.append(c)
    return out


def local(tag: str) -> str:
    return tag.rsplit("}", 1)[-1] if isinstance(tag, str) else ""


UNIT = {"": 1.0, "px": 1.0, "pt": 4 / 3, "em": 16.0, "ex": 8.0, "in": 96.0, "cm": 37.8, "mm": 3.78}


def svg_size(svg: str, max_width: int) -> tuple[int | None, int | None]:
    """Pixel size for the <img>: from width/height (MathJax uses ex), else the viewBox; scaled to fit the page."""
    try:
        root = ET.fromstring(svg)
    except ET.ParseError:
        return None, None
    w, h = length(root.get("width")), length(root.get("height"))
    if not (w and h):
        vb = (root.get("viewBox") or "").replace(",", " ").split()
        if len(vb) == 4:
            try:
                w, h = float(vb[2]), float(vb[3])
            except ValueError:
                return None, None
    if not (w and h):
        return None, None
    if w > max_width:
        h, w = h * max_width / w, max_width
    return max(1, round(w)), max(1, round(h))


def length(v: str | None) -> float | None:
    if not v:
        return None
    m = re.fullmatch(r"\s*([0-9.]+)\s*(px|pt|em|ex|in|cm|mm)?\s*", v)
    if not m:
        return None
    return float(m.group(1)) * UNIT[m.group(2) or ""]


def esc(s) -> str:
    return html.escape(str(s if s is not None else ""), quote=True)
