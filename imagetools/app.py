"""Leenax image tools: private background removal for the Thumbnail Studio.

Runs only inside the Docker network (no published port). No API keys, no
outside calls at runtime: the model is downloaded when the image is built.
"""
import io
import os

from fastapi import FastAPI, File, HTTPException, UploadFile
from fastapi.responses import Response
from PIL import Image, UnidentifiedImageError
from rembg import new_session, remove

MODEL = os.environ.get("REMBG_MODEL", "u2net")
MAX_BYTES = int(os.environ.get("MAX_IMAGE_BYTES", str(20 * 1024 * 1024)))
MAX_SIDE = int(os.environ.get("MAX_IMAGE_SIDE", "4096"))

app = FastAPI(title="Leenax image tools", docs_url=None, redoc_url=None, openapi_url=None)
_session = None


def session():
    global _session
    if _session is None:
        _session = new_session(MODEL)
    return _session


@app.get("/health")
def health():
    return {"status": "UP", "model": MODEL}


@app.post("/remove-background")
async def remove_background(file: UploadFile = File(...)):
    data = await file.read(MAX_BYTES + 1)
    if len(data) > MAX_BYTES:
        raise HTTPException(status_code=413, detail="Image too large")
    try:
        img = Image.open(io.BytesIO(data))
        img.load()
    except (UnidentifiedImageError, OSError):
        raise HTTPException(status_code=415, detail="Not an image")
    if max(img.size) > MAX_SIDE:
        img.thumbnail((MAX_SIDE, MAX_SIDE))
    out = remove(img.convert("RGBA"), session=session())
    buf = io.BytesIO()
    out.save(buf, format="PNG", optimize=True)
    return Response(content=buf.getvalue(), media_type="image/png")
