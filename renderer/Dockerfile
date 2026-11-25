# Leenax render service (Remotion + headless Chrome). Built from the repository root:
#   docker build -f renderer/Dockerfile .
FROM node:22-bookworm-slim

# Libraries Chrome Headless Shell needs (from Remotion's Docker guide), plus fonts for emoji/fallback.
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
       libnss3 libdbus-1-3 libatk1.0-0 libgbm-dev libasound2 libxrandr2 libxkbcommon-dev libxfixes3 \
       libxcomposite1 libxdamage1 libatk-bridge2.0-0 libpango-1.0-0 libcairo2 libcups2 \
       fonts-noto-color-emoji ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --create-home --home /home/render render

WORKDIR /app
COPY renderer/package.json renderer/package-lock.json ./
RUN npm ci --omit=dev

# The composition is shared with the editor preview, so exports match what you see.
COPY frontend/src/remotion ./composition
COPY renderer/*.mjs ./

RUN node -e "import('@remotion/renderer').then(m => m.ensureBrowser())" \
    && node bundle.mjs \
    && chown -R render /app

USER render
ENV PORT=7100 BUNDLE_DIR=/app/bundle OUTPUT_DIR=/tmp/leenax-renders NODE_ENV=production
EXPOSE 7100
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s CMD node -e "fetch('http://127.0.0.1:7100/health').then(r=>process.exit(r.ok?0:1),()=>process.exit(1))"
CMD ["node", "server.mjs"]
