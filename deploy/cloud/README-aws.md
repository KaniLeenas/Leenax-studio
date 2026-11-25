# Leenax Studio on AWS (Free plan)

This puts the whole studio on one Amazon EC2 server with HTTPS, reachable from anywhere in the
browser or the Leenax Studio desktop app. It uses the same scripts as the Oracle guide.

## How "free" works on AWS (read this first)

- New AWS accounts can choose the **Free plan**: US$100 of credits at sign-up, and up to US$100
  more for trying a few services. **You are not charged on the Free plan.**
- The Free plan ends **6 months after sign-up or when the credits are used up, whichever is first.**
  Then AWS **closes the account**. Your server and data are kept for 90 days, but you can only get
  them back by upgrading to a paid plan.
- The only free-plan server big enough for Leenax is **m7i-flex.large** (2 vCPU, 8 GB). Running all
  day it costs about US$0.10 an hour, so with the disk and IP address roughly **US$80 a month of
  credits**: about **5 weeks on $100, or about 10 weeks on $200**. Check the price shown when you
  create the server and the *Credits* page in the Billing console.
- So: **copy the backups to your PC every week** (step 7). When the credits run low, either upgrade
  and pay, or move to the free Oracle server (`README-oracle.md`) and restore the backup there.

## 1. Create the AWS account

1. Sign up at <https://aws.amazon.com/free/> and choose the **Free plan** (not Paid). AWS asks for a
   card to check who you are; the Free plan doesn't charge it.
2. In the console's top-right corner choose the **region** nearest you and your users, e.g.
   **Asia Pacific (Mumbai)** for Sri Lanka or **Asia Pacific (Sydney)** for Sydney. Create everything
   below in that region.

## 2. Create the server

**EC2 → Instances → Launch instances**:

| Setting | Choose |
|---|---|
| Name | leenax |
| Application and OS image | **Ubuntu Server 24.04 LTS**, architecture **64-bit (x86)** |
| Instance type | **m7i-flex.large** (shows "Free tier eligible") |
| Key pair | **Create new key pair** → RSA, **.pem** → it downloads; keep it safe, it is the only way in |
| Network settings | **Allow SSH**, **Allow HTTPS**, **Allow HTTP** traffic from the internet |
| Configure storage | **60 GiB, gp3** |

Click **Launch instance**.

### Give it a fixed IP (Elastic IP)

The studio's address is made from the server's IP. A normal EC2 IP changes if the server is
stopped and started, which would change the address. So: **EC2 → Elastic IPs → Allocate Elastic IP
address → Allocate**, then **Actions → Associate Elastic IP address** → choose the *leenax*
instance. Use this IP from now on. (While it's attached to a running server it costs the same as
the normal public IP, from your credits.)

## 3. Ports

You opened 22, 80 and 443 in step 2. To check later: the instance → **Security** → its security
group → *Inbound rules* should list SSH (22), HTTP (80) and HTTPS (443).

## 4. Install the studio (Ubuntu terminal)

Two ways; pick one. Both end the same: the server builds and starts Leenax with HTTPS.

### Way A: only the browser (EC2 Instance Connect)

1. **Put the package in S3.** Console → **S3 → Create bucket** (any name, e.g. `leenax-upload-kani`,
   same region, keep *Block all public access* on) → open it → **Upload** →
   `leenax-server.tar.gz` (in `E:\Leenax Studio\Claude outputs`).
2. Select the uploaded file → **Actions → Share with a presigned URL** → 60 minutes → **Create** →
   the link is copied.
3. **Open the server's terminal:** EC2 → Instances → *leenax* → **Connect → EC2 Instance Connect →
   Connect**. A Ubuntu terminal opens in the browser.
4. Paste these, putting your link between the quotes:

   ```bash
   curl -fL -o leenax.tar.gz "PASTE-THE-PRESIGNED-LINK-HERE"
   mkdir -p ~/leenax && tar -xzf leenax.tar.gz -C ~/leenax && rm leenax.tar.gz
   cd ~/leenax && tmux new -s setup 'sudo bash deploy/cloud/setup-server.sh; read -p "Press Enter to close"'
   ```

   `tmux` keeps the setup running if the browser tab disconnects during the long build: connect
   again and type `tmux attach -t setup` to see it. With your own domain, put it after
   `setup-server.sh` (e.g. `setup-server.sh studio.your-domain`).
5. Afterwards delete the file from S3 (it holds no passwords, but it isn't needed any more).

### Way B: from a Ubuntu terminal on your computer (Ubuntu, WSL or macOS)

From the Leenax folder (on Windows with WSL: `cd "/mnt/e/Leenax Studio"`), with your Elastic IP and
the .pem file:

```bash
bash deploy/cloud/deploy-to-server.sh 3.110.20.5 ~/leenax.pem
```

(Copy the .pem into your Linux home first, e.g. `cp /mnt/c/Users/you/Downloads/leenax.pem ~/`;
ssh refuses keys on Windows drives.) Add a third argument `studio.your-domain` if you have a domain.
It uploads the project (never `.env`, backups or `node_modules`) and runs the same setup.

### What the setup does

`deploy/cloud/setup-server.sh` installs Docker and automatic security updates, adds 4 GB swap, writes
a new `.env` with random passwords and memory limits that fit 8 GB, sets up HTTPS with Caddy, and
starts the studio. **The first build takes 30–50 minutes** on this server; keep the terminal open.
At the end it prints the address, e.g. `https://studio.3-110-20-5.sslip.io`, and the **setup token**.

## 5. First sign-in

1. Open the address and create your owner account with the setup token.
2. Remove the token: in the server's terminal (EC2 Instance Connect, or `ssh -i <pem> ubuntu@<ip>`) run
   `sed -i 's/^SETUP_TOKEN=.*/SETUP_TOKEN=/' ~/leenax/.env && sudo bash ~/leenax/deploy/cloud/setup-server.sh`
3. **Desktop app:** tray menu → *Change server…* → the `https://studio…` address.
4. If you use YouTube/Facebook/TikTok publishing, add the new callback addresses
   (`https://studio…/api/v1/social/oauth/<google|meta|tiktok>/callback`) in each developer console.

## 6. Updating

- **Way A:** upload the new `leenax-server.tar.gz` to S3, make a new presigned link, then in the
  server's terminal run the same three lines as in step 4. The server keeps its `.env` and data.
- **Way B:** run `bash deploy/cloud/deploy-to-server.sh <ip> <pem>` again.

## 7. Backups (every week)

The server makes a backup every night at 03:15 into `~/leenax/backups` (keeps 14). **On the Free
plan, copies outside the server are what you keep when the account closes**, so copy them to your
computer every week, from a Ubuntu terminal (Ubuntu, WSL or macOS), in the Leenax folder:

```bash
bash deploy/cloud/get-backups.sh 3.110.20.5 ~/leenax.pem
```

They go to `server-backups/<date>`, plus the server's settings as `server-backups/server.env` the
first time (keep it private: passwords and the master key).

## Keeping the credits for the studio

- Run only this one server. Other services (load balancers, RDS databases, NAT gateways, more
  disks) also use credits.
- Stopping the server stops the hourly charge, but the disk and the Elastic IP still use a little.
  If you stop it, the address stays the same thanks to the Elastic IP.
- Watch **Billing and Cost Management → Credits**. AWS also emails as the credits run low.

## Security

- Open to the internet: 22 (SSH, key only), 80 and 443. Everything else listens only inside the server.
- `.env` is created on the server, readable only by its owner, and never uploaded from your PC.
- To allow SSH only from your connection, change the SSH rule's source to *My IP* (your home IP may change).

## When something doesn't work

```bash
# in EC2 Instance Connect, or:  ssh -i <pem> ubuntu@<ip>
cd ~/leenax
sudo docker compose -f docker-compose.yml -f deploy/cloud/docker-compose.cloud.yml ps
sudo docker compose -f docker-compose.yml -f deploy/cloud/docker-compose.cloud.yml logs --tail 50 caddy backend
```

| What you see | Usually |
|---|---|
| The address doesn't open | HTTP/HTTPS missing from the security group (step 3) |
| Caddy log: certificate errors | The IP in the address isn't this server's (use the Elastic IP; run the setup again after changing it in `.env`) |
| Build stops with "Killed" | Out of memory while building; run the setup again (swap helps) |
| "UNPROTECTED PRIVATE KEY" (Way B) | `chmod 600 ~/leenax.pem`, and keep the key in your Linux home, not on a Windows drive |
