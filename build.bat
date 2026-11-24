@echo off
setlocal
cd /d "%~dp0"
set "JAVA_HOME=C:\Program Files\Microsoft\jdk-17.0.21.102-hotspot"
set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
set "ANDROID_SDK_ROOT=%ANDROID_HOME%"
set "PATH=%JAVA_HOME%\bin;%ANDROID_HOME%\platform-tools;%PATH%"
call "%~dp0gradlew.bat" assembleDebug --no-daemon %*
endlocal
