@echo off
setlocal
cd /d "%~dp0"
if not exist target\classes mkdir target\classes
if not exist dist mkdir dist
type nul > target\sources.txt
for /r src\main\java %%F in (*.java) do @echo "%%F" >> target\sources.txt
set "COMPILER_RELEASE=--release 17"
if "%JAVA_RELEASE_FALLBACK%"=="1" set "COMPILER_RELEASE=-source 17 -target 17"
java com.sun.tools.javac.Main %COMPILER_RELEASE% -encoding UTF-8 -d target\classes @target\sources.txt
if errorlevel 1 exit /b 1
xcopy src\main\resources target\classes /E /I /Y > nul
java --module jdk.jartool/sun.tools.jar.Main --create --file dist\lostfound.jar --main-class edu.usc.lostfound.App -C target\classes .
if errorlevel 1 exit /b 1
echo Built dist\lostfound.jar. Start with run.cmd.
