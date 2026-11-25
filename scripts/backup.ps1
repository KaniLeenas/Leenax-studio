# Backs up the database and all media into .\backups\<timestamp>\  (Windows PowerShell)
# Run from the project folder:   powershell -ExecutionPolicy Bypass -File .\scripts\backup.ps1
$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')

$stamp = (Get-Date).ToUniversalTime().ToString('yyyyMMdd-HHmmss')
$dest = Join-Path 'backups' $stamp
New-Item -ItemType Directory -Force -Path $dest | Out-Null
$abs = (Resolve-Path $dest).Path

Write-Host 'Dumping PostgreSQL...'
docker compose exec -T postgres sh -c 'pg_dump -U leenax -d leenax --format=custom > /tmp/leenax.dump'
docker compose cp postgres:/tmp/leenax.dump (Join-Path $abs 'leenax.dump')
docker compose exec -T postgres rm /tmp/leenax.dump

Write-Host 'Archiving media storage...'
docker run --rm -v leenax_storagedata:/data:ro -v "${abs}:/backup" alpine tar czf /backup/storage.tar.gz -C /data .

Write-Host "Backup written to $dest"
