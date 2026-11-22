@echo off
setlocal
cd /d "%~dp0.."
call build.cmd
if errorlevel 1 exit /b 1
if not exist target\test-classes mkdir target\test-classes
type nul > target\test-sources.txt
for /r src\test\java %%F in (*.java) do @echo "%%F" >> target\test-sources.txt
set "COMPILER_RELEASE=--release 17"
if "%JAVA_RELEASE_FALLBACK%"=="1" set "COMPILER_RELEASE=-source 17 -target 17"
java com.sun.tools.javac.Main %COMPILER_RELEASE% -encoding UTF-8 -cp "target\classes;lib\*" -d target\test-classes @target\test-sources.txt
if errorlevel 1 exit /b 1
for %%T in (MatchingEngineTest PhotoServiceTest CoreTest) do (
  java -Djava.awt.headless=true -cp "target\classes;target\test-classes;lib\*" edu.usc.lostfound.%%T
  if errorlevel 1 exit /b 1
)
