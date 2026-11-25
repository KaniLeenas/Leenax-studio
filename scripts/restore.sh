#!/usr/bin/env bash
# Restores a backup made by the backup service or scripts/backup.sh.
#   ./scripts/restore.sh backups/20261119-214500
# Replaces the current database AND all media. Stop and think before running it.
set -euo pipefail
cd "$(dirname "$0")/.."
src="${1:?Give the backup folder, e.g. backups/20261119-214500}"
[ -f "$src/leenax.dump" ] && [ -f "$src/storage.tar.gz" ] || { echo "$src has no leenax.dump / storage.tar.gz"; exit 1; }
read -r -p "This replaces ALL current data with $src. Type RESTORE to continue: " ok
[ "$ok" = "RESTORE" ] || { echo "Cancelled."; exit 1; }

echo "Stopping the app..."
docker compose stop backend renderer imagetools web backup storage 2>/dev/null || true

echo "Restoring the database..."
docker compose up -d postgres
sleep 5
docker compose exec -T postgres dropdb -U leenax --if-exists leenax
docker compose exec -T postgres createdb -U leenax leenax
docker compose exec -T postgres pg_restore -U leenax -d leenax --no-owner < "$src/leenax.dump"

echo "Restoring media..."
docker run --rm -v leenax_storagedata:/data -v "$(pwd)/$src":/backup:ro alpine sh -c "rm -rf /data/* && tar xzf /backup/storage.tar.gz -C /data"

echo "Starting everything..."
docker compose up -d
echo "Done. Use the same LEENAX_MASTER_KEY as when the backup was made, or saved keys and sign-ins can't be read."
