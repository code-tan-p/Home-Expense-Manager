@echo off
rem Starts Home Expense Manager and opens it in your browser. Close this window to stop it.
rem The first time, the program asks you to create a password (or skip it).
cd /d "%~dp0"
where java >nul 2>nul
if errorlevel 1 (
  echo Java 21 or newer is needed. Install it from https://adoptium.net and run this again.
  pause
  exit /b 1
)
if not exist target\home-expense-manager.jar (
  echo First start: building the program. This takes a minute...
  call mvnw.cmd -q -DskipTests package
  if errorlevel 1 (
    echo The build failed. See the messages above.
    pause
    exit /b 1
  )
)
echo Starting at http://127.0.0.1:8080/ ...
start "" http://127.0.0.1:8080/
java -jar target\home-expense-manager.jar
pause
