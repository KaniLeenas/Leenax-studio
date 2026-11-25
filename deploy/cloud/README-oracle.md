# Leenax Studio on Oracle Cloud Always Free

This puts the whole studio (database, file storage, backend, AI jobs, video rendering) on a free
Oracle Cloud server, reachable over HTTPS from anywhere. You then use it in the browser or in the
Leenax Studio desktop app.

**The server:** one Ampere A1 virtual machine (ARM) with **2 OCPUs and 12 GB of memory** and a
100 GB disk. That's within Oracle's Always Free allowance, which in 2026 is 1,500 OCPU hours and
9,000 GB hours a month (2 OCPUs and 12 GB running all month) and 200 GB of block storage.

**The address:** with no domain of your own, it's `https://studio.<your-ip-with-dashes>.sslip.io`,
for example `https://studio.140-238-1-23.sslip.io`. sslip.io is a free public DNS service that
turns that name into your server's IP; nothing to sign up for. With your own domain it's
`https://studio.your-domain` and `https://media.your-domain`.

**HTTPS:** Caddy gets free Let's Encrypt certificates and renews them by itself.

Takes about an hour the first time, most of it waiting for the build.

## 1. Create the Oracle account

1. Sign up at <https://www.oracle.com/cloud/free/>. Oracle asks for a card to check who you are;
   an Always Free account isn't charged.
2. **Choose the home region carefully: it can't be changed, and free servers can only be made
   there.** Pick the one nearest you and your users, e.g. *India West (Mumbai)* or *India South
   (Hyderabad)* for Sri Lanka, *Australia East (Sydney)* for Sydney.

## 2. Create the server

In the Oracle Cloud console: **Compute → Instances → Create instance**.

| Setting | Choose |
|---|---|
| Name | leenax |
| Image | **Canonical Ubuntu 24.04** |
| Shape | **Ampere → VM.Standard.A1.Flex**, **2 OCPUs**, **12 GB memory** (it should say "Always Free-eligible") |
| Networking | Create a new virtual cloud network with a **public subnet**; **assign a public IPv4 address** |
| SSH keys | **Generate a key pair for me** → **Save private key** (keep this file safe; it is the only way in) |
| Boot volume | Specify a custom size: **100 GB** |

Click **Create**. If Oracle says **"Out of capacity"**, try another availability domain, or try
again later; free A1 capacity in busy regions comes and goes.

When it's running, copy its **Public IP address**.

## 3. Open ports 80 and 443

On the instance page: click the **subnet** → **Security** (or *Security Lists*) → the default
security list → **Add Ingress Rules**:

| Source CIDR | IP protocol | Destination port |
|---|---|---|
| 0.0.0.0/0 | TCP | 80 |
| 0.0.0.0/0 | TCP | 443 |

(Port 22 for SSH is already open. The setup script opens 80 and 443 in the server's own firewall too.)

## 4. Install the studio from your PC

Open **PowerShell** in `E:\Leenax Studio` and run (with your IP and key file):

```powershell
powershell -ExecutionPolicy Bypass -File deploy\cloud\deploy-to-server.ps1 -Server 140.238.1.23 -Key C:\Users\you\Downloads\ssh-key-2026-11-25.key
```

Add `-Domain studio.your-domain` if you have a domain and have pointed `studio.your-domain` and
`media.your-domain` (A records) at the server's IP.

It packs the project (never `.env`, backups or `node_modules`), uploads it, and runs
`deploy/cloud/setup-server.sh` on the server, which:

- installs Docker and automatic security updates, opens 80/443 in the server firewall, adds 4 GB swap;
- writes `.env` **the first time** with new random passwords, a new `LEENAX_MASTER_KEY`, HTTPS
  addresses, and memory limits that fit 12 GB;
- builds and starts everything, then waits until `https://studio…` answers.

At the end it prints the **setup token**.

## 5. First sign-in

1. Open the address it printed, e.g. `https://studio.140-238-1-23.sslip.io`, and create your owner
   account with the setup token.
2. Remove the token, on the server:
   `ssh -i <key> ubuntu@<ip>` then
   `sed -i 's/^SETUP_TOKEN=.*/SETUP_TOKEN=/' ~/leenax/.env && sudo bash ~/leenax/deploy/cloud/setup-server.sh`
3. Copy the backups and the server's `.env` to your PC (step 7). Its `LEENAX_MASTER_KEY` unlocks
   the AI keys you save in the studio.
4. **Desktop app:** tray menu → *Change server…* → the `https://studio…` address.
5. If you use YouTube/Facebook/TikTok publishing, add the new callback addresses
   (`https://studio…/api/v1/social/oauth/<google|meta|tiktok>/callback`) in each developer console.

## 6. Updating

Run the same `deploy-to-server.ps1` command again. The server keeps its `.env` and data; only the
program is rebuilt. Database updates run by themselves when the backend starts.

## 7. Backups

The server makes a backup every night at 03:15 (database and media) and keeps 14, in
`~/leenax/backups`. That's on the same server, so also copy them to your PC now and then:

```powershell
powershell -ExecutionPolicy Bypass -File deploy\cloud\get-backups.ps1 -Server 140.238.1.23 -Key C:\Users\you\Downloads\ssh-key.key
```

They go to `E:\Leenax Studio\server-backups\<date>`, with the server's settings as
`server-backups\server.env` the first time. **Keep `server.env` private** (it holds the passwords and
the master key) and out of shared or synced folders.

## Staying free

- Keep to **one A1 server with 2 OCPUs and 12 GB**, and **200 GB of disks in total**. More than the
  Always Free allowance on a free account gets the A1 servers disabled, then deleted after 30 days.
- Oracle may stop Always Free servers it sees as **idle for 7 days** (very low CPU, network and
  memory use together). Leenax keeps its database, Java backend and services running, so it
  normally stays above that; if Oracle emails you about it, start the server again from the
  console. Upgrading the account to *Pay As You Go* stops this, but then anything beyond the free
  allowance is charged, so set a budget alert first (Billing → Budgets).
- Don't create other paid resources (load balancers, extra disks, bigger shapes).

## Security

- Only ports 22 (SSH, key only), 80 and 443 are open. The database, Redis, the studio's own port
  and the media store listen only inside the server.
- `.env` is readable only by its owner and is never uploaded from your PC; the setup writes new
  random passwords for the server.
- Ubuntu installs security updates by itself.
- To allow SSH only from your own connection, change the port-22 rule's source from `0.0.0.0/0` to
  your IP (but your home IP may change).

## When something doesn't work

On the server (`ssh -i <key> ubuntu@<ip>`, then `cd ~/leenax`):

```bash
sudo docker compose -f docker-compose.yml -f deploy/cloud/docker-compose.cloud.yml ps
sudo docker compose -f docker-compose.yml -f deploy/cloud/docker-compose.cloud.yml logs --tail 50 caddy backend
```

| What you see | Usually |
|---|---|
| The address doesn't open at all | Ports 80/443 missing from the security list (step 3) |
| Caddy log: certificate errors | The address doesn't point at this server yet (own domain: check the A records) |
| Build stops with "Killed" | Out of memory during the build; run the setup again (swap helps) |
| Upload fails: "UNPROTECTED PRIVATE KEY" | The script fixes the key's permissions; run it again from your own Windows account |
| "Out of capacity" when creating the server | Another availability domain, or try later |
