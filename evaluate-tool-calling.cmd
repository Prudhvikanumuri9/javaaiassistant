@echo off
setlocal
cd /d "%~dp0"
if not exist "target\PersonalAssistant\personal-assistant.jar" (
  echo Portable package not found. Run: mvn clean package
  exit /b 1
)
mvn -Dassistant.toolEvaluation=true -Dpersonalassistant.home=target\PersonalAssistant -Dtest=ToolCallingEvaluationTest test
exit /b %ERRORLEVEL%
