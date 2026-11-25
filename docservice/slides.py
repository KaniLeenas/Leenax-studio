"""Slides (E7): fitting text into fixed boxes, and writing PPTX and PDF files.

Text is measured with fonts that have the same character widths as the ones PowerPoint uses:
Carlito for Calibri and Liberation Mono for Courier New. Every box gets an exact line spacing
and no autofit, so the lines measured here are the lines PowerPoint draws. A box's text is
made smaller until it fits, down to a readable minimum; what still doesn't fit is reported as
overflow, and the deck can't be approved until it's fixed.

Tamil is measured with Noto Sans Tamil and shown with Nirmala UI on Windows, which has
different widths, so Tamil gets a 12% safety margin.
"""
from __future__ import annotations

import base64
import io
import os
import re
import subprocess
import tempfile
from dataclasses import dataclass, field
from functools import lru_cache

from PIL import Image, ImageFont

# ---- the page ---------------------------------------------------------------------------------------

W, H = 960.0, 540.0          # 13.333 × 7.5 inches in points (16:9)
INSET = 3.6                  # 0.05 inch inside every text box
LINE = 1.2                   # exact line spacing, × font size
WIDTH_SAFETY = 0.97          # leave 3% of every line unused
TAMIL_FACTOR = 1.12          # Tamil widths differ between Noto Sans Tamil and Nirmala UI
BULLET_INDENT = 22.0         # points from the bullet to the text
PARA_GAP = 0.45              # space before each bullet, × font size

FONT_DIRS = [os.environ.get("LEENAX_FONT_DIR", ""), "/usr/share/fonts/truetype/crosextra", "/usr/share/fonts/truetype/liberation",
             "/usr/share/fonts/truetype/liberation2", "/usr/share/fonts/truetype/noto", "/usr/share/fonts/opentype/noto",
             os.path.expanduser("~/.fonts")]
FILES = {
    ("sans", False): ["Carlito-Regular.ttf"], ("sans", True): ["Carlito-Bold.ttf"],
    ("mono", False): ["LiberationMono-Regular.ttf"], ("mono", True): ["LiberationMono-Bold.ttf"],
    ("tamil", False): ["NotoSansTamil-Regular.ttf"], ("tamil", True): ["NotoSansTamil-Bold.ttf", "NotoSansTamil-Regular.ttf"],
}
TYPEFACE = {"sans": "Calibri", "mono": "Courier New"}
TAMIL_TYPEFACE = "Nirmala UI"

TYPES = ["TITLE", "OBJECTIVES", "INTRODUCTION", "OVERVIEW", "DEFINITION", "CONCEPT", "DIAGRAM", "FLOWCHART", "EXAMPLE",
         "COMPARISON", "APPLICATIONS", "EXAM_TIPS", "SUMMARY", "REVISION", "REFERENCES"]
LAYOUTS = ["TITLE", "BULLETS", "BULLETS_IMAGE", "DEFINITION", "TABLE", "DIAGRAM", "EXAMPLE", "IMAGE"]


@dataclass(frozen=True)
class Style:
    name: str
    bg: str
    title: str
    text: str
    accent: str
    muted: str
    code_bg: str
    head_bg: str
    head_text: str
    band: str | None = None      # a solid band behind the title
    bar: bool = False            # a thin accent bar under the title


STYLES = {
    "LECTURE": Style("University lecture", "FFFFFF", "1F3864", "222222", "2E75B6", "6B7280", "F2F4F7", "1F3864", "FFFFFF", bar=True),
    "MINIMAL": Style("Modern minimalist", "FAFAF7", "111111", "333333", "0F766E", "8A8A85", "F0F0EB", "E7E5E0", "111111"),
    "DARK_TECH": Style("Dark technology", "0E1726", "7DD3FC", "E5ECF5", "22D3EE", "94A3B8", "1B2A41", "1E3A5F", "E5ECF5", bar=True),
    "INFOGRAPHIC": Style("Animated infographic", "FFFFFF", "FFFFFF", "1E293B", "F59E0B", "64748B", "FEF3C7", "F59E0B", "1E293B", band="0F766E"),
    "WHITEBOARD": Style("Whiteboard", "FBFAF5", "1D3557", "2B2D42", "E63946", "6C757D", "EEF1F4", "1D3557", "FFFFFF"),
    "CORPORATE": Style("Corporate education", "FFFFFF", "FFFFFF", "1F2937", "1D4ED8", "6B7280", "F3F4F6", "1E3A8A", "FFFFFF", band="1E3A8A"),
    "ILLUSTRATED": Style("Illustrated presentation", "FFF8F0", "7C2D12", "3F3F46", "EA580C", "78716C", "FDEBD8", "EA580C", "FFFFFF", bar=True),
}


@dataclass
class Box:
    """A text area: where it is, the most and least font size, and whether it's bold, code or a title."""
    field: str
    x: float
    y: float
    w: float
    h: float
    max: float
    min: float
    role: str = "sans"
    bold: bool = False
    bullets: bool = False
    anchor: str = "top"


def geometry(layout: str, has_image: bool = False) -> dict[str, Box]:
    """Where each part of a slide goes, per layout (points, from the top left)."""
    title = Box("title", 43, 32, 874, 80, 36, 24, bold=True, anchor="bottom")
    body_y, body_h = 128, 372
    if layout == "TITLE":
        return {"title": Box("title", 72, 150, 816, 150, 48, 28, bold=True, anchor="bottom"),
                "subtitle": Box("subtitle", 72, 316, 816, 96, 24, 16)}
    if layout == "BULLETS":
        return {"title": title, "bullets": Box("bullets", 43, body_y, 874, body_h, 28, 16, bullets=True)}
    if layout == "BULLETS_IMAGE":
        return {"title": title, "bullets": Box("bullets", 43, body_y, 500, body_h, 26, 16, bullets=True),
                "image": Box("image", 563, body_y, 354, 330, 0, 0), "caption": Box("caption", 563, body_y + 336, 354, 36, 14, 11)}
    if layout == "DEFINITION":
        return {"title": title, "term": Box("term", 43, 140, 874, 70, 32, 22, bold=True),
                "text": Box("text", 43, 222, 874, 278, 26, 16)}
    if layout == "TABLE":
        return {"title": title, "table": Box("table", 43, body_y, 874, body_h, 20, 11)}
    if layout == "DIAGRAM":
        return {"title": title, "figure": Box("figure", 43, body_y, 874, 318, 0, 0), "caption": Box("caption", 43, body_y + 326, 874, 46, 18, 12)}
    if layout == "EXAMPLE":
        return {"title": title, "text": Box("text", 43, body_y, 874, 112, 22, 14),
                "code": Box("code", 43, body_y + 124, 874, body_h - 124, 18, 10, role="mono")}
    if layout == "IMAGE":
        return {"title": title, "image": Box("image", 43, body_y, 874, 318, 0, 0), "caption": Box("caption", 43, body_y + 326, 874, 46, 18, 12)}
    raise ValueError(f"Unknown layout {layout}")


NUMBER_BOX = Box("number", 860, 508, 72, 22, 11, 11)

# ---- measuring --------------------------------------------------------------------------------------

SCALE = 20  # measure at 20× so sub-point widths count


@lru_cache(maxsize=None)
def font_file(role: str, bold: bool) -> str:
    for name in FILES[(role, bold)]:
        for d in FONT_DIRS:
            if d and os.path.isfile(os.path.join(d, name)):
                return os.path.join(d, name)
    raise RuntimeError(f"Font for {role} {'bold' if bold else 'regular'} isn't installed ({FILES[(role, bold)][0]})")


@lru_cache(maxsize=256)
def font(role: str, bold: bool, size: float) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(font_file(role, bold), int(round(size * SCALE)), layout_engine=ImageFont.Layout.BASIC)


TAMIL = re.compile(r"[஀-௿‌‍]+")


def is_tamil(s: str) -> bool:
    return bool(TAMIL.search(s))


def width(text: str, role: str, bold: bool, size: float) -> float:
    """Width in points, Tamil runs measured with the Tamil font and the safety factor."""
    total = 0.0
    pos = 0
    for m in TAMIL.finditer(text):
        if m.start() > pos:
            total += font(role, bold, size).getlength(text[pos:m.start()]) / SCALE
        total += font("tamil", bold, size).getlength(m.group()) / SCALE * TAMIL_FACTOR
        pos = m.end()
    if pos < len(text):
        total += font(role, bold, size).getlength(text[pos:]) / SCALE
    return total


def wrap(text: str, maxw: float, role: str, bold: bool, size: float) -> list[str]:
    """Greedy word wrap, as PowerPoint does; a word wider than the line is broken by characters."""
    lines: list[str] = []
    for para in (text or "").split("\n"):
        words = para.split(" ")
        line = ""
        for w in words:
            cand = w if not line else line + " " + w
            if width(cand, role, bold, size) <= maxw:
                line = cand
                continue
            if line:
                lines.append(line)
            if width(w, role, bold, size) <= maxw:
                line = w
                continue
            chunk = ""
            for ch in w:
                if width(chunk + ch, role, bold, size) <= maxw:
                    chunk += ch
                else:
                    lines.append(chunk)
                    chunk = ch
            line = chunk
        lines.append(line)
    return lines


@dataclass
class Fit:
    field: str
    size: float
    fits: bool
    lines: int
    needed: float
    room: float
    min: float
    max: float
    note: str | None = None


def fit_paragraphs(box: Box, paras: list[str]) -> Fit:
    """The biggest size, from box.max down to box.min in 1-point steps, at which every paragraph fits."""
    inner_w = (box.w - 2 * INSET - (BULLET_INDENT if box.bullets else 0)) * WIDTH_SAFETY
    room = box.h - 2 * INSET
    size = box.max
    last = None
    while size >= box.min - 1e-6:
        lines = 0
        need = 0.0
        for i, p in enumerate(paras):
            n = len(wrap(p, inner_w, box.role, box.bold, size))
            lines += n
            need += n * size * LINE + (size * PARA_GAP if box.bullets and i > 0 else 0)
        last = Fit(box.field, size, need <= room, lines, round(need, 1), round(room, 1), box.min, box.max)
        if need <= room:
            return last
        size -= 1
    last.note = (f"Too long: needs {last.needed:.0f} points of height at the smallest size ({box.min:.0f} pt) but the box has "
                 f"{last.room:.0f}. Shorten it or split the slide.")
    return last


def fit_table(box: Box, headers: list[str], rows: list[list[str]]) -> tuple[Fit, list[float], list[float]]:
    """A table fits when every row's tallest cell, added up, fits the box. Returns the fit, column widths and row heights."""
    cols = max(1, len(headers), max((len(r) for r in rows), default=0))
    grid = [list(headers) + [""] * (cols - len(headers))] + [list(r) + [""] * (cols - len(r)) for r in rows]
    # column widths in proportion to the longest text, within limits
    longest = [max(len(grid[r][c] or "") for r in range(len(grid))) + 4 for c in range(cols)]
    total = sum(longest)
    widths = [max(box.w * 0.12, box.w * l / total) for l in longest]
    scale = box.w / sum(widths)
    widths = [w * scale for w in widths]
    cell_inset = 5.0
    size = box.max
    while True:
        heights = []
        for r, row in enumerate(grid):
            tallest = 0.0
            for c, text in enumerate(row):
                n = len(wrap(text or "", (widths[c] - 2 * cell_inset) * WIDTH_SAFETY, "sans", r == 0, size))
                tallest = max(tallest, n * size * LINE)
            heights.append(tallest + 2 * cell_inset)
        need = sum(heights)
        ok = need <= box.h
        if ok or size <= box.min:
            f = Fit("table", size, ok, sum(1 for _ in grid), round(need, 1), round(box.h, 1), box.min, box.max,
                    None if ok else f"The table needs {need:.0f} points of height at {box.min:.0f} pt but has {box.h:.0f}. Cut rows or words.")
            return f, widths, heights
        size -= 1


# ---- what a slide holds -----------------------------------------------------------------------------

def text_of(slide: dict, fieldname: str) -> list[str]:
    if fieldname == "bullets":
        return [str(b) for b in slide.get("bullets") or [] if str(b).strip()]
    if fieldname == "code":
        c = slide.get("code") or {}
        return [str(c.get("text") or "")]
    v = slide.get(fieldname)
    return [str(v)] if v else []


def layout_of(slide: dict) -> str:
    l = slide.get("layout")
    return l if l in LAYOUTS else "BULLETS"


def fit_slide(slide: dict) -> dict:
    """Fits one slide: each text area's size, or what overflows."""
    layout = layout_of(slide)
    boxes = geometry(layout)
    out, problems = [], []
    for name, box in boxes.items():
        if name in ("image", "figure"):
            continue
        if name == "table":
            t = slide.get("table") or {}
            headers, rows = [str(x) for x in t.get("headers") or []], [[str(x) for x in r] for r in t.get("rows") or []]
            if not headers and not rows:
                problems.append("The table is empty")
                continue
            f, _, _ = fit_table(box, headers, rows)
        else:
            paras = text_of(slide, name)
            if not paras:
                continue
            f = fit_paragraphs(box, paras)
        out.append(f.__dict__)
        if not f.fits:
            problems.append(f"{name.capitalize()}: {f.note}")
    if not (slide.get("title") or "").strip():
        problems.append("No title")
    return {"id": slide.get("id"), "layout": layout, "ok": not problems, "boxes": out, "problems": problems}


def fit_deck(deck: dict) -> dict:
    slides = [s for s in deck.get("slides") or [] if isinstance(s, dict)]
    res = [fit_slide(s) for s in slides]
    return {"ok": all(r["ok"] for r in res), "slides": res, "method": (
        "Measured with Carlito (same widths as Calibri) and Liberation Mono (same widths as Courier New); Tamil with Noto Sans "
        f"Tamil plus {round((TAMIL_FACTOR - 1) * 100)}%. Exact line spacing ({LINE} × size), no autofit, {round((1 - WIDTH_SAFETY) * 100)}% "
        "of each line left unused.")}


# ---- writing the PPTX -------------------------------------------------------------------------------

def build_pptx(deck: dict, figures: dict[str, str], images: dict[str, str]) -> tuple[bytes, list[dict]]:
    """The PPTX file, and where every text area went (for the render check)."""
    from pptx import Presentation
    from pptx.util import Pt

    style = STYLES.get(deck.get("style") or "LECTURE", STYLES["LECTURE"])
    prs = Presentation()
    prs.slide_width, prs.slide_height = Pt(W), Pt(H)
    blank = prs.slide_layouts[6]
    slides = [s for s in deck.get("slides") or [] if isinstance(s, dict)]
    placed: list[dict] = []
    for n, s in enumerate(slides, start=1):
        sl = prs.slides.add_slide(blank)
        fill = sl.background.fill
        fill.solid()
        fill.fore_color.rgb = rgb(style.bg)
        layout = layout_of(s)
        boxes = geometry(layout)
        rects: list[tuple[float, float, float, float]] = []
        decorate(sl, style, layout)
        for name, box in boxes.items():
            if name == "figure":
                png = figure_png(figures.get(figure_key(s.get("figure") or {})))
                r = picture(sl, box, png, "Diagram not drawn yet: open the deck in Leenax once", style)
                if r:
                    rects.append(r)
                continue
            if name == "image":
                img = s.get("image") or {}
                r = picture(sl, box, data_url_bytes(images.get(str(img.get("assetId")))), "Image missing", style)
                if r:
                    rects.append(r)
                continue
            if name == "table":
                t = s.get("table") or {}
                headers, rows = [str(x) for x in t.get("headers") or []], [[str(x) for x in r] for r in t.get("rows") or []]
                if headers or rows:
                    rects.append(table(sl, box, headers, rows, style))
                continue
            paras = text_of(s, name)
            if not paras:
                continue
            f = fit_paragraphs(box, paras)
            color = style.title if name == "title" else (style.muted if name in ("caption", "subtitle") and layout != "TITLE" else style.text)
            if layout == "TITLE" and name == "subtitle":
                color = style.muted if style.band is None else style.text
            if name == "title" and style.band and layout != "TITLE":
                color = style.head_text if style.head_text != style.text else "FFFFFF"
            if layout == "TITLE" and name == "title" and style.band:
                color = style.band
            rects.append(textbox(sl, box, paras, f.size, color, style, code=name == "code"))
        num = textbox(sl, NUMBER_BOX, [f"{n} / {len(slides)}"], NUMBER_BOX.max, style.muted, style, align_right=True)
        rects.append(num)
        notes = (s.get("notes") or "").strip()
        if notes:
            sl.notes_slide.notes_text_frame.text = notes
        placed.append({"slide": n, "rects": rects})
    buf = io.BytesIO()
    prs.save(buf)
    return buf.getvalue(), placed


def rgb(hexstr: str):
    from pptx.dml.color import RGBColor
    return RGBColor.from_string(hexstr)


def decorate(sl, style: Style, layout: str):
    from pptx.enum.shapes import MSO_SHAPE
    from pptx.util import Pt
    if style.band and layout != "TITLE":
        r = sl.shapes.add_shape(MSO_SHAPE.RECTANGLE, 0, 0, Pt(W), Pt(120))
        r.fill.solid()
        r.fill.fore_color.rgb = rgb(style.band)
        r.line.fill.background()
    if style.band and layout == "TITLE":
        r = sl.shapes.add_shape(MSO_SHAPE.RECTANGLE, 0, Pt(H - 36), Pt(W), Pt(36))
        r.fill.solid()
        r.fill.fore_color.rgb = rgb(style.band)
        r.line.fill.background()
    if style.bar and layout != "TITLE":
        r = sl.shapes.add_shape(MSO_SHAPE.RECTANGLE, Pt(43), Pt(116), Pt(96), Pt(4))
        r.fill.solid()
        r.fill.fore_color.rgb = rgb(style.accent)
        r.line.fill.background()
    if style.name == "Illustrated presentation":
        for x, y, d in ((W - 70, -30, 120), (-40, H - 60, 110)):
            c = sl.shapes.add_shape(MSO_SHAPE.OVAL, Pt(x), Pt(y), Pt(d), Pt(d))
            c.fill.solid()
            c.fill.fore_color.rgb = rgb(style.code_bg)
            c.line.fill.background()


def textbox(sl, box: Box, paras: list[str], size: float, color: str, style: Style, code: bool = False, align_right: bool = False):
    from lxml import etree
    from pptx.enum.text import MSO_ANCHOR, MSO_AUTO_SIZE, PP_ALIGN
    from pptx.oxml.ns import qn
    from pptx.util import Pt

    tb = sl.shapes.add_textbox(Pt(box.x), Pt(box.y), Pt(box.w), Pt(box.h))
    if code:
        tb.fill.solid()
        tb.fill.fore_color.rgb = rgb(style.code_bg)
    tf = tb.text_frame
    tf.word_wrap = True
    tf.auto_size = MSO_AUTO_SIZE.NONE
    tf.margin_left = tf.margin_right = tf.margin_top = tf.margin_bottom = Pt(INSET)
    tf.vertical_anchor = {"top": MSO_ANCHOR.TOP, "bottom": MSO_ANCHOR.BOTTOM}.get(box.anchor, MSO_ANCHOR.TOP)
    lines = paras if not code else (paras[0] or "").split("\n")
    for i, text in enumerate(lines):
        p = tf.paragraphs[0] if i == 0 else tf.add_paragraph()
        p.line_spacing = Pt(size * LINE)
        p.space_before = Pt(size * PARA_GAP if box.bullets and i > 0 else 0)
        p.space_after = Pt(0)
        if align_right:
            p.alignment = PP_ALIGN.RIGHT
        r = p.add_run()
        r.text = text if text else " "
        f = r.font
        f.size = Pt(size)
        f.bold = box.bold
        f.color.rgb = rgb(color)
        f.name = TYPEFACE["mono" if code else "sans"]
        rpr = r._r.get_or_add_rPr()
        cs = etree.SubElement(rpr, qn("a:cs"))
        cs.set("typeface", TAMIL_TYPEFACE)
        if is_tamil(text):
            rpr.set("lang", "ta-IN")
        if box.bullets:
            ppr = p._p.get_or_add_pPr()
            ppr.set("marL", str(int(Pt(BULLET_INDENT))))
            ppr.set("indent", str(int(-Pt(BULLET_INDENT))))
            clr = etree.SubElement(ppr, qn("a:buClr"))
            etree.SubElement(clr, qn("a:srgbClr")).set("val", style.accent)
            etree.SubElement(ppr, qn("a:buFont")).set("typeface", "Arial")
            etree.SubElement(ppr, qn("a:buChar")).set("char", "•")
    return (box.x, box.y, box.w, box.h)


def table(sl, box: Box, headers: list[str], rows: list[list[str]], style: Style):
    from lxml import etree
    from pptx.oxml.ns import qn
    from pptx.util import Pt

    f, widths, heights = fit_table(box, headers, rows)
    grid = [headers] + rows if headers else rows
    cols = len(widths)
    shape = sl.shapes.add_table(len(grid), cols, Pt(box.x), Pt(box.y), Pt(sum(widths)), Pt(sum(heights)))
    tbl = shape.table
    tbl.first_row = bool(headers)
    tbl.horz_banding = False
    for c, w in enumerate(widths):
        tbl.columns[c].width = Pt(w)
    for r, h in enumerate(heights[: len(grid)]):
        tbl.rows[r].height = Pt(h)
    for r, row in enumerate(grid):
        for c in range(cols):
            cell = tbl.cell(r, c)
            cell.margin_left = cell.margin_right = cell.margin_top = cell.margin_bottom = Pt(5)
            head = r == 0 and bool(headers)
            cell.fill.solid()
            cell.fill.fore_color.rgb = rgb(style.head_bg if head else style.bg)
            tf = cell.text_frame
            tf.word_wrap = True
            p = tf.paragraphs[0]
            p.line_spacing = Pt(f.size * LINE)
            p.space_before = p.space_after = Pt(0)
            run = p.add_run()
            run.text = (row[c] if c < len(row) else "") or " "
            run.font.size = Pt(f.size)
            run.font.bold = head
            run.font.name = TYPEFACE["sans"]
            run.font.color.rgb = rgb(style.head_text if head else style.text)
            rpr = run._r.get_or_add_rPr()
            etree.SubElement(rpr, qn("a:cs")).set("typeface", TAMIL_TYPEFACE)
    return (box.x, box.y, sum(widths), sum(heights))


def picture(sl, box: Box, png: bytes | None, missing: str, style: Style):
    from pptx.util import Pt
    if not png:
        return textbox(sl, Box("missing", box.x, box.y + box.h / 2 - 20, box.w, 40, 18, 18), [missing], 18, style.muted, style)
    im = Image.open(io.BytesIO(png))
    iw, ih = im.size
    scale = min(box.w / iw, box.h / ih)
    w, h = iw * scale, ih * scale
    sl.shapes.add_picture(io.BytesIO(png), Pt(box.x + (box.w - w) / 2), Pt(box.y + (box.h - h) / 2), Pt(w), Pt(h))
    return None


def figure_key(fig: dict) -> str:
    """Same key the notes exports use: kind and the SHA-256 of the trimmed source."""
    import hashlib
    kind = fig.get("kind") if fig.get("kind") in ("MERMAID", "MATH") else "MERMAID"
    return f"{kind}:{hashlib.sha256(str(fig.get('source') or '').strip().encode('utf-8')).hexdigest()}"


def data_url_bytes(url: str | None) -> bytes | None:
    from exports import DATA_IMAGE
    if not url or not DATA_IMAGE.match(url):
        return None
    try:
        raw = base64.b64decode(url.split(",", 1)[1])
        im = Image.open(io.BytesIO(raw))
        out = io.BytesIO()
        im.convert("RGBA").save(out, "PNG")
        return out.getvalue()
    except Exception:
        return None


def figure_png(svg: str | None) -> bytes | None:
    """A diagram's SVG (already cleaned) as a PNG three times its size, drawn by LibreOffice."""
    if not svg:
        return None
    from exports import sanitize_svg, svg_size
    clean = sanitize_svg(svg)
    if not clean:
        return None
    w, h = svg_size(clean, 4000)
    if w and h:
        clean = re.sub(r'(<svg\b[^>]*?)\swidth="[^"]*"', r"\1", clean, count=1)
        clean = re.sub(r'(<svg\b[^>]*?)\sheight="[^"]*"', r"\1", clean, count=1)
        clean = clean.replace("<svg", f'<svg width="{w * 3:.0f}" height="{h * 3:.0f}"', 1)
    with tempfile.TemporaryDirectory(prefix="lx-fig-") as work:
        src = os.path.join(work, "fig.svg")
        with open(src, "w", encoding="utf-8") as fh:
            fh.write(clean)
        cmd = ["soffice", f"-env:UserInstallation=file://{work}/profile", "--headless", "--norestore", "--nolockcheck",
               "--convert-to", "png", "--outdir", work, src]
        try:
            subprocess.run(cmd, check=True, timeout=60, capture_output=True, env={**os.environ, "HOME": work})
            with open(os.path.join(work, "fig.png"), "rb") as fh:
                return fh.read()
        except Exception:
            return None


def pptx_to_pdf(pptx: bytes) -> bytes:
    with tempfile.TemporaryDirectory(prefix="lx-slides-") as work:
        src = os.path.join(work, "deck.pptx")
        with open(src, "wb") as fh:
            fh.write(pptx)
        cmd = ["soffice", f"-env:UserInstallation=file://{work}/profile", "--headless", "--norestore", "--nolockcheck",
               "--convert-to", "pdf:impress_pdf_Export", "--outdir", work, src]
        subprocess.run(cmd, check=True, timeout=180, capture_output=True, env={**os.environ, "HOME": work})
        with open(os.path.join(work, "deck.pdf"), "rb") as fh:
            return fh.read()


# ---- the render check -------------------------------------------------------------------------------

def render_check(pdf: bytes, placed: list[dict], tolerance: float = 1.5) -> list[str]:
    """Every character LibreOffice drew must lie inside one of the slide's text areas.
    Returns what doesn't, as "slide 3: 'x' at (x, y)"."""
    import pypdfium2 as pdfium
    doc = pdfium.PdfDocument(pdf)
    out = []
    try:
        for i, page in enumerate(doc):
            pw, ph = page.get_size()
            sx, sy = W / pw, H / ph
            rects = placed[i]["rects"] if i < len(placed) else []
            tp = page.get_textpage()
            for k in range(tp.count_chars()):
                ch = tp.get_text_range(k, 1)
                if not ch.strip():
                    continue
                l, b, r, t = tp.get_charbox(k)
                x0, x1 = l * sx, r * sx
                y0, y1 = (ph - t) * sy, (ph - b) * sy
                inside = any(x0 >= rx - tolerance and x1 <= rx + rw + tolerance and y0 >= ry - tolerance and y1 <= ry + rh + tolerance
                             for rx, ry, rw, rh in rects)
                if not inside:
                    out.append(f"slide {i + 1}: {ch!r} at ({x0:.0f}, {y0:.0f})")
            tp.close()
            page.close()
    finally:
        doc.close()
    return out


# ---- the service ------------------------------------------------------------------------------------

def export(payload: dict) -> tuple[bytes, str]:
    """PPTX or PDF. Both are checked: the PPTX is drawn by LibreOffice and every character must be inside its box.
    A deck whose text doesn't fit is refused rather than exported with overflow."""
    from fastapi import HTTPException
    fmt = str(payload.get("format", "")).lower()
    if fmt not in ("pptx", "pdf"):
        raise HTTPException(status_code=400, detail="Unsupported export")
    deck = payload.get("deck") or {}
    fit = fit_deck(deck)
    if not fit["ok"]:
        bad = [f"Slide {i + 1}: {p}" for i, s in enumerate(fit["slides"]) for p in s["problems"]]
        raise HTTPException(status_code=422, detail={"message": "Some text doesn't fit its box", "problems": bad[:20]})
    data, placed = build_pptx(deck, payload.get("figures") or {}, payload.get("images") or {})
    pdf = pptx_to_pdf(data)
    outside = render_check(pdf, placed)
    if outside:
        raise HTTPException(status_code=422, detail={"message": "Text was drawn outside its box", "problems": outside[:20]})
    return (data if fmt == "pptx" else pdf), fmt
