<#
PC 의 C:\kra-data 를 클라우드 서버로 옮기고, PC 쪽 가치마(웹·자동 수집)를 멈춘다. 한 번만 씀.
경주가 없는 월~목에 하세요. 몇 분 동안 사이트가 멈춥니다.

  powershell -ExecutionPolicy Bypass -File automation\push-to-server.ps1
  (서버 별칭이 gachima 가 아니면 -Server 별칭)

순서: PC 자동 수집 끄기 → PC 웹 끄기 → C:\kra-data 묶기(로그 빼고) → 서버로 보내기 → 서버에서 풀고 웹·수집 켜기
되돌리기: CLOUD.md 의 '되돌리기'
#>
param([string]$Server = 'gachima', [string]$DataDir = 'C:\kra-data')
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.Encoding]::UTF8
. (Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) 'common.ps1')
$ssh = Find-Exe 'ssh'; $scp = Find-Exe 'scp'; $tar = Find-Exe 'tar'
$sa = Ssh-Args $DataDir

Write-Host "== 서버 연결 확인 ($Server)"
& $ssh @sa -o BatchMode=yes $Server 'test -x ~/kra-horse-racing/automation/linux/import-data.sh && echo ok'
if ($LASTEXITCODE -ne 0) { throw "서버에 연결되지 않거나 install.sh 를 아직 안 돌렸습니다 (CLOUD.md 2~4단계)" }

Write-Host '== PC 자동 수집·웹 작업 끄기 (작업 스케줄러 > Gachima, 지우지 않고 사용 안 함으로)'
Get-ScheduledTask -TaskPath '\Gachima\' -ErrorAction SilentlyContinue | ForEach-Object {
    Stop-ScheduledTask -InputObject $_ -ErrorAction SilentlyContinue
    Disable-ScheduledTask -InputObject $_ | Out-Null
    Write-Host "   끔: $($_.TaskName)"
}
Write-Host '== PC 웹(8090) 끄기'
Get-NetTCPConnection -LocalPort 8090 -State Listen -ErrorAction SilentlyContinue | ForEach-Object {
    Stop-Process -Id $_.OwningProcess -Force -ErrorAction SilentlyContinue
    Write-Host "   끔: 프로세스 $($_.OwningProcess)"
}
Start-Sleep -Seconds 3

$parent = Split-Path -Parent $DataDir; $leaf = Split-Path -Leaf $DataDir
# 사용자 폴더(TEMP)가 한글이면 scp 가 경로를 못 읽을 수 있어 데이터 폴더 안 sshkey 에 둔다 (묶을 때 sshkey 는 뺌: 접속 키가 서버로 가지 않게)
New-Item -ItemType Directory -Force -Path (Join-Path $DataDir 'sshkey') | Out-Null
$tgz = Join-Path $DataDir 'sshkey\kra-data.tgz'
Write-Host "== $DataDir 묶기 (로그 빼고, 몇 분 걸림)"
if (Test-Path $tgz) { Remove-Item $tgz }
& $tar -czf $tgz -C $parent --exclude="$leaf/logs" --exclude="$leaf/sshkey" --exclude="$leaf/backup" --exclude="$leaf/db/*.lock.db" --exclude="$leaf/db/*.trace.db" $leaf
if ($LASTEXITCODE -ne 0) { throw '묶기 실패' }
Write-Host ("   {0:N0} MB" -f ((Get-Item $tgz).Length / 1MB))

Write-Host '== 서버로 보내기'
& $scp @sa $tgz "${Server}:kra-data.tgz"
if ($LASTEXITCODE -ne 0) { throw '보내기 실패' }

Write-Host '== 서버에서 풀고 켜기'
& $ssh @sa $Server 'bash ~/kra-horse-racing/automation/linux/import-data.sh ~/kra-data.tgz'
if ($LASTEXITCODE -ne 0) { throw '서버에서 풀기 실패 (위 메시지 확인)' }
Remove-Item $tgz

Write-Host ''
Write-Host '끝. 이제 CLOUD.md 6단계: Cloudflare 터널을 서버로 옮기기'
Write-Host 'PC 의 C:\kra-data 는 그대로 남아 있습니다 (되돌릴 때 씀).'
