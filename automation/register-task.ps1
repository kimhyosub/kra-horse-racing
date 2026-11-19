<#
윈도우 작업 스케줄러에 가치마 자동 수집을 등록한다. 관리자 권한 필요 없음.

  powershell -ExecutionPolicy Bypass -File register-task.ps1                 # 매일 21:40
  powershell -ExecutionPolicy Bypass -File register-task.ps1 -Time 22:10
  powershell -ExecutionPolicy Bypass -File register-task.ps1 -Morning 08:40  # 금·토·일 아침에 출전표 한 번 더 (기수 변경·취소 반영)
  powershell -ExecutionPolicy Bypass -File register-task.ps1 -Web            # 로그인하면 가치마 웹 자동 실행
  powershell -ExecutionPolicy Bypass -File register-task.ps1 -Results        # 매일 11:00~19:00 30분마다 오늘 결과 반영
  powershell -ExecutionPolicy Bypass -File register-task.ps1 -Results -ResultsFrom 10:30 -ResultsHours 9 -ResultsEvery 20
  powershell -ExecutionPolicy Bypass -File register-task.ps1 -Remove

로그인한 동안에만 실행된다(비밀번호 저장 안 함). 그 시각에 PC가 꺼져 있었으면 켜진 뒤 바로 한 번 실행한다.
#>
param(
    [string]$Time = '21:40',
    [string]$Morning = '',
    [switch]$Web,
    [switch]$Results,
    [string]$ResultsFrom = '11:00',
    [int]$ResultsHours = 8,
    [int]$ResultsEvery = 30,
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
if ($Results) {
    # 매일 같은 시각에 시작해서 ResultsEvery 분마다, ResultsHours 시간 동안 반복.
    # 경주가 없는 날은 받을 것이 없어 금방 끝난다. 밤 21:40 Daily 와 겹치면 나중 것은 건너뛴다(중복 실행 방지).
    $trig = New-ScheduledTaskTrigger -Daily -At $ResultsFrom
    $rep = New-ScheduledTaskTrigger -Once -At $ResultsFrom -RepetitionInterval (New-TimeSpan -Minutes $ResultsEvery) `
        -RepetitionDuration (New-TimeSpan -Hours $ResultsHours)
    $trig.Repetition = $rep.Repetition
    $resSettings = New-ScheduledTaskSettingsSet -StartWhenAvailable -MultipleInstances IgnoreNew `
        -ExecutionTimeLimit (New-TimeSpan -Minutes 25) -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
    Register-ScheduledTask -TaskPath $Path -TaskName 'Results' -Force -Settings $resSettings `
        -Action (Action '-BackDays 0 -AheadDays 0 -SkipOdds -Quiet') -Trigger $trig `
        -Description '가치마: 경주일 낮 동안 오늘 경주성적을 주기적으로 받아 착순·모의 장부 정산 반영' | Out-Null
    Write-Host ("등록: 매일 {0}부터 {1}시간 동안 {2}분마다 오늘 결과 반영  (작업 스케줄러 > Gachima > Results)" -f $ResultsFrom, $ResultsHours, $ResultsEvery)
}
Write-Host '지금 한 번 돌려 보려면: Start-ScheduledTask -TaskPath \Gachima\ -TaskName Daily'
