"""Leenax document service: reads course documents page by page.

Runs only inside the Docker network (no published port) and makes no outside
calls: text comes from the file itself, or from Tesseract OCR running here, with
English and Tamil models installed in the image.

POST /extract  (multipart "file", optional "force_ocr")
  PDF          embedded text per page; pages with no usable text are OCR'd
  DOCX, PPTX, ODT, ODP, DOC, PPT
               converted to PDF by LibreOffice first, so page numbers match print
  PNG, JPG, TIFF, WEBP
               OCR (a multi-page TIFF gives one page per frame)
  TXT          UTF-8 text, split into pages of about 3,000 characters
"""
from __future__ import annotations

import io
import os
import re
import shutil
import subprocess
import tempfile
import unicodedata
from collections import Counter
from dataclasses import dataclass, asdict

from fastapi import Body, FastAPI, File, Form, HTTPException, UploadFile
from fastapi.responses import Response

import exports
import slides
from PIL import Image, ImageSequence, UnidentifiedImageError

MAX_BYTES = int(os.environ.get("MAX_DOCUMENT_BYTES", str(100 * 1024 * 1024)))
MAX_PAGES = int(os.environ.get("MAX_DOCUMENT_PAGES", "500"))
OCR_LANGS = os.environ.get("OCR_LANGUAGES", "eng+tam")
OCR_DPI = int(os.environ.get("OCR_DPI", "300"))
OCR_TIMEOUT = int(os.environ.get("OCR_PAGE_TIMEOUT_SECONDS", "120"))
CONVERT_TIMEOUT = int(os.environ.get("CONVERT_TIMEOUT_SECONDS", "180"))
TEXT_PAGE_CHARS = 3000
# A page with fewer visible characters than this is treated as a scan.
MIN_TEXT_CHARS = 25

Image.MAX_IMAGE_PIXELS = 120_000_000

app = FastAPI(title="Leenax document service", docs_url=None, redoc_url=None, openapi_url=None)

from agent_graph import router as agent_graph_router
app.include_router(agent_graph_router)

TAMIL = re.compile(r"[஀-௿]")
LATIN = re.compile(r"[A-Za-z]")
MODULE_CODE = re.compile(r"\b([A-Z]{2,5})[ -]?(\d{3,5}[A-Z]?)\b")
# Codes that look like module codes but aren't (ISO numbers, years with prefixes, ...).
NOT_MODULE = {"ISO", "ISBN", "PAGE", "FIG", "TABLE", "COVID", "UTF", "HTTP", "IEEE", "AD", "BC", "PO", "NO", "AM", "PM"}

OFFICE = {
    "docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "pptx": "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "odt": "application/vnd.oasis.opendocument.text",
    "odp": "application/vnd.oasis.opendocument.presentation",
    "doc": "application/msword",
    "ppt": "application/vnd.ms-powerpoint",
}


@dataclass
class Page:
    page: int
    text: str
    method: str  # TEXT or OCR
    confidence: float | None = None


# ---- entry points ----------------------------------------------------------------------------------

@app.get("/health")
def health():
    return {"status": "UP", "ocr": tesseract_languages(), "office": shutil.which("soffice") is not None}


@app.post("/extract")
async def extract(file: UploadFile = File(...), force_ocr: bool = Form(False)):
    data = await file.read(MAX_BYTES + 1)
    if len(data) > MAX_BYTES:
        raise HTTPException(status_code=413, detail="Document too large")
    if not data:
        raise HTTPException(status_code=400, detail="Empty file")
    kind = sniff(data, file.filename or "")
    if kind is None:
        raise HTTPException(status_code=415, detail="Unsupported document type")

    converted = False
    if kind in OFFICE:
        data = to_pdf(data, kind)
        kind, converted = "pdf", True

    if kind == "pdf":
        pages, total = read_pdf(data, force_ocr)
    elif kind == "image":
        pages, total = read_image(data)
    else:
        pages, total = read_text(data)

    return summary(pages, total, converted)


EXPORT_TYPES = {
    "pdf": "application/pdf",
    "docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "csv": "text/csv; charset=utf-8",
    "xlsx": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "pptx": "application/vnd.openxmlformats-officedocument.presentationml.presentation",
}


@app.post("/export")
def export(payload: dict = Body(...)):
    """Notes, question banks, quizzes and reports (exports.py); slide decks as PPTX or PDF (slides.py)."""
    data, fmt = slides.export(payload) if payload.get("kind") == "slides" else exports.export(payload)
    return Response(content=data, media_type=EXPORT_TYPES[fmt])


@app.post("/slides/fit")
def slides_fit(payload: dict = Body(...)):
    """How each slide's text fits its boxes: the size used, or how much is too long. No files are made."""
    deck = payload.get("deck") if isinstance(payload.get("deck"), dict) else {}
    if len(deck.get("slides") or []) > 200:
        raise HTTPException(status_code=413, detail="A deck can have at most 200 slides")
    return slides.fit_deck(deck)


# ---- type detection --------------------------------------------------------------------------------

def sniff(data: bytes, filename: str) -> str | None:
    """By content first; the filename only separates formats that share a container."""
    ext = filename.rsplit(".", 1)[-1].lower() if "." in filename else ""
    if data.startswith(b"%PDF-"):
        return "pdf"
    if data.startswith(b"PK\x03\x04"):
        head = data[:8192]
        if b"word/" in head:
            return "docx"
        if b"ppt/" in head:
            return "pptx"
        if b"opendocument.text" in head:
            return "odt"
        if b"opendocument.presentation" in head:
            return "odp"
        return ext if ext in ("docx", "pptx", "odt", "odp") else None
    if data.startswith(b"\xD0\xCF\x11\xE0\xA1\xB1\x1A\xE1"):
        return ext if ext in ("doc", "ppt") else None
    if data[:3] == b"\xFF\xD8\xFF" or data.startswith(b"\x89PNG") or data[:4] in (b"II*\x00", b"MM\x00*") \
            or (data[:4] == b"RIFF" and data[8:12] == b"WEBP"):
        return "image"
    if ext in ("txt", "md", "") and looks_like_text(data):
        return "text"
    return None


def looks_like_text(data: bytes) -> bool:
    if b"\x00" in data[:65536]:
        return False
    try:
        data.decode("utf-8")
        return True
    except UnicodeDecodeError:
        return False


# ---- readers ---------------------------------------------------------------------------------------

def read_pdf(data: bytes, force_ocr: bool) -> tuple[list[Page], int]:
    import pypdfium2 as pdfium

    try:
        pdf = pdfium.PdfDocument(data)
    except pdfium.PdfiumError:
        raise HTTPException(status_code=422, detail="This PDF can't be opened (damaged or password-protected)")
    try:
        total = len(pdf)
        pages: list[Page] = []
        for i in range(min(total, MAX_PAGES)):
            page = pdf[i]
            try:
                text = ""
                if not force_ocr:
                    tp = page.get_textpage()
                    text = clean(tp.get_text_range())
                    tp.close()
                if force_ocr or not usable_text(text):
                    image = page.render(scale=OCR_DPI / 72).to_pil()
                    ocr_text, conf = ocr(image)
                    # Keep the embedded text if OCR found nothing better.
                    if usable_text(ocr_text) or not text:
                        pages.append(Page(i + 1, ocr_text, "OCR", conf))
                        continue
                pages.append(Page(i + 1, text, "TEXT"))
            finally:
                page.close()
        return pages, total
    finally:
        pdf.close()


def read_image(data: bytes) -> tuple[list[Page], int]:
    try:
        img = Image.open(io.BytesIO(data))
        frames = [f.copy() for _, f in zip(range(MAX_PAGES), ImageSequence.Iterator(img))]
        total = getattr(img, "n_frames", 1)
    except (UnidentifiedImageError, OSError, Image.DecompressionBombError):
        raise HTTPException(status_code=422, detail="This image can't be read")
    pages = []
    for i, frame in enumerate(frames):
        text, conf = ocr(frame)
        pages.append(Page(i + 1, text, "OCR", conf))
    return pages, total


def read_text(data: bytes) -> tuple[list[Page], int]:
    text = clean(data.decode("utf-8"))
    parts: list[str] = []
    buf = ""
    for para in re.split(r"\n\s*\n", text):
        if buf and len(buf) + len(para) > TEXT_PAGE_CHARS:
            parts.append(buf)
            buf = ""
        buf = f"{buf}\n\n{para}" if buf else para
    if buf or not parts:
        parts.append(buf)
    return [Page(i + 1, p.strip(), "TEXT") for i, p in enumerate(parts[:MAX_PAGES])], len(parts)


def to_pdf(data: bytes, kind: str) -> bytes:
    """LibreOffice in a throwaway profile; macros never run during a headless conversion."""
    if shutil.which("soffice") is None:
        raise HTTPException(status_code=503, detail="Office conversion isn't installed in the document service")
    with tempfile.TemporaryDirectory(prefix="lx-office-") as work:
        src = os.path.join(work, f"input.{kind}")
        with open(src, "wb") as fh:
            fh.write(data)
        cmd = ["soffice", f"-env:UserInstallation=file://{work}/profile", "--headless", "--norestore",
               "--nolockcheck", "--convert-to", "pdf", "--outdir", work, src]
        try:
            subprocess.run(cmd, check=True, timeout=CONVERT_TIMEOUT, capture_output=True,
                           env={**os.environ, "HOME": work})
        except subprocess.TimeoutExpired:
            raise HTTPException(status_code=422, detail="Converting this document took too long")
        except subprocess.CalledProcessError:
            raise HTTPException(status_code=422, detail="This document can't be converted (damaged or protected)")
        out = os.path.join(work, "input.pdf")
        if not os.path.exists(out):
            raise HTTPException(status_code=422, detail="This document can't be converted (damaged or protected)")
        with open(out, "rb") as fh:
            return fh.read()


# ---- OCR -------------------------------------------------------------------------------------------

def tesseract_languages() -> list[str]:
    try:
        out = subprocess.run(["tesseract", "--list-langs"], capture_output=True, text=True, timeout=10).stdout
    except (OSError, subprocess.TimeoutExpired):
        return []
    return [line.strip() for line in out.splitlines()[1:] if line.strip()]


def ocr(image: Image.Image) -> tuple[str, float | None]:
    """Tesseract's TSV output, rebuilt into lines and paragraphs, plus mean word confidence."""
    if image.mode not in ("RGB", "L"):
        image = image.convert("RGB")
    buf = io.BytesIO()
    image.save(buf, format="PNG")
    try:
        res = subprocess.run(["tesseract", "stdin", "stdout", "-l", OCR_LANGS, "--psm", "3", "tsv"],
                             input=buf.getvalue(), capture_output=True, timeout=OCR_TIMEOUT)
    except FileNotFoundError:
        raise HTTPException(status_code=503, detail="OCR isn't installed in the document service")
    except subprocess.TimeoutExpired:
        return "", None
    if res.returncode != 0:
        raise HTTPException(status_code=503, detail="OCR failed: " + res.stderr.decode("utf-8", "replace")[:200])
    return parse_tsv(res.stdout.decode("utf-8", "replace"))


def parse_tsv(tsv: str) -> tuple[str, float | None]:
    lines: dict[tuple[int, int, int], list[str]] = {}
    confs: list[float] = []
    for row in tsv.splitlines()[1:]:
        cols = row.split("\t")
        if len(cols) < 12 or cols[0] != "5":
            continue
        word = cols[11].strip()
        if not word:
            continue
        key = (int(cols[2]), int(cols[3]), int(cols[4]))  # block, paragraph, line
        lines.setdefault(key, []).append(word)
        try:
            c = float(cols[10])
            if c >= 0:
                confs.append(c)
        except ValueError:
            pass
    out: list[str] = []
    last_para = None
    for key in sorted(lines):
        para = key[:2]
        if last_para is not None and para != last_para:
            out.append("")
        out.append(" ".join(lines[key]))
        last_para = para
    text = clean("\n".join(out))
    return text, (round(sum(confs) / len(confs), 1) if confs else None)


# ---- text checks -----------------------------------------------------------------------------------

def clean(text: str) -> str:
    text = unicodedata.normalize("NFC", text.replace("\r\n", "\n").replace("\r", "\n"))
    # Soft hyphens, byte-order marks and zero-width (non-)joiners: OCR adds the latter after Tamil pulli.
    text = re.sub("[­﻿​‌‍]", "", text)
    text = re.sub(r"[ \t ]+", " ", text)
    text = re.sub(r" *\n *", "\n", text)
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.strip()


def usable_text(text: str) -> bool:
    """False for scans and for legacy-font Tamil (Bamini and similar), which extracts as Latin junk."""
    visible = [c for c in text if not c.isspace()]
    if len(visible) < MIN_TEXT_CHARS:
        return False
    bad = sum(1 for c in visible if c == "�" or 0xE000 <= ord(c) <= 0xF8FF or unicodedata.category(c) == "Cc")
    if bad / len(visible) > 0.05:
        return False
    return not looks_like_legacy_tamil(text)


def looks_like_legacy_tamil(text: str) -> bool:
    """Legacy Tamil fonts map letters onto Latin code points, so the 'words' have almost no vowels."""
    words = re.findall(r"[A-Za-z]{3,}", text)
    if len(words) < 20 or TAMIL.search(text):
        return False
    vowelless = sum(1 for w in words if not re.search(r"[aeiouyAEIOUY]", w))
    odd_case = sum(1 for w in words if re.search(r"[a-z][A-Z]", w))
    return (vowelless + odd_case) / len(words) > 0.35


def language(text: str) -> str:
    tamil = len(TAMIL.findall(text))
    latin = len(LATIN.findall(text))
    total = tamil + latin
    if total < 20:
        return "unknown"
    share = tamil / total
    if share > 0.85:
        return "ta"
    if share < 0.15:
        return "en"
    return "mixed"


def module_codes(text: str) -> list[str]:
    counts = Counter(f"{a}{b}" for a, b in MODULE_CODE.findall(text) if a not in NOT_MODULE)
    return [code for code, _ in counts.most_common(3)]


def title_guess(pages: list[Page]) -> str | None:
    for page in pages[:2]:
        for line in page.text.splitlines():
            line = line.strip()
            if 4 <= len(line) <= 120 and re.search(r"[\w஀-௿]{3}", line):
                return line
    return None


def summary(pages: list[Page], total: int, converted: bool) -> dict:
    text = "\n".join(p.text for p in pages)
    head = "\n".join(p.text for p in pages[:3])
    return {
        "pageCount": total,
        "pagesRead": len(pages),
        "truncated": total > len(pages),
        "ocrPages": sum(1 for p in pages if p.method == "OCR"),
        "converted": converted,
        "language": language(text),
        "moduleCodes": module_codes(head),
        "title": title_guess(pages),
        "pages": [asdict(p) for p in pages],
    }
