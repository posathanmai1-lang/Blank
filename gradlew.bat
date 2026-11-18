@echo off
setlocal

if not defined JAVA_HOME set "JAVA_HOME=D:\jdk\jdk-21.0.4+7"
if not defined GRADLE_USER_HOME set "GRADLE_USER_HOME=D:\Caches\gradle"

set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if not exist "%JAVA_EXE%" (
    echo Java not found under JAVA_HOME: %JAVA_HOME%
    endlocal & exit /b 1
)

set "CLASSPATH=%~dp0gradle\wrapper\gradle-wrapper.jar"
"%JAVA_EXE%" -Xmx64m -Xms64m "-Dorg.gradle.appname=gradlew" -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain -g "%GRADLE_USER_HOME%" %*
set "EXIT_CODE=%ERRORLEVEL%"
endlocal & exit /b %EXIT_CODE%
