<#
가치마 웹(kra-web) 실행. 작업 스케줄러가 로그인할 때 숨김 창으로 띄운다 (register-task.ps1 -Web).
손으로: powershell -ExecutionPolicy Bypass -File start-web.ps1

필요한 사용자 환경변수 (setx 로 한 번):
  GACHIMA_LOGIN_PASSWORD  로그인 비밀번호 (아이디는 GACHIMA_LOGIN_USER, 기본 admin)
  ANTHROPIC_API_KEY       스크린샷 읽기 (없으면 그 기능만 꺼짐)
#>
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$Repo = Split-Path -Parent $ScriptDir
$DataDir = 'C:\kra-data'
$Java = 'java'
$WebJar = Join-Path $Repo 'kra-web\target\kra-web-0.1.0.jar'
$LocalConfig = Join-Path $ScriptDir 'kra-auto.config.ps1'
if (Test-Path $LocalConfig) { . $LocalConfig }
. (Join-Path $ScriptDir 'common.ps1')

foreach ($n in 'GACHIMA_LOGIN_USER', 'GACHIMA_LOGIN_PASSWORD', 'ANTHROPIC_API_KEY', 'GACHIMA_PUBLIC_READ',
            'TELEGRAM_BOT_TOKEN', 'GACHIMA_TELEGRAM_CHATS', 'GACHIMA_PUBLIC_URL',
            'GACHIMA_ADSENSE_CLIENT', 'GACHIMA_GSC_VERIFY', 'GACHIMA_NAVER_VERIFY', 'GACHIMA_DAUM_VERIFY', 'GACHIMA_CONTACT') {
    if (-not [Environment]::GetEnvironmentVariable($n, 'Process')) {
        $v = [Environment]::GetEnvironmentVariable($n, 'User')
        if ($v) { [Environment]::SetEnvironmentVariable($n, $v, 'Process') }
    }
}
if (-not $env:GACHIMA_LOGIN_PASSWORD) { Write-Warning 'GACHIMA_LOGIN_PASSWORD 가 없어 실행할 때마다 임시 비밀번호가 콘솔/로그에 찍힙니다.' }
if (-not (Test-Path $WebJar)) { throw "웹 jar 가 없습니다: $WebJar  (kra-web 폴더에서 mvn clean package)" }

$Java = Find-Java $Java
$LogDir = Join-Path $DataDir 'logs'
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
$Log = Join-Path $LogDir ('web-{0}.log' -f (Get-Date).ToString('yyyyMMdd-HHmm'))
Get-ChildItem $LogDir -Filter 'web-*.log' | Where-Object { $_.LastWriteTime -lt (Get-Date).AddDays(-14) } | Remove-Item -ErrorAction SilentlyContinue

Write-Host "가치마 웹 실행: http://localhost:8090  (로그: $Log)"
# 로그 파일로 보내고 계속 떠 있는다
& $Java '-Dfile.encoding=UTF-8' '-jar' $WebJar "--logging.file.name=$Log" '--logging.charset.file=UTF-8'
