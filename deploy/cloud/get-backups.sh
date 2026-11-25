#!/usr/bin/env bash
# Copies the server's newest backups to this computer, into server-backups/<date> in the Leenax
# folder. For Ubuntu, other Linux, macOS or WSL. Run it weekly:
#
#   bash deploy/cloud/get-backups.sh <server-ip> <key-file>
#
# The first time it also copies the server's .env as server-backups/server.env. Keep that file
# private: it holds the passwords and LEENAX_MASTER_KEY, needed to restore with your saved AI keys.
set -euo pipefail
SERVER="${1:?usage: bash deploy/cloud/get-backups.sh <server-ip> <key-file>}"
KEY="${2:?usage: bash deploy/cloud/get-backups.sh <server-ip> <key-file>}"
USER_NAME="${LEENAX_SSH_USER:-ubuntu}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DEST="$ROOT/server-backups/$(date +%F)"
mkdir -p "$DEST"
chmod 600 "$KEY" 2>/dev/null || true
SSH=(-i "$KEY" -o StrictHostKeyChecking=accept-new)
T="$USER_NAME@$SERVER"

echo "Copying backups from $SERVER..."
# the backup service writes as root: make a readable copy of the newest three first
ssh "${SSH[@]}" "$T" "sudo rm -rf /tmp/leenax-backups && sudo mkdir -p /tmp/leenax-backups && cd ~/leenax/backups && ls -1d */ 2>/dev/null | tail -n 3 | xargs -r -I{} sudo cp -r {} /tmp/leenax-backups/ && sudo chown -R $USER_NAME /tmp/leenax-backups"
scp "${SSH[@]}" -r "$T:/tmp/leenax-backups/*" "$DEST/"
ssh "${SSH[@]}" "$T" "rm -rf /tmp/leenax-backups"

ENV_COPY="$ROOT/server-backups/server.env"
if [[ ! -f "$ENV_COPY" ]]; then
  (umask 077; scp "${SSH[@]}" "$T:~/leenax/.env" "$ENV_COPY")
  echo "Saved the server's settings as $ENV_COPY (private: passwords and master key)."
fi
echo "Backups saved in $DEST"
