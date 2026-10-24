@echo off
setlocal

rem Auto-set JAVA_HOME to installed JDK 21 on D drive
if not defined JAVA_HOME (
    set "JAVA_HOME=D:\jdk\jdk-21.0.4+7"
)

rem Set GRADLE_USER_HOME strictly to D drive
if not defined GRADLE_USER_HOME (
    set "GRADLE_USER_HOME=D:\Gradle\.gradle"
)

rem Ensure JDK bin is in PATH
set "PATH=%JAVA_HOME%\bin;%PATH%"

rem Execute installed Gradle 8.7 binary on D drive
if exist "D:\Gradle\.gradle\wrapper\dists\gradle-8.7-bin\gradle-8.7\bin\gradle.bat" (
    "D:\Gradle\.gradle\wrapper\dists\gradle-8.7-bin\gradle-8.7\bin\gradle.bat" -g "%GRADLE_USER_HOME%" %*
) else (
    set CLASSPATH=%~dp0gradle\wrapper\gradle-wrapper.jar
    "java.exe" -Xmx64m -Xms64m "-Dorg.gradle.appname=gradlew" -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain -g "%GRADLE_USER_HOME%" %*
)

endlocal
