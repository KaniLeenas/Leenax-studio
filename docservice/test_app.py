"""Run: pip install -r requirements.txt pytest httpx && pytest -q
Needs tesseract with the eng and tam models, and LibreOffice (the Docker image has both).
The fixtures are one two-page lecture in English and Tamil: as a Word file, a PDF
with real text, a scanned PDF (images only) and a photo of the first page.
"""
import io
import pathlib

from fastapi.testclient import TestClient

import app as service
from app import app

client = TestClient(app)
FIX = pathlib.Path(__file__).parent / "fixtures"


def post(name, content=None, **form):
    data = content if content is not None else (FIX / name).read_bytes()
    return client.post("/extract", files={"file": (name, data)}, data={k: str(v).lower() for k, v in form.items()})


def test_health_lists_both_ocr_languages():
    body = client.get("/health").json()
    assert body["status"] == "UP"
    assert {"eng", "tam"} <= set(body["ocr"])


def test_pdf_with_text_is_read_without_ocr():
    body = post("lesson.pdf").json()
    assert body["pageCount"] == 2 and body["ocrPages"] == 0
    assert body["language"] == "mixed"
    assert body["moduleCodes"] == ["CSC2041"]
    assert body["title"] == "CSC2041 Database Systems"
    p1, p2 = body["pages"]
    assert p1["method"] == "TEXT" and "முதன்மை" in p1["text"]
    assert "transactions" in p2["text"] and "பரிவர்த்தனைகள்" in p2["text"]


def test_scanned_pdf_is_read_with_ocr_in_both_languages():
    body = post("lesson-scan.pdf").json()
    assert body["pageCount"] == 2 and body["ocrPages"] == 2
    p1, p2 = body["pages"]
    assert p1["method"] == "OCR" and p1["confidence"] > 60
    assert "Normalisation" in p1["text"]
    assert "தரவுத்தள" in p1["text"]
    assert "ACID" in p2["text"]
    assert body["moduleCodes"] == ["CSC2041"]


def test_word_file_keeps_its_printed_pages():
    body = post("lesson.docx").json()
    assert body["converted"] is True and body["pageCount"] == 2
    assert "ஒருங்கமைவு" in body["pages"][1]["text"]


def test_photo_is_ocrd():
    body = post("lesson-page1.png").json()
    assert body["ocrPages"] == 1 and "Database" in body["pages"][0]["text"]


def test_force_ocr_reads_a_text_pdf_from_the_picture():
    body = post("lesson.pdf", force_ocr=True).json()
    assert body["ocrPages"] == 2 and "Normalisation" in body["pages"][0]["text"]


def test_plain_text_is_split_into_pages():
    text = ("Paragraph about indexes. " * 40 + "\n\n") * 6
    body = post("notes.txt", text.encode()).json()
    assert body["pageCount"] >= 2 and all(p["method"] == "TEXT" for p in body["pages"])


def test_rejects_unknown_and_damaged_files():
    assert post("x.exe", b"MZ\x90\x00" + b"\x00" * 100).status_code == 415
    assert post("x.svg", b"<svg xmlns='http://www.w3.org/2000/svg'/>").status_code == 415
    assert post("broken.pdf", b"%PDF-1.7\nnot really").status_code == 422
    assert post("empty.pdf", b"").status_code == 400


def test_legacy_tamil_font_text_counts_as_unusable():
    # Bamini-style text: Tamil typed with a font that maps letters onto Latin keys.
    junk = "jkpo; nkhop gw;wpa ghlj;jpl; tpsf;fk; nra;jpy; " * 6
    assert service.looks_like_legacy_tamil(junk)
    assert not service.usable_text(junk)
    assert service.usable_text("Normalisation removes redundancy from relations in a database design. " * 2)


def test_language_and_codes():
    assert service.language("தமிழ் மொழி பாடம் இன்று தொடங்குகிறது") == "ta"
    assert service.language("An English lecture about operating systems") == "en"
    assert service.module_codes("Module IT 1050 and IT1050, see ISO 9001 and IEEE 754") == ["IT1050"]


def test_ocr_tsv_is_rebuilt_into_lines_and_paragraphs():
    rows = ["level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext"]
    for (b, p, l, w, word, conf) in [(1, 1, 1, 1, "Hello", 90), (1, 1, 1, 2, "there", 80),
                                     (1, 1, 2, 1, "next", 70), (1, 2, 1, 1, "Para", 60)]:
        rows.append(f"5\t1\t{b}\t{p}\t{l}\t{w}\t0\t0\t1\t1\t{conf}\t{word}")
    text, conf = service.parse_tsv("\n".join(rows))
    assert text == "Hello there\nnext\n\nPara"
    assert conf == 75.0


def test_zero_width_marks_from_ocr_are_removed():
    assert service.clean("முறைமைகள்‌ பற்றிய​") == "முறைமைகள் பற்றிய"
