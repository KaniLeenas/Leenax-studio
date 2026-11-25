# Restores a backup made by the backup service or scripts\backup.ps1   (Windows PowerShell)
#   powershell -ExecutionPolicy Bypass -File .\scripts\restore.ps1 .\backups\20261119-214500
# Replaces the current database AND all media.
param([Parameter(Mandatory = $true)][string]$Folder)
$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')
$abs = (Resolve-Path $Folder).Path
if (!(Test-Path (Join-Path $abs 'leenax.dump')) -or !(Test-Path (Join-Path $abs 'storage.tar.gz'))) { throw "$Folder has no leenax.dump / storage.tar.gz" }
$ok = Read-Host "This replaces ALL current data with $Folder. Type RESTORE to continue"
if ($ok -ne 'RESTORE') { Write-Host 'Cancelled.'; exit 1 }

Write-Host 'Stopping the app...'
docker compose stop backend renderer imagetools web backup storage

Write-Host 'Restoring the database...'
docker compose up -d postgres
Start-Sleep -Seconds 5
docker compose exec -T postgres dropdb -U leenax --if-exists leenax
docker compose exec -T postgres createdb -U leenax leenax
docker compose cp (Join-Path $abs 'leenax.dump') postgres:/tmp/leenax.dump
docker compose exec -T postgres pg_restore -U leenax -d leenax --no-owner /tmp/leenax.dump
docker compose exec -T postgres rm /tmp/leenax.dump

Write-Host 'Restoring media...'
docker run --rm -v leenax_storagedata:/data -v "${abs}:/backup:ro" alpine sh -c "rm -rf /data/* && tar xzf /backup/storage.tar.gz -C /data"

Write-Host 'Starting everything...'
docker compose up -d
Write-Host 'Done. Use the same LEENAX_MASTER_KEY as when the backup was made.'
