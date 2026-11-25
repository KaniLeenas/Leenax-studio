"""Run: pip install -r requirements.txt pytest httpx && pytest -q"""
import io

from fastapi.testclient import TestClient
from PIL import Image, ImageDraw

from app import app

client = TestClient(app)


def sample_png() -> bytes:
    img = Image.new("RGB", (320, 240), (40, 160, 70))
    ImageDraw.Draw(img).ellipse((100, 50, 220, 190), fill=(230, 40, 40))
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    return buf.getvalue()


def test_health():
    assert client.get("/health").json()["status"] == "UP"


def test_removes_background_to_transparent_png():
    res = client.post("/remove-background", files={"file": ("x.png", sample_png(), "image/png")})
    assert res.status_code == 200
    assert res.headers["content-type"] == "image/png"
    out = Image.open(io.BytesIO(res.content))
    assert out.mode == "RGBA"
    assert out.size == (320, 240)
    assert out.getpixel((2, 2))[3] < 40  # corner (background) is transparent


def test_rejects_non_images():
    res = client.post("/remove-background", files={"file": ("x.txt", b"hello", "text/plain")})
    assert res.status_code == 415
