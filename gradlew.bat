@echo off
setlocal

rem Check for JAVA_HOME or fallback to C:\Android\jdk-17
if not defined JAVA_HOME (
    if exist "C:\Android\jdk-17" (
        set "JAVA_HOME=C:\Android\jdk-17"
    )
)

if defined JAVA_HOME (
    set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
) else (
    set "JAVA_EXE=java.exe"
)

set "WRAPPER_JAR=%~dp0gradle\wrapper\gradle-wrapper.jar"

"%JAVA_EXE%" -Xmx64m -Xms64m "-Dorg.gradle.appname=gradlew" -cp "%WRAPPER_JAR%" org.gradle.wrapper.GradleWrapperMain %*

endlocal
