# Automatic deploys from GitHub (CI/CD)

After this is set up, every push to `main` runs the checks (`.github/workflows/ci.yml`), and when
they pass, `.github/workflows/deploy.yml` copies the code to your server over SSH and runs
`deploy/cloud/setup-server.sh` there. The studio is rebuilt and restarted; its `.env`, database,
files and backups stay on the server.

Example values below: repository `KaniLeenas/Leenax-studio`, address `leenax-studio.duckdns.org`,
AWS server with Elastic IP `3.110.20.5`.

## 1. The server

Create it as in `README-aws.md`, steps 1–3: Ubuntu 24.04, m7i-flex.large, SSH/HTTP/HTTPS
allowed, 60 GiB, **with an Elastic IP**. Keep the `.pem` key file.

## 2. The address (DuckDNS)

At <https://www.duckdns.org>, signed in: add the domain **leenax-studio** if it isn't there, type
the Elastic IP in its **current ip** box and click **update ip**. `leenax-studio.duckdns.org` and
everything under it (the studio uses `media.leenax-studio.duckdns.org` for files) now point at
the server. The Elastic IP doesn't change, so this needs no updater.

## 3. The server's identity (for SSH from GitHub)

So GitHub only ever talks to *your* server, it is given the server's SSH host key. In the server's
terminal (EC2 → Instances → leenax → **Connect → EC2 Instance Connect**), run:

```bash
echo "leenax-studio.duckdns.org $(cut -d' ' -f1,2 /etc/ssh/ssh_host_ed25519_key.pub)"
```

Copy the one line it prints. (It's a public key, not a secret.)

## 4. GitHub settings

In the repository: **Settings → Secrets and variables → Actions**.

| Kind | Name | Value |
|---|---|---|
| Secret | `SERVER_SSH_KEY` | the whole `.pem` file (open it in Notepad, copy everything from `-----BEGIN` to `-----END ...-----`) |
| Secret | `SERVER_KNOWN_HOSTS` | the line from step 3 |
| Variable | `SERVER_HOST` | `leenax-studio.duckdns.org` |
| Variable | `LEENAX_DOMAIN` | `leenax-studio.duckdns.org` |

Then **Settings → Environments → New environment → `production`**. Optional: tick *Required
reviewers* and add yourself, so each deploy waits for your click.

Protect `main` (**Settings → Branches → Add rule → main**): anyone who can push to `main` can
change what runs on your server.

**Public or private?** The repository is public now: anyone can read the code (there are no
passwords or keys in it; those live only in the server's `.env` and in GitHub's encrypted
secrets). To keep the code to yourself, **Settings → General → Change visibility → Private**.
Private repositories get a monthly allowance of free Actions minutes; public ones don't use it.

## 5. Push the code

In a terminal (Git Bash, or WSL, or the VS Code terminal) in the Leenax folder:

```bash
git init -b main
git add -A
git commit -m "Leenax Studio"
git remote add origin https://github.com/KaniLeenas/Leenax-studio.git
git push -u origin main
```

`.gitignore` keeps `.env`, backups, `node_modules`, build output, `.pem`/`.key` files and
"Claude outputs" out of git.

Watch **Actions**: `ci` runs first, then `deploy`. **The first deploy takes 30–50 minutes**
(it installs Docker and builds everything); later ones take a few minutes.

## 6. First sign-in

1. In the server's terminal: `sudo grep '^SETUP_TOKEN=' ~/leenax/.env` (the deploy never prints it).
2. Open <https://leenax-studio.duckdns.org> and create your owner account with that token.
3. Remove the token:
   `sed -i 's/^SETUP_TOKEN=.*/SETUP_TOKEN=/' ~/leenax/.env && sudo bash ~/leenax/deploy/cloud/setup-server.sh`
4. Desktop app: tray menu → *Change server…* → `https://leenax-studio.duckdns.org`.
5. Back up: `bash deploy/cloud/get-backups.sh leenax-studio.duckdns.org ~/leenax.pem` (from
   Ubuntu/WSL; see `README-aws.md`, step 7). It also saves the server's `.env`, which holds the
   master key.

## After that

- **Change something, push to `main`, and it's live** once the checks pass.
- **Deploy without waiting for the checks:** Actions → deploy → **Run workflow**.
- **A failed check means no deploy**; the server keeps running the last good version.
- **See what happened:** the deploy run's log; on the server,
  `cd ~/leenax && sudo docker compose -f docker-compose.yml -f deploy/cloud/docker-compose.cloud.yml logs --tail 50 backend caddy`.
- The Windows desktop installer is built by `.github/workflows/desktop.yml` when `desktop/` changes
  (download it from that run's artifacts).
