@echo off
setlocal
cd /d "%~dp0.."
java scripts\DownloadDependencies.java
