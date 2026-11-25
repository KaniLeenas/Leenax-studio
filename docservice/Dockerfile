FROM python:3.12-slim-bookworm

# Tesseract with English and Tamil models, LibreOffice (Writer + Impress only) for
# Word/PowerPoint/OpenDocument files, and fonts so Tamil documents convert correctly. Carlito and
# Liberation Mono have the same widths as Calibri and Courier New, so slide text is measured as PowerPoint draws it.
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
       tesseract-ocr tesseract-ocr-eng tesseract-ocr-tam \
       libreoffice-writer-nogui libreoffice-impress-nogui \
       fonts-noto-core fonts-dejavu-core fonts-liberation2 fonts-crosextra-carlito \
    && rm -rf /var/lib/apt/lists/*

ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt && useradd --system --create-home --uid 10002 docs
COPY app.py exports.py slides.py ./
USER docs
EXPOSE 7200
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:7200/health')"
# One worker: OCR and LibreOffice are CPU-heavy, and the backend sends one document at a time.
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "7200", "--workers", "1"]
