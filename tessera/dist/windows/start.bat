@echo off
REM Avvio di Tessera su Windows. Richiede Java 25: https://adoptium.net/
REM La memoria del server si imposta in tessera-launcher.properties (memory.max).
cd /d "%~dp0"
where java >nul 2>nul
if errorlevel 1 (
  echo Java non trovato. Installa Java 25 da https://adoptium.net/
  pause
  exit /b 1
)
java -Xmx256M -jar tessera-launcher.jar %*
pause
