@echo off
REM Needs JDK 21 + Maven. Output: target\MNLeaderboards-1.1.0.jar
mvn -B clean package
pause
