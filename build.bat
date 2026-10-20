@echo off
rem Builds Trevoga.exe with the C# compiler that ships with .NET Framework 4.x
cd /d "%~dp0"
set CSC=%WINDIR%\Microsoft.NET\Framework\v4.0.30319\csc.exe
if not exist "%CSC%" set CSC=%WINDIR%\Microsoft.NET\Framework64\v4.0.30319\csc.exe
if not exist "%CSC%" (
  echo .NET Framework 4 not found. Install .NET Framework 4.8 and run again.
  pause
  exit /b 1
)
rem Voice clips from the voice folder are embedded into the exe
set RES=
for %%f in (voice\*.wav) do call set RES=%%RES%% /resource:"voice\%%~nxf",voice.%%~nxf
"%CSC%" /nologo /target:winexe /optimize+ /codepage:65001 /r:System.Windows.Forms.dll /r:System.Drawing.dll %RES% /resource:config.json,config.json /out:Trevoga.exe Trevoga.cs
if errorlevel 1 (
  echo BUILD FAILED
) else (
  echo OK: Trevoga.exe created
)
pause
