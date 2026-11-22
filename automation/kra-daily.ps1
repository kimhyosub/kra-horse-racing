<#
가치마 자동 수집: 수집기(출전표·경주성적·심판·확정배당) → CSV → export_web.py → C:\kra-data\web

기본 범위: 어제 ~ 내일. 매일 밤 한 번 돌리면
  - 오늘 경주의 확정 착순·최종 배당
  - 내일 출전표(전날 예상)
가 웹 폴더에 들어가고, 웹(kra-web)이 1분 안에 불러온다.

  수동 실행:  powershell -ExecutionPolicy Bypass -File kra-daily.ps1
  기간 지정:  ... -File kra-daily.ps1 -From 2026-11-20 -To 2026-11-22   (20261120 형식도 됨)
  배당 생략:  ... -SkipOdds      (all-odds 는 400MB 라 CSV 만들기가 오래 걸림)

인증키는 사용자 환경변수 KRA_SERVICE_KEY 에서만 읽는다 (한 번: setx KRA_SERVICE_KEY "발급키").
종료 코드: 0 정상, 1 실패, 3 일부 실패(계속 진행함)
#>
param(
    [string]$From,
    [string]$To,
    [int]$BackDays = 1,
    [int]$AheadDays = 1,
    [string]$Meets = '1,2,3,4',
    [switch]$SkipOdds,
    # 확정배당(all-odds)만 받기: 출전표·경주성적·심판은 건너뜀 (지난 날짜 승식 배당 채우기용)
    [switch]$OddsOnly,
    # 이미 받은 날짜도 다시 받기 (장애로 비거나 일부만 받은 날 복구용). 새로 받은 게 더 적으면 기존 파일을 유지함
    [switch]$Refresh,
    [switch]$SkipCollect,
    [switch]$SkipExport,
    # 낮 동안 30분마다 도는 결과 반영용: 로그를 따로(results-*.log) 남기고 오래된 것은 3일만 보관
    [switch]$Quiet
)

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$Repo = Split-Path -Parent $ScriptDir

# ---- 설정: 다르면 같은 폴더의 kra-auto.config.ps1 에 같은 변수를 적어 덮어쓴다 ----
$DataDir = 'C:\kra-data'
$WebDir = 'C:\kra-data\web'
$Java = 'java'
$Python = 'python'
$Jar = Join-Path $Repo 'kra-collector\target\kra-collector-1.0.0.jar'
$RaceDay = Join-Path $Repo 'kra-raceday'
$KeepLogDays = 30
$LocalConfig = Join-Path $ScriptDir 'kra-auto.config.ps1'
if (Test-Path $LocalConfig) { . $LocalConfig }

. (Join-Path $ScriptDir 'common.ps1')

[Console]::OutputEncoding = [Text.Encoding]::UTF8
$env:PYTHONIOENCODING = 'utf-8'
$Started = Get-Date
$LogDir = Join-Path $DataDir 'logs'
New-Item -ItemType Directory -Force -Path $LogDir, $WebDir | Out-Null
$Log = Join-Path $LogDir ('{0}-{1}.log' -f $(if ($Quiet) { 'results' } else { 'daily' }), $Started.ToString('yyyyMMdd-HHmm'))
$Utf8 = New-Object Text.UTF8Encoding $false

function Log([string]$m) {
    $line = '{0} {1}' -f (Get-Date).ToString('HH:mm:ss'), $m
    Write-Host $line
    [IO.File]::AppendAllText($Log, $line + "`r`n", $Utf8)
}

function Parse-Day([string]$s) {
    foreach ($f in 'yyyy-MM-dd', 'yyyyMMdd') {
        $d = [datetime]::MinValue
        if ([datetime]::TryParseExact($s, $f, $null, 'None', [ref]$d)) { return $d }
    }
    throw "날짜 형식이 아닙니다: $s (예: 2026-11-20)"
}

function Write-Status([bool]$ok, [string]$msg) {
    $st = [ordered]@{
        startedAt  = $Started.ToString('yyyy-MM-dd HH:mm:ss')
        finishedAt = (Get-Date).ToString('yyyy-MM-dd HH:mm:ss')
        ok         = $ok
        from       = $script:FromDay.ToString('yyyy-MM-dd')
        to         = $script:ToDay.ToString('yyyy-MM-dd')
        message    = $(if ($Quiet) { '[결과 반영] ' + $msg } else { $msg })
        log        = $Log
    }
    [IO.File]::WriteAllText((Join-Path $WebDir 'collect-status.json'), ($st | ConvertTo-Json), $Utf8)
}

# 실행 중에 나오는 줄을 로그로. 네이티브 명령의 stderr 를 오류로 보지 않도록 Continue
function Run([string]$exe, [string[]]$argv) {
    $old = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try { & $exe @argv 2>&1 | ForEach-Object { Log ("  " + $_) } }
    finally { $ErrorActionPreference = $old }
    return $LASTEXITCODE
}

# 같은 작업이 겹쳐 돌지 않게
$mutex = New-Object Threading.Mutex($false, 'GachimaDaily')
if (-not $mutex.WaitOne(0)) { Write-Host '이미 실행 중입니다.'; exit 0 }

$exitCode = 0
try {
    $today = (Get-Date).Date
    $script:FromDay = if ($From) { Parse-Day $From } else { $today.AddDays(-$BackDays) }
    $script:ToDay = if ($To) { Parse-Day $To } else { $today.AddDays($AheadDays) }
    if ($script:FromDay -gt $script:ToDay) { throw "시작일이 종료일보다 늦습니다: $From ~ $To" }
    $f1 = $script:FromDay.ToString('yyyy-MM-dd'); $t1 = $script:ToDay.ToString('yyyy-MM-dd')
    Log "가치마 자동 수집 $f1 ~ $t1, 경마장 $Meets"

    if (-not $SkipCollect) {
        if (-not $env:KRA_SERVICE_KEY) {
            $env:KRA_SERVICE_KEY = [Environment]::GetEnvironmentVariable('KRA_SERVICE_KEY', 'User')
        }
        if (-not $env:KRA_SERVICE_KEY) { throw '환경변수 KRA_SERVICE_KEY 가 없습니다. 명령 프롬프트에서 한 번: setx KRA_SERVICE_KEY "발급키"' }
        if (-not (Test-Path $Jar)) { throw "수집기 jar 가 없습니다: $Jar  (kra-collector 폴더에서 mvn clean package)" }

        $Java = Find-Java $Java
        Log "  java: $Java"
        $apis = @('race-result', 'referee', 'entry-sheet')
        if (-not $SkipOdds) { $apis += 'all-odds' }
        if ($OddsOnly) { $apis = @('all-odds') }
        $argv = @(
            '-Dsun.stdout.encoding=UTF-8', '-Dsun.stderr.encoding=UTF-8', '-jar', $Jar,
            "--kra.from=$f1", "--kra.to=$t1", "--kra.meets=$Meets", "--kra.output-dir=$DataDir",
            ('--kra.apis.entry-sheet.enabled=' + (-not $OddsOnly).ToString().ToLower()), '--kra.apis.quinella-odds.enabled=false',
            ('--kra.apis.race-result.enabled=' + (-not $OddsOnly).ToString().ToLower()),
            ('--kra.apis.referee.enabled=' + (-not $OddsOnly).ToString().ToLower()),
            ('--kra.apis.all-odds.enabled=' + ($OddsOnly -or -not $SkipOdds).ToString().ToLower()),
            # 웹용 파일은 확정배당 원본(JSON)을 직접 읽으므로, 확정배당만 받을 때는 큰 CSV 를 다시 쓰지 않는다
            ('--kra.export-apis=' + $(if ($OddsOnly) { 'referee' } else { $apis -join ',' })),
            ('--kra.skip-existing=' + (-not $Refresh).ToString().ToLower()),
            '--logging.charset.console=UTF-8')
        Log "[1/2] 수집기: $($apis -join ', ')"
        $rc = Run $Java $argv
        if ($rc -eq 2) { throw '수집기가 호출 한도 또는 인증키 문제로 중단했습니다 (로그 확인).' }
        elseif ($rc -eq 3) { Log '  일부 날짜 수집 실패 → 받은 것으로 계속'; $exitCode = 3 }
        elseif ($rc -ne 0) { throw "수집기 오류 (종료 코드 $rc)" }
    }

    if (-not $SkipExport) {
        $argv = @('export_web.py',
            '--from', $script:FromDay.ToString('yyyyMMdd'), '--to', $script:ToDay.ToString('yyyyMMdd'),
            '--entries', (Join-Path $DataDir 'entry-sheet.csv'),
            '--history', (Join-Path $DataDir 'race-result.csv'),
            '--referee', (Join-Path $DataDir 'referee.csv'),
            '--raw', (Join-Path $DataDir 'raw'),
            '--out', $WebDir)
        Log '[2/2] 웹용 파일 만들기 (export_web.py)'
        $Python = Find-Python $Python
        Log "  python: $Python"
        Push-Location $RaceDay
        try { $rc = Run $Python $argv } finally { Pop-Location }
        if ($rc -ne 0) { throw "export_web.py 오류 (종료 코드 $rc)" }
    }

    $msg = if ($exitCode -eq 3) { '완료 (일부 날짜 수집 실패)' } else { '완료' }
    Log $msg
    Write-Status $true $msg
}
catch {
    Log ('실패: ' + $_.Exception.Message)
    if ($script:FromDay) { Write-Status $false $_.Exception.Message }
    $exitCode = 1
}
finally {
    Get-ChildItem $LogDir -Filter 'daily-*.log' | Where-Object { $_.LastWriteTime -lt (Get-Date).AddDays(-$KeepLogDays) } | Remove-Item -ErrorAction SilentlyContinue
    Get-ChildItem $LogDir -Filter 'results-*.log' | Where-Object { $_.LastWriteTime -lt (Get-Date).AddDays(-3) } | Remove-Item -ErrorAction SilentlyContinue
    $mutex.ReleaseMutex()
}
exit $exitCode
