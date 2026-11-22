@echo off
rem 가치마 서버에 새 코드 반영 (git push 한 다음). 옵션: --test --all
chcp 65001 >nul
ssh gachima "bash ~/kra-horse-racing/automation/linux/deploy.sh %*"
