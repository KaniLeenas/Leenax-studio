FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 U2NET_HOME=/models
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt \
    && python -c "from rembg import new_session; new_session('u2net')" \
    && useradd --system --uid 10001 tools && chown -R tools /models
COPY app.py .
USER tools
EXPOSE 7000
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:7000/health')"
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "7000", "--workers", "1"]
