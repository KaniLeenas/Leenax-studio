#!/usr/bin/env bash
# Copies Leenax Studio from this computer to your cloud server (AWS or Oracle) and installs or
# updates it there. For Ubuntu, other Linux, macOS or WSL. Run it from the Leenax folder:
#
#   bash deploy/cloud/deploy-to-server.sh <server-ip> <key-file> [studio.your-domain]
#
# Sent to the server: the project files only. Never sent: .env, backups, the desktop app,
# node_modules, build output. The server keeps its own .env (passwords, master key) between updates.
set -euo pipefail
SERVER="${1:?usage: bash deploy/cloud/deploy-to-server.sh <server-ip> <key-file> [domain]}"
KEY="${2:?usage: bash deploy/cloud/deploy-to-server.sh <server-ip> <key-file> [domain]}"
DOMAIN="${3:-}"
USER_NAME="${LEENAX_SSH_USER:-ubuntu}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

[[ -f "$ROOT/docker-compose.yml" ]] || { echo "Run it from the Leenax folder." >&2; exit 1; }
[[ -f "$KEY" ]] || { echo "Key file not found: $KEY" >&2; exit 1; }
[[ -z "$DOMAIN" || "$DOMAIN" =~ ^[A-Za-z0-9.-]+$ ]] || { echo "That domain doesn't look right: $DOMAIN" >&2; exit 1; }
chmod 600 "$KEY" 2>/dev/null || true   # ssh refuses a key others can read

ARCHIVE="$(mktemp -t leenax-XXXX).tar.gz"
trap 'rm -f "$ARCHIVE"' EXIT
echo "Packing the project..."
tar -czf "$ARCHIVE" -C "$ROOT" \
  --exclude=./.env --exclude=./backups --exclude="./Claude outputs" --exclude=./server-backups --exclude=./desktop \
  --exclude=node_modules --exclude=dist --exclude=target --exclude=build --exclude=.gradle \
  --exclude=__pycache__ --exclude=.pytest_cache --exclude=.git --exclude='*.tsbuildinfo' .
du -h "$ARCHIVE" | cut -f1

SSH=(-i "$KEY" -o StrictHostKeyChecking=accept-new -o ServerAliveInterval=30)
echo "Uploading to $SERVER..."
scp "${SSH[@]}" "$ARCHIVE" "$USER_NAME@$SERVER:/tmp/leenax-upload.tgz"
echo "Installing on the server (the first time takes 30-50 minutes)..."
ssh "${SSH[@]}" -t "$USER_NAME@$SERVER" \
  "set -e; mkdir -p ~/leenax; tar -xzf /tmp/leenax-upload.tgz -C ~/leenax; rm /tmp/leenax-upload.tgz; cd ~/leenax; sudo bash deploy/cloud/setup-server.sh $DOMAIN"
echo "Done."
