<#
서버의 최근 로그(3일)와 웹 파일(day-*.json 등)을 PC 로 가져온다. 점검·백업용.
  powershell -ExecutionPolicy Bypass -File automation\pull-from-server.ps1
가져오는 곳: C:\kra-data\logs, C:\kra-data\web  (서버로 옮긴 뒤 PC 쪽 파일은 쓰이지 않으므로 덮어씀)
-Db 를 붙이면 서버 DB 백업도 C:\kra-data\backup 에 받는다.
#>
param([string]$Server = 'gachima', [string]$DataDir = 'C:\kra-data', [switch]$Db)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.Encoding]::UTF8
. (Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) 'common.ps1')
$ssh = Find-Exe 'ssh'; $scp = Find-Exe 'scp'; $tar = Find-Exe 'tar'
$sa = Ssh-Args $DataDir
New-Item -ItemType Directory -Force -Path "$DataDir\logs", "$DataDir\web" | Out-Null

Write-Host '== 로그 (최근 3일)'
& $ssh @sa $Server 'cd /srv/kra-data/logs && find . -name \*.log\* -mtime -3 -print0 | tar -czf /tmp/gachima-logs.tgz --null -T -'
& $scp @sa "${Server}:/tmp/gachima-logs.tgz" "$DataDir\gachima-logs.tgz"
& $tar -xzf "$DataDir\gachima-logs.tgz" -C "$DataDir\logs"
Remove-Item "$DataDir\gachima-logs.tgz"

Write-Host '== 웹 파일'
& $scp @sa "${Server}:/srv/kra-data/web/*.json" "$DataDir\web\"

if ($Db) {
    Write-Host '== DB 백업 (웹이 켜진 채로 H2 SCRIPT 로 받음)'
    New-Item -ItemType Directory -Force -Path "$DataDir\backup" | Out-Null
    & $ssh @sa $Server 'bash ~/kra-horse-racing/automation/linux/backup-db.sh /tmp/gachima-db.zip'
    $name = 'gachima-db-{0}.zip' -f (Get-Date).ToString('yyyyMMdd-HHmm')
    & $scp @sa "${Server}:/tmp/gachima-db.zip" "$DataDir\backup\$name"
    Write-Host "   $DataDir\backup\$name"
}
Write-Host '끝.'
