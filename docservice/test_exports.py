"""Exports: run with the document service's tests (needs LibreOffice, openpyxl, python-docx for reading back)."""
import csv
import hashlib
import io
import pathlib
import zipfile

import pypdfium2 as pdfium
from fastapi.testclient import TestClient

import exports
from app import app

client = TestClient(app)
FIX = pathlib.Path(__file__).parent / "fixtures"
MATH = r"\frac{a}{b} + \sqrt{x^2+1}"
MERMAID = "flowchart LR\n  A[Table] --> B[1NF]"
MERMAID_SVG = ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 300 80" width="100%" style="max-width: 300px;">'
               '<style>#m .node rect{fill:#fde68a;stroke:#333} .label{font-family:sans-serif}</style>'
               '<defs><marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" markerHeight="6" orient="auto">'
               '<path d="M0,0 L10,5 L0,10 z"/></marker></defs>'
               # the editor copies each element's computed colours onto it before saving, as here
               '<g class="node"><rect x="5" y="20" width="90" height="40" fill="#fde68a" stroke="#333333"/>'
               '<text x="20" y="45" class="label" fill="#111111" font-family="sans-serif" font-size="14">Table</text></g>'
               '<line x1="95" y1="40" x2="190" y2="40" stroke="#333" marker-end="url(#arrow)"/>'
               '<g class="node"><rect x="195" y="20" width="90" height="40" fill="#fde68a" stroke="#333333"/>'
               '<text x="225" y="45" fill="#111111" font-family="sans-serif" font-size="14">1NF</text></g></svg>')


def key(kind, source):
    return f"{kind}:{hashlib.sha256(source.strip().encode()).hexdigest()}"


def notes_payload(fmt, figures=True):
    doc = {"type": "doc", "content": [
        {"type": "heading", "attrs": {"level": 1}, "content": [{"type": "text", "text": "Normalisation"}]},
        {"type": "paragraph", "content": [
            {"type": "text", "text": "A "}, {"type": "text", "text": "key", "marks": [{"type": "bold"}]},
            {"type": "text", "text": " identifies a row. தரவுத்தள முறைமைகள். "},
            {"type": "text", "text": "Docs", "marks": [{"type": "link", "attrs": {"href": "javascript:alert(1)"}}]}]},
        {"type": "mathBlock", "attrs": {"latex": MATH}},
        {"type": "mermaid", "attrs": {"code": MERMAID}},
        {"type": "table", "content": [
            {"type": "tableRow", "content": [{"type": "tableHeader", "content": [{"type": "paragraph", "content": [{"type": "text", "text": "Form"}]}]},
                                             {"type": "tableHeader", "content": [{"type": "paragraph", "content": [{"type": "text", "text": "Rule"}]}]}]},
            {"type": "tableRow", "content": [{"type": "tableCell", "content": [{"type": "paragraph", "content": [{"type": "text", "text": "3NF"}]}]},
                                             {"type": "tableCell", "content": [{"type": "paragraph", "content": [{"type": "text", "text": "<script>no transitive</script>"}]}]}]}]},
        {"type": "codeBlock", "attrs": {"language": "sql"}, "content": [{"type": "text", "text": "SELECT * FROM t WHERE a < 3;"}]},
    ]}
    figs = {key("MATH", MATH): (FIX / "equation-mathjax.svg").read_text(), key("MERMAID", MERMAID): MERMAID_SVG} if figures else {}
    return {"format": fmt, "kind": "notes", "title": "Keys and normal forms", "subtitle": "Exam-focused notes",
            "meta": [["Subject", "CSC2041, Database Systems"], ["Version", "3, approved"]], "doc": doc, "figures": figs,
            "images": {}, "references": ["Handbook, page 4"]}


QUESTIONS = [
    {"number": 1, "topic": "Keys", "style": "APPLICATION", "difficulty": "INTERMEDIATE", "stem": "Which key links two tables?",
     "options": ["Foreign key", "Primary key", "Super key", "=SUM(A1)"], "answer": 0, "explanation": "A foreign key references another table.",
     "optionExplanations": ["", "Identifies rows", "Any unique set", "Not a key"], "source": "Exam notes, version 2", "verification": "UNVERIFIED"},
    {"number": 2, "topic": "Keys", "style": "CODE", "difficulty": "ADVANCED", "stem": "இந்த வினவல் என்ன தரும்?",
     "code": {"language": "sql", "text": "SELECT COUNT(*) FROM t;"}, "options": ["வரிசைகளின் எண்ணிக்கை", "நெடுவரிசைகள்", "பிழை", "எதுவும் இல்லை"],
     "answer": 0, "explanation": "COUNT(*) எண்ணுகிறது.", "verification": "UNVERIFIED"},
]


def post(payload):
    return client.post("/export", json=payload)


def pdf_text(data):
    pdf = pdfium.PdfDocument(data)
    return len(pdf), "\n".join(pdf[i].get_textpage().get_text_range() for i in range(len(pdf)))


def test_notes_pdf_has_text_tables_and_drawings():
    r = post(notes_payload("pdf"))
    assert r.status_code == 200 and r.headers["content-type"] == "application/pdf"
    pages, text = pdf_text(r.content)
    assert "Keys and normal forms" in text and "தரவுத்தள" in text
    assert "3NF" in text and "SELECT * FROM t WHERE a < 3;" in text
    assert "Table" in text and "1NF" in text  # the diagram's labels are vector text
    assert "\\frac" not in text  # the equation is drawn, not shown as source
    assert "<script>" in text  # escaped, shown as text


def test_notes_docx_opens_and_keeps_drawings_as_images():
    import docx
    r = post(notes_payload("docx"))
    assert r.status_code == 200
    d = docx.Document(io.BytesIO(r.content))
    body = "\n".join(p.text for p in d.paragraphs)
    assert "Keys and normal forms" in body and "தரவுத்தள" in body
    assert len(d.tables) == 1 and d.tables[0].cell(1, 0).text == "3NF"
    media = [n for n in zipfile.ZipFile(io.BytesIO(r.content)).namelist() if n.startswith("word/media/")]
    assert any(n.endswith(".png") for n in media), media  # PNG fallback for older Word
    assert any(n.endswith(".svg") for n in media), media
    assert "javascript" not in zipfile.ZipFile(io.BytesIO(r.content)).read("word/document.xml").decode()


def test_undrawn_figures_fall_back_to_source_with_a_note():
    pages, text = pdf_text(post(notes_payload("pdf", figures=False)).content)
    assert "frac" in text and "flowchart" in text
    assert "haven't been drawn yet" in text


def test_question_bank_formats():
    payload = {"kind": "questions", "title": "Keys: question bank", "answerKey": "inline", "meta": [["Version", "2, approved"]], "questions": QUESTIONS}
    pages, text = pdf_text(post({**payload, "format": "pdf"}).content)
    assert "Which key links two tables?" in text and "Answer: A" in text and "இந்த வினவல்" in text
    import docx
    d = docx.Document(io.BytesIO(post({**payload, "format": "docx"}).content))
    assert any("Answer: A" in p.text for p in d.paragraphs)

    raw = post({**payload, "format": "csv"}).content
    assert raw.startswith("﻿".encode())
    table = list(csv.reader(io.StringIO(raw.decode("utf-8-sig"))))
    assert table[0][:6] == ["Number", "Topic", "Subtopic", "Style", "Difficulty", "Question"]
    assert table[1][11] == "A" and table[1][10] == "'=SUM(A1)"  # formulas stay text
    assert table[2][5] == "இந்த வினவல் என்ன தரும்?"

    from openpyxl import load_workbook
    wb = load_workbook(io.BytesIO(post({**payload, "format": "xlsx"}).content))
    ws = wb["Questions"]
    assert ws["F2"].value == "Which key links two tables?" and ws["L3"].value == "A" and ws["K2"].value == "'=SUM(A1)"
    assert wb["About"]["B1"].value == "Keys: question bank"


def test_quiz_answer_key_goes_last_on_its_own_page():
    payload = {"kind": "questions", "title": "Quiz 1", "answerKey": "end", "format": "pdf", "questions": QUESTIONS}
    pages, text = pdf_text(post(payload).content)
    first = pdfium.PdfDocument(post(payload).content)[0].get_textpage().get_text_range()
    assert pages >= 2 and "Answer key" in text and "Answer key" not in first
    none = pdf_text(post({**payload, "answerKey": "none"}).content)[1]
    assert "Answer" not in none


def test_unsupported_export_is_refused():
    assert post({"kind": "notes", "format": "xlsx"}).status_code == 400
    assert post({"kind": "spells", "format": "pdf"}).status_code == 400


def test_svg_cleaning_removes_anything_active_or_external():
    bad = ('<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" onload="x()">'
           '<script>alert(1)</script><foreignObject><div>hi</div></foreignObject>'
           '<style>@import url(http://evil/x.css); .a{fill:red} .b{background:url(http://evil/y.png)}</style>'
           '<image href="http://evil/z.png"/><use xlink:href="http://evil/s.svg#a"/><use xlink:href="#ok"/>'
           '<rect width="10" height="10" style="fill:url(http://evil)" onclick="y()"/><a href="http://evil"><text>t</text></a></svg>')
    clean = exports.sanitize_svg(bad)
    assert clean is not None
    for gone in ("script", "foreignObject", "onload", "onclick", "evil", "@import", "<image", "<a "):
        assert gone not in clean, (gone, clean)
    assert "#ok" in clean and "fill:red" in clean
    assert exports.sanitize_svg('<!DOCTYPE svg [<!ENTITY x "y">]><svg xmlns="http://www.w3.org/2000/svg">&x;</svg>') is None
    assert exports.sanitize_svg("<html><body/></html>") is None
    assert exports.sanitize_svg("not xml") is None


def test_figure_sizes():
    w, h = exports.svg_size((FIX / "equation-mathjax.svg").read_text(), 560)
    assert 100 < w < 120 and 30 < h < 35  # 13.444ex x 4.104ex at 8px per ex
    assert exports.svg_size(MERMAID_SVG, 620) == (300, 80)
    assert exports.svg_size('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 2000 500"/>', 620) == (620, 155)


# ---- QA reports (E5) -------------------------------------------------------------------------------

def qa_report():
    return {
        "topicId": "t1", "topicTitle": "சார்பு நீக்கம் (normalisation)", "headline": "BLOCKED",
        "items": [
            {"resourceId": "n1", "kind": "NOTES", "label": "Exam-focused notes", "versionNo": 2, "versionStatus": "IN_REVIEW",
             "run": {"model": "claude-x", "provider": "Anthropic", "independent": True, "versionNo": 2, "current": True},
             "claimsByBasis": {"UPLOADED": 12, "AI": 3}},
            {"resourceId": "b1", "kind": "MCQ", "label": "Question bank", "versionNo": 1, "versionStatus": "DRAFT", "questions": 20,
             "difficulty": {"BEGINNER": 8, "ADVANCED": 12},
             "run": {"model": "gpt-x", "secondModel": "gemini-y", "independent": False, "versionNo": 1, "current": True}}],
        "issues": [
            {"number": 1, "resourceId": "b1", "resource": "Question bank", "where": "Question 4", "severity": "CRITICAL", "type": "WRONG_KEY",
             "status": "OPEN", "explanation": "The key is C, but the checker's answer is B: <script>x</script>", "basis": "AI"},
            {"number": 1, "resourceId": "n1", "resource": "Exam-focused notes", "where": "3NF: சார்பு நீக்கம்", "severity": "MINOR", "type": "GRAMMAR",
             "status": "DISMISSED", "statusNote": "House style", "explanation": "Missing article.", "basis": "AI"}],
        "corrections": [{"issue": 1, "resource": "Question bank", "status": "REJECTED", "decidedBy": "Kani", "at": "2026-11-24T00:00:00Z"}],
        "coverage": [{"subtopic": "BCNF", "covered": False, "levels": []}, {"subtopic": "3NF", "covered": True, "levels": ["Exam-focused notes"]}],
        "parts": {
            "NOTES": {"checked": 40, "passed": 40, "failed": 0, "corrected": 0, "open": 0, "openCritical": 0, "score": 100, "headline": "PASSED",
                      "categories": {"ACCURACY": {"checked": 15, "passed": 15, "failed": 0}}},
            "MCQ": {"checked": 100, "passed": 99, "failed": 1, "corrected": 0, "open": 1, "openCritical": 1, "score": 99, "headline": "BLOCKED",
                    "categories": {"MCQ_KEYS": {"checked": 20, "passed": 19, "failed": 1}}},
            "COMBINED": {"checked": 140, "passed": 139, "failed": 1, "corrected": 0, "open": 1, "openCritical": 1, "score": 99, "headline": "BLOCKED",
                         "categories": {}}},
        "method": "Passed is checked minus failed.", "recommendations": ["Deal with the 1 open critical issue."],
        "limitations": ["QA is done by AI models."]}


def test_qa_report_shows_blocked_whatever_the_score():
    r = post({"format": "pdf", "kind": "qa", "part": "COMBINED", "title": "Normalisation: academic QA report", "report": qa_report()})
    assert r.status_code == 200
    pages, text = pdf_text(r.content)
    flat = " ".join(text.split())
    assert "Blocked: critical issues are open" in flat and "99%" in flat
    assert "Wrong key" in flat and "<script>x</script>" in flat  # escaped, shown as text
    assert "Not found in any level" in flat and "சார்பு" in flat
    assert "Not independent" in flat and "confirmed by gemini-y" in flat


def test_qa_report_parts_and_word():
    import docx
    r = post({"format": "docx", "kind": "qa", "part": "NOTES", "title": "Notes QA", "report": qa_report()})
    assert r.status_code == 200
    d = docx.Document(io.BytesIO(r.content))
    text = "\n".join(p.text for p in d.paragraphs) + "\n".join(c.text for t in d.tables for row in t.rows for c in row.cells)
    assert "Passed: no critical or major issues open" in text
    assert "Grammar" in text and "Wrong key" not in text  # only the notes' issues
    assert "Not found in any level" in text
    mcq = post({"format": "pdf", "kind": "qa", "part": "MCQ", "title": "MCQ QA", "report": qa_report()})
    flat = " ".join(pdf_text(mcq.content)[1].split())
    assert "Grammar" not in flat and "Wrong key" in flat and "Syllabus coverage" not in flat
    assert post({"format": "xlsx", "kind": "qa", "report": qa_report()}).status_code == 400


# ---- YouTube SEO reports (E6) ------------------------------------------------------------------

def seo_payload():
    m = lambda v, unit, src: {"value": v, "unit": unit, "source": src, "retrievedAt": "2026-11-23T10:00:00Z"}
    est = lambda v, unit="0-1": {"value": v, "unit": unit, "estimated": True, "method": "m"}
    research = {
        "topicTitle": "1NF to 3NF", "subtopics": ["Third normal form", "BCNF"], "languages": ["en", "ta"], "region": "LK",
        "startedAt": "2026-11-23T10:00:00Z",
        "keywords": [{"text": "third normal form", "language": "en", "kind": "PRIMARY", "intent": "LEARN", "searched": True,
                      "relevance": est(1.0), "competition": est(0.44), "gap": est(0.6), "score": est(0.62), "partial": True,
                      "source": "AI suggestion (gpt-x)"},
                     {"text": "மூன்றாம் இயல்பு நிலை", "language": "ta", "kind": "QUESTION", "searched": False, "relevance": est(0.5)}],
        "competitors": [{"rank": 1, "title": "=HYPERLINK(\"http://x\")", "channel": "DB Lab", "url": "https://www.youtube.com/watch?v=v1",
                         "publishedAt": "2025-03-01T10:00:00Z", "duration": m(723, "seconds", "YouTube Data API videos.list"),
                         "views": m(125000, "views", "YouTube Data API videos.list"), "likes": {"value": None, "unit": "likes", "note": "Hidden by the channel"},
                         "comments": m(40, "comments", "YouTube Data API videos.list"), "covers": ["Third normal form"]}],
        "coverage": [{"subtopic": "BCNF", "competitorsCovering": {"value": 0, "unit": "of 1 competitor videos", "source": "Counted by Leenax"}}],
        "analysis": {"titleStrategies": "Most lead with 3NF", "contentGaps": ["BCNF is rarely explained"], "source": "AI analysis (gpt-x)"},
        "sources": [{"name": "YouTube Data API search.list", "detail": "1 keyword", "retrievedAt": "2026-11-23T10:00:00Z"}],
        "limitations": ["No Google Trends file was used, so every opportunity score is partial (no demand signal)."]}
    titles = [{"id": f"t{i}", "text": f"Third normal form explained {i}", "style": "EDUCATIONAL", "language": "en",
               "check": {"length": {"value": 30, "unit": "characters", "source": "Counted by Leenax"}, "warnings": [],
                         "ai": {"clarity": est(4, "1-5")}, "overall": est(4, "1-5"), "reasoning": "Clear and accurate"}} for i in range(1, 11)]
    report = {"topicTitle": "1NF to 3NF", "titles": titles, "recommendedTitleId": "t1", "chosenTitleId": "t2", "recommendationReason": "Clearest",
              "description": "A lesson on 3NF.\nWith examples.", "primaryKeywords": ["third normal form"], "tags": ["3nf", "dbms"],
              "hashtags": ["#dbms"], "thumbnailText": "3NF in 10 minutes", "thumbnailPrompt": "A table splitting in two",
              "chapters": [{"title": "Intro", "start": est(0, "seconds")}, {"title": "3NF", "start": est(95, "seconds")}],
              "strategies": {"SHORTS": {"titleIdea": "3NF in 60 seconds", "keywords": ["3nf"]}}}
    return {"kind": "seo", "title": "1NF to 3NF: YouTube SEO report", "report": report, "research": research}


def test_seo_report_has_its_23_parts_and_labels_its_numbers():
    r = post({**seo_payload(), "format": "pdf"})
    assert r.status_code == 200
    flat = " ".join(pdf_text(r.content)[1].split())
    for part in ("1. Topic", "3. Search demand estimates", "8. Title recommendations", "17. Thumbnail design prompt", "21. Research sources",
                 "23. Data limitations"):
        assert part in flat, part
    assert "No demand signal" in flat and "none are estimated here" in flat
    assert "125,000" in flat and "Public numbers from the YouTube Data API" in flat
    assert "Hidden by the channel" in flat
    assert "0.62 (estimated, partial)" in flat
    assert "1:35 3NF" in flat and "Times are estimated plans" in flat
    assert "மூன்றாம்" in flat
    assert "(chosen)" in flat and "(recommended)" in flat


def test_seo_report_as_word_and_excel():
    import docx
    from openpyxl import load_workbook
    d = docx.Document(io.BytesIO(post({**seo_payload(), "format": "docx"}).content))
    text = "\n".join(p.text for p in d.paragraphs) + "\n".join(c.text for t in d.tables for row in t.rows for c in row.cells)
    assert "Data limitations" in text and "Clearest" in text
    x = post({**seo_payload(), "format": "xlsx"})
    assert x.status_code == 200
    wb = load_workbook(io.BytesIO(x.content))
    assert wb.sheetnames == ["Keywords", "Competitors", "Titles", "Sources"]
    kw = wb["Keywords"]
    assert kw["A2"].value == "third normal form" and kw["J2"].value == "0.62 (estimated) partial"
    assert kw["J3"].value == "not searched" and kw["G2"].value == "no Trends value"
    comp = wb["Competitors"]
    assert comp["B2"].value.startswith("'="), "formula-like text is made safe"
    assert comp["G2"].value == "125,000 (YouTube Data API videos.list, 2026-11-23)"
    assert comp["H2"].value == "Hidden by the channel"
    assert wb["Titles"]["P2"].value == "recommended" and wb["Titles"]["P3"].value == "chosen"
    assert any(r[0].value == "Scoring" for r in wb["Sources"].iter_rows(min_row=2))
