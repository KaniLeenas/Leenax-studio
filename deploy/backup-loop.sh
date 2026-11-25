#!/bin/sh
# Automatic backups (docker compose service "backup").
# Every day at BACKUP_TIME (in TZ) it writes /backups/<timestamp>/ with:
#   leenax.dump        PostgreSQL custom-format dump (pg_restore)
#   storage.tar.gz     every media file (SeaweedFS data folder)
# keeps the newest BACKUP_KEEP folders, and records the result in /backups/latest.json
# (the System page in Settings reads it).
# Secrets in the dump (API keys, social tokens, MFA secret) are encrypted with
# LEENAX_MASTER_KEY, which is NOT in the backup: keep a copy of your .env somewhere safe.
set -u

BACKUP_TIME="${BACKUP_TIME:-03:15}"
BACKUP_KEEP="${BACKUP_KEEP:-14}"
DEST=/backups

log() { echo "$(date '+%Y-%m-%d %H:%M:%S') $*"; }

write_status() { # ok folder dbBytes mediaBytes error
  err=$(printf '%s' "$5" | sed 's/"/\\"/g')
  cat > "$DEST/latest.json.tmp" <<JSON
{"ok": $1, "finishedAt": "$(date -u +%Y-%m-%dT%H:%M:%SZ)", "folder": "$2", "databaseBytes": $3, "mediaBytes": $4, "error": "$err"}
JSON
  mv "$DEST/latest.json.tmp" "$DEST/latest.json"
}

run_backup() {
  stamp=$(date -u +%Y%m%d-%H%M%S)
  dir="$DEST/$stamp"
  mkdir -p "$dir"
  log "Backup $stamp: database"
  if ! pg_dump -h postgres -U leenax -d leenax --format=custom --file="$dir/leenax.dump"; then
    write_status false "$stamp" null null "pg_dump failed"; log "Database dump failed"; return 1
  fi
  log "Backup $stamp: media"
  if ! tar czf "$dir/storage.tar.gz" -C /media-data .; then
    write_status false "$stamp" "$(stat -c %s "$dir/leenax.dump")" null "Archiving media failed"; log "Media archive failed"; return 1
  fi
  # A quick check that the dump can be read back.
  if ! pg_restore --list "$dir/leenax.dump" > /dev/null; then
    write_status false "$stamp" null null "The database dump could not be read back"; return 1
  fi
  write_status true "$stamp" "$(stat -c %s "$dir/leenax.dump")" "$(stat -c %s "$dir/storage.tar.gz")" ""
  # Keep the newest BACKUP_KEEP folders.
  ls -1d "$DEST"/20* 2>/dev/null | sort -r | tail -n "+$((BACKUP_KEEP + 1))" | while read -r old; do rm -rf "$old"; done
  log "Backup $stamp finished"
}

seconds_until() { # HH:MM -> seconds until the next occurrence
  now=$(date +%s)
  target=$(date -d "$(date +%Y-%m-%d) $1" +%s 2>/dev/null || date -D '%Y-%m-%d %H:%M' -d "$(date +%Y-%m-%d) $1" +%s)
  [ "$target" -le "$now" ] && target=$((target + 86400))
  echo $((target - now))
}

# Catch up straight away if the newest backup is older than a day (e.g. the PC was off).
newest=$(ls -1d "$DEST"/20* 2>/dev/null | sort | tail -n 1)
if [ -z "$newest" ] || [ -n "$(find "$newest" -maxdepth 0 -mmin +1500 2>/dev/null)" ]; then
  sleep 60   # let PostgreSQL finish starting
  run_backup
fi

while true; do
  wait=$(seconds_until "$BACKUP_TIME")
  log "Next backup in $((wait / 3600))h $((wait % 3600 / 60))m"
  sleep "$wait"
  run_backup
done
