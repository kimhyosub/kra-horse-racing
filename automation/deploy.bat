@echo off
rem 가치마 서버에 새 코드 반영 (git push 한 다음). 옵션: --test --all
chcp 65001 >nul
set F=
if exist C:\kra-data\sshkey\config set F=-F C:\kra-data\sshkey\config
set SSH=ssh
where ssh >nul 2>nul || set SSH=%WINDIR%\System32\OpenSSH\ssh.exe
"%SSH%" %F% gachima "bash ~/kra-horse-racing/automation/linux/deploy.sh %*"
