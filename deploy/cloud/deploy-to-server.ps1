<#
  Copies Leenax Studio from this PC to your cloud server (Oracle or AWS) and installs or updates it there.
  Run it in PowerShell from the Leenax folder:

    powershell -ExecutionPolicy Bypass -File deploy\cloud\deploy-to-server.ps1 -Server 140.238.1.23 -Key C:\Users\you\Downloads\ssh-key.key

  Add -Domain studio.example.com the first time if you have your own domain (otherwise the address
  is made from the server's IP with sslip.io).

  What goes to the server: the project files only. Never sent: .env, backups, the desktop app, node_modules,
  build output, "Claude outputs". The server keeps its own .env (passwords, master key) between updates.
#>
param(
  [Parameter(Mandatory = $true)][string]$Server,
  [Parameter(Mandatory = $true)][string]$Key,
  [string]$Domain = "",
  [string]$User = "ubuntu"
)
$ErrorActionPreference = "Stop"

$root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
if (-not (Test-Path (Join-Path $root "docker-compose.yml"))) { throw "Run this from the Leenax folder (docker-compose.yml not found in $root)." }
if (-not (Test-Path $Key)) { throw "SSH key not found: $Key" }
foreach ($tool in "ssh", "scp", "tar") {
  if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) { throw "$tool isn't available. Windows 10/11 include it: Settings > System > Optional features > OpenSSH Client." }
}
if ($Domain -and $Domain -notmatch '^[A-Za-z0-9.-]+$') { throw "That domain doesn't look right: $Domain" }

# OpenSSH refuses a private key that other Windows users can read.
icacls $Key /inheritance:r /grant:r "$($env:USERNAME):(R)" | Out-Null

$archive = Join-Path $env:TEMP "leenax-upload.tgz"
if (Test-Path $archive) { Remove-Item $archive }
Write-Host "Packing the project..." -ForegroundColor Cyan
tar -czf $archive `
  --exclude=./.env --exclude=./backups --exclude="./Claude outputs" --exclude=./server-backups --exclude=./desktop `
  --exclude=node_modules --exclude=dist --exclude=target --exclude=build --exclude=.gradle `
  --exclude=__pycache__ --exclude=.pytest_cache --exclude=.git --exclude="*.tsbuildinfo" `
  -C $root .
if ($LASTEXITCODE -ne 0) { throw "Packing failed." }
"{0:N1} MB" -f ((Get-Item $archive).Length / 1MB) | Write-Host

$ssh = @("-i", $Key, "-o", "StrictHostKeyChecking=accept-new", "-o", "ServerAliveInterval=30")
$target = "$User@$Server"

Write-Host "Uploading to $Server..." -ForegroundColor Cyan
scp @ssh $archive "${target}:/tmp/leenax-upload.tgz"
if ($LASTEXITCODE -ne 0) { throw "Upload failed. Check the IP, the key file, and that port 22 is open." }

Write-Host "Installing on the server (the first time takes 20-40 minutes)..." -ForegroundColor Cyan
$remote = "set -e; mkdir -p ~/leenax; tar -xzf /tmp/leenax-upload.tgz -C ~/leenax; rm /tmp/leenax-upload.tgz; cd ~/leenax; sudo bash deploy/cloud/setup-server.sh $Domain"
ssh @ssh -t $target $remote
if ($LASTEXITCODE -ne 0) { throw "Setup on the server stopped with an error (see above)." }
Remove-Item $archive
Write-Host "Done." -ForegroundColor Green
