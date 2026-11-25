#!/usr/bin/env bash
# Backs up the database and all media into ./backups/<timestamp>/
# Run from the project folder (Linux, macOS, WSL or Git Bash):   ./scripts/backup.sh
# Schedule daily on a Linux server with cron, e.g.:
#   15 3 * * *  cd /opt/leenax-studio && ./scripts/backup.sh >> backups/backup.log 2>&1
set -euo pipefail
cd "$(dirname "$0")/.."

stamp=$(date -u +%Y%m%d-%H%M%S)
dest="backups/$stamp"
mkdir -p "$dest"

echo "Dumping PostgreSQL..."
docker compose exec -T postgres pg_dump -U leenax -d leenax --format=custom > "$dest/leenax.dump"

echo "Archiving media storage..."
docker run --rm \
  -v leenax_storagedata:/data:ro \
  -v "$(pwd)/$dest":/backup \
  alpine tar czf /backup/storage.tar.gz -C /data .

# Keep the 14 most recent backups.
ls -1d backups/20* 2>/dev/null | sort | head -n -14 | xargs -r rm -rf

echo "Backup written to $dest"
