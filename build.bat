@echo off
if exist gradlew.bat (
    call gradlew.bat build %*
) else (
    gradle build %*
)

