@echo off
rem RPG Bridge launcher for Windows: starts an unmodified Purpur server through Fabric Loader, so the
rem Fabric mods in .\mods (Fabric API, RPG Series, Better Combat, ...) run on Purpur next to the plugins.
rem
rem Put this file, rpgbridge-loader.jar and rpgbridge-log4j2.xml in the server folder, next to
rem purpur.jar. Put rpgbridge-core.jar and the same mod jars the players use into .\mods.
rem Usage: run.bat [server arguments, e.g. nogui]
rem Env: JAVA (default: java, must be Java 25), MEMORY (default: 4G), JVM_ARGS, PURPUR_JAR (default: purpur.jar)
rem Needs curl.exe (included in Windows 10 1803+ and Windows 11) for the first start.
setlocal EnableDelayedExpansion
cd /d "%~dp0"

rem Java 25+: JAVA if set, otherwise the first JDK/JRE 25+ found in the usual install folders, otherwise PATH.
if not defined JAVA call :findjava
if not defined JAVA set "JAVA=java"
call :javamajor
if !JMAJOR! LSS 25 (
  echo.
  echo Serve Java 25 o superiore, ma "%JAVA%" e' la versione !JVER!.
  echo Installa Java 25, per esempio Temurin JDK 25: https://adoptium.net/temurin/releases/?version=25
  echo Poi riavvia run.bat; se non lo trova, imposta il percorso prima dell'avvio, per esempio:
  echo   set "JAVA=C:\Program Files\Eclipse Adoptium\jdk-25.0.1.8-hotspot\bin\java.exe"
  goto :fail
)
echo Java !JVER!: %JAVA%
if not defined MEMORY set "MEMORY=4G"
if not defined PURPUR_JAR set "PURPUR_JAR=purpur.jar"
set "LOADER_VERSION=0.19.5"
set "FABRIC_MAVEN=https://maven.fabricmc.net"
set "LIB=.rpgbridge\libraries"

if not exist "%PURPUR_JAR%" ( echo Missing %PURPUR_JAR% & goto :fail )
if not exist rpgbridge-loader.jar ( echo Missing rpgbridge-loader.jar & goto :fail )
if not exist mods\rpgbridge-core*.jar ( echo Missing mods\rpgbridge-core.jar & goto :fail )

rem 1. Let paperclip apply Purpur's patches (creates versions\ and libraries\), without starting.
call :findgame
if not defined GAME_JAR (
  "%JAVA%" -Dpaperclip.patchonly=true -jar "%PURPUR_JAR%" || goto :fail
  call :findgame
)
if not defined GAME_JAR ( echo Paperclip did not create versions\...\purpur-*.jar & goto :fail )

rem 2. Fabric Loader and its libraries (Apache-2.0 / MIT / BSD), downloaded once.
rem    Same list as the "libraries" of fabric-loader-%LOADER_VERSION%.json (Fabric Maven).
if not exist "%LIB%" mkdir "%LIB%"
call :fetch net/fabricmc/fabric-loader/%LOADER_VERSION%/fabric-loader-%LOADER_VERSION%.jar || goto :fail
call :fetch org/ow2/asm/asm/9.10.1/asm-9.10.1.jar || goto :fail
call :fetch org/ow2/asm/asm-analysis/9.10.1/asm-analysis-9.10.1.jar || goto :fail
call :fetch org/ow2/asm/asm-commons/9.10.1/asm-commons-9.10.1.jar || goto :fail
call :fetch org/ow2/asm/asm-tree/9.10.1/asm-tree-9.10.1.jar || goto :fail
call :fetch org/ow2/asm/asm-util/9.10.1/asm-util-9.10.1.jar || goto :fail
call :fetch net/fabricmc/sponge-mixin/0.17.4+mixin.0.8.7/sponge-mixin-0.17.4+mixin.0.8.7.jar || goto :fail

rem 3. Class path in a Java argument file: Fabric Loader, then only the libraries Purpur lists (plugin libraries
rem    downloaded into libraries\ by Paper must stay out), without Paper's own ASM (Fabric's replaces it).
set "ARGS=.rpgbridge\launch.txt"
"%JAVA%" -cp rpgbridge-loader.jar dev.rpgbridge.loader.LaunchArgs "%PURPUR_JAR%" "%LIB%" "%ARGS%" || goto :fail

"%JAVA%" -Xmx%MEMORY% %JVM_ARGS% -Dlog4j.configurationFile=rpgbridge-log4j2.xml -Dfabric.skipMcProvider=true @"%ARGS%" net.fabricmc.loader.impl.launch.knot.KnotServer %*
set "RC=%ERRORLEVEL%"
pause
exit /b %RC%

:findjava
for %%d in ("%ProgramFiles%\Eclipse Adoptium" "%ProgramFiles%\Java" "%ProgramFiles%\Microsoft" "%ProgramFiles%\Zulu" "%ProgramFiles%\Amazon Corretto" "%ProgramFiles%\BellSoft" "%LOCALAPPDATA%\Programs\Eclipse Adoptium") do (
  if exist "%%~d" for /d %%j in ("%%~d\*") do if exist "%%~j\bin\java.exe" (
    set "JAVA=%%~j\bin\java.exe"
    call :javamajor
    if !JMAJOR! GEQ 25 exit /b 0
  )
)
set "JAVA="
exit /b 0

:javamajor
rem Reads e.g. 'openjdk version "25.0.1" 2025-10-21' into JVER=25.0.1, JMAJOR=25 (Java 8 gives 1).
set "JVER=?" & set "JMAJOR=0"
for /f "tokens=3" %%v in ('call "%JAVA%" -version 2^>^&1 ^| findstr /i version') do if "!JVER!"=="?" set "JVER=%%~v"
for /f "delims=.-+" %%m in ("!JVER!") do set /a "JMAJOR=%%m" 2>nul
exit /b 0

:findgame
set "GAME_JAR="
if exist versions for /r versions %%f in (purpur-*.jar) do if not defined GAME_JAR set "GAME_JAR=%%f"
exit /b 0

:fetch
set "F=%LIB%\%~nx1"
if exist "%F%" for %%s in ("%F%") do if %%~zs GTR 0 exit /b 0
echo Downloading %~nx1
curl.exe -fsSL -o "%F%" "%FABRIC_MAVEN%/%~1"
if errorlevel 1 ( del /q "%F%" 2>nul & echo Download failed: %FABRIC_MAVEN%/%~1 & exit /b 1 )
exit /b 0

:fail
pause
exit /b 1
