@echo off
setlocal
cd /d "%~dp0"
if not exist dist\lostfound.jar call build.cmd
if errorlevel 1 exit /b 1
if not exist lib\h2-2.3.232.jar call scripts\download-dependencies.cmd
if errorlevel 1 exit /b 1
if not exist lib\imageio-webp-3.15.3.jar call scripts\download-dependencies.cmd
if errorlevel 1 exit /b 1
java -cp "dist\lostfound.jar;lib\*" edu.usc.lostfound.App %*
