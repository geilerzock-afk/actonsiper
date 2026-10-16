@echo off
chcp 65001 >nul
call gradlew.bat build
if errorlevel 1 ( echo FEHLER beim Bauen. Ist Java 21 installiert? & pause & exit /b 1 )
echo FERTIG: build\libs\ahsniper-1.0.0.jar
start "" "%cd%\build\libs"
pause
