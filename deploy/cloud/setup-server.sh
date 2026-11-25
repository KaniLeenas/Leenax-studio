#!/usr/bin/env bash
# Sets up Leenax Studio on a cloud server running Ubuntu 24.04: Oracle Cloud Always Free (ARM)
# or AWS EC2 (x86 or ARM). See README-oracle.md / README-aws.md beside this script.
# Run it on the server, from the Leenax folder:
#
#   sudo bash deploy/cloud/setup-server.sh                      # address from the server's IP (sslip.io)
#   sudo bash deploy/cloud/setup-server.sh studio.example.com   # your own domain (and media.example.com)
#
# Running it again is safe: it keeps .env (your passwords and the master key) and only rebuilds
# and restarts the studio with the files that are here now. That is how updates are installed.
#
# What it does: installs Docker, opens ports 80 and 443 in the server's firewall, adds swap,
# turns on automatic security updates, writes .env with new random passwords the first time,
# and starts the studio with HTTPS.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="$HERE/.env"
COMPOSE=(docker compose -f "$HERE/docker-compose.yml" -f "$HERE/deploy/cloud/docker-compose.cloud.yml" --env-file "$ENV_FILE")

say() { printf '\n\033[1m%s\033[0m\n' "$*"; }
die() { printf '\n\033[31m%s\033[0m\n' "$*" >&2; exit 1; }

# ---- .env ----------------------------------------------------------------------------------------------

rand_hex() { openssl rand -hex "$1"; }

set_env() { # set_env KEY VALUE FILE : replaces KEY=... or a commented "# KEY=..." line, else appends
  local key="$1" value="$2" file="$3"
  if grep -qE "^#? ?${key}=" "$file"; then
    # values are hex, base64 or host names: no characters that need escaping beyond / and &
    local escaped
    escaped=$(printf '%s' "$value" | sed -e 's/[\/&|]/\\&/g')
    sed -i -E "0,/^#? ?${key}=.*/s|^#? ?${key}=.*|${key}=${escaped}|" "$file"
  else
    printf '%s=%s\n' "$key" "$value" >> "$file"
  fi
}

# hosts_for DOMAIN-OR-IP : prints "STUDIO_HOST MEDIA_HOST"
#   1.2.3.4                    -> studio.1-2-3-4.sslip.io      media.1-2-3-4.sslip.io
#   leenax-studio.duckdns.org  -> leenax-studio.duckdns.org    media.leenax-studio.duckdns.org
#   studio.example.com         -> studio.example.com           media.example.com
hosts_for() {
  local target="$1"
  if [[ "$target" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    local dashed="${target//./-}"
    echo "studio.${dashed}.sslip.io media.${dashed}.sslip.io"
  else
    echo "${target} media.${target#studio.}"
  fi
}

write_env() { # write_env DIR STUDIO_HOST MEDIA_HOST : first-time .env with new secrets
  local dir="$1" studio="$2" media="$3" file="$1/.env"
  cp "$dir/.env.example" "$file"
  chmod 600 "$file"
  set_env POSTGRES_PASSWORD "$(rand_hex 24)" "$file"
  set_env REDIS_PASSWORD "$(rand_hex 24)" "$file"
  set_env S3_SECRET_KEY "$(rand_hex 24)" "$file"
  set_env LEENAX_MASTER_KEY "$(openssl rand -base64 32)" "$file"
  set_env SETUP_TOKEN "$(rand_hex 16)" "$file"
  set_env RENDERER_TOKEN "$(rand_hex 32)" "$file"
  set_env PUBLIC_BASE_URL "https://$studio" "$file"
  set_env MEDIA_PUBLIC_URL "https://$media" "$file"
  set_env COOKIE_SECURE true "$file"
  set_env STUDIO_HOST "$studio" "$file"
  set_env MEDIA_HOST "$media" "$file"
  # leave room for the database, the backend and Caddy: 8 GB servers (AWS m7i-flex.large) get less
  local mem_gb
  mem_gb=$(awk '/MemTotal/{printf "%d", $2/1024/1024 + 0.5}' /proc/meminfo)
  if [[ "${LEENAX_MEM_GB:-$mem_gb}" -le 9 ]]; then
    set_env RENDERER_MEMORY 2560m "$file"
    set_env DOCSERVICE_MEMORY 1g "$file"
  else
    set_env RENDERER_MEMORY 3g "$file"
    set_env DOCSERVICE_MEMORY 1536m "$file"
  fi
}

# For tests: bash setup-server.sh --write-env-only DIR IP-OR-DOMAIN
if [[ "${1:-}" == "--write-env-only" ]]; then
  read -r s m < <(hosts_for "$3")
  write_env "$2" "$s" "$m"
  exit 0
fi

# ---- checks ----------------------------------------------------------------------------------------------

[[ $EUID -eq 0 ]] || die "Run it with sudo: sudo bash deploy/cloud/setup-server.sh"
[[ -f "$HERE/docker-compose.yml" ]] || die "Run it from the Leenax folder (docker-compose.yml not found in $HERE)."
. /etc/os-release
[[ "${ID:-}" == "ubuntu" ]] || die "This script is for Ubuntu (found ${PRETTY_NAME:-unknown})."
MEM_GB=$(awk '/MemTotal/{printf "%d", $2/1024/1024 + 0.5}' /proc/meminfo)
[[ "$MEM_GB" -ge 7 ]] || die "This server has ${MEM_GB} GB of memory; Leenax needs at least 8 GB."

# ---- packages ---------------------------------------------------------------------------------------------

say "1/6 Installing Docker and security updates"
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq docker.io docker-compose-v2 openssl curl unattended-upgrades iptables-persistent >/dev/null
systemctl enable --now docker >/dev/null
dpkg-reconfigure -f noninteractive unattended-upgrades >/dev/null 2>&1 || true
SUDO_USER_NAME="${SUDO_USER:-ubuntu}"
id "$SUDO_USER_NAME" >/dev/null 2>&1 && usermod -aG docker "$SUDO_USER_NAME" || true

# ---- firewall -----------------------------------------------------------------------------------------------
# Oracle's Ubuntu images block everything but SSH in iptables, as well as in the cloud's own
# security list (AWS: the security group). Open 80 (certificates, redirect) and 443 (the studio)
# here; the guides cover the cloud side.

say "2/6 Opening ports 80 and 443 in the server's firewall"
for port in 80 443; do
  iptables -C INPUT -p tcp --dport "$port" -m state --state NEW -j ACCEPT 2>/dev/null \
    || iptables -I INPUT 5 -p tcp --dport "$port" -m state --state NEW -j ACCEPT
done
iptables -C INPUT -p udp --dport 443 -j ACCEPT 2>/dev/null || iptables -I INPUT 5 -p udp --dport 443 -j ACCEPT
netfilter-persistent save >/dev/null

# ---- swap -----------------------------------------------------------------------------------------------------

say "3/6 Adding 4 GB of swap (headroom for builds and video renders)"
if ! swapon --show | grep -q /swapfile; then
  fallocate -l 4G /swapfile && chmod 600 /swapfile && mkswap /swapfile >/dev/null && swapon /swapfile
  grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

# ---- .env -------------------------------------------------------------------------------------------------------

say "4/6 Settings (.env)"
FIRST_RUN=false
if [[ -f "$ENV_FILE" ]]; then
  echo "Keeping the existing .env (passwords and master key unchanged)."
else
  FIRST_RUN=true
  TARGET="${1:-}"
  if [[ -z "$TARGET" ]]; then
    TARGET="$(curl -fsS --max-time 10 https://checkip.amazonaws.com | tr -d '[:space:]')" \
      || die "Couldn't find this server's public IP. Run it again with your domain or IP: sudo bash $0 <domain-or-ip>"
  fi
  read -r STUDIO MEDIA < <(hosts_for "$TARGET")
  write_env "$HERE" "$STUDIO" "$MEDIA"
  chown "$SUDO_USER_NAME" "$ENV_FILE" 2>/dev/null || true
  echo "Wrote .env with new random passwords. Studio: https://$STUDIO  Media: https://$MEDIA"
fi
STUDIO="$(grep -E '^STUDIO_HOST=' "$ENV_FILE" | cut -d= -f2-)"
MEDIA="$(grep -E '^MEDIA_HOST=' "$ENV_FILE" | cut -d= -f2-)"
[[ -n "$STUDIO" && -n "$MEDIA" ]] || die ".env has no STUDIO_HOST / MEDIA_HOST. Add them (see deploy/cloud/README-oracle.md or README-aws.md)."

# the address must point at this server before Caddy can get a certificate
if command -v getent >/dev/null; then
  MYIP="$(curl -fsS --max-time 10 https://checkip.amazonaws.com | tr -d '[:space:]' || true)"
  for h in "$STUDIO" "$MEDIA"; do
    RES="$(getent ahostsv4 "$h" | awk 'NR==1{print $1}')"
    if [[ -n "$MYIP" && "$RES" != "$MYIP" ]]; then
      say "Warning: $h points at '${RES:-nothing}', not this server ($MYIP). HTTPS will work once its DNS record points here."
    fi
  done
fi

# ---- build and start ----------------------------------------------------------------------------------------------

say "5/6 Building and starting the studio (the first time takes 20–40 minutes on this server)"
mkdir -p "$HERE/backups"
"${COMPOSE[@]}" up -d --build --remove-orphans

say "6/6 Waiting for https://$STUDIO"
ok=false
for _ in $(seq 1 60); do
  if curl -fsS --max-time 10 "https://$STUDIO/api/v1/auth/status" >/dev/null 2>&1; then ok=true; break; fi
  sleep 10
done

if $ok; then
  say "Leenax Studio is running at https://$STUDIO"
else
  say "The studio didn't answer at https://$STUDIO yet."
  echo "Check:  sudo ${COMPOSE[*]} ps        and   sudo ${COMPOSE[*]} logs --tail 50 caddy backend"
  echo "Usual causes: ports 80/443 not open in the Oracle security list, or the DNS record not pointing here."
fi

if $FIRST_RUN; then
  if [[ -t 1 ]]; then
    TOKEN="$(grep -E '^SETUP_TOKEN=' "$ENV_FILE" | cut -d= -f2-)"
    TOKEN_LINE="with this setup token:
       $TOKEN"
  else
    # not on a terminal (e.g. a GitHub Actions deploy): keep the token out of logs
    TOKEN_LINE="with the setup token. See it on the server (never in deploy logs) with:
       sudo grep '^SETUP_TOKEN=' $ENV_FILE"
  fi
  cat <<MSG

Next:
  1. Open https://$STUDIO and create your owner account $TOKEN_LINE
  2. Then remove the token:  sed -i 's/^SETUP_TOKEN=.*/SETUP_TOKEN=/' $ENV_FILE  and run this script again.
  3. Copy $ENV_FILE to your computer and keep it safe. Its LEENAX_MASTER_KEY unlocks your saved AI keys;
     without it, backups can't be fully restored. (deploy/cloud/get-backups.sh does this.)
  4. In the Leenax Studio desktop app, the server address is:  https://$STUDIO
MSG
fi
