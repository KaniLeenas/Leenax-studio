"""Slides (E7): text fitting, the PPTX file, and an independent check that nothing is drawn outside its box.

The render check doesn't trust the fitter: LibreOffice draws the PPTX as a PDF and every character's box,
read back with pdfium, must lie inside the text area it was put in.
"""
import io
import random

import pytest
from fastapi.testclient import TestClient
from pptx import Presentation
from pptx.util import Pt

import slides
from app import app
from test_exports import MERMAID, MERMAID_SVG, key

client = TestClient(app)
PNG = ("data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAFklEQVR4nGP8z8DAwMDAxMDAwMDAAAANHQEDasKb6QAAAABJRU5ErkJggg==")


def deck(style="LECTURE"):
    return {"style": style, "title": "Normalisation", "slides": [
        {"id": "s1", "type": "TITLE", "layout": "TITLE", "title": "Database normalisation", "subtitle": "CSC2041, lesson 4"},
        {"id": "s2", "type": "OBJECTIVES", "layout": "BULLETS", "title": "By the end you can",
         "bullets": ["Explain update anomalies", "Find functional dependencies", "Reach 3NF"], "notes": "Start with the spreadsheet example."},
        {"id": "s3", "type": "COMPARISON", "layout": "TABLE", "title": "Normal forms",
         "table": {"headers": ["Form", "Removes"], "rows": [["1NF", "Repeating groups"], ["2NF", "Partial dependencies"]]}},
        {"id": "s4", "type": "FLOWCHART", "layout": "DIAGRAM", "title": "From table to 1NF",
         "figure": {"kind": "MERMAID", "source": MERMAID}, "caption": "Split repeating groups out"},
        {"id": "s5", "type": "EXAMPLE", "layout": "EXAMPLE", "title": "In SQL", "text": "A composite key:",
         "code": {"language": "sql", "text": "CREATE TABLE enrolment (\n  student_id INT,\n  PRIMARY KEY (student_id)\n);"}},
        {"id": "s6", "type": "DEFINITION", "layout": "DEFINITION", "title": "Definition", "term": "சார்பு",
         "text": "தரவு முரண்பாடுகளைத் தவிர்க்க அட்டவணைகளைப் பிரித்தல்."},
        {"id": "s7", "type": "APPLICATIONS", "layout": "BULLETS_IMAGE", "title": "Where it's used",
         "bullets": ["Banking", "Enrolment systems"], "image": {"assetId": "a1"}, "caption": "A schema"},
    ]}


def test_a_normal_deck_fits_at_full_size():
    f = slides.fit_deck(deck())
    assert f["ok"], f
    sizes = {(s["id"], b["field"]): b["size"] for s in f["slides"] for b in s["boxes"]}
    assert sizes[("s1", "title")] == 48 and sizes[("s2", "bullets")] == 28
    assert "Carlito" in f["method"]


def test_long_text_shrinks_then_is_reported_as_overflow():
    d = {"slides": [{"id": "a", "layout": "BULLETS", "title": "T", "bullets": ["word " * 200]},
                    {"id": "b", "layout": "BULLETS", "title": "T", "bullets": ["word " * 400]}]}
    a, b = slides.fit_deck(d)["slides"]
    assert a["ok"] and 16 <= a["boxes"][1]["size"] < 28
    assert not b["ok"] and "Shorten it or split the slide" in b["problems"][0]
    assert b["boxes"][1]["size"] == 16 and not b["boxes"][1]["fits"]


def test_missing_title_and_empty_table_are_problems():
    r = slides.fit_slide({"id": "x", "layout": "TABLE", "title": " ", "table": {}})
    assert not r["ok"] and "The table is empty" in r["problems"] and "No title" in r["problems"]


def test_tamil_is_measured_wider():
    plain = slides.width("அட்டவணை", "sans", False, 20)
    noto = slides.font("tamil", False, 20).getlength("அட்டவணை") / slides.SCALE
    assert plain == pytest.approx(noto * slides.TAMIL_FACTOR)


def test_pptx_has_fixed_boxes_exact_spacing_notes_and_fonts():
    data, placed = slides.build_pptx(deck(), {key("MERMAID", MERMAID): MERMAID_SVG}, {"a1": PNG})
    prs = Presentation(io.BytesIO(data))
    assert len(prs.slides) == 7 and prs.slide_width == Pt(960) and prs.slide_height == Pt(540)
    for sl in prs.slides:
        for sh in sl.shapes:
            if sh.has_text_frame and sh.text_frame.text.strip():
                body = sh.text_frame._txBody.bodyPr
                assert body.find("{http://schemas.openxmlformats.org/drawingml/2006/main}noAutofit") is not None or \
                    body.find("{http://schemas.openxmlformats.org/drawingml/2006/main}normAutofit") is None
                assert sh.text_frame.word_wrap is True
                for p in sh.text_frame.paragraphs:
                    assert p.line_spacing is not None and p.line_spacing.pt > 0
                    for r in p.runs:
                        assert r.font.name in ("Calibri", "Courier New")
                        assert 'typeface="Nirmala UI"' in r._r.xml
    assert prs.slides[1].notes_slide.notes_text_frame.text == "Start with the spreadsheet example."
    pictures = [sh for sl in prs.slides for sh in sl.shapes if sh.shape_type == 13]
    assert len(pictures) == 2  # the drawn diagram and the image
    tamil = [r for sh in prs.slides[5].shapes if sh.has_text_frame for p in sh.text_frame.paragraphs for r in p.runs if "சார்பு" in r.text]
    assert tamil and 'lang="ta-IN"' in tamil[0]._r.xml
    assert len(placed) == 7


@pytest.mark.parametrize("style", list(slides.STYLES))
def test_libreoffice_draws_every_character_inside_its_box(style):
    data, placed = slides.build_pptx(deck(style), {key("MERMAID", MERMAID): MERMAID_SVG}, {"a1": PNG})
    assert slides.render_check(slides.pptx_to_pdf(data), placed) == []


def test_text_at_the_fit_limit_still_stays_inside_when_drawn():
    """For each text layout, the longest text the fitter accepts, drawn by LibreOffice, mixing in Tamil and wide letters."""
    rnd = random.Random(11)
    words = "normalisation dependency transitive attribute WWW mmmm iii the a of சார்பு அட்டவணை முரண்பாடுகளைத்".split()

    def text(n):
        return " ".join(rnd.choice(words) for _ in range(n))

    makers = {
        "BULLETS": lambda n: {"layout": "BULLETS", "title": text(8), "bullets": [text(max(1, n // 4)) for _ in range(4)]},
        "DEFINITION": lambda n: {"layout": "DEFINITION", "title": text(3), "term": text(4), "text": text(n)},
        "TABLE": lambda n: {"layout": "TABLE", "title": text(3), "table": {"headers": ["A", "B"], "rows": [[text(2), text(max(1, n // 6))] for _ in range(4)]}},
        "TITLE": lambda n: {"layout": "TITLE", "title": text(max(1, n // 4)), "subtitle": text(max(1, n // 6))},
    }
    chosen = []
    for make in makers.values():
        lo, hi, best = 4, 500, None
        while lo <= hi:
            mid = (lo + hi) // 2
            s = make(mid)
            if slides.fit_slide(s)["ok"]:
                best, lo = s, mid + 1
            else:
                hi = mid - 1
        chosen.append(best)
    data, placed = slides.build_pptx({"style": "CORPORATE", "slides": chosen}, {}, {})
    assert slides.render_check(slides.pptx_to_pdf(data), placed) == []


def test_the_render_check_catches_real_overflow(monkeypatch):
    """Turn the fitter off (pretend every box is huge) and the check must find characters below the box."""
    d = {"slides": [{"id": "x", "layout": "BULLETS", "title": "T", "bullets": ["word " * 400]}]}
    real = slides.fit_paragraphs
    monkeypatch.setattr(slides, "fit_paragraphs", lambda b, p: real(slides.Box(b.field, b.x, b.y, b.w, 9999, b.max, b.min, b.role, b.bold, b.bullets, b.anchor), p))
    data, placed = slides.build_pptx(d, {}, {})
    assert len(slides.render_check(slides.pptx_to_pdf(data), placed)) > 50


def test_undrawn_diagram_and_bad_image_get_a_visible_placeholder():
    d = {"slides": [deck()["slides"][3], dict(deck()["slides"][6], image={"assetId": "zz"})]}
    data, placed = slides.build_pptx(d, {}, {"zz": "data:image/svg+xml;base64,PHN2Zz4="})
    prs = Presentation(io.BytesIO(data))
    text = " ".join(sh.text_frame.text for sl in prs.slides for sh in sl.shapes if sh.has_text_frame)
    assert "Diagram not drawn yet" in text and "Image missing" in text
    assert not [sh for sl in prs.slides for sh in sl.shapes if sh.shape_type == 13]


def test_export_endpoint_gives_pptx_and_pdf_and_refuses_overflow():
    r = client.post("/export", json={"kind": "slides", "format": "pptx", "deck": deck(), "figures": {}, "images": {}})
    assert r.status_code == 200 and r.headers["content-type"].startswith("application/vnd.openxmlformats-officedocument.presentationml")
    assert len(Presentation(io.BytesIO(r.content)).slides) == 7
    r = client.post("/export", json={"kind": "slides", "format": "pdf", "deck": deck()})
    assert r.status_code == 200 and r.content[:5] == b"%PDF-"
    bad = {"slides": [{"id": "x", "layout": "BULLETS", "title": "T", "bullets": ["word " * 400]}]}
    r = client.post("/export", json={"kind": "slides", "format": "pptx", "deck": bad})
    assert r.status_code == 422 and "Slide 1: Bullets: Too long" in r.json()["detail"]["problems"][0]
    assert client.post("/export", json={"kind": "slides", "format": "docx", "deck": deck()}).status_code == 400


def test_fit_endpoint():
    r = client.post("/slides/fit", json={"deck": deck()})
    assert r.status_code == 200 and r.json()["ok"] is True and len(r.json()["slides"]) == 7
    assert client.post("/slides/fit", json={"deck": {"slides": [{}] * 201}}).status_code == 413
