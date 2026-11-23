# kra-daily.ps1, start-web.ps1 이 같이 쓰는 함수

# PATH 에 없으면 흔히 설치되는 곳에서 찾는다 (IntelliJ 가 받은 JDK: %USERPROFILE%\.jdks)
function Find-Java([string]$want) {
    if (Get-Command $want -ErrorAction SilentlyContinue) { return $want }
    $c = @()
    if ($env:JAVA_HOME) { $c += Join-Path $env:JAVA_HOME 'bin\java.exe' }
    foreach ($root in @((Join-Path $env:USERPROFILE '.jdks'), 'C:\Program Files\Java', 'C:\Program Files\Eclipse Adoptium',
                        'C:\Program Files\Amazon Corretto', 'C:\Program Files\Zulu', 'C:\Program Files\Microsoft')) {
        if (Test-Path $root) {
            $c += Get-ChildItem $root -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending |
                  ForEach-Object { Join-Path $_.FullName 'bin\java.exe' }
        }
    }
    foreach ($root in @('C:\Program Files\JetBrains', (Join-Path $env:LOCALAPPDATA 'Programs'), (Join-Path $env:LOCALAPPDATA 'JetBrains\Toolbox\apps'))) {
        if (Test-Path $root) {
            $c += Get-ChildItem $root -Recurse -Filter java.exe -ErrorAction SilentlyContinue |
                  Where-Object { $_.FullName -like '*\jbr\bin\java.exe' } | ForEach-Object { $_.FullName }
        }
    }
    foreach ($x in $c) { if (Test-Path $x) { return $x } }
    throw "java 를 찾지 못했습니다. kra-auto.config.ps1 에 `$Java = 'C:\...\bin\java.exe' 를 적어 주세요."
}

function Find-Python([string]$want) {
    if (Get-Command $want -ErrorAction SilentlyContinue) {
        # 윈도우 스토어로 안내하는 가짜 python.exe 는 건너뛴다
        $p = (Get-Command $want).Source
        if ($p -notlike '*\WindowsApps\*') { return $want }
    }
    if (Get-Command 'py' -ErrorAction SilentlyContinue) { return 'py' }
    $c = @()
    foreach ($root in @((Join-Path $env:LOCALAPPDATA 'Programs\Python'), 'C:\Program Files', 'C:\')) {
        if (Test-Path $root) {
            $c += Get-ChildItem $root -Directory -Filter 'Python3*' -ErrorAction SilentlyContinue | Sort-Object Name -Descending |
                  ForEach-Object { Join-Path $_.FullName 'python.exe' }
        }
    }
    foreach ($root in @((Join-Path $env:USERPROFILE 'anaconda3'), (Join-Path $env:USERPROFILE 'miniconda3'))) { $c += Join-Path $root 'python.exe' }
    foreach ($x in $c) { if (Test-Path $x) { return $x } }
    throw "python 을 찾지 못했습니다. kra-auto.config.ps1 에 `$Python = 'C:\...\python.exe' 를 적어 주세요."
}


# ssh·scp·tar: PATH 에 없으면 윈도우 기본 위치에서 (32비트 PowerShell 이면 Sysnative)
function Find-Exe([string]$name) {
    $c = Get-Command $name -ErrorAction SilentlyContinue
    if ($c) { return $c.Source }
    foreach ($d in "$env:WINDIR\Sysnative\OpenSSH", "$env:WINDIR\System32\OpenSSH", "$env:WINDIR\Sysnative", "$env:WINDIR\System32") {
        $p = Join-Path $d "$name.exe"
        if (Test-Path $p) { return $p }
    }
    throw "$name 을(를) 찾지 못했습니다 (설정 → 앱 → 선택적 기능 → OpenSSH 클라이언트)"
}

# 서버 접속 설정. 사용자 폴더 이름이 한글이면 윈도우 ssh 가 %USERPROFILE%\.ssh\config 를 못 읽으므로
# C:\kra-data\sshkey\config 가 있으면 그것을 쓴다 (-F)
function Ssh-Args([string]$dataDir = 'C:\kra-data') {
    $cfg = Join-Path $dataDir 'sshkey\config'
    if (Test-Path $cfg) { return @('-F', $cfg) }
    return @()
}
