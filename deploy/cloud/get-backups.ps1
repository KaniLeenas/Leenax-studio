<#
  Copies the server's daily backups to this PC, into "server-backups\<date>" in the Leenax folder.
  Run it now and then (weekly is a good habit):

    powershell -ExecutionPolicy Bypass -File deploy\cloud\get-backups.ps1 -Server 140.238.1.23 -Key C:\Users\you\Downloads\ssh-key.key

  It also copies the server's .env (as server.env) the first time. Keep it private: it holds the
  database passwords and LEENAX_MASTER_KEY, which you need to restore a backup with your saved
  AI keys. Don't put it in a shared or synced folder.
#>
param(
  [Parameter(Mandatory = $true)][string]$Server,
  [Parameter(Mandatory = $true)][string]$Key,
  [string]$User = "ubuntu"
)
$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$dest = Join-Path $root ("server-backups\" + (Get-Date -Format "yyyy-MM-dd"))
New-Item -ItemType Directory -Force -Path $dest | Out-Null
icacls $Key /inheritance:r /grant:r "$($env:USERNAME):(R)" | Out-Null
$ssh = @("-i", $Key, "-o", "StrictHostKeyChecking=accept-new")
$target = "$User@$Server"

Write-Host "Copying backups from $Server..." -ForegroundColor Cyan
# the backup service writes as root; make a readable copy of the newest few first
ssh @ssh $target "sudo rm -rf /tmp/leenax-backups && sudo mkdir -p /tmp/leenax-backups && cd ~/leenax/backups && ls -1d */ 2>/dev/null | tail -n 3 | xargs -r -I{} sudo cp -r {} /tmp/leenax-backups/ && sudo chown -R $User /tmp/leenax-backups"
if ($LASTEXITCODE -ne 0) { throw "Couldn't read the backups on the server." }
scp @ssh -r "${target}:/tmp/leenax-backups/*" $dest
if ($LASTEXITCODE -ne 0) { throw "Copying failed." }
ssh @ssh $target "rm -rf /tmp/leenax-backups"

$envCopy = Join-Path $root "server-backups\server.env"
if (-not (Test-Path $envCopy)) {
  scp @ssh "${target}:~/leenax/.env" $envCopy
  icacls $envCopy /inheritance:r /grant:r "$($env:USERNAME):(R,W)" | Out-Null
  Write-Host "Saved the server's settings as $envCopy (private: passwords and master key)." -ForegroundColor Yellow
}
Write-Host "Backups saved in $dest" -ForegroundColor Green
