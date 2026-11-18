<#
윈도우 작업 스케줄러에 가치마 자동 수집을 등록한다. 관리자 권한 필요 없음.

  powershell -ExecutionPolicy Bypass -File register-task.ps1                 # 매일 21:40
  powershell -ExecutionPolicy Bypass -File register-task.ps1 -Time 22:10
  powershell -ExecutionPolicy Bypass -File register-task.ps1 -Morning 08:40  # 금·토·일 아침에 출전표 한 번 더 (기수 변경·취소 반영)
  powershell -ExecutionPolicy Bypass -File register-task.ps1 -Web            # 로그인하면 가치마 웹 자동 실행
  powershell -ExecutionPolicy Bypass -File register-task.ps1 -Remove

로그인한 동안에만 실행된다(비밀번호 저장 안 함). 그 시각에 PC가 꺼져 있었으면 켜진 뒤 바로 한 번 실행한다.
#>
param(
    [string]$Time = '21:40',
    [string]$Morning = '',
    [switch]$Web,
    [switch]$Remove
)
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$Daily = Join-Path $ScriptDir 'kra-daily.ps1'
$Path = '\Gachima\'

if ($Remove) {
    Get-ScheduledTask -TaskPath $Path -ErrorAction SilentlyContinue | Unregister-ScheduledTask -Confirm:$false
    Write-Host '가치마 작업을 지웠습니다.'
    exit 0
}

if (-not [Environment]::GetEnvironmentVariable('KRA_SERVICE_KEY', 'User')) {
    Write-Warning '사용자 환경변수 KRA_SERVICE_KEY 가 없습니다. 먼저: setx KRA_SERVICE_KEY "발급키"'
}

$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -MultipleInstances IgnoreNew `
    -ExecutionTimeLimit (New-TimeSpan -Hours 2) -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries

function Action([string]$extra) {
    New-ScheduledTaskAction -Execute 'powershell.exe' -WorkingDirectory $ScriptDir `
        -Argument ("-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$Daily`" " + $extra).Trim()
}

Register-ScheduledTask -TaskPath $Path -TaskName 'Daily' -Force -Settings $settings `
    -Action (Action '') -Trigger (New-ScheduledTaskTrigger -Daily -At $Time) `
    -Description '가치마: 어제~내일 출전표·경주성적·배당 수집 → export_web.py' | Out-Null
Write-Host "등록: 매일 $Time  (작업 스케줄러 > Gachima > Daily)"

if ($Morning) {
    Register-ScheduledTask -TaskPath $Path -TaskName 'RaceMorning' -Force -Settings $settings `
        -Action (Action '-BackDays 0 -AheadDays 0 -SkipOdds') `
        -Trigger (New-ScheduledTaskTrigger -Weekly -DaysOfWeek Friday, Saturday, Sunday -At $Morning) `
        -Description '가치마: 경주일 아침 출전표 다시 받기 (기수 변경·출전 취소)' | Out-Null
    Write-Host "등록: 금·토·일 $Morning  (작업 스케줄러 > Gachima > RaceMorning)"
}
if ($Web) {
    $webSettings = New-ScheduledTaskSettingsSet -StartWhenAvailable -MultipleInstances IgnoreNew `
        -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1) `
        -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
    $webAction = New-ScheduledTaskAction -Execute 'powershell.exe' -WorkingDirectory $ScriptDir `
        -Argument ("-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"" + (Join-Path $ScriptDir 'start-web.ps1') + "`"")
    Register-ScheduledTask -TaskPath $Path -TaskName 'Web' -Force -Settings $webSettings `
        -Action $webAction -Trigger (New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME) `
        -Description '가치마 웹(kra-web, 8090) 로그인할 때 실행' | Out-Null
    Write-Host "등록: 로그인할 때 가치마 웹 실행  (작업 스케줄러 > Gachima > Web)"
    Write-Host '지금 바로 띄우려면: Start-ScheduledTask -TaskPath \Gachima\ -TaskName Web'
}
Write-Host '지금 한 번 돌려 보려면: Start-ScheduledTask -TaskPath \Gachima\ -TaskName Daily'
