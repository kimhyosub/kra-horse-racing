@echo off
rem Gachima: run collector + export once by hand (double click). Args are passed through, e.g. kra-daily.bat -From 2026-11-20 -To 2026-11-22
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0kra-daily.ps1" %*
pause
