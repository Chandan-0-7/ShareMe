@echo off
setlocal
set JAVA_CMD=java.exe
if defined JAVA_HOME set JAVA_CMD=%JAVA_HOME%\bin\java.exe
"%JAVA_CMD%" -classpath "%~dp0gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
